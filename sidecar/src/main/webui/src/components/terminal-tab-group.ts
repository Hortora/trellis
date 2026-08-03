import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import './terminal-panel';
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

    .terminal-area { flex: 1; min-height: 0; }
    .terminal-area trellis-terminal-panel { height: 100%; }
    .empty { color: #666; padding: 2rem; font-style: italic; }
  `;

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
      <div class="terminal-area">
        ${activeTab ? html`
          <trellis-terminal-panel
            .sessionName=${activeTab.sessionName}
          ></trellis-terminal-panel>
        ` : nothing}
      </div>
    `;
  }
}
