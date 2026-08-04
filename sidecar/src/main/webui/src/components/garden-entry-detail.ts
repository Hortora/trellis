import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { marked } from 'marked';

interface ProvenanceRef {
  issueRepo: string;
  issueNumber: number;
  specName: string;
  workspace: { slotNumber: number; slotStatus: string; repos: string[] } | null;
}

@customElement('trellis-garden-entry-detail')
export class GardenEntryDetail extends LitElement {

  @property() entryId: string = '';
  @state() private _entry: any = null;
  @state() private _provenance: ProvenanceRef[] = [];
  @state() private _loading = false;
  @state() private _error: string | null = null;

  static override styles = css`
    :host { display: block; padding: 1rem; }

    .metadata {
      display: flex; gap: 0.8rem; margin-bottom: 1rem;
      padding: 0.5rem 0; border-bottom: 1px solid #333;
    }

    .badge {
      display: inline-block; padding: 0.15rem 0.5rem; border-radius: 3px;
      font-size: 0.75rem; font-weight: 500; text-transform: uppercase;
    }
    .badge-domain { background: #1e3a5f; color: #7cb3e0; }
    .badge-type { background: #3a1e5f; color: #b37ce0; }
    .badge-score { background: #1e5f3a; color: #7ce0b3; }

    .body {
      font-family: system-ui, sans-serif; font-size: 0.9rem;
      color: #ddd; line-height: 1.6;
    }
    .body :is(h1, h2, h3, h4, h5, h6) { color: #eee; margin-top: 1.5em; }
    .body h1 { font-size: 1.3rem; border-bottom: 1px solid #333; padding-bottom: 0.3rem; }
    .body h2 { font-size: 1.1rem; }
    .body code { background: #2a2a2a; padding: 0.15em 0.4em; border-radius: 3px; font-size: 0.9em; }
    .body pre { background: #2a2a2a; padding: 1rem; border-radius: 6px; overflow-x: auto; }
    .body pre code { background: none; padding: 0; }
    .body table { border-collapse: collapse; margin: 1em 0; }
    .body th, .body td { border: 1px solid #444; padding: 0.4rem 0.8rem; }
    .body th { background: #2a2a2a; }
    .body blockquote { border-left: 3px solid #444; margin: 1em 0; padding: 0.5em 1em; color: #999; }
    .body a { color: #60a5fa; }
    .body ul, .body ol { padding-left: 1.5em; }
    .body li { margin: 0.3em 0; }
    .body p { margin: 0.6em 0; }

    h3 { font-size: 1rem; color: #aaa; margin: 1.5rem 0 0.5rem; }

    .provenance-list { display: flex; flex-direction: column; gap: 0.3rem; }
    .provenance-item {
      font-size: 0.85rem; color: #bbb; padding: 0.3rem 0.5rem;
      background: #1a1a1a; border-radius: 4px;
    }
    .provenance-issue { color: #7cb3e0; }
    .provenance-slot { color: #888; font-size: 0.75rem; }

    .empty { color: #666; font-style: italic; }
    .error { color: #f87171; }
    .loading { color: #888; }
  `;

  override updated(changed: Map<string, unknown>) {
    if (changed.has('entryId') && this.entryId) {
      this._loadEntry();
    }
  }

  override render() {
    if (this._loading) return html`<div class="loading">Loading...</div>`;
    if (this._error) return html`<div class="error">${this._error}</div>`;
    if (!this._entry) return html`<div class="empty">Select an entry to view details</div>`;

    return html`
      <div class="metadata">
        <span class="badge badge-domain">${this._entry.domain}</span>
        <span class="badge badge-type">${this._entry.type}</span>
        <span class="badge badge-score">score: ${this._entry.score}</span>
      </div>

      <div class="body" .innerHTML=${marked.parse(this._entry.body || '', { async: false }) as string}></div>

      ${this._provenance.length > 0 ? html`
        <h3>Informed by this entry</h3>
        <div class="provenance-list">
          ${this._provenance.map(p => html`
            <div class="provenance-item">
              <span class="provenance-issue">${p.issueRepo}#${p.issueNumber}</span>
              ${p.specName ? html` &mdash; ${p.specName}` : nothing}
              ${p.workspace ? html`
                <span class="provenance-slot">
                  (slot ${p.workspace.slotNumber}, ${p.workspace.slotStatus})
                </span>
              ` : nothing}
            </div>
          `)}
        </div>
      ` : html`<h3>Usage</h3><div class="empty">No provenance records</div>`}
    `;
  }

  private async _loadEntry() {
    this._loading = true;
    this._error = null;
    try {
      const [entryRes, provRes] = await Promise.all([
        fetch(`/api/garden/entries?id=${encodeURIComponent(this.entryId)}`),
        fetch(`/api/garden/provenance/reverse?geId=${encodeURIComponent(this.entryId)}`),
      ]);

      if (!entryRes.ok) {
        this._entry = null;
        this._error = entryRes.status === 404 ? 'Entry not found' : 'Failed to load entry';
        this._provenance = [];
        return;
      }

      this._entry = await entryRes.json();
      this._provenance = provRes.ok ? await provRes.json() : [];
    } catch (e) {
      this._error = 'Garden service unavailable';
      this._entry = null;
      this._provenance = [];
    } finally {
      this._loading = false;
    }
  }
}
