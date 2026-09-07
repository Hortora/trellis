import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import '../components/terminal-tab-group';
import '../components/agent-status-badge';
import { subscribeWorkspace } from '../services/workspace-sse.js';

interface PlanItem {
  ref: string;
  title: string;
  done: boolean;
  active: boolean;
  group?: boolean;
  children?: PlanItem[];
}

interface PlanBatch {
  name: string | null;
  items: PlanItem[];
}

interface PlanProgress {
  batches: PlanBatch[];
  activeIssue: string | null;
  completed: number;
  total: number;
}

interface SlotInfo {
  number: number;
  path: string;
  issue: string;
  status: string;
  isEpic: boolean;
  repos: string[];
  slug: string | null;
  title: string | null;
  covers: number[];
  planProgress?: PlanProgress;
}

interface RepoInfo {
  name: string;
  branch: string;
}

interface AgentProcess {
  pid: number;
  state: string;
  memoryBytes: number;
  startedAt: string | null;
  command: string | null;
}

interface AgentSnapshot {
  terminalName: string;
  terminal: {
    name: string;
    workingDir: string | null;
    slot: string | null;
    repo: string | null;
    issue: string | null;
  };
  process: AgentProcess | null;
  lastError: string | null;
}

@customElement('trellis-slot-detail')
export class TrellisSlotDetail extends LitElement {

  @property({ type: Number }) slotNumber = 0;
  @property() workspaceRoot = '';
  @property({ type: Boolean }) modal = false;

  @state() private _slot: SlotInfo | null = null;
  @state() private _snapshots: AgentSnapshot[] = [];
  @state() private _repoInfos: RepoInfo[] = [];
  @state() private _error: string | null = null;
  @state() private _actionInProgress: string | null = null;
  @state() private _evictionCandidates: Set<string> = new Set();
  @state() private _totalAgentMemoryMb = 0;
  private _eventSource: EventSource | null = null;
  private _unsubWorkspace: (() => void) | null = null;

