import { registerPanel } from '@casehubio/pages-runtime';
import type { Entry, ContentFactory } from '@casehubio/pages-runtime/dist/frame-sandbox';
import type { DockPanelConfig } from '@casehubio/pages-ui/dist/dsl/builders.js';
import { hostPanel } from '@casehubio/pages-ui/dist/dsl/builders.js';

import '../views/org-dashboard.js';
import '../views/slot-detail.js';
import '../views/epic-dashboard.js';
import '../views/garden-view.js';
import '../views/artifact-panel.js';
import '../views/repo-detail.js';
import '../components/coordinator-panel.js';
import '../components/workspace-view.js';
import '../views/protocol-view.js';
import '../views/backlog-panel.js';
import '../views/intelligence-panel.js';
import '../views/blockers-panel.js';

export const PANEL_TAGS: Record<string, string> = {
  workspace:   'trellis-workspace-view',
  dashboard:   'trellis-org-dashboard',
  slot:        'trellis-slot-detail',
  artifacts:   'trellis-artifact-panel',
  garden:      'trellis-garden-view',
  protocols:   'trellis-protocol-view',
  coordinator: 'trellis-coordinator-panel',
  memory:      'trellis-memory-panel',
  backlog:     'trellis-backlog-panel',
  epic:        'trellis-epic-dashboard',
  repo:        'trellis-repo-detail',
  intelligence:'trellis-intelligence-panel',
  blockers:    'trellis-blockers-panel',
};

export function registerAllPanels(): void {
  for (const [key, tag] of Object.entries(PANEL_TAGS)) {
    registerPanel(key, tag);
  }
}

export function createPanelFactory(workspaceRoot: string): ContentFactory {
  return (entry: Entry) => {
    const tag = PANEL_TAGS[entry.key];
    if (!tag) throw new Error(`Unknown panel: ${entry.key}`);
    const el = document.createElement(tag);
    (el as any).workspaceRoot = workspaceRoot;
    return { element: el, dispose: () => el.remove() };
  };
}

export const DOCK_PANELS: DockPanelConfig[] = [
  { key: 'workspace',   label: 'Workspace',   icon: '\u{2B1A}', content: hostPanel('workspace'),   fixed: true, defaultOpen: true },
  { key: 'dashboard',   label: 'Dashboard',   icon: '\u{1F4C1}', content: hostPanel('dashboard') },
  { key: 'backlog',     label: 'Backlog',      icon: '\u{1F4CB}', content: hostPanel('backlog') },
  { key: 'artifacts',   label: 'Artifacts',    icon: '\u{1F4C4}', content: hostPanel('artifacts') },
  { key: 'garden',      label: 'Garden',       icon: '\u{1F33F}', content: hostPanel('garden') },
  { key: 'protocols',   label: 'Protocols',    icon: '\u{1F4DC}', content: hostPanel('protocols') },
  { key: 'coordinator', label: 'Coordinator',  icon: '\u{1F916}', content: hostPanel('coordinator') },
  { key: 'memory',      label: 'Memory',       icon: '\u{1F4CA}', content: hostPanel('memory') },
  { key: 'intelligence',label: 'Intelligence', icon: '\u{1F50D}', content: hostPanel('intelligence') },
  { key: 'blockers',     label: 'Blockers',     icon: '\u{1F6A7}', content: hostPanel('blockers') },
];
