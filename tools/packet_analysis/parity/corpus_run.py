"""End-to-end corpus parity: the all-Java pipeline (PacketAnalysisMain) against the frozen parity corpus.

Run with the frozen reference interpreter (it needs the ``duckdb`` module of the reference version; PacketBreaker
itself is not imported and the reference is not executed):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/corpus_run.py \
        --archive /path/to/packet-analysis-parity-corpus.tar.gz --work /path/to/scratch/e2e \
        [--jobs 5] [--only CASE ...] [--reuse] [--timing 5]

The archive is verified against the SHA-256 in ``ui2/packet-analysis/src/test/resources/parity/manifest.json`` and
extracted under ``--work`` (never into the repository). For every case the archived captures are run through
``PacketAnalysisMain`` (Java ingest -> topology / case setup -> engine -> report + FindingsExport) the way the
reference built the case (``construction`` in the case file, see reference.py):

* ``cli`` cases (``packetbreaker demo``): files in generator order, pre-filter off, sequential ingest, the
  generator topology (capture file names bound to the Java capture ids; corpus_inputs.py); ``--confirm-demo-nat``
  cases analyse, confirm every NAT suggestion and analyse again (``--confirm-suggested-nat``);
* tier-1 generator cases (``demo``, ``nat``, ``offload``, ``integrity_all``, ``dns_diagnosis``,
  ``fifty_flow_replica``): the same with 4 ingest workers, as tests/test_golden.py does;
* ``folder_b_replica``, ``oct8_replica``: the reference ``create_case`` (case mode: default points, pre-filter
  on, automatic assembly).

Capture ids are numbered 1, 2, ... in input order (``--deterministic-capture-ids``, the reference golden tests'
counter ids); they are opaque to the comparison, which maps every id to its file name.

Levels (Java at DuckDB threads=1, as the corpus):

* L1: ``canonical.L1_SQL`` on the Java ``project.duckdb`` against the case file's ``l1``;
* L2: the final ``obs``, ``observation_matches``, ``events``, ``flow_summary`` and ``clock_values`` tables, dumped
  by the Java run, canonicalized by ``canonical.l2_select_sql`` and hashed by ``canonical.l2_hash_sql``; on a hash
  mismatch the rows are compared with the archived reference Parquet to name the differing columns;
* L3: the Java report and FindingsExport canonicalized by ``canonical.canonicalize``; every top-level section
  (and every section of the export's report snapshot) compared with the archived canonical report / export.
  ``engine_version`` is the engine identity and is masked; the Java report's own ``unknown_sections`` and
  ``engine_notes`` must be empty and are then dropped.

Status per case: MATCH (all levels identical); RECORDED-TIES (the only differences are tie-breaks recorded in
ui2/packet-analysis/DETERMINISM.md, accepted by corpus_ties.py exactly as the lane parity scripts accept them);
DIFF (first differing level/table/path).

Determinism (contract rule "same input, same output"): every case also runs with DuckDB threads=4; L1, L2 and the
report and export (strict form: capture ids named, generation times dropped, other 32-hex ids masked, list order,
key order and number types kept) must be identical to the threads=1 run.

``--timing N``: wall time and peak RSS (``/usr/bin/time -l``) of the reference (corpus_reference.py) and of Java on
the N largest cases (input bytes), both at DuckDB threads=1 and 4.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import canonical as canon  # noqa: E402
import corpus_inputs  # noqa: E402
import corpus_ties as ties  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
MANIFEST = REPO / "ui2/packet-analysis/src/test/resources/parity/manifest.json"
MAIN = "com.securityexpert.nexus.ui2.packetanalysis.PacketAnalysisMain"
TIER1_GENERATOR = {"demo", "nat", "offload", "integrity_all", "dns_diagnosis", "fifty_flow_replica"}
TIER1_CASE_SETUP = {"folder_b_replica", "oct8_replica"}
L2_TABLES = ("obs", "observation_matches", "events", "flow_summary", "clock_values")
# Report keys the Java engine adds about itself (stub detectors, stage notes): compared only when non-empty.
JAVA_ONLY = {"unknown_sections", "engine_notes"}
IDENTITY = {"engine_version"}
HEX32 = re.compile(r"(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])")


# ---------------------------------------------------------------- corpus


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract_corpus(archive: Path, manifest: dict, target: Path) -> Path:
    """Verify the archive SHA-256 against the manifest and extract it once under ``target``."""
    marker = target / ".extracted-sha256"
    expected = manifest["archive"]["sha256"]
    if marker.is_file() and marker.read_text().strip() == expected and (target / "run1").is_dir():
        return target / "run1"
    actual = sha256_file(archive)
    if actual != expected:
        raise SystemExit(f"corpus archive SHA-256 {actual} != manifest {expected}")
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        tar.extractall(target, filter="data")
    marker.write_text(expected + "\n")
    return target / "run1"


def load_case(manifest_path: Path, entry: dict) -> dict:
    return json.loads((manifest_path.parent / entry["file"]).read_text(encoding="utf-8"))


def java_command(case: dict, input_dir: Path, out: Path, threads: int, classpath: str) -> list[str]:
    name = case["name"]
    construction = case["construction"]
    cmd = [os.environ.get("JAVA", "java"), "-cp", classpath, MAIN, "--out", str(out), "--case-dir", str(out / "case"),
           "--threads", str(threads), "--dump-tables", str(out / "tables"), "--deterministic-capture-ids"]
    if name in TIER1_CASE_SETUP:
        return cmd + [str(input_dir)]
    topology = corpus_inputs.analysis_topology(input_dir, name)
    if construction.get("confirm_demo_nat"):
        cmd.append("--confirm-suggested-nat")
    out.mkdir(parents=True, exist_ok=True)
    topology_path = out / "input-topology.json"
    topology_path.write_text(json.dumps(topology, indent=2), encoding="utf-8")
    cmd += ["--topology", str(topology_path), "--no-prefilter"]
    if construction.get("kind") == "golden":
        if name not in TIER1_GENERATOR:
            raise ValueError(f"unknown tier-1 construction for {name}")
        cmd += ["--ingest-workers", "4"]
    return cmd + [str(input_dir / f) for f in corpus_inputs.capture_files(input_dir)]


def run_java(case: dict, corpus: Path, work: Path, threads: int, classpath: str, reuse: bool) -> dict:
    out = work / "java" / f"t{threads}" / case["name"]
    if reuse and (out / "run.json").is_file():
        return dict(out=out, seconds=json.loads((out / "run.json").read_text())["seconds"]["total"], reused=True)
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    cmd = java_command(case, corpus / case["name"] / "input", out, threads, classpath)
    started = time.monotonic()
    result = subprocess.run(cmd, capture_output=True, text=True)
    elapsed = time.monotonic() - started
    (out / "stdout.txt").write_text(result.stdout)
    (out / "stderr.txt").write_text(result.stderr)
    if result.returncode != 0:
        return dict(out=out, error=f"exit {result.returncode}: {(result.stderr or result.stdout)[-1500:]}", seconds=elapsed)
    return dict(out=out, seconds=elapsed)


# ---------------------------------------------------------------- levels


def engine_neutral(value):
    """Every (nested) report: ``engine_version`` masked, the engine's own empty bookkeeping keys dropped.

    ``engine_version`` is the engine identity, never compared. ``unknown_sections`` / ``engine_notes`` are Java-only
    report keys (stub detectors, stage notes); empty they say nothing the reference does not, non-empty they are a
    difference and stay in place.
    """
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            if "engine_version" in value and k in JAVA_ONLY and not v:
                continue
            out[k] = "<engine>" if k in IDENTITY and isinstance(v, str) else engine_neutral(v)
        return out
    if isinstance(value, list):
        return [engine_neutral(v) for v in value]
    return value


def java_l1(out: Path) -> dict:
    import duckdb

    with duckdb.connect(str(out / "case" / "project.duckdb"), read_only=True) as db:
        return {c: dict(rows=r, sha256=h) for c, r, h in db.execute(canon.L1_SQL).fetchall()}


def java_l2(out: Path, id_to_name: dict) -> dict:
    import duckdb

    result = {}
    mem = duckdb.connect()
    canon_dir = out / "tables-canonical"
    canon_dir.mkdir(exist_ok=True)
    schema_file = out / "tables" / "schema.json"
    table_schema = json.loads(schema_file.read_text(encoding="utf-8")) if schema_file.is_file() else {}
    for table in L2_TABLES:
        raw = out / "tables" / f"{table}.parquet"
        if not raw.is_file():
            result[table] = None
            continue
        source = "read_parquet('" + str(raw).replace("'", "''") + "')"
        schema = mem.execute(f"DESCRIBE SELECT * FROM {source}").fetchall()
        varchar = [c[0] for c in schema if c[1] == "VARCHAR"]
        target = canon_dir / f"{table}.parquet"
        mem.execute(f"COPY ({canon.l2_select_sql(source, varchar, id_to_name)}) TO '{target}' (FORMAT parquet)")
        rows, digest = mem.execute(canon.l2_hash_sql(str(target))).fetchone()
        # Column types as the table declares them (Parquet stores HUGEINT as DOUBLE), as the corpus records them.
        columns = table_schema.get(table) or [[c[0], c[1]] for c in schema]
        result[table] = dict(columns=columns, rows=rows, sha256=digest, path=target)
    mem.close()
    return result


def parquet_rows(path: Path):
    import duckdb

    with duckdb.connect() as mem:
        cur = mem.execute("SELECT * FROM read_parquet(?)", [str(path)])
        columns = [c[0] for c in cur.description]
        return columns, cur.fetchall()


def _cell(v):
    if isinstance(v, float):
        return "f:" + repr(0.0 if v == 0.0 else v)
    return v if v is None or isinstance(v, (bool, int, str)) else str(v)


def row_diff(ref_path: Path, java_path: Path) -> str:
    """Rows only in one side and the columns that differ between the first unmatched rows."""
    rc, rrows = parquet_rows(ref_path)
    jc, jrows = parquet_rows(java_path)
    if rc != jc:
        return f"columns ref={rc} java={jc}"
    a = Counter(json.dumps([_cell(x) for x in r]) for r in rrows)
    b = Counter(json.dumps([_cell(x) for x in r]) for r in jrows)
    only_ref, only_java = sorted((a - b).elements()), sorted((b - a).elements())
    columns = set()
    for x, y in zip(only_ref[:50], only_java[:50]):
        xa, ya = json.loads(x), json.loads(y)
        columns |= {rc[i] for i in range(len(rc)) if xa[i] != ya[i]}
    detail = f"rows ref={len(rrows)} java={len(jrows)} only_ref={len(only_ref)} only_java={len(only_java)}"
    if columns:
        detail += f" columns~{sorted(columns)[:8]}"
    if only_ref:
        detail += " ref=" + only_ref[0][:300]
    if only_java:
        detail += " java=" + only_java[0][:300]
    return detail


def compare_l2(case: dict, corpus: Path, java: dict) -> dict:
    tables = {}
    for table, ref in case["l2"].items():
        got = java.get(table)
        if "sha256" not in ref:
            tables[table] = dict(status="NOT_CAPTURED", detail=ref.get("reason", ""))
        elif got is None:
            tables[table] = dict(status="DIFF", detail="table absent in the Java run")
        elif got["columns"] != ref["columns"]:
            tables[table] = dict(status="DIFF", detail=f"columns ref={ref['columns']} java={got['columns']}")
        elif got["rows"] == ref["rows"] and got["sha256"] == ref["sha256"]:
            tables[table] = dict(status="MATCH", detail=f"{ref['rows']} rows")
        else:
            tables[table] = dict(status="DIFF",
                                 detail=row_diff(corpus / case["name"] / "l2" / f"{table}.parquet", got["path"]))
    return tables


def differences(expected, actual, path="$", out=None, limit=6):
    out = [] if out is None else out
    if len(out) >= limit:
        return out
    if type(expected) is not type(actual):
        out.append(f"{path}: {json.dumps(expected)[:120]} != {json.dumps(actual)[:120]}")
    elif isinstance(expected, dict):
        for key in sorted(set(expected) | set(actual)):
            if len(out) >= limit:
                break
            if key not in expected or key not in actual:
                out.append(f"{path}.{key}: {'missing in reference' if key not in expected else 'missing in java'}")
            else:
                differences(expected[key], actual[key], f"{path}.{key}", out, limit)
    elif isinstance(expected, list):
        if len(expected) != len(actual):
            out.append(f"{path}: {len(expected)} items in reference, {len(actual)} in java")
        for i, (e, a) in enumerate(zip(expected, actual)):
            if len(out) >= limit:
                break
            differences(e, a, f"{path}[{i}]", out, limit)
    elif expected != actual:
        out.append(f"{path}: {json.dumps(expected)[:120]} != {json.dumps(actual)[:120]}")
    return out


def compare_sections(ref: dict, java: dict) -> dict:
    sections = {}
    for key in sorted(set(ref) | set(java)):
        if key not in java:
            sections[key] = dict(status="DIFF", detail="missing in java")
        elif key not in ref:
            sections[key] = dict(status="DIFF", detail="not in reference")
        elif canon.dumps(ref[key]) == canon.dumps(java[key]):
            sections[key] = dict(status="MATCH")
        else:
            sections[key] = dict(status="DIFF", detail="; ".join(differences(ref[key], java[key], f"$.{key}")))
    return sections


def l3(case: dict, corpus: Path, out: Path, id_to_name: dict) -> dict:
    report = json.loads((out / "report.json").read_text(encoding="utf-8"))
    export = json.loads((out / "findings-export.json").read_text(encoding="utf-8"))
    ref_dir = corpus / case["name"] / "canonical"
    ref = dict(report=engine_neutral(json.loads((ref_dir / "report.json").read_text(encoding="utf-8"))),
               export=engine_neutral(json.loads((ref_dir / "findings-export.json").read_text(encoding="utf-8"))))
    java = dict(report=engine_neutral(canon.canonicalize(report, id_to_name)),
                export=engine_neutral(canon.canonicalize(export, id_to_name)))
    sections = {f"report.{k}": v for k, v in compare_sections(ref["report"], java["report"]).items()}
    top = compare_sections({k: v for k, v in ref["export"].items() if k != "report"},
                           {k: v for k, v in java["export"].items() if k != "report"})
    sections.update({f"export.{k}": v for k, v in top.items()})
    snapshot = compare_sections(ref["export"]["report"], java["export"]["report"])
    sections.update({f"export.report.{k}": v for k, v in snapshot.items()})
    for path, entry in sections.items():
        if entry["status"] == "DIFF":
            reason = ties.accept(case, path, ref, java)
            if reason:
                entry.update(status="RECORDED-TIES", tie=reason)
    return sections


def level_status(entries: dict) -> tuple[str, str]:
    diffs = [(k, v) for k, v in entries.items() if v["status"] == "DIFF"]
    if diffs:
        k, v = diffs[0]
        return "DIFF", f"{k}: {v.get('detail', '')}"
    tied = sorted({v["tie"] for v in entries.values() if v["status"] == "RECORDED-TIES"})
    if tied:
        return "RECORDED-TIES", "; ".join(tied)
    return "MATCH", ""


# ---------------------------------------------------------------- determinism


def strict_form(value, id_to_name):
    """Capture ids -> names, generation times dropped, other 32-hex ids masked; order and floats kept."""
    value = canon.strip_volatile(value, canon.capture_token_map(id_to_name))

    def mask(v):
        if isinstance(v, str):
            return HEX32.sub("<id>", v)
        if isinstance(v, list):
            return [mask(x) for x in v]
        if isinstance(v, dict):
            return {HEX32.sub("<id>", k): mask(x) for k, x in v.items()}
        return v

    return mask(value)


def determinism(out1: Path, out4: Path, l2_1: dict, l2_4: dict, l1_1: dict, l1_4: dict) -> tuple[bool, str]:
    def load(out):
        ids = json.loads((out / "capture-map.json").read_text(encoding="utf-8"))
        report = json.loads((out / "report.json").read_text(encoding="utf-8"))
        export = json.loads((out / "findings-export.json").read_text(encoding="utf-8"))
        return strict_form(report, ids), strict_form(export, ids)

    r1, e1 = load(out1)
    r4, e4 = load(out4)
    problems = []
    if l1_1 != l1_4:
        problems.append("L1")
    for t in L2_TABLES:
        a, b = l2_1.get(t), l2_4.get(t)
        if (a is None) != (b is None) or (a and (a["sha256"], a["rows"]) != (b["sha256"], b["rows"])):
            problems.append(f"L2.{t}")
    for key in sorted(set(r1) | set(r4)):
        if json.dumps(r1.get(key)) != json.dumps(r4.get(key)):
            problems.append(f"report.{key}")
    for key in sorted(set(e1) | set(e4)):
        if key == "report":
            for sub in sorted(set(e1["report"]) | set(e4["report"])):
                if json.dumps(e1["report"].get(sub)) != json.dumps(e4["report"].get(sub)):
                    problems.append(f"export.report.{sub}")
        elif json.dumps(e1.get(key)) != json.dumps(e4.get(key)):
            problems.append(f"export.{key}")
    return not problems, ", ".join(problems)


# ---------------------------------------------------------------- driver


def evaluate(case: dict, corpus: Path, work: Path, classpath: str, threads: list[int], reuse: bool) -> dict:
    runs = {t: run_java(case, corpus, work, t, classpath, reuse) for t in threads}
    first = runs[threads[0]]
    entry = dict(name=case["name"], tier=case["tier"], seconds={t: round(r["seconds"], 1) for t, r in runs.items()})
    errors = {t: r["error"] for t, r in runs.items() if "error" in r}
    if errors:
        entry["error"] = errors
        return entry
    out = first["out"]
    ids = json.loads((out / "capture-map.json").read_text(encoding="utf-8"))
    l1 = java_l1(out)
    entry["l1"] = {
        capture: dict(status="MATCH") if case["l1"].get(capture) == l1.get(capture)
        else dict(status="DIFF", detail=f"ref={case['l1'].get(capture)} java={l1.get(capture)}")
        for capture in sorted(set(case["l1"]) | set(l1))
    }
    l2 = java_l2(out, ids)
    entry["l2"] = compare_l2(case, corpus, l2)
    entry["l3"] = l3(case, corpus, out, ids)
    if len(threads) > 1:
        other = runs[threads[1]]["out"]
        ids4 = json.loads((other / "capture-map.json").read_text(encoding="utf-8"))
        ok, detail = determinism(out, other, l2, java_l2(other, ids4), l1, java_l1(other))
        entry["determinism"] = dict(identical=ok, detail=detail)
    for t in l2.values():
        if t:
            t.pop("path", None)
    return entry


def summarize(entry: dict) -> dict:
    if "error" in entry:
        return dict(status="ERROR", L1="ERROR", L2="ERROR", L3="ERROR", note=json.dumps(entry["error"])[:300],
                    determinism=None)
    levels = {"L1": level_status(entry["l1"]), "L2": level_status(entry["l2"]), "L3": level_status(entry["l3"])}
    status, note = "MATCH", ""
    for name, (s, d) in levels.items():
        if s == "DIFF":
            status, note = "DIFF", f"{name} {d}"
            break
        if s == "RECORDED-TIES" and status == "MATCH":
            status, note = "RECORDED-TIES", d
    det = entry.get("determinism")
    return dict(status=status, note=note, **{k: v[0] for k, v in levels.items()},
                determinism=None if det is None else ("IDENTICAL" if det["identical"] else "DIFF: " + det["detail"]))


def timing(cases: list[dict], corpus: Path, work: Path, classpath: str, count: int) -> list[dict]:
    """Wall time and peak RSS of reference and Java on the ``count`` largest cases (sequentially, one at a time)."""
    def size(case):
        return sum(p.stat().st_size for p in (corpus / case["name"] / "input").iterdir() if p.is_file())

    largest = sorted(cases, key=size, reverse=True)[:count]
    reference_py = Path.home() / "Codo" / "PacketBreaker" / ".venv" / "bin" / "python"
    rows = []
    for case in largest:
        row = dict(case=case["name"], input_mb=round(size(case) / 1e6, 1))
        for threads in (1, 4):
            for side in ("reference", "java"):
                out = work / "timing" / side / f"t{threads}" / case["name"]
                shutil.rmtree(out, ignore_errors=True)
                out.mkdir(parents=True)
                if side == "java":
                    cmd = java_command(case, corpus / case["name"] / "input", out, threads, classpath)
                    cmd = [c for c in cmd if c not in ("--dump-tables", str(out / "tables"))]
                else:
                    cmd = [str(reference_py), "-I", str(Path(__file__).resolve().parent / "corpus_reference.py"),
                           "--corpus", str(corpus), "--work", str(work), "--case", case["name"], "--out", str(out),
                           "--threads", str(threads)]
                started = time.monotonic()
                result = subprocess.run(["/usr/bin/time", "-l"] + cmd, capture_output=True, text=True)
                wall = time.monotonic() - started
                match = re.search(r"(\d+)\s+maximum resident set size", result.stderr)
                rss = int(match.group(1)) / 1e6 if match else None  # macOS reports bytes
                row[f"{side}_t{threads}"] = dict(seconds=round(wall, 1), rss_mb=None if rss is None else round(rss),
                                                 exit=result.returncode)
        rows.append(row)
        print(json.dumps(row), flush=True)
    return rows


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--work", type=Path, required=True, help="scratch directory (corpus extract, Java outputs)")
    parser.add_argument("--classpath", help="packet-analysis runtime classpath (default: gradle printRuntimeClasspath)")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--only", nargs="*")
    parser.add_argument("--threads", type=int, default=1, help="DuckDB threads of the compared run")
    parser.add_argument("--determinism-threads", type=int, default=4, help="0 skips the determinism run")
    parser.add_argument("--reuse", action="store_true", help="reuse existing Java outputs")
    parser.add_argument("--timing", type=int, default=0, help="time reference and Java on the N largest cases")
    parser.add_argument("--json", type=Path, help="write the full result here (default WORK/corpus-run.json)")
    args = parser.parse_args(argv)

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    work = args.work.resolve()
    corpus = extract_corpus(args.archive, manifest, work / "corpus")
    classpath = args.classpath or subprocess.run(
        ["./gradlew", "-q", ":packet-analysis:printRuntimeClasspath"], cwd=REPO / "ui2", capture_output=True,
        text=True, check=True).stdout.strip().splitlines()[-1]
    cases = [load_case(args.manifest, e) for e in manifest["cases"]]
    if args.only:
        cases = [c for c in cases if c["name"] in set(args.only)]
    if args.timing:
        rows = timing(cases, corpus, work, classpath, args.timing)
        (work / "timing.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
        print(f"\n{'case':34} {'MB':>5}  " + "  ".join(f"{s + '_t' + str(t):>18}" for t in (1, 4)
                                                    for s in ("reference", "java")))
        for r in rows:
            cells = [r[f"{s}_t{t}"] for t in (1, 4) for s in ("reference", "java")]
            print(f"{r['case']:34} {r['input_mb']:5}  " + "  ".join(
                f"{c['seconds']:7.1f}s {c['rss_mb'] or 0:6}MB{'' if c['exit'] == 0 else '!'}" for c in cells))
        return 0
    threads = [args.threads] + ([args.determinism_threads] if args.determinism_threads else [])
    entries = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(evaluate, c, corpus, work, classpath, threads, args.reuse): c["name"] for c in cases}
        for f in concurrent.futures.as_completed(futures):
            name = futures[f]
            try:
                entries[name] = f.result()
            except Exception as exc:  # report and continue: one broken case must not hide the others
                entries[name] = dict(name=name, error={"harness": f"{type(exc).__name__}: {exc}"})
            s = summarize(entries[name])
            print(f"{name}: {s['status']} (L1 {s['L1']} L2 {s['L2']} L3 {s['L3']}) det {s['determinism']}", flush=True)
    ordered = [entries[c["name"]] for c in cases]
    summary = {e["name"]: summarize(e) for e in ordered}
    (args.json or work / "corpus-run.json").write_text(
        json.dumps(dict(entries=ordered, summary=summary), indent=1, default=str), encoding="utf-8")

    width = max(len(n) for n in summary)
    print("\n" + f"{'case':{width}}  {'status':14} det   first divergence / recorded ties")
    for name, s in summary.items():
        det = "-" if s.get("determinism") is None else ("same" if s["determinism"] == "IDENTICAL" else "DIFF")
        print(f"{name:{width}}  {s['status']:14} {det:5} {(s['note'] or '')[:160]}")
    counts = Counter(s["status"] for s in summary.values())
    det_counts = Counter(("-" if s.get("determinism") is None else s["determinism"].split(":")[0])
                         for s in summary.values())
    print(f"\n{len(summary)} cases: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    print("determinism threads=1 vs threads=4: " + ", ".join(f"{k} {v}" for k, v in sorted(det_counts.items())))
    failed = any(s["status"] in ("DIFF", "ERROR") for s in summary.values())
    failed |= any(s.get("determinism") not in (None, "IDENTICAL") for s in summary.values())
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
