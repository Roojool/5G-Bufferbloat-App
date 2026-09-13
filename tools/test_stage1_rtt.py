import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import stage1_batch


REPLIES = """PING 192.0.2.1 (192.0.2.1) 56(84) bytes of data.
64 bytes from 192.0.2.1: icmp_seq=1 ttl=64 time=10.0 ms
64 bytes from 192.0.2.1: icmp_seq=2 ttl=64 time=20.0 ms
"""
SUMMARY = """--- 192.0.2.1 ping statistics ---
2 packets transmitted, 2 received, 0% packet loss, time 200ms
rtt min/avg/max/mdev = 10.000/15.000/20.000/5.000 ms
"""


class Stage1RttTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.output = Path(temp.name) / "rtt-load.private.txt"

    def summarize(self, text, code=0, requested=350, reason=None):
        self.output.write_text(text, encoding="utf-8")
        return stage1_batch.ping_summary(self.output, code, requested, stopped_reason=reason)

    def test_complete_output_preserves_normal_results(self):
        result = self.summarize(REPLIES + SUMMARY, requested=2)
        self.assertEqual("RECORDED", result["status"])
        self.assertEqual("NORMAL_COMPLETION", result["completion_reason"])
        self.assertEqual(2, result["requested_samples"])
        self.assertEqual(2, result["observed_replies"])
        self.assertEqual((2, 2), (result["transmitted"], result["received"]))
        self.assertEqual((10, 15, 20), tuple(result[k] for k in ("min_ms", "avg_ms", "max_ms")))

    def test_summary_only_and_three_value_android_summary(self):
        for text in (SUMMARY, SUMMARY.replace("rtt min/avg/max/mdev", "round-trip min/avg/max").replace("/5.000 ms", " ms")):
            with self.subTest(text=text):
                result = self.summarize(text, requested=2)
                self.assertEqual("RECORDED", result["status"])
                self.assertEqual("PING_SUMMARY", result["statistics_source"])
                self.assertEqual(2, result["observed_replies"])

    def test_partial_replies_without_summary_after_transfer_stop(self):
        result = self.summarize(REPLIES, code=-15, reason="TRANSFER_ENDED")
        self.assertEqual("RECORDED_PARTIAL", result["status"])
        self.assertEqual("TRANSFER_ENDED", result["completion_reason"])
        self.assertEqual("REPLY_LINES", result["statistics_source"])
        self.assertEqual(350, result["requested_samples"])
        self.assertEqual(2, result["observed_replies"])
        self.assertEqual((10, 15, 20), tuple(result[k] for k in ("min_ms", "avg_ms", "max_ms")))
        self.assertNotIn("transmitted", result)
        self.assertNotIn("received", result)
        self.assertFalse(any("loss" in key or "missing" in key for key in result))

    def test_intentional_stop_with_partial_summary_retains_observed_counts(self):
        result = self.summarize(REPLIES + SUMMARY, code=0, reason="TRANSFER_ENDED")
        self.assertEqual("RECORDED_PARTIAL", result["status"])
        self.assertEqual(2, result["transmitted"])
        self.assertEqual(350, result["requested_samples"])
        # A normal completion racing with cleanup still keeps its full result.
        result = self.summarize(REPLIES + SUMMARY, requested=2, reason="TRANSFER_ENDED")
        self.assertEqual("RECORDED", result["status"])

    def test_no_usable_replies_or_missing_file_remains_unavailable(self):
        for text in ("", "ping: network unreachable\n",
                     "2 packets transmitted, 0 packets received, 100% packet loss\n"):
            for code in (0, 1, -15):
                with self.subTest(text=text, code=code):
                    result = self.summarize(text, code, reason="TRANSFER_ENDED")
                    self.assertEqual("UNAVAILABLE", result["status"])
                    self.assertEqual(0, result["observed_replies"])
                    self.assertEqual("NO_USABLE_REPLIES", result["observation_reason"])
                    self.assertNotIn("avg_ms", result)
        self.output.unlink()
        self.assertEqual("UNAVAILABLE", stage1_batch.ping_summary(self.output, 1, 350)["status"])

    def test_malformed_lines_do_not_fabricate_samples_or_raise(self):
        bad = "\n".join(f"64 bytes from 192.0.2.1: icmp_seq=3 ttl=64 time={value} ms"
                        for value in ("nan", "inf", "-2", "1.2.3", "9" * 400))
        bad += "\n64 bytes from 192.0.2.1: icmp_seq=4 ttl=64 time<1 ms\n"
        bad += "unrelated time=5 ms\nrtt min/avg/max/mdev = 1../2/3/4 ms\n"
        result = self.summarize(REPLIES + bad, code=-15, reason="TRANSFER_ENDED")
        self.assertEqual(2, result["observed_replies"])
        self.assertEqual(15, result["avg_ms"])

    def test_unexpected_failure_is_not_reclassified_as_transfer_completion(self):
        result = self.summarize(REPLIES + SUMMARY, code=1)
        self.assertEqual("UNAVAILABLE", result["status"])
        self.assertEqual("PING_FAILED", result["completion_reason"])
        for reason in ("OWNER_ABORTED", "RUN_FAILED"):
            self.assertEqual("UNAVAILABLE", self.summarize(REPLIES, code=1, reason=reason)["status"])

    def collector(self, text, poll, returncode):
        process = Mock()
        process.poll.return_value = poll
        process.wait.return_value = returncode
        with patch.object(stage1_batch.subprocess, "Popen", return_value=process):
            collector = stage1_batch.OptionalProcess(["mock-ping"], self.output)
        collector.stream.write(text)
        collector.stream.flush()
        self.addCleanup(collector.stream.close)
        return collector, process

    def test_both_longer_presets_retain_early_stop_replies_through_cleanup(self):
        for preset in ("wifi-efficacy", "cellular-paired"):
            with self.subTest(preset=preset):
                _, settings, _ = stage1_batch.load_manifest(stage1_batch.PRESETS / f"{preset}.json")
                collector, process = self.collector(REPLIES, None, -15)
                result = stage1_batch.load_ping_summary(collector, collector.stop(), settings["rtt"]["load_count"], {"status": "RESULT"})
                self.assertEqual("RECORDED_PARTIAL", result["status"])
                self.assertEqual(350, result["requested_samples"])
                self.assertEqual(2, result["observed_replies"])
                process.terminate.assert_called_once()
                self.assertTrue(collector.stream.closed)

    def test_already_completed_ping_is_not_marked_intentionally_stopped(self):
        collector, process = self.collector(REPLIES + SUMMARY, 0, 0)
        result = stage1_batch.load_ping_summary(collector, collector.stop(), 2, {"status": "RESULT"})
        self.assertEqual("RECORDED", result["status"])
        self.assertFalse(collector.stop_requested)
        process.terminate.assert_not_called()
        self.assertTrue(collector.stream.closed)

    def test_abort_and_host_failure_cleanup_keep_the_actual_reason(self):
        for phone, reason in (({"status": "OWNER_ABORTED"}, "OWNER_ABORTED"), (None, "RUN_FAILED")):
            collector, _ = self.collector(REPLIES, None, 1)
            result = stage1_batch.load_ping_summary(collector, collector.stop(), 350, phone)
            self.assertEqual("UNAVAILABLE", result["status"])
            self.assertEqual(reason, result["completion_reason"])

    def test_bounded_kill_fallback_still_retains_partial_replies(self):
        collector, process = self.collector(REPLIES, None, 1)
        process.wait.side_effect = [subprocess.TimeoutExpired("mock-ping", 5), 1]
        result = stage1_batch.load_ping_summary(collector, collector.stop(), 350, {"status": "RESULT"})
        process.kill.assert_called_once()
        self.assertEqual("RECORDED_PARTIAL", result["status"])
        self.assertTrue(collector.stream.closed)

    def test_redacted_rtt_exports_only_observations_and_no_benefit_claim(self):
        result = self.summarize(REPLIES, code=1, reason="TRANSFER_ENDED")
        record = stage1_batch.redact_private({"rtt": {"under_load": result},
                                              "physical_benefit": stage1_batch.UNVERIFIED})
        text = json.dumps(record)
        self.assertNotIn("192.0.2.1", text)
        self.assertNotIn(str(self.output), text)
        self.assertNotIn("icmp_seq", text)
        self.assertNotIn("tcp_info", text)
        self.assertEqual(stage1_batch.UNVERIFIED, record["physical_benefit"])


if __name__ == "__main__":
    unittest.main()
