"""Owner-run deterministic download TEST endpoint. Never relays or accepts application payloads.

Standard library only. Bind address/port are supplied locally, never printed.
Every connection receives exactly --bytes bytes: byte i = i % 251, then EOF.
Sender counts are send() acceptance, NOT wire departure or physical efficacy.
"""
import argparse
import hashlib
import json
import socket
import time
from concurrent.futures import ThreadPoolExecutor
import struct

BLOCK = bytes(range(251)) * 64
MAX_BYTES = 256 * 1024 * 1024


def chunks(count):
    if not 1 <= count <= MAX_BYTES:
        raise ValueError("byte count outside bounded test range")
    remaining = count
    while remaining:
        chunk = BLOCK[:min(remaining, len(BLOCK))]
        yield chunk
        remaining -= len(chunk)


def expected_hash(count):
    digest = hashlib.sha256()
    for chunk in chunks(count):
        digest.update(chunk)
    return digest.hexdigest()


def transfer(peer, count, timeout, emit=print):
    started = time.monotonic()
    next_report = started
    sent = 0
    digest = hashlib.sha256()
    outcome = "COMPLETE"
    error = None
    try:
        for chunk in chunks(count):
            view = memoryview(chunk)
            while view:
                remaining = timeout - (time.monotonic() - started)
                if remaining <= 0:
                    raise TimeoutError()
                peer.settimeout(min(remaining, 5))
                n = peer.send(view)
                if n == 0:
                    raise ConnectionError("no progress")
                digest.update(view[:n])
                sent += n
                view = view[n:]
                now = time.monotonic()
                if now >= next_report:
                    emit(json.dumps({"elapsed_ms": round((now-started)*1000), "accepted_bytes": sent}))
                    next_report = now + 0.25
    except OSError as exc:
        outcome = "FAILED"
        error = exc.errno
    result = {"outcome": outcome, "errno": error, "accepted_bytes": sent,
              "sha256": digest.hexdigest(), "expected_bytes": count,
              "elapsed_ms": round((time.monotonic()-started)*1000),
              "physical_efficacy": "UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT"}
    emit(json.dumps(result))
    return result


def receive_transfer(peer, count, timeout, pause_ms=0, cadence_ms=0, read_bytes=16384,
                     reset_after=0, emit=print):
    """Receive only a bounded synthetic campaign stream; hash/count, never store or relay payload."""
    started = time.monotonic()
    received = 0
    digest = hashlib.sha256()
    outcome, error = "COMPLETE", None
    try:
        time.sleep(pause_ms / 1000)
        while True:
            remaining = timeout - (time.monotonic() - started)
            if remaining <= 0:
                raise TimeoutError()
            peer.settimeout(min(remaining, 5))
            data = peer.recv(min(read_bytes, count - received + 1))
            if not data:
                if received != count:
                    outcome = "EARLY_EOF"
                break
            received += len(data)
            digest.update(data)
            if received > count:
                outcome = "EXCESS_DATA"
                break
            if reset_after and received >= reset_after:
                # Windows uses two unsigned shorts; Unix uses two ints.
                import os
                peer.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER,
                                struct.pack("HH" if os.name == "nt" else "ii", 1, 0))
                outcome = "INTENTIONAL_RESET"
                break
            time.sleep(cadence_ms / 1000)
        if outcome == "COMPLETE":
            if digest.hexdigest() != expected_hash(count):
                outcome = "INTEGRITY_FAILED"
            else:
                peer.sendall((digest.hexdigest() + "\n").encode("ascii"))
                peer.shutdown(socket.SHUT_WR)
    except OSError as exc:
        outcome, error = "FAILED", exc.errno
    result = {"outcome": outcome, "errno": error, "accepted_bytes": received,
              "sha256": digest.hexdigest(), "expected_bytes": count,
              "elapsed_ms": round((time.monotonic() - started) * 1000),
              "physical_efficacy": "UNVERIFIED — REQUIRES PHYSICAL EXPERIMENT"}
    emit(json.dumps(result))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True, help="Owner's numeric local interface address")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--bytes", type=int, default=16*1024*1024)
    parser.add_argument("--connections", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--upload-flow-bytes", help="comma-separated synthetic upload flow lengths")
    parser.add_argument("--pause-ms", type=int, default=0)
    parser.add_argument("--cadence-ms", type=int, default=0)
    parser.add_argument("--read-bytes", type=int, default=16384)
    parser.add_argument("--reset-after", type=int, default=0)
    args = parser.parse_args()
    flow_bytes = [int(value) for value in args.upload_flow_bytes.split(",")] if args.upload_flow_bytes else None
    if flow_bytes and not (1 <= len(flow_bytes) <= 4 and all(1 <= n <= MAX_BYTES for n in flow_bytes)
                           and sum(flow_bytes) == args.bytes <= MAX_BYTES):
        parser.error("invalid upload flow bounds")
    if not (0 <= args.pause_ms <= 30000 and 0 <= args.cadence_ms <= 100 and
            1 <= args.read_bytes <= 16384 and 0 <= args.reset_after <= MAX_BYTES):
        parser.error("invalid receiver bounds")
    if not (1 <= args.port <= 65535 and 1 <= args.connections <= 20 and 1 <= args.timeout <= 300):
        parser.error("invalid bounded endpoint configuration")
    print(json.dumps({"expected_bytes": args.bytes, "expected_sha256": expected_hash(args.bytes)}), flush=True)
    family = socket.AF_INET6 if ":" in args.bind else socket.AF_INET
    socket.inet_pton(family, args.bind)  # No DNS or project-operated destination
    with socket.socket(family, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((args.bind, args.port))
        listener.listen(1)
        listener.settimeout(args.timeout)
        if flow_bytes:
            print(json.dumps({"status": "awaiting_owner_connection"}), flush=True)
            def receive_owned(peer, count):
                with peer:
                    return receive_transfer(peer, count, args.timeout, args.pause_ms, args.cadence_ms,
                                            args.read_bytes, args.reset_after, emit=lambda _: None)
            with ThreadPoolExecutor(max_workers=len(flow_bytes)) as pool:
                futures = []
                for count in flow_bytes:
                    peer, _ = listener.accept()
                    futures.append(pool.submit(receive_owned, peer, count))
                results = [future.result() for future in futures]
            print(json.dumps({"outcome": "COMPLETE" if all(r["outcome"] == "COMPLETE" for r in results) else "PARTIAL",
                              "accepted_bytes": sum(r["accepted_bytes"] for r in results), "flows": results}), flush=True)
            return
        for run in range(args.connections):
            print(json.dumps({"run": run + 1, "status": "awaiting_owner_connection"}), flush=True)
            peer, _ = listener.accept()
            with peer:
                transfer(peer, args.bytes, args.timeout, lambda line: print(line, flush=True))


if __name__ == "__main__":
    main()
