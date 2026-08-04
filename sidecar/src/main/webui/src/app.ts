import './components/workbench';
import './views/launcher';

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
