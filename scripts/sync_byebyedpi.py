"""Vendor the pinned ByeByeDPI reference and verify its snapshot integrity.

The upstream Android application ``romanvht/ByeByeDPI`` is the parity baseline
for the merged TG-WS application.  This script records an immutable snapshot:

* the repository URL and the tag -> commit pin;
* the recursive native dependencies (the two git submodules) by URL + commit;
* the SHA-256 of every vendored file;
* the license files and their detected SPDX identifiers.

Two distinct checks protect the snapshot:

* ``--check``      offline: recomputes the vendored file hashes and the
                   submodule pins declared in the vendored ``.gitmodules``
                   against the lock, without any network access;
* ``--ref <tag>``  online: resolves the tag and, when the same tag is already
                   pinned, rejects a moved tag or a changed submodule commit
                   before it can silently re-pin the snapshot.

Local adaptations belong in the TG-WS application source, never inside the
snapshot; the snapshot preserves the exact upstream bytes and licenses.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
from pathlib import Path, PurePosixPath
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "romanvht/ByeByeDPI"
URL = f"https://github.com/{REPOSITORY}"
LOCK = ROOT / "upstream/byebyedpi.lock.json"
VENDOR = ROOT / "upstream/byebyedpi"

LICENSE_PATTERN = re.compile(r"^(license|copying|notice|copyright)(\..*)?$", re.IGNORECASE)


def fetch(url: str) -> bytes:
    headers = {
        "User-Agent": "Derigava-TgWsProxy-sync",
        "Accept": "application/vnd.github+json",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token and urllib.parse.urlparse(url).hostname == "api.github.com":
        headers["Authorization"] = f"Bearer {token}"

    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_sha(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) is not None


def parse_gitmodules(text: str) -> dict[str, str]:
    """Return ``{path: url}`` for every submodule declared in ``.gitmodules``."""
    subs: dict[str, dict[str, str]] = {}
    current: str | None = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip()
            if section.startswith('submodule "') and section.endswith('"'):
                current = section[len('submodule "'):-1]
                subs.setdefault(current, {})
            else:
                current = None
            continue
        if current is not None and "=" in line:
            key, _, value = line.partition("=")
            key, value = key.strip(), value.strip()
            if key in ("path", "url"):
                subs[current][key] = value
    return {entry["path"]: entry["url"] for entry in subs.values() if entry.get("path") and entry.get("url")}


def detect_spdx(data: bytes) -> str:
    text = data.decode("utf-8", "replace")
    if "GNU GENERAL PUBLIC LICENSE" in text and "Version 3" in text:
        return "GPL-3.0-only"
    if "Apache License" in text and "Version 2.0" in text:
        return "Apache-2.0"
    if "MIT License" in text or ("Permission is hereby granted" in text and "MIT" in text):
        return "MIT"
    return "UNKNOWN"


def extract_sources(archive: bytes) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        for item in source.infolist():
            if item.is_dir():
                continue
            # A source archive has exactly one repository-prefix component.
            path = item.filename.partition("/")[2]
            parts = PurePosixPath(path).parts
            if not parts or any(part in (".", "..", "") for part in parts) or "\\" in path or PurePosixPath(path).as_posix() != path:
                continue
            if (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"Symlink in upstream source: {path}")
            if path in files:
                raise ValueError(f"Duplicate upstream path: {path}")
            files[path] = source.read(item)
    if "LICENSE" not in files or "app/src/main/AndroidManifest.xml" not in files:
        raise ValueError("Archive lacks the ByeByeDPI license or manifest")
    return files


def validate(vendor: Path, lock: dict) -> None:
    if lock.get("repository") != REPOSITORY:
        raise ValueError("Invalid upstream repository")
    if lock.get("url") != URL:
        raise ValueError("Invalid upstream URL")
    if not re.fullmatch(r"v\.\d+\.\d+\.\d+(?:\.\d+)?", lock.get("tag", "")):
        raise ValueError("Invalid upstream tag")
    if not is_sha(lock.get("commit")):
        raise ValueError("Invalid upstream commit SHA")
    submodules = lock.get("submodules")
    if not isinstance(submodules, dict) or not submodules:
        raise ValueError("Missing upstream submodule pins")
    for path, entry in submodules.items():
        if not isinstance(entry, dict) or not entry.get("url") or not is_sha(entry.get("commit")):
            raise ValueError(f"Invalid submodule pin: {path}")
    licenses = lock.get("licenses")
    if not isinstance(licenses, dict) or not licenses:
        raise ValueError("Missing upstream licenses")
    expected = lock.get("files")
    if not isinstance(expected, dict) or not expected:
        raise ValueError("Invalid upstream file inventory")
    for path, meta in licenses.items():
        if not isinstance(meta, dict) or not meta.get("spdx"):
            raise ValueError(f"Invalid license record: {path}")
        if expected.get(path) != meta.get("sha256"):
            raise ValueError(f"License hash disagrees with inventory: {path}")
    actual = {path.relative_to(vendor).as_posix(): sha256(path.read_bytes())
              for path in vendor.rglob("*") if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc"}
    if actual != expected:
        added = sorted(actual.keys() - expected.keys())
        missing = sorted(expected.keys() - actual.keys())
        modified = sorted(path for path in actual.keys() & expected.keys() if actual[path] != expected[path])
        raise ValueError(f"Upstream integrity failed: added={added}, missing={missing}, modified={modified}")
    gitmodules = vendor / ".gitmodules"
    if not gitmodules.is_file():
        raise ValueError("Snapshot lacks .gitmodules")
    declared = parse_gitmodules(gitmodules.read_text(encoding="utf-8"))
    pinned = {path: entry["url"] for path, entry in submodules.items()}
    if declared != pinned:
        raise ValueError(f"Submodule pins disagree with .gitmodules: declared={declared}, pinned={pinned}")


def resolve_commit(ref: str) -> str:
    data = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/commits/{urllib.parse.quote(ref, safe='')}"))
    commit = data.get("sha", "")
    if not is_sha(commit):
        raise ValueError("Invalid upstream commit SHA from API")
    return commit


def resolve_submodules(commit: str) -> dict[str, dict[str, str]]:
    tree = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/git/trees/{commit}?recursive=1"))
    if tree.get("truncated"):
        raise ValueError("Upstream tree listing is truncated")
    gitlinks = {entry["path"]: entry["sha"] for entry in tree.get("tree", []) if entry.get("mode") == "160000"}
    gitmodules = fetch(f"https://raw.githubusercontent.com/{REPOSITORY}/{commit}/.gitmodules").decode("utf-8")
    urls = parse_gitmodules(gitmodules)
    if set(urls) != set(gitlinks):
        raise ValueError("Submodule paths disagree between .gitmodules and the tree gitlinks")
    return {path: {"url": urls[path], "commit": gitlinks[path]} for path in sorted(urls)}


def sync(ref: str | None = None) -> dict:
    previous = json.loads(LOCK.read_text(encoding="utf-8")) if LOCK.exists() else None
    if ref is None:
        release = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/releases/latest"))
        if release.get("draft") or release.get("prerelease"):
            raise ValueError("Upstream release must be stable and published")
        ref = release["tag_name"]
    if not re.fullmatch(r"v\.\d+\.\d+\.\d+(?:\.\d+)?", ref):
        raise ValueError(f"Unsupported stable release tag: {ref!r}")
    commit = resolve_commit(ref)
    if previous and previous.get("tag") == ref and previous.get("commit") != commit:
        raise ValueError(f"Tag {ref!r} moved: pinned {previous.get('commit')}, now {commit}")
    submodules = resolve_submodules(commit)
    if previous and previous.get("tag") == ref and previous.get("submodules") != submodules:
        raise ValueError(f"Submodules changed under tag {ref!r}")
    if previous and previous.get("commit") == commit:
        validate(VENDOR, previous)
        return {"changed": False, "tag": ref, "commit": commit}
    files = extract_sources(fetch(f"https://codeload.github.com/{REPOSITORY}/zip/{commit}"))
    licenses = {path: {"spdx": detect_spdx(data), "sha256": sha256(data)}
                for path, data in files.items() if LICENSE_PATTERN.match(Path(path).name)}
    lock = {"repository": REPOSITORY, "url": URL, "tag": ref, "commit": commit,
            "submodules": submodules, "licenses": licenses,
            "files": {path: sha256(data) for path, data in sorted(files.items())}}
    VENDOR.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="byebyedpi-", dir=VENDOR.parent) as temporary:
        staged = Path(temporary) / "byebyedpi"
        for path, data in files.items():
            target = staged / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        validate(staged, lock)
        # Only our dedicated vendor folder can be replaced, never a computed shell path.
        if VENDOR.resolve() != (ROOT.resolve() / "upstream" / "byebyedpi"):
            raise ValueError("Refusing to replace a vendor directory outside the workspace")
        if VENDOR.exists():
            shutil.rmtree(VENDOR)
        shutil.move(str(staged), str(VENDOR))
        LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return {"changed": True, "tag": ref, "commit": commit}


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", help="Exact stable tag; omitted means latest stable release")
    parser.add_argument("--check", action="store_true", help="Verify local lock and bytes without network or changes")
    args = parser.parse_args(argv)
    try:
        if args.check:
            lock = json.loads(LOCK.read_text(encoding="utf-8"))
            validate(VENDOR, lock)
            result = {"changed": False, "tag": lock["tag"], "commit": lock["commit"]}
        else:
            result = sync(args.ref)
    except ValueError as error:
        print(f"byebyedpi sync: {error}", file=sys.stderr)
        raise SystemExit(1)
    print(json.dumps(result))
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            for key, value in result.items():
                if isinstance(value, (dict, list)):
                    continue
                stream.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")


if __name__ == "__main__":
    main()