  static override styles = css`
    :host { display: flex; height: 100%; font-family: system-ui, -apple-system, sans-serif; }

    .main { flex: 1; display: flex; flex-direction: column; min-width: 0; }

    .toolbar {
      display: flex; align-items: center; gap: 0.75rem; padding: 0.5rem 1rem;
      background: #1a1a1a; border-bottom: 1px solid #333; flex-shrink: 0;
    }
    .toolbar h2 { margin: 0; font-size: 1rem; font-weight: 600; }
    .toolbar .issue-ref { color: #888; font-size: 0.85rem; font-family: monospace; }
    .toolbar .spacer { flex: 1; }

    .action-btn {
      padding: 0.3rem 0.75rem; border: 1px solid #444; border-radius: 4px;
      background: #2a2a2a; color: #ccc; cursor: pointer; font-size: 0.75rem;
      transition: background 0.15s;
    }
    .action-btn:hover { background: #333; }
    .action-btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .action-btn.danger { border-color: #991b1b; color: #fca5a5; }
    .action-btn.danger:hover { background: #450a0a; }
    .action-btn.primary { border-color: #1d4ed8; color: #93c5fd; }
    .action-btn.primary:hover { background: #1e3a5f; }

    .terminal-area { flex: 1; min-height: 0; }

    .sidebar {
      width: 280px; background: #1e1e1e; border-left: 1px solid #333;
      padding: 1rem; overflow-y: auto; flex-shrink: 0;
    }
    .sidebar h3 { margin: 0 0 0.5rem; font-size: 0.85rem; font-weight: 600; color: #aaa; text-transform: uppercase; letter-spacing: 0.05em; }
    .sidebar-section { margin-bottom: 1.5rem; }

    .meta-item { font-size: 0.8rem; color: #999; margin-bottom: 0.3rem; }
    .meta-value { color: #ccc; font-family: monospace; }

    .badge {
      display: inline-flex; padding: 0.1rem 0.5rem; border-radius: 4px;
      font-size: 0.7rem; font-weight: 500;
    }
    .badge-active { background: #166534; color: #86efac; }
    .badge-ready { background: #854d0e; color: #fde68a; }
    .badge-epic { background: #4c1d95; color: #c4b5fd; }

    .repo-list { list-style: none; padding: 0; margin: 0; }
    .repo-list li { font-size: 0.8rem; color: #ccc; padding: 0.2rem 0; font-family: monospace; }

    .plan-batch { margin-bottom: 0.75rem; }
    .plan-batch-header {
      font-size: 0.7rem; color: #777; text-transform: uppercase;
      letter-spacing: 0.03em; margin-bottom: 0.3rem; margin-top: 0.5rem;
    }
    .plan-item {
      display: flex; align-items: baseline; gap: 0.4rem;
      font-size: 0.8rem; padding: 0.1rem 0;
    }
    .plan-item-done { color: #666; }
    .plan-item-active { color: #e5e5e5; }
    .plan-item-pending { color: #555; }
    .plan-icon-done { color: #86efac; }
    .plan-icon-active { color: #93c5fd; }
    .plan-icon-pending { color: #555; }
    .plan-ref { font-family: monospace; font-size: 0.7rem; flex-shrink: 0; }
    .plan-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .plan-summary {
      font-size: 0.75rem; color: #888; padding-top: 0.5rem;
      margin-top: 0.5rem; border-top: 1px solid #333;
    }

    .error { color: #f87171; padding: 1rem; }
    .loading { color: #666; padding: 2rem; text-align: center; }

    .pressure-banner {
      display: flex; align-items: center; gap: 0.5rem; padding: 0.4rem 1rem;
      background: #450a0a; border-bottom: 1px solid #991b1b;
      font-size: 0.8rem; color: #fca5a5;
    }
    .evict-btn {
      padding: 0.15rem 0.5rem; border: 1px solid #991b1b; border-radius: 3px;
      background: #7f1d1d; color: #fca5a5; cursor: pointer; font-size: 0.7rem;
    }
    .evict-btn:hover { background: #991b1b; }
  `;

  private _lastSlotNumber = -1;

