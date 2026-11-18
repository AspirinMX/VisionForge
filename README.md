# VisionForge

**An AI-native multimodal film, image, and video creation studio built for durable workflows, verifiable storyboarding, and clean workspace isolation.**

[简体中文](README_zh-CN.md) · [Documentation Hub](docs/README.md) · [Contributing Guide](CONTRIBUTING.md)

---

## Overview

VisionForge is a full-stack multimodal creative workbench combining a multi-turn tool-using AI agent runtime with an interactive studio workspace and an isolated administrative console:

- **Durable AI Agent & Media Orchestration**: Driven by **Temporal workflows** (`AgentRunWorkflow`, `MediaGenerationWorkflow`, `VideoCompositionWorkflow`), separating paid upstream calls, asynchronous polling, media archiving, and credit settlement from web request lifecycles.
- **Conversation-Scoped Work Areas**: Each conversation owns its own isolated scripts, storyboards, prompts, uploaded reference images, generated candidates, and FFmpeg-composed outputs—without workspace tenancy overhead or cross-user leakage (`404` on foreign resources).
- **Real-Time Streaming & Persisted Process Timelines**: Live **SSE** (`fetch` reader over Redis pub/sub) delivers reasoning, reasoning summaries, tool call start/waiting/done status transitions, and token deltas, backed by full database persistence for seamless reload recovery.
- **Storyboard-to-Video Pipeline**: Validated structured JSON storyboards (`schemaVersion: 1`), shot-level first-frame and video candidate binding, and FFmpeg + `ffprobe` range-checked multipart S3 composition.
- **Restrained Animate UI Studio + Isolated `/admin` Console**: Next.js 16 App Router interface using **Animate UI** (`@animate-ui/react` + Base UI + `motion`) with a floating capsule composer, collapsible canvas tabs (`Storyboard`, `Files`, `Assets`, `Tasks`), and a separated `/admin` interface for model routing and user role administration.

---

## Architecture at a Glance

```text
Browser (Next.js 16 + Animate UI)
   │  /api/v1/* (same-origin runtime proxy via frontend/src/app/api/v1/[...path]/route.ts)
   ▼
Spring Boot API (port 8080, module: api)
   ├── Identity / JWT & Admin Guards (identity)
   ├── Presigned S3 Tickets & Conversation Files (asset)
   ├── Credit Reservation / Ledger Views (billing)
   ├── SSE Bridge (Redis Pub/Sub Listener -> SseEmitter)
   └── Enqueues WorkflowStartRequest + Temporal Start ───► Temporal Server (port 7233)
                                                               │
Spring Boot Temporal Worker (module: worker) ◄─────────────────┘
   ├── AgentRunWorkflow (OpenAI Responses API + 17 Tools + Context Compactor)
   ├── MediaGenerationWorkflow (OpenAI Images, Ark Seedream/Seedance, DashScope)
   ├── VideoCompositionWorkflow (FFmpeg + ffprobe + S3 Multipart Upload)
   └── AgentRunStartCompensator (retries unstarted DB workflow requests)
         │
         ├──► PostgreSQL 18 (Flyway V1–V15, UUIDv7 PKs)
         ├──► Redis 7 (best-effort SSE event fan-out)
         └──► RustFS / S3-Compatible Storage (binary media, presigned GET/PUT)
```

---

## Repository Structure

| Path | Responsibility |
| --- | --- |
| `common/` | Shared contracts (`ApiResponse`, `PageResult`, `ErrorCode`, `BizAssert`), `UuidV7Generator`, `TraceContext`, `LogMasker`, `@BizLog` AOP infrastructure |
| `identity/` | Users, roles (`ADMIN`, `USER`), bcrypt authentication, JWT & hashed refresh token rotation |
| `asset/` | Conversation-scoped versioned text files (`conversation_file`) and S3 object assets / presigned upload sessions (`asset`, `upload_session`) |
| `billing/` | Credit accounts, idempotent reserve/settle/release operations, and keyset-paginated `credit_ledger` |
| `agent/` | Model registry (AES-GCM encrypted keys), conversation/run domain, context compaction, storyboard validation, AI & media adapters, Temporal workflows/activities |
| `api/` | Spring Boot HTTP & SSE REST API, security filter chain, exception handling, request DTOs |
| `worker/` | Non-web Spring Boot Temporal worker host, pollers/concurrency config, `AgentRunStartCompensator` |
| `database/` | Flyway SQL migrations (`V1`–`V15`) decoupled from runtime app startup |
| `frontend/` | Next.js 16 App Router UI (`(auth)`, `(studio)`, dedicated `(admin)` route groups) using React 19, Tailwind CSS v4, and Animate UI |
| `scripts/` | Local development utilities including `mock_media_server.py` (OpenAI / Ark / DashScope media mock) |
| `docs/` | Enterprise maintenance, architecture, API contract, design, and coding standards documentation |

