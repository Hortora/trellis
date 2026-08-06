import { describe, it, expect } from 'vitest';
import { findSpatialTarget, type FrameRect } from './workspace-spatial-nav.js';

describe('findSpatialTarget', () => {
  const frames: FrameRect[] = [
    { id: 'tl', x: 0, y: 0, width: 200, height: 200 },
    { id: 'tr', x: 300, y: 0, width: 200, height: 200 },
    { id: 'bl', x: 0, y: 300, width: 200, height: 200 },
    { id: 'br', x: 300, y: 300, width: 200, height: 200 },
  ];

  it('should find frame to the right', () => {
    expect(findSpatialTarget('tl', frames, 'right')).toBe('tr');
  });

  it('should find frame to the left', () => {
    expect(findSpatialTarget('tr', frames, 'left')).toBe('tl');
  });

  it('should find frame below', () => {
    expect(findSpatialTarget('tl', frames, 'down')).toBe('bl');
  });

  it('should find frame above', () => {
    expect(findSpatialTarget('bl', frames, 'up')).toBe('tl');
  });

  it('should return null at edge of layout', () => {
    expect(findSpatialTarget('tl', frames, 'up')).toBeNull();
    expect(findSpatialTarget('tl', frames, 'left')).toBeNull();
    expect(findSpatialTarget('br', frames, 'right')).toBeNull();
    expect(findSpatialTarget('br', frames, 'down')).toBeNull();
  });

  it('should prefer closer frame on primary axis', () => {
    const diagonal: FrameRect[] = [
      { id: 'a', x: 0, y: 0, width: 100, height: 100 },
      { id: 'near', x: 150, y: 10, width: 100, height: 100 },
      { id: 'far', x: 400, y: 0, width: 100, height: 100 },
    ];
    expect(findSpatialTarget('a', diagonal, 'right')).toBe('near');
  });

  it('should handle single frame gracefully', () => {
    const single: FrameRect[] = [{ id: 'only', x: 0, y: 0, width: 200, height: 200 }];
    expect(findSpatialTarget('only', single, 'right')).toBeNull();
  });

  it('should handle unknown current frame', () => {
    expect(findSpatialTarget('missing', frames, 'right')).toBeNull();
  });

  it('should prefer aligned frame over diagonal', () => {
    const aligned: FrameRect[] = [
      { id: 'center', x: 200, y: 200, width: 100, height: 100 },
      { id: 'aligned-right', x: 400, y: 200, width: 100, height: 100 },
      { id: 'diagonal-right', x: 350, y: 50, width: 100, height: 100 },
    ];
    expect(findSpatialTarget('center', aligned, 'right')).toBe('aligned-right');
  });
});
