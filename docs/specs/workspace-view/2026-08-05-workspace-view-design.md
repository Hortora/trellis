# Workspace View — Frames, Tabs, Keyboard Nav, Organisers, Persistence

**Issue:** Hortora/trellis#28
**Date:** 2026-08-05
**Status:** Approved

## Problem

Trellis currently shows one panel at a time via a dock-bar workbench. For a
power user managing ~30 repos across CaseHub with 5+ logical groupings of 7-8
related repos, there is no way to see and interact with multiple terminals
simultaneously. Users position overlapping groups of terminals so they can type
on one while waiting for another to finish, then switch — the current single-
panel model forces linear navigation.

## Solution

A workspace view within the existing dock-bar workbench that supports
free-form floating frames with tabbed terminals, persistent layouts, keyboard-
driven navigation, and organiser presets.

**Library:** Dockview (v7+, vanilla TypeScript, MIT, zero dependencies). Proven
with xterm.js via the Cate IDE project. If Dockview's floating mode fights the
free-positioning model, we fork and adjust — the MIT license and clean TS
codebase make this viable.

## Terminology

| Level | Term | What it is |
|-------|------|-----------|
| Outer shell | **Workbench** | Dock-bar + active panel (`trellis-workbench`) |
| Dock-bar destination | **Panel** | Garden, Artifacts, Protocols, Memory, Workspace, etc. |
| Floating rectangle with tabs | **Frame** | Positioned within the workspace panel, contains tabs |
| Terminal view within a frame | **Tab** | References a repo or slot terminal |
| Saved named tab collection | **Group** | Reusable set of tabs (e.g. "Engine Repos") |
| Persisted arrangement | **Layout** | All shells, frames, tabs, positions |

Workbench → Panel (workspace) → Frames → Tabs. Four levels, no overloading.

Implementation note: Dockview's group type is `DockviewGroupPanel`, not
`Group`, so there is no import collision with the spec's `Group` type.

## §1 Data Model

Groups, layout, and keymap are stored under separate electron-store keys
(see §6) to isolate save triggers and failure domains.

```typescript
// --- Storage shapes (one electron-store key each) ---

// groups.{workspacePath} — saved on explicit user action only
interface PersistedGroups {
  groups: Group[];
}

// layout.{workspacePath} — saved on every layout change, debounced 1s
interface PersistedLayout {
  windows: ShellLayout[];
}

// keymap.{workspacePath} — saved on user keymap change
interface PersistedKeymap {
  overrides: KeymapOverrides;
}

// --- Domain types ---

interface Group {
  id: string;
  name: string;
  tabs: TabRef[];
}

interface TabRef {
  terminalName: string;
  type: 'repo' | 'slot';
}

interface ShellLayout {
  id: string;
  bounds: { x: number; y: number; width: number; height: number };
  isMain: boolean;
  frames: FrameLayout[];
  lastActiveFrameId?: string;
}

interface FrameLayout {
  id: string;
  groupId?: string;
  order: number;
  position: { x: number; y: number };
  size: { width: number; height: number };
  zIndex: number;
  pinned: boolean;
  tabs: TabRef[];
  activeTabIndex: number;
}

interface KeymapOverrides {
  [action: string]: string;
}
```

- A frame *may* reference a group (`groupId`) — meaning it was opened from
  that group. Or it may be ad-hoc (no `groupId`).
- Groups are the persistence unit for stable groupings. Frames are the
  persistence unit for runtime positions.
- `TabRef.terminalName` is the key into the existing `TerminalRegistry` —
  all metadata (repo info, agent state, working dir) is looked up from there,
  not duplicated.
- `pinned` means always-on-top AND position-locked.
- `order` is assigned at frame creation (max existing order + 1) and is
  stable across repositioning. Used by `Cmd+Opt+1`..`Cmd+Opt+9` and cycle
  navigation (see §5).
- `TabRef` is used in both `Group.tabs` and `FrameLayout.tabs` — the same
  shape, same semantics, one type.

## §2 Frame Manager (Dockview Integration)

### Component: `trellis-workspace-view`

A new Lit component registered as the workspace panel in the dock-bar. Hosts
the Dockview instance and manages the frame lifecycle.

