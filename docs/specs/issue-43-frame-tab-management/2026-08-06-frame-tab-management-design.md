# Frame and Tab Management via Agent Control Plane

**Issue:** Hortora/trellis#43
**Date:** 2026-08-06
**Status:** Approved

## Problem

The coordinating agent can observe and control terminals, agents, and lifecycle
operations via the MCP control plane (#27). But it can't see or manage the
workspace view's frames and tabs — the visual layout where terminals are
arranged.

Frame/tab state lives entirely in the frontend (Dockview). The workspace-view
doesn't push its layout to the sidecar, and there's no command path to create,
remove, or rearrange frames and tabs programmatically.

A coordinating agent that can start terminals and agents but can't arrange them
in the UI is operating blind on the visual workspace.

## Design Rationale

Issue #43 proposed routing frame mutations through `trellis_navigate`. During
design, we identified that navigate is semantically "activate a UI element" —
mutations (create, remove, resize) are not navigation. Rather than distort
navigate or add a 7th tool (the original #27 spec's "6 tools" constraint was
a design preference, not an architectural invariant), we extend
`trellis_workspace` — which already owns workspace state — with frame
mutation operations. The SSE correlation mechanism lives in `UIStateStore`
and is tool-agnostic, so `trellis_workspace` uses the same pattern as
`trellis_navigate`.

## Solution

Three changes, no new MCP tools:

1. **Observation** — workspace-view implements `getUIState()`, making frames
   and tabs visible via `trellis_model(path="ui")`.
2. **Mutation** — `trellis_workspace` gains `operation` and `params` parameters
   for frame/tab/group/organiser operations. Uses SSE-to-frontend correlation
   via a shared helper extracted from `trellis_navigate`.
3. **Focus** — `trellis_navigate` completes the frame/tab focus paths already
   spec'd in #27 §6 but not yet implemented.

## §1 Observation — `getUIState()` on Workspace-View

The workbench's `_buildUIState()` already calls `getUIState()` on each cached
panel if the method exists (workbench.ts line 228). Workspace-view implements
it, reusing `_serializeLayout()` for the frame data and adding action
descriptors.

### Shape

```typescript
getUIState() {
  const layout = this._serializeLayout();
  return {
    frames: layout.frames.map(f => ({
      ...f,
      actions: [
        { name: 'remove', source: 'backend', tool: 'trellis_workspace', operation: 'frame-remove' },
        { name: 'move', source: 'backend', tool: 'trellis_workspace', operation: 'frame-move' },
        { name: 'resize', source: 'backend', tool: 'trellis_workspace', operation: 'frame-resize' },
        { name: 'pin', source: 'backend', tool: 'trellis_workspace', operation: 'frame-pin' },
        { name: 'unpin', source: 'backend', tool: 'trellis_workspace', operation: 'frame-unpin' },
        { name: 'add-tab', source: 'backend', tool: 'trellis_workspace', operation: 'tab-add' },
        { name: 'detach', source: 'backend', tool: 'trellis_workspace', operation: 'frame-detach' },
        { name: 'attach', source: 'backend', tool: 'trellis_workspace', operation: 'frame-attach' },
      ],
      tabs: f.tabs.map((t, idx) => ({
        ...t,
        tabIndex: idx,
        actions: [
          { name: 'remove', source: 'backend', tool: 'trellis_workspace', operation: 'tab-remove' },
        ],
      })),
    })),
    focusedFrameId: layout.lastActiveFrameId,
    actions: [
      { name: 'create-frame', source: 'backend', tool: 'trellis_workspace', operation: 'frame-create' },
      { name: 'apply-organiser', source: 'backend', tool: 'trellis_workspace', operation: 'organiser-apply' },
    ],
  };
}
```

Top-level actions (create-frame, apply-organiser) sit on the workspace-view
itself — they don't target an existing frame.

The sidecar serves this as opaque content under `ui/panels/workspace`. An
agent calling `trellis_model(path="ui")` sees the full workspace layout with
discoverable operations. `UIStateModelProvider` remains unchanged — it stores
and serves opaque content without parsing it.

### Action Descriptor Convention

Action descriptors on frames use `source: "backend"` because they are
MCP-executable — the agent invokes them via `trellis_workspace`. The
frontend declares them (it owns frame entities) but the `source` field
indicates invocation path, not declaration ownership.

This matches the existing pattern: `TerminalModelProvider` declares backend
actions for terminals. Workspace-view declares backend actions for frames.
Each entity owner declares its own capabilities.

## §2 `trellis_workspace` Tool Extension

### Signature Change

Current:
```java
@Tool(name = "trellis_workspace",
      description = "Full workspace queries")
public ToolResponse trellisWorkspace(
    @ToolArg(name = "path", required = false) String path,
    @ToolArg(name = "refresh", required = false) Boolean refresh)
```

Extended:
```java
@Tool(name = "trellis_workspace",
      description = "Workspace queries and frame/tab management. "
                  + "Query: path + refresh. "
                  + "Mutate: operation + params (dispatched to frontend via SSE).")
public ToolResponse trellisWorkspace(
    @ToolArg(name = "path", required = false) String path,
    @ToolArg(name = "refresh", required = false) Boolean refresh,
    @ToolArg(name = "operation", required = false,
             description = "Frame/tab operation: frame-create, frame-remove, "
                         + "frame-move, frame-resize, frame-pin, frame-unpin, "
                         + "frame-detach, frame-attach, tab-add, tab-remove, "
                         + "group-save, group-update, group-delete, organiser-apply")
    String operation,
    @ToolArg(name = "params", required = false,
             description = "JSON parameters for the operation") String params)
```

### Dispatch Logic

```java
if (operation != null) {
    var parsedParams = params != null
        ? objectMapper.readValue(params, Map.class)
        : Map.of();
    return dispatchFrontendCommand("control:workspace",
        Map.of("command", operation, "params", parsedParams));
}
// existing read path unchanged
```

### Shared SSE Dispatch Helper

Extracted from `trellisNavigate` — the correlation + timeout pattern is
reusable across any tool that commands the frontend:

```java
private ToolResponse dispatchFrontendCommand(String topic, Map<String, Object> payload) {
    if (!uiStateStore.hasFrontend()) {
        return ToolResponse.error("no frontend connected");
    }
    var correlationId = UUID.randomUUID().toString();
    var future = uiStateStore.registerNavigation(correlationId);
    var eventPayload = new LinkedHashMap<>(payload);
    eventPayload.put("correlationId", correlationId);
    broadcaster.broadcast(topic, eventPayload);
    try {
        var postState = future.get(5, TimeUnit.SECONDS);
        return ToolResponse.success(objectMapper.writeValueAsString(postState));
    } catch (java.util.concurrent.TimeoutException e) {
        uiStateStore.cleanupNavigation(correlationId);
        return ToolResponse.error("timeout: " + topic);
    } catch (Exception e) {
        uiStateStore.cleanupNavigation(correlationId);
        return ToolResponse.error("command failed: " + e.getMessage());
    }
}
```

`trellisNavigate` refactored to use the same helper:

```java
public ToolResponse trellisNavigate(String target) {
    return dispatchFrontendCommand("control:navigate", Map.of("target", target));
}
```

Each SSE topic keeps its own payload shape. `control:navigate` sends
`{target, correlationId}`. `control:workspace` sends
`{command, params, correlationId}`. The correlation plumbing is shared;
the existing navigate flow and frontend handler stay untouched.

## §3 SSE Command Flow — `control:workspace` Topic

### Topic Convention

New SSE topic: `control:workspace`. Same convention as `control:navigate` —
the `control:` prefix means the sidecar is sending a directive the frontend
must execute.

Payload shape:
```json
{
  "command": "frame-create",
  "params": { "tabs": [{"terminalName": "repo-engine", "type": "repo"}] },
  "correlationId": "uuid"
}
```

### Workbench Subscription

The workbench subscribes to both topics:

```typescript
private _connectSSE() {
  this._eventSource = new EventSource(
    '/api/push?topics=control:navigate,control:workspace'
  );
  this._eventSource.addEventListener('message', (event: MessageEvent) => {
    const msg = JSON.parse(event.data);
    if (msg.topic === 'control:navigate' && msg.payload) {
      this._handleNavigateEvent(/* existing */);
    } else if (msg.topic === 'control:workspace' && msg.payload) {
      this._handleWorkspaceCommand(/* new */);
    }
  });
}
```

### Workbench Delegation

The workbench doesn't know about Dockview or frames. It activates the
workspace panel, delegates the command, awaits the result, and pushes
UI state with the correlationId:

```typescript
private async _handleWorkspaceCommand(
    payload: { command: string; params?: any; correlationId?: string }) {
  if (payload.correlationId) {
    this._pendingCorrelationId = payload.correlationId;
  }
  this._activatePanel('workspace');
  const wsView = this._panelCache.get('workspace');
  if (wsView && typeof (wsView as any).handleCommand === 'function') {
    await (wsView as any).handleCommand(payload.command, payload.params);
  }
  this._pushUIStateImmediate();
}
```

### Panel Activation

The workbench activates the workspace panel before executing any command.
The Dockview DOM is retained across panel switches (workspace-view spec §8
— panels use `display: none` not DOM removal), so Dockview state is
preserved. Activating ensures the user sees the result.

## §4 Workspace-View Command Handler

### `handleCommand()` Dispatch

Maps command strings to existing or new methods. Returns a Promise for
async operations (detach, group persistence). Sets `_lastCommandResult`
before returning so `getUIState()` can include it in the correlation ack.

Methods that internally use `_focusedFrameId` (`_detachFrame`,
`_saveFrameAsGroup`) need the focused frame set before calling. The
handler sets `this._focusedFrameId = params.frameId` for these cases.

```typescript
async handleCommand(command: string, params?: any): Promise<{ ok: boolean; error?: string; frameId?: string }> {
  let result: { ok: boolean; error?: string; frameId?: string };

  switch (command) {
    case 'frame-create': {
      const id = this.createFrame(params.tabs, params.groupId, params.name, params);
      result = id ? { ok: true, frameId: id } : { ok: false, error: 'no valid tabs' };
      break;
    }
    case 'frame-remove': {
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      this.hideFrame(params.frameId);
      this.deleteFrame(params.frameId);
      result = { ok: true };
      break;
    }
    case 'frame-move': {
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      this._moveFrame(params.frameId, params.position);
      result = { ok: true };
      break;
    }
    case 'frame-resize': {
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      this._resizeFrame(params.frameId, params.size);
      result = { ok: true };
      break;
    }
    case 'frame-pin': {
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (!this._pinnedFrames.has(params.frameId)) this.togglePin(params.frameId);
      result = { ok: true };
      break;
    }
    case 'frame-unpin': {
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (this._pinnedFrames.has(params.frameId)) this.togglePin(params.frameId);
      result = { ok: true };
      break;
    }
    case 'frame-detach': {
      if (this._browserMode) { result = { ok: false, error: 'electron only' }; break; }
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      this._focusedFrameId = params.frameId;
      await this._detachFrame();
      result = { ok: true };
      break;
    }
    case 'frame-attach': {
      if (this._browserMode) { result = { ok: false, error: 'electron only' }; break; }
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      await this._attachToMainWindow(params.frameId);
      result = { ok: true };
      break;
    }
    case 'tab-add': {
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (!params.tab?.terminalName) { result = { ok: false, error: 'tab.terminalName required' }; break; }
      if (this._activeTerminals.has(params.tab.terminalName)) { result = { ok: false, error: 'terminal already open' }; break; }
      await this._addTab(params.frameId, params.tab);
      result = { ok: true };
      break;
    }
    case 'tab-remove': {
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (!params.terminalName) { result = { ok: false, error: 'terminalName required' }; break; }
      const tabs = this._frameTabs.get(params.frameId);
      const tabIdx = tabs?.findIndex(t => t.terminalName === params.terminalName);
      if (tabIdx === undefined || tabIdx < 0) { result = { ok: false, error: 'terminal not in frame' }; break; }
      this._removeTab(params.frameId, tabIdx);
      result = { ok: true };
      break;
    }
    case 'group-save': {
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      this._focusedFrameId = params.frameId;
      await this._saveFrameAsGroup(params.name);
      result = { ok: true };
      break;
    }
    case 'group-update': {
      if (!this._frameOrders.has(params.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (!this._frameGroupIds.has(params.frameId)) { result = { ok: false, error: 'frame has no group' }; break; }
      this._updateGroup(params.frameId);
      result = { ok: true };
      break;
    }
    case 'group-delete': {
      if (!params.groupId) { result = { ok: false, error: 'groupId required' }; break; }
      await this._deleteGroupById(params.groupId);
      result = { ok: true };
      break;
    }
    case 'organiser-apply': {
      const validPresets = ['side-by-side', 'stacked', 'grid', 'main-sidebar', 'focus'];
      if (!validPresets.includes(params.preset)) { result = { ok: false, error: 'unknown preset: ' + params.preset }; break; }
      this.applyOrganiser(params.preset);
      result = { ok: true };
      break;
    }
    default:
      result = { ok: false, error: 'unknown command: ' + command };
  }

  this._lastCommandResult = result;
  return result;
}
```

### Command Result Feedback

`getUIState()` includes a `commandResult` field when a command has been
executed since the last push. The field is cleared after being included
once — one-shot feedback, not persistent state:

```typescript
private _lastCommandResult: { ok: boolean; error?: string; frameId?: string } | null = null;

getUIState() {
  const layout = this._serializeLayout();
  const state = {
    frames: /* ... as in §1 ... */,
    focusedFrameId: layout.lastActiveFrameId,
    actions: /* ... as in §1 ... */,
  };
  if (this._lastCommandResult) {
    (state as any).commandResult = this._lastCommandResult;
    this._lastCommandResult = null;
  }
  return state;
}
```

The agent sees `commandResult: {ok: true, frameId: "frame-..."}` or
`commandResult: {ok: false, error: "frame not found"}` in the correlation
ack response.

### New Wrapper Methods

Thin wrappers over existing Dockview state mutations:

**`_moveFrame(frameId, position)`** — Updates internal position map and
Dockview floating group position. Uses Dockview's `group.api.setPosition()`
or direct DOM manipulation on the `.dv-resize-container` element if the
Dockview API doesn't support post-creation position changes.

**`_resizeFrame(frameId, size)`** — Updates Dockview floating group size
via `group.api.setSize()` or direct DOM manipulation. Also updates the
internal size tracking (see implementation note below). Debounces `fit()`
on each terminal in the frame after 150ms.

**`_addTab(frameId, tab)`** — Async. Calls `_ensureTerminalExists` (HTTP
fetch to create terminal if needed), adds terminal to `_activeTerminals`,
creates Dockview panel in the frame's group, updates `_frameTabs`, calls
`_connectTerminal`.

**`_removeTab(frameId, tabIndex)`** — Removes Dockview panel from group,
removes terminal from `_activeTerminals`, updates `_frameTabs`. If the
frame has no remaining tabs after removal, calls `hideFrame` then
`deleteFrame`.

**`_deleteGroupById(groupId)`** — Async. Deletes a group by ID from
persistence, independent of whether a frame with that groupId is open.
If a frame references this group, clears its `groupId`. Unlike the
existing `_deleteGroup(frameId)` which requires a focused frame.

### Implementation Notes

**Frame size tracking:** The existing `_serializeLayout()` hardcodes
`{ width: 600, height: 400 }` for all frames. This must be fixed — the
implementation must track actual Dockview group sizes (read from the
DOM or Dockview API on layout change events) so that `getUIState()`
reports real dimensions. Without this, `frame-resize` would succeed
but the model tree would still show the default 600×400.

**Dockview floating group API:** Verify that Dockview v7 exposes
`group.api.setPosition()` and `group.api.setSize()` for floating groups.
If not available, fall back to direct CSS manipulation on the group's
container element — Dockview uses `transform: translate()` for position
and explicit width/height for size.

### Concurrency Limitation

The workbench's `_pendingCorrelationId` is a single scalar. If two MCP
tools dispatch SSE commands concurrently, the second overwrites the
first's correlationId, causing the first to timeout. This is a
pre-existing limitation (affects `control:navigate` too) but becomes
more likely with workspace commands.

The fix — changing `_pendingCorrelationId` to a `Set<string>` and
draining all IDs into the UI state push — is straightforward but
out of scope for this issue. Until fixed, the sidecar should not send
concurrent workspace commands. The `dispatchFrontendCommand` helper
serializes within a single tool call (CompletableFuture blocks until
ack), so concurrent commands only arise from separate MCP clients.

## §5 Frame/Tab Focus via `trellis_navigate`

The Agent Control Plane spec (#27 §6) defines navigate paths for frame and
tab focus but the workbench handler doesn't implement them yet.

### Navigate Path Extensions

| Target path | Action |
|---|---|
| `panels/workspace-view/frames/{id}` | Activate workspace, focus frame |
| `panels/workspace-view/frames/{id}/tabs/{idx}` | Activate workspace, focus frame, switch tab |

### Workbench Handler

The workbench `_handleNavigateEvent` extends its path parsing to delegate
frame/tab focus to workspace-view:

```typescript
} else if (target.startsWith('panels/')) {
  const parts = target.substring('panels/'.length).split('/');
  const panelId = parts[0];
  if (panelId === 'workspace-view' || panelId === 'workspace') {
    this._activatePanel('workspace');
    if (parts.length >= 3 && parts[1] === 'frames') {
      const wsView = this._panelCache.get('workspace');
      if (wsView) {
        (wsView as any).focusFrame(parts[2]);
        if (parts.length >= 5 && parts[3] === 'tabs') {
          (wsView as any).focusTab(parts[2], parseInt(parts[4], 10));
        }
      }
    }
  }
  // ...
}
```

### Workspace-View Focus Methods

ID-based focus methods for MCP consumption. Distinct from the existing
order-based `_jumpToFrame`/`_jumpToTab` used by keyboard navigation:

```typescript
focusFrame(frameId: string) {
  this._focusedFrameId = frameId;
  this.bringToFront(frameId);
  const activeIdx = this._frameActiveTab.get(frameId) ?? 0;
  const tabs = this._frameTabs.get(frameId);
  if (tabs?.[activeIdx]) {
    this._terminalElements.get(tabs[activeIdx].terminalName)?.focus();
  }
}

focusTab(frameId: string, tabIndex: number) {
  this._frameActiveTab.set(frameId, tabIndex);
  const tabs = this._frameTabs.get(frameId);
  if (tabs?.[tabIndex]) {
    const group = this._frameGroups.get(frameId);
    if (group) {
      const panels = [...group.panels];
      if (panels[tabIndex]) {
        group.model.openPanel(panels[tabIndex]);
      }
    }
    this._terminalElements.get(tabs[tabIndex].terminalName)?.focus();
  }
}
```

## §6 Operations Reference

Full operation set for `trellis_workspace(operation=..., params=...)`:

| Operation | Params | Maps to |
|---|---|---|
| `frame-create` | `tabs: TabRef[]`, `groupId?: string`, `name?: string`, `position?: {x,y}`, `size?: {width,height}` | `createFrame()` |
| `frame-remove` | `frameId: string` | `deleteFrame()` |
| `frame-move` | `frameId: string`, `position: {x, y}` | `_moveFrame()` |
| `frame-resize` | `frameId: string`, `size: {width, height}` | `_resizeFrame()` |
| `frame-pin` | `frameId: string` | `togglePin()` guarded — no-op if already pinned |
| `frame-unpin` | `frameId: string` | `togglePin()` guarded — no-op if already unpinned |
| `frame-detach` | `frameId: string` | `_detachFrame()` — Electron only |
| `frame-attach` | `frameId: string` | `_attachToMainWindow()` — Electron only |
| `tab-add` | `frameId: string`, `tab: TabRef` | `_addTab()` |
| `tab-remove` | `frameId: string`, `terminalName: string` | `_removeTab()` — looks up tab by name, not index |
| `group-save` | `frameId: string`, `name: string` | `_saveFrameAsGroup()` |
| `group-update` | `frameId: string` | `_updateGroup()` |
| `group-delete` | `groupId: string` | `_deleteGroupById()` — deletes group by ID, independent of frames |
| `organiser-apply` | `preset: string` — `side-by-side`, `stacked`, `grid`, `main-sidebar`, `focus` | `applyOrganiser()` |

## §7 Testing Strategy

### Java (Sidecar)

| Component | Test |
|---|---|
| `trellis_workspace` dispatch | `operation` set → calls `dispatchFrontendCommand`. `operation` null → existing read path |
| `dispatchFrontendCommand` | Registers correlation, broadcasts SSE, returns on ack. Timeout → error. No frontend → error |
| `trellisNavigate` refactored | Uses `dispatchFrontendCommand`. Behavior unchanged |
| Parameter validation | Invalid operation → error. Missing required params → error |

### TypeScript (Frontend)

| Component | Test |
|---|---|
| `getUIState()` | Correct frame shape. Action descriptors on frames, tabs, workspace level. Empty workspace → `{frames: [], actions: [...]}` |
| `handleCommand()` — frame-create | Creates frame → `{ok: true, frameId}`. Duplicate terminals filtered. All filtered → `{ok: false}` |
| `handleCommand()` — frame-remove | Existing → `{ok: true}`. Unknown → `{ok: false, error}` |
| `handleCommand()` — tab-add | Adds tab. Uniqueness violation → `{ok: false}`. Unknown frame → `{ok: false}` |
| `handleCommand()` — tab-remove | Removes tab by terminalName. Last tab removes frame. Unknown name → `{ok: false}` |
| `handleCommand()` — frame-move/resize | Updates position/size. Clamps to container bounds |
| `handleCommand()` — frame-pin/unpin | Idempotent — pinning a pinned frame → `{ok: true}` |
| `handleCommand()` — organiser-apply | All 5 presets produce valid layouts |
| `handleCommand()` — group-save/update/delete | Round-trip persistence. Update non-group → error |
| `handleCommand()` — frame-detach/attach | Browser mode → `{ok: false, error: "electron only"}` |
| Workbench SSE | Subscribes `control:workspace`. Delegates to workspace-view. Pushes UI state with correlationId |
| Navigate frame focus | `panels/workspace-view/frames/{id}` → activates workspace, focuses frame. `/tabs/{idx}` → switches tab |
| `commandResult` lifecycle | Present after command, cleared on next `getUIState()` |

### Integration

| Area | Test |
|---|---|
| MCP round-trip | `trellis_workspace(operation="frame-create")` → SSE → frontend → ack with new frame |
| Observation round-trip | Create frame → `trellis_model(path="ui")` → frame with actions |
| Navigate after create | Create frame → `trellis_navigate(target="panels/workspace-view/frames/{id}")` → focused |

## §8 Scope Boundary

### In scope

- `getUIState()` on workspace-view with frame/tab action descriptors
- `trellis_workspace` extended with `operation` + `params` parameters
- `dispatchFrontendCommand` shared helper extracted from `trellisNavigate`
- `control:workspace` SSE topic with workbench subscription and delegation
- `handleCommand()` on workspace-view with result reporting
- Frame/tab focus paths in `_handleNavigateEvent` (completing #27 §6)
- `focusFrame()` / `focusTab()` ID-based methods on workspace-view
- New wrapper methods: `_moveFrame`, `_resizeFrame`, `_addTab`, `_removeTab`
- `commandResult` in UI state push for operation feedback
- CLAUDE.md: remove "MCP tool surface is stable at 6 tools" convention

### Out of scope

- Multi-window frame coordination — detach/attach included but cross-window
  targeting (specifying which window) deferred per #27 (single window for now)
- Frame persistence changes — existing serialization/restore untouched
- New REST endpoints — all operations flow through MCP → SSE
- Model tree restructuring — `UIStateModelProvider` stays opaque

### Dependencies

- #27 Agent Control Plane (SSE navigate, UI state push, correlation ack) — done
- #28/#29 Workspace view (Dockview, frames, tabs, groups, persistence) — done
- #33 Workspace view spec implementation — done
