import { LitElement, html, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';

export interface GardenSearchResult {
  id: string;
  title: string;
  domain: string;
  type: string;
  score: number;
  body: string;
  relevance: number;
  crossEncoderScore: number | null;
}

@customElement('trellis-garden-search-results')
export class GardenSearchResults extends LitElement {

  @property({ type: Array }) results: GardenSearchResult[] = [];

  static override styles = css`
    :host { display: block; }

    .result {
      padding: 0.6rem 0.8rem; border-bottom: 1px solid #222;
      cursor: pointer; transition: background 0.15s;
    }
    .result:hover { background: #1a1a1a; }

    .result-title { font-weight: 500; color: #eee; font-size: 0.9rem; }

    .result-meta { display: flex; gap: 0.5rem; margin-top: 0.3rem; align-items: center; }

    .badge {
      display: inline-block; padding: 0.1rem 0.4rem; border-radius: 3px;
      font-size: 0.7rem; font-weight: 500; text-transform: uppercase;
    }
    .badge-domain { background: #1e3a5f; color: #7cb3e0; }
    .badge-type { background: #3a1e5f; color: #b37ce0; }

    .score { font-size: 0.75rem; color: #888; }

    .empty { color: #666; font-style: italic; padding: 1rem; }
  `;

  override render() {
    if (this.results.length === 0) {
      return html`<div class="empty">No results</div>`;
    }

    return html`
      ${this.results.map(r => html`
        <div class="result" @click=${() => this._select(r)}>
          <div class="result-title">${r.title}</div>
          <div class="result-meta">
            <span class="badge badge-domain">${r.domain}</span>
            <span class="badge badge-type">${r.type}</span>
            <span class="score">
              ${r.crossEncoderScore != null
                ? `CE: ${r.crossEncoderScore.toFixed(1)}`
                : `rel: ${r.relevance.toFixed(2)}`}
            </span>
          </div>
        </div>
      `)}
    `;
  }

  private _select(result: GardenSearchResult) {
    this.dispatchEvent(new CustomEvent('entry-selected', {
      detail: { id: this._extractGeId(result.id), title: result.title },
      bubbles: true, composed: true,
    }));
  }

  private _extractGeId(rawId: string): string {
    const withoutExt = rawId.replace(/\.md$/, '');
    const filename = withoutExt.includes('/') ? withoutExt.substring(withoutExt.lastIndexOf('/') + 1) : withoutExt;
    if (/^GE-\d{8}-[0-9a-f]{6}$/.test(filename)) return filename;
    if (/^GE-\d+$/.test(filename)) return filename;
    return withoutExt;
  }
}
