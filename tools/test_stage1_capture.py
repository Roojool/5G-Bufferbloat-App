import io
import json
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import stage1_batch as batch
import stage1_capture as capture


def packet(side, time, **fields):
    # Intentionally fixed synthetic metadata: endpoint is never inferred from order.
    row = {key: "" for key in capture.FIELDS}
    row.update({"frame.time_relative": str(time), "frame.cap_len": "60", "frame.len": "60",
        "tcp.stream": "7", "ip.src": "192.0.2.1" if side == "sender" else "192.0.2.2",
        "ip.dst": "192.0.2.2" if side == "sender" else "192.0.2.1",
        "tcp.srcport": "39001" if side == "sender" else "54321",
        "tcp.dstport": "54321" if side == "sender" else "39001",
        "tcp.flags.syn": "False", "tcp.flags.ack": "True", "tcp.flags.fin": "False",
        "tcp.flags.reset": "False", "tcp.window_size_value": "100", "tcp.len": "0",
        "tcp.seq_raw": "101", "tcp.ack_raw": "1001"})
    row.update({key: str(value) for key, value in fields.items()})
    return row


def handshake():
    return [packet("receiver", 0, **{"tcp.flags.syn": "True", "tcp.flags.ack": "False",
                 "tcp.options.wscale.shift": 2, "tcp.window_size_value": 65535, "tcp.seq_raw": 100}),
            packet("sender", .01, **{"tcp.flags.syn": "True", "tcp.options.wscale.shift": 8,
                 "tcp.seq_raw": 1000, "tcp.ack_raw": 101})]


