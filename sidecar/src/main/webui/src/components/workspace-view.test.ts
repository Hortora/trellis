import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

vi.mock('dockview-core', () => ({
  DockviewComponent: class {
    dispose() {}
    addPanel() { return { group: { panels: [] } }; }
    removePanel() {}
    onDidLayoutChange() { return { dispose() {} }; }
    onDidActivePanelChange() { return { dispose() {} }; }
    get panels() { return []; }
    getPanel() { return null; }
  },
  themeDark: { className: 'dockview-theme-dark' },
}));

const { TrellisWorkspaceView, toFrameTabConfig, toTabRef } = await import('./workspace-view.js');
const { nextFramePosition, clampPosition } = await import('@casehubio/pages-runtime/dist/frame-boundaries.js');

async function createEl(): Promise<InstanceType<typeof TrellisWorkspaceView>> {
  const el = document.createElement('trellis-workspace-view') as any;
  document.body.appendChild(el);
  await el.updateComplete;
  for (let i = 0; i < 20 && !el._engine; i++) await new Promise(r => setTimeout(r, 50));
  return el;
}

describe('type bridge', () => {
  it('converts TabRef to FrameTabConfig', () => {
    const config = toFrameTabConfig({ terminalName: 'repo-engine', type: 'repo' as const });
    expect(config.key).toBe('repo-engine');
    expect(config.label).toBe('engine');
  });

  it('converts slot TabRef with correct label', () => {
    const config = toFrameTabConfig({ terminalName: 'slot-3', type: 'slot' as const });
    expect(config.key).toBe('slot-3');
    expect(config.label).toBe('3');
  });

  it('converts FrameTabConfig back to TabRef', () => {
    const tab = toTabRef({ key: 'repo-engine', label: 'engine', content: {} as any });
    expect(tab.terminalName).toBe('repo-engine');
    expect(tab.type).toBe('repo');
  });

  it('infers slot type from key prefix', () => {
    const tab = toTabRef({ key: 'slot-3', label: '3', content: {} as any });
    expect(tab.type).toBe('slot');
  });
});

describe('frame CRUD via engine', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => { el = await createEl(); });
  afterEach(() => { el.remove(); });

  it('createFrame returns frameId and adds to engine', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    expect(frameId).toBeTruthy();
    expect((el as any)._engine.frames.has(frameId)).toBe(true);
    expect((el as any)._engine.frames.get(frameId).tabs.length).toBe(1);
    expect((el as any)._engine.frames.get(frameId).tabs[0].key).toBe('repo-a');
  });

  it('createFrame rejects duplicate terminals', () => {
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    const f2 = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    expect(f2).toBe('');
  });

  it('createFrame filters out duplicate tabs from mixed input', () => {
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    const f2 = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }, { terminalName: 'repo-b', type: 'repo' }]);
    expect(f2).not.toBe('');
    const frame = (el as any)._engine.frames.get(f2);
    expect(frame.tabs.length).toBe(1);
    expect(frame.tabs[0].key).toBe('repo-b');
  });

  it('hideFrame removes from active terminals and hides in engine', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any).hideFrame(frameId);
    expect((el as any)._activeTerminals.has('repo-a')).toBe(false);
    expect((el as any)._engine.frames.get(frameId).hidden).toBe(true);
  });

  it('showFrame re-adds to active terminals', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any).hideFrame(frameId);
    (el as any).showFrame(frameId);
    expect((el as any)._engine.frames.get(frameId).hidden).toBe(false);
    expect((el as any)._activeTerminals.has('repo-a')).toBe(true);
  });

  it('deleteFrame removes from engine entirely', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any).deleteFrame(frameId);
    expect((el as any)._engine.frames.has(frameId)).toBe(false);
  });

  it('togglePin delegates to engine', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    expect((el as any)._engine.frames.get(frameId).pinned).toBe(false);
    (el as any).togglePin(frameId);
    expect((el as any)._engine.frames.get(frameId).pinned).toBe(true);
    (el as any).togglePin(frameId);
    expect((el as any)._engine.frames.get(frameId).pinned).toBe(false);
  });

  it('bringToFront sets focusedFrameId', () => {
    const f1 = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    const f2 = (el as any).createFrame([{ terminalName: 'repo-b', type: 'repo' }]);
    expect((el as any)._focusedFrameId).toBe(f2);
    (el as any).bringToFront(f1);
    expect((el as any)._focusedFrameId).toBe(f1);
  });

  it('isTerminalOpen returns true for open terminals', () => {
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    expect((el as any).isTerminalOpen('repo-a')).toBe(true);
    expect((el as any).isTerminalOpen('repo-z')).toBe(false);
  });
});

