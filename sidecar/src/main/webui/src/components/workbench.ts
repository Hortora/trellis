import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { dockWorkbench } from '@casehubio/pages-ui/dist/dsl/builders.js';
import { renderComponent } from '@casehubio/pages-component';
import type { LayoutState } from '@casehubio/pages-component';
import { createZoneLayoutEngine } from '@casehubio/pages-runtime';
import type { ZoneLayoutEngine } from '@casehubio/pages-runtime';
import { attachDockDrag } from '@casehubio/pages-runtime/dist/dock-drag.js';
import { renderDockBar } from '@casehubio/pages-runtime/dist/dock-bar-renderer.js';
import { createContainer, createContainerToolbar } from '@casehubio/pages-runtime/dist/frame-sandbox';
import type { Container, ContainerToolbar, Layout } from '@casehubio/pages-runtime/dist/frame-sandbox';
import { DOCK_PANELS, PANEL_TAGS, registerAllPanels, createPanelFactory } from './workbench-panels.js';
import './space-switcher.js';

registerAllPanels();

const ALLOWED_LAYOUTS: readonly Layout[] = ['content', 'tabbed', 'splith', 'splitv'];
const LAYOUT_STORE_KEY = 'workbench';

@customElement('trellis-workbench')
export class TrellisWorkbench extends LitElement {

  static override shadowRootOptions = { ...LitElement.shadowRootOptions, delegatesFocus: true };

  @property() workspaceRoot = '';

  private _container: Container | null = null;
  private _toolbar: ContainerToolbar | null = null;
  private _engine: ZoneLayoutEngine | null = null;
  private _rendered = false;
  private _lastRoot = '';
  private _heartbeatInterval: ReturnType<typeof setInterval> | null = null;
  private _eventSource: EventSource | null = null;
  private _pendingCorrelationId: string | null = null;
  private _saveDebounce: ReturnType<typeof setTimeout> | null = null;
  private _panelCache = new Map<string, HTMLElement>();
  private _activePanel: string | null = null;

