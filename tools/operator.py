"""Stateless operator entry point for reviewed Stage 1 physical experiments.

This debug/internal tool wraps stage1_batch and its capture analysis. It never
establishes a production VPN route and never decides Stage 1 or efficacy.

Exit codes: 0 command completed; 2 prerequisite blocked; 3 experiment failed or
inconclusive; 70 tooling/data failure; 130 owner abort.
"""
from __future__ import annotations

# The requested filename shadows Python's standard `operator` when any sibling
# tool is launched directly. In that import mode, expose the built-in operator
# surface and avoid loading the CLI's dependency graph. In normal CLI/test mode,
# preload the real standard module with this directory temporarily absent.
if __name__ == "operator":
    import _operator as _stdlib_operator_surface
    globals().update({name: getattr(_stdlib_operator_surface, name)
                      for name in dir(_stdlib_operator_surface) if not name.startswith("_")})
else:
    import sys as _bootstrap_sys
    if __name__ == "__main__":
        _tool_directory = _bootstrap_sys.path.pop(0)
        import operator as _stdlib_operator  # noqa: F401
        _bootstrap_sys.path.insert(0, _tool_directory)

    import argparse
    import ipaddress
    import json
    import math
    from pathlib import Path
    import re
    import secrets
    import sys
    import time
    from typing import Any, Sequence

    import stage1_batch as batch


REVIEWED_PRESETS = ("wifi-screen", "wifi-efficacy", "cellular-paired", "wifi-paired", "wifi-upload", "cellular-upload")
EXIT_SUCCESS = 0
EXIT_BLOCKED = 2
EXIT_EXPERIMENT_INCONCLUSIVE = 3
EXIT_TOOLING_FAILURE = 70
EXIT_OWNER_ABORT = 130
REPORT_END = "AWAITING_REVIEWER_CONCLUSION"


class BlockedPrerequisite(RuntimeError):
    pass


def validate_target(args: argparse.Namespace) -> None:
    """Validate only values explicitly supplied for this invocation."""
    if not args.confirm_endpoint_bind:
        raise BlockedPrerequisite(
            "confirm this invocation's endpoint, bind address, and port with --confirm-endpoint-bind"
        )
    if args.preset not in REVIEWED_PRESETS:
        raise BlockedPrerequisite("preset is not in the reviewed Stage 1 set")
    expected_transport = "cellular" if args.preset.startswith("cellular-") else "wifi"
    if args.transport != expected_transport:
        raise BlockedPrerequisite("requested transport does not match the reviewed preset")
    try:
        endpoint = ipaddress.ip_address(args.endpoint_address)
        ipaddress.ip_address(args.bind_address)
    except ValueError as exc:
        raise BlockedPrerequisite("endpoint and bind address must be numeric IP literals") from exc
    if "%" in args.endpoint_address or "%" in args.bind_address:
        raise BlockedPrerequisite("scoped IP addresses are unsupported")
    if not 1 <= args.port <= 65535:
        raise BlockedPrerequisite("port must be in 1..65535")
    if args.transport == "cellular" and not endpoint.is_global:
        raise BlockedPrerequisite(
            "cellular batches require an explicitly confirmed globally routable endpoint; private-LAN assumptions are rejected"
        )


def _batch_args(args: argparse.Namespace) -> argparse.Namespace:
    return argparse.Namespace(
        preset=args.preset,
        manifest=None,
        endpoint_address=args.endpoint_address,
        bind_address=args.bind_address,
        port=args.port,
        transport=args.transport,
        serial=args.serial,
        network_ordinal=args.network_ordinal,
        build=args.build,
        install=args.install,
        tshark_path=args.tshark_path,
        tshark_interface=args.tshark_interface,
        no_tshark=args.no_tshark,
        continue_on_failure=getattr(args, "continue_on_failure", False),
        allow_emulator=False,
        source_commit=getattr(args, "source_commit", None),
    )


def _ensure_private_root() -> None:
    relative = (batch.OUTPUT_ROOT / "operator-ignore-check").relative_to(batch.ROOT)
    ignored = batch.command(["git", "check-ignore", "-q", str(relative)], check=False)
    if ignored.returncode:
        raise BlockedPrerequisite("private Stage 1 output root is not git-ignored")


