import { LitElement, html, css, nothing } from 'lit';
import { subscribeWorkspace } from '../services/workspace-sse.js';
import './slot-detail.js';
import './repo-detail.js';
import { customElement, property, state } from 'lit/decorators.js';

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
  status: 'ACTIVE' | 'PAUSED' | 'READY' | 'LANDED' | 'STALE' | 'ABANDONED' | 'ARCHIVED';
  isEpic: boolean;
  repos: string[];
  slug: string | null;
  title: string | null;
  description: string | null;
  whatToDo: string | null;
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

interface EpicSummary {
  issueKey: string;
  title: string;
  criticalPathLength: number;
  bottleneckCount: number;
  topRecommendation: { key: string; title: string; type: string; reason: string } | null;
  progress: { total: number; open: number; closed: number };
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
  PAUSED: '#f59e0b',
  READY: '#facc15',
  LANDED: '#3b82f6',
  STALE: '#f97316',
  ABANDONED: '#ef4444',
  ARCHIVED: '#6b7280',
};

@customElement('trellis-org-dashboard')
export class TrellisOrgDashboard extends LitElement {
  @property() workspaceRoot = '';
  @state() private _model: WorkspaceModel | null = null;
  @state() private _error: string | null = null;
  @state() private _loading = false;
  @state() private _root = '';
  @state() private _portfolioData = new Map<string, EpicSummary>();
  @state() private _recentRoots: string[] = [];
  @state() private _showRecent = false;
  @state() private _modal: { type: 'slot'; slotNumber: number; label: string } | { type: 'repo'; repoName: string; label: string } | null = null;
  @state() private _hideArchived = true;
  @state() private _statusFilter: string | null = null;
  @state() private _hoverSlot: number | null = null;
  private _lastScannedRoot = '';
  private _savedScrollTop = 0;
  private _unsubWorkspace: (() => void) | null = null;

