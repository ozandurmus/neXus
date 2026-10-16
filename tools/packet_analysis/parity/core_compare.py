"""L2 + L3 core parity: frozen PacketBreaker reference versus the Java core engine.

Run with the frozen reference interpreter (CPython 3.12 venv of ~/Codo/PacketBreaker):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/core_compare.py WORKDIR \
        [--only NAME ...] [--java-classpath CP] [--skip-java]

For every input set the script

1. generates the synthetic captures (the code path of `packetbreaker demo DIR --scenario X`), ingests them
   into a reference project and binds the topology (`topology.json`);
2. copies `project.duckdb` right after ingest + topology, before any analysis table exists
   (`java/project.duckdb`);
3. runs the reference analysis (`packetbreaker.analysis.analyze`, the call `packetbreaker analyze` makes) with
   read-only observation hooks: at each core stage boundary the intermediate tables are dumped, and the core
   state (segments, findings, quality, coverage, clocks) is deep-copied right after `add_headlines`, before any
   secondary detector runs;
4. runs the Java engine on the copy (`EngineCli`), which materializes the same intermediate tables into a
   separate parity database at the same stage boundaries;
5. compares (a) every intermediate table row by row (multiset of canonical rows; floats by exact repr) and
   (b) the core report sections against the reference's final report (every detector lane is ported, so the
   segments, findings and verdict carry every detector's output on both sides). The core-only state is still
   computed: export_fixture.py freezes it for the JUnit core fixtures.

Canonicalization: 32-hex random capture ids are not masked for tables (the Java engine runs on a copy, so
ids are equal); in reports, random ids are masked, `generated_at` and `engine_version` dropped, and lists
built by `GROUP BY` without `ORDER BY` in the reference (row order is hash-dependent) are sorted.
"""

from __future__ import annotations

import argparse
import copy
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

INPUTS = [
    ("demo", "demo", {}),
    ("nat", "nat", {}),
    ("offload", "offload", {}),
    ("healthy", "healthy", {}),
    ("capture_miss", "capture_miss", {}),
    ("recovered_loss", "recovered_loss", {}),
    ("impactful_loss", "impactful_loss", {}),
    ("delay", "delay", {}),
    ("duplicate", "duplicate", {}),
    ("syn_blocked", "syn_blocked", {}),
    ("unrecovered_reset", "unrecovered_reset", {}),
    ("unrecovered_stall", "unrecovered_stall", {}),
    ("realistic_loss", "realistic_loss", {}),
    ("realistic_capture_miss", "realistic_capture_miss", {}),
    ("sequence_randomization", "sequence_randomization", {}),
    ("demo_ipv6", "demo", {"ipv6": True}),
    ("demo_ipid_zero", "demo", {"ip_id": "zero"}),
    # `packetbreaker demo --confirm-demo-nat`: the second analysis runs with the suggested NAT mappings confirmed.
    ("demo_nat_confirmed", "demo", {"confirm_nat": True}),
    ("demo_ipid_zero_nat_confirmed", "demo", {"ip_id": "zero", "confirm_nat": True}),
]

# Additional reference scenarios (--extra): not part of the acceptance set (application/UDP indexing, host
# captures, vendors; covered in depth by the lane scripts).
EXTRA = [
    (name, name, {})
    for name in (
        "truncation",
        "acked_unseen",
        "control_capture_miss",
        "sequence_inconsistent",
        "realistic_healthy",
        "realistic_syn_blocked",
        "onset_loss",
        "onset_delay",
        "onset_capture_miss",
        "onset_random_loss",
        "pilot",
        "pilot_healthy",
        "udp_transactions",
        "security",
        "diagnostics",
    )
] + [("demo_ipid_constant", "demo", {"ip_id": "constant"}), ("demo_ipid_random", "demo", {"ip_id": "random"}),
     ("nat_ipv6", "nat", {"ipv6": True})]

# Stage boundary -> tables dumped there. The Java engine materializes the same names (see EngineCli --parity).
SNAPSHOTS = {
    "selection": ["obs", "analysis_scope", "fragment_groups", "fragment_members", "wire_observations"],
    "prepare": [
        "obs",
        "tuple_lookup",
        "index_scope",
        "analysis_scope",
        "sequence_models",
        "calibration",
        "nat_candidates",
        "nat_point_tuples",
        "span_duplicates",
        "client_tuples",
    ],
    "align": ["obs", "clock_values"],
    "matching": ["obs", "session_map"],
    "bytes": [
        "obs",
        "byte_flows",
        "byte_obs",
        "byte_links",
        "byte_missing",
        "byte_recoveries",
        "observation_matches",
    ],
    "flow_filters": ["flow_filters", "observation_matches", "delivery_rejections", "delivery_candidates"],
    "core": ["obs", "events", "flow_summary", "index_scope", "analysis_scope"],
}

