import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import '../components/garden-search-results';
import '../components/garden-entry-detail';

interface StatsData {
  totalRecords: number;
  uniqueEntries: number;
  uniqueIssues: number;
  topReferenced: { geId: string; referenceCount: number }[];
}

@customElement('trellis-garden-view')
export class GardenView extends LitElement {

  @state() private _query = '';
  @state() private _domain = '';
  @state() private _type = '';
  @state() private _results: any[] = [];
  @state() private _selectedEntryId: string | null = null;
  @state() private _searching = false;
  @state() private _unavailable = false;
  @state() private _reindexing = false;
  @state() private _stats: StatsData | null = null;

  static override styles = css`
    :host {
      display: grid; grid-template-columns: 1fr 1fr; grid-template-rows: minmax(0, 1fr);
      gap: 1rem; padding: 1rem; height: 100%; box-sizing: border-box;
      font-family: system-ui, sans-serif; color: #eee; overflow: hidden;
    }

    .left { display: flex; flex-direction: column; overflow: hidden; min-height: 0; }
    .right { overflow-y: auto; min-height: 0; }

    .search-bar {
      display: flex; gap: 0.5rem; margin-bottom: 0.8rem; flex-wrap: wrap;
    }

    input {
      flex: 1; min-width: 200px; padding: 0.5rem 0.8rem;
      background: #1a1a1a; border: 1px solid #333; border-radius: 4px;
      color: #eee; font-size: 0.9rem;
    }
    input:focus { outline: none; border-color: #555; }

    select {
      padding: 0.4rem; background: #1a1a1a; border: 1px solid #333;
      border-radius: 4px; color: #eee; font-size: 0.8rem;
    }

    button {
      padding: 0.4rem 1rem; border-radius: 4px; border: 1px solid #444;
      background: #2a2a2a; color: #eee; cursor: pointer; font-size: 0.85rem;
    }
    button:hover { background: #333; }

    .results-container { flex: 1; overflow-y: auto; }

    .status { font-size: 0.8rem; color: #888; margin-bottom: 0.5rem; }
    .unavailable {
      padding: 2rem; text-align: center; color: #f87171;
    }
    .reindexing {
      padding: 0.5rem; text-align: center; color: #fbbf24;
      background: #3a2e1e; border-radius: 4px; margin-bottom: 0.5rem;
    }

    h2 { font-size: 1.1rem; font-weight: 500; color: #aaa; margin: 0 0 0.5rem; }

    .stats {
      margin-top: 1rem; padding: 0.8rem; background: #111; border-radius: 6px;
    }
    .stat-row {
      display: flex; justify-content: space-between; font-size: 0.8rem;
      padding: 0.2rem 0; color: #aaa;
    }
    .stat-value { color: #eee; font-weight: 500; }

    .top-entries { margin-top: 0.5rem; }
    .top-entry {
      display: flex; justify-content: space-between; font-size: 0.8rem;
      padding: 0.15rem 0; color: #bbb; cursor: pointer;
    }
    .top-entry:hover { color: #eee; }
    .top-count { color: #7cb3e0; }
  `;

  override connectedCallback() {
    super.connectedCallback();
    this._loadStats();
  }

  override render() {
    if (this._unavailable) {
      return html`
        <div class="unavailable">
          <h2>Garden Unavailable</h2>
          <p>Engine not running. Start the garden engine and retry.</p>
          <button @click=${this._loadStats}>Retry</button>
        </div>
      `;
    }

    return html`
      <div class="left">
        <h2>Garden Search</h2>

        <div class="search-bar">
          <input type="text" placeholder="Search garden entries..."
                 .value=${this._query}
                 @input=${(e: InputEvent) => this._query = (e.target as HTMLInputElement).value}
                 @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter') this._search(); }}>
          <select @change=${(e: Event) => this._domain = (e.target as HTMLSelectElement).value}>
            <option value="">All domains</option>
            <option value="jvm">jvm</option>
            <option value="quarkus">quarkus</option>
            <option value="python">python</option>
            <option value="tools">tools</option>
            <option value="web">web</option>
            <option value="electron">electron</option>
          </select>
          <select @change=${(e: Event) => this._type = (e.target as HTMLSelectElement).value}>
            <option value="">All types</option>
            <option value="gotcha">gotcha</option>
            <option value="technique">technique</option>
            <option value="undocumented">undocumented</option>
            <option value="pattern">pattern</option>
          </select>
          <button @click=${this._search} ?disabled=${this._searching}>
            ${this._searching ? 'Searching...' : 'Search'}
          </button>
        </div>

        ${this._reindexing ? html`
          <div class="reindexing">Garden is re-indexing — search temporarily unavailable</div>
        ` : nothing}

        ${this._results.length > 0 ? html`
          <div class="status">${this._results.length} result(s)</div>
        ` : nothing}

        <div class="results-container">
          <trellis-garden-search-results
            .results=${this._results}
            @entry-selected=${(e: CustomEvent) => this._selectedEntryId = e.detail.id}>
          </trellis-garden-search-results>
        </div>

        ${this._stats ? html`
          <div class="stats">
            <div class="stat-row">
              <span>Provenance records</span>
              <span class="stat-value">${this._stats.totalRecords}</span>
            </div>
            <div class="stat-row">
              <span>Entries referenced</span>
              <span class="stat-value">${this._stats.uniqueEntries}</span>
            </div>
            <div class="stat-row">
              <span>Issues tracked</span>
              <span class="stat-value">${this._stats.uniqueIssues}</span>
            </div>
            ${this._stats.topReferenced.length > 0 ? html`
              <div class="top-entries">
                <div class="stat-row" style="color:#666;font-size:0.7rem;">Top referenced</div>
                ${this._stats.topReferenced.slice(0, 5).map(e => html`
                  <div class="top-entry" @click=${() => this._selectedEntryId = e.geId}>
                    <span>${e.geId}</span>
                    <span class="top-count">${e.referenceCount}x</span>
                  </div>
                `)}
              </div>
            ` : nothing}
          </div>
        ` : nothing}
      </div>

      <div class="right">
        <h2>Entry Detail</h2>
        <trellis-garden-entry-detail
          .entryId=${this._selectedEntryId || ''}>
        </trellis-garden-entry-detail>
      </div>
    `;
  }

  private async _search() {
    if (!this._query.trim()) return;
    this._searching = true;
    this._reindexing = false;
    try {
      const params = new URLSearchParams({ q: this._query });
      if (this._domain) params.set('domain', this._domain);
      if (this._type) params.set('type', this._type);

      const res = await fetch(`/api/garden/search?${params}`);
      const data = await res.json();

      if (data.available === false) {
        this._unavailable = true;
        this._results = [];
        return;
      }

      if (data.collectionReady === false) {
        this._reindexing = true;
        this._results = [];
        return;
      }

      this._results = data.results || [];
    } catch {
      this._unavailable = true;
      this._results = [];
    } finally {
      this._searching = false;
    }
  }

  private async _loadStats() {
    try {
      const res = await fetch('/api/garden/stats');
      const data = await res.json();
      if (data.available === false) {
        this._unavailable = true;
        return;
      }
      this._unavailable = false;
      this._stats = data;
    } catch {
      this._stats = null;
    }
  }
}
