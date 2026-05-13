# Audit Log Service

## Description

Audit Log Service is an internal Spring Boot service for receiving audit events from other company services and storing them immutably in PostgreSQL. It is intended for compliance, security, and observability workflows used by compliance officers, SREs, and security analysts.

The service is built with Java 21, Spring Boot 3, Gradle Kotlin DSL, PostgreSQL, Flyway, and Testcontainers.

## Features

- Append-only audit event ingestion through `POST /audit-events`.
- Server-generated UTC timestamps for every event.
- Required actor, action, resource, and outcome fields.
- JSONB event context storage.
- Query endpoint with actor/resource filters, mandatory time window, and cursor pagination.
- Flyway-managed database schema.
- PostgreSQL trigger that rejects updates to `audit_events`.
- Docker Compose setup for the app and PostgreSQL.
- Actuator health endpoint with database readiness checks.

## Quick Start

Start the full stack:

```bash
docker compose up --build
```

The app listens on `http://localhost:8080`. PostgreSQL is exposed on `localhost:5432` and is also reachable by the app through the Compose service host `db`.

Check service health:

```bash
curl http://localhost:8080/actuator/health
```

Stop the stack:

```bash
docker compose down
```

Remove the local PostgreSQL volume when you need a clean database:

```bash
docker compose down -v
```

## API / Usage

Create an audit event:

```bash
curl -i -X POST http://localhost:8080/audit-events \
  -H 'Content-Type: application/json' \
  -d '{
    "actor": "service:billing",
    "action": "invoice.created",
    "resource": "invoice/123",
    "outcome": "SUCCESS",
    "context": {
      "traceId": "abc"
    }
  }'
```

Successful writes return `201 Created` with a `Location` header.

Query audit events (first page):

```bash
curl 'http://localhost:8080/audit-events?actor=service:billing&from=2026-05-01T00:00:00Z&to=2026-05-08T00:00:00Z&limit=100'
```

The response is a paginated envelope:

```json
{
  "items": [ ],
  "nextCursor": "eyJ0cyI6Ij…",
  "hasMore": true
}
```

Fetch the next page by passing the opaque `nextCursor` back; filter params are encoded inside the token and must not be repeated:

```bash
curl 'http://localhost:8080/audit-events?cursor=eyJ0cyI6Ij…&limit=100'
```

Supported query parameters:

- `actor` — exact match; at least one of `actor` or `resource` is required on first-page requests.
- `resource` — exact match.
- `from` — ISO-8601 UTC timestamp, inclusive. Required on first-page requests.
- `to` — ISO-8601 UTC timestamp, exclusive. Required on first-page requests. `to - from` must not exceed 7 days.
- `limit` — default `100`, must be between `1` and `500`.
- `cursor` — opaque token from a previous response. Mutually exclusive with `actor`/`resource`/`from`/`to`.

Validation failures return HTTP 400 with a JSON body shaped as
`{ "error": "<code>", "message": "<text>", "field": "<param>" }` (e.g.
`MISSING_FILTER`, `WINDOW_TOO_LARGE`, `INVALID_TIMESTAMP`, `INVALID_CURSOR`).

Valid outcomes:

- `SUCCESS`
- `DENIED`
- `ERROR`

## Architecture

The code follows a DDD-oriented clean architecture structure:

- `domain`: framework-free business model and invariants.
- `application`: use cases, commands, query/cursor/page value types, validation, and repository ports.
- `infrastructure`: Spring configuration, JPA persistence adapter, Flyway migrations, and database startup verification.
- `api`: REST controllers and HTTP request/response models.

Database schema changes live in `src/main/resources/db/migration`. Hibernate validates the schema at startup, but Flyway owns schema creation and migration.

Package dependencies:

![Package dependency diagram](docs/images/package-dependencies.svg)

Audit event storage flow:

![Audit event storage flow sequence diagram](docs/images/audit-event-storage-flow.svg)

Audit event query flow (cursor pagination):

![Audit event query flow sequence diagram](docs/images/audit-event-query-flow.svg)

## Configuration

Runtime configuration is provided through environment variables:

| Variable | Default | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port for the service. |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/audit_log` | JDBC URL for PostgreSQL. |
| `SPRING_DATASOURCE_USERNAME` | `audit` | PostgreSQL username. |
| `SPRING_DATASOURCE_PASSWORD` | `audit` | PostgreSQL password. |

Docker Compose sets the datasource URL to `jdbc:postgresql://db:5432/audit_log` so the app connects to the PostgreSQL service inside the Compose network.

## Testing

Run the test suite:

```bash
./gradlew test
```

If Gradle is not installed locally, build through Docker:

```bash
docker compose build app
```

Current tests cover:

- Domain event creation and actor validation.
- PostgreSQL connectivity with Testcontainers.
- Persistence of audit events.
- Rejection of direct updates to `audit_events`.

## Contribution Workflow

Do not commit or push directly to `master`. Every task must be done on a dedicated branch and merged through a pull request.

Create a task branch:

```bash
git switch -c <task-branch>
```

Push the task branch and open a pull request:

```bash
git push -u origin <task-branch>
```

Enable the local hooks to block commits on `master`, block direct pushes to `master`, and run Spotless before every commit:

```bash
git config core.hooksPath .githooks
```

Repository administrators must also configure GitHub branch protection or a repository ruleset for `master` that blocks direct pushes and requires pull requests before merging. Local hooks are only a developer-side guard.

## Documentation

Key files:

- `AGENTS.md`: project-specific engineering rules and invariants.
- `compose.yaml`: local app and PostgreSQL stack.
- `Dockerfile`: multi-stage Java 21 container build.
- `.github/workflows/ci.yml`: GitHub Actions build and test workflow.
- `src/main/resources/application.yml`: Spring runtime defaults.
- `src/main/resources/db/migration`: Flyway database migrations.