`trellis-org-dashboard` moves to its own dock-bar panel ("Dashboard") and
retains all existing rendering: repo cards, slot status, epic progress,
paused branches. The workspace panel is for terminal work; the dashboard
panel is for organisational overview. The workbench `DOCK_PANELS` array
gains a `dashboard` entry and the `PANELS` map rebinds the `workspace` key
to `trellis-workspace-view`.

```
┌──────────────────────────────────────────────────────────┐
│ dock │                                                   │
│ bar  │  ┌─────────────────┐  ┌──────────────────┐       │
│      │  │ Engine Repos  × │  │ Frontend Repos × │       │
│      │  │ [eng][led][idx] │  │ [web][dash][ui]  │       │
│      │  │                 │  │                  │       │
│      │  │  terminal       │  │  terminal        │       │
│      │  │  content        │  │  content         │       │
│      │  │                 │  │                  │       │
│      │  └─────────────────┘  │                  │       │
│      │                       └──────────────────┘       │
│      │        ┌──────────────────┐                      │
│      │        │ Infra          × │                      │
│      │        │ [ci][deploy]     │                      │
│      │        │  terminal        │                      │
│      │        └──────────────────┘                      │
└──────────────────────────────────────────────────────────┘
```

### Dockview Configuration

- All groups are floating — no docked panels.
- Disable Dockview's built-in snap/dock behaviour for dock targets; keep
  tab-level drag within/between groups enabled.
- Each Dockview group = one frame. Each panel within a group = one tab.
- `onDidLayoutChange` triggers persistence saves (debounced at 1s with a
  5s max-wait — a save fires at least every 5 seconds regardless of event
  frequency, preventing starvation under continuous layout changes).

### Drag / Resize

- Use Dockview's floating group drag if it supports free-positioning. If not,
  overlay pointer-based drag on the group header.
- Move via `transform: translate()` — GPU-composited, no layout thrash.
- `setPointerCapture()` for reliable tracking during fast movement.
- During drag: `pointer-events: none` on all xterm containers,
  `will-change: transform` on dragged frame.
- On drag end: remove `will-change`, debounce `fit()` on terminals after
  150ms.

### Z-Order

- Flat sibling structure with `isolation: isolate` on parent container.
- Separate z-counters per tier: `normalMaxZ` (starts at 1) and
  `pinnedMaxZ` (starts at 1).
- Click anywhere in a normal frame → `z-index = ++normalMaxZ`.
- Click anywhere in a pinned frame → `z-index = 10000 + ++pinnedMaxZ`.
- **Compaction:** When either counter exceeds 5000, compact that tier —
  reassign z-indices 1..N (normal) or 10001..10000+N (pinned) preserving
  relative order, reset the counter to N. Compaction runs synchronously
  on the triggering click; ordering is never stale.
- **Invariant:** Normal frame z-indices stay in [1, 9999]. Pinned frame
  z-indices stay in [10001, 20000]. Tiers never collide.
- On persistence save: z-indices are normalized to sequential integers
  (1, 2, ..., N within each tier) preserving relative order. Prevents
  unbounded growth across sessions.

### Frame Chrome

