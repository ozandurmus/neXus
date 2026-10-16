"""Build the Packet Analysis parity corpus from the frozen PacketBreaker reference.

Run with the PacketBreaker virtualenv interpreter (it imports ``packetbreaker`` and PacketBreaker's own
test helpers); the frozen repository is never modified: the frozen commit is exported with ``git archive``
into the work directory and imported from there.

    ~/Codo/PacketBreaker/.venv/bin/python tools/packet_analysis/parity/reference.py \
        --work /path/to/scratch/pbpar --goldens ui2/packet-analysis/src/test/resources/parity

Per case it records three comparison levels (see ui2/packet-analysis/README.md):

* L1 ingest: per capture file, a row hash of the ``packets`` table (``canonical.L1_SQL``).
* L2 intermediate tables: ``obs``, ``observation_matches``, ``events``, ``flow_summary`` (persistent) and
  ``clock_values`` (TEMP, snapshotted in-process before the analysis connection closes), dumped to Parquet
  with capture ids replaced by ``<capture:NAME>``, then hashed by ``canonical.l2_hash_sql``.
* L3: the PacketBreaker golden payload, the report and the FindingsExport (schema v11), canonical form.

Two complete runs are made into separate directories; the run is accepted only when every recorded hash
is identical between them (determinism check). The full corpus (inputs, Parquet, canonical JSON) is written
as one deterministic ``.tar.gz``; only manifests, hashes and masked key sections are written to
``--goldens``.
"""

from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
import io
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from concurrent.futures import ProcessPoolExecutor
from multiprocessing import get_context
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import canonical as canon  # noqa: E402  (plain-python sibling module)

FROZEN_COMMIT = "5b608e64003ee1f20a3f8dc79385fbffee1c84e7"
DEFAULT_REPO = Path.home() / "Codo" / "PacketBreaker"

CLI_SCENARIOS = [
    "udp_transactions", "pilot", "real_case_2", "pilot_healthy", "security", "security_reply",
    "diagnostics", "onset_intermittent_2", "onset_intermittent_3", "onset_intermittent_4",
    "onset_intermittent_5", "onset_random_loss", "onset_loss", "onset_delay", "onset_propagation",
    "onset_capture_miss", "offload", "sequence_randomization", "sequence_inconsistent", "healthy", "demo",
    "capture_miss", "recovered_loss", "impactful_loss", "nat", "delay", "truncation", "duplicate",
    "acked_unseen", "syn_blocked", "unrecovered_reset", "unrecovered_stall", "control_capture_miss",
    "realistic_healthy", "realistic_capture_miss", "realistic_loss", "realistic_syn_blocked",
]
CLI_DEFAULTS = dict(rounds=100, ip_id="increment", ipv6=False)
# One axis at a time from the CLI defaults; a variant is kept only when its generated inputs differ.
VARIANT_AXES = [
    ("ip-id-zero", dict(ip_id="zero")),
    ("ip-id-constant", dict(ip_id="constant")),
    ("ip-id-random", dict(ip_id="random")),
    ("ipv6", dict(ipv6=True)),
    ("rounds-40", dict(rounds=40)),
]
NAT_CONFIRM_SCENARIOS = ("demo", "nat")  # the only generators that translate addresses (use_nat)

# Tier 1: the PacketBreaker author's golden scenarios, built exactly as tests/test_golden.py builds them.
TIER1 = [
    "demo", "nat", "offload", "integrity_all", "dns_diagnosis",
    "fifty_flow_replica", "folder_b_replica", "oct8_replica",
]
L2_PERSISTENT = ("obs", "observation_matches", "events", "flow_summary")
L2_TEMP = ("clock_values",)
CAPTURE_SUFFIXES = (".pcap", ".pcapng", ".cap")

# ------------------------------------------------------------------------------------------------
# Worker-side state (one case at a time per worker process).
_TEMP_SNAPSHOT: dict = {}
_PB = None


