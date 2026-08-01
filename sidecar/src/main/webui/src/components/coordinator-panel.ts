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

@customElement('trellis-coordinator-panel')
export class CoordinatorPanel extends LitElement {
    @property() workspaceRoot = '';
    @property() epicRef = '';
    @state() private advice: CoordinatorAdvice[] = [];
    @state() private conversation: ConversationTurn[] = [];
    @state() private inputValue = '';
    @state() private loading = false;
    private eventSource: EventSource | null = null;

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
    `;

    override connectedCallback() {
        super.connectedCallback();
        this._loadHistory();
        this._connectSSE();
    }

    override disconnectedCallback() {
        super.disconnectedCallback();
        this.eventSource?.close();
    }

    private async _loadHistory() {
        const ws = encodeURIComponent(this.workspaceRoot);
        const [adviceRes, convRes] = await Promise.all([
            fetch(`/api/coordinator/advice?workspace=${ws}`),
            fetch(`/api/coordinator/conversation?workspace=${ws}`)
        ]);
        if (adviceRes.ok) this.advice = await adviceRes.json();
        if (convRes.ok) this.conversation = await convRes.json();
    }

    private _connectSSE() {
        this.eventSource = new EventSource('/api/push?topics=coordinator:advice,coordinator:message');
        this.eventSource.addEventListener('coordinator:advice', (e: MessageEvent) => {
            this.advice = [JSON.parse(e.data), ...this.advice];
        });
        this.eventSource.addEventListener('coordinator:message', (e: MessageEvent) => {
            this.conversation = [...this.conversation, JSON.parse(e.data)];
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

    override render() {
        return html`
            <div class="advice-feed">
                <div class="section-label">Advisor</div>
                ${this.advice.length === 0
                    ? html`<div class="advice-empty">No active advice</div>`
                    : this.advice.map(a => html`
                        <div class="advice-card">
                            <span class="dismiss" @click=${() => this._dismiss(a.id)}>✕</span>
                            <span class="badge badge-${a.type}">${a.type}</span>
                            <span class="advice-title">${a.title}</span>
                            <div class="advice-body">${a.body}</div>
                        </div>
                    `)}
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
