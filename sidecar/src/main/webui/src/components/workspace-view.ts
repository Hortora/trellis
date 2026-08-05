import { LitElement, html, css, unsafeCSS } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { DockviewComponent, DockviewGroupPanel, themeDark } from 'dockview-core';
import dockviewCSS from 'dockview-core/dist/styles/dockview.css?raw';
import xtermCSS from '@xterm/xterm/css/xterm.css?raw';

const MIN_DELTA = 30;
const ANGLES = 12;

export function nextFramePosition(
  container: { width: number; height: number },
  frameSize: { width: number; height: number },
  existing: { x: number; y: number }[],
  displacement = 40,
): { x: number; y: number } {
  const maxX = Math.max(0, container.width - frameSize.width);
  const maxY = Math.max(0, container.height - frameSize.height);

  if (existing.length === 0) {
    return { x: Math.round(maxX / 2), y: Math.round(maxY / 2) };
  }

  let globalBest: { x: number; y: number } | null = null;
  let globalBestMinDist = -1;

  for (const anchor of existing) {
    for (let d = displacement; d <= Math.max(maxX, maxY) + displacement; d += displacement) {
      for (let i = 0; i < ANGLES; i++) {
        const angle = (i * Math.PI * 2) / ANGLES;
        const x = Math.max(0, Math.min(maxX, Math.round(anchor.x + d * Math.cos(angle))));
        const y = Math.max(0, Math.min(maxY, Math.round(anchor.y + d * Math.sin(angle))));

        const minDist = existing.reduce(
          (min, f) => Math.min(min, Math.hypot(x - f.x, y - f.y)), Infinity,
        );

        if (minDist >= MIN_DELTA) return { x, y };

        if (minDist > globalBestMinDist) {
          globalBestMinDist = minDist;
          globalBest = { x, y };
        }
      }
    }
  }

  return globalBest ?? { x: Math.round(maxX / 2), y: Math.round(maxY / 2) };
}

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
  private _hiddenFrames = new Map<string, { tabs: TabRef[]; groupId?: string }>();
  private _frameTabs = new Map<string, TabRef[]>();
  private _frameGroups = new Map<string, any>();
  private _groupToFrame = new Map<any, string>();
  private _framePositions = new Map<string, { x: number; y: number }>();
  private _nextOrder = 1;
  private _normalMaxZ = 1;
  private _pinnedMaxZ = 1;
  private _pinnedFrames = new Set<string>();
  private _focusedFrameId: string | null = null;
  private _saveDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private _saveMaxWaitTimer: ReturnType<typeof setTimeout> | null = null;
  private _lastSaveTime = 0;

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
      .dockview-container { flex: 1; }
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
      .frame-close-dot { width: 12px; height: 12px; border-radius: 50%; background: #ff5f57; border: none; cursor: pointer; margin: 0 8px 0 4px; padding: 0; flex-shrink: 0; }
      .frame-close-dot:hover { background: #ff3b30; }
      .dv-floating-titlebar { display: flex; align-items: center; }
      .frame-add-tab-btn { background: transparent; border: none; color: #888; font-size: 16px; cursor: pointer; padding: 0 6px; line-height: 1; }
      .frame-add-tab-btn:hover { color: #ccc; }
      .xterm { padding: 4px; }
      .xterm-viewport, .xterm-screen { background-color: #1e1e1e !important; }
      .dv-resize-container { border-radius: 10px; background: #1e1e1e; }
      .dv-groupview { border-radius: 10px; background: #1e1e1e; overflow: hidden; }
      .dv-groupview .dv-tabs-and-actions-container { border-top-left-radius: 10px; border-top-right-radius: 10px; }
      .dv-groupview .dv-content-container { border-bottom-left-radius: 10px; border-bottom-right-radius: 10px; background: #1e1e1e; }
      .dv-render-overlay { background: #1e1e1e; border-radius: 10px; overflow: hidden; }
    `,
  ];

  private _keydownHandler: ((e: KeyboardEvent) => void) | null = null;

  override firstUpdated() {
    this._container = this.shadowRoot!.querySelector('.dockview-container') as HTMLDivElement;
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

  private _onNewFrame() {
    const btn = this.shadowRoot!.querySelector('.new-frame-btn') as HTMLElement;
    if (btn) this._showPicker(btn, 'create');
  }
  private _onNewTab() { /* TODO: show repo/slot picker */ }
  private _onCloseTab() { /* TODO: close active tab in focused frame */ }
  private _showOrganiserPicker() { /* TODO: show preset picker */ }
  private _saveAsGroup() { /* TODO: save focused frame as group */ }
  private _detachFrame() { /* TODO: detach focused frame to new window */ }

  private _terminalElements = new Map<string, HTMLElement>();
  private _pickerEl: HTMLElement | null = null;
  private _backdropEl: HTMLElement | null = null;
  private _pickerDismissEscape: ((e: KeyboardEvent) => void) | null = null;

  private async _showPicker(anchor: HTMLElement, mode: 'create' | 'add', group?: any) {
    this._dismissPicker();
    const workspace = await this._fetchWorkspace();
    const repos = workspace.repos;
    const activeSlots = workspace.slots.filter((s: any) => s.status !== 'ARCHIVED');
    const archivedSlots = workspace.slots.filter((s: any) => s.status === 'ARCHIVED');

    const picker = document.createElement('div');
    picker.className = 'workspace-picker';
    const anchorRect = anchor.getBoundingClientRect();
    const hostRect = this.getBoundingClientRect();
    picker.style.left = `${anchorRect.left - hostRect.left}px`;
    picker.style.top = `${anchorRect.bottom - hostRect.top + 4}px`;

    const selected = new Map<string, string>();
    let confirmBtn!: HTMLButtonElement;
    let selectedArea!: HTMLElement;

    const updateSelectedArea = () => {
      selectedArea.innerHTML = '';
      for (const [termName, type] of selected) {
        const chip = document.createElement('span');
        chip.className = 'selected-chip';
        const label = termName.replace(/^(repo-|slot-)/, '');
        chip.textContent = `${label} (${type})`;
        const removeBtn = document.createElement('button');
        removeBtn.className = 'selected-chip-remove';
        removeBtn.textContent = '×';
        removeBtn.addEventListener('click', () => {
          selected.delete(termName);
          const cb = picker.querySelector(`input[data-term="${termName}"]`) as HTMLInputElement;
          if (cb) cb.checked = false;
          updateSelectedArea();
          confirmBtn.disabled = selected.size === 0;
        });
        chip.appendChild(removeBtn);
        selectedArea.appendChild(chip);
      }
      selectedArea.style.display = selected.size > 0 ? '' : 'none';
      confirmBtn.disabled = selected.size === 0;
    };

    // Tab bar
    const tabBar = document.createElement('div');
    tabBar.className = 'picker-tab-bar';
    const tabNames = ['Repos', 'Slots', 'Attic'];
    const sections: HTMLElement[] = [];
    for (const name of tabNames) {
      const tab = document.createElement('button');
      tab.className = 'picker-tab';
      tab.textContent = name;
      if (name === 'Repos') tab.classList.add('picker-tab-active');
      tab.addEventListener('click', () => {
        tabBar.querySelectorAll('.picker-tab').forEach(t => t.classList.remove('picker-tab-active'));
        tab.classList.add('picker-tab-active');
        sections.forEach(s => s.style.display = s.dataset.section === name.toLowerCase() ? '' : 'none');
      });
      tabBar.appendChild(tab);
    }
    picker.appendChild(tabBar);

    // Selected area (fixed, spans all tabs)
    selectedArea = document.createElement('div');
    selectedArea.className = 'picker-selected';
    selectedArea.style.display = 'none';
    picker.appendChild(selectedArea);

    // Scrollable content wrapper
    const scrollArea = document.createElement('div');
    scrollArea.className = 'picker-scroll';

    const makeItem = (termName: string, displayName: string, type: string, branch?: string) => {
      const isOpen = this._activeTerminals.has(termName);
      const item = document.createElement('label');
      item.className = `picker-item${isOpen ? ' picker-item-disabled' : ''}`;
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.disabled = isOpen;
      cb.dataset.term = termName;
      cb.addEventListener('change', () => {
        if (cb.checked) selected.set(termName, type); else selected.delete(termName);
        updateSelectedArea();
      });
      const nameEl = document.createElement('span');
      nameEl.className = 'picker-name';
      nameEl.textContent = displayName;
      item.appendChild(cb);
      item.appendChild(nameEl);
      if (branch) {
        const branchEl = document.createElement('span');
        branchEl.className = 'picker-branch';
        branchEl.textContent = branch;
        item.appendChild(branchEl);
      }
      return item;
    };

    // Repos section
    const reposSection = document.createElement('div');
    reposSection.className = 'picker-section';
    reposSection.dataset.section = 'repos';
    for (const repo of repos) {
      reposSection.appendChild(makeItem(`repo-${repo.name}`, repo.name, 'repo', repo.branch));
    }
    scrollArea.appendChild(reposSection);
    sections.push(reposSection);

    // Slots section (ACTIVE / READY_TO_LAND)
    const slotsSection = document.createElement('div');
    slotsSection.className = 'picker-section';
    slotsSection.dataset.section = 'slots';
    slotsSection.style.display = 'none';
    for (const slot of activeSlots) {
      const termName = `slot-${slot.number}`;
      const label = `slot-${slot.number}`;
      slotsSection.appendChild(makeItem(termName, label, 'slot', slot.issue));
    }
    if (activeSlots.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'picker-empty';
      empty.textContent = 'No active slots';
      slotsSection.appendChild(empty);
    }
    scrollArea.appendChild(slotsSection);
    sections.push(slotsSection);

    // Attic section (ARCHIVED)
    const atticSection = document.createElement('div');
    atticSection.className = 'picker-section';
    atticSection.dataset.section = 'attic';
    atticSection.style.display = 'none';
    for (const slot of archivedSlots) {
      const termName = `slot-${slot.number}`;
      const label = `slot-${slot.number}`;
      atticSection.appendChild(makeItem(termName, label, 'attic', slot.issue));
    }
    if (archivedSlots.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'picker-empty';
      empty.textContent = 'No archived slots';
      atticSection.appendChild(empty);
    }
    scrollArea.appendChild(atticSection);
    sections.push(atticSection);

    picker.appendChild(scrollArea);

    // Action bar (fixed at bottom)
    const actions = document.createElement('div');
    actions.className = 'picker-actions';
    confirmBtn = document.createElement('button');
    confirmBtn.className = 'picker-confirm';
    confirmBtn.textContent = mode === 'create' ? 'Create Frame' : 'Add';
    confirmBtn.disabled = true;
    confirmBtn.addEventListener('click', () => {
      const tabs: TabRef[] = [...selected.keys()].map(n => ({
        terminalName: n,
        type: (n.startsWith('slot-') ? 'slot' : 'repo') as 'repo' | 'slot',
      }));
      if (mode === 'create') {
        this.createFrame(tabs);
      } else if (group && this._dockview) {
        for (const tab of tabs) {
          if (this._activeTerminals.has(tab.terminalName)) continue;
          this._activeTerminals.add(tab.terminalName);
          this._dockview.addPanel({
            id: tab.terminalName,
            title: tab.terminalName.replace(/^(repo-|slot-)/, ''),
            component: 'terminal',
            position: { referenceGroup: group },
          });
        }
      }
      this._dismissPicker();
    });
    actions.appendChild(confirmBtn);
    picker.appendChild(actions);

    // Backdrop
    const backdrop = document.createElement('div');
    backdrop.className = 'picker-backdrop';
    backdrop.addEventListener('click', () => this._dismissPicker());

    this._backdropEl = backdrop;
    this._pickerEl = picker;
    this.shadowRoot!.appendChild(backdrop);
    this.shadowRoot!.appendChild(picker);

    this._pickerDismissEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') this._dismissPicker();
    };
    document.addEventListener('keydown', this._pickerDismissEscape);
  }

  private _dismissPicker() {
    if (this._pickerDismissEscape) {
      document.removeEventListener('keydown', this._pickerDismissEscape);
      this._pickerDismissEscape = null;
    }
    if (this._backdropEl) {
      this._backdropEl.remove();
      this._backdropEl = null;
    }
    if (this._pickerEl) {
      this._pickerEl.remove();
      this._pickerEl = null;
    }
  }

  private _injectCloseDot(group: any, frameId: string) {
    const el = group.element ?? group.header?.element;
    if (!el) return;
    const tryInject = () => {
      const titlebar = el.closest('.dv-resize-container')?.querySelector('.dv-floating-titlebar');
      if (!titlebar) return false;
      if (titlebar.querySelector('.frame-close-dot')) return true;
      const dot = document.createElement('button');
      dot.className = 'frame-close-dot';
      dot.title = 'Hide frame';
      dot.addEventListener('pointerdown', (e) => e.stopPropagation());
      dot.addEventListener('mousedown', (e) => e.stopPropagation());
      dot.addEventListener('click', (e) => {
        e.stopPropagation();
        this.hideFrame(frameId);
      });
      titlebar.prepend(dot);
      return true;
    };
    if (!tryInject()) {
      requestAnimationFrame(() => tryInject());
    }
  }

  private async _fetchWorkspace(): Promise<{ repos: any[]; slots: any[] }> {
    if (!this.workspaceRoot) return { repos: [], slots: [] };
    try {
      const resp = await fetch(`/api/workspace?root=${encodeURIComponent(this.workspaceRoot)}`);
      if (!resp.ok) return { repos: [], slots: [] };
      const data = await resp.json();
      return { repos: data.repos || [], slots: data.slots || [] };
    } catch { return { repos: [], slots: [] }; }
  }

  private _initDockview() {
    if (!this._container) return;

    this._dockview = new DockviewComponent(this._container, {
      theme: themeDark,
      floatingGroupDragHandle: 'titlebar' as const,
      createRightHeaderActionComponent: (group: any) => {
        const btn = document.createElement('button');
        btn.className = 'frame-add-tab-btn';
        btn.textContent = '+';
        btn.title = 'Add tab';
        btn.addEventListener('click', (e) => {
          e.stopPropagation();
          this._showPicker(btn, 'add', group);
        });
        return { element: btn, init() {}, dispose() {} };
      },
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

    const containerRect = this._container?.getBoundingClientRect() ?? { width: 1200, height: 800 };
    const fWidth = 600;
    const fHeight = 400;
    const pos = nextFramePosition(
      { width: containerRect.width, height: containerRect.height },
      { width: fWidth, height: fHeight },
      [...this._framePositions.values()],
    );
    this._framePositions.set(frameId, pos);

    const firstTab = validTabs[0];
    const panel = this._dockview.addPanel({
      id: firstTab.terminalName,
      title: firstTab.terminalName.replace(/^(repo-|slot-)/, ''),
      component: 'terminal',
      floating: { width: fWidth, height: fHeight, x: pos.x, y: pos.y },
    });
    const group = panel.group;
    this._frameGroups.set(frameId, group);
    this._groupToFrame.set(group, frameId);
    this._injectCloseDot(group, frameId);
    for (let i = 1; i < validTabs.length; i++) {
      this._dockview.addPanel({
        id: validTabs[i].terminalName,
        title: validTabs[i].terminalName.replace(/^(repo-|slot-)/, ''),
        component: 'terminal',
        position: { referenceGroup: group },
      });
    }

    this._frameTabs.set(frameId, [...validTabs]);
    this._focusedFrameId = frameId;
    this._scheduleSave();
    return frameId;
  }

  hideFrame(frameId: string) {
    const tabs = this._frameTabs.get(frameId);
    if (!tabs) return;
    this._hiddenFrames.set(frameId, { tabs: [...tabs] });
    const group = this._frameGroups.get(frameId);
    if (group && this._dockview) {
      this._groupToFrame.delete(group);
      this._frameGroups.delete(frameId);
      try { this._dockview.removeGroup(group); } catch { /* already removed */ }
    }
    for (const tab of tabs) {
      this._activeTerminals.delete(tab.terminalName);
    }
    this._frameTabs.delete(frameId);
    this._framePositions.delete(frameId);
    this._frameOrders.delete(frameId);
    this._pinnedFrames.delete(frameId);
    if (this._focusedFrameId === frameId) this._focusedFrameId = null;
    this._scheduleSave();
  }

  showFrame(frameId: string) {
    const hidden = this._hiddenFrames.get(frameId);
    if (!hidden) return;
    this._hiddenFrames.delete(frameId);
    this.createFrame(hidden.tabs, hidden.groupId);
  }

  deleteFrame(frameId: string) {
    const hidden = this._hiddenFrames.get(frameId);
    if (hidden) {
      this._hiddenFrames.delete(frameId);
    }
    this._frameTabs.delete(frameId);
    this._framePositions.delete(frameId);
    this._frameOrders.delete(frameId);
    this._pinnedFrames.delete(frameId);
    if (this._focusedFrameId === frameId) this._focusedFrameId = null;
    this._scheduleSave();
  }

  removeFrame(frameId: string) {
    this.hideFrame(frameId);
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

  private _showFramesList() {
    this._dismissPicker();
    const btn = this.shadowRoot!.querySelector('.frames-btn') as HTMLElement;
    if (!btn) return;

    const picker = document.createElement('div');
    picker.className = 'workspace-picker';
    const anchorRect = btn.getBoundingClientRect();
    const hostRect = this.getBoundingClientRect();
    picker.style.left = `${anchorRect.left - hostRect.left}px`;
    picker.style.top = `${anchorRect.bottom - hostRect.top + 4}px`;

    const scrollArea = document.createElement('div');
    scrollArea.className = 'picker-scroll';

    const visibleFrames = [...this._frameTabs.entries()];
    const hiddenFrames = [...this._hiddenFrames.entries()];

    if (visibleFrames.length > 0) {
      const header = document.createElement('div');
      header.className = 'frames-section-header';
      header.textContent = 'Visible';
      scrollArea.appendChild(header);
      for (const [frameId, tabs] of visibleFrames) {
        const row = document.createElement('div');
        row.className = 'frames-row';
        const label = document.createElement('span');
        label.className = 'frames-label';
        label.textContent = tabs.map(t => t.terminalName.replace(/^(repo-|slot-)/, '')).join(', ');
        const hideBtn = document.createElement('button');
        hideBtn.className = 'frames-action';
        hideBtn.textContent = 'Hide';
        hideBtn.addEventListener('click', () => { this.hideFrame(frameId); this._dismissPicker(); });
        const delBtn = document.createElement('button');
        delBtn.className = 'frames-action frames-action-delete';
        delBtn.textContent = 'Delete';
        delBtn.addEventListener('click', () => { this.hideFrame(frameId); this.deleteFrame(frameId); this._dismissPicker(); });
        row.appendChild(label);
        row.appendChild(hideBtn);
        row.appendChild(delBtn);
        scrollArea.appendChild(row);
      }
    }

    if (hiddenFrames.length > 0) {
      const header = document.createElement('div');
      header.className = 'frames-section-header';
      header.textContent = 'Hidden';
      scrollArea.appendChild(header);
      for (const [frameId, data] of hiddenFrames) {
        const row = document.createElement('div');
        row.className = 'frames-row frames-row-hidden';
        const label = document.createElement('span');
        label.className = 'frames-label';
        label.textContent = data.tabs.map(t => t.terminalName.replace(/^(repo-|slot-)/, '')).join(', ');
        const showBtn = document.createElement('button');
        showBtn.className = 'frames-action';
        showBtn.textContent = 'Show';
        showBtn.addEventListener('click', () => { this.showFrame(frameId); this._dismissPicker(); });
        const delBtn = document.createElement('button');
        delBtn.className = 'frames-action frames-action-delete';
        delBtn.textContent = 'Delete';
        delBtn.addEventListener('click', () => { this.deleteFrame(frameId); this._dismissPicker(); });
        row.appendChild(label);
        row.appendChild(showBtn);
        row.appendChild(delBtn);
        scrollArea.appendChild(row);
      }
    }

    if (visibleFrames.length === 0 && hiddenFrames.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'picker-empty';
      empty.textContent = 'No frames';
      scrollArea.appendChild(empty);
    }

    picker.appendChild(scrollArea);

    const backdrop = document.createElement('div');
    backdrop.className = 'picker-backdrop';
    backdrop.addEventListener('click', () => this._dismissPicker());

    this._backdropEl = backdrop;
    this._pickerEl = picker;
    this.shadowRoot!.appendChild(backdrop);
    this.shadowRoot!.appendChild(picker);

    this._pickerDismissEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') this._dismissPicker();
    };
    document.addEventListener('keydown', this._pickerDismissEscape);
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
}
