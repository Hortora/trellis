# Enriched Backlog Panel — Design Spec

**Issue:** Hortora/trellis#47
**Date:** 2026-08-10
**Branch:** issue-47-enriched-backlog-panel

## Overview

Read-only consumer of the soredium worklog DB's enrichment tables (`github_issue_cache`, `issue_enrichment`, `trajectory_notes`). Exposes enriched backlog data via a trellis REST endpoint and renders it in a new dashboard panel with filtering, sorting, and cache staleness indicators.

## Decisions

See [decisions.md](decisions.md) for the full decision log.

- **D1:** Dedicated `WorklogDataSourceProducer` with `@WorklogDS` qualifier (follows `CoordinatorDataSourceProducer` pattern)
- **D2:** `pages-data-table` with `fromRows()`, `columnRenderers` for classification badges, dropdown filters

## Constraints

- Client-side filtering only (browser cache + interval refresh)
- No server-side TTL — `cached_at` returned per row, UI computes cache age
- Cross-repo by default — optional `?repo=` filter on the endpoint

## Architecture

### Backend — `io.hortora.trellis.backlog`

**WorklogDataSourceProducer**
- `@ApplicationScoped` CDI bean
- Produces `@WorklogDS DataSource` pointing at `~/.hortora/worklog.db`
- Config property: `trellis.worklog.db-path` with default `${user.home}/.hortora/worklog.db`
- **Read-only connection:** JDBC URL uses `?mode=ro` — trellis must never mutate soredium's database
- **Busy timeout:** Set `busy_timeout=3000` (3s) on the connection to handle concurrent writes from `enrichment.py`
- **Graceful degradation:** If the DB file does not exist (soredium never ran), the producer initialises successfully but `BacklogResource` returns an empty list — not a startup crash. Check `Files.exists()` before opening; set a `dbAvailable` flag

**BacklogEntry**
- Java record for the response shape:
  ```
  issueNumber, issueRepo, title, labels, cachedAt,
  strategicRole, readiness, decay, blastRadius, cohesion, enrichedAt,
  trajectoryNote, trajectoryAt
  ```
- `labels` is `List<String>` (parsed from the JSON string in the DB, not passed through raw)
- `state` field omitted — the query filters to `OPEN` only, so it's always the same value
- Unenriched issues have null for all enrichment fields
- `trajectoryNote`/`trajectoryAt` are null when no trajectory exists

**BacklogResource**
- `@Path("/api/backlog")`, `@Produces(APPLICATION_JSON)`
- Single `GET` endpoint with optional `@QueryParam("repo")` filter
- SQL: LEFT JOIN `github_issue_cache` with `issue_enrichment`, filtered to `state = 'OPEN'`
- Returns `List<BacklogEntry>` ordered by `issue_repo, issue_number`
- If `dbAvailable` is false, returns empty list immediately

### SQL Query

Single query — trajectory joined via correlated subquery, no N+1:

```sql
SELECT c.issue_number, c.issue_repo, c.title, c.labels, c.cached_at,
       e.strategic_role, e.readiness, e.decay, e.blast_radius, e.cohesion, e.updated_at,
       t.note AS trajectory_note, t.created_at AS trajectory_at
FROM github_issue_cache c
LEFT JOIN issue_enrichment e
  ON c.issue_number = e.issue_number AND c.issue_repo = e.issue_repo
LEFT JOIN trajectory_notes t
  ON t.id = (
    SELECT id FROM trajectory_notes t2
    WHERE t2.issue_number = c.issue_number AND t2.issue_repo = c.issue_repo
    ORDER BY t2.id DESC LIMIT 1
  )
WHERE c.state = 'OPEN'
[AND c.issue_repo = ?]
ORDER BY c.issue_repo, c.issue_number
```

### Frontend — `views/backlog-panel.ts`

**Component:** `trellis-backlog-panel`

**Data flow:**
1. `connectedCallback` → fetch `/api/backlog` → store as `_items`, start 60s refresh interval
2. `disconnectedCallback` → clear the refresh interval (prevent leak)
3. Refresh interval pauses when the panel is not visible (check via workbench's panel switching — no polling when hidden)
4. Filter state as `@state` properties — one per dimension plus repo
5. On render: apply filters → `fromRows()` → `pages-data-table`

**Table columns:**

| Column | Type | Renderer |
|--------|------|----------|
| # | NUMBER | Issue number, clickable GitHub link |
| Repo | TEXT | `owner/repo` |
| Title | TEXT | Plain text, truncated |
| Role | LABEL | Colour pill |
| Readiness | LABEL | Colour pill |
| Decay | LABEL | Colour pill |
| Blast | LABEL | Colour pill |
| Cohesion | TEXT | Tag text |

**Filters:** Row of `<select>` dropdowns above the table, one per enrichment dimension plus repo. Populated from distinct values in the dataset. "All" as default.

**Cache age indicator:** Header shows "Refreshed X ago" from `min(cachedAt)` across **all** items (not filtered subset — the cache age applies to the dataset, not the current view). Colour: neutral < 4h, amber 4–24h, red > 24h.

**Trajectory detail:** Sidebar pane (memory panel pattern), activated on row click. Shows issue title, all classifications, and most recent trajectory note with timestamp.

**Sorting:** Client-side via `pages-data-table` built-in (`sortable` + `clientSort`).

### Workbench Wiring

- Add `backlog` entry to `PANELS`: `{ icon: '📋', label: 'Backlog', tag: 'trellis-backlog-panel' }`
- Add `'backlog'` to `DOCK_PANELS` array
- Import `'../views/backlog-panel'` in `workbench.ts`
- Hash route: `#backlog`

## Testing

### Backend — `BacklogResourceTest`

Quarkus `@QuarkusTest` with REST Assured:
- Creates temp SQLite DB, inserts test rows into all three tables
- `GET /api/backlog` returns all open issues with enrichment fields
- `GET /api/backlog?repo=X` filters correctly
- Unenriched issues have null enrichment fields
- Closed issues are excluded
- Trajectory note is the most recent one
- Returns empty list when worklog.db does not exist (no crash)
- `labels` field is a parsed `List<String>`, not raw JSON

### Frontend — `backlog-panel.test.ts`

Vitest unit tests:
- Filter logic: each dimension filters correctly, multiple filters compose
- Cache age computation from min `cachedAt`
- Empty state rendering
- `fromRows()` produces correct column types and values

## Out of Scope

- Server-side filtering/pagination (client-side is sufficient at this data volume)
- Refresh button triggering `enrichment.py refresh` (requires shell-out, separate issue)
- Dockview-based split layout for side-by-side dashboard+backlog (#49)
- E2E/Playwright tests
