// @vitest-environment node
import { describe, it, expect, vi } from 'vitest';

if (typeof globalThis.HTMLElement === 'undefined') {
  (globalThis as any).HTMLElement = class { appendChild() {} removeChild() {} set innerHTML(_: string) {} dispatchEvent() { return true; } };
}
if (typeof globalThis.customElements === 'undefined') {
  (globalThis as any).customElements = { define() {} };
}
if (typeof globalThis.CustomEvent === 'undefined') {
  (globalThis as any).CustomEvent = class extends Event { detail: any; constructor(type: string, init?: any) { super(type); this.detail = init?.detail; } };
}
if (typeof globalThis.ResizeObserver === 'undefined') {
  (globalThis as any).ResizeObserver = class { observe() {} disconnect() {} };
}
// PagesTerminal._init calls document.createElement — provide a minimal stub
// only if happy-dom hasn't already set it up (avoids contaminating other tests)
const _origDoc = globalThis.document;
if (!_origDoc?.createElement) {
  (globalThis as any).document = { createElement: () => ({ style: {}, tabIndex: 0, addEventListener() {}, appendChild() {} }) };
}
if (typeof globalThis.WebSocket === 'undefined') {
  (globalThis as any).WebSocket = class { static OPEN = 1; readyState = 0; send() {} close() {} onopen: any; onclose: any; onmessage: any; onerror: any; };
}

vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    cols = 80;
    rows = 24;
    open() {}
    loadAddon() {}
    onResize() { return { dispose() {} }; }
    onData() { return { dispose() {} }; }
    dispose() {}
    focus() {}
    write() {}
    paste() {}
    reset() {}
  },
}));
vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class {
    fit() {}
  },
}));

const { PagesTerminal } = await import('../../.casehub-packages/packages/pages-component-terminal/dist/PagesTerminal.js');

function createTerminal() {
  const el = new PagesTerminal();
  (el as any)._connected = true;
  return el as any;
}

describe('PagesTerminal configure guard', () => {

  it('does not teardown when configure called with same wsUrl', () => {
    const el = createTerminal();
    const props = {
      wsUrl: 'ws://localhost:9777/ws/terminal/test/{cols}/{rows}',
      theme: { background: '#1e1e1e' },
      fontSize: 13,
    };
    el.configure(props);
    const terminal = el._terminal;
    expect(terminal).toBeDefined();

    const teardownSpy = vi.spyOn(el, '_teardown');
    el.configure(props);
    expect(teardownSpy).not.toHaveBeenCalled();
    expect(el._terminal).toBe(terminal);
  });

  it('tears down and reinits when wsUrl changes', () => {
    const el = createTerminal();
    el.configure({
      wsUrl: 'ws://localhost:9777/ws/terminal/test-a/{cols}/{rows}',
      fontSize: 13,
    });
    const terminal1 = el._terminal;
    expect(terminal1).toBeDefined();

    el.configure({
      wsUrl: 'ws://localhost:9777/ws/terminal/test-b/{cols}/{rows}',
      fontSize: 13,
    });
    expect(el._terminal).not.toBe(terminal1);
  });
});

describe('PagesTerminal reconnect cap', () => {

  it('stops reconnecting after 3 attempts', () => {
    const el = createTerminal();
    el.configure({
      wsUrl: 'ws://localhost:9777/ws/terminal/test/{cols}/{rows}',
      fontSize: 13,
    });
    el._retries = 3;
    el._scheduleReconnect();
    expect(el._reconnectTimer).toBeUndefined();
  });

  it('allows reconnect when retries below cap', () => {
    const el = createTerminal();
    el.configure({
      wsUrl: 'ws://localhost:9777/ws/terminal/test/{cols}/{rows}',
      fontSize: 13,
    });
    el._retries = 2;
    el._scheduleReconnect();
    expect(el._reconnectTimer).not.toBeUndefined();
    clearTimeout(el._reconnectTimer);
  });
});
