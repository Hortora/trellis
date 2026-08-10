import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { fromRows, ColumnType, columnId } from '@casehubio/pages-data';
import type { TypedDataSet, TypedRow, ColumnId } from '@casehubio/pages-data';
import type { ColumnRenderer, TableColumnConfig } from '@casehubio/pages-table';
import '@casehubio/pages-table';

export interface BacklogItem {
  issueNumber: number;
  issueRepo: string;
  title: string;
  labels: string[];
  cachedAt: string;
  strategicRole: string | null;
  readiness: string | null;
  decay: string | null;
  blastRadius: string | null;
  cohesion: string | null;
  enrichedAt: string | null;
  trajectoryNote: string | null;
  trajectoryAt: string | null;
}

export type FilterKey = 'repo' | 'strategicRole' | 'readiness' | 'decay' | 'blastRadius' | 'cohesion';

const COL = {
  key: columnId('key'),
  number: columnId('number'),
  repo: columnId('repo'),
  title: columnId('title'),
  role: columnId('role'),
  readiness: columnId('readiness'),
  decay: columnId('decay'),
  blast: columnId('blast'),
  cohesion: columnId('cohesion'),
} as const;

const ROLE_COLORS: Record<string, string> = { 'quick-win': '#166534', 'load-bearing': '#854d0e', 'foundational': '#1e3a5f', 'exploratory': '#4c1d95' };
const READINESS_COLORS: Record<string, string> = { ready: '#166534', blocked: '#991b1b', 'needs-spec': '#374151' };
const DECAY_COLORS: Record<string, string> = { compounding: '#991b1b', stable: '#374151', improving: '#166534' };
const BLAST_COLORS: Record<string, string> = { isolated: '#166534', 'cross-cutting': '#854d0e' };

export function applyFilters(
  items: BacklogItem[],
  filters: Partial<Record<FilterKey, string>>
): BacklogItem[] {
  return items.filter(item => {
    for (const [key, value] of Object.entries(filters)) {
      if (!value) continue;
      const field = key === 'repo' ? 'issueRepo' : key;
      if ((item as any)[field] !== value) return false;
    }
    return true;
  });
}

export function cacheAge(items: BacklogItem[]): number | null {
  if (items.length === 0) return null;
  const oldest = Math.min(...items.map(i => new Date(i.cachedAt).getTime()));
  return Date.now() - oldest;
}

@customElement('trellis-backlog-panel')
export class TrellisBacklogPanel extends LitElement {

  @property() workspaceRoot = '';

  @state() private _items: BacklogItem[] = [];
  @state() private _error: string | null = null;
  @state() private _loading = false;
  @state() private _filters: Partial<Record<FilterKey, string>> = {};
  @state() private _activeItem: BacklogItem | null = null;

  private _refreshInterval: ReturnType<typeof setInterval> | null = null;

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

  private async _load() {
    this._loading = true;
    try {
      const res = await fetch('/api/backlog');
      if (!res.ok) {
        this._error = `HTTP ${res.status}`;
        return;
      }
      this._items = await res.json();
      this._error = null;
    } catch (e) {
      this._error = `Failed to load: ${e}`;
    } finally {
      this._loading = false;
    }
  }

  private _filtered(): BacklogItem[] {
    return applyFilters(this._items, this._filters);
  }

  private _distinctValues(field: string): string[] {
    const vals = new Set<string>();
    for (const item of this._items) {
      const v = (item as any)[field];
      if (v != null) vals.add(v);
    }
    return [...vals].sort();
  }

  private _setFilter(key: FilterKey, value: string) {
    this._filters = { ...this._filters, [key]: value || undefined };
  }

  private _buildDataSet(): TypedDataSet {
    return fromRows(this._filtered(), [
      { id: COL.key, type: ColumnType.TEXT, getValue: i => `${i.issueRepo}#${i.issueNumber}` },
      { id: COL.number, type: ColumnType.NUMBER, getValue: i => i.issueNumber },
      { id: COL.repo, type: ColumnType.TEXT, getValue: i => i.issueRepo },
      { id: COL.title, type: ColumnType.TEXT, getValue: i => i.title },
      { id: COL.role, type: ColumnType.LABEL, getValue: i => i.strategicRole ?? '—' },
      { id: COL.readiness, type: ColumnType.LABEL, getValue: i => i.readiness ?? '—' },
      { id: COL.decay, type: ColumnType.LABEL, getValue: i => i.decay ?? '—' },
      { id: COL.blast, type: ColumnType.LABEL, getValue: i => i.blastRadius ?? '—' },
      { id: COL.cohesion, type: ColumnType.TEXT, getValue: i => i.cohesion ?? '—' },
    ]);
  }

