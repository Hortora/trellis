import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import '@casehubio/blocks-ui-kpi-metric-row';
import '@casehubio/blocks-ui-blocks-timeline';
import '../components/dag';

interface EpicAnalysis {
  issues: { key: string; title: string; state: string }[];
  graph: {
    nodes: { key: string; layer: number; index: number; closed: boolean; onCriticalPath: boolean; inCycle: boolean; external: boolean }[];
    edges: { source: string; target: string }[];
  };
  kpis: {
    total: number; open: number; closed: number;
    criticalPathLength: number; estimatedSerialSteps: number;
    bottleneckCount: number; maxParallelism: number;
  };
  recommendations: { key: string; title: string; type: string; score: number; reason: string }[];
  batches: { batch: number; label: string; status: string; issues: string[] }[];
  cycleWarning: string[];
}

interface TimelineNode {
  key: string;
  label: string;
  status: 'completed' | 'active' | 'pending';
}

interface TimelineStrategy {
  toNodes(data: unknown): TimelineNode[];
  defaultLayout: 'horizontal' | 'vertical' | 'compact';
}

const batchStrategy: TimelineStrategy = {
  toNodes(data: unknown): TimelineNode[] {
    const batches = data as { batch: number; label: string; status: string }[];
    return batches.map(b => ({
      key: `batch-${b.batch}`,
      label: `Batch ${b.batch} — ${b.label}`,
      status: b.status as 'completed' | 'active' | 'pending',
    }));
  },
  defaultLayout: 'horizontal',
};

@customElement('trellis-epic-dashboard')
export class TrellisEpicDashboard extends LitElement {
  @property() owner = '';
  @property() repo = '';
  @property({ type: Number }) epicNumber = 0;
  @property() workspaceRoot = '';

  @state() private _data: EpicAnalysis | null = null;
  @state() private _error: string | null = null;
  @state() private _loading = true;
  @state() private _startingKey: string | null = null;
  @state() private _startError: string | null = null;

  static override styles = css`
    :host { display: block; height: 100%; overflow-y: auto; font-family: system-ui, sans-serif; }

    .header {
      display: flex; align-items: center; gap: 1rem; padding: 1rem 1.5rem;
      border-bottom: 1px solid #333;
    }
    .header h1 { margin: 0; font-size: 1.3rem; font-weight: 600; }
    .header .ref { color: #888; font-family: monospace; font-size: 0.85rem; }
    .header .progress-badge {
      padding: 0.2rem 0.6rem; border-radius: 12px; font-size: 0.75rem;
      background: #166534; color: #86efac; font-weight: 500;
    }

    .cycle-warning {
      margin: 0.75rem 1.5rem; padding: 0.75rem 1rem; background: #450a0a;
      border: 1px solid #991b1b; border-radius: 6px; color: #fca5a5; font-size: 0.85rem;
    }

    .kpi-section { padding: 1rem 1.5rem; }

    .body { display: flex; gap: 1rem; padding: 0 1.5rem; min-height: 400px; }
    .dag-panel { flex: 2; min-width: 0; }
    .recs-panel { flex: 1; min-width: 250px; max-width: 350px; }

    .recs-title { font-size: 0.9rem; font-weight: 600; margin: 0 0 0.75rem; color: #aaa; }
    .rec-card {
      padding: 0.6rem 0.75rem; margin-bottom: 0.5rem; background: #252525;
      border: 1px solid #333; border-radius: 6px;
    }
    .rec-type {
      display: inline-block; padding: 0.1rem 0.4rem; border-radius: 3px;
      font-size: 0.65rem; font-weight: 600; margin-bottom: 0.3rem;
    }
    .rec-type-CRITICAL_PATH { background: #1e3a5f; color: #93c5fd; }
    .rec-type-BOTTLENECK { background: #713f12; color: #fde68a; }
    .rec-title { font-size: 0.85rem; font-weight: 500; margin-bottom: 0.2rem; }
    .rec-reason { font-size: 0.75rem; color: #999; }
    .rec-actions { display: flex; justify-content: flex-end; margin-top: 0.4rem; }
    .start-btn {
      padding: 0.2rem 0.6rem; border: 1px solid #1d4ed8; border-radius: 4px;
      background: transparent; color: #93c5fd; cursor: pointer; font-size: 0.7rem;
      font-weight: 500; transition: background 0.15s;
    }
    .start-btn:hover { background: #1e3a5f; }
    .start-btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .start-error {
      margin-top: 0.4rem; padding: 0.4rem 0.6rem; background: #450a0a;
      border: 1px solid #991b1b; border-radius: 4px; color: #fca5a5; font-size: 0.75rem;
    }

    .timeline-section { padding: 1rem 1.5rem; }

    .loading { padding: 2rem; text-align: center; color: #666; }
    .error { padding: 2rem; color: #f87171; }
    .back-link {
      color: #93c5fd; text-decoration: none; font-size: 0.85rem; cursor: pointer;
    }
    .back-link:hover { text-decoration: underline; }
  `;

  override connectedCallback() {
    super.connectedCallback();
    this._fetchAnalysis();
  }

