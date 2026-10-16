#!/usr/bin/env python3
"""Reference side of the L1 ingest parity check: ingest capture files with the frozen PacketBreaker engine.

Run with the frozen PacketBreaker interpreter (its venv has the editable packetbreaker install and duckdb 1.5.6):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/l1_reference_ingest.py PROJECT FILE...

Uses the same entry point as `packetbreaker demo` (Project + batch_ingest.ingest_many, sequential); `--prefilter`
switches to the `packetbreaker case` default. `--threads` (default 1) pins DuckDB threads on every connection,
like `-Dpacketanalysis.duckdb.threads` on the Java side. Prints the capture ids as JSON. Never writes anything but PROJECT.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("project", type=Path)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--prefilter", action="store_true")
    parser.add_argument("--memory-limit-mb", type=int)
    parser.add_argument("--threads", type=int, default=1, help="DuckDB threads per connection (parity: 1)")
    args = parser.parse_args()

    import duckdb

    from packetbreaker import store
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.store import Project

    class ForcedThreads:
        """duckdb module stand-in for the reference store: every connection gets the forced thread count.

        With several threads, tie order (ORDER BY ... LIMIT, arg_min/arg_max, GROUP BY output) is not
        deterministic in the reference, so parity runs pin it on both sides."""

        def __getattr__(self, name):
            return getattr(duckdb, name)

        @staticmethod
        def connect(*a, config=None, **k):
            return duckdb.connect(*a, config={**(config or {}), "threads": str(args.threads)}, **k)

    store.duckdb = ForcedThreads()

    project = Project(args.project, memory_limit_mb=args.memory_limit_mb)
    status = 0
    try:
        ids = ingest_many(project, [str(f) for f in args.files], parallel=False, prefilter=args.prefilter)
        error = None
    except (ValueError, InterruptedError) as exc:
        ids, error, status = None, str(exc), 1
    print(json.dumps({"project": str(project.path), "capture_ids": ids, "error": error}))
    return status


if __name__ == "__main__":
    sys.exit(main())
