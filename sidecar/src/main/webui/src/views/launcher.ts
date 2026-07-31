import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';

interface ProjectEntry {
  id: string;
  name: string;
  description: string;
  parentRepoUrl: string;
  setupCommand: string;
  expectedStructure: string[];
}

interface RecentWorkspace {
  root: string;
  lastOpened: string;
}

@customElement('trellis-launcher')
export class TrellisLauncher extends LitElement {

  @state() private _projects: ProjectEntry[] = [];
  @state() private _recents: RecentWorkspace[] = [];
  @state() private _bootstrapping: string | null = null;
  @state() private _progressMessages: string[] = [];
  @state() private _error: string | null = null;

  static override styles = css`
    :host {
      display: block;
      max-width: 700px;
      margin: 3rem auto;
      font-family: system-ui, sans-serif;
      color: #eee;
    }

    h1 { font-size: 1.6rem; font-weight: 600; margin-bottom: 0.5rem; }
    h2 { font-size: 1.1rem; font-weight: 500; color: #aaa; margin: 2rem 0 0.8rem; }

    .recents { display: flex; flex-direction: column; gap: 0.4rem; }
    .recent {
      display: flex; align-items: center; gap: 1rem;
      padding: 0.6rem 1rem; background: #1a1a1a; border-radius: 6px;
      cursor: pointer; transition: background 0.15s;
    }
    .recent:hover { background: #252525; }
    .recent-path { font-size: 0.85rem; color: #888; }

    .projects { display: flex; flex-direction: column; gap: 0.4rem; }
    .project {
      display: flex; justify-content: space-between; align-items: center;
      padding: 0.6rem 1rem; background: #1a1a1a; border-radius: 6px;
    }
    .project-info { flex: 1; }
    .project-name { font-weight: 500; }
    .project-desc { font-size: 0.8rem; color: #888; margin-top: 0.2rem; }

    button {
      padding: 0.4rem 1rem; border-radius: 4px; border: 1px solid #444;
      background: #2a2a2a; color: #eee; cursor: pointer; font-size: 0.85rem;
      transition: background 0.15s;
    }
    button:hover { background: #333; }
    button:disabled { opacity: 0.5; cursor: not-allowed; }

    .progress {
      margin-top: 1rem; padding: 1rem; background: #111; border-radius: 6px;
      font-family: monospace; font-size: 0.8rem; max-height: 200px; overflow-y: auto;
    }
    .progress-line { color: #6ee7b7; margin: 0.2rem 0; }
    .progress-error { color: #f87171; }

    .error { color: #f87171; margin-top: 0.5rem; font-size: 0.85rem; }
    .empty { color: #666; font-style: italic; }
  `;

  override connectedCallback() {
    super.connectedCallback();
    this._loadProjects();
    this._loadRecents();
  }

  override render() {
    return html`
      <h1>Trellis</h1>

      ${this._recents.length > 0 ? html`
        <h2>Recent Workspaces</h2>
        <div class="recents">
          ${this._recents.map(r => html`
            <div class="recent" @click=${() => this._openWorkspace(r.root)}>
              <span>${r.root.split('/').pop()}</span>
              <span class="recent-path">${r.root}</span>
            </div>
          `)}
        </div>
      ` : nothing}

      <h2>Set Up a Project</h2>
      ${this._projects.length === 0 ? html`
        <div class="empty">No projects available.</div>
      ` : html`
        <div class="projects">
          ${this._projects.map(p => html`
            <div class="project">
              <div class="project-info">
                <div class="project-name">${p.name}</div>
                <div class="project-desc">${p.description}</div>
              </div>
              <button
                ?disabled=${this._bootstrapping !== null}
                @click=${() => this._startBootstrap(p.id)}
              >${this._bootstrapping === p.id ? 'Setting up...' : 'Set up'}</button>
            </div>
          `)}
        </div>
      `}

      ${this._bootstrapping ? html`
        <div class="progress">
          ${this._progressMessages.map(m => html`
            <div class="progress-line">${m}</div>
          `)}
        </div>
      ` : nothing}

      ${this._error ? html`<div class="error">${this._error}</div>` : nothing}
    `;
  }

  private async _loadProjects() {
    try {
      const res = await fetch('/api/projects');
      this._projects = await res.json();
    } catch (e) {
      this._error = 'Failed to load projects';
    }
  }

  private async _loadRecents() {
    try {
      const stored = localStorage.getItem('trellis-recent-workspaces');
      if (stored) this._recents = JSON.parse(stored);
    } catch {
      this._recents = [];
    }
  }

  private _openWorkspace(root: string) {
    location.hash = `#?root=${encodeURIComponent(root)}`;
  }

  private async _startBootstrap(projectId: string) {
    this._bootstrapping = projectId;
    this._progressMessages = [];
    this._error = null;

    try {
      const res = await fetch(`/api/projects/${projectId}/bootstrap`, { method: 'POST' });
      if (!res.ok) {
        const body = await res.json();
        this._error = body.error || 'Bootstrap failed to start';
        this._bootstrapping = null;
        return;
      }

      const evtSource = new EventSource(`/api/projects/${projectId}/progress`);
      evtSource.addEventListener('clone', (e) => {
        this._progressMessages = [...this._progressMessages, (e as MessageEvent).data];
      });
      evtSource.addEventListener('setup', (e) => {
        this._progressMessages = [...this._progressMessages, (e as MessageEvent).data];
      });
      evtSource.addEventListener('done', (e) => {
        this._progressMessages = [...this._progressMessages, (e as MessageEvent).data];
        this._bootstrapping = null;
        evtSource.close();
      });
      evtSource.addEventListener('failed', (e) => {
        this._progressMessages = [...this._progressMessages, `ERROR: ${(e as MessageEvent).data}`];
        this._error = (e as MessageEvent).data;
        this._bootstrapping = null;
        evtSource.close();
      });
    } catch (e) {
      this._error = 'Failed to start bootstrap';
      this._bootstrapping = null;
    }
  }
}
