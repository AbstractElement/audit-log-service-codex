#!/usr/bin/env python3
"""Stop hook enforcing spec-self-eval reports for touched .specs features."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


PATCH_PATH_RE = re.compile(r"^\*\*\* (?:Add File|Update File|Delete File): (?P<path>\.specs/[^\s]+)", re.MULTILINE)


def main() -> int:
    payload = read_payload()
    repo = repo_root(payload)
    features = touched_features(payload, repo)

    if not features:
        print(json.dumps({"continue": True}))
        return 0

    blockers: list[str] = []
    reports: list[str] = []
    today = datetime.now().date().isoformat()

    for feature in sorted(features):
        spec_dir = repo / ".specs" / feature
        if not spec_dir.exists():
            continue
        spec_files = [spec_dir / name for name in ("requirements.md", "design.md", "tasks.md")]
        if not any(path.exists() for path in spec_files):
            continue
        report = spec_dir / f"eval-report-{today}.md"
        reports.append(str(report))
        if not report.exists():
            blockers.append(
                f"{feature}: missing {report.relative_to(repo)}; run the spec-self-eval skill for `.specs/{feature}`."
            )
            continue
        if report_is_stale(report, spec_dir):
            blockers.append(
                f"{feature}: {report.relative_to(repo)} is older than the spec files; rerun spec-self-eval."
            )
        blockers.extend(parse_blocking_items(feature, report))

    if blockers:
        print(json.dumps({"decision": "block", "reason": block_reason(reports, blockers)}))
        return 0

    print(json.dumps({"continue": True}))
    return 0


def read_payload() -> dict[str, Any]:
    raw = sys.stdin.read().strip()
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return value if isinstance(value, dict) else {}


def repo_root(payload: dict[str, Any]) -> Path:
    cwd = Path(str(payload.get("cwd") or os.getcwd())).resolve()
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode == 0 and result.stdout.strip():
        return Path(result.stdout.strip()).resolve()
    return cwd


def touched_features(payload: dict[str, Any], repo: Path) -> set[str]:
    override = os.environ.get("SPEC_SELF_EVAL_FEATURES")
    if override:
        return {item.strip() for item in override.split(",") if item.strip()}
    return transcript_features(payload, repo) | git_status_features(repo)


def transcript_features(payload: dict[str, Any], repo: Path) -> set[str]:
    transcript = payload.get("transcript_path")
    if not transcript or not Path(str(transcript)).exists():
        return set()

    turn_id = str(payload.get("turn_id") or "")
    in_turn = not turn_id
    seen_turn = False
    features: set[str] = set()

    with Path(str(transcript)).open(encoding="utf-8") as handle:
        for line in handle:
            try:
                event = json.loads(line).get("payload", {})
            except json.JSONDecodeError:
                continue
            if not isinstance(event, dict):
                continue
            if event.get("type") == "task_started":
                current = str(event.get("turn_id") or "")
                if turn_id and current == turn_id:
                    in_turn = True
                    seen_turn = True
                elif turn_id and seen_turn:
                    break
            if not in_turn:
                continue
            if event.get("type") == "patch_apply_end" and isinstance(event.get("changes"), dict):
                features.update(filter(None, (feature_from_path(path, repo) for path in event["changes"])))
            if event.get("type") == "function_call" and event.get("name") == "apply_patch":
                args = str(event.get("arguments") or "")
                features.update(filter(None, (feature_from_path(m.group("path"), repo) for m in PATCH_PATH_RE.finditer(args))))
    return features


def git_status_features(repo: Path) -> set[str]:
    result = subprocess.run(
        ["git", "status", "--porcelain", "--", ".specs"],
        cwd=repo,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        return set()

    features: set[str] = set()
    for line in result.stdout.splitlines():
        path = line[3:].strip()
        if " -> " in path:
            path = path.split(" -> ", 1)[1].strip()
        feature = feature_from_path(path, repo)
        if feature:
            features.add(feature)
    return features


def feature_from_path(raw: str, repo: Path) -> str | None:
    path = Path(raw.strip().strip('"'))
    if path.is_absolute():
        try:
            path = path.resolve().relative_to(repo)
        except ValueError:
            return None
    parts = path.parts
    if len(parts) < 3 or parts[0] != ".specs" or parts[1].startswith(("_", ".")):
        return None
    return parts[1]


def report_is_stale(report: Path, spec_dir: Path) -> bool:
    spec_files = [spec_dir / name for name in ("requirements.md", "design.md", "tasks.md")]
    mtimes = [path.stat().st_mtime for path in spec_files if path.exists()]
    return bool(mtimes) and report.stat().st_mtime < max(mtimes)


def parse_blocking_items(feature: str, report: Path) -> list[str]:
    items: list[str] = []
    text = report.read_text(encoding="utf-8")
    if re.search(r"^\*\*Verdict:\*\*\s*FAIL\b", text, re.MULTILINE):
        items.append(f"{feature}: report verdict is FAIL.")
    for line in text.splitlines():
        if not line.startswith("|") or "---" in line or "Category" in line:
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if not cells:
            continue
        category = cells[0].replace("**", "")
        summary = cells[-1] if len(cells) > 1 else ""
        if any("[FAIL]" in cell for cell in cells):
            items.append(f"{feature} / {category}: [FAIL] - {summary}")
            continue
        for cell in cells[1:]:
            if re.fullmatch(r"\**[1-5]\**", cell):
                points = int(cell.replace("*", ""))
                if 1 <= points <= 3:
                    items.append(f"{feature} / {category}: {points} points - {summary}")
                break
    return items


def block_reason(reports: list[str], blockers: list[str]) -> str:
    lines = [
        "spec-self-eval Stop hook blocked turn close.",
        "",
        "Reports checked:",
        *[f"- {report}" for report in reports],
        "",
        "Items that must be fixed:",
        *[f"- {item}" for item in blockers],
        "",
        "Run the spec-self-eval skill for the touched feature, fix the listed spec gaps, and let the Stop hook run again.",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
