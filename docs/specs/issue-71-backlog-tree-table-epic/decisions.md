# Decisions — #71 Backlog Tree Table

## D1: Parent/child data source

**Choice:** GitHub native sub-issues API (`subIssues` GraphQL field)
**Alternatives:**
- Body checklist parsing (`- [x] #N`) — fragile, survives body edits poorly, duplicates logic already handled by GitHub
**Rationale:** All Hortora/casehub repos now support native sub-issues. The GraphQL API is authoritative and doesn't require parsing markdown.
**Trade-offs:** Requires enrichment refresh to fetch sub-issue relationships; issues not yet migrated to native sub-issues won't show hierarchy.
**Sources:** GitHub GraphQL API (`subIssues` field), issue #2 body structure
**Exploration:** quick
**Status:** captured

## D2: Where to compute hierarchy

**Choice:** Backend — store `parent_issue` in `github_issue_cache`, expose via `BacklogEntry.parentKey`
**Alternatives:**
- Frontend computation — would require sending issue bodies or relationships to client, adds complexity to the Lit component
**Rationale:** The cache already stores issue data; adding one column keeps the frontend thin. `WorklogService` already joins enrichment data — same pattern.
**Trade-offs:** Enrichment refresh must include the sub-issues query.
**Sources:** `WorklogService.backlogEntries()`, `github_issue_cache` schema
**Exploration:** quick
**Status:** captured

## D3: Tree rendering

**Choice:** `pages-data-table` built-in `expandable` config (`{ idColumn, parentColumn, defaultExpanded }`)
**Alternatives:**
- Custom tree rendering — unnecessary since the platform component already supports it
**Rationale:** `pages-data-table` has full tree support: `buildTreeIndex`, expand/collapse, tree pagination, sorting, filtering. Zero custom code needed.
**Trade-offs:** None — this is the designed API for tree tables.
**Sources:** `pages-table/src/tree-builder.ts`, `ExpandableConfig` interface
**Exploration:** quick
**Status:** captured

## D4: Epic detection

**Choice:** No detection — any issue with sub-issues is a parent, regardless of type or title
**Alternatives:**
- Title prefix matching (`"Epic: ..."`) — brittle, requires naming conventions
- GitHub issue types — not consistently applied across repos
**Rationale:** The hierarchy is the data. Whether something is called an "epic" is a label concern, not a structural concern.
**Trade-offs:** None.
**Exploration:** quick
**Status:** captured
