import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { WebglAddon } from '@xterm/addon-webgl';

@customElement('trellis-terminal-panel')
export class TrellisTerminalPanel extends LitElement {

  @property() sessionName = '';
  @property({ type: Number }) cols = 120;
  @property({ type: Number }) rows = 40;

  @state() private _connected = false;
  @state() private _error: string | null = null;

  private _terminal: Terminal | null = null;
  private _fitAddon: FitAddon | null = null;
  private _ws: WebSocket | null = null;
  private _resizeObserver: ResizeObserver | null = null;

  static override styles = css`
    :host { display: block; height: 100%; background: #1e1e1e; position: relative; }
    .terminal-container { width: 100%; height: 100%; }
    .status {
      position: absolute; top: 4px; right: 8px; font-size: 11px;
      padding: 2px 6px; border-radius: 3px; z-index: 1;
    }
    .status.connected { background: #166534; color: #86efac; }
    .status.disconnected { background: #7f1d1d; color: #fca5a5; }
    .error { color: #f87171; padding: 1rem; font-family: monospace; font-size: 0.85rem; }
  `;

  override render() {
    return html`
      <span class="status ${this._connected ? 'connected' : 'disconnected'}">
        ${this._connected ? 'connected' : 'disconnected'}
      </span>
      ${this._error ? html`<div class="error">${this._error}</div>` : ''}
      <div class="terminal-container"></div>
    `;
  }

  override firstUpdated() {
    const container = this.renderRoot.querySelector('.terminal-container') as HTMLElement;
    if (!container) return;

    const term = new Terminal({
      cursorBlink: true,
      fontSize: 13,
      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace",
      theme: {
        background: '#1e1e1e',
        foreground: '#cccccc',
        cursor: '#aeafad',
      },
    });

    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(container);

    try {
      term.loadAddon(new WebglAddon());
    } catch {
      // WebGL not available — falls back to canvas renderer
    }

    fitAddon.fit();
    this._terminal = term;
    this._fitAddon = fitAddon;

    this._resizeObserver = new ResizeObserver(() => {
      if (this._fitAddon) this._fitAddon.fit();
    });
    this._resizeObserver.observe(container);

    if (this.sessionName) this._connect();
  }

  override updated(changed: Map<string, unknown>) {
    if (changed.has('sessionName') && this.sessionName) {
      this._disconnect();
      this._connect();
    }
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this._disconnect();
    this._resizeObserver?.disconnect();
    this._terminal?.dispose();
  }

  private _connect() {
    if (!this.sessionName || !this._terminal) return;

    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${proto}//${location.host}/ws/terminal/${this.sessionName}/${this.cols}/${this.rows}`;

    const ws = new WebSocket(url);
    this._ws = ws;

    ws.onopen = () => {
      this._connected = true;
      this._error = null;
    };

    ws.onmessage = (event) => {
      this._terminal?.write(event.data);
    };

    ws.onclose = () => {
      this._connected = false;
    };

    ws.onerror = () => {
      this._error = `Failed to connect to session: ${this.sessionName}`;
      this._connected = false;
    };

    this._terminal.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(data);
      }
    });
  }

  private _disconnect() {
    if (this._ws) {
      this._ws.close();
      this._ws = null;
    }
    this._connected = false;
  }
}
