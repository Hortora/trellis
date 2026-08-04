import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { fromRows, ColumnType, columnId } from '@casehubio/pages-data';
import type { TypedDataSet, TypedRow, CellValue, ColumnId } from '@casehubio/pages-data';
import type { ColumnRenderer, TableColumnConfig } from '@casehubio/pages-table';
import '@casehubio/pages-table';
import './agent-status-badge';

interface Terminal {
  name: string;
  workingDir: string | null;
  slot: string | null;
  repo: string | null;
  issue: string | null;
}

interface Process {
  pid: number;
  state: string;
  memoryBytes: number;
  startedAt: string | null;
  command: string | null;
}

interface Snapshot {
  terminalName: string;
  terminal: Terminal;
  process: Process | null;
  lastError: string | null;
}

interface ProcessEntry {
  pid: number;
  ppid: number;
  rssBytes: number;
  command: string;
}

interface TreeResponse {
  rootPid: number;
  totalBytes: number;
  processes: ProcessEntry[];
}

interface RepoInfo {
  name: string;
  path: string;
  branch: string;
  remoteUrl: string | null;
}

interface SlotInfo {
  number: number;
  path: string;
  issue: string | null;
  status: string;
  repos: string[];
}

interface AvailableItem {
  key: string;
  type: 'repo' | 'slot';
  name: string;
  path: string;
  slot: string | null;
  repo: string;
}

const COL = {
  type: columnId('type'), slot: columnId('slot'), repo: columnId('repo'),
  state: columnId('state'), memory: columnId('memory'), actions: columnId('actions'),
  key: columnId('key'), path: columnId('path'),
} as const;

@customElement('trellis-memory-panel')
export class TrellisMemoryPanel extends LitElement {

  @property() workspaceRoot = '';

  @state() private _snapshots: Snapshot[] = [];
  @state() private _available: AvailableItem[] = [];
  @state() private _selectedKeys: string[] = [];
  @state() private _availableSelectedKeys: string[] = [];
  @state() private _activeRow: string | null = null;
  @state() private _tree: TreeResponse | null = null;
  @state() private _actionInProgress: string | null = null;
  @state() private _showAvailable = true;

  private _eventSource: EventSource | null = null;

