# Worklog DB Reader — Expose Soredium Work State Through the Model Tree

**Issue:** Hortora/trellis#42
**Date:** 2026-08-11
**Status:** Approved

## Problem

Trellis tracks work state via filesystem scanning (`FileWatcherService` →
`WorkspaceScanner`). Soredium independently tracks structured work lifecycle
events in `worklog.db` via Python (`worklog.py`). The two systems have no
bridge — the coordinating agent sees the physical layout but not the work
history, and the frontend UI can't display work lifecycle data.

`BacklogResource` already queries worklog.db directly for enrichment/cache
tables, but uses inline SQL with no service abstraction. Adding lifecycle
queries alongside it would create a second unstructured reader.

## Solution

Three components, one data source, following the terminal pattern
(`TerminalRegistry` → `TerminalResource` → `TerminalModelProvider`):

```
worklog.db (SQLite, read-only)
    ↓
WorklogService (JDBC reader + .plan file reader)
    ↓ ↓ ↓
    │ │ └─ WorklogModelProvider → trellis_model(path="worklog/...")
    │ └─── WorklogResource     → /api/worklog/events, /work-items, /slots
    └───── BacklogResource     → /api/backlog (delegates, no longer queries directly)
```

## §1 Package Reorganization

The existing `backlog` package merges into a new `worklog` package.
`WorklogDataSourceProducer` serves the shared `@WorklogDS DataSource`.
`WorklogService` becomes the single query layer.

```
io.hortora.trellis.worklog/
├── WorklogService.java            — single query layer (JDBC + .plan)
├── WorklogDataSourceProducer.java — moved from backlog (unchanged logic)
├── WorklogResource.java           — REST endpoints at /api/worklog
├── WorklogEvent.java              — event record
├── WorkItem.java                  — work item record
├── WorkItemIssue.java             — issue association record
├── SlotInfo.java                  — slot record
├── BacklogEntry.java              — moved from backlog (avoids circular dep)
└── PlanState.java                 — .plan parser result

io.hortora.trellis.mcp/
└── WorklogModelProvider.java      — follows convention: all providers in mcp

io.hortora.trellis.backlog/
└── BacklogResource.java           — stays at /api/backlog, delegates to WorklogService
```

`WorklogModelProvider` lives in the `mcp` package following the convention
of all other providers (`TerminalModelProvider`, `WorkspaceModelProvider`,
`UIStateModelProvider`). Domain service in `worklog`, MCP layer in `mcp`.

`BacklogEntry` moves to the `worklog` package to avoid a circular
dependency (`worklog` → `backlog` for the record, `backlog` → `worklog`
for the service). `BacklogResource` imports from `worklog`.

## §2 WorklogService

`@ApplicationScoped` CDI bean. Injects `@WorklogDS DataSource`. All queries
return Java records — no raw JDBC leaking out.

### Availability Guard

All query methods check `isDbAvailable()` first. When `false`:
- Methods returning lists return `List.of()`
- `planPosition()` returns `null`
- No exceptions — silent graceful degradation matching `BacklogResource`'s
  existing behavior

### Query Methods (DB-backed)

| Method | Returns | Source tables |
|--------|---------|--------------|
| `recentEvents(since?, type?, limit?)` | `List<WorklogEvent>` | `events` |
| `activeWork()` | `List<WorkItem>` | `work_items` + `repos` + `work_item_issues` (state != 'ended') |
| `workItemTimeline(branch, repoPath)` | `List<WorklogEvent>` | `events` via `work_items` join |
| `slotStatus(familyRoot?)` | `List<SlotInfo>` | `slots` |
| `backlogEntries(repo?)` | `List<BacklogEntry>` | `github_issue_cache` + `issue_enrichment` + `trajectory_notes` |

`activeWork()` uses a two-pass strategy: first query fetches work items
joined with repos, second query batch-fetches issues for all returned
work item IDs (`WHERE work_item_id IN (?,?,...)`) and groups them by
work item ID in Java. Two queries total, not N+1.