CORE_SECTIONS = [
    "verdict",
    "window",
    "clocks",
    "coverage",
    "quality",
    "segments",
    "findings",
    "finding_count",
    "flow_count",
    "nat_suggestions",
    "limitations",
    "analysis_scope",
    "sequence_translations",
    "offload_points",
    "prefilter_excluded",
    "auto_order_suggestion",
    "tunnel_points",
]
# Deliberate differences (PO decision 2026-10-15: same input, same output). The Java engine appends
# deterministic tie-breaks (ui2/packet-analysis/DETERMINISM.md); where the reference resolved a tie
# differently at threads=1, the scenario and section path are recorded here and reported, not failed.
DELIBERATE = {
    ("offload", "quality[0].evidence[2].byte_ranges"): (
        "evidence range_peers: list(DISTINCT ...) has no order in the reference; Java orders the ranges"
    ),
    ("realistic_capture_miss", "findings[3].evidence"): (
        "classification first_event: ORDER BY ts LIMIT 1 tie between two events at the same timestamp; "
        "Java appends id,packet_key (reference frame 1225/1219, Java 1226/1220)"
    ),
}
HEX32 = re.compile(r"\b[0-9a-f]{32}\b")


# ---------------------------------------------------------------- canonical rows


def canon_value(v):
    if v is None or isinstance(v, (bool, str)):
        return v
    if isinstance(v, int):
        return v
    if isinstance(v, float):
        # DuckDB's on-disk compression folds -0.0 into 0.0 (they compare equal), so a persisted parity copy
        # cannot carry the sign of zero; tables compare zero unsigned. Report JSON keeps the sign.
        return "f:" + repr(0.0 if v == 0.0 else v)
    if isinstance(v, (list, tuple)):
        return [canon_value(x) for x in v]
    if isinstance(v, dict):
        return {str(k): canon_value(x) for k, x in v.items()}
    return "t:" + str(v)


def table_dump(db, table):
    exists = db.execute(
        "SELECT count(*) FROM information_schema.tables WHERE table_name=?", [table]
    ).fetchone()[0]
    if not exists:
        return None
    cursor = db.execute(f'SELECT * FROM "{table}"')
    columns = [c[0] for c in cursor.description]
    rows = sorted(json.dumps(canon_value(list(r)), separators=(",", ":")) for r in cursor.fetchall())
    return dict(columns=columns, rows=rows)


def compare_tables(ref, java):
    """Return (status, detail). status: identical | differs | missing_java | missing_ref | absent."""
    if ref is None and java is None:
        return "absent", ""
    if ref is None:
        return "missing_ref", f"java has {len(java['rows'])} rows"
    if java is None:
        return "missing_java", f"reference has {len(ref['rows'])} rows"
    if ref["columns"] != java["columns"]:
        return "differs", f"columns ref={ref['columns']} java={java['columns']}"
    if ref["rows"] == java["rows"]:
        return "identical", f"{len(ref['rows'])} rows"
    a, b = Counter(ref["rows"]), Counter(java["rows"])
    only_ref = sorted((a - b).elements())
    only_java = sorted((b - a).elements())
    detail = f"rows ref={len(ref['rows'])} java={len(java['rows'])}; only_ref={len(only_ref)} only_java={len(only_java)}"
    if only_ref and only_java:
        x, y = json.loads(only_ref[0]), json.loads(only_java[0])
        cols = [ref["columns"][i] for i in range(min(len(x), len(y))) if x[i] != y[i]]
        detail += f"; first differing columns {cols[:8]}"
        detail += "\n      ref : " + only_ref[0][:600] + "\n      java: " + only_java[0][:600]
    elif only_ref:
        detail += "\n      ref : " + only_ref[0][:600]
    elif only_java:
        detail += "\n      java: " + only_java[0][:600]
    return "differs", detail


# ---------------------------------------------------------------- report canonicalization