def _probe_current_transport(serial: str, transport: str, ordinal: int,
                             directory: Path) -> dict[str, Any]:
    token = f"operator-{secrets.token_hex(8)}"
    try:
        batch.launch_android(serial, token, token, transport, ordinal, "probe")
        return batch.remote_result(serial, token, 15, directory / "ABORT")
    finally:
        batch.adb(serial, "shell", "run-as", batch.PACKAGE, "rm", "-f",
                  f"files/stage1_batch/{token}.json", check=False)


def preflight_command(args: argparse.Namespace) -> int:
    validate_target(args)
    frozen = getattr(args, "source_commit", None)
    source = batch.git_state(frozen) if frozen else batch.git_state()
    _, settings, _ = batch.load_manifest(batch.PRESETS / f"{args.preset}.json")
    if settings.get("transport") != args.transport:
        raise BlockedPrerequisite("reviewed preset transport changed; operator refuses the mismatch")
    # Discovery is deliberately performed here, every invocation. --serial is
    # accepted only if that device is currently attached and authorized.
    serial = batch.discover_device(args.serial)
    context = batch.device_context(serial, allow_emulator=False)
    _ensure_private_root()
    name = (f"{time.strftime('%Y%m%d-%H%M%S', time.gmtime())}-operator-preflight-"
            f"{source['git_sha'][:8]}-{secrets.token_hex(3)}")
    directory = batch.OUTPUT_ROOT / name
    raw = directory / "raw"
    raw.mkdir(parents=True, exist_ok=False)
    batch.atomic_json(raw / "operator-input.private.json", {
        "preset": args.preset, "transport": args.transport,
        "endpoint_address": args.endpoint_address, "bind_address": args.bind_address,
        "port": args.port, "serial": serial, "network_ordinal": args.network_ordinal,
    })
    build = batch.build_and_verify(serial, source, args.build, args.install, raw)
    capture = batch.resolve_capture_setup(args.bind_address, args.tshark_path,
                                          args.tshark_interface, args.no_tshark)
    probe = _probe_current_transport(serial, args.transport, args.network_ordinal, directory)
    batch.atomic_json(raw / "transport-probe.private.json", probe)
    if probe.get("status") == "CONSENT_REQUIRED":
        raise BlockedPrerequisite(
            f"manual VPN consent is required once: adb shell am start -n {batch.UI_COMPONENT}; choose Prepare, then invoke preflight again"
        )
    if probe.get("status") != "READY":
        raise BlockedPrerequisite(f"requested Android transport is unavailable or ambiguous: {probe.get('status')}")
    summary = batch.redact_private({
        "schema": 1, "status": "READY", "preset": settings["preset"],
        "transport": settings["transport"], "source_build": build,
        "device_context": context, "budgets": settings["budgets"],
        "capture_setup": capture,
        "evidence_boundary": "preflight only; no experiment, Stage conclusion, or efficacy conclusion",
    })
    batch.atomic_json(directory / "preflight-summary.json", summary)
    print(json.dumps(summary, indent=2, sort_keys=True))
    print("Preflight is current-invocation evidence only; repeat it after any environment change.")
    return EXIT_SUCCESS


def _experiment_exit(summary: dict[str, Any]) -> int:
    runs = summary.get("runs")
    if summary.get("batch_status") != "COMPLETE" or not isinstance(runs, list) or not runs:
        return EXIT_EXPERIMENT_INCONCLUSIVE
    if any(run.get("evidence_layers", {}).get("download_topology", "").startswith(("UNSUITABLE", "INCONCLUSIVE")) for run in runs):
        return EXIT_EXPERIMENT_INCONCLUSIVE
    return (EXIT_SUCCESS if all(isinstance(run, dict) and run.get("run_status") == "SCREEN_COMPLETE"
                                for run in runs) else EXIT_EXPERIMENT_INCONCLUSIVE)


def _safe_sha(value: Any) -> str:
    text = value if isinstance(value, str) else ""
    return text if len(text) == 40 and all(character in "0123456789abcdef" for character in text) else "UNAVAILABLE"


def _known(value: Any, allowed: set[str]) -> str:
    return value if isinstance(value, str) and value in allowed else "UNAVAILABLE"


def _number(value: Any) -> str:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return "UNAVAILABLE"
    return str(value) if math.isfinite(value) else "UNAVAILABLE"


