# VisionForge

An image and video generation workbench.

[简体中文](README_zh-CN.md)

## Repository layout

| Path | Purpose |
| --- | --- |
| `pom.xml` and module directories at the repository root | Java 21 Maven parent project and all backend modules |
| `frontend/` | Next.js App Router application with TypeScript and Tailwind CSS |
| `Dockerfile.frontend`, `Dockerfile.api`, `Dockerfile.worker`, `docker-compose.yaml`, `docker-compose.prod.yaml`, `.dockerignore`, `.env.example` | Deployment configuration at the repository root |
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

Set `UPSTREAM_USER_AGENT` to override the `User-Agent` sent to the Responses API, OpenAI Images, Ark and DashScope, including provider result downloads. Leave it blank to keep each SDK's default.

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

API keys are never returned; responses expose only `apiKeyConfigured`. The provider uses the official OpenAI Java SDK to stream `POST {baseUrl}/responses` (`responses().createStreaming()`) with an explicit `input` array, `store: false`, `max_output_tokens` and `reasoning.effort` when configured. Chat Completions is not used. Each request also carries a stable `prompt_cache_key` derived from the conversation (`vf-chat:<conversationId>`; compaction uses `vf-compact:<conversationId>`) so repeated prefixes can reuse the provider prompt cache. No `prompt_cache_retention` override is sent. The worker must be running to execute AgentRun workflows.

Regular users read the enabled model catalog from `GET /api/v1/models?capabilityType=IMAGE|VIDEO|CHAT`. The response never includes provider base URLs or key material. Generation requests may omit `modelDefinitionId`; the server then routes to the default enabled model of the requested capability.

Conversation endpoints:

```text
POST /api/v1/conversations
GET  /api/v1/conversations
GET  /api/v1/conversations/{conversationId}
GET  /api/v1/conversations/{conversationId}/messages
POST /api/v1/conversations/{conversationId}/messages
POST /api/v1/conversations/{conversationId}/messages/stream
GET  /api/v1/agent-runs?conversationId=&status=&limit=
GET  /api/v1/agent-runs/{runId}
```

`MessageResponse` carries `assetIds` and `generationTaskIds`, resolved from `message_part`, so attached images survive a reload without client-side state.

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

### Credits and usage

```text
GET /api/v1/account/credit
GET /api/v1/account/credit/ledger?limit=50&before=<ledgerId>
GET /api/v1/account/usage?window=100
```

`credit` returns `balanceUnits`, `reservedUnits` and `availableUnits`. The ledger is newest-first and uses the UUIDv7 ledger id as a keyset cursor; each row exposes the entry type (GRANT/RESERVE/SETTLE/RELEASE), the amount, and the generation task reached through its reservation. `usage` aggregates `priceUnits` of the newest `window` tasks by billing status. Reading the account creates it with `BILLING_INITIAL_CREDIT_UNITS` on first access, matching the generation path.

### Built-in creative skills

The system prompt advertises each bundled skill by name and description. The agent calls `load_skill` to read the full instructions and reuses the recorded tool output on later model turns in the same run. The first skill, [`short-film`](agent/src/main/resources/visionforge/skills/short-film/SKILL.md), covers scripts, storyboards, and short-film production. `save_storyboard` takes a structured storyboard and requires `fileId` and `baseVersion` for updates. Add bundled skills under `agent/src/main/resources/visionforge/skills/<name>/SKILL.md` with a matching `name`, a single-line `description`, and Markdown instructions.

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