Each frame has a thin title bar:
- Group name (editable inline) or "Untitled" for ad-hoc frames
- Tab strip (Dockview's native tab bar)
- Pin toggle (📌), detach button (⎋), close button (×)
- Draggable from the title bar area above the tabs

## §3 Terminal Renderer Lifecycle

With 30+ repos, the WebGL context limit (16 in Chrome/Electron, raisable to 32
with `--max-active-webgl-contexts` flag) is the hard architectural constraint.

### Three Tiers

| Tier | Condition | Renderer | Cost |
|------|-----------|----------|------|
| Active | Focused tab in focused frame | WebGL | Full GPU acceleration |
| Visible | Active tab in non-focused frames | Canvas | CPU-rendered, lighter |
| Hidden | Non-active tabs (background tabs within a frame) | None | Renderer disposed |

### Lifecycle Transitions

- Tab gains focus → promote to WebGL renderer, `fit()` after 150ms
- Tab loses focus but frame visible → downgrade to Canvas renderer
- Tab becomes hidden → dispose renderer entirely
- Tab becomes visible again → create Canvas renderer, restore from buffer

### WebGL Context Budget

All BrowserWindows share one GPU process, so the 16-context limit is global.
Each window's renderer process tracks its own local count but coordinates
through the main process via IPC:

- `webgl:acquire` — window requests a WebGL slot before promoting a terminal.
  Main process grants if total < 16; denies otherwise (terminal stays Canvas).
- `webgl:release` — window notifies when a WebGL renderer is disposed or
  demoted.
- `webgl:demote` — main process sends to the window holding the least-recently-
  focused WebGL context when a higher-priority window needs a slot. Target
  window demotes that terminal to Canvas and replies with `webgl:release`.

The main process maintains a global LRU list of `{windowId, terminalName,
lastFocusedAt}` entries to determine demotion order across windows.

### Ownership

`trellis-workspace-view` owns renderer lifecycle within its window. It
listens to Dockview's panel visibility and focus events and drives all
promote/demote/dispose transitions locally. Cross-window context budget
coordination is handled via the IPC protocol described above.

### Terminal vs Renderer Lifetime

The xterm.js `Terminal` instance is created when a tab is added to a frame
and destroyed only when the tab is permanently removed (closed or frame
destroyed). Renderer attachment is a sub-operation of the Terminal:

- `Terminal.open(container)` attaches a renderer to the DOM
- Changing renderer tier disposes the current renderer and optionally
  attaches a new one, but never disposes the `Terminal` itself
- The WebSocket data handler writes to the Terminal's buffer regardless
  of whether a renderer is attached

Dispose renderer ≠ dispose Terminal. The buffer survives all renderer
transitions, which is what makes the tab hover flyout (§4) and seamless
tab switching work.

### Connection Persistence

The WebSocket connection to tmux stays alive regardless of renderer tier.
Output continues buffering in xterm.js's terminal buffer even with no renderer.
When switching back to a hidden tab, full scrollback is present.

**One connection per tmux session:** `TerminalWebSocket` enforces that
only one active WebSocket connection exists per tmux session name. When
`onOpen` receives a connection for a session that already has an active
connection, it closes the previous connection (triggering its `cleanup()`)
before setting up the new pipe-pane. This prevents the pipe-pane takeover
problem where the old connection silently stops receiving data.

Implementation: `TerminalWebSocket` maintains a
`ConcurrentHashMap<String, WebSocketConnection> activeBySession`
(keyed by session name, not connection ID). On `onOpen`, call
`activeBySession.put(sessionName, connection)` — if the previous value
is non-null and not the same connection, close it. On `cleanup`, remove
the entry only if it still points to this connection (compare by
connection ID to avoid closing a newer connection's entry).

### FIFO Lifecycle

FIFO files at `/tmp/trellis-{connectionId}.pipe` are cleaned up in
`@OnClose` and `@OnError`. For abnormal termination (SIGKILL, OOM):

- **Startup sweep:** On sidecar startup, before `TerminalRegistry.bootstrap()`,
  sweep `/tmp/trellis-*.pipe` and remove all stale FIFOs. Any FIFO from a
  previous sidecar process is guaranteed stale — the sidecar is single-instance.
- **JVM shutdown hook:** Best-effort cleanup of all FIFOs tracked in
  `fifoPaths` for SIGTERM and normal shutdown. Not guaranteed for SIGKILL,
  hence the startup sweep.

### Visibility

Hidden terminals use `visibility: hidden` not `display: none` — this preserves
layout dimensions so `fit()` works correctly when the container becomes visible.

## §4 Tab Hover Flyout

Mouse hover on a tab label (300ms delay) shows a flyout with context pulled
from existing APIs:

```
┌──────────────────────────────────┐
│ engine                           │
│ ─────────────────────────────────│
│ Branch: feat/issue-42-auth       │
│ Path:   ~/casehub/engine         │
│ Slot:   3 (if slot terminal)     │
│ Issue:  #42 — Add OAuth2 flow    │
│ ─────────────────────────────────│
│ Agent:  ● RUNNING  (3m 22s)     │
│ Memory: 412 MB                   │
│ ─────────────────────────────────│
│ Last output:                     │
│ > ✅ All 47 tests passed         │
│ > Committing changes...          │
└──────────────────────────────────┘
```

### Data Sources (no new endpoints)

- Repo metadata (name, branch, path) → `GET /api/workspace/repo`
- Terminal/agent state (status, memory) → SSE `agent:state` (already subscribed)
- Issue title → parsed from terminal's `issue` field
- Last output → last 2-3 lines from xterm buffer (client-side read)

Dismiss on mouse leave or keyboard nav away. Styled as a dark panel matching
`casehub-dark` theme.

## §5 Keyboard Navigation

### Tab Navigation (within focused frame)

| Shortcut | Action |
|----------|--------|
| `Cmd+Shift+]` | Next tab |
| `Cmd+Shift+[` | Previous tab |
| `Cmd+1`..`Cmd+9` | Jump to tab N |

### Frame Navigation (within browser window)

| Shortcut | Action |
|----------|--------|
| `Cmd+Opt+]` | Next frame (creation order, wrapping) |
| `Cmd+Opt+[` | Previous frame (creation order, wrapping) |
| `Cmd+Opt+1`..`Cmd+Opt+9` | Jump to frame N (by creation order) |
| `Cmd+Opt+Arrow` | Spatial — focus nearest frame in that direction |

Frame order is determined by the `order` field in `FrameLayout`, assigned
at creation time (max existing order + 1). Order is stable across drag,
resize, and focus changes.

**Spatial navigation algorithm (`Cmd+Opt+Arrow`):**

1. Compute the center point of the current frame.
2. Filter candidate frames to those whose center lies in the directional
   half-plane relative to the current frame's center (e.g., for Right:
   `candidate.centerX > current.centerX`).
3. Among candidates, pick the one with shortest Euclidean distance
   (center-to-center).
4. **Tie-breaking:** prefer the candidate closer to the primary axis
   (e.g., for Right: smaller `|deltaY|`).
5. If no candidates exist in the half-plane (edge of layout), do nothing
   (no wrapping — spatial navigation is inherently non-cyclic).

Note: `Ctrl+[` produces ESC (ASCII 27) — essential for vim, tmux prefix, and
any program reading escape sequences. All frame/window navigation uses
`Cmd`-based modifiers so shortcuts are intercepted at the app level before
reaching xterm.

### Cross-Window Navigation

| Shortcut | Action |
|----------|--------|
| `Cmd+Ctrl+]` | Next browser window |
| `Cmd+Ctrl+[` | Previous browser window |

### Global Shortcuts

| Shortcut | Action |
|----------|--------|
| `Cmd+N` | New frame (group picker) |
| `Cmd+T` | New tab in focused frame (repo/slot picker) |
| `Cmd+W` | Close active tab (close frame if last tab) |
| `Cmd+Shift+W` | Close frame |
| `Cmd+Shift+S` | Save focused frame as group |
| `Cmd+Shift+Backspace` | Delete group (when focused frame has `groupId`) |
| `Cmd+Shift+P` | Pin/unpin focused frame |
| `Cmd+Shift+D` | Detach focused frame |
| `Cmd+Shift+L` | Organiser preset picker |

### Implementation

- `Cmd+N`, `Cmd+T`, `Cmd+W` are registered as Electron application menu
  accelerators in `main.js`. This overrides the default Chromium accelerators
  (new window, new tab, close window). Menu item actions dispatch IPC to the
  focused window's workspace panel handler.
- All other shortcuts are handled via a global `keydown` listener on the
  workspace panel. Registered when workspace panel is active, removed when
  switching to another dock-bar panel.
- `attachCustomKeyEventHandler()` on every xterm.js instance — intercepts app
  shortcuts (returns `false`), passes everything else to xterm (returns `true`).
- Keymap stored in `PersistedKeymap.overrides` for user customisation (see §1).

### Focus Model

- One frame is focused at a time (subtle border accent).
- Within the focused frame, one tab is active with terminal keyboard focus.
- Frame navigation shortcuts immediately focus the target frame's active
  terminal — no extra keystroke to start typing.

## §6 Groups and Persistence

### Groups

Named tab collections for stable repo groupings.

**Lifecycle:**
- **Save from frame:** Right-click title → "Save as Group" (or `Cmd+Shift+S`).
  Captures current tabs as a named group.
- **Open group:** `Cmd+N` → picker shows saved groups + "Empty frame."
  Creates a frame populated with those tabs.
- **Update group:** Right-click title → "Update Group" (only if frame has a
  `groupId`). Syncs group definition to current tabs.
- **Divergence:** Adding ad-hoc tabs to a group-based frame doesn't change the
  group. The group stays clean until explicitly updated.
- **Template semantics:** Groups are templates, not live bindings. The
  `groupId` on a frame is provenance metadata recording which group it was
  opened from — not a live subscription. Changes to a group definition do
  not propagate to existing frames opened from that group. Each frame's
  `FrameLayout.tabs` is authoritative for that frame's content.
- **Delete group:** Right-click title → "Delete Group" (or `Cmd+Shift+Backspace`
  when a group-based frame is focused). Removes the group definition from
  `PersistedGroups`. The frame remains open but loses its `groupId` — it
  becomes ad-hoc.

### Persistence

Three concerns persisted via `LayoutStore` (electron-store), each under its
own key, scoped by workspace path:

1. **Groups** (`groups.{workspacePath}`) — saved on explicit user action
   (save/update group). Never written by layout auto-save.
2. **Layout** (`layout.{workspacePath}`) — saved on every layout change,
   debounced at 1s with 5s max-wait (see §2). Contains
   `{ windows: ShellLayout[] }`.
3. **Keymap** (`keymap.{workspacePath}`) — saved on user keymap change.

Separate keys ensure that frequent layout saves cannot corrupt group data
and that a failed layout save does not affect groups or keymap.

The existing `LayoutStore.save()` validation (`Array.isArray(layout.windows)`)
is superseded. The LayoutStore evolves to provide typed methods:
`saveGroups`/`loadGroups`, `saveLayout`/`loadLayout`, `saveKeymap`/`loadKeymap`.

### Workspace Path Persistence

`LayoutStore` persists `lastWorkspacePath` as a top-level electron-store key
(not workspace-scoped). Updated whenever a workspace is scanned from the
Dashboard panel or a layout save fires. This provides the workspace root at
startup without requiring user input.

### Shutdown Save Protocol

The existing `before-quit` handler calls `wm.closeAll()` before any layout
save — destroying window tracking and renderer processes before the
debounced frontend save can fire. The shutdown sequence must be:

1. `before-quit` fires, calls `event.preventDefault()`.
2. Main process sends `layout:flush` IPC to every non-destroyed
   BrowserWindow.
3. Each window's frontend immediately serializes Dockview state and calls
   `trellis.saveLayout(workspacePath, layout)` — bypassing the debounce.
4. Main process awaits all `layout:flush` responses (or 2s timeout —
   if a window is unresponsive, the last debounced save is the fallback).
5. `wm.closeAll()` → `server.killServer()` → `app.exit(0)`.

The `layout:flush` handler in the frontend calls the same serialization
path as the debounced auto-save — no separate code path, just immediate
invocation.

### Restore Sequence on Startup

1. Sidecar starts, bootstraps `TerminalRegistry` from surviving tmux sessions.
2. **Readiness gate:** The sidecar exposes `GET /api/health/ready` that
   returns 200 only after `TerminalRegistry.bootstrap()` completes. The
   existing `GET /api/health` returns 200 as soon as the HTTP layer is up
   (used by the health monitor for crash detection). The Electron shell
   polls `/api/health/ready` before opening the main window — this
   prevents the frontend from connecting WebSockets to sessions that
   haven't been indexed yet.
3. Electron reads `lastWorkspacePath` from `LayoutStore`.
   - Found → proceed to step 4.
   - Not found → workspace panel opens in empty state (no frames). User
     switches to Dashboard panel to scan a workspace root.
4. Electron loads `PersistedLayout`, `PersistedGroups`, and `PersistedKeymap`
   from their respective `LayoutStore` keys for the workspace path.
5. For each `ShellLayout` in the layout: create BrowserWindow with saved
   bounds, **clamped to available display area** (see below).
6. Frontend creates Dockview floating groups matching `FrameLayout` positions,
   ordered by `order` field.
7. For each tab: look up `terminalName` in `TerminalRegistry`.
   - Found → connect WebSocket, attach renderer per tier rules (§3).
   - Not found → show "Disconnected" state with reconnect/restart button.
   As defence in depth, WebSocket connection uses retry with exponential
   backoff (100ms, 200ms, 400ms, up to 3 retries) before showing
   "Disconnected" — handles any residual race after readiness gating.
8. Focus the frame and tab identified by `ShellLayout.lastActiveFrameId` and
   `FrameLayout.activeTabIndex`.

**Display bounds validation (step 5):** During restore, all
`ShellLayout.bounds` and `FrameLayout.position` are validated against
`screen.getAllDisplays()`. For each `ShellLayout`, if its saved bounds
don't intersect any current display, the window is repositioned to the
primary display at a default offset. For each `FrameLayout`, positions
beyond the owning window's content area are clamped to fit. This handles
the common case of undocking a monitor between sessions.

### Folder Rescan Resilience

Workspace rescans do not touch the layout. Tabs referencing removed repos show
"Repo not found." New repos don't auto-appear — added explicitly. Prevents
filesystem changes from disrupting carefully arranged layouts.

## §7 Organisers

One-shot layout functions. They rearrange frames, then get out of the way.

### Built-in Presets

| Preset | Behaviour |
|--------|-----------|
| Side by side | Left-to-right, equal width, full height |
| Stacked | Top-to-bottom, full width, equal height |
| Grid | 1→fills area, 2→1×2, 3-4→2×2, 5-6→2×3, 7-9→3×3, 10+→`ceil(sqrt(n))`cols × `ceil(n/cols)`rows |
| Main + sidebar | Active frame 2/3 left, others stacked right |
| Focus | Active frame fills area, others minimised to bottom strip |

Each preset is a pure function:
`(frames: FrameLayout[], canvasSize: {width, height}) → FrameLayout[]`.
No ongoing constraint enforcement — drag freely immediately after.

### Access

- `Cmd+Shift+L` opens preset picker, then `1`–`5` for quick selection.

### Snap (opt-in only)

- Hold `Shift` during drag → 10px magnetic edge snapping.
- Release `Shift` → pure free movement.
- Snap is a drag modifier, not a mode toggle. No global state to forget.

### Pinned Frames

Excluded from organiser presets. The organiser arranges unpinned frames around
pinned ones.

## §8 Detach / Reattach

### Detach

- `Cmd+Shift+D` or click detach button (⎋).
- Source window serializes the frame's `FrameLayout` (tabs, active tab,
  position, size, groupId, order).
