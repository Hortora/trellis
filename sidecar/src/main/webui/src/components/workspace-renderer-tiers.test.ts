import { describe, it, expect } from 'vitest';
import { determineTier, computeTransitions, computeAllTiers } from './workspace-renderer-tiers.js';
import type { RendererTier } from './workspace-renderer-tiers.js';

describe('determineTier', () => {
  it('should return webgl for active tab in focused frame', () => {
    expect(determineTier({
      terminalName: 'repo-a', frameId: 'f1',
      isActiveTab: true, isInFocusedFrame: true,
    })).toBe('webgl');
  });

  it('should return canvas for active tab in non-focused frame', () => {
    expect(determineTier({
      terminalName: 'repo-a', frameId: 'f1',
      isActiveTab: true, isInFocusedFrame: false,
    })).toBe('canvas');
  });

  it('should return none for inactive tab', () => {
    expect(determineTier({
      terminalName: 'repo-a', frameId: 'f1',
      isActiveTab: false, isInFocusedFrame: true,
    })).toBe('none');
  });

  it('should return none for inactive tab in non-focused frame', () => {
    expect(determineTier({
      terminalName: 'repo-a', frameId: 'f1',
      isActiveTab: false, isInFocusedFrame: false,
    })).toBe('none');
  });
});

describe('computeTransitions', () => {
  it('should detect promotion from none to webgl', () => {
    const prev = new Map<string, RendererTier>([['repo-a', 'none']]);
    const curr = new Map<string, RendererTier>([['repo-a', 'webgl']]);
    const transitions = computeTransitions(prev, curr);
    expect(transitions).toEqual([{ terminalName: 'repo-a', from: 'none', to: 'webgl' }]);
  });

  it('should detect demotion from webgl to canvas', () => {
    const prev = new Map<string, RendererTier>([['repo-a', 'webgl']]);
    const curr = new Map<string, RendererTier>([['repo-a', 'canvas']]);
    const transitions = computeTransitions(prev, curr);
    expect(transitions).toEqual([{ terminalName: 'repo-a', from: 'webgl', to: 'canvas' }]);
  });

  it('should return empty for no changes', () => {
    const prev = new Map<string, RendererTier>([['repo-a', 'webgl']]);
    const curr = new Map<string, RendererTier>([['repo-a', 'webgl']]);
    expect(computeTransitions(prev, curr)).toEqual([]);
  });

  it('should detect removal (terminal closed)', () => {
    const prev = new Map<string, RendererTier>([['repo-a', 'canvas']]);
    const curr = new Map<string, RendererTier>();
    const transitions = computeTransitions(prev, curr);
    expect(transitions).toEqual([{ terminalName: 'repo-a', from: 'canvas', to: 'none' }]);
  });

  it('should detect new terminal (added)', () => {
    const prev = new Map<string, RendererTier>();
    const curr = new Map<string, RendererTier>([['repo-a', 'webgl']]);
    const transitions = computeTransitions(prev, curr);
    expect(transitions).toEqual([{ terminalName: 'repo-a', from: 'none', to: 'webgl' }]);
  });
});

describe('computeAllTiers', () => {
  it('should assign webgl to focused frame active tab, canvas to others', () => {
    const frameTabs = new Map([
      ['f1', [{ terminalName: 'repo-a' }, { terminalName: 'repo-b' }]],
      ['f2', [{ terminalName: 'repo-c' }]],
    ]);
    const frameActiveTab = new Map([['f1', 0], ['f2', 0]]);
    const tiers = computeAllTiers(frameTabs, frameActiveTab, 'f1');

    expect(tiers.get('repo-a')).toBe('webgl');
    expect(tiers.get('repo-b')).toBe('none');
    expect(tiers.get('repo-c')).toBe('canvas');
  });

  it('should assign none to all when no frame is focused', () => {
    const frameTabs = new Map([
      ['f1', [{ terminalName: 'repo-a' }]],
    ]);
    const frameActiveTab = new Map([['f1', 0]]);
    const tiers = computeAllTiers(frameTabs, frameActiveTab, null);

    expect(tiers.get('repo-a')).toBe('canvas');
  });

  it('should handle multiple tabs with correct active index', () => {
    const frameTabs = new Map([
      ['f1', [{ terminalName: 'repo-a' }, { terminalName: 'repo-b' }, { terminalName: 'repo-c' }]],
    ]);
    const frameActiveTab = new Map([['f1', 1]]);
    const tiers = computeAllTiers(frameTabs, frameActiveTab, 'f1');

    expect(tiers.get('repo-a')).toBe('none');
    expect(tiers.get('repo-b')).toBe('webgl');
    expect(tiers.get('repo-c')).toBe('none');
  });
});
