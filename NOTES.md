# NOTES

- 2026-05-13: Created `spec-self-eval` skill under `.codex/skills/` and mirrored it to `.claude/skills/`, with a bundled evaluation checklist seeded from `.specs/_eval-checklist.md`.
- 2026-05-13: Ran `./gradlew check`; Spotless and compilation completed, but Testcontainers integration tests failed because no Docker environment was available at `/var/run/docker.sock`.
- 2026-05-13: Evaluated `.specs/query-api` documentation with `spec-self-eval` and saved `.specs/query-api/eval-report-2026-05-13.md` with verdict WEAK and average 3.89/5.
- 2026-05-13: Reran `./gradlew check` after the query-api evaluation report; Spotless and compilation passed, but the same three Testcontainers integration tests failed because Docker was unavailable.
