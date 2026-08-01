# Trellis — Design

## Architecture

Electron shell + Quarkus sidecar (sparge pattern). Electron manages window lifecycle and sidecar process; all application logic lives in the sidecar REST API + Quinoa frontend.

## Module Structure

| Module | Type | Purpose |
|--------|------|---------|
| `shell/` | Electron | Window management, sidecar lifecycle, port discovery |
| `sidecar/` | Quarkus | REST API, backend logic, Quinoa frontend host |
| `sidecar/src/main/webui/` | TypeScript | Pages + blocks-ui frontend (esbuild, Yarn Berry) |

## Key Abstractions

_To be documented._

## Data Model

_To be documented._

## Configuration

_To be documented._
