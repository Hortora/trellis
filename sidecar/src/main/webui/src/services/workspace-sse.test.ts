import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { subscribeWorkspace, _resetForTest } from './workspace-sse';

class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  readyState = 0;
  listeners = new Map<string, Function[]>();

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, fn: Function) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type)!.push(fn);
  }

  removeEventListener(type: string, fn: Function) {
    const fns = this.listeners.get(type);
    if (fns) this.listeners.set(type, fns.filter(f => f !== fn));
  }

  close() { this.readyState = 2; }

  simulateMessage(data: unknown) {
    const event = { data: JSON.stringify(data) } as MessageEvent;
    for (const fn of this.listeners.get('message') ?? []) { fn(event); }
  }
}

beforeEach(() => {
  MockEventSource.instances = [];
  vi.stubGlobal('EventSource', MockEventSource);
  _resetForTest();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('subscribeWorkspace', () => {
  it('creates shared EventSource on first subscribe', () => {
    subscribeWorkspace(['workspace:repos'], () => {});
    expect(MockEventSource.instances).toHaveLength(1);
    expect(MockEventSource.instances[0].url).toContain('workspace%3Arepos');
  });

  it('reuses EventSource for second subscriber', () => {
    subscribeWorkspace(['workspace:repos'], () => {});
    subscribeWorkspace(['workspace:slots'], () => {});
    expect(MockEventSource.instances).toHaveLength(1);
  });

  it('dispatches to correct subscriber by topic', () => {
    const reposCb = vi.fn();
    const slotsCb = vi.fn();
    subscribeWorkspace(['workspace:repos'], reposCb);
    subscribeWorkspace(['workspace:slots'], slotsCb);

    MockEventSource.instances[0].simulateMessage({ topic: 'workspace:repos' });
    expect(reposCb).toHaveBeenCalledWith('workspace:repos');
    expect(slotsCb).not.toHaveBeenCalled();
  });

  it('closes EventSource when last subscriber unsubscribes', () => {
    const unsub1 = subscribeWorkspace(['workspace:repos'], () => {});
    const unsub2 = subscribeWorkspace(['workspace:slots'], () => {});

    unsub1();
    expect(MockEventSource.instances[0].readyState).not.toBe(2);

    unsub2();
    expect(MockEventSource.instances[0].readyState).toBe(2);
  });

  it('ignores non-JSON messages', () => {
    const cb = vi.fn();
    subscribeWorkspace(['workspace:repos'], cb);

    const event = { data: 'not json' } as MessageEvent;
    for (const fn of MockEventSource.instances[0].listeners.get('message') ?? []) {
      fn(event);
    }
    expect(cb).not.toHaveBeenCalled();
  });
});
