# Audit Log Service

Spring Boot 3 service for append-only audit event ingestion and querying.

## Run with Docker Compose

```bash
docker compose up --build
```

The app listens on `http://localhost:8080` and connects to PostgreSQL at service host `db`.

Health check:

```bash
curl http://localhost:8080/actuator/health
```

Create an event:

```bash
curl -i -X POST http://localhost:8080/audit-events \
  -H 'Content-Type: application/json' \
  -d '{
    "actor": "service:billing",
    "action": "invoice.created",
    "resource": "invoice/123",
    "outcome": "SUCCESS",
    "context": { "traceId": "abc" }
  }'
```
