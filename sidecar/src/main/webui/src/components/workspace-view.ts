import { LitElement, html, css, unsafeCSS } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import xtermCSS from '@xterm/xterm/css/xterm.css?raw';
import dockviewCSS from 'dockview-core/dist/styles/dockview.css?raw';
import { computeAllTiers, computeTransitions, type RendererTier } from './workspace-renderer-tiers.js';
import type { FrameTabConfig, ContentFactory } from '@casehubio/pages-component';
import type { FloatingFrameBackend, FloatingFrameEngine, Preset } from '@casehubio/pages-runtime';
import { createDockviewBackend } from '@casehubio/pages-runtime/dist/dockview-backend.js';
import { createFloatingFrameEngine } from '@casehubio/pages-runtime/dist/floating-frame-engine.js';
import { nextFramePosition, clampPosition } from '@casehubio/pages-runtime/dist/frame-boundaries.js';

export function toFrameTabConfig(tab: TabRef): FrameTabConfig {
  return { key: tab.terminalName, label: tab.terminalName.replace(/^(repo-|slot-)/, ''), content: {} as any };
}

export function toTabRef(tab: FrameTabConfig): TabRef {
  return { terminalName: tab.key, type: tab.key.startsWith('slot-') ? 'slot' : 'repo' };
}

interface TabRef { terminalName: string; type: 'repo' | 'slot'; }

interface FrameLayout {
  id: string; groupId?: string; order: number;
  position: { x: number; y: number }; size: { width: number; height: number };
  zIndex: number; pinned: boolean; tabs: TabRef[]; activeTabIndex: number;
}

interface Group { id: string; name: string; tabs: TabRef[]; }

interface ShellLayout {
  id: string; bounds: { x: number; y: number; width: number; height: number };
  isMain: boolean; frames: FrameLayout[]; lastActiveFrameId?: string;
}

@customElement('trellis-workspace-view')
export class TrellisWorkspaceView extends LitElement {
  @property() workspaceRoot = '';

  private _container: HTMLDivElement | null = null;
  private _activeTerminals = new Set<string>();
  private _lastCommandResult: { ok: boolean; error?: string; frameId?: string } | null = null;
  private _frameGroupIds = new Map<string, string>();
  private _focusedFrameId: string | null = null;
  private _saveDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private _saveMaxWaitTimer: ReturnType<typeof setTimeout> | null = null;
  private _lastSaveTime = 0;
  private _flyoutEl: HTMLElement | null = null;
  private _flyoutHideTimer: ReturnType<typeof setTimeout> | null = null;
  private _agentStates = new Map<string, any>();
  private _sseSource: EventSource | null = null;
  private _browserMode = false;
  private _rendererTiers = new Map<string, RendererTier>();
  private _rendererAddons = new Map<string, any>();
  private _connectedTerminals = new Set<string>();
  private _beforeUnloadHandler: (() => void) | null = null;
  private _restoring = false;
  private _engine: FloatingFrameEngine | null = null;
  private _backend: FloatingFrameBackend | null = null;
  private _terminalElements = new Map<string, HTMLElement>();
  private _pickerEl: HTMLElement | null = null;
  private _backdropEl: HTMLElement | null = null;
  private _pickerDismissEscape: ((e: KeyboardEvent) => void) | null = null;
  private _keydownHandler: ((e: KeyboardEvent) => void) | null = null;

  static override styles = [
    unsafeCSS(dockviewCSS),
    unsafeCSS(xtermCSS),
    css`
      :host { display: flex; flex-direction: column; width: 100%; height: 100%; background: #1e1e1e; color: #ccc; position: relative; overflow: hidden; }
      .workspace-toolbar { display: flex; align-items: center; height: 32px; padding: 0 8px; background: #252526; border-bottom: 1px solid #333; flex-shrink: 0; }
      .new-frame-btn, .frames-btn { background: transparent; border: 1px solid #555; color: #ccc; padding: 2px 10px; border-radius: 3px; cursor: pointer; font-size: 12px; margin-right: 6px; }
      .new-frame-btn:hover, .frames-btn:hover { background: #333; }
      .frames-section-header { padding: 6px 12px 2px; font-size: 11px; color: #888; text-transform: uppercase; letter-spacing: 0.5px; }
      .frames-row { display: flex; align-items: center; gap: 6px; padding: 4px 12px; }
      .frames-row-hidden { opacity: 0.6; }
      .frames-label { flex: 1; color: #ccc; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .frames-action { background: transparent; border: 1px solid #555; color: #ccc; padding: 1px 8px; border-radius: 3px; cursor: pointer; font-size: 11px; }
      .frames-action:hover { background: #333; }
      .frames-action-delete { border-color: #733; color: #c88; }
      .frames-action-delete:hover { background: #422; }
      .dockview-container { flex: 1; isolation: isolate; overflow: hidden; position: relative; }
      .picker-backdrop { position: absolute; inset: 0; z-index: 9999; }
      .workspace-picker { position: absolute; z-index: 10000; min-width: 280px; max-height: 420px; display: flex; flex-direction: column; background: #252526; border: 1px solid #555; border-radius: 4px; box-shadow: 0 4px 16px rgba(0,0,0,0.4); font-size: 13px; }
      .picker-tab-bar { display: flex; border-bottom: 1px solid #333; flex-shrink: 0; }
      .picker-tab { flex: 1; background: transparent; border: none; color: #888; padding: 6px 0; cursor: pointer; font-size: 12px; border-bottom: 2px solid transparent; }
      .picker-tab:hover { color: #ccc; }
      .picker-tab-active { color: #ccc; border-bottom-color: #3b82f6; }
      .picker-selected { display: flex; flex-wrap: wrap; gap: 4px; padding: 6px 8px; border-bottom: 1px solid #333; flex-shrink: 0; }
      .selected-chip { display: inline-flex; align-items: center; gap: 4px; background: #0e639c; color: #fff; padding: 2px 6px; border-radius: 3px; font-size: 11px; }
      .selected-chip-remove { background: none; border: none; color: #fff; cursor: pointer; font-size: 14px; padding: 0 2px; opacity: 0.7; }
      .selected-chip-remove:hover { opacity: 1; }
      .picker-scroll { flex: 1; overflow-y: auto; }
      .picker-section { padding: 4px 0; }
      .picker-empty { padding: 12px; color: #666; text-align: center; font-size: 12px; }
      .picker-item { display: flex; align-items: center; gap: 8px; padding: 4px 12px; cursor: pointer; }
      .picker-item:hover { background: #333; }
      .picker-item-disabled { opacity: 0.4; cursor: default; }
      .picker-item-disabled:hover { background: transparent; }
      .picker-name { flex: 1; color: #ccc; }
      .picker-branch { color: #666; font-size: 11px; }
      .picker-actions { display: flex; justify-content: flex-end; padding: 8px 12px 4px; }
      .picker-confirm { background: #0e639c; border: none; color: #fff; padding: 4px 12px; border-radius: 3px; cursor: pointer; font-size: 12px; }
      .picker-confirm:hover { background: #1177bb; }
      .picker-confirm:disabled { opacity: 0.4; cursor: default; }
      .xterm { padding: 4px; }
      .xterm-viewport, .xterm-screen { background-color: #1e1e1e !important; }
    `,
  ];