- Electron main process creates a new BrowserWindow via `window:create` IPC,
  sized to the frame.
- Frame state is sent to the new window via
  `webContents.send('frame:init', frameLayout)`.
- New window loads workbench at `#workspace?root=...`.
- The new window's `trellis-workspace-view` listens for `frame:init` and
  creates a Dockview floating group from the received `FrameLayout`.
- Original frame's Dockview group is removed from the source window.
- Both windows persist independently as `ShellLayout` entries.

### Reattach

- Right-click title → "Attach to main window."
- Source window serializes the frame's `FrameLayout`.
- Sends to main process via `trellis.sendFrameToWindow(targetWindowId, frameLayout)`.
- Main process forwards to target window via
  `webContents.send('frame:receive', frameLayout)`.
- Target window's `trellis-workspace-view` creates a Dockview floating group.
- Source window's Dockview group is removed; window closes if last frame.
- Race safety: `frame:receive` handler is idempotent (uses frame ID to
  deduplicate). If the source window closes before the target reconstructs,
  the persisted layout already contains the frame — restore picks it up.

### Panel Switching and DOM Retention

The workbench currently renders only the active panel and removes inactive
panels from the DOM (via Lit's `render()` replacing `.panel-area`
content). Dockview's state is DOM-resident — removing and re-inserting
its container element breaks floating group tracking.

