import argparse
import contextlib
import io
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("stage1_operator", Path(__file__).with_name("operator.py"))
assert spec and spec.loader
operator_tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operator_tool)


class OperatorTests(unittest.TestCase):
    def target(self, **changes):
        values = dict(
            preset="wifi-screen", transport="wifi", endpoint_address="192.168.50.2",
            bind_address="192.168.50.2", port=39001, confirm_endpoint_bind=True,
            serial=None, network_ordinal=-1, build=False, install=False,
            tshark_path=None, tshark_interface=None, no_tshark=True,
            continue_on_failure=False,
        )
        values.update(changes)
        return argparse.Namespace(**values)

    def test_only_reviewed_presets_are_exposed(self):
        self.assertEqual(("wifi-screen", "wifi-efficacy", "cellular-paired"),
                         operator_tool.REVIEWED_PRESETS)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            operator_tool.parser().parse_args([
                "run", "--preset", "custom", "--transport", "wifi",
                "--endpoint-address", "192.168.50.2", "--bind-address", "192.168.50.2",
                "--port", "39001", "--confirm-endpoint-bind",
            ])

    def test_endpoint_and_bind_are_required_on_each_invocation(self):
        required = ["run", "--preset", "wifi-screen", "--transport", "wifi",
                    "--bind-address", "192.168.50.2", "--port", "39001",
                    "--confirm-endpoint-bind"]
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            operator_tool.parser().parse_args(required)
        with self.assertRaises(operator_tool.BlockedPrerequisite):
            operator_tool.validate_target(self.target(confirm_endpoint_bind=False))
        first = operator_tool._batch_args(self.target(endpoint_address="192.168.50.2"))
        second = operator_tool._batch_args(self.target(endpoint_address="192.168.60.2"))
        self.assertEqual("192.168.50.2", first.endpoint_address)
        self.assertEqual("192.168.60.2", second.endpoint_address)

    def test_cellular_rejects_private_lan_endpoint(self):
        with self.assertRaisesRegex(operator_tool.BlockedPrerequisite, "globally routable"):
            operator_tool.validate_target(self.target(
                preset="cellular-paired", transport="cellular",
                endpoint_address="192.168.50.2", bind_address="0.0.0.0"))
        operator_tool.validate_target(self.target(
            preset="cellular-paired", transport="cellular",
            endpoint_address="8.8.8.8", bind_address="192.168.50.2"))

    def test_transport_must_match_reviewed_preset(self):
        with self.assertRaisesRegex(operator_tool.BlockedPrerequisite, "does not match"):
            operator_tool.validate_target(self.target(transport="cellular"))

    def test_preflight_rediscovers_device_and_transport_every_time(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "output" / "stage1"
            discovery = Mock(side_effect=["serial-a", "serial-b"])
            probe = Mock(side_effect=[{"status": "READY"}, {"status": "READY"}])
            source = {"git_sha": "a" * 40, "origin_main_sha": "b" * 40, "working_tree": "clean"}
            settings = {"preset": "wifi-screen", "transport": "wifi", "budgets": {},
                        "failure_policy": "continue", "rtt": {}, "pair_seed": None}
            with patch.object(operator_tool.batch, "OUTPUT_ROOT", root), \
                 patch.object(operator_tool, "_ensure_private_root"), \
                 patch.object(operator_tool.batch, "git_state", return_value=source), \
                 patch.object(operator_tool.batch, "load_manifest", return_value=([], settings, {})), \
                 patch.object(operator_tool.batch, "discover_device", discovery), \
                 patch.object(operator_tool.batch, "device_context", return_value={"target_kind": "physical"}), \
                 patch.object(operator_tool.batch, "build_and_verify", return_value={"git_sha": "a" * 40}), \
                 patch.object(operator_tool.batch, "resolve_capture_setup", return_value={"status": "SKIPPED"}), \
                 patch.object(operator_tool, "_probe_current_transport", probe), \
                 contextlib.redirect_stdout(io.StringIO()):
                operator_tool.preflight_command(self.target(serial="serial-a", network_ordinal=1))
                operator_tool.preflight_command(self.target(serial="serial-b", network_ordinal=2))
            self.assertEqual([unittest.mock.call("serial-a"), unittest.mock.call("serial-b")], discovery.call_args_list)
            self.assertEqual(("serial-a", "wifi", 1), probe.call_args_list[0].args[:3])
            self.assertEqual(("serial-b", "wifi", 2), probe.call_args_list[1].args[:3])
            summaries = list(root.glob("*/preflight-summary.json"))
            self.assertEqual(2, len(summaries))
            exported = "\n".join(path.read_text(encoding="utf-8") for path in summaries)
            self.assertNotIn("serial-a", exported)
            self.assertNotIn("192.168.50.2", exported)
            self.assertNotIn("39001", exported)

    def test_zero_or_multiple_devices_are_blocked(self):
        valid = ["preflight", "--preset", "wifi-screen", "--transport", "wifi",
                 "--endpoint-address", "192.168.50.2", "--bind-address", "192.168.50.2",
                 "--port", "39001", "--confirm-endpoint-bind", "--no-tshark"]
        for count in (0, 2):
            with self.subTest(count=count), \
                 patch.object(operator_tool.batch, "git_state", return_value={"git_sha": "a" * 40}), \
                 patch.object(operator_tool.batch, "load_manifest", return_value=([], {"transport": "wifi"}, {})), \
                 patch.object(operator_tool.batch, "discover_device",
                              side_effect=operator_tool.batch.Stage1Error(
                                  f"expected exactly one authorized ADB device, found {count}; use --serial")), \
                 contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(operator_tool.EXIT_BLOCKED, operator_tool.main(valid))

    def test_explicit_current_session_device_is_forwarded(self):
        converted = operator_tool._batch_args(self.target(serial="current-device"))
        self.assertEqual("current-device", converted.serial)

    def test_underlying_discovery_requires_current_explicit_choice_when_multiple(self):
        devices = subprocess.CompletedProcess(
            ["adb", "devices"], 0, "List of devices attached\nfirst\tdevice\nsecond\tdevice\n", "")
        with patch.object(operator_tool.batch, "command", return_value=devices):
            with self.assertRaises(operator_tool.batch.Stage1Error):
                operator_tool.batch.discover_device(None)
            self.assertEqual("second", operator_tool.batch.discover_device("second"))

    def test_direct_script_entry_point_does_not_shadow_stdlib_operator(self):
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("operator.py")), "--help"],
            text=True, capture_output=True, timeout=15,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("{preflight,run,report}", result.stdout)

    def test_report_is_strictly_redacted_and_awaits_review(self):
        summary = {
            "schema": 1, "preset": "wifi-screen", "transport": "wifi",
            "batch_status": "COMPLETE", "serial": "device-secret",
            "endpoint_address": "192.168.50.2", "port": 39001,
            "source_build": {"git_sha": "a" * 40, "capture_path": "private.pcapng"},
            "device_context": {"ssid": "private-ssid", "location": "private-place"},
            "runs": [{
                "run_status": "SCREEN_COMPLETE", "interface": "private-interface",
                "phone": {"result": {"outcome": "COMPLETE", "bytes": 16,
                    "elapsed_ms": 20, "errno": 0, "close_errno": 0,
                    "options": [{"kind": "SO_RCVBUF", "phase": "before_connect",
                        "constant_available": True, "requested": 65536, "set_errno": 0,
                        "get_errno": 0, "returned": 131072, "returned_length": 4}],
                    "samples": [{"tcp_info_errno": 0, "tcp_info_length": 232,
                        "fields": {"rtt_us": 1000, "address": "192.168.50.2"}}]}},
                "evidence_layers": {
                    "acceptance_readback": "OBSERVED_SEPARATELY",
                    "integrity": "VERIFIED_FOR_THIS_TRANSFER",
                    "recovery": operator_tool.batch.UNVERIFIED,
                    "sender_transport_effect": "PARTIAL_WIRE_OBSERVATIONS",
                    "physical_benefit": operator_tool.batch.UNVERIFIED,
                },
                "rtt": {"idle": {"status": "RECORDED"},
                        "under_load": {"status": "RECORDED_PARTIAL", "address": "203.0.113.8"}},
                "capture": {"derived": {"status": "PARTIAL"}, "mac": "00:11:22:33:44:55"},
            }],
        }
        report = operator_tool.render_report(summary)
        for private in ("device-secret", "192.168.50.2", "39001", "private.pcapng",
                        "private-ssid", "private-place", "private-interface",
                        "203.0.113.8", "00:11:22:33:44:55"):
            self.assertNotIn(private, report)
        self.assertTrue(report.endswith(operator_tool.REPORT_END + "\n"))
        self.assertIn("no Stage pass/fail or efficacy conclusion", report)
        self.assertIn("returned_lengths=232", report)
        self.assertIn("kind=SO_RCVBUF", report)

    def test_report_includes_only_allowlisted_runtime_scope(self):
        summary = {
            "schema": 1, "preset": "wifi-screen", "transport": "wifi",
            "batch_status": "COMPLETE", "source_build": {"git_sha": "a" * 40},
            "runs": [{"run_status": "SCREEN_COMPLETE"}],
            "device_context": {
                "target_kind": "physical", "android_release": "14", "sdk": "35",
                "abi": "arm64-v8a", "kernel": "5.15.149-android13-private-decoy",
                "model": "PRIVATE_MODEL_DECOY", "serial": "PRIVATE_SERIAL_DECOY",
                "ssid": "PRIVATE_SSID_DECOY", "carrier": "PRIVATE_CARRIER_DECOY",
            },
        }
        report = operator_tool.render_report(summary)
        for expected in ("Target kind: physical", "Android release: 14", "API level: 35",
                         "ABI: arm64-v8a", "Kernel family: 5.15"):
            self.assertIn(expected, report)
        for decoy in ("PRIVATE_MODEL_DECOY", "PRIVATE_SERIAL_DECOY", "PRIVATE_SSID_DECOY",
                      "PRIVATE_CARRIER_DECOY", "android13-private-decoy"):
            self.assertNotIn(decoy, report)

    def test_run_uses_only_each_current_invocation(self):
        first = self.target(endpoint_address="192.0.2.10", bind_address="192.0.2.20", port=41001)
        second = self.target(endpoint_address="198.51.100.10", bind_address="198.51.100.20", port=42002)
        seen = []
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)

            def run_batch(arguments):
                seen.append(arguments)
                session = root / str(len(seen))
                session.mkdir()
                return session, {
                    "schema": 1, "preset": "wifi-screen", "transport": "wifi",
                    "batch_status": "COMPLETE", "source_build": {"git_sha": "a" * 40},
                    "runs": [{"run_status": "SCREEN_COMPLETE"}],
                }

            with patch.object(operator_tool.batch, "run_batch", side_effect=run_batch), \
                 patch.object(operator_tool, "preflight_command") as preflight, \
                 contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(operator_tool.EXIT_SUCCESS, operator_tool.run_command(first))
                self.assertEqual(operator_tool.EXIT_SUCCESS, operator_tool.run_command(second))
            preflight.assert_not_called()
        self.assertEqual([(item.endpoint_address, item.bind_address, item.port) for item in seen], [
            ("192.0.2.10", "192.0.2.20", 41001),
            ("198.51.100.10", "198.51.100.20", 42002),
        ])
        self.assertTrue(all(item.manifest is None for item in seen))

    def test_report_artifact_references_cannot_expose_raw_paths(self):
        summary = {
            "schema": 1, "preset": "wifi-screen", "transport": "wifi",
            "batch_status": "COMPLETE", "source_build": {"git_sha": "a" * 40},
            "runs": [{"run_status": "SCREEN_COMPLETE"}],
            "capture_path": r"C:\private\raw\capture.pcapng",
            "raw_path": r"output\stage1\session\raw\private.json",
            "report_path": r"C:\private\operator-report.md",
        }
        report = operator_tool.render_report(summary)
        self.assertIn("- redacted-summary.json", report)
        self.assertIn("- operator-report.md", report)
        for private in ("capture.pcapng", "private.json", "C:/private", "raw/"):
            self.assertNotIn(private, report.replace("\\", "/"))

    def test_experiment_exit_codes_do_not_imply_stage_conclusion(self):
        complete = {"batch_status": "COMPLETE", "runs": [{"run_status": "SCREEN_COMPLETE"}]}
        inconclusive = {"batch_status": "COMPLETE", "runs": [{"run_status": "FAILED_OR_INCONCLUSIVE"}]}
        self.assertEqual(operator_tool.EXIT_SUCCESS, operator_tool._experiment_exit(complete))
        self.assertEqual(operator_tool.EXIT_EXPERIMENT_INCONCLUSIVE,
                         operator_tool._experiment_exit(inconclusive))
        self.assertEqual(operator_tool.EXIT_EXPERIMENT_INCONCLUSIVE,
                         operator_tool._experiment_exit({"batch_status": "STOPPED_BY_POLICY", "runs": []}))

    def test_main_exit_codes_for_block_abort_and_tooling_failure(self):
        cases = [
            (operator_tool.BlockedPrerequisite("missing"), operator_tool.EXIT_BLOCKED),
            (KeyboardInterrupt(), operator_tool.EXIT_OWNER_ABORT),
            (ValueError("bad data"), operator_tool.EXIT_TOOLING_FAILURE),
        ]
        for error, expected in cases:
            parsed = argparse.Namespace(handler=Mock(side_effect=error))
            fake_parser = Mock()
            fake_parser.parse_args.return_value = parsed
            with self.subTest(expected=expected), patch.object(operator_tool, "parser", return_value=fake_parser), \
                 contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(expected, operator_tool.main([]))

    def test_report_command_writes_ignored_session_report_and_preserves_exit(self):
        with tempfile.TemporaryDirectory() as temp:
            session = Path(temp) / "session"
            session.mkdir()
            summary = {"schema": 1, "preset": "wifi-screen", "transport": "wifi",
                       "batch_status": "COMPLETE", "source_build": {"git_sha": "a" * 40},
                       "runs": [{"run_status": "FAILED_OR_INCONCLUSIVE"}]}
            with patch.object(operator_tool, "_load_session_summary", return_value=(session, summary)), \
                 contextlib.redirect_stdout(io.StringIO()):
                code = operator_tool.report_command(argparse.Namespace(session=session))
            report = (session / "operator-report.md").read_text(encoding="utf-8")
            self.assertEqual(operator_tool.EXIT_EXPERIMENT_INCONCLUSIVE, code)
            self.assertTrue(report.endswith(operator_tool.REPORT_END + "\n"))


if __name__ == "__main__":
    unittest.main()
