## D1: Service consolidation — single access layer

**Choice:** Consolidate all worklog.db access into WorklogService
**Alternatives:**
- Separate concerns — WorklogService owns lifecycle tables, BacklogResource keeps enrichment/cache queries. Clear domain boundaries but two maintenance sites.
- Consolidate later — ship lifecycle tables first, absorb BacklogResource in a follow-up. Faster initial delivery but defers the fragmentation problem.
- Shared DataSource, separate services (reviewer R1-02) — extract DataSource producer to `worklog` package, keep domain-specific query services. Architecturally cleaner but more moving parts for a pre-release project.
**Rationale:** The issue explicitly states "single access layer." BacklogResource's query is a single SQL join — mechanical to move. One service, multiple consumers (REST resource, ModelProvider). The reviewer's domain separation argument has merit (lifecycle ≠ enrichment), but for pre-release with a single DB file and no API consumers, one service is simpler. If the query domains diverge significantly, split the service then.
**Trade-offs:** BacklogResource loses its self-contained simplicity. The `backlog` package merges into `worklog`. Domain boundaries are softer than the reviewer's alternative.
**Exploration:** quick
**Review note:** R1-02 challenged this — "physical storage colocation is not domain cohesion." Valid concern; acknowledged but kept for simplicity at pre-release stage.
**Status:** captured

## D2: .plan file reading — WorklogService reads on demand

**Choice:** WorklogService reads the .plan file directly on demand
**Alternatives:**
- Provider reads it — keeps WorklogService pure JDBC but ModelProvider does filesystem I/O.
- Drop from worklog model — .plan position available via workspace model already, don't duplicate.
- Extend WorkspaceScanner (reviewer R1-03) — .plan is structurally part of slot directories. WorkspaceScanner already reads slot files.
- Soredium writes plan state to worklog.db (reviewer R1-03) — a `plan_state` table keeps WorklogService pure DB reader.
**Rationale:** All work-state assembly in one service. .plan is a small text file — reading it on demand is trivial. Keeps the ModelProvider thin. The WorkspaceScanner alternative is architecturally correct but outside scope — noted for future refactoring.
**Trade-offs:** WorklogService does both JDBC and filesystem I/O. Not pure DB reader.
**Exploration:** quick
**Review note:** R1-03 proposed WorkspaceScanner or soredium-side alternatives. Deferred — pragmatic choice for v1.
**Status:** captured

## D3: Model subpaths — schema-aligned

**Choice:** Map resolve() subpaths to schema-aligned names: `events`, `work-items`, `slots`, `backlog`
**Alternatives:**
- Issue spec names — `events`, `sessions`, `outcomes`, `health`. "Sessions" and "outcomes" don't map to tables; "health" requires Python.
- Minimal — just events and active-work. Add more when frontend needs them.
**Rationale:** Direct mapping to actual DB tables. "sessions" was ambiguous (no `sessions` table — it's `work_items`). Using `work-items` makes the model tree self-documenting. `backlog` consolidates the enrichment/cache queries from BacklogResource per D1.
**Trade-offs:** Diverges from the issue's proposed subpath list. No health/outcomes until a consumer needs them.
**Exploration:** quick
**Review note:** R1-04 caught that "sessions" had no corresponding table. Revised to schema-aligned names.
**Status:** revised

## D4: REST shape — model-aligned with query parameters

**Choice:** REST endpoints share the same subpath names as the model tree, but with richer query parameters for filtering
**Alternatives:**
- Carbon-copy of model subpaths — identical shape, no query params. Removes ability to filter.
- Frontend-tailored composite endpoints — custom shape per panel. Diverges from model.
- Single endpoint with query params — flexible but less discoverable.
**Rationale:** Existing pattern shows model providers give compact domain summaries while REST/tools give richer query interfaces (trellis_workspace has `refresh`, `operation`, `params`). REST endpoints for worklog should accept `?since=`, `?type=`, `?limit=` etc. Model subpath returns defaults (recent events, active work items).
**Trade-offs:** REST and model responses aren't identical — frontend developers need to know the REST endpoint may return more/different data than the model tree.
**Depends on:** D3 (subpath definition)
**Exploration:** quick
**Review note:** R1-05 correctly identified that existing REST/model patterns already diverge. Revised from "mirror" to "model-aligned with query params."
**Status:** revised

## D5: Freshness — PRAGMA data_version for generation counter

**Choice:** Use SQLite `PRAGMA data_version` to detect external writes and increment GenerationCounter
**Alternatives:**
- Don't increment (original choice) — external data uses DB timestamps only. Creates a silent freshness gap where `generation: unchanged` doesn't mean "all data current."
- Poll periodically — timer-based DB change detection. Adds complexity.
- File mtime check — simpler than data_version but less precise.
**Rationale:** `PRAGMA data_version` returns a value that changes whenever any connection modifies the database. WorklogService checks this on each `summary()` call. If changed since last check, increment GenerationCounter. Near-zero cost (single pragma read), no polling, and MCP consumers get a uniform freshness signal across all domains.
**Trade-offs:** Adds one PRAGMA read per model query. Negligible cost but adds a code path.
**Exploration:** quick
**Review note:** R1-06 identified the generation gap and proposed PRAGMA data_version. Superior to the original "don't increment" choice.
**Status:** revised

## D6: Schema version strategy — fail-fast with graceful degradation

**Choice:** Check PRAGMA user_version on connect; warn and degrade if version is unknown
**Alternatives:**
- No check — hope schemas evolve in lockstep. Silent breakage on divergence.
- Strict version match — refuse to serve if version != expected. Too aggressive for a read-only reader.
**Rationale:** WorklogService checks `PRAGMA user_version` during init. If version < 2 (minimum required), disable worklog features (same as `isDbAvailable() == false`). If version > max known (currently 2), log a warning but continue — newer schemas are additive (new tables, not column renames). Query unknown tables with try/catch and degrade per-subpath.
**Trade-offs:** Graceful degradation may silently miss new data in unknown tables. But failing hard on a read-only external DB is worse — it blocks the whole worklog domain for a schema the writer controls.
**Exploration:** quick
**Review note:** R1-08 identified missing schema coupling strategy. Added as new decision.
**Status:** captured
