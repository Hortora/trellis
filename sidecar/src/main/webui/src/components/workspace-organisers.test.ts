import { describe, it, expect } from 'vitest';
import { sideBySide, stacked, grid, mainSidebar, focus, PRESETS } from './workspace-organisers.js';

const canvas = { width: 1200, height: 800 };

function makeFrames(n: number) {
  return Array.from({ length: n }, (_, i) => ({
    id: `f${i}`, x: 0, y: 0, width: 100, height: 100, pinned: false,
  }));
}

describe('sideBySide', () => {
  it('should tile frames left to right with equal width', () => {
    const result = sideBySide(makeFrames(3), canvas);
    const unpinned = result.filter(f => !f.pinned);
    expect(unpinned[0].x).toBe(0);
    expect(unpinned[0].height).toBe(canvas.height);
    expect(unpinned[1].x).toBeGreaterThan(0);
    expect(unpinned[2].x).toBeGreaterThan(unpinned[1].x);
    const totalWidth = unpinned.reduce((s, f) => s + f.width, 0);
    expect(totalWidth).toBeCloseTo(canvas.width - 4 * 2, 0);
  });

  it('should skip pinned frames', () => {
    const frames = makeFrames(3);
    frames[1].pinned = true;
    frames[1].x = 500;
    frames[1].y = 100;
    const result = sideBySide(frames, canvas);
    expect(result[1].x).toBe(500);
    expect(result[1].y).toBe(100);
  });
});

describe('stacked', () => {
  it('should stack frames top to bottom with equal height', () => {
    const result = stacked(makeFrames(2), canvas);
    const unpinned = result.filter(f => !f.pinned);
    expect(unpinned[0].y).toBe(0);
    expect(unpinned[0].width).toBe(canvas.width);
    expect(unpinned[1].y).toBeGreaterThan(0);
  });
});

describe('grid', () => {
  it('should fill area for a single frame', () => {
    const result = grid(makeFrames(1), canvas);
    expect(result[0].width).toBe(canvas.width);
    expect(result[0].height).toBe(canvas.height);
  });

  it('should create 2x2 grid for 4 frames', () => {
    const result = grid(makeFrames(4), canvas);
    const xs = new Set(result.map(f => f.x));
    const ys = new Set(result.map(f => f.y));
    expect(xs.size).toBe(2);
    expect(ys.size).toBe(2);
  });

  it('should handle odd frame counts without overlap', () => {
    const result = grid(makeFrames(5), canvas);
    for (let i = 0; i < result.length; i++) {
      for (let j = i + 1; j < result.length; j++) {
        const noOverlap =
          result[i].x + result[i].width <= result[j].x + 1 ||
          result[j].x + result[j].width <= result[i].x + 1 ||
          result[i].y + result[i].height <= result[j].y + 1 ||
          result[j].y + result[j].height <= result[i].y + 1;
        expect(noOverlap, `frames ${i} and ${j} overlap`).toBe(true);
      }
    }
  });
});

describe('mainSidebar', () => {
  it('should give first frame 2/3 width', () => {
    const result = mainSidebar(makeFrames(3), canvas);
    const unpinned = result.filter(f => !f.pinned);
    expect(unpinned[0].width).toBe(Math.floor(canvas.width * 2 / 3));
    expect(unpinned[0].height).toBe(canvas.height);
  });

  it('should stack sidebar frames vertically', () => {
    const result = mainSidebar(makeFrames(3), canvas);
    const unpinned = result.filter(f => !f.pinned);
    expect(unpinned[1].x).toBeGreaterThan(unpinned[0].x);
    expect(unpinned[2].x).toBe(unpinned[1].x);
    expect(unpinned[2].y).toBeGreaterThan(unpinned[1].y);
  });
});

describe('focus', () => {
  it('should give first frame nearly full area', () => {
    const result = focus(makeFrames(3), canvas);
    const unpinned = result.filter(f => !f.pinned);
    expect(unpinned[0].width).toBe(canvas.width);
    expect(unpinned[0].height).toBeGreaterThan(canvas.height * 0.9);
  });

  it('should minimise other frames to bottom strip', () => {
    const result = focus(makeFrames(3), canvas);
    const unpinned = result.filter(f => !f.pinned);
    expect(unpinned[1].height).toBeLessThan(50);
    expect(unpinned[2].height).toBeLessThan(50);
    expect(unpinned[1].y).toBeGreaterThan(unpinned[0].y);
  });
});

describe('PRESETS', () => {
  it('should export 5 preset entries', () => {
    expect(PRESETS.length).toBe(5);
  });

  it('should have named presets', () => {
    const names = PRESETS.map(p => p.name);
    expect(names).toContain('Side by side');
    expect(names).toContain('Grid');
    expect(names).toContain('Focus');
  });
});
