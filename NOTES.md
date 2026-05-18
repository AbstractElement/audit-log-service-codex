# NOTES

- 2026-05-13: Created `spec-self-eval` skill under `.codex/skills/` and mirrored it to `.claude/skills/`, with a bundled evaluation checklist seeded from `.specs/_eval-checklist.md`.
- 2026-05-13: Ran `./gradlew check`; Spotless and compilation completed, but Testcontainers integration tests failed because no Docker environment was available at `/var/run/docker.sock`.
- 2026-05-13: Evaluated `.specs/query-api` documentation with `spec-self-eval` and saved `.specs/query-api/eval-report-2026-05-13.md` with verdict WEAK and average 3.89/5.
- 2026-05-13: Reran `./gradlew check` after the query-api evaluation report; Spotless and compilation passed, but the same three Testcontainers integration tests failed because Docker was unavailable.
- 2026-05-13: Added repo-local Codex Stop hook configuration that enforces same-day `spec-self-eval` reports for touched `.specs/<feature>/` folders and blocks on `[FAIL]`, `FAIL` verdicts, stale or missing reports, or category scores 1-3.
- 2026-05-13: Verified the Stop hook script manually: clean/no-spec path returns continue, forced `query-api` path blocks on the two 3-point categories, and `./gradlew check` still fails only at Docker-backed Testcontainers initialization.
- 2026-05-18: Updated the repo-local `spec-self-eval` skill and Stop hook for the current `.specs/_eval-checklist.md` 0-5 rubric, including 0-point failures, WEAK verdict/status blocking, and a synced bundled fallback checklist.
- 2026-05-18: Verified the updated Stop hook with redirected Python bytecode cache, the no-feature path, and parser coverage for WEAK plus 0-point failures; `./gradlew check` passed Spotless and compilation but failed at the three Docker-backed Testcontainers integration-test initializers.
- 2026-05-18: Updated the `spec-self-eval` skill report naming instruction to use local creation timestamps in `eval-report-YYYY-MM-DD-HHMMSS.md` filenames.
- 2026-05-18: Evaluated `.specs/query-api` documentation with `spec-self-eval` and saved `.specs/query-api/eval-report-2026-05-18-212452.md` with verdict PASS and average 4.29/5.
- 2026-05-18: Ran `./gradlew check` after the query-api documentation evaluation; Spotless and compilation passed, but three Testcontainers integration-test initializers failed because no valid Docker environment was available at `/var/run/docker.sock`.
- 2026-05-18: Added `.specs/query-api/eval-report-2026-05-18.md` as a compatibility copy for the current Stop hook, with the same PASS verdict and 4.29/5 average as the timestamped report.
- 2026-05-18: Updated the spec-self-eval Stop hook to accept either date-only `eval-report-YYYY-MM-DD.md` or timestamped `eval-report-YYYY-MM-DD-HHMMSS.md` report filenames, and verified timestamp-only discovery.
- 2026-05-18: Reran `./gradlew check` after the Stop hook filename update; Spotless and compilation passed, but the same three Testcontainers integration-test initializers failed because Docker was unavailable.
- 2026-05-18: Mirrored the repo-local `.codex/skills/spec-self-eval/` SKILL.md and bundled fallback checklist changes into `.claude/skills/spec-self-eval/`.
- 2026-05-18: Reran `./gradlew check` after mirroring the spec-self-eval skill to `.claude`; Spotless and compilation passed, but the same three Docker-backed Testcontainers integration-test initializers failed because Docker was unavailable.
