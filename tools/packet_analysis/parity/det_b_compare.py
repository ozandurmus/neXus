"""Lane B parity (integrity, proxies, TLS, application index, fragments, capture layers): frozen PacketBreaker
reference versus the Java engine.

Run with the frozen reference interpreter (CPython 3.12 venv of ~/Codo/PacketBreaker):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/det_b_compare.py WORKDIR \
        [--only NAME ...] [--java-classpath CP] [-v]

Same method as core_compare.py (DuckDB threads=1 on both sides, the Java engine runs on a copy of the project
taken right after reference ingest + topology), extended with

- inputs built by the reference's own test fixture builders (tests/proxy_fixtures.make_fixture,
  tests/capture_realism_fixtures.fragment_path) besides the synthetic scenarios;
- the direct return value of every lane B reference function (index_applications, correlate, add_dwell,
  analyze_integrity, fragment_findings, group_downstream_findings, reset_checks, tls_checks), captured by
  read-only hooks and compared with the value the Java stage records in parity."detectors";
- the lane B intermediate tables (proxy_requests, proxy_pairs, app_protocol, tcp_point_analysis,
  app_protocol_cache, modified_payload_pairs, fragment_groups, layer_packets, layer_cache) and the core
  snapshots of core_compare.py (application indexing rewrites obs before clock alignment);
- the final report sections integrity_notes, proxies, tls, fragment_points, tunnel_points,
  application_status (canonicalized as core_compare.py does).
"""

from __future__ import annotations

import argparse
import copy
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import core_compare as C  # noqa: E402

REFERENCE = Path.home() / "Codo" / "PacketBreaker"

INPUTS = [
    ("integrity_all", "scenario", ("integrity_multi", {"fault_location": "all", "ip_id": "zero", "ipv6": False})),
    ("integrity_constant_ipv6", "scenario", ("integrity_multi", {"fault_location": "all", "ip_id": "constant", "ipv6": True})),
    ("integrity_asym_fw", "scenario", ("integrity_multi_asym", {"fault_location": "FW", "ip_id": "zero", "ipv6": False})),
    ("integrity_asym_link", "scenario", ("integrity_multi_asym", {"fault_location": "link", "ip_id": "constant", "ipv6": False})),
    ("demo", "scenario", ("demo", {})),
    ("sequence_randomization", "scenario", ("sequence_randomization", {})),
    ("sequence_inconsistent", "scenario", ("sequence_inconsistent", {})),
    ("offload", "scenario", ("offload", {})),
    ("truncation", "scenario", ("truncation", {})),
    ("duplicate", "scenario", ("duplicate", {})),
    ("acked_unseen", "scenario", ("acked_unseen", {})),
    ("control_capture_miss", "scenario", ("control_capture_miss", {})),
    ("pilot", "scenario", ("pilot", {})),
    # Note: udp_transactions requires udp_index/udp_decoded from the UDP/RTP/SIP/DNS detector lane (out of scope).
    ("proxy_zero_ipv4", "proxy", ("zero", False)),
    ("proxy_constant_ipv4", "proxy", ("constant", False)),
    ("proxy_zero_ipv6", "proxy", ("zero", True)),
    # Second analysis of the same project: application_index takes its cache path (identity check, cached=True).
    ("proxy_cached_ipv4", "proxy_cached", ("zero", False)),
    ("fragments_ipv4_zero", "fragment", (False, "zero")),
    ("fragments_ipv6_constant", "fragment", (True, "constant")),
    ("layers_default", "layers", None),
    ("layers_inner", "layers", "inner"),
    ("layers_outer", "layers", "outer"),
    ("diagnostics", "scenario", ("diagnostics", {"ip_id": "zero", "ipv6": False})),
    ("diagnostics_ipv6", "scenario", ("diagnostics", {"ip_id": "constant", "ipv6": True})),
]

