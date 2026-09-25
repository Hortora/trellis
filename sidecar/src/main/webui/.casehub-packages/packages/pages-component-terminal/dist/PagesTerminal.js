import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
export class PagesTerminal extends HTMLElement {
    _props;
    _terminal;
    _fitAddon;
    _ws;
    _resizeObserver;
    _reconnectTimer;
    _retries = 0;
    _tearingDown = false;
    _onDataDisposable;
    _onResizeDisposable;
    _connected = false;
    configure(props) {
        if (this._props?.wsUrl === props.wsUrl && this._terminal) {
            return;
        }
        this._props = props;
        if (this._connected) {
            this._teardown();
            this._init();
        }
    }
    connectedCallback() {
        this._connected = true;
        if (this._props) {
            this._init();
        }
    }
    disconnectedCallback() {
        this._connected = false;
        this._teardown();
    }
    sendInput(text) {
        if (this._ws?.readyState === WebSocket.OPEN) {
            this._ws.send(text);
        }
    }
    paste(text) {
        this._terminal?.paste(text);
    }
    get terminal() {
        return this._terminal;
    }
    _init() {
        const props = this._props;
        if (!props)
            return;
        const container = document.createElement("div");
        container.style.width = "100%";
        container.style.height = "100%";
        container.tabIndex = 0;
        container.addEventListener("mousedown", () => {
            this._terminal?.focus();
        });
        this.appendChild(container);
        const terminal = new Terminal({
            fontSize: props.fontSize ?? 14,
            fontFamily: props.fontFamily ?? "Menlo, Monaco, Consolas, monospace",
            scrollback: props.scrollback ?? 5000,
            cursorBlink: props.cursorBlink ?? true,
            ...(props.theme ? { theme: props.theme } : {}),
        });
        this._terminal = terminal;
        terminal.open(container);
        const fitAddon = new FitAddon();
        this._fitAddon = fitAddon;
        terminal.loadAddon(fitAddon);
        fitAddon.fit();
        this._onResizeDisposable = terminal.onResize(({ cols, rows }) => {
            if (this._tearingDown)
                return;
            this._dispatchEvent("terminal-resize", { cols, rows });
        });
        this._resizeObserver = new ResizeObserver(() => {
            if (this._tearingDown)
                return;
            fitAddon.fit();
            if (terminal.cols > 0 && terminal.rows > 0 && !this._ws) {
                this._dispatchEvent("terminal-ready", { cols: terminal.cols, rows: terminal.rows });
                this._connect();
            }
        });
        this._resizeObserver.observe(container);
        if (terminal.cols > 0 && terminal.rows > 0) {
            this._dispatchEvent("terminal-ready", { cols: terminal.cols, rows: terminal.rows });
            this._connect();
        }
    }
    _connect() {
        const props = this._props;
        const terminal = this._terminal;
        if (!props || !terminal)
            return;
        const url = props.wsUrl
            .replace("{cols}", String(terminal.cols))
            .replace("{rows}", String(terminal.rows));
        const ws = new WebSocket(url);
        this._ws = ws;
        ws.onopen = () => {
            this._retries = 0;
            this._dispatchEvent("terminal-connected", {});
        };
        ws.onmessage = (event) => {
            terminal.write(event.data);
        };
        this._onDataDisposable = terminal.onData((data) => {
            if (ws.readyState === WebSocket.OPEN) {
                ws.send(data);
            }
        });
        ws.onclose = (event) => {
            this._ws = undefined;
            this._onDataDisposable?.dispose();
            this._onDataDisposable = undefined;
            if (!this._connected || this._tearingDown)
                return;
            if (event.code === 4001) {
                this._dispatchEvent("terminal-disconnected", { reason: "session-expired" });
                return;
            }
            this._dispatchEvent("terminal-disconnected", { reason: "connection-lost" });
            this._scheduleReconnect();
        };
        ws.onerror = () => {
            // onclose will fire after onerror
        };
    }
    _scheduleReconnect() {
        if (this._retries >= 3) {
            this._dispatchEvent("terminal-disconnected", { reason: "max-retries" });
            return;
        }
        const delay = Math.min(1000 * Math.pow(2, this._retries), 30000);
        this._retries++;
        this._reconnectTimer = setTimeout(() => {
            this._reconnectTimer = undefined;
            if (!this._connected || this._tearingDown)
                return;
            this._fitAddon?.fit();
            const terminal = this._terminal;
            if (!terminal || terminal.cols === 0 || terminal.rows === 0)
                return;
            terminal.reset();
            this._connect();
        }, delay);
    }
    _teardown() {
        this._tearingDown = true;
        if (this._reconnectTimer !== undefined) {
            clearTimeout(this._reconnectTimer);
            this._reconnectTimer = undefined;
        }
        if (this._ws) {
            this._ws.onclose = null;
            this._ws.close(1000);
            this._ws = undefined;
        }
        this._onDataDisposable?.dispose();
        this._onDataDisposable = undefined;
        this._onResizeDisposable?.dispose();
        this._onResizeDisposable = undefined;
        this._resizeObserver?.disconnect();
        this._resizeObserver = undefined;
        this._terminal?.dispose();
        this._terminal = undefined;
        this._fitAddon = undefined;
        this._retries = 0;
        this.innerHTML = "";
        this._tearingDown = false;
    }
    _dispatchEvent(topic, payload) {
        this.dispatchEvent(new CustomEvent("pages-event", {
            bubbles: true,
            composed: true,
            detail: { topic, payload },
        }));
    }
}
customElements.define("pages-component-terminal", PagesTerminal);
//# sourceMappingURL=PagesTerminal.js.map