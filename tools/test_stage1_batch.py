import copy
import json
from pathlib import Path
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
                   "capture_path": "x.pcapng", "safe": 7}], "physical_benefit": stage1_batch.UNVERIFIED}
        redacted = stage1_batch.redact_private(private)
        text = json.dumps(redacted)
        self.assertNotIn("192.0.2.1", text)
        self.assertNotIn("secret", text)
        self.assertNotIn("x.pcapng", text)
        self.assertEqual(7, redacted["nested"][0]["safe"])
        self.assertEqual(stage1_batch.UNVERIFIED, redacted["physical_benefit"])

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
