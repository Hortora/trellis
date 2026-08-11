# HANDOFF — issue-43-frame-tab-management

## Last Session

Built #43 Frame and Tab Management via Agent Control Plane. Feature implementation complete: `getUIState()` for observation, `trellis_workspace` extended with `operation`+`params` for 14 frame/tab operations, SSE `control:workspace` transport, `handleCommand()` dispatch, navigate frame/tab focus. 184 unit tests passing. MCP round-trip verified live — frames created, removed, moved via MCP tool calls appearing in Playwright browser.

During live testing found and TDD-fixed 7 bugs (SSE topic routing, correlation race, Dockview overlay API, terminal fit, renderer tiers, terminal connection, z-order delegation). Found 7 more bugs that need fixing before branch close.

## Immediate Next Step

Fix the 7 remaining bugs on this branch. Use systematic-debugging for root cause, TDD each fix. The bugs and how to approach them were printed at session end — paste them into the next session prompt. Key instruction from the user: "fix root problems, not symptoms" and "as we find errors, TDD them — write a failing test first."

**To test live:** `mvn -f sidecar/pom.xml quarkus:dev -Dquarkus.http.port=8090 -DskipTests`, scan workspace via `curl "http://localhost:8090/api/workspace?root=/Users/mdproctor/claude/public/casehub"`, open in Playwright. MCP at `POST /mcp` with `Accept: application/json, text/event-stream`.

## References

- Spec: `docs/specs/issue-43-frame-tab-management/2026-08-06-frame-tab-management-design.md`
- Plan: `docs/plans/2026-08-06-frame-tab-management.md`
- Key files: `sidecar/src/main/webui/src/components/workspace-view.ts`, `workbench.ts`, `sidecar/src/main/java/io/hortora/trellis/mcp/TrellisTools.java`
