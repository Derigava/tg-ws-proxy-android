"""Run prebuilt Android tests without keeping a Gradle VM beside the emulator."""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import subprocess


def validate_result(output: str) -> int:
    # am instrument may return shell exit code zero even when JUnit failed.
    if re.search(r"INSTRUMENTATION_STATUS_CODE: -\d+\b|FAILURES!!!|INSTRUMENTATION_FAILED", output):
        raise ValueError("Android instrumentation reported failures")
    match = re.search(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    if not match or int(match[1]) < 14:
        raise ValueError("The complete Android test suite did not report success")
    return int(match[1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--expected-page-size", choices=("4096", "16384"), required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    for path in (args.apk, args.test_apk, args.fixtures):
        if not path.exists():
            raise FileNotFoundError(path)
    def adb(*command):
        return subprocess.run(["adb", *map(str, command)], check=True, timeout=180)
    adb("install", "-r", args.apk)
    adb("install", "-r", args.test_apk)
    adb("shell", "rm", "-rf", "/data/local/tmp/tgws-updater-fixtures")
    adb("push", args.fixtures, "/data/local/tmp/")
    result = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-r", "-e", "expectedPageSize",
        args.expected_page_size, "io.github.derigava.tgwsproxy.test/androidx.test.runner.AndroidJUnitRunner"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", timeout=240)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(result.stdout, encoding="utf-8")
    print(result.stdout)
    result.check_returncode()
    print(f"Android gate passed: {validate_result(result.stdout)} tests; page size {args.expected_page_size}")


if __name__ == "__main__":
    main()