GET   /api/v1/conversations/{conversationId}/assets?kind=&source=&limit=
GET   /api/v1/conversations/{conversationId}/assets/{assetId}
```

The first version stores text files (`SCRIPT`, `STORYBOARD`, `PROMPT`, `OTHER`) in PostgreSQL with a 1 MB limit. V12 creates `conversation_file` and `conversation_file_version`. Media file versions report the backing `assetId`; when the version row is missing it the server falls back to the generation output and then the composition output, and only returns assets owned by that conversation.

The model uses the conversation-file tools `list_files`, `search_files`, `read_file`, `create_file`, `update_file` and `list_file_versions`; the conversation and user always come from the server-side execution context. Media tools are `generate_image`, `generate_video`, `get_generation`, `cancel_generation` and `inspect_asset`.

`POST /api/v1/conversations/{conversationId}/messages` and `/messages/stream` accept an optional `assetIds` list (at most 8 images of the same conversation). The main model sees them as `input_image` content for that turn; history replays text only.

Worker runtime settings live under `visionforge.worker` and can be overridden with environment variables such as `WORKER_MAX_CONCURRENT_ACTIVITIES`, `WORKER_MAX_CONCURRENT_WORKFLOW_TASKS`, `WORKER_WORKFLOW_CACHE_SIZE`, `WORKER_MAX_WORKFLOW_THREADS` and `WORKER_GRACEFUL_SHUTDOWN_TIMEOUT`. Start compensation is controlled by `WORKER_START_COMPENSATION_ENABLED`, `WORKER_START_COMPENSATION_INTERVAL_MS`, `WORKER_START_COMPENSATION_BATCH_SIZE` and `WORKER_START_COMPENSATION_MAX_ATTEMPTS`.

### Object storage

Conversation text files live in PostgreSQL. Images, video and other binaries use an S3-compatible object store; RustFS is the local default:

```sh
docker compose up -d --wait rustfs
docker compose up rustfs-init
```

The API and worker read `STORAGE_BACKEND`, `STORAGE_ENDPOINT`, `STORAGE_PUBLIC_ENDPOINT`, `STORAGE_REGION`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_PATH_STYLE`, `STORAGE_PRESIGN_TTL`, `STORAGE_UPLOAD_SESSION_TTL` and `STORAGE_MAX_UPLOAD_BYTES`. Presigned URLs are signed for `STORAGE_PUBLIC_ENDPOINT`, so that host must be reachable by the browser; the server keeps using `STORAGE_ENDPOINT`. V13 creates `asset`, `asset_variant` and `upload_session`; the adapter uses only the S3 subset tested by RustFS (object CRUD, presigned GET/PUT, range reads and multipart).

Upload flow:

```text
POST /api/v1/conversations/{conversationId}/uploads
  -> presigned PUT URL + required headers
PUT  <presigned URL> with the exact Content-Type
POST /api/v1/conversations/{conversationId}/uploads/{uploadSessionId}/complete
  -> verifies size and content type, marks the asset READY
GET  /api/v1/assets/{assetId}/download-url
  -> short-lived presigned GET
```

### Media generation tasks

V14 creates `generation_task`, `generation_attempt`, `generation_input`, `generation_output`, `message_part` and the credit ledger; V15 adds `shotId`/storyboard linkage, the `WAITING` tool state and `composition_task`/`composition_input`. Model definitions gain `capabilityType` (`CHAT`/`IMAGE`/`VIDEO`), `protocol`, `executionMode` and `priceUnits`; one default model exists per capability type.

```text
POST /api/v1/generation-tasks                202 + Location, honors Idempotency-Key
GET  /api/v1/generation-tasks?conversationId=
GET  /api/v1/generation-tasks/{taskId}
POST /api/v1/generation-tasks/{taskId}/cancel
POST /api/v1/generation-tasks/{taskId}/retry-archive

POST /api/v1/composition-tasks               202 + Location
GET  /api/v1/composition-tasks?conversationId=&status=&limit=
GET  /api/v1/composition-tasks/{taskId}
POST /api/v1/composition-tasks/{taskId}/cancel
POST /api/v1/composition-tasks/{taskId}/retry
```

Both task lists are scoped to the caller; supplying a conversation validates ownership first, so a foreign id is reported as not found. Composition list responses batch their inputs in one query.

Acceptance writes the task, frozen inputs, output placeholder, credit reservation and workflow start request in one local transaction. `MediaGenerationWorkflow` is independent of the AgentRun and owns submit, wait, archive, publish and settlement. An archive failure can only be repaired with `retry-archive`; it never regenerates or double-charges.

A generation may carry `shotId`, `storyboardFileId` and `storyboardVersion`. Completed first frames are written back to the shot's `firstFrame`; completed videos are appended to `videoCandidates` and the first candidate is selected by default. `STORYBOARD` files are validated on create, update and by the `validate_storyboard` tool.