  static override styles = css`
    :host { display: block; height: 100%; overflow-y: auto; overflow-x: hidden; padding: 1.5rem; font-family: system-ui, -apple-system, sans-serif; box-sizing: border-box; }

    .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.5rem; }
    .header h1 { margin: 0; font-size: 1.4rem; font-weight: 600; }
    .scanned { font-size: 0.75rem; color: #888; }

    .root-input { display: flex; gap: 0.5rem; margin-bottom: 1.5rem; max-width: 100%; }
    .root-input input {
      flex: 1; min-width: 0; padding: 0.5rem 0.75rem; background: #2a2a2a; border: 1px solid #444;
      border-radius: 6px; color: #eee; font-family: monospace; font-size: 0.85rem;
    }
    .root-input button {
      padding: 0.5rem 1rem; background: #3b82f6; color: white; border: none;
      border-radius: 6px; cursor: pointer; font-size: 0.85rem;
    }
    .root-input button:hover { background: #2563eb; }
    .root-input button:disabled { opacity: 0.5; cursor: not-allowed; }
    .root-input .browse-btn { padding: 0.5rem 0.6rem; background: #333; }
    .root-input .browse-btn:hover { background: #444; }

    .root-wrapper { position: relative; flex: 1; display: flex; flex-direction: column; }

    .recent-list {
      position: absolute; top: 100%; left: 0; right: 0; z-index: 10;
      background: #2a2a2a; border: 1px solid #444; border-top: none;
      border-radius: 0 0 6px 6px; max-height: 200px; overflow-y: auto;
    }
    .recent-item {
      padding: 0.4rem 0.75rem; cursor: pointer; font-family: monospace;
      font-size: 0.85rem; color: #ccc;
    }
    .recent-item:hover { background: #333; color: #fff; }
    .recent-header {
      padding: 0.3rem 0.75rem; font-size: 0.7rem; color: #666;
      text-transform: uppercase; letter-spacing: 0.05em;
    }

    .section { margin-bottom: 2rem; }
    .section h2 { font-size: 1rem; font-weight: 600; margin: 0 0 0.75rem; display: flex; align-items: center; gap: 0.5rem; }
    .count { background: #333; padding: 0.15rem 0.5rem; border-radius: 10px; font-size: 0.75rem; font-weight: 400; }

    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 0.75rem; }

    .card {
      background: #252525; border: 1px solid #333; border-radius: 8px; padding: 0.75rem;
      transition: border-color 0.15s;
    }
    .card:hover { border-color: #555; }

    .card-name { font-weight: 600; font-size: 0.9rem; }
    .card-detail { font-size: 0.75rem; color: #999; font-family: monospace; margin-top: 0.2rem; }
    .card-meta { display: flex; gap: 0.4rem; margin-top: 0.3rem; flex-wrap: wrap; }

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

    .filters { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
    .pill {
      padding: 0.2rem 0.75rem; border-radius: 12px; font-size: 0.75rem;
      cursor: pointer; border: 1px solid #444; background: #2a2a2a; color: #ccc;
      transition: all 0.15s;
    }
    .pill:hover { background: #333; }
    .pill.active { background: #1e3a5f; border-color: #3b82f6; color: #93c5fd; }

    .card { position: relative; }
    .card.expanded { border-radius: 8px 8px 0 0; border-bottom-color: transparent; z-index: 10; }
    .card-slot-num { font-size: 0.7rem; color: #666; font-family: monospace; }

    .hover-extension {
      position: absolute; top: calc(100% - 1px); left: -1px; right: -1px; z-index: 10;
      background: #252525; border: 1px solid #555; border-top: 1px solid #333;
      border-radius: 0 0 8px 8px; padding: 0.5rem 0.75rem 0.75rem;
      box-shadow: 0 4px 12px rgba(0,0,0,0.3);
    }
    .hover-label { font-size: 0.65rem; color: #555; text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 0.15rem; }
    .hover-label:not(:first-child) { margin-top: 0.4rem; }
    .hover-text { font-size: 0.78rem; color: #aaa; line-height: 1.4; }

    .modal-backdrop {
      position: fixed; inset: 0; background: rgba(0,0,0,0.6); z-index: 200;
      display: flex; align-items: center; justify-content: center;
    }
    :host(.modal-open) { overflow: hidden; }
    .modal-frame {
      width: 90vw; height: 85vh; max-width: 1200px;
      background: #1e1e1e; border: 1px solid #444; border-radius: 10px;
      overflow: hidden; display: flex; flex-direction: column;
    }
    .modal-header {
      display: flex; align-items: center; gap: 0.75rem;
      padding: 0.5rem 1rem; background: #181818; border-bottom: 1px solid #333;
    }
    .modal-header .spacer { flex: 1; }
    .modal-title { font-weight: 600; font-size: 0.95rem; }
    .modal-btn {
      padding: 0.25rem 0.6rem; border: 1px solid #444; border-radius: 4px;
      background: #2a2a2a; color: #ccc; cursor: pointer; font-size: 0.75rem;
    }
    .modal-btn:hover { background: #333; }
    .modal-btn.primary { border-color: #1d4ed8; color: #93c5fd; }
    .modal-btn.primary:hover { background: #1e3a5f; }
    .modal-body { flex: 1; min-height: 0; overflow: hidden; }
    .modal-body trellis-slot-detail { height: 100%; }

    .modal-nav {
      display: flex; align-items: center; gap: 0.25rem;
    }
    .nav-btn {
      padding: 0.2rem 0.4rem; border: 1px solid #444; border-radius: 4px;
      background: #2a2a2a; color: #888; cursor: pointer; font-size: 0.85rem;
      line-height: 1; transition: all 0.15s;
    }
    .nav-btn:hover { background: #333; color: #eee; }
    .nav-btn:disabled { opacity: 0.3; cursor: default; }
    .nav-pos { font-size: 0.7rem; color: #666; padding: 0 0.3rem; font-variant-numeric: tabular-nums; }
  `;

