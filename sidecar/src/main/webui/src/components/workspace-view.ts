import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { DockviewComponent, DockviewGroupPanel } from 'dockview-core';

interface TabRef {
  terminalName: string;
  type: 'repo' | 'slot';
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

interface Group {
  id: string;
  name: string;
  tabs: TabRef[];
}

interface ShellLayout {
  id: string;
  bounds: { x: number; y: number; width: number; height: number };
  isMain: boolean;
  frames: FrameLayout[];
  lastActiveFrameId?: string;
}

@customElement('trellis-workspace-view')
export class TrellisWorkspaceView extends LitElement {

  @property() workspaceRoot = '';

  private _dockview: DockviewComponent | null = null;
  private _container: HTMLDivElement | null = null;
  private _activeTerminals = new Set<string>();
  private _frameOrders = new Map<string, number>();
  private _nextOrder = 1;
  private _normalMaxZ = 1;
  private _pinnedMaxZ = 1;
  private _pinnedFrames = new Set<string>();
  private _focusedFrameId: string | null = null;
  private _saveDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private _saveMaxWaitTimer: ReturnType<typeof setTimeout> | null = null;
  private _lastSaveTime = 0;

  static override styles = css`
    :host { display: block; width: 100%; height: 100%; background: #1e1e1e; color: #ccc; position: relative; overflow: hidden; }
    .dockview-container { width: 100%; height: 100%; }
  `;

  private _keydownHandler: ((e: KeyboardEvent) => void) | null = null;

  override firstUpdated() {
    this._container = this.shadowRoot!.querySelector('.dockview-container') as HTMLDivElement;
    this._injectDockviewCSS();
    this._initDockview();
    this._setupFlushHandler();
    this._setupKeyboard();
    this._setupShortcutIPC();
    this._setupDetachIPC();
    this._restoreLayout();
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    if (this._dockview) {
      this._dockview.dispose();
      this._dockview = null;
    }
    if (this._saveDebounceTimer) clearTimeout(this._saveDebounceTimer);
    if (this._saveMaxWaitTimer) clearTimeout(this._saveMaxWaitTimer);
    if (this._keydownHandler) {
      document.removeEventListener('keydown', this._keydownHandler);
      this._keydownHandler = null;
    }
  }

  private _setupKeyboard() {
    this._keydownHandler = (e: KeyboardEvent) => this._handleKeydown(e);
    document.addEventListener('keydown', this._keydownHandler);
  }

  private _setupShortcutIPC() {
    const trellis = (window as any).trellis;
    if (!trellis) return;
    trellis.onShortcut('new-frame', () => this._onNewFrame());
    trellis.onShortcut('new-tab', () => this._onNewTab());
    trellis.onShortcut('close-tab', () => this._onCloseTab());
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
    if (meta && shift && e.key === 'S') { e.preventDefault(); this._saveAsGroup(); return; }
    if (meta && shift && e.key === 'D') { e.preventDefault(); this._detachFrame(); return; }

    if (meta && e.ctrlKey && e.key === ']') { e.preventDefault(); (window as any).trellis?.nextWindow(); return; }
    if (meta && e.ctrlKey && e.key === '[') { e.preventDefault(); (window as any).trellis?.prevWindow(); return; }
  }

  private _nextTab() { /* TODO: cycle active tab in focused frame */ }
  private _prevTab() { /* TODO: cycle active tab in focused frame */ }
  private _jumpToTab(_index: number) { /* TODO: jump to tab N in focused frame */ }

  private _nextFrame() {
    const orders = [...this._frameOrders.entries()].sort((a, b) => a[1] - b[1]);
    if (orders.length < 2) return;
    const currentIdx = orders.findIndex(([id]) => id === this._focusedFrameId);
    const nextIdx = (currentIdx + 1) % orders.length;
    this._focusedFrameId = orders[nextIdx][0];
  }

  private _prevFrame() {
    const orders = [...this._frameOrders.entries()].sort((a, b) => a[1] - b[1]);
    if (orders.length < 2) return;
    const currentIdx = orders.findIndex(([id]) => id === this._focusedFrameId);
    const prevIdx = (currentIdx - 1 + orders.length) % orders.length;
    this._focusedFrameId = orders[prevIdx][0];
  }

  private _jumpToFrame(index: number) {
    const orders = [...this._frameOrders.entries()].sort((a, b) => a[1] - b[1]);
    if (index >= 0 && index < orders.length) {
      this._focusedFrameId = orders[index][0];
    }
  }

  private _spatialNav(direction: 'up' | 'down' | 'left' | 'right') {
    // Spatial navigation: find nearest frame in the given direction
    // Uses center-to-center Euclidean distance in the directional half-plane
    // Full implementation requires frame position tracking from Dockview
  }

