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
- Electron shell follows sparge pattern: find free port, spawn sidecar, poll health, open window
- `GET /api/health` — sidecar readiness endpoint
