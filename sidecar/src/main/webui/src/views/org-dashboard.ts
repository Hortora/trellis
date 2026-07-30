import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';

interface RepoInfo {
  name: string;
  path: string;
  branch: string;
  remoteUrl: string | null;
}

interface SlotInfo {
  number: number;
  path: string;
  issue: string;
  status: 'ACTIVE' | 'READY_TO_LAND' | 'ARCHIVED';
  isEpic: boolean;
  repos: string[];
}

interface PauseEntry {
  branch: string;
  issue: number;
  pausedAt: string | null;
}

interface EpicInfo {
  issue: string;
  currentBatch: number;
  currentIssue: string | null;
  completedChildren: number;
  totalChildren: number;
}

interface WorkspaceModel {
  root: string;
  scannedAt: string;
  repos: RepoInfo[];
  slots: SlotInfo[];
  pauses: PauseEntry[];
  epics: EpicInfo[];
}

const STATUS_COLORS: Record<string, string> = {
  ACTIVE: '#4ade80',
  READY_TO_LAND: '#facc15',
  ARCHIVED: '#6b7280',
};

@customElement('trellis-org-dashboard')
export class TrellisOrgDashboard extends LitElement {
  @state() private _model: WorkspaceModel | null = null;
  @state() private _error: string | null = null;
  @state() private _loading = false;
  @state() private _root = '';

  static override styles = css`
    :host { display: block; height: 100%; overflow-y: auto; padding: 1.5rem; font-family: system-ui, -apple-system, sans-serif; }

    .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.5rem; }
    .header h1 { margin: 0; font-size: 1.4rem; font-weight: 600; }
    .scanned { font-size: 0.75rem; color: #888; }

    .root-input { display: flex; gap: 0.5rem; margin-bottom: 1.5rem; }
    .root-input input {
      flex: 1; padding: 0.5rem 0.75rem; background: #2a2a2a; border: 1px solid #444;
      border-radius: 6px; color: #eee; font-family: monospace; font-size: 0.85rem;
    }
    .root-input button {
      padding: 0.5rem 1rem; background: #3b82f6; color: white; border: none;
      border-radius: 6px; cursor: pointer; font-size: 0.85rem;
    }
    .root-input button:hover { background: #2563eb; }
    .root-input button:disabled { opacity: 0.5; cursor: not-allowed; }

    .section { margin-bottom: 2rem; }
    .section h2 { font-size: 1rem; font-weight: 600; margin: 0 0 0.75rem; display: flex; align-items: center; gap: 0.5rem; }
    .count { background: #333; padding: 0.15rem 0.5rem; border-radius: 10px; font-size: 0.75rem; font-weight: 400; }

    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 0.75rem; }

    .card {
      background: #252525; border: 1px solid #333; border-radius: 8px; padding: 0.75rem;
      transition: border-color 0.15s;
    }
    .card:hover { border-color: #555; }

    .card-name { font-weight: 600; font-size: 0.9rem; margin-bottom: 0.25rem; }
    .card-detail { font-size: 0.8rem; color: #999; font-family: monospace; }
    .card-meta { display: flex; gap: 0.5rem; margin-top: 0.4rem; flex-wrap: wrap; }

    .badge {
      display: inline-flex; align-items: center; padding: 0.1rem 0.5rem;
      border-radius: 4px; font-size: 0.7rem; font-weight: 500;
    }
    .badge-branch { background: #1e3a5f; color: #93c5fd; }
    .badge-status { color: #111; font-weight: 600; }
    .badge-epic { background: #4c1d95; color: #c4b5fd; }
    .badge-pause { background: #713f12; color: #fde68a; }

    .progress-bar { height: 4px; background: #333; border-radius: 2px; margin-top: 0.4rem; overflow: hidden; }
    .progress-fill { height: 100%; background: #4ade80; border-radius: 2px; transition: width 0.3s; }

    .empty { color: #666; font-style: italic; font-size: 0.85rem; }
    .error { color: #f87171; margin-bottom: 1rem; }
  `;