  private get _columnConfig(): TableColumnConfig[] {
    return [
      { id: COL.number, label: '#', width: '0.7fr' },
      { id: COL.repo, label: 'Repo', width: '1.5fr' },
      { id: COL.title, label: 'Title', width: '3fr' },
      { id: COL.role, label: 'Role', width: '1.2fr' },
      { id: COL.readiness, label: 'Ready', width: '1fr' },
      { id: COL.decay, label: 'Decay', width: '1fr' },
      { id: COL.blast, label: 'Blast', width: '1.2fr' },
      { id: COL.cohesion, label: 'Cohesion', width: '1fr' },
    ];
  }

  private _pill(value: string, colorMap: Record<string, string>) {
    if (value === '—') return html`<span style="color:#555">—</span>`;
    const bg = colorMap[value] ?? '#374151';
    return html`<span style="font-size:10px; padding:2px 8px; border-radius:3px; color:#fff; background:${bg}; display:inline-block; min-width:55px; text-align:center;">${value}</span>`;
  }

  private get _columnRenderers(): ReadonlyMap<ColumnId, ColumnRenderer> {
    const self = this;
    return new Map<ColumnId, ColumnRenderer>([
      [COL.number, (cell) => {
        const num = (cell as any).value;
        return html`<span style="color:#60a5fa; font-family:monospace">${num}</span>`;
      }],
      [COL.role, (cell) => self._pill((cell as any).value, ROLE_COLORS)],
      [COL.readiness, (cell) => self._pill((cell as any).value, READINESS_COLORS)],
      [COL.decay, (cell) => self._pill((cell as any).value, DECAY_COLORS)],
      [COL.blast, (cell) => self._pill((cell as any).value, BLAST_COLORS)],
    ]);
  }

  private _getRowKey = (row: TypedRow) => row.text(COL.key);

  private _handleRowActivate = (e: CustomEvent) => {
    const key = e.detail.row.text(COL.key);
    const item = this._items.find(i => `${i.issueRepo}#${i.issueNumber}` === key);
    this._activeItem = this._activeItem === item ? null : (item ?? null);
  };

  private _formatAge(ms: number): string {
    if (ms < 60_000) return 'just now';
    if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m ago`;
    if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)}h ago`;
    return `${Math.floor(ms / 86_400_000)}d ago`;
  }

  private _ageColor(ms: number): string {
    if (ms < 4 * 3_600_000) return '#9ca3af';
    if (ms < 24 * 3_600_000) return '#fbbf24';
    return '#f87171';
  }

  private _renderFilter(key: FilterKey, label: string, field: string) {
    const values = this._distinctValues(field);
    if (values.length === 0) return nothing;
    return html`
      <select @change=${(e: Event) => this._setFilter(key, (e.target as HTMLSelectElement).value)}>
        <option value="">${label}: All</option>
        ${values.map(v => html`<option value=${v} ?selected=${this._filters[key] === v}>${v}</option>`)}
      </select>
    `;
  }

  static override styles = css`
    :host { display: flex; height: 100%; font-family: system-ui, -apple-system, sans-serif; }
    .main { flex: 1; display: flex; flex-direction: column; min-width: 0; overflow-y: auto; }

    .header {
      display: flex; align-items: center; gap: 1rem; padding: 0.75rem 1rem;
      background: #1a1a1a; border-bottom: 1px solid #333; flex-shrink: 0;
    }
    .header h2 { margin: 0; font-size: 1rem; font-weight: 600; }
    .header .spacer { flex: 1; }
    .cache-age { font-size: 0.8rem; font-family: monospace; }

    .filters {
      display: flex; gap: 0.5rem; padding: 0.5rem 1rem; background: #1a1a1a;
      border-bottom: 1px solid #2a2a2a; flex-shrink: 0; flex-wrap: wrap;
    }
    .filters select {
      padding: 0.3rem 0.5rem; background: #2a2a2a; border: 1px solid #444;
      border-radius: 4px; color: #ccc; font-size: 0.75rem;
    }

    .empty { color: #666; padding: 2rem; text-align: center; font-style: italic; }
    .error { color: #f87171; padding: 1rem; }

    .sidebar {
      width: 300px; background: #1e1e1e; border-left: 1px solid #333;
      padding: 1rem; overflow-y: auto; flex-shrink: 0;
    }
    .sidebar h3 {
      margin: 0 0 0.75rem; font-size: 0.85rem; font-weight: 600;
      color: #aaa; text-transform: uppercase; letter-spacing: 0.05em;
    }
    .sidebar-field { margin-bottom: 0.5rem; }
    .sidebar-label { font-size: 0.7rem; color: #666; text-transform: uppercase; letter-spacing: 0.05em; }
    .sidebar-value { font-size: 0.85rem; color: #ccc; margin-top: 0.15rem; }
    .sidebar-note { font-size: 0.8rem; color: #ccc; line-height: 1.5; white-space: pre-wrap; margin-top: 0.5rem; }
    .sidebar-placeholder { color: #555; font-size: 0.8rem; font-style: italic; }
  `;