def _setup(pb_root: str) -> None:
    """Import the frozen PacketBreaker tree and its test helpers; hook the report write."""
    global _PB
    if _PB is not None:
        return
    sys.path.insert(0, str(Path(pb_root) / "tests"))
    sys.path.insert(0, str(Path(pb_root) / "src"))
    import conftest  # noqa: F401  PacketBreaker test harness: every duckdb.connect gets threads=1
    import test_golden
    import packetbreaker
    from packetbreaker.store import Project

    if not str(Path(packetbreaker.__file__).resolve()).startswith(str(Path(pb_root).resolve())):
        raise RuntimeError(f"packetbreaker imported from {packetbreaker.__file__}, not the frozen tree")
    original_set = Project.set

    def hooked_set(db, key, value):
        original_set(db, key, value)
        if key == "report" and value is not None:
            # Multi-threaded DuckDB is not deterministic for this reference (ORDER BY ... LIMIT ties and
            # arg_min/arg_max choose different evidence); every analysis connection must run one thread.
            threads = db.execute("SELECT current_setting('threads')").fetchone()[0]
            if int(threads) != 1:
                raise RuntimeError(f"analysis connection runs {threads} DuckDB threads; the reference needs 1")
            # Keep the snapshot of the last analysis connection that holds the TEMP table: a multi-group
            # case_setup re-writes the primary report from a fresh connection without it, and the
            # persistent L2 tables then hold that last analysis' state.
            for table in L2_TEMP:
                present = db.execute(
                    "SELECT count(*) FROM duckdb_tables() WHERE table_name=? AND temporary", [table]
                ).fetchone()[0]
                if not present:
                    continue
                schema = db.execute(f'DESCRIBE "{table}"').fetchall()
                rows = db.execute(f'SELECT * FROM "{table}"').fetchall()
                _TEMP_SNAPSHOT[table] = ([(c[0], c[1]) for c in schema], rows)

    Project.set = staticmethod(hooked_set)
    _PB = dict(test_golden=test_golden, Project=Project)


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _input_hashes(folder: Path) -> dict[str, str]:
    return {
        p.name: _sha256_file(p)
        for p in sorted(folder.iterdir())
        if p.is_file() and p.suffix.lower() in CAPTURE_SUFFIXES
    }


# ------------------------------------------------------------------------------------------------
# Case construction


def tier2_cases(signatures: dict) -> list[dict]:
    cases = []
    for scenario in CLI_SCENARIOS:
        base = dict(CLI_DEFAULTS)
        cases.append(dict(name=f"cli-{scenario}", tier=2, kind="cli", scenario=scenario, kwargs=base))
        seen = {signatures[(scenario, "default")]}
        for label, change in VARIANT_AXES:
            sig = signatures[(scenario, label)]
            if sig in seen:
                continue
            seen.add(sig)
            cases.append(
                dict(name=f"cli-{scenario}--{label}", tier=2, kind="cli", scenario=scenario,
                     kwargs={**base, **change})
            )
        if scenario in NAT_CONFIRM_SCENARIOS:
            cases.append(
                dict(name=f"cli-{scenario}--confirm-demo-nat", tier=2, kind="cli", scenario=scenario,
                     kwargs=base, confirm_demo_nat=True)
            )
    return cases


def tier1_cases() -> list[dict]:
    return [dict(name=name, tier=1, kind="golden") for name in TIER1]


def input_signature(pb_root: str, scenario: str, label: str, kwargs: dict, scratch: str):
    """Hash of everything the analysis consumes from a generator: pcaps + topology (pre-binding)."""
    _setup(pb_root)
    from packetbreaker.synthetic import generate

    with tempfile.TemporaryDirectory(dir=scratch) as tmp:
        truth, topology = generate(Path(tmp) / "g", scenario=scenario, **kwargs)
        digest = hashlib.sha256()
        for name, value in _input_hashes(Path(tmp) / "g").items():
            digest.update(f"{name}={value}\n".encode())
        digest.update(json.dumps(topology, sort_keys=True).encode())
    return (scenario, label), digest.hexdigest()


