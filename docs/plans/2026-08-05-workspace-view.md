# Workspace View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #28 — Workspace view — frames, tabs, keyboard nav, organisers, persistence
**Issue group:** #28

**Goal:** Build a multi-frame terminal workspace within the existing dock-bar workbench, supporting free-form floating frames with tabbed terminals, persistent layouts, keyboard navigation, organisers, and detach/reattach to separate Electron windows.

**Architecture:** Dockview (v7+, vanilla TS) manages floating groups within a new `trellis-workspace-view` Lit component. The existing `trellis-org-dashboard` moves to its own "Dashboard" dock-bar panel. Electron's main process coordinates layout persistence, WebGL context budget, and cross-window IPC. Backend changes are minimal: a readiness endpoint and terminal creation atomicity fix.

**Tech Stack:** Dockview 7.x, Lit 3.x, xterm.js (via pages-component-terminal), electron-store, Electron IPC

## Global Constraints

- Java 21, Quarkus 3.x, esbuild, Yarn Berry v4
- Dockview: vanilla TS core (no React wrapper), MIT license
- WebGL context limit: 16 per GPU process (Electron flag can raise to 32)
- Pre-release: breaking changes cost nothing — fix the design, never protect callers
- IntelliJ MCP mandatory for all .java/.ts/.tsx file operations
- TDD: failing test first, then implementation

---

### Task 1: Backend — Readiness endpoint, terminal creation atomicity, session takeover, FIFO cleanup

**Files:**
- Modify: `sidecar/src/main/java/io/hortora/trellis/HealthResource.java`
- Modify: `sidecar/src/main/java/io/hortora/trellis/terminal/TerminalRegistry.java`
- Modify: `sidecar/src/main/java/io/hortora/trellis/terminal/TerminalWebSocket.java`
- Modify: `sidecar/src/main/java/io/hortora/trellis/terminal/TerminalResource.java`
- Test: `sidecar/src/test/java/io/hortora/trellis/HealthResourceTest.java`
- Test: `sidecar/src/test/java/io/hortora/trellis/terminal/TerminalRegistryTest.java`
- Test: `sidecar/src/test/java/io/hortora/trellis/terminal/TerminalWebSocketTest.java`

**Interfaces:**
- Produces: `GET /api/health/ready` — returns 200 after bootstrap, 503 before
- Produces: `TerminalRegistry.createSession()` with `putIfAbsent` atomicity
- Produces: `TerminalWebSocket.activeBySession` map with close code 4001 for session takeover
- Produces: FIFO startup sweep in `TerminalWebSocket` or a `@Startup` observer

**Why this task first:** These are backend prerequisites that the frontend restore sequence (Task 11) depends on. The readiness endpoint gates window creation; session takeover prevents pipe-pane conflicts; atomic terminal creation prevents TOCTOU races on auto-create.

---

#### §1a Readiness endpoint

- [ ] **Step 1: Write failing test — `GET /api/health/ready` returns 503 before bootstrap**

```java
@QuarkusTest
class HealthResourceTest {
    @Test
    void readyEndpointReturns200() {
        given().when().get("/api/health/ready")
            .then().statusCode(200)
            .body("status", is("ready"));
    }
}
```

