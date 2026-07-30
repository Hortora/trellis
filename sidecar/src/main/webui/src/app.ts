import { loadSite, registerPanel } from "@casehubio/pages-runtime";
import { page, hostPanel } from "@casehubio/pages-ui";
import "./views/org-dashboard";

registerPanel("org-dashboard", "trellis-org-dashboard");

const app = page("Trellis",
  hostPanel("org-dashboard"),
);

const container = document.getElementById("app");
if (container) {
  loadSite(container, app).catch(console.error);
}
