import { describe, it, expect, beforeEach } from 'vitest';
import { createPanelFactory, PANEL_TAGS, registerAllPanels } from './workbench-panels.js';

describe('workbench-panels', () => {
  beforeEach(() => {
    registerAllPanels();
  });

  it('PANEL_TAGS maps all 11 panel keys to tag names', () => {
    const keys = Object.keys(PANEL_TAGS);
    expect(keys).toContain('workspace');
    expect(keys).toContain('dashboard');
    expect(keys).toContain('backlog');
    expect(keys).toContain('artifacts');
    expect(keys).toContain('garden');
    expect(keys).toContain('protocols');
    expect(keys).toContain('coordinator');
    expect(keys).toContain('memory');
    expect(keys).toContain('slot');
    expect(keys).toContain('epic');
    expect(keys).toContain('repo');
    expect(keys.length).toBe(11);
  });

  it('ContentFactory creates element with correct tag', () => {
    const factory = createPanelFactory('/test/root');
    const entry = { key: 'dashboard', label: 'Dashboard' };
    const { element } = factory(entry as any);
    expect(element.tagName.toLowerCase()).toBe('trellis-org-dashboard');
  });

  it('ContentFactory sets workspaceRoot on element', () => {
    const factory = createPanelFactory('/test/root');
    const entry = { key: 'dashboard', label: 'Dashboard' };
    const { element } = factory(entry as any);
    expect((element as any).workspaceRoot).toBe('/test/root');
  });

  it('ContentFactory dispose removes element', () => {
    const factory = createPanelFactory('/test/root');
    const entry = { key: 'dashboard', label: 'Dashboard' };
    const { element, dispose } = factory(entry as any);
    const parent = document.createElement('div');
    parent.appendChild(element);
    expect(parent.children.length).toBe(1);
    dispose!();
    expect(parent.children.length).toBe(0);
  });

  it('ContentFactory throws for unknown panel key', () => {
    const factory = createPanelFactory('/test/root');
    const entry = { key: 'nonexistent', label: 'Bad' };
    expect(() => factory(entry as any)).toThrow('Unknown panel: nonexistent');
  });
});