  override firstUpdated() {
    this._browserMode = !(window as any).trellis;
    this._container = this.shadowRoot!.querySelector('.dockview-container') as HTMLDivElement;
    this._initEngine().then(() => {
      requestAnimationFrame(() => requestAnimationFrame(() => this._restoreLayout()));
    });
    this._setupFlushHandler();
    this._setupKeyboard();
    this._setupShortcutIPC();
    this._setupDetachIPC();
    this._setupAgentSSE();
    this._setupWebglIPC();
    this._beforeUnloadHandler = () => this._saveBeforeUnload();
    window.addEventListener('beforeunload', this._beforeUnloadHandler);
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    if (this._engine) { this._engine.dispose(); this._engine = null; }
    if (this._saveDebounceTimer) clearTimeout(this._saveDebounceTimer);
    if (this._saveMaxWaitTimer) clearTimeout(this._saveMaxWaitTimer);
    if (this._keydownHandler) { document.removeEventListener('keydown', this._keydownHandler); this._keydownHandler = null; }
    this._hideTabFlyoutImmediate();
    if (this._flyoutHideTimer) clearTimeout(this._flyoutHideTimer);
    if (this._sseSource) { this._sseSource.close(); this._sseSource = null; }
    if (this._beforeUnloadHandler) { window.removeEventListener('beforeunload', this._beforeUnloadHandler); this._beforeUnloadHandler = null; }
  }

  private async _initEngine() {
    this._backend = await createDockviewBackend();
    const contentFactory: ContentFactory = (tab) => {
      const termEl = document.createElement('pages-component-terminal') as any;
      termEl.style.cssText = 'flex:1;overflow:hidden;';
      this._terminalElements.set(tab.key, termEl);
      this._connectTerminal(tab.key, termEl);
      return { element: termEl, dispose: () => { this._terminalElements.delete(tab.key); this._activeTerminals.delete(tab.key); this._connectedTerminals.delete(tab.key); } };
    };
    this._backend.attach(this._container!, contentFactory);
    this._engine = createFloatingFrameEngine(this._backend);
    this._backend.onFrameMove((key, pos) => { this._engine?.updatePosition(key, pos); this._scheduleSave(); });
    this._backend.onFrameResize((key, size) => { this._engine?.updateSize(key, size); this._fitTerminalsInFrame(key); this._scheduleSave(); });
    this._backend.onFrameClose((key) => this.hideFrame(key));
    this._backend.onFramePin((key) => this.togglePin(key));
    this._backend.onTabDragOut((fromFrame, tabKey, position) => {
      if (!this._engine) return;
      const frame = this._engine.frames.get(fromFrame);
      if (!frame || frame.tabs.length < 2) return;
      this._engine.removeTab(fromFrame, tabKey);
      this._activeTerminals.delete(tabKey);
      this.createFrame([toTabRef({ key: tabKey, label: '', content: {} as any })], undefined, undefined, { position });
    });
    this._backend.onTabReorder(() => this._scheduleSave());
  }

  override render() {
    return html`
      <div class="workspace-toolbar">
        <button class="new-frame-btn" @click=${this._onNewFrame}>+ New Frame</button>
        <button class="frames-btn" @click=${() => this._showFramesList()}>Frames</button>
      </div>
      <div class="dockview-container"></div>
    `;
  }

  createFrame(tabs: TabRef[], groupId?: string, _name?: string, restore?: Partial<FrameLayout>): string {
    if (!this._engine) return '';
    const validTabs = tabs.filter(t => !this._activeTerminals.has(t.terminalName));
    if (validTabs.length === 0 && tabs.length > 0) return '';
    const frameId = restore?.id ?? crypto.randomUUID();
    const cw = this._container?.clientWidth ?? 1200;
    const ch = this._container?.clientHeight ?? 800;
    const fw = restore?.size?.width ?? 600;
    const fh = restore?.size?.height ?? 400;
    const existingPos = [...this._engine.frames.values()].filter(f => !f.hidden).map(f => f.position);
    const position = restore?.position ? clampPosition(restore.position, { width: fw, height: fh }, { width: cw, height: ch }) : nextFramePosition({ width: cw, height: ch }, { width: fw, height: fh }, existingPos);
    this._engine.createFrame({ key: frameId, tabs: validTabs.map(toFrameTabConfig), position, size: { width: fw, height: fh }, pinned: restore?.pinned ?? false });
    for (const tab of validTabs) this._activeTerminals.add(tab.terminalName);
    if (groupId) this._frameGroupIds.set(frameId, groupId);
    if (!this._restoring) this._focusedFrameId = frameId;
    this._scheduleSave();
    return frameId;
  }

  hideFrame(frameId: string) {
    if (!this._engine) return;
    const frame = this._engine.frames.get(frameId);
    if (!frame) return;
    for (const tab of frame.tabs) this._activeTerminals.delete(tab.key);
    this._engine.hideFrame(frameId);
    this._frameGroupIds.delete(frameId);
    if (this._focusedFrameId === frameId) this._focusedFrameId = null;
    this._scheduleSave();
  }

  showFrame(frameId: string) {
    if (!this._engine) return;
    this._engine.showFrame(frameId);
    const frame = this._engine.frames.get(frameId);
    if (frame) for (const tab of frame.tabs) this._activeTerminals.add(tab.key);
    this._focusedFrameId = frameId;
    this._scheduleSave();
  }

  deleteFrame(frameId: string) {
    if (!this._engine) return;
    const frame = this._engine.frames.get(frameId);
    if (frame) for (const tab of frame.tabs) this._activeTerminals.delete(tab.key);
    this._engine.removeFrame(frameId);
    this._frameGroupIds.delete(frameId);
    if (this._focusedFrameId === frameId) this._focusedFrameId = null;
    this._scheduleSave();
  }

  removeFrame(frameId: string) { this.hideFrame(frameId); }

  togglePin(frameId: string) {
    if (!this._engine) return;
    this._engine.togglePin(frameId);
    const frame = this._engine.frames.get(frameId);
    if (frame) this._backend?.updatePinState(frameId, frame.pinned);
    this._scheduleSave();
  }

  bringToFront(frameId: string) {
    if (!this._engine?.frames.has(frameId)) return;
    this._engine.bringToFront(frameId);
    this._focusedFrameId = frameId;
    this._updateRendererTiers();
  }

  applyOrganiser(presetName: string) {
    if (!this._engine) return;
    const validPresets: Preset[] = ['side-by-side', 'stacked', 'grid', 'main-sidebar', 'focus'];
    const preset = presetName.toLowerCase().replace(/\s+/g, '-') as Preset;
    if (!validPresets.includes(preset)) return;
    const r = this._container?.getBoundingClientRect() ?? { width: 1200, height: 800 };
    this._engine.applyOrganiser(preset, { width: r.width, height: r.height });
    this._scheduleSave();
  }

  isTerminalOpen(terminalName: string): boolean { return this._activeTerminals.has(terminalName); }

  focusFrame(frameId: string) {
    if (!this._engine?.frames.has(frameId)) return;
    this._focusedFrameId = frameId;
    this._engine.bringToFront(frameId);
  }

  focusTab(frameId: string, tabIndex: number) {
    if (!this._engine?.frames.has(frameId)) return;
    const frame = this._engine.frames.get(frameId)!;
    if (tabIndex >= 0 && tabIndex < frame.tabs.length) this._engine.setActiveTab(frameId, frame.tabs[tabIndex].key);
  }

  private _setupKeyboard() {
    this._keydownHandler = (e: KeyboardEvent) => this._handleKeydown(e);
    document.addEventListener('keydown', this._keydownHandler);
  }