def _boolean(value: Any) -> str:
    return str(value).lower() if isinstance(value, bool) else "UNAVAILABLE"


def _runtime_scope(value: Any) -> list[str]:
    """Return only broad, non-identifying fields from redacted device context."""
    context = value if isinstance(value, dict) else {}
    target_kind = _known(context.get("target_kind"), {"physical", "emulator"})
    release_value = context.get("android_release")
    release = (release_value.strip() if isinstance(release_value, str) and
               re.fullmatch(r"[0-9]{1,3}(?:\.[0-9]{1,3}){0,2}", release_value.strip())
               else "UNAVAILABLE")
    sdk_value = context.get("sdk")
    sdk_text = (str(sdk_value).strip()
                if isinstance(sdk_value, (str, int)) and not isinstance(sdk_value, bool) else "")
    api_level = sdk_text if sdk_text.isascii() and sdk_text.isdigit() and 1 <= int(sdk_text) <= 999 else "UNAVAILABLE"
    abi = _known(context.get("abi"), {
        "arm64-v8a", "armeabi-v7a", "x86", "x86_64", "riscv64",
    })
    kernel_value = context.get("kernel")
    kernel_match = (re.match(r"^([0-9]{1,3})\.([0-9]{1,3})(?:[.\-+_]|$)", kernel_value.strip())
                    if isinstance(kernel_value, str) else None)
    kernel_family = (f"{int(kernel_match.group(1))}.{int(kernel_match.group(2))}"
                     if kernel_match else "UNAVAILABLE")
    return [
        f"Target kind: {target_kind}",
        f"Android release: {release}",
        f"API level: {api_level}",
        f"ABI: {abi}",
        f"Kernel family: {kernel_family}",
    ]


def _rtt_line(value: Any) -> str:
    item = value if isinstance(value, dict) else {}
    status = _known(item.get("status"), {
        "RECORDED", "RECORDED_PARTIAL", "UNAVAILABLE", "UNAVAILABLE_TIMEOUT", "SKIPPED",
    })
    completion = _known(item.get("completion_reason"), {
        "NORMAL_COMPLETION", "TRANSFER_ENDED", "PING_FAILED", "RUN_FAILED", "OWNER_ABORTED",
    })
    return (f"status={status}; requested={_number(item.get('requested_samples'))}; "
            f"observed={_number(item.get('observed_replies'))}; "
            f"min/avg/max_ms={_number(item.get('min_ms'))}/{_number(item.get('avg_ms'))}/"
            f"{_number(item.get('max_ms'))}; completion={completion}")


def _window_line(value: Any) -> str:
    item = value if isinstance(value, dict) else {}
    return (f"samples={_number(item.get('samples'))}; min/median/max_bytes="
            f"{_number(item.get('min'))}/{_number(item.get('median'))}/{_number(item.get('max'))}")


def _tcp_info_line(value: Any) -> str:
    samples = value if isinstance(value, list) else []
    bounded = [item for item in samples[:256] if isinstance(item, dict)]
    successes = [item for item in bounded if item.get("tcp_info_errno") == 0]
    lengths = sorted({item["tcp_info_length"] for item in successes
                      if isinstance(item.get("tcp_info_length"), int) and
                      not isinstance(item.get("tcp_info_length"), bool) and item["tcp_info_length"] >= 0})
    known_fields = {
        "state", "rto_us", "snd_mss_bytes", "rcv_mss_bytes", "unacked_segments",
        "retrans_segments", "rtt_us", "rttvar_us", "snd_cwnd_segments", "rcv_rtt_us",
        "rcv_space_bytes", "total_retrans_segments",
    }
    available_values = set()
    for item in successes:
        fields = item.get("fields") if isinstance(item.get("fields"), dict) else {}
        available_values.update(key for key, field in fields.items()
                                if key in known_fields and _number(field) != "UNAVAILABLE")
    available = sorted(available_values)
    return (f"calls={len(bounded)}; successful={len(successes)}; "
            f"returned_lengths={','.join(map(str, lengths)) or 'UNAVAILABLE'}; "
            f"available_fields={','.join(available) or 'UNAVAILABLE'}")