  private _onNewFrame() { /* TODO: show group picker */ }
  private _onNewTab() { /* TODO: show repo/slot picker */ }
  private _onCloseTab() { /* TODO: close active tab in focused frame */ }
  private _showOrganiserPicker() { /* TODO: show preset picker */ }
  private _saveAsGroup() { /* TODO: save focused frame as group */ }
  private _detachFrame() { /* TODO: detach focused frame to new window */ }

  private _terminalElements = new Map<string, HTMLElement>();
  private _cssInjected = false;

  private _injectDockviewCSS() {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'https://cdn.jsdelivr.net/npm/dockview-core@7.0.4/dist/styles/dockview.css';
    this.shadowRoot!.appendChild(link);
  }

  private _initDockview() {
    if (!this._container) return;

    if (!this._cssInjected) {
      const xtermLink = document.createElement('link');
      xtermLink.rel = 'stylesheet';
      xtermLink.href = 'https://cdn.jsdelivr.net/npm/@xterm/xterm@6.0.0/css/xterm.min.css';
      this.shadowRoot!.appendChild(xtermLink);
      this._cssInjected = true;
    }

    this._dockview = new DockviewComponent(this._container, {
      createComponent: (options) => {
        const wrapper = document.createElement('div');
        wrapper.style.cssText = 'width:100%;height:100%;display:flex;flex-direction:column;overflow:hidden;';

        const terminal = document.createElement('pages-component-terminal') as any;
        terminal.style.cssText = 'flex:1;overflow:hidden;';
        wrapper.appendChild(terminal);

        const terminalName = options.id;
        this._terminalElements.set(terminalName, terminal);

        this._connectTerminal(terminalName, terminal);

        terminal.addEventListener('pages-event', (e: any) => {
          const { topic, payload } = e.detail;
          if (topic === 'terminal-resize') {
            fetch(`/api/terminals/${terminalName}/resize`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ cols: payload.cols, rows: payload.rows }),
            }).catch(() => {});
          }
        });

        return {
          element: wrapper,
          init: () => {},
          update: () => {},
          dispose: () => {
            this._terminalElements.delete(terminalName);
            this._activeTerminals.delete(terminalName);
          },
        };
      },
    });

    this._dockview.onDidLayoutChange(() => this._scheduleSave());
  }

  private async _connectTerminal(terminalName: string, terminalEl: any) {
    const exists = await this._ensureTerminalExists(terminalName);
    if (!exists) return;

    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    terminalEl.configure({
      wsUrl: `${proto}//${location.host}/ws/terminal/${terminalName}/{cols}/{rows}`,
      theme: { background: '#1e1e1e', foreground: '#cccccc', cursor: '#aeafad' },
      fontSize: 13,
      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
    });
  }

  private async _ensureTerminalExists(terminalName: string): Promise<boolean> {
    const type = terminalName.startsWith('slot-') ? 'slot' : 'repo';
    const repoName = type === 'repo' ? terminalName.replace(/^repo-/, '') : undefined;

    try {
      const resp = await fetch(`/api/terminals/${terminalName}`);
      if (resp.ok) return true;
    } catch { /* fall through to create */ }

    if (type === 'slot') return false;

    try {
      const createResp = await fetch('/api/terminals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: terminalName,
          workingDir: `/tmp`,
          repo: repoName,
        }),
      });
      return createResp.status === 201 || createResp.status === 409;
    } catch {
      return false;
    }
  }

  private _setupFlushHandler() {
    const trellis = (window as any).trellis;
    if (trellis?.onLayoutFlush) {
      trellis.onLayoutFlush(() => {
        const layout = this._serializeLayout();
        trellis.saveWindowLayout(layout);
      });
    }
  }

  createFrame(tabs: TabRef[], groupId?: string, name?: string): string {
    if (!this._dockview) return '';

    const frameId = `frame-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    const order = this._nextOrder++;
    this._frameOrders.set(frameId, order);

    const validTabs = tabs.filter(t => !this._activeTerminals.has(t.terminalName));
    if (validTabs.length === 0 && tabs.length > 0) return '';

    for (const tab of validTabs) {
      this._activeTerminals.add(tab.terminalName);
    }

    const firstTab = validTabs[0];
    const panel = this._dockview.addPanel({
      id: firstTab.terminalName,
      title: firstTab.terminalName.replace(/^(repo-|slot-)/, ''),
      component: 'terminal',
      floating: { width: 600, height: 400 },
    });
    const group = panel.group;
    for (let i = 1; i < validTabs.length; i++) {
      this._dockview.addPanel({
        id: validTabs[i].terminalName,
        title: validTabs[i].terminalName.replace(/^(repo-|slot-)/, ''),
        component: 'terminal',
        position: { referenceGroup: group },
      });
    }

    this._focusedFrameId = frameId;
    this._scheduleSave();
    return frameId;
  }

  removeFrame(frameId: string) {
    this._frameOrders.delete(frameId);
    this._pinnedFrames.delete(frameId);
    if (this._focusedFrameId === frameId) this._focusedFrameId = null;
    this._scheduleSave();
  }

  togglePin(frameId: string) {
    if (this._pinnedFrames.has(frameId)) {
      this._pinnedFrames.delete(frameId);
    } else {
      this._pinnedFrames.add(frameId);
    }
    this._scheduleSave();
  }

  isTerminalOpen(terminalName: string): boolean {
    return this._activeTerminals.has(terminalName);
  }

  private _scheduleSave() {
    if (this._saveDebounceTimer) clearTimeout(this._saveDebounceTimer);
    this._saveDebounceTimer = setTimeout(() => this._doSave(), 1000);

    const now = Date.now();
    if (!this._saveMaxWaitTimer) {
      this._saveMaxWaitTimer = setTimeout(() => {
        this._saveMaxWaitTimer = null;
        if (this._saveDebounceTimer) {
          clearTimeout(this._saveDebounceTimer);
          this._saveDebounceTimer = null;
        }
        this._doSave();
      }, 5000);
    }
  }

  private _doSave() {
    this._saveDebounceTimer = null;
    this._lastSaveTime = Date.now();
    if (this._saveMaxWaitTimer) {
      clearTimeout(this._saveMaxWaitTimer);
      this._saveMaxWaitTimer = null;
    }

    const trellis = (window as any).trellis;
    if (trellis?.saveWindowLayout) {
      const layout = this._serializeLayout();
      trellis.saveWindowLayout(layout);
    }
  }

  private _serializeLayout(): ShellLayout {
    const frames: FrameLayout[] = [];
    let zCounter = 1;

    for (const [frameId, order] of this._frameOrders) {
      const pinned = this._pinnedFrames.has(frameId);
      frames.push({
        id: frameId,
        order,
        position: { x: 0, y: 0 },
        size: { width: 600, height: 400 },
        zIndex: zCounter++,
        pinned,
        tabs: [],
        activeTabIndex: 0,
      });
    }

    return {
      id: `shell-${Date.now()}`,
      bounds: { x: 0, y: 0, width: window.innerWidth, height: window.innerHeight },
      isMain: true,
      frames,
      lastActiveFrameId: this._focusedFrameId ?? undefined,
    };
  }

  private async _restoreLayout() {
    const trellis = (window as any).trellis;
    if (!trellis) return;

    const workspacePath = await trellis.getLastWorkspacePath();
    if (!workspacePath) return;

    const persisted = await trellis.loadLayout(workspacePath);
    if (!persisted?.windows) return;

    const shell = persisted.windows.find((w: ShellLayout) => w.isMain) || persisted.windows[0];
    if (!shell?.frames) return;

    for (const frame of shell.frames.sort((a: FrameLayout, b: FrameLayout) => a.order - b.order)) {
      if (frame.tabs && frame.tabs.length > 0) {
        this.createFrame(frame.tabs, frame.groupId, undefined);
      }
    }

    if (shell.lastActiveFrameId) {
      this._focusedFrameId = shell.lastActiveFrameId;
    }
  }

  private _setupDetachIPC() {
    const trellis = (window as any).trellis;
    if (!trellis) return;

    trellis.onFrameInit((_event: any, frameLayout: FrameLayout) => {
      if (frameLayout.tabs && frameLayout.tabs.length > 0) {
        this.createFrame(frameLayout.tabs, frameLayout.groupId, undefined);
      }
    });

    trellis.onFrameReceive((_event: any, frameLayout: FrameLayout) => {
      if (this._frameOrders.has(frameLayout.id)) return;
      if (frameLayout.tabs && frameLayout.tabs.length > 0) {
        this.createFrame(frameLayout.tabs, frameLayout.groupId, undefined);
      }
    });
  }

  async saveCurrentGroups(name: string): Promise<void> {
    const trellis = (window as any).trellis;
    if (!trellis || !this.workspaceRoot) return;

    const existing = await trellis.loadGroups(this.workspaceRoot) || { groups: [] };
    const tabs = [...this._activeTerminals].map(t => ({
      terminalName: t,
      type: (t.startsWith('slot-') ? 'slot' : 'repo') as 'repo' | 'slot',
    }));

    const group: Group = {
      id: `group-${Date.now()}`,
      name,
      tabs,
    };

    existing.groups.push(group);
    await trellis.saveGroups(this.workspaceRoot, existing);
  }

  async loadGroups(): Promise<Group[]> {
    const trellis = (window as any).trellis;
    if (!trellis || !this.workspaceRoot) return [];
    const data = await trellis.loadGroups(this.workspaceRoot);
    return data?.groups || [];
  }

  override render() {
    return html`<div class="dockview-container"></div>`;
  }
}
