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

The defaults target `jdbc:postgresql://localhost:5432/visionforge` with user and password `visionforge`. Override them with `-Dvisionforge.db.url=... -Dvisionforge.db.user=... -Dvisionforge.db.password=...`. Business modules and generation pipelines are still pending.

## Deployment

Deployment files are located at the repository root. The Dockerfile and Compose file remain placeholders; runnable service definitions will be added separately.
