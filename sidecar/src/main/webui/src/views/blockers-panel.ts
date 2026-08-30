import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

export interface IssueRefData {
  number: number;
  repo: string;
  state?: string;
}

export interface DependencyNode {
  number: number;
  repo: string;
  title: string;
  issueState: string;
  status: 'BLOCKED' | 'UNBLOCKED' | 'CLEAR';
  blockedBy: IssueRefData[];
  blocking: IssueRefData[];
}

export interface GraphResponse {
  criticalPath: IssueRefData[];
  blocked: DependencyNode[];
  unblocked: DependencyNode[];
  clear: DependencyNode[];
  stats: {
    totalIssues: number;
    blocked: number;
    unblocked: number;
    clear: number;
    criticalPathDepth: number;
  };
}

export function classifyNodes(nodes: DependencyNode[]): {
  blocked: DependencyNode[];
  unblocked: DependencyNode[];
  clear: DependencyNode[];
} {
  const blocked: DependencyNode[] = [];
  const unblocked: DependencyNode[] = [];
  const clear: DependencyNode[] = [];
  for (const n of nodes) {
    if (n.status === 'BLOCKED') blocked.push(n);
    else if (n.status === 'UNBLOCKED') unblocked.push(n);
    else clear.push(n);
  }
  return { blocked, unblocked, clear };
}

export function criticalPathDepth(path: IssueRefData[]): number {
  return path.length;
}

@customElement('trellis-blockers-panel')
export class TrellisBlockersPanel extends LitElement {

  @property() workspaceRoot = '';
  @state() private _data: GraphResponse | null = null;
  @state() private _loading = true;
  @state() private _error: string | null = null;

  private _refreshInterval: ReturnType<typeof setInterval> | null = null;

  static override styles = css`
    :host { display: flex; flex-direction: column; height: 100%; font-family: system-ui, -apple-system, sans-serif; container-type: inline-size; }

    .critical-path {
      padding: 8px 16px; background: #1a1a2e; border-bottom: 1px solid #333;
      font-size: 12px; display: flex; align-items: center; gap: 8px; flex-shrink: 0;
    }
    .critical-path-label { color: #f87171; font-weight: 600; text-transform: uppercase; font-size: 11px; letter-spacing: 0.05em; }
    .critical-path-chain { color: #ccc; font-family: monospace; }
    .critical-path-chain .arrow { color: #555; margin: 0 4px; }
    .critical-path-chain .issue { color: #60a5fa; }
    .critical-path-depth { color: #666; margin-left: auto; font-size: 11px; }

    .stats-bar {
      display: flex; gap: 8px; padding: 8px 12px;
      border-bottom: 1px solid #333; font-size: 12px; flex-shrink: 0;
      flex-wrap: wrap;
    }
    .stat { display: flex; align-items: center; gap: 4px; }
    .stat-count { font-weight: 600; }
    .stat-count.blocked { color: #f87171; }
    .stat-count.unblocked { color: #4ade80; }
    .stat-count.clear { color: #9ca3af; }

    .columns {
      display: flex; flex: 1; min-height: 0; overflow: auto;
    }

    .columns {
      display: flex; flex: 1; min-height: 0; overflow: auto;
    }
    .column {
      flex: 1; display: flex; flex-direction: column; overflow-y: auto;
      border-right: 1px solid #2a2a2a; padding: 8px; min-width: 0;
    }
    .column:last-child { border-right: none; }
    .column-header {
      font-size: 11px; font-weight: 600; text-transform: uppercase;
      letter-spacing: 0.05em; padding: 4px 8px; margin-bottom: 8px;
    }
    .column-header.blocked { color: #f87171; }
    .column-header.unblocked { color: #4ade80; }
    .column-header.clear { color: #9ca3af; }

    .card {
      padding: 8px 12px; margin-bottom: 6px; border-radius: 4px;
      background: var(--vscode-editor-background, #252525);
      border-left: 3px solid;
    }
    .card.blocked { border-color: #f87171; }
    .card.unblocked { border-color: #4ade80; }
    .card.clear { border-color: #555; }

    .card-title { font-size: 13px; font-weight: 500; color: #e0e0e0; }
    .card-number { font-family: monospace; color: #60a5fa; margin-right: 6px; font-size: 12px; }
    .card-blockers { margin-top: 4px; font-size: 11px; }
    .blocker { display: flex; align-items: center; gap: 4px; padding: 1px 0; }
    .blocker-arrow { color: #555; }
    .blocker-ref { font-family: monospace; }
    .blocker-ref.open { color: #f87171; }
    .blocker-ref.closed { color: #4ade80; }
    .blocker-ref.external { color: #888; }

    .empty { color: #666; padding: 16px; text-align: center; font-style: italic; font-size: 12px; }
    .error { color: #f87171; padding: 16px; }

    @container (max-width: 500px) {
      .columns { flex-direction: column; overflow-y: auto; }
      .column { border-right: none; padding: 4px 8px; overflow: visible; }
      .column-header { margin-bottom: 4px; padding: 2px 4px; }
      .card { padding: 4px 8px; margin-bottom: 3px; }
      .card-title { font-size: 12px; }
      .card-number { font-size: 11px; }
      .card-blockers { margin-top: 2px; font-size: 10px; }
      .card.clear { padding: 3px 8px; margin-bottom: 2px; border-left-width: 2px; }
      .empty { padding: 8px; font-size: 11px; }
    }
  `;

