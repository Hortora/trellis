# Slot Status Enrichment — Design Spec

## Problem

The dashboard shows three slot states: ACTIVE, READY_TO_LAND, ARCHIVED.
This misses important lifecycle stages — paused slots look active, and
landed-but-not-archived slots show as READY_TO_LAND when they're past that
stage. Identifying which slots need cleanup requires manual investigation.

worklog.db already tracks 6 lifecycle states (`active`, `archived`,
`archiving`, `failed`, `pending`, `purged`), and `.slot` files have an
optional `## Status` section for `paused` state. Neither source is
consulted by `WorkspaceScanner` — it infers status from filesystem
markers only.

## Design

### Extended SlotStatus enum

| Enum value | Source | Meaning | Badge color |
|---|---|---|---|
| `ACTIVE` | default | Normal working state | Green |
| `PAUSED` | `.slot` `## Status` | Slot paused by user | Amber |
| `PENDING` | worklog.db | Created but not started | Grey |
| `READY_TO_LAND` | `.phase-a-complete` | Work done, PR pending | Yellow |
| `ARCHIVING` | worklog.db | Landed, waiting for attic move | Blue |
| `ARCHIVED` | attic location | In attic | Grey dim |
| `FAILED` | worklog.db | Something went wrong | Red |
| `PURGED` | worklog.db | Cleaned up | Grey dim |

### Status resolution priority

When multiple sources provide conflicting status, apply this priority:

1. **Attic location** → `ARCHIVED` (ground truth — slot is physically in attic)
2. **worklog.db state** → `ARCHIVING`, `FAILED`, `PENDING`, `PURGED` (lifecycle states the filesystem can't express)
3. **`.phase-a-complete` marker** → `READY_TO_LAND` (filesystem marker for completed work)
4. **`.slot` `## Status` field** → `PAUSED` if `status: paused` (operational state from soredium)
5. **Default** → `ACTIVE`

When worklog.db is unavailable (no db file, connection error), the scanner
falls back to filesystem-only status — graceful degradation.

### Backend changes

#### `SlotStatus.java`

Add new enum values: `PAUSED`, `PENDING`, `ARCHIVING`, `FAILED`, `PURGED`.

#### `WorkspaceScanner.java`

- Add `@Inject WorklogService` dependency
- In `parseSlotFile()`: parse `## Status` section for `status: paused`
- After scanning all slots: call `WorklogService.slotStatus(root)` to
  get worklog states, merge into scanner results by slot number
- Apply priority order: attic > worklog > .phase-a-complete > .slot status > default

The merge logic:
```
for each scanned slot:
    if in attic → ARCHIVED (already handled)
    else if worklog says archiving → ARCHIVING
    else if worklog says failed → FAILED
    else if worklog says pending → PENDING
    else if worklog says purged → PURGED
    else if .phase-a-complete exists → READY_TO_LAND
    else if .slot status == paused → PAUSED
    else → ACTIVE
```

#### `SlotInfo.java`

No changes needed — already carries `SlotStatus status` field.

### Frontend changes

#### `org-dashboard.ts`

- Add badge colors for new states in `STATUS_COLORS` map
- `PAUSED` → amber (#f59e0b)
- `PENDING` → grey (#6b7280)
- `ARCHIVING` → blue (#3b82f6)
- `FAILED` → red (#ef4444)
- `PURGED` → grey dim (#4b5563)

#### Filter support

Add a filter pill row for status filtering so users can quickly find
slots in a specific state (e.g., show only ARCHIVING to identify cleanup
targets).

### Testing

- **`WorkspaceScannerTest`** — extend existing test to verify:
  - `.slot` with `## Status\nstatus: paused` → PAUSED
  - `.slot` without status section → ACTIVE (default)
  - Worklog state override: slot is ACTIVE on filesystem but `archiving` in worklog → ARCHIVING
  - Worklog unavailable → fallback to filesystem-only status
  - Priority: attic location beats worklog state
- **Frontend** — verify new badge colors render correctly

## Known limitations

**Worklog broadcast scope:** The `workspace:worklog` SSE topic (from #70)
fires globally when `~/.hortora/worklog.db` changes — there's no way to
know which workspace or slot was affected without querying. This means
panels for unrelated workspaces may re-fetch unnecessarily. The re-fetch
is cheap (local SQLite) and the write frequency is low, so this is
acceptable for now. A future optimisation could add workspace-scoped
worklog events.

## References

- `SlotStatus.java` — current 3-value enum
- `WorkspaceScanner.java:229-230` — current filesystem-only status logic
- `WorklogService.java:191-214` — existing `slotStatus()` query
- worklog.db `slots` table schema — `state` column values
- Issue #69 — parse active/paused from .slot files
- Issue #74 — add ARCHIVING state for landed-but-not-archived slots