  private _handleKeydown(e: KeyboardEvent) {
    const meta = e.metaKey || e.ctrlKey;
    const shift = e.shiftKey;
    const alt = e.altKey;
    if (meta && shift && e.key === ']') { e.preventDefault(); this._nextTab(); return; }
    if (meta && shift && e.key === '[') { e.preventDefault(); this._prevTab(); return; }
    if (meta && !shift && !alt && e.key >= '1' && e.key <= '9') { e.preventDefault(); this._jumpToTab(parseInt(e.key) - 1); return; }
    if (meta && alt && e.key === ']') { e.preventDefault(); this._nextFrame(); return; }
    if (meta && alt && e.key === '[') { e.preventDefault(); this._prevFrame(); return; }
    if (meta && alt && e.key >= '1' && e.key <= '9') { e.preventDefault(); this._jumpToFrame(parseInt(e.key) - 1); return; }
    if (meta && alt && e.key === 'ArrowUp') { e.preventDefault(); this._spatialNav('up'); return; }
    if (meta && alt && e.key === 'ArrowDown') { e.preventDefault(); this._spatialNav('down'); return; }
    if (meta && alt && e.key === 'ArrowLeft') { e.preventDefault(); this._spatialNav('left'); return; }
    if (meta && alt && e.key === 'ArrowRight') { e.preventDefault(); this._spatialNav('right'); return; }
    if (meta && shift && e.key === 'P') { e.preventDefault(); if (this._focusedFrameId) this.togglePin(this._focusedFrameId); return; }
    if (meta && shift && e.key === 'W') { e.preventDefault(); if (this._focusedFrameId) this.removeFrame(this._focusedFrameId); return; }
    if (meta && shift && e.key === 'L') { e.preventDefault(); this._showOrganiserPicker(); return; }
    if (meta && shift && e.key === 'S') { e.preventDefault(); this._promptSaveAsGroup(); return; }
    if (meta && shift && e.key === 'D') { e.preventDefault(); this._detachFrame(); return; }
    if (meta && shift && e.key === 'Backspace') { e.preventDefault(); if (this._focusedFrameId && this._frameGroupIds.has(this._focusedFrameId)) this._deleteGroup(this._focusedFrameId); return; }
    if (meta && e.ctrlKey && e.key === ']') { e.preventDefault(); (window as any).trellis?.nextWindow(); return; }
    if (meta && e.ctrlKey && e.key === '[') { e.preventDefault(); (window as any).trellis?.prevWindow(); return; }
  }

  private _nextTab() {
    if (!this._focusedFrameId || !this._engine) return;
    const frame = this._engine.frames.get(this._focusedFrameId);
    if (!frame || frame.tabs.length < 2) return;
    const idx = frame.tabs.findIndex(t => t.key === frame.activeTabKey);
    this._engine.setActiveTab(this._focusedFrameId, frame.tabs[(idx + 1) % frame.tabs.length].key);
  }

  private _prevTab() {
    if (!this._focusedFrameId || !this._engine) return;
    const frame = this._engine.frames.get(this._focusedFrameId);
    if (!frame || frame.tabs.length < 2) return;
    const idx = frame.tabs.findIndex(t => t.key === frame.activeTabKey);
    this._engine.setActiveTab(this._focusedFrameId, frame.tabs[(idx - 1 + frame.tabs.length) % frame.tabs.length].key);
  }

  private _jumpToTab(index: number) {
    if (!this._focusedFrameId || !this._engine) return;
    const frame = this._engine.frames.get(this._focusedFrameId);
    if (!frame || index < 0 || index >= frame.tabs.length) return;
    this._engine.setActiveTab(this._focusedFrameId, frame.tabs[index].key);
  }

  private _nextFrame() {
    if (!this._engine) return;
    const visible = [...this._engine.frames.entries()].filter(([, f]) => !f.hidden).sort((a, b) => a[1].order - b[1].order);
    if (visible.length < 2) return;
    const idx = visible.findIndex(([k]) => k === this._focusedFrameId);
    const next = visible[(idx + 1) % visible.length];
    this._focusedFrameId = next[0];
    this._engine.bringToFront(next[0]);
  }

  private _prevFrame() {
    if (!this._engine) return;
    const visible = [...this._engine.frames.entries()].filter(([, f]) => !f.hidden).sort((a, b) => a[1].order - b[1].order);
    if (visible.length < 2) return;
    const idx = visible.findIndex(([k]) => k === this._focusedFrameId);
    const prev = visible[(idx - 1 + visible.length) % visible.length];
    this._focusedFrameId = prev[0];
    this._engine.bringToFront(prev[0]);
  }

  private _jumpToFrame(index: number) {
    if (!this._engine) return;
    const visible = [...this._engine.frames.entries()].filter(([, f]) => !f.hidden).sort((a, b) => a[1].order - b[1].order);
    if (index >= 0 && index < visible.length) { this._focusedFrameId = visible[index][0]; this._engine.bringToFront(visible[index][0]); }
  }

  private _spatialNav(direction: 'up' | 'down' | 'left' | 'right') {
    if (!this._engine) return;
    const target = this._engine.focusDirection(direction);
    if (target) { this._focusedFrameId = target; this._engine.bringToFront(target); }
  }

  private _setupShortcutIPC() {
    const trellis = (window as any).trellis;
    if (!trellis) return;
    trellis.onShortcut('new-frame', () => this._onNewFrame());
    trellis.onShortcut('new-tab', () => this._onNewTab());
    trellis.onShortcut('close-tab', () => this._onCloseTab());
  }

  private _setupFlushHandler() {
    const trellis = (window as any).trellis;
    if (trellis?.onLayoutFlush) trellis.onLayoutFlush(() => trellis.saveWindowLayout(this._serializeLayout()));
  }

  private _setupDetachIPC() {
    const trellis = (window as any).trellis;
    if (!trellis) return;
    trellis.onFrameInit((_event: any, fl: FrameLayout) => { if (fl.tabs?.length > 0) this.createFrame(fl.tabs, fl.groupId); });
    trellis.onFrameReceive((_event: any, fl: FrameLayout) => { if (!this._engine?.frames.has(fl.id) && fl.tabs?.length > 0) this.createFrame(fl.tabs, fl.groupId); });
  }

  private _setupWebglIPC() {
    const trellis = (window as any).trellis;
    if (!trellis) return;
    if (trellis.onWebglGrant) trellis.onWebglGrant((_e: any, name: string) => { this._rendererTiers.set(name, 'webgl'); this._applyRendererTier(name, 'webgl'); });
    if (trellis.onWebglDemote) trellis.onWebglDemote((_e: any, name: string) => { this._rendererTiers.set(name, 'canvas'); this._applyRendererTier(name, 'canvas'); this._releaseWebgl(name); });
  }

  private _onNewFrame() { const btn = this.shadowRoot!.querySelector('.new-frame-btn') as HTMLElement; if (btn) this._showPicker(btn, 'create'); }

  private _onNewTab() {
    if (!this._focusedFrameId || !this._engine?.frames.has(this._focusedFrameId)) return;
    const btn = this.shadowRoot?.querySelector('.frame-add-tab-btn') as HTMLElement;
    if (btn) this._showPicker(btn, 'add', this._focusedFrameId);
  }

  private _onCloseTab() {
    if (!this._focusedFrameId || !this._engine) return;
    const frame = this._engine.frames.get(this._focusedFrameId);
    if (!frame || frame.tabs.length === 0) return;
    const activeTab = frame.tabs.find(t => t.key === frame.activeTabKey);
    if (!activeTab) return;
    if (frame.tabs.length === 1) { this.hideFrame(this._focusedFrameId); return; }
    this._engine.removeTab(this._focusedFrameId, activeTab.key);
    this._activeTerminals.delete(activeTab.key);
    this._scheduleSave();
  }

  private async _detachFrame() {
    if (!this._focusedFrameId || !this._engine) return;
    const trellis = (window as any).trellis;
    if (!trellis?.createWindow) return;
    const frameId = this._focusedFrameId;
    const frame = this._engine.frames.get(frameId);
    if (!frame || frame.tabs.length === 0) return;
    const fl: FrameLayout = { id: frameId, groupId: this._frameGroupIds.get(frameId), order: frame.order, position: frame.position, size: frame.size, zIndex: frame.zIndex, pinned: frame.pinned, tabs: frame.tabs.map(toTabRef), activeTabIndex: Math.max(0, frame.tabs.findIndex(t => t.key === frame.activeTabKey)) };
    await trellis.inhibitSave();
    await trellis.createWindow('/workspace?root=' + encodeURIComponent(this.workspaceRoot), { frameLayout: fl, width: fl.size.width, height: fl.size.height });
    this.hideFrame(frameId); this.deleteFrame(frameId);
    await trellis.releaseSave();
  }

