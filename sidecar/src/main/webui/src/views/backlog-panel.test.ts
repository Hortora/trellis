import { describe, it, expect } from 'vitest';
import { applyFilters, cacheAge } from './backlog-panel';
import type { BacklogItem } from './backlog-panel';

const ITEMS: BacklogItem[] = [
  { issueNumber: 1, issueRepo: 'Org/repo', title: 'Issue 1', labels: ['bug'], cachedAt: '2026-08-09T10:00:00Z', strategicRole: 'quick-win', readiness: 'ready', decay: 'compounding', blastRadius: 'isolated', cohesion: 'infra', enrichedAt: '2026-08-09T10:00:00Z', trajectoryNote: 'Latest note', trajectoryAt: '2026-08-09T10:00:00Z' },
  { issueNumber: 2, issueRepo: 'Org/repo', title: 'Issue 2', labels: [], cachedAt: '2026-08-09T10:00:00Z', strategicRole: null, readiness: null, decay: null, blastRadius: null, cohesion: null, enrichedAt: null, trajectoryNote: null, trajectoryAt: null },
  { issueNumber: 3, issueRepo: 'Org/other', title: 'Other repo', labels: [], cachedAt: '2026-08-09T10:00:00Z', strategicRole: 'load-bearing', readiness: 'blocked', decay: 'stable', blastRadius: 'cross-cutting', cohesion: 'core', enrichedAt: '2026-08-09T10:00:00Z', trajectoryNote: null, trajectoryAt: null },
];

describe('backlog filter logic', () => {
  it('returns all items when no filters', () => {
    expect(applyFilters(ITEMS, {})).toHaveLength(3);
  });

  it('filters by repo', () => {
    const result = applyFilters(ITEMS, { repo: 'Org/repo' });
    expect(result).toHaveLength(2);
    expect(result.every(i => i.issueRepo === 'Org/repo')).toBe(true);
  });

  it('filters by strategicRole', () => {
    const result = applyFilters(ITEMS, { strategicRole: 'quick-win' });
    expect(result).toHaveLength(1);
    expect(result[0].issueNumber).toBe(1);
  });

  it('composes multiple filters', () => {
    const result = applyFilters(ITEMS, { repo: 'Org/repo', readiness: 'ready' });
    expect(result).toHaveLength(1);
    expect(result[0].issueNumber).toBe(1);
  });

  it('null fields do not match filter values', () => {
    const result = applyFilters(ITEMS, { strategicRole: 'quick-win' });
    expect(result.find(i => i.issueNumber === 2)).toBeUndefined();
  });
});

describe('cache age', () => {
  it('returns null for empty items', () => {
    expect(cacheAge([])).toBeNull();
  });

  it('returns age from oldest cachedAt', () => {
    const age = cacheAge(ITEMS);
    expect(age).toBeGreaterThan(0);
  });
});
