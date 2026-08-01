import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface EnhancedRecommendation {
    base: { key: string; title: string; type: string; score: number; reason: string };
    reasoning: string;
    contextFactors: string[];
    adjustedScore: number;
    generatedAt: string;
}

@customElement('trellis-enhanced-recommendation-card')
export class EnhancedRecommendationCard extends LitElement {
    @property({ type: Object }) recommendation!: EnhancedRecommendation;
    @property() workspaceRoot = '';
    @state() private expanded = false;

    static override styles = css`
        :host { display: block; }
        .card {
            padding: 0.6rem 0.75rem; margin-bottom: 0.5rem;
            background: #252525; border: 1px solid #333; border-radius: 6px;
        }
        .header { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
        .score { font-weight: 700; font-size: 0.9rem; font-family: monospace; }
        .score-adjusted { color: #86efac; }
        .score-arrow { color: #666; font-size: 0.75rem; }
        .rec-type {
            display: inline-block; padding: 0.1rem 0.4rem; border-radius: 3px;
            font-size: 0.65rem; font-weight: 600;
        }
        .rec-type-CRITICAL_PATH { background: #1e3a5f; color: #93c5fd; }
        .rec-type-BOTTLENECK { background: #713f12; color: #fde68a; }
        .rec-title { font-size: 0.85rem; font-weight: 500; }
        .factors {
            display: flex; gap: 0.25rem; flex-wrap: wrap; margin-top: 0.4rem;
        }
        .pill {
            padding: 0.1rem 0.5rem; border-radius: 12px;
            background: #333; font-size: 0.7rem; color: #aaa;
        }
        .toggle {
            cursor: pointer; color: #4a9eff; font-size: 0.75rem;
            margin-top: 0.4rem; user-select: none;
        }
        .toggle:hover { text-decoration: underline; }
        .reasoning {
            margin-top: 0.5rem; padding: 0.5rem 0.75rem;
            background: #1a1a1a; border-radius: 4px;
            font-size: 0.8rem; color: #bbb; white-space: pre-wrap; line-height: 1.5;
        }
        .actions { display: flex; justify-content: flex-end; margin-top: 0.4rem; }
        .start-btn {
            padding: 0.2rem 0.6rem; border: 1px solid #1d4ed8; border-radius: 4px;
            background: transparent; color: #93c5fd; cursor: pointer;
            font-size: 0.7rem; font-weight: 500; transition: background 0.15s;
        }
        .start-btn:hover { background: #1e3a5f; }
    `;

    override render() {
        const r = this.recommendation;
        const scoreChanged = r.adjustedScore !== r.base.score;

        return html`
            <div class="card">
                <div class="header">
                    ${scoreChanged
                        ? html`
                            <span class="score">${r.base.score}</span>
                            <span class="score-arrow">→</span>
                            <span class="score score-adjusted">${r.adjustedScore}</span>`
                        : html`<span class="score">${r.base.score}</span>`
                    }
                    <span class="rec-type rec-type-${r.base.type}">${r.base.type.replace('_', ' ')}</span>
                    <span class="rec-title">${r.base.title}</span>
                </div>
                ${r.contextFactors.length > 0 ? html`
                    <div class="factors">
                        ${r.contextFactors.map(f => html`<span class="pill">${f}</span>`)}
                    </div>
                ` : nothing}
                <div class="toggle" @click=${() => this.expanded = !this.expanded}>
                    ${this.expanded ? '▾ Hide reasoning' : '▸ Show reasoning'}
                </div>
                ${this.expanded ? html`<div class="reasoning">${r.reasoning}</div>` : nothing}
                ${this.workspaceRoot ? html`
                    <div class="actions">
                        <button class="start-btn" @click=${this._start}>Start</button>
                    </div>
                ` : nothing}
            </div>
        `;
    }

    private _start() {
        this.dispatchEvent(new CustomEvent('start-work', {
            detail: { key: this.recommendation.base.key },
            bubbles: true, composed: true
        }));
    }
}
