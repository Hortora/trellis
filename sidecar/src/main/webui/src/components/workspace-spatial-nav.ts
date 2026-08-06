interface FrameRect {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

type Direction = 'up' | 'down' | 'left' | 'right';

export function findSpatialTarget(
  currentId: string,
  frames: FrameRect[],
  direction: Direction,
): string | null {
  const current = frames.find(f => f.id === currentId);
  if (!current) return null;

  const cx = current.x + current.width / 2;
  const cy = current.y + current.height / 2;

  let bestId: string | null = null;
  let bestDist = Infinity;
  let bestAxisDist = Infinity;

  for (const f of frames) {
    if (f.id === currentId) continue;

    const fx = f.x + f.width / 2;
    const fy = f.y + f.height / 2;
    const dx = fx - cx;
    const dy = fy - cy;

    const inHalfPlane =
      (direction === 'right' && dx > 0) ||
      (direction === 'left' && dx < 0) ||
      (direction === 'down' && dy > 0) ||
      (direction === 'up' && dy < 0);

    if (!inHalfPlane) continue;

    const dist = Math.hypot(dx, dy);
    const axisDist =
      (direction === 'left' || direction === 'right') ? Math.abs(dy) : Math.abs(dx);

    if (dist < bestDist || (dist === bestDist && axisDist < bestAxisDist)) {
      bestDist = dist;
      bestAxisDist = axisDist;
      bestId = f.id;
    }
  }

  return bestId;
}

export type { FrameRect, Direction };