  override connectedCallback() {
    super.connectedCallback();
    this._lastSlotNumber = this.slotNumber;
    this._loadSlot();
    this._loadTerminals();
    this._subscribeEvents();
    this._unsubWorkspace = subscribeWorkspace(
      ['workspace:slots'],
      () => this._loadSlot()
    );
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('slotNumber') && this.slotNumber !== this._lastSlotNumber) {
      this._lastSlotNumber = this.slotNumber;
      this._slot = null;
      this._error = null;
      this._loadSlot();
      this._loadTerminals();
    }
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this._eventSource?.close();
    this._unsubWorkspace?.();
  }

  private _subscribeEvents() {
    this._eventSource = new EventSource('/api/push?topics=agent:state,agent:eviction');
    this._eventSource.onmessage = (e: MessageEvent) => {
      this._loadTerminals();
      try {
        const data = JSON.parse(e.data);
        if (data.topic === 'agent:eviction') {
          const candidates = data.payload?.candidates ?? [];
          this._evictionCandidates = new Set(candidates.map((c: { terminalName: string }) => c.terminalName));
          this._totalAgentMemoryMb = candidates.reduce((sum: number, c: { memoryBytes: number }) => sum + Math.round(c.memoryBytes / (1024 * 1024)), 0);
        }
      } catch { /* ignore parse errors */ }
    };
  }

  override render() {
    if (this._error) return html`<div class="error">${this._error}</div>`;
    if (!this._slot) return html`<div class="loading">Loading slot ${this.slotNumber}...</div>`;

    const tabs = this._snapshots
        .filter(s => s.terminal.slot === String(this.slotNumber))
        .map(s => ({
          name: s.terminal.repo ?? s.terminalName,
          sessionName: s.terminalName,
          agentState: s.process?.state ?? 'IDLE',
          memoryMb: s.process ? Math.round(s.process.memoryBytes / (1024 * 1024)) : 0,
          lastError: s.lastError,
        }));

    return html`
      <div class="main">
        ${this._renderToolbar()}
        ${this._evictionCandidates.size > 0 ? html`
          <div class="pressure-banner">
            ⚠ Memory pressure: ${this._totalAgentMemoryMb} MB across ${this._evictionCandidates.size} agent(s)
          </div>
        ` : nothing}
        <div class="terminal-area">
          ${tabs.length > 0
            ? html`<trellis-terminal-tab-group .tabs=${tabs}></trellis-terminal-tab-group>`
            : html`
              <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;gap:1.5rem;color:#666">
                <div style="font-size:0.9rem">No terminal sessions for this slot.</div>
                <div style="font-size:0.8rem;color:#555">${this._slot!.repos[0] ?? 'unknown'} (primary)</div>
                <div style="display:flex;gap:0.75rem;flex-wrap:wrap;justify-content:center">
                  <button class="action-btn" @click=${() => this._createTerminal(this._slot!.repos[0], false)}>
                    Terminal
                  </button>
                  <button class="action-btn primary" @click=${() => this._createTerminal(this._slot!.repos[0], true)}>
                    New agent
                  </button>
                  <button class="action-btn primary" @click=${() => this._createTerminal(this._slot!.repos[0], true, true)}>
                    Resume agent
                  </button>
                </div>
              </div>
            `}
        </div>
      </div>
      ${this._renderSidebar()}
    `;
  }

  private _goBack() {
    const root = this.workspaceRoot ? `root=${encodeURIComponent(this.workspaceRoot)}` : '';
    location.hash = `#?${root}`;
  }

  private _renderToolbar() {
    const slot = this._slot!;
    return html`
      <div class="toolbar">
        ${!this.modal ? html`<button class="action-btn" @click=${this._goBack} title="Back to workspace">←</button>` : nothing}
        <h2>Slot ${slot.number}</h2>
        <span class="issue-ref">${slot.issue}</span>
        <span class="spacer"></span>
        ${slot.isEpic ? html`
          <button class="action-btn primary" ?disabled=${!!this._actionInProgress}
                  @click=${this._nextEpic}>next</button>
        ` : nothing}
        <button class="action-btn" ?disabled=${!!this._actionInProgress}
                @click=${this._pause}>pause</button>
        <button class="action-btn danger" ?disabled=${!!this._actionInProgress}
                @click=${this._end}>end (skip review)</button>
      </div>
    `;
  }

  private _renderSidebar() {
    const slot = this._slot!;
    return html`
      <div class="sidebar">
        <div class="sidebar-section">
          <h3>Status</h3>
          <span class="badge ${slot.status === 'ACTIVE' ? 'badge-active' : 'badge-ready'}">
            ${slot.status.replace('_', ' ')}
          </span>
          ${slot.isEpic ? html`<span class="badge badge-epic">epic</span>` : nothing}
        </div>

        <div class="sidebar-section">
          <h3>Issues</h3>
          <div class="meta-item"><span class="meta-value">${slot.issue}</span></div>
          ${slot.covers && slot.covers.length > 0 ? html`
            <div style="margin-top:0.4rem">
              ${slot.covers.map(n => {
                const issuePrefix = slot.issue.replace(/#\d+$/, '#');
                const isCurrent = slot.issue.endsWith('#' + n);
                return html`
                  <span class="badge" style="margin:0.1rem 0.15rem;${isCurrent ? 'background:#1e3a5f;color:#93c5fd;font-weight:600' : 'background:#333;color:#888'}">
                    #${n}${isCurrent ? ' ●' : ''}
                  </span>
                `;
              })}
            </div>
          ` : nothing}
        </div>

        ${this._renderPlan()}

        <div class="sidebar-section">
          <h3>Repos</h3>
          <ul class="repo-list">
            ${this._repoInfos.length > 0
              ? this._repoInfos.map((r, i) => html`
                  <li>${r.name}${i === 0 ? ' (primary)' : ''}
                    <span style="display:block;font-size:0.7rem;color:#666">${r.branch}</span>
                  </li>`)
              : slot.repos.map(r => html`<li>${r}</li>`)}
          </ul>
        </div>

        <div class="sidebar-section">
          <h3>Terminals</h3>
          ${this._snapshots.filter(s => s.terminal.slot === String(this.slotNumber)).length === 0
            ? html`<div class="meta-item">No active terminals.</div>`
            : this._snapshots.filter(s => s.terminal.slot === String(this.slotNumber))
                .map(s => html`
                  <div class="meta-item" style="display:flex;align-items:center;gap:0.4rem;margin-bottom:0.5rem">
                    <span class="meta-value" style="flex:1">${s.terminalName}</span>
                    <agent-status-badge
                      .state=${s.process?.state ?? 'IDLE'}
                      .memoryMb=${s.process ? Math.round(s.process.memoryBytes / (1024 * 1024)) : 0}
                      .lastError=${s.lastError}
                      .evictionCandidate=${this._evictionCandidates.has(s.terminalName)}
                    ></agent-status-badge>
                  </div>
                  <div style="display:flex;gap:0.3rem;margin-bottom:0.75rem">
                    ${this._renderAgentButtons(s)}
                  </div>
                `)}
        </div>
      </div>
    `;
  }

  private _renderPlan() {
    const plan = this._slot?.planProgress;
    if (!plan) return nothing;

    const currentBatchIdx = plan.batches.findIndex(b =>
      this._hasActiveItem(b.items));
    const totalBatches = plan.batches.filter(b => b.items.length > 0).length;

    return html`
      <div class="sidebar-section">
        <h3>Plan</h3>
        ${plan.batches.map(batch => html`
          <div class="plan-batch">
            ${batch.name ? html`<div class="plan-batch-header">${batch.name}</div>` : nothing}
            ${this._renderPlanItems(batch.items, 0)}
          </div>
        `)}
        <div class="plan-summary">
          ${plan.completed}/${plan.total} done${totalBatches > 1
            ? ` · Batch ${currentBatchIdx >= 0 ? currentBatchIdx + 1 : totalBatches} of ${totalBatches}`
            : ''}
        </div>
      </div>
    `;
  }

  private _renderPlanItems(items: PlanItem[], depth: number): unknown {
    const shortRef = (ref: string) => {
      const parts = ref.split('/');
      return parts.length > 1 ? parts[parts.length - 1] : ref;
    };

    return items.map(item => {
      const hasChildren = item.children && item.children.length > 0;
      return html`
        <div class="plan-item ${item.done ? 'plan-item-done' : item.active ? 'plan-item-active' : 'plan-item-pending'}"
             style="padding-left: ${depth * 0.75}rem">
          <span class="${item.done ? 'plan-icon-done' : item.active ? 'plan-icon-active' : 'plan-icon-pending'}">
            ${item.done ? '✓' : item.active ? '●' : '○'}
          </span>
          <span class="plan-ref">${shortRef(item.ref)}</span>
          <span class="plan-title">${item.title}</span>
        </div>
        ${hasChildren ? this._renderPlanItems(item.children!, depth + 1) : nothing}
      `;
    });
  }

  private _hasActiveItem(items: PlanItem[]): boolean {
    return items.some(i => i.active || (i.children && this._hasActiveItem(i.children)));
  }

  private async _loadSlot() {
    if (!this.workspaceRoot) return;
    try {
      const res = await fetch(`/api/workspace?root=${encodeURIComponent(this.workspaceRoot)}`);
      if (!res.ok) { this._error = `Failed to load workspace: HTTP ${res.status}`; return; }
      const model = await res.json();
      this._slot = model.slots.find((s: SlotInfo) => s.number === this.slotNumber) ?? null;
      if (!this._slot) { this._error = `Slot ${this.slotNumber} not found`; return; }
      this._repoInfos = (model.repos ?? []).filter((r: RepoInfo) =>
        this._slot!.repos.includes(r.name));
    } catch (e) {
      this._error = `Failed to load slot: ${e}`;
    }
  }

  private _renderAgentButtons(s: AgentSnapshot) {
    const state = s.process?.state ?? 'IDLE';
    const disabled = !!this._actionInProgress;
    switch (state) {
      case 'RUNNING':
        return html`
          <button class="action-btn" ?disabled=${disabled}
                  @click=${() => this._agentAction(s.terminalName, 'refresh')}>refresh</button>
          <button class="action-btn" ?disabled=${disabled}
                  @click=${() => this._agentAction(s.terminalName, 'pause')}>pause</button>
          <button class="action-btn danger" ?disabled=${disabled}
                  @click=${() => this._agentAction(s.terminalName, 'stop')}>stop</button>
          ${this._evictionCandidates.has(s.terminalName) ? html`
            <button class="evict-btn" ?disabled=${disabled}
                    @click=${() => this._agentAction(s.terminalName, 'pause')}>evict</button>
          ` : nothing}
        `;
      case 'PAUSED':
      case 'PAUSED_BY_COORDINATOR':
        return html`
          <button class="action-btn primary" ?disabled=${disabled}
                  @click=${() => this._agentAction(s.terminalName, 'resume')}>resume</button>
        `;
      case 'IDLE':
        return html`
          <button class="action-btn primary" ?disabled=${disabled}
                  @click=${() => this._agentAction(s.terminalName, 'start')}>start</button>
        `;
      case 'STARTING':
        return html`<span class="meta-item">starting...</span>`;
      default:
        return nothing;
    }
  }

  private async _agentAction(terminalName: string, action: string) {
    this._actionInProgress = action;
    try {
      const res = await fetch(`/api/terminals/${terminalName}/agent/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: action === 'start' ? '{}' : undefined,
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._error = body?.error ?? `${action} failed: HTTP ${res.status}`;
      }
      this._loadTerminals();
    } catch (e) {
      this._error = `${action} failed: ${e}`;
    } finally {
      this._actionInProgress = null;
    }
  }

  private async _createTerminal(repo: string, withAgent = false, resume = false) {
    this._actionInProgress = 'create';
    try {
      const repoPath = this.workspaceRoot ? `${this.workspaceRoot}/${repo}` : `/tmp/${repo}`;
      const body: Record<string, unknown> = {
        name: `repo-${repo}`,
        workingDir: repoPath,
        slot: String(this.slotNumber),
        repo,
        issue: this._slot?.issue ?? null,
      };
      if (withAgent) {
        body.agent = { resume, prompt: null };
      }
      const res = await fetch('/api/terminals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const body2 = await res.json().catch(() => null);
        this._error = body2?.error ?? `Create failed: HTTP ${res.status}`;
      }
      this._loadTerminals();
    } catch (e) {
      this._error = `Create failed: ${e}`;
    } finally {
      this._actionInProgress = null;
    }
  }

  private async _loadTerminals() {
    try {
      const params = new URLSearchParams();
      params.set('slot', String(this.slotNumber));
      const res = await fetch(`/api/terminals?${params}`);
      if (res.ok) this._snapshots = await res.json();
    } catch { /* ignore */ }
  }

  private async _end() {
    await this._lifecycleAction('end', `/api/lifecycle/end/${this.slotNumber}`);
  }

  private async _pause() {
    await this._lifecycleAction('pause', `/api/lifecycle/pause/${this.slotNumber}`);
  }

  private async _nextEpic() {
    await this._lifecycleAction('next', `/api/lifecycle/epic/${this.slotNumber}/next`);
  }

  private async _lifecycleAction(name: string, url: string) {
    this._actionInProgress = name;
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workspaceRoot: this.workspaceRoot }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._error = body?.error ?? `${name} failed: HTTP ${res.status}`;
      }
      this._loadSlot();
      this._loadTerminals();
    } catch (e) {
      this._error = `${name} failed: ${e}`;
    } finally {
      this._actionInProgress = null;
    }
  }
}
