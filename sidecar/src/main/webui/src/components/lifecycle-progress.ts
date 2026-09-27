import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

export interface StepProgress {
  name: string;
  state: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';
  summary: string;
  stdout: string | null;
  stderr: string | null;
}

export interface OperationProgress {
  operationId: string;
  operationType: string;
  contextId: string;
  state: 'RUNNING' | 'COMPLETED' | 'FAILED';
  steps: StepProgress[];
  errorMessage?: string;
}

@customElement('lifecycle-progress')
export class LifecycleProgress extends LitElement {
  @property({ type: Object }) operation: OperationProgress | null = null;
  @state() private _expandedStep: string | null = null;

  static override styles = css`
    .sidebar-section { margin-bottom: 1.5rem; }
    h3 { margin: 0 0 0.5rem; font-size: 0.85rem; font-weight: 600; color: #aaa; text-transform: uppercase; letter-spacing: 0.05em; }

    .lifecycle-step {
      display: flex; align-items: baseline; gap: 0.4rem;
      font-size: 0.8rem; padding: 0.15rem 0;
    }
    .lifecycle-step-done { color: #666; }
    .lifecycle-step-active { color: #e5e5e5; }
    .lifecycle-step-pending { color: #555; }
    .lifecycle-step-failed { color: #fca5a5; }
    .lifecycle-icon-done { color: #86efac; }
    .lifecycle-icon-active { color: #93c5fd; }
    .lifecycle-icon-pending { color: #555; }
    .lifecycle-icon-failed { color: #f87171; }

    @keyframes lifecycle-spin {
      to { transform: rotate(360deg); }
    }
    .lifecycle-spinner {
      display: inline-block; animation: lifecycle-spin 1s linear infinite;
    }

    .lifecycle-expand {
      font-size: 0.7rem; color: #888; cursor: pointer; padding: 0.1rem 0;
      background: none; border: none;
    }
    .lifecycle-expand:hover { color: #ccc; }

    .lifecycle-output {
      font-family: monospace; font-size: 0.7rem; background: #111;
      border: 1px solid #333; padding: 0.5rem; margin: 0.3rem 0 0.5rem;
      max-height: 150px; overflow-y: auto; white-space: pre-wrap;
      color: #ccc;
    }

    .lifecycle-type-badge {
      display: inline-flex; padding: 0.1rem 0.4rem; border-radius: 3px;
      font-size: 0.65rem; font-weight: 500; background: #1e3a5f; color: #93c5fd;
      margin-bottom: 0.5rem;
    }
  `;

  override render() {
    const op = this.operation;
    if (!op) return nothing;

    return html`
      <div class="sidebar-section">
        <h3>Lifecycle</h3>
        <span class="lifecycle-type-badge">${op.operationType}</span>
        ${op.steps.map(s => html`
          <div class="lifecycle-step ${s.state === 'RUNNING' ? 'lifecycle-step-active' : s.state === 'DONE' ? 'lifecycle-step-done' : s.state === 'FAILED' ? 'lifecycle-step-failed' : 'lifecycle-step-pending'}">
            <span class="${s.state === 'DONE' ? 'lifecycle-icon-done' : s.state === 'RUNNING' ? 'lifecycle-icon-active lifecycle-spinner' : s.state === 'FAILED' ? 'lifecycle-icon-failed' : 'lifecycle-icon-pending'}">
              ${s.state === 'DONE' ? '✓' : s.state === 'RUNNING' ? '●' : s.state === 'FAILED' ? '✗' : '○'}
            </span>
            <span>${s.name}</span>
          </div>
          ${(s.state === 'DONE' || s.state === 'FAILED') && (s.stdout || s.stderr) ? html`
            <button class="lifecycle-expand"
                    @click=${() => { this._expandedStep = this._expandedStep === s.name ? null : s.name; }}>
              ${this._expandedStep === s.name ? '▼' : '▶'} output
            </button>
            ${this._expandedStep === s.name ? html`
              <div class="lifecycle-output">${s.stdout || s.stderr || ''}</div>
            ` : nothing}
          ` : nothing}
          ${s.state === 'FAILED' && this._expandedStep !== s.name && (s.stderr || s.stdout) ? html`
            <div class="lifecycle-output" style="border-color:#991b1b">${s.stderr || s.stdout || ''}</div>
          ` : nothing}
        `)}
        ${op.errorMessage ? html`
          <div style="font-size:0.75rem;color:#f87171;margin-top:0.5rem">${op.errorMessage}</div>
        ` : nothing}
      </div>
    `;
  }
}
