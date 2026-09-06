import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

const STORAGE_KEY = 'trellis:recent-roots';

export function mergeRecent(recents: string[], root: string): string[] {
  const filtered = recents.filter(r => r !== root);
  return [root, ...filtered].slice(0, 5);
}

@customElement('trellis-space-switcher')
export class TrellisSpaceSwitcher extends LitElement {

  @property() root = '';
  @state() private _open = false;
  @state() private _showAddModal = false;
  @state() private _recents: string[] = [];
  @state() private _addInput = '';

  static override styles = css`
    :host {
      display: flex;
      align-items: center;
      height: 32px;
      padding: 0 8px;
      font-family: system-ui, -apple-system, sans-serif;
      user-select: none;
      overflow: visible;
      position: relative;
    }

    .switcher {
      position: relative;
      display: flex;
      align-items: center;
      gap: 6px;
      cursor: pointer;
      padding: 3px 8px;
      border-radius: 6px;
      transition: background 0.15s;
    }
    .switcher:hover { background: #333; }

    .space-name {
      font-size: 0.85rem;
      font-weight: 600;
      color: #eee;
      max-width: 200px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .chevron {
      font-size: 0.6rem;
      color: #888;
      transition: transform 0.15s;
    }
    .chevron.open { transform: rotate(180deg); }

    .no-space {
      font-size: 0.8rem;
      color: #888;
      font-style: italic;
    }

    .dropdown {
      position: fixed;
      top: 40px;
      left: 4px;
      min-width: 320px;
      background: #1e1e1e;
      border: 1px solid #444;
      border-radius: 8px;
      box-shadow: 0 8px 24px rgba(0,0,0,0.4);
      z-index: 1000;
      overflow: hidden;
    }

    .dropdown input {
      width: 100%;
      padding: 8px 12px;
      background: #2a2a2a;
      border: none;
      border-bottom: 1px solid #333;
      color: #eee;
      font-family: monospace;
      font-size: 0.8rem;
      outline: none;
      box-sizing: border-box;
    }
    .dropdown input::placeholder { color: #666; }

    .dropdown-header {
      display: flex;
      align-items: center;
      padding: 8px 12px 4px;
      font-size: 0.65rem;
      color: #666;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .dropdown-header .spacer { flex: 1; }
    .close-btn {
      border: none; background: none; color: #666; cursor: pointer;
      font-size: 0.8rem; padding: 2px 6px; border-radius: 3px;
    }
    .close-btn:hover { background: #333; color: #eee; }

    .space-item {
      padding: 6px 12px;
      cursor: pointer;
      font-size: 0.8rem;
      color: #ccc;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .space-item:hover { background: #2a2a2a; color: #fff; }
    .space-item.active { color: #3b82f6; }
    .space-item .info { flex: 1; overflow: hidden; }
    .space-item .name { font-weight: 600; display: block; }
    .space-item .path { font-family: monospace; font-size: 0.7rem; color: #666; display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    .remove-btn {
      opacity: 0; padding: 2px 6px; border: none; background: none;
      color: #666; cursor: pointer; font-size: 0.75rem; border-radius: 3px;
    }
    .space-item:hover .remove-btn { opacity: 1; }
    .remove-btn:hover { background: #450a0a; color: #fca5a5; }

    .divider { height: 1px; background: #333; margin: 4px 0; }

    .add-item {
      padding: 8px 12px; cursor: pointer; font-size: 0.8rem;
      color: #93c5fd; display: flex; align-items: center; gap: 6px;
    }
    .add-item:hover { background: #1e3a5f; }

    .backdrop { position: fixed; inset: 0; z-index: 999; }

    .modal-backdrop {
      position: fixed; inset: 0; background: rgba(0,0,0,0.5); z-index: 200;
      display: flex; align-items: center; justify-content: center;
    }
    .modal {
      background: #1e1e1e; border: 1px solid #444; border-radius: 10px;
      padding: 1.5rem; width: 500px; max-width: 90vw;
    }
    .modal h3 { margin: 0 0 1rem; font-size: 1rem; font-weight: 600; color: #eee; }
    .modal input {
      width: 100%; padding: 0.5rem 0.75rem; background: #2a2a2a; border: 1px solid #444;
      border-radius: 6px; color: #eee; font-family: monospace; font-size: 0.85rem;
      outline: none; box-sizing: border-box;
    }
    .modal input:focus { border-color: #3b82f6; }
    .modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
    .modal-btn {
      padding: 0.4rem 1rem; border-radius: 6px; cursor: pointer; font-size: 0.85rem; border: none;
    }
    .modal-btn.cancel { background: #333; color: #ccc; }
    .modal-btn.cancel:hover { background: #444; }
    .modal-btn.primary { background: #3b82f6; color: white; }
    .modal-btn.primary:hover { background: #2563eb; }
  `;