SNAPSHOTS = dict(C.SNAPSHOTS)
SNAPSHOTS["proxy"] = ["proxy_requests", "proxy_pairs", "app_protocol", "tcp_point_analysis", "app_protocol_cache"]
SNAPSHOTS["path"] = ["modified_payload_pairs", "fragment_groups", "fragment_members", "layer_packets", "layer_cache"]
SNAPSHOTS["transport"] = [
    "transport_tcp",
    "transport_handshake",
    "transport_acks",
    "transport_samples",
    "transport_point_facts",
    "transport_edges",
    "transport_transit",
    "transport_path_facts",
    "transport_rtt_facts",
    "transport_retry_signals",
    "transport_retries",
    "transport_reordering",
]
STAGES = [
    "index_applications",
    "correlate",
    "add_dwell",
    "integrity",
    "fragment_findings",
    "group_downstream_findings",
    "reset_checks",
    "tls_checks",
    "transport_export",
    "proxy_waterfall",
]
SECTIONS = ["integrity_notes", "proxies", "tls", "fragment_points", "tunnel_points", "application_status"]
# Deliberate differences (tie-breaks, ui2/packet-analysis/DETERMINISM.md): (input, stage or section, path prefix).
# Tables whose only difference is a recorded tie-break: (input, "point.table") -> reason. Empty: the classification
# acked tie on pilot is resolved by the frame tie-break (DETERMINISM.md).
DELIBERATE_TABLES: dict[tuple[str, str], str] = {}
DELIBERATE: dict[tuple[str, str, str], str] = {
    ("proxy_zero_ipv4", "integrity", "integrity[0]["): (
        "integrity endpoint_packets: Java appends ORDER BY corrected,frame after QUALIFY row_number()=1 "
        "(reference has no outer ORDER BY; frames 1159 vs 1214)"
    ),
    ("proxy_constant_ipv4", "integrity", "integrity[0]["): (
        "integrity endpoint_packets: Java appends ORDER BY corrected,frame after QUALIFY row_number()=1 "
        "(reference has no outer ORDER BY; frames 1159 vs 1214)"
    ),
    ("proxy_zero_ipv4", "group_downstream_findings", "group_downstream_findings["): (
        "classification first_event ORDER BY ts,id,packet_key LIMIT 1 tie at ts=1700000000.013 plus integrity endpoint_packets ORDER BY"
    ),
    ("proxy_constant_ipv4", "group_downstream_findings", "group_downstream_findings["): (
        "classification first_event ORDER BY ts,id,packet_key LIMIT 1 tie at ts=1700000000.013 plus integrity endpoint_packets ORDER BY"
    ),
    ("proxy_zero_ipv6", "group_downstream_findings", "group_downstream_findings["): (
        "classification first_event ORDER BY ts,id,packet_key LIMIT 1 tie at ts=1700000000.013"
    ),
    ("proxy_zero_ipv4", "tls_checks", "tls_checks[1]["): (
        "tls facts: Java appends ORDER BY r.start NULLS LAST,r.point,r.last_frame,r.id (reference has no ORDER BY; "
        "a_in/tls-9.example frame 718 vs client/tls-10.example frame 733)"
    ),
    ("proxy_constant_ipv4", "tls_checks", "tls_checks[1]["): (
        "tls facts: Java appends ORDER BY r.start NULLS LAST,r.point,r.last_frame,r.id (reference has no ORDER BY; "
        "a_in/tls-9.example frame 718 vs client/tls-10.example frame 733)"
    ),
    ("proxy_zero_ipv6", "tls_checks", "tls_checks[1]["): (
        "tls facts: Java appends ORDER BY r.start NULLS LAST,r.point,r.last_frame,r.id (reference has no ORDER BY; "
        "a_in/tls-9.example frame 718 vs client/tls-10.example frame 733)"
    ),
    ("proxy_zero_ipv4", "tls", "tls["): (
        "tls facts: Java appends ORDER BY r.start NULLS LAST,r.point,r.last_frame,r.id (reference has no ORDER BY; "
        "a_in/tls-9.example frame 718 vs client/tls-10.example frame 733)"
    ),
    ("proxy_constant_ipv4", "tls", "tls["): (
        "tls facts: Java appends ORDER BY r.start NULLS LAST,r.point,r.last_frame,r.id (reference has no ORDER BY; "
        "a_in/tls-9.example frame 718 vs client/tls-10.example frame 733)"
    ),
    ("proxy_zero_ipv6", "tls", "tls["): (
        "tls facts: Java appends ORDER BY r.start NULLS LAST,r.point,r.last_frame,r.id (reference has no ORDER BY; "
        "a_in/tls-9.example frame 718 vs client/tls-10.example frame 733)"
    ),
    ("diagnostics", "transport_export", "transport_export.items["): (
        "transport.profiles signal evidence: reference iterates Python set {s['point'] for s in signal} (hash-seed "
        "order); Java iterates TreeSet<String> in lexicographical point order"
    ),
    ("diagnostics_ipv6", "transport_export", "transport_export.items["): (
        "transport.profiles signal evidence: reference iterates Python set {s['point'] for s in signal} (hash-seed "
        "order); Java iterates TreeSet<String> in lexicographical point order"
    ),
    ("offload", "transport_export", "transport_export.items[0].evidence[7].byte_ranges["): (
        "evidence range_peers: list(DISTINCT ...) has no order in the reference; Java orders the ranges"
    ),
}

