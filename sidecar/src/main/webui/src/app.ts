import './components/workbench';
import './views/launcher';

const container = document.getElementById('app');

function route() {
  if (!container) return;
  const hash = location.hash;

  container.innerHTML = '';

  const rootMatch = hash.match(/[?&]root=([^&]+)/);
  if (!rootMatch && (hash === '' || hash === '#' || hash === '#launcher')) {
    const launcher = document.createElement('trellis-launcher');
    container.appendChild(launcher);
    return;
  }

  const workbench = document.createElement('trellis-workbench') as any;
  if (rootMatch) {
    workbench.workspaceRoot = decodeURIComponent(rootMatch[1]);
  }
  container.appendChild(workbench);
}

window.addEventListener('hashchange', route);
route();