describe('z-order via engine', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => { el = await createEl(); });
  afterEach(() => { el.remove(); });

  it('second frame has higher zIndex than first', () => {
    const f1 = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    const f2 = (el as any).createFrame([{ terminalName: 'repo-b', type: 'repo' }]);
    expect((el as any)._engine.frames.get(f2).zIndex).toBeGreaterThan((el as any)._engine.frames.get(f1).zIndex);
  });

  it('bringToFront raises zIndex', () => {
    const f1 = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    const f2 = (el as any).createFrame([{ terminalName: 'repo-b', type: 'repo' }]);
    const z1Before = (el as any)._engine.frames.get(f1).zIndex;
    (el as any).bringToFront(f1);
    expect((el as any)._engine.frames.get(f1).zIndex).toBeGreaterThan(z1Before);
  });
});

describe('tab navigation via engine', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => { el = await createEl(); });
  afterEach(() => { el.remove(); });

  it('nextTab cycles active tab key', () => {
    const f1 = (el as any).createFrame([
      { terminalName: 'repo-a', type: 'repo' },
      { terminalName: 'repo-b', type: 'repo' },
      { terminalName: 'repo-c', type: 'repo' },
    ]);
    (el as any)._focusedFrameId = f1;
    const frame0 = (el as any)._engine.frames.get(f1);
    expect(frame0.activeTabKey).toBe('repo-a');

    (el as any)._nextTab();
    expect((el as any)._engine.frames.get(f1).activeTabKey).toBe('repo-b');

    (el as any)._nextTab();
    expect((el as any)._engine.frames.get(f1).activeTabKey).toBe('repo-c');

    (el as any)._nextTab();
    expect((el as any)._engine.frames.get(f1).activeTabKey).toBe('repo-a');
  });

  it('prevTab cycles backward', () => {
    const f1 = (el as any).createFrame([
      { terminalName: 'repo-a', type: 'repo' },
      { terminalName: 'repo-b', type: 'repo' },
    ]);
    (el as any)._focusedFrameId = f1;

    (el as any)._prevTab();
    expect((el as any)._engine.frames.get(f1).activeTabKey).toBe('repo-b');
  });

  it('jumpToTab sets active tab by index', () => {
    const f1 = (el as any).createFrame([
      { terminalName: 'repo-a', type: 'repo' },
      { terminalName: 'repo-b', type: 'repo' },
      { terminalName: 'repo-c', type: 'repo' },
    ]);
    (el as any)._focusedFrameId = f1;

    (el as any)._jumpToTab(2);
    expect((el as any)._engine.frames.get(f1).activeTabKey).toBe('repo-c');
  });

  it('jumpToTab out of bounds is a no-op', () => {
    const f1 = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any)._focusedFrameId = f1;
    (el as any)._jumpToTab(5);
    expect((el as any)._engine.frames.get(f1).activeTabKey).toBe('repo-a');
  });
});

