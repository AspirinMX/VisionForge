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

Import the root `pom.xml` as the Maven project in your IDE. With Maven installed, validate the module structure from the repository root:

```sh
mvn validate
```

The backend currently contains module skeletons; Spring Boot startup and business dependencies have not been configured yet.

## Deployment

Deployment files are located at the repository root. The Dockerfile and Compose file remain placeholders; runnable service definitions will be added separately.