def mask(value):
    if isinstance(value, str):
        return HEX32.sub("<id>", value)
    if isinstance(value, list):
        return [mask(v) for v in value]
    if isinstance(value, dict):
        return {mask(k): mask(v) for k, v in value.items()}
    return value


def _sorted_by(items, *keys):
    return sorted(items, key=lambda x: json.dumps([x.get(k) for k in keys], sort_keys=True, default=str))


def canon_section(name, value, capture_names):
    def names(v):
        # Capture ids -> file names before masking so clocks keyed by capture id stay comparable.
        if isinstance(v, str):
            return capture_names.get(v, v)
        if isinstance(v, list):
            return [names(x) for x in v]
        if isinstance(v, dict):
            return {names(k): names(x) for k, x in v.items()}
        return v

    value = names(copy.deepcopy(value))
    # GROUP BY without ORDER BY in the reference: row order is DuckDB hash-dependent.
    if name in ("coverage", "quality", "analysis_scope", "offload_points", "tunnel_points"):
        value = _sorted_by(value, "point")
    if name == "segments":
        for s in value:
            for key in ("classes", "excluded_counts"):
                if isinstance(s.get(key), dict):
                    s[key] = dict(sorted(s[key].items()))
            for q in (s.get("endpoint_quality") or {}).values():
                q["excluded"] = dict(sorted(q["excluded"].items()))
    return mask(value)


def json_diff(a, b, path="", out=None, limit=12):
    out = [] if out is None else out
    if len(out) >= limit:
        return out
    if type(a) is not type(b) and not (isinstance(a, (int, float)) and isinstance(b, (int, float)) and type(a) is type(b)):
        out.append(f"{path}: type {type(a).__name__} {json.dumps(a)[:160]} != {type(b).__name__} {json.dumps(b)[:160]}")
    elif isinstance(a, dict):
        if list(a) != list(b):
            missing = [k for k in a if k not in b]
            extra = [k for k in b if k not in a]
            if missing or extra:
                out.append(f"{path}: keys missing_in_java={missing[:8]} extra_in_java={extra[:8]}")
            else:
                out.append(f"{path}: key order ref={list(a)[:12]} java={list(b)[:12]}")
        for k in a:
            if k in b:
                json_diff(a[k], b[k], f"{path}.{k}", out, limit)
    elif isinstance(a, list):
        if len(a) != len(b):
            out.append(f"{path}: length {len(a)} != {len(b)}")
        for i, (x, y) in enumerate(zip(a, b)):
            json_diff(x, y, f"{path}[{i}]", out, limit)
    elif isinstance(a, float) and isinstance(b, float):
        if repr(a) != repr(b) and not (math.isnan(a) and math.isnan(b)):
            out.append(f"{path}: {a!r} != {b!r}")
    elif a != b:
        out.append(f"{path}: {json.dumps(a)[:200]} != {json.dumps(b)[:200]}")
    return out


# ---------------------------------------------------------------- reference run


def force_single_thread():
    """The reference is not deterministic with several DuckDB threads (ties in ORDER BY ... LIMIT, arg_min,
    GROUP BY output order). Parity runs force one thread on both sides (Java: -Dpacketanalysis.duckdb.threads=1)."""
    import duckdb
    import packetbreaker.store as S

    if getattr(S.duckdb, "_parity_single_thread", False):
        return

    class SingleThread:
        _parity_single_thread = True

        def __getattr__(self, name):
            return getattr(duckdb, name)

        @staticmethod
        def connect(*args, **kwargs):
            config = dict(kwargs.pop("config", None) or {})
            config["threads"] = "1"
            return duckdb.connect(*args, config=config, **kwargs)

    S.duckdb = SingleThread()


