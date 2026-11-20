"""Reject tampering of the pinned ByeByeDPI reference: files, submodules and tag.

The vendored snapshot is the immutability baseline for the later native
integration and update CI.  Three independent failure modes must each end in a
non-zero exit from the sync script:

* a changed vendored file            -> offline ``--check`` fails;
* a changed submodule commit         -> online ``--ref`` resolution fails;
* a moved tag                        -> online ``--ref`` resolution fails.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


sync = module("sync_byebyedpi")

TAG = "v.1.8.2"
COMMIT = "96a3c1ff555043d915af814aa4f0e9a572c89aba"
SUBMODULE_COMMIT = "ba532298de7b28cfe854aea83d061369d13ca290"
GITMODULES = (
    '[submodule "byedpi"]\n'
    "\tpath = app/src/main/cpp/byedpi\n"
    "\turl = https://github.com/hufrea/byedpi.git\n"
)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build_fixture(directory: Path):
    """Write a minimal, internally consistent vendor tree and lock file."""
    vendor = directory / "byebyedpi"
    (vendor / "app").mkdir(parents=True)
    (vendor / "LICENSE").write_bytes(b"GPL-3.0-only license text\n")
    (vendor / ".gitmodules").write_bytes(GITMODULES.encode())
    (vendor / "app" / "Main.kt").write_bytes(b"package demo\n")
    lock = {
        "repository": sync.REPOSITORY,
        "url": sync.URL,
        "tag": TAG,
        "commit": COMMIT,
        "submodules": {
            "app/src/main/cpp/byedpi": {
                "url": "https://github.com/hufrea/byedpi.git",
                "commit": SUBMODULE_COMMIT,
            }
        },
        "licenses": {"LICENSE": {"spdx": "GPL-3.0-only", "sha256": sha(b"GPL-3.0-only license text\n")}},
        "files": {
            ".gitmodules": sha(GITMODULES.encode()),
            "LICENSE": sha(b"GPL-3.0-only license text\n"),
            "app/Main.kt": sha(b"package demo\n"),
        },
    }
    lock_path = directory / "byebyedpi.lock.json"
    lock_path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    return vendor, lock_path, lock


class IntegrityTests(unittest.TestCase):
    def test_changed_file_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, lock_path, _ = build_fixture(Path(temporary))
            (vendor / "app" / "Main.kt").write_bytes(b"package demo CHANGED\n")
            with mock.patch.object(sync, "LOCK", lock_path), mock.patch.object(sync, "VENDOR", vendor):
                with self.assertRaises(SystemExit) as raised:
                    sync.main(["--check"])
                self.assertEqual(raised.exception.code, 1)

    def test_changed_submodule_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, lock_path, _ = build_fixture(Path(temporary))

            def fake_fetch(url: str) -> bytes:
                if f"/commits/{TAG}" in url:
                    return json.dumps({"sha": COMMIT}).encode()
                if "/git/trees/" in url and "recursive=1" in url:
                    moved = "1" * 40
                    tree = [{"path": "app/src/main/cpp/byedpi", "mode": "160000", "sha": moved}]
                    return json.dumps({"tree": tree, "truncated": False}).encode()
                if ".gitmodules" in url:
                    return GITMODULES.encode()
                raise AssertionError(f"unexpected fetch: {url}")

            with mock.patch.object(sync, "LOCK", lock_path), mock.patch.object(sync, "VENDOR", vendor), \
                    mock.patch.object(sync, "fetch", fake_fetch):
                with self.assertRaises(SystemExit) as raised:
                    sync.main(["--ref", TAG])
                self.assertEqual(raised.exception.code, 1)

    def test_tag_moved_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, lock_path, _ = build_fixture(Path(temporary))

            def fake_fetch(url: str) -> bytes:
                if f"/commits/{TAG}" in url:
                    moved = "2" * 40
                    return json.dumps({"sha": moved}).encode()
                raise AssertionError(f"unexpected fetch: {url}")

            with mock.patch.object(sync, "LOCK", lock_path), mock.patch.object(sync, "VENDOR", vendor), \
                    mock.patch.object(sync, "fetch", fake_fetch):
                with self.assertRaises(SystemExit) as raised:
                    sync.main(["--ref", TAG])
                self.assertEqual(raised.exception.code, 1)

    def test_clean_checkout_passes_offline(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, lock_path, _ = build_fixture(Path(temporary))
            with mock.patch.object(sync, "LOCK", lock_path), mock.patch.object(sync, "VENDOR", vendor):
                sync.main(["--check"])  # must not raise


if __name__ == "__main__":
    unittest.main()
