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
docker compose up -d --wait postgres
./mvnw -pl database flyway:migrate
```

The defaults target `jdbc:postgresql://localhost:5432/visionforge` with user and password `visionforge`. Override them with `-Dvisionforge.db.url=... -Dvisionforge.db.user=... -Dvisionforge.db.password=...` for Flyway, or the `VISIONFORGE_DB_URL`, `VISIONFORGE_DB_USER` and `VISIONFORGE_DB_PASSWORD` environment variables for the applications.

### Authentication and workspaces

The API exposes `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/workspace`, `/api/v1/auth/switch-workspace`, `/api/v1/auth/refresh`, `/api/v1/auth/logout` and `/api/v1/auth/me`. Access tokens are short-lived JWTs bound to one workspace (`wsid`, `ws_role`, `platform_admin`); refresh tokens are user-level, rotated and stored as hashes.

A user with a single workspace receives a token pair directly. A user with multiple workspaces receives a short-lived selection token and exchanges it through `/api/v1/auth/workspace`. Switching workspaces issues a new access token, and refresh requests include the current `workspaceId`. Registration creates a personal workspace owned by the new user.

Workspace administration uses `/api/v1/workspaces`, `/api/v1/workspaces/{id}`, `/members` and `/transfer`; every workspace-scoped request must present an access token whose `wsid` matches the path. System roles are `ADMIN` and `USER`; workspace roles are `OWNER`, `ADMIN` and `MEMBER`. System administrators can switch into any workspace, and workspace operations are written to the audit log.

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

API keys are never returned; responses expose only `apiKeyConfigured`. The provider calls `{baseUrl}/chat/completions` and sends `max_tokens` plus `reasoning_effort` when configured. The worker must be running to execute AgentRun workflows.

Conversation endpoints:

```text
POST /api/v1/workspaces/{workspaceId}/conversations
GET  /api/v1/workspaces/{workspaceId}/conversations
GET  /api/v1/workspaces/{workspaceId}/conversations/{conversationId}/messages
POST /api/v1/workspaces/{workspaceId}/conversations/{conversationId}/messages
GET  /api/v1/workspaces/{workspaceId}/agent-runs/{runId}
```

Sending a message returns `202 Accepted` with a `runId`. The workflow persists the assistant reply asynchronously; poll the AgentRun endpoint until its status is `COMPLETED` or `FAILED`.

## Deployment

Deployment files are located at the repository root. The Dockerfile and Compose file remain placeholders; runnable service definitions will be added separately.
