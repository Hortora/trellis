import { applyTheme } from '@casehubio/pages-ui-tokens';
import '@casehubio/pages-component-terminal';
import './components/workbench';
import './components/memory-panel';
import './views/launcher';

applyTheme('casehub-dark');
document.documentElement.classList.add('pages-density-compact');

const container = document.getElementById('app');
let currentView: 'launcher' | 'workbench' = 'launcher';
let workbench: HTMLElement | null = null;

function route() {
  if (!container) return;
  const hash = location.hash;

  const rootMatch = hash.match(/[?&]root=([^&]+)/);
  const wantsLauncher = !rootMatch && (hash === '' || hash === '#' || hash === '#launcher');

  if (wantsLauncher) {
    if (currentView !== 'launcher') {
      container.innerHTML = '';
      container.appendChild(document.createElement('trellis-launcher'));
      currentView = 'launcher';
      workbench = null;
    }
    return;
  }

  if (!workbench) {
    container.innerHTML = '';
    workbench = document.createElement('trellis-workbench');
    container.appendChild(workbench);
    currentView = 'workbench';
  }

  if (rootMatch) {
    (workbench as any).workspaceRoot = decodeURIComponent(rootMatch[1]);
  }
}

window.addEventListener('hashchange', route);
route();
