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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True, help="Owner's numeric local interface address")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--bytes", type=int, default=16*1024*1024)
    parser.add_argument("--connections", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
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
        for run in range(args.connections):
            print(json.dumps({"run": run + 1, "status": "awaiting_owner_connection"}), flush=True)
            peer, _ = listener.accept()
            with peer:
                transfer(peer, args.bytes, args.timeout, lambda line: print(line, flush=True))


if __name__ == "__main__":
    main()
