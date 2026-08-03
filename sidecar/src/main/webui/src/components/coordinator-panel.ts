import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface CoordinatorAdvice {
    id: string;
    type: 'INSIGHT' | 'WARNING' | 'SUGGESTION' | 'STATUS';
    epicRef: string | null;
    title: string;
    body: string;
    actionKey: string | null;
    timestamp: string;
}

interface ConversationTurn {
    id: number;
    workspaceRoot: string;
    role: 'USER' | 'COORDINATOR' | 'SYSTEM';
    content: string;
    timestamp: string;
}

interface ProposedAction {
    id: string;
    category: 'LIFECYCLE' | 'AGENT' | 'ADVISORY';
    actionType: string;
    params: Record<string, string>;
    risk: 'LOW' | 'HIGH';
    rationale: string;
    status: 'PROPOSED' | 'APPROVED' | 'CONFIRMING' | 'EXECUTING' | 'COMPLETED' | 'FAILED' | 'REJECTED' | 'EXPIRED';
    adviceId: string;
    workspace: string;
    proposedAt: string;
    resolvedAt: string | null;
    executionResult: string | null;
    countdownEndsAt: string | null;
}

type AutonomyLevel = 'MANUAL' | 'OBSERVATION' | 'AUTONOMOUS';

interface AutonomyState {
    level: AutonomyLevel;
    source: 'session' | 'preference';
}

@customElement('trellis-coordinator-panel')
export class CoordinatorPanel extends LitElement {
    @property() workspaceRoot = '';
    @property() epicRef = '';
    @state() private advice: CoordinatorAdvice[] = [];
    @state() private conversation: ConversationTurn[] = [];
    @state() private actions: Map<string, ProposedAction> = new Map();
    @state() private inputValue = '';
    @state() private loading = false;
    @state() private autonomy: AutonomyState = { level: 'MANUAL', source: 'preference' };
    private eventSource: EventSource | null = null;
    private autoExecuted: Set<string> = new Set();
    private countdownTimers: Map<string, number> = new Map();

