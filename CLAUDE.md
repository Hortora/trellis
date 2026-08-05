# CLAUDE.md

## No AI Attribution in Commits -- Ever

**NEVER add AI attribution to any commit message unless the user explicitly requests it for that specific commit.**

---

## Project Identity

**Name:** trellis
**GitHub:** [Hortora/trellis](https://github.com/Hortora/trellis)

## Project Type

**Type:** java, ts
**Stage:** pre-release

## What It Is

Trellis is a local Electron app backed by a Quarkus sidecar that serves as an epic delivery engine for spec-driven parallel development across multi-repo organisations.

## Architecture

- **Electron shell** (`shell/`) — launches the Quarkus sidecar, opens BrowserWindow at the sidecar's dynamic port. No Node.js application logic — window management and sidecar lifecycle only (sparge pattern).
- **Quarkus sidecar** (`sidecar/`) — REST API + Quinoa frontend. Serves the pages/blocks-ui dashboard on a dynamic port. All backend logic lives here.
- **Frontend** (`sidecar/src/main/webui/`) — TypeScript, esbuild, pages + blocks-ui components. Yarn Berry (v4) with portal: resolutions for casehub-packages.

## Build & Test

```bash
# Sidecar
/opt/homebrew/bin/mvn -f sidecar/pom.xml compile          # compile
/opt/homebrew/bin/mvn -f sidecar/pom.xml test              # run tests
/opt/homebrew/bin/mvn -f sidecar/pom.xml quarkus:dev       # dev mode
/opt/homebrew/bin/mvn -f sidecar/pom.xml package -DskipTests  # build jar

# Frontend (inside sidecar/src/main/webui/)
yarn install                                               # install deps
yarn build                                                 # build frontend
yarn test                                                  # run vitest tests
yarn test:watch                                            # vitest watch mode

# Electron shell
npm install --prefix shell                                 # install electron
npm start                                                  # launch app (requires sidecar jar)

# Full launch
/opt/homebrew/bin/mvn -f sidecar/pom.xml package -DskipTests && npm start
```

## Key Conventions

- Java 21 — records, sealed interfaces, pattern matching
- Package root: `io.hortora.trellis`
- Quarkus 3.x with Quinoa (esbuild + yarn berry)
- Pages/blocks-ui consumed as Maven SNAPSHOT artifacts via portal: resolutions
- Electron shell follows sparge pattern: find free port, spawn sidecar, poll health/ready, open window
- `GET /api/health` — sidecar liveness; `GET /api/health/ready` — sidecar readiness (200 after TerminalRegistry bootstrap, 503 before)
- Frontend uses a dock-bar workbench shell (`trellis-workbench`) — views are panels, not standalone hash-routed pages
- Workspace panel (`trellis-workspace-view`) — Dockview-backed floating frames with tabbed terminals. Dashboard panel (`trellis-org-dashboard`) — organisational overview (repo cards, slots, epics)
- Workspace view terminology: Workbench → Panel → Frame → Tab. Frames are Dockview floating groups; tabs reference terminals by name
- Dockview (`dockview-core` v7+) for frame/tab management — vanilla TS, MIT, zero dependencies
- New panels should use platform rendering primitives (`marked`, pages DSL, `pages-data-table`) — the artifact panel is the reference for markdown, the memory panel is the reference for tabular data
- Tables use `pages-data-table` with `fromRows()` for data binding, custom `columnRenderers` for badges/buttons, and `mode="paginated"` for content-sized tables
- Frontend theme: `casehub-dark` via `applyTheme()` + `pages-density-compact` class on documentElement
- `GET /api/artifacts?root=...` — list workspace/project artifacts; `GET /api/artifacts/content?path=...&root=...` — serve raw markdown
- `GET /api/terminals` — list all terminal sessions with agent state/memory; `GET /api/terminals/{name}/agent/tree` — process tree breakdown
- `GET /api/protocols/repos?root=...` — list repos with `docs/protocols/INDEX.md`; `GET /api/protocols/entries?index=...` — parse INDEX.md chain; `POST/DELETE /api/protocols/entries` — add/remove with git commit
- Protocol panel (`trellis-protocol-view`) — accordion repo list, garden-style entry rows, split-pane layout, garden search integration for adding entries

## Project Artifacts

Paths that are project content (not workspace noise). Skills use this to avoid
filtering or dropping commits that touch these paths.

| Path | What it is |
|------|------------|
| `CLAUDE.md` | Project conventions (build, test, naming) |
| `docs/adr/` | Architecture decision records |
| `docs/ARC42STORIES.MD` | Design document |

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** Hortora/trellis
**Changelog:** GitHub Releases (run `gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — when the user says "implement", "start coding",
  "execute the plan", "let's build", or similar: check if an active issue or epic
  exists. If not, run issue-workflow Phase 1 to create one **before writing any code**.
- **Before writing any code** — check if an issue exists for what's about to be
  implemented. If not, draft one and assess epic placement (issue-workflow Phase 2)
  before starting. Also check if the work spans multiple concerns.
- **Before any commit** — run issue-workflow Phase 3 (via git-commit) to confirm
  issue linkage and check for split candidates. This is a fallback — the issue
  should already exist from before implementation began.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
  If the user explicitly says to skip ("commit as is", "no issue"), ask once to confirm
  before proceeding — it must be a deliberate choice, not a default.
