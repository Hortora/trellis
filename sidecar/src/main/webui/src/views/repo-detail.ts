import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import '../components/agent-status-badge';
import '../components/lifecycle-progress.js';
import type { OperationProgress, StepProgress } from '../components/lifecycle-progress.js';
import { PairEntry } from '../components/terminal-pair-view';
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

interface RepoData {
  name: string;
  path: string;
  branch: string;
  remoteUrl: string | null;
  issue: string | null;
  covers: number[];
  workState: string | null;
  planProgress: PlanProgress | null;
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
    pairedTerminal: string | null;
  };
  process: AgentProcess | null;
  lastError: string | null;
}

@customElement('trellis-repo-detail')
export class TrellisRepoDetail extends LitElement {

  @property() repoName = '';
  @property() workspaceRoot = '';
  @property({ type: Boolean }) modal = false;

  @state() private _repo: RepoData | null = null;
  @state() private _terminalName = '';
  @state() private _agentState: { state: string; memoryMb: number; lastError: string | null } | null = null;
  @state() private _snapshots: AgentSnapshot[] = [];
  @state() private _error: string | null = null;
  @state() private _loading = false;
  @state() private _actionInProgress: string | null = null;
  @state() private _operation: OperationProgress | null = null;

  private _lastLoaded = '';
  private _lastTerminalName = '';
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

    .terminal-area { flex: 1; min-height: 0; overflow: hidden; display: flex; }
    .terminal-area pages-component-terminal { flex: 1; overflow: hidden; }
    .terminal-area trellis-terminal-pair-view { flex: 1; overflow: hidden; }
    pages-component-terminal .xterm { height: 100%; }
    pages-component-terminal .xterm-viewport { overflow: hidden !important; }

    .empty-state {
      flex: 1; display: flex; flex-direction: column; align-items: center;
      justify-content: center; gap: 1rem; color: #666;
    }
    .empty-state p { margin: 0; font-size: 0.9rem; }