    static override styles = css`
        :host {
            display: flex; flex-direction: column; height: 100%;
            font-family: system-ui, sans-serif; color: #eee;
        }
        .advice-feed {
            flex: 0 0 auto; max-height: 40%; overflow-y: auto;
            padding: 0.5rem; border-bottom: 1px solid #333;
        }
        .advice-empty { padding: 1rem; color: #666; font-size: 0.85rem; text-align: center; }
        .chat-area { flex: 1; overflow-y: auto; padding: 0.5rem; }
        .input-bar {
            display: flex; gap: 0.5rem; padding: 0.5rem;
            border-top: 1px solid #333;
        }
        .input-bar input {
            flex: 1; padding: 0.5rem; border: 1px solid #555;
            border-radius: 4px; background: #1a1a1a; color: #eee;
            font-size: 0.85rem;
        }
        .input-bar input:focus { outline: none; border-color: #4a9eff; }
        .input-bar button {
            padding: 0.5rem 1rem; border: none; border-radius: 4px;
            background: #1d4ed8; color: white; cursor: pointer;
            font-size: 0.85rem; font-weight: 500;
        }
        .input-bar button:disabled { opacity: 0.4; cursor: not-allowed; }
        .advice-card {
            padding: 0.75rem; margin: 0.25rem 0; border-radius: 6px;
            background: #1e1e2e; border: 1px solid #333;
        }
        .badge {
            display: inline-block; padding: 0.1rem 0.5rem; border-radius: 10px;
            font-size: 0.65rem; font-weight: 600; margin-right: 0.5rem;
        }
        .badge-INSIGHT { background: #1e3a5f; color: #93c5fd; }
        .badge-WARNING { background: #713f12; color: #fde68a; }
        .badge-SUGGESTION { background: #14532d; color: #86efac; }
        .badge-STATUS { background: #333; color: #9ca3af; }
        .advice-title { font-size: 0.85rem; font-weight: 500; }
        .advice-body { font-size: 0.8rem; color: #aaa; margin-top: 0.3rem; }
        .dismiss {
            cursor: pointer; float: right; opacity: 0.4; font-size: 0.75rem;
        }
        .dismiss:hover { opacity: 1; }
        .turn {
            padding: 0.5rem 0.75rem; margin: 0.25rem 0; border-radius: 6px;
            font-size: 0.85rem; white-space: pre-wrap;
        }
        .turn-USER { background: #1e3a5f; margin-left: 2rem; }
        .turn-COORDINATOR { background: #1e1e2e; margin-right: 2rem; }
        .turn-SYSTEM { background: #2d2d1e; font-style: italic; opacity: 0.8; }
        .section-label {
            font-size: 0.7rem; font-weight: 600; color: #666;
            text-transform: uppercase; padding: 0.5rem; letter-spacing: 0.05em;
        }
        .action-buttons { display: flex; gap: 0.5rem; margin-top: 0.5rem; }
        .btn-approve { padding: 0.3rem 0.8rem; border: none; border-radius: 4px; background: #166534; color: white; cursor: pointer; font-size: 0.75rem; }
        .btn-reject { padding: 0.3rem 0.8rem; border: none; border-radius: 4px; background: #991b1b; color: white; cursor: pointer; font-size: 0.75rem; }
        .btn-confirm { padding: 0.3rem 0.8rem; border: none; border-radius: 4px; background: #b45309; color: white; cursor: pointer; font-size: 0.75rem; }
        .btn-cancel { padding: 0.3rem 0.8rem; border: none; border-radius: 4px; background: #333; color: #aaa; cursor: pointer; font-size: 0.75rem; }
        .action-confirm { margin-top: 0.5rem; padding: 0.5rem; background: #2d1f00; border-radius: 4px; }
        .confirm-warning { font-size: 0.8rem; color: #fde68a; margin-bottom: 0.5rem; }
        .action-status { margin-top: 0.5rem; font-size: 0.75rem; }
        .action-status.executing { color: #60a5fa; }
        .action-status.completed { color: #86efac; }
        .action-status.failed { color: #fca5a5; }
        .mode-toggle { display: flex; gap: 2px; margin-left: auto; }
        .mode-btn {
            padding: 0.2rem 0.5rem; border: 1px solid #444; border-radius: 3px;
            background: transparent; color: #888; cursor: pointer; font-size: 0.65rem;
            font-weight: 500; transition: all 0.15s;
        }
        .mode-btn.active { background: #1d4ed8; color: white; border-color: #1d4ed8; }
        .mode-btn:hover:not(.active) { background: #333; color: #ccc; }
        .mode-reset { font-size: 0.6rem; color: #4a9eff; cursor: pointer; margin-left: 0.5rem; }
        .mode-reset:hover { text-decoration: underline; }
        .header-row { display: flex; align-items: center; padding: 0.5rem; gap: 0.5rem; }
        .countdown-bar {
            display: flex; align-items: center; gap: 0.5rem; margin-top: 0.5rem;
            font-size: 0.75rem; color: #fde68a;
        }
        .countdown-ring {
            width: 20px; height: 20px; border-radius: 50%;
            border: 2px solid #fde68a; border-top-color: transparent;
            animation: spin 1s linear infinite; display: inline-block;
        }
        @keyframes spin { to { transform: rotate(360deg); } }
        .auto-badge {
            display: inline-block; padding: 0.1rem 0.4rem; border-radius: 8px;
            font-size: 0.6rem; font-weight: 600; background: #1e3a5f; color: #93c5fd;
            margin-left: 0.5rem;
        }
        .toast {
            position: fixed; bottom: 1rem; right: 1rem; padding: 0.75rem 1rem;
            border-radius: 6px; font-size: 0.8rem; z-index: 1000;
            animation: fadeIn 0.3s ease-in;
        }
        .toast-info { background: #14532d; color: #86efac; border: 1px solid #166534; }
        .toast-warning { background: #713f12; color: #fde68a; border: 1px solid #854d0e; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
    `;

    override connectedCallback() {
        super.connectedCallback();
        this._loadHistory();
        this._connectSSE();
    }

    override disconnectedCallback() {
        super.disconnectedCallback();
        this.eventSource?.close();
        this.countdownTimers.forEach(timer => clearInterval(timer));
        this.countdownTimers.clear();
    }

    private async _loadHistory() {
        const ws = encodeURIComponent(this.workspaceRoot);
        const [adviceRes, convRes, autonomyRes] = await Promise.all([
            fetch(`/api/coordinator/advice?workspace=${ws}`),
            fetch(`/api/coordinator/conversation?workspace=${ws}`),
            fetch(`/api/coordinator/autonomy?workspace=${ws}`)
        ]);
        if (adviceRes.ok) this.advice = await adviceRes.json();
        if (convRes.ok) this.conversation = await convRes.json();
        if (autonomyRes.ok) this.autonomy = await autonomyRes.json();

        for (const a of this.advice.filter(a => a.actionKey)) {
            const res = await fetch(`/api/coordinator/actions/${a.actionKey}`);
            if (res.ok) {
                const action: ProposedAction = await res.json();
                this.actions = new Map(this.actions).set(a.id, action);
                if (action.countdownEndsAt && action.status === 'PROPOSED') {
                    this._startCountdownTimer(action);
                }
            }
        }
    }

