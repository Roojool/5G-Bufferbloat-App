"""Synthetic/mocked source tests only. Never discover a device or open a network socket."""
import copy
from contextlib import ExitStack
import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import Mock, patch

import socket_endpoint as endpoint
import stage1_batch as batch

spec = importlib.util.spec_from_file_location("upload_operator", Path(__file__).with_name("operator.py"))
operator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operator)


class UploadTests(unittest.TestCase):
    def test_adb_cleanup_timeout_preserves_collected_failure_record(self):
        runs, settings, original = batch.load_manifest(batch.PRESETS / "wifi-upload.json")
        settings["rtt"] = {"method": "none"}
        record = {"status": "RESULT", "cleanup_joined": True,
                  "result": {"outcome": "CANCELLED", "stream": {"total_accepted_bytes": 64}}}
        args = batch.parser().parse_args(["--preset", "wifi-upload", "--endpoint-address", "192.0.2.1",
                                         "--bind-address", "192.0.2.1", "--no-tshark"])
        endpoint_process = Mock()
        endpoint_process.final.return_value = None
        with tempfile.TemporaryDirectory() as temp, ExitStack() as stack:
            stack.enter_context(patch.object(batch, "OUTPUT_ROOT", Path(temp)))
            stack.enter_context(patch.object(batch, "ROOT", Path(temp)))
            for name, value in {
                "git_state": {"git_sha": "a" * 40}, "load_manifest": (runs[:1], settings, original),
                "discover_device": "private", "device_context": {}, "build_and_verify": {},
                "resolve_capture_setup": {"status": "SKIPPED"}, "EndpointProcess": endpoint_process,
                "command": subprocess.CompletedProcess([], 0, "", ""), "launch_android": None,
                "cancel_android": None,
            }.items():
                stack.enter_context(patch.object(batch, name, return_value=value))
            stack.enter_context(patch.object(batch, "remote_result", side_effect=[
                {"status": "READY"}, batch.Stage1Error("lost connection"), record]))
            stack.enter_context(patch.object(batch, "adb", side_effect=subprocess.TimeoutExpired("adb", 10)))
            session, summary = batch.run_batch(args)
            saved = json.loads((session / "redacted-summary.json").read_text())
            phone = saved["runs"][0]["phone"]
            self.assertEqual(64, phone["result"]["stream"]["total_accepted_bytes"])
            self.assertEqual("HOST_FAILURE", phone["host_completion_reason"])
            self.assertEqual("UNAVAILABLE", saved["runs"][0]["phone_result_file_cleanup"])
            self.assertEqual("FAILED_OR_INCONCLUSIVE", summary["runs"][0]["run_status"])
            endpoint_process.stop.assert_called_once()

    def test_cancellation_retains_worker_accounting_and_unavailable_cleanup_is_explicit(self):
        record = {"status": "RESULT", "result": {"outcome": "CANCELLED", "stream": {"total_accepted_bytes": 64}}}
        with patch.object(batch, "cancel_android") as cancel, patch.object(batch, "remote_result", return_value=record) as read:
            result = batch.cancel_and_collect("private", "run", "token", Path("unused"), "OWNER_ABORTED")
            self.assertEqual(64, result["result"]["stream"]["total_accepted_bytes"])
            self.assertEqual("OWNER_ABORTED", result["host_completion_reason"])
            cancel.assert_called_once(); self.assertEqual(5, read.call_args.args[2])
        with patch.object(batch, "cancel_android"), patch.object(batch, "remote_result", side_effect=batch.Stage1Error()):
            result = batch.cancel_and_collect("private", "run", "token", Path("unused"), "OWNER_ABORTED")
            self.assertEqual("UNAVAILABLE", result["cleanup_evidence"])

    def test_frozen_source_is_explicit_exact_and_still_requires_clean_tree(self):
        head = "a" * 40
        def command(args, **kwargs):
            value = "" if args[1] in ("status", "fetch") else head if args[-1] == "HEAD" else "b" * 40
            return subprocess.CompletedProcess(args, 0, value, "")
        with patch.object(batch, "command", side_effect=command):
            self.assertEqual("EXPLICIT_FROZEN_COMMIT", batch.git_state(head)["source_policy"])
            for bad in ("a" * 8, "b" * 40):
                with self.assertRaises(batch.Stage1Error): batch.git_state(bad)
        with patch.object(batch, "command", return_value=subprocess.CompletedProcess([], 0, " M dirty", "")):
            with self.assertRaises(batch.Stage1Error): batch.git_state(head)

    def config(self):
        return batch.upload_config({"experiment": "upload", "flowBytes": [100], "expectedBytes": 100,
                                    "rateBytesPerSecond": 1024})

    def test_presets_bounded_symmetric_and_reproducible(self):
        wifi, ws, _ = batch.load_manifest(batch.PRESETS / "wifi-upload.json")
        cellular, cs, _ = batch.load_manifest(batch.PRESETS / "cellular-upload.json")
        self.assertEqual(7, len(wifi))
        self.assertEqual([r["config"] for r in wifi], [r["config"] for r in cellular])
        self.assertLessEqual(ws["budgets"]["planned_bytes"], 268435456)
        self.assertEqual("continue", cs["failure_policy"])
        first, settings, _ = batch.load_manifest(batch.PRESETS / "wifi-paired.json")
        second, _, _ = batch.load_manifest(batch.PRESETS / "wifi-paired.json")
        self.assertEqual(first, second); self.assertEqual(10, len(first))
        self.assertEqual(20260917, settings["pair_seed"])

    def test_upload_input_rejects_invalid_bounds_and_types(self):
        for changes in ({"flowBytes": [100] * 5}, {"expectedBytes": 101}, {"rateBytesPerSecond": True},
                        {"sendBufferBytes": 0}, {"receiverPauseMs": 30001}, {"globalBufferBytes": 16},
                        {"rateChanges": [{"atMs": 1, "rateBytesPerSecond": 0}]}, {"relay": True}):
            with self.subTest(changes=changes), self.assertRaises(batch.Stage1Error):
                batch.upload_config({**self.config(), **changes})

    def peer(self, data):
        peer = Mock()
        pending = bytearray(data)
        def recv(limit):
            part = bytes(pending[:min(limit, 7)])
            del pending[:len(part)]
            return part
        peer.recv.side_effect = recv
        return peer

    def test_receiver_exact_hash_receipt_only_after_fin(self):
        peer = self.peer(bytes(range(100)))
        with patch.object(endpoint.time, "sleep"):
            result = endpoint.receive_transfer(peer, 100, 5, emit=lambda _: None)
        self.assertEqual("COMPLETE", result["outcome"])
        self.assertEqual(100, result["accepted_bytes"])
        peer.sendall.assert_called_once_with((endpoint.expected_hash(100) + "\n").encode())
        peer.shutdown.assert_called_once()

    def test_receiver_rejects_early_eof_excess_wrong_hash_and_errors(self):
        for data, outcome in ((bytes(range(50)), "EARLY_EOF"), (bytes(range(101)), "EXCESS_DATA"),
                              (b"x" * 100, "INTEGRITY_FAILED")):
            peer = self.peer(data)
            with patch.object(endpoint.time, "sleep"):
                result = endpoint.receive_transfer(peer, 100, 5, emit=lambda _: None)
            self.assertEqual(outcome, result["outcome"]); peer.sendall.assert_not_called()
        peer = self.peer(b""); peer.recv.side_effect = ConnectionResetError(104, "private")
        result = endpoint.receive_transfer(peer, 100, 5, emit=lambda _: None)
        self.assertEqual("FAILED", result["outcome"]); self.assertEqual(104, result["errno"])
        self.assertNotIn("private", json.dumps(result))

    def test_receiver_controlled_reset_never_sends_success_receipt(self):
        peer = self.peer(bytes(range(100)))
        result = endpoint.receive_transfer(peer, 100, 5, reset_after=14, emit=lambda _: None)
        self.assertEqual("INTENTIONAL_RESET", result["outcome"])
        peer.setsockopt.assert_called_once(); peer.sendall.assert_not_called()

    def test_classifier_requires_receiver_hash_counts_cleanup_and_all_flows(self):
        digest = endpoint.expected_hash(100)
        phone = {"status": "RESULT", "cleanup_joined": True, "result": {"outcome": "COMPLETE",
            "stream": {"flows": [{"accepted_bytes": 100, "written_bytes": 100, "undelivered_accepted_bytes": 0,
                                   "accepted_sha256": digest, "written_sha256": digest}]},
            "sockets": [{"receipt_sha256": digest, "close_errno": 0}]}}
        remote = {"outcome": "COMPLETE", "flows": [{"outcome": "COMPLETE", "accepted_bytes": 100, "sha256": digest}]}
        def classify(p, r): return batch.classify_result(p, r, 100, digest, self.config())
        self.assertEqual("SCREEN_COMPLETE", classify(phone, remote)["run_status"])
        for mutation in ("hash", "cleanup", "missing", "undelivered"):
            p, r = copy.deepcopy(phone), copy.deepcopy(remote)
            if mutation == "hash": r["flows"][0]["sha256"] = "0" * 64
            elif mutation == "cleanup": p["cleanup_joined"] = False
            elif mutation == "missing": r["flows"] = []
            else: p["result"]["stream"]["flows"][0]["undelivered_accepted_bytes"] = 1
            self.assertEqual("FAILED_OR_INCONCLUSIVE", classify(p, r)["run_status"])
        self.assertEqual(batch.UNVERIFIED, classify(phone, remote)["physical_benefit"])

    def test_endpoint_process_reuses_existing_orchestration_with_upload_controls(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(batch.subprocess, "Popen") as popen, \
                patch.object(batch.threading, "Thread"):
            batch.EndpointProcess("192.0.2.1", 39001, 100, 60, Path(temp) / "private.jsonl", self.config())
            args = popen.call_args.args[0]
            self.assertIn("--upload-flow-bytes", args); self.assertIn("--pause-ms", args)
            self.assertIn("socket_endpoint.py", " ".join(args))

    def test_baseline_topology_missing_or_no_inflation_is_inconclusive(self):
        idle = {"status": "RECORDED", "reply_sample_count": 30, "reply_p95_ms": 10}
        low = {**idle, "reply_p95_ms": 11}
        self.assertEqual("UNSUITABLE_NO_BASELINE_INFLATION", batch.topology_screen(idle, low))
        self.assertEqual("INCONCLUSIVE_MISSING_BASELINE_LATENCY", batch.topology_screen({}, low))
        self.assertEqual("BASELINE_INFLATION_OBSERVED_REVIEW_REQUIRED", batch.topology_screen(idle, {**low, "reply_p95_ms": 35}))
        summary = {"batch_status": "COMPLETE", "runs": [{"run_status": "SCREEN_COMPLETE",
                   "evidence_layers": {"download_topology": batch.topology_screen(idle, low)}}]}
        self.assertEqual(3, operator._experiment_exit(summary))

    def test_report_upload_allowlist_never_renders_private_decoys(self):
        result = {"experiment": "upload", "outcome": "COMPLETE", "address": "private-address",
                  "plan": {"sendBufferBytes": "private-value"}, "stream": {"flows": [{"accepted_bytes": "private-count"}]},
                  "sockets": [{"receipt_status": "private-state", "options": [{"returned": "private-option"}]}]}
        summary = {"runs": [{"phone": {"result": result}}]}
        report = operator.render_report(summary)
        self.assertNotIn("private-", report)
        self.assertIn("F-03 outcome: COMPLETE", report)
        self.assertTrue(report.endswith("AWAITING_REVIEWER_CONCLUSION\n"))


if __name__ == "__main__":
    unittest.main()