    .start-btn {
      padding: 0.5rem 1.5rem; border: 1px solid #1d4ed8; border-radius: 6px;
      background: #1e3a5f; color: #93c5fd; cursor: pointer; font-size: 0.85rem;
      font-weight: 500; transition: background 0.15s;
    }
    .start-btn:hover { background: #1d4ed8; }
    .start-btn:disabled { opacity: 0.4; cursor: not-allowed; }

    .sidebar {
      width: 280px; background: #1e1e1e; border-left: 1px solid #333;
      padding: 1rem; overflow-y: auto; flex-shrink: 0;
    }
    .sidebar h3 {
      margin: 0 0 0.5rem; font-size: 0.85rem; font-weight: 600;
      color: #aaa; text-transform: uppercase; letter-spacing: 0.05em;
    }
    .sidebar-section { margin-bottom: 1.5rem; }

    .meta-item { font-size: 0.8rem; color: #999; margin-bottom: 0.3rem; }
    .meta-value { color: #ccc; font-family: monospace; }

    .badge {
      display: inline-flex; padding: 0.1rem 0.5rem; border-radius: 4px;
      font-size: 0.7rem; font-weight: 500;
    }
    .badge-branch { background: #1e3a5f; color: #93c5fd; }

    .remote-link { color: #60a5fa; font-size: 0.8rem; text-decoration: none; }
    .remote-link:hover { text-decoration: underline; }

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
    .plan-link { color: #60a5fa; text-decoration: none; cursor: pointer; }
    .plan-link:hover { text-decoration: underline; }
    .plan-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .plan-summary {
      font-size: 0.75rem; color: #888; padding-top: 0.5rem;
      margin-top: 0.5rem; border-top: 1px solid #333;
    }

    .error { color: #f87171; padding: 1rem; }
    .loading { color: #666; padding: 2rem; text-align: center; }
  `;

  override connectedCallback() {
    super.connectedCallback();
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'https://cdn.jsdelivr.net/npm/@xterm/xterm@6.0.0/css/xterm.min.css';
    this.renderRoot.prepend(link);
    this._fetchController = new AbortController();
    this._loadRepo();
    this._loadTerminal();
    this._subscribeEvents();
    this._recoverOperation();
    this._unsubWorkspace = subscribeWorkspace(
      ['workspace:repos'],
      () => this._loadRepo()
    );
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this._fetchController?.abort();
    this._eventSource?.close();
    this._unsubWorkspace?.();
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if ((changed.has('repoName') || changed.has('workspaceRoot')) && this.repoName && this.workspaceRoot) {
      const key = `${this.workspaceRoot}:${this.repoName}`;
      if (key !== this._lastLoaded) {
        this._lastLoaded = key;
        this._lastTerminalName = '';
        this._terminalName = '';
        this._snapshots = [];
        this._agentState = null;
        this._fetchController?.abort();
        this._fetchController = new AbortController();
        this._navigationVersion++;
        this._loadRepo();
        this._loadTerminal();
      }
    }
    if (this._needsTerminalConfigure(changed)) {
      this._configureTerminalElement();
    }
  }

  private _needsTerminalConfigure(changed: Map<PropertyKey, unknown>): boolean {
    if (!this._terminalName || this._terminalName === this._lastTerminalName) return false;
    if (changed.has('_terminalName')) return true;
    if (changed.has('_loading') && !this._loading) return true;
    return false;
  }

  private _configureTerminalElement() {
    this.updateComplete.then(() => {
      const el = this.renderRoot.querySelector('#repo-terminal') as any;
      if (el) {
        this._lastTerminalName = this._terminalName;
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        el.configure({
          wsUrl: `${proto}//${location.host}/ws/terminal/${this._terminalName}/{cols}/{rows}`,
          theme: { background: '#1e1e1e', foreground: '#cccccc', cursor: '#aeafad' },
          fontSize: 13,
          fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
        });
        setTimeout(() => this._focusTerminal(), 500);
      }
    });
  }

  static override shadowRootOptions = { ...LitElement.shadowRootOptions, delegatesFocus: true };

  private _focusTerminal(retries = 5) {
    const el = this.renderRoot.querySelector('#repo-terminal') as any;
    if (el?._terminal) {
      el._terminal.focus();
    } else if (retries > 0) {
      setTimeout(() => this._focusTerminal(retries - 1), 200);
    }
  }

  private _handleTerminalEvent(e: CustomEvent) {
    const { topic, payload } = e.detail;
    if (topic === 'terminal-connected') {
      this._focusTerminal();
    }
    if (topic === 'terminal-resize' && this._terminalName) {
      fetch(`/api/terminals/${this._terminalName}/resize`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cols: payload.cols, rows: payload.rows }),
      }).catch(() => {});
    }
  }

  private _subscribeEvents() {
    this._eventSource = new EventSource('/api/push?topics=agent:state,lifecycle:progress');
    this._eventSource.onmessage = (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data);
        if (data.topic === 'lifecycle:progress') {
          this._handleLifecycleEvent(data.payload ?? data);
        } else {
          this._loadTerminal();
        }
      } catch { this._loadTerminal(); }
    };
  }

  private _handleLifecycleEvent(data: Record<string, unknown>) {
    if (String(data.contextId) !== 'repo-' + this.repoName) return;
    if (data.step) {
      if (this._operation) {
        this._operation = {
          ...this._operation,
          state: data.operationState as OperationProgress['state'],
          steps: this._operation.steps.map(s =>
            s.name === data.step
              ? { ...s, state: data.state as StepProgress['state'], stdout: data.stdout as string | null, stderr: data.stderr as string | null }
              : s
          ),
        };
      }
    } else {
      if (this._operation) {
        this._operation = {
          ...this._operation,
          state: data.operationState as OperationProgress['state'],
          errorMessage: data.errorMessage as string | undefined,
        };
      }
      if (data.operationState === 'COMPLETED' || data.operationState === 'FAILED') {
        this._actionInProgress = null;
        this._loadRepo();
        this._loadTerminal();
        if (data.operationState === 'COMPLETED') {
          setTimeout(() => { this._operation = null; }, 10000);
        }
      }
    }
  }

  override render() {
    if (this._loading) return html`<div class="loading">Loading ${this.repoName}...</div>`;
    if (this._error) return html`<div class="error">${this._error}</div>`;
    if (!this._repo) return nothing;

    const paired = this._pairedSnapshots();

    return html`
      <div class="main">
        ${this._renderToolbar()}
        ${paired
          ? html`<div class="terminal-area">
              <trellis-terminal-pair-view
                .primary=${this._toPairEntry(paired[0])}
                .secondary=${this._toPairEntry(paired[1])}
              ></trellis-terminal-pair-view>
            </div>`
          : this._terminalName
          ? html`<div class="terminal-area" @click=${() => this._focusTerminal()}>
              <pages-component-terminal
                id="repo-terminal"
                @pages-event=${this._handleTerminalEvent}
              ></pages-component-terminal>
            </div>`
          : html`<div class="empty-state">
              <p>No terminal sessions for this repo.</p>
              <div style="display:flex;gap:0.75rem;justify-content:center;margin-top:0.75rem">
                <button class="action-btn" ?disabled=${!!this._actionInProgress}
                        @click=${() => this._createTerminalWith(false)}>Terminal</button>
                <button class="action-btn primary" ?disabled=${!!this._actionInProgress}
                        @click=${() => this._createTerminalWith(true)}>New agent</button>
                <button class="action-btn primary" ?disabled=${!!this._actionInProgress}
                        @click=${() => this._createTerminalWith(true, true)}>Resume agent</button>
              </div>
            </div>`
        }
      </div>
      ${this._renderSidebar()}
    `;
  }

  private _goBack() {
    const root = this.workspaceRoot ? `root=${encodeURIComponent(this.workspaceRoot)}` : '';
    location.hash = `#?${root}`;
  }

  private _githubUrl(): string | null {
    const url = this._repo?.remoteUrl;
    if (!url) return null;
    const m = url.match(/github\.com[:/](.+?)(?:\.git)?$/);
    return m ? `https://github.com/${m[1]}` : null;
  }

  private _renderToolbar() {
    const repo = this._repo!;
    return html`
      <div class="toolbar">
        ${!this.modal ? html`<button class="action-btn" @click=${this._goBack} title="Back to workspace">←</button>` : nothing}
        <h2>${repo.name}</h2>
        <span class="badge badge-branch">${repo.branch}</span>
        <span class="spacer"></span>
        ${repo.branch !== 'main' ? html`
          <button class="action-btn" ?disabled=${!!this._actionInProgress}
                  @click=${() => this._lifecycleAction('pause')}>pause</button>
          <button class="action-btn danger" ?disabled=${!!this._actionInProgress}
                  @click=${() => this._lifecycleAction('end')}>end</button>
        ` : nothing}
      </div>
    `;
  }

  private _renderSidebar() {
    const repo = this._repo!;
    const gh = this._githubUrl();
    return html`
      <div class="sidebar">
        <div class="sidebar-section">
          <h3>Path</h3>
          <div class="meta-item"><span class="meta-value">${repo.path}</span></div>
        </div>

        ${repo.workState ? html`
          <div class="sidebar-section">
            <h3>Status</h3>
            <span class="badge badge-branch">${repo.workState}</span>
          </div>
        ` : nothing}

        ${repo.issue ? this._renderIssueSection(repo) : nothing}

        ${this._renderPlan()}

        <lifecycle-progress .operation=${this._operation}></lifecycle-progress>

        ${gh ? html`
          <div class="sidebar-section">
            <h3>Remote</h3>
            <a class="remote-link" href=${gh} target="_blank">${gh}</a>
          </div>
        ` : repo.remoteUrl ? html`
          <div class="sidebar-section">
            <h3>Remote</h3>
            <div class="meta-item"><span class="meta-value">${repo.remoteUrl}</span></div>
          </div>
        ` : nothing}

        ${this._terminalName ? html`
          <div class="sidebar-section">
            <h3>Agent</h3>
            <div class="meta-item" style="display:flex;align-items:center;gap:0.4rem;margin-bottom:0.5rem">
              <agent-status-badge
                .state=${this._agentState?.state ?? 'IDLE'}
                .memoryMb=${this._agentState?.memoryMb ?? 0}
                .lastError=${this._agentState?.lastError ?? null}
              ></agent-status-badge>
            </div>
            <div style="display:flex;gap:0.3rem">
              ${this._renderAgentButtons()}
            </div>
          </div>
        ` : nothing}
      </div>
    `;
  }

  private _renderIssueSection(repo: RepoData) {
    const issueHref = repo.issue ? this._issueUrl(repo.issue) : null;
    return html`
      <div class="sidebar-section">
        <h3>Issue</h3>
        <div class="meta-item">
          ${issueHref
            ? html`<a class="remote-link" href=${issueHref} target="_blank">${repo.issue}</a>`
            : html`<span class="meta-value">${repo.issue}</span>`}
        </div>
        ${repo.covers && repo.covers.length > 1 ? html`
          <div style="margin-top:0.4rem">
            ${repo.covers.map(n => {
              const isCurrent = repo.issue?.endsWith('#' + n);
              return html`
                <span class="badge" style="margin:0.1rem 0.15rem;${isCurrent ? 'background:#1e3a5f;color:#93c5fd;font-weight:600' : 'background:#333;color:#888'}">
                  #${n}${isCurrent ? ' ●' : ''}
                </span>
              `;
            })}
          </div>
        ` : nothing}
      </div>
    `;
  }

  private _renderPlan() {
    const plan = this._repo?.planProgress;
    if (!plan) return nothing;
    return html`
      <div class="sidebar-section">
        <h3>Plan</h3>
        ${plan.batches.map(batch => html`
          <div class="plan-batch">
            ${batch.name ? html`<div class="plan-batch-header">${batch.name}</div>` : nothing}
            ${this._renderPlanItems(batch.items)}
          </div>
        `)}
        <div class="plan-summary">${plan.completed}/${plan.total} done</div>
      </div>
    `;
  }

  private _renderPlanItems(items: PlanItem[]): unknown {
    return items.map(item => {
      const url = this._issueUrl(item.ref);
      const label = this._shortRef(item.ref);
      const hasChildren = item.children && item.children.length > 0;
      return html`
        <div class="plan-item ${item.done ? 'plan-item-done' : item.active ? 'plan-item-active' : 'plan-item-pending'}">
          <span class="${item.done ? 'plan-icon-done' : item.active ? 'plan-icon-active' : 'plan-icon-pending'}">
            ${item.done ? '✓' : item.active ? '●' : '○'}
          </span>
          ${url
            ? html`<a class="plan-ref plan-link" href=${url} target="_blank">${label}</a>`
            : html`<span class="plan-ref">${label}</span>`}
          <span class="plan-title">${item.title}</span>
        </div>
        ${hasChildren ? this._renderPlanItems(item.children!) : nothing}
      `;
    });
  }

  private _shortRef(ref: string): string {
    const parts = ref.split('/');
    return parts.length > 1 ? parts[parts.length - 1] : ref;
  }

  private _issueUrl(ref: string): string | null {
    const m = ref.match(/^(?:([^/]+)\/)?([^#]+)#(\d+)$/);
    if (!m) return null;
    let owner = m[1];
    const repo = m[2];
    const num = m[3];
    if (!owner) {
      const issue = this._repo?.issue ?? '';
      const ownerMatch = issue.match(/^([^/]+)\//);
      owner = ownerMatch ? ownerMatch[1] : '';
    }
    if (!owner) return null;
    return `https://github.com/${owner}/${repo}/issues/${num}`;
  }

  private _renderAgentButtons() {
    if (!this._terminalName) return nothing;
    const state = this._agentState?.state ?? 'IDLE';
    const disabled = !!this._actionInProgress;
    switch (state) {
      case 'RUNNING':
        return html`
          <button class="action-btn" ?disabled=${disabled}
                  @click=${() => this._agentAction('refresh')}>refresh</button>
          <button class="action-btn" ?disabled=${disabled}
                  @click=${() => this._agentAction('pause')}>pause</button>
          <button class="action-btn danger" ?disabled=${disabled}
                  @click=${() => this._agentAction('stop')}>stop</button>
        `;
      case 'PAUSED':
      case 'PAUSED_BY_COORDINATOR':
        return html`
          <button class="action-btn primary" ?disabled=${disabled}
                  @click=${() => this._agentAction('resume')}>resume</button>
        `;
      case 'IDLE':
        return html`
          <button class="action-btn primary" ?disabled=${disabled}
                  @click=${() => this._agentAction('start')}>start</button>
        `;
      case 'STARTING':
        return html`<span class="meta-item">starting...</span>`;
      default:
        return nothing;
    }
  }

  private async _createTerminalWith(withAgent = false, resume = false) {
    if (!this._repo) return;
    this._actionInProgress = 'create';
    try {
      const body: Record<string, unknown> = {
        name: `repo-${this.repoName}`,
        workingDir: this._repo.path,
        repo: this.repoName,
      };
      if (withAgent) {
        body.agent = { resume, prompt: null };
      }
      const res = await fetch('/api/terminals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok && res.status !== 409) {
        const body2 = await res.json().catch(() => null);
        this._error = body2?.error ?? `Failed to create terminal: HTTP ${res.status}`;
        return;
      }
      await this._loadTerminal();
    } catch (e) {
      this._error = `Failed to create terminal: ${e}`;
    } finally {
      this._actionInProgress = null;
    }
  }

  private async _agentAction(action: string) {
    if (!this._terminalName) return;
    this._actionInProgress = action;
    try {
      const res = await fetch(`/api/terminals/${this._terminalName}/agent/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: action === 'start' ? '{}' : undefined,
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._error = body?.error ?? `${action} failed: HTTP ${res.status}`;
      }
      await this._loadTerminal();
    } catch (e) {
      this._error = `${action} failed: ${e}`;
    } finally {
      this._actionInProgress = null;
      setTimeout(() => this._focusTerminal(), 200);
    }
  }

  private _pairedSnapshots(): [AgentSnapshot, AgentSnapshot] | null {
    for (const s of this._snapshots) {
      if (s.terminal.pairedTerminal) {
        const partner = this._snapshots.find(o => o.terminalName === s.terminal.pairedTerminal);
        if (partner) return [s, partner];
      }
    }
    return null;
  }

  private _toPairEntry(s: AgentSnapshot): PairEntry {
    return {
      name: s.terminal.repo ?? s.terminalName,
      sessionName: s.terminalName,
      agentState: s.process?.state,
      memoryMb: s.process ? Math.round(s.process.memoryBytes / (1024 * 1024)) : 0,
      lastError: s.lastError,
    };
  }

  private async _loadRepo() {
    const version = this._navigationVersion;
    const signal = this._fetchController?.signal;
    this._loading = true;
    this._error = null;
    try {
      const params = new URLSearchParams({ root: this.workspaceRoot, repo: this.repoName });
      const res = await fetch(`/api/workspace/repo?${params}`, { signal });
      if (version !== this._navigationVersion) return;
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._error = body?.error ?? `HTTP ${res.status}`;
        this._repo = null;
        return;
      }
      this._repo = await res.json();
    } catch (e) {
      if (version !== this._navigationVersion) return;
      if (e instanceof DOMException && e.name === 'AbortError') return;
      this._error = `Failed to load repo: ${e}`;
      this._repo = null;
    } finally {
      if (version === this._navigationVersion) {
        this._loading = false;
      }
    }
  }

  private async _loadTerminal(signal?: AbortSignal) {
    const version = this._navigationVersion;
    const fetchSignal = signal ?? this._fetchController?.signal;
    try {
      const params = new URLSearchParams();
      if (this.repoName) params.set('repo', this.repoName);
      const res = await fetch(`/api/terminals?${params}`, { signal: fetchSignal });
      if (version !== this._navigationVersion) return;
      if (!res.ok) return;
      const all: AgentSnapshot[] = await res.json();
      if (version !== this._navigationVersion) return;
      const filtered = all.filter(s => !s.terminal.slot);
      this._snapshots = filtered;
      const next = filtered[0] ?? null;
      const name = next?.terminalName ?? '';
      if (name !== this._terminalName) {
        this._terminalName = name;
      }
      const agentState = next?.process
        ? { state: next.process.state, memoryMb: Math.round(next.process.memoryBytes / (1024 * 1024)), lastError: next.lastError }
        : null;
      const prev = this._agentState;
      if (agentState?.state !== prev?.state || agentState?.memoryMb !== prev?.memoryMb || agentState?.lastError !== prev?.lastError) {
        this._agentState = agentState;
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return;
    }
  }

  private async _recoverOperation() {
    if (!this.repoName) return;
    try {
      const res = await fetch(`/api/lifecycle/operations?context=repo-${this.repoName}`);
      if (res.ok && res.status !== 204) {
        const body = await res.json();
        if (body && body.operationId) {
          this._operation = body as OperationProgress;
          if (body.state === 'RUNNING') {
            this._actionInProgress = body.operationType;
          }
        }
      }
    } catch { /* ignore */ }
  }

  private async _lifecycleAction(name: string) {
    this._actionInProgress = name;
    try {
      const res = await fetch(`/api/lifecycle/${name}/repo-${this.repoName}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workspaceRoot: this.workspaceRoot }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._error = body?.error ?? `${name} failed: HTTP ${res.status}`;
        this._actionInProgress = null;
        return;
      }
      this._operation = await res.json() as OperationProgress;
    } catch (e) {
      this._error = `${name} failed: ${e}`;
      this._actionInProgress = null;
    }
  }
}
