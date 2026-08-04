import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import './agent-status-badge';

interface TabEntry {
  name: string;
  sessionName: string;
  agentState?: string;
  memoryMb?: number;
  lastError?: string | null;
}

@customElement('trellis-terminal-tab-group')
export class TrellisTerminalTabGroup extends LitElement {

  @property({ type: Array }) tabs: TabEntry[] = [];
  @state() private _activeIndex = 0;
  private _cssInjected = false;
  private _mousedownHandler: ((e: Event) => void) | null = null;

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

  override disconnectedCallback() {
    super.disconnectedCallback();
    if (this._mousedownHandler) {
      document.removeEventListener('mousedown', this._mousedownHandler);
      this._mousedownHandler = null;
    }
  }

  static override styles = css`
    :host { display: flex; flex-direction: column; height: 100%; }

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

    .terminal-area { flex: 1; min-height: 0; overflow: hidden; display: flex; }
    .terminal-area pages-component-terminal { flex: 1; overflow: hidden; }
    pages-component-terminal .xterm { height: 100%; }
    pages-component-terminal .xterm-viewport { overflow: hidden !important; }
    .empty { color: #666; padding: 2rem; font-style: italic; }
  `;

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('tabs') || changed.has('_activeIndex')) {
      const activeTab = this.tabs[this._activeIndex];
      if (activeTab) {
        const el = this.renderRoot.querySelector('#active-terminal') as any;
        if (el) {
          const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
          el.configure({
            wsUrl: `${proto}//${location.host}/ws/terminal/${activeTab.sessionName}/{cols}/{rows}`,
            theme: { background: '#1e1e1e', foreground: '#cccccc', cursor: '#aeafad' },
            fontSize: 13,
            fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
          });
          if (this._mousedownHandler) {
            document.removeEventListener('mousedown', this._mousedownHandler);
          }
          this._mousedownHandler = (e: Event) => {
            const termArea = this.renderRoot.querySelector('.terminal-area');
            if (!termArea) return;
            const rect = termArea.getBoundingClientRect();
            const me = e as MouseEvent;
            if (me.clientX >= rect.left && me.clientX <= rect.right &&
                me.clientY >= rect.top && me.clientY <= rect.bottom) {
              setTimeout(() => { if (el._terminal) el._terminal.focus(); }, 0);
            }
          };
          document.addEventListener('mousedown', this._mousedownHandler);
        }
      }
    }
  }

  static override shadowRootOptions = { ...LitElement.shadowRootOptions, delegatesFocus: true };

  private _focusTerminal() {
    const el = this.renderRoot.querySelector('#active-terminal') as any;
    if (el?._terminal) el._terminal.focus();
  }

  private _handleTerminalEvent(e: CustomEvent) {
    const { topic, payload } = e.detail;
    if (topic === 'terminal-connected') {
      this._focusTerminal();
    }
    if (topic === 'terminal-resize') {
      const activeTab = this.tabs[this._activeIndex];
      if (activeTab) {
        fetch(`/api/terminals/${activeTab.sessionName}/resize`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ cols: payload.cols, rows: payload.rows }),
        }).catch(() => {});
      }
    }
  }

  override render() {
    if (this.tabs.length === 0) {
      return html`<div class="empty">No terminal sessions.</div>`;
    }

    const activeTab = this.tabs[this._activeIndex];

    return html`
      <div class="tab-bar">
        ${this.tabs.map((tab, i) => html`
          <button
            class="tab ${i === this._activeIndex ? 'active' : ''}"
            @click=${() => { this._activeIndex = i; }}
          >${tab.name}
            ${tab.agentState ? html`
              <agent-status-badge
                .state=${tab.agentState}
                .memoryMb=${tab.memoryMb ?? 0}
                .lastError=${tab.lastError ?? null}
              ></agent-status-badge>
            ` : nothing}
          </button>
        `)}
      </div>
      <div class="terminal-area" @click=${this._focusTerminal}>
        ${activeTab ? html`
          <pages-component-terminal
            id="active-terminal"
            @pages-event=${this._handleTerminalEvent}
          ></pages-component-terminal>
        ` : nothing}
      </div>
    `;
  }
}
