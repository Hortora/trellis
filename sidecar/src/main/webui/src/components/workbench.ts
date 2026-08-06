import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import '../views/org-dashboard';
import '../views/slot-detail';
import '../views/epic-dashboard';
import '../views/garden-view';
import '../views/artifact-panel';
import '../views/repo-detail';
import '../components/coordinator-panel';
import '../components/workspace-view';
import '../views/protocol-view';

interface PanelDef {
  icon: string;
  label: string;
  tag: string;
}

const PANELS: Record<string, PanelDef> = {
  workspace:   { icon: '\u{2B1A}', label: 'Workspace',   tag: 'trellis-workspace-view' },
  dashboard:   { icon: '\u{1F4C1}', label: 'Dashboard',   tag: 'trellis-org-dashboard' },
  slot:        { icon: '\u{1F4CB}', label: 'Slot',         tag: 'trellis-slot-detail' },
  artifacts:   { icon: '\u{1F4C4}', label: 'Artifacts',    tag: 'trellis-artifact-panel' },
  garden:      { icon: '\u{1F33F}', label: 'Garden',       tag: 'trellis-garden-view' },
  protocols:   { icon: '\u{1F4DC}', label: 'Protocols',    tag: 'trellis-protocol-view' },
  coordinator: { icon: '\u{1F916}', label: 'Coordinator',  tag: 'trellis-coordinator-panel' },
  memory:      { icon: '\u{1F4CA}', label: 'Memory',       tag: 'trellis-memory-panel' },
  epic:        { icon: '⚡',    label: 'Epic',          tag: 'trellis-epic-dashboard' },
  repo:        { icon: '\u{1F4E6}', label: 'Repo',         tag: 'trellis-repo-detail' },
};

const DOCK_PANELS = ['workspace', 'dashboard', 'artifacts', 'garden', 'protocols', 'coordinator', 'memory'];

@customElement('trellis-workbench')
export class TrellisWorkbench extends LitElement {

  static override shadowRootOptions = { ...LitElement.shadowRootOptions, delegatesFocus: true };

  @property() workspaceRoot = '';

  @state() private _activePanel = 'workspace';
  @state() private _panelContext: Record<string, string> = {};

  private _panelCache = new Map<string, HTMLElement>();
  private _lastRoot = '';
  private _lastHash = new Map<string, string>();
  private _pushDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private _pushMaxWaitTimer: ReturnType<typeof setTimeout> | null = null;
  private _heartbeatInterval: ReturnType<typeof setInterval> | null = null;
  private _eventSource: EventSource | null = null;
  private _pendingCorrelationId: string | null = null;

