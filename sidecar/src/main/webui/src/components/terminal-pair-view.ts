import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import './agent-status-badge';

export interface PairEntry {
  name: string;
  sessionName: string;
  agentState?: string;
  memoryMb?: number;
  lastError?: string | null;
}

@customElement('trellis-terminal-pair-view')
export class TrellisTerminalPairView extends LitElement {

  @property({ type: Object }) primary: PairEntry | null = null;
  @property({ type: Object }) secondary: PairEntry | null = null;
  @property() mode: 'split' | 'tab' = 'split';

  @state() private _activeTab = 0;
  private _cssInjected = false;
  private _configuredPrimary = '';
  private _configuredSecondary = '';

  override connectedCallback() {
    super.connectedCallback();
    if (!this._cssInjected) {
      const link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = 'https://cdn.jsdelivr.net/npm/@xterm/xterm@6.0.0/css/xterm.min.css';
      this.renderRoot.prepend(link);
      this._cssInjected = true;
    }
  }

  static override styles = css`
    :host { display: flex; flex-direction: column; height: 100%; }

    .toolbar {
      display: flex; align-items: center; gap: 0.5rem; padding: 0.3rem 0.75rem;
      background: #1a1a1a; border-bottom: 1px solid #333; flex-shrink: 0;
    }
    .toolbar .spacer { flex: 1; }

    .mode-toggle {
      padding: 0.2rem 0.5rem; border: 1px solid #444; border-radius: 3px;
      background: #2a2a2a; color: #999; cursor: pointer; font-size: 0.7rem;
      transition: background 0.15s;
    }
    .mode-toggle:hover { background: #333; color: #ccc; }

    .split-container {
      flex: 1; display: flex; flex-direction: row; min-height: 0;
    }

    .split-pane {
      flex: 1; display: flex; flex-direction: column; min-width: 0; overflow: hidden;
    }
    .split-pane + .split-pane { border-left: 1px solid #333; }

    .pane-label {
      display: flex; align-items: center; gap: 0.4rem;
      padding: 0.25rem 0.75rem; background: #1a1a1a; border-bottom: 1px solid #333;
      font-size: 0.75rem; color: #888; flex-shrink: 0;
    }

    .pane-terminal { flex: 1; min-height: 0; overflow: hidden; display: flex; }
    .pane-terminal pages-component-terminal { flex: 1; overflow: hidden; }

    .tab-bar {
      display: flex; gap: 0; background: #1a1a1a; border-bottom: 1px solid #333;
      flex-shrink: 0; overflow-x: auto;
    }

    .tab {
      padding: 0.4rem 1rem; cursor: pointer; font-size: 0.8rem;
      color: #888; border: none; background: none; border-bottom: 2px solid transparent;
      white-space: nowrap; transition: color 0.15s, border-color 0.15s;
    }
    .tab:hover { color: #ccc; }
    .tab.active { color: #eee; border-bottom-color: #3b82f6; }

    .tab-terminal { flex: 1; min-height: 0; overflow: hidden; display: flex; }
    .tab-terminal pages-component-terminal { flex: 1; overflow: hidden; }
  `;

  override updated(changed: Map<PropertyKey, unknown>) {
    this.updateComplete.then(() => {
      this._configureTerminal('primary-terminal', this.primary);
      if (this.secondary) this._configureTerminal('secondary-terminal', this.secondary);
    });
  }

  private _configureTerminal(id: string, entry: PairEntry | null) {
    if (!entry) return;
    const currentKey = `${entry.sessionName}:${this.mode}:${this._activeTab}`;
    const configured = id === 'primary-terminal' ? this._configuredPrimary : this._configuredSecondary;
    if (configured === currentKey) return;
    if (id === 'primary-terminal') this._configuredPrimary = currentKey;
    else this._configuredSecondary = currentKey;
    const el = this.renderRoot.querySelector(`#${id}`) as any;
    if (!el) return;
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    el.configure({
      wsUrl: `${proto}//${location.host}/ws/terminal/${entry.sessionName}/{cols}/{rows}`,
      theme: { background: '#1e1e1e', foreground: '#cccccc', cursor: '#aeafad' },
      fontSize: 13,
      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
    });
  }

  private _handleTerminalEvent(e: CustomEvent, entry: PairEntry) {
    const { topic, payload } = e.detail;
    if (topic === 'terminal-resize') {
      fetch(`/api/terminals/${entry.sessionName}/resize`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cols: payload.cols, rows: payload.rows }),
      }).catch(() => {});
    }
  }

  private _toggleMode() {
    this.mode = this.mode === 'split' ? 'tab' : 'split';
  }

  override render() {
    if (!this.primary) return nothing;
    const hasPair = !!this.secondary;

    if (!hasPair) {
      return html`
        <div class="pane-terminal">
          <pages-component-terminal id="primary-terminal"
            @pages-event=${(e: CustomEvent) => this._handleTerminalEvent(e, this.primary!)}
          ></pages-component-terminal>
        </div>
      `;
    }

    return html`
      <div class="toolbar">
        <span class="spacer"></span>
        <button class="mode-toggle" @click=${this._toggleMode}>
          ${this.mode === 'split' ? '⊞ tabs' : '⊟ split'}
        </button>
      </div>
      ${this.mode === 'split' ? this._renderSplit() : this._renderTabs()}
    `;
  }

  private _renderSplit() {
    return html`
      <div class="split-container">
        <div class="split-pane">
          <div class="pane-label">${this.primary!.name}</div>
          <div class="pane-terminal">
            <pages-component-terminal id="primary-terminal"
              @pages-event=${(e: CustomEvent) => this._handleTerminalEvent(e, this.primary!)}
            ></pages-component-terminal>
          </div>
        </div>
        <div class="split-pane">
          <div class="pane-label">
            ${this.secondary!.name}
            ${this.secondary!.agentState ? html`
              <agent-status-badge
                .state=${this.secondary!.agentState}
                .memoryMb=${this.secondary!.memoryMb ?? 0}
                .lastError=${this.secondary!.lastError ?? null}
              ></agent-status-badge>
            ` : nothing}
          </div>
          <div class="pane-terminal">
            <pages-component-terminal id="secondary-terminal"
              @pages-event=${(e: CustomEvent) => this._handleTerminalEvent(e, this.secondary!)}
            ></pages-component-terminal>
          </div>
        </div>
      </div>
    `;
  }

  private _renderTabs() {
    const entries = [this.primary!, this.secondary!];
    const active = entries[this._activeTab];
    return html`
      <div class="tab-bar">
        ${entries.map((e, i) => html`
          <button
            class="tab ${i === this._activeTab ? 'active' : ''}"
            @click=${() => { this._activeTab = i; }}
          >
            ${e.name}
            ${e.agentState ? html`
              <agent-status-badge
                .state=${e.agentState}
                .memoryMb=${e.memoryMb ?? 0}
                .lastError=${e.lastError ?? null}
              ></agent-status-badge>
            ` : nothing}
          </button>
        `)}
      </div>
      <div class="tab-terminal">
        <pages-component-terminal id="${this._activeTab === 0 ? 'primary' : 'secondary'}-terminal"
          @pages-event=${(e: CustomEvent) => this._handleTerminalEvent(e, active)}
        ></pages-component-terminal>
      </div>
    `;
  }
}
