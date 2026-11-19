#!/usr/bin/env python3
"""Unit tests for scripts/mock_media_server.py FFmpeg path resolution."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mock_media_server as mock_server  # noqa: E402


class MockMediaServerFfmpegTest(unittest.TestCase):
    def test_resolve_ffmpeg_prefers_cli_over_env(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            cli_bin = Path(tmpdir) / "cli-ffmpeg.exe"
            env_bin = Path(tmpdir) / "env-ffmpeg.exe"
            cli_bin.write_bytes(b"cli")
            env_bin.write_bytes(b"env")

            with mock.patch.dict(os.environ, {"FFMPEG_PATH": str(env_bin)}):
                resolved, _ = mock_server.resolve_ffmpeg(str(cli_bin))
            self.assertEqual(resolved, str(cli_bin))

    def test_resolve_ffmpeg_from_env_directory_and_quoted_path(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            bin_name = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
            binary = Path(tmpdir) / bin_name
            binary.write_bytes(b"bin")

            with mock.patch.dict(os.environ, {"FFMPEG_PATH": f'"{tmpdir}"'}):
                resolved, _ = mock_server.resolve_ffmpeg()
            self.assertEqual(resolved, str(binary))

    def test_prepare_video_runs_resolved_ffmpeg(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            fake_ffmpeg = Path(tmpdir) / "ffmpeg.exe"
            fake_ffmpeg.write_bytes(b"bin")

            def fake_run(cmd: list[str], check: bool) -> None:
                self.assertEqual(cmd[0], str(fake_ffmpeg))
                self.assertTrue(check)
                Path(cmd[-1]).write_bytes(b"\x00\x00\x00\x18ftypmp42")

            with mock.patch.object(mock_server.subprocess, "run", side_effect=fake_run):
                video = mock_server.prepare_video(None, str(fake_ffmpeg))

            self.assertEqual(video, b"\x00\x00\x00\x18ftypmp42")

    def test_prepare_video_reads_video_file_without_ffmpeg(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            sample = Path(tmpdir) / "sample.mp4"
            sample.write_bytes(b"sample-mp4")

            with mock.patch.dict(os.environ, {"FFMPEG_PATH": ""}, clear=False):
                video = mock_server.prepare_video(str(sample))

            self.assertEqual(video, b"sample-mp4")


    def test_resolve_ffmpeg_missing_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            missing = Path(tmpdir) / "no-such-ffmpeg.exe"
            resolved, candidate = mock_server.resolve_ffmpeg(str(missing))
            self.assertIsNone(resolved)
            self.assertEqual(candidate, str(missing))
            self.assertIsNone(mock_server.prepare_video(None, str(missing)))


if __name__ == "__main__":
    unittest.main()
