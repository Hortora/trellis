import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import '../components/terminal-tab-group';

interface SlotInfo {
  number: number;
  path: string;
  issue: string;
  status: string;
  isEpic: boolean;
  repos: string[];
}

interface SessionInfo {
  name: string;
  workingDir: string | null;
  slot: string | null;
  repo: string | null;
  issue: string | null;
}

@customElement('trellis-slot-detail')
export class TrellisSlotDetail extends LitElement {

  @property({ type: Number }) slotNumber = 0;
  @property() workspaceRoot = '';

  @state() private _slot: SlotInfo | null = null;
  @state() private _sessions: SessionInfo[] = [];
  @state() private _error: string | null = null;
  @state() private _actionInProgress: string | null = null;

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

    .error { color: #f87171; padding: 1rem; }
    .loading { color: #666; padding: 2rem; text-align: center; }
  `;

  override connectedCallback() {
    super.connectedCallback();
    this._loadSlot();
    this._loadSessions();
  }

  override render() {
    if (this._error) return html`<div class="error">${this._error}</div>`;
    if (!this._slot) return html`<div class="loading">Loading slot ${this.slotNumber}...</div>`;

    const tabs = this._sessions
        .filter(s => s.slot === String(this.slotNumber))
        .map(s => ({ name: s.repo ?? s.name, sessionName: s.name }));

    return html`
      <div class="main">
        ${this._renderToolbar()}
        <div class="terminal-area">
          <trellis-terminal-tab-group .tabs=${tabs}></trellis-terminal-tab-group>
        </div>
      </div>
      ${this._renderSidebar()}
    `;
  }

  private _renderToolbar() {
    const slot = this._slot!;
    return html`
      <div class="toolbar">
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
          <h3>Issue</h3>
          <div class="meta-item"><span class="meta-value">${slot.issue}</span></div>
        </div>

        <div class="sidebar-section">
          <h3>Repos</h3>
          <ul class="repo-list">
            ${slot.repos.map(r => html`<li>${r}</li>`)}
          </ul>
        </div>

        <div class="sidebar-section">
          <h3>Sessions</h3>
          ${this._sessions.filter(s => s.slot === String(this.slotNumber)).length === 0
            ? html`<div class="meta-item">No active sessions. Create one from the terminal.</div>`
            : html`<ul class="repo-list">
                ${this._sessions.filter(s => s.slot === String(this.slotNumber))
                    .map(s => html`<li>${s.name}</li>`)}
              </ul>`}
        </div>
      </div>
    `;
  }

  private async _loadSlot() {
    if (!this.workspaceRoot) return;
    try {
      const res = await fetch(`/api/workspace?root=${encodeURIComponent(this.workspaceRoot)}`);
      if (!res.ok) { this._error = `Failed to load workspace: HTTP ${res.status}`; return; }
      const model = await res.json();
      this._slot = model.slots.find((s: SlotInfo) => s.number === this.slotNumber) ?? null;
      if (!this._slot) this._error = `Slot ${this.slotNumber} not found`;
    } catch (e) {
      this._error = `Failed to load slot: ${e}`;
    }
  }

  private async _loadSessions() {
    try {
      const res = await fetch('/api/sessions');
      if (res.ok) this._sessions = await res.json();
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
      this._loadSessions();
    } catch (e) {
      this._error = `${name} failed: ${e}`;
    } finally {
      this._actionInProgress = null;
    }
  }
}
