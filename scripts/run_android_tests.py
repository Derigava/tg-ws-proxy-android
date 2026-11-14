"""Run prebuilt Android tests without keeping a Gradle VM beside the emulator."""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import subprocess

MIN_ANDROID_TESTS = 19


def validate_result(output: str) -> int:
    # am instrument may return shell exit code zero even when JUnit failed.
    if re.search(r"INSTRUMENTATION_STATUS_CODE: -\d+\b|FAILURES!!!|INSTRUMENTATION_FAILED", output):
        raise ValueError("Android instrumentation reported failures")
    match = re.search(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    if not match or int(match[1]) < MIN_ANDROID_TESTS:
        raise ValueError("The complete Android test suite did not report success")
    return int(match[1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--wrong-signer-apk", type=Path, required=True)
    parser.add_argument("--expected-page-size", choices=("4096", "16384"), required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    for path in (args.apk, args.test_apk, args.wrong_signer_apk):
        if not path.exists():
            raise FileNotFoundError(path)
    def adb(*command):
        # Installing a large APK on a CI emulator without KVM headroom can take minutes.
        return subprocess.run(["adb", *map(str, command)], check=True, timeout=300)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    try:
        adb("install", "-r", args.apk)
        adb("install", "-r", args.test_apk)
        foreign = subprocess.run(["adb", "install", "-r", str(args.wrong_signer_apk)], capture_output=True,
                                 text=True, timeout=120)
        if foreign.returncode == 0 or "INSTALL_FAILED_UPDATE_INCOMPATIBLE" not in foreign.stdout + foreign.stderr:
            raise RuntimeError(f"Expected Android to reject the foreign signing key: {foreign.stdout}{foreign.stderr}")
        print("Android rejected an update with a foreign signing key")
        result = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-r", "-e", "expectedPageSize",
            args.expected_page_size, "io.github.derigava.tgwsproxy.test/androidx.test.runner.AndroidJUnitRunner"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", timeout=240)
        args.report.write_text(result.stdout, encoding="utf-8")
        print(result.stdout)
        result.check_returncode()
        count = validate_result(result.stdout)
    except Exception as failure:
        if isinstance(failure, subprocess.TimeoutExpired) and failure.output:
            partial = failure.output.decode("utf-8", errors="replace") if isinstance(failure.output, bytes) else failure.output
            args.report.write_text(partial, encoding="utf-8")
        diagnostics = {
            "logcat": ["adb", "logcat", "-d", "-b", "all", "-v", "threadtime"],
            "crash": ["adb", "logcat", "-d", "-b", "crash", "-v", "threadtime"],
            "exit-info": ["adb", "shell", "dumpsys", "activity", "exit-info", "io.github.derigava.tgwsproxy"],
        }
        for name, command in diagnostics.items():
            try:
                diagnostic = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    text=True, encoding="utf-8", errors="replace", timeout=30)
                args.report.with_suffix("." + name + ".txt").write_text(diagnostic.stdout, encoding="utf-8")
                if name != "logcat":
                    print(f"Android {name}:\n" + diagnostic.stdout[-20000:])
            except Exception as error:
                print(f"Could not collect {name}: {error}")
        raise
    print(f"Android gate passed: {count} tests; page size {args.expected_page_size}")



if __name__ == "__main__":
    main()