  private async _attachToMainWindow(frameId: string) {
    const trellis = (window as any).trellis;
    if (!trellis?.listWindows || !this._engine?.frames.has(frameId)) return;
    const windows = await trellis.listWindows();
    if (!windows || windows.length < 2) return;
    await trellis.attachPanel(frameId, windows[0].id);
    this.hideFrame(frameId); this.deleteFrame(frameId);
  }

  async loadGroups(): Promise<Group[]> {
    if (!this.workspaceRoot) return [];
    const trellis = (window as any).trellis;
    if (trellis?.loadGroups) { const data = await trellis.loadGroups(this.workspaceRoot); return data?.groups || []; }
    try { const resp = await fetch('/api/workspace/groups?root=' + encodeURIComponent(this.workspaceRoot)); if (resp.ok) { const data = await resp.json(); return data?.groups || []; } } catch { /* non-critical */ }
    return [];
  }

  private async _loadGroupsData(): Promise<{ groups: Group[] }> {
    if (!this.workspaceRoot) return { groups: [] };
    const trellis = (window as any).trellis;
    if (trellis?.loadGroups) return (await trellis.loadGroups(this.workspaceRoot)) || { groups: [] };
    try { const resp = await fetch('/api/workspace/groups?root=' + encodeURIComponent(this.workspaceRoot)); if (resp.ok) return await resp.json(); } catch { /* non-critical */ }
    return { groups: [] };
  }

  private async _saveGroupsData(data: { groups: Group[] }): Promise<void> {
    if (!this.workspaceRoot) return;
    const trellis = (window as any).trellis;
    if (trellis?.saveGroups) { await trellis.saveGroups(this.workspaceRoot, data); return; }
    try { await fetch('/api/workspace/groups?root=' + encodeURIComponent(this.workspaceRoot), { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }); } catch { /* non-critical */ }
  }

