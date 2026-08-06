export type RendererTier = 'webgl' | 'canvas' | 'none';

export interface TerminalTierState {
  terminalName: string;
  frameId: string;
  isActiveTab: boolean;
  isInFocusedFrame: boolean;
}

export function determineTier(state: TerminalTierState): RendererTier {
  if (state.isActiveTab && state.isInFocusedFrame) return 'webgl';
  if (state.isActiveTab) return 'canvas';
  return 'none';
}

export interface TierTransition {
  terminalName: string;
  from: RendererTier;
  to: RendererTier;
}

export function computeTransitions(
  previous: Map<string, RendererTier>,
  current: Map<string, RendererTier>,
): TierTransition[] {
  const transitions: TierTransition[] = [];
  for (const [name, tier] of current) {
    const prev = previous.get(name) ?? 'none';
    if (prev !== tier) {
      transitions.push({ terminalName: name, from: prev, to: tier });
    }
  }
  for (const [name, prev] of previous) {
    if (!current.has(name) && prev !== 'none') {
      transitions.push({ terminalName: name, from: prev, to: 'none' });
    }
  }
  return transitions;
}

export function computeAllTiers(
  frameTabs: Map<string, { terminalName: string }[]>,
  frameActiveTab: Map<string, number>,
  focusedFrameId: string | null,
): Map<string, RendererTier> {
  const tiers = new Map<string, RendererTier>();
  for (const [frameId, tabs] of frameTabs) {
    const activeIdx = frameActiveTab.get(frameId) ?? 0;
    for (let i = 0; i < tabs.length; i++) {
      const state: TerminalTierState = {
        terminalName: tabs[i].terminalName,
        frameId,
        isActiveTab: i === activeIdx,
        isInFocusedFrame: frameId === focusedFrameId,
      };
      tiers.set(tabs[i].terminalName, determineTier(state));
    }
  }
  return tiers;
}
