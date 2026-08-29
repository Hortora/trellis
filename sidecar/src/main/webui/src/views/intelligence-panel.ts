import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface Finding {
  facet: string;
  severity: string;
  subject: string;
  summary?: string;
  suggestion?: string;
  confidence: number;
  since: string;
  lastSignal: string;
  evidence: Record<string, unknown>;
}

@customElement('trellis-intelligence-panel')
export class TrellisIntelligencePanel extends LitElement {

  @property() workspaceRoot = '';
  @state() private _findings: Finding[] = [];
  @state() private _loading = true;

  static override styles = css`
    :host { display: block; height: 100%; overflow-y: auto; padding: 16px; }
    .severity-group { margin-bottom: 16px; }
    .severity-label {
      font-size: 11px; font-weight: 600; text-transform: uppercase;
      letter-spacing: 0.05em; margin-bottom: 8px; padding: 2px 0;
      cursor: pointer; list-style: none;
    }
    .severity-label::marker { content: ''; }
    .severity-label.action { color: var(--vscode-errorForeground, #f87171); }
    .severity-label.attention { color: var(--vscode-editorWarning-foreground, #fbbf24); }
    .severity-label.info { color: var(--vscode-editorInfo-foreground, #60a5fa); }
    .finding {
      padding: 8px 12px; border-left: 3px solid; margin-bottom: 4px;
      background: var(--vscode-editor-background, #252525); border-radius: 2px;
    }
    .finding.action { border-color: var(--vscode-errorForeground, #f87171); }
    .finding.attention { border-color: var(--vscode-editorWarning-foreground, #fbbf24); }
    .finding.info { border-color: var(--vscode-editorInfo-foreground, #60a5fa); }
    .subject { font-weight: 500; font-size: 13px; }
    .detail { font-size: 12px; color: var(--vscode-descriptionForeground, #999); margin-top: 2px; }
    .suggestion { font-size: 12px; color: var(--vscode-descriptionForeground, #777); margin-top: 4px; font-style: italic; }
    .evidence { font-size: 11px; color: var(--vscode-descriptionForeground, #888); margin-top: 4px; font-family: monospace; }
    .empty { color: var(--vscode-descriptionForeground, #666); padding: 32px; text-align: center; }
    .summary-bar {
      display: flex; gap: 12px; margin-bottom: 16px; padding: 8px 0;
      border-bottom: 1px solid var(--vscode-panel-border, #333);
      font-size: 12px;
    }
    .summary-bar .count { font-weight: 600; }
    .summary-bar .count.action { color: var(--vscode-errorForeground, #f87171); }
    .summary-bar .count.attention { color: var(--vscode-editorWarning-foreground, #fbbf24); }
    .summary-bar .count.info { color: var(--vscode-editorInfo-foreground, #60a5fa); }
  `;

  override connectedCallback() {
    super.connectedCallback();
    this._loadFindings();
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('workspaceRoot') && this.workspaceRoot) {
      this._loadFindings();
    }
  }

  private async _loadFindings() {
    if (!this.workspaceRoot) return;
    this._loading = true;
    try {
      const resp = await fetch(`/api/intelligence?root=${encodeURIComponent(this.workspaceRoot)}`);
      if (resp.ok) {
        const data = await resp.json();
        this._findings = data.findings ?? [];
      }
    } catch { /* non-critical */ }
    this._loading = false;
  }

  override render() {
    if (this._loading) return html`<div class="empty">Loading intelligence...</div>`;
    if (this._findings.length === 0) return html`<div class="empty">No findings — all clear.</div>`;

    const grouped = { action: [] as Finding[], attention: [] as Finding[], info: [] as Finding[] };
    for (const f of this._findings) {
      const key = f.severity === 'ACTION_NEEDED' ? 'action' : f.severity === 'ATTENTION' ? 'attention' : 'info';
      grouped[key].push(f);
    }

    return html`
      <div class="summary-bar">
        <span class="count action">${grouped.action.length} action needed</span>
        <span class="count attention">${grouped.attention.length} attention</span>
        <span class="count info">${grouped.info.length} info</span>
      </div>
      ${this._renderGroup('action', 'Action Needed', grouped.action, false)}
      ${this._renderGroup('attention', 'Attention', grouped.attention, false)}
      ${this._renderGroup('info', 'Info', grouped.info, true)}
    `;
  }

  private _renderGroup(cls: string, label: string, findings: Finding[], collapsed: boolean) {
    if (findings.length === 0) return '';
    return html`
      <details class="severity-group" ?open=${!collapsed}>
        <summary class="severity-label ${cls}">${label} (${findings.length})</summary>
        ${findings.map(f => html`
          <div class="finding ${cls}">
            <div class="subject">${f.subject}</div>
            <div class="detail">${f.summary ?? `${f.facet} — confidence ${(f.confidence * 100).toFixed(0)}%`}</div>
            ${f.suggestion ? html`<div class="suggestion">${f.suggestion}</div>` : ''}
            ${this._renderEvidence(f.evidence)}
          </div>
        `)}
      </details>
    `;
  }

  private _renderEvidence(evidence: Record<string, unknown>) {
    const entries = Object.entries(evidence).filter(([, v]) => v !== null && v !== '');
    if (entries.length === 0) return '';
    return html`<div class="evidence">${entries.map(([k, v]) => `${k}: ${v}`).join(' · ')}</div>`;
  }
}