def render_report(summary: dict[str, Any]) -> str:
    """Render a strict allowlist; arbitrary/private source values are ignored."""
    preset = _known(summary.get("preset"), set(REVIEWED_PRESETS))
    transport = _known(summary.get("transport"), {"wifi", "cellular"})
    batch_status = _known(summary.get("batch_status"), {
        "COMPLETE", "STOPPED_BY_POLICY", "OWNER_ABORTED", "RUNNING",
    })
    source = summary.get("source_build") if isinstance(summary.get("source_build"), dict) else {}
    device_context = summary.get("device_context")
    lines = [
        "# Stage 1 operator report",
        "",
        f"Tested build: {_safe_sha(source.get('git_sha'))}",
        f"Preset: {preset}",
        f"Transport requested for this session: {transport}",
        f"Batch execution status: {batch_status}",
        "",
        "This report records bounded experiment observations only. It makes no Stage pass/fail or efficacy conclusion.",
        "TCP_INFO RTT is separate from independent RTT evidence.",
        "",
        "## Runtime scope",
        "",
        *_runtime_scope(device_context),
        "",
        "## Redacted artifacts",
        "",
        "- redacted-summary.json",
        "- operator-report.md",
        "",
        "## Run observations",
        "",
    ]
    run_statuses = {"SCREEN_COMPLETE", "FAILED_OR_INCONCLUSIVE", "AGGREGATE_TIME_BUDGET_EXCEEDED"}
    layer_values = {
        "OBSERVED_SEPARATELY", "UNAVAILABLE", "VERIFIED_FOR_THIS_TRANSFER",
        "FAILED_OR_UNVERIFIED", batch.UNVERIFIED, "OBSERVED_FOR_CAPTURED_PACKETS",
        "PARTIAL_WIRE_OBSERVATIONS",
    }
    capture_values = {
        "COMPLETE", "PARTIAL", "SKIPPED", "RECORDED_PENDING_REVIEW",
        "SKIPPED_TSHARK_DISABLED", "SKIPPED_TSHARK_UNAVAILABLE",
        "SKIPPED_TSHARK_START_FAILED", "UNAVAILABLE",
    }
    runs = summary.get("runs") if isinstance(summary.get("runs"), list) else []
    for index, run in enumerate(runs, 1):
        if not isinstance(run, dict):
            continue
        layers = run.get("evidence_layers") if isinstance(run.get("evidence_layers"), dict) else {}
        rtt = run.get("rtt") if isinstance(run.get("rtt"), dict) else {}
        idle = rtt.get("idle") if isinstance(rtt.get("idle"), dict) else {}
        load = rtt.get("under_load") if isinstance(rtt.get("under_load"), dict) else {}
        capture = run.get("capture") if isinstance(run.get("capture"), dict) else {}
        derived = capture.get("derived") if isinstance(capture.get("derived"), dict) else {}
        phone = run.get("phone") if isinstance(run.get("phone"), dict) else {}
        phone_result = phone.get("result") if isinstance(phone.get("result"), dict) else {}
        lines.extend([
            f"Run {index}:",
            f"- execution: {_known(run.get('run_status'), run_statuses)}",
            f"- phone outcome/bytes/elapsed_ms: {_known(phone_result.get('outcome'), {'COMPLETE', 'EARLY_EOF', 'EXCESS_DATA', 'DEADLINE', 'STALL_TIMEOUT', 'CONNECT_FAILED', 'CANCELLED', 'NATIVE_ERROR'})} / {_number(phone_result.get('bytes'))} / {_number(phone_result.get('elapsed_ms'))}",
            f"- phone errno/close_errno: {_number(phone_result.get('errno'))} / {_number(phone_result.get('close_errno'))}",
            f"- acceptance/readback: {_known(layers.get('acceptance_readback'), layer_values)}",
            f"- TCP_INFO: {_tcp_info_line(phone_result.get('samples'))}",
            f"- integrity: {_known(layers.get('integrity'), layer_values)}",
            f"- recovery: {_known(layers.get('recovery'), layer_values)}",
            f"- sender transport observation: {_known(layers.get('sender_transport_effect'), layer_values)}",
            f"- independent RTT idle: {_rtt_line(idle)}",
            f"- independent RTT load: {_rtt_line(load)}",
            f"- capture analysis: {_known(derived.get('status'), capture_values)}",
            f"- scaled advertised rwnd: {_window_line(derived.get('receiver_scaled_rwnd_bytes'))}",
            f"- zero-window/probe/retransmission observations: {_number(derived.get('receiver_zero_window_frames'))}/{_number(derived.get('sender_zero_window_probe_frames'))}/{_number(derived.get('sender_retransmission_frames'))}",
            f"- sender bytes-in-flight peak: {_number(derived.get('sender_bytes_in_flight_peak'))}",
            f"- physical benefit: {_known(layers.get('physical_benefit'), layer_values)}",
            f"- download baseline topology: {_known(layers.get('download_topology'), {'INCONCLUSIVE_MISSING_BASELINE_LATENCY', 'UNSUITABLE_NO_BASELINE_INFLATION', 'BASELINE_INFLATION_OBSERVED_REVIEW_REQUIRED'})}",
        ])
        if phone_result.get("experiment") == "upload":
            lines.extend(_upload_lines(phone_result))
        options = phone_result.get("options") if isinstance(phone_result.get("options"), list) else []
        for option_index, option in enumerate(options[:20], 1):
            if not isinstance(option, dict):
                continue
            lines.append(
                f"- option {option_index}: kind={_known(option.get('kind'), {'SO_RCVBUF', 'TCP_WINDOW_CLAMP'})}; "
                f"phase={_known(option.get('phase'), {'before_connect', 'after_connect', 'dynamic'})}; "
                f"constant_available={_boolean(option.get('constant_available'))}; "
                f"requested={_number(option.get('requested'))}; set_errno={_number(option.get('set_errno'))}; "
                f"get_errno={_number(option.get('get_errno'))}; returned={_number(option.get('returned'))}; "
                f"returned_length={_number(option.get('returned_length'))}"
            )
        lines.append("")
    if not runs:
        lines.extend(["No run records were available.", ""])
    lines.extend([
        "Human review must use the retained private evidence and canonical experiment criteria.",
        REPORT_END,
    ])
    return "\n".join(lines) + "\n"


