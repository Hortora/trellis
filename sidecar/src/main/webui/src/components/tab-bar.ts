import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';

export interface Tab {
  id: string;
  label: string;
  route: string;
}

@customElement('trellis-tab-bar')
export class TrellisTabBar extends LitElement {

  @property({ type: Array }) tabs: Tab[] = [];
  @property({ type: String }) activeId = '';

  private _dragSourceIndex = -1;

  static override styles = css`
    :host {
      display: flex;
      background: #111;
      border-bottom: 1px solid #333;
      flex-shrink: 0;
      overflow-x: auto;
      user-select: none;
    }

    .tab {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0.4rem 0.8rem;
      cursor: pointer;
      font-size: 0.8rem;
      color: #888;
      border: none;
      background: none;
      border-bottom: 2px solid transparent;
      white-space: nowrap;
      transition: color 0.15s, border-color 0.15s;
    }
    .tab:hover { color: #ccc; }
    .tab.active { color: #eee; border-bottom-color: #3b82f6; }
    .tab.drag-over { border-bottom-color: #f59e0b; }

    .detach-btn {
      opacity: 0;
      background: none;
      border: none;
      color: #666;
      cursor: pointer;
      font-size: 0.7rem;
      padding: 0 0.2rem;
      transition: opacity 0.15s;
    }
    .tab:hover .detach-btn { opacity: 1; }
    .detach-btn:hover { color: #3b82f6; }
  `;

  override render() {
    return html`
      ${this.tabs.map((tab, i) => html`
        <div
          class="tab ${tab.id === this.activeId ? 'active' : ''}"
          draggable="true"
          @click=${() => this._onSelect(tab)}
          @dragstart=${(e: DragEvent) => this._onDragStart(e, i)}
          @dragover=${(e: DragEvent) => this._onDragOver(e, i)}
          @dragleave=${(e: DragEvent) => this._onDragLeave(e)}
          @drop=${(e: DragEvent) => this._onDrop(e, i)}
        >
          ${tab.label}
          <button
            class="detach-btn"
            title="Detach to new window"
            @click=${(e: Event) => { e.stopPropagation(); this._onDetach(tab); }}
          >⧉</button>
        </div>
      `)}
    `;
  }

  private _onSelect(tab: Tab) {
    this.dispatchEvent(new CustomEvent('tab-select', { detail: tab, bubbles: true, composed: true }));
  }

  private _onDetach(tab: Tab) {
    this.dispatchEvent(new CustomEvent('tab-detach', { detail: tab, bubbles: true, composed: true }));
  }

  private _onDragStart(e: DragEvent, index: number) {
    this._dragSourceIndex = index;
    e.dataTransfer!.effectAllowed = 'move';
    e.dataTransfer!.setData('text/plain', String(index));
  }

  private _onDragOver(e: DragEvent, _index: number) {
    e.preventDefault();
    e.dataTransfer!.dropEffect = 'move';
    (e.currentTarget as HTMLElement).classList.add('drag-over');
  }

  private _onDragLeave(e: DragEvent) {
    (e.currentTarget as HTMLElement).classList.remove('drag-over');
  }

  private _onDrop(e: DragEvent, targetIndex: number) {
    e.preventDefault();
    (e.currentTarget as HTMLElement).classList.remove('drag-over');
    if (this._dragSourceIndex === targetIndex) return;

    this.dispatchEvent(new CustomEvent('tab-reorder', {
      detail: { from: this._dragSourceIndex, to: targetIndex },
      bubbles: true,
      composed: true,
    }));
    this._dragSourceIndex = -1;
  }
}
