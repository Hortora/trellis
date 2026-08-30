import { describe, it, expect } from 'vitest';
import { classifyNodes, criticalPathDepth } from './blockers-panel';
import type { DependencyNode } from './blockers-panel';

const BLOCKED_NODE: DependencyNode = {
  number: 55, repo: 'R', title: 'User mgmt', issueState: 'OPEN',
  status: 'BLOCKED', blockedBy: [{ number: 42, repo: 'R', state: 'OPEN' }], blocking: [],
};
const UNBLOCKED_NODE: DependencyNode = {
  number: 19, repo: 'R', title: 'Intelligence', issueState: 'OPEN',
  status: 'UNBLOCKED', blockedBy: [{ number: 11, repo: 'R', state: 'CLOSED' }], blocking: [],
};
const CLEAR_NODE: DependencyNode = {
  number: 53, repo: 'R', title: 'CrossRepo', issueState: 'OPEN',
  status: 'CLEAR', blockedBy: [], blocking: [],
};

describe('classifyNodes', () => {
  it('groups nodes by status', () => {
    const { blocked, unblocked, clear } = classifyNodes([BLOCKED_NODE, UNBLOCKED_NODE, CLEAR_NODE]);
    expect(blocked).toHaveLength(1);
    expect(blocked[0].number).toBe(55);
    expect(unblocked).toHaveLength(1);
    expect(clear).toHaveLength(1);
  });

  it('returns empty arrays for no nodes', () => {
    const { blocked, unblocked, clear } = classifyNodes([]);
    expect(blocked).toHaveLength(0);
    expect(unblocked).toHaveLength(0);
    expect(clear).toHaveLength(0);
  });
});

describe('criticalPathDepth', () => {
  it('returns 0 for empty path', () => {
    expect(criticalPathDepth([])).toBe(0);
  });

  it('returns length of path', () => {
    expect(criticalPathDepth([
      { number: 11, repo: 'R' },
      { number: 19, repo: 'R' },
      { number: 42, repo: 'R' },
    ])).toBe(3);
  });
});
