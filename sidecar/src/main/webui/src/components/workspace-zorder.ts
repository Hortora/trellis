interface ZFrame {
  id: string;
  zIndex: number;
  pinned: boolean;
}

const NORMAL_MIN = 1;
const NORMAL_MAX = 9999;
const PINNED_BASE = 10000;
const COMPACTION_THRESHOLD = 5000;

export function bringToFront(
  counter: number,
  pinned: boolean,
): { zIndex: number; counter: number; needsCompaction: boolean } {
  const next = counter + 1;
  const zIndex = pinned ? PINNED_BASE + next : next;
  return { zIndex, counter: next, needsCompaction: next > COMPACTION_THRESHOLD };
}

export function compactFrames(frames: ZFrame[]): {
  updates: { id: string; zIndex: number }[];
  normalMax: number;
  pinnedMax: number;
} {
  const normal = frames.filter(f => !f.pinned).sort((a, b) => a.zIndex - b.zIndex);
  const pinned = frames.filter(f => f.pinned).sort((a, b) => a.zIndex - b.zIndex);

  const updates: { id: string; zIndex: number }[] = [];
  for (let i = 0; i < normal.length; i++) {
    updates.push({ id: normal[i].id, zIndex: NORMAL_MIN + i });
  }
  for (let i = 0; i < pinned.length; i++) {
    updates.push({ id: pinned[i].id, zIndex: PINNED_BASE + 1 + i });
  }

  return { updates, normalMax: normal.length, pinnedMax: pinned.length };
}

export function normalizeForSave(frames: ZFrame[]): { id: string; zIndex: number }[] {
  return compactFrames(frames).updates;
}

export function isPinnedZIndex(zIndex: number): boolean {
  return zIndex > PINNED_BASE;
}

export { NORMAL_MIN, NORMAL_MAX, PINNED_BASE, COMPACTION_THRESHOLD };
export type { ZFrame };
