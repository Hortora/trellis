# Garden Service + Provenance — Design Spec

**Issue:** Hortora/trellis#14
**Date:** 2026-08-02
**Status:** Draft

---

## 1. Problem

The garden (Hortora's knowledge corpus of developer gotchas, techniques, and undocumented behaviours) is consumed exclusively through `gardenSearch` MCP calls in Claude Code sessions. There is no UI for browsing, searching, or understanding how the garden informs design decisions. When garden entries are consulted during brainstorming, the connection between "knowledge consulted" and "design produced" is lost immediately.

Two capabilities are missing:

1. **Search and browse** — no way to explore the garden outside of an LLM session. Trellis should surface garden search, entry viewing, and usage statistics.
2. **Provenance** — no record of which garden entries informed which design decisions. When brainstorming surfaces GE-0031 and it shapes the database migration strategy, that link should be recorded for lineage queries.

## 2. Architecture

All garden data is centralised through the engine. Trellis is a pure UI client.

```
Skills (soredium)                    Engine (hortora)                 Trellis (hortora)
                     MCP                                  REST
brainstorming  ───────────────────→  gardenRecord      ←──────────  Garden View
work-start         gardenRecord      Provenance           /search   (search,
                   Provenance()                          /prov...    browse,
                                     Provenance                      lineage)
                     MCP             Store
               ───────────────────→  (SQLite)
                   gardenSearch()
                                     GET /search/
                                     adaptive        ──────────→   Search proxy
```

### Why engine-centralised

- The engine already owns garden entries (Qdrant), retrieval tracking (SQLite), and all search infrastructure (5-signal hybrid retrieval, adaptive filtering, cross-encoder reranking).
- Provenance is garden usage metadata — the same category as retrieval tracking. One service should own both.
- Lineage queries are O(1) database lookups, not O(n) filesystem scans.
- No data duplication — single source of truth for all garden metadata.

### What each repo does

| Repo | Role | Changes |
|------|------|---------|
| **Engine** (Hortora/engine) | Data owner — stores provenance, serves search + lineage queries | New SQLite table, REST endpoints, MCP tool |
| **Soredium** (skills) | Data producer — records provenance during brainstorming/work-start | Adds `gardenRecordProvenance` call after user selects relevant entries |
| **Trellis** (Hortora/trellis) | UI client — presents search, browse, lineage | New REST client, resource, frontend page |

### Engine lifecycle

The engine is an independently managed background service, not a trellis-managed process. It serves multiple consumers — Claude Code sessions connect via MCP SSE, trellis connects via REST. Trellis does not own the engine lifecycle because stopping the engine when trellis closes would break active MCP sessions.

The engine is started manually by the developer and runs persistently. The trellis Garden View degrades gracefully when the engine is unavailable (§5.4). Port configuration uses `quarkus.rest-client.garden-engine.url` — if port 8080 is taken, the developer changes this config property.

## 3. Engine Extensions

### 3.1 Provenance Data Model

New SQLite database at `~/.hortora/stats/provenance.db` (separate from retrieval-tracking — provenance is hortora-specific metadata, retrieval tracking is a casehub-generic library concern). Schema managed by Flyway — migration `V1__provenance.sql` at `src/main/resources/db/provenance/migration/`:

```sql
CREATE TABLE provenance (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    issue_repo TEXT NOT NULL,
    issue_number INTEGER NOT NULL,
    ge_id TEXT NOT NULL,
    spec_name TEXT NOT NULL DEFAULT '',
    recorded_at TEXT NOT NULL,
    recorded_by TEXT,
    UNIQUE(issue_repo, issue_number, ge_id)
);

CREATE INDEX idx_provenance_issue
    ON provenance(issue_repo, issue_number);
CREATE INDEX idx_provenance_ge
    ON provenance(ge_id);
```

| Column | Type | Purpose |
|--------|------|---------|
| `issue_repo` | TEXT | GitHub repo — `Hortora/trellis` |
| `issue_number` | INTEGER | Issue number — `14` |
| `ge_id` | TEXT | Garden entry ID — `GE-20260618-c552c3` |
| `spec_name` | TEXT NOT NULL | Spec filename — `2026-08-02-garden-service-provenance-design.md`. Empty string `""` when recording before any spec exists (e.g. at work-start). Updated via UPSERT when the spec name becomes known. |
| `recorded_at` | TEXT | ISO-8601 timestamp |
| `recorded_by` | TEXT (nullable) | Source skill — `brainstorming` or `work-start` |

The unique constraint on `(issue_repo, issue_number, ge_id)` ensures one row per issue+entry pair, making recording idempotent. `spec_name` is an updatable column — initially `""`, set via UPSERT when the spec is written. This avoids the 4-column constraint design where a work-start recording (`spec_name=""`) and a later spec-time recording (`spec_name="spec.md"`) would create two separate rows for the same logical provenance link.

### 3.2 ProvenanceStore

New `@ApplicationScoped` bean in package `io.hortora.garden.provenance`. Creates its own `HikariDataSource` for `~/.hortora/stats/provenance.db` (configured via `hortora.garden.provenance.sqlite.path`). Schema managed by Flyway in `@PostConstruct`: `Flyway.configure().dataSource(dataSource).locations("classpath:db/provenance/migration").load().migrate()`.

Methods:
- `record(String issueRepo, int issueNumber, String specName, List<String> geIds, String recordedBy)` — batch UPSERT: `INSERT INTO provenance (...) ON CONFLICT(issue_repo, issue_number, ge_id) DO UPDATE SET spec_name = CASE WHEN excluded.spec_name != '' THEN excluded.spec_name ELSE provenance.spec_name END, recorded_at = excluded.recorded_at`
- `forwardLineage(String issueRepo, int issueNumber)` — returns all GE-IDs associated with an issue
- `reverseLineage(String geId)` — returns all issues that consulted this entry
- `stats()` — top-referenced entries, unreferenced entries, total count

#### Types

```java
record ProvenanceRecord(
    String issueRepo,
    int issueNumber,
    String specName,     // "" when no spec
    String geId,
    String recordedAt,   // ISO-8601
    String recordedBy    // source skill, nullable
)

record ProvenanceStats(
    int totalRecords,
    int uniqueEntries,   // distinct ge_id count
    int uniqueIssues,    // distinct (issue_repo, issue_number) count
    List<EntryRefCount> topReferenced,
    int unreferencedCount
)

record EntryRefCount(
    String geId,
    int referenceCount
)
```

### 3.3 ProvenanceResource

New JAX-RS resource at `@Path("/provenance")`:

| Endpoint | Method | Purpose | Returns |
|----------|--------|---------|---------|
| `/provenance` | POST | Record provenance | 201 Created, count recorded |
| `/provenance?issueRepo=...&issueNumber=...` | GET | Forward lineage | `List<ProvenanceRecord>` |
| `/provenance/reverse?geId=...` | GET | Reverse lineage | `List<ProvenanceRecord>` |
| `/provenance/stats` | GET | Aggregate stats | `ProvenanceStats` |

Request body for POST:
```json
{
  "issueRepo": "Hortora/trellis",
  "issueNumber": 14,
  "specName": "2026-08-02-garden-service-provenance-design.md",
  "geIds": ["GE-20260618-c552c3", "GE-0031"],
  "recordedBy": "brainstorming"
}
```

Note: `specName` should be `""` (empty string) when no spec exists yet, not null.

### 3.4 New MCP Tool

```java
@Tool(description = "Record which garden entries informed a design artifact. "
    + "Call after the user selects relevant entries during brainstorming or work-start. "
    + "Idempotent — re-recording the same provenance is a no-op.")
String gardenRecordProvenance(
    @ToolArg(description = "GitHub repo (e.g. 'Hortora/trellis')") String issueRepo,
    @ToolArg(description = "Issue number") int issueNumber,
    @ToolArg(description = "Spec filename, if known (e.g. '2026-08-02-design.md'). "
        + "Pass empty string or omit when no spec exists yet.",
             required = false) String specName,
    @ToolArg(description = "Pipe-separated GE-IDs (e.g. 'GE-0031|GE-20260618-c552c3')")
             String geIds,
    @ToolArg(description = "Source skill (e.g. 'brainstorming', 'work-start')",
             required = false) String recordedBy)
```

The MCP tool calls `ProvenanceStore.record()` directly — it parses the pipe-separated `geIds` string into a `List<String>` and coerces null/omitted `specName` to `""`. It does not route through the REST endpoint.

**Input validation:** After splitting on `|`, empty and whitespace-only segments are filtered out (handles leading/trailing pipes and consecutive delimiters like `"|GE-0031||GE-0032|"`). Any non-empty string is accepted as a GE-ID — format enforcement belongs at the garden entry layer, not the provenance layer. GE-IDs are opaque foreign keys that may use multiple formats: new-format (`GE-20260618-c552c3`), legacy (`GE-0031`), or domain-qualified paths from federation (`jvm/GE-0031`). If no IDs remain after filtering empty segments, the tool returns an error message rather than recording nothing silently.

### 3.5 Adaptive Search REST Endpoint

Expose the existing `SearchResource.searchAdaptive()` as a REST endpoint. Currently this method is internal — only called by `GardenMcpTools.gardenSearch()`.

New endpoint on `SearchResource`:

| Endpoint | Method | Purpose | Returns |
|----------|--------|---------|---------|
| `/search/adaptive?q=...&keywords=...&domain=...&type=...&tags=...&limit=...` | GET | Adaptive search with gap detection and cluster extension | `AdaptiveResult` |

This reuses the existing `searchAdaptive()` logic — no new search code. The `domain` parameter is `@QueryParam("domain") List<String> domains` (repeatable query param, e.g., `?domain=jvm&domain=python`), matching the existing `search()` endpoint signature.

**Re-index awareness:** When the Qdrant collection is unavailable (deleted during `gardenReindex`), the endpoint returns `AdaptiveResult` with empty results and `collectionReady: false`. Normal responses have `collectionReady: true`. This lets consumers distinguish "no matches" from "index unavailable" without a separate health endpoint. The `AdaptiveResult` record gains one field: `boolean collectionReady` (default `true`).

### 3.6 Entry Lookup Endpoint

| Endpoint | Method | Purpose | Returns |
|----------|--------|---------|---------|
| `/entries/{id}` | GET | Fetch a single entry by GE-ID | `EntryDetail` |

`EntryDetail` is a new record type containing entry metadata without search-scoring fields:

```java
public record EntryDetail(
    String id, String title, String domain, String type,
    int score, String body, String source, String sourcePrefix,
    List<String> seeAlsoIds)
```

The engine maps from the Qdrant payload to `EntryDetail`, explicitly omitting `relevanceScore` and `crossEncoderScore` which are search-specific. This matches the `EntryDetail` type expected by the trellis `GardenClient` (§5.1).

The `{id}` path parameter is a GE-ID (e.g., `GE-20260618-c552c3`), which differs from Qdrant's `sourceDocumentId` format (e.g., `jvm/GE-20260618-c552c3.md`). The endpoint resolves GE-IDs to sourceDocumentIds by scanning `embeddingIngestor.listDocuments()` and building a `geId → sourceDocumentId` mapping (same approach as `GardenMcpTools.expandWithSeeAlso()`). The mapping is cached and invalidated on re-index. Returns 404 if the GE-ID cannot be resolved to any indexed document.

## 4. Soredium Skill Changes

### 4.1 brainstorming

Step 1 (Gather context) runs `forage SEARCH` with keywords from the idea, which calls `gardenSearch` internally to surface relevant garden entries before design questions begin. **New step:** after entries are surfaced, ask the user which entries are relevant to this design, then record provenance:

```
gardenRecordProvenance(
    issueRepo=<from .meta>,
    issueNumber=<from .meta>,
    specName="",
    geIds=<pipe-separated selected IDs>,
    recordedBy="brainstorming"
)
```

This is a skill change — brainstorming currently surfaces entries without an explicit selection step. The new step asks "Which of these entries are relevant to this design?" before proceeding to design questions.

When the spec is written (Step 5), record again with `specName` filled in to link the specific spec.

### 4.2 work-start

Step 3b calls `gardenSearch` (MCP) directly to surface garden entries and asks the user which are relevant. Record after user selection:

```
gardenRecordProvenance(
    issueRepo=<from .meta or CLAUDE.md>,
    issueNumber=<from .meta>,
    specName="",
    geIds=<pipe-separated selected IDs>,
    recordedBy="work-start"
)
```

### 4.3 Error Handling

If `gardenRecordProvenance` fails (engine not running, MCP unavailable): warn once, continue. Same pattern as `gardenSearch` unavailability. Provenance recording is never a gate on work.

## 5. Trellis Garden Service

### 5.1 GardenClient

Quarkus REST Client interface in `io.hortora.trellis.garden`:

```java
@RegisterRestClient(configKey = "garden-engine")
@Path("/")
public interface GardenClient {

    @GET @Path("/search/adaptive")
    AdaptiveResult search(
        @QueryParam("q") String query,
        @QueryParam("keywords") String keywords,
        @QueryParam("domain") List<String> domains,
        @QueryParam("type") String type,
        @QueryParam("tags") String tags,
        @QueryParam("limit") Integer limit);

    @GET @Path("/entries/{id}")
    EntryDetail getEntry(@PathParam("id") String geId);

    @GET @Path("/provenance")
    List<ProvenanceRecord> forwardLineage(
        @QueryParam("issueRepo") String repo,
        @QueryParam("issueNumber") int number);

    @GET @Path("/provenance/reverse")
    List<ProvenanceRecord> reverseLineage(@QueryParam("geId") String geId);

    @GET @Path("/provenance/stats")
    ProvenanceStats stats();
}
```

**Trellis-side types:** `AdaptiveResult` is a client-side record mirroring the engine's `io.hortora.garden.search.AdaptiveResult` JSON shape:

```java
record AdaptiveResult(
    List<SearchResult> results,
    int requestedLimit,
    int availableAboveFloor,
    boolean extended,
    boolean trimmed,
    int floorFiltered,
    boolean collectionReady  // false during re-index
)
```

Configuration: `quarkus.rest-client.garden-engine.url=http://localhost:8080`

### 5.2 GardenResource

Trellis REST resource at `@Path("/api/garden")`. Delegates to `GardenClient` for engine communication and `ProvenanceEnricher` for workspace enrichment. `ProvenanceEnricher` is a separate `@ApplicationScoped` bean that joins engine provenance data with workspace scanner data (correlating issue numbers with slots/branches). This separation keeps the resource thin and both components independently testable.

| Endpoint | Purpose |
|----------|---------|
| `GET /api/garden/search?q=...` | Proxy to engine adaptive search |
| `GET /api/garden/entries/{id}` | Proxy to engine entry detail |
| `GET /api/garden/provenance?issueRepo=...&issueNumber=...` | Forward provenance + workspace enrichment |
| `GET /api/garden/provenance/reverse?geId=...` | Reverse provenance + workspace enrichment |
| `GET /api/garden/stats` | Provenance stats |

#### Enrichment Contract

Trellis enriches provenance records with workspace context by correlating `issueRepo + "#" + issueNumber` against `SlotInfo.issue` from the `WorkspaceScanner`. This is the primary value-add of the trellis layer over direct engine access.

Enriched response type (wraps engine's `ProvenanceRecord` with workspace fields):

```java
record EnrichedProvenanceRecord(
    // Fields from engine ProvenanceRecord
    String issueRepo,
    int issueNumber,
    String specName,
    String geId,
    String recordedAt,
    String recordedBy,
    // Workspace enrichment (null when no matching slot)
    WorkspaceContext workspace
)

record WorkspaceContext(
    int slotNumber,
    String slotStatus,    // ACTIVE, READY_TO_LAND, ARCHIVED
    List<String> repos    // repos checked out in this slot
)
```

Enrichment behaviour:
- **Match found:** `workspace` populated from `SlotInfo` (slot number, status, repos).
- **No match:** `workspace` is `null`. No error — the issue may predate the current workspace layout or belong to a different machine.
- **Synchronous:** `WorkspaceScanner` data is in-memory (refreshed by `FileWatcherService`). No async/blocking calls.
- **Failure isolation:** If `WorkspaceScanner` throws during enrichment (e.g., watched directory unmounted), return unenriched provenance data with `workspace: null` on all records. Enrichment failure never causes a 500 — the successfully retrieved provenance data from the engine is always returned. Log the enrichment error at WARN level.

### 5.3 Garden View UI

New page in the trellis frontend (TypeScript + Lit, consistent with existing pages):

**Search panel:**
- Query input with domain/type filter dropdowns
- Results list: entry title, domain badge, type badge, relevance score
- Click result → entry detail

**Entry detail panel:**
- Rendered markdown body
- Metadata bar: domain, type, score, submitted date
- Usage map: reverse provenance — "Informed: #14 (trellis), #42 (engine), ..." with links. When a referenced entry returns 404 from the entry detail endpoint, display it as "[Entry removed] GE-XXXXXXXX-XXXXXX" — provenance records are historical and persist after entry deletion from the garden corpus.

**Stats dashboard:**
- Top-referenced entries (bar chart or ranked list)
- Unreferenced entries count
- Domain distribution

### 5.4 Degradation

Two degradation states:

1. **Engine unreachable** → Garden View shows "Garden service unavailable — engine not running at {url}" with a retry button. No fallback — trellis does not own garden data.
2. **Engine re-indexing** → Detected via `AdaptiveResult.collectionReady == false`. Garden View shows "Garden is re-indexing — search temporarily unavailable. Provenance queries still work." Provenance and stats endpoints are unaffected (backed by SQLite, not Qdrant). Entry lookup (`/entries/{id}`) also fails during re-indexing since it depends on Qdrant; the UI handles 404s gracefully per §5.3.

REST client configuration:
```properties
quarkus.rest-client.garden-engine.connect-timeout=2000
quarkus.rest-client.garden-engine.read-timeout=10000
```

Uses `@Retry(maxRetries=2, delay=500, jitter=200)` and `@Fallback` on the REST client. Retries use fixed delay with jitter (500ms ± 200ms per retry). Fallback returns an error response with `available: false` that the UI renders as the unavailable state.

## 6. Data Flow

### Recording provenance (write path)

```
1. User starts brainstorming on issue #14
2. brainstorming runs forage SEARCH → gardenSearch (MCP) → engine returns entries
3. User selects relevant entries (GE-0031, GE-20260618-c552c3)
4. brainstorming calls gardenRecordProvenance (MCP) →
   engine writes to SQLite provenance table
5. User writes spec → brainstorming calls gardenRecordProvenance again
   with specName filled in
```

### Querying lineage (read path)

```
1. User opens Garden View in trellis
2. Searches for "CDI restart" → trellis calls engine GET /search/adaptive
3. Clicks GE-20260618-c552c3 → trellis calls engine GET /entries/{id}
4. Usage map shows → trellis calls engine GET /provenance/reverse?geId=GE-20260618-c552c3
5. Engine returns: [{issueRepo: "Hortora/trellis", issueNumber: 14, specName: "..."}]
6. Trellis enriches with workspace context (slot info, branch status)
```

## 7. Testing

| Layer | Approach |
|-------|----------|
| Engine: `ProvenanceStore` | `@QuarkusTest` with real SQLite (temp file). CRUD, lineage queries, idempotency, index performance. |
| Engine: `ProvenanceResource` | REST-assured integration tests. Record → forward → reverse → stats. |
| Engine: adaptive search endpoint | REST-assured, using `InMemoryCaseRetriever` (existing test infra). |
| Engine: MCP tool | Unit test calling tool method directly (existing `GardenMcpToolsTest` pattern). |
| Soredium: skill changes | Manual validation — run brainstorming on a real issue, verify engine DB. Skills are LLM prompt templates, not deterministic code — automated unit testing is not applicable. The engine MCP tool test (`GardenMcpToolsTest`) covers the recording mechanism; skill-side integration (argument construction, workflow step timing) is validated manually at initial release. See Hortora/trellis#21 for automated end-to-end provenance path testing. |
| Trellis: `GardenClient` | `@QuarkusTest` with WireMock stubbing engine responses. |
| Trellis: `GardenResource` | REST-assured with WireMock. Verify enrichment logic. |
| Trellis: UI | Manual — dev server, exercise search/browse/lineage in browser. |

## 8. Not in Scope

- **Outcome tracking** — filed as Hortora/engine#74. Builds on CBR infrastructure (`CbrOutcome`, `CbrCaseMemoryStore.recordOutcome()`). Separate from provenance.
- **`.provenance.yaml` files** — engine-only storage. No filesystem artifacts. Reversible — can add file export later without changing the query model.
- **Garden entry editing/creation** — the garden git repo is authoritative. Trellis is read-only.
- **Garden federation** — the engine already handles federation (chain walking across canonical/child gardens). Trellis inherits this transparently via the search endpoint.

## 9. Dependencies

- Engine must be running for Garden View to function (no offline mode). Engine is independently managed — see §2 "Engine lifecycle."
- Qdrant vector store must be running and accessible to the engine for entry lookup (`/entries/{id}`) and search endpoints.
- `quarkus-rest-client-jackson` dependency needed in trellis sidecar pom (not currently present — sidecar has server-side `quarkus-rest-jackson` but not the REST client).
- `sqlite-jdbc` and `HikariCP` for engine's `ProvenanceStore` (sqlite-jdbc already present in engine; HikariCP already a transitive dependency via `casehub-neocortex-rag-tracking`).
- Soredium skills must have `.meta` context (issue number, repo) available — already true when work-start has run.

## 10. Future

- **Outcome tracking** (engine#74) — close the feedback loop with CBR-based confidence adjustment.
- **Provenance in LLM coordinator** — the coordinator (trellis issue #17) could consult provenance to understand what knowledge informed current designs.
- **Garden health dashboard** — combine retrieval tracking, provenance, and outcome data into a garden health score per entry.
