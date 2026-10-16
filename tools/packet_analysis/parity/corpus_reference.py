"""Reference side of corpus_run.py: rebuild one frozen corpus case with the frozen reference (timing runs).

Run with the frozen reference interpreter (corpus_run.py ``--timing`` calls it under ``/usr/bin/time -l``):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/corpus_reference.py \
        --corpus WORK/corpus/run1 --work WORK --case CASE --out DIR [--threads N]

The project is rebuilt from the ARCHIVED inputs exactly as ``reference.py`` built the case (same file order,
workers, pre-filter, topology, confirm step, ``create_case`` with counter capture ids), then the FindingsExport is
produced; every DuckDB connection runs ``--threads`` threads. The reference tree is a ``git archive`` export of
the frozen commit under ``WORK`` (the checkout is never modified).
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpus_inputs  # noqa: E402
import reference as refmod  # noqa: E402  (export_frozen_tree, FROZEN_COMMIT, DEFAULT_REPO)

REPO = Path(__file__).resolve().parents[3]
MANIFEST = REPO / "ui2/packet-analysis/src/test/resources/parity/manifest.json"
CASE_SETUP = {"folder_b_replica", "oct8_replica"}
WORKERS4 = {"demo", "nat", "offload", "integrity_all", "dns_diagnosis", "fifty_flow_replica"}
_LOADED = None


def _setup(pb_root: str, threads: int | None) -> None:
    """Import the frozen tree. threads=None: PacketBreaker's test harness (every connection one thread)."""
    global _LOADED
    if _LOADED is not None:
        return
    sys.path.insert(0, str(Path(pb_root) / "tests"))
    sys.path.insert(0, str(Path(pb_root) / "src"))
    if threads is None:
        import conftest  # noqa: F401  every duckdb.connect gets threads=1, as in the corpus build
    else:
        import duckdb

        original = duckdb.connect

        def connect(*args, **kwargs):
            kwargs["config"] = {**kwargs.get("config", {}), "threads": str(threads)}
            return original(*args, **kwargs)

        duckdb.connect = connect
    import packetbreaker

    if not str(Path(packetbreaker.__file__).resolve()).startswith(str(Path(pb_root).resolve())):
        raise RuntimeError(f"packetbreaker imported from {packetbreaker.__file__}, not the frozen tree")
    _LOADED = pb_root


def _files(input_dir: Path) -> list[Path]:
    return [input_dir / n for n in corpus_inputs.capture_files(input_dir)]


def build(case: dict, input_dir: Path, case_dir: Path):
    """Rebuild the reference project from the archived inputs; returns (project, final report)."""
    from packetbreaker.analysis import analyze
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.case_setup import create_case
    from packetbreaker.ingest import find_tshark
    from packetbreaker.store import Project
    from packetbreaker.synthetic import bind_capture_ids

    name = case["name"]
    if name in CASE_SETUP:
        import uuid

        import packetbreaker.ingest as ingest_mod

        wire = case_dir / "input"
        wire.mkdir(parents=True)
        for p in _files(input_dir):
            shutil.copy2(p, wire / p.name)
        counter = iter(range(1, 10000))
        original = ingest_mod.uuid.uuid4
        ingest_mod.uuid.uuid4 = lambda: uuid.UUID(int=next(counter))  # tests/test_golden.py::_deterministic_uuids
        try:
            project = create_case(wire, tshark=find_tshark())
        finally:
            ingest_mod.uuid.uuid4 = original
        with project.connect() as db:
            return project, project.get(db, "report")
    files = _files(input_dir)
    topology = corpus_inputs.analysis_topology(input_dir, name)
    project = Project(case_dir / "project")
    if name in WORKERS4:
        ids = ingest_many(project, [str(p) for p in files], tshark=find_tshark(), workers=4)
    else:
        ids = ingest_many(project, [str(p) for p in files], parallel=False)
    bind_capture_ids(topology, {p.name: cid for p, cid in zip(files, ids)})
    if case["construction"].get("confirm_demo_nat"):
        first = analyze(project, topology)
        topology["nat_mappings"] = [
            {k: s[k] for k in ("point_a", "point_b", "tuple_a", "tuple_b")} for s in first["nat_suggestions"]
        ]
    return project, analyze(project, topology)


def plain_case(pb_root: str, case: dict, corpus: Path, out: Path, threads: int) -> None:
    """Reference pipeline without hooks: ingest, analysis, FindingsExport (timing runs)."""
    _setup(pb_root, threads)
    from packetbreaker.export import export_data

    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    started = time.monotonic()
    project, report = build(case, corpus / case["name"] / "input", out)
    export = export_data(project)
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    (out / "findings-export.json").write_text(json.dumps(export, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(dict(case=case["name"], seconds=round(time.monotonic() - started, 3), threads=threads)))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--corpus", type=Path, required=True, help="extracted corpus run1/ directory")
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--repo", type=Path, default=refmod.DEFAULT_REPO, help="frozen PacketBreaker (read-only)")
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=1)
    args = parser.parse_args(argv)
    pb_root = str(refmod.export_frozen_tree(args.repo, refmod.FROZEN_COMMIT, args.work.resolve()))
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    cases = [json.loads((args.manifest.parent / e["file"]).read_text(encoding="utf-8")) for e in manifest["cases"]]
    case = next(c for c in cases if c["name"] == args.case)
    plain_case(pb_root, case, args.corpus.resolve(), args.out.resolve(), args.threads)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
