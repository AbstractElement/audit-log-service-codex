#!/usr/bin/env python3
"""Focused tests for spec_self_eval_stop.py."""

from __future__ import annotations

import json
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


if __name__ == "__main__":
    unittest.main()
