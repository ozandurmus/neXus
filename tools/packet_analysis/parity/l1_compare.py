#!/usr/bin/env python3
"""L1 ingest parity: the Java ingest must store the same `packets` rows as the frozen PacketBreaker ingest.

Run with the frozen PacketBreaker interpreter (it provides duckdb 1.5.6 for reading both projects):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/l1_compare.py \
        --captures DIR [--generate] [--derived] [--sets demo nat ...] [--work DIR]

For every capture set (a sub-folder of DIR holding .pcap/.pcapng files) both engines ingest the same files into
fresh projects under WORK/<set>/{reference,java}; then the tables are compared row by row with capture ids replaced
by capture file names, ordered by file name and frame:

- packets: every column, floats by exact IEEE bits; per column the mismatch count and the first examples;
- excluded_frames: frame, reason, observed_ts;
- captures: name, state, checkpoint, identity, error and the inventory JSON (exact text, then key by key);
- settings: schema_version, analysis_version, report.

--generate creates the standard synthetic sets with `packetbreaker demo` (demo, nat, offload, realistic_loss,
udp_transactions, security, truncation, duplicate, demo --ipv6, demo --ip-id zero) when missing. --derived adds a
"derived" set made from the demo and udp_transactions captures by byte edits that exercise the container walker and
tshark tail handling: zero-filled tails (whole and partial record), a final record cut short, a pcapng cut inside its
last block, big-endian and nanosecond pcap headers, and timestamps before 2000 / far in the future; plus "prefilter_mix", "prefilter_one" and "prefilter_pairs",
capture folders with files of more than five times the frames of the others, for runs with --prefilter (the
`packetbreaker case` default): skipped, applied and scanned pre-filter paths. Inventory values that come from an
unordered GROUP BY (timestamp_excluded_counts, tunnel_counts, vendor_stages) are compared order-insensitively,
because their order is not deterministic in the reference either. Both sides run DuckDB with --threads (default 1)
threads per connection, since multi-threaded tie order is not deterministic in the reference; the packets table
order also depends on the parallel CSV load (preserve_insertion_order=false), so rows are always compared by key.
Synthetic data only. Exit status 0 when every set has zero mismatches.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
UI2 = REPO / "ui2"
REFERENCE_ROOT = Path.home() / "Codo" / "PacketBreaker"
TSHARK_DIR = "/Applications/Wireshark.app/Contents/MacOS"
JAVA_MAIN = "com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestCli"
THREADS_PROPERTY = "packetanalysis.duckdb.threads"
STANDARD_SETS = {
    "demo": ["--scenario", "demo"],
    "nat": ["--scenario", "nat"],
    "offload": ["--scenario", "offload"],
    "realistic_loss": ["--scenario", "realistic_loss"],
    "udp_transactions": ["--scenario", "udp_transactions"],
    "security": ["--scenario", "security"],
    "truncation": ["--scenario", "truncation"],
    "duplicate": ["--scenario", "duplicate"],
    "demo_ipv6": ["--scenario", "demo", "--ipv6"],
    "demo_ipid_zero": ["--scenario", "demo", "--ip-id", "zero"],
}
CAPTURE_SUFFIXES = (".pcap", ".pcapng", ".cap", ".snoop")
MAX_EXAMPLES = 3


def env() -> dict:
    e = dict(os.environ)
    e["PATH"] = TSHARK_DIR + os.pathsep + e.get("PATH", "")
    return e


def generate(captures: Path, reference: Path) -> None:
    cli = reference / ".venv" / "bin" / "packetbreaker"
    procs = []
    for name, args in STANDARD_SETS.items():
        target = captures / name
        if target.exists():
            continue
        log = (captures / f"{name}.log").open("w")
        procs.append((name, subprocess.Popen([str(cli), "demo", str(target), *args], stdout=log, stderr=log, env=env())))
    for name, proc in procs:
        if proc.wait() != 0:
            raise SystemExit(f"generation failed for {name}; see {captures / (name + '.log')}")


# ---- derived captures (byte edits of generated synthetic pcaps) ----


def pcap_records(data: bytes):
    endian = "<" if data[:4] in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1") else ">"
    pos, out = 24, []
    while pos + 16 <= len(data):
        sec, frac, incl, orig = struct.unpack_from(endian + "IIII", data, pos)
        out.append((sec, frac, incl, orig, data[pos + 16 : pos + 16 + incl]))
        pos += 16 + incl
    return endian, out


def write_pcap(path: Path, header: bytes, endian: str, records, magic: bytes | None = None) -> None:
    ver_major, ver_minor, zone, sigfigs, snaplen, link = struct.unpack("<HHIIII", header[4:24])
    with path.open("wb") as f:
        f.write(magic if magic else header[:4])
        f.write(struct.pack(endian + "HHIIII", ver_major, ver_minor, zone, sigfigs, snaplen, link))
        for sec, frac, incl, orig, body in records:
            f.write(struct.pack(endian + "IIII", sec, frac, incl, orig))
            f.write(body)


def derive(captures: Path) -> None:
    target = captures / "derived"
    if target.exists():
        shutil.rmtree(target)
    target.mkdir()
    base = (captures / "demo" / "00-client.pcap").read_bytes()
    if base[:4] != b"\xd4\xc3\xb2\xa1":
        raise SystemExit("derived set expects a little-endian microsecond demo pcap")
    endian, records = pcap_records(base)
    (target / "a-zero-tail-whole.pcap").write_bytes(base + b"\0" * (16 * 64))
    (target / "b-zero-tail-partial.pcap").write_bytes(base + b"\0" * 1000)
    (target / "c-cut-short.pcap").write_bytes(base[:-10])
    write_pcap(target / "d-big-endian.pcap", base, ">", records, magic=b"\xa1\xb2\xc3\xd4")
    nano = [(s, us * 1000, i, o, b) for s, us, i, o, b in records]
    write_pcap(target / "e-nanosecond.pcap", base, "<", nano, magic=b"\x4d\x3c\xb2\xa1")
    odd = list(records)
    odd[2] = (0,) + odd[2][1:]
    odd[5] = (4_000_000_000,) + odd[5][1:]
    odd[7] = (100,) + odd[7][1:]
    write_pcap(target / "f-bad-timestamps.pcap", base, "<", odd)
    ng = (captures / "udp_transactions" / "points.pcapng").read_bytes()
    (target / "g-cut-block.pcapng").write_bytes(ng[:-7])
    (target / "h-pcapng-zero-tail.pcapng").write_bytes(ng + b"\0" * 120)

    # Pre-filter sets (meaningful with --prefilter): captures with >5x the frames of the others.
    mix = captures / "prefilter_mix"
    if mix.exists():
        shutil.rmtree(mix)
    mix.mkdir()
    shutil.copy(captures / "demo" / "00-client.pcap", mix / "00-client.pcap")
    shutil.copy(captures / "demo" / "04-server.pcap", mix / "04-server.pcap")
    shutil.copy(captures / "realistic_loss" / "00-client.pcap", mix / "10-realistic-client.pcap")
    shutil.copy(captures / "udp_transactions" / "points.pcapng", mix / "20-points.pcapng")
    # prefilter_one: one large capture, filtered to the small capture's IP pairs.
    # prefilter_pairs: two large captures, both scanned and filtered to pairs seen in the other captures.
    client_big = (captures / "realistic_loss" / "00-client.pcap").read_bytes()
    security = (captures / "security" / "client.pcap").read_bytes()
    _, client_records = pcap_records(client_big)
    _, security_records = pcap_records(security)
    for set_name, larges in (("prefilter_one", ("b",)), ("prefilter_pairs", ("b", "e"))):
        folder = captures / set_name
        if folder.exists():
            shutil.rmtree(folder)
        folder.mkdir()
        shutil.copy(captures / "demo" / "00-client.pcap", folder / "a-small.pcap")
        if "b" in larges:
            write_pcap(folder / "b-large.pcap", client_big, "<", client_records + records * 6)
        if "e" in larges:
            write_pcap(folder / "e-large.pcap", security, "<", security_records * 4 + records * 6)


# ---- ingest runs ----


def java_classpath() -> str:
    out = subprocess.run(
        ["./gradlew", "-q", ":packet-analysis:printRuntimeClasspath"], cwd=UI2, check=True, capture_output=True, text=True
    ).stdout.strip().splitlines()
    return out[-1]


def capture_files(folder: Path) -> list[Path]:
    return sorted(p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in CAPTURE_SUFFIXES)


def run_reference(reference: Path, project: Path, files: list[Path], prefilter: bool, threads: int) -> dict:
    cmd = [str(reference / ".venv" / "bin" / "python"), "-I", str(Path(__file__).with_name("l1_reference_ingest.py"))]
    cmd += [str(project), *map(str, files), "--threads", str(threads)] + (["--prefilter"] if prefilter else [])
    proc = subprocess.run(cmd, capture_output=True, text=True, env=env())
    if proc.returncode not in (0, 1):
        raise SystemExit("reference ingest crashed:\n" + proc.stderr[-4000:])
    return json.loads(proc.stdout.strip().splitlines()[-1])


def run_java(classpath: str, java: str, project: Path, files: list[Path], prefilter: bool, threads: int) -> dict:
    cmd = [java, f"-D{THREADS_PROPERTY}={threads}", "-cp", classpath, JAVA_MAIN, "--project", str(project)]
    cmd += list(map(str, files))
    cmd += ["--prefilter"] if prefilter else []
    proc = subprocess.run(cmd, capture_output=True, text=True, env=env())
    if proc.returncode not in (0, 1):
        raise SystemExit("java ingest crashed:\n" + proc.stderr[-4000:])
    return json.loads(proc.stdout.strip().splitlines()[-1])


# ---- comparison ----


def same(a, b) -> bool:
    if isinstance(a, float) and isinstance(b, float):
        return struct.pack(">d", a) == struct.pack(">d", b)
    return type(a) is type(b) and a == b


def short(v, limit=120):
    text = repr(v)
    return text if len(text) <= limit else text[:limit] + "..."


def table(db, sql):
    cur = db.execute(sql)
    names = [d[0] for d in cur.description]
    return names, cur.fetchall()


def compare_keyed(label, ref_names, ref_rows, java_names, java_rows, key_len, report):
    """Rows keyed by their first key_len columns; returns (rows compared, mismatching rows)."""
    if ref_names != java_names:
        report.append(f"  {label}: column lists differ: reference {ref_names} java {java_names}")
        return 0, max(len(ref_rows), len(java_rows))
    ref = {r[:key_len]: r for r in ref_rows}
    jav = {r[:key_len]: r for r in java_rows}
    missing = sorted(set(ref) - set(jav))
    extra = sorted(set(jav) - set(ref))
    bad_rows = len(missing) + len(extra)
    if missing:
        report.append(f"  {label}: {len(missing)} rows only in reference, first {missing[:MAX_EXAMPLES]}")
    if extra:
        report.append(f"  {label}: {len(extra)} rows only in java, first {extra[:MAX_EXAMPLES]}")
    per_column = {}
    for k in sorted(set(ref) & set(jav)):
        a, b = ref[k], jav[k]
        row_bad = False
        for i, name in enumerate(ref_names[key_len:], start=key_len):
            if not same(a[i], b[i]):
                row_bad = True
                per_column.setdefault(name, []).append((k, a[i], b[i]))
        bad_rows += row_bad
    for name, items in per_column.items():
        report.append(f"  {label}.{name}: {len(items)} mismatches")
        for k, a, b in items[:MAX_EXAMPLES]:
            report.append(f"    {k}: reference={short(a)} java={short(b)}")
    return len(set(ref) | set(jav)), bad_rows


# Inventory values built from a DuckDB GROUP BY without ORDER BY: their order is not deterministic in the
# reference itself (two reference runs can differ), so they are compared order-insensitively.
UNORDERED_INVENTORY = ("timestamp_excluded_counts", "tunnel_counts", "vendor_stages")


def canonical(key, value):
    if key in UNORDERED_INVENTORY:
        if isinstance(value, list):
            return json.dumps(sorted(json.dumps(v, sort_keys=True) for v in value))
        return json.dumps(value, sort_keys=True)
    return json.dumps(value)


def inventory_diff(a: str, b: str) -> list[str]:
    """Differences between two inventory JSON texts; [] when equal up to the unordered GROUP BY values."""
    if a == b:
        return []
    ja, jb = json.loads(a), json.loads(b)
    out = []
    if list(ja) != list(jb):
        out.append(f"key order: reference={list(ja)} java={list(jb)}")
    for k in list(dict.fromkeys([*ja, *jb])):
        if k not in jb:
            out.append(f"{k}: only in reference")
        elif k not in ja:
            out.append(f"{k}: only in java")
        elif canonical(k, ja[k]) != canonical(k, jb[k]):
            out.append(f"{k}: reference={short(ja[k])} java={short(jb[k])}")
    if not out and json.dumps({k: canonical(k, v) for k, v in ja.items()}) != json.dumps(
        {k: canonical(k, v) for k, v in jb.items()}
    ):
        out.append("spacing differs")
    return out


def compare(ref_db: Path, java_db: Path, report: list[str]) -> dict:
    import duckdb

    a = duckdb.connect(str(ref_db), read_only=True)
    b = duckdb.connect(str(java_db), read_only=True)
    try:
        packets_sql = (
            "SELECT c.name AS file, p.* EXCLUDE (capture_id) FROM packets p JOIN captures c ON c.id=p.capture_id "
            "ORDER BY file, frame"
        )
        rn, rr = table(a, packets_sql)
        jn, jr = table(b, packets_sql)
        rows, bad = compare_keyed("packets", rn, rr, jn, jr, 2, report)
        excluded_sql = (
            "SELECT c.name AS file, e.frame, e.reason, e.observed_ts FROM excluded_frames e "
            "JOIN captures c ON c.id=e.capture_id ORDER BY file, frame"
        )
        en, er = table(a, excluded_sql)
        fn, fr = table(b, excluded_sql)
        ex_rows, ex_bad = compare_keyed("excluded_frames", en, er, fn, fr, 2, report)
        cap_sql = "SELECT name, state, checkpoint, identity, error, inventory FROM captures ORDER BY name"
        cn, cr = table(a, cap_sql)
        dn, dr = table(b, cap_sql)
        cap_rows, cap_bad = compare_keyed("captures", cn[:5], [r[:5] for r in cr], dn[:5], [r[:5] for r in dr], 1, report)
        inv_ref = {r[0]: r[5] for r in cr}
        inv_java = {r[0]: r[5] for r in dr}
        inv_bad = 0
        for name in sorted(set(inv_ref) & set(inv_java)):
            diffs = inventory_diff(inv_ref[name], inv_java[name])
            if diffs:
                inv_bad += 1
                report.append(f"  captures.inventory[{name}]: " + "; ".join(diffs[:6]))
        set_sql = "SELECT key, value FROM settings WHERE key IN ('schema_version','analysis_version','report') ORDER BY key"
        sn, sr = table(a, set_sql)
        tn, tr = table(b, set_sql)
        set_rows, set_bad = compare_keyed("settings", sn, sr, tn, tr, 1, report)
    finally:
        a.close()
        b.close()
    return dict(
        packets=rows,
        packet_mismatches=bad,
        excluded=ex_rows,
        excluded_mismatches=ex_bad,
        captures=cap_rows,
        capture_mismatches=cap_bad + inv_bad,
        settings_mismatches=set_bad,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--captures", type=Path, required=True)
    parser.add_argument("--work", type=Path)
    parser.add_argument("--reference", type=Path, default=REFERENCE_ROOT)
    parser.add_argument("--java", default=shutil.which("java") or "java")
    parser.add_argument("--sets", nargs="*")
    parser.add_argument("--generate", action="store_true")
    parser.add_argument("--derived", action="store_true")
    parser.add_argument("--prefilter", action="store_true", help="ingest with the case pre-filter on both sides")
    parser.add_argument("--threads", type=int, default=1, help="DuckDB threads on both sides (default 1)")
    args = parser.parse_args()
    captures = args.captures.resolve()
    work = (args.work or captures.parent / "l1-work").resolve()
    if args.generate:
        generate(captures, args.reference)
    if args.derived:
        derive(captures)
    sets = args.sets or sorted(p.name for p in captures.iterdir() if p.is_dir() and capture_files(p))
    classpath = java_classpath()
    failed = 0
    print(f"{'set':<18} {'files':>5} {'packets':>8} {'mism.':>6} {'excl.':>6} {'mism.':>6} {'capt.':>6} {'mism.':>6}")
    for name in sets:
        files = capture_files(captures / name)
        root = work / name
        if root.exists():
            shutil.rmtree(root)
        root.mkdir(parents=True)
        ref = run_reference(args.reference, root / "reference", files, args.prefilter, args.threads)
        jav = run_java(classpath, args.java, root / "java", files, args.prefilter, args.threads)
        report: list[str] = []
        if bool(ref.get("error")) != bool(jav.get("error")):
            report.append(f"  batch error differs: reference={ref.get('error')!r} java={jav.get('error')!r}")
        result = compare(root / "reference" / "project.duckdb", root / "java" / "project.duckdb", report)
        bad = sum(v for k, v in result.items() if k.endswith("mismatches"))
        bad += any(line.startswith("  batch error") for line in report)
        failed += bool(bad)
        print(
            f"{name:<18} {len(files):>5} {result['packets']:>8} {result['packet_mismatches']:>6} "
            f"{result['excluded']:>6} {result['excluded_mismatches']:>6} {result['captures']:>6} "
            f"{result['capture_mismatches'] + result['settings_mismatches']:>6}"
        )
        for line in report:
            print(line)
    print("L1 PASS" if not failed else f"L1 FAIL: {failed} set(s) differ")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