def _upload_lines(result: dict[str, Any]) -> list[str]:
    stream = result.get("stream") if isinstance(result.get("stream"), dict) else {}
    plan = result.get("plan") if isinstance(result.get("plan"), dict) else {}
    lines = [f"- F-03 outcome: {_known(result.get('outcome'), {'COMPLETE', 'CANCELLED', 'DEADLINE', 'STALLED', 'PARTIAL_FAILURE', 'PROTECT_FAILED', 'SEND_BUFFER_UNAVAILABLE', 'CONNECT_FAILED', 'RECEIPT_STALLED', 'RECEIPT_FAILED', 'RECEIPT_MALFORMED', 'RECEIPT_EXCESS', 'INTEGRITY_FAILED', 'CLEANUP_FAILED', 'CALLBACK_OR_INTERNAL_FAILURE'})}",
             "- F-03 socket write acceptance is not wire departure; queue samples are not kernel memory allocation."]
    for key in ("per_flow_buffer_bytes", "global_buffer_bytes", "requested_sndbuf_bytes",
                "max_kernel_sndbuf_readback_bytes", "rate_bytes_per_second", "burst_bytes"):
        lines.append(f"- configured {key}: {_number(plan.get(key))}")
    for key in ("allocated_queue_bytes", "read_scratch_bytes", "max_global_queued_bytes", "total_accepted_bytes",
                "total_written_bytes", "write_acceptance_bytes_per_second", "pacing_wait_count", "elapsed_ms"):
        lines.append(f"- observed {key}: {_number(stream.get(key))}")
    for index, flow in enumerate(stream.get("flows", [])[:4]):
        if not isinstance(flow, dict):
            continue
        values = "; ".join(f"{key}={_number(flow.get(key))}" for key in
            ("accepted_bytes", "written_bytes", "undelivered_accepted_bytes", "first_write_at_ms", "completed_at_ms",
             "write_eagain_count", "partial_write_count", "read_backpressure_count", "read_resume_count"))
        lines.append(f"- flow {index}: {values}")
    for index, sock in enumerate(result.get("sockets", [])[:4]):
        if not isinstance(sock, dict):
            continue
        lines.append(f"- socket {index}: receipt={_known(sock.get('receipt_status'), {'COMPLETE', 'UNAVAILABLE'})}; "
                     f"abort_errno={_number(sock.get('abort_errno'))}; close_errno={_number(sock.get('close_errno'))}")
        for option in sock.get("options", [])[:2]:
            if isinstance(option, dict):
                lines.append(f"- SO_SNDBUF readback={_number(option.get('returned'))}; "
                             f"set_errno={_number(option.get('set_errno'))}; get_errno={_number(option.get('get_errno'))}")
    lines.append("- Exact hashes, per-flow live occupancy, socket queues, TCP_INFO and scoped capabilities: redacted-summary.json; review required.")
    return lines


