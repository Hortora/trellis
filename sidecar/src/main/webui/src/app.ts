import { loadSite } from "@casehubio/pages-runtime";
import { page, title } from "@casehubio/pages-ui";

const app = page("Trellis",
  title("Trellis — no workspace loaded."),
);

const container = document.getElementById("app");
if (container) {
  loadSite(container, app).catch(console.error);
}
