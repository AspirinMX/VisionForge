# VisionForge

**An AI-native multimodal film, image, and video creation studio built for durable workflows, verifiable storyboarding, and clean workspace isolation.**

[简体中文](README_zh-CN.md) · [Documentation Index](docs/README.md) · [Contributing](CONTRIBUTING.md)

---

## Overview

VisionForge is a full-stack multimodal creative workbench combining a multi-turn tool-using AI agent runtime with an interactive studio workspace and an isolated administrative console:

- **Durable AI Agent & Media Orchestration**: Powered by **Temporal workflows** (`AgentRunWorkflow`, `MediaGenerationWorkflow`, `VideoCompositionWorkflow`) that separate paid upstream calls, async polling, media archiving, FFmpeg composition, and credit settlement from HTTP request lifecycles.
- **Conversation-Scoped Work Areas**: Each conversation owns its own isolated scripts, storyboards, prompts, uploaded references, generated candidates, and composed outputs, with strict user-level ownership (`404` on unauthorized access).
- **Real-Time Streaming & Persisted Timelines**: Live **SSE** (over Redis Pub/Sub and `fetch` readers) streams reasoning, tool progress, and token deltas while PostgreSQL persists full execution records for seamless page-reload recovery.
- **Storyboard-to-Video Pipeline**: Validated structured JSON storyboards, shot-level first-frame/video candidate tracking, `ffprobe` range verification, FFmpeg rendering, and S3 multipart output publishing.
- **Animate UI Studio & Isolated `/admin` Console**: Next.js 16 App Router interface built with **Animate UI** (`@animate-ui/react`, Base UI primitives, and `motion`), separating creator workspaces under `(studio)` from system administration under `(admin)`.

---

## Demo

![VisionForge studio: the agent turns a reference portrait into a 3-second 480P clip and delivers it to the task center](assets/demo_1.png)

The agent reads the reference portrait, generates a 3-second 480P clip, and returns the delivered video in the conversation's task center:

<video src="https://raw.githubusercontent.com/AspirinMX/VisionForge/main/assets/demo.mp4" controls muted width="100%"></video>

If the inline player is unavailable, [open the generated video](assets/demo.mp4).

---

## Architecture Overview

```text
Browser (Next.js 16 + Animate UI)
   │  /api/v1/* (runtime proxy via frontend/src/app/api/v1/[...path]/route.ts)
   ▼
Spring Boot API (:8080, module: api)
   ├── Identity / JWT & Admin Guards (identity)
   ├── Conversation Files & S3 Presigned Uploads (asset)
   ├── Credit Accounts & Ledger (billing)
   ├── SSE Bridge (Redis Pub/Sub -> SseEmitter)
   └── Workflow Start Outbox + Temporal Client ─────► Temporal Server (:7233)
                                                            │
Spring Boot Temporal Worker (module: worker) ◄──────────────┘
   ├── AgentRunWorkflow (Responses API + Agent Tools + Context Compactor)
   ├── MediaGenerationWorkflow (OpenAI Images, Ark, DashScope)
   ├── VideoCompositionWorkflow (FFmpeg + ffprobe + S3 Multipart)
   └── AgentRunStartCompensator (retries pending DB workflow requests)
         │
         ├──► PostgreSQL (Flyway-managed schema)
         ├──► Redis (best-effort SSE event fan-out)
         └──► RustFS / S3-Compatible Storage (binary assets & outputs)
```

---

## Repository Structure

| Path | Role |
| --- | --- |
| `common/` | Shared contracts, UUIDv7 ID generation, error model, logging & audit infrastructure |
| `identity/` | User management, bcrypt authentication, JWT access tokens, and refresh token rotation |
| `asset/` | Conversation-scoped versioned files, upload sessions, and S3 object lifecycle |
| `billing/` | Credit accounts, reservations, settlement, and ledger |
| `agent/` | Model registry, conversations, runtime turns, context compaction, storyboard validation, AI/media adapters, and Temporal workflows |
| `api/` | Spring Boot HTTP & SSE REST API, security configuration, and DTO layer |
| `worker/` | Non-web Temporal Worker host and workflow start compensator |
| `database/` | Standalone Flyway SQL migrations |
| `frontend/` | Next.js App Router studio & isolated admin application |
| `scripts/` | Local development scripts (including media mock server) |
| `assets/` | README demo screenshot and a generated video example |
| `docs/` | Technical architecture, API contracts, design details, coding standards, and setup documentation (index in [`docs/README.md`](docs/README.md)) |
| `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `lombok.config` | Root Maven configuration, wrapper, and Java compiler tooling |
| `Dockerfile.api`, `Dockerfile.worker`, `Dockerfile.frontend`, `docker-compose.yaml`, `docker-compose.prod.yaml`, `.dockerignore`, `.env.example` | Container images, Compose stacks, and environment template |
| `AGENTS.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `LICENSE` | Repository agent instructions, contribution guide, and license |
| `.gitignore`, `.idea/` | VCS ignore rules and shared IDE metadata |

