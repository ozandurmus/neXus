"""Topology / case-setup parity: frozen reference vs the Java port (ui2/packet-analysis ...topology).

Run with the frozen PacketBreaker interpreter (it needs the reference's duckdb module to read stored settings):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/topology_compare.py OUT [--regen]

Steps (each skipped when its output exists, unless --regen):

1. ``packetbreaker demo OUT/demo/<scenario> --scenario <scenario>`` for the demo scenario set (+ ``--ipv6`` demo).
2. ``topology_reference.py fixtures OUT``: capture folders OUT/fixtures/<name> (demo pcaps plus the multi-path,
   oct8-style and ambiguity folders the reference tests build with their own builders).
3. ``topology_reference.py run``: per folder, the reference outputs in OUT/work/<name>/ref and the ingest snapshot.
4. The Java main on copies of the same snapshot: suggest, assemble, case (scaffold + auto assembly with the
   analysis stubbed like the reference run), then reassemble and confirm on the Java post-case project.

Comparison: the JSON text of every step must be byte-identical (both sides print ``json.dumps(x, indent=2)``), and
the stored settings rows written by case/confirm (topology, suggested_topology, unconfirmed_topology, case_context,
report) must be identical strings. On a difference the first differing JSON paths are printed with capture ids
masked as ``<capture:file name>``. Exit status 1 when any input set differs.
"""

import json
import os
import re
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
UI2 = REPO / "ui2"
PB_ROOT = REPO.parent / "PacketBreaker"
PB_PYTHON = PB_ROOT / ".venv" / "bin" / "python"
PB_CLI = PB_ROOT / ".venv" / "bin" / "packetbreaker"
TSHARK_DIR = "/Applications/Wireshark.app/Contents/MacOS"
REFERENCE = Path(__file__).with_name("topology_reference.py")
MAIN = "com.securityexpert.nexus.ui2.packetanalysis.topology.TopologyMain"

DEMO_SCENARIOS = [
    ("demo", []),
    ("nat", []),
    ("pilot", []),
    ("real_case_2", []),
    ("realistic_capture_miss", []),
    ("offload", []),
    ("udp_transactions", []),
    ("demo_ipv6", ["--scenario", "demo", "--ipv6"]),
]
SETTINGS_KEYS = ("topology", "suggested_topology", "unconfirmed_topology", "case_context", "report")
EMPTY_SQL = "DELETE FROM packets WHERE capture_id=(SELECT id FROM captures WHERE name='hop_mostly_excluded.pcap')"
HEX_ID = re.compile(r"(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])")


def env():
    e = dict(os.environ)
    e["PATH"] = TSHARK_DIR + os.pathsep + e.get("PATH", "")
    return e


def run(cmd, **kw):
    return subprocess.run([str(c) for c in cmd], check=True, env=env(), **kw)


# ------------------------------------------------------------------------------------------------ reference side


def generate(out: Path, regen: bool) -> list[str]:
    demo = out / "demo"
    jobs = []
    for name, extra in DEMO_SCENARIOS:
        d = demo / name
        if regen or not (d / "report.json").exists():
            shutil.rmtree(d, ignore_errors=True)
            jobs.append([PB_CLI, "demo", d, *(extra or ["--scenario", name])])
    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(lambda c: run(c, stdout=subprocess.DEVNULL), jobs))
    if regen or not (out / "fixtures").exists():
        shutil.rmtree(out / "fixtures", ignore_errors=True)
        run([PB_PYTHON, "-I", REFERENCE, "fixtures", out, "--demo-root", demo])
        shutil.copytree(out / "fixtures" / "amb_excluded", out / "fixtures" / "amb_excluded_empty")
    names = sorted(p.name for p in (out / "fixtures").iterdir() if p.is_dir() and not p.name.endswith(".packetbreaker"))

    def reference(name):
        work = out / "work" / name
        if regen or not (work / "meta.json").exists():
            extra = ["--post-ingest-sql", EMPTY_SQL] if name == "amb_excluded_empty" else []
            with open(out / "work" / f"{name}.log", "w") as log:
                run([PB_PYTHON, "-I", REFERENCE, "run", out / "fixtures" / name, work, *extra], stdout=log, stderr=log)

    (out / "work").mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(reference, names))
    return names


