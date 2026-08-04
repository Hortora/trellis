import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface RepoData {
  name: string;
  path: string;
  branch: string;
  remoteUrl: string | null;
}

@customElement('trellis-repo-detail')
export class TrellisRepoDetail extends LitElement {

  @property() repoName = '';
  @property() workspaceRoot = '';

  @state() private _repo: RepoData | null = null;
  @state() private _error: string | null = null;
  @state() private _loading = false;

  private _lastLoaded = '';

  static override styles = css`
    :host { display: block; height: 100%; overflow-y: auto; padding: 1.5rem; font-family: system-ui, -apple-system, sans-serif; }

    .toolbar {
      display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1.5rem;
    }
    .back-btn {
      padding: 0.3rem 0.75rem; border: 1px solid #444; border-radius: 4px;
      background: #2a2a2a; color: #ccc; cursor: pointer; font-size: 0.85rem;
    }
    .back-btn:hover { background: #333; }
    .toolbar h1 { margin: 0; font-size: 1.4rem; font-weight: 600; }

    .meta { display: flex; gap: 1rem; margin-bottom: 1.5rem; flex-wrap: wrap; }

    .badge {
      display: inline-flex; align-items: center; padding: 0.2rem 0.6rem;
      border-radius: 4px; font-size: 0.8rem; font-weight: 500;
    }
    .badge-branch { background: #1e3a5f; color: #93c5fd; }
    .badge-path { background: #333; color: #aaa; font-family: monospace; font-size: 0.75rem; }

    .remote-link {
      color: #60a5fa; font-size: 0.85rem; text-decoration: none;
    }
    .remote-link:hover { text-decoration: underline; }

    .section { margin-bottom: 1.5rem; }
    .section h2 { font-size: 1rem; font-weight: 600; margin: 0 0 0.75rem; color: #aaa; }

    .error { color: #f87171; }
    .loading { color: #666; }
  `;

  override updated(changed: Map<PropertyKey, unknown>) {
    if ((changed.has('repoName') || changed.has('workspaceRoot')) && this.repoName && this.workspaceRoot) {
      const key = `${this.workspaceRoot}:${this.repoName}`;
      if (key !== this._lastLoaded) {
        this._lastLoaded = key;
        this._loadRepo();
      }
    }
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

  override render() {
    if (this._loading) return html`<div class="loading">Loading ${this.repoName}...</div>`;
    if (this._error) return html`<div class="error">${this._error}</div>`;
    if (!this._repo) return nothing;

    const gh = this._githubUrl();

    return html`
      <div class="toolbar">
        <button class="back-btn" @click=${this._goBack}>←</button>
        <h1>${this._repo.name}</h1>
      </div>

      <div class="meta">
        <span class="badge badge-branch">${this._repo.branch}</span>
        <span class="badge badge-path">${this._repo.path}</span>
      </div>

      ${gh ? html`
        <div class="section">
          <a class="remote-link" href=${gh} target="_blank">${gh}</a>
        </div>
      ` : this._repo.remoteUrl ? html`
        <div class="section">
          <span style="color:#999;font-size:0.85rem;font-family:monospace">${this._repo.remoteUrl}</span>
        </div>
      ` : nothing}
    `;
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
}
