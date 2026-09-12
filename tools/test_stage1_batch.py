import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import stage1_batch


class Stage1BatchTests(unittest.TestCase):
    def preset(self, name):
        return json.loads((stage1_batch.PRESETS / f"{name}.json").read_text(encoding="utf-8"))

    def test_wifi_screen_has_isolated_stable_variants_and_bounded_plan(self):
        runs, settings = stage1_batch.expand_manifest(self.preset("wifi-screen"))
        self.assertEqual(10, len(runs))
        self.assertEqual(10, len({run["run_id"] for run in runs}))
        self.assertEqual(167772160, settings["budgets"]["planned_bytes"])
        for run in runs:
            config = run["config"]
            active = sum(key in config for key in ("receiveBuffer", "clamp"))
            self.assertLessEqual(active, 1)
        self.assertEqual([5000, 7000], [c["atMs"] for c in runs[-1]["config"]["changes"]])

    def test_bounds_and_zero_clamp_are_rejected(self):
        document = self.preset("wifi-screen")
        document["variants"][0]["config"]["expectedBytes"] = stage1_batch.MAX_RUN_BYTES + 1
        with self.assertRaises(stage1_batch.Stage1Error):
            stage1_batch.expand_manifest(document)

    def test_independent_rtt_plan_is_bounded(self):
        document = self.preset("wifi-screen")
        document["rtt"]["load_count"] = 601
        with self.assertRaises(stage1_batch.Stage1Error):
            stage1_batch.expand_manifest(document)
        document = self.preset("wifi-screen")
        document["variants"][0]["config"]["clamp"] = 0
        with self.assertRaises(stage1_batch.Stage1Error):
            stage1_batch.expand_manifest(document)

    def test_seeded_pair_order_is_reproducible_and_balanced(self):
        document = self.preset("cellular-paired")
        first, settings = stage1_batch.expand_manifest(document)
        second, _ = stage1_batch.expand_manifest(copy.deepcopy(document))
        self.assertEqual(first, second)
        self.assertEqual(20260912, settings["pair_seed"])
        for pair in range(1, 6):
            variants = {run["variant"] for run in first if run["pair"] == pair}
            self.assertEqual({"baseline", "candidate-rcvbuf-65536"}, variants)

    def test_redaction_removes_private_values_recursively(self):
        private = {"address": "192.0.2.1", "serial": "secret", "nested": [{"network_handle": 55,
                   "capture_path": "x.pcapng", "tshark_path": "private-tshark.exe",
                   "capture_interface": "private-interface", "adapter_alias": "Wi-Fi", "safe": 7}],
                   "physical_benefit": stage1_batch.UNVERIFIED}
        redacted = stage1_batch.redact_private(private)
        text = json.dumps(redacted)
        self.assertNotIn("192.0.2.1", text)
        self.assertNotIn("secret", text)
        self.assertNotIn("x.pcapng", text)
        self.assertNotIn("private-tshark.exe", text)
        self.assertNotIn("private-interface", text)
        self.assertNotIn("Wi-Fi", text)
        self.assertEqual(7, redacted["nested"][0]["safe"])
        self.assertEqual(stage1_batch.UNVERIFIED, redacted["physical_benefit"])

    def test_tshark_path_discovery_is_first_lookup(self):
        calls = []
        def which(name):
            calls.append(name)
            return "/opt/bin/tshark" if name == "tshark" else None
        result = stage1_batch.discover_tshark(which=which, windows=False)
        self.assertEqual("READY", result["status"])
        self.assertEqual("PATH", result["source"])
        self.assertEqual("/opt/bin/tshark", result["tshark_path"])
        self.assertEqual(["tshark"], calls)

    def test_tshark_windows_standard_install_fallback(self):
        expected = r"C:\Program Files\Wireshark\tshark.exe"
        result = stage1_batch.discover_tshark(
            which=lambda name: None,
            environ={"ProgramFiles": r"C:\Program Files"},
            is_file=lambda path: path == expected,
            windows=True,
        )
        self.assertEqual("READY", result["status"])
        self.assertEqual("WINDOWS_STANDARD_INSTALL", result["source"])
        self.assertEqual(expected, result["tshark_path"])

    def test_explicit_tshark_path_override_wins_after_path_probe(self):
        with tempfile.TemporaryDirectory() as temp:
            explicit = Path(temp) / "tshark.exe"
            explicit.write_bytes(b"")
            calls = []
            def which(name):
                calls.append(name)
                return "/path/tshark"
            result = stage1_batch.discover_tshark(str(explicit), which=which, windows=False)
            self.assertEqual("EXPLICIT", result["source"])
            self.assertEqual(os.path.abspath(explicit), result["tshark_path"])
            self.assertEqual(["tshark"], calls)

    def test_windows_wifi_interface_resolves_from_bind_address(self):
        tshark = r"C:\Program Files\Wireshark\tshark.exe"
        adapter_json = json.dumps([
            {"InterfaceAlias": "Ethernet", "InterfaceIndex": 3, "IPAddress": "192.0.2.20"},
            {"InterfaceAlias": "Wi-Fi", "InterfaceIndex": 11, "IPAddress": "192.0.2.10"},
        ])
        interfaces = "1. \\Device\\NPF_{ETHERNET} (Ethernet)\n4. \\Device\\NPF_{WIFI} (Wi-Fi)\n"
        def which(name):
            return tshark if name == "tshark" else "powershell.exe"
        def run(args, **unused):
            output = interfaces if args[-1] == "-D" else adapter_json
            return subprocess.CompletedProcess(args, 0, output, "")
        result = stage1_batch.resolve_capture_setup(
            "192.0.2.10", None, None, False, command_fn=run, which=which, windows=True)
        self.assertEqual("READY", result["status"])
        self.assertEqual("PATH", result["source"])
        self.assertEqual("WINDOWS_BIND_ADAPTER_MATCH", result["interface_source"])
        self.assertEqual(r"\Device\NPF_{WIFI}", result["capture_interface"])
        self.assertFalse(result["capture_interface"].isdecimal())

    def test_tshark_interface_ambiguity_and_no_match_are_skipped(self):
        duplicate = stage1_batch.match_tshark_interface("Wi-Fi", [
            {"capture_interface": r"\Device\NPF_{ONE}", "display_name": "Wi-Fi"},
            {"capture_interface": r"\Device\NPF_{TWO}", "display_name": "Wi-Fi"},
        ])
        missing = stage1_batch.match_tshark_interface("Wi-Fi", [
            {"capture_interface": r"\Device\NPF_{ONE}", "display_name": "Ethernet"},
        ])
        self.assertEqual("SKIPPED_TSHARK_INTERFACE_AMBIGUOUS", duplicate["status"])
        self.assertEqual("SKIPPED_TSHARK_INTERFACE_NO_MATCH", missing["status"])
        self.assertTrue(duplicate["reason"])
        expected = "a" * 64
        phone = {"status": "RESULT", "result": {"outcome": "COMPLETE", "bytes": 5,
                 "sha256": expected, "options": []}}
        endpoint = {"outcome": "COMPLETE", "accepted_bytes": 5, "sha256": expected}
        transfer = stage1_batch.classify_result(phone, endpoint, 5, expected, {})
        self.assertEqual("SCREEN_COMPLETE", transfer["run_status"])
        self.assertEqual(stage1_batch.UNVERIFIED, transfer["sender_transport_effect"])

    def test_numeric_tshark_interface_override_is_rejected(self):
        result = stage1_batch.resolve_capture_setup(
            "192.0.2.10", None, "4", False,
            which=lambda name: "/opt/tshark" if name == "tshark" else None,
            windows=True,
        )
        self.assertEqual("SKIPPED_TSHARK_NUMERIC_INTERFACE_REJECTED", result["status"])

    def test_hash_match_never_promotes_transport_or_benefit(self):
        expected = "a" * 64
        phone = {"status": "RESULT", "result": {"outcome": "COMPLETE", "bytes": 5,
                 "sha256": expected, "options": []}}
        endpoint = {"outcome": "COMPLETE", "accepted_bytes": 5, "sha256": expected}
        result = stage1_batch.classify_result(phone, endpoint, 5, expected, {})
        self.assertEqual("VERIFIED_FOR_THIS_TRANSFER", result["integrity"])
        self.assertEqual(stage1_batch.UNVERIFIED, result["sender_transport_effect"])
        self.assertEqual(stage1_batch.UNVERIFIED, result["physical_benefit"])

    def test_failed_option_is_retained_and_policy_controls_later_runs(self):
        calls = []
        runs = [{"run_id": "a"}, {"run_id": "b"}]
        def fail(run):
            calls.append(run["run_id"])
            return {"run_status": "FAILED_OR_INCONCLUSIVE"}
        self.assertEqual(1, len(stage1_batch.execute_sequence(runs, fail, False)))
        self.assertEqual(["a"], calls)
        calls.clear()
        self.assertEqual(2, len(stage1_batch.execute_sequence(runs, fail, True)))
        self.assertEqual(["a", "b"], calls)

    def test_endpoint_process_cleanup_retains_log_on_start_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            log = Path(temp) / "endpoint.jsonl"
            process = stage1_batch.EndpointProcess("127.0.0.1", 0, 8, 1, log)
            with self.assertRaises(stage1_batch.Stage1Error):
                process.wait_ready(2)
            self.assertTrue(log.exists())


if __name__ == "__main__":
    unittest.main()