# ------------------------------------------------------------------------------------------------ Java side


def java_classpath() -> str:
    run([UI2 / "gradlew", "-q", "-p", UI2, ":packet-analysis:classes"])
    module = UI2 / "packet-analysis"
    parts = [module / "build" / "classes" / "java" / "main", module / "build" / "resources" / "main"]
    cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
    for line in (module / "gradle.lockfile").read_text().splitlines():
        if "=" not in line or line.startswith("#") or "runtimeClasspath" not in line.split("=", 1)[1]:
            continue
        group, artifact, version = line.split("=", 1)[0].split(":")
        jars = sorted(
            j for j in (cache / group / artifact / version).glob("*/*.jar") if not j.name.endswith("-sources.jar")
        )
        if not jars and list((cache / group / artifact / version).glob("*/*.pom")):
            continue  # a BOM: constraints only, no jar
        if not jars:
            raise SystemExit(f"{group}:{artifact}:{version} not in the Gradle cache; run a Gradle build first")
        parts.append(jars[0])
    return os.pathsep.join(str(p) for p in parts)


def java(cp: str, args: list, out_file: Path) -> None:
    with open(out_file, "wb") as f:
        subprocess.run(["java", "-Dpacketanalysis.duckdb.threads=1", "-cp", cp, MAIN, *[str(a) for a in args]],
                       check=True, stdout=f)


def fresh(src: Path, dst: Path) -> Path:
    shutil.rmtree(dst, ignore_errors=True)
    dst.mkdir(parents=True)
    shutil.copy2(src, dst / "project.duckdb")
    return dst


def java_side(cp: str, out: Path, name: str) -> Path:
    work = out / "work" / name
    jdir = work / "java"
    shutil.rmtree(jdir, ignore_errors=True)
    jdir.mkdir()
    snap = work / "snapshot" / "project.duckdb"
    meta = json.loads((work / "meta.json").read_text())
    java(cp, ["suggest", "--project", fresh(snap, jdir / "p_suggest")], jdir / "suggest.json")
    java(cp, ["assemble", "--project", fresh(snap, jdir / "p_assemble")], jdir / "assemble.json")
    case = fresh(snap, jdir / "p_case")
    java(cp, ["case", "--project", case, "--folder", out / "fixtures" / name], jdir / "case.json")
    shutil.copy2(case / "project.duckdb", jdir / "case.duckdb")
    java(cp, ["assemble", "--project", fresh(jdir / "case.duckdb", jdir / "p_reassemble")], jdir / "reassemble.json")
    p = fresh(jdir / "case.duckdb", jdir / "p_confirm")
    java(cp, ["confirm", "--project", p], jdir / "confirm.json")
    shutil.copy2(p / "project.duckdb", jdir / "confirm.duckdb")
    if meta.get("confirm_group"):
        p = fresh(jdir / "case.duckdb", jdir / "p_confirm_group")
        java(cp, ["confirm", "--project", p, "--group", meta["confirm_group"]], jdir / "confirm_group.json")
        shutil.copy2(p / "project.duckdb", jdir / "confirm_group.duckdb")
    for d in jdir.glob("p_*"):
        shutil.rmtree(d)
    return jdir


# ------------------------------------------------------------------------------------------------ comparison


def settings(db_path: Path) -> dict:
    import duckdb

    con = duckdb.connect(str(db_path), read_only=True)
    try:
        return dict(con.execute("SELECT key, value FROM settings").fetchall())
    finally:
        con.close()


