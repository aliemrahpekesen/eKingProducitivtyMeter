#!/usr/bin/env python3
"""G5 API load smoke — stdlib-only closed-loop HTTP load driver (docs/performance evidence).

Drives a fixed set of read endpoints against a running eip-app with N concurrent worker threads
for a fixed wall-clock duration, then prints per-endpoint latency percentiles as a markdown table.

Design notes (kept deliberately simple and dependency-free):
  * threads + one persistent keep-alive ``http.client.HTTPConnection`` per worker — a closed-loop
    driver (each worker issues its next request only after the previous response), which measures
    latency under concurrency, not an open-loop arrival-rate SLO;
  * each worker cycles round-robin through the endpoint list so every endpoint receives a
    comparable sample count;
  * percentiles use the nearest-rank method on the full recorded sample (no reservoir, no loss);
  * errors = any non-2xx status or transport failure (the connection is re-opened on failure).

Usage:
  python3 scripts/perf/api_load.py --base http://localhost:8080 \\
      --tenant 00000000-0000-4000-8000-0000000000de --seconds 30 --concurrency 20
"""

from __future__ import annotations

import argparse
import http.client
import math
import threading
import time
import urllib.parse

DEFAULT_ENDPOINTS = [
    "/api/v1/friction/summary",
    "/api/v1/metrics/trends?weeks=12",
    "/api/v1/insights/recommendations",
    "/api/v1/reports",
]


class EndpointStats:
    """Latency samples and error count for one endpoint (lock-protected merge target)."""

    def __init__(self) -> None:
        self.latencies_ms: list[float] = []
        self.errors = 0


def percentile(sorted_ms: list[float], pct: float) -> float:
    """Nearest-rank percentile over an ascending-sorted sample."""
    if not sorted_ms:
        return float("nan")
    rank = max(1, math.ceil(pct / 100.0 * len(sorted_ms)))
    return sorted_ms[rank - 1]


def worker(
    host: str,
    port: int,
    scheme: str,
    tenant: str,
    endpoints: list[str],
    deadline: float,
    stats: dict[str, EndpointStats],
    lock: threading.Lock,
    offset: int,
) -> None:
    """One closed-loop worker: cycles endpoints on a persistent keep-alive connection."""
    conn_cls = http.client.HTTPSConnection if scheme == "https" else http.client.HTTPConnection
    conn = conn_cls(host, port, timeout=30)
    headers = {"X-EIP-Tenant": tenant, "Accept": "application/json", "Connection": "keep-alive"}
    local: dict[str, list[tuple[float, bool]]] = {ep: [] for ep in endpoints}
    i = offset  # staggered start so workers don't hit endpoints in lockstep
    while time.monotonic() < deadline:
        endpoint = endpoints[i % len(endpoints)]
        i += 1
        start = time.monotonic()
        ok = False
        try:
            conn.request("GET", endpoint, headers=headers)
            response = conn.getresponse()
            response.read()  # drain so the connection is reusable
            ok = 200 <= response.status < 300
        except OSError:
            conn.close()
            conn = conn_cls(host, port, timeout=30)
        elapsed_ms = (time.monotonic() - start) * 1000.0
        local[endpoint].append((elapsed_ms, ok))
    conn.close()
    with lock:
        for endpoint, samples in local.items():
            for elapsed_ms, ok in samples:
                if ok:
                    stats[endpoint].latencies_ms.append(elapsed_ms)
                else:
                    stats[endpoint].errors += 1


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:8080", help="backend base URL")
    parser.add_argument("--tenant", required=True, help="X-EIP-Tenant header value")
    parser.add_argument("--seconds", type=int, default=30, help="test duration (wall clock)")
    parser.add_argument("--concurrency", type=int, default=20, help="worker thread count")
    parser.add_argument(
        "--endpoints",
        default=",".join(DEFAULT_ENDPOINTS),
        help="comma-separated GET paths (default: the four dashboard reads)",
    )
    args = parser.parse_args()

    parsed = urllib.parse.urlparse(args.base)
    host = parsed.hostname or "localhost"
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    endpoints = [e.strip() for e in args.endpoints.split(",") if e.strip()]

    stats = {ep: EndpointStats() for ep in endpoints}
    lock = threading.Lock()
    deadline = time.monotonic() + args.seconds
    started = time.monotonic()
    threads = [
        threading.Thread(
            target=worker,
            args=(host, port, parsed.scheme, args.tenant, endpoints, deadline, stats, lock, n),
            daemon=True,
        )
        for n in range(args.concurrency)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.monotonic() - started

    total_requests = sum(len(s.latencies_ms) + s.errors for s in stats.values())
    print(
        f"base={args.base} tenant={args.tenant} duration={wall:.1f}s "
        f"concurrency={args.concurrency} total_requests={total_requests} "
        f"throughput={total_requests / wall:.1f} req/s"
    )
    print()
    print("| endpoint | requests | errors | p50 ms | p95 ms | p99 ms | max ms |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for endpoint in endpoints:
        s = stats[endpoint]
        sample = sorted(s.latencies_ms)
        n = len(sample)
        row = (
            f"| `{endpoint}` | {n} | {s.errors} "
            f"| {percentile(sample, 50):.1f} | {percentile(sample, 95):.1f} "
            f"| {percentile(sample, 99):.1f} | {(sample[-1] if sample else float('nan')):.1f} |"
        )
        print(row)


if __name__ == "__main__":
    main()
