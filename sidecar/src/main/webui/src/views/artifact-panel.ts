import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { marked } from 'marked';

interface ArtifactEntry {
  type: string;
  name: string;
  path: string;
  modifiedAt: string;
}

interface ArtifactGroup {
  type: string;
  label: string;
  entries: ArtifactEntry[];
  expanded: boolean;
}

const TYPE_LABELS: Record<string, string> = {
  spec: 'Specs',
  adr: 'ADRs',
  plan: 'Plans',
  blog: 'Blog',
  handover: 'Handovers',
  design: 'Design',
  journal: 'Journals',
};

const TYPE_ORDER = ['spec', 'adr', 'plan', 'blog', 'handover', 'design', 'journal'];

@customElement('trellis-artifact-panel')
export class TrellisArtifactPanel extends LitElement {

  @property() workspaceRoot = '';

  @state() private _groups: ArtifactGroup[] = [];
  @state() private _selectedPath: string | null = null;
  @state() private _content = '';
  @state() private _loading = false;
  @state() private _listLoading = false;
  @state() private _error: string | null = null;

  private _contentCache = new Map<string, string>();
  private _lastRoot = '';

  static override styles = css`
    :host { display: flex; height: 100%; font-family: system-ui, -apple-system, sans-serif; }

    .sidebar {
      width: 260px; min-width: 200px; max-width: 400px;
      background: #1a1a1a; border-right: 1px solid #333;
      overflow-y: auto; flex-shrink: 0; padding: 0.5rem 0;
    }

    .group-header {
      display: flex; align-items: center; gap: 0.4rem;
      padding: 0.4rem 1rem; cursor: pointer; user-select: none;
      font-size: 0.75rem; font-weight: 600; color: #888;
      text-transform: uppercase; letter-spacing: 0.05em;
    }
    .group-header:hover { color: #aaa; }
    .group-header .chevron { font-size: 0.6rem; transition: transform 0.15s; }
    .group-header .chevron.expanded { transform: rotate(90deg); }
    .group-header .count { color: #555; font-weight: 400; }

    .artifact-item {
      padding: 0.3rem 1rem 0.3rem 2rem;
      font-size: 0.8rem; color: #ccc; cursor: pointer;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .artifact-item:hover { background: #252525; }
    .artifact-item[data-selected] { background: #1e3a5f; color: #93c5fd; }

    .content-pane {
      flex: 1; overflow-y: auto; padding: 2rem;
      color: #ccc; line-height: 1.6;
    }

    .content-pane .placeholder { color: #555; font-size: 0.9rem; }
    .content-pane .error { color: #f87171; }

    .content-pane :is(h1, h2, h3, h4, h5, h6) { color: #eee; margin-top: 1.5em; }
    .content-pane h1 { font-size: 1.5rem; border-bottom: 1px solid #333; padding-bottom: 0.3rem; }
    .content-pane h2 { font-size: 1.25rem; }
    .content-pane code { background: #2a2a2a; padding: 0.15em 0.4em; border-radius: 3px; font-size: 0.9em; }
    .content-pane pre { background: #2a2a2a; padding: 1rem; border-radius: 6px; overflow-x: auto; }
    .content-pane pre code { background: none; padding: 0; }
    .content-pane table { border-collapse: collapse; margin: 1em 0; }
    .content-pane th, .content-pane td { border: 1px solid #444; padding: 0.4rem 0.8rem; }
    .content-pane th { background: #2a2a2a; }
    .content-pane blockquote { border-left: 3px solid #444; margin: 1em 0; padding: 0.5em 1em; color: #999; }
    .content-pane a { color: #60a5fa; }
    .content-pane img { max-width: 100%; }

    .spinner { color: #666; font-size: 0.85rem; }
  `;

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('workspaceRoot') && this.workspaceRoot !== this._lastRoot) {
      this._lastRoot = this.workspaceRoot;
      this._contentCache.clear();
      this._selectedPath = null;
      this._content = '';
      this._loadArtifacts();
    }
  }

  override connectedCallback() {
    super.connectedCallback();
    if (this.workspaceRoot) this._loadArtifacts();
  }

  private async _loadArtifacts() {
    if (!this.workspaceRoot) return;
    this._listLoading = true;
    this._error = null;
    try {
      const res = await fetch(`/api/artifacts?root=${encodeURIComponent(this.workspaceRoot)}`);
      if (!res.ok) { this._error = `Failed to load artifacts: ${res.status}`; return; }
      const entries: ArtifactEntry[] = await res.json();
      this._groups = this._groupEntries(entries);
    } catch (e) {
      this._error = `Failed to load artifacts: ${e}`;
    } finally {
      this._listLoading = false;
    }
  }

  private _groupEntries(entries: ArtifactEntry[]): ArtifactGroup[] {
    const byType = new Map<string, ArtifactEntry[]>();
    for (const e of entries) {
      const list = byType.get(e.type) ?? [];
      list.push(e);
      byType.set(e.type, list);
    }
    return TYPE_ORDER
      .filter(t => byType.has(t))
      .map(t => ({
        type: t,
        label: TYPE_LABELS[t] ?? t,
        entries: byType.get(t)!,
        expanded: true,
      }));
  }

  private async _selectArtifact(path: string) {
    this._selectedPath = path;
    this._error = null;

    const cached = this._contentCache.get(path);
    if (cached) { this._content = cached; return; }

    this._loading = true;
    try {
      const res = await fetch(`/api/artifacts/content?path=${encodeURIComponent(path)}&root=${encodeURIComponent(this.workspaceRoot)}`);
      if (!res.ok) { this._error = `Failed to load content: ${res.status}`; this._loading = false; return; }
      const text = await res.text();
      this._contentCache.set(path, text);
      this._content = text;
    } catch (e) {
      this._error = `Failed to load content: ${e}`;
    } finally {
      this._loading = false;
    }
  }

  private _toggleGroup(type: string) {
    this._groups = this._groups.map(g =>
      g.type === type ? { ...g, expanded: !g.expanded } : g
    );
  }

  override render() {
    return html`
      <div class="sidebar">
        ${this._listLoading ? html`<div class="spinner" style="padding:1rem">Loading...</div>` :
          this._groups.length === 0 ? html`<div class="spinner" style="padding:1rem">No artifacts found</div>` :
          this._groups.map(g => html`
            <div class="group-header" @click=${() => this._toggleGroup(g.type)}>
              <span class="chevron ${g.expanded ? 'expanded' : ''}">&#9654;</span>
              ${g.label}
              <span class="count">(${g.entries.length})</span>
            </div>
            ${g.expanded ? g.entries.map(e => html`
              <div class="artifact-item"
                   ?data-selected=${e.path === this._selectedPath}
                   @click=${() => this._selectArtifact(e.path)}>
                ${e.name}
              </div>
            `) : nothing}
          `)}
      </div>
      <div class="content-pane">
        ${this._loading ? html`<div class="spinner">Loading...</div>` :
          this._error ? html`<div class="error">${this._error}</div>` :
          !this._selectedPath ? html`<div class="placeholder">Select an artifact to view</div>` :
          nothing}
        ${!this._loading && !this._error && this._selectedPath ? html`
          <div .innerHTML=${marked.parse(this._content, { async: false }) as string}></div>
        ` : nothing}
      </div>
    `;
  }
}
