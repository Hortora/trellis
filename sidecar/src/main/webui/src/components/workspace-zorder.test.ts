import { describe, it, expect } from 'vitest';
import {
  bringToFront,
  compactFrames,
  normalizeForSave,
  isPinnedZIndex,
  PINNED_BASE,
  COMPACTION_THRESHOLD,
} from './workspace-zorder.js';

describe('bringToFront', () => {
  it('should increment counter and return z-index for normal frame', () => {
    const result = bringToFront(3, false);
    expect(result.zIndex).toBe(4);
    expect(result.counter).toBe(4);
    expect(result.needsCompaction).toBe(false);
  });

  it('should use pinned tier for pinned frame', () => {
    const result = bringToFront(2, true);
    expect(result.zIndex).toBe(PINNED_BASE + 3);
    expect(result.counter).toBe(3);
  });

  it('should signal compaction when counter exceeds threshold', () => {
    const result = bringToFront(COMPACTION_THRESHOLD, false);
    expect(result.needsCompaction).toBe(true);
  });

  it('should not signal compaction below threshold', () => {
    const result = bringToFront(COMPACTION_THRESHOLD - 1, false);
    expect(result.needsCompaction).toBe(false);
  });

  it('normal z-index stays in [1, 9999]', () => {
    const result = bringToFront(0, false);
    expect(result.zIndex).toBeGreaterThanOrEqual(1);
    expect(result.zIndex).toBeLessThanOrEqual(9999);
  });

  it('pinned z-index stays in [10001, 20000]', () => {
    const result = bringToFront(0, true);
    expect(result.zIndex).toBeGreaterThanOrEqual(PINNED_BASE + 1);
  });
});

describe('compactFrames', () => {
  it('should reassign sequential z-indices preserving relative order', () => {
    const frames = [
      { id: 'a', zIndex: 100, pinned: false },
      { id: 'b', zIndex: 500, pinned: false },
      { id: 'c', zIndex: 250, pinned: false },
    ];
    const { updates, normalMax } = compactFrames(frames);
    const sorted = updates.sort((a, b) => a.zIndex - b.zIndex);
    expect(sorted[0]).toEqual({ id: 'a', zIndex: 1 });
    expect(sorted[1]).toEqual({ id: 'c', zIndex: 2 });
    expect(sorted[2]).toEqual({ id: 'b', zIndex: 3 });
    expect(normalMax).toBe(3);
  });

  it('should compact pinned frames into pinned tier', () => {
    const frames = [
      { id: 'a', zIndex: 10500, pinned: true },
      { id: 'b', zIndex: 10100, pinned: true },
    ];
    const { updates, pinnedMax } = compactFrames(frames);
    const sorted = updates.sort((a, b) => a.zIndex - b.zIndex);
    expect(sorted[0]).toEqual({ id: 'b', zIndex: PINNED_BASE + 1 });
    expect(sorted[1]).toEqual({ id: 'a', zIndex: PINNED_BASE + 2 });
    expect(pinnedMax).toBe(2);
  });

  it('should handle mixed normal and pinned frames', () => {
    const frames = [
      { id: 'a', zIndex: 50, pinned: false },
      { id: 'b', zIndex: 10200, pinned: true },
      { id: 'c', zIndex: 80, pinned: false },
      { id: 'd', zIndex: 10100, pinned: true },
    ];
    const { updates, normalMax, pinnedMax } = compactFrames(frames);
    expect(normalMax).toBe(2);
    expect(pinnedMax).toBe(2);

    const normal = updates.filter(u => u.zIndex <= 9999).sort((a, b) => a.zIndex - b.zIndex);
    const pinned = updates.filter(u => u.zIndex > PINNED_BASE).sort((a, b) => a.zIndex - b.zIndex);
    expect(normal[0]).toEqual({ id: 'a', zIndex: 1 });
    expect(normal[1]).toEqual({ id: 'c', zIndex: 2 });
    expect(pinned[0]).toEqual({ id: 'd', zIndex: PINNED_BASE + 1 });
    expect(pinned[1]).toEqual({ id: 'b', zIndex: PINNED_BASE + 2 });
  });

  it('should handle empty frames array', () => {
    const { updates, normalMax, pinnedMax } = compactFrames([]);
    expect(updates).toEqual([]);
    expect(normalMax).toBe(0);
    expect(pinnedMax).toBe(0);
  });

  it('should handle single frame', () => {
    const { updates } = compactFrames([{ id: 'only', zIndex: 4999, pinned: false }]);
    expect(updates).toEqual([{ id: 'only', zIndex: 1 }]);
  });
});

describe('normalizeForSave', () => {
  it('should produce sequential z-indices suitable for persistence', () => {
    const frames = [
      { id: 'x', zIndex: 300, pinned: false },
      { id: 'y', zIndex: 100, pinned: false },
      { id: 'z', zIndex: 200, pinned: false },
    ];
    const normalized = normalizeForSave(frames);
    const sorted = normalized.sort((a, b) => a.zIndex - b.zIndex);
    expect(sorted.map(f => f.zIndex)).toEqual([1, 2, 3]);
    expect(sorted.map(f => f.id)).toEqual(['y', 'z', 'x']);
  });
});

describe('isPinnedZIndex', () => {
  it('should return true for pinned tier z-indices', () => {
    expect(isPinnedZIndex(PINNED_BASE + 1)).toBe(true);
    expect(isPinnedZIndex(15000)).toBe(true);
  });

  it('should return false for normal tier z-indices', () => {
    expect(isPinnedZIndex(1)).toBe(false);
    expect(isPinnedZIndex(9999)).toBe(false);
  });
});