describe('handleCommand', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => {
    el = await createEl();
    (el as any).workspaceRoot = '/tmp/test-ws';
  });
  afterEach(() => { el.remove(); });

  it('frame-create returns ok with frameId', async () => {
    const result = await (el as any).handleCommand('frame-create', { tabs: [{ terminalName: 'repo-engine', type: 'repo' }] });
    expect(result.ok).toBe(true);
    expect(result.frameId).toBeTruthy();
  });

  it('frame-create with empty tabs returns error', async () => {
    const result = await (el as any).handleCommand('frame-create', { tabs: [] });
    expect(result.ok).toBe(false);
  });

  it('frame-remove on existing frame succeeds', async () => {
    const create = await (el as any).handleCommand('frame-create', { tabs: [{ terminalName: 'repo-test', type: 'repo' }] });
    const result = await (el as any).handleCommand('frame-remove', { frameId: create.frameId });
    expect(result.ok).toBe(true);
    expect((el as any).isTerminalOpen('repo-test')).toBe(false);
  });

  it('frame-remove on unknown frame returns error', async () => {
    const result = await (el as any).handleCommand('frame-remove', { frameId: 'nonexistent' });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('not found');
  });

  it('frame-pin is idempotent', async () => {
    const create = await (el as any).handleCommand('frame-create', { tabs: [{ terminalName: 'repo-a', type: 'repo' }] });
    await (el as any).handleCommand('frame-pin', { frameId: create.frameId });
    const result = await (el as any).handleCommand('frame-pin', { frameId: create.frameId });
    expect(result.ok).toBe(true);
  });

  it('tab-add validates terminalName', async () => {
    const create = await (el as any).handleCommand('frame-create', { tabs: [{ terminalName: 'repo-a', type: 'repo' }] });
    const result = await (el as any).handleCommand('tab-add', { frameId: create.frameId, tab: {} });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('terminalName required');
  });

  it('tab-add rejects duplicate terminal', async () => {
    const create = await (el as any).handleCommand('frame-create', { tabs: [{ terminalName: 'repo-a', type: 'repo' }] });
    const result = await (el as any).handleCommand('tab-add', { frameId: create.frameId, tab: { terminalName: 'repo-a', type: 'repo' } });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('already open');
  });

  it('tab-remove uses terminalName', async () => {
    const create = await (el as any).handleCommand('frame-create', { tabs: [{ terminalName: 'repo-a', type: 'repo' }, { terminalName: 'repo-b', type: 'repo' }] });
    const result = await (el as any).handleCommand('tab-remove', { frameId: create.frameId, terminalName: 'repo-a' });
    expect(result.ok).toBe(true);
    expect((el as any).isTerminalOpen('repo-a')).toBe(false);
    expect((el as any).isTerminalOpen('repo-b')).toBe(true);
  });

  it('tab-remove with unknown terminal returns error', async () => {
    const create = await (el as any).handleCommand('frame-create', { tabs: [{ terminalName: 'repo-a', type: 'repo' }] });
    const result = await (el as any).handleCommand('tab-remove', { frameId: create.frameId, terminalName: 'repo-z' });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('not in frame');
  });

  it('organiser-apply validates preset name', async () => {
    const result = await (el as any).handleCommand('organiser-apply', { preset: 'invalid' });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('unknown preset');
  });

  it('unknown command returns error', async () => {
    const result = await (el as any).handleCommand('bogus', {});
    expect(result.ok).toBe(false);
    expect(result.error).toContain('unknown command');
  });

  it('sets _lastCommandResult', async () => {
    await (el as any).handleCommand('frame-create', { tabs: [{ terminalName: 'repo-x', type: 'repo' }] });
    const state = (el as any).getUIState();
    expect(state.commandResult).toBeDefined();
    expect(state.commandResult.ok).toBe(true);
  });

  it('frame-detach returns electron-only error in browser mode', async () => {
    const create = await (el as any).handleCommand('frame-create', { tabs: [{ terminalName: 'repo-d', type: 'repo' }] });
    const result = await (el as any).handleCommand('frame-detach', { frameId: create.frameId });
    expect(result.ok).toBe(false);
    expect(result.error).toContain('electron only');
  });
});

describe('getUIState', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => { el = await createEl(); });
  afterEach(() => { el.remove(); });

  it('returns empty workspace state when no frames exist', () => {
    const state = (el as any).getUIState();
    expect(state.frames).toEqual([]);
    expect(state).toHaveProperty('focusedFrameId');
    expect(state.actions.length).toBeGreaterThan(0);
    expect(state.actions.find((a: any) => a.operation === 'frame-create')).toBeTruthy();
    expect(state.actions.find((a: any) => a.operation === 'organiser-apply')).toBeTruthy();
  });

  it('returns frames with action descriptors', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-engine', type: 'repo' }]);
    const state = (el as any).getUIState();
    expect(state.frames.length).toBe(1);
    const frame = state.frames[0];
    expect(frame.id).toBe(frameId);
    expect(frame.actions).toBeDefined();
    expect(frame.tabs.length).toBe(1);
    expect(frame.tabs[0].terminalName).toBe('repo-engine');
  });

  it('includes commandResult when present and clears it', () => {
    (el as any)._lastCommandResult = { ok: true, frameId: 'f1' };
    const state1 = (el as any).getUIState();
    expect(state1.commandResult).toEqual({ ok: true, frameId: 'f1' });
    const state2 = (el as any).getUIState();
    expect(state2.commandResult).toBeUndefined();
  });
});