`await_generation` makes the current AgentRun wait durably for a task terminal state and then hands the result back to the same model. `compose_video` freezes `selectedVideo`, assets and `usedRange` in storyboard order; `VideoCompositionWorkflow` reads clips from S3, runs FFmpeg, streams a multipart upload and publishes the final asset. Before composing, the worker uses ffprobe to check that each source covers its edit range. Configure the executables with `FFMPEG_PATH` and `FFPROBE_PATH`. Use `COMPOSITION_PART_SIZE_BYTES` and `COMPOSITION_TIMEOUT` to configure the composition job.

Registered media protocols:

- `OPENAI_IMAGES`: GPT Image generation and reference-image editing via `openai-java`.
- `ARK_IMAGES`: Seedream image generation and reference images via `ark-runtime`.
- `ARK_VIDEO_TASKS`: Seedance submit/query/video download via `ark-runtime`.
- `DASHSCOPE_IMAGE_MESSAGES`: Wanxiang multimodal image generation, synchronous result.
- `DASHSCOPE_T2I`: Wanxiang text-to-image async task submit/query.
- `DASHSCOPE_VIDEO`: Wanxiang video async task submit/query with first-frame/reference media.

The GPT Image, Seedream, Seedance and Wanxiang adapters are implemented but have **not been verified against a live gateway**. `GATEWAY_CUSTOM` has no adapter yet, and asset metadata probing, covers, and transcoding are still pending. `STUB` remains only as a test double and is not a selectable or registered model route. A new credit account is initialized with `BILLING_INITIAL_CREDIT_UNITS` (default 10000).

### Local media mock

`scripts/mock_media_server.py` implements the OpenAI, Ark and DashScope media endpoints without paid calls. It returns deterministic PNGs and a generated MP4, and supports `[mock:fail]`, `[mock:slow]` and `[mock:empty]` prompt markers for failure-path tests.

```sh
python scripts/mock_media_server.py --port 8000
```

Point image/video models at the mock and allow HTTP result downloads in the worker:

```text
OPENAI_IMAGES                     http://127.0.0.1:8000/v1
ARK_IMAGES / ARK_VIDEO_TASKS      http://127.0.0.1:8000
DASHSCOPE_*                       http://127.0.0.1:8000/api/v1
MEDIA_ALLOW_HTTP=true
```

Video endpoints need `ffmpeg` on `PATH`; the default mock clip lasts 2 seconds. To test a five-second shot, pass an MP4 of at least that duration with `--video-file <path>`. `MEDIA_ALLOW_HTTP` is for local development only and must stay unset in production.

## Deployment

Three root Dockerfiles build the runtime images:

- `Dockerfile.frontend`: Next.js standalone server, non-root, port 3000. The backend URL is read at runtime from `VISIONFORGE_API_BASE_URL`; `/api/v1/*` is proxied by `frontend/src/app/api/v1/[...path]/route.ts`, so one image works across environments.
- `Dockerfile.api`: Spring Boot API, non-root, port 8080, healthcheck on `/actuator/health`.
- `Dockerfile.worker`: Temporal worker with `ffmpeg` and `ffprobe` installed, non-root; `FFMPEG_PATH=/usr/bin/ffmpeg`.

`docker-compose.yaml` is the development stack: PostgreSQL, Redis, RustFS, Temporal and Temporal UI only. Run the applications from the host:

```sh
docker compose up -d --wait postgres redis rustfs temporal temporal-ui
```

Temporal UI is available at `http://localhost:8233` by default.

`docker-compose.prod.yaml` is the full stack and builds the three images. A one-shot `migrate` service applies the Flyway migrations before the API and worker start:

```sh
export JWT_SECRET="$(openssl rand -base64 32)"
export MODEL_CONFIG_SECRET="$(openssl rand -base64 32)"
docker compose -f docker-compose.prod.yaml up -d --build
```

Set `STORAGE_PUBLIC_ENDPOINT` to a browser-reachable object-store address and adjust `TEMPORAL_CORS_ORIGINS` for the Temporal UI origin. Temporal uses `temporalio/auto-setup` in this compose setup to initialize databases and the default namespace; a managed Temporal deployment is preferable for real production.