def _build_tier1(name: str, case_dir: Path):
    """Exact construction of tests/test_golden.py; returns (project, golden payload, input dir)."""
    import pytest
    from packetbreaker.analysis import analyze
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.case_setup import create_case
    from packetbreaker.dns import dns_page
    from packetbreaker.ingest import find_tshark
    from packetbreaker.pilot_synthetic import make_pilot
    from packetbreaker.synthetic import bind_capture_ids, generate
    from packetbreaker.transport import profiles

    tg = _PB["test_golden"]
    Project = _PB["Project"]
    tshark = find_tshark()
    wire = case_dir / "input"

    def scenario(sname, **kwargs):  # conftest.build_scenario_fixture
        truth, topology = generate(wire, scenario=sname, **kwargs)
        project = Project(case_dir / "project")
        ids = ingest_many(project, truth["files"], tshark=tshark, workers=4)
        bind_capture_ids(topology, {Path(p).name: cid for p, cid in zip(truth["files"], ids)})
        return project, truth, analyze(project, topology)

    if name in ("demo", "nat", "offload"):
        project, _, report = scenario(name)
        return project, {"report": report}, wire
    if name == "integrity_all":
        project, _, report = scenario("integrity_multi", fault_location="all", ip_id="zero", ipv6=False)
        return project, {"report": report}, wire
    if name == "dns_diagnosis":
        project, truth, report = scenario("diagnostics", ip_id="zero", ipv6=False)
        ports = {}
        with project.connect() as db:
            for case, expected in sorted(truth["cases"].items()):
                ports[case] = db.execute(
                    "SELECT min(flow) FROM obs WHERE sport=?", [expected["port"]]
                ).fetchone()[0]
        diag_profiles = {}
        for case, fid in sorted(ports.items()):
            prof = profiles(project, fid)
            for item in prof["items"]:
                item["evidence"] = sorted(
                    item["evidence"], key=lambda e: (e.get("point", ""), e.get("frame", 0))
                )
            diag_profiles[case] = prof
        dns = dns_page(project, point="client")
        return project, {"report": report, "dns": dns, "profiles": diag_profiles}, wire
    if name == "fifty_flow_replica":
        truth, topology = make_pilot(
            wire, flows=50, affected_flows=25, slow_local_flow=True, host_jitter_us=150, ip_id="increment"
        )
        topology["forward"] = [p for p in topology["forward"] if p != "mirror"]
        topology["reverse"] = [p for p in topology["reverse"] if p != "mirror"]
        topology["points"] = [p for p in topology["points"] if p["id"] != "mirror"]
        project = Project(case_dir / "project")
        ids = ingest_many(project, truth["files"], tshark=tshark, workers=4)
        bind_capture_ids(topology, {Path(f).name: c for f, c in zip(truth["files"], ids)})
        return project, {"report": analyze(project, topology)}, wire
    if name in ("folder_b_replica", "oct8_replica"):
        mp = pytest.MonkeyPatch()
        try:
            tg._deterministic_uuids(mp)
            if name == "folder_b_replica":
                tg._build_folder_b(wire)
            else:
                make_pilot(wire, flows=2, rounds=12, real_case=True, noise=200, ip_id="increment")
                for meta_file in ("ground-truth.json", "topology.json"):
                    (wire / meta_file).unlink(missing_ok=True)
            project = create_case(wire, tshark=tshark)
        finally:
            mp.undo()
        with project.connect() as db:
            payload = {"report": project.get(db, "report"), "suggested_topology": project.get(db, "suggested_topology")}
        return project, payload, wire
    raise ValueError(name)


