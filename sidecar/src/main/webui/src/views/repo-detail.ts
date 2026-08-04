import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import '../components/agent-status-badge';

interface RepoData {
  name: string;
  path: string;
  branch: string;
  remoteUrl: string | null;
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

@customElement('trellis-repo-detail')
export class TrellisRepoDetail extends LitElement {

  @property() repoName = '';
  @property() workspaceRoot = '';

  @state() private _repo: RepoData | null = null;
  @state() private _snapshot: AgentSnapshot | null = null;
  @state() private _error: string | null = null;
  @state() private _loading = false;
  @state() private _actionInProgress: string | null = null;

  private _lastLoaded = '';
  private _lastTerminalName = '';
  private _eventSource: EventSource | null = null;
  private _mousedownHandler: ((e: Event) => void) | null = null;

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

    .error { color: #f87171; padding: 1rem; }
    .loading { color: #666; padding: 2rem; text-align: center; }
  `;

  override connectedCallback() {
    super.connectedCallback();
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'https://cdn.jsdelivr.net/npm/@xterm/xterm@6.0.0/css/xterm.min.css';
    this.renderRoot.prepend(link);
    this._loadRepo();
    this._loadTerminal();
    this._subscribeEvents();
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this._eventSource?.close();
    if (this._mousedownHandler) {
      document.removeEventListener('mousedown', this._mousedownHandler);
      this._mousedownHandler = null;
    }
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if ((changed.has('repoName') || changed.has('workspaceRoot')) && this.repoName && this.workspaceRoot) {
      const key = `${this.workspaceRoot}:${this.repoName}`;
      if (key !== this._lastLoaded) {
        this._lastLoaded = key;
        this._loadRepo();
        this._loadTerminal();
      }
    }
    if (changed.has('_snapshot') && this._snapshot &&
        this._snapshot.terminalName !== this._lastTerminalName) {
      this._lastTerminalName = this._snapshot.terminalName;
      this.updateComplete.then(() => {
        const el = this.renderRoot.querySelector('#repo-terminal') as any;
        if (el) {
          const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
          el.configure({
            wsUrl: `${proto}//${location.host}/ws/terminal/${this._snapshot!.terminalName}/{cols}/{rows}`,
            theme: { background: '#1e1e1e', foreground: '#cccccc', cursor: '#aeafad' },
            fontSize: 13,
            fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
          });
          if (this._mousedownHandler) {
            document.removeEventListener('mousedown', this._mousedownHandler);
          }
          this._mousedownHandler = (e: Event) => {
            const termArea = this.renderRoot.querySelector('.terminal-area');
            if (!termArea) return;
            const rect = termArea.getBoundingClientRect();
            const me = e as MouseEvent;
            if (me.clientX >= rect.left && me.clientX <= rect.right &&
                me.clientY >= rect.top && me.clientY <= rect.bottom) {
              setTimeout(() => { if (el._terminal) el._terminal.focus(); }, 0);
            }
          };
          document.addEventListener('mousedown', this._mousedownHandler);
        }
      });
    }
  }

  static override shadowRootOptions = { ...LitElement.shadowRootOptions, delegatesFocus: true };

  private _focusTerminal() {
    const el = this.renderRoot.querySelector('#repo-terminal') as any;
    if (el?._terminal) el._terminal.focus();
  }

  private _handleTerminalEvent(e: CustomEvent) {
    const { topic, payload } = e.detail;
    if (topic === 'terminal-connected') {
      this._focusTerminal();
    }
    if (topic === 'terminal-resize' && this._snapshot) {
      fetch(`/api/terminals/${this._snapshot.terminalName}/resize`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cols: payload.cols, rows: payload.rows }),
      }).catch(() => {});
    }
  }

  private _subscribeEvents() {
    this._eventSource = new EventSource('/api/push?topics=agent:state');
    this._eventSource.addEventListener('agent:state', () => this._loadTerminal());
  }

  override render() {
    if (this._loading) return html`<div class="loading">Loading ${this.repoName}...</div>`;
    if (this._error) return html`<div class="error">${this._error}</div>`;
    if (!this._repo) return nothing;

    return html`
      <div class="main">
        ${this._renderToolbar()}
        ${this._snapshot
          ? html`<div class="terminal-area" @click=${this._focusTerminal}>
              <pages-component-terminal
                id="repo-terminal"
                @pages-event=${this._handleTerminalEvent}
              ></pages-component-terminal>
            </div>`
          : html`<div class="empty-state">
              <p>No agent running for this repo.</p>
              <button class="start-btn" ?disabled=${!!this._actionInProgress}
                      @click=${this._createTerminal}>Start Agent</button>
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
        <button class="action-btn" @click=${this._goBack} title="Back to workspace">←</button>
        <h2>${repo.name}</h2>
        <span class="badge badge-branch">${repo.branch}</span>
        <span class="spacer"></span>
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

        ${this._snapshot ? html`
          <div class="sidebar-section">
            <h3>Agent</h3>
            <div class="meta-item" style="display:flex;align-items:center;gap:0.4rem;margin-bottom:0.5rem">
              <agent-status-badge
                .state=${this._snapshot.process?.state ?? 'IDLE'}
                .memoryMb=${this._snapshot.process ? Math.round(this._snapshot.process.memoryBytes / (1024 * 1024)) : 0}
                .lastError=${this._snapshot.lastError}
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

  private _renderAgentButtons() {
    if (!this._snapshot) return nothing;
    const state = this._snapshot.process?.state ?? 'IDLE';
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

  private async _createTerminal() {
    if (!this._repo) return;
    this._actionInProgress = 'create';
    try {
      const res = await fetch('/api/terminals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: `repo-${this.repoName}`,
          workingDir: this._repo.path,
          repo: this.repoName,
          agent: {},
        }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._error = body?.error ?? `Failed to create terminal: HTTP ${res.status}`;
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
    if (!this._snapshot) return;
    this._actionInProgress = action;
    try {
      const res = await fetch(`/api/terminals/${this._snapshot.terminalName}/agent/${action}`, {
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
    }
  }

  private async _loadRepo() {
    this._loading = true;
    this._error = null;
    try {
      const params = new URLSearchParams({ root: this.workspaceRoot, repo: this.repoName });
      const res = await fetch(`/api/workspace/repo?${params}`);
      if (!res.ok) {
        const body = await res.json().catch(() => null);
        this._error = body?.error ?? `HTTP ${res.status}`;
        this._repo = null;
        return;
      }
      this._repo = await res.json();
    } catch (e) {
      this._error = `Failed to load repo: ${e}`;
      this._repo = null;
    } finally {
      this._loading = false;
    }
  }

  private async _loadTerminal() {
    try {
      const res = await fetch('/api/terminals');
      if (!res.ok) return;
      const all: AgentSnapshot[] = await res.json();
      this._snapshot = all.find(
        s => s.terminal.repo === this.repoName && !s.terminal.slot
      ) ?? null;
    } catch { /* ignore */ }
  }
}
