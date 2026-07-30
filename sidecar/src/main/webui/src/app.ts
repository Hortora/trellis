import "./views/org-dashboard";
import "./views/slot-detail";

const container = document.getElementById("app");

function route() {
  if (!container) return;
  const hash = location.hash;
  const slotMatch = hash.match(/^#slot\/(\d+)\?root=(.+)$/);

  if (slotMatch) {
    const slotNum = parseInt(slotMatch[1]);
    const root = decodeURIComponent(slotMatch[2]);
    container.innerHTML = '';
    const detail = document.createElement('trellis-slot-detail') as any;
    detail.slotNumber = slotNum;
    detail.workspaceRoot = root;
    container.appendChild(detail);
  } else {
    container.innerHTML = '';
    const dashboard = document.createElement('trellis-org-dashboard');
    container.appendChild(dashboard);
  }
}

window.addEventListener('hashchange', route);
route();