def _build_tier2(case: dict, case_dir: Path):
    """The `packetbreaker demo` command path (cli.py), in-process."""
    from packetbreaker.analysis import analyze
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.synthetic import bind_capture_ids, generate

    Project = _PB["Project"]
    wire = case_dir / "input"
    truth, topology = generate(wire, scenario=case["scenario"], **case["kwargs"])
    project = Project(case_dir / "project")
    ids = ingest_many(project, truth["files"], parallel=False)
    bind_capture_ids(topology, {Path(p).name: cid for p, cid in zip(truth["files"], ids)})
    report = analyze(project, topology)
    if case.get("confirm_demo_nat"):
        topology["nat_mappings"] = [
            {k: s[k] for k in ("point_a", "point_b", "tuple_a", "tuple_b")} for s in report["nat_suggestions"]
        ]
        report = analyze(project, topology)
    return project, {"report": report}, wire


# ------------------------------------------------------------------------------------------------
# Level recording


def _capture_names(db) -> dict[str, str]:
    return {cid: name for cid, name in db.execute("SELECT id, name FROM captures").fetchall()}


def _record_l1(db) -> dict:
    return {
        capture: dict(rows=rows, sha256=digest) for capture, rows, digest in db.execute(canon.L1_SQL).fetchall()
    }


def _dump_l2(db, id_to_name: dict, out_dir: Path) -> dict:
    import duckdb

    out_dir.mkdir(parents=True, exist_ok=True)
    result = {}
    for table in L2_PERSISTENT:
        exists = db.execute(
            "SELECT count(*) FROM (SELECT table_name FROM duckdb_tables() UNION ALL "
            "SELECT view_name FROM duckdb_views()) WHERE table_name=?",
            [table],
        ).fetchone()[0]
        if not exists:
            result[table] = dict(status="NOT_PRESENT", reason="table/view absent after analysis")
            continue
        try:
            schema = db.execute(f'DESCRIBE "{table}"').fetchall()
            varchar = [c[0] for c in schema if c[1] == "VARCHAR"]
            target = out_dir / f"{table}.parquet"
            sql = canon.l2_select_sql(f'"{table}"', varchar, id_to_name)
            db.execute(f"COPY ({sql}) TO '{target}' (FORMAT parquet)")
        except duckdb.Error as exc:
            result[table] = dict(status="NOT_CAPTURED", reason=type(exc).__name__ + ": " + str(exc)[:200])
            continue
        result[table] = dict(columns=[[c[0], c[1]] for c in schema])
    mem = duckdb.connect()
    for table in L2_TEMP:
        snap = _TEMP_SNAPSHOT.get(table)
        if snap is None:
            result[table] = dict(status="NOT_CAPTURED", reason="TEMP table absent at report write")
            continue
        schema, rows = snap
        cols = ", ".join(f'"{n}" {t}' for n, t in schema)
        mem.execute(f'CREATE TABLE "{table}" ({cols})')
        if rows:
            mem.executemany(f'INSERT INTO "{table}" VALUES ({", ".join("?" for _ in schema)})', rows)
        varchar = [n for n, t in schema if t == "VARCHAR"]
        target = out_dir / f"{table}.parquet"
        mem.execute(f"COPY ({canon.l2_select_sql(chr(34) + table + chr(34), varchar, id_to_name)}) TO '{target}' (FORMAT parquet)")
        result[table] = dict(columns=[[n, t] for n, t in schema], source="TEMP snapshot at report write")
    for table, entry in result.items():
        if "columns" in entry:
            count, digest = mem.execute(canon.l2_hash_sql(str(out_dir / f"{table}.parquet"))).fetchone()
            entry.update(rows=count, sha256=digest)
    mem.close()
    return result


def _l3(payload, report, export, id_to_name, out_dir: Path) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    golden_bytes = canon.serialize_golden(payload, id_to_name)
    report_c = canon.canonicalize(report, id_to_name)
    export_c = canon.canonicalize(export, id_to_name)
    if set(payload) != {"report"}:  # otherwise identical in content to report.json
        (out_dir / "golden-payload.json").write_bytes(golden_bytes)
    (out_dir / "report.json").write_bytes(canon.dumps(report_c))
    (out_dir / "findings-export.json").write_bytes(canon.dumps(export_c))
    return dict(
        golden_payload_sha256=canon.sha256(golden_bytes),
        report_sha256=canon.sha256(canon.dumps(report_c)),
        report_sections=canon.section_hashes(report_c),
        findings_export_sha256=canon.sha256(canon.dumps(export_c)),
        findings_export_sections=canon.section_hashes(export_c),
        key_sections={k: report_c[k] for k in canon.KEY_SECTIONS if k in report_c},
    ), golden_bytes