def _load_session_summary(session: Path) -> tuple[Path, dict[str, Any]]:
    resolved = session.resolve()
    root = batch.OUTPUT_ROOT.resolve()
    if not resolved.is_relative_to(root) or not resolved.is_dir():
        raise BlockedPrerequisite("session must be an existing directory below output/stage1")
    if batch.command(["git", "check-ignore", "-q", str(resolved)], check=False).returncode:
        raise BlockedPrerequisite("session must remain under git-ignored output")
    source = resolved / "redacted-summary.json"
    if not source.is_file():
        raise BlockedPrerequisite("session has no redacted Stage 1 summary")
    value = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise ValueError("invalid Stage 1 summary schema")
    return resolved, value


def report_command(args: argparse.Namespace) -> int:
    session, summary = _load_session_summary(args.session)
    report = render_report(summary)
    destination = session / "operator-report.md"
    temporary = destination.with_suffix(".md.tmp")
    temporary.write_text(report, encoding="utf-8")
    temporary.replace(destination)
    print(report, end="")
    return _experiment_exit(summary)


def run_command(args: argparse.Namespace) -> int:
    validate_target(args)
    # run_batch performs fresh Git/device discovery and Android transport probe,
    # then re-resolves the requested Network for every fresh-socket run.
    session, summary = batch.run_batch(_batch_args(args))
    report = render_report(summary)
    destination = session / "operator-report.md"
    temporary = destination.with_suffix(".md.tmp")
    temporary.write_text(report, encoding="utf-8")
    temporary.replace(destination)
    print(report, end="")
    return _experiment_exit(summary)


def _target_arguments(parser: argparse.ArgumentParser, *, run: bool) -> None:
    parser.add_argument("--preset", required=True, choices=REVIEWED_PRESETS)
    parser.add_argument("--transport", required=True, choices=("wifi", "cellular"))
    parser.add_argument("--endpoint-address", required=True,
                        help="numeric endpoint address confirmed for this invocation")
    parser.add_argument("--bind-address", required=True,
                        help="numeric local bind address confirmed for this invocation")
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--confirm-endpoint-bind", action="store_true",
                        help="confirm endpoint, bind address and port for this invocation only")
    parser.add_argument("--serial", help="required on this invocation when multiple devices are attached")
    parser.add_argument("--source-commit", help="full frozen source SHA; clean HEAD must match on this invocation")
    parser.add_argument("--network-ordinal", type=int, default=-1,
                        help="current-session selection when eligible Android Networks are ambiguous")
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--tshark-path")
    parser.add_argument("--tshark-interface")
    parser.add_argument("--no-tshark", action="store_true")
    if run:
        parser.add_argument("--continue-on-failure", action="store_true")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    preflight = commands.add_parser("preflight", help="check this session without transferring experiment data")
    _target_arguments(preflight, run=False)
    preflight.set_defaults(handler=preflight_command)
    run = commands.add_parser("run", help="execute one reviewed Stage 1 preset")
    _target_arguments(run, run=True)
    run.set_defaults(handler=run_command)
    report = commands.add_parser("report", help="render an allowlisted report for one retained session")
    report.add_argument("--session", required=True, type=Path)
    report.set_defaults(handler=report_command)
    return root


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = parser().parse_args(argv)
        return int(args.handler(args))
    except (BlockedPrerequisite, batch.Stage1Error) as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return EXIT_BLOCKED
    except KeyboardInterrupt:
        print("OWNER_ABORTED: retained completed/raw records", file=sys.stderr)
        return EXIT_OWNER_ABORT
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"TOOLING_FAILURE: {type(exc).__name__}", file=sys.stderr)
        return EXIT_TOOLING_FAILURE


if __name__ == "__main__":
    raise SystemExit(main())