def reference_run(case_dir: Path, scenario: str, options: dict):
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.store import Project
    from packetbreaker.synthetic import bind_capture_ids, generate
    import packetbreaker.analysis.matching as AM
    import packetbreaker.analysis.report as R

    force_single_thread()
    options = dict(options)
    confirm_nat = options.pop("confirm_nat", False)
    if case_dir.exists():
        shutil.rmtree(case_dir)
    case_dir.mkdir(parents=True)
    truth, topology = generate(case_dir, scenario=scenario, rounds=100, **options)
    project = Project(case_dir / "project")
    ids = ingest_many(project, truth["files"], parallel=False)
    bind_capture_ids(topology, {Path(path).name: cid for path, cid in zip(truth["files"], ids)})
    java_dir = case_dir / "java"
    java_dir.mkdir()
    shutil.copy2(case_dir / "project" / "project.duckdb", java_dir / "project.duckdb")
    if confirm_nat:
        first = R.analyze(project, topology)
        topology["nat_mappings"] = [
            {k: s[k] for k in ("point_a", "point_b", "tuple_a", "tuple_b")} for s in first["nat_suggestions"]
        ]
    (case_dir / "topology.json").write_text(json.dumps(topology, indent=2), encoding="utf-8")

    dumps = {}
    snap = {}

    def dump(point, db):
        dumps[point] = {t: table_dump(db, t) for t in SNAPSHOTS[point]}

    originals = {}

    def hook(module, name, point=None, before=None, after=None):
        original = getattr(module, name)
        originals[(module, name)] = original

        def wrapped(*args, **kwargs):
            if before:
                before(*args, **kwargs)
            result = original(*args, **kwargs)
            if point:
                dump(point, args[0])
            if after:
                after(result, *args, **kwargs)
            return result

        setattr(module, name, wrapped)

    def snapshot_detectors(db, topology, points, segments, coverage, models, findings, proxies, start, end,
                           common_start, common_end, byte_active, progress):
        snap["coverage"] = copy.deepcopy(coverage)
        snap["clocks"] = {cid: m.json() for cid, m in models.items()}
        snap["window"] = dict(start=start, end=end, common_start=common_start, common_end=common_end)

    def snapshot_headlines(result, db, findings, segments, topology, end):
        dump("core", db)
        snap["segments"] = copy.deepcopy(segments)
        snap["findings"] = copy.deepcopy(findings)

    def snapshot_quality(result, *args):
        snap["quality"] = copy.deepcopy(result[0])

    hook(AM, "select_observations", "selection")
    hook(R, "prepare", "prepare")
    hook(R, "align", "align")
    hook(R, "propagate_translation_unknown", "matching")
    hook(R, "prepare_byte_ranges", "bytes")
    hook(R, "prepare_flow_filters", "flow_filters")
    hook(R, "build_flow_summary_and_quality", after=snapshot_quality)
    hook(R, "_run_secondary_detectors", before=snapshot_detectors)
    hook(R, "add_headlines", after=snapshot_headlines)
    try:
        started = time.monotonic()
        report = R.analyze(project, topology)
        elapsed = time.monotonic() - started
    finally:
        for (module, name), original in originals.items():
            setattr(module, name, original)
    (case_dir / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    # Core-only report: the reference's own verdict / ordering functions on the snapshot, no detector output.
    from packetbreaker.host_path import lead_degradation

    findings = copy.deepcopy(snap["findings"])
    segments = copy.deepcopy(snap["segments"])
    inconclusive = any(
        q["unknown_direction"] == q["packets"] or q["excluded"] == q["packets"] for q in snap["quality"]
    )
    core = dict(
        verdict=R._select_verdict(None, [], [], [], [], [], findings, inconclusive, segments, None),
        window=snap["window"],
        clocks=snap["clocks"],
        coverage=snap["coverage"],
        quality=snap["quality"],
        nat_suggestions=report["nat_suggestions"],
        segments=segments,
        findings=findings[:200],
        finding_count=len(findings),
        flow_count=report["flow_count"],
        limitations=report["limitations"],
        analysis_scope=report["analysis_scope"],
        sequence_translations=report["sequence_translations"],
        offload_points=report["offload_points"],
        prefilter_excluded=report["prefilter_excluded"],
        auto_order_suggestion=report["auto_order_suggestion"],
        tunnel_points=report["tunnel_points"],
    )
    lead_degradation(core, findings, segments)
    with project.connect() as db:
        capture_names = dict(db.execute("SELECT id,name FROM captures").fetchall())
    return dict(dumps=dumps, report=report, core=core, capture_names=capture_names, seconds=elapsed)


# ---------------------------------------------------------------- java run


def java_run(case_dir: Path, classpath: str):
    java_dir = case_dir / "java"
    parity = java_dir / "parity.duckdb"
    if parity.exists():
        parity.unlink()
    command = [
        os.environ.get("JAVA", "java"),
        "-Dpacketanalysis.duckdb.threads=1",
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

    dumps = {}
    with duckdb.connect(str(parity), read_only=True) as db:
        for point, tables in SNAPSHOTS.items():
            dumps[point] = {}
            for table in tables:
                name = f"{point}__{table}"
                dumps[point][table] = table_dump(db, name)
    text = (java_dir / "report.json").read_text(encoding="utf-8")
    report = json.loads(text)
    # The Java writer must emit exactly the reference json.dumps(indent=2) text for its own content.
    formatting_ok = text == json.dumps(report, indent=2)
    return dict(dumps=dumps, report=report, seconds=elapsed, formatting_ok=formatting_ok)


def default_classpath(repo: Path):
    out = subprocess.run(
        ["./gradlew", "-q", ":packet-analysis:printRuntimeClasspath"],
        cwd=repo / "ui2",
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip().splitlines()
    return out[-1]


# ---------------------------------------------------------------- main


def compare_case(name, ref, java, verbose):
    lines = []
    table_status = {}
    first_divergence = None
    for point, tables in SNAPSHOTS.items():
        for table in tables:
            status, detail = compare_tables(ref["dumps"][point][table], java["dumps"][point][table])
            table_status[f"{point}.{table}"] = (status, detail)
            if status in ("differs", "missing_java", "missing_ref") and first_divergence is None:
                first_divergence = f"{point}.{table}: {status} {detail}"
    names = ref["capture_names"]
    # Every detector lane is ported: the Java report is compared with the reference's final report (all findings).
    diffs = {}
    for section in CORE_SECTIONS:
        a = canon_section(section, ref["report"].get(section), names)
        b = canon_section(section, java["report"].get(section), names)
        if a != b or json.dumps(a) != json.dumps(b):
            diffs[section] = json_diff(a, b, section)
    deliberate = []
    for section in list(diffs):
        paths = [d.split(":")[0] for d in diffs[section]]
        reasons = [r for (case, prefix), r in DELIBERATE.items() if case == name and all(p.startswith(prefix) for p in paths)]
        if reasons:
            deliberate.append(f"{section} ({reasons[0]})")
            del diffs[section]
    identical_tables = sum(1 for s, _ in table_status.values() if s == "identical")
    compared = sum(1 for s, _ in table_status.values() if s != "absent")
    rows = sum(len(ref["dumps"][p][t]["rows"]) for p, ts in SNAPSHOTS.items() for t in ts if ref["dumps"][p][t])
    lines.append(
        f"{name}: tables {identical_tables}/{compared} identical ({rows} reference rows); "
        f"report {'IDENTICAL' if not diffs else 'DIFFERS ' + ','.join(diffs)}; "
        f"json text {'exact' if java['formatting_ok'] else 'DIFFERS'}; "
        f"ref {ref['seconds']:.1f}s java {java['seconds']:.1f}s"
    )
    if deliberate:
        lines.append("  deliberate differences: " + "; ".join(deliberate))
    if first_divergence:
        lines.append("  first table divergence: " + first_divergence)
    if verbose:
        for key, (status, detail) in table_status.items():
            if status not in ("identical", "absent"):
                lines.append(f"  table {key}: {status} {detail}")
        for section, ds in diffs.items():
            for d in ds:
                lines.append(f"  section {d}")
    ok = not first_divergence and not diffs and java["formatting_ok"]
    return ok, lines


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("workdir", type=Path)
    parser.add_argument("--only", nargs="*")
    parser.add_argument("--java-classpath")
    parser.add_argument("--skip-java", action="store_true")
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument("--extra", action="store_true", help="also run the non-acceptance scenarios")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    classpath = None
    if not args.skip_java:
        classpath = args.java_classpath or default_classpath(repo)
    all_ok = True
    summary = []
    for name, scenario, options in INPUTS + (EXTRA if args.extra else []):
        if args.only and name not in args.only:
            continue
        case_dir = args.workdir / name
        ref = reference_run(case_dir, scenario, options)
        if args.skip_java:
            print(f"{name}: reference only ({ref['seconds']:.1f}s)")
            continue
        java = java_run(case_dir, classpath)
        if "error" in java:
            all_ok = False
            print(f"{name}: JAVA ERROR\n{java['error']}")
            summary.append(f"{name}: java error")
            continue
        ok, lines = compare_case(name, ref, java, args.verbose)
        all_ok &= ok
        print("\n".join(lines), flush=True)
        summary.append(lines[0])
    if not args.skip_java:
        print("\nSUMMARY " + ("all identical" if all_ok else "divergences present"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