def run_case(pb_root: str, case: dict, run_root: str) -> dict:
    _setup(pb_root)
    import duckdb
    from packetbreaker.export import export_data

    tg = _PB["test_golden"]
    case_dir = Path(run_root) / case["name"]
    if case_dir.exists():
        shutil.rmtree(case_dir)
    case_dir.mkdir(parents=True)
    _TEMP_SNAPSHOT.clear()
    started = time.monotonic()
    if case["tier"] == 1:
        project, payload, wire = _build_tier1(case["name"], case_dir)
    else:
        project, payload, wire = _build_tier2(case, case_dir)
    report = payload["report"]
    db_path = project.path / "project.duckdb"
    db = duckdb.connect(str(db_path), read_only=True)
    try:
        id_to_name = _capture_names(db)
        topology = json.loads(db.execute("SELECT value FROM settings WHERE key='topology'").fetchone()[0])
        l1 = _record_l1(db)
        l2 = _dump_l2(db, id_to_name, case_dir / "l2")
    finally:
        db.close()
    export = export_data(project)
    l3, golden_bytes = _l3(payload, report, export, id_to_name, case_dir / "canonical")
    if case["tier"] == 1:
        # The ported canonicalization must reproduce PacketBreaker's own serializer and committed golden.
        pb_bytes = tg.serialize_golden(payload, project)
        committed = gzip.decompress((Path(pb_root) / "tests" / "golden" / f"{case['name']}.json.gz").read_bytes())
        l3["pb_serializer_identical"] = pb_bytes == golden_bytes
        l3["pb_committed_golden_identical"] = committed == golden_bytes
    # Analysis input as the corpus stores it: the saved topology with every capture id (point capture_id,
    # clock_overrides keys, ...) mapped back to its file name.
    def by_name(obj):
        if isinstance(obj, dict):
            return {id_to_name.get(k, k): by_name(v) for k, v in obj.items()}
        if isinstance(obj, list):
            return [by_name(v) for v in obj]
        return id_to_name.get(obj, obj) if isinstance(obj, str) else obj

    (wire / "analysis-topology.json").write_text(
        json.dumps(by_name(topology), indent=2, sort_keys=True), encoding="utf-8"
    )
    for path in sorted(wire.glob("*.json")):
        if path.name != "analysis-topology.json":  # generator metadata: absolute input paths -> names
            text = path.read_text(encoding="utf-8")
            for prefix in (str(wire.resolve()) + "/", str(wire) + "/"):
                text = text.replace(prefix, "")
            path.write_text(text, encoding="utf-8")
    shutil.rmtree(project.path, ignore_errors=True)  # reproducible from the inputs; not archived
    entry = dict(
        name=case["name"],
        tier=case["tier"],
        construction={k: v for k, v in case.items() if k not in ("name", "tier")},
        inputs=_input_hashes(wire),
        l1=l1,
        l2=l2,
        l3=l3,
        seconds=round(time.monotonic() - started, 1),
    )
    return entry


def versions(pb_root: str, repo: Path) -> dict:
    _setup(pb_root)
    import duckdb
    import packetbreaker
    from packetbreaker.export import FindingsExport
    from packetbreaker.ingest import PARSER_VERSION, find_tshark

    tshark = find_tshark()
    tshark_version = subprocess.run([tshark, "--version"], capture_output=True, text=True).stdout.splitlines()[0]
    head = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    return dict(
        packetbreaker_commit=FROZEN_COMMIT,
        packetbreaker_checkout_head=head,
        packetbreaker_version=packetbreaker.__version__,
        parser_version=PARSER_VERSION,
        findings_export_schema_version=FindingsExport.model_fields["schema_version"].default,
        python=platform.python_version(),
        duckdb=duckdb.__version__,
        duckdb_threads=1,
        duckdb_jdbc="org.duckdb:duckdb_jdbc:" + duckdb.__version__ + ".0",
        tshark=tshark_version,
    )