describe('group CRUD', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => {
    let savedGroups: any = { groups: [] };
    (window as any).trellis = {
      saveGroups: vi.fn((_root: string, data: any) => { savedGroups = data; return Promise.resolve(); }),
      loadGroups: vi.fn(() => Promise.resolve(savedGroups)),
      saveWindowLayout: vi.fn(), onShortcut: vi.fn(), onLayoutFlush: vi.fn(),
      onFrameInit: vi.fn(), onFrameReceive: vi.fn(), getLastWorkspacePath: vi.fn(() => Promise.resolve(null)),
    };
    el = await createEl();
    (el as any).workspaceRoot = '/test';
  });

  afterEach(() => { el.remove(); delete (window as any).trellis; });

  it('saves focused frame tabs as a named group', async () => {
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }, { terminalName: 'repo-b', type: 'repo' }]);
    await (el as any)._saveFrameAsGroup('Engine Repos');
    const data = (window as any).trellis.saveGroups.mock.calls.at(-1)[1];
    expect(data.groups).toHaveLength(1);
    expect(data.groups[0].name).toBe('Engine Repos');
    expect(data.groups[0].tabs).toHaveLength(2);
  });

  it('sets groupId on frame after saving', async () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    await (el as any)._saveFrameAsGroup('My Group');
    expect((el as any)._frameGroupIds.has(frameId)).toBe(true);
  });
});

describe('keyboard shortcuts', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => { el = await createEl(); });
  afterEach(() => { el.remove(); });

  it('Cmd+Shift+Backspace calls deleteGroup on focused frame with groupId', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any)._frameGroupIds.set(frameId, 'group-x');
    const spy = vi.spyOn(el as any, '_deleteGroup');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', metaKey: true, shiftKey: true }));
    expect(spy).toHaveBeenCalledWith(frameId);
  });
});

describe('toolbar', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => { el = await createEl(); });
  afterEach(() => { el.remove(); });

  it('renders toolbar with New Frame and Frames buttons', () => {
    const toolbar = el.shadowRoot!.querySelector('.workspace-toolbar');
    expect(toolbar).not.toBeNull();
    expect(toolbar!.querySelector('.new-frame-btn')).not.toBeNull();
    expect(toolbar!.querySelector('.frames-btn')).not.toBeNull();
  });
});

describe('picker UI', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => {
    globalThis.fetch = vi.fn((url: string) => {
      if (url.includes('/api/workspace')) {
        return Promise.resolve(new Response(JSON.stringify({
          root: '/test',
          repos: [{ name: 'engine', path: '/test/engine', branch: 'main', remoteUrl: '' }, { name: 'ledger', path: '/test/ledger', branch: 'feat/x', remoteUrl: '' }],
          slots: [{ number: 1, path: '/slots/1', issue: 'org/repo#5', status: 'ACTIVE', repos: ['engine'] }],
        })));
      }
      return Promise.resolve(new Response('{}'));
    }) as any;
    el = await createEl();
    (el as any).workspaceRoot = '/test';
  });

  afterEach(() => { el.remove(); });

  it('opens picker when New Frame is clicked', async () => {
    const btn = el.shadowRoot!.querySelector('.new-frame-btn') as HTMLElement;
    btn.click();
    await new Promise(r => setTimeout(r, 50));
    expect(el.shadowRoot!.querySelector('.workspace-picker')).not.toBeNull();
  });

  it('dismisses picker on Escape', async () => {
    const btn = el.shadowRoot!.querySelector('.new-frame-btn') as HTMLElement;
    btn.click();
    await new Promise(r => setTimeout(r, 50));
    expect(el.shadowRoot!.querySelector('.workspace-picker')).not.toBeNull();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await new Promise(r => setTimeout(r, 10));
    expect(el.shadowRoot!.querySelector('.workspace-picker')).toBeNull();
  });
});