  override render() {
    return html`
      <div class="header">
        <h1>${this._root ? this._root.split('/').pop() : 'Trellis'}</h1>
        ${this._model ? html`<span class="scanned">scanned ${this._formatTime(this._model.scannedAt)}</span>` : nothing}
      </div>

      ${this._error ? html`<div class="error">${this._error}</div>` : nothing}
      ${this._loading ? html`<div class="empty">Scanning...</div>` : nothing}
      ${!this._loading && this._model ? this._renderModel(this._model) : nothing}
      ${!this._loading && !this._model && !this._error ? html`<div class="empty">Select a space from the switcher above.</div>` : nothing}
      ${this._modal ? this._renderModal() : nothing}
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
            <div class="card" style="cursor:pointer" @click=${() => this._openRepo(r.name)}>
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
    const archivedCount = slots.filter(s => s.status === 'ARCHIVED').length;
    let filtered = this._hideArchived ? slots.filter(s => s.status !== 'ARCHIVED') : slots;
    if (this._statusFilter) {
      filtered = filtered.filter(s => s.status === this._statusFilter);
    }
    const statusPills = ['PAUSED', 'LANDED', 'STALE', 'ABANDONED'] as const;
    return html`
      <div class="section">
        <h2>Slots <span class="count">${filtered.length}${this._hideArchived && archivedCount > 0 ? ` / ${slots.length}` : ''}</span></h2>
        <div class="filters">
          <span class="pill ${this._hideArchived ? 'active' : ''}"
                @click=${() => { this._hideArchived = !this._hideArchived; }}>
            ${this._hideArchived ? 'Show archived' : 'Hide archived'} (${archivedCount})
          </span>
          ${statusPills.map(s => html`
            <span class="pill ${this._statusFilter === s ? 'active' : ''}"
                  @click=${() => { this._statusFilter = this._statusFilter === s ? null : s; }}>
              ${s.replace('_', ' ')}
            </span>
          `)}
        </div>
        <div class="grid">${filtered.map(s => html`
          <div class="card ${this._hoverSlot === s.number && (s.description || s.whatToDo) ? 'expanded' : ''}" style="cursor:pointer"
               @click=${() => this._openSlot(s.number)}
               @mouseenter=${() => { this._hoverSlot = s.number; }}
               @mouseleave=${() => { this._hoverSlot = null; }}>
            <div style="display:flex;align-items:baseline;gap:0.4rem">
              <span class="card-slot-num">#${s.number}</span>
              <span class="card-name">${s.title ?? s.slug ?? `Slot ${s.number}`}</span>
            </div>
            <div class="card-detail">${s.issue}</div>
            <div class="card-meta">
              <span class="badge badge-status" style="background:${STATUS_COLORS[s.status] ?? '#666'}">
                ${s.status.replace('_', ' ')}
              </span>
              ${s.isEpic ? html`<span class="badge badge-epic">epic</span>` : nothing}
              ${s.repos.map(r => html`<span class="badge badge-branch">${r}</span>`)}
            </div>
            ${this._hoverSlot === s.number && (s.description || s.whatToDo) ? html`
              <div class="hover-extension">
                ${s.description ? html`
                  <div class="hover-label">Description</div>
                  <div class="hover-text">${s.description}</div>
                ` : nothing}
                ${s.whatToDo ? html`
                  <div class="hover-label">What to do</div>
                  <div class="hover-text">${s.whatToDo}</div>
                ` : nothing}
              </div>
            ` : nothing}
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
          const summary = this._portfolioData.get(e.issue);
          return html`
            <div class="card" style="cursor:pointer" @click=${() => this._openEpic(e.issue)}>
              <div class="card-name">${e.issue}</div>
              <div class="card-detail">Batch ${e.currentBatch}${e.currentIssue ? ` — ${e.currentIssue}` : ''}</div>
              <div class="card-detail">${e.completedChildren}/${e.totalChildren} children</div>
              ${summary ? html`
                <div class="card-meta">
                  <span class="badge badge-branch">CP: ${summary.criticalPathLength}</span>
                  ${summary.bottleneckCount > 0 ? html`
                    <span class="badge badge-pause">BN: ${summary.bottleneckCount}</span>
                  ` : nothing}
                </div>
                ${summary.topRecommendation ? html`
                  <div class="card-detail" style="margin-top:0.3rem;color:#93c5fd;font-size:0.75rem">
                    Next: ${summary.topRecommendation.title}
                  </div>
                ` : nothing}
              ` : nothing}
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

  private _openSlot(slotNumber: number) {
    const slot = this._model?.slots.find(s => s.number === slotNumber);
    this._modal = { type: 'slot', slotNumber, label: slot?.title ?? slot?.slug ?? `Slot ${slotNumber}` };
    this._showModal();
  }

  private _showModal() {
    this._savedScrollTop = this.scrollTop;
    this.classList.add('modal-open');
    this.scrollTop = 0;
  }

  private _closeModal() {
    this._modal = null;
    this.classList.remove('modal-open');
    requestAnimationFrame(() => { this.scrollTop = this._savedScrollTop; });
  }

  private _openInWorkspace() {
    if (!this._modal) return;
    const m = this._modal;
    this._modal = null;
    if (m.type === 'slot') {
      location.hash = `#slot/${m.slotNumber}?root=${encodeURIComponent(this._root)}`;
    } else {
      location.hash = `#repo/${encodeURIComponent(m.repoName)}?root=${encodeURIComponent(this._root)}`;
    }
  }

  private _openRepo(name: string) {
    this._modal = { type: 'repo', repoName: name, label: name };
    this._showModal();
  }

  override connectedCallback() {
    super.connectedCallback();
    this._loadRecent();
    if (this.workspaceRoot && this.workspaceRoot !== this._lastScannedRoot) {
      this._root = this.workspaceRoot;
      this._scan();
    }
    this._unsubWorkspace = subscribeWorkspace(
      ['workspace:repos', 'workspace:slots', 'workspace:lifecycle'],
      () => { if (this._root) this._scan(); }
    );
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this._unsubWorkspace?.();
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('workspaceRoot') && this.workspaceRoot && this.workspaceRoot !== this._lastScannedRoot) {
      this._root = this.workspaceRoot;
      this._scan();
    }
  }

  private _hasBrowse(): boolean {
    return typeof (window as any).trellis?.openFolderDialog === 'function';
  }

  private async _browse() {
    const path = await (window as any).trellis.openFolderDialog();
    if (path) {
      this._root = path;
      this._scan();
    }
  }

  private _loadRecent() {
    try {
      const stored = localStorage.getItem('trellis:recent-roots');
      this._recentRoots = stored ? JSON.parse(stored) : [];
    } catch { this._recentRoots = []; }
  }

  private _saveRecent(root: string) {
    const filtered = this._recentRoots.filter(r => r !== root);
    this._recentRoots = [root, ...filtered].slice(0, 5);
    localStorage.setItem('trellis:recent-roots', JSON.stringify(this._recentRoots));
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
      this._lastScannedRoot = this._root.trim();
      this._saveRecent(this._root.trim());
      this._fetchPortfolio();
    } catch (e) {
      this._error = `Failed to scan: ${e}`;
    } finally {
      this._loading = false;
    }
  }

  private _fetchPortfolio() {
    if (!this._model?.epics.length) return;
    const repos = new Set<string>();
    for (const e of this._model.epics) {
      const m = e.issue.match(/^([^/]+\/[^#]+)#/);
      if (m) repos.add(m[1]);
    }
    for (const ownerRepo of repos) {
      const [owner, repo] = ownerRepo.split('/');
      fetch(`/api/repos/${owner}/${repo}/portfolio`)
        .then(r => r.ok ? r.json() : [])
        .then((summaries: EpicSummary[]) => {
          for (const s of summaries) {
            this._portfolioData.set(s.issueKey, s);
          }
          this._portfolioData = new Map(this._portfolioData);
        })
        .catch(() => {});
    }
  }

  private _getNavList(): { id: string; label: string }[] {
    if (!this._modal || !this._model) return [];
    if (this._modal.type === 'slot') {
      const filtered = this._hideArchived
        ? this._model.slots.filter(s => s.status !== 'ARCHIVED')
        : this._model.slots;
      return filtered.map(s => ({ id: String(s.number), label: s.title ?? s.slug ?? `Slot ${s.number}` }));
    } else {
      return this._model.repos.map(r => ({ id: r.name, label: r.name }));
    }
  }

  private _navTo(index: number) {
    if (!this._modal) return;
    const list = this._getNavList();
    if (index < 0 || index >= list.length) return;
    const item = list[index];
    if (this._modal.type === 'slot') {
      this._modal = { type: 'slot', slotNumber: parseInt(item.id), label: item.label };
    } else {
      this._modal = { type: 'repo', repoName: item.id, label: item.label };
    }
  }

  private _renderModal() {
    const m = this._modal!;
    const list = this._getNavList();
    const currentId = m.type === 'slot' ? String(m.slotNumber) : m.repoName;
    const idx = list.findIndex(i => i.id === currentId);
    const total = list.length;

    return html`
      <div class="modal-backdrop" @click=${this._closeModal}>
        <div class="modal-frame" @click=${(e: Event) => e.stopPropagation()}>
          <div class="modal-header">
            <div class="modal-nav">
              <button class="nav-btn" ?disabled=${idx <= 0} @click=${() => this._navTo(0)} title="First">⏮</button>
              <button class="nav-btn" ?disabled=${idx <= 0} @click=${() => this._navTo(idx - 1)} title="Previous">◀</button>
              <span class="nav-pos">${idx + 1} / ${total}</span>
              <button class="nav-btn" ?disabled=${idx >= total - 1} @click=${() => this._navTo(idx + 1)} title="Next">▶</button>
              <button class="nav-btn" ?disabled=${idx >= total - 1} @click=${() => this._navTo(total - 1)} title="Last">⏭</button>
            </div>
            <span class="modal-title">${m.label}</span>
            <span class="spacer"></span>
            <button class="modal-btn primary" @click=${() => this._openInWorkspace()}>
              Open in workspace
            </button>
            <button class="modal-btn" @click=${this._closeModal}>✕</button>
          </div>
          <div class="modal-body">
            ${m.type === 'slot'
              ? html`<trellis-slot-detail
                  .slotNumber=${m.slotNumber}
                  .workspaceRoot=${this._root}
                  .modal=${true}
                ></trellis-slot-detail>`
              : html`<trellis-repo-detail
                  .repoName=${m.repoName}
                  .workspaceRoot=${this._root}
                  .modal=${true}
                ></trellis-repo-detail>`}
          </div>
        </div>
      </div>
    `;
  }

  private _openEpic(issueKey: string) {
    const m = issueKey.match(/^([^/]+)\/([^#]+)#(\d+)$/);
    if (m) {
      location.hash = `#epic/${m[1]}/${m[2]}/${m[3]}?root=${encodeURIComponent(this._root)}`;
    }
  }
}