### Filesystem-backed

| Method | Returns | Source |
|--------|---------|--------|
| `planPosition(workspaceRoot)` | `PlanState?` | Reads `.plan` file on demand — parses `← active` marker, counts checked/total items |

WorklogService does both JDBC and filesystem I/O. This is a pragmatic
choice — all work-state assembly in one service. If the filesystem concern
grows, `.plan` reading can move to `WorkspaceScanner` later.

### Freshness Detection

WorklogService checks the file modification time of `worklog.db` before
any DB query (via `Files.getLastModifiedTime(dbPath)`). If the mtime
changed since the last check, it calls `generationCounter.increment()`.
One `AtomicReference<FileTime>` field stores the last seen mtime.

This fires on every WorklogService query (not just `summary()`), so
both model tree queries and direct REST calls trigger freshness detection.
The file stat is near-zero cost and works correctly across independent
JDBC connections (unlike `PRAGMA data_version` which is per-connection).

### Summary Cache

`WorklogService` caches the summary result (counts, latest event, plan
position) with a short TTL (5 seconds). The cache is invalidated when
file mtime changes (detected by the freshness check above). This keeps
`summary()` off the hot path — after the first call, `trellis_model()`
root queries return cached data until the DB changes or the TTL expires.

### Schema Version Check

During `@PostConstruct`, after confirming the DB file exists:

- Read `PRAGMA user_version`
- If < 2: set `dbAvailable = false` (schema too old, missing required tables)
- If > 2: log warning, continue (newer schemas are additive — new tables
  don't break existing queries)
- Per-subpath try/catch for V2 tables (`issue_enrichment`,
  `trajectory_notes`, `github_issue_cache`) — if missing, that subpath
  returns empty, doesn't take down the whole service

## §3 Records

```java
record WorklogEvent(long id, String timestamp, String eventType,
                    Long workItemId, Long slotId, String repoPath,
                    String metadata) {}

record WorkItem(long id, String branch, String state, String location,
                Long slotId, String createdAt, String repoPath,
                String githubRepo, List<WorkItemIssue> issues) {}

record WorkItemIssue(int issueNumber, String issueRepo, boolean isPrimary) {}

record SlotInfo(long id, int slotNumber, String familyRoot, String state,
                String createdAt, String archivedAt) {}

record PlanState(String activeIssue, int position, int total) {}
```

All timestamps are ISO-8601 strings (matching what soredium writes). No
parsing to `Instant` — pass through as-is.

## §4 WorklogModelProvider

Implements `ModelProvider`. Lives in the `mcp` package (convention). Thin
delegate to `WorklogService`. Injects `FileWatcherService` to resolve the
workspace root for `.plan` lookups (same pattern as
`WorkspaceModelProvider`).

### domain()

Returns `"worklog"`.

### summary()

Compact overview for `trellis_model()` with no path. Returns cached result
from `WorklogService` (5s TTL, mtime-invalidated):

```json
{
  "activeWorkItems": 3,
  "recentEventCount": 12,
  "latestEvent": { "type": "work-start", "timestamp": "..." },
  "planPosition": { "active": "#42", "position": "2/6" },
  "slotsActive": 2
}
```

### resolve(subpath)

Full data for `trellis_model(path="worklog/...")`:

| Subpath | Delegates to | Returns |
|---------|-------------|---------|
| `events` | `recentEvents(limit=50)` | Last 50 events with defaults |
| `work-items` | `activeWork()` | Active work items with repo/issue associations |
| `slots` | `slotStatus()` | All slots with state and timestamps |
| `backlog` | `backlogEntries()` | Enriched issue list |
| null/empty | summary() | Same as summary |

No query parameters through the model tree — defaults only. Rich queries
go through REST.

### actionsFor()

Returns empty list. Read-only domain, no MCP-executable actions.

## §5 REST Endpoints

`WorklogResource` at `@Path("/api/worklog")`. Same subpath names as the
model tree, but with query parameters for filtering.

| Endpoint | Query params | Delegates to |
|----------|-------------|-------------|
| `GET /api/worklog/events` | `?since=`, `?type=`, `?limit=` (default 50) | `recentEvents(since, type, limit)` |
| `GET /api/worklog/work-items` | (none — active only) | `activeWork()` |
| `GET /api/worklog/work-items/{branch}/timeline` | `?repoPath=` (required) | `workItemTimeline(branch, repoPath)` |
| `GET /api/worklog/slots` | `?familyRoot=` | `slotStatus(familyRoot)` |

`BacklogResource` stays at `/api/backlog` (no breaking change) but
delegates to `WorklogService.backlogEntries()` instead of querying
directly. The SQL moves into WorklogService; BacklogResource becomes a
thin REST layer.

The MCP tool count stays at 6. Worklog data is accessible through
`trellis_model(path="worklog/...")` with default query parameters.

## §6 Scope Deviations from Issue #42

Issue #42 proposes `resolve()` subpaths: `events`, `sessions`, `outcomes`,
`health`. This spec implements `events`, `work-items`, `slots`, `backlog`.

| Issue subpath | Spec treatment | Rationale |
|---------------|---------------|-----------|
| `events` | Implemented | Direct table mapping |
| `sessions` | Renamed to `work-items` | Schema-aligned — no `sessions` table exists |
| `outcomes` | Deferred | Derivable from event metadata — add when a consumer needs it |
| `health` | Deferred | Requires running Python (`work_health.py`) — out of scope for a JDBC reader |
| `slots` | Added | Direct table mapping, high value for the coordinating agent |
| `backlog` | Added | Consolidates existing BacklogResource queries per D1 |

Outcomes and health are not dropped — they can be added as derived
subpaths when a consumer (frontend panel or coordinating agent) needs
them. Issue #42 can be closed when the three layers (service, REST,
model provider) are operational.