describe('REST persistence fallback', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;
  let fetchCalls: { url: string; method: string; body?: string }[];

  beforeEach(async () => {
    fetchCalls = [];
    globalThis.fetch = vi.fn((url: string, opts?: any) => {
      const method = opts?.method || 'GET';
      fetchCalls.push({ url, method, body: opts?.body });
      if (typeof url === 'string' && url.includes('/api/layouts/workspace-frames') && method === 'GET') return Promise.resolve(new Response(JSON.stringify({ windows: [{ id: 'shell-1', isMain: true, frames: [] }] })));
      if (typeof url === 'string' && url.includes('/api/layouts/workspace-groups') && method === 'GET') return Promise.resolve(new Response(JSON.stringify({ groups: [] })));
      if (method === 'PUT') return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(new Response('{}'));
    }) as any;
    el = await createEl();
    (el as any).workspaceRoot = '/test';
  });

  afterEach(() => { el.remove(); });

  it('detects browser mode when window.trellis is absent', () => {
    expect((el as any)._browserMode).toBe(true);
  });

  it('saves layout via REST PUT in browser mode', async () => {
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any)._doSave();
    await new Promise(r => setTimeout(r, 50));
    const putCalls = fetchCalls.filter(c => c.url.includes('/api/layouts/workspace-frames') && c.method === 'PUT');
    expect(putCalls.length).toBeGreaterThanOrEqual(1);
    const body = JSON.parse(putCalls[0].body!);
    expect(body.windows[0].frames.length).toBe(1);
  });
});

describe('serialization round-trip', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => { el = await createEl(); });
  afterEach(() => { el.remove(); });

  it('serializes frames with correct tab format', () => {
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }, { terminalName: 'repo-b', type: 'repo' }]);
    const layout = (el as any)._serializeLayout();
    expect(layout.frames.length).toBe(1);
    expect(layout.frames[0].tabs[0].terminalName).toBe('repo-a');
    expect(layout.frames[0].tabs[1].terminalName).toBe('repo-b');
  });

  it('normalizes z-indices on serialize', () => {
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any).createFrame([{ terminalName: 'repo-b', type: 'repo' }]);
    (el as any).createFrame([{ terminalName: 'repo-c', type: 'repo' }]);
    const layout = (el as any)._serializeLayout();
    const zIndices = layout.frames.map((f: any) => f.zIndex).sort((a: number, b: number) => a - b);
    expect(zIndices).toEqual([1, 2, 3]);
  });

  it('includes groupId in serialized layout', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }], 'group-1');
    const layout = (el as any)._serializeLayout();
    const frame = layout.frames.find((f: any) => f.id === frameId);
    expect(frame.groupId).toBe('group-1');
  });

  it('includes pinned state in serialized layout', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any).togglePin(frameId);
    const layout = (el as any)._serializeLayout();
    const frame = layout.frames.find((f: any) => f.id === frameId);
    expect(frame.pinned).toBe(true);
  });

  it('includes fontSize in serialized layout when set', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any)._frameFontSizes.set(frameId, 15);
    const layout = (el as any)._serializeLayout();
    const frame = layout.frames.find((f: any) => f.id === frameId);
    expect(frame.fontSize).toBe(15);
  });

  it('omits fontSize when not set', () => {
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    const layout = (el as any)._serializeLayout();
    expect(layout.frames[0].fontSize).toBeUndefined();
  });
});