  override connectedCallback() {
    super.connectedCallback();
    this._load();
    this._refreshInterval = setInterval(() => this._load(), 60_000);
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    if (this._refreshInterval) {
      clearInterval(this._refreshInterval);
      this._refreshInterval = null;
    }
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('workspaceRoot') && this.workspaceRoot) this._load();
  }

  private async _load() {
    if (!this.workspaceRoot) return;
    this._loading = true;
    try {
      const resp = await fetch(`/api/dependencies?root=${encodeURIComponent(this.workspaceRoot)}`);
      if (resp.ok) {
        this._data = await resp.json();
        this._error = null;
      } else {
        this._error = `HTTP ${resp.status}`;
      }
    } catch (e) {
      this._error = `Failed: ${e}`;
    }
    this._loading = false;
  }

  override render() {
    if (this._loading && !this._data) return html`<div class="empty">Loading dependencies...</div>`;
    if (this._error && !this._data) return html`<div class="error">${this._error}</div>`;
    if (!this._data) return html`<div class="empty">No dependency data.</div>`;

    const { blocked, unblocked, clear, criticalPath, stats } = this._data;

    return html`
      ${criticalPath.length > 0 ? html`
        <div class="critical-path">
          <span class="critical-path-label">Critical Path</span>
          <span class="critical-path-chain">
            ${criticalPath.map((ref, i) => html`${i > 0 ? html`<span class="arrow">→</span>` : nothing}<span class="issue">#${ref.number}</span>`)}
          </span>
          <span class="critical-path-depth">depth ${criticalPath.length}</span>
        </div>
      ` : nothing}

      <div class="stats-bar">
        <div class="stat"><span class="stat-count blocked">${stats.blocked}</span> blocked</div>
        <div class="stat"><span class="stat-count unblocked">${stats.unblocked}</span> unblocked</div>
        <div class="stat"><span class="stat-count clear">${stats.clear}</span> clear</div>
        <div style="margin-left:auto;color:#666;font-size:11px">${stats.totalIssues} total</div>
      </div>

      <div class="columns">
        <div class="column">
          <div class="column-header blocked">Blocked (${blocked.length})</div>
          ${blocked.length === 0
            ? html`<div class="empty">None</div>`
            : blocked.map(n => this._renderCard(n, 'blocked'))}
        </div>
        <div class="column">
          <div class="column-header unblocked">Unblocked (${unblocked.length})</div>
          ${unblocked.length === 0
            ? html`<div class="empty">None</div>`
            : unblocked.map(n => this._renderCard(n, 'unblocked'))}
        </div>
        <div class="column">
          <div class="column-header clear">Clear (${clear.length})</div>
          ${clear.length === 0
            ? html`<div class="empty">None</div>`
            : clear.map(n => this._renderCard(n, 'clear'))}
        </div>
      </div>
    `;
  }

  private _renderCard(node: DependencyNode, cls: string) {
    return html`
      <div class="card ${cls}">
        <div class="card-title">
          <span class="card-number">${node.repo}#${node.number}</span>${node.title}
        </div>
        ${node.blockedBy.length > 0 ? html`
          <div class="card-blockers">
            ${node.blockedBy.map(b => html`
              <div class="blocker">
                <span class="blocker-arrow">←</span>
                <span class="blocker-ref ${this._blockerClass(b)}">${b.repo !== node.repo ? `${b.repo}` : ''}#${b.number}</span>
              </div>
            `)}
          </div>
        ` : nothing}
      </div>
    `;
  }

  private _blockerClass(blocker: IssueRefData): string {
    if (blocker.state === 'CLOSED') return 'closed';
    if (blocker.state === 'OPEN') return 'open';
    return 'external';
  }
}
