# VisionForge

An image and video generation workbench.

[简体中文](README_zh-CN.md)

## Repository layout

| Path | Purpose |
| --- | --- |
| `pom.xml` and module directories at the repository root | Java 21 Maven parent project and all backend modules |
| `frontend/` | Next.js App Router application with TypeScript and Tailwind CSS |
| `Dockerfile`, `docker-compose.yaml`, `.dockerignore`, `.env.example` | Deployment configuration at the repository root |
| `docs/` | Project documentation |

## Frontend development

Run from the repository root:

```sh
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend dev
```

The development server listens on port 3000. The starter page is `frontend/src/app/page.tsx`.

```sh
pnpm --dir frontend lint
pnpm --dir frontend build
pnpm --dir frontend start
```

## Backend development

Import the root `pom.xml` as the Maven project in your IDE. The repository includes the Maven Wrapper, so a local Maven installation is not required:

```sh
./mvnw validate
```

Dependency and plugin versions are managed centrally by the root POM (Java 21, Spring Boot 4.1.2 BOM, Temporal Java SDK 1.41.1 BOM). Minimal Spring Boot startup applications exist for the API and worker; the worker connects to Temporal and registers a self-check workflow.

Business database migrations live in `database/src/main/resources/db/migration` and are managed by Flyway. Start PostgreSQL and apply migrations from the repository root:

```sh
docker compose up -d --wait postgres redis
./mvnw -pl database flyway:migrate
```

The defaults target `jdbc:postgresql://localhost:5432/visionforge` with user and password `visionforge`. Override them with `-Dvisionforge.db.url=... -Dvisionforge.db.user=... -Dvisionforge.db.password=...` for Flyway, or the `VISIONFORGE_DB_URL`, `VISIONFORGE_DB_USER` and `VISIONFORGE_DB_PASSWORD` environment variables for the applications.

### Authentication and isolation

The API exposes `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout` and `/api/v1/auth/me`. Access tokens are short-lived JWTs carrying `sub`, `email` and `role`; refresh tokens are user-level, rotated and stored as hashes. Login issues a token pair directly — there is no workspace selection or switching.

There is no workspace entity and no membership. Each user only sees their own conversations and files; requests for another user's resources return `404`. The system roles `ADMIN` and `USER` gate the operational `/api/v1/admin/**` endpoints only and do not grant access to other users' content.

Set `JWT_SECRET` to a Base64-encoded value of at least 32 bytes before starting the API:

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
```

To bootstrap the first administrator, also set `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` (at least 12 characters). The account is created, or promoted to `ADMIN` if it already exists. The worker shares the same database settings and must be started after PostgreSQL is available.

### Text model configuration and conversations

The API and worker both require `MODEL_CONFIG_SECRET` (Base64, exactly 32 bytes) to encrypt provider API keys at rest:

```sh
export MODEL_CONFIG_SECRET="$(openssl rand -base64 32)"
```

A system administrator configures an OpenAI-compatible endpoint through `/api/v1/admin/models`:

```sh
curl -X POST http://localhost:8080/api/v1/admin/models \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "displayName": "Primary Text Model",
    "baseUrl": "https://gateway.example.com/v1",
    "apiKey": "sk-...",
    "modelId": "gpt-6-astra",
    "reasoningEffort": "MEDIUM",
    "maxInputTokens": 32000,
    "maxOutputTokens": 4096,
    "defaultModel": true
  }'
```

API keys are never returned; responses expose only `apiKeyConfigured`. The provider uses the official OpenAI Java SDK to stream `POST {baseUrl}/responses` (`responses().createStreaming()`) with an explicit `input` array, `store: false`, `max_output_tokens` and `reasoning.effort` when configured. Chat Completions is not used. The worker must be running to execute AgentRun workflows.

Conversation endpoints:

```text
POST /api/v1/conversations
GET  /api/v1/conversations
GET  /api/v1/conversations/{conversationId}
GET  /api/v1/conversations/{conversationId}/messages
POST /api/v1/conversations/{conversationId}/messages
POST /api/v1/conversations/{conversationId}/messages/stream
GET  /api/v1/agent-runs/{runId}
```

Sending a message returns `202 Accepted` with a `runId`. The workflow persists the assistant reply asynchronously; poll the AgentRun endpoint until its status is `COMPLETED` or `FAILED`. If Temporal is temporarily unavailable, the AgentRun stays `PENDING` and the worker's start compensator retries the workflow start.

The `/messages/stream` endpoint returns `text/event-stream`. It requires Redis (provided by Compose) and emits:

```text
event: accepted          data: {"type":"accepted","runId":"..."}
event: reasoning         data: {"type":"reasoning","content":"..."}
event: reasoning_summary data: {"type":"reasoning_summary","content":"..."}
event: delta             data: {"type":"delta","content":"..."}
event: done              data: {"type":"done","assistantMessageId":"..."}
event: error             data: {"type":"error","message":"..."}
```

Browsers should consume it with `fetch` and a streaming reader because `EventSource` cannot send the `Authorization` header. Redis is required for this endpoint; the non-streaming message endpoint still works without it. Redis pub/sub is best-effort for live delivery; the persisted assistant message remains the source of truth if the stream disconnects.

### Conversation work area

Every conversation has its own work area. Scripts, storyboards, prompts, images and videos created in that conversation belong to it and are not shared with other conversations or users. File updates create immutable versions and require `baseVersion` to detect concurrent edits.

```text
GET   /api/v1/conversations/{conversationId}/files
POST  /api/v1/conversations/{conversationId}/files
GET   /api/v1/conversations/{conversationId}/files/search
GET   /api/v1/conversations/{conversationId}/files/{fileId}
PATCH /api/v1/conversations/{conversationId}/files/{fileId}
GET   /api/v1/conversations/{conversationId}/files/{fileId}/versions
GET   /api/v1/conversations/{conversationId}/files/{fileId}/versions/{version}
```

The first version stores text files (`SCRIPT`, `STORYBOARD`, `PROMPT`, `OTHER`) in PostgreSQL with a 1 MB limit. V12 creates `conversation_file` and `conversation_file_version`.

The model uses the tools `list_files`, `search_files`, `read_file`, `create_file`, `update_file` and `list_file_versions`; the conversation and user always come from the server-side execution context.

Worker runtime settings live under `visionforge.worker` and can be overridden with environment variables such as `WORKER_MAX_CONCURRENT_ACTIVITIES`, `WORKER_MAX_CONCURRENT_WORKFLOW_TASKS`, `WORKER_WORKFLOW_CACHE_SIZE`, `WORKER_MAX_WORKFLOW_THREADS` and `WORKER_GRACEFUL_SHUTDOWN_TIMEOUT`. Start compensation is controlled by `WORKER_START_COMPENSATION_ENABLED`, `WORKER_START_COMPENSATION_INTERVAL_MS`, `WORKER_START_COMPENSATION_BATCH_SIZE` and `WORKER_START_COMPENSATION_MAX_ATTEMPTS`.

## Deployment

Deployment files are located at the repository root. The Dockerfile and Compose file remain placeholders; runnable service definitions will be added separately.
