## D1: State scope — all worklog states

**Choice:** Map all worklog.db slot states to SlotStatus enum values, plus PAUSED from .slot files
**Alternatives:**
- PAUSED + ARCHIVING only — solves immediate problems but leaves edge states invisible
- PAUSED only — minimal scope, defers worklog integration
**Rationale:** worklog.db already tracks 6 lifecycle states (active, archived, archiving, failed, pending, purged). Mapping them all gives a complete lifecycle view in the dashboard with minimal extra work — the data source already exists.
**Trade-offs:** More enum values to maintain badge colors/display for. Edge states (failed, pending, purged) may rarely appear.
**Sources:** worklog.db schema (`slots` table, `state` column), `WorklogService.slotStatus()`
**Exploration:** quick
**Status:** captured

## D2: Architecture — scanner calls WorklogService

**Choice:** WorkspaceScanner gets @Inject WorklogService and merges worklog slot state into scan results
**Alternatives:**
- Merge in FileWatcherService — keeps scanner pure but adds merging logic to the watcher
- Merge in ScannerResource — scanner stays pure but every consumer repeats the merge
**Rationale:** Single scan() call returns enriched data. Simplest consumer API — FileWatcherService, ScannerResource, and MCP tools all get the correct status without per-consumer merging.
**Trade-offs:** WorkspaceScanner gains a CDI dependency (was previously stateless). Test needs mock or @QuarkusTest.
**Sources:** `WorkspaceScanner.java` (current pure-filesystem scanner), `WorklogService.slotStatus()` (existing worklog query)
**Exploration:** quick
**Status:** captured

## D3: Status priority order

**Choice:** attic location > worklog state > .phase-a-complete > .slot status field > default ACTIVE
**Alternatives:**
- worklog-first (always trust worklog over filesystem) — but attic location is authoritative for ARCHIVED
- filesystem-only (ignore worklog) — loses archiving/failed/pending visibility
**Rationale:** Attic location is the ground truth for ARCHIVED. Worklog state provides lifecycle stages the filesystem can't express (archiving, failed, pending, purged). Filesystem markers (.phase-a-complete, .slot status) fill gaps for slots not yet in worklog or when worklog is unavailable.
**Trade-offs:** If worklog.db is stale or unavailable, slots fall back to filesystem-only status (ACTIVE/READY_TO_LAND/ARCHIVED) — graceful degradation.
**Sources:** `WorkspaceScanner.parseSlotFile()` lines 229-230 (current filesystem-only logic)
**Exploration:** quick
**Status:** captured
