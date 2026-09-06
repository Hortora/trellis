import { describe, it, expect } from 'vitest';
import { mergeRecent } from './space-switcher.js';

describe('space-switcher mergeRecent', () => {
  it('adds new root to empty list', () => {
    expect(mergeRecent([], '/Users/test/hortora')).toEqual(['/Users/test/hortora']);
  });

  it('moves existing root to front', () => {
    const result = mergeRecent(['/a', '/b', '/c'], '/b');
    expect(result).toEqual(['/b', '/a', '/c']);
  });

  it('does not duplicate', () => {
    const result = mergeRecent(['/a'], '/a');
    expect(result.filter(r => r === '/a')).toHaveLength(1);
  });

  it('limits to 5 entries', () => {
    const result = mergeRecent(['/1', '/2', '/3', '/4', '/5'], '/new');
    expect(result).toHaveLength(5);
    expect(result[0]).toBe('/new');
    expect(result).not.toContain('/5');
  });
});