The workbench must be modified to keep all cached panel elements in the
DOM simultaneously, hiding inactive panels with `display: none`.
The `render()` method renders all entries from `_panelCache` and
applies `display: none` to all except the active panel. This preserves
Dockview's internal DOM state, WebSocket connections, and terminal
buffers across panel switches.

Note: `display: none` (not `visibility: hidden`) is correct here because
the entire panel is inactive — we want to reclaim layout space and avoid
Dockview processing visibility events for a hidden container. The
`visibility: hidden` rule in §3 applies to individual terminal containers
within the active workspace panel, where layout dimensions must be
preserved for `fit()`.

### Detached Windows as Full Citizens

Every BrowserWindow loads the same `trellis-workbench` with dock-bar and
panel container. This is intentional:

- Own dock-bar (can switch to garden, artifacts, etc.) — a detached frame
  on a second monitor should not require switching back for non-terminal work
- Can host 1..N frames — drag additional frames to a detached window
- Own keyboard navigation scope (frame cycling, spatial nav)
- Participates in layout persistence as another `ShellLayout` entry
- All windows share the same sidecar — panels hit the same REST/SSE
  endpoints with independent UI state per window (existing architecture)

### Cross-Window Navigation

- `Cmd+Ctrl+]` / `Cmd+Ctrl+[` cycles between browser windows via
  Electron's `win.focus()`.

