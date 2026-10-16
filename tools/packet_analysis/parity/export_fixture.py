"""Freeze a reference-ingested synthetic input set as a JUnit fixture for the Java core engine.

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/export_fixture.py WORKDIR SCENARIO \
        [--rounds N] [--name NAME]

Writes ui2/packet-analysis/src/test/resources/packetanalysis/engine/<NAME>.json.gz holding the base tables of
the project right after reference ingest + topology (captures, packets, settings; capture paths reduced to file
names), the topology, and the reference core-only report sections (core_compare.py semantics, DuckDB threads=1),
canonicalized as core_compare.py does (lists from unordered GROUP BY sorted by point, count dicts sorted).
"""

from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import core_compare  # noqa: E402

KEEP = ["verdict", "window", "clocks", "coverage", "quality", "segments", "findings", "finding_count",
        "flow_count", "nat_suggestions", "limitations", "analysis_scope", "sequence_translations",
        "offload_points", "prefilter_excluded", "auto_order_suggestion", "tunnel_points"]


def value(v):
    if isinstance(v, (bool, int, float, str)) or v is None:
        return v
    return str(v)


def canonical(name, v):
    v = json.loads(json.dumps(v))
    if name in ("coverage", "quality", "analysis_scope", "offload_points", "tunnel_points"):
        v = sorted(v, key=lambda x: x["point"])
    if name == "segments":
        for s in v:
            for key in ("classes", "excluded_counts"):
                s[key] = dict(sorted(s[key].items()))
            for q in s["endpoint_quality"].values():
                q["excluded"] = dict(sorted(q["excluded"].items()))
    return v


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("workdir", type=Path)
    parser.add_argument("scenario")
    parser.add_argument("--rounds", type=int, default=100)
    parser.add_argument("--name")
    parser.add_argument("--ip-id")
    parser.add_argument("--confirm-nat", action="store_true")
    args = parser.parse_args()
    import duckdb

    name = args.name or args.scenario
    case = args.workdir / ("fixture_" + name)
    import packetbreaker.synthetic as synthetic

    original = synthetic.generate

    def generate(directory, **kw):
        kw["rounds"] = args.rounds
        return original(directory, **kw)

    synthetic.generate = generate
    try:
        options = {"confirm_nat": True} if args.confirm_nat else {}
        if args.ip_id:
            options["ip_id"] = args.ip_id
        ref = core_compare.reference_run(case, args.scenario, options)
    finally:
        synthetic.generate = original
    tables = {}
    with duckdb.connect(str(case / "java" / "project.duckdb"), read_only=True) as db:
        for table in ("captures", "packets", "settings"):
            columns = [(r[0], r[1]) for r in db.execute(f"DESCRIBE {table}").fetchall()]
            order = ", ".join(f'"{c}"' for c, _ in columns)
            rows = [[value(x) for x in r] for r in db.execute(f"SELECT {order} FROM {table} ORDER BY rowid").fetchall()]
            if table == "captures":
                path = [c for c, _ in columns].index("path")
                identity = [c for c, _ in columns].index("identity")
                for r in rows:
                    r[path] = Path(r[path]).name
                    parts = json.loads(r[identity])
                    if isinstance(parts, list) and parts and isinstance(parts[0], str):
                        parts[0] = Path(parts[0]).name
                        r[identity] = json.dumps(parts)
            tables[table] = dict(columns=columns, rows=rows)
    fixture = dict(
        scenario=args.scenario,
        rounds=args.rounds,
        topology=json.loads((case / "topology.json").read_text()),
        tables=tables,
        reference_core={k: canonical(k, ref["core"][k]) for k in KEEP},
    )
    out = Path(__file__).resolve().parents[3] / "ui2/packet-analysis/src/test/resources/packetanalysis/engine"
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"{name}.json.gz"
    with gzip.GzipFile(target, "wb", mtime=0) as f:
        f.write(json.dumps(fixture, separators=(",", ":")).encode())
    print(target, target.stat().st_size)


if __name__ == "__main__":
    main()
