"""Fail when a release APK requests permissions beyond the published allowlist.

Play Protect reacted to the 1.1.0 self-updater (REQUEST_INSTALL_PACKAGES) and to
the battery-optimization exemption request. The published app needs only the
permissions below, so any addition is a deliberate decision that must update
this list, the README and the Play Protect story together.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ALLOWED = frozenset({
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.WAKE_LOCK",
})

# Explicitly called out so the failure names the Play Protect capability rather
# than only saying "not in the allowlist".
FORBIDDEN = frozenset({
    "android.permission.INSTALL_PACKAGES",
    "android.permission.REQUEST_DELETE_PACKAGES",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.WRITE_SETTINGS",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
})

PERMISSION_RE = re.compile(r"uses-permission(?:-sdk-\d+)?: name=['\"]([^'\"]+)['\"]")
PACKAGE_RE = re.compile(r"^package: name=['\"]([^'\"]+)['\"]", re.MULTILINE)


def parse_package(badging: str) -> str:
    match = PACKAGE_RE.search(badging)
    if not match:
        raise ValueError("aapt2 output has no package name")
    return match.group(1)


def parse_permissions(badging: str) -> set[str]:
    return set(PERMISSION_RE.findall(badging))


def check(permissions: set[str], package: str) -> None:
    forbidden = sorted(permissions & FORBIDDEN)
    if forbidden:
        raise ValueError("Forbidden Play Protect capability: " + ", ".join(forbidden))
    allowed = ALLOWED | {f"{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
    unexpected = sorted(permissions - allowed)
    if unexpected:
        raise ValueError(
            "Unexpected permission(s): " + ", ".join(unexpected) +
            "; update scripts/check_permissions.py, README.md and docs/PLAY_PROTECT.md intentionally"
        )


def find_aapt2(explicit: str | None = None) -> str:
    candidates: list[str] = []
    if explicit:
        candidates.append(explicit)
    if os.environ.get("AAPT2"):
        candidates.append(os.environ["AAPT2"])
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        build_tools = Path(sdk) / "build-tools"
        if build_tools.is_dir():
            candidates.extend(str(path) for path in sorted(build_tools.glob("*/aapt2"), reverse=True))
    which = shutil.which("aapt2")
    if which:
        candidates.append(which)
    for candidate in candidates:
        path = Path(candidate)
        if path.is_file():
            return str(path)
    raise ValueError("aapt2 not found; pass --aapt2 or set AAPT2/ANDROID_HOME")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", help="path to aapt2 (default: AAPT2/ANDROID_HOME/PATH)")
    options = parser.parse_args(argv)
    try:
        aapt2 = find_aapt2(options.aapt2)
        result = subprocess.run(
            [aapt2, "dump", "badging", str(options.apk)],
            capture_output=True, text=True, check=False,
        )
        if result.returncode != 0:
            raise ValueError(f"aapt2 failed: {result.stderr.strip() or result.stdout.strip()}")
        package = parse_package(result.stdout)
        permissions = parse_permissions(result.stdout)
        check(permissions, package)
    except ValueError as error:
        print(f"Permission check failed: {error}", file=sys.stderr)
        return 1
    print(f"Permission check: {package} requests {len(permissions)} expected permission(s)")
    for permission in sorted(permissions):
        print(f"  {permission}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