## §7 Testing Strategy

| Component | Test | Type |
|-----------|------|------|
| WorklogService queries | Seed a temp SQLite file, verify each query method | Unit |
| WorklogService two-pass join | Verify activeWork() returns issues grouped by work item | Unit |
| WorklogService .plan reader | Write `.plan` to temp dir, verify `planPosition()` | Unit |
| WorklogService schema version | DB with user_version=1 → unavailable; version=3 → available with warning | Unit |
| WorklogService freshness | Modify DB file, verify mtime change increments generation | Unit |
| WorklogService summary cache | Verify cached result returned within TTL, refreshed after | Unit |
| WorklogService dbAvailable=false | Verify all methods return empty/null gracefully | Unit |
| WorklogModelProvider | Mock WorklogService, verify summary/resolve shapes | Unit |
| WorklogResource | `@QuarkusTest` with seeded DB, verify REST responses and query params | Integration |
| WorklogResource timeline | Verify repoPath query param is required, returns correct events | Integration |
| BacklogResource delegation | Verify same response shape after delegation refactor | Integration |

SQLite test databases use `jdbc:sqlite:<tmpdir>/test.db` — never `:memory:`
(each `getConnection()` creates a new empty DB per GE-20260801-1148df).
Normalize paths with `Path.resolve()` to avoid macOS `/tmp` → `/private/tmp`
divergence per GE-20260730-e942d8.

## §8 Scope Boundary

### In scope

- `WorklogService` — JDBC reader + .plan reader with summary cache
- `WorklogModelProvider` — ModelProvider SPI implementation (in `mcp` package)
- `WorklogResource` — REST endpoints at `/api/worklog`
- BacklogResource refactor — delegate to WorklogService
- Package move: `backlog` → `worklog` for shared infrastructure + records
- Schema version check + file mtime freshness detection
- Unit and integration tests

### Out of scope

- Frontend panels consuming the new endpoints (separate issue)
- New MCP tool (tool count stays at 6)
- Write path to worklog.db (soredium owns writes)
- WorkspaceScanner integration for `.plan` (future refactor)
- Outcomes subpath — derivable from event metadata when needed
- Health subpath — requires Python integration, separate concern