---

## Tech Stack

| Layer | Baseline Stack |
| --- | --- |
| **Backend Runtime** | Java `21`, Spring Boot `4.1.2`, Maven Wrapper |
| **Workflow Engine** | Temporal Java SDK `1.41.1`, Temporal Server (`temporalio/auto-setup:1.29.5`) |
| **Persistence & Migration** | PostgreSQL `18`, Flyway `12.4.0`, MyBatis-Plus `3.5.18.1`, `hutool-core:5.8.48` |
| **Messaging & Media** | Redis `7`, RustFS / AWS SDK S3 `2.59.2`, FFmpeg / `ffprobe` |
| **Model & Media SDKs** | OpenAI Java SDK `4.99.0`, Volcengine Ark & DashScope HTTP adapters |
| **Frontend** | Next.js `16.4.6` (Turbopack, `cacheComponents`), React `19.3.0`, TypeScript `5`, Tailwind CSS `4.3.3`, Animate UI (`@animate-ui/react:2.4.6`), `motion:14.14.0`, `@base-ui/react` |

---

## Quick Start

### 1. Prerequisites

- JDK `21` (bundled `./mvnw`)
- Node.js & `pnpm` (`12.21.0`)
- Docker & Docker Compose
- `ffmpeg` and `ffprobe` on `PATH` (required for video composition and local video mocking)

### 2. Start Infrastructure & Apply Migrations

```sh
cp .env.example .env
docker compose up -d --wait postgres redis rustfs temporal temporal-ui
docker compose up rustfs-init
./mvnw -pl database flyway:migrate
```

- PostgreSQL: `localhost:5432` (`visionforge` / `visionforge`)
- Redis: `localhost:6379`
- RustFS S3 & Console: `localhost:9000` / `localhost:9001`
- Temporal gRPC & UI: `localhost:7233` / [http://localhost:8233](http://localhost:8233)

### 3. Configure Secrets & Run API + Worker

Generate Base64 32-byte secrets (never commit `.env`):

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
export MODEL_CONFIG_SECRET="$(openssl rand -base64 32)"
# Optional bootstrap administrator (password length 12-72):
export BOOTSTRAP_ADMIN_EMAIL="admin@example.com"
export BOOTSTRAP_ADMIN_PASSWORD="change-this-strong-password"
```

Start the API and Worker from the repository root:

```sh
./mvnw -pl api spring-boot:run
./mvnw -pl worker spring-boot:run
```

### 4. Start Frontend

```sh
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend dev
```

Open [http://localhost:3000](http://localhost:3000). `/api/v1/*` requests proxy cleanly via `frontend/src/app/api/v1/[...path]/route.ts` (target controlled by `VISIONFORGE_API_BASE_URL`).

> [!NOTE]
> **Local Media Mock**: Run `python scripts/mock_media_server.py --port 8000` (pass `--ffmpeg-path <path>` or set `FFMPEG_PATH` if `ffmpeg` is not on `PATH`, or use `--video-file <sample.mp4>`) with `MEDIA_ALLOW_HTTP=true` in the worker to test image/video pipelines locally without paid calls. Keep `MEDIA_ALLOW_HTTP` disabled in production.

### 5. Build, Test & Full Stack Deployment

```sh
# Backend tests
./mvnw -B test

# Frontend lint & build
pnpm --dir frontend lint
pnpm --dir frontend build

# Full containerized stack
docker compose -f docker-compose.prod.yaml up -d --build
```

---

## Documentation & Governance

- **Technical Documentation Hub**: [`docs/README.md`](docs/README.md)
- **Contribution Guidelines**: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- **License**: [`LICENSE`](LICENSE)
