import { LitElement, html, css, unsafeCSS } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { DockviewComponent, DockviewGroupPanel, themeDark } from 'dockview-core';
import dockviewCSS from 'dockview-core/dist/styles/dockview.css?raw';
import xtermCSS from '@xterm/xterm/css/xterm.css?raw';
import { bringToFront as zBringToFront, compactFrames, normalizeForSave } from './workspace-zorder.js';
import { findSpatialTarget } from './workspace-spatial-nav.js';
import { PRESETS } from './workspace-organisers.js';
import { computeAllTiers, computeTransitions, type RendererTier } from './workspace-renderer-tiers.js';

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

export function clampPosition(
  position: { x: number; y: number },
  size: { width: number; height: number },
  container: { width: number; height: number },
): { x: number; y: number } {
  const maxX = Math.max(0, container.width - size.width);
  const maxY = Math.max(0, container.height - size.height);
  return {
    x: Math.max(0, Math.min(position.x, maxX)),
    y: Math.max(0, Math.min(position.y, maxY)),
  };
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
  private _frameZIndices = new Map<string, number>();
  private _frameActiveTab = new Map<string, number>();
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
      .dockview-container { isolation: isolate; }
      .frame-focused .dv-groupview { box-shadow: 0 0 0 1px rgba(59, 130, 246, 0.5); }
      .frame-pin-btn, .frame-detach-btn { background: transparent; border: none; color: #888; font-size: 13px; cursor: pointer; padding: 0 4px; line-height: 1; }
      .frame-pin-btn:hover, .frame-detach-btn:hover { color: #ccc; }
      .frame-pin-btn.pinned { color: #3b82f6; }
      .custom-tab { display: inline-flex; align-items: center; padding: 0 8px; color: #ccc; font-size: 12px; white-space: nowrap; cursor: pointer; height: 100%; }
      .custom-tab:hover { color: #fff; }
    `,
  ];

  private _keydownHandler: ((e: KeyboardEvent) => void) | null = null;

  override firstUpdated() {
    this._browserMode = !(window as any).trellis;
    this._container = this.shadowRoot!.querySelector('.dockview-container') as HTMLDivElement;
    this._initDockview();
    this._setupFlushHandler();
    this._setupKeyboard();
    this._setupShortcutIPC();
    this._setupDetachIPC();
    this._restoreLayout();
    this._setupAgentSSE();
    this._setupWebglIPC();
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
    this._hideTabFlyoutImmediate();
    if (this._flyoutHideTimer) clearTimeout(this._flyoutHideTimer);
    if (this._sseSource) { this._sseSource.close(); this._sseSource = null; }
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
    if (meta && shift && e.key === 'S') { e.preventDefault(); this._promptSaveAsGroup(); return; }
    if (meta && shift && e.key === 'D') { e.preventDefault(); this._detachFrame(); return; }
    if (meta && shift && e.key === 'Backspace') { e.preventDefault(); if (this._focusedFrameId && this._frameGroupIds.has(this._focusedFrameId)) { this._deleteGroup(this._focusedFrameId); } return; }

    if (meta && e.ctrlKey && e.key === ']') { e.preventDefault(); (window as any).trellis?.nextWindow(); return; }
    if (meta && e.ctrlKey && e.key === '[') { e.preventDefault(); (window as any).trellis?.prevWindow(); return; }
  }

  private _nextTab() {
    if (!this._focusedFrameId) return;
    const tabs = this._frameTabs.get(this._focusedFrameId);
    if (!tabs || tabs.length < 2) return;
    const current = this._frameActiveTab.get(this._focusedFrameId) ?? 0;
    this._frameActiveTab.set(this._focusedFrameId, (current + 1) % tabs.length);
  }

  private _prevTab() {
    if (!this._focusedFrameId) return;
    const tabs = this._frameTabs.get(this._focusedFrameId);
    if (!tabs || tabs.length < 2) return;
    const current = this._frameActiveTab.get(this._focusedFrameId) ?? 0;
    this._frameActiveTab.set(this._focusedFrameId, (current - 1 + tabs.length) % tabs.length);
  }

  private _jumpToTab(index: number) {
    if (!this._focusedFrameId) return;
    const tabs = this._frameTabs.get(this._focusedFrameId);
    if (!tabs || index < 0 || index >= tabs.length) return;
    this._frameActiveTab.set(this._focusedFrameId, index);
  }

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
    if (!this._focusedFrameId) return;
    const frames = [...this._framePositions.entries()].map(([id, pos]) => ({
      id,
      x: pos.x,
      y: pos.y,
      width: 600,
      height: 400,
    }));
    const target = findSpatialTarget(this._focusedFrameId, frames, direction);
    if (target) {
      this._focusedFrameId = target;
      this.bringToFront(target);
    }
  }

  private _onNewFrame() {
    const btn = this.shadowRoot!.querySelector('.new-frame-btn') as HTMLElement;
    if (btn) this._showPicker(btn, 'create');
  }
  private _onNewTab() {
    if (!this._focusedFrameId) return;
    const group = this._frameGroups.get(this._focusedFrameId);
    if (!group) return;
    const addBtn = this.shadowRoot?.querySelector('.frame-add-tab-btn') as HTMLElement;
    if (addBtn) this._showPicker(addBtn, 'add', group);
  }

  private _onCloseTab() {
    if (!this._focusedFrameId) return;
    const tabs = this._frameTabs.get(this._focusedFrameId);
    if (!tabs || tabs.length === 0) return;
    const activeIdx = this._frameActiveTab.get(this._focusedFrameId) ?? 0;
    const removed = tabs[activeIdx];

    if (tabs.length === 1) {
      this.hideFrame(this._focusedFrameId);
      return;
    }

    tabs.splice(activeIdx, 1);
    this._activeTerminals.delete(removed.terminalName);

    if (this._dockview?.panels) {
      const panel = this._dockview.panels.find((p: any) => p.id === removed.terminalName);
      if (panel) panel.api.close();
    }

    const newIdx = Math.min(activeIdx, tabs.length - 1);
    this._frameActiveTab.set(this._focusedFrameId, newIdx);
  }
  private _showOrganiserPicker() {
    this._dismissPicker();
    const picker = document.createElement('div');
    picker.className = 'workspace-picker';
    picker.style.left = '50%';
    picker.style.top = '50%';
    picker.style.transform = 'translate(-50%, -50%)';

    const scrollArea = document.createElement('div');
    scrollArea.className = 'picker-scroll';

    PRESETS.forEach((preset, i) => {
      const item = document.createElement('div');
      item.className = 'picker-item';
      item.style.cursor = 'pointer';
      const label = document.createElement('span');
      label.className = 'picker-name';
      label.textContent = `${i + 1}. ${preset.name}`;
      item.appendChild(label);
      item.addEventListener('click', () => {
        this.applyOrganiser(preset.name);
        this._dismissPicker();
      });
      scrollArea.appendChild(item);
    });

    picker.appendChild(scrollArea);

    const backdrop = document.createElement('div');
    backdrop.className = 'picker-backdrop';
    backdrop.addEventListener('click', () => this._dismissPicker());

    this._backdropEl = backdrop;
    this._pickerEl = picker;
    this.shadowRoot!.appendChild(backdrop);
    this.shadowRoot!.appendChild(picker);

    this._pickerDismissEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { this._dismissPicker(); return; }
      const num = parseInt(e.key);
      if (num >= 1 && num <= PRESETS.length) {
        this.applyOrganiser(PRESETS[num - 1].name);
        this._dismissPicker();
      }
    };
    document.addEventListener('keydown', this._pickerDismissEscape);
  }

  applyOrganiser(presetName: string) {
    const preset = PRESETS.find(p => p.name === presetName);
    if (!preset) return;

    const containerRect = this._container?.getBoundingClientRect() ?? { width: 1200, height: 800 };
    const canvasSize = { width: containerRect.width, height: containerRect.height };

    const frames = [...this._frameOrders.entries()].map(([id, order]) => ({
      id,
      x: this._framePositions.get(id)?.x ?? 0,
      y: this._framePositions.get(id)?.y ?? 0,
      width: 600,
      height: 400,
      pinned: this._pinnedFrames.has(id),
    }));

    const arranged = preset.fn(frames, canvasSize);

    for (const f of arranged) {
      if (this._pinnedFrames.has(f.id)) continue;
      this._framePositions.set(f.id, { x: f.x, y: f.y });
    }

    this._scheduleSave();
  }
  private async _detachFrame() {
    if (!this._focusedFrameId) return;
    const trellis = (window as any).trellis;
    if (!trellis?.createWindow) return;

    const frameId = this._focusedFrameId;
    const tabs = this._frameTabs.get(frameId);
    if (!tabs || tabs.length === 0) return;

    const frameLayout: FrameLayout = {
      id: frameId,
      groupId: this._frameGroupIds.get(frameId),
      order: this._frameOrders.get(frameId) ?? 1,
      position: this._framePositions.get(frameId) ?? { x: 0, y: 0 },
      size: { width: 600, height: 400 },
      zIndex: this._frameZIndices.get(frameId) ?? 1,
      pinned: this._pinnedFrames.has(frameId),
      tabs: tabs.map((t: TabRef) => ({ ...t })),
      activeTabIndex: this._frameActiveTab.get(frameId) ?? 0,
    };

    await trellis.inhibitSave();
    const route = '/workspace?root=' + encodeURIComponent(this.workspaceRoot);
    await trellis.createWindow(route, { frameLayout, width: 600, height: 400 });
    this.hideFrame(frameId);
    this.deleteFrame(frameId);
    await trellis.releaseSave();
  }

  private async _loadGroupsData(): Promise<{ groups: Group[] }> {
    if (!this.workspaceRoot) return { groups: [] };
    const trellis = (window as any).trellis;
    if (trellis?.loadGroups) {
      return (await trellis.loadGroups(this.workspaceRoot)) || { groups: [] };
    }
    try {
      const resp = await fetch('/api/workspace/groups?root=' + encodeURIComponent(this.workspaceRoot));
      if (resp.ok) return await resp.json();
    } catch { /* non-critical */ }
    return { groups: [] };
  }

  private async _saveGroupsData(data: { groups: Group[] }): Promise<void> {
    if (!this.workspaceRoot) return;
    const trellis = (window as any).trellis;
    if (trellis?.saveGroups) {
      await trellis.saveGroups(this.workspaceRoot, data);
      return;
    }
    try {
      await fetch('/api/workspace/groups?root=' + encodeURIComponent(this.workspaceRoot), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      });
    } catch { /* non-critical */ }
  }

  private async _saveFrameAsGroup(name: string): Promise<void> {
    if (!this._focusedFrameId || !this.workspaceRoot) return;

    const tabs = this._frameTabs.get(this._focusedFrameId);
    if (!tabs || tabs.length === 0) return;

    const existing = await this._loadGroupsData();
    const group: Group = {
      id: `group-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      name,
      tabs: tabs.map((t: TabRef) => ({ ...t })),
    };
    existing.groups.push(group);
    await this._saveGroupsData(existing);
    this._frameGroupIds.set(this._focusedFrameId, group.id);
  }

  private async _updateGroup(frameId: string): Promise<void> {
    const groupId = this._frameGroupIds.get(frameId);
    if (!groupId || !this.workspaceRoot) return;

    const tabs = this._frameTabs.get(frameId);
    if (!tabs) return;

    const existing = await this._loadGroupsData();
    const group = existing.groups.find((g: Group) => g.id === groupId);
    if (!group) return;
    group.tabs = tabs.map((t: TabRef) => ({ ...t }));
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

  private _promptSaveAsGroup() {
    if (!this._focusedFrameId) return;
    this._dismissPicker();

    const picker = document.createElement('div');
    picker.className = 'workspace-picker';
    picker.style.left = '50%';
    picker.style.top = '50%';
    picker.style.transform = 'translate(-50%, -50%)';
    picker.style.padding = '12px';

    const label = document.createElement('div');
    label.style.cssText = 'color:#ccc;font-size:13px;margin-bottom:8px;';
    label.textContent = 'Group name:';

    const input = document.createElement('input');
    input.type = 'text';
    input.style.cssText = 'width:100%;background:#1e1e1e;border:1px solid #555;color:#ccc;padding:4px 8px;border-radius:3px;font-size:13px;box-sizing:border-box;';

    const actions = document.createElement('div');
    actions.style.cssText = 'display:flex;justify-content:flex-end;gap:6px;margin-top:8px;';

    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'frames-action';
    cancelBtn.textContent = 'Cancel';

    const saveBtn = document.createElement('button');
    saveBtn.className = 'picker-confirm';
    saveBtn.textContent = 'Save';
    saveBtn.disabled = true;

    input.addEventListener('input', () => {
      saveBtn.disabled = !input.value.trim();
    });

    const doSave = () => {
      const name = input.value.trim();
      if (name) this._saveFrameAsGroup(name);
      this._dismissPicker();
    };

    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && input.value.trim()) doSave();
      if (e.key === 'Escape') this._dismissPicker();
      e.stopPropagation();
    });
    cancelBtn.addEventListener('click', () => this._dismissPicker());
    saveBtn.addEventListener('click', doSave);

    actions.appendChild(cancelBtn);
    actions.appendChild(saveBtn);
    picker.appendChild(label);
    picker.appendChild(input);
    picker.appendChild(actions);

    const backdrop = document.createElement('div');
    backdrop.className = 'picker-backdrop';
    backdrop.addEventListener('click', () => this._dismissPicker());

    this._backdropEl = backdrop;
    this._pickerEl = picker;
    this.shadowRoot!.appendChild(backdrop);
    this.shadowRoot!.appendChild(picker);
    requestAnimationFrame(() => input.focus());
  }

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
    const tabNames = ['Repos', 'Slots', 'Groups', 'Attic'];
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

    // Groups section
    const groups = await this.loadGroups();
    const groupsSection = document.createElement('div');
    groupsSection.className = 'picker-section';
    groupsSection.dataset.section = 'groups';
    groupsSection.style.display = 'none';
    for (const grp of groups) {
      const item = document.createElement('div');
      item.className = 'picker-item';
      item.style.cursor = 'pointer';
      const nameEl = document.createElement('span');
      nameEl.className = 'picker-name';
      nameEl.textContent = grp.name;
      const countEl = document.createElement('span');
      countEl.className = 'picker-branch';
      countEl.textContent = `${grp.tabs.length} tabs`;
      item.appendChild(nameEl);
      item.appendChild(countEl);
      item.addEventListener('click', () => {
        this.createFrame(grp.tabs, grp.id);
        this._dismissPicker();
      });
      groupsSection.appendChild(item);
    }
    if (groups.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'picker-empty';
      empty.textContent = 'No saved groups — Cmd+Shift+S to save';
      groupsSection.appendChild(empty);
    }
    scrollArea.appendChild(groupsSection);
    sections.push(groupsSection);

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

  private _injectFrameChrome(group: any, frameId: string) {
    const el = group.element ?? group.header?.element;
    if (!el) return;
    const tryInject = () => {
      const container = el.closest('.dv-resize-container') as HTMLElement | null;
      const titlebar = container?.querySelector('.dv-floating-titlebar');
      if (!titlebar) return false;
      if (titlebar.querySelector('.frame-close-dot')) return true;

      const stopPropagation = (e: Event) => e.stopPropagation();

      const dot = document.createElement('button');
      dot.className = 'frame-close-dot';
      dot.title = 'Hide frame';
      dot.addEventListener('pointerdown', stopPropagation);
      dot.addEventListener('mousedown', stopPropagation);
      dot.addEventListener('click', (e) => { e.stopPropagation(); this.hideFrame(frameId); });

      const pinBtn = document.createElement('button');
      pinBtn.className = 'frame-pin-btn';
      pinBtn.textContent = '\u{1F4CC}';
      pinBtn.title = 'Pin/unpin frame';
      pinBtn.addEventListener('pointerdown', stopPropagation);
      pinBtn.addEventListener('mousedown', stopPropagation);
      pinBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        this.togglePin(frameId);
        pinBtn.classList.toggle('pinned', this._pinnedFrames.has(frameId));
      });

      const detachBtn = document.createElement('button');
      detachBtn.className = 'frame-detach-btn';
      detachBtn.textContent = '⎋';
      detachBtn.title = 'Detach frame';
      detachBtn.addEventListener('pointerdown', stopPropagation);
      detachBtn.addEventListener('mousedown', stopPropagation);
      detachBtn.addEventListener('click', (e) => { e.stopPropagation(); this._detachFrame(); });

      titlebar.prepend(dot);
      titlebar.appendChild(pinBtn);
      titlebar.appendChild(detachBtn);

      titlebar.addEventListener('contextmenu', (e: Event) => {
        e.preventDefault();
        e.stopPropagation();
        this._showFrameContextMenu(frameId, e as MouseEvent);
      });

      container?.addEventListener('pointerdown', () => {
        this.bringToFront(frameId);
      });

      return true;
    };
    if (!tryInject()) {
      requestAnimationFrame(() => tryInject());
    }
  }

  private _showFrameContextMenu(frameId: string, event: MouseEvent) {
    this._dismissPicker();
    const menu = document.createElement('div');
    menu.className = 'workspace-picker';
    menu.style.left = `${event.clientX - this.getBoundingClientRect().left}px`;
    menu.style.top = `${event.clientY - this.getBoundingClientRect().top}px`;
    menu.style.minWidth = '180px';

    const addItem = (label: string, action: () => void) => {
      const item = document.createElement('div');
      item.className = 'picker-item';
      item.style.cursor = 'pointer';
      const nameEl = document.createElement('span');
      nameEl.className = 'picker-name';
      nameEl.textContent = label;
      item.appendChild(nameEl);
      item.addEventListener('click', () => { action(); this._dismissPicker(); });
      menu.appendChild(item);
    };

    addItem('Save as Group', () => this._promptSaveAsGroup());
    const groupId = this._frameGroupIds.get(frameId);
    if (groupId) {
      addItem('Update Group', () => this._updateGroup(frameId));
      addItem('Delete Group', () => this._deleteGroup(frameId));
    }
    addItem('Attach to main window', () => this._attachToMainWindow(frameId));

    const backdrop = document.createElement('div');
    backdrop.className = 'picker-backdrop';
    backdrop.addEventListener('click', () => this._dismissPicker());

    this._backdropEl = backdrop;
    this._pickerEl = menu;
    this.shadowRoot!.appendChild(backdrop);
    this.shadowRoot!.appendChild(menu);
  }

  private async _attachToMainWindow(frameId: string) {
    const trellis = (window as any).trellis;
    if (!trellis?.listWindows) return;

    const tabs = this._frameTabs.get(frameId);
    if (!tabs || tabs.length === 0) return;

    const windows = await trellis.listWindows();
    if (!windows || windows.length < 2) return;

    const frameLayout: FrameLayout = {
      id: frameId,
      groupId: this._frameGroupIds.get(frameId),
      order: this._frameOrders.get(frameId) ?? 1,
      position: this._framePositions.get(frameId) ?? { x: 0, y: 0 },
      size: { width: 600, height: 400 },
      zIndex: this._frameZIndices.get(frameId) ?? 1,
      pinned: this._pinnedFrames.has(frameId),
      tabs: tabs.map((t: TabRef) => ({ ...t })),
      activeTabIndex: this._frameActiveTab.get(frameId) ?? 0,
    };

    const targetWinId = windows[0].id;
    await trellis.attachPanel(frameId, targetWinId);
    this.hideFrame(frameId);
    this.deleteFrame(frameId);
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
      createTabComponent: (options: any) => {
        const tabEl = document.createElement('div');
        tabEl.className = 'custom-tab';
        const terminalName = options.id;

        let hoverTimer: ReturnType<typeof setTimeout> | null = null;

        tabEl.addEventListener('mouseenter', () => {
          hoverTimer = setTimeout(() => {
            this._showTabFlyout(terminalName, tabEl);
          }, 300);
        });

        tabEl.addEventListener('mouseleave', () => {
          if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null; }
          this._hideTabFlyout();
        });

        return {
          element: tabEl,
          init(params: any) {
            tabEl.textContent = params.title ?? terminalName;
          },
          update(event: any) {
            if (event.params?.title) tabEl.textContent = event.params.title;
          },
          dispose() {
            if (hoverTimer) clearTimeout(hoverTimer);
          },
        };
      },
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

  createFrame(tabs: TabRef[], groupId?: string, name?: string, restore?: Partial<FrameLayout>): string {
    if (!this._dockview) return '';

    const frameId = `frame-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    const order = restore?.order ?? this._nextOrder++;
    if (restore?.order && restore.order >= this._nextOrder) {
      this._nextOrder = restore.order + 1;
    }
    this._frameOrders.set(frameId, order);

    const validTabs = tabs.filter(t => !this._activeTerminals.has(t.terminalName));
    if (validTabs.length === 0 && tabs.length > 0) return '';

    for (const tab of validTabs) {
      this._activeTerminals.add(tab.terminalName);
    }

    const rect = this._container?.getBoundingClientRect();
    const containerRect = (rect && rect.width > 0 && rect.height > 0) ? rect : { width: 1200, height: 800 };
    const fWidth = restore?.size?.width ?? 600;
    const fHeight = restore?.size?.height ?? 400;
    const pos = restore?.position
      ? clampPosition(restore.position, { width: fWidth, height: fHeight },
          { width: containerRect.width, height: containerRect.height })
      : nextFramePosition(
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
    this._injectFrameChrome(group, frameId);
    for (let i = 1; i < validTabs.length; i++) {
      this._dockview.addPanel({
        id: validTabs[i].terminalName,
        title: validTabs[i].terminalName.replace(/^(repo-|slot-)/, ''),
        component: 'terminal',
        position: { referenceGroup: group },
      });
    }

    this._frameTabs.set(frameId, [...validTabs]);
    this._frameActiveTab.set(frameId, restore?.activeTabIndex ?? 0);

    if (restore) {
      const z = restore.zIndex ?? 1;
      this._frameZIndices.set(frameId, z);
      if (restore.pinned) {
        this._pinnedFrames.add(frameId);
        const pinnedZ = z - 10000;
        if (pinnedZ >= this._pinnedMaxZ) this._pinnedMaxZ = pinnedZ;
      } else {
        if (z >= this._normalMaxZ) this._normalMaxZ = z;
      }
    } else {
      const zResult = zBringToFront(this._normalMaxZ, false);
      this._normalMaxZ = zResult.counter;
      this._frameZIndices.set(frameId, zResult.zIndex);
      this._focusedFrameId = frameId;
    }

    if (groupId) {
      this._frameGroupIds.set(frameId, groupId);
    }
    if (!restore) {
      this._scheduleSave();
    }
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
    this._frameActiveTab.delete(frameId);
    this._framePositions.delete(frameId);
    this._frameOrders.delete(frameId);
    this._pinnedFrames.delete(frameId);
    this._frameZIndices.delete(frameId);
    this._frameGroupIds.delete(frameId);
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
    this._frameActiveTab.delete(frameId);
    this._framePositions.delete(frameId);
    this._frameOrders.delete(frameId);
    this._pinnedFrames.delete(frameId);
    this._frameZIndices.delete(frameId);
    this._frameGroupIds.delete(frameId);
    if (this._focusedFrameId === frameId) this._focusedFrameId = null;
    this._scheduleSave();
  }

  removeFrame(frameId: string) {
    this.hideFrame(frameId);
  }

  togglePin(frameId: string) {
    const wasPinned = this._pinnedFrames.has(frameId);
    if (wasPinned) {
      this._pinnedFrames.delete(frameId);
      const result = zBringToFront(this._normalMaxZ, false);
      this._normalMaxZ = result.counter;
      this._frameZIndices.set(frameId, result.zIndex);
      if (result.needsCompaction) this._compactZOrder();
    } else {
      this._pinnedFrames.add(frameId);
      const result = zBringToFront(this._pinnedMaxZ, true);
      this._pinnedMaxZ = result.counter;
      this._frameZIndices.set(frameId, result.zIndex);
      if (result.needsCompaction) this._compactZOrder();
    }
    this._applyZIndex(frameId);
    this._scheduleSave();
  }

  bringToFront(frameId: string) {
    if (!this._frameOrders.has(frameId)) return;
    const pinned = this._pinnedFrames.has(frameId);
    const result = pinned
      ? zBringToFront(this._pinnedMaxZ, true)
      : zBringToFront(this._normalMaxZ, false);
    if (pinned) this._pinnedMaxZ = result.counter;
    else this._normalMaxZ = result.counter;
    this._frameZIndices.set(frameId, result.zIndex);
    this._focusedFrameId = frameId;
    this._applyZIndex(frameId);
    if (result.needsCompaction) this._compactZOrder();
    this._updateRendererTiers();
  }

  private _compactZOrder() {
    const frames = [...this._frameZIndices.entries()].map(([id, zIndex]) => ({
      id, zIndex, pinned: this._pinnedFrames.has(id),
    }));
    const { updates, normalMax, pinnedMax } = compactFrames(frames);
    this._normalMaxZ = normalMax;
    this._pinnedMaxZ = pinnedMax;
    for (const u of updates) {
      this._frameZIndices.set(u.id, u.zIndex);
      this._applyZIndex(u.id);
    }
  }

  private _applyZIndex(frameId: string) {
    const group = this._frameGroups.get(frameId);
    if (!group) return;
    const el = group.element ?? group.header?.element;
    if (!el) return;
    const container = el.closest('.dv-resize-container') as HTMLElement | null;
    if (container) {
      container.style.zIndex = String(this._frameZIndices.get(frameId) ?? 1);
    }
  }

  isTerminalOpen(terminalName: string): boolean {
    return this._activeTerminals.has(terminalName);
  }

  _showTabFlyout(terminalName: string, anchorEl: HTMLElement) {
    this._hideTabFlyoutImmediate();
    const flyout = document.createElement('trellis-tab-flyout') as any;
    flyout.terminalName = terminalName;
    flyout.repoName = terminalName.replace(/^(repo-|slot-)/, '');

    const hostRect = this.getBoundingClientRect();
    const tabRect = anchorEl.getBoundingClientRect();
    flyout.style.left = `${tabRect.left - hostRect.left}px`;
    flyout.style.top = `${tabRect.bottom - hostRect.top + 4}px`;
    flyout.style.pointerEvents = 'auto';

    flyout.addEventListener('mouseenter', () => {
      if (this._flyoutHideTimer) {
        clearTimeout(this._flyoutHideTimer);
        this._flyoutHideTimer = null;
      }
    });
    flyout.addEventListener('mouseleave', () => {
      this._hideTabFlyout();
    });

    this._flyoutEl = flyout;
    this.shadowRoot!.appendChild(flyout);
    this._populateFlyout(terminalName, flyout);
  }

  _hideTabFlyout() {
    if (this._flyoutHideTimer) {
      clearTimeout(this._flyoutHideTimer);
      this._flyoutHideTimer = null;
    }
    this._flyoutHideTimer = setTimeout(() => {
      this._hideTabFlyoutImmediate();
      this._flyoutHideTimer = null;
    }, 100);
  }

  private _hideTabFlyoutImmediate() {
    if (this._flyoutEl) {
      this._flyoutEl.remove();
      this._flyoutEl = null;
    }
  }

  private async _populateFlyout(terminalName: string, flyout: any): Promise<void> {
    const isSlot = terminalName.startsWith('slot-');
    const repoName = isSlot ? undefined : terminalName.replace(/^repo-/, '');

    if (isSlot) {
      flyout.slot = terminalName.replace(/^slot-/, '');
    }

    if (repoName && this.workspaceRoot) {
      try {
        const resp = await fetch(
          `/api/workspace/repo?root=${encodeURIComponent(this.workspaceRoot)}&repo=${encodeURIComponent(repoName)}`,
        );
        if (resp.ok) {
          const repo = await resp.json();
          flyout.repoName = repo.name || repoName;
          flyout.branch = repo.branch || '';
          flyout.path = repo.path || '';
        }
      } catch { /* non-critical */ }
    }

    const cached = this._agentStates.get(terminalName);
    if (cached) {
      flyout.agentState = cached.status || '';
      flyout.memoryMb = cached.memoryMb || 0;
      flyout.agentUptimeMs = cached.uptimeMs || 0;
    }

    try {
      const resp = await fetch(`/api/terminals/${terminalName}`);
      if (resp.ok) {
        const data = await resp.json();
        if (data.issue) flyout.issue = data.issue;
        if (!cached && data.agent) {
          flyout.agentState = data.agent.status || '';
          flyout.memoryMb = data.agent.memoryMb || 0;
          flyout.agentUptimeMs = data.agent.uptimeMs || 0;
        }
      }
    } catch { /* non-critical */ }

    const termEl = this._terminalElements.get(terminalName) as any;
    if (termEl?.terminal?.buffer?.active) {
      const buf = termEl.terminal.buffer.active;
      const lines: string[] = [];
      const start = Math.max(0, buf.cursorY - 2);
      for (let i = start; i <= buf.cursorY; i++) {
        const line = buf.getLine(i);
        if (line) {
          const text = line.translateToString(true).trim();
          if (text) lines.push(text);
        }
      }
      if (lines.length > 0) {
        flyout.lastOutput = lines.map((l: string) => `> ${l}`).join('\n');
      }
    }
  }

  _handleAgentStateEvent(data: any) {
    if (!data.terminal) return;
    this._agentStates.set(data.terminal, {
      status: data.status,
      memoryMb: data.memoryMb,
      uptimeMs: data.uptimeMs,
    });
  }

  private _setupAgentSSE() {
    try {
      this._sseSource = new EventSource('/api/push');
      this._sseSource.addEventListener('agent:state', (event) => {
        try {
          const data = JSON.parse((event as MessageEvent).data);
          this._handleAgentStateEvent(data);
        } catch { /* malformed event */ }
      });
    } catch { /* SSE not available */ }
  }

  async _updateRendererTiers() {
    const newTiers = computeAllTiers(this._frameTabs, this._frameActiveTab, this._focusedFrameId);
    const transitions = computeTransitions(this._rendererTiers, newTiers);

    for (const t of transitions) {
      if (t.from === 'webgl') {
        await this._releaseWebgl(t.terminalName);
      }
      if (t.to === 'webgl') {
        const granted = await this._acquireWebgl(t.terminalName);
        if (!granted) {
          newTiers.set(t.terminalName, 'canvas');
        }
      }
      this._applyRendererTier(t.terminalName, newTiers.get(t.terminalName)!);
    }

    this._rendererTiers = newTiers;
  }

  private async _acquireWebgl(terminalName: string): Promise<boolean> {
    const trellis = (window as any).trellis;
    if (!trellis?.webglAcquire) return true;
    try {
      const result = await trellis.webglAcquire(terminalName);
      return result?.granted ?? false;
    } catch { return false; }
  }

  private async _releaseWebgl(terminalName: string): Promise<void> {
    const trellis = (window as any).trellis;
    if (!trellis?.webglRelease) return;
    try { await trellis.webglRelease(terminalName); } catch { /* non-critical */ }
  }

  private _applyRendererTier(terminalName: string, tier: RendererTier) {
    const termEl = this._terminalElements.get(terminalName) as any;
    if (!termEl?.terminal) return;

    const existing = this._rendererAddons.get(terminalName);
    if (existing) {
      try { existing.dispose(); } catch { /* already disposed */ }
      this._rendererAddons.delete(terminalName);
    }

    const terminal = termEl.terminal;
    try {
      if (tier === 'webgl') {
        const { WebglAddon } = require('@xterm/addon-webgl');
        const addon = new WebglAddon();
        terminal.loadAddon(addon);
        this._rendererAddons.set(terminalName, addon);
      } else if (tier === 'canvas') {
        const { CanvasAddon } = require('@xterm/addon-canvas');
        const addon = new CanvasAddon();
        terminal.loadAddon(addon);
        this._rendererAddons.set(terminalName, addon);
      }
    } catch { /* addon load failure — fall back to default renderer */ }

    const el = termEl as HTMLElement;
    if (tier === 'none') {
      el.style.visibility = 'hidden';
    } else {
      el.style.visibility = '';
    }
  }

  private _setupWebglIPC() {
    const trellis = (window as any).trellis;
    if (!trellis) return;

    if (trellis.onWebglGrant) {
      trellis.onWebglGrant((_event: any, terminalName: string) => {
        this._rendererTiers.set(terminalName, 'webgl');
        this._applyRendererTier(terminalName, 'webgl');
      });
    }

    if (trellis.onWebglDemote) {
      trellis.onWebglDemote((_event: any, terminalName: string) => {
        this._rendererTiers.set(terminalName, 'canvas');
        this._applyRendererTier(terminalName, 'canvas');
        this._releaseWebgl(terminalName);
      });
    }
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

    const layout = this._serializeLayout();
    const trellis = (window as any).trellis;
    if (trellis?.saveWindowLayout) {
      trellis.saveWindowLayout(layout);
    } else if (this._browserMode && this.workspaceRoot) {
      fetch(`/api/workspace/layout?root=${encodeURIComponent(this.workspaceRoot)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ windows: [layout] }),
      }).catch(() => {});
    }
  }

  private _serializeLayout(): ShellLayout {
    const rawFrames = [...this._frameOrders.entries()].map(([frameId, order]) => ({
      id: frameId,
      zIndex: this._frameZIndices.get(frameId) ?? 1,
      pinned: this._pinnedFrames.has(frameId),
      order,
    }));

    const normalized = normalizeForSave(rawFrames);
    const zMap = new Map(normalized.map(n => [n.id, n.zIndex]));

    const frames: FrameLayout[] = rawFrames.map(rf => ({
      id: rf.id,
      groupId: this._frameGroupIds.get(rf.id),
      order: rf.order,
      position: this._framePositions.get(rf.id) ?? { x: 0, y: 0 },
      size: { width: 600, height: 400 },
      zIndex: zMap.get(rf.id) ?? 1,
      pinned: rf.pinned,
      tabs: this._frameTabs.get(rf.id) ?? [],
      activeTabIndex: this._frameActiveTab.get(rf.id) ?? 0,
    }));

    return {
      id: `shell-${Date.now()}`,
      bounds: { x: 0, y: 0, width: window.innerWidth, height: window.innerHeight },
      isMain: true,
      frames,
      lastActiveFrameId: this._focusedFrameId ?? undefined,
    };
  }

  private async _restoreLayout() {
    let persisted: any = null;
    const trellis = (window as any).trellis;
    if (trellis?.getLastWorkspacePath) {
      const workspacePath = await trellis.getLastWorkspacePath();
      if (!workspacePath) return;
      persisted = await trellis.loadLayout(workspacePath);
    } else if (this.workspaceRoot) {
      try {
        const resp = await fetch('/api/workspace/layout?root=' + encodeURIComponent(this.workspaceRoot));
        if (resp.ok) persisted = await resp.json();
      } catch { /* non-critical */ }
    }
    if (!persisted?.windows) return;

    const shell = persisted.windows.find((w: ShellLayout) => w.isMain) || persisted.windows[0];
    if (!shell?.frames) return;

    for (const frame of shell.frames.sort((a: FrameLayout, b: FrameLayout) => a.order - b.order)) {
      if (frame.tabs && frame.tabs.length > 0) {
        this.createFrame(frame.tabs, frame.groupId, undefined, frame);
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

  async loadGroups(): Promise<Group[]> {
    if (!this.workspaceRoot) return [];
    const trellis = (window as any).trellis;
    if (trellis?.loadGroups) {
      const data = await trellis.loadGroups(this.workspaceRoot);
      return data?.groups || [];
    }
    try {
      const resp = await fetch('/api/workspace/groups?root=' + encodeURIComponent(this.workspaceRoot));
      if (resp.ok) { const data = await resp.json(); return data?.groups || []; }
    } catch { /* non-critical */ }
    return [];
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