  override render() {
    return html`
      <div class="header">
        <h1>Trellis</h1>
        ${this._model ? html`<span class="scanned">scanned ${this._formatTime(this._model.scannedAt)}</span>` : nothing}
      </div>

      <div class="root-input">
        <input
          type="text"
          placeholder="Workspace root (e.g., ~/claude/casehub)"
          .value=${this._root}
          @input=${(e: Event) => { this._root = (e.target as HTMLInputElement).value; }}
          @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter') this._scan(); }}
        />
        <button @click=${this._scan} ?disabled=${this._loading}>
          ${this._loading ? 'Scanning...' : 'Scan'}
        </button>
      </div>

      ${this._error ? html`<div class="error">${this._error}</div>` : nothing}
      ${this._model ? this._renderModel(this._model) : html`<div class="empty">Enter a workspace root to scan.</div>`}
    `;
  }

  private _renderModel(m: WorkspaceModel) {
    return html`
      ${this._renderSlots(m.slots)}
      ${this._renderRepos(m.repos)}
      ${this._renderEpics(m.epics)}
      ${this._renderPauses(m.pauses)}
    `;
  }

  private _renderRepos(repos: RepoInfo[]) {
    return html`
      <div class="section">
        <h2>Repos <span class="count">${repos.length}</span></h2>
        ${repos.length === 0
          ? html`<div class="empty">No repos found.</div>`
          : html`<div class="grid">${repos.map(r => html`
            <div class="card">
              <div class="card-name">${r.name}</div>
              <div class="card-meta">
                <span class="badge badge-branch">${r.branch}</span>
              </div>
            </div>
          `)}</div>`}
      </div>
    `;
  }

  private _renderSlots(slots: SlotInfo[]) {
    if (slots.length === 0) return nothing;
    return html`
      <div class="section">
        <h2>Slots <span class="count">${slots.length}</span></h2>
        <div class="grid">${slots.map(s => html`
          <div class="card">
            <div class="card-name">Slot ${s.number}</div>
            <div class="card-detail">${s.issue}</div>
            <div class="card-meta">
              <span class="badge badge-status" style="background:${STATUS_COLORS[s.status] ?? '#666'}">
                ${s.status.replace('_', ' ')}
              </span>
              ${s.isEpic ? html`<span class="badge badge-epic">epic</span>` : nothing}
              ${s.repos.map(r => html`<span class="badge badge-branch">${r}</span>`)}
            </div>
          </div>
        `)}</div>
      </div>
    `;
  }

  private _renderEpics(epics: EpicInfo[]) {
    if (epics.length === 0) return nothing;
    return html`
      <div class="section">
        <h2>Epics <span class="count">${epics.length}</span></h2>
        <div class="grid">${epics.map(e => {
          const pct = e.totalChildren > 0 ? (e.completedChildren / e.totalChildren) * 100 : 0;
          return html`
            <div class="card">
              <div class="card-name">${e.issue}</div>
              <div class="card-detail">Batch ${e.currentBatch}${e.currentIssue ? ` — ${e.currentIssue}` : ''}</div>
              <div class="card-detail">${e.completedChildren}/${e.totalChildren} children</div>
              <div class="progress-bar"><div class="progress-fill" style="width:${pct}%"></div></div>
            </div>
          `;
        })}</div>
      </div>
    `;
  }

  private _renderPauses(pauses: PauseEntry[]) {
    if (pauses.length === 0) return nothing;
    return html`
      <div class="section">
        <h2>Paused <span class="count">${pauses.length}</span></h2>
        <div class="grid">${pauses.map(p => html`
          <div class="card">
            <div class="card-name">${p.branch}</div>
            <div class="card-meta">
              <span class="badge badge-pause">#${p.issue}</span>
              ${p.pausedAt ? html`<span class="card-detail">${this._formatTime(p.pausedAt)}</span>` : nothing}
            </div>
          </div>
        `)}</div>
      </div>
    `;
  }

  private _formatTime(iso: string): string {
    try {
      const d = new Date(iso);
      const now = Date.now();
      const diff = now - d.getTime();
      if (diff < 60_000) return 'just now';
      if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
      if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
      return `${Math.floor(diff / 86_400_000)}d ago`;
    } catch { return iso; }
  }

  private async _scan() {
    if (!this._root.trim()) return;
    this._loading = true;
    this._error = null;
    try {
      const res = await fetch(`/api/workspace?root=${encodeURIComponent(this._root.trim())}`);
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._error = body?.error ?? `HTTP ${res.status}`;
        return;
      }
      this._model = await res.json();
    } catch (e) {
      this._error = `Failed to scan: ${e}`;
    } finally {
      this._loading = false;
    }
  }
}