## §9 Backend Changes

### No New Endpoints

| Data needed | Existing source |
|-------------|----------------|
| Repo list + metadata | `GET /api/workspace?root=...` |
| Individual repo detail | `GET /api/workspace/repo?root=...&repo=...` |
| Terminal list + agent state | `GET /api/terminals` |
| Terminal I/O | `WebSocket /ws/terminal/{id}/{cols}/{rows}` |
| Agent state changes | SSE `agent:state` via `/api/push` |
| Eviction candidates | SSE `agent:eviction` via `/api/push` |
| Workspace changes | SSE `workspace:repos`, `workspace:slots` via `/api/push` |

### Terminal Auto-Creation

When a tab is added for a repo with no terminal session:

1. Frontend calls `POST /api/terminals` with
   `{ name: "repo-{repoName}", workingDir: "{repo.path}", repo: "{repoName}" }`
2. On `201 Created`: new tmux session without agent — user gets a shell prompt
3. On `409 Conflict`: terminal already exists (e.g., from a surviving tmux
   session recovered at bootstrap). Frontend connects to the existing terminal
   via its WebSocket — no error shown, no suffix.
4. Starting an agent remains an explicit action

**Atomicity:** `TerminalRegistry.createSession()` must be atomic with
respect to the name reservation. The current `get() → create()` sequence
in `TerminalResource` has a TOCTOU race when two requests arrive for the
same name. Fix: `TerminalRegistry.createSession()` uses
`sessions.putIfAbsent(name, placeholder)` as the atomic gate. If
`putIfAbsent` returns non-null, the name is taken — return immediately
without calling tmux. If tmux session creation fails after reservation,
`sessions.remove(name)` rolls back the reservation.

