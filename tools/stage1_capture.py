"""Offline, identifier-free transport observations from owned Stage 1 captures.

TShark fields/logs stay private. Redacted output is constructed from numeric
aggregates and fixed statuses, never from arbitrary TShark text or input records.
"""
from __future__ import annotations

import argparse
from collections import Counter
import csv
import hashlib
import ipaddress
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
from typing import Any, Iterable

UNVERIFIED = "UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT"
MAX_CAPTURE_BYTES = 1024 * 1024 * 1024
MAX_ROWS = 200_000
MAX_SPAN_SECONDS = 600
FIELDS = (
    "frame.time_relative", "frame.cap_len", "frame.len", "tcp.stream",
    "ip.src", "ip.dst", "ipv6.src", "ipv6.dst", "tcp.srcport", "tcp.dstport",
    "tcp.flags.syn", "tcp.flags.ack", "tcp.flags.fin", "tcp.flags.reset",
    "tcp.options.wscale.shift", "tcp.window_size_value", "tcp.seq_raw", "tcp.ack_raw",
    "tcp.len", "tcp.analysis.bytes_in_flight", "tcp.analysis.zero_window_probe",
    "tcp.analysis.retransmission", "tcp.analysis.fast_retransmission",
    "tcp.analysis.spurious_retransmission",
)


def number(row: dict[str, str], key: str) -> float | None:
    value = row.get(key, "")
    if value is None or value == "":
        return None
    if value.lower() in ("true", "false"):
        return 1 if value.lower() == "true" else 0
    result = float(value)
    if not math.isfinite(result) or result < 0:
        raise ValueError("invalid numeric field")
    if key != "frame.time_relative" and not result.is_integer():
        raise ValueError("non-integral protocol field")
    return result


def stats(values: list[int]) -> dict[str, Any] | None:
    if not values:
        return None
    return {"samples": len(values), "min": min(values),
            "median": statistics.median(values), "max": max(values)}


def direction(row: dict[str, str], address: str, port: int) -> str | None:
    """Endpoint is the sender; never infer direction from the first packet."""
    src = row.get("ip.src") or row.get("ipv6.src")
    dst = row.get("ip.dst") or row.get("ipv6.dst")
    try:
        source = src and ipaddress.ip_address(src) == ipaddress.ip_address(address) and int(row["tcp.srcport"]) == port
        target = dst and ipaddress.ip_address(dst) == ipaddress.ip_address(address) and int(row["tcp.dstport"]) == port
    except (ValueError, KeyError, TypeError):
        return None
    return "sender" if source and not target else "receiver" if target and not source else None


def evidence(result: dict[str, Any]) -> dict[str, Any]:
    observed = result.get("receiver_raw_window") is not None
    return {
        "sender_transport_effect": ("OBSERVED_FOR_CAPTURED_PACKETS" if result.get("status") == "COMPLETE"
                                    else "PARTIAL_WIRE_OBSERVATIONS") if observed else UNVERIFIED,
        "causal_option_effect": UNVERIFIED,
        "physical_benefit": UNVERIFIED, "cellular_efficacy": UNVERIFIED,
        "integrated_forwarding": UNVERIFIED, "production_readiness": UNVERIFIED,
        "tcp_info_rtt": "separate observation; not independent RTT evidence",
    }


def skipped(reason: str) -> dict[str, Any]:
    result: dict[str, Any] = {"schema": 1, "status": "SKIPPED", "reasons": [reason],
                              "capture_parse": "UNVERIFIED"}
    result["evidence_layers"] = evidence(result)
    return result


