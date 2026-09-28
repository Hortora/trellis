---
title: "Backlog Panel Tree Table for Issue Hierarchy"
date: 2026-09-28
issue: 71
issue_repo: Hortora/trellis
---

# Backlog Panel Tree Table for Issue Hierarchy

## Problem

The backlog panel renders all issues in a flat table. Issues with sub-issues
(epics, tracking issues) appear alongside their children with no visual
hierarchy. Users can't see which issues belong to which parent.

## Solution

Use the `pages-data-table` built-in tree table support to render parent/child
relationships. Data source: GitHub's native sub-issues API.

## Architecture

Three layers, each a small change:

### 1. Cache — `github_issue_cache` schema

Add a `parent_issue` column to `github_issue_cache`:

```sql
ALTER TABLE github_issue_cache ADD COLUMN parent_issue TEXT;
```

Value format: `"Org/repo#N"` (matches the existing `key` format in the
frontend) or `NULL` for root-level issues. Populated during enrichment
refresh.

### 2. Enrichment — `enrichment.py refresh`

During the GitHub cache refresh, fetch sub-issue relationships via GraphQL:

```graphql
{
  repository(owner: $owner, name: $repo) {
    issues(first: 100, states: OPEN) {
      nodes {
        number
        subIssues(first: 50) {
          nodes { number }
        }
      }
    }
  }
}
```

For each parent→child pair, write `parent_issue = "owner/repo#parentNumber"`
on the child's cache row. This inverts the API direction (parent has children)
into the storage direction (child knows its parent) — which is what the tree
builder needs.

Pagination: use cursor-based pagination for repos with >100 open issues.
Sub-issues per parent are capped at 50 (no known epic exceeds this).

### 3. Backend — `BacklogEntry` + `WorklogService`

Add `parentKey` field to `BacklogEntry`:

```java
public record BacklogEntry(
    // ... existing fields ...
    String parentKey   // "Org/repo#N" or null
) {}
```

Update `WorklogService.backlogEntries()` SQL to include `parent_issue`:

```sql
SELECT c.issue_number, c.issue_repo, c.title, c.labels, c.cached_at,
       e.strategic_role, e.readiness, ...,
       c.parent_issue
FROM github_issue_cache c
LEFT JOIN ...
```

### 4. Frontend — `backlog-panel.ts`

Add a `parentKey` field to the TypeScript `BacklogItem` interface.

Add a `parent` column to the dataset via `fromRows()`:

```typescript
{ id: COL.parent, type: ColumnType.TEXT, getValue: i => i.parentKey ?? '' }
```

Pass `expandable` config to `pages-data-table`:

```typescript
.expandable=${{ idColumn: COL.key, parentColumn: COL.parent, defaultExpanded: 1 }}
```

`defaultExpanded: 1` expands the first level by default (epics show their
children). Deeper nesting (sub-sub-issues) starts collapsed.

Hide the `parent` column from display — it's structural, not user-facing:

```typescript
.hiddenColumns=${[COL.key, COL.parent] as any}
```

### Edge cases

- **Orphan children** — child references a parent not in the dataset (closed,
  different repo filter). `buildTreeIndex` treats orphans as roots — they
  appear at top level. No special handling needed.
- **Cross-repo refs** — sub-issues can reference issues in other repos.
  `COL.key` already uses `${issueRepo}#${issueNumber}` format, so cross-repo
  parents resolve correctly when both repos are in the workspace.
- **Empty parent column** — issues without parents have `parentKey = null`,
  serialised as empty string. `buildTreeIndex` treats empty/null `parentId`
  as root.

## What changes

| Layer | File | Change |
|-------|------|--------|
| Cache | `enrichment.py` | Fetch `subIssues` during refresh, write `parent_issue` |
| Schema | `github_issue_cache` | Add `parent_issue TEXT` column |
| Backend | `BacklogEntry.java` | Add `parentKey` field |
| Backend | `WorklogService.java` | Include `parent_issue` in backlog query |
| Frontend | `backlog-panel.ts` | Add `parentKey` to interface, `parent` column, `expandable` prop |
| Frontend | `backlog-panel.test.ts` | Update test data with `parentKey` field |

## What doesn't change

- Filter logic — `applyFilters` works on the flat item list before tree
  building. Filtering a parent hides it and its children automatically
  (tree builder only sees filtered results).
- Sort — `pages-data-table` sorts within tree levels natively.
- Sidebar detail view — unchanged, works on the active item regardless
  of nesting.
- Other panels — no cross-panel impact.

## References

- `pages-table/src/tree-builder.ts` — `ExpandableConfig`, `buildTreeIndex`
- `sidecar/src/main/java/io/hortora/trellis/worklog/WorklogService.java:242` — `backlogEntries()`
- `sidecar/src/main/java/io/hortora/trellis/worklog/BacklogEntry.java` — record definition
- `sidecar/src/main/webui/src/views/backlog-panel.ts` — current flat table implementation
- GitHub GraphQL API — `subIssues` field
