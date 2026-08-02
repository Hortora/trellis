# HANDOFF — epic-2-post-mvp

## Last Session

Built Garden Service + Provenance (trellis#14) across three repos: engine (provenance store, REST endpoints, MCP tool, adaptive search + entry lookup), soredium (provenance recording in brainstorming + work-start skills), trellis (GardenClient, GardenResource with workspace enrichment, Garden View UI). All tests green — engine 187, trellis 187. Filed engine#74 for CBR-based outcome tracking. Blog entry written.

## Immediate Next Step

Start #20 (Process Isolation + Session Lifecycle). Run `/work` to continue on this branch.

## What's Left

- Engine changes (5 commits) need pushing to remote · XS · Low
- Soredium skill changes (1 commit) need pushing to remote · XS · Low
- Trellis changes on this branch — will push at epic close · XS · Low
- Garden View entry detail click needs manual browser test (Playwright had caching issues; curl-verified working) · XS · Low

## What's Next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #20 | Process Isolation + Session Lifecycle | L | Med | Cross-cutting, ready |
| #15 | Artifact Browser (B6b) | M | Low | Ready, parallel track |
| #17 | LLM Coordinator L2 — Propose Actions | L | High | Ready, parallel track |
| #19 | Velocity Tracking + Projections | M | Med | Ready, parallel track |
| #1 | Workspace State Log | M | Med | Cross-cutting, ready |
| #16 | Drafthouse Integration (B6c) | M | Med | Blocked by #15 |

## Epic Progress

Batches 1–6 (MVP) complete. Batch 7 (#14) complete. Remaining: #15, #16, #17, #18, #19, #20, #1.

## References

- Spec: `docs/specs/2026-08-02-garden-service-provenance-design.md`
- Plan: `docs/plans/2026-08-02-garden-service-provenance.md`
- Blog: `~/claude/mdproctor.github.io/_notes/2026-08-02-mdp02-when-knowledge-forgets.md`
- Engine provenance: `io.hortora.garden.provenance` package
- Trellis garden: `io.hortora.trellis.garden` package
- Outcome tracking: Hortora/engine#74