# ------------------------------------------------------------------------------------------------
# Driver


def export_frozen_tree(repo: Path, commit: str, work: Path) -> Path:
    target = work / f"packetbreaker-{commit[:12]}"
    if (target / "src" / "packetbreaker").is_dir():
        return target
    target.mkdir(parents=True, exist_ok=True)
    data = subprocess.run(["git", "-C", str(repo), "archive", commit], capture_output=True, check=True).stdout
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        tar.extractall(target, filter="data")
    return target


def comparable(entry: dict) -> dict:
    """Everything recorded except timings."""
    return {k: v for k, v in entry.items() if k != "seconds"}


def deterministic_tar_gz(root: Path, members: list[Path], target: Path) -> None:
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as tar:
        for path in members:
            info = tarfile.TarInfo(path.relative_to(root.parent).as_posix())
            info.size = path.stat().st_size
            info.mtime = 0
            info.mode = 0o644
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            with path.open("rb") as f:
                tar.addfile(info, f)
    with target.open("wb") as out, gzip.GzipFile(fileobj=out, mode="wb", mtime=0, compresslevel=9) as gz:
        gz.write(raw.getvalue())


def write_goldens(goldens: Path, entries: list[dict], meta: dict) -> int:
    """cases/<case>.json: hashes (plain JSON, scanned by the privacy gate as is);
    cases/<case>.key-sections.json.gz: the masked canonical key sections (gzip, mtime 0)."""
    cases_dir = goldens / "cases"
    if cases_dir.exists():
        shutil.rmtree(cases_dir)
    cases_dir.mkdir(parents=True)
    index = []
    for entry in entries:
        body = comparable(entry)
        body["l3"] = dict(body["l3"])
        key_bytes = canon.dumps(canon.mask_for_commit(body["l3"].pop("key_sections"))) + b"\n"
        key_name = entry["name"] + ".key-sections.json.gz"
        (cases_dir / key_name).write_bytes(gzip.compress(key_bytes, compresslevel=9, mtime=0))
        body["l3"]["key_sections_file"] = key_name
        body["l3"]["key_sections_masked_sha256"] = canon.sha256(key_bytes)
        name = entry["name"] + ".json"
        text = json.dumps(body, indent=1, sort_keys=True, ensure_ascii=False) + "\n"
        (cases_dir / name).write_text(text, encoding="utf-8")
        index.append(dict(name=entry["name"], tier=entry["tier"], file="cases/" + name))
    manifest = dict(meta, cases=index)
    (goldens / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    return sum(p.stat().st_size for p in goldens.rglob("*") if p.is_file())


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--repo", type=Path, default=DEFAULT_REPO, help="frozen PacketBreaker repository (read-only)")
    parser.add_argument("--commit", default=FROZEN_COMMIT)
    parser.add_argument("--work", type=Path, required=True, help="scratch directory for runs and the corpus")
    parser.add_argument("--goldens", type=Path, help="committed golden directory to (re)write")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--runs", type=int, default=2, help="complete runs for the determinism check")
    parser.add_argument("--only", nargs="*", help="limit to these case names (no goldens/archive written)")
    args = parser.parse_args(argv)

    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    pb_root = str(export_frozen_tree(args.repo, args.commit, work))
    ctx = get_context("spawn")
    with ProcessPoolExecutor(max_workers=args.jobs, mp_context=ctx, initializer=_setup, initargs=(pb_root,)) as pool:
        meta_versions = pool.submit(versions, pb_root, args.repo).result()
        scratch = work / "signatures"
        scratch.mkdir(exist_ok=True)
        jobs = [
            pool.submit(input_signature, pb_root, s, label, {**CLI_DEFAULTS, **change}, str(scratch))
            for s in CLI_SCENARIOS
            for label, change in [("default", {})] + VARIANT_AXES
        ]
        signatures = dict(j.result() for j in jobs)
        cases = tier1_cases() + tier2_cases(signatures)
        if args.only:
            cases = [c for c in cases if c["name"] in set(args.only)]
        print(f"{len(cases)} cases ({sum(c['tier'] == 1 for c in cases)} tier 1)", flush=True)
        runs = []
        for run in range(1, args.runs + 1):
            run_root = work / f"run{run}"
            futures = [pool.submit(run_case, pb_root, c, str(run_root)) for c in cases]
            entries = []
            for f in futures:
                entry = f.result()
                entries.append(entry)
                print(f"run{run} {entry['name']}: {entry['seconds']}s", flush=True)
            (run_root / "entries.json").write_text(json.dumps(entries, indent=1, sort_keys=True), encoding="utf-8")
            runs.append(entries)

    differing = []
    for entries in runs[1:]:
        for a, b in zip(runs[0], entries):
            if comparable(a) != comparable(b):
                levels = [lvl for lvl in ("inputs", "l1", "l2", "l3") if a[lvl] != b[lvl]]
                differing.append(dict(case=a["name"], levels=levels))
    # The archived files themselves must be byte-identical between runs too; Parquet files are exempt
    # (DuckDB does not keep row order byte-stable) because their content is covered by the L2 hashes.
    archive_differing = []
    for run in range(2, args.runs + 1):
        for e in runs[0]:
            for sub in ("input", "canonical"):
                for p in sorted((work / "run1" / e["name"] / sub).rglob("*")):
                    if p.is_file():
                        other = work / f"run{run}" / p.relative_to(work / "run1")
                        if not other.is_file() or _sha256_file(p) != _sha256_file(other):
                            archive_differing.append(p.relative_to(work / "run1").as_posix())
    determinism = dict(
        runs=args.runs,
        identical=not differing and not archive_differing,
        differing=differing,
        archive_files_differing=archive_differing,
        archive_files_rule="input/ and canonical/ byte-identical across runs; l2/*.parquet by content hash",
    )
    print(json.dumps(determinism, indent=1))
    tier1_ok = all(
        e["l3"].get("pb_serializer_identical") and e["l3"].get("pb_committed_golden_identical")
        for e in runs[0]
        if e["tier"] == 1
    )
    print(f"tier-1 canonicalization reproduces PacketBreaker goldens: {tier1_ok}")
    if args.only or not args.goldens:
        return 0 if determinism["identical"] and tier1_ok else 1

    run1 = work / "run1"
    corpus_dir = work / "corpus"
    corpus_dir.mkdir(exist_ok=True)
    members = sorted(
        p
        for e in runs[0]
        for sub in ("input", "l2", "canonical")
        for p in (run1 / e["name"] / sub).rglob("*")
        if p.is_file()
    )
    archive = corpus_dir / "packet-analysis-parity-corpus.tar.gz"
    deterministic_tar_gz(run1, members, archive)
    meta = dict(
        schema="nexus.packet_analysis.parity_corpus",
        schema_version=1,
        reference=meta_versions,
        canonicalization=dict(
            l3="tools/packet_analysis/parity/canonical.py (port of PacketBreaker tests/test_golden.py)",
            l1_sql=canon.L1_SQL,
            l2_tables=list(L2_PERSISTENT + L2_TEMP),
            commit_masking="private/link-local IPv4 literals in key sections -> <ipv4:SHA256_12>",
        ),
        archive=dict(
            file=archive.name,
            sha256=_sha256_file(archive),
            bytes=archive.stat().st_size,
            layout="run1/<case>/{input,l2,canonical}/...",
        ),
        determinism=determinism,
        tier1_reproduces_packetbreaker_goldens=tier1_ok,
    )
    size = write_goldens(args.goldens, runs[0], meta)
    print(f"archive {archive} {archive.stat().st_size} bytes; goldens {size} bytes")
    return 0 if determinism["identical"] and tier1_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
