"""Freeze a lane B parity input as a JUnit fixture (integrity, proxies, TLS, application index).

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/export_detb_fixture.py WORKDIR NAME

NAME is an input of det_b_compare.py. Writes ui2/packet-analysis/src/test/resources/packetanalysis/engine/
detb_<NAME>.json.gz with the base tables right after reference ingest + topology (capture paths reduced to file
names, as export_fixture.py does), the topology, the synthetic capture files the application index decodes
(base64, for the fixture-builder inputs whose layers or application events need a tshark pass), the reference return value of every lane B
stage and the lane B report sections (DuckDB threads=1).
"""

from __future__ import annotations

import base64
import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import det_b_compare as B  # noqa: E402
import export_fixture as E  # noqa: E402


def main():
    workdir, name = Path(sys.argv[1]), sys.argv[2]
    kind, spec = next((k, s) for n, k, s in B.INPUTS if n == name)
    import duckdb

    case = workdir / ("fixture_detb_" + name)
    ref = B.reference_run(case, kind, spec)
    tables, files = {}, {}
    with duckdb.connect(str(case / "java" / "project.duckdb"), read_only=True) as db:
        for table in ("captures", "packets", "settings"):
            columns = [(r[0], r[1]) for r in db.execute(f"DESCRIBE {table}").fetchall()]
            order = ", ".join(f'"{c}"' for c, _ in columns)
            rows = [[E.value(x) for x in r] for r in db.execute(f"SELECT {order} FROM {table} ORDER BY rowid").fetchall()]
            if table == "captures":
                names = [c for c, _ in columns]
                path, identity = names.index("path"), names.index("identity")
                for r in rows:
                    source = Path(r[path])
                    if kind != "scenario":  # fixture builders whose inputs need a tshark pass (layers, application index)
                        files[source.name] = base64.b64encode(source.read_bytes()).decode()
                    r[path] = source.name
                    parts = json.loads(r[identity])
                    if isinstance(parts, list) and parts and isinstance(parts[0], str):
                        parts[0] = Path(parts[0]).name
                        r[identity] = json.dumps(parts)
            tables[table] = dict(columns=columns, rows=rows)
    sections = {s: B.C.canon_section(s, ref["report"].get(s), ref["capture_names"]) for s in B.SECTIONS}
    fixture = dict(
        input=name,
        topology=json.loads((case / "topology.json").read_text()),
        tables=tables,
        files=files,
        outputs={k: v for k, v in ref["outputs"].items() if k in B.STAGES},
        sections=sections,
        capture_names=ref["capture_names"],
    )
    out = Path(__file__).resolve().parents[3] / "ui2/packet-analysis/src/test/resources/packetanalysis/engine"
    target = out / f"detb_{name}.json.gz"
    with gzip.GzipFile(target, "wb", mtime=0) as f:
        f.write(json.dumps(fixture, separators=(",", ":")).encode())
    print(target, target.stat().st_size)


if __name__ == "__main__":
    main()
