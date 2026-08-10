## D1: Backend data access pattern

**Choice:** Dedicated WorklogDataSourceProducer with @WorklogDS qualifier
**Alternatives:**
- Shared "external DB" abstraction — premature; two consumers with different DBs, schemas, lifecycles
- Shell out to enrichment.py — Python runtime dependency, subprocess overhead, brittle
**Rationale:** Follows the proven CoordinatorDataSourceProducer pattern. Clean separation, simple to test, no coupling between worklog and coordinator access.
**Trade-offs:** Another DataSource producer class, but the qualifier pattern keeps them isolated and the boilerplate is minimal.
**Exploration:** quick
**Status:** captured

## D2: Frontend panel component pattern

**Choice:** pages-data-table with fromRows(), columnRenderers for badges, client-side sort, dropdown filters above the table
**Alternatives:**
- Custom card grid layout — more visual but doesn't suit filtering/sorting workflow, doesn't scale to dozens of issues
**Rationale:** Data-dense, filterable view. Tables are the right form factor. pages-data-table is the codebase convention (memory panel is the reference). Selection, sorting, column config come free.
**Trade-offs:** Less visually striking than cards, but the data demands comparison not browsing.
**Exploration:** quick
**Status:** captured