- [ ] **Step 2: Run test — verify it fails** (endpoint doesn't exist yet)

Run: `/opt/homebrew/bin/mvn -f sidecar/pom.xml test -pl . -Dtest=HealthResourceTest#readyEndpointReturns200`
Expected: 404

- [ ] **Step 3: Implement readiness endpoint**

Add `bootstrapComplete` field to `HealthResource` (injected from `TerminalRegistry` or set via CDI event after bootstrap). Add `@GET @Path("/ready")` method that returns 200 with `{"status":"ready"}` when bootstrap is complete, 503 with `{"status":"starting"}` otherwise.

`TerminalRegistry.bootstrap()` fires a CDI event on completion. `HealthResource` observes it and sets `bootstrapComplete = true`.

Use `ide_insert_member` for new methods on `HealthResource`.

- [ ] **Step 4: Run test — verify it passes**

Run: `/opt/homebrew/bin/mvn -f sidecar/pom.xml test -pl . -Dtest=HealthResourceTest`

- [ ] **Step 5: Verify with `ide_diagnostics`**

---

#### §1b Terminal creation atomicity

- [ ] **Step 6: Write failing test — concurrent createSession with same name**

```java
@Test
void createSessionRejectsConflictAtomically() {
    registry.createSession("test-dup", "/tmp", null, null, null);
    assertThrows(IllegalStateException.class, () ->
        registry.createSession("test-dup", "/tmp", null, null, null));
}
```

- [ ] **Step 7: Run test — verify it fails** (current impl may not throw cleanly)

- [ ] **Step 8: Fix `TerminalRegistry.createSession()`**

Replace `get() → tmux.createSession()` with:
1. `sessions.putIfAbsent(name, placeholder)` — if returns non-null, throw `IllegalStateException`
2. Call `tmux.createSession()` — if fails, `sessions.remove(name)` to rollback
3. `sessions.put(name, realTerminalInfo)` on success

Use `ide_replace_member` on `createSession`.

- [ ] **Step 9: Update `TerminalResource.create()` to return 409 on conflict**

Catch `IllegalStateException` from `createSession()` → return `Response.status(409).build()`.

- [ ] **Step 10: Run tests — verify all pass**

Run: `/opt/homebrew/bin/mvn -f sidecar/pom.xml test -pl . -Dtest=TerminalRegistryTest,TerminalResourceTest`

---

#### §1c Session takeover in TerminalWebSocket

- [ ] **Step 11: Write failing test — second connection closes first with code 4001**

Test that opening a WebSocket for a session name that already has an active connection closes the previous connection with close code 4001.

- [ ] **Step 12: Implement `activeBySession` map**

Add `ConcurrentHashMap<String, WebSocketConnection> activeBySession` to `TerminalWebSocket`.

In `onOpen`: `activeBySession.put(sessionName, connection)` — if previous value is non-null and different connection, close it with `CloseReason(4001, "session-takeover")`.

In `cleanup`: `activeBySession.remove(sessionName, connection)` (only removes if still this connection).

Use `ide_insert_member` for the field, `ide_replace_member` for modified `onOpen` and `cleanup`.

- [ ] **Step 13: Run tests**

---

#### §1d FIFO startup sweep

- [ ] **Step 14: Add startup sweep**

Create a `@Startup` CDI bean or add to existing startup path: sweep `/tmp/trellis-*.pipe` and delete all stale FIFOs before `TerminalRegistry.bootstrap()`.

- [ ] **Step 15: Add JVM shutdown hook for FIFO cleanup**

Best-effort cleanup of all FIFOs tracked in `TerminalWebSocket.fifoPaths` on shutdown.

- [ ] **Step 16: Commit**

```
feat(#28): readiness endpoint, terminal atomicity, session takeover, FIFO lifecycle

Refs #28
```

---

### Task 2: Electron — LayoutStore evolution and shutdown protocol

**Files:**
- Modify: `shell/layout-store.js`
- Modify: `shell/main.js`
- Modify: `shell/preload.js`
- Modify: `shell/window-manager.js`
- Test: `shell/test/layout-store.test.js` (create if not exists)

**Interfaces:**
- Consumes: nothing (standalone Electron changes)
- Produces: `LayoutStore.saveGroups(path, groups)`, `loadGroups(path)`, `saveLayout(path, layout)`, `loadLayout(path)`, `saveKeymap(path, keymap)`, `loadKeymap(path)`, `saveLastWorkspacePath(path)`, `loadLastWorkspacePath()`
- Produces: `layout:flush` IPC for shutdown save protocol
- Produces: `layout:window-save(windowId, shellLayout)` IPC for per-window layout saves
- Produces: Preload bridge: `trellis.saveWindowLayout(shellLayout)`, `trellis.onLayoutFlush(callback)`, `trellis.getLastWorkspacePath()`

**Why second:** The persistence layer is consumed by everything downstream. Frontend tasks need the IPC channels and preload API to be in place.

---

- [ ] **Step 1: Write tests for LayoutStore typed methods**

```javascript
test('saveGroups and loadGroups round-trip', async () => {
  const store = new LayoutStore();
  const groups = { groups: [{ id: 'g1', name: 'Engine', tabs: [] }] };
  await store.saveGroups('/test/path', groups);
  const loaded = await store.loadGroups('/test/path');
  expect(loaded).toEqual(groups);
});

test('saveLayout and loadLayout round-trip', async () => { ... });
test('saveKeymap and loadKeymap round-trip', async () => { ... });
test('lastWorkspacePath persists and loads', async () => { ... });
test('layout save does not corrupt groups', async () => { ... });
```

- [ ] **Step 2: Implement LayoutStore typed methods**

Replace the single `save`/`load`/`clear` with typed methods. Each uses a distinct electron-store key prefix:
- `groups.{path}` for groups
- `layout.{path}` for layout (windows array)
- `keymap.{path}` for keymap
- `lastWorkspacePath` for the top-level workspace path

Keep the old `save`/`load`/`clear` as deprecated no-ops (or remove — pre-release, breaking changes are free).

- [ ] **Step 3: Run tests — verify pass**

- [ ] **Step 4: Add layout save coordination to main.js**

Add `Map<number, ShellLayout>` (`_windowLayouts`) in main process. Register IPC handler:
```javascript
ipcMain.handle('layout:window-save', (_event, windowId, shellLayout) => {
  _windowLayouts.set(windowId, shellLayout);
  const composite = { windows: [..._windowLayouts.values()] };
  layoutStore.saveLayout(currentWorkspacePath, composite);
});
```

On BrowserWindow `closed`: remove entry from map, save.

- [ ] **Step 5: Add shutdown save protocol**

Modify `before-quit` handler:
1. `event.preventDefault()`
2. Send `layout:flush` to all non-destroyed windows via `webContents.send('layout:flush')`
3. Collect `layout:window-save` responses (with 2s timeout)
4. Write composite layout
5. `wm.closeAll()` → `server.killServer()` → `app.exit(0)`

- [ ] **Step 6: Update preload.js**

Add to `window.trellis`:
```javascript
saveWindowLayout: (shellLayout) => ipcRenderer.invoke('layout:window-save', /* windowId resolved in main */, shellLayout),
onLayoutFlush: (callback) => ipcRenderer.on('layout:flush', callback),
getLastWorkspacePath: () => ipcRenderer.invoke('layout:lastWorkspacePath'),
loadLayout: (path) => ipcRenderer.invoke('layout:load', path),
loadGroups: (path) => ipcRenderer.invoke('layout:loadGroups', path),
loadKeymap: (path) => ipcRenderer.invoke('layout:loadKeymap', path),
saveGroups: (path, groups) => ipcRenderer.invoke('layout:saveGroups', path, groups),
```

- [ ] **Step 7: Add save-inhibit flag for detach sequences**

`_saveInhibited = false` in main process. When inhibited, queue saves; on clear, flush.

- [ ] **Step 8: Commit**

```
feat(#28): LayoutStore typed methods, shutdown save protocol, layout IPC

Refs #28
```

---

### Task 3: Electron — WebGL context budget IPC and menu accelerators

**Files:**
- Modify: `shell/main.js`
- Modify: `shell/preload.js`
- Modify: `shell/window-manager.js`

**Interfaces:**
- Consumes: `WindowManager` from Task 2
- Produces: `webgl:acquire` / `webgl:release` / `webgl:demote` / `webgl:grant` IPC protocol
- Produces: `Cmd+N`, `Cmd+T`, `Cmd+W` as Electron menu accelerators dispatching to focused window
- Produces: `Cmd+Ctrl+]` / `Cmd+Ctrl+[` cross-window navigation
- Produces: Preload bridge: `trellis.webglAcquire()`, `trellis.webglRelease(terminalName)`, `trellis.onWebglDemote(callback)`, `trellis.onWebglGrant(callback)`

---

- [ ] **Step 1: Implement WebGL context budget in main process**

Global state in main.js:
```javascript
const webglSlots = { max: 16, active: new Map(), pendingQueue: [] };
```

IPC handlers:
- `webgl:acquire(terminalName)` → grants or queues
- `webgl:release(terminalName)` → frees slot, grants to highest-priority waiter
- On window close: release all slots held by that window, remove pending entries

- [ ] **Step 2: Add Electron menu accelerators**

Create application menu with `Cmd+N`, `Cmd+T`, `Cmd+W` accelerators. Menu item actions send IPC to focused window:
```javascript
{ label: 'New Frame', accelerator: 'CmdOrCtrl+N', click: () => focusedWindow?.webContents.send('shortcut:new-frame') }
```

- [ ] **Step 3: Add cross-window navigation IPC**

```javascript
ipcMain.handle('window:next', () => { /* cycle to next window */ });
ipcMain.handle('window:prev', () => { /* cycle to previous window */ });
```

Cycle order: creation order (from `_windows` Map insertion order).

- [ ] **Step 4: Update preload.js**

Add WebGL IPC methods and shortcut listeners to `window.trellis`.

- [ ] **Step 5: Commit**

```
feat(#28): WebGL context budget IPC, menu accelerators, cross-window nav

Refs #28
```

---

### Task 4: Frontend — Workbench DOM retention and dashboard panel

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workbench.ts` (lines 18-30 for PANELS/DOCK_PANELS, lines 209-224 for render)
- Create: `sidecar/src/main/webui/src/components/workspace-view.ts` (minimal shell — full implementation in Task 5)

**Interfaces:**
- Consumes: existing `trellis-workbench` component
- Produces: DOM retention on panel switch (all panels kept, inactive hidden with `display: none`)
- Produces: `dashboard` dock-bar entry pointing to `trellis-org-dashboard`
- Produces: `workspace` panel rebound to `trellis-workspace-view`
- Produces: `trellis-workspace-view` minimal Lit component (renders placeholder text, accepts `workspaceRoot` property)

**Why before Dockview:** Dockview's state is DOM-resident. If the workbench destroys the workspace panel on dock-bar switch, Dockview breaks. This fix must land first.

---

- [ ] **Step 1: Modify workbench PANELS map**

Add `dashboard` entry pointing to `trellis-org-dashboard`. Change `workspace` entry to point to `trellis-workspace-view`. Update `DOCK_PANELS` array to include `dashboard`.

Use `ide_replace_member` on the `PANELS` and `DOCK_PANELS` declarations.

- [ ] **Step 2: Modify workbench render() for DOM retention**

Change `render()` to render ALL entries from `_panelCache` simultaneously. Apply `display: none` to all except the active panel. This preserves Dockview's DOM state, WebSocket connections, and terminal buffers.

```typescript
render() {
  return html`
    <div class="dock-bar">
      ${DOCK_PANELS.map(id => { /* dock buttons */ })}
    </div>
    <div class="panel-area">
      ${[...this._panelCache.entries()].map(([id, el]) =>
        html`<div class="panel-slot" style="display:${id === this._activePanel ? 'contents' : 'none'}">${el}</div>`
      )}
    </div>
  `;
}
```

- [ ] **Step 3: Create minimal `trellis-workspace-view`**

```typescript
@customElement('trellis-workspace-view')
export class TrellisWorkspaceView extends LitElement {
  @property() workspaceRoot = '';

  render() {
    return html`<div class="workspace-container">Workspace view — ${this.workspaceRoot}</div>`;
  }
}
```

Register in `app.ts` imports.

- [ ] **Step 4: Verify — dev mode, dock-bar shows dashboard and workspace icons, switching preserves panel state**

Run: `/opt/homebrew/bin/mvn -f sidecar/pom.xml quarkus:dev`

- [ ] **Step 5: Commit**

```
feat(#28): workbench DOM retention, dashboard panel, workspace-view shell

Refs #28
```

---

### Task 5: Frontend — Dockview integration and frame manager

**Files:**
- Modify: `sidecar/src/main/webui/package.json` (add dockview dependency)
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts` (full implementation)
- Create: `sidecar/src/main/webui/src/components/workspace-view.css` (if needed, or inline styles)

**Interfaces:**
- Consumes: `trellis-workspace-view` shell from Task 4
- Consumes: `PersistedLayout`, `FrameLayout` types (defined inline in this task)
- Produces: Dockview floating groups as frames
- Produces: Frame chrome (title bar, pin/detach/close buttons, tab strip)
- Produces: Z-order management (per-tier counters with compaction)
- Produces: Tab uniqueness enforcement (`Set<string>` of active terminal names)
- Produces: Drag/resize with `pointer-events: none` on xterm containers during drag

**This is the largest task.** It establishes the core visual model.

---

- [ ] **Step 1: Add dockview to package.json**

```bash
cd sidecar/src/main/webui && yarn add dockview-core
```

The `dockview-core` package is the vanilla TS core without React bindings.

- [ ] **Step 2: Define TypeScript types**

In `workspace-view.ts` (or a separate `workspace-types.ts`), define:
- `Group`, `TabRef`, `FrameLayout`, `ShellLayout`, `PersistedLayout`, `PersistedGroups`, `PersistedKeymap`, `KeymapOverrides`

These match §1 of the spec exactly.

- [ ] **Step 3: Initialize Dockview in workspace-view**

In `connectedCallback` or `firstUpdated`:
1. Create Dockview container div
2. Initialize `DockviewComponent` with floating-only configuration
3. Listen to `onDidLayoutChange` for persistence (debounce 1s, maxWait 5s)
4. Listen to `onDidActivePanelChange` for focus tracking

- [ ] **Step 4: Implement frame creation**

Method `_createFrame(tabs: TabRef[], groupId?: string, name?: string)`:
1. Create a Dockview floating group at a default position
2. For each tab, add a Dockview panel to the group
3. Track terminal names in `_activeTerminals: Set<string>`
4. Apply frame chrome (title bar with name, pin/detach/close buttons)

- [ ] **Step 5: Implement Z-order management**

- `_normalMaxZ = 1`, `_pinnedMaxZ = 1`
- On frame click: update z-index per tier
- Compaction when counter exceeds 5000
- Normalize on persistence save

- [ ] **Step 6: Implement tab uniqueness**

- `_activeTerminals: Set<string>` maintained on add/remove
- Skip duplicates on group open
- Focus existing tab instead of creating duplicate

- [ ] **Step 7: Implement drag/resize handling**

- During drag: `pointer-events: none` on all `pages-component-terminal` elements
- `will-change: transform` on dragged frame, removed on drag end
- Debounced `fit()` on terminals after 150ms on drag end

- [ ] **Step 8: Verify — create frames with placeholder content, drag/resize/z-order works**

- [ ] **Step 9: Commit**

```
feat(#28): Dockview frame manager with z-order, tab uniqueness, drag/resize

Refs #28
```

---

### Task 6: Frontend — Terminal tab lifecycle and renderer management

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts`

**Interfaces:**
- Consumes: Frame manager from Task 5
- Consumes: `pages-component-terminal` for xterm.js rendering
- Consumes: `POST /api/terminals` for auto-creation
- Consumes: WebGL IPC from Task 3 (`trellis.webglAcquire/Release`)
- Produces: Terminal connection per tab (WebSocket, auto-create on 404)
- Produces: Three-tier renderer lifecycle (WebGL/Canvas/None)
- Produces: `Terminal` vs `Renderer` lifetime separation

---

- [ ] **Step 1: Implement terminal tab panel**

Each Dockview panel renders a `pages-component-terminal` element. On panel add:
1. Check if terminal exists: `GET /api/terminals` filtered by name
2. If not found: `POST /api/terminals` to auto-create (handle 201 and 409)
3. Connect WebSocket to `/ws/terminal/{name}/{cols}/{rows}`
4. Handle close code 4001 → show "Connection moved" with Reconnect button
5. Handle other close codes → exponential backoff retry (100ms, 200ms, 400ms, max 3)

- [ ] **Step 2: Implement renderer tier management**

Track renderer state per terminal: `Map<string, 'webgl' | 'canvas' | 'none'>`.

- On tab focus (active tab in active frame): request WebGL via `trellis.webglAcquire()`
  - Granted → attach WebGL renderer
  - Denied → fall back to Canvas
- On tab visible but not focused: Canvas renderer
- On tab hidden (background tab): dispose renderer, keep Terminal alive
- Listen for `webgl:demote` IPC → downgrade specified terminal to Canvas
- Listen for `webgl:grant` IPC → promote specified terminal to WebGL

- [ ] **Step 3: Use `visibility: hidden` for hidden terminal containers**

Not `display: none` — preserves layout dimensions for `fit()`.

- [ ] **Step 4: Implement `fit()` debouncing**

150ms debounce on all `fit()` calls. Never call `fit()` during drag/resize.

- [ ] **Step 5: Verify — open multiple tabs, switch between them, verify renderer transitions**

- [ ] **Step 6: Commit**

```
feat(#28): terminal tab lifecycle, three-tier renderer management, auto-creation

Refs #28
```

---

### Task 7: Frontend — Keyboard navigation

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts`

**Interfaces:**
- Consumes: Frame manager from Task 5
- Consumes: Menu accelerator IPC from Task 3 (`shortcut:new-frame`, etc.)
- Produces: Tab navigation (`Cmd+Shift+[/]`, `Cmd+1-9`)
- Produces: Frame navigation (`Cmd+Opt+[/]`, `Cmd+Opt+1-9`, `Cmd+Opt+Arrow`)
- Produces: Global shortcuts (`Cmd+N/T/W`, `Cmd+Shift+W/S/P/D/L/Backspace`)
- Produces: `attachCustomKeyEventHandler` on all xterm instances
- Produces: Spatial navigation algorithm

---

- [ ] **Step 1: Register xterm key event handler**

On every `pages-component-terminal`, call `attachCustomKeyEventHandler()`:
- Return `false` for all app-level shortcuts (prevents xterm from consuming them)
- Return `true` for everything else

- [ ] **Step 2: Register workspace panel keydown listener**

In `connectedCallback` (when workspace panel is active):
```typescript
this._keydownHandler = (e: KeyboardEvent) => this._handleKeydown(e);
document.addEventListener('keydown', this._keydownHandler);
```

Remove in `disconnectedCallback`.

- [ ] **Step 3: Implement `_handleKeydown` dispatcher**

Match keyboard events to actions:
- `Cmd+Shift+]` → next tab
- `Cmd+Shift+[` → prev tab
- `Cmd+1..9` → jump to tab N
- `Cmd+Opt+]` → next frame (by `order`)
- `Cmd+Opt+[` → prev frame
- `Cmd+Opt+1..9` → jump to frame N
- `Cmd+Opt+Arrow` → spatial navigation
- `Cmd+Shift+P` → pin/unpin
- `Cmd+Shift+D` → detach
- `Cmd+Shift+L` → organiser picker
- `Cmd+Shift+S` → save as group
- `Cmd+Shift+Backspace` → delete group
- `Cmd+Shift+W` → close frame

- [ ] **Step 4: Implement spatial navigation algorithm**

```typescript
_spatialNavigate(direction: 'up' | 'down' | 'left' | 'right') {
  const current = this._focusedFrame;
  const currentCenter = { x: current.x + current.width/2, y: current.y + current.height/2 };
  // Filter candidates by half-plane, pick nearest by Euclidean distance
}
```

- [ ] **Step 5: Listen for menu accelerator IPC**

```typescript
window.trellis.onShortcut('new-frame', () => this._showFramePicker());
window.trellis.onShortcut('new-tab', () => this._showTabPicker());
window.trellis.onShortcut('close-tab', () => this._closeActiveTab());
```

- [ ] **Step 6: Listen for cross-window nav IPC**

`Cmd+Ctrl+]` / `Cmd+Ctrl+[` → `trellis.nextWindow()` / `trellis.prevWindow()`

- [ ] **Step 7: Verify — all keyboard shortcuts work in dev mode**

- [ ] **Step 8: Commit**

```
feat(#28): keyboard navigation — tabs, frames, spatial, global shortcuts

Refs #28
```

---

### Task 8: Frontend — Tab hover flyout

**Files:**
- Create: `sidecar/src/main/webui/src/components/tab-flyout.ts`
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts` (wire flyout to tab hover)

**Interfaces:**
- Consumes: `GET /api/workspace/repo` for repo metadata
- Consumes: SSE `agent:state` for terminal/agent state
- Consumes: xterm Terminal buffer for last output lines
- Produces: `trellis-tab-flyout` component

---

- [ ] **Step 1: Create `trellis-tab-flyout` Lit component**

Properties: `terminalName`, `repoInfo`, `agentState`, `lastOutput`

Renders: repo name, branch, path, slot (if applicable), issue, agent status with uptime, memory, last 2-3 output lines.

Styled: dark panel, `casehub-dark` theme, positioned relative to tab.

- [ ] **Step 2: Wire flyout to tab hover events**

On Dockview tab `mouseenter` (300ms delay): show flyout, populate from:
- Repo metadata: cached from workspace scan or fetched from `/api/workspace/repo`
- Agent state: from SSE subscription (already maintained in workspace-view)
- Last output: `terminal.buffer.active.getLine(terminal.buffer.active.length - N)`

On `mouseleave`: dismiss.

- [ ] **Step 3: Verify — hover over tabs shows metadata**

- [ ] **Step 4: Commit**

```
feat(#28): tab hover flyout with repo metadata, agent state, last output

Refs #28
```

---

### Task 9: Frontend — Groups (save, open, update, delete)

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts`

**Interfaces:**
- Consumes: `trellis.saveGroups()`, `trellis.loadGroups()` from Task 2
- Produces: Group picker (on `Cmd+N`)
- Produces: Repo/slot picker (on `Cmd+T`)
- Produces: Context menu on frame title (Save as Group, Update Group, Delete Group)

---

- [ ] **Step 1: Implement group picker**

Modal overlay listing saved groups + "Empty frame" option. On select:
- Group: `_createFrame(group.tabs, group.id, group.name)` — skipping duplicates per tab uniqueness
- Empty: `_createFrame([], undefined, 'Untitled')`

- [ ] **Step 2: Implement repo/slot picker**

Modal overlay listing repos (from workspace scan) and slots (active). Excludes terminals already open (tab uniqueness). On select: add tab to focused frame.

- [ ] **Step 3: Implement frame title context menu**

Right-click on frame title bar shows:
- "Save as Group" (always available) → prompts for name, saves to `PersistedGroups`
- "Update Group" (only if `groupId` set) → updates group's tabs to match frame
- "Delete Group" (only if `groupId` set) → removes from `PersistedGroups`, clears `groupId`

- [ ] **Step 4: Wire keyboard shortcuts**

- `Cmd+N` → group picker
- `Cmd+T` → repo/slot picker
- `Cmd+Shift+S` → save as group
- `Cmd+Shift+Backspace` → delete group

- [ ] **Step 5: Verify — create groups, open from groups, update, delete**

- [ ] **Step 6: Commit**

```
feat(#28): groups — save, open, update, delete with picker UIs

Refs #28
```

---

### Task 10: Frontend — Organisers and pinning

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts`

**Interfaces:**
- Consumes: Frame manager from Task 5
- Produces: Five preset layout functions
- Produces: Preset picker UI (`Cmd+Shift+L`)
- Produces: Opt-in snap (Shift modifier during drag)
- Produces: Pin toggle (always-on-top + position-locked)

---

- [ ] **Step 1: Implement preset layout functions**

Pure functions: `(frames: FrameLayout[], canvasSize) → FrameLayout[]`

```typescript
function sideBySide(frames, canvas) { /* equal width, full height */ }
function stacked(frames, canvas) { /* full width, equal height */ }
function grid(frames, canvas) { /* ceil(sqrt(n)) cols × ceil(n/cols) rows */ }
function mainSidebar(frames, canvas) { /* first 2/3 left, rest stacked right */ }
function focus(frames, canvas) { /* active fills, rest minimised to bottom strip */ }
```

Pinned frames excluded from rearrangement.

- [ ] **Step 2: Implement preset picker**

Small dropdown on `Cmd+Shift+L`: list of 5 presets with `1-5` number keys for quick access.

- [ ] **Step 3: Implement opt-in snap**

During drag, detect `Shift` key held:
- If held: snap to edges of other frames and container with 10px magnetic zone
- If not held: pure free movement

- [ ] **Step 4: Implement pin toggle**

`Cmd+Shift+P` or click 📌 button:
- Pinned: z-index moves to pinned tier (10000+), frame position locked (drag disabled), pin button highlighted
- Unpinned: z-index returns to normal tier, drag re-enabled

- [ ] **Step 5: Verify — each preset arranges correctly, snap works only with Shift, pin works**

- [ ] **Step 6: Commit**

```
feat(#28): organiser presets, opt-in snap, frame pinning

Refs #28
```

---

### Task 11: Frontend — Persistence and restore

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts`
- Modify: `shell/main.js` (readiness polling, lastWorkspacePath)
- Modify: `shell/health-monitor.js` (poll `/api/health/ready` instead of `/api/health`)

**Interfaces:**
- Consumes: LayoutStore IPC from Task 2
- Consumes: Readiness endpoint from Task 1
- Consumes: `screen.getAllDisplays()` for bounds validation
- Produces: Auto-save layout on every change (debounce 1s, maxWait 5s)
- Produces: Full restore sequence on startup
- Produces: `layout:flush` handler for shutdown save
- Produces: Display bounds validation and clamping

---

- [ ] **Step 1: Implement layout auto-save**

On `onDidLayoutChange` (debounced 1s, maxWait 5s):
1. Serialize Dockview state into `ShellLayout`
2. Normalize z-indices to sequential integers
3. Send via `trellis.saveWindowLayout(shellLayout)`

- [ ] **Step 2: Implement `layout:flush` handler**

```typescript
window.trellis.onLayoutFlush(() => {
  const shellLayout = this._serializeLayout();
  window.trellis.saveWindowLayout(shellLayout);
});
```

- [ ] **Step 3: Modify Electron startup for readiness polling**

In `main.js`, after `server.spawnServer(port)`:
1. Poll `/api/health/ready` (not `/api/health`) until 200
2. Load `lastWorkspacePath` from LayoutStore
3. Load `PersistedLayout` for that path
4. Cross-window terminal deduplication (scan for duplicate terminalNames)
5. Create BrowserWindows per `ShellLayout` with bounds clamped to displays
6. Send layout data to each window

- [ ] **Step 4: Implement restore in workspace-view**

On init, receive layout from Electron main process:
1. Create Dockview floating groups per `FrameLayout`
2. For each tab: connect terminal (auto-create if needed)
3. Focus `lastActiveFrameId` and `activeTabIndex`
4. Handle "Disconnected" state for terminals not in TerminalRegistry

- [ ] **Step 5: Implement display bounds validation**

On restore, validate `ShellLayout.bounds` against `screen.getAllDisplays()`. Clamp windows and frames that would be off-screen.

- [ ] **Step 6: Verify — save layout, quit, restart, verify full restoration**

- [ ] **Step 7: Commit**

```
feat(#28): layout persistence — auto-save, shutdown flush, startup restore

Refs #28
```

---

### Task 12: Frontend — Detach and reattach

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts`
- Modify: `shell/main.js` (frame:init IPC forwarding)
- Modify: `shell/preload.js` (frame:init / frame:receive listeners)

**Interfaces:**
- Consumes: Frame manager from Task 5
- Consumes: WindowManager from Task 2
- Consumes: Save-inhibit flag from Task 2
- Produces: `Cmd+Shift+D` detach → new BrowserWindow with frame
- Produces: Right-click "Attach to main window" → reattach
- Produces: `frame:init` / `frame:receive` IPC protocol
- Produces: Idempotent `frame:receive` handler (dedup by frame ID)

---

- [ ] **Step 1: Implement detach**

On `Cmd+Shift+D` or click ⎋:
1. Serialize focused frame's `FrameLayout`
2. Call `trellis.detachFrame(frameLayout)` → main process
3. Main process: set save-inhibit, create BrowserWindow, send `frame:init`
4. New window's workspace-view: listen for `frame:init`, create Dockview group
5. Source window: remove Dockview group, clear save-inhibit

- [ ] **Step 2: Implement reattach**

Right-click frame title → "Attach to main window":
1. Serialize frame's `FrameLayout`
2. Call `trellis.attachFrame(targetWindowId, frameLayout)` → main process
3. Main process forwards to target window via `frame:receive`
4. Target window creates Dockview group with `order = max + 1`
5. Source window removes group; closes if last frame

- [ ] **Step 3: Handle edge cases**

- `frame:receive` is idempotent (dedup by frame ID)
- Source window closing before target reconstructs → persisted layout has the frame
- Tab uniqueness checked on receive (skip terminals already open in target)

- [ ] **Step 4: Verify — detach to new window, reattach, verify layout persists**

- [ ] **Step 5: Commit**

```
feat(#28): frame detach to BrowserWindow, reattach, cross-window IPC

Refs #28
```

---

## Task Dependencies

```
Task 1 (backend) ──────────────────────────────────────┐
Task 2 (LayoutStore + shutdown) ───┐                   │
Task 3 (WebGL IPC + accelerators) ─┤                   │
                                   ├─► Task 5 (Dockview frame manager)
Task 4 (workbench DOM + dashboard) ┘   │
                                       ├─► Task 6 (terminal lifecycle)
                                       ├─► Task 7 (keyboard nav)
                                       ├─► Task 8 (hover flyout)
                                       ├─► Task 9 (groups)
                                       ├─► Task 10 (organisers + pinning)
                                       ├─► Task 11 (persistence + restore) ◄── Task 1
                                       └─► Task 12 (detach/reattach) ◄── Task 2
```

Tasks 1-4 can run in parallel. Tasks 6-12 depend on Task 5 but are independent of each other (can be done in any order after 5). Task 11 also depends on Task 1 (readiness endpoint) and Task 2 (LayoutStore). Task 12 depends on Task 2 (save-inhibit).

## Execution Order

Sequential (inline): 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12
