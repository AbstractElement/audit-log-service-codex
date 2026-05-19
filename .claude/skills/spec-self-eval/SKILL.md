---
name: spec-self-eval
description: Evaluate .specs/<feature> requirements, design, and tasks against the spec quality checklist, then write a timestamped PASS, WEAK, or FAIL report with 0-5 category scores.
---

# Spec Self Evaluation

Use this skill when asked to review or self-evaluate a feature spec in `.specs/<feature>/`.

## Inputs

- Feature directory: infer `<feature>` from the user request. If it is ambiguous, ask for the feature name.
- Spec files: `.specs/<feature>/requirements.md`, `.specs/<feature>/design.md`, and `.specs/<feature>/tasks.md`.
- Checklist: prefer `.specs/_eval-checklist.md` when present in the repo. Otherwise read `references/_eval-checklist.md` from this skill.

## Workflow

1. Read the checklist and extract every quality category and its `0` through `5` scoring descriptions.
2. Read all three spec files. If a file is missing, include it in the report scope and score affected categories accordingly.
3. For every checklist category, assign an integer score from `0` to `5` using the checklist descriptions:
   - `0`: missing
   - `1`: very poor
   - `2`: weak
   - `3`: acceptable
   - `4`: good
   - `5`: excellent
4. For every category, assign a status tag: `[PASS]` for scores `4-5`, `[WEAK]` for score `3`, and `[FAIL]` for scores `0-2`.
5. For every category, write exactly one sentence explaining why that score was given.
6. Calculate the average score to two decimal places.
7. Choose a verdict:
   - `PASS`: average is at least `4.00`, every category is scored `4` or `5`, and all required spec files exist.
   - `WEAK`: average is at least `3.00`, every category is scored at least `3`, but the spec has material gaps or at least one category is scored `3`.
   - `FAIL`: average is below `3.00`, any category is scored `0-2`, or required spec files are missing in a way that prevents meaningful evaluation.
8. Save the report to `.specs/<feature>/eval-report-<timestamp>.md`, where `<timestamp>` is the local creation time in `YYYY-MM-DD-HHMMSS` format.

## Report Format

```markdown
# <Feature Name> spec evaluation report

**Date:** <YYYY-MM-DD>
**Created at:** <YYYY-MM-DD HH:MM:SS local time>
**Verdict:** <PASS|WEAK|FAIL>
**Average:** <average> / 5
**Scope reviewed:** `.specs/<feature>/requirements.md`, `.specs/<feature>/design.md`, `.specs/<feature>/tasks.md`
**Rubric:** `<path-to-checklist-used>`

## Scores

| Category | Status | Points | Summary |
|---|---|---:|---|
| **<Category>** | <[PASS|WEAK|FAIL]> | <0-5> | <One sentence explaining the score.> |

## General Summary

Average score: **<average> / 5**. <One concise paragraph explaining the verdict and the most important strengths and gaps.>
```

After saving the report, tell the user the output path, verdict, and average score.
