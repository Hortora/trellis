import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { dockWorkbench } from '@casehubio/pages-ui/dist/dsl/builders.js';
import { renderComponent } from '@casehubio/pages-component';
import type { LayoutState } from '@casehubio/pages-component';
import { createZoneLayoutEngine } from '@casehubio/pages-runtime';
import type { ZoneLayoutEngine } from '@casehubio/pages-runtime';
import { attachDockDrag } from '@casehubio/pages-runtime/dist/dock-drag.js';
import { createContainer, createContainerToolbar } from '@casehubio/pages-runtime/dist/frame-sandbox';
import type { Container, ContainerToolbar, Layout } from '@casehubio/pages-runtime/dist/frame-sandbox';
import { DOCK_PANELS, PANEL_TAGS, registerAllPanels, createPanelFactory } from './workbench-panels.js';

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

  static override styles = css`
    :host {
      display: block;
      height: 100%;
      width: 100%;
    }
    .workbench-root {
      height: 100%;
      width: 100%;
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

    renderComponent(config, root as HTMLElement);

    const siteRoot = root as HTMLElement;
    const buttons = siteRoot.querySelectorAll<HTMLElement>('button[data-dock-panel-id]');
    for (const btn of buttons) {
      attachDockDrag(btn, this._engine, siteRoot);
    }

    siteRoot.addEventListener('pages-dock-rearrange', ((e: CustomEvent) => {
      const { panelKey, toZone, insertIndex } = e.detail;
      this._engine?.movePanel(panelKey, toZone, insertIndex);
      this._scheduleSave();
    }) as EventListener);

    const centreMount = root.querySelector('#__dock-centre');
    if (!centreMount) return;

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

    const panelMatch = hash.match(/^#([a-z]+)/);
    if (panelMatch && PANEL_TAGS[panelMatch[1]]) {
      this._activatePanel(panelMatch[1]);
    } else if (!hash || hash === '#') {
      this._activatePanel('workspace');
    }
  }

  private _activatePanel(key: string) {
    if (PANEL_TAGS[key]) {
      const root = this.shadowRoot!.querySelector('.workbench-root');
      const target = root?.querySelector(`[data-component-id="${key}"]`) ?? root;
      target?.dispatchEvent(new CustomEvent('pages-dock-toggle', {
        bubbles: true, composed: true,
        detail: { panelId: key, visible: true },
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
    return html`<div class="workbench-root"></div>`;
  }
}
