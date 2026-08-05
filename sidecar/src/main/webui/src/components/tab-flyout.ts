import { LitElement, html, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';

@customElement('trellis-tab-flyout')
export class TrellisTabFlyout extends LitElement {

  @property() terminalName = '';
  @property() repoName = '';
  @property() branch = '';
  @property() path = '';
  @property() slot = '';
  @property() issue = '';
  @property() agentState = '';
  @property({ type: Number }) agentUptimeMs = 0;
  @property({ type: Number }) memoryMb = 0;
  @property() lastOutput = '';

  static override styles = css`
    :host {
      display: block;
      position: absolute;
      z-index: 99999;
      background: #252526;
      border: 1px solid #3c3c3c;
      border-radius: 6px;
      padding: 10px 14px;
      font-size: 12px;
      color: #ccc;
      min-width: 240px;
      max-width: 360px;
      box-shadow: 0 4px 12px rgba(0,0,0,0.5);
      pointer-events: none;
      font-family: system-ui, -apple-system, sans-serif;
    }

    .name { font-size: 13px; font-weight: 600; color: #e0e0e0; margin-bottom: 6px; }
    .divider { border-top: 1px solid #3c3c3c; margin: 6px 0; }
    .row { display: flex; gap: 6px; margin: 2px 0; }
    .label { color: #888; min-width: 50px; }
    .value { color: #ccc; }

    .agent-dot {
      display: inline-block;
      width: 7px; height: 7px;
      border-radius: 50%;
      margin-right: 4px;
      vertical-align: middle;
    }
    .agent-dot.running { background: #22c55e; }
    .agent-dot.idle { background: #666; }
    .agent-dot.paused { background: #f59e0b; }
    .agent-dot.starting { background: #3b82f6; }

    .output {
      margin-top: 4px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 11px;
      color: #999;
      white-space: pre-wrap;
      max-height: 48px;
      overflow: hidden;
    }
  `;

  override render() {
    const uptime = this.agentUptimeMs > 0 ? this._formatUptime(this.agentUptimeMs) : '';
    const stateLower = (this.agentState || '').toLowerCase();
    const dotClass = ['running', 'idle', 'paused', 'starting'].includes(stateLower) ? stateLower : 'idle';

    return html`
      <div class="name">${this.repoName || this.terminalName}</div>
      <div class="divider"></div>
      ${this.branch ? html`<div class="row"><span class="label">Branch:</span><span class="value">${this.branch}</span></div>` : nothing}
      ${this.path ? html`<div class="row"><span class="label">Path:</span><span class="value">${this.path}</span></div>` : nothing}
      ${this.slot ? html`<div class="row"><span class="label">Slot:</span><span class="value">${this.slot}</span></div>` : nothing}
      ${this.issue ? html`<div class="row"><span class="label">Issue:</span><span class="value">${this.issue}</span></div>` : nothing}
      ${this.agentState ? html`
        <div class="divider"></div>
        <div class="row">
          <span class="label">Agent:</span>
          <span class="value"><span class="agent-dot ${dotClass}"></span>${this.agentState}${uptime ? ` (${uptime})` : ''}</span>
        </div>
        ${this.memoryMb > 0 ? html`<div class="row"><span class="label">Memory:</span><span class="value">${this.memoryMb} MB</span></div>` : nothing}
      ` : nothing}
      ${this.lastOutput ? html`
        <div class="divider"></div>
        <div class="output">${this.lastOutput}</div>
      ` : nothing}
    `;
  }

  private _formatUptime(ms: number): string {
    const s = Math.floor(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m ${s % 60}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
  }
}