  override render() {
    if (this._loading) return html`<div class="loading">Loading epic analysis...</div>`;
    if (this._error) return html`<div class="error">${this._error}</div>`;
    if (!this._data) return nothing;

    const d = this._data;
    const epicTitle = this._epicTitle(d);

    return html`
      ${this._renderHeader(epicTitle, d.kpis)}
      ${d.cycleWarning.length > 0 ? html`
        <div class="cycle-warning">
          Dependency cycle detected involving: ${d.cycleWarning.map(k => k.replace(/.*#/, '#')).join(', ')}. These issues cannot be ordered.
        </div>
      ` : nothing}
      <div class="kpi-section">
        <blocks-kpi-metric-row
          .metrics=${this._buildMetrics(d.kpis)}
          density="compact"
        ></blocks-kpi-metric-row>
      </div>
      <div class="body">
        <div class="dag-panel">
          <trellis-dag
            .nodes=${d.graph.nodes.map(n => ({
              ...n,
              label: this._nodeLabel(n.key, d),
            }))}
            .edges=${d.graph.edges}
          ></trellis-dag>
        </div>
        <div class="recs-panel">
          ${this._renderRecommendations(d.recommendations)}
        </div>
      </div>
      <div class="timeline-section">
        <blocks-timeline
          .strategy=${batchStrategy}
          .data=${d.batches}
          layout="horizontal"
        ></blocks-timeline>
      </div>
    `;
  }

  private _epicTitle(d: EpicAnalysis): string {
    const epicKey = `${this.owner}/${this.repo}#${this.epicNumber}`;
    return d.issues.find(i => i.key === epicKey)?.title ?? `Epic #${this.epicNumber}`;
  }

  private _nodeLabel(key: string, d: EpicAnalysis): string {
    const issue = d.issues.find(i => i.key === key);
    if (!issue) return key.replace(/.*#/, '#');
    const short = issue.title.replace(/^.*?:\s*/, '');
    return short.length > 25 ? short.slice(0, 22) + '...' : short;
  }

  private _renderHeader(title: string, kpis: EpicAnalysis['kpis']) {
    return html`
      <div class="header">
        <a class="back-link" @click=${() => history.back()}>back</a>
        <h1>${title}</h1>
        <span class="ref">${this.owner}/${this.repo}#${this.epicNumber}</span>
        <span class="progress-badge">${kpis.closed}/${kpis.total} done</span>
      </div>
    `;
  }

  private _buildMetrics(kpis: EpicAnalysis['kpis']): { key: string; value: number | string; label: string; status?: string }[] {
    return [
      { key: 'progress', value: `${kpis.closed}/${kpis.total}`, label: 'Progress' },
      { key: 'critPath', value: kpis.criticalPathLength, label: 'Critical Path' },
      { key: 'serialSteps', value: kpis.estimatedSerialSteps, label: 'Serial Steps Left' },
      {
        key: 'bottlenecks', value: kpis.bottleneckCount, label: 'Bottlenecks',
        status: kpis.bottleneckCount > 5 ? 'critical' : kpis.bottleneckCount > 2 ? 'warning' : 'normal',
      },
      { key: 'parallel', value: kpis.maxParallelism, label: 'Max Parallelism' },
    ];
  }

  private _renderRecommendations(recs: EpicAnalysis['recommendations']) {
    if (recs.length === 0) {
      return html`<div class="recs-title">No recommendations</div>`;
    }
    return html`
      <div class="recs-title">Recommendations</div>
      ${this._startError ? html`<div class="start-error">${this._startError}</div>` : nothing}
      ${recs.map(r => html`
        <div class="rec-card">
          <span class="rec-type rec-type-${r.type}">${r.type.replace('_', ' ')}</span>
          <div class="rec-title">${r.title}</div>
          <div class="rec-reason">${r.reason}</div>
          ${this.workspaceRoot ? html`
            <div class="rec-actions">
              <button class="start-btn"
                ?disabled=${this._startingKey !== null}
                @click=${() => this._startWork(r)}
              >${this._startingKey === r.key ? 'Starting...' : 'Start'}</button>
            </div>
          ` : nothing}
        </div>
      `)}
    `;
  }

  private _toSlug(title: string): string {
    return title
      .toLowerCase()
      .replace(/[^a-z0-9\s-]/g, '')
      .trim()
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-')
      .slice(0, 40)
      .replace(/-$/, '');
  }

  private async _startWork(rec: EpicAnalysis['recommendations'][0]) {
    if (!this.workspaceRoot) return;
    this._startingKey = rec.key;
    this._startError = null;

    const m = rec.key.match(/^([^/]+\/[^#]+)#(\d+)$/);
    if (!m) {
      this._startError = `Invalid issue key: ${rec.key}`;
      this._startingKey = null;
      return;
    }
    const issueRepo = m[1];
    const issueNumber = m[2];
    const repoName = issueRepo.split('/')[1];
    const branch = `issue-${issueNumber}-${this._toSlug(rec.title)}`;

    try {
      const res = await fetch('/api/lifecycle/slot/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          workspaceRoot: this.workspaceRoot,
          args: [
            this.workspaceRoot,
            `repos=${repoName}`,
            `branch=${branch}`,
            `issue=${issueNumber}`,
            `issue-repo=${issueRepo}`,
            `context=${rec.reason}`,
          ],
        }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._startError = body?.error ?? `Slot creation failed: HTTP ${res.status}`;
        return;
      }
      const result = await res.json();
      const slotNumber = result.output?.SLOT_NUMBER;
      if (slotNumber) {
        location.hash = `#slot/${slotNumber}?root=${encodeURIComponent(this.workspaceRoot)}`;
      }
    } catch (e) {
      this._startError = `Failed to start work: ${e}`;
    } finally {
      this._startingKey = null;
    }
  }

  private async _fetchAnalysis() {
    this._loading = true;
    try {
      const res = await fetch(`/api/repos/${this.owner}/${this.repo}/epics/${this.epicNumber}/analysis`);
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._error = body?.error ?? `HTTP ${res.status}`;
        return;
      }
      this._data = await res.json();
    } catch (e) {
      this._error = `Failed to load: ${e}`;
    } finally {
      this._loading = false;
    }
  }
}