  override connectedCallback() {
    super.connectedCallback();
    this._load();
    this._loadWorkspace();
    this._eventSource = new EventSource('/api/push?topics=agent:state');
    this._eventSource.onmessage = () => this._load();
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this._eventSource?.close();
    this._eventSource = null;
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('workspaceRoot') && this.workspaceRoot) {
      this._load();
      this._loadWorkspace();
    }
  }

  private async _load() {
    try {
      const res = await fetch('/api/terminals');
      if (!res.ok) return;
      const data: Snapshot[] = await res.json();
      if (this._snapshotsEqual(data)) return;
      this._snapshots = data;
    } catch { /* ignore */ }
  }

  private _snapshotsEqual(next: Snapshot[]): boolean {
    const prev = this._snapshots;
    if (prev.length !== next.length) return false;
    for (let i = 0; i < prev.length; i++) {
      if (prev[i].terminalName !== next[i].terminalName) return false;
      if ((prev[i].process?.state ?? '') !== (next[i].process?.state ?? '')) return false;
      if ((prev[i].process?.memoryBytes ?? 0) !== (next[i].process?.memoryBytes ?? 0)) return false;
    }
    return true;
  }

  private async _loadWorkspace() {
    if (!this.workspaceRoot) return;
    try {
      const res = await fetch(`/api/workspace?root=${encodeURIComponent(this.workspaceRoot)}`);
      if (!res.ok) return;
      const data = await res.json();
      const items: AvailableItem[] = [];
      for (const repo of (data.repos ?? [])) {
        items.push({ key: `repo-${repo.name}`, type: 'repo', name: repo.name, path: repo.path, slot: null, repo: repo.name });
      }
      for (const slot of (data.slots ?? [])) {
        for (const repoName of (slot.repos ?? [])) {
          const repo = (data.repos ?? []).find((r: RepoInfo) => r.name === repoName);
          if (repo) {
            items.push({ key: `slot-${slot.number}-${repoName}`, type: 'slot', name: `${repoName} (slot ${slot.number})`, path: repo.path, slot: String(slot.number), repo: repoName });
          }
        }
      }
      this._available = items;
    } catch { /* ignore */ }
  }

  private _availableItems(): AvailableItem[] {
    const terminalKeys = new Set(this._snapshots.map(s => {
      if (s.terminal.slot) return `slot-${s.terminal.slot}-${s.terminal.repo}`;
      return `repo-${s.terminal.repo}`;
    }));
    return this._available.filter(a => !terminalKeys.has(a.key));
  }

  private _totalMemory(snapshots: Snapshot[]): number {
    return snapshots.reduce((sum, s) => sum + (s.process?.memoryBytes ?? 0), 0);
  }

  private _buildActiveDataSet(): TypedDataSet {
    return fromRows(this._snapshots, [
      { id: COL.key, type: ColumnType.TEXT, getValue: s => s.terminalName },
      { id: COL.type, type: ColumnType.LABEL, getValue: s => s.terminal.slot ? 'slot' : 'repo' },
      { id: COL.slot, type: ColumnType.TEXT, getValue: s => s.terminal.slot ?? '—' },
      { id: COL.repo, type: ColumnType.TEXT, getValue: s => s.terminal.repo ?? s.terminalName },
      { id: COL.state, type: ColumnType.LABEL, getValue: s => s.process?.state ?? 'IDLE' },
      { id: COL.memory, type: ColumnType.NUMBER, getValue: s => s.process?.memoryBytes ?? 0 },
      { id: COL.actions, type: ColumnType.TEXT, getValue: s => s.terminalName },
    ]);
  }

  private _buildAvailableDataSet(): TypedDataSet {
    return fromRows(this._availableItems(), [
      { id: COL.key, type: ColumnType.TEXT, getValue: a => a.key },
      { id: COL.type, type: ColumnType.LABEL, getValue: a => a.type },
      { id: COL.slot, type: ColumnType.TEXT, getValue: a => a.slot ?? '—' },
      { id: COL.repo, type: ColumnType.TEXT, getValue: a => a.repo },
      { id: COL.actions, type: ColumnType.TEXT, getValue: a => a.key },
    ]);
  }

  private get _activeColumnConfig(): TableColumnConfig[] {
    return [
      { id: COL.type, label: 'Type', width: '1.5fr' },
      { id: COL.slot, label: 'Slot', width: '1fr' },
      { id: COL.repo, label: 'Repo', width: '2fr' },
      { id: COL.state, label: 'State', width: '1.5fr' },
      { id: COL.memory, label: 'Memory', width: '1.5fr' },
      { id: COL.actions, label: 'Actions', width: '3fr' },
    ];
  }

  private get _availableColumnConfig(): TableColumnConfig[] {
    return [
      { id: COL.type, label: 'Type', width: '1.5fr' },
      { id: COL.slot, label: 'Slot', width: '1fr' },
      { id: COL.repo, label: 'Repo', width: '2fr' },
      { id: COL.actions, label: '', width: '1.5fr' },
    ];
  }

  private _pill(value: string, colorMap: Record<string, string>) {
    const bg = colorMap[value] ?? '#374151';
    return html`<span style="font-size:10px; padding:2px 8px; border-radius:3px; color:#fff; background:${bg}; display:inline-block; min-width:55px; text-align:center;">${value}</span>`;
  }

  private static TYPE_COLORS: Record<string, string> = { slot: '#166534', repo: '#1e3a5f' };
  private static STATE_COLORS: Record<string, string> = { RUNNING: '#166534', STARTING: '#1e3a5f', PAUSED: '#854d0e', IDLE: '#374151', PAUSED_BY_COORDINATOR: '#854d0e' };

  private get _activeRenderers(): ReadonlyMap<ColumnId, ColumnRenderer> {
    const self = this;
    return new Map<ColumnId, ColumnRenderer>([
      [COL.type, (cell) => self._pill((cell as any).value, TrellisMemoryPanel.TYPE_COLORS)],
      [COL.repo, (_cell, row) => {
        const repo = (row.cell(COL.repo) as any).value as string;
        const slot = (row.cell(COL.slot) as any).value as string;
        const href = slot && slot !== '—'
          ? `#slot/${slot}?root=${encodeURIComponent(self.workspaceRoot)}`
          : `#repo/${repo}?root=${encodeURIComponent(self.workspaceRoot)}`;
        return html`<a href="${href}" style="color:#60a5fa; text-decoration:none;" @click=${(e: Event) => e.stopPropagation()}>${repo}</a>`;
      }],
      [COL.state, (cell) => self._pill((cell as any).value, TrellisMemoryPanel.STATE_COLORS)],
      [COL.memory, (cell) => {
        const bytes = (cell as any).value as number;
        const mb = Math.round(bytes / (1024 * 1024));
        const color = mb > 1000 ? '#f87171' : mb > 500 ? '#fbbf24' : '#9ca3af';
        const weight = mb > 500 ? '600' : 'normal';
        return html`<span style="font-family:monospace; color:${color}; font-weight:${weight}">${self._formatBytes(bytes)}</span>`;
      }],
      [COL.actions, (_cell, row) => {
        const name = row.text(COL.key);
        const state = row.text(COL.state);
        const isRunning = state === 'RUNNING';
        const isPaused = state === 'PAUSED' || state === 'PAUSED_BY_COORDINATOR';
        const busy = self._actionInProgress === name;
        const btnBase = 'padding:2px 6px; border-radius:3px; font-size:11px; cursor:pointer; margin-right:4px;';
        const btnNorm = btnBase + 'border:1px solid #444; background:#2a2a2a; color:#ccc;';
        const btnDanger = btnBase + 'border:1px solid #991b1b; background:#2a2a2a; color:#fca5a5;';
        return html`
          ${isRunning ? html`<button style="${btnNorm}" ?disabled=${busy} @click=${(e: Event) => { e.stopPropagation(); self._pause(name); }}>Pause</button>` : nothing}
          ${isPaused ? html`<button style="${btnNorm}" ?disabled=${busy} @click=${(e: Event) => { e.stopPropagation(); self._resume(name); }}>Resume</button>` : nothing}
          <button style="${btnDanger}" ?disabled=${busy} @click=${(e: Event) => { e.stopPropagation(); self._terminate(name); }}>Terminate</button>
        `;
      }],
    ]);
  }

  private get _availableRenderers(): ReadonlyMap<ColumnId, ColumnRenderer> {
    const self = this;
    return new Map<ColumnId, ColumnRenderer>([
      [COL.type, (cell) => self._pill((cell as any).value, TrellisMemoryPanel.TYPE_COLORS)],
      [COL.repo, (_cell, row) => {
        const repo = (row.cell(COL.repo) as any).value as string;
        const slot = (row.cell(COL.slot) as any).value as string;
        const href = slot && slot !== '—'
          ? `#slot/${slot}?root=${encodeURIComponent(self.workspaceRoot)}`
          : `#repo/${repo}?root=${encodeURIComponent(self.workspaceRoot)}`;
        return html`<a href="${href}" style="color:#60a5fa; text-decoration:none;">${repo}</a>`;
      }],
      [COL.actions, (_cell, row) => {
        const key = row.text(COL.key);
        const item = self._availableItems().find(a => a.key === key);
        if (!item) return nothing;
        const busy = self._actionInProgress === key;
        return html`<button style="padding:2px 6px; border-radius:3px; font-size:11px; cursor:pointer; border:1px solid #1d4ed8; background:#2a2a2a; color:#93c5fd;" ?disabled=${busy} @click=${(e: Event) => { e.stopPropagation(); self._startTerminal(item); }}>Start</button>`;
      }],
    ]);
  }

  private _formatBytes(bytes: number): string {
    if (bytes === 0) return '0 MB';
    if (bytes >= 1024 * 1024 * 1024) return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
    return Math.round(bytes / (1024 * 1024)) + ' MB';
  }

  private _getRowKey = (row: TypedRow) => row.text(COL.key);

  private _handleActiveSelection = (e: CustomEvent) => {
    this._selectedKeys = [...e.detail.selectedKeys];
  };

  private _handleAvailableSelection = (e: CustomEvent) => {
    this._availableSelectedKeys = [...e.detail.selectedKeys];
  };

  private _handleRowActivate = (e: CustomEvent) => {
    const name = e.detail.row.text(COL.key);
    if (this._activeRow === name) {
      this._activeRow = null;
      this._tree = null;
      return;
    }
    this._activeRow = name;
    fetch(`/api/terminals/${name}/agent/tree`)
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data) this._tree = data; })
      .catch(() => {});
  };

  private async _pause(name: string) {
    this._actionInProgress = name;
    try {
      await fetch(`/api/terminals/${name}/agent/pause`, { method: 'POST' });
      await this._load();
    } finally { this._actionInProgress = null; }
  }

  private async _resume(name: string) {
    this._actionInProgress = name;
    try {
      await fetch(`/api/terminals/${name}/agent/resume`, { method: 'POST' });
      await this._load();
    } finally { this._actionInProgress = null; }
  }

  private async _terminate(name: string) {
    this._actionInProgress = name;
    try {
      await fetch(`/api/terminals/${name}`, { method: 'DELETE' });
      const next = new Set(this._selected);
      next.delete(name);
      this._selected = next;
      if (this._activeRow === name) { this._activeRow = null; this._tree = null; }
      await this._load();
    } finally { this._actionInProgress = null; }
  }

  private async _startTerminal(item: AvailableItem) {
    this._actionInProgress = item.key;
    try {
      const termName = item.slot ? `slot-${item.slot}-${item.repo}` : `repo-${item.repo}`;
      await fetch('/api/terminals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: termName, workingDir: item.path, slot: item.slot, repo: item.repo, agent: {} }),
      });
      const next = new Set(this._availableSelected);
      next.delete(item.key);
      this._availableSelected = next;
      await this._load();
    } finally { this._actionInProgress = null; }
  }

  private async _bulkAction(action: 'pause' | 'resume' | 'terminate') {
    const names = [...this._selectedKeys];
    for (const name of names) {
      if (action === 'pause') await this._pause(name);
      else if (action === 'resume') await this._resume(name);
      else await this._terminate(name);
    }
    this._selectedKeys = [];
  }

  private async _bulkStart() {
    const items = this._availableItems().filter(a => this._availableSelectedKeys.includes(a.key));
    for (const item of items) {
      await this._startTerminal(item);
    }
    this._availableSelectedKeys = [];
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
    .header .total { font-size: 0.85rem; color: #9ca3af; font-family: monospace; }

    .bulk-actions { display: flex; gap: 0.5rem; }
    .bulk-btn {
      padding: 0.25rem 0.6rem; border: 1px solid #444; border-radius: 4px;
      background: #2a2a2a; color: #666; cursor: not-allowed; font-size: 0.75rem;
    }
    .bulk-btn.enabled { color: #ccc; cursor: pointer; }
    .bulk-btn.enabled:hover { background: #333; }
    .bulk-btn.enabled.danger { border-color: #991b1b; color: #fca5a5; }
    .bulk-btn.enabled.danger:hover { background: #450a0a; }
    .bulk-btn.enabled.primary { border-color: #1d4ed8; color: #93c5fd; }
    .bulk-btn.enabled.primary:hover { background: #1e3a5f; }

    .type-badge {
      display: inline-flex; padding: 0.1rem 0.4rem; border-radius: 3px;
      font-size: 0.7rem; font-weight: 500;
    }
    .type-slot { background: #166534; color: #86efac; }
    .type-repo { background: #1e3a5f; color: #93c5fd; }

    .memory { font-family: monospace; white-space: nowrap; }
    .memory.high { color: #fbbf24; font-weight: 600; }
    .memory.critical { color: #f87171; font-weight: 600; }

    .action-btn {
      padding: 0.2rem 0.5rem; border: 1px solid #444; border-radius: 3px;
      background: #2a2a2a; color: #ccc; cursor: pointer; font-size: 0.7rem;
      margin-right: 0.25rem;
    }
    .action-btn:hover { background: #333; }
    .action-btn:disabled { opacity: 0.3; cursor: not-allowed; }
    .action-btn.danger { border-color: #991b1b; color: #fca5a5; }
    .action-btn.danger:hover { background: #450a0a; }
    .action-btn.primary { border-color: #1d4ed8; color: #93c5fd; }
    .action-btn.primary:hover { background: #1e3a5f; }

    .footer {
      padding: 0.5rem 1rem; background: #1a1a1a; border-top: 1px solid #333;
      font-size: 0.8rem; color: #9ca3af; flex-shrink: 0;
    }

    .sidebar {
      width: 300px; background: #1e1e1e; border-left: 1px solid #333;
      padding: 1rem; overflow-y: auto; flex-shrink: 0;
    }
    .sidebar h3 {
      margin: 0 0 0.75rem; font-size: 0.85rem; font-weight: 600;
      color: #aaa; text-transform: uppercase; letter-spacing: 0.05em;
    }
    .process-entry {
      display: flex; justify-content: space-between; align-items: baseline;
      padding: 0.3rem 0; font-size: 0.75rem; border-bottom: 1px solid #262626;
    }
    .process-entry.child { padding-left: 1rem; }
    .process-cmd { color: #ccc; font-family: monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 180px; }
    .process-mem { color: #9ca3af; font-family: monospace; white-space: nowrap; }
    .process-pid { color: #666; font-size: 0.7rem; margin-right: 0.5rem; }
    .tree-total {
      margin-top: 0.75rem; padding-top: 0.5rem; border-top: 1px solid #444;
      font-size: 0.8rem; font-family: monospace; color: #ccc;
      display: flex; justify-content: space-between;
    }
    .sidebar-placeholder { color: #555; font-size: 0.8rem; font-style: italic; }

    .section-header {
      display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0.5rem;
      flex-shrink: 0;
    }
    .section-header h3 {
      margin: 0; font-size: 0.85rem; font-weight: 600; color: #aaa;
      text-transform: uppercase; letter-spacing: 0.05em;
    }
    .section-header .count {
      font-size: 0.75rem; color: #666; background: #2a2a2a;
      padding: 0.1rem 0.4rem; border-radius: 3px;
    }
    .toggle-btn {
      padding: 0.15rem 0.4rem; border: 1px solid #444; border-radius: 3px;
      background: #2a2a2a; color: #888; cursor: pointer; font-size: 0.7rem;
    }
    .toggle-btn:hover { color: #ccc; }

    .empty { color: #666; padding: 2rem; text-align: center; font-style: italic; }

    pages-data-table { flex-shrink: 0; }
    .table-section { flex-shrink: 0; overflow: hidden; }
  `;

  override render() {
    const hasSelection = this._selectedKeys.length > 0;
    const selectedMemory = this._totalMemory(this._snapshots.filter(s => this._selectedKeys.includes(s.terminalName)));
    const totalMemory = this._totalMemory(this._snapshots);
    const availableItems = this._availableItems();
    const hasAvailableSelection = this._availableSelectedKeys.length > 0;

    return html`
      <div class="main">
        <div class="header">
          <h2>Memory</h2>
          <div class="bulk-actions">
            <button class="bulk-btn ${hasSelection ? 'enabled' : ''}"
                    ?disabled=${!hasSelection || !!this._actionInProgress}
                    @click=${() => this._bulkAction('pause')}>Pause Selected</button>
            <button class="bulk-btn ${hasSelection ? 'enabled' : ''}"
                    ?disabled=${!hasSelection || !!this._actionInProgress}
                    @click=${() => this._bulkAction('resume')}>Resume Selected</button>
            <button class="bulk-btn ${hasSelection ? 'enabled danger' : ''}"
                    ?disabled=${!hasSelection || !!this._actionInProgress}
                    @click=${() => this._bulkAction('terminate')}>Terminate Selected</button>
          </div>
          <span class="spacer"></span>
          <span class="total">Total: ${this._formatBytes(totalMemory)} across ${this._snapshots.length} terminals</span>
        </div>

        ${this._snapshots.length === 0
          ? html`<div class="empty">No terminal sessions.</div>`
          : html`
            <div class="table-section" style="max-height: ${Math.min(this._snapshots.length * 32 + 40, 400)}px">
              <pages-data-table
                .embedded=${true}
                mode="paginated"
                .pageSize=${100}
                .dataSet=${this._buildActiveDataSet()}
                .columnConfig=${this._activeColumnConfig}
                .columnRenderers=${this._activeRenderers}
                .sortable=${true}
                .clientSort=${true}
                selection="multi"
                .getRowKey=${this._getRowKey}
                .selectedKeys=${this._selectedKeys}
                .hiddenColumns=${[COL.key] as any}
                .emptyMessage=${'No terminal sessions.'}
                @selection-change=${this._handleActiveSelection}
                @row-activate=${this._handleRowActivate}
              ></pages-data-table>
            </div>
          `}

        ${hasSelection ? html`
          <div class="footer">
            Selected: ${this._formatBytes(selectedMemory)} (${this._selectedKeys.length} of ${this._snapshots.length} terminals)
          </div>
        ` : nothing}

        ${availableItems.length > 0 ? html`
          <div class="section-header">
            <h3>Available</h3>
            <span class="count">${availableItems.length}</span>
            <button class="toggle-btn" @click=${() => { this._showAvailable = !this._showAvailable; }}>
              ${this._showAvailable ? 'Hide' : 'Show'}
            </button>
            <button class="bulk-btn ${hasAvailableSelection ? 'enabled primary' : ''}"
                    ?disabled=${!hasAvailableSelection || !!this._actionInProgress}
                    @click=${() => this._bulkStart()}>Start Selected${hasAvailableSelection ? ` (${this._availableSelectedKeys.length})` : ''}</button>
          </div>
          ${this._showAvailable ? html`
            <pages-data-table
              .embedded=${true}
              mode="paginated"
              .pageSize=${100}
              .dataSet=${this._buildAvailableDataSet()}
              .columnConfig=${this._availableColumnConfig}
              .columnRenderers=${this._availableRenderers}
              selection="multi"
              .getRowKey=${this._getRowKey}
              .selectedKeys=${this._availableSelectedKeys}
              .hiddenColumns=${[COL.key] as any}
              .emptyMessage=${'No available repos.'}
              @selection-change=${this._handleAvailableSelection}
            ></pages-data-table>
          ` : nothing}
        ` : nothing}
      </div>

      <div class="sidebar">
        ${this._activeRow
          ? this._tree
            ? this._renderTreeContent()
            : html`<h3>Process Tree</h3><div class="sidebar-placeholder">Loading...</div>`
          : html`<h3>Process Tree</h3><div class="sidebar-placeholder">Click a row to inspect its process tree.</div>`
        }
      </div>
    `;
  }

  private _renderTreeContent() {
    const tree = this._tree!;
    const rootPid = tree.rootPid;
    return html`
      <h3>Process Tree</h3>
      ${tree.processes.length === 0
        ? html`<div class="sidebar-placeholder">No processes running.</div>`
        : html`
          ${tree.processes.map(p => html`
            <div class="process-entry ${p.ppid !== rootPid && p.pid !== rootPid ? 'child' : ''}">
              <div>
                <span class="process-pid">${p.pid}</span>
                <span class="process-cmd" title=${p.command}>${p.command.split('/').pop()?.split(' ')[0] ?? p.command}</span>
              </div>
              <span class="process-mem">${this._formatBytes(p.rssBytes)}</span>
            </div>
          `)}
          <div class="tree-total">
            <span>Total</span>
            <span>${this._formatBytes(tree.totalBytes)}</span>
          </div>
        `}
    `;
  }
}
