import { LitElement, html, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';

@customElement('agent-status-badge')
export class AgentStatusBadge extends LitElement {

  @property() state: string = 'IDLE';
  @property({ type: Number }) memoryMb: number = 0;
  @property() lastError: string | null = null;

  static override styles = css`
    :host { display: inline-flex; align-items: center; gap: 0.4rem; font-size: 0.75rem; }

    .badge {
      display: inline-flex; align-items: center; gap: 0.25rem;
      padding: 0.1rem 0.5rem; border-radius: 4px; font-weight: 500;
    }
    .badge-running { background: #166534; color: #86efac; }
    .badge-paused { background: #854d0e; color: #fde68a; }
    .badge-idle { background: #374151; color: #9ca3af; }
    .badge-starting { background: #1e3a5f; color: #93c5fd; animation: pulse 1.5s ease-in-out infinite; }
    .badge-error { background: #7f1d1d; color: #fca5a5; }

    .memory { font-family: monospace; color: #9ca3af; }
    .memory.warning { color: #f87171; font-weight: 600; }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.5; }
    }
  `;

  override render() {
    const hasError = this.lastError && this.state === 'IDLE';
    const stateClass = hasError ? 'error' : this.state.toLowerCase();
    const label = hasError ? 'error' : this.state.toLowerCase();

    return html`
      <span class="badge badge-${stateClass}" title=${this.lastError ?? ''}>
        ${label}
      </span>
      ${this.state === 'RUNNING' && this.memoryMb > 0 ? html`
        <span class="memory ${this.memoryMb > 500 ? 'warning' : ''}">
          ${this.memoryMb} MB
        </span>
      ` : nothing}
    `;
  }
}
