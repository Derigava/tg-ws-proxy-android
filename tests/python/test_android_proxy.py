"""Offline lifecycle checks against the actual vendored upstream listener."""
import asyncio
import hashlib
import json
import logging
import socket
import sys
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "app/src/main/python"), str(ROOT / "upstream/flowseal")]

import android_proxy as adapter


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def config(**changes):
    value = {"host": "127.0.0.1", "port": free_port(), "secret": "0123456789abcdef" * 2,
             "dc_ips": "", "pool_size": 0, "cf_enabled": False, "cf_domain": ""}
    value.update(changes)
    return value


class AdapterLifecycleTests(unittest.TestCase):
    def setUp(self):
        adapter.stop()
        self.refresh = patch.object(adapter.core, "start_cfproxy_domain_refresh")
        self.refresh.start()
        self.addCleanup(self.refresh.stop)
        self.addCleanup(adapter.stop)

    def test_native_crypto_http2_and_ca_imports(self):
        result = json.loads(adapter.self_test())
        self.assertTrue(result["ok"])
        lock = json.loads((ROOT / "upstream/flowseal.lock.json").read_text(encoding="utf-8"))
        self.assertEqual(result["core_version"], lock["core_version"])

    def test_listener_ready_and_snapshot_uses_actual_configuration(self):
        settings = config()
        started = json.loads(adapter.start(json.dumps(settings)))
        self.assertEqual(started["state"], "running", started)
        self.assertEqual(started["config"]["full_secret"], "dd" + settings["secret"])
        self.assertEqual(started["config"]["port"], settings["port"])
        with socket.create_connection(("127.0.0.1", settings["port"]), timeout=1):
            pass
        stopped = json.loads(adapter.stop())
        self.assertEqual(stopped["state"], "stopped")
        self.assertIsNone(stopped["config"])
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", settings["port"]))

    def test_repeated_start_is_idempotent_but_new_settings_require_stop(self):
        settings = config()
        first = json.loads(adapter.start(json.dumps(settings)))
        self.assertTrue(first["running"])
        self.assertEqual(json.loads(adapter.start(json.dumps(settings)))["config"], first["config"])
        different = dict(settings, port=free_port())
        rejected = json.loads(adapter.start(json.dumps(different)))
        self.assertTrue(rejected["running"])
        self.assertEqual(rejected["config"]["port"], settings["port"])
        self.assertIsNotNone(rejected["error"])

    def test_stop_during_pool_warmup_closes_listener_and_tasks(self):
        async def blocked_warmup():
            await asyncio.Future()

        settings = config()
        with patch.object(adapter.core.ws_pool, "warmup", side_effect=blocked_warmup):
            self.assertTrue(json.loads(adapter.start(json.dumps(settings)))["running"])
            before = time.monotonic()
            self.assertEqual(json.loads(adapter.stop())["state"], "stopped")
            self.assertLess(time.monotonic() - before, 3)
        self.assertFalse(adapter._runtime._thread.is_alive())
        self.assertIsNone(adapter.core._server_instance)
        self.assertFalse(adapter.core._client_tasks)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", settings["port"]))

    def test_stop_interrupts_start_before_listener_exists(self):
        reached = threading.Event()

        async def pending_start(_):
            reached.set()
            await asyncio.Future()

        result = []
        with patch.object(adapter.core, "_run", side_effect=pending_start):
            starter = threading.Thread(target=lambda: result.append(json.loads(adapter.start(json.dumps(config())))))
            starter.start()
            self.assertTrue(reached.wait(2))
            self.assertEqual(json.loads(adapter.stop())["state"], "stopped")
            starter.join(2)
            self.assertFalse(starter.is_alive())
            self.assertFalse(result[0]["running"])

    def test_occupied_port_returns_failure_and_can_retry(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            settings = config(port=listener.getsockname()[1])
            failed = json.loads(adapter.start(json.dumps(settings)))
            self.assertEqual(failed["state"], "error", failed)
            self.assertFalse(failed["running"])
            self.assertIsNotNone(failed["error"])
        self.assertTrue(json.loads(adapter.start(json.dumps(settings)))["running"])

    def test_invalid_settings_are_rejected_without_starting(self):
        for invalid in ({"port": 0}, {"port": 65536}, {"port": True}, {"secret": "bad"},
                        {"host": "not an IP"}, {"dc_ips": "2:broken"},
                        {"cf_domain": "https://example.org"}, {"pool_size": -1},
                        {"cf_enabled": "true"}):
            with self.subTest(invalid=invalid):
                failed = json.loads(adapter.start(json.dumps(config(**invalid))))
                self.assertEqual(failed["state"], "error")
                self.assertFalse(failed["running"])
        self.assertEqual(json.loads(adapter.start("[]"))["state"], "error")
        self.assertEqual(json.loads(adapter.start("{"))["state"], "error")

    def test_repeated_start_stop_has_no_thread_or_listener_leak(self):
        settings = config()
        for _ in range(8):
            self.assertTrue(json.loads(adapter.start(json.dumps(settings)))["running"])
            self.assertEqual(json.loads(adapter.stop())["state"], "stopped")
            self.assertFalse(adapter._runtime._thread.is_alive())
        self.assertFalse([thread for thread in threading.enumerate() if thread.name == "FlowsealProxy"])

    def test_upstream_refresh_worker_is_reaped(self):
        def real_background_worker():
            adapter.core_config._refresh_stop = threading.Event()
            event = adapter.core_config._refresh_stop
            threading.Thread(target=event.wait, name="cfproxy-domains-refresh", daemon=True).start()

        with patch.object(adapter.core, "start_cfproxy_domain_refresh", side_effect=real_background_worker):
            self.assertTrue(json.loads(adapter.start(json.dumps(config())))["running"])
            self.assertEqual(json.loads(adapter.stop())["state"], "stopped")
        self.assertFalse([thread for thread in threading.enumerate() if thread.name == "cfproxy-domains-refresh"])

    def test_secret_redaction_for_upstream_records(self):
        secret = "0123456789abcdef" * 2
        adapter._logs.clear()
        logging.getLogger("tg-mtproto-proxy").info("Secret: %s tg://proxy?secret=dd%s", secret, secret)
        record = adapter._logs[-1]
        self.assertNotIn(secret, record["message"])
        self.assertIn("[secret redacted]", record["message"])

    def test_vendored_files_match_locked_source_hashes(self):
        lock = json.loads((ROOT / "upstream/flowseal.lock.json").read_text(encoding="utf-8"))
        self.assertRegex(lock["commit"], r"^[0-9a-f]{40}$")
        for name, digest in lock["files"].items():
            self.assertEqual(hashlib.sha256((ROOT / "upstream/flowseal" / name).read_bytes()).hexdigest(), digest, name)


if __name__ == "__main__":
    unittest.main()