describe('font size', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => { el = await createEl(); });
  afterEach(() => { el.remove(); });

  it('restores fontSize from persisted layout', () => {
    const frameId = (el as any).createFrame(
      [{ terminalName: 'repo-a', type: 'repo' }],
      undefined, undefined,
      { id: 'f-1', fontSize: 18, order: 0, position: { x: 0, y: 0 }, size: { width: 600, height: 400 }, zIndex: 1, pinned: false, tabs: [], activeTabIndex: 0 },
    );
    expect((el as any)._frameFontSizes.get(frameId)).toBe(18);
  });

  it('cycleFontSize advances through preset sizes', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    expect((el as any)._frameFontSizes.get(frameId)).toBeUndefined();
    (el as any)._cycleFontSize(frameId);
    expect((el as any)._frameFontSizes.get(frameId)).toBe(15);
    (el as any)._cycleFontSize(frameId);
    expect((el as any)._frameFontSizes.get(frameId)).toBe(18);
    (el as any)._cycleFontSize(frameId);
    expect((el as any)._frameFontSizes.get(frameId)).toBe(11);
    (el as any)._cycleFontSize(frameId);
    expect((el as any)._frameFontSizes.get(frameId)).toBe(13);
  });

  it('deleteFrame cleans up font size', () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any)._frameFontSizes.set(frameId, 15);
    (el as any).deleteFrame(frameId);
    expect((el as any)._frameFontSizes.has(frameId)).toBe(false);
  });

  it('frameForTerminal finds the correct frame', () => {
    const f1 = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any).createFrame([{ terminalName: 'repo-b', type: 'repo' }]);
    expect((el as any)._frameForTerminal('repo-a')).toBe(f1);
    expect((el as any)._frameForTerminal('repo-z')).toBeUndefined();
  });
});

describe('flyout data assembly', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => {
    (window as any).trellis = {
      saveWindowLayout: vi.fn(), onShortcut: vi.fn(), onLayoutFlush: vi.fn(),
      onFrameInit: vi.fn(), onFrameReceive: vi.fn(), getLastWorkspacePath: vi.fn(() => Promise.resolve(null)),
    };
    globalThis.fetch = vi.fn((url: string) => {
      if (typeof url === 'string' && url.includes('/api/workspace/repo')) return Promise.resolve(new Response(JSON.stringify({ name: 'engine', branch: 'feat/issue-42', path: '/home/dev/casehub/engine', remoteUrl: 'git@github.com:org/engine.git' })));
      if (typeof url === 'string' && url.includes('/api/terminals/repo-engine')) return Promise.resolve(new Response(JSON.stringify({ name: 'repo-engine', issue: 'org/engine#42 — Add OAuth2 flow', agent: { status: 'RUNNING', memoryMb: 412, uptimeMs: 202000 } })));
      return Promise.resolve(new Response('{}'));
    }) as any;
    el = await createEl();
    (el as any).workspaceRoot = '/test';
  });

  afterEach(() => { el.remove(); delete (window as any).trellis; });

  it('populates flyout with repo metadata from REST', async () => {
    const flyout = document.createElement('trellis-tab-flyout') as any;
    await (el as any)._populateFlyout('repo-engine', flyout);
    expect(flyout.repoName).toBe('engine');
    expect(flyout.branch).toBe('feat/issue-42');
  });

  it('populates flyout with agent state from cache', async () => {
    (el as any)._agentStates.set('repo-engine', { status: 'RUNNING', memoryMb: 412, uptimeMs: 202000 });
    const flyout = document.createElement('trellis-tab-flyout') as any;
    await (el as any)._populateFlyout('repo-engine', flyout);
    expect(flyout.agentState).toBe('RUNNING');
  });

  it('updates agent state cache from SSE events', () => {
    (el as any)._handleAgentStateEvent({ terminal: 'repo-engine', status: 'IDLE', memoryMb: 0, uptimeMs: 0 });
    expect((el as any)._agentStates.get('repo-engine').status).toBe('IDLE');
  });
});

describe('nextFramePosition', () => {
  const container = { width: 1200, height: 800 };
  const frameSize = { width: 600, height: 400 };

  it('centers first frame', () => {
    const pos = nextFramePosition(container, frameSize, []);
    expect(pos.x).toBe(300);
    expect(pos.y).toBe(200);
  });

  it('displaces from existing frame', () => {
    const pos = nextFramePosition(container, frameSize, [{ x: 300, y: 200 }]);
    const dist = Math.hypot(pos.x - 300, pos.y - 200);
    expect(dist).toBeGreaterThanOrEqual(30);
  });

  it('keeps frames fully visible', () => {
    const pos = nextFramePosition(container, frameSize, [{ x: 580, y: 380 }]);
    expect(pos.x + frameSize.width).toBeLessThanOrEqual(container.width);
    expect(pos.y + frameSize.height).toBeLessThanOrEqual(container.height);
  });
});

