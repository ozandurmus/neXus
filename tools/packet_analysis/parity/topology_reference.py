"""Reference side of the topology / case-setup parity harness.

Run with the frozen PacketBreaker interpreter (``~/Codo/PacketBreaker/.venv/bin/python -I``). Two sub-commands:

``fixtures OUT``
    Builds the synthetic capture folders under OUT/fixtures/<name>/ (pcaps only): the demo scenario folders
    generated beforehand with ``packetbreaker demo`` (passed with ``--demo-root``) are copied, and the multi-path and
    ambiguity folders the reference tests build are rebuilt with the tests' own builders.

``run FOLDER WORK``
    For one capture folder: runs the reference ``create_case`` (ingest, topology scaffold, auto assemble; the core
    analysis is replaced by a stub returning ``{}`` because it is outside this port stage), snapshots project.duckdb
    right after ingest, and writes the reference JSON for every topology entry point into WORK/ref/:

    - ``suggest.json``    ``suggest_topology`` (``GET /api/topology/suggest``) on the ingest snapshot
    - ``assemble.json``   ``assemble_topology`` (``POST /api/topology/assemble`` without analysis) on the snapshot
    - ``case.json``       ``case_context`` and ``inventory_overview`` after ``create_case`` (``packetbreaker case``)
    - ``reassemble.json`` ``assemble_topology`` on the post-case project (saved topology as the base)
    - ``confirm.json``    ``confirm_suggested_topology`` on the post-case project (and ``confirm_group.json`` for the
      first ready group of a multi-path case)

    WORK/snapshot/project.duckdb is the input for the Java side; WORK/ref/<step>.duckdb are the reference projects
    after each writing step, so the harness can diff the stored settings rows too.
"""

import ast
import json
import shutil
import sys
from pathlib import Path

PB_ROOT = Path(__file__).resolve().parents[3].parent / "PacketBreaker"
TSHARK = "/Applications/Wireshark.app/Contents/MacOS/tshark"


def _dump(path: Path, obj) -> None:
    path.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")


def _stub_analyze(project, topology, progress=None, cancel=None):
    return {}


# ---------------------------------------------------------------------------------------------- fixtures


def _copy_pcaps(src: Path, dst: Path) -> None:
    dst.mkdir(parents=True, exist_ok=True)
    for p in sorted(src.iterdir()):
        if p.suffix.lower() in (".pcap", ".pcapng", ".cap") and p.is_file():
            shutil.copy2(p, dst / p.name)


