"""Freeze a det-a parity input (det_a_compare.py inputs) as a JUnit fixture for the time and event detectors.

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/det_a_export_fixture.py WORKDIR NAME

NAME is a det_a_compare.DET_A_INPUTS name. Writes ui2/packet-analysis/src/test/resources/packetanalysis/engine/
det_a_<NAME>.json.gz: the base tables right after reference ingest + topology (capture paths reduced to file
names, as export_fixture.py), the topology, and the reference report sections of the det-a lane at DuckDB
threads=1 (the full reference analysis: every detector lane is ported, nothing is stubbed).
"""

from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import det_a_compare  # noqa: E402
from export_fixture import value  # noqa: E402

KEEP = ["verdict", "segments", "findings", "finding_count", "timeseries", "onsets", "microbursts", "host_path",
        "cycles", "incident", "delivery", "vendor_device_events", "vendor_device_event_count",
        "confirmed_device_drops", "confirmed_device_drop_count", "sessions", "session_events", "security"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("workdir", type=Path)
    parser.add_argument("name")
    args = parser.parse_args()
    import duckdb

    entry = next(x for x in det_a_compare.DET_A_INPUTS if x[0] == args.name)
    case = args.workdir / ("fixture_" + args.name)
    ref = det_a_compare.reference_run(case, entry[1], dict(entry[2]))
    tables = {}
    with duckdb.connect(str(case / "java" / "project.duckdb"), read_only=True) as db:
        for table in ("captures", "packets", "settings"):
            columns = [(r[0], r[1]) for r in db.execute(f"DESCRIBE {table}").fetchall()]
            order = ", ".join(f'"{c}"' for c, _ in columns)
            rows = [[value(x) for x in r] for r in db.execute(f"SELECT {order} FROM {table} ORDER BY rowid").fetchall()]
            if table == "captures":
                names = [c for c, _ in columns]
                for r in rows:
                    r[names.index("path")] = Path(r[names.index("path")]).name
                    parts = json.loads(r[names.index("identity")])
                    if isinstance(parts, list) and parts and isinstance(parts[0], str):
                        parts[0] = Path(parts[0]).name
                        r[names.index("identity")] = json.dumps(parts)
            tables[table] = dict(columns=columns, rows=rows)
    fixture = dict(
        scenario=entry[1],
        options=entry[2],
        topology=json.loads((case / "topology.json").read_text()),
        tables=tables,
        reference_det_a={k: json.loads(json.dumps(ref["report"][k])) for k in KEEP},
    )
    out = Path(__file__).resolve().parents[3] / "ui2/packet-analysis/src/test/resources/packetanalysis/engine"
    target = out / f"det_a_{args.name}.json.gz"
    with gzip.GzipFile(target, "wb", mtime=0) as f:
        f.write(json.dumps(fixture, separators=(",", ":")).encode())
    print(target, target.stat().st_size)


if __name__ == "__main__":
    main()
