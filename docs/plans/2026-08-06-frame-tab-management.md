# Frame and Tab Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #43 — Frame and tab management via Agent Control Plane
**Issue group:** #43

**Goal:** Enable the coordinating agent to observe and manage workspace
frames and tabs through the existing MCP tool surface — no new tools.

**Architecture:** Three layers: (1) workspace-view pushes frame/tab
state via `getUIState()` for observation, (2) `trellis_workspace` gains
`operation`+`params` parameters that dispatch SSE commands to the
frontend via a shared correlation helper, (3) the workbench subscribes
to `control:workspace` SSE and delegates to workspace-view's
`handleCommand()`.

**Tech Stack:** Java 21 / Quarkus 3.x (sidecar), TypeScript / Lit /
Dockview v7 (frontend), vitest (TS tests), JUnit 5 + @QuarkusTest (Java
tests)

## Global Constraints

- No new MCP tools — extend `trellis_workspace` with `operation`+`params`
- No new REST endpoints — all frame operations flow through MCP → SSE
- `UIStateModelProvider` stays opaque — no backend parsing of workspace content
- Frame IDs are stable identifiers assigned at creation, never positional
- `terminalName` (not positional index) for tab identification in commands
- Pre-release platform — breaking changes to tool signatures are fine

## Spec

`docs/specs/issue-43-frame-tab-management/2026-08-06-frame-tab-management-design.md`

---