def summarize(rows: Iterable[dict[str, str]], supported: set[str], address: str,
              port: int, parse_status: str, expected_bytes: int | None = None) -> dict[str, Any]:
    """Summarize only one owned TCP connection. Missing values remain null."""
    required = {"frame.time_relative", "tcp.stream", "tcp.srcport", "tcp.dstport",
                "tcp.flags.syn", "tcp.flags.ack", "tcp.window_size_value"}
    family_fields = {"ip.src", "ip.dst"} if ipaddress.ip_address(address).version == 4 else {"ipv6.src", "ipv6.dst"}
    if not (required | family_fields) <= supported:
        return skipped("REQUIRED_DIRECTION_OR_WINDOW_FIELDS_UNAVAILABLE")
    packets = []
    flows = set()
    malformed = 0
    reasons: list[str] = []
    for index, row in enumerate(rows):
        if index >= MAX_ROWS:
            reasons.append("PACKET_BUDGET_EXCEEDED")
            break
        side = direction(row, address, port)
        if not side:
            continue
        try:
            # Parse every requested numeric field; duplicate/tunneled values
            # cannot silently masquerade as a single owned connection.
            packet = {key: number(row, key) for key in FIELDS if key in supported and
                      key not in {"ip.src", "ip.dst", "ipv6.src", "ipv6.dst"}}
            if any(packet.get(key) is None for key in required):
                raise ValueError("missing mandatory packet field")
        except (ValueError, TypeError):
            malformed += 1
            continue
        peer = ((row.get("ip.dst") or row.get("ipv6.dst"), row.get("tcp.dstport")) if side == "sender"
                else (row.get("ip.src") or row.get("ipv6.src"), row.get("tcp.srcport")))
        flows.add((packet["tcp.stream"], peer))
        packets.append((side, packet))
    if len(flows) > 1:
        return skipped("AMBIGUOUS_MULTIPLE_OWNED_ENDPOINT_CONNECTIONS")
    if not packets:
        result = skipped("NO_DECODABLE_OWNED_TCP_PACKETS")
        result["capture_parse"] = parse_status
        return result
    if parse_status != "READABLE_TO_EOF":
        reasons.append(parse_status)
    if malformed:
        reasons.append("MALFORMED_PACKET_FIELDS")
    missing = sorted(set(FIELDS) - supported - ({"ipv6.src", "ipv6.dst"} if "ip.src" in family_fields else {"ip.src", "ip.dst"}))
    if missing:
        reasons.append("OPTIONAL_FIELDS_UNAVAILABLE")
    first = min(p["frame.time_relative"] for _, p in packets)
    packets.sort(key=lambda item: item[1]["frame.time_relative"])
    if packets[-1][1]["frame.time_relative"] - first > MAX_SPAN_SECONDS:
        reasons.append("TIMELINE_BUDGET_EXCEEDED")
        packets = [(s, p) for s, p in packets if p["frame.time_relative"] - first <= MAX_SPAN_SECONDS]
    syns = [p for s, p in packets if s == "receiver" and p["tcp.flags.syn"] == 1 and p["tcp.flags.ack"] == 0]
    synacks = [p for s, p in packets if s == "sender" and p["tcp.flags.syn"] == 1 and p["tcp.flags.ack"] == 1]
    # Repeated SYNs must agree. Scaling requires both captured offers; SYN
    # windows are never scaled (RFC 7323). A missing offer disables scaling.
    offers = lambda ps: {p.get("tcp.options.wscale.shift") for p in ps}
    shifts = [offers(syns), offers(synacks)]
    handshake = bool(syns and synacks and all(len(s) == 1 for s in shifts))
    if any(p.get("frame.cap_len") is not None and p.get("frame.len") is not None and
           p["frame.cap_len"] < p["frame.len"] for p in syns + synacks):
        handshake = False
    if handshake and "tcp.seq_raw" in supported and "tcp.ack_raw" in supported:
        handshake = (syns[0].get("tcp.seq_raw") is not None and
                     synacks[0].get("tcp.ack_raw") == (syns[0]["tcp.seq_raw"] + 1) % 2**32)
    receiver_shift = next(iter(shifts[0])) if len(shifts[0]) == 1 else None
    sender_shift = next(iter(shifts[1])) if len(shifts[1]) == 1 else None
    scale = None
    negotiation = "UNVERIFIED_INCOMPLETE_HANDSHAKE"
    if handshake and "tcp.options.wscale.shift" in supported:
        if receiver_shift is None or sender_shift is None:
            scale, negotiation = 1, "NOT_NEGOTIATED"
        elif receiver_shift <= 14 and sender_shift <= 14:
            scale, negotiation = 2 ** int(receiver_shift), "NEGOTIATED"
        else:
            negotiation = "UNVERIFIED_INVALID_SHIFT"
    if scale is None:
        reasons.append("WINDOW_SCALE_UNVERIFIED")
    server_isn = synacks[0].get("tcp.seq_raw") if handshake else None
    raw, scaled, flight = [], [], []
    zero_count = 0
    probe_count = 0 if "tcp.analysis.zero_window_probe" in supported else None
    retrans_fields = {"tcp.analysis.retransmission", "tcp.analysis.fast_retransmission", "tcp.analysis.spurious_retransmission"}
    retrans = 0 if retrans_fields <= supported else None
    timeline: dict[int, dict[str, Any]] = {}
    episodes = []
    pending = None
    max_ack = None
    sender_payload = 0 if "tcp.len" in supported else None
    truncated_packets = 0
    for side, p in packets:
        t = round(p["frame.time_relative"] - first, 6)
        bucket = int(t * 4)
        item = timeline.setdefault(bucket, {"start_s": bucket / 4, "sender_payload_bytes": 0 if sender_payload is not None else None,
                    "receiver_raw_min": None, "receiver_raw_max": None, "receiver_rwnd_min": None,
                    "receiver_rwnd_max": None, "acked_sequence_bytes": None, "window_right_edge_bytes": None})
        if p.get("frame.cap_len") is not None and p.get("frame.len") is not None and p["frame.cap_len"] < p["frame.len"]:
            truncated_packets += 1
        if side == "sender":
            length = p.get("tcp.len")
            if length is not None and sender_payload is not None:
                sender_payload += int(length)
                item["sender_payload_bytes"] += int(length)
                for episode in episodes[-1:]:
                    if length > 0 and episode["first_sender_data_after_reopen_s"] is None:
                        episode["first_sender_data_after_reopen_s"] = t
            if p.get("tcp.analysis.bytes_in_flight") is not None:
                flight.append(int(p["tcp.analysis.bytes_in_flight"]))
            if probe_count is not None and p.get("tcp.analysis.zero_window_probe"):
                probe_count += 1
            if retrans is not None and any(p.get(f) for f in retrans_fields):
                retrans += 1  # count frames once even when multiple flags apply
        if side != "receiver" or p["tcp.flags.syn"] or not p["tcp.flags.ack"] or p.get("tcp.flags.reset"):
            continue
        window = int(p["tcp.window_size_value"])
        if not 0 <= window <= 65535:
            reasons.append("INVALID_RAW_WINDOW")
            continue
        raw.append(window)
        rwnd = window * scale if scale is not None else None
        if rwnd is not None:
            scaled.append(rwnd)
        for key, value, fn in (("receiver_raw_min", window, min), ("receiver_raw_max", window, max),
                               ("receiver_rwnd_min", rwnd, min), ("receiver_rwnd_max", rwnd, max)):
            if value is not None:
                item[key] = value if item[key] is None else fn(item[key], value)
        ack = None
        if server_isn is not None and p.get("tcp.ack_raw") is not None:
            ack = int((p["tcp.ack_raw"] - server_isn - 1) % 2**32)
            if ack > MAX_CAPTURE_BYTES + 1:
                ack = None
            else:
                max_ack = max(max_ack or 0, ack)
                item["acked_sequence_bytes"] = max(item["acked_sequence_bytes"] or 0, ack)
                if rwnd is not None:
                    item["window_right_edge_bytes"] = ack + rwnd
        if window == 0:
            zero_count += 1
            if pending is None:
                pending = {"zero_s": t, "ack_at_zero": ack}
        elif pending is not None:
            episodes.append({"zero_s": pending["zero_s"], "reopen_s": t,
                             "ack_at_zero": pending["ack_at_zero"],
                             "first_sender_data_after_reopen_s": None,
                             "first_ack_advance_after_reopen_s": None})
            pending = None
        for episode in episodes[-1:]:
            if ack is not None and episode["ack_at_zero"] is not None and ack > episode["ack_at_zero"] and episode["first_ack_advance_after_reopen_s"] is None:
                episode["first_ack_advance_after_reopen_s"] = t
    if truncated_packets:
        reasons.append("SNAPLEN_TRUNCATED_PACKETS")
    if not raw:
        reasons.append("NO_RECEIVER_WINDOW_SAMPLES")
    close_seen = any(p.get("tcp.flags.fin") or p.get("tcp.flags.reset") for _, p in packets)
    payload_ack_seen = max_ack >= expected_bytes if max_ack is not None and expected_bytes is not None else None
    if not close_seen:
        reasons.append("CONNECTION_END_NOT_CAPTURED")
    if payload_ack_seen is not True:
        reasons.append("FULL_PAYLOAD_ACK_UNVERIFIED")
    result = {"schema": 1, "status": "PARTIAL" if reasons else "COMPLETE", "reasons": sorted(set(reasons)),
        "capture_parse": parse_status, "capture_losslessness": "UNVERIFIED",
        "missing_fields": missing, "malformed_rows": malformed, "snaplen_truncated_packets": truncated_packets,
        "owned_packets": len(packets), "duration_s": round(packets[-1][1]["frame.time_relative"] - first, 6),
        "handshake": {"receiver_syn_count": len(syns), "sender_synack_count": len(synacks),
            "status": negotiation, "receiver_shift": receiver_shift, "sender_shift": sender_shift},
        "receiver_raw_window": stats(raw), "receiver_scaled_rwnd_bytes": stats(scaled),
        "receiver_zero_window_frames": zero_count, "sender_zero_window_probe_frames": probe_count,
        "sender_bytes_in_flight_peak": max(flight) if flight else None,
        "sender_retransmission_frames": retrans, "sender_payload_bytes_including_retransmissions": sender_payload,
        "max_acked_sequence_bytes": max_ack, "expected_payload_ack_observed": payload_ack_seen,
        "fin_or_rst_observed": close_seen,
        "zero_window_episodes": episodes, "unreopened_zero_window": pending,
        "timeline_origin": "first captured owned packet; separate from phone monotonic clock",
        "timeline_bin_seconds": 0.25, "timeline": list(timeline.values()),
    }
    result["evidence_layers"] = evidence(result)
    return result


