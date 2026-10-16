"""Expected output of ``PacketAnalysisEndToEndTest`` (reference-checked, then frozen as Java output).

Run with the frozen reference interpreter:

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/e2e_fixtures.py --work SCRATCH

The three synthetic inputs are built by the Java test helper ``E2eFixtures`` (captures are never committed; the
test builds them at run time): ``folder_b`` (checked byte for byte against the reference golden test's
``_build_folder_b``; case mode), ``chain_loss`` (three-point topology, one request lost and retransmitted) and
``nat_confirm`` (source NAT, suggestions confirmed and analysed again). For each input the reference runs the way the
corpus builds its cases (reference.py: ``create_case`` with counter capture ids, or sequential ingest + ``analyze``
with the topology), the Java ``PacketAnalysisMain`` runs on the same files, and both are canonicalized
(``canonical.py``) and compared section by section; the expected files are only written when every section is
identical. Expected form: the Java output in strict form (capture ids -> ``<capture:FILE>``, ``generated_at`` /
``exported_at`` dropped, 32-hex ids masked, list order and number types kept), private IPv4 literals masked as the
committed goldens are (``canonical.mask_for_commit``), gzip: ``e2e/<name>/expected-report.json.gz`` and
``expected-export.json.gz`` (the export's report snapshot only with the sections the export adds: transport, dns,
field_diffs; the test asserts the rest equals the report).
"""

from __future__ import annotations

import argparse
import gzip
import json
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import canonical as canon  # noqa: E402
import compare  # noqa: E402
import corpus_run  # noqa: E402

REPO = HERE.parents[2]
REFERENCE = Path.home() / "Codo" / "PacketBreaker"
TARGET = REPO / "ui2/packet-analysis/src/test/resources/packetanalysis/e2e"
FIXTURES = ("folder_b", "chain_loss", "nat_confirm")
WRITER = "com.securityexpert.nexus.ui2.packetanalysis.E2eFixtures"
EXPORT_SECTIONS = {"transport", "dns", "field_diffs"}


def _setup():
    sys.path.insert(0, str(REFERENCE / "tests"))
    sys.path.insert(0, str(REFERENCE / "src"))
    import conftest  # noqa: F401  every duckdb.connect gets threads=1 (the corpus setting)


def build_reference(name: str, inputs: Path, work: Path):
    """Run the reference on the Java-built input; returns (project, report, export)."""
    from packetbreaker.analysis import analyze
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.case_setup import create_case
    from packetbreaker.export import export_data
    from packetbreaker.ingest import find_tshark
    from packetbreaker.store import Project
    from packetbreaker.synthetic import bind_capture_ids

    wire = work / "input"
    shutil.copytree(inputs, wire)
    if name == "folder_b":
        import packetbreaker.ingest as ingest_mod
        import test_golden

        built = work / "reference-built"
        test_golden._build_folder_b(built)
        for path in sorted(built.iterdir()):
            if (wire / path.name).read_bytes() != path.read_bytes():
                raise SystemExit(f"folder_b: Java-built {path.name} differs from the reference _build_folder_b")
        counter = iter(range(1, 10000))
        original = ingest_mod.uuid.uuid4
        ingest_mod.uuid.uuid4 = lambda: uuid.UUID(int=next(counter))  # tests/test_golden.py::_deterministic_uuids
        try:
            project = create_case(wire, tshark=find_tshark())
        finally:
            ingest_mod.uuid.uuid4 = original
        with project.connect() as db:
            report = project.get(db, "report")
        return project, report, export_data(project)
    topology = json.loads((wire / "topology.json").read_text(encoding="utf-8"))
    files = [str(wire / p["capture_id"]) for p in topology["points"]]
    project = Project(work / "project")
    ids = ingest_many(project, files, parallel=False)
    bind_capture_ids(topology, {Path(p).name: cid for p, cid in zip(files, ids)})
    report = analyze(project, topology)
    if name == "nat_confirm":
        topology["nat_mappings"] = [
            {k: s[k] for k in ("point_a", "point_b", "tuple_a", "tuple_b")} for s in report["nat_suggestions"]
        ]
        report = analyze(project, topology)
    return project, report, export_data(project)