### Task 1: Observation — `getUIState()` + frame size tracking

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts`
- Test: `sidecar/src/main/webui/src/components/workspace-view.test.ts`

**Interfaces:**
- Consumes: `_serializeLayout(): ShellLayout` (existing), `_frameOrders`,
  `_frameTabs`, `_pinnedFrames`, `_frameZIndices`, `_framePositions`,
  `_frameGroupIds`, `_focusedFrameId` (existing internal state)
- Produces: `getUIState(): object` — called by workbench `_buildUIState()`
  (already looks for this method). `_lastCommandResult` field used by
  Task 3.

- [ ] **Step 1: Write failing test for `getUIState`**

Add to `workspace-view.test.ts`:

```typescript
describe('getUIState', () => {
  it('returns empty workspace state when no frames exist', async () => {
    const el = new TrellisWorkspaceView();
    const state = el.getUIState();
    expect(state).toHaveProperty('frames');
    expect(state.frames).toEqual([]);
    expect(state).toHaveProperty('focusedFrameId');
    expect(state).toHaveProperty('actions');
    expect(state.actions.length).toBeGreaterThan(0);
    expect(state.actions.find((a: any) => a.operation === 'frame-create')).toBeTruthy();
    expect(state.actions.find((a: any) => a.operation === 'organiser-apply')).toBeTruthy();
  });

  it('returns frames with action descriptors', async () => {
    const el = new TrellisWorkspaceView();
    el.workspaceRoot = '/tmp/test-ws';
    document.body.appendChild(el);
    await el.updateComplete;

    const frameId = el.createFrame([{ terminalName: 'repo-engine', type: 'repo' as const }]);
    expect(frameId).toBeTruthy();

    const state = el.getUIState();
    expect(state.frames.length).toBe(1);
    const frame = state.frames[0];
    expect(frame.id).toBe(frameId);
    expect(frame.actions).toBeDefined();
    expect(frame.actions.find((a: any) => a.operation === 'frame-remove')).toBeTruthy();
    expect(frame.actions.find((a: any) => a.operation === 'frame-attach')).toBeTruthy();
    expect(frame.tabs.length).toBe(1);
    expect(frame.tabs[0].terminalName).toBe('repo-engine');
    expect(frame.tabs[0].actions).toBeDefined();
    expect(frame.tabs[0].actions.find((a: any) => a.operation === 'tab-remove')).toBeTruthy();

    document.body.removeChild(el);
  });

  it('includes commandResult when present and clears it', () => {
    const el = new TrellisWorkspaceView();
    (el as any)._lastCommandResult = { ok: true, frameId: 'f1' };
    const state1 = el.getUIState();
    expect(state1.commandResult).toEqual({ ok: true, frameId: 'f1' });
    const state2 = el.getUIState();
    expect(state2.commandResult).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `yarn --cwd sidecar/src/main/webui test -- --run workspace-view`
Expected: FAIL — `getUIState` is not a function

- [ ] **Step 3: Add `_frameSizes` map and `_lastCommandResult` field**

Add to workspace-view field declarations (after `_frameActiveTab`):

```typescript
private _frameSizes: Map<string, { width: number; height: number }> = new Map();
private _lastCommandResult: { ok: boolean; error?: string; frameId?: string } | null = null;
```

Update `createFrame()` to track the actual frame size — after the
`const fWidth` / `const fHeight` assignments:

```typescript
this._frameSizes.set(frameId, { width: fWidth, height: fHeight });
```

Update `_serializeLayout()` to use `_frameSizes` instead of hardcoded
`{ width: 600, height: 400 }`:

```typescript
size: this._frameSizes.get(rf.id) ?? { width: 600, height: 400 },
```

Update `hideFrame()` — after other map deletions:

```typescript
this._frameSizes.delete(frameId);
```

Update `deleteFrame()` — after other map deletions:

```typescript
this._frameSizes.delete(frameId);
```

- [ ] **Step 4: Implement `getUIState()`**

Add method to `TrellisWorkspaceView` class:

```typescript
getUIState() {
  const FRAME_ACTIONS = [
    { name: 'remove', source: 'backend', tool: 'trellis_workspace', operation: 'frame-remove' },
    { name: 'move', source: 'backend', tool: 'trellis_workspace', operation: 'frame-move' },
    { name: 'resize', source: 'backend', tool: 'trellis_workspace', operation: 'frame-resize' },
    { name: 'pin', source: 'backend', tool: 'trellis_workspace', operation: 'frame-pin' },
    { name: 'unpin', source: 'backend', tool: 'trellis_workspace', operation: 'frame-unpin' },
    { name: 'add-tab', source: 'backend', tool: 'trellis_workspace', operation: 'tab-add' },
    { name: 'detach', source: 'backend', tool: 'trellis_workspace', operation: 'frame-detach' },
    { name: 'attach', source: 'backend', tool: 'trellis_workspace', operation: 'frame-attach' },
  ];
  const TAB_ACTIONS = [
    { name: 'remove', source: 'backend', tool: 'trellis_workspace', operation: 'tab-remove' },
  ];
  const WORKSPACE_ACTIONS = [
    { name: 'create-frame', source: 'backend', tool: 'trellis_workspace', operation: 'frame-create' },
    { name: 'apply-organiser', source: 'backend', tool: 'trellis_workspace', operation: 'organiser-apply' },
  ];

  const layout = this._serializeLayout();
  const state: Record<string, unknown> = {
    frames: layout.frames.map(f => ({
      ...f,
      actions: FRAME_ACTIONS,
      tabs: f.tabs.map((t, idx) => ({
        ...t,
        tabIndex: idx,
        actions: TAB_ACTIONS,
      })),
    })),
    focusedFrameId: layout.lastActiveFrameId,
    actions: WORKSPACE_ACTIONS,
  };
  if (this._lastCommandResult) {
    state.commandResult = this._lastCommandResult;
    this._lastCommandResult = null;
  }
  return state;
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `yarn --cwd sidecar/src/main/webui test -- --run workspace-view`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add sidecar/src/main/webui/src/components/workspace-view.ts sidecar/src/main/webui/src/components/workspace-view.test.ts
git commit -m "feat(#43): getUIState() on workspace-view — frame/tab observation  Refs #43"
```

---

### Task 2: Backend — `dispatchFrontendCommand` + `trellisWorkspace` extension

**Files:**
- Modify: `sidecar/src/main/java/io/hortora/trellis/mcp/TrellisTools.java`
- Test: `sidecar/src/test/java/io/hortora/trellis/mcp/TrellisToolsTest.java`

**Interfaces:**
- Consumes: `UIStateStore.registerNavigation()`, `UIStateStore.hasFrontend()`,
  `UIStateStore.cleanupNavigation()`, `EventBroadcaster.broadcast()`,
  `ObjectMapper` (all existing injected fields)
- Produces: `trellisWorkspace(path, refresh, operation, params)` — MCP
  tool with optional `operation`+`params` for frame commands.
  `dispatchFrontendCommand(topic, payload)` — shared helper.

- [ ] **Step 1: Write failing test for workspace operation dispatch**

Add to `TrellisToolsTest.java`:

```java
@Test
void workspaceOperationWithNoFrontendReturnsError() {
    var result = tools.trellisWorkspace(null, null, "frame-create", "{\"tabs\":[]}");
    assertNotNull(result);
    assertTrue(result.isError());
}

@Test
void workspaceReadPathUnchangedWithOperationNull() {
    var result = tools.trellisWorkspace(null, null, null, null);
    assertNotNull(result);
    assertFalse(result.isError());
}

@Test
void workspaceOperationWithNullParamsDoesNotThrow() {
    var result = tools.trellisWorkspace(null, null, "frame-pin", null);
    assertNotNull(result);
    // Will be error (no frontend) but not NPE
    assertTrue(result.isError());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f sidecar/pom.xml test -Dtest=TrellisToolsTest -pl . 2>&1 | tail -20`
Expected: FAIL — `trellisWorkspace` doesn't accept 4 parameters

- [ ] **Step 3: Extract `dispatchFrontendCommand` and refactor `trellisNavigate`**

In `TrellisTools.java`, add private helper method after the field
declarations:

```java
private ToolResponse dispatchFrontendCommand(String topic, Map<String, Object> payload) {
    try {
        if (!uiStateStore.hasFrontend()) {
            return ToolResponse.error("no frontend connected");
        }
        var correlationId = UUID.randomUUID().toString();
        var future = uiStateStore.registerNavigation(correlationId);
        var eventPayload = new java.util.LinkedHashMap<>(payload);
        eventPayload.put("correlationId", correlationId);
        broadcaster.broadcast(topic, eventPayload);
        try {
            var postState = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            return ToolResponse.success(objectMapper.writeValueAsString(postState));
        } catch (java.util.concurrent.TimeoutException e) {
            uiStateStore.cleanupNavigation(correlationId);
            return ToolResponse.error("timeout: " + topic);
        }
    } catch (Exception e) {
        return ToolResponse.error("command failed: " + e.getMessage());
    }
}
```

Refactor `trellisNavigate` to use it:

```java
@Tool(name = "trellis_navigate", description = "Activate a UI element (panel, frame, tab)")
public ToolResponse trellisNavigate(
        @ToolArg(name = "target", description = "Target model path") String target) {
    return dispatchFrontendCommand("control:navigate", Map.of("target", target));
}
```

- [ ] **Step 4: Extend `trellisWorkspace` with `operation` + `params`**

Replace the existing `trellisWorkspace` method signature and add
dispatch at the top:

```java
@Tool(name = "trellis_workspace",
      description = "Workspace queries and frame/tab management. "
                  + "Query: path + refresh. "
                  + "Mutate: operation + params (dispatched to frontend via SSE).")
public ToolResponse trellisWorkspace(
        @ToolArg(name = "path", description = "Workspace subpath", required = false) String path,
        @ToolArg(name = "refresh", description = "Force fresh scan", required = false) Boolean refresh,
        @ToolArg(name = "operation", description = "Frame/tab operation: frame-create, frame-remove, frame-move, frame-resize, frame-pin, frame-unpin, frame-detach, frame-attach, tab-add, tab-remove, group-save, group-update, group-delete, organiser-apply", required = false) String operation,
        @ToolArg(name = "params", description = "JSON parameters for the operation", required = false) String params) {
    if (operation != null) {
        try {
            @SuppressWarnings("unchecked")
            var parsedParams = params != null
                    ? (Map<String, Object>) objectMapper.readValue(params, Map.class)
                    : Map.<String, Object>of();
            return dispatchFrontendCommand("control:workspace",
                    Map.of("command", operation, "params", parsedParams));
        } catch (Exception e) {
            return ToolResponse.error("invalid params: " + e.getMessage());
        }
    }
    // existing read path unchanged below...
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f sidecar/pom.xml test -Dtest=TrellisToolsTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add sidecar/src/main/java/io/hortora/trellis/mcp/TrellisTools.java sidecar/src/test/java/io/hortora/trellis/mcp/TrellisToolsTest.java
git commit -m "feat(#43): dispatchFrontendCommand + trellis_workspace frame operations  Refs #43"
```

---

### Task 3: Frontend command handler — `handleCommand()` + wrapper methods

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workspace-view.ts`
- Test: `sidecar/src/main/webui/src/components/workspace-view.test.ts`

**Interfaces:**
- Consumes: `createFrame()`, `hideFrame()`, `deleteFrame()`, `togglePin()`,
  `applyOrganiser()`, `_detachFrame()`, `_attachToMainWindow()`,
  `_updateGroup()`, `_saveFrameAsGroup()`, `_ensureTerminalExists()`,
  `_connectTerminal()` (all existing methods). `_lastCommandResult`
  (from Task 1).
- Produces: `handleCommand(command, params): Promise<{ok, error?, frameId?}>` —
  called by workbench (Task 4). `focusFrame(frameId)`,
  `focusTab(frameId, tabIndex)` — called by workbench navigate handler
  (Task 4).

- [ ] **Step 1: Write failing tests for `handleCommand`**

Add to `workspace-view.test.ts`:

```typescript
describe('handleCommand', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => {
    el = new TrellisWorkspaceView();
    el.workspaceRoot = '/tmp/test-ws';
    document.body.appendChild(el);
    await el.updateComplete;
  });

  afterEach(() => {
    document.body.removeChild(el);
  });

  it('frame-create returns ok with frameId', async () => {
    const result = await el.handleCommand('frame-create', {
      tabs: [{ terminalName: 'repo-engine', type: 'repo' }],
    });
    expect(result.ok).toBe(true);
    expect(result.frameId).toBeTruthy();
  });

  it('frame-create with no valid tabs returns error', async () => {
    const result = await el.handleCommand('frame-create', { tabs: [] });
    expect(result.ok).toBe(false);
  });

  it('frame-remove calls hideFrame + deleteFrame', async () => {
    const frameId = el.createFrame([{ terminalName: 'repo-test', type: 'repo' as const }]);
    const result = await el.handleCommand('frame-remove', { frameId });
    expect(result.ok).toBe(true);
    expect(el.isTerminalOpen('repo-test')).toBe(false);
  });

  it('frame-remove on unknown frame returns error', async () => {
    const result = await el.handleCommand('frame-remove', { frameId: 'nonexistent' });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('not found');
  });

  it('frame-pin is idempotent', async () => {
    const frameId = el.createFrame([{ terminalName: 'repo-a', type: 'repo' as const }]);
    await el.handleCommand('frame-pin', { frameId });
    const result = await el.handleCommand('frame-pin', { frameId });
    expect(result.ok).toBe(true);
  });

  it('tab-add validates terminalName', async () => {
    const frameId = el.createFrame([{ terminalName: 'repo-a', type: 'repo' as const }]);
    const result = await el.handleCommand('tab-add', { frameId, tab: {} });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('terminalName required');
  });

  it('tab-add rejects duplicate terminal', async () => {
    const frameId = el.createFrame([{ terminalName: 'repo-a', type: 'repo' as const }]);
    const result = await el.handleCommand('tab-add', {
      frameId,
      tab: { terminalName: 'repo-a', type: 'repo' },
    });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('already open');
  });

  it('tab-remove uses terminalName not index', async () => {
    const frameId = el.createFrame([
      { terminalName: 'repo-a', type: 'repo' as const },
      { terminalName: 'repo-b', type: 'repo' as const },
    ]);
    const result = await el.handleCommand('tab-remove', { frameId, terminalName: 'repo-a' });
    expect(result.ok).toBe(true);
    expect(el.isTerminalOpen('repo-a')).toBe(false);
    expect(el.isTerminalOpen('repo-b')).toBe(true);
  });

  it('tab-remove with unknown terminal returns error', async () => {
    const frameId = el.createFrame([{ terminalName: 'repo-a', type: 'repo' as const }]);
    const result = await el.handleCommand('tab-remove', { frameId, terminalName: 'repo-z' });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('not in frame');
  });

  it('organiser-apply validates preset name', async () => {
    const result = await el.handleCommand('organiser-apply', { preset: 'invalid' });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('unknown preset');
  });

  it('organiser-apply accepts valid presets', async () => {
    el.createFrame([{ terminalName: 'repo-a', type: 'repo' as const }]);
    const result = await el.handleCommand('organiser-apply', { preset: 'grid' });
    expect(result.ok).toBe(true);
  });

  it('unknown command returns error', async () => {
    const result = await el.handleCommand('bogus', {});
    expect(result.ok).toBe(false);
    expect(result.error).toContain('unknown command');
  });

  it('sets _lastCommandResult', async () => {
    await el.handleCommand('frame-create', {
      tabs: [{ terminalName: 'repo-x', type: 'repo' }],
    });
    const state = el.getUIState();
    expect(state.commandResult).toBeDefined();
    expect(state.commandResult.ok).toBe(true);
  });

  it('frame-detach returns electron-only error in browser mode', async () => {
    const frameId = el.createFrame([{ terminalName: 'repo-d', type: 'repo' as const }]);
    const result = await el.handleCommand('frame-detach', { frameId });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('electron only');
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `yarn --cwd sidecar/src/main/webui test -- --run workspace-view`
Expected: FAIL — `handleCommand` is not a function

- [ ] **Step 3: Implement wrapper methods**

Add to `TrellisWorkspaceView` class:

```typescript
private _moveFrame(frameId: string, position: { x: number; y: number }) {
  const clamped = clampPosition(position,
    this._frameSizes.get(frameId) ?? { width: 600, height: 400 },
    { width: this._container?.clientWidth ?? 1200, height: this._container?.clientHeight ?? 800 });
  this._framePositions.set(frameId, clamped);
  const group = this._frameGroups.get(frameId);
  if (group) {
    const el = group.element ?? group.header?.element?.parentElement;
    if (el) {
      el.style.transform = `translate(${clamped.x}px, ${clamped.y}px)`;
    }
  }
  this._scheduleSave();
}

private _resizeFrame(frameId: string, size: { width: number; height: number }) {
  this._frameSizes.set(frameId, size);
  const group = this._frameGroups.get(frameId);
  if (group) {
    const el = group.element ?? group.header?.element?.parentElement;
    if (el) {
      el.style.width = size.width + 'px';
      el.style.height = size.height + 'px';
    }
  }
  const tabs = this._frameTabs.get(frameId);
  if (tabs) {
    setTimeout(() => {
      for (const tab of tabs) {
        const termEl = this._terminalElements.get(tab.terminalName);
        if (termEl && typeof (termEl as any).fit === 'function') {
          (termEl as any).fit();
        }
      }
    }, 150);
  }
  this._scheduleSave();
}

private async _addTab(frameId: string, tab: TabRef) {
  this._activeTerminals.add(tab.terminalName);
  const tabs = this._frameTabs.get(frameId) ?? [];
  tabs.push(tab);
  this._frameTabs.set(frameId, tabs);
  const group = this._frameGroups.get(frameId);
  if (group && this._dockview) {
    this._dockview.addPanel({
      id: tab.terminalName,
      title: tab.terminalName.replace(/^(repo-|slot-)/, ''),
      component: 'terminal',
      position: { referenceGroup: group },
    });
  }
  await this._ensureTerminalExists(tab.terminalName);
  const termEl = this._terminalElements.get(tab.terminalName);
  if (termEl) this._connectTerminal(tab.terminalName, termEl);
  this._scheduleSave();
}

private _removeTab(frameId: string, tabIndex: number) {
  const tabs = this._frameTabs.get(frameId);
  if (!tabs || tabIndex < 0 || tabIndex >= tabs.length) return;
  const removed = tabs.splice(tabIndex, 1)[0];
  this._activeTerminals.delete(removed.terminalName);
  const group = this._frameGroups.get(frameId);
  if (group) {
    const panels = [...group.panels];
    if (panels[tabIndex]) {
      try { group.model.removePanel(panels[tabIndex]); } catch { /* ok */ }
    }
  }
  if (tabs.length === 0) {
    this.hideFrame(frameId);
    this.deleteFrame(frameId);
  } else {
    const activeIdx = this._frameActiveTab.get(frameId) ?? 0;
    if (activeIdx >= tabs.length) {
      this._frameActiveTab.set(frameId, tabs.length - 1);
    }
    this._scheduleSave();
  }
}

private async _deleteGroupById(groupId: string) {
  if (!this.workspaceRoot) return;
  const data = await this._loadGroupsData();
  data.groups = data.groups.filter(g => g.id !== groupId);
  await this._saveGroupsData(data);
  for (const [fid, gid] of this._frameGroupIds) {
    if (gid === groupId) this._frameGroupIds.delete(fid);
  }
}
```

- [ ] **Step 4: Implement `handleCommand()`**

Add to `TrellisWorkspaceView` class:

```typescript
async handleCommand(command: string, params?: any): Promise<{ ok: boolean; error?: string; frameId?: string }> {
  let result: { ok: boolean; error?: string; frameId?: string };

  switch (command) {
    case 'frame-create': {
      const id = this.createFrame(params?.tabs ?? [], params?.groupId, params?.name, params);
      result = id ? { ok: true, frameId: id } : { ok: false, error: 'no valid tabs' };
      break;
    }
    case 'frame-remove': {
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      this.hideFrame(params.frameId);
      this.deleteFrame(params.frameId);
      result = { ok: true };
      break;
    }
    case 'frame-move': {
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      this._moveFrame(params.frameId, params.position);
      result = { ok: true };
      break;
    }
    case 'frame-resize': {
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      this._resizeFrame(params.frameId, params.size);
      result = { ok: true };
      break;
    }
    case 'frame-pin': {
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (!this._pinnedFrames.has(params.frameId)) this.togglePin(params.frameId);
      result = { ok: true };
      break;
    }
    case 'frame-unpin': {
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (this._pinnedFrames.has(params.frameId)) this.togglePin(params.frameId);
      result = { ok: true };
      break;
    }
    case 'frame-detach': {
      if (this._browserMode) { result = { ok: false, error: 'electron only' }; break; }
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      this._focusedFrameId = params.frameId;
      await this._detachFrame();
      result = { ok: true };
      break;
    }
    case 'frame-attach': {
      if (this._browserMode) { result = { ok: false, error: 'electron only' }; break; }
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      await this._attachToMainWindow(params.frameId);
      result = { ok: true };
      break;
    }
    case 'tab-add': {
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (!params?.tab?.terminalName) { result = { ok: false, error: 'tab.terminalName required' }; break; }
      if (this._activeTerminals.has(params.tab.terminalName)) { result = { ok: false, error: 'terminal already open' }; break; }
      await this._addTab(params.frameId, params.tab);
      result = { ok: true };
      break;
    }
    case 'tab-remove': {
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (!params?.terminalName) { result = { ok: false, error: 'terminalName required' }; break; }
      const tabs = this._frameTabs.get(params.frameId);
      const tabIdx = tabs?.findIndex(t => t.terminalName === params.terminalName);
      if (tabIdx === undefined || tabIdx < 0) { result = { ok: false, error: 'terminal not in frame' }; break; }
      this._removeTab(params.frameId, tabIdx);
      result = { ok: true };
      break;
    }
    case 'group-save': {
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (!params?.name) { result = { ok: false, error: 'name required' }; break; }
      this._focusedFrameId = params.frameId;
      await this._saveFrameAsGroup(params.name);
      result = { ok: true };
      break;
    }
    case 'group-update': {
      if (!this._frameOrders.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; }
      if (!this._frameGroupIds.has(params.frameId)) { result = { ok: false, error: 'frame has no group' }; break; }
      await this._updateGroup(params.frameId);
      result = { ok: true };
      break;
    }
    case 'group-delete': {
      if (!params?.groupId) { result = { ok: false, error: 'groupId required' }; break; }
      await this._deleteGroupById(params.groupId);
      result = { ok: true };
      break;
    }
    case 'organiser-apply': {
      const validPresets = ['side-by-side', 'stacked', 'grid', 'main-sidebar', 'focus'];
      if (!validPresets.includes(params?.preset)) { result = { ok: false, error: 'unknown preset: ' + params?.preset }; break; }
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

- [ ] **Step 5: Implement `focusFrame()` and `focusTab()`**

Add to `TrellisWorkspaceView` class:

```typescript
focusFrame(frameId: string) {
  if (!this._frameOrders.has(frameId)) return;
  this._focusedFrameId = frameId;
  this.bringToFront(frameId);
  const activeIdx = this._frameActiveTab.get(frameId) ?? 0;
  const tabs = this._frameTabs.get(frameId);
  if (tabs?.[activeIdx]) {
    this._terminalElements.get(tabs[activeIdx].terminalName)?.focus();
  }
}

focusTab(frameId: string, tabIndex: number) {
  if (!this._frameOrders.has(frameId)) return;
  this._frameActiveTab.set(frameId, tabIndex);
  const tabs = this._frameTabs.get(frameId);
  if (tabs?.[tabIndex]) {
    const group = this._frameGroups.get(frameId);
    if (group) {
      const panels = [...group.panels];
      if (panels[tabIndex]) {
        try { group.model.openPanel(panels[tabIndex]); } catch { /* ok */ }
      }
    }
    this._terminalElements.get(tabs[tabIndex].terminalName)?.focus();
  }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `yarn --cwd sidecar/src/main/webui test -- --run workspace-view`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add sidecar/src/main/webui/src/components/workspace-view.ts sidecar/src/main/webui/src/components/workspace-view.test.ts
git commit -m "feat(#43): handleCommand + wrapper methods for frame/tab management  Refs #43"
```

---

### Task 4: SSE transport + navigate frame focus

**Files:**
- Modify: `sidecar/src/main/webui/src/components/workbench.ts`

**Interfaces:**
- Consumes: `TrellisWorkspaceView.handleCommand()` (Task 3),
  `TrellisWorkspaceView.focusFrame()`, `TrellisWorkspaceView.focusTab()`
  (Task 3)
- Produces: `_handleWorkspaceCommand()` — internal, invoked by SSE
  listener. Extended `_handleNavigateEvent()` — handles frame/tab paths.

**Note:** workbench.ts has no dedicated test file in the project
currently. The workbench is tested via integration tests. Add inline
validation via the existing pattern (SSE subscription setup, hash
parsing).

- [ ] **Step 1: Extend SSE subscription to `control:workspace`**

In `_connectSSE()`, change the topic subscription:

```typescript
private _connectSSE() {
  this._eventSource = new EventSource('/api/push?topics=control:navigate,control:workspace');
  this._eventSource.addEventListener('message', (event: MessageEvent) => {
    try {
      const msg = JSON.parse(event.data);
      if (msg.topic === 'control:navigate' && msg.payload) {
        const payload = typeof msg.payload === 'string' ? JSON.parse(msg.payload) : msg.payload;
        this._handleNavigateEvent(payload);
      } else if (msg.topic === 'control:workspace' && msg.payload) {
        const payload = typeof msg.payload === 'string' ? JSON.parse(msg.payload) : msg.payload;
        this._handleWorkspaceCommand(payload);
      }
    } catch { /* ignore parse errors */ }
  });
}
```

- [ ] **Step 2: Add `_handleWorkspaceCommand`**

Add new method to `TrellisWorkbench` class:

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

- [ ] **Step 3: Extend `_handleNavigateEvent` for frame/tab focus**

Replace the workspace panel handling section in `_handleNavigateEvent`:

```typescript
_handleNavigateEvent(payload: { target: string; correlationId?: string }) {
  const { target, correlationId } = payload;
  if (correlationId) {
    this._pendingCorrelationId = correlationId;
  }

  if (target.startsWith('dock-bar/')) {
    const panelId = target.substring('dock-bar/'.length);
    if (PANELS[panelId]) {
      this._activatePanel(panelId);
    }
  } else if (target.startsWith('panels/')) {
    const parts = target.substring('panels/'.length).split('/');
    const panelId = parts[0];
    if (panelId === 'workspace-view' || panelId === 'workspace') {
      this._activatePanel('workspace');
      if (parts.length >= 3 && parts[1] === 'frames') {
        const wsView = this._panelCache.get('workspace');
        if (wsView && typeof (wsView as any).focusFrame === 'function') {
          (wsView as any).focusFrame(parts[2]);
          if (parts.length >= 5 && parts[3] === 'tabs') {
            (wsView as any).focusTab(parts[2], parseInt(parts[4], 10));
          }
        }
      }
    } else if (PANELS[panelId]) {
      this._activatePanel(panelId);
    }
  }

  this._pushUIStateImmediate();
}
```

- [ ] **Step 4: Verify sidecar compiles and frontend builds**

Run both:
```bash
/opt/homebrew/bin/mvn -f sidecar/pom.xml compile
yarn --cwd sidecar/src/main/webui build
```
Expected: Both succeed

- [ ] **Step 5: Run all tests**

```bash
/opt/homebrew/bin/mvn -f sidecar/pom.xml test
yarn --cwd sidecar/src/main/webui test -- --run
```
Expected: All pass

- [ ] **Step 6: Commit**

```bash
git add sidecar/src/main/webui/src/components/workbench.ts
git commit -m "feat(#43): SSE control:workspace subscription + navigate frame focus  Refs #43"
```

---

### Task 5: CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:** None — documentation only.

- [ ] **Step 1: Remove "6 tools" convention**

In `CLAUDE.md`, find and update the two lines that enforce the 6-tool
constraint:

Change:
```
- `quarkus-mcp-server` embedded in sidecar — 6 `@Tool` methods on `TrellisTools` CDI bean (`trellis_model`, `trellis_navigate`, `trellis_terminal`, `trellis_agent`, `trellis_lifecycle`, `trellis_workspace`)
- MCP tool surface is stable at 6 tools — new capabilities extend the model, not the tool list
```

To:
```
- `quarkus-mcp-server` embedded in sidecar — `@Tool` methods on `TrellisTools` CDI bean (`trellis_model`, `trellis_navigate`, `trellis_terminal`, `trellis_agent`, `trellis_lifecycle`, `trellis_workspace`)
- MCP tool count follows the design — new capabilities extend existing tools or add tools as needed
```

- [ ] **Step 2: Add `trellis_workspace` frame management convention**

Add to the Key Conventions section, after the existing
`trellis_workspace` entry:

```
- `trellis_workspace` extended with `operation` + `params` for frame/tab management — dispatches SSE commands to frontend via `control:workspace` topic
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(#43): update CLAUDE.md — remove 6-tool constraint, add workspace frame management  Refs #43"
```
