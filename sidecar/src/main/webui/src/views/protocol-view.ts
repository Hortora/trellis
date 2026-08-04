import { LitElement, html, css, PropertyValues, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { marked } from 'marked';

interface ProtocolIndex {
  repoName: string;
  repoPath: string;
  indexPath: string;
  relativePath: string;
}

interface ProtocolEntry {
  file: string;
  summary: string;
  appliesTo: string;
  resolvedPath: string;
  section: string;
}

@customElement('trellis-protocol-view')
export class ProtocolView extends LitElement {

  @property() workspaceRoot = '';

  @state() private _repos: ProtocolIndex[] = [];
  @state() private _selectedRepo: ProtocolIndex | null = null;
  @state() private _indexes: string[] = [];
  @state() private _selectedIndex = '';
  @state() private _entries: ProtocolEntry[] = [];
  @state() private _selectedEntry: ProtocolEntry | null = null;
  @state() private _detailContent = '';
  @state() private _loading = false;

  @state() private _showAddSearch = false;
  @state() private _addQuery = '';
  @state() private _gardenResults: any[] = [];
  @state() private _searchingGarden = false;
  @state() private _selectedGardenEntry: any = null;

  static override styles = css`
    :host {
      display: grid;
      grid-template-columns: 1fr 1fr;
      grid-template-rows: minmax(0, 1fr);
      gap: 1rem;
      padding: 1rem;
      height: 100%;
      box-sizing: border-box;
      font-family: system-ui, sans-serif;
      color: #eee;
      overflow: hidden;
    }
    .left {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      overflow: hidden;
    }
    .repos-pane {
      overflow-y: auto;
      background: #1a1a2e;
      border-radius: 8px;
      padding: 1rem;
      flex-shrink: 0;
      max-height: 40%;
    }
    .entries-pane {
      overflow-y: auto;
      background: #1a1a2e;
      border-radius: 8px;
      padding: 1rem;
      flex: 1;
      min-height: 0;
    }
    .right {
      overflow-y: auto;
      background: #1a1a2e;
      border-radius: 8px;
      padding: 1rem;
    }
    h3 { margin: 0 0 0.5rem 0; color: #8888cc; font-size: 0.9rem; }
    .repo-list, .index-list { margin-bottom: 1rem; }
    .repo-item, .index-item {
      padding: 0.4rem 0.6rem;
      cursor: pointer;
      border-radius: 4px;
      margin-bottom: 2px;
      font-size: 0.85rem;
    }
    .repo-item:hover, .index-item:hover { background: #2a2a4e; }
    .repo-item.selected, .index-item.selected { background: #3a3a6e; }
    .repo-chevron { font-size: 0.7rem; margin-right: 0.3rem; color: #888; }
    .index-list { padding-left: 1.2rem; }
    .entry-row {
      padding: 0.6rem 0.8rem;
      border-bottom: 1px solid #222;
      cursor: pointer;
      transition: background 0.15s;
    }
    .entry-row:hover { background: #2a2a4e; }
    .entry-row.selected { background: #3a3a6e; }
    .entry-title-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .entry-file { font-weight: 500; color: #eee; font-size: 0.9rem; flex: 1; }
    .entry-meta { display: flex; gap: 0.5rem; margin-top: 0.3rem; align-items: center; }
    .entry-summary {
      font-size: 0.75rem; color: #888;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .entry-applies {
      display: inline-block; padding: 0.1rem 0.4rem; border-radius: 3px;
      font-size: 0.7rem; font-weight: 500; background: #1e3a5f; color: #7cb3e0;
    }
    .section-header {
      color: #666;
      font-size: 0.75rem;
      text-transform: uppercase;
      margin: 0.8rem 0 0.3rem 0;
      letter-spacing: 0.05em;
    }
    .remove-btn {
      background: none;
      border: 1px solid #633;
      color: #c66;
      border-radius: 3px;
      cursor: pointer;
      font-size: 0.7rem;
      padding: 2px 6px;
      flex-shrink: 0;
    }
    .remove-btn:hover { background: #633; color: #fcc; }
    .add-entry-btn {
      background: none;
      border: 1px solid #363;
      color: #6c6;
      border-radius: 3px;
      cursor: pointer;
      font-size: 0.7rem;
      padding: 2px 8px;
      flex-shrink: 0;
    }
    .add-entry-btn:hover { background: #363; color: #cfc; }
    .score { font-size: 0.75rem; color: #888; }
    .detail-content {
      font-size: 0.85rem;
      line-height: 1.5;
    }
    .detail-content h1, .detail-content h2, .detail-content h3 {
      color: #aaf;
    }
    .detail-content code { background: #2a2a4e; padding: 1px 4px; border-radius: 3px; }
    .detail-content pre {
      background: #12122a;
      padding: 0.8rem;
      border-radius: 4px;
      overflow-x: auto;
    }
    .badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 3px;
      font-size: 0.7rem;
      margin-right: 4px;
      margin-bottom: 4px;
    }
    .badge-scope { background: #2a4a2a; color: #8c8; }
    .badge-severity { background: #4a2a2a; color: #c88; }
    .badge-type { background: #2a2a4a; color: #88c; }
    .badges { margin-bottom: 0.8rem; }
    .empty { color: #666; font-style: italic; font-size: 0.85rem; }
    .add-btn {
      margin-top: 0.5rem;
      background: #2a4a2a;
      color: #8c8;
      border: 1px solid #3a6a3a;
      border-radius: 4px;
      padding: 0.4rem 0.8rem;
      cursor: pointer;
      font-size: 0.8rem;
    }
    .add-btn:hover { background: #3a6a3a; }
    .add-search { margin-top: 0.5rem; }
    .search-row {
      display: flex;
      gap: 0.5rem;
      margin-bottom: 0.8rem;
    }
    .add-search input {
      flex: 1;
      padding: 0.4rem 0.6rem;
      background: #12122a;
      border: 1px solid #333;
      color: #eee;
      border-radius: 4px;
      font-size: 0.85rem;
    }
    .add-search button {
      padding: 0.4rem 0.8rem;
      background: #2a2a4e;
      border: 1px solid #444;
      color: #aaf;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.85rem;
    }
  `;

  override connectedCallback() {
    super.connectedCallback();
    if (this.workspaceRoot) this._loadRepos();
  }

  override updated(changed: PropertyValues) {
    if (changed.has('workspaceRoot') && this.workspaceRoot) {
      this._loadRepos();
    }
  }

  private async _loadRepos() {
    try {
      const res = await fetch(`/api/protocols/repos?root=${encodeURIComponent(this.workspaceRoot)}`);
      if (res.ok) this._repos = await res.json();
    } catch { /* unavailable */ }
  }

  private async _selectRepo(repo: ProtocolIndex) {
    this._selectedRepo = repo;
    this._selectedIndex = '';
    this._entries = [];
    this._selectedEntry = null;
    this._detailContent = '';
    try {
      const res = await fetch(`/api/protocols/indexes?repo=${encodeURIComponent(repo.repoPath)}`);
      if (res.ok) {
        this._indexes = await res.json();
        if (this._indexes.length === 1) {
          this._selectIndex(this._indexes[0]);
        }
      }
    } catch { /* unavailable */ }
  }

  private async _selectIndex(indexRelPath: string) {
    if (!this._selectedRepo) return;
    this._selectedIndex = indexRelPath;
    this._selectedEntry = null;
    this._detailContent = '';
    const fullPath = this._selectedRepo.repoPath + '/docs/protocols/' + indexRelPath;
    try {
      const res = await fetch(`/api/protocols/entries?index=${encodeURIComponent(fullPath)}`);
      if (res.ok) this._entries = await res.json();
    } catch { /* unavailable */ }
  }

  private async _selectEntry(entry: ProtocolEntry) {
    this._selectedEntry = entry;
    this._loading = true;
    try {
      const res = await fetch(
        `/api/artifacts/content?path=${encodeURIComponent(entry.resolvedPath)}&root=${encodeURIComponent(this.workspaceRoot)}`
      );
      if (res.ok) {
        this._detailContent = await res.text();
      }
    } catch { /* unavailable */ }
    this._loading = false;
  }

  private async _removeEntry(entry: ProtocolEntry, e: Event) {
    e.stopPropagation();
    if (!this._selectedRepo || !this._selectedIndex) return;
    const fullPath = this._selectedRepo.repoPath + '/docs/protocols/' + this._selectedIndex;
    try {
      await fetch(
        `/api/protocols/entries?index=${encodeURIComponent(fullPath)}&file=${encodeURIComponent(entry.file)}`,
        { method: 'DELETE' }
      );
      this._selectIndex(this._selectedIndex);
    } catch { /* unavailable */ }
  }

  private async _searchGarden() {
    if (!this._addQuery.trim()) return;
    this._searchingGarden = true;
    try {
      const res = await fetch(`/api/garden/search?q=${encodeURIComponent(this._addQuery)}`);
      if (res.ok) {
        const data = await res.json();
        this._gardenResults = data.results || [];
      }
    } catch { /* unavailable */ }
    this._searchingGarden = false;
  }

  private async _addFromGarden(gardenEntry: any) {
    if (!this._selectedRepo || !this._selectedIndex) return;
    const slug = (gardenEntry.id || 'protocol').replace(/\//g, '-').replace('.md', '') + '.md';
    const fullPath = this._selectedRepo.repoPath + '/docs/protocols/' + this._selectedIndex;
    const id = 'PP-' + new Date().toISOString().slice(0, 10).replace(/-/g, '') + '-' + Math.random().toString(36).slice(2, 8);
    const body = {
      indexPath: fullPath,
      section: '',
      file: slug,
      summary: gardenEntry.title || '',
      appliesTo: gardenEntry.domain || 'All',
      gardenEntryId: gardenEntry.id || null,
      content: `---\nid: ${id}\ntitle: "${gardenEntry.title || ''}"\ntype: rule\nscope: universal\nseverity: guidance\napplies_to: "${gardenEntry.domain || 'All'}"\ngarden_ref: "${gardenEntry.id || ''}"\n---\n\n${gardenEntry.body || gardenEntry.title || ''}\n`
    };
    try {
      await fetch('/api/protocols/entries', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      this._showAddSearch = false;
      this._gardenResults = [];
      this._addQuery = '';
      this._selectIndex(this._selectedIndex);
    } catch { /* unavailable */ }
  }

  private _parseFrontmatter(content: string): { meta: Record<string, string>; body: string } {
    const match = content.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
    if (!match) return { meta: {}, body: content };
    const meta: Record<string, string> = {};
    for (const line of match[1].split('\n')) {
      const colonIdx = line.indexOf(':');
      if (colonIdx > 0) {
        meta[line.substring(0, colonIdx).trim()] = line.substring(colonIdx + 1).trim().replace(/^"(.*)"$/, '$1');
      }
    }
    return { meta, body: match[2] };
  }

  private _renderEntries() {
    if (this._entries.length === 0) {
      return html`<p class="empty">No protocol entries found.</p>`;
    }
    let currentSection = '';
    const items: unknown[] = [];
    for (const entry of this._entries) {
      if (entry.section && entry.section !== currentSection) {
        currentSection = entry.section;
        items.push(html`<div class="section-header">${currentSection}</div>`);
      }
      const selected = this._selectedEntry?.file === entry.file;
      items.push(html`
        <div class="entry-row ${selected ? 'selected' : ''}" @click=${() => this._selectEntry(entry)}>
          <div class="entry-title-row">
            <span class="entry-file">${entry.file.replace('.md', '')}</span>
            <button class="remove-btn" @click=${(e: Event) => this._removeEntry(entry, e)}>remove</button>
          </div>
          <div class="entry-meta">
            <span class="entry-summary">${entry.summary}</span>
          </div>
          <div class="entry-meta">
            <span class="entry-applies">${entry.appliesTo}</span>
          </div>
        </div>
      `);
    }
    return items;
  }

  private _renderDetail() {
    if (!this._selectedEntry) {
      return html`<p class="empty">Select a protocol entry to view its content.</p>`;
    }
    if (this._loading) {
      return html`<p class="empty">Loading...</p>`;
    }
    const { meta, body } = this._parseFrontmatter(this._detailContent);
    const rendered = marked.parse(body) as string;
    return html`
      <div class="badges">
        ${meta.scope ? html`<span class="badge badge-scope">${meta.scope}</span>` : nothing}
        ${meta.severity ? html`<span class="badge badge-severity">${meta.severity}</span>` : nothing}
        ${meta.type ? html`<span class="badge badge-type">${meta.type}</span>` : nothing}
      </div>
      <div class="detail-content" .innerHTML=${rendered}></div>
    `;
  }

  private _selectGardenEntry(entry: any) {
    this._selectedGardenEntry = entry;
  }

  private _renderGardenSearch() {
    return html`
      <div class="left" style="display:block; overflow-y:auto;">
        <div style="display:flex; align-items:center; gap:0.5rem; margin-bottom:0.8rem;">
          <button class="add-btn" @click=${() => { this._showAddSearch = false; this._selectedGardenEntry = null; }}>
            ← Back
          </button>
          <h3 style="margin:0;">Add from Garden</h3>
        </div>
        <div class="add-search">
          <div class="search-row">
            <input type="text" .value=${this._addQuery}
                   @input=${(e: any) => { this._addQuery = e.target.value; }}
                   @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter') this._searchGarden(); }}
                   placeholder="Search garden entries..." />
            <button @click=${() => this._searchGarden()}>Search</button>
          </div>
          ${this._searchingGarden ? html`<p class="empty">Searching...</p>` : nothing}
          ${this._gardenResults.map(r => html`
            <div class="entry-row ${this._selectedGardenEntry?.id === r.id ? 'selected' : ''}"
                 @click=${() => this._selectGardenEntry(r)}>
              <div class="entry-title-row">
                <span class="entry-file">${r.title}</span>
                <button class="add-entry-btn" @click=${(e: Event) => { e.stopPropagation(); this._addFromGarden(r); }}>add</button>
              </div>
              <div class="entry-meta">
                ${r.domain ? html`<span class="entry-applies">${r.domain}</span>` : nothing}
                ${r.type ? html`<span class="entry-applies">${r.type}</span>` : nothing}
                ${r.crossEncoderScore != null ? html`<span class="score">CE: ${r.crossEncoderScore.toFixed(1)}</span>` : nothing}
              </div>
            </div>
          `)}
        </div>
      </div>

      <div class="right">
        <h3>Entry Detail</h3>
        ${this._selectedGardenEntry ? html`
          <div class="badges">
            ${this._selectedGardenEntry.domain ? html`<span class="badge badge-scope">${this._selectedGardenEntry.domain}</span>` : nothing}
            ${this._selectedGardenEntry.type ? html`<span class="badge badge-type">${this._selectedGardenEntry.type}</span>` : nothing}
          </div>
          <div class="detail-content" .innerHTML=${marked.parse(this._selectedGardenEntry.body || '') as string}></div>
        ` : html`<p class="empty">Click a garden entry to preview it.</p>`}
      </div>
    `;
  }

  override render() {
    if (this._showAddSearch) {
      return this._renderGardenSearch();
    }
    return html`
      <div class="left">
        <div class="repos-pane">
          <h3>Repos with Protocols</h3>
          <div class="repo-list">
            ${this._repos.length === 0
              ? html`<p class="empty">No repos with protocols found.</p>`
              : this._repos.map(r => html`
                <div class="repo-item ${this._selectedRepo?.repoName === r.repoName ? 'selected' : ''}"
                     @click=${() => this._selectRepo(r)}>
                  <span class="repo-chevron">${this._selectedRepo?.repoName === r.repoName ? '▾' : '▸'}</span>
                  ${r.repoName}
                </div>
                ${this._selectedRepo?.repoName === r.repoName && this._indexes.length > 1 ? html`
                  <div class="index-list">
                    ${this._indexes.map(idx => html`
                      <div class="index-item ${this._selectedIndex === idx ? 'selected' : ''}"
                           @click=${(e: Event) => { e.stopPropagation(); this._selectIndex(idx); }}>
                        ${idx}
                      </div>
                    `)}
                  </div>
                ` : nothing}
              `)}
          </div>
        </div>

        <div class="entries-pane">
          ${this._selectedIndex ? html`
            <h3>Protocol Entries</h3>
            ${this._renderEntries()}
            <button class="add-btn" @click=${() => { this._showAddSearch = true; this._selectedGardenEntry = null; }}>
              + Add from Garden
            </button>
          ` : html`<p class="empty">Select a repo to browse its protocols.</p>`}
        </div>
      </div>

      <div class="right">
        <h3>Protocol Detail</h3>
        ${this._renderDetail()}
      </div>
    `;
  }
}