class TransportTests(unittest.TestCase):
    def summary(self, rows, status="READABLE_TO_EOF", supported=None):
        return capture.summarize(rows, set(capture.FIELDS) if supported is None else supported,
                                 "192.0.2.1", 39001, status, 500)

    def test_window_math_excludes_syn_and_uses_receiver_shift(self):
        result = self.summary(handshake() + [packet("receiver", .02),
                    packet("receiver", .03, **{"tcp.window_size_value": 200}),
                    packet("sender", .04, **{"tcp.window_size_value": 60000})])
        self.assertEqual("NEGOTIATED", result["handshake"]["status"])
        self.assertEqual({"samples": 2, "min": 100, "median": 150, "max": 200}, result["receiver_raw_window"])
        self.assertEqual(600, result["receiver_scaled_rwnd_bytes"]["median"])

    def test_direction_is_metadata_based_even_without_handshake(self):
        result = self.summary([packet("sender", 0, **{"tcp.window_size_value": 5000}), packet("receiver", .1)])
        self.assertEqual(100, result["receiver_raw_window"]["max"])
        self.assertIsNone(result["receiver_scaled_rwnd_bytes"])
        self.assertEqual("PARTIAL", result["status"])

    def test_missing_scale_field_is_not_no_scaling(self):
        result = self.summary(handshake() + [packet("receiver", .02)],
                              supported=set(capture.FIELDS) - {"tcp.options.wscale.shift"})
        self.assertIsNone(result["receiver_scaled_rwnd_bytes"])
        self.assertEqual("PARTIAL", result["status"])

    def test_captured_handshakes_without_scale_offer_use_raw(self):
        rows = handshake()
        rows[0]["tcp.options.wscale.shift"] = ""
        result = self.summary(rows + [packet("receiver", .02)])
        self.assertEqual("NOT_NEGOTIATED", result["handshake"]["status"])
        self.assertEqual(100, result["receiver_scaled_rwnd_bytes"]["max"])

    def test_zero_probe_reopen_ack_progress_and_retransmission_dedup(self):
        rows = handshake() + [packet("receiver", .1, **{"tcp.window_size_value": 0}),
            packet("sender", .2, **{"tcp.analysis.zero_window_probe": "1"}),
            packet("receiver", 2), packet("sender", 2.1, **{"tcp.len": 500,
                "tcp.analysis.bytes_in_flight": 500, "tcp.analysis.retransmission": "1",
                "tcp.analysis.fast_retransmission": "1"}),
            packet("receiver", 2.2, **{"tcp.ack_raw": 1501})]
        r = self.summary(rows)
        self.assertEqual(1, r["receiver_zero_window_frames"])
        self.assertEqual(1, r["sender_zero_window_probe_frames"])
        self.assertEqual(1, r["sender_retransmission_frames"])
        self.assertEqual(500, r["sender_bytes_in_flight_peak"])
        self.assertEqual(500, r["max_acked_sequence_bytes"])
        self.assertTrue(r["expected_payload_ack_observed"])
        episode = r["zero_window_episodes"][0]
        self.assertEqual(2, episode["reopen_s"])
        self.assertEqual(2.1, episode["first_sender_data_after_reopen_s"])
        self.assertEqual(2.2, episode["first_ack_advance_after_reopen_s"])

    def test_truncated_capture_retains_prefix_as_partial_not_benefit(self):
        r = self.summary(handshake() + [packet("receiver", .02)], "PARSE_ERROR_OR_TRUNCATED")
        self.assertEqual("PARTIAL", r["status"])
        self.assertEqual("PARTIAL_WIRE_OBSERVATIONS", r["evidence_layers"]["sender_transport_effect"])
        self.assertEqual(capture.UNVERIFIED, r["evidence_layers"]["physical_benefit"])
        self.assertEqual(capture.UNVERIFIED, r["evidence_layers"]["causal_option_effect"])

    def test_invalid_fields_and_snaplen_are_partial(self):
        bad = packet("receiver", .03, **{"tcp.window_size_value": "garbage"})
        r = self.summary(handshake() + [bad, packet("receiver", .04, **{"frame.cap_len": 40})])
        self.assertEqual(1, r["malformed_rows"])
        self.assertEqual(1, r["snaplen_truncated_packets"])
        self.assertEqual("PARTIAL", r["status"])

    def test_partial_tsv_rows_and_truncated_syn_do_not_invent_scaling(self):
        rows = handshake()
        rows[0]["frame.cap_len"] = "40"
        broken = packet("receiver", .03)
        broken["tcp.window_size_value"] = None
        r = self.summary(rows + [broken, packet("receiver", .04)])
        self.assertEqual(1, r["malformed_rows"])
        self.assertIsNone(r["receiver_scaled_rwnd_bytes"])
        self.assertEqual("PARTIAL", r["status"])

    def test_multiple_connections_and_missing_direction_skip(self):
        other = packet("receiver", 1, **{"tcp.stream": 8})
        self.assertEqual("SKIPPED", self.summary(handshake() + [other])["status"])
        self.assertEqual("SKIPPED", self.summary(handshake(), supported=set())["status"])

    def test_output_is_allowlisted_and_never_contains_identifiers(self):
        rows = handshake() + [packet("receiver", .03)]
        rows[-1].update({"capture_path": "secret.pcapng", "serial": "secret-serial", "eth.src": "de:ad:be:ef:00:00"})
        text = json.dumps(self.summary(rows))
        for private in ["192.0.2.", "54321", "secret", "de:ad", '"tcp.stream"']:
            self.assertNotIn(private, text)

    def test_missing_tool_or_capture_skips(self):
        self.assertEqual("SKIPPED", capture.analyze_capture(None, Path("missing"), "192.0.2.1", 39001, Path("missing"))["status"])

    def test_ipv6_direction_and_absent_analysis_fields(self):
        rows = handshake() + [packet("receiver", .02)]
        for row in rows:
            row["ipv6.src"] = "2001:db8::1" if row.pop("ip.src") == "192.0.2.1" else "2001:db8::2"
            row["ipv6.dst"] = "2001:db8::1" if row.pop("ip.dst") == "192.0.2.1" else "2001:db8::2"
        supported = set(capture.FIELDS) - {"tcp.analysis.zero_window_probe", "tcp.analysis.bytes_in_flight"}
        r = capture.summarize(rows, supported, "2001:db8::1", 39001, "READABLE_TO_EOF", 500)
        self.assertEqual(400, r["receiver_scaled_rwnd_bytes"]["max"])
        self.assertIsNone(r["sender_zero_window_probe_frames"])
        self.assertIsNone(r["sender_bytes_in_flight_peak"])

    def test_readable_file_with_missing_tail_is_partial(self):
        r = self.summary(handshake() + [packet("receiver", .02)])
        self.assertEqual("READABLE_TO_EOF", r["capture_parse"])
        self.assertEqual("PARTIAL", r["status"])
        self.assertFalse(r["expected_payload_ack_observed"])
        self.assertIn("CONNECTION_END_NOT_CAPTURED", r["reasons"])

    def test_complete_wire_coverage_never_claims_benefit(self):
        r = self.summary(handshake() + [packet("receiver", .02, **{"tcp.ack_raw": 1501, "tcp.flags.fin": "1"})])
        self.assertEqual("COMPLETE", r["status"])
        self.assertEqual("OBSERVED_FOR_CAPTURED_PACKETS", r["evidence_layers"]["sender_transport_effect"])
        self.assertEqual(capture.UNVERIFIED, r["evidence_layers"]["physical_benefit"])
        self.assertEqual("UNVERIFIED", r["capture_losslessness"])

    def test_packet_bound_and_sequence_wrap(self):
        rows = handshake()
        rows[1]["tcp.seq_raw"] = str(2**32 - 100)
        rows.append(packet("receiver", .02, **{"tcp.ack_raw": 401}))
        self.assertEqual(500, self.summary(rows)["max_acked_sequence_bytes"])
        with patch.object(capture, "MAX_ROWS", 3):
            r = self.summary(rows + [packet("receiver", .03)])
        self.assertEqual(3, r["owned_packets"])
        self.assertIn("PACKET_BUDGET_EXCEEDED", r["reasons"])

    def test_offline_preserves_original_records_and_writes_separate_redacted_result(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            session = root / "session"
            raw = session / "raw"
            raw.mkdir(parents=True)
            manifest = {"schema": 1, "preset": "test", "transport": "wifi",
                        "variants": [{"id": "baseline", "config": {}}]}
            original = json.dumps({"manifest": manifest, "bind_address": "192.0.2.1", "port": 39001})
            (raw / "input-manifest.private.json").write_text(original)
            summary = json.dumps({"source_build": {"git_sha": "a" * 40}, "serial": "PRIVATE"})
            (session / "redacted-summary.json").write_text(summary)
            with patch.object(batch, "OUTPUT_ROOT", root), patch.object(capture.subprocess, "run", return_value=Mock(returncode=0)), \
                    patch.object(capture, "analyze_capture", return_value=capture.skipped("CAPTURE_MISSING")):
                result = capture.analyze_session(session, None)
            self.assertEqual({"SKIPPED": 1}, result["analysis_status_counts"])
            self.assertEqual(summary, (session / "redacted-summary.json").read_text())
            self.assertEqual(original, (raw / "input-manifest.private.json").read_text())
            derived = (session / "redacted-transport-summary.json").read_text()
            self.assertNotIn("PRIVATE", derived)
            self.assertNotIn("192.0.2.1", derived)

    def test_tshark_error_decodes_prefix_and_preserves_raw_error_privately(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pcap = root / "test.pcapng"
            pcap.write_bytes(b"synthetic")
            def run(args, **kwargs):
                if "-G" in args:
                    return subprocess.CompletedProcess(args, 0, "\n".join("F\tname\t" + f for f in capture.FIELDS), "")
                if "--version" in args:
                    return subprocess.CompletedProcess(args, 0, "TShark (Wireshark) 4.6.8", "")
                rows = handshake() + [packet("receiver", .03)]
                kwargs["stdout"].write("\t".join(capture.FIELDS) + "\n")
                for row in rows:
                    kwargs["stdout"].write("\t".join(row[f] for f in capture.FIELDS) + "\n")
                kwargs["stderr"].write("private-path: truncated file")
                return subprocess.CompletedProcess(args, 2)
            with patch.object(capture.subprocess, "run", side_effect=run):
                result = capture.analyze_capture("tshark", pcap, "192.0.2.1", 39001, root)
            self.assertEqual("PARTIAL", result["status"])
            self.assertEqual(400, result["receiver_scaled_rwnd_bytes"]["max"])
            self.assertNotIn("private-path", json.dumps(result))
            self.assertIn("private-path", (root / "transport-analysis.private.log").read_text())


class ShutdownTests(unittest.TestCase):
    def process(self):
        obj = batch.CaptureProcess.__new__(batch.CaptureProcess)
        obj.stream = io.StringIO()
        obj.shutdown = None
        obj.process = Mock()
        obj.process.poll.return_value = None
        obj.process.wait.return_value = 0
        return obj

    def test_graceful_signal_precedes_wait_and_no_terminate(self):
        p = self.process()
        self.assertEqual(0, p.stop())
        self.assertEqual("send_signal", p.process.method_calls[1][0])
        p.process.terminate.assert_not_called()
        p.process.kill.assert_not_called()
        self.assertEqual("GRACEFUL_SIGNAL", p.shutdown["method"])
        self.assertTrue(p.stream.closed)
        p.stop()
        p.process.send_signal.assert_called_once()

    def test_timeout_uses_terminate_then_kill_fallback(self):
        p = self.process()
        p.process.wait.side_effect = [subprocess.TimeoutExpired("tshark", 10),
                                     subprocess.TimeoutExpired("tshark", 5), 1, 1]
        self.assertEqual(1, p.stop())
        names = [call[0] for call in p.process.method_calls]
        self.assertLess(names.index("send_signal"), names.index("terminate"))
        self.assertLess(names.index("terminate"), names.index("kill"))
        self.assertEqual("KILL_FALLBACK", p.shutdown["method"])

    def test_signal_failure_still_waits_before_fallback(self):
        p = self.process()
        p.process.send_signal.side_effect = OSError("no console")
        p.stop()
        self.assertTrue(p.shutdown["signal_failed"])
        p.process.terminate.assert_not_called()

    def test_windows_break_signal_and_exited_process(self):
        p = self.process()
        with patch.object(batch.os, "name", "nt"), patch.object(batch.signal, "CTRL_BREAK_EVENT", 1, create=True):
            p.stop()
        p.process.send_signal.assert_called_once_with(1)
        p = self.process()
        p.process.poll.return_value = 0
        p.stop()
        p.process.send_signal.assert_not_called()
        p.process.terminate.assert_not_called()

    def test_windows_creation_flags_and_launch_failure_close_stream(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(batch.subprocess, "Popen", side_effect=OSError("launch failed")) as popen:
                with self.assertRaises(OSError):
                    batch.CaptureProcess(["tshark"], Path(temp) / "log")
                if batch.os.name == "nt":
                    self.assertEqual(subprocess.CREATE_NEW_PROCESS_GROUP, popen.call_args.kwargs["creationflags"])
            # Windows permits deletion only if the failed launch closed its log.
            (Path(temp) / "log").unlink()


if __name__ == "__main__":
    unittest.main()