  static override styles = css`
    :host {
      display: flex;
      height: 100%;
      font-family: system-ui, -apple-system, sans-serif;
    }

    .dock-bar {
      display: flex;
      flex-direction: column;
      width: 48px;
      background: #141414;
      border-right: 1px solid #333;
      padding: 4px 0;
      flex-shrink: 0;
    }

    .dock-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 48px;
      height: 44px;
      border: none;
      background: transparent;
      cursor: pointer;
      font-size: 18px;
      border-left: 2px solid transparent;
      transition: background 0.15s;
    }

    .dock-btn:hover { background: #222; }
    .dock-btn[data-active] {
      background: #1e1e1e;
      border-left-color: #3b82f6;
    }

    .panel-area {
      flex: 1;
      overflow: hidden;
      background: #1e1e1e;
    }

    .panel-area > * {
      width: 100%;
      height: 100%;
      box-sizing: border-box;
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
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('workspaceRoot') && this._lastRoot && this._lastRoot !== this.workspaceRoot) {
      this._panelCache.forEach(el => el.remove());
      this._panelCache.clear();
    }
    this._lastRoot = this.workspaceRoot;
  }

  private _onHashChange = () => { this._parseHash(); };

  private _parseHash() {
    const hash = location.hash;
    const ctx: Record<string, string> = {};

    const rootMatch = hash.match(/[?&]root=([^&]+)/);
    if (rootMatch) {
      this.workspaceRoot = decodeURIComponent(rootMatch[1]);
    }

    if (hash.match(/^#slot\/(\d+)/)) {
      const m = hash.match(/^#slot\/(\d+)/)!;
      this._activePanel = 'slot';
      ctx['slotNumber'] = m[1];
    } else if (hash.match(/^#epic\/([^/]+)\/([^/]+)\/(\d+)/)) {
      const m = hash.match(/^#epic\/([^/]+)\/([^/]+)\/(\d+)/)!;
      this._activePanel = 'epic';
      ctx['owner'] = m[1];
      ctx['repo'] = m[2];
      ctx['epicNumber'] = m[3];
    } else if (hash.match(/^#repo\/([^?]+)/)) {
      const m = hash.match(/^#repo\/([^?]+)/)!;
      this._activePanel = 'repo';
      ctx['repoName'] = decodeURIComponent(m[1]);
    } else if (hash.match(/^#coordinator/)) {
      this._activePanel = 'coordinator';
      const epicParam = hash.match(/[?&]epic=([^&]+)/);
      if (epicParam) ctx['epicRef'] = decodeURIComponent(epicParam[1]);
    } else if (hash.match(/^#artifacts/)) {
      this._activePanel = 'artifacts';
    } else if (hash.match(/^#garden/)) {
      this._activePanel = 'garden';
    } else if (hash.match(/^#protocols/)) {
      this._activePanel = 'protocols';
    } else if (hash.match(/^#memory/)) {
      this._activePanel = 'memory';
    } else if (hash.match(/^#workspace/)) {
      this._activePanel = 'workspace';
    } else {
      this._activePanel = 'dashboard';
    }

    this._panelContext = ctx;
    this._lastHash.set(this._activePanel, hash);
    if (this._activePanel === 'slot' || this._activePanel === 'epic' || this._activePanel === 'repo') {
      this._lastHash.set('dashboard', hash);
    }
  }

  private _activatePanel(id: string) {
    const saved = this._lastHash.get(id);
    if (saved) {
      location.hash = saved;
    } else {
      const root = this.workspaceRoot ? `root=${encodeURIComponent(this.workspaceRoot)}` : '';
      if (id === 'dashboard') {
        location.hash = `#?${root}`;
      } else {
        location.hash = `#${id}?${root}`;
      }
    }
    this._pushUIStateImmediate();
  }

  private _getOrCreatePanel(id: string): HTMLElement | null {
    const def = PANELS[id];
    if (!def) return null;

    let el = this._panelCache.get(id);
    if (!el) {
      el = document.createElement(def.tag);
      this._panelCache.set(id, el);
    }
    this._applyContext(el, id);
    return el;
  }

  private _applyContext(el: HTMLElement, panelId: string) {
    const ctx = this._panelContext;
    (el as any).workspaceRoot = this.workspaceRoot;
    if (panelId === 'slot' && ctx['slotNumber']) {
      (el as any).slotNumber = parseInt(ctx['slotNumber']);
    }
    if (panelId === 'epic') {
      (el as any).owner = ctx['owner'] ?? '';
      (el as any).repo = ctx['repo'] ?? '';
      (el as any).epicNumber = parseInt(ctx['epicNumber'] ?? '0');
    }
    if (panelId === 'coordinator' && ctx['epicRef']) {
      (el as any).epicRef = ctx['epicRef'];
    }
    if (panelId === 'repo' && ctx['repoName']) {
      (el as any).repoName = ctx['repoName'];
    }
  }

  private _buildUIState(): Record<string, unknown> {
    const panels: Record<string, unknown> = {};
    for (const [id, el] of this._panelCache) {
      panels[id] = {
        visible: id === this._activePanel,
        content: typeof (el as any).getUIState === 'function' ? (el as any).getUIState() : {},
        lastPushed: Date.now(),
      };
    }
    const state: Record<string, unknown> = { activePanel: this._activePanel, panels };
    if (this._pendingCorrelationId) {
      state['correlationId'] = this._pendingCorrelationId;
      this._pendingCorrelationId = null;
    }
    return state;
  }

  private _pushUIStateImmediate() {
    if (this._pushDebounceTimer) clearTimeout(this._pushDebounceTimer);
    if (this._pushMaxWaitTimer) clearTimeout(this._pushMaxWaitTimer);
    this._pushDebounceTimer = null;
    this._pushMaxWaitTimer = null;
    this._doPushUIState();
  }

  private _doPushUIState() {
    const state = this._buildUIState();
    fetch('/api/model/ui-state', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(state),
    }).catch(() => {});
  }

  private _startHeartbeat() {
    this._heartbeatInterval = setInterval(() => this._doPushUIState(), 15000);
  }

  private _stopHeartbeat() {
    if (this._heartbeatInterval) {
      clearInterval(this._heartbeatInterval);
      this._heartbeatInterval = null;
    }
  }

  private _connectSSE() {
    this._eventSource = new EventSource('/api/push?topics=control:navigate');
    this._eventSource.addEventListener('message', (event: MessageEvent) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.topic === 'control:navigate' && msg.payload) {
          const payload = typeof msg.payload === 'string' ? JSON.parse(msg.payload) : msg.payload;
          this._handleNavigateEvent(payload);
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
      } else if (PANELS[panelId]) {
        this._activatePanel(panelId);
      }
    }

    this._pushUIStateImmediate();
  }

  override render() {
    this._getOrCreatePanel(this._activePanel);
    return html`
      <div class="dock-bar">
        ${DOCK_PANELS.map(id => { const def = PANELS[id]; const isActive = id === this._activePanel || (id === 'dashboard' && (this._activePanel === 'slot' || this._activePanel === 'epic' || this._activePanel === 'repo')); return html`
          <button class="dock-btn"
                  title=${def.label}
                  ?data-active=${isActive}
                  @click=${() => this._activatePanel(id)}>
            ${def.icon}
          </button>
        `;})}
      </div>
      <div class="panel-area">
        ${[...this._panelCache.entries()].map(([id, el]) =>
          html`<div style="display:${id === this._activePanel ? 'contents' : 'none'}">${el}</div>`
        )}
      </div>
    `;
  }
}