    private _connectSSE() {
        this.eventSource = new EventSource('/api/push?topics=coordinator:advice,coordinator:message,coordinator:action,coordinator:notification');
        this.eventSource.addEventListener('coordinator:advice', (e: MessageEvent) => {
            this.advice = [JSON.parse(e.data), ...this.advice];
        });
        this.eventSource.addEventListener('coordinator:message', (e: MessageEvent) => {
            this.conversation = [...this.conversation, JSON.parse(e.data)];
        });
        this.eventSource.addEventListener('coordinator:action', (e: MessageEvent) => {
            const action: ProposedAction = JSON.parse(e.data);
            if (action.status === 'APPROVED' && !this.countdownTimers.has(action.id)) {
                this.autoExecuted.add(action.adviceId);
            }
            if (action.countdownEndsAt && action.status === 'PROPOSED') {
                this._startCountdownTimer(action);
            }
            if (action.status !== 'PROPOSED') {
                this._clearCountdownTimer(action.id);
            }
            this.actions = new Map(this.actions).set(action.adviceId, action);
        });
        this.eventSource.addEventListener('coordinator:notification', (e: MessageEvent) => {
            const n = JSON.parse(e.data);
            this._showToast(n.title, n.severity, n.detail);
        });
    }

    private async _sendMessage() {
        if (!this.inputValue.trim() || this.loading) return;
        this.loading = true;
        const msg = this.inputValue;
        this.inputValue = '';
        this.conversation = [...this.conversation, {
            id: 0, workspaceRoot: this.workspaceRoot,
            role: 'USER', content: msg, timestamp: new Date().toISOString()
        }];

        try {
            const res = await fetch('/api/coordinator/message', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ workspace: this.workspaceRoot, epicRef: this.epicRef, message: msg })
            });
            if (res.ok) {
                const turn = await res.json();
                this.conversation = [...this.conversation, turn];
            }
        } finally {
            this.loading = false;
        }
    }

    private async _dismiss(id: string) {
        await fetch(`/api/coordinator/advice/${id}/dismiss`, { method: 'POST' });
        this.advice = this.advice.filter(a => a.id !== id);
    }

    private async _approveAction(id: string) {
        const res = await fetch(`/api/coordinator/actions/${id}/approve`, { method: 'POST' });
        if (res.ok) { const action = await res.json(); this.actions = new Map(this.actions).set(action.adviceId, action); }
    }

    private async _rejectAction(id: string) {
        const res = await fetch(`/api/coordinator/actions/${id}/reject`, { method: 'POST' });
        if (res.ok) { const action = await res.json(); this.actions = new Map(this.actions).set(action.adviceId, action); }
    }

    private async _confirmAction(id: string) {
        const res = await fetch(`/api/coordinator/actions/${id}/confirm`, { method: 'POST' });
        if (res.ok) { const action = await res.json(); this.actions = new Map(this.actions).set(action.adviceId, action); }
    }

    private async _cancelAction(id: string) {
        const res = await fetch(`/api/coordinator/actions/${id}/cancel`, { method: 'POST' });
        if (res.ok) { const action = await res.json(); this.actions = new Map(this.actions).set(action.adviceId, action); }
    }

    private async _setAutonomy(level: AutonomyLevel) {
        const res = await fetch(`/api/coordinator/autonomy?level=${level}`, { method: 'POST' });
        if (res.ok) this.autonomy = await res.json();
    }

    private async _resetAutonomy() {
        const res = await fetch('/api/coordinator/autonomy/reset', { method: 'POST' });
        if (res.ok) this.autonomy = await res.json();
    }

    private _startCountdownTimer(action: ProposedAction) {
        this._clearCountdownTimer(action.id);
        const timer = window.setInterval(() => {
            const deadline = new Date(action.countdownEndsAt!).getTime();
            if (Date.now() >= deadline) {
                this._clearCountdownTimer(action.id);
            }
            this.requestUpdate();
        }, 1000);
        this.countdownTimers.set(action.id, timer);
    }

    private _clearCountdownTimer(actionId: string) {
        const timer = this.countdownTimers.get(actionId);
        if (timer) {
            clearInterval(timer);
            this.countdownTimers.delete(actionId);
        }
    }

    private _remainingSeconds(action: ProposedAction): number {
        if (!action.countdownEndsAt) return 0;
        return Math.max(0, Math.ceil((new Date(action.countdownEndsAt).getTime() - Date.now()) / 1000));
    }

    private _showToast(title: string, severity: string, detail: string) {
        const toast = document.createElement('div');
        toast.className = `toast toast-${severity}`;
        toast.textContent = detail ? `${title} — ${detail}` : title;
        this.shadowRoot?.appendChild(toast);
        setTimeout(() => toast.remove(), 5000);
    }

    private _renderActionControls(action: ProposedAction) {
        switch (action.status) {
            case 'PROPOSED': {
                const remaining = this._remainingSeconds(action);
                if (action.countdownEndsAt && remaining > 0) {
                    return html`
                        <div class="countdown-bar">
                            <span class="countdown-ring"></span>
                            <span>Auto-executing in ${remaining}s</span>
                            <button class="btn-approve" @click=${() => this._approveAction(action.id)}>Approve Now</button>
                            <button class="btn-reject" @click=${() => this._rejectAction(action.id)}>Veto</button>
                        </div>`;
                }
                return html`
                    <div class="action-buttons">
                        <button class="btn-approve" @click=${() => this._approveAction(action.id)}>Approve</button>
                        <button class="btn-reject" @click=${() => this._rejectAction(action.id)}>Reject</button>
                    </div>`;
            }
            case 'CONFIRMING':
                return html`
                    <div class="action-confirm">
                        <div class="confirm-warning">${action.rationale}</div>
                        <button class="btn-confirm" @click=${() => this._confirmAction(action.id)}>Confirm</button>
                        <button class="btn-cancel" @click=${() => this._cancelAction(action.id)}>Cancel</button>
                    </div>`;
            case 'EXECUTING':
                return html`<div class="action-status executing">Executing...</div>`;
            case 'COMPLETED': {
                const isAuto = this.autoExecuted.has(action.adviceId);
                return html`<div class="action-status completed">✓ ${action.executionResult ?? 'Done'}${isAuto ? html`<span class="auto-badge">auto</span>` : nothing}</div>`;
            }
            case 'FAILED':
                return html`<div class="action-status failed">✗ ${action.executionResult ?? 'Failed'}</div>`;
            default:
                return nothing;
        }
    }

    override render() {
        return html`
            <div class="header-row">
                <div class="section-label" style="padding:0">Advisor</div>
                <div class="mode-toggle">
                    <button class="mode-btn ${this.autonomy.level === 'MANUAL' ? 'active' : ''}" @click=${() => this._setAutonomy('MANUAL')}>MANUAL</button>
                    <button class="mode-btn ${this.autonomy.level === 'OBSERVATION' ? 'active' : ''}" @click=${() => this._setAutonomy('OBSERVATION')}>OBS</button>
                    <button class="mode-btn ${this.autonomy.level === 'AUTONOMOUS' ? 'active' : ''}" @click=${() => this._setAutonomy('AUTONOMOUS')}>AUTO</button>
                    ${this.autonomy.source === 'session' ? html`<span class="mode-reset" @click=${this._resetAutonomy}>reset</span>` : nothing}
                </div>
            </div>
            <div class="advice-feed">
                ${this.advice.length === 0
                    ? html`<div class="advice-empty">No active advice</div>`
                    : this.advice.map(a => {
                        const action = a.actionKey ? this.actions.get(a.id) : null;
                        return html`
                            <div class="advice-card">
                                <span class="dismiss" @click=${() => this._dismiss(a.id)}>✕</span>
                                <span class="badge badge-${a.type}">${a.type}</span>
                                <span class="advice-title">${a.title}</span>
                                <div class="advice-body">${a.body}</div>
                                ${action ? this._renderActionControls(action) : nothing}
                            </div>
                        `;
                    })}
            </div>
            <div class="chat-area">
                <div class="section-label">Conversation</div>
                ${this.conversation.map(t => html`
                    <div class="turn turn-${t.role}">${t.content}</div>
                `)}
            </div>
            <div class="input-bar">
                <input
                    .value=${this.inputValue}
                    @input=${(e: Event) => this.inputValue = (e.target as HTMLInputElement).value}
                    @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter' && !e.shiftKey) this._sendMessage(); }}
                    placeholder="Ask the coordinator..."
                    ?disabled=${this.loading} />
                <button @click=${this._sendMessage} ?disabled=${this.loading}>
                    ${this.loading ? 'Sending...' : 'Send'}
                </button>
            </div>
        `;
    }
}