  override render() {
    const age = cacheAge(this._items);
    const filtered = this._filtered();

    return html`
      <div class="main">
        <div class="header">
          <h2>Backlog</h2>
          <span class="spacer"></span>
          ${age != null ? html`
            <span class="cache-age" style="color:${this._ageColor(age)}">
              Refreshed ${this._formatAge(age)}
            </span>
          ` : nothing}
          <span style="font-size:0.8rem;color:#666">${filtered.length} of ${this._items.length} issues</span>
        </div>

        <div class="filters">
          ${this._renderFilter('repo', 'Repo', 'issueRepo')}
          ${this._renderFilter('strategicRole', 'Role', 'strategicRole')}
          ${this._renderFilter('readiness', 'Ready', 'readiness')}
          ${this._renderFilter('decay', 'Decay', 'decay')}
          ${this._renderFilter('blastRadius', 'Blast', 'blastRadius')}
          ${this._renderFilter('cohesion', 'Cohesion', 'cohesion')}
        </div>

        ${this._error ? html`<div class="error">${this._error}</div>` : nothing}
        ${filtered.length === 0 && !this._error
          ? html`<div class="empty">${this._items.length === 0 ? 'No backlog data. Run enrichment refresh to populate.' : 'No issues match the current filters.'}</div>`
          : html`
            <pages-data-table
              .embedded=${true}
              mode="paginated"
              .pageSize=${100}
              .dataSet=${this._buildDataSet()}
              .columnConfig=${this._columnConfig}
              .columnRenderers=${this._columnRenderers}
              .sortable=${true}
              .clientSort=${true}
              .getRowKey=${this._getRowKey}
              .hiddenColumns=${[COL.key] as any}
              .emptyMessage=${'No backlog data.'}
              @row-activate=${this._handleRowActivate}
            ></pages-data-table>
          `}
      </div>

      <div class="sidebar">
        ${this._activeItem
          ? this._renderDetail(this._activeItem)
          : html`<h3>Issue Detail</h3><div class="sidebar-placeholder">Click a row to see details.</div>`
        }
      </div>
    `;
  }

  private _renderDetail(item: BacklogItem) {
    return html`
      <h3>#${item.issueNumber}</h3>
      <div class="sidebar-field">
        <div class="sidebar-label">Title</div>
        <div class="sidebar-value">${item.title}</div>
      </div>
      <div class="sidebar-field">
        <div class="sidebar-label">Repo</div>
        <div class="sidebar-value">${item.issueRepo}</div>
      </div>
      ${item.labels.length > 0 ? html`
        <div class="sidebar-field">
          <div class="sidebar-label">Labels</div>
          <div class="sidebar-value">${item.labels.join(', ')}</div>
        </div>
      ` : nothing}
      ${item.strategicRole ? html`
        <div class="sidebar-field">
          <div class="sidebar-label">Classification</div>
          <div class="sidebar-value">
            ${item.strategicRole} / ${item.readiness} / ${item.decay} / ${item.blastRadius}
            ${item.cohesion ? html` / ${item.cohesion}` : nothing}
          </div>
        </div>
      ` : html`
        <div class="sidebar-field">
          <div class="sidebar-value" style="color:#555;font-style:italic">Not enriched</div>
        </div>
      `}
      ${item.trajectoryNote ? html`
        <div class="sidebar-field">
          <div class="sidebar-label">Trajectory</div>
          <div class="sidebar-note">${item.trajectoryNote}</div>
          <div style="font-size:0.7rem;color:#555;margin-top:0.25rem">${item.trajectoryAt}</div>
        </div>
      ` : nothing}
      <div class="sidebar-field">
        <div class="sidebar-label">Cached</div>
        <div class="sidebar-value" style="font-family:monospace;font-size:0.8rem">${item.cachedAt}</div>
      </div>
    `;
  }
}
