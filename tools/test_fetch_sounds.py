"""Focused offline downloader safety tests; no network or installed audio writes."""
import contextlib
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import fetch_sounds as fetch


class FetchTests(unittest.TestCase):
    def test_http_error_includes_approval_body(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "source"
            target.write_text("Approval required for example.org:443")
            with patch.object(fetch, "run", return_value=b"403"):
                with self.assertRaisesRegex(RuntimeError, "Approval required"):
                    fetch.download("https://example.org/audio", target, "unused")

    def test_checksum_required(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "source"
            target.write_bytes(b"audio")
            with patch.object(fetch, "run", return_value=b"200"):
                with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
                    fetch.download("https://example.org/audio", target, "bad")
                fetch.download("https://example.org/audio", target,
                               hashlib.sha256(b"audio").hexdigest())

    def test_missing_rain_fails_before_download(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with patch("sys.argv", ["fetch", "--output-dir", tmp]), patch.object(fetch, "download") as download:
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    fetch.main()
                download.assert_not_called()

    def test_existing_assets_are_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            names = [f"{row[0]}.ogg" for row in fetch.SOURCES] + ["fan.ogg", "ambient.ogg", "rain.ogg", "fan.pcm"]
            for name in names:
                (root / name).write_bytes(b"user audio")
            with patch("sys.argv", ["fetch", "--output-dir", tmp]), patch.object(fetch, "download") as download:
                fetch.main()
                download.assert_not_called()
            self.assertEqual(sorted(p.name for p in root.iterdir()), sorted(names))
            self.assertTrue(all((root / name).read_bytes() == b"user audio" for name in names))

    def test_missing_pcm_uses_retained_fan_without_download(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in [row[0] for row in fetch.SOURCES] + ["fan", "ambient", "rain"]:
                (root / f"{name}.ogg").write_bytes(b"retained audio")

            def convert(*args: str) -> None:
                self.assertEqual(args[:2], ("-i", str(root / "fan.ogg")))
                self.assertIn("s16le", args)
                Path(args[-1]).write_bytes(b"\x01\x00")

            with patch("sys.argv", ["fetch", "--output-dir", tmp]), patch.object(fetch, "download") as download, patch.object(fetch, "ffmpeg", side_effect=convert):
                fetch.main()
                download.assert_not_called()
            self.assertEqual((root / "fan.pcm").read_bytes(), b"\x01\x00")
            self.assertEqual((root / "fan.ogg").read_bytes(), b"retained audio")

    def test_force_requires_manual_rain_even_if_existing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "rain.ogg").write_bytes(b"keep")
            with patch("sys.argv", ["fetch", "--output-dir", tmp, "--force"]):
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    fetch.main()
            self.assertEqual((Path(tmp) / "rain.ogg").read_bytes(), b"keep")

    def test_failure_does_not_publish_or_leave_staging(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "rain.ogg").write_bytes(b"user rain")
            with patch("sys.argv", ["fetch", "--output-dir", tmp]), patch.object(fetch, "download", side_effect=RuntimeError("broken")):
                with self.assertRaisesRegex(RuntimeError, "broken"):
                    fetch.main()
            self.assertEqual([p.name for p in root.iterdir()], ["rain.ogg"])
            self.assertEqual((root / "rain.ogg").read_bytes(), b"user rain")


if __name__ == "__main__":
    unittest.main()