# The cached re-analysis of the proxy fixture has the same tie-breaks as its first analysis.
DELIBERATE.update({("proxy_cached_ipv4", w, prefix): why for (case, w, prefix), why in list(DELIBERATE.items())
                   if case == "proxy_zero_ipv4"})


def plain(value):
    return json.loads(json.dumps(value))


def build(case_dir: Path, kind: str, spec):
    """Generate + ingest + bind; returns (project, topology dict)."""
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.store import Project
    from packetbreaker.synthetic import bind_capture_ids, generate

    if kind == "scenario":
        scenario, options = spec
        truth, topology = generate(case_dir, scenario=scenario, rounds=100, **options)
        project = Project(case_dir / "project")
        ids = ingest_many(project, truth["files"], parallel=False)
        bind_capture_ids(topology, {Path(path).name: cid for path, cid in zip(truth["files"], ids)})
        return project, topology
    sys.path.insert(0, str(REFERENCE / "tests"))
    if kind == "proxy_cached":
        import packetbreaker.analysis.report as R

        project, topology = build(case_dir, "proxy", spec)
        R.analyze(project, json.loads(json.dumps(topology)))
        return project, topology
    if kind == "proxy":
        from packetbreaker.ingest import ingest
        from proxy_fixtures import make_fixture

        ip_id, ipv6 = spec
        file, topology, _ = make_fixture(case_dir / "wire", ipv6=ipv6, ip_id=ip_id)
        project = Project(case_dir / "project")
        capture_id = ingest(project, file)
        bind_capture_ids(topology, {file.name: capture_id})
        return project, topology
    if kind == "fragment":
        import packetbreaker.analysis as A
        from capture_realism_fixtures import fragment_path

        captured = {}
        original = A.analyze

        def capture(project, topology, *args, **kwargs):
            captured.update(project=project, topology=topology)

        A.analyze = capture
        try:
            ipv6, ip_id = spec
            fragment_path(case_dir / "wire", ipv6, ip_id)
        finally:
            A.analyze = original
        project = captured["project"]
        target = case_dir / "project"
        shutil.copytree(project.path, target)
        return Project(target), captured["topology"]
    if kind == "layers":
        import struct
        from capture_realism_fixtures import tunnel
        from packetbreaker.ingest import ingest
        from packetbreaker.synthetic import tcp_packet, write_pcap

        plain_pkt = tcp_packet("198.51.100.1", "203.0.113.2", 41001, 80, 1001, 2001, 24, b"x" * 180, 0)
        decoy = tcp_packet("192.0.2.10", "192.0.2.20", 41001, 80, 1001, 2001, 24, b"x" * 180, 555)
        project = Project(case_dir / "project")
        wire = case_dir / "wire"
        wire.mkdir(parents=True, exist_ok=True)
        points = []
        for name, packets in [
            ("client", [plain_pkt, decoy]),
            ("fw_in", [tunnel(plain_pkt, "erspan2", truncated=True), decoy]),
            ("fw_out", [plain_pkt, decoy]),
            ("lb_in", [tunnel(plain_pkt, "vxlan"), decoy]),
            ("lb_out", [plain_pkt, decoy]),
            ("server", [plain_pkt, decoy]),
        ]:
            path = wire / (name + ".pcap")
            write_pcap(path, [(1700000000 + i * 0.1, p) for i, p in enumerate(packets)])
            if name == "fw_in":
                with path.open("wb") as output:
                    output.write(struct.pack("<IHHIIII", 0xA1B23C4D, 2, 4, 0, 0, 65535, 1))
                    for i, packet in enumerate(packets):
                        output.write(
                            struct.pack("<IIII", 1700000000, i * 100000000 + 789, len(packet), len(packet))
                        )
                        output.write(packet)
            cid = ingest(project, path)
            pt = dict(
                id=name,
                label=name,
                device="FW" if name.startswith("fw") else "LB" if name.startswith("lb") else name,
                kind="Generic",
                capture_id=cid,
            )
            if name == "lb_in" and spec is not None:
                pt["matching_layer"] = spec
            points.append(pt)
        topology = dict(
            points=points, forward=[p["id"] for p in points], client_cidrs=["198.51.100.0/24", "192.0.2.10/32"]
        )
        return project, topology
    raise ValueError(kind)