def _nested_function(test_file: Path, outer: str, inner: str, namespace: dict):
    """Compile a builder defined inside a reference test function, unchanged, into ``namespace``."""
    tree = ast.parse(test_file.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name == outer:
            for sub in node.body:
                if isinstance(sub, ast.FunctionDef) and sub.name == inner:
                    module = ast.Module(body=[sub], type_ignores=[])
                    exec(compile(module, str(test_file), "exec"), namespace)
                    return namespace[inner]
    raise SystemExit(f"{inner} not found in {test_file}:{outer}")


def build_fixtures(out: Path, demo_root: Path | None) -> None:
    tests = PB_ROOT / "tests"
    sys.path.insert(0, str(tests))
    from packetbreaker.pilot_synthetic import make_pilot
    from packetbreaker.synthetic import tcp_packet, write_pcap
    import packetbreaker.case_setup as case_setup
    import test_golden
    import test_case_setup

    fixtures = out / "fixtures"
    fixtures.mkdir(parents=True, exist_ok=True)

    if demo_root is not None:
        for d in sorted(demo_root.iterdir()):
            if d.is_dir() and any(p.suffix.lower() in (".pcap", ".pcapng") for p in d.iterdir()):
                _copy_pcaps(d, fixtures / f"demo_{d.name}")

    # Multi-path two-client folder (golden builder).
    test_golden._build_folder_b(fixtures / "folder_b")

    # Folder B variants from the case-setup test (nested builder, compiled unchanged).
    build_folder_b = _nested_function(
        tests / "test_case_setup.py",
        "test_multi_path_folder_b_replica_assembles_both_paths_and_stops_when_group_ambiguous",
        "build_folder_b",
        dict(tcp_packet=tcp_packet, write_pcap=write_pcap),
    )
    build_folder_b(fixtures / "folder_b_isolated", inconsistent_ttl_lb=True, inconsistent_ttl_gen=False)
    build_folder_b(fixtures / "folder_b_both_ambiguous", inconsistent_ttl_lb=True, inconsistent_ttl_gen=True)

    # Oct-8 replica: stage pairs, shared clock (mirror), host offload (golden builder arguments).
    oct8 = fixtures / "oct8"
    make_pilot(oct8, flows=2, rounds=12, real_case=True, noise=200, ip_id="increment")
    for meta_file in ("ground-truth.json", "topology.json"):
        (oct8 / meta_file).unlink(missing_ok=True)
    # Same captures with swapped in/out names (name/order mismatch warning test).
    swapped = fixtures / "swapped_names"
    swapped.mkdir()
    for src, dst in (
        ("a_rx.pcap", "ark_fw_out.pcap"),
        ("a_tx.pcap", "ark_fw_in.pcap"),
        ("b_rx.pcap", "b_rx.pcap"),
        ("b_tx.pcap", "b_tx.pcap"),
        ("mirror.pcap", "mirror.pcap"),
        ("host.pcap", "host.pcap"),
    ):
        shutil.copy2(oct8 / src, swapped / dst)

    # Ambiguity folders: run the reference test with create_case wrapped so each folder it builds is copied.
    captured = []
    real_create_case = case_setup.create_case

    def capture_create_case(folder, **kwargs):
        folder = Path(folder)
        dst = fixtures / folder.name
        if not dst.exists():
            _copy_pcaps(folder, dst)
            captured.append(folder.name)
        return real_create_case(folder, **kwargs)

    test_case_setup.create_case = capture_create_case
    tmp = out / "tmp_ambiguity"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir()
    test_case_setup.test_auto_assemble_stops_on_ambiguous_order_or_direction(tmp, TSHARK)
    test_case_setup.create_case = real_create_case
    shutil.rmtree(tmp, ignore_errors=True)
    print("fixtures:", ", ".join(sorted(p.name for p in fixtures.iterdir())))


# ---------------------------------------------------------------------------------------------- run


def single_thread_duckdb() -> None:
    """Project.connect uses min(8, cpu_count - 1) DuckDB threads; parity runs use one on both sides.

    Multi-threaded aggregation breaks ties nondeterministically (``ORDER BY spread_us LIMIT 200`` and
    ``arg_min(frame, abs(delta))`` in the suggestion SQL): two reference runs on the same project.duckdb can order
    suggestions and pick evidence frames differently. Only store.py's view of ``os`` is replaced.
    """
    import types

    import packetbreaker.store as store

    store.os = types.SimpleNamespace(cpu_count=lambda: 2)


def run(folder: Path, work: Path, post_ingest_sql: str | None) -> None:
    import packetbreaker.analysis.report as report_mod
    import packetbreaker.case_setup as case_setup
    from packetbreaker.store import Project
    from packetbreaker.topology import assemble_topology, confirm_suggested_topology, suggest_topology

    report_mod.analyze = _stub_analyze
    single_thread_duckdb()
    ref = work / "ref"
    snap = work / "snapshot"
    for d in (ref, snap):
        shutil.rmtree(d, ignore_errors=True)
        d.mkdir(parents=True)
    project_dir = folder.resolve().with_name(folder.resolve().name + ".packetbreaker")
    shutil.rmtree(project_dir, ignore_errors=True)

    real_ingest = case_setup.ingest_many

    def ingest_and_snapshot(project, paths, **kwargs):
        try:
            return real_ingest(project, paths, **kwargs)
        finally:
            if post_ingest_sql:
                with project.connect() as db:
                    db.execute(post_ingest_sql)
            shutil.copy2(project.path / "project.duckdb", snap / "project.duckdb")

    case_setup.ingest_many = ingest_and_snapshot
    project = case_setup.create_case(folder, tshark=TSHARK)
    case_setup.ingest_many = real_ingest
    with project.connect() as db:
        case_context = project.get(db, "case_context")
    _dump(ref / "case.json", dict(case_context=case_context, overview=case_setup.inventory_overview(project.inventory())))
    shutil.copy2(project.path / "project.duckdb", ref / "case.duckdb")

    def fresh(src: Path, name: str) -> Project:
        d = work / "tmp" / name
        shutil.rmtree(d, ignore_errors=True)
        d.mkdir(parents=True)
        shutil.copy2(src, d / "project.duckdb")
        return Project(d)

    _dump(ref / "suggest.json", suggest_topology(fresh(snap / "project.duckdb", "suggest")))
    _dump(ref / "assemble.json", assemble_topology(fresh(snap / "project.duckdb", "assemble")))
    _dump(ref / "reassemble.json", assemble_topology(fresh(ref / "case.duckdb", "reassemble")))

    def confirm(name: str, group):
        p = fresh(ref / "case.duckdb", name)
        try:
            result = dict(ok=confirm_suggested_topology(p, group=group))
        except ValueError as exc:
            result = dict(error=str(exc))
        _dump(ref / f"{name}.json", result)
        shutil.copy2(p.path / "project.duckdb", ref / f"{name}.duckdb")

    confirm("confirm", None)
    suggested = case_context.get("suggested_topology") or {}
    ready = [g["name"] for g in suggested.get("groups") or [] if not g.get("ambiguous")]
    if ready:
        confirm("confirm_group", ready[0])
    meta = dict(confirm_group=ready[0] if ready else None, project_dir=str(project_dir))
    _dump(work / "meta.json", meta)
    shutil.rmtree(work / "tmp", ignore_errors=True)


def main(argv):
    if sys.version_info[:2] != (3, 12):
        raise SystemExit("run with the frozen PacketBreaker interpreter (CPython 3.12)")
    if argv[:1] == ["fixtures"]:
        demo_root = Path(argv[3]) if len(argv) > 3 and argv[2] == "--demo-root" else None
        build_fixtures(Path(argv[1]), demo_root)
    elif argv[:1] == ["run"]:
        sql = argv[4] if len(argv) > 4 and argv[3] == "--post-ingest-sql" else None
        run(Path(argv[1]), Path(argv[2]), sql)
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