describe('clampPosition', () => {
  it('does not change position within bounds', () => {
    expect(clampPosition({ x: 100, y: 100 }, { width: 600, height: 400 }, { width: 1200, height: 800 })).toEqual({ x: 100, y: 100 });
  });

  it('clamps negative positions to zero', () => {
    expect(clampPosition({ x: -50, y: -20 }, { width: 600, height: 400 }, { width: 1200, height: 800 })).toEqual({ x: 0, y: 0 });
  });

  it('clamps position exceeding right edge', () => {
    const result = clampPosition({ x: 900, y: 100 }, { width: 600, height: 400 }, { width: 1200, height: 800 });
    expect(result.x).toBe(600);
  });
});

describe('renderer lifecycle', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => {
    (window as any).trellis = {
      saveWindowLayout: vi.fn(), onShortcut: vi.fn(), onLayoutFlush: vi.fn(),
      onFrameInit: vi.fn(), onFrameReceive: vi.fn(), getLastWorkspacePath: vi.fn(() => Promise.resolve(null)),
      loadGroups: vi.fn(() => Promise.resolve({ groups: [] })),
      webglAcquire: vi.fn(() => Promise.resolve({ granted: true })),
      webglRelease: vi.fn(() => Promise.resolve()),
      onWebglGrant: vi.fn(), onWebglDemote: vi.fn(),
    };
    el = await createEl();
  });

  afterEach(() => { el.remove(); delete (window as any).trellis; });

  it('tracks renderer tiers per terminal', () => {
    expect((el as any)._rendererTiers).toBeDefined();
    expect((el as any)._rendererTiers instanceof Map).toBe(true);
  });

  it('updates tiers when focus changes', async () => {
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any).createFrame([{ terminalName: 'repo-b', type: 'repo' }]);
    await (el as any)._updateRendererTiers();
    expect((el as any)._rendererTiers.get('repo-b')).toBe('webgl');
    expect((el as any)._rendererTiers.get('repo-a')).toBe('canvas');
  });

  it('falls back to canvas when webgl acquire is denied', async () => {
    (window as any).trellis.webglAcquire = vi.fn(() => Promise.resolve({ granted: false }));
    (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    await (el as any)._updateRendererTiers();
    expect((el as any)._rendererTiers.get('repo-a')).toBe('canvas');
  });
});

describe('detach and reattach', () => {
  let el: InstanceType<typeof TrellisWorkspaceView>;

  beforeEach(async () => {
    (window as any).trellis = {
      saveWindowLayout: vi.fn(), onShortcut: vi.fn(), onLayoutFlush: vi.fn(),
      onFrameInit: vi.fn(), onFrameReceive: vi.fn(), getLastWorkspacePath: vi.fn(() => Promise.resolve(null)),
      loadGroups: vi.fn(() => Promise.resolve({ groups: [] })),
      inhibitSave: vi.fn(() => Promise.resolve()),
      releaseSave: vi.fn(() => Promise.resolve()),
      createWindow: vi.fn(() => Promise.resolve(42)),
    };
    el = await createEl();
    (el as any).workspaceRoot = '/test';
  });

  afterEach(() => { el.remove(); delete (window as any).trellis; });

  it('serializes and removes frame on detach', async () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }, { terminalName: 'repo-b', type: 'repo' }]);
    (el as any)._focusedFrameId = frameId;
    await (el as any)._detachFrame();
    expect((el as any)._engine.frames.has(frameId)).toBe(false);
    expect((window as any).trellis.createWindow).toHaveBeenCalled();
  });

  it('calls inhibitSave before and releaseSave after detach', async () => {
    const frameId = (el as any).createFrame([{ terminalName: 'repo-a', type: 'repo' }]);
    (el as any)._focusedFrameId = frameId;
    await (el as any)._detachFrame();
    expect((window as any).trellis.inhibitSave).toHaveBeenCalled();
    expect((window as any).trellis.releaseSave).toHaveBeenCalled();
  });

  it('does nothing if no focused frame', async () => {
    (el as any)._focusedFrameId = null;
    await (el as any)._detachFrame();
    expect((window as any).trellis.createWindow).not.toHaveBeenCalled();
  });
});