---

## Tech Stack

| Layer | Technologies & Baseline Versions |
| --- | --- |
| **Backend Runtime** | Java `21`, Spring Boot `4.1.2`, Maven Wrapper (`pom.xml` dependency management) |
| **Workflow Engine** | Temporal Java SDK `1.41.1`, Temporal Server `temporalio/auto-setup:1.29.5` |
| **Persistence & Migration** | PostgreSQL `18`, Flyway `12.4.0`, MyBatis-Plus `3.5.18.1` (`mybatis-plus-spring-boot4-starter`), `hutool-core:5.8.48` |
| **Messaging & Storage** | Redis `7`, RustFS / AWS SDK S3 `2.59.2`, FFmpeg / `ffprobe` |
| **AI & Media SDKs** | OpenAI Java SDK `4.99.0` (Responses API streaming + Images), Volcengine Ark Runtime `0.17.0`, DashScope HTTP adapters |
| **Frontend** | Next.js `16.4.6` (Turbopack, `cacheComponents`), React `19.3.0`, TypeScript `5`, Tailwind CSS `4.3.3`, Animate UI (`@animate-ui/react:2.4.6`), `motion:14.14.0`, `@base-ui/react` |

---

## Quick Start

### 1. Prerequisites

- JDK `21` (Maven Wrapper `./mvnw` is bundled)
- Node.js & `pnpm` (`12.21.0`)
- Docker & Docker Compose
- `ffmpeg` and `ffprobe` on `PATH` (for worker video composition or local video mock)

### 2. Start Development Infrastructure & Migrate Database

```sh
cp .env.example .env
docker compose up -d --wait postgres redis rustfs temporal temporal-ui
docker compose up rustfs-init
./mvnw -pl database flyway:migrate
```

- PostgreSQL: `localhost:5432` (`visionforge` / `visionforge`)
- Redis: `localhost:6379`
- RustFS S3 & Console: `localhost:9000` / `localhost:9001` (`rustfsadmin` / `rustfsadmin`)
- Temporal gRPC & UI: `localhost:7233` / [http://localhost:8233](http://localhost:8233)

### 3. Configure Required Secrets & Run Backend Services

Generate Base64 32-byte secrets (never commit `.env`):

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
export MODEL_CONFIG_SECRET="$(openssl rand -base64 32)"
# Optional first administrator bootstrap:
export BOOTSTRAP_ADMIN_EMAIL="admin@example.com"
export BOOTSTRAP_ADMIN_PASSWORD="change-this-strong-password"
```

Start the API and Worker from the repository root (two terminals):

```sh
./mvnw -pl api spring-boot:run
./mvnw -pl worker spring-boot:run
```

### 4. Start the Frontend

```sh
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend dev
```

Open [http://localhost:3000](http://localhost:3000). Browser `/api/v1/*` requests proxy cleanly through `frontend/src/app/api/v1/[...path]/route.ts` (configurable at runtime with `VISIONFORGE_API_BASE_URL`).

> [!NOTE]
> **Local Media Mock**: To test image/video generation without paid upstream calls, run `python scripts/mock_media_server.py --port 8000` with `MEDIA_ALLOW_HTTP=true` in the worker and configure model `baseUrl` values in `/admin/models`. Never enable `MEDIA_ALLOW_HTTP` in production.

### 5. Verification & Production Deployment

```sh
# Backend unit & module tests
./mvnw -B test

# Frontend lint, typecheck & production build
pnpm --dir frontend lint
pnpm --dir frontend build

# Full containerized stack (API + Worker + Frontend + DB migration job)
docker compose -f docker-compose.prod.yaml up -d --build
```

---

## Documentation

| Document | Description |
| --- | --- |
| [docs/README.md](docs/README.md) | Documentation hub and recommended reading order |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Backend module graph, Temporal orchestration, Redis SSE, RustFS S3 & security design |
| [docs/DESIGN_DETAILS.md](docs/DESIGN_DETAILS.md) | Schema reference (`V1`–`V15`), state machines, storyboard schema, context compaction & UI design |
| [docs/API_REFERENCE.md](docs/API_REFERENCE.md) | Full HTTP & SSE API specification, request/response schemas & error codes |
| [docs/BACKEND_DEV_STANDARDS.md](docs/BACKEND_DEV_STANDARDS.md) | Enterprise Java coding rules (Mandatory / Recommended / Prohibited) |
| [docs/FRONTEND_DEV_STANDARDS.md](docs/FRONTEND_DEV_STANDARDS.md) | Next.js / React / Animate UI coding rules (Mandatory / Recommended / Prohibited) |
| [docs/DEVELOPMENT_SETUP.md](docs/DEVELOPMENT_SETUP.md) | Detailed local setup, environment variables, mock server & troubleshooting |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Contributor onboarding & multi-line commit format requirements |