  private async _saveFrameAsGroup(name: string): Promise<void> {
    if (!this._focusedFrameId || !this.workspaceRoot || !this._engine) return;
    const frame = this._engine.frames.get(this._focusedFrameId);
    if (!frame || frame.tabs.length === 0) return;
    const existing = await this._loadGroupsData();
    const group: Group = { id: `group-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, name, tabs: frame.tabs.map(toTabRef) };
    existing.groups.push(group);
    await this._saveGroupsData(existing);
    this._frameGroupIds.set(this._focusedFrameId, group.id);
  }

  private async _updateGroup(frameId: string): Promise<void> {
    const groupId = this._frameGroupIds.get(frameId);
    if (!groupId || !this.workspaceRoot || !this._engine) return;
    const frame = this._engine.frames.get(frameId);
    if (!frame) return;
    const existing = await this._loadGroupsData();
    const group = existing.groups.find((g: Group) => g.id === groupId);
    if (!group) return;
    group.tabs = frame.tabs.map(toTabRef);
    await this._saveGroupsData(existing);
  }

  private async _deleteGroup(frameId: string): Promise<void> {
    const groupId = this._frameGroupIds.get(frameId);
    if (!groupId || !this.workspaceRoot) return;
    const existing = await this._loadGroupsData();
    existing.groups = existing.groups.filter((g: Group) => g.id !== groupId);
    await this._saveGroupsData(existing);
    this._frameGroupIds.delete(frameId);
  }

  private async _deleteGroupById(groupId: string) {
    if (!this.workspaceRoot) return;
    const data = await this._loadGroupsData();
    data.groups = data.groups.filter((g: Group) => g.id !== groupId);
    await this._saveGroupsData(data);
    for (const [fid, gid] of this._frameGroupIds) { if (gid === groupId) this._frameGroupIds.delete(fid); }
  }

  private _promptSaveAsGroup() {
    if (!this._focusedFrameId) return;
    this._dismissPicker();
    const picker = document.createElement('div'); picker.className = 'workspace-picker'; picker.style.left = '50%'; picker.style.top = '50%'; picker.style.transform = 'translate(-50%, -50%)'; picker.style.padding = '12px';
    const label = document.createElement('div'); label.style.cssText = 'color:#ccc;font-size:13px;margin-bottom:8px;'; label.textContent = 'Group name:';
    const input = document.createElement('input'); input.type = 'text'; input.style.cssText = 'width:100%;background:#1e1e1e;border:1px solid #555;color:#ccc;padding:4px 8px;border-radius:3px;font-size:13px;box-sizing:border-box;';
    const actions = document.createElement('div'); actions.style.cssText = 'display:flex;justify-content:flex-end;gap:6px;margin-top:8px;';
    const cancelBtn = document.createElement('button'); cancelBtn.className = 'frames-action'; cancelBtn.textContent = 'Cancel';
    const saveBtn = document.createElement('button'); saveBtn.className = 'picker-confirm'; saveBtn.textContent = 'Save'; saveBtn.disabled = true;
    input.addEventListener('input', () => { saveBtn.disabled = !input.value.trim(); });
    const doSave = () => { const n = input.value.trim(); if (n) this._saveFrameAsGroup(n); this._dismissPicker(); };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && input.value.trim()) doSave(); if (e.key === 'Escape') this._dismissPicker(); e.stopPropagation(); });
    cancelBtn.addEventListener('click', () => this._dismissPicker()); saveBtn.addEventListener('click', doSave);
    actions.appendChild(cancelBtn); actions.appendChild(saveBtn); picker.appendChild(label); picker.appendChild(input); picker.appendChild(actions);
    const backdrop = document.createElement('div'); backdrop.className = 'picker-backdrop'; backdrop.addEventListener('click', () => this._dismissPicker());
    this._backdropEl = backdrop; this._pickerEl = picker; this.shadowRoot!.appendChild(backdrop); this.shadowRoot!.appendChild(picker);
    requestAnimationFrame(() => input.focus());
  }

  private async _showPicker(anchor: HTMLElement, mode: 'create' | 'add', targetFrameId?: string) {
    this._dismissPicker();
    const workspace = await this._fetchWorkspace();
    const repos = workspace.repos;
    const activeSlots = workspace.slots.filter((s: any) => s.status !== 'ARCHIVED');
    const archivedSlots = workspace.slots.filter((s: any) => s.status === 'ARCHIVED');
    const picker = document.createElement('div'); picker.className = 'workspace-picker';
    const anchorRect = anchor.getBoundingClientRect(); const hostRect = this.getBoundingClientRect();
    picker.style.left = `${anchorRect.left - hostRect.left}px`; picker.style.top = `${anchorRect.bottom - hostRect.top + 4}px`;
    const selected = new Map<string, string>();
    let confirmBtn!: HTMLButtonElement; let selectedArea!: HTMLElement;
    const updateSelectedArea = () => {
      selectedArea.innerHTML = '';
      for (const [termName, type] of selected) {
        const chip = document.createElement('span'); chip.className = 'selected-chip'; chip.textContent = `${termName.replace(/^(repo-|slot-)/, '')} (${type})`;
        const removeBtn = document.createElement('button'); removeBtn.className = 'selected-chip-remove'; removeBtn.textContent = '×';
        removeBtn.addEventListener('click', () => { selected.delete(termName); const cb = picker.querySelector(`input[data-term="${termName}"]`) as HTMLInputElement; if (cb) cb.checked = false; updateSelectedArea(); confirmBtn.disabled = mode !== 'create' && selected.size === 0; });
        chip.appendChild(removeBtn); selectedArea.appendChild(chip);
      }
      selectedArea.style.display = selected.size > 0 ? '' : 'none';
      confirmBtn.disabled = mode !== 'create' && selected.size === 0;
    };
    const tabBar = document.createElement('div'); tabBar.className = 'picker-tab-bar';
    const tabNames = ['Repos', 'Slots', 'Groups', 'Attic']; const sections: HTMLElement[] = [];
    for (const name of tabNames) {
      const tab = document.createElement('button'); tab.className = 'picker-tab'; tab.textContent = name;
      if (name === 'Repos') tab.classList.add('picker-tab-active');
      tab.addEventListener('click', () => { tabBar.querySelectorAll('.picker-tab').forEach(t => t.classList.remove('picker-tab-active')); tab.classList.add('picker-tab-active'); sections.forEach(s => s.style.display = s.dataset.section === name.toLowerCase() ? '' : 'none'); });
      tabBar.appendChild(tab);
    }
    picker.appendChild(tabBar);
    selectedArea = document.createElement('div'); selectedArea.className = 'picker-selected'; selectedArea.style.display = 'none'; picker.appendChild(selectedArea);
    const scrollArea = document.createElement('div'); scrollArea.className = 'picker-scroll';
    const makeItem = (termName: string, displayName: string, type: string, branch?: string) => {
      const isOpen = this._activeTerminals.has(termName);
      const item = document.createElement('label'); item.className = `picker-item${isOpen ? ' picker-item-disabled' : ''}`;
      const cb = document.createElement('input'); cb.type = 'checkbox'; cb.disabled = isOpen; cb.dataset.term = termName;
      cb.addEventListener('change', () => { if (cb.checked) selected.set(termName, type); else selected.delete(termName); updateSelectedArea(); });
      const nameEl = document.createElement('span'); nameEl.className = 'picker-name'; nameEl.textContent = displayName;
      item.appendChild(cb); item.appendChild(nameEl);
      if (branch) { const branchEl = document.createElement('span'); branchEl.className = 'picker-branch'; branchEl.textContent = branch; item.appendChild(branchEl); }
      return item;
    };
    const reposSection = document.createElement('div'); reposSection.className = 'picker-section'; reposSection.dataset.section = 'repos';
    for (const repo of repos) reposSection.appendChild(makeItem(`repo-${repo.name}`, repo.name, 'repo', repo.branch));
    scrollArea.appendChild(reposSection); sections.push(reposSection);
    const slotsSection = document.createElement('div'); slotsSection.className = 'picker-section'; slotsSection.dataset.section = 'slots'; slotsSection.style.display = 'none';
    for (const slot of activeSlots) slotsSection.appendChild(makeItem(`slot-${slot.number}`, `slot-${slot.number}`, 'slot', slot.issue));
    if (activeSlots.length === 0) { const empty = document.createElement('div'); empty.className = 'picker-empty'; empty.textContent = 'No active slots'; slotsSection.appendChild(empty); }
    scrollArea.appendChild(slotsSection); sections.push(slotsSection);
    const groups = await this.loadGroups();
    const groupsSection = document.createElement('div'); groupsSection.className = 'picker-section'; groupsSection.dataset.section = 'groups'; groupsSection.style.display = 'none';
    for (const grp of groups) {
      const item = document.createElement('div'); item.className = 'picker-item'; item.style.cursor = 'pointer';
      const nameEl = document.createElement('span'); nameEl.className = 'picker-name'; nameEl.textContent = grp.name;
      const countEl = document.createElement('span'); countEl.className = 'picker-branch'; countEl.textContent = `${grp.tabs.length} tabs`;
      item.appendChild(nameEl); item.appendChild(countEl);
      item.addEventListener('click', () => { this.createFrame(grp.tabs, grp.id); this._dismissPicker(); });
      groupsSection.appendChild(item);
    }
    if (groups.length === 0) { const empty = document.createElement('div'); empty.className = 'picker-empty'; empty.textContent = 'No saved groups — Cmd+Shift+S to save'; groupsSection.appendChild(empty); }
    scrollArea.appendChild(groupsSection); sections.push(groupsSection);
    const atticSection = document.createElement('div'); atticSection.className = 'picker-section'; atticSection.dataset.section = 'attic'; atticSection.style.display = 'none';
    for (const slot of archivedSlots) atticSection.appendChild(makeItem(`slot-${slot.number}`, `slot-${slot.number}`, 'attic', slot.issue));
    if (archivedSlots.length === 0) { const empty = document.createElement('div'); empty.className = 'picker-empty'; empty.textContent = 'No archived slots'; atticSection.appendChild(empty); }
    scrollArea.appendChild(atticSection); sections.push(atticSection);
    picker.appendChild(scrollArea);
    const actionsDiv = document.createElement('div'); actionsDiv.className = 'picker-actions';
    confirmBtn = document.createElement('button'); confirmBtn.className = 'picker-confirm'; confirmBtn.textContent = mode === 'create' ? 'Create Frame' : 'Add'; confirmBtn.disabled = mode !== 'create';
    confirmBtn.addEventListener('click', () => {
      const tabs: TabRef[] = [...selected.keys()].map(n => ({ terminalName: n, type: (n.startsWith('slot-') ? 'slot' : 'repo') as 'repo' | 'slot' }));
      if (mode === 'create') { this.createFrame(tabs); }
      else if (targetFrameId && this._engine) { for (const tab of tabs) { if (this._activeTerminals.has(tab.terminalName)) continue; this._engine.addTab(targetFrameId, toFrameTabConfig(tab)); this._activeTerminals.add(tab.terminalName); } this._scheduleSave(); }
      this._dismissPicker();
    });
    actionsDiv.appendChild(confirmBtn); picker.appendChild(actionsDiv);
    const backdrop = document.createElement('div'); backdrop.className = 'picker-backdrop'; backdrop.addEventListener('click', () => this._dismissPicker());
    this._backdropEl = backdrop; this._pickerEl = picker; this.shadowRoot!.appendChild(backdrop); this.shadowRoot!.appendChild(picker);
    this._pickerDismissEscape = (e: KeyboardEvent) => { if (e.key === 'Escape') this._dismissPicker(); };
    document.addEventListener('keydown', this._pickerDismissEscape);
  }

  private _showOrganiserPicker() {
    this._dismissPicker();
    const presetNames: Preset[] = ['side-by-side', 'stacked', 'grid', 'main-sidebar', 'focus'];
    const picker = document.createElement('div'); picker.className = 'workspace-picker'; picker.style.left = '50%'; picker.style.top = '50%'; picker.style.transform = 'translate(-50%, -50%)';
    const scrollArea = document.createElement('div'); scrollArea.className = 'picker-scroll';
    presetNames.forEach((preset, i) => {
      const item = document.createElement('div'); item.className = 'picker-item'; item.style.cursor = 'pointer';
      const label = document.createElement('span'); label.className = 'picker-name'; label.textContent = `${i + 1}. ${preset}`;
      item.appendChild(label);
      item.addEventListener('click', () => { this.applyOrganiser(preset); this._dismissPicker(); });
      scrollArea.appendChild(item);
    });
    picker.appendChild(scrollArea);
    const backdrop = document.createElement('div'); backdrop.className = 'picker-backdrop'; backdrop.addEventListener('click', () => this._dismissPicker());
    this._backdropEl = backdrop; this._pickerEl = picker; this.shadowRoot!.appendChild(backdrop); this.shadowRoot!.appendChild(picker);
    this._pickerDismissEscape = (e: KeyboardEvent) => { if (e.key === 'Escape') { this._dismissPicker(); return; } const num = parseInt(e.key); if (num >= 1 && num <= presetNames.length) { this.applyOrganiser(presetNames[num - 1]); this._dismissPicker(); } };
    document.addEventListener('keydown', this._pickerDismissEscape);
  }

  private _showFramesList() {
    this._dismissPicker();
    const btn = this.shadowRoot!.querySelector('.frames-btn') as HTMLElement;
    if (!btn || !this._engine) return;
    const picker = document.createElement('div'); picker.className = 'workspace-picker';
    const anchorRect = btn.getBoundingClientRect(); const hostRect = this.getBoundingClientRect();
    picker.style.left = `${anchorRect.left - hostRect.left}px`; picker.style.top = `${anchorRect.bottom - hostRect.top + 4}px`;
    const scrollArea = document.createElement('div'); scrollArea.className = 'picker-scroll';
    const visibleFrames = [...this._engine.frames.entries()].filter(([, f]) => !f.hidden);
    const hiddenFrames = [...this._engine.frames.entries()].filter(([, f]) => f.hidden);
    if (visibleFrames.length > 0) {
      const header = document.createElement('div'); header.className = 'frames-section-header'; header.textContent = 'Visible'; scrollArea.appendChild(header);
      for (const [frameId, frame] of visibleFrames) {
        const row = document.createElement('div'); row.className = 'frames-row';
        const label = document.createElement('span'); label.className = 'frames-label'; label.textContent = frame.tabs.map(t => t.label).join(', ');
        const hideBtn = document.createElement('button'); hideBtn.className = 'frames-action'; hideBtn.textContent = 'Hide'; hideBtn.addEventListener('click', () => { this.hideFrame(frameId); this._dismissPicker(); });
        const delBtn = document.createElement('button'); delBtn.className = 'frames-action frames-action-delete'; delBtn.textContent = 'Delete'; delBtn.addEventListener('click', () => { this.hideFrame(frameId); this.deleteFrame(frameId); this._dismissPicker(); });
        row.appendChild(label); row.appendChild(hideBtn); row.appendChild(delBtn); scrollArea.appendChild(row);
      }
    }
    if (hiddenFrames.length > 0) {
      const header = document.createElement('div'); header.className = 'frames-section-header'; header.textContent = 'Hidden'; scrollArea.appendChild(header);
      for (const [frameId, frame] of hiddenFrames) {
        const row = document.createElement('div'); row.className = 'frames-row frames-row-hidden';
        const label = document.createElement('span'); label.className = 'frames-label'; label.textContent = frame.tabs.map(t => t.label).join(', ');
        const showBtn = document.createElement('button'); showBtn.className = 'frames-action'; showBtn.textContent = 'Show'; showBtn.addEventListener('click', () => { this.showFrame(frameId); this._dismissPicker(); });
        const delBtn = document.createElement('button'); delBtn.className = 'frames-action frames-action-delete'; delBtn.textContent = 'Delete'; delBtn.addEventListener('click', () => { this.deleteFrame(frameId); this._dismissPicker(); });
        row.appendChild(label); row.appendChild(showBtn); row.appendChild(delBtn); scrollArea.appendChild(row);
      }
    }
    if (visibleFrames.length === 0 && hiddenFrames.length === 0) { const empty = document.createElement('div'); empty.className = 'picker-empty'; empty.textContent = 'No frames'; scrollArea.appendChild(empty); }
    picker.appendChild(scrollArea);
    const backdrop = document.createElement('div'); backdrop.className = 'picker-backdrop'; backdrop.addEventListener('click', () => this._dismissPicker());
    this._backdropEl = backdrop; this._pickerEl = picker; this.shadowRoot!.appendChild(backdrop); this.shadowRoot!.appendChild(picker);
    this._pickerDismissEscape = (e: KeyboardEvent) => { if (e.key === 'Escape') this._dismissPicker(); };
    document.addEventListener('keydown', this._pickerDismissEscape);
  }

  private _showFrameContextMenu(frameId: string, event: MouseEvent) {
    this._dismissPicker();
    const menu = document.createElement('div'); menu.className = 'workspace-picker'; menu.style.left = `${event.clientX - this.getBoundingClientRect().left}px`; menu.style.top = `${event.clientY - this.getBoundingClientRect().top}px`; menu.style.minWidth = '180px';
    const addItem = (label: string, action: () => void) => { const item = document.createElement('div'); item.className = 'picker-item'; item.style.cursor = 'pointer'; const nameEl = document.createElement('span'); nameEl.className = 'picker-name'; nameEl.textContent = label; item.appendChild(nameEl); item.addEventListener('click', () => { action(); this._dismissPicker(); }); menu.appendChild(item); };
    addItem('Save as Group', () => this._promptSaveAsGroup());
    const groupId = this._frameGroupIds.get(frameId);
    if (groupId) { addItem('Update Group', () => this._updateGroup(frameId)); addItem('Delete Group', () => this._deleteGroup(frameId)); }
    addItem('Attach to main window', () => this._attachToMainWindow(frameId));
    const backdrop = document.createElement('div'); backdrop.className = 'picker-backdrop'; backdrop.addEventListener('click', () => this._dismissPicker());
    this._backdropEl = backdrop; this._pickerEl = menu; this.shadowRoot!.appendChild(backdrop); this.shadowRoot!.appendChild(menu);
  }

  private _dismissPicker() {
    if (this._pickerDismissEscape) { document.removeEventListener('keydown', this._pickerDismissEscape); this._pickerDismissEscape = null; }
    if (this._backdropEl) { this._backdropEl.remove(); this._backdropEl = null; }
    if (this._pickerEl) { this._pickerEl.remove(); this._pickerEl = null; }
  }

  private async _connectTerminal(terminalName: string, terminalEl: any, retries = 3, delay = 1000) {
    const exists = await this._ensureTerminalExists(terminalName);
    if (!exists) { if (retries > 0) setTimeout(() => this._connectTerminal(terminalName, terminalEl, retries - 1, delay * 2), delay); return; }
    if (typeof terminalEl.configure !== 'function') { if (retries > 0) setTimeout(() => this._connectTerminal(terminalName, terminalEl, retries - 1, delay), delay); return; }
    this._connectedTerminals.add(terminalName);
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    terminalEl.configure({ wsUrl: `${proto}//${location.host}/ws/terminal/${terminalName}/{cols}/{rows}`, theme: { background: '#1e1e1e', foreground: '#cccccc', cursor: '#aeafad' }, fontSize: 13, fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace" });
    setTimeout(() => { if (typeof terminalEl.fit === 'function') terminalEl.fit(); const term = terminalEl.terminal; if (term) { fetch(`/api/terminals/${terminalName}/resize`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ cols: term.cols ?? 80, rows: term.rows ?? 24 }) }).catch(() => {}); } }, 500);
  }

  private async _ensureTerminalExists(terminalName: string): Promise<boolean> {
    const type = terminalName.startsWith('slot-') ? 'slot' : 'repo';
    const repoName = type === 'repo' ? terminalName.replace(/^repo-/, '') : undefined;
    try { const resp = await fetch(`/api/terminals/${terminalName}`); if (resp.ok) return true; } catch { /* fall through */ }
    if (type === 'slot') return false;
    try { const r = await fetch('/api/terminals', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: terminalName, workingDir: '/tmp', repo: repoName }) }); return r.status === 201 || r.status === 409; } catch { return false; }
  }

  private _fitTerminalsInFrame(frameKey: string) {
    if (!this._engine) return;
    const frame = this._engine.frames.get(frameKey);
    if (!frame) return;
    setTimeout(() => { for (const tab of frame.tabs) { const el = this._terminalElements.get(tab.key); if (el && typeof (el as any).fit === 'function') (el as any).fit(); } }, 150);
  }

  private async _fetchWorkspace(): Promise<{ repos: any[]; slots: any[] }> {
    if (!this.workspaceRoot) return { repos: [], slots: [] };
    try { const resp = await fetch(`/api/workspace?root=${encodeURIComponent(this.workspaceRoot)}`); if (!resp.ok) return { repos: [], slots: [] }; const data = await resp.json(); return { repos: data.repos || [], slots: data.slots || [] }; } catch { return { repos: [], slots: [] }; }
  }

  _showTabFlyout(terminalName: string, anchorEl: HTMLElement) {
    this._hideTabFlyoutImmediate();
    const flyout = document.createElement('trellis-tab-flyout') as any;
    flyout.terminalName = terminalName; flyout.repoName = terminalName.replace(/^(repo-|slot-)/, '');
    const hostRect = this.getBoundingClientRect(); const tabRect = anchorEl.getBoundingClientRect();
    flyout.style.left = `${tabRect.left - hostRect.left}px`; flyout.style.top = `${tabRect.bottom - hostRect.top + 4}px`; flyout.style.pointerEvents = 'auto';
    flyout.addEventListener('mouseenter', () => { if (this._flyoutHideTimer) { clearTimeout(this._flyoutHideTimer); this._flyoutHideTimer = null; } });
    flyout.addEventListener('mouseleave', () => this._hideTabFlyout());
    this._flyoutEl = flyout; this.shadowRoot!.appendChild(flyout); this._populateFlyout(terminalName, flyout);
  }

  _hideTabFlyout() {
    if (this._flyoutHideTimer) { clearTimeout(this._flyoutHideTimer); this._flyoutHideTimer = null; }
    this._flyoutHideTimer = setTimeout(() => { this._hideTabFlyoutImmediate(); this._flyoutHideTimer = null; }, 100);
  }

  private _hideTabFlyoutImmediate() { if (this._flyoutEl) { this._flyoutEl.remove(); this._flyoutEl = null; } }

  private async _populateFlyout(terminalName: string, flyout: any): Promise<void> {
    const isSlot = terminalName.startsWith('slot-');
    const repoName = isSlot ? undefined : terminalName.replace(/^repo-/, '');
    if (isSlot) flyout.slot = terminalName.replace(/^slot-/, '');
    if (repoName && this.workspaceRoot) { try { const resp = await fetch(`/api/workspace/repo?root=${encodeURIComponent(this.workspaceRoot)}&repo=${encodeURIComponent(repoName)}`); if (resp.ok) { const repo = await resp.json(); flyout.repoName = repo.name || repoName; flyout.branch = repo.branch || ''; flyout.path = repo.path || ''; } } catch { /* non-critical */ } }
    const cached = this._agentStates.get(terminalName);
    if (cached) { flyout.agentState = cached.status || ''; flyout.memoryMb = cached.memoryMb || 0; flyout.agentUptimeMs = cached.uptimeMs || 0; }
    try { const resp = await fetch(`/api/terminals/${terminalName}`); if (resp.ok) { const data = await resp.json(); if (data.issue) flyout.issue = data.issue; if (!cached && data.agent) { flyout.agentState = data.agent.status || ''; flyout.memoryMb = data.agent.memoryMb || 0; flyout.agentUptimeMs = data.agent.uptimeMs || 0; } } } catch { /* non-critical */ }
    const termEl = this._terminalElements.get(terminalName) as any;
    if (termEl?.terminal?.buffer?.active) { const buf = termEl.terminal.buffer.active; const lines: string[] = []; const start = Math.max(0, buf.cursorY - 2); for (let i = start; i <= buf.cursorY; i++) { const line = buf.getLine(i); if (line) { const text = line.translateToString(true).trim(); if (text) lines.push(text); } } if (lines.length > 0) flyout.lastOutput = lines.map((l: string) => `> ${l}`).join('\n'); }
  }

  _handleAgentStateEvent(data: any) {
    if (!data.terminal) return;
    this._agentStates.set(data.terminal, { status: data.status, memoryMb: data.memoryMb, uptimeMs: data.uptimeMs });
  }

  private _setupAgentSSE() {
    try { this._sseSource = new EventSource('/api/push'); this._sseSource.addEventListener('agent:state', (event) => { try { this._handleAgentStateEvent(JSON.parse((event as MessageEvent).data)); } catch { /* malformed */ } }); } catch { /* SSE not available */ }
  }

  async _updateRendererTiers() {
    if (!this._engine) return;
    const frameTabs = new Map<string, TabRef[]>();
    const frameActiveTab = new Map<string, number>();
    for (const [key, frame] of this._engine.frames) { if (frame.hidden) continue; frameTabs.set(key, frame.tabs.map(toTabRef)); frameActiveTab.set(key, Math.max(0, frame.tabs.findIndex(t => t.key === frame.activeTabKey))); }
    const newTiers = computeAllTiers(frameTabs, frameActiveTab, this._focusedFrameId);
    const transitions = computeTransitions(this._rendererTiers, newTiers);
    for (const t of transitions) { if (t.from === 'webgl') await this._releaseWebgl(t.terminalName); if (t.to === 'webgl') { const granted = await this._acquireWebgl(t.terminalName); if (!granted) newTiers.set(t.terminalName, 'canvas'); } this._applyRendererTier(t.terminalName, newTiers.get(t.terminalName)!); }
    this._rendererTiers = newTiers;
  }

  private async _acquireWebgl(terminalName: string): Promise<boolean> { const trellis = (window as any).trellis; if (!trellis?.webglAcquire) return true; try { const result = await trellis.webglAcquire(terminalName); return result?.granted ?? false; } catch { return false; } }
  private async _releaseWebgl(terminalName: string): Promise<void> { const trellis = (window as any).trellis; if (!trellis?.webglRelease) return; try { await trellis.webglRelease(terminalName); } catch { /* non-critical */ } }

  private _applyRendererTier(terminalName: string, tier: RendererTier) {
    const termEl = this._terminalElements.get(terminalName) as any;
    if (!termEl?.terminal) return;
    const existing = this._rendererAddons.get(terminalName); if (existing) { try { existing.dispose(); } catch { /* ok */ } this._rendererAddons.delete(terminalName); }
    const terminal = termEl.terminal;
    try { if (tier === 'webgl') { const { WebglAddon } = require('@xterm/addon-webgl'); const addon = new WebglAddon(); terminal.loadAddon(addon); this._rendererAddons.set(terminalName, addon); } else if (tier === 'canvas') { const { CanvasAddon } = require('@xterm/addon-canvas'); const addon = new CanvasAddon(); terminal.loadAddon(addon); this._rendererAddons.set(terminalName, addon); } } catch { /* addon load failure */ }
    (termEl as HTMLElement).style.visibility = tier === 'none' ? 'hidden' : '';
  }

  private _scheduleSave() {
    if (this._saveDebounceTimer) clearTimeout(this._saveDebounceTimer);
    this._saveDebounceTimer = setTimeout(() => this._doSave(), 500);
    if (!this._saveMaxWaitTimer) { this._saveMaxWaitTimer = setTimeout(() => { this._saveMaxWaitTimer = null; if (this._saveDebounceTimer) { clearTimeout(this._saveDebounceTimer); this._saveDebounceTimer = null; } this._doSave(); }, 5000); }
  }

  private _saveBeforeUnload() {
    if (this._saveDebounceTimer) clearTimeout(this._saveDebounceTimer); if (this._saveMaxWaitTimer) clearTimeout(this._saveMaxWaitTimer);
    this._saveDebounceTimer = null; this._saveMaxWaitTimer = null;
    const trellis = (window as any).trellis; if (trellis?.saveWindowLayout) trellis.saveWindowLayout(this._serializeLayout());
  }

  private _doSave() {
    this._saveDebounceTimer = null; this._lastSaveTime = Date.now();
    if (this._saveMaxWaitTimer) { clearTimeout(this._saveMaxWaitTimer); this._saveMaxWaitTimer = null; }
    const layout = this._serializeLayout(); const trellis = (window as any).trellis;
    if (trellis?.saveWindowLayout) { trellis.saveWindowLayout(layout); }
    else if (this._browserMode && this.workspaceRoot) { fetch(`/api/workspace/layout?root=${encodeURIComponent(this.workspaceRoot)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ windows: [layout] }), keepalive: true }).catch(() => {}); }
  }

  private _serializeLayout(): ShellLayout {
    if (!this._engine) return { id: 'shell-1', bounds: { x: 0, y: 0, width: 1200, height: 800 }, isMain: true, frames: [], lastActiveFrameId: undefined };
    const engineFrames = this._engine.captureLayout();
    return {
      id: 'shell-1',
      bounds: { x: 0, y: 0, width: this._container?.clientWidth ?? 1200, height: this._container?.clientHeight ?? 800 },
      isMain: true,
      frames: engineFrames.map(f => ({ id: f.key, groupId: this._frameGroupIds.get(f.key), order: f.order, position: f.position, size: f.size, zIndex: f.zIndex, pinned: f.pinned, tabs: f.tabs.map(toTabRef), activeTabIndex: Math.max(0, f.tabs.findIndex(t => t.key === f.activeTabKey)) })),
      lastActiveFrameId: this._focusedFrameId ?? undefined,
    };
  }

  private async _restoreLayout() {
    let persisted: any = null;
    const trellis = (window as any).trellis;
    if (trellis?.getLastWorkspacePath) { const wp = await trellis.getLastWorkspacePath(); if (!wp) return; persisted = await trellis.loadLayout(wp); }
    else if (this.workspaceRoot) { try { const resp = await fetch('/api/workspace/layout?root=' + encodeURIComponent(this.workspaceRoot)); if (resp.ok) persisted = await resp.json(); } catch { /* non-critical */ } }
    if (!persisted?.windows) return;
    const shell = persisted.windows.find((w: ShellLayout) => w.isMain) || persisted.windows[0];
    if (!shell?.frames) return;
    this._restoring = true;
    try { for (const frame of shell.frames.sort((a: FrameLayout, b: FrameLayout) => a.order - b.order)) { if (frame.tabs?.length > 0) this.createFrame(frame.tabs, frame.groupId, undefined, frame); } } finally { this._restoring = false; }
    if (shell.lastActiveFrameId) this._focusedFrameId = shell.lastActiveFrameId;
  }

  async handleCommand(command: string, params?: any): Promise<{ ok: boolean; error?: string; frameId?: string }> {
    let result: { ok: boolean; error?: string; frameId?: string };
    switch (command) {
      case 'frame-create': { const t = params?.tabs ?? []; if (t.length === 0) { result = { ok: false, error: 'no valid tabs' }; break; } const id = this.createFrame(t, params?.groupId, params?.name, params); result = id ? { ok: true, frameId: id } : { ok: false, error: 'no valid tabs' }; break; }
      case 'frame-remove': { if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } this.hideFrame(params.frameId); this.deleteFrame(params.frameId); result = { ok: true }; break; }
      case 'frame-move': { if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } this._backend?.updatePosition(params.frameId, params.position); this._scheduleSave(); setTimeout(() => this._fitTerminalsInFrame(params.frameId), 150); result = { ok: true }; break; }
      case 'frame-resize': { if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } this._backend?.updateSize(params.frameId, params.size); this._scheduleSave(); setTimeout(() => this._fitTerminalsInFrame(params.frameId), 150); result = { ok: true }; break; }
      case 'frame-pin': { if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } const fp = this._engine.frames.get(params.frameId)!; if (!fp.pinned) this._engine.togglePin(params.frameId); result = { ok: true }; break; }
      case 'frame-unpin': { if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } const fu = this._engine.frames.get(params.frameId)!; if (fu.pinned) this._engine.togglePin(params.frameId); result = { ok: true }; break; }
      case 'frame-detach': { if (this._browserMode) { result = { ok: false, error: 'electron only' }; break; } if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } this._focusedFrameId = params.frameId; await this._detachFrame(); result = { ok: true }; break; }
      case 'frame-attach': { if (this._browserMode) { result = { ok: false, error: 'electron only' }; break; } if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } await this._attachToMainWindow(params.frameId); result = { ok: true }; break; }
      case 'tab-add': { if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } if (!params?.tab?.terminalName) { result = { ok: false, error: 'tab.terminalName required' }; break; } if (this._activeTerminals.has(params.tab.terminalName)) { result = { ok: false, error: 'terminal already open' }; break; } this._engine.addTab(params.frameId, toFrameTabConfig(params.tab)); this._activeTerminals.add(params.tab.terminalName); await this._ensureTerminalExists(params.tab.terminalName); this._scheduleSave(); result = { ok: true }; break; }
      case 'tab-remove': { if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } if (!params?.terminalName) { result = { ok: false, error: 'terminalName required' }; break; } const frame = this._engine.frames.get(params.frameId)!; if (!frame.tabs.find(t => t.key === params.terminalName)) { result = { ok: false, error: 'terminal not in frame' }; break; } this._engine.removeTab(params.frameId, params.terminalName); this._activeTerminals.delete(params.terminalName); const updated = this._engine.frames.get(params.frameId); if (!updated || updated.tabs.length === 0) { this.hideFrame(params.frameId); this.deleteFrame(params.frameId); } this._scheduleSave(); result = { ok: true }; break; }
      case 'group-save': { if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } if (!params?.name) { result = { ok: false, error: 'name required' }; break; } this._focusedFrameId = params.frameId; await this._saveFrameAsGroup(params.name); result = { ok: true }; break; }
      case 'group-update': { if (!this._engine?.frames.has(params?.frameId)) { result = { ok: false, error: 'frame not found' }; break; } if (!this._frameGroupIds.has(params.frameId)) { result = { ok: false, error: 'frame has no group' }; break; } await this._updateGroup(params.frameId); result = { ok: true }; break; }
      case 'group-delete': { if (!params?.groupId) { result = { ok: false, error: 'groupId required' }; break; } await this._deleteGroupById(params.groupId); result = { ok: true }; break; }
      case 'organiser-apply': { const vp = ['side-by-side', 'stacked', 'grid', 'main-sidebar', 'focus']; if (!vp.includes(params?.preset)) { result = { ok: false, error: 'unknown preset: ' + params?.preset }; break; } this.applyOrganiser(params.preset); result = { ok: true }; break; }
      default: result = { ok: false, error: 'unknown command: ' + command };
    }
    this._lastCommandResult = result;
    return result;
  }

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
    const TAB_ACTIONS = [{ name: 'remove', source: 'backend', tool: 'trellis_workspace', operation: 'tab-remove' }];
    const WORKSPACE_ACTIONS = [
      { name: 'create-frame', source: 'backend', tool: 'trellis_workspace', operation: 'frame-create' },
      { name: 'apply-organiser', source: 'backend', tool: 'trellis_workspace', operation: 'organiser-apply' },
    ];
    const frames = this._engine ? [...this._engine.frames.values()].filter(f => !f.hidden).map(f => ({
      id: f.key, order: f.order, position: f.position, size: f.size, zIndex: f.zIndex, pinned: f.pinned, actions: FRAME_ACTIONS,
      tabs: f.tabs.map((t, idx) => ({ terminalName: t.key, type: t.key.startsWith('slot-') ? 'slot' : 'repo', tabIndex: idx, actions: TAB_ACTIONS })),
      activeTabIndex: Math.max(0, f.tabs.findIndex(t => t.key === f.activeTabKey)),
    })) : [];
    const state: Record<string, unknown> = { frames, focusedFrameId: this._focusedFrameId, actions: WORKSPACE_ACTIONS };
    if (this._lastCommandResult) { state.commandResult = this._lastCommandResult; this._lastCommandResult = null; }
    return state;
  }
}
