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

## §1 Data Model

```typescript
interface PersistedWorkspace {
  groups: Group[];
  layout: ShellLayout[];
  keymap?: KeymapOverrides;
}

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
}

interface FrameLayout {
  id: string;
  groupId?: string;
  position: { x: number; y: number };
  size: { width: number; height: number };
  zIndex: number;
  pinned: boolean;
  tabs: TabState[];
  activeTabIndex: number;
}

interface TabState {
  terminalName: string;
  type: 'repo' | 'slot';
}

interface KeymapOverrides {
  [action: string]: string;
}
```

- A frame *may* reference a group (`groupId`) — meaning it was opened from
  that group. Or it may be ad-hoc (no `groupId`).
- Groups are the persistence unit for stable groupings. Frames are the
  persistence unit for runtime positions.
- `TabState.terminalName` is the key into the existing `TerminalRegistry` —
  all metadata (repo info, agent state, working dir) is looked up from there,
  not duplicated.
- `pinned` means always-on-top AND position-locked.

## §2 Frame Manager (Dockview Integration)

### Component: `trellis-workspace-view`

A new Lit component registered as the workspace panel in the dock-bar. Hosts
the Dockview instance and manages the frame lifecycle. Replaces
`trellis-org-dashboard`.

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
- `onDidLayoutChange` triggers persistence saves (debounced at 1 second).

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
- Click anywhere in a frame → `z-index = ++maxZ`.
- Two z-tiers: normal (0–9999) and pinned (10000+). Pinned frames always
  render above normal frames.

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
| Hidden | Non-active tabs, fully occluded frames | None | Renderer disposed |

### Lifecycle Transitions

- Tab gains focus → promote to WebGL renderer, `fit()` after 150ms
- Tab loses focus but frame visible → downgrade to Canvas renderer
- Tab becomes hidden → dispose renderer entirely
- Tab becomes visible again → create Canvas renderer, restore from buffer

### WebGL Context Budget

Track active contexts globally. If promoting to WebGL would exceed 16, demote
the least-recently-focused WebGL terminal to Canvas first.

### Connection Persistence

The WebSocket connection to tmux stays alive regardless of renderer tier.
Output continues buffering in xterm.js's terminal buffer even with no renderer.
When switching back to a hidden tab, full scrollback is present.

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
| `Ctrl+]` | Next frame (cycle order) |
| `Ctrl+[` | Previous frame |
| `Ctrl+1`..`Ctrl+9` | Jump to frame N |
| `Ctrl+Arrow` | Spatial — focus nearest frame in that direction |

### Cross-Window Navigation

| Shortcut | Action |
|----------|--------|
| `Ctrl+Shift+]` | Next browser window |
| `Ctrl+Shift+[` | Previous browser window |

### Global Shortcuts

| Shortcut | Action |
|----------|--------|
| `Cmd+N` | New frame (group picker) |
| `Cmd+T` | New tab in focused frame (repo/slot picker) |
| `Cmd+W` | Close active tab (close frame if last tab) |
| `Cmd+Shift+W` | Close frame |
| `Cmd+Shift+P` | Pin/unpin focused frame |
| `Cmd+Shift+D` | Detach focused frame |
| `Cmd+Shift+L` | Organiser preset picker |

### Implementation

- Global `keydown` listener on the workspace panel. Registered when workspace
  panel is active, removed when switching to another dock-bar panel.
- `attachCustomKeyEventHandler()` on every xterm.js instance — intercepts app
  shortcuts (returns `false`), passes everything else to xterm (returns `true`).
- Keymap stored in `Layout.keymap` for user customisation.

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

### Persistence

Two things persisted via `LayoutStore` (electron-store), keyed by workspace
path:

1. **Groups** — saved on explicit user action (save/update group).
2. **Layout** — saved on every layout change, debounced at 1 second.

### Restore Sequence on Startup

1. Sidecar starts, bootstraps `TerminalRegistry` from surviving tmux sessions.
2. Electron loads `PersistedWorkspace` from `LayoutStore` for the workspace.
3. For each `ShellLayout`: create BrowserWindow with saved bounds.
4. Frontend creates Dockview floating groups matching `FrameLayout` positions.
5. For each tab: look up `terminalName` in `TerminalRegistry`.
   - Found → connect WebSocket, attach renderer per tier rules (§3).
   - Not found → show "Disconnected" state with reconnect/restart button.
6. Focus the frame and tab that were last active.

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
| Grid | 2→1×2, 3-4→2×2, 5-6→2×3, 7-9→3×3 |
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
- Frame state captured (tabs, active tab, size).
- New BrowserWindow created via `window:create` IPC, sized to frame.
- Loads workbench at `#workspace?detached=frameId&root=...`.
- Detached window hosts a full workspace panel — can contain 1..N frames.
- Original frame removed from source window.

### Reattach

- Right-click title → "Attach to main window."
- Frame state sent to target window via IPC.
- Frame created in target window's workspace panel.
- Source window closes if it was the last frame.

### Detached Windows as Full Citizens

- Own dock-bar (can switch to garden, artifacts, etc.)
- Can host 1..N frames
- Own keyboard navigation scope
- Participates in layout persistence as another `ShellLayout` entry

### Cross-Window Navigation

- `Ctrl+Shift+]` / `Ctrl+Shift+[` cycles between browser windows via
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
2. Creates tmux session without starting an agent — user gets a shell prompt
3. Starting an agent remains an explicit action

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