def capture_names(db_path: Path) -> dict:
    import duckdb

    con = duckdb.connect(str(db_path), read_only=True)
    try:
        return {cid: f"<capture:{n}>" for cid, n in con.execute("SELECT id, name FROM captures").fetchall()}
    finally:
        con.close()


def mask(obj, ids):
    if isinstance(obj, dict):
        return {mask(k, ids): mask(v, ids) for k, v in obj.items()}
    if isinstance(obj, list):
        return [mask(v, ids) for v in obj]
    if isinstance(obj, str):
        return HEX_ID.sub(lambda m: ids.get(m.group(0), "<id>"), obj)
    return obj


def differences(a, b, path="$", out=None, limit=8):
    out = [] if out is None else out
    if len(out) >= limit:
        return out
    if type(a) is not type(b):
        out.append(f"{path}: {json.dumps(a)[:100]} != {json.dumps(b)[:100]}")
    elif isinstance(a, dict):
        if list(a) != list(b) and set(a) == set(b):
            out.append(f"{path}: key order {list(a)} != {list(b)}")
        for k in list(a) + [k for k in b if k not in a]:
            if k not in a or k not in b:
                out.append(f"{path}.{k}: {'missing in reference' if k not in a else 'missing in Java'}")
            else:
                differences(a[k], b[k], f"{path}.{k}", out, limit)
    elif isinstance(a, list):
        if len(a) != len(b):
            out.append(f"{path}: {len(a)} items in reference, {len(b)} in Java")
        for i, (x, y) in enumerate(zip(a, b)):
            differences(x, y, f"{path}[{i}]", out, limit)
    elif a != b or repr(a) != repr(b):
        out.append(f"{path}: {json.dumps(a)[:100]} != {json.dumps(b)[:100]}")
    return out


def compare(out: Path, name: str) -> list[str]:
    work = out / "work" / name
    ref, jdir = work / "ref", work / "java"
    ids = capture_names(work / "snapshot" / "project.duckdb")
    problems = []
    for f in sorted(ref.glob("*.json")):
        jf = jdir / f.name
        if not jf.exists():
            problems.append(f"{f.name}: missing Java output")
            continue
        rt, jt = f.read_bytes(), jf.read_bytes()
        if rt != jt:
            diffs = differences(mask(json.loads(rt), ids), mask(json.loads(jt), ids))
            problems.append(f"{f.name}: text differs" + "".join(f"\n      {d}" for d in diffs or ["(formatting only)"]))
    for db in sorted(ref.glob("*.duckdb")):
        rs, js = settings(db), settings(jdir / db.name)
        for key in SETTINGS_KEYS:
            if rs.get(key) != js.get(key):
                a = json.loads(rs[key]) if rs.get(key) else None
                b = json.loads(js[key]) if js.get(key) else None
                diffs = differences(mask(a, ids), mask(b, ids))
                problems.append(f"{db.stem} settings[{key}] differs" + "".join(f"\n      {d}" for d in diffs))
    return problems


def main(argv):
    if sys.version_info[:2] != (3, 12):
        raise SystemExit("run with the frozen PacketBreaker interpreter (CPython 3.12)")
    if not argv:
        raise SystemExit(__doc__)
    out = Path(argv[0]).resolve()
    regen = "--regen" in argv
    names = generate(out, regen)
    cp = java_classpath()
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda n: java_side(cp, out, n), names))
    failed = 0
    for name in names:
        problems = compare(out, name)
        steps = sorted(p.stem for p in (out / "work" / name / "ref").glob("*.json"))
        status = (json.loads((out / "work" / name / "ref" / "assemble.json").read_text()) or {}).get("status")
        if problems:
            failed += 1
            print(f"DIFF      {name} ({status})")
            for p in problems:
                print(f"    {p}")
        else:
            print(f"IDENTICAL {name} ({status}; {', '.join(steps)} + stored settings)")
    print(f"{len(names) - failed}/{len(names)} input sets identical")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