def java_args(name: str, inputs: Path, out: Path) -> list[str]:
    args = ["--out", str(out), "--threads", "1", "--deterministic-capture-ids"]
    topology = inputs / "topology.json"
    if not topology.is_file():
        return args + [str(inputs)]
    files = [str(inputs / p["capture_id"]) for p in json.loads(topology.read_text(encoding="utf-8"))["points"]]
    args += ["--topology", str(topology), "--no-prefilter"]
    if name == "nat_confirm":
        args.append("--confirm-suggested-nat")
    return args + files


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--classpath", help="packet-analysis runtime classpath (default: gradle)")
    args = parser.parse_args(argv)
    _setup()
    ui2 = REPO / "ui2"
    subprocess.run(["./gradlew", "-q", ":packet-analysis:testClasses"], cwd=ui2, check=True)
    classpath = args.classpath or subprocess.run(
        ["./gradlew", "-q", ":packet-analysis:printRuntimeClasspath"], cwd=ui2, capture_output=True,
        text=True, check=True).stdout.strip().splitlines()[-1]
    classpath += ":" + str(ui2 / "packet-analysis/build/classes/java/test")
    work = args.work.resolve() / "e2e-fixtures"
    shutil.rmtree(work, ignore_errors=True)
    subprocess.run(["java", "-cp", classpath, WRITER, str(work / "inputs")], check=True)
    shutil.rmtree(TARGET, ignore_errors=True)
    ok = True
    total = 0
    for name in FIXTURES:
        inputs = work / "inputs" / name
        project, report, export = build_reference(name, inputs, work / name / "reference")
        with project.connect() as db:
            ref_ids = dict(db.execute("SELECT id,name FROM captures").fetchall())
        out = work / name / "java"
        cmd = ["java", "-cp", classpath, corpus_run.MAIN] + java_args(name, inputs, out)
        subprocess.run(cmd, check=True, capture_output=True, text=True)
        java_ids = json.loads((out / "capture-map.json").read_text())
        java_report = json.loads((out / "report.json").read_text())
        java_export = json.loads((out / "findings-export.json").read_text())
        diffs = []
        for label, ref_value, java_value in (("report", report, java_report), ("export", export, java_export)):
            a = corpus_run.engine_neutral(canon.canonicalize(ref_value, ref_ids))
            b = corpus_run.engine_neutral(canon.canonicalize(java_value, java_ids))
            pairs = [(label, a, b)] + ([("export.report", a["report"], b["report"])] if label == "export" else [])
            for where, x, y in pairs:
                for key in sorted(set(x) | set(y)):
                    if where == "export" and key == "report":
                        continue
                    if canon.dumps(x.get(key)) != canon.dumps(y.get(key)):
                        first = compare.differences(x.get(key), y.get(key), f"$.{key}", limit=3)
                        diffs.append(f"{where}.{key} {first}")
        if diffs:
            ok = False
            print(f"{name}: Java differs from the reference in {', '.join(diffs)}; expected output not written")
            continue
        strict_report = canon.mask_for_commit(corpus_run.strict_form(java_report, java_ids))
        strict_export = canon.mask_for_commit(corpus_run.strict_form(java_export, java_ids))
        # The export's report snapshot repeats the report except for the sections the export adds; only those are
        # frozen in the export file (the test asserts the rest equals the report).
        snapshot = strict_export["report"]
        assert set(snapshot) == (set(strict_report) - {"findings"}) | EXPORT_SECTIONS, name
        assert all(snapshot[k] == strict_report[k] for k in snapshot if k not in EXPORT_SECTIONS), name
        strict_export["report"] = {k: snapshot[k] for k in snapshot if k in EXPORT_SECTIONS}
        target = TARGET / name
        target.mkdir(parents=True)
        for label, strict in (("report", strict_report), ("export", strict_export)):
            data = json.dumps(strict, separators=(",", ":"), ensure_ascii=False).encode("utf-8") + b"\n"
            (target / f"expected-{label}.json.gz").write_bytes(gzip.compress(data, mtime=0))
        size = sum(p.stat().st_size for p in target.iterdir())
        total += size
        verdict = java_report.get("verdict")
        print(f"{name}: Java identical to the reference (canonical); {len(java_report.get('findings', []))} findings, "
              f"verdict {str(verdict)[:60]!r}; expected {size} bytes")
    print(f"committed expected output: {total} bytes")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