  static override styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      width: 100%;
    }
    .space-bar {
      display: flex;
      align-items: center;
      height: 36px;
      background: #181818;
      border-bottom: 1px solid #333;
      flex-shrink: 0;
      padding-left: 4px;
      overflow: visible;
      position: relative;
      z-index: 50;
    }
    .workbench-root {
      flex: 1;
      width: 100%;
      min-height: 0;
      overflow: hidden;
    }
  `;

  override connectedCallback() {
    super.connectedCallback();
    window.addEventListener('hashchange', this._onHashChange);
    this._parseHash();
    this._startHeartbeat();
    this._connectSSE();
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    window.removeEventListener('hashchange', this._onHashChange);
    this._stopHeartbeat();
    this._disconnectSSE();
    this._container?.dispose();
    this._container = null;
    this._toolbar?.dispose();
    this._toolbar = null;
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('workspaceRoot') && this._lastRoot !== this.workspaceRoot) {
      this._lastRoot = this.workspaceRoot;
      this._rendered = false;
      this._container?.dispose();
      this._container = null;
      this._toolbar?.dispose();
      this._toolbar = null;
      this._panelCache.clear();
      this._activePanel = null;
    }
    if (!this._rendered && this.workspaceRoot) {
      this._initWorkbench();
      this._rendered = true;
    }
  }

  private async _initWorkbench() {
    const root = this.shadowRoot!.querySelector('.workbench-root');
    if (!root) return;
    root.innerHTML = '';

    const savedState = await this._loadLayout();
    const factory = createPanelFactory(this.workspaceRoot);

    const dockConfig = {
      centre: { type: 'html' as const, props: { id: '__dock-centre' } },
      left: DOCK_PANELS,
      storageKey: LAYOUT_STORE_KEY,
    };
    const config = dockWorkbench(dockConfig);
    this._engine = createZoneLayoutEngine(dockConfig, savedState?.zones);

    renderComponent(root as HTMLElement, config);

    const siteRoot = root as HTMLElement;
    const dockBars = siteRoot.querySelectorAll<HTMLElement>('[data-component-type="dock-bar"]');
    for (const bar of dockBars) {
      const propsStr = bar.dataset.componentProps;
      if (propsStr) {
        renderDockBar(bar, JSON.parse(propsStr), { zoneEngine: this._engine!, siteTarget: siteRoot });
      }
    }

    const buttons = siteRoot.querySelectorAll<HTMLElement>('button[data-dock-panel-id]');
    for (const btn of buttons) {
      attachDockDrag(btn, this._engine, siteRoot);
    }

    const hideSideZone = () => {
      const sideZone = siteRoot.querySelector('[data-component-id="__zone:left-top"]') as HTMLElement | null;
      if (sideZone) {
        const sideSlot = sideZone.closest('[data-slot]') as HTMLElement | null;
        if (sideSlot) sideSlot.style.display = 'none';
        const handle = siteRoot.querySelector('[data-split-handle]') as HTMLElement | null;
        if (handle) handle.style.display = 'none';
      }
    };
    hideSideZone();

    siteRoot.addEventListener('pages-dock-toggle', ((e: CustomEvent) => {
      const { panelId, visible, extraProps } = e.detail;
      const centre = siteRoot.querySelector('[data-component-id="__dock-centre"]') as HTMLElement | null;
      if (!centre) return;

      if (visible) {
        const tag = PANEL_TAGS[panelId];
        if (tag) {
          centre.querySelectorAll('[data-dock-panel-content]').forEach(el => {
            const key = el.getAttribute('data-dock-panel-content');
            if (key) this._panelCache.set(key, el as HTMLElement);
            el.remove();
          });
          this._activePanel = panelId;
          const cacheKey = extraProps ? `${panelId}:${JSON.stringify(extraProps)}` : panelId;
          let el = extraProps ? null : this._panelCache.get(panelId);
          if (!el) {
            el = document.createElement(tag);
            (el as any).workspaceRoot = this.workspaceRoot;
            el.style.height = '100%';
            el.style.width = '100%';
            el.setAttribute('data-dock-panel-content', panelId);
            if (extraProps) {
              for (const [k, v] of Object.entries(extraProps)) {
                (el as any)[k] = v;
              }
            }
            this._panelCache.set(cacheKey, el);
          }
          centre.appendChild(el);
          hideSideZone();
        }
      } else {
        const existing = centre.querySelector(`[data-dock-panel-content="${panelId}"]`);
        if (existing) {
          this._panelCache.set(panelId, existing as HTMLElement);
          existing.remove();
        }
        this._activePanel = null;
        hideSideZone();
      }
      this._scheduleSave();
    }) as EventListener);

    siteRoot.addEventListener('pages-dock-rearrange', ((e: CustomEvent) => {
      const { panelKey, toZone, insertIndex } = e.detail;
      this._engine?.movePanel(panelKey, toZone, insertIndex);
      this._scheduleSave();
    }) as EventListener);

    const centreMount = root.querySelector('[data-component-id="__dock-centre"]') as HTMLElement | null;
    if (!centreMount) return;
    centreMount.style.overflow = 'hidden';
    centreMount.style.minHeight = '0';
    let ancestor = centreMount.parentElement;
    while (ancestor && ancestor !== (root as HTMLElement)) {
      ancestor.style.minHeight = '0';
      ancestor.style.overflow = 'hidden';
      ancestor = ancestor.parentElement;
    }

    const activeLayout = (savedState?.containerState?.layout as Layout) ?? 'content';

    this._toolbar = createContainerToolbar(ALLOWED_LAYOUTS, activeLayout, {
      onAdd: () => {},
      onLayoutChange: (type: Layout) => {
        this._container?.setLayout(type);
        this._scheduleSave();
        this._pushUIStateImmediate();
      },
    });
    centreMount.insertAdjacentElement('afterbegin', this._toolbar.element);

    this._container = createContainer({
      entries: [{ key: 'workspace', label: 'Workspace' }],
      layout: activeLayout,
      contentFactory: factory,
      callbacks: {
        onStateChange: () => {
          this._scheduleSave();
          this._pushUIStateImmediate();
        },
      },
    });
    this._container.mount(centreMount as HTMLElement);

    this._routeFromHash();
  }

  private _routeFromHash() {
    const hash = location.hash;

    const slotMatch = hash.match(/^#slot\/(\d+)/);
    if (slotMatch) {
      this._activatePanel('slot', { slotNumber: parseInt(slotMatch[1], 10) });
      return;
    }

    const repoMatch = hash.match(/^#repo\/([^?]+)/);
    if (repoMatch) {
      this._activatePanel('repo', { repoName: decodeURIComponent(repoMatch[1]) });
      return;
    }

    const panelMatch = hash.match(/^#([a-z]+)/);
    if (panelMatch && PANEL_TAGS[panelMatch[1]]) {
      this._activatePanel(panelMatch[1]);
    } else {
      this._activatePanel('dashboard');
    }
  }

  private async _loadLayout(): Promise<LayoutState | null> {
    try {
      const resp = await fetch(`/api/layouts/${LAYOUT_STORE_KEY}?root=${encodeURIComponent(this.workspaceRoot)}`);
      if (!resp.ok) return null;
      return await resp.json();
    } catch { return null; }
  }

  private _scheduleSave() {
    if (this._saveDebounce) clearTimeout(this._saveDebounce);
    this._saveDebounce = setTimeout(() => this._saveLayout(), 500);
  }

  private _saveLayout() {
    const state: LayoutState = {
      splits: {},
      docks: {},
      panels: {},
      zones: this._engine ? Object.fromEntries(this._engine.zoneMap) : undefined,
      containerState: this._container ? { layout: this._container.organiser.type as Layout, tabs: [] } : undefined,
    };
    fetch(`/api/layouts/${LAYOUT_STORE_KEY}?root=${encodeURIComponent(this.workspaceRoot)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(state),
      keepalive: true,
    }).catch(() => {});
  }

  private _onHashChange = () => { this._parseHash(); };

  private _parseHash() {
    const hash = location.hash;

    const rootMatch = hash.match(/[?&]root=([^&]+)/);
    if (rootMatch) {
      this.workspaceRoot = decodeURIComponent(rootMatch[1]);
    }

    const slotMatch = hash.match(/^#slot\/(\d+)/);
    if (slotMatch) {
      this._activatePanel('slot', { slotNumber: parseInt(slotMatch[1], 10) });
      return;
    }

    const repoMatch = hash.match(/^#repo\/([^?]+)/);
    if (repoMatch) {
      this._activatePanel('repo', { repoName: decodeURIComponent(repoMatch[1]) });
      return;
    }

    const panelMatch = hash.match(/^#([a-z]+)/);
    if (panelMatch && PANEL_TAGS[panelMatch[1]]) {
      this._activatePanel(panelMatch[1]);
    } else if (!hash || hash === '#') {
      this._activatePanel('workspace');
    }
  }

  private _activatePanel(key: string, extraProps?: Record<string, unknown>) {
    if (PANEL_TAGS[key]) {
      const root = this.shadowRoot!.querySelector('.workbench-root');
      const target = root?.querySelector(`[data-component-id="${key}"]`) ?? root;
      target?.dispatchEvent(new CustomEvent('pages-dock-toggle', {
        bubbles: true, composed: true,
        detail: { panelId: key, visible: true, extraProps },
      }));
    }
    this._pushUIStateImmediate();
  }

  private _buildUIState(): Record<string, unknown> {
    const state: Record<string, unknown> = {
      layoutMode: this._container?.organiser?.type ?? 'content',
      visiblePanels: this._container?.entries.map(e => e.key) ?? [],
    };
    if (this._pendingCorrelationId) {
      state['correlationId'] = this._pendingCorrelationId;
      this._pendingCorrelationId = null;
    }
    return state;
  }

  private _pushUIStateImmediate() {
    const state = this._buildUIState();
    fetch('/api/model/ui-state', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(state),
    }).catch(() => {});
  }

  private _startHeartbeat() {
    this._heartbeatInterval = setInterval(() => this._pushUIStateImmediate(), 15000);
  }

  private _stopHeartbeat() {
    if (this._heartbeatInterval) {
      clearInterval(this._heartbeatInterval);
      this._heartbeatInterval = null;
    }
  }

  private _connectSSE() {
    this._eventSource = new EventSource('/api/push?topics=control:navigate&topics=control:workspace');
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

  private _disconnectSSE() {
    if (this._eventSource) {
      this._eventSource.close();
      this._eventSource = null;
    }
  }

  _handleNavigateEvent(payload: { target: string; correlationId?: string }) {
    const { target, correlationId } = payload;
    if (correlationId) this._pendingCorrelationId = correlationId;

    if (target.startsWith('dock-bar/')) {
      this._activatePanel(target.substring('dock-bar/'.length));
    } else if (target.startsWith('panels/')) {
      const parts = target.substring('panels/'.length).split('/');
      const panelId = parts[0] === 'workspace-view' ? 'workspace' : parts[0];
      this._activatePanel(panelId);
      if (panelId === 'workspace' && parts.length >= 3 && parts[1] === 'frames') {
        const wsEl = this.shadowRoot!.querySelector('trellis-workspace-view');
        if (wsEl && typeof (wsEl as any).focusFrame === 'function') {
          (wsEl as any).focusFrame(parts[2]);
          if (parts.length >= 5 && parts[3] === 'tabs') {
            (wsEl as any).focusTab(parts[2], parseInt(parts[4], 10));
          }
        }
      }
    }
    this._pushUIStateImmediate();
  }

  private async _handleWorkspaceCommand(
      payload: { command: string; params?: any; correlationId?: string }) {
    this._activatePanel('workspace');
    const wsView = this.shadowRoot!.querySelector('trellis-workspace-view');
    if (wsView && typeof (wsView as any).handleCommand === 'function') {
      await (wsView as any).handleCommand(payload.command, payload.params);
    }
    if (payload.correlationId) this._pendingCorrelationId = payload.correlationId;
    this._pushUIStateImmediate();
  }

  override render() {
    return html`
      <div class="space-bar">
        <trellis-space-switcher
          .root=${this.workspaceRoot}
          @space-change=${this._onSpaceChange}
        ></trellis-space-switcher>
      </div>
      <div class="workbench-root"></div>
    `;
  }

  private _onSpaceChange(e: CustomEvent) {
    const root = e.detail.root;
    this.workspaceRoot = root;
    location.hash = `#?root=${encodeURIComponent(root)}`;
  }
}
