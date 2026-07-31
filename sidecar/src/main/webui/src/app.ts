import "./views/org-dashboard";
import "./views/slot-detail";
import "./views/launcher";
import "./views/epic-dashboard";

const container = document.getElementById("app");

function route() {
  if (!container) return;
  const hash = location.hash;
  const slotMatch = hash.match(/^#slot\/(\d+)\?root=(.+)$/);
  const rootMatch = hash.match(/^#\?root=(.+)$/);

  container.innerHTML = '';

  if (slotMatch) {
    const slotNum = parseInt(slotMatch[1]);
    const root = decodeURIComponent(slotMatch[2]);
    const detail = document.createElement('trellis-slot-detail') as any;
    detail.slotNumber = slotNum;
    detail.workspaceRoot = root;
    container.appendChild(detail);
  } else if (rootMatch) {
    const root = decodeURIComponent(rootMatch[1]);
    const dashboard = document.createElement('trellis-org-dashboard') as any;
    dashboard.workspaceRoot = root;
    container.appendChild(dashboard);
  } else if (hash.match(/^#epic\/([^/]+)\/([^/]+)\/(\d+)/)) {
    const epicMatch = hash.match(/^#epic\/([^/]+)\/([^/]+)\/(\d+)/)!;
    const rootParam = hash.match(/[?&]root=([^&]+)/);
    const dashboard = document.createElement('trellis-epic-dashboard') as any;
    dashboard.owner = epicMatch[1];
    dashboard.repo = epicMatch[2];
    dashboard.epicNumber = parseInt(epicMatch[3]);
    if (rootParam) dashboard.workspaceRoot = decodeURIComponent(rootParam[1]);
    container.appendChild(dashboard);
  } else if (hash === '' || hash === '#' || hash === '#launcher') {
    const launcher = document.createElement('trellis-launcher');
    container.appendChild(launcher);
  } else {
    const dashboard = document.createElement('trellis-org-dashboard');
    container.appendChild(dashboard);
  }
}

window.addEventListener('hashchange', route);
route();
