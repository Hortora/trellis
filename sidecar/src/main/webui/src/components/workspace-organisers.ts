interface FrameRect {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  pinned: boolean;
}

interface CanvasSize {
  width: number;
  height: number;
}

const GAP = 4;

export function sideBySide(frames: FrameRect[], canvas: CanvasSize): FrameRect[] {
  const unpinned = frames.filter(f => !f.pinned);
  if (unpinned.length === 0) return frames;
  const w = (canvas.width - GAP * (unpinned.length - 1)) / unpinned.length;
  return frames.map(f => {
    if (f.pinned) return f;
    const idx = unpinned.indexOf(f);
    return { ...f, x: idx * (w + GAP), y: 0, width: w, height: canvas.height };
  });
}

export function stacked(frames: FrameRect[], canvas: CanvasSize): FrameRect[] {
  const unpinned = frames.filter(f => !f.pinned);
  if (unpinned.length === 0) return frames;
  const h = (canvas.height - GAP * (unpinned.length - 1)) / unpinned.length;
  return frames.map(f => {
    if (f.pinned) return f;
    const idx = unpinned.indexOf(f);
    return { ...f, x: 0, y: idx * (h + GAP), width: canvas.width, height: h };
  });
}

export function grid(frames: FrameRect[], canvas: CanvasSize): FrameRect[] {
  const unpinned = frames.filter(f => !f.pinned);
  if (unpinned.length === 0) return frames;
  if (unpinned.length === 1) {
    return frames.map(f => f.pinned ? f : { ...f, x: 0, y: 0, width: canvas.width, height: canvas.height });
  }
  const cols = Math.ceil(Math.sqrt(unpinned.length));
  const rows = Math.ceil(unpinned.length / cols);
  const cellW = (canvas.width - GAP * (cols - 1)) / cols;
  const cellH = (canvas.height - GAP * (rows - 1)) / rows;
  return frames.map(f => {
    if (f.pinned) return f;
    const idx = unpinned.indexOf(f);
    const col = idx % cols;
    const row = Math.floor(idx / cols);
    return { ...f, x: col * (cellW + GAP), y: row * (cellH + GAP), width: cellW, height: cellH };
  });
}

export function mainSidebar(frames: FrameRect[], canvas: CanvasSize): FrameRect[] {
  const unpinned = frames.filter(f => !f.pinned);
  if (unpinned.length === 0) return frames;
  if (unpinned.length === 1) {
    return frames.map(f => f.pinned ? f : { ...f, x: 0, y: 0, width: canvas.width, height: canvas.height });
  }
  const mainW = Math.floor(canvas.width * 2 / 3);
  const sideW = canvas.width - mainW - GAP;
  const sideCount = unpinned.length - 1;
  const sideH = (canvas.height - GAP * (sideCount - 1)) / sideCount;
  return frames.map(f => {
    if (f.pinned) return f;
    const idx = unpinned.indexOf(f);
    if (idx === 0) return { ...f, x: 0, y: 0, width: mainW, height: canvas.height };
    const sideIdx = idx - 1;
    return { ...f, x: mainW + GAP, y: sideIdx * (sideH + GAP), width: sideW, height: sideH };
  });
}

export function focus(frames: FrameRect[], canvas: CanvasSize): FrameRect[] {
  const unpinned = frames.filter(f => !f.pinned);
  if (unpinned.length === 0) return frames;
  const stripH = 32;
  const mainH = canvas.height - stripH - GAP;
  const stripW = unpinned.length > 1 ? (canvas.width - GAP * (unpinned.length - 2)) / (unpinned.length - 1) : 0;
  return frames.map(f => {
    if (f.pinned) return f;
    const idx = unpinned.indexOf(f);
    if (idx === 0) return { ...f, x: 0, y: 0, width: canvas.width, height: mainH };
    const stripIdx = idx - 1;
    return { ...f, x: stripIdx * (stripW + GAP), y: mainH + GAP, width: stripW, height: stripH };
  });
}

export const PRESETS = [
  { name: 'Side by side', fn: sideBySide },
  { name: 'Stacked', fn: stacked },
  { name: 'Grid', fn: grid },
  { name: 'Main + sidebar', fn: mainSidebar },
  { name: 'Focus', fn: focus },
] as const;
