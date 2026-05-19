#!/usr/bin/env python3
"""Focused tests for spec_self_eval_stop.py."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

import spec_self_eval_stop as hook


class TranscriptFeaturesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.repo = Path(self.temp_dir.name)

    def write_transcript(self, *payloads: dict[str, object]) -> Path:
        path = self.repo / "transcript.jsonl"
        with path.open("w", encoding="utf-8") as handle:
            for payload in payloads:
                handle.write(json.dumps({"payload": payload}) + "\n")
        return path

    def test_exec_command_referencing_spec_file_marks_feature_touched(self) -> None:
        transcript = self.write_transcript(
            {"type": "task_started", "turn_id": "turn-1"},
            {
                "type": "function_call",
                "name": "exec_command",
                "arguments": json.dumps({"cmd": "rtk cat .specs/query-api/requirements.md"}),
            },
        )

        features = hook.transcript_features(
            {"transcript_path": str(transcript), "turn_id": "turn-1"},
            self.repo,
        )

        self.assertEqual({"query-api"}, features)

    def test_absolute_spec_file_path_marks_feature_touched(self) -> None:
        spec_path = self.repo / ".specs" / "query-api" / "design.md"
        transcript = self.write_transcript(
            {"type": "task_started", "turn_id": "turn-1"},
            {
                "type": "function_call",
                "name": "exec_command",
                "arguments": json.dumps({"cmd": f"rtk cat {spec_path}"}),
            },
        )

        features = hook.transcript_features(
            {"transcript_path": str(transcript), "turn_id": "turn-1"},
            self.repo,
        )

        self.assertEqual({"query-api"}, features)

    def test_bare_specs_directory_does_not_mark_feature_touched(self) -> None:
        transcript = self.write_transcript(
            {"type": "task_started", "turn_id": "turn-1"},
            {
                "type": "function_call",
                "name": "exec_command",
                "arguments": json.dumps({"cmd": "rtk rg --files .codex .specs"}),
            },
        )

        features = hook.transcript_features(
            {"transcript_path": str(transcript), "turn_id": "turn-1"},
            self.repo,
        )

        self.assertEqual(set(), features)

    def test_apply_patch_ignores_spec_paths_in_file_content(self) -> None:
        transcript = self.write_transcript(
            {"type": "task_started", "turn_id": "turn-1"},
            {
                "type": "function_call",
                "name": "apply_patch",
                "arguments": (
                    "*** Begin Patch\n"
                    "*** Add File: .codex/hooks/example_test.py\n"
                    "+cmd = 'rtk cat .specs/query-api/requirements.md'\n"
                    "*** End Patch\n"
                ),
            },
        )

        features = hook.transcript_features(
            {"transcript_path": str(transcript), "turn_id": "turn-1"},
            self.repo,
        )

        self.assertEqual(set(), features)

    def test_turn_filtering_ignores_other_turns(self) -> None:
        transcript = self.write_transcript(
            {"type": "task_started", "turn_id": "turn-1"},
            {
                "type": "function_call",
                "name": "exec_command",
                "arguments": json.dumps({"cmd": "rtk cat .specs/query-api/requirements.md"}),
            },
            {"type": "task_started", "turn_id": "turn-2"},
            {
                "type": "function_call",
                "name": "exec_command",
                "arguments": json.dumps({"cmd": "rtk cat .specs/other/tasks.md"}),
            },
        )

        features = hook.transcript_features(
            {"transcript_path": str(transcript), "turn_id": "turn-2"},
            self.repo,
        )

        self.assertEqual({"other"}, features)


class ReportFreshnessTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.spec_dir = Path(self.temp_dir.name) / ".specs" / "query-api"
        self.spec_dir.mkdir(parents=True)
        for name in ("requirements.md", "design.md", "tasks.md"):
            path = self.spec_dir / name
            path.write_text(f"# {name}\n", encoding="utf-8")
            os.utime(path, (1_000, 1_000))

    def write_report(self, name: str, mtime: int) -> Path:
        path = self.spec_dir / name
        path.write_text(
            "# Query API spec evaluation report\n\n"
            "**Verdict:** PASS\n\n"
            "## Scores\n\n"
            "| Category | Status | Points | Summary |\n"
            "|---|---|---:|---|\n"
            "| **Business Context** | [PASS] | 4 | Good enough. |\n",
            encoding="utf-8",
        )
        os.utime(path, (mtime, mtime))
        return path

    def test_latest_report_is_not_limited_to_today(self) -> None:
        self.write_report("eval-report-2026-05-13.md", 1_100)
        newest = self.write_report("eval-report-2026-05-18-224141.md", 1_200)

        self.assertEqual(newest, hook.latest_report(self.spec_dir))
        self.assertFalse(hook.report_is_stale(newest, self.spec_dir))

    def test_report_is_stale_when_spec_file_changed_after_report(self) -> None:
        report = self.write_report("eval-report-2026-05-18-224141.md", 1_200)
        os.utime(self.spec_dir / "requirements.md", (1_300, 1_300))

        self.assertTrue(hook.report_is_stale(report, self.spec_dir))


if __name__ == "__main__":
    unittest.main()
