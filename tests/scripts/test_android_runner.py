import importlib.util
from pathlib import Path
import pytest

spec = importlib.util.spec_from_file_location("android_runner", Path(__file__).resolve().parents[2] / "scripts/run_android_tests.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_accepts_complete_instrumentation_success():
    assert module.validate_result("OK (14 tests)\nINSTRUMENTATION_CODE: -1\n") == 14


@pytest.mark.parametrize("output", ["FAILURES!!!\nTests run: 14, Failures: 1", "INSTRUMENTATION_STATUS_CODE: -2\nOK (14 tests)", "OK (0 tests)", "INSTRUMENTATION_FAILED: Process crashed", ""])
def test_rejects_failed_or_incomplete_runner_even_when_shell_exit_was_zero(output):
    with pytest.raises(ValueError):
        module.validate_result(output)