  override connectedCallback() {
    super.connectedCallback();
    this._loadRecents();
  }

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('root') && this.root) {
      this._saveRecent(this.root);
    }
  }

  override render() {
    const name = this.root ? this.root.split('/').pop() : '';

    return html`
      <div class="switcher" @click=${this._toggle}>
        ${name
          ? html`<span class="space-name">${name}</span>`
          : html`<span class="no-space">Select space...</span>`}
        <span class="chevron ${this._open ? 'open' : ''}">▼</span>
      </div>
      ${this._open ? html`
        <div class="backdrop" @click=${this._close}></div>
        <div class="dropdown">
          <div class="dropdown-header">
            <span>Spaces</span>
            <span class="spacer"></span>
            <button class="close-btn" @click=${(e: Event) => { e.stopPropagation(); this._close(); }}
                    title="Close">✕</button>
          </div>
          ${this._recents.map(r => html`
            <div class="space-item ${r === this.root ? 'active' : ''}"
                 @click=${(e: Event) => { e.stopPropagation(); this._selectRoot(r); }}>
              <div class="info">
                <span class="name">${r.split('/').pop()}</span>
                <span class="path">${r}</span>
              </div>
              <button class="remove-btn" @click=${(e: Event) => { e.stopPropagation(); this._removeRecent(r); }}
                      title="Remove space">✕</button>
            </div>
          `)}
          <div class="divider"></div>
          <div class="add-item" @click=${(e: Event) => { e.stopPropagation(); this._openAddModal(); }}>
            + Add space...
          </div>
        </div>
      ` : nothing}
      ${this._showAddModal ? this._renderAddModal() : nothing}
    `;
  }

  private _renderAddModal() {
    return html`
      <div class="modal-backdrop" @click=${this._closeAddModal}>
        <div class="modal" @click=${(e: Event) => e.stopPropagation()}>
          <h3>Add Space</h3>
          <input
            type="text"
            placeholder="Workspace root path (e.g., /Users/me/claude/casehub)"
            .value=${this._addInput}
            @input=${(e: Event) => { this._addInput = (e.target as HTMLInputElement).value; }}
            @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter') this._confirmAdd(); }}
          />
          <div class="modal-actions">
            <button class="modal-btn cancel" @click=${this._closeAddModal}>Cancel</button>
            <button class="modal-btn primary" @click=${this._confirmAdd}>Add &amp; Scan</button>
          </div>
        </div>
      </div>
    `;
  }

  private _toggle() {
    this._open = !this._open;
  }

  private _close() {
    this._open = false;
  }

  private _openAddModal() {
    this._open = false;
    this._addInput = '';
    this._showAddModal = true;
  }

  private _closeAddModal() {
    this._showAddModal = false;
  }

  private _confirmAdd() {
    if (!this._addInput.trim()) return;
    this._showAddModal = false;
    this._selectRoot(this._addInput.trim());
  }

  private _selectRoot(root: string) {
    this._saveRecent(root);
    this._open = false;
    this.dispatchEvent(new CustomEvent('space-change', {
      bubbles: true, composed: true,
      detail: { root },
    }));
  }

  private _removeRecent(root: string) {
    this._recents = this._recents.filter(r => r !== root);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(this._recents));
    if (this.root === root) {
      this.dispatchEvent(new CustomEvent('space-change', {
        bubbles: true, composed: true,
        detail: { root: this._recents[0] ?? '' },
      }));
    }
  }

  private _loadRecents() {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      this._recents = stored ? JSON.parse(stored) : [];
    } catch { this._recents = []; }
  }

  private _saveRecent(root: string) {
    this._recents = mergeRecent(this._recents, root);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(this._recents));
  }
}
