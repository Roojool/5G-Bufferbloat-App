"""Host orchestrator for the debug-only Stage 1 protected-socket harness.

This tool drives owned test traffic only. It does not establish a VPN route,
proxy application traffic, analyze payloads, or promote measurements to efficacy.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import random
import re
import shutil
import subprocess
import sys
import threading
import time
from typing import Any, Callable

import socket_endpoint

ROOT = Path(__file__).resolve().parents[1]
PRESETS = ROOT / "tools" / "stage1_presets"
OUTPUT_ROOT = ROOT / "output" / "stage1"
PACKAGE = "com.bufferbloatshaper"
RUN_COMPONENT = f"{PACKAGE}/.harness.BatchHarnessActivity"
UI_COMPONENT = f"{PACKAGE}/.harness.HarnessActivity"
CANCEL_COMPONENT = RUN_COMPONENT
RUN_ACTION = "com.bufferbloatshaper.harness.RUN_BATCH_EXPERIMENT"
CANCEL_ACTION = "com.bufferbloatshaper.harness.CANCEL_BATCH_EXPERIMENT"
UNVERIFIED = "UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT"
MAX_RUN_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024
MAX_RUN_SECONDS = 120
MAX_TOTAL_SECONDS = 3600
TOKEN = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
PRIVATE_KEYS = {
    "address", "endpoint_address", "bind_address", "port", "serial", "device_id",
    "network_handle", "network_ordinal", "capture_path", "capture_interface", "pcap",
    "credential", "credentials", "location",
}


class Stage1Error(RuntimeError):
    pass


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def command(args: list[str], timeout: float = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        detail = (result.stderr or result.stdout).strip().splitlines()
        raise Stage1Error(f"command failed ({args[0]}): {detail[-1] if detail else result.returncode}")
    return result


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def redact_private(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: redact_private(item) for key, item in value.items() if key.lower() not in PRIVATE_KEYS}
    if isinstance(value, list):
        return [redact_private(item) for item in value]
    return value


def _config(config: dict[str, Any]) -> dict[str, Any]:
    allowed = {"expectedBytes", "receiveBuffer", "clamp", "cadenceMs", "readBytes",
               "changes", "durationMs", "stallTimeoutMs"}
    if set(config) - allowed:
        raise Stage1Error(f"unknown experiment keys: {sorted(set(config) - allowed)}")
    result = dict(config)
    result.setdefault("expectedBytes", 16 * 1024 * 1024)
    result.setdefault("durationMs", 60_000)
    result.setdefault("stallTimeoutMs", 10_000)
    result.setdefault("cadenceMs", 0)
    result.setdefault("readBytes", 16_384)
    if not 1 <= int(result["expectedBytes"]) <= MAX_RUN_BYTES:
        raise Stage1Error("expectedBytes outside harness bounds")
    if not 1_000 <= int(result["durationMs"]) <= MAX_RUN_SECONDS * 1000:
        raise Stage1Error("durationMs outside harness bounds")
    if not 500 <= int(result["stallTimeoutMs"]) <= 30_000:
        raise Stage1Error("stallTimeoutMs outside harness bounds")
    if not 0 <= int(result["cadenceMs"]) <= 2_000 or not 1 <= int(result["readBytes"]) <= 16_384:
        raise Stage1Error("read cadence/chunk outside harness bounds")
    for key in ("receiveBuffer", "clamp"):
        if key in result and not 1 <= int(result[key]) <= 4_194_304:
            raise Stage1Error(f"{key} outside harness bounds; zero is not restoration")
    changes = result.get("changes", [])
    if not isinstance(changes, list) or len(changes) > 8:
        raise Stage1Error("too many dynamic changes")
    previous = -1
    for change in changes:
        at = int(change.get("atMs", -1))
        if at <= previous or not 1 <= at < int(result["durationMs"]):
            raise Stage1Error("dynamic change times must be ordered and within the run")
        previous = at
        for key in ("receiveBuffer", "clamp"):
            if key in change and not 1 <= int(change[key]) <= 4_194_304:
                raise Stage1Error(f"dynamic {key} outside harness bounds")
        if "cadenceMs" in change and not 0 <= int(change["cadenceMs"]) <= 2_000:
            raise Stage1Error("dynamic cadence outside harness bounds")
    return result


def expand_manifest(document: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    if document.get("schema") != 1 or not TOKEN.match(str(document.get("preset", ""))):
        raise Stage1Error("manifest schema/preset invalid")
    transport = document.get("transport")
    if transport not in ("wifi", "cellular"):
        raise Stage1Error("transport must be wifi or cellular")
    runs: list[dict[str, Any]] = []
    if "variants" in document:
        for item in document["variants"]:
            variant = str(item.get("id", ""))
            if not TOKEN.match(variant):
                raise Stage1Error("invalid variant id")
            runs.append({"run_id": f"{document['preset']}-{variant}", "variant": variant,
                         "config": _config(item.get("config", {}))})
    elif "paired" in document:
        paired = document["paired"]
        repetitions = int(paired.get("repetitions", 0))
        if not 1 <= repetitions <= 20:
            raise Stage1Error("paired repetitions outside 1..20")
        seed = int(paired["seed"])
        candidate_id = str(paired.get("candidate_id", "candidate"))
        if not TOKEN.match(candidate_id):
            raise Stage1Error("invalid candidate id")
        baseline = _config(paired["baseline"])
        candidate = _config(paired["candidate"])
        rng = random.Random(seed)
        for pair in range(1, repetitions + 1):
            ordered = [("baseline", baseline), (candidate_id, candidate)]
            rng.shuffle(ordered)
            for order, (variant, config) in enumerate(ordered, 1):
                runs.append({"run_id": f"{document['preset']}-p{pair:02d}-o{order}-{variant}",
                             "variant": variant, "pair": pair, "order": order,
                             "config": dict(config)})
    else:
        raise Stage1Error("manifest needs variants or paired")
    if not runs or len({run["run_id"] for run in runs}) != len(runs):
        raise Stage1Error("run IDs must be unique")
    if any(not TOKEN.match(run["run_id"]) for run in runs):
        raise Stage1Error("expanded run ID invalid")
    rtt = document.get("rtt", {"method": "none"})
    if rtt.get("method") not in ("none", "adb_shell_ping"):
        raise Stage1Error("unsupported independent RTT method")
    if rtt.get("method") == "adb_shell_ping":
        interval = int(rtt.get("interval_ms", 0))
        idle_count = int(rtt.get("idle_count", 0))
        load_count = int(rtt.get("load_count", 0))
        if not 100 <= interval <= 5_000 or not 0 <= idle_count <= 600 or not 0 <= load_count <= 600:
            raise Stage1Error("RTT interval/count outside bounded range")
        if idle_count * interval > 30_000 or load_count * interval > MAX_RUN_SECONDS * 1000:
            raise Stage1Error("RTT collection exceeds per-run time bounds")
        rtt = {"method": "adb_shell_ping", "interval_ms": interval,
               "idle_count": idle_count, "load_count": load_count}
    budgets = document.get("budgets", {})
    max_run_bytes = min(int(budgets.get("max_run_bytes", MAX_RUN_BYTES)), MAX_RUN_BYTES)
    max_total_bytes = min(int(budgets.get("max_total_bytes", MAX_TOTAL_BYTES)), MAX_TOTAL_BYTES)
    max_run_seconds = min(int(budgets.get("max_run_seconds", MAX_RUN_SECONDS)), MAX_RUN_SECONDS)
    max_total_seconds = min(int(budgets.get("max_total_seconds", MAX_TOTAL_SECONDS)), MAX_TOTAL_SECONDS)
    if min(max_run_bytes, max_total_bytes, max_run_seconds, max_total_seconds) <= 0:
        raise Stage1Error("manifest budgets must be positive")
    total_bytes = sum(int(run["config"]["expectedBytes"]) for run in runs)
    idle_seconds = (int(rtt.get("idle_count", 0)) * int(rtt.get("interval_ms", 0)) / 1000
                    if rtt.get("method") == "adb_shell_ping" else 0)
    total_seconds = sum(int(run["config"]["durationMs"]) / 1000 + idle_seconds for run in runs)
    if any(int(run["config"]["expectedBytes"]) > max_run_bytes for run in runs) or total_bytes > max_total_bytes:
        raise Stage1Error("planned byte budget exceeded")
    if any(int(run["config"]["durationMs"]) > max_run_seconds * 1000 for run in runs) or total_seconds > max_total_seconds:
        raise Stage1Error("planned time budget exceeded")
    policy = document.get("failure_policy", "stop")
    if policy not in ("stop", "continue"):
        raise Stage1Error("failure_policy must be stop or continue")
    settings = {"preset": document["preset"], "transport": transport, "budgets": {
        "max_run_bytes": max_run_bytes, "max_total_bytes": max_total_bytes,
        "max_run_seconds": max_run_seconds, "max_total_seconds": max_total_seconds,
        "planned_bytes": total_bytes, "planned_seconds": total_seconds,
    }, "failure_policy": policy, "rtt": rtt,
       "pair_seed": document.get("paired", {}).get("seed")}
    return runs, settings


def load_manifest(path: Path) -> tuple[list[dict[str, Any]], dict[str, Any], dict[str, Any]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    runs, settings = expand_manifest(document)
    return runs, settings, document


def classify_result(phone: dict[str, Any] | None, endpoint: dict[str, Any] | None,
                    expected_bytes: int, expected_hash: str, variant: dict[str, Any]) -> dict[str, Any]:
    result = (phone or {}).get("result") if (phone or {}).get("status") == "RESULT" else None
    phone_match = bool(result and result.get("outcome") == "COMPLETE" and
                       result.get("bytes") == expected_bytes and result.get("sha256") == expected_hash)
    endpoint_match = bool(endpoint and endpoint.get("outcome") == "COMPLETE" and
                          endpoint.get("accepted_bytes") == expected_bytes and endpoint.get("sha256") == expected_hash)
    option_ok = True
    required = []
    if "receiveBuffer" in variant:
        required.append(("SO_RCVBUF", variant["receiveBuffer"]))
    if "clamp" in variant:
        required.append(("TCP_WINDOW_CLAMP", variant["clamp"]))
    for kind, requested in required:
        records = [item for item in (result or {}).get("options", [])
                   if item.get("phase") == "before_connect" and item.get("kind") == kind and item.get("requested") == requested]
        option_ok = option_ok and bool(records and records[0].get("set_errno") == 0)
    passed = phone_match and endpoint_match and option_ok
    return {
        "run_status": "SCREEN_COMPLETE" if passed else "FAILED_OR_INCONCLUSIVE",
        "acceptance_readback": "OBSERVED_SEPARATELY" if result else "UNAVAILABLE",
        "sender_transport_effect": UNVERIFIED,
        "integrity": "VERIFIED_FOR_THIS_TRANSFER" if phone_match and endpoint_match else "FAILED_OR_UNVERIFIED",
        "recovery": UNVERIFIED,
        "physical_benefit": UNVERIFIED,
        "option_variant_accepted": option_ok,
    }


def execute_sequence(runs: list[dict[str, Any]], execute: Callable[[dict[str, Any]], dict[str, Any]],
                     continue_on_failure: bool) -> list[dict[str, Any]]:
    results = []
    for run in runs:
        result = execute(run)
        results.append(result)
        if result.get("run_status") != "SCREEN_COMPLETE" and not continue_on_failure:
            break
    return results


class EndpointProcess:
    def __init__(self, bind: str, port: int, count: int, timeout: int, log_path: Path):
        self.lines: list[str] = []
        self.ready = threading.Event()
        self.log_path = log_path
        self.process = subprocess.Popen(
            [sys.executable, str(ROOT / "tools" / "socket_endpoint.py"), "--bind", bind,
             "--port", str(port), "--bytes", str(count), "--connections", "1", "--timeout", str(timeout)],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            self.lines.append(line.rstrip("\r\n"))
            try:
                if json.loads(line).get("status") == "awaiting_owner_connection":
                    self.ready.set()
            except (json.JSONDecodeError, AttributeError):
                pass

    def wait_ready(self, seconds: float = 5) -> None:
        if not self.ready.wait(seconds) or self.process.poll() is not None:
            self.stop()
            raise Stage1Error("owner endpoint did not become ready")

    def final(self) -> dict[str, Any] | None:
        for line in reversed(self.lines):
            try:
                item = json.loads(line)
                if "accepted_bytes" in item and "outcome" in item:
                    return item
            except json.JSONDecodeError:
                continue
        return None

    def stop(self) -> None:
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(3)
        self.reader.join(2)
        if self.process.stdout is not None:
            self.process.stdout.close()
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self.log_path.write_text("\n".join(self.lines) + ("\n" if self.lines else ""), encoding="utf-8")


class OptionalProcess:
    def __init__(self, args: list[str], output: Path):
        self.output = output
        self.stream = output.open("w", encoding="utf-8")
        self.process = subprocess.Popen(args, cwd=ROOT, stdout=self.stream, stderr=subprocess.STDOUT, text=True)

    def stop(self, interrupt: bool = True) -> int:
        if self.process.poll() is None and interrupt:
            self.process.terminate()
        try:
            code = self.process.wait(5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            code = self.process.wait(3)
        self.stream.close()
        return code


def adb(serial: str, *args: str, timeout: float = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
    return command(["adb", "-s", serial, *args], timeout=timeout, check=check)


def discover_device(selected: str | None) -> str:
    result = command(["adb", "devices"], timeout=15)
    devices = [line.split()[0] for line in result.stdout.splitlines()[1:] if "\tdevice" in line]
    if selected:
        if selected not in devices:
            raise Stage1Error("requested ADB device is not attached and authorized")
        return selected
    if len(devices) != 1:
        raise Stage1Error(f"expected exactly one authorized ADB device, found {len(devices)}; use --serial")
    return devices[0]


def device_context(serial: str, allow_emulator: bool) -> dict[str, Any]:
    def prop(name: str) -> str:
        return adb(serial, "shell", "getprop", name, timeout=15).stdout.strip()
    emulator = prop("ro.kernel.qemu") == "1"
    if emulator and not allow_emulator:
        raise Stage1Error("attached target is an emulator; physical batches require hardware")
    return {"android_release": prop("ro.build.version.release"), "sdk": prop("ro.build.version.sdk"),
            "abi": prop("ro.product.cpu.abi"), "kernel": adb(serial, "shell", "uname", "-r", timeout=15).stdout.strip(),
            "target_kind": "emulator" if emulator else "physical"}


def git_state() -> dict[str, str]:
    dirty = command(["git", "status", "--porcelain=v1"], timeout=15).stdout.strip()
    if dirty:
        raise Stage1Error("working tree is not clean; commit/stash unrelated work before physical evidence")
    command(["git", "fetch", "origin", "main"], timeout=60)
    head = command(["git", "rev-parse", "HEAD"]).stdout.strip()
    main = command(["git", "rev-parse", "origin/main"]).stdout.strip()
    ancestry = command(["git", "merge-base", "--is-ancestor", main, head], check=False)
    if ancestry.returncode:
        raise Stage1Error("current checkout does not contain current origin/main")
    return {"git_sha": head, "origin_main_sha": main, "working_tree": "clean"}


def build_and_verify(serial: str, source: dict[str, str], build: bool, install: bool,
                     raw_dir: Path) -> dict[str, str]:
    apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    provenance = OUTPUT_ROOT / "build-provenance" / f"{source['git_sha']}.json"
    if build or install:
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command([str(wrapper), "--no-daemon", ":app:assembleDebug"], timeout=900)
        if not apk.is_file():
            raise Stage1Error("debug APK missing after build")
        built_hash = sha256_file(apk)
        atomic_json(provenance, {**source, "apk_sha256": built_hash})
    elif not provenance.is_file():
        raise Stage1Error("no current-commit build provenance; rerun with --build --install")
    record = json.loads(provenance.read_text(encoding="utf-8"))
    if record.get("git_sha") != source["git_sha"] or not apk.is_file() or sha256_file(apk) != record.get("apk_sha256"):
        raise Stage1Error("local APK does not match current clean commit provenance; rerun with --build")
    if install:
        adb(serial, "install", "-r", str(apk), timeout=180)
    package_path = adb(serial, "shell", "pm", "path", PACKAGE, timeout=30, check=False)
    paths = [line.removeprefix("package:").strip() for line in package_path.stdout.splitlines() if line.startswith("package:")]
    if len(paths) != 1:
        raise Stage1Error("debug package is absent or split unexpectedly; rerun with --install")
    pulled = raw_dir / "installed-base.apk"
    adb(serial, "pull", paths[0], str(pulled), timeout=180)
    installed_hash = sha256_file(pulled)
    pulled.unlink()
    if installed_hash != record["apk_sha256"]:
        raise Stage1Error("installed APK differs from current clean build; rerun with --install")
    return {"git_sha": source["git_sha"], "origin_main_sha": source["origin_main_sha"],
            "apk_sha256": installed_hash, "provenance": "installed_apk_matches_current_clean_build"}


def remote_result(serial: str, token: str, timeout_seconds: float, abort_file: Path) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if abort_file.exists():
            raise KeyboardInterrupt()
        result = adb(serial, "exec-out", "run-as", PACKAGE, "cat", f"files/stage1_batch/{token}.json",
                     timeout=10, check=False)
        if result.returncode == 0 and result.stdout.strip():
            try:
                value = json.loads(result.stdout)
                if value.get("status") not in ("STARTING",):
                    return value
            except json.JSONDecodeError:
                pass
        time.sleep(0.2)
    raise Stage1Error("Android command result timed out")


def launch_android(serial: str, run_id: str, token: str, transport: str, ordinal: int,
                   mode: str, address: str | None = None, port: int | None = None,
                   config: dict[str, Any] | None = None) -> None:
    adb(serial, "shell", "run-as", PACKAGE, "rm", "-f", f"files/stage1_batch/{token}.json", check=False)
    args = ["shell", "am", "start", "-W", "-a", RUN_ACTION, "-n", RUN_COMPONENT,
            "--es", "mode", mode, "--es", "run_id", run_id, "--es", "result_token", token,
            "--es", "transport", transport, "--ei", "network_ordinal", str(ordinal)]
    if config is not None and address is not None and port is not None:
        encoded = base64.urlsafe_b64encode(json.dumps(config, separators=(",", ":")).encode()).decode()
        args += ["--es", "address", address, "--ei", "port", str(port), "--es", "config_b64", encoded]
    adb(serial, *args, timeout=30)


def cancel_android(serial: str, run_id: str) -> None:
    adb(serial, "shell", "am", "start", "-W", "-a", CANCEL_ACTION, "-n", CANCEL_COMPONENT,
        "--es", "run_id", run_id, timeout=15, check=False)


def ping_command(serial: str, address: str, count: int, interval_ms: int) -> list[str]:
    return ["adb", "-s", serial, "shell", "ping", "-n", "-c", str(count), "-i", f"{interval_ms / 1000:.3f}", address]


def ping_summary(path: Path, returncode: int | None) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""
    packets = re.search(r"(\d+) packets transmitted, (\d+) (?:packets )?received", text)
    timing = re.search(r"=\s*([0-9.]+)/([0-9.]+)/([0-9.]+)/(?:[0-9.]+)\s*ms", text)
    result: dict[str, Any] = {"method": "adb_shell_ping_round_trip", "status": "RECORDED" if returncode == 0 else "UNAVAILABLE"}
    if packets:
        result.update({"transmitted": int(packets.group(1)), "received": int(packets.group(2))})
    if timing:
        result.update({"min_ms": float(timing.group(1)), "avg_ms": float(timing.group(2)), "max_ms": float(timing.group(3))})
    return result


def run_batch(args: argparse.Namespace) -> tuple[Path, dict[str, Any]]:
    source = git_state()
    manifest_path = Path(args.manifest) if args.manifest else PRESETS / f"{args.preset}.json"
    runs, settings, original = load_manifest(manifest_path)
    if args.transport and args.transport != settings["transport"]:
        raise Stage1Error("--transport must match the manifest transport")
    serial = discover_device(args.serial)
    context = device_context(serial, args.allow_emulator)
    session_name = f"{time.strftime('%Y%m%d-%H%M%S', time.gmtime())}-{settings['preset']}-{source['git_sha'][:8]}"
    session = OUTPUT_ROOT / session_name
    raw = session / "raw"
    ignored = command(["git", "check-ignore", "-q", str((OUTPUT_ROOT / "ignore-check").relative_to(ROOT))],
                      check=False)
    if ignored.returncode != 0:
        raise Stage1Error("private output root is not covered by .gitignore")
    raw.mkdir(parents=True, exist_ok=False)
    print(f"Session directory: {session}", flush=True)
    atomic_json(raw / "input-manifest.private.json", {"manifest": original, "endpoint_address": args.endpoint_address,
                                                       "bind_address": args.bind_address, "port": args.port})
    build = build_and_verify(serial, source, args.build, args.install, raw)
    probe = f"probe-{int(time.time())}"
    launch_android(serial, probe, probe, settings["transport"], args.network_ordinal, "probe")
    probe_result = remote_result(serial, probe, 15, session / "ABORT")
    atomic_json(raw / "probe.json", probe_result)
    if probe_result.get("status") == "CONSENT_REQUIRED":
        raise Stage1Error(f"manual VPN consent required once: adb shell am start -n {UI_COMPONENT}; choose Prepare, then rerun")
    if probe_result.get("status") != "READY":
        raise Stage1Error(f"transport preflight failed: {probe_result.get('status')}")
    summary: dict[str, Any] = {"schema": 1, "preset": settings["preset"], "transport": settings["transport"],
        "source_build": build, "device_context": context, "budgets": settings["budgets"],
        "failure_policy": "continue" if args.continue_on_failure else settings["failure_policy"],
        "pair_seed": settings["pair_seed"], "evidence_boundary": {
            "acceptance_readback": "reported separately per run",
            "sender_transport_effect": UNVERIFIED, "integrity_recovery": "split per run",
            "physical_benefit": UNVERIFIED, "tcp_info_rtt": "not independent RTT evidence",
        }, "runs": [], "batch_status": "RUNNING"}
    atomic_json(session / "redacted-summary.json", redact_private(summary))
    started = time.monotonic()
    continue_policy = args.continue_on_failure or settings["failure_policy"] == "continue"

    def one(run: dict[str, Any]) -> dict[str, Any]:
        if time.monotonic() - started >= settings["budgets"]["max_total_seconds"]:
            return {"run_id": run["run_id"], "variant": run["variant"], "run_status": "AGGREGATE_TIME_BUDGET_EXCEEDED"}
        run_dir = raw / run["run_id"]
        run_dir.mkdir(parents=True)
        config = run["config"]
        expected_bytes = int(config["expectedBytes"])
        expected_hash = socket_endpoint.expected_hash(expected_bytes)
        rtt = settings.get("rtt", {})
        idle_path = run_dir / "rtt-idle.private.txt"
        idle_result = {"method": "none", "status": "SKIPPED"}
        if rtt.get("method") == "adb_shell_ping" and int(rtt.get("idle_count", 0)) > 0:
            try:
                idle = command(ping_command(serial, args.endpoint_address, int(rtt["idle_count"]), int(rtt["interval_ms"])),
                               timeout=max(15, int(rtt["idle_count"]) * int(rtt["interval_ms"]) / 1000 + 10), check=False)
                idle_path.write_text(idle.stdout + idle.stderr, encoding="utf-8")
                idle_result = ping_summary(idle_path, idle.returncode)
            except subprocess.TimeoutExpired:
                idle_path.write_text("ping collection timed out\n", encoding="utf-8")
                idle_result = {"method": "adb_shell_ping_round_trip", "status": "UNAVAILABLE_TIMEOUT"}
        capture: OptionalProcess | None = None
        capture_status = "SKIPPED_NOT_REQUESTED"
        if args.tshark_interface:
            tshark = shutil.which("tshark")
            if tshark:
                try:
                    capture = OptionalProcess([tshark, "-i", args.tshark_interface, "-f",
                        f"tcp port {args.port} and host {args.bind_address}", "-w", str(run_dir / "sender.private.pcapng"), "-q"],
                        run_dir / "tshark.private.log")
                    time.sleep(0.5)
                    if capture.process.poll() is None:
                        capture_status = "RECORDED_PENDING_REVIEW"
                    else:
                        capture.stop(interrupt=False); capture = None
                        capture_status = "SKIPPED_TSHARK_START_FAILED"
                except OSError:
                    capture_status = "SKIPPED_TSHARK_START_FAILED"
            else:
                capture_status = "SKIPPED_TSHARK_UNAVAILABLE"
        endpoint = EndpointProcess(args.bind_address, args.port, expected_bytes,
                                   min(300, int(config["durationMs"]) // 1000 + 30), run_dir / "endpoint.private.jsonl")
        load_ping: OptionalProcess | None = None
        phone: dict[str, Any] | None = None
        endpoint_result: dict[str, Any] | None = None
        aborted = False
        token = f"r-{hashlib.sha256((session_name + run['run_id']).encode()).hexdigest()[:16]}"
        try:
            endpoint.wait_ready()
            if rtt.get("method") == "adb_shell_ping" and int(rtt.get("load_count", 0)) > 0:
                load_ping = OptionalProcess(ping_command(serial, args.endpoint_address, int(rtt["load_count"]), int(rtt["interval_ms"])),
                                            run_dir / "rtt-load.private.txt")
            launch_android(serial, run["run_id"], token, settings["transport"], args.network_ordinal,
                           "run", args.endpoint_address, args.port, config)
            phone = remote_result(serial, token, int(config["durationMs"]) / 1000 + 15, session / "ABORT")
            atomic_json(run_dir / "phone.private.json", phone)
            try:
                endpoint.process.wait(min(15, int(config["stallTimeoutMs"]) / 1000 + 5))
            except subprocess.TimeoutExpired:
                pass
        except KeyboardInterrupt:
            cancel_android(serial, run["run_id"])
            phone = {"status": "OWNER_ABORTED"}
            aborted = True
        except (Stage1Error, subprocess.TimeoutExpired) as exc:
            cancel_android(serial, run["run_id"])
            phone = {"status": "HOST_FAILURE", "reason": type(exc).__name__}
        finally:
            endpoint.stop()
            endpoint_result = endpoint.final()
            load_code = load_ping.stop() if load_ping else None
            if capture:
                capture.stop()
            adb(serial, "shell", "run-as", PACKAGE, "rm", "-f", f"files/stage1_batch/{token}.json", check=False)
        load_result = ping_summary(run_dir / "rtt-load.private.txt", load_code) if load_ping else {"method": "none", "status": "SKIPPED"}
        layers = classify_result(phone, endpoint_result, expected_bytes, expected_hash, config)
        result = {"run_id": run["run_id"], "variant": run["variant"],
            "pair": run.get("pair"), "order": run.get("order"), "run_status": layers["run_status"],
            "expected_bytes": expected_bytes, "expected_sha256": expected_hash,
            "phone": phone, "endpoint": endpoint_result, "rtt": {"method": "adb_shell_ping_round_trip",
                "idle": idle_result, "under_load": load_result, "interpretation": "independent round-trip observation; not one-way queue delay"},
            "capture": {"status": capture_status, "transport_effect": UNVERIFIED}, "evidence_layers": layers}
        atomic_json(run_dir / "record.private.json", result)
        redacted = redact_private(result)
        summary["runs"].append(redacted)
        atomic_json(session / "redacted-summary.json", redact_private(summary))
        if aborted:
            raise KeyboardInterrupt()
        return redacted

    try:
        execute_sequence(runs, one, continue_policy)
    except KeyboardInterrupt:
        summary["batch_status"] = "OWNER_ABORTED"
        atomic_json(session / "redacted-summary.json", redact_private(summary))
        raise
    summary["batch_status"] = "COMPLETE" if len(summary["runs"]) == len(runs) else "STOPPED_BY_POLICY"
    atomic_json(session / "redacted-summary.json", redact_private(summary))
    return session, summary


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    source = result.add_mutually_exclusive_group(required=True)
    source.add_argument("--preset", choices=("wifi-screen", "wifi-efficacy", "cellular-paired"))
    source.add_argument("--manifest", type=Path)
    result.add_argument("--endpoint-address", required=True, help="Numeric address reachable from the selected phone Network")
    result.add_argument("--bind-address", help="Numeric owner-host bind address; defaults to endpoint address")
    result.add_argument("--port", type=int, default=39001)
    result.add_argument("--transport", choices=("wifi", "cellular"))
    result.add_argument("--serial", help="ADB selector; never copied to the redacted summary")
    result.add_argument("--network-ordinal", type=int, default=-1, help="Zero-based eligible transport Network when Android reports ambiguity")
    result.add_argument("--build", action="store_true", help="Build and record the current clean debug APK")
    result.add_argument("--install", action="store_true", help="Build if needed and install the exact debug APK")
    result.add_argument("--tshark-interface", help="Owner sender interface; capture remains under ignored raw output")
    result.add_argument("--continue-on-failure", action="store_true")
    result.add_argument("--allow-emulator", action="store_true", help="Lifecycle testing only; never physical efficacy")
    return result


def main() -> int:
    args = parser().parse_args()
    args.bind_address = args.bind_address or args.endpoint_address
    if not 1 <= args.port <= 65535:
        raise SystemExit("--port outside 1..65535")
    try:
        endpoint_ip = ipaddress.ip_address(args.endpoint_address)
        bind_ip = ipaddress.ip_address(args.bind_address)
    except ValueError:
        raise SystemExit("endpoint and bind addresses must be numeric IP literals")
    if "%" in args.endpoint_address or "%" in args.bind_address:
        raise SystemExit("scoped addresses are not supported")
    if args.tshark_interface and bind_ip.is_unspecified:
        raise SystemExit("TShark requires a specific --bind-address for the owned-flow filter")
    try:
        session, summary = run_batch(args)
    except Stage1Error as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("ABORTED: cancellation requested; retained completed/raw records", file=sys.stderr)
        return 130
    print(f"Redacted summary: {session / 'redacted-summary.json'}")
    print(f"Runs recorded: {len(summary['runs'])}; batch status: {summary['batch_status']}")
    print(f"Physical benefit: {UNVERIFIED}")
    return 0 if summary["batch_status"] == "COMPLETE" else 3


if __name__ == "__main__":
    raise SystemExit(main())