def analyze_capture(tshark: str | None, capture: Path, address: str, port: int,
                    private_dir: Path, expected_bytes: int | None = None) -> dict[str, Any]:
    if not tshark:
        return skipped("TSHARK_UNAVAILABLE")
    if not capture.is_file():
        return skipped("CAPTURE_MISSING")
    if capture.stat().st_size > MAX_CAPTURE_BYTES:
        return skipped("CAPTURE_SIZE_BUDGET_EXCEEDED")
    try:
        ipaddress.ip_address(address)
        if not 1 <= port <= 65535:
            return skipped("INVALID_ENDPOINT_METADATA")
        private_dir.mkdir(parents=True, exist_ok=True)
        registry = subprocess.run([tshark, "-G", "fields"], capture_output=True, text=True,
                                  encoding="utf-8", errors="replace", timeout=30)
        if registry.returncode:
            return skipped("FIELD_REGISTRY_UNAVAILABLE")
        version = subprocess.run([tshark, "--version"], capture_output=True, text=True,
                                 encoding="utf-8", errors="replace", timeout=10)
        supported = {parts[2] for line in registry.stdout.splitlines()
                     if len(parts := line.split("\t")) > 2 and parts[0] == "F"}
        selected = [f for f in FIELDS if f in supported]
        family = "ip" if ipaddress.ip_address(address).version == 4 else "ipv6"
        args = [tshark, "-n", "-r", str(capture), "-o", "tcp.analyze_sequence_numbers:TRUE",
                "-Y", f"{family}.addr == {address} && tcp.port == {port}",
                "-T", "fields", "-E", "header=y", "-E", "separator=/t", "-E", "occurrence=a"]
        for field in selected:
            args += ["-e", field]
        table = private_dir / "transport-fields.private.tsv"
        log = private_dir / "transport-analysis.private.log"
        with table.open("w", encoding="utf-8") as output, log.open("w", encoding="utf-8") as errors:
            returncode = None
            try:
                process = subprocess.run(args, stdout=output, stderr=errors, timeout=60)
                returncode = process.returncode
                parse_status = "READABLE_TO_EOF" if process.returncode == 0 else "PARSE_ERROR_OR_TRUNCATED"
            except subprocess.TimeoutExpired:
                parse_status = "ANALYSIS_TIMEOUT"
        if table.stat().st_size > 256 * 1024 * 1024:
            return skipped("FIELD_OUTPUT_BUDGET_EXCEEDED")
        with table.open(encoding="utf-8", errors="replace", newline="") as stream:
            result = summarize(csv.DictReader(stream, delimiter="\t"), supported, address, port, parse_status, expected_bytes)
        digest = hashlib.sha256()
        with capture.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        result["capture_sha256"] = digest.hexdigest()
        match = re.search(r"TShark \(Wireshark\) (\d+\.\d+\.\d+)", version.stdout)
        result["tshark_version"] = match.group(1) if match else None
        result["tshark_returncode"] = returncode
        result["analyzer_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        return result
    except (OSError, subprocess.SubprocessError, ValueError, csv.Error):
        return skipped("ANALYSIS_UNAVAILABLE")


def analyze_session(session: Path, tshark: str | None) -> dict[str, Any]:
    # Import lazily to share discovery/manifest semantics without coupling the
    # pure decoder to Android orchestration or copying its private input.
    import stage1_batch as batch
    session = session.resolve()
    if not session.is_relative_to(batch.OUTPUT_ROOT.resolve()) or not session.is_dir():
        raise ValueError("session must be an existing directory below ignored output/stage1")
    if subprocess.run(["git", "check-ignore", "-q", str(session)], cwd=batch.ROOT).returncode:
        raise ValueError("session is not git-ignored")
    original = json.loads((session / "raw/input-manifest.private.json").read_text(encoding="utf-8"))
    summary = json.loads((session / "redacted-summary.json").read_text(encoding="utf-8"))
    runs, _ = batch.expand_manifest(original["manifest"])
    source = summary.get("source_build", {}).get("git_sha", "")
    result: dict[str, Any] = {"schema": 1, "tested_git_sha": source if len(source) == 40 and all(c in "0123456789abcdef" for c in source) else None,
                              "evidence_layers": evidence({}), "runs": []}
    for run in runs:
        run_dir = session / "raw" / run["run_id"]
        # Never let an unexpected symlink escape the private session directory.
        if not run_dir.resolve().is_relative_to(session):
            raise ValueError("run directory escapes session")
        derived = analyze_capture(tshark, run_dir / "sender.private.pcapng", original["bind_address"],
                                  int(original["port"]), run_dir, int(run["config"]["expectedBytes"]))
        derived["planned_cadence"] = cadence_plan(run["config"])
        try:
            record = json.loads((run_dir / "record.private.json").read_text(encoding="utf-8"))
            integrity = batch.classify_result(record.get("phone"), record.get("endpoint"),
                int(run["config"]["expectedBytes"]), batch.socket_endpoint.expected_hash(int(run["config"]["expectedBytes"])),
                run["config"])["integrity"]
        except (OSError, ValueError, TypeError):
            integrity = "FAILED_OR_UNVERIFIED"
        batch.atomic_json(run_dir / "transport-derived.private.json", derived)
        result["runs"].append({"run_id": run["run_id"], "recorded_transfer_integrity": integrity,
                               "transport": derived})
    result["analysis_status_counts"] = dict(Counter(r["transport"]["status"] for r in result["runs"]))
    # Separate derived summary preserves the original experiment records exactly.
    batch.atomic_json(session / "redacted-transport-summary.json", result)
    return result


def cadence_plan(config: dict[str, Any]) -> dict[str, Any]:
    return {"initial_ms": int(config.get("cadenceMs", 0)),
            "changes": [{"planned_at_ms": int(c["atMs"]), "cadence_ms": int(c["cadenceMs"])}
                        for c in config.get("changes", []) if "cadenceMs" in c],
            "alignment": "planned phone-relative times; exact capture clock alignment UNVERIFIED"}


def main() -> int:
    import stage1_batch as batch
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", type=Path, required=True)
    parser.add_argument("--tshark-path")
    args = parser.parse_args()
    try:
        executable = batch.discover_tshark(args.tshark_path)
        result = analyze_session(args.session, executable.get("tshark_path"))
    except (OSError, ValueError, KeyError, batch.Stage1Error):
        print("SKIPPED: session metadata unavailable or invalid; original records preserved")
        return 2
    print("Offline transport analysis: " + json.dumps(result["analysis_status_counts"], sort_keys=True))
    print("Physical benefit: " + UNVERIFIED)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