Slot terminals already exist when a slot is active. No auto-creation needed.

## §10 Testing Strategy

### Unit Tests (Java)

No new Java classes. Existing terminal and agent tests cover consumed endpoints.

### Frontend Component Tests

| Component | Test |
|-----------|------|
| Layout model | Serialization round-trip preserves all fields |
| Groups | Save, open, update, divergence detection |
| Organiser presets | Computed positions: no overlap (grid/stack), correct proportions (main+sidebar) |
| Keyboard dispatch | Interceptor returns `false` for app shortcuts, `true` for terminal input |
| Renderer lifecycle | Tier transitions, WebGL context count stays within budget |
| Tab hover flyout | Data assembly from repo metadata + agent state + terminal buffer |

### Electron Tests

| Area | Test |
|------|------|
| LayoutStore | Save/load/clear round-trip. Debounced saves don't lose data. |
| WindowManager | Detach creates BrowserWindow with correct bounds. Reattach closes source. |
| Restore sequence | Correct window count, frame positions, terminal name references. |

### Manual Playwright / Browser Verification

- Frame drag/resize persists across restart
- Tab add/switch/close with terminal connection
- Full keyboard nav suite (tabs, frames, spatial, numbered, cross-window)
- Each organiser preset arranges correctly, then free drag works
- Pin: stays on top + position-locked
- Detach to new window, reattach
- Hover flyout: correct metadata, agent state, last output
- 16+ terminals: renderer tier transitions
- Full quit and restart: layout + terminal reconnection

## §11 Scope Boundary

### In scope
- Frame/tab model within workspace panel (Dockview floating groups)
- Groups — saved named tab collections
- Keyboard navigation (tab, frame, cross-window)
- Organisers — preset layouts + opt-in snap
- Pinning — always-on-top + position-locked
- Detach/reattach frames to/from separate BrowserWindows
- Tab hover flyout with repo/agent/terminal metadata
- Terminal renderer lifecycle (WebGL/Canvas/None tiering)
- Layout persistence via LayoutStore (survives restart)
- Terminal auto-creation for repo tabs
- Terminal reconnection to surviving tmux sessions

### Out of scope
- Agent control plane / MCP tools (#27)
- Custom user-defined organiser presets
- Drag frame between browser windows (stretch goal)
- Workspace panel extraction to casehub-packages (after stabilisation)