def reference_run(case_dir: Path, kind: str, spec):
    import packetbreaker.analysis.matching as AM
    import packetbreaker.analysis.report as R

    C.force_single_thread()
    if case_dir.exists():
        shutil.rmtree(case_dir)
    case_dir.mkdir(parents=True)
    project, topology = build(case_dir, kind, spec)
    topology = json.loads(json.dumps(topology))
    java_dir = case_dir / "java"
    java_dir.mkdir()
    shutil.copy2(project.path / "project.duckdb", java_dir / "project.duckdb")
    (case_dir / "topology.json").write_text(json.dumps(topology, indent=2), encoding="utf-8")

    dumps, outputs = {}, {}
    originals = {}

    def dump(point, db):
        dumps[point] = {t: C.table_dump(db, t) for t in SNAPSHOTS[point]}

    def hook(module, name, after):
        original = getattr(module, name)
        originals[(module, name)] = original

        def wrapped(*args, **kwargs):
            result = original(*args, **kwargs)
            after(result, *args)
            return result

        setattr(module, name, wrapped)

    def record(stage, value):
        outputs[stage] = plain(copy.deepcopy(value))

    hook(AM, "select_observations", lambda r, db, *a: dump("selection", db))
    hook(R, "prepare", lambda r, db, *a: dump("prepare", db))
    hook(R, "index_applications", lambda r, *a: record("index_applications", r))
    hook(R, "align", lambda r, db, *a: dump("align", db))
    hook(R, "propagate_translation_unknown", lambda r, db, *a: dump("matching", db))
    hook(R, "prepare_byte_ranges", lambda r, db, *a: dump("bytes", db))
    hook(R, "prepare_flow_filters", lambda r, db, *a: dump("flow_filters", db))
    hook(R, "correlate", lambda r, *a: record("correlate", r))

    def after_dwell(r, db, topology, models, proxies):
        record("add_dwell", proxies)
        dump("proxy", db)

    hook(R, "add_dwell", after_dwell)
    hook(R, "add_headlines", lambda r, db, *a: dump("core", db))
    hook(R, "analyze_integrity", lambda r, *a: record("integrity", r))
    hook(R, "fragment_findings", lambda r, *a: record("fragment_findings", r))
    hook(R, "group_downstream_findings", lambda r, findings, *a: record("group_downstream_findings", findings))
    hook(R, "reset_checks", lambda r, *a: record("reset_checks", r))

    def after_tls(r, db, *a):
        record("tls_checks", r)
        dump("path", db)

    hook(R, "tls_checks", after_tls)
    from packetbreaker.proxy_waterfall import waterfall as proxy_waterfall
    from packetbreaker.transport import export_profiles

    try:
        started = time.monotonic()
        report = R.analyze(project, topology)
        with project.connect() as db:
            project.set(db, "topology", topology)
            project.set(db, "report", report)
        record("transport_export", export_profiles(project, 200))
        with project.connect() as db:
            dumps["transport"] = {t: C.table_dump(db, t) for t in SNAPSHOTS["transport"]}
            if report.get("proxies", {}).get("transactions") and any(
                p.get("translation") == "full_proxy" and p.get("vendor") != "f5" for p in topology.get("points", [])
            ):
                row = db.execute(
                    "SELECT flow FROM proxy_requests WHERE point='client' AND kind='http' ORDER BY start,last_frame,id LIMIT 1"
                ).fetchone()
                if row and row[0]:
                    record("proxy_waterfall", proxy_waterfall(db, report, topology, row[0], None, None, 0))
        elapsed = time.monotonic() - started
    finally:
        for (module, name), original in originals.items():
            setattr(module, name, original)
    (case_dir / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    with project.connect() as db:
        capture_names = dict(db.execute("SELECT id,name FROM captures").fetchall())
    return dict(dumps=dumps, outputs=outputs, report=plain(report), capture_names=capture_names, seconds=elapsed)


def java_run(case_dir: Path, classpath: str):
    import os

    java_dir = case_dir / "java"
    parity = java_dir / "parity.duckdb"
    if parity.exists():
        parity.unlink()
    command = [
        os.environ.get("JAVA", "java"),
        "-Dpacketanalysis.duckdb.threads=1",
        "-Dpacketanalysis.parity.detb=true",
        "-cp",
        classpath,
        "com.securityexpert.nexus.ui2.packetanalysis.engine.EngineCli",
        "--project",
        str(java_dir / "project.duckdb"),
        "--topology",
        str(case_dir / "topology.json"),
        "--report",
        str(java_dir / "report.json"),
        "--parity",
        str(parity),
    ]
    started = time.monotonic()
    result = subprocess.run(command, capture_output=True, text=True)
    elapsed = time.monotonic() - started
    if result.returncode != 0:
        return dict(error=(result.stderr or result.stdout)[-4000:], seconds=elapsed)
    import duckdb

    dumps, outputs = {}, {}
    with duckdb.connect(str(parity), read_only=True) as db:
        for point, tables in SNAPSHOTS.items():
            dumps[point] = {t: C.table_dump(db, f"{point}__{t}") for t in tables}
        if db.execute("SELECT count(*) FROM information_schema.tables WHERE table_name='detectors'").fetchone()[0]:
            for stage, value in db.execute("SELECT stage,value FROM detectors").fetchall():
                outputs[stage] = json.loads(value)
    text = (java_dir / "report.json").read_text(encoding="utf-8")
    report = json.loads(text)
    formatting_ok = text == json.dumps(report, indent=2)
    return dict(dumps=dumps, outputs=outputs, report=report, seconds=elapsed, formatting_ok=formatting_ok)


def deliberate(name, where, diffs):
    reasons = [r for (case, w, prefix), r in DELIBERATE.items() if case == name and w == where
               and all(d.split(":")[0].startswith(prefix) for d in diffs)]
    return reasons[0] if reasons else None


COUNTER = re.compile(r"^(integrity:.*):\d+(:merged)?$")


def order_free(value):
    """Lists of findings/notes as multisets; the per-detector finding counter in integrity ids dropped."""
    def strip(v):
        if isinstance(v, dict):
            return {k: (COUNTER.sub(r"\1\2", x) if k == "id" and isinstance(x, str) else strip(x)) for k, x in v.items()}
        if isinstance(v, list):
            return [strip(x) for x in v]
        return v

    def walk(v):
        if isinstance(v, list):
            return sorted(json.dumps(walk(x), sort_keys=True) for x in v)
        if isinstance(v, dict):
            return {k: walk(x) for k, x in v.items()}
        return v

    return walk(strip(value))


def explain(why, a, b):
    """A deliberate entry is accepted only with its nature shown: order only, or the content difference."""
    if order_free(a) == order_free(b):
        return f"deliberate, order only ({why})"
    return f"deliberate, content differs ({why}): " + "; ".join(C.json_diff(a, b, "", limit=4))


def compare_case(name, ref, java, verbose):
    lines, problems = [], []
    tables_ok = tables_total = rows = 0
    for point, tables in SNAPSHOTS.items():
        for table in tables:
            a = ref["dumps"].get(point, {}).get(table)
            b = java["dumps"].get(point, {}).get(table)
            status, detail = C.compare_tables(a, b)
            if status == "absent":
                continue
            tables_total += 1
            rows += len(a["rows"]) if a else 0
            if status == "identical":
                tables_ok += 1
            else:
                why = DELIBERATE_TABLES.get((name, f"{point}.{table}"))
                (lines if why else problems).append(
                    f"table {point}.{table}: " + (f"deliberate ({why})" if why else f"{status} {detail}")
                )
    stages_ok = []
    for stage in STAGES:
        a, b = ref["outputs"].get(stage), java["outputs"].get(stage)
        if a is None and b is None:
            continue
        if b is None:
            problems.append(f"stage {stage}: not recorded by java")
            continue
        if a is None:
            problems.append(f"stage {stage}: recorded by java, not called by the reference")
            continue
        diffs = C.json_diff(a, b, stage, limit=8)
        if json.dumps(a) == json.dumps(b):
            stages_ok.append(stage)
        else:
            why = deliberate(name, stage, diffs)
            (lines if why else problems).append(f"stage {stage}: " + (explain(why, a, b) if why else "; ".join(diffs or ["key order"])))
    names = ref["capture_names"]
    sections_ok = []
    for section in SECTIONS:
        a = C.canon_section(section, ref["report"].get(section), names)
        b = C.canon_section(section, java["report"].get(section), names)
        if json.dumps(a) == json.dumps(b):
            sections_ok.append(section)
        else:
            diffs = C.json_diff(a, b, section, limit=8)
            why = deliberate(name, section, diffs)
            (lines if why else problems).append(f"section {section}: " + (explain(why, a, b) if why else "; ".join(diffs or ["key order"])))
    head = (f"{name}: tables {tables_ok}/{tables_total} identical ({rows} reference rows); stages identical "
            f"{len(stages_ok)}/{len([s for s in STAGES if s in ref['outputs'] or s in java['outputs']])}; sections identical "
            f"{len(sections_ok)}/{len(SECTIONS)}; json {'exact' if java['formatting_ok'] else 'DIFFERS'}; "
            f"ref {ref['seconds']:.1f}s java {java['seconds']:.1f}s")
    out = [head] + ["  " + x for x in lines]
    if problems:
        out.append("  FIRST DIVERGENCE: " + problems[0][:1500])
        if verbose:
            out += ["  " + p[:1500] for p in problems[1:]]
    return not problems and java["formatting_ok"], out


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("workdir", type=Path)
    parser.add_argument("--only", nargs="*")
    parser.add_argument("--java-classpath")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    classpath = args.java_classpath or C.default_classpath(repo)
    all_ok = True
    for name, kind, spec in INPUTS:
        if args.only and name not in args.only:
            continue
        case_dir = args.workdir / name
        ref = reference_run(case_dir, kind, spec)
        java = java_run(case_dir, classpath)
        if "error" in java:
            all_ok = False
            print(f"{name}: JAVA ERROR\n{java['error']}", flush=True)
            continue
        ok, lines = compare_case(name, ref, java, args.verbose)
        all_ok &= ok
        print("\n".join(lines), flush=True)
    print("\nSUMMARY " + ("all identical" if all_ok else "divergences present"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
