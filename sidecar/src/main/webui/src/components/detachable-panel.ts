import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import './tab-bar';
import type { Tab } from './tab-bar';

declare global {
  interface Window {
    trellis: {
      detachPanel: (panelId: string, opts: { route: string; bounds?: object }) => Promise<number>;
      attachPanel: (panelId: string, targetWindowId: number) => Promise<void>;
      saveLayout: (workspacePath: string) => Promise<void>;
      loadLayout: (workspacePath: string) => Promise<unknown>;
      listWindows: () => Promise<Array<{ id: number; bounds: object }>>;
      createWindow: (route: string, opts?: object) => Promise<number>;
      getVersion: () => Promise<string>;
    };
  }
}

@customElement('trellis-detachable-panel')
export class TrellisDetachablePanel extends LitElement {

  @property({ type: Array }) tabs: Tab[] = [];
  @state() private _activeTabId = '';
  @state() private _orderedTabs: Tab[] = [];

  static override styles = css`
    :host { display: flex; flex-direction: column; height: 100%; }
    .panel-content { flex: 1; min-height: 0; overflow: auto; }
  `;

  override willUpdate(changed: Map<string, unknown>) {
    if (changed.has('tabs')) {
      this._orderedTabs = [...this.tabs];
      if (!this._activeTabId && this._orderedTabs.length > 0) {
        this._activeTabId = this._orderedTabs[0].id;
      }
    }
  }

  override render() {
    return html`
      <trellis-tab-bar
        .tabs=${this._orderedTabs}
        .activeId=${this._activeTabId}
        @tab-select=${this._onTabSelect}
        @tab-detach=${this._onTabDetach}
        @tab-reorder=${this._onTabReorder}
      ></trellis-tab-bar>
      <div class="panel-content">
        ${this._renderActivePanel()}
      </div>
    `;
  }

  private _renderActivePanel() {
    const tab = this._orderedTabs.find(t => t.id === this._activeTabId);
    if (!tab) return nothing;

    this.dispatchEvent(new CustomEvent('panel-activated', {
      detail: tab,
      bubbles: true,
      composed: true,
    }));

    return html`<slot name=${tab.id}></slot>`;
  }

  private _onTabSelect(e: CustomEvent<Tab>) {
    this._activeTabId = e.detail.id;
  }

  private async _onTabDetach(e: CustomEvent<Tab>) {
    const tab = e.detail;
    try {
      await window.trellis.detachPanel(tab.id, { route: tab.route });
      this._orderedTabs = this._orderedTabs.filter(t => t.id !== tab.id);
      if (this._activeTabId === tab.id && this._orderedTabs.length > 0) {
        this._activeTabId = this._orderedTabs[0].id;
      }
      this.requestUpdate();
    } catch (err) {
      console.error('Failed to detach panel:', err);
    }
  }

  private _onTabReorder(e: CustomEvent<{ from: number; to: number }>) {
    const { from, to } = e.detail;
    const tabs = [...this._orderedTabs];
    const [moved] = tabs.splice(from, 1);
    tabs.splice(to, 0, moved);
    this._orderedTabs = tabs;
    this.requestUpdate();
  }
}
