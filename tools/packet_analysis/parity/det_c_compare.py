"""Lane C parity (UDP, DNS, RTP, SIP and vendor inputs): frozen PacketBreaker reference versus the Java engine.

Run with the frozen reference interpreter (CPython 3.12 venv of ~/Codo/PacketBreaker):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/det_c_compare.py WORKDIR \
        [--only NAME ...] [--java-classpath CP] [-v]

For every input the script builds the synthetic captures (the `packetbreaker demo --scenario X` code path, or the
fixture builders of the reference tests: tests/vendor_fixtures.py, test_dns.py, test_paloalto.py), ingests them
into a reference project, copies `project.duckdb` before analysis, runs the reference analysis (DuckDB threads=1)
with read-only hooks that keep the lane's detector outputs (`build_udp`, `build_rtp`, `build_sip`, `f5_report`,
`device_report`) and the core stage snapshots (core_compare.py), optionally the DNS page (`dns_page`), then runs the
Java engine on the copy (EngineCli, threads=1) and compares

1. the core and det-a stage tables and every core + time/event detector report section exactly as det_a_compare.py
   does (every lane is merged: the Java segments, findings and verdict carry every detector, so they are compared
   with the reference's unmodified final report);
2. the lane's persistent tables row by row: udp_decoded, udp_decode_cache, udp_protocol, udp_transactions,
   rtp_input, rtp_packets, rtp_streams, sip_messages, rtp_call_packets, f5_connections, dns_*;
3. the report sections udp, rtp, sip, f5, vendor_device_events, vendor_device_event_count, the media findings
   (rtp_loss, rtp_jitter, sip_rewritten, sdp_media_mismatch, one_way_audio) and the DNS page, after
   canonicalization (capture ids -> file names, 32-hex ids masked). A section that differs only in list order is
   reported as "order" (a tie-break the reference leaves to DuckDB, see ui2/packet-analysis/DETERMINISM.md).

`--fortinet` additionally compares the Fortinet text import (`packetbreaker import-fortinet` versus the Java
FortinetCli) on the reference test fixtures: converted pcap bytes, the conversion result and the stored topology.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
REFERENCE = Path(os.environ.get("PACKETBREAKER", Path.home() / "Codo" / "PacketBreaker"))
sys.path.insert(0, str(REFERENCE / "tests"))

import core_compare as C  # noqa: E402
import det_a_compare as A  # noqa: E402

LANE_TABLES = [
    "udp_decoded",
    "udp_decode_cache",
    "udp_protocol",
    "udp_transactions",
    "rtp_input",
    "rtp_packets",
    "rtp_streams",
    "sip_messages",
    "rtp_call_packets",
    "f5_connections",
    "dns_observations",
    "dns_queries",
    "dns_local_pairs",
    "dns_signals",
    "dns_transactions",
]
SECTIONS = ["udp", "rtp", "sip", "f5", "vendor_device_events", "vendor_device_event_count"]
MEDIA = {"rtp_loss", "rtp_jitter", "sip_rewritten", "sdp_media_mismatch", "one_way_audio"}
# Inputs built exactly like a det_a_compare.py input: that input's recorded deliberate differences apply.
SAME_INPUT_AS_DET_A = {"dns_diagnosis": "diagnostics"}
# scenario_pilot is the pilot scenario at CLI defaults (det_a's pilot uses ip_id zero): the same recorded ties
# (delivery_series frame_b, lifecycle_points syn_frame) apply, copied under its own name.
for (_case, _where), _why in list(A.DELIBERATE.items()):
    if _case == "pilot":
        A.DELIBERATE[("scenario_pilot", _where)] = _why
for (_case, _where), _why in list(A.DELIBERATE_TABLES.items()):
    if _case == "pilot":
        A.DELIBERATE_TABLES[("scenario_pilot", _where)] = _why
# Sections that differ only in list order because of a tie-break recorded in ui2/packet-analysis/DETERMINISM.md
# ("Deliberate differences"): (input name prefix, section) -> reason. Only the status "order" is accepted.
DELIBERATE_ORDER = {
    **{(p, "rtp"): "rtp one_way: GROUP BY point,flow order; Java orders by point, flow"
       for p in ("udp_FW1_", "udp_FW2_", "udp_link_", "udp_transactions")},
    ("f5_peer_reuse", "f5"): "f5_report_connections: UNION hash order; Java orders the pairs",
}


# ---------------------------------------------------------------- input builders


def scenario(name, **options):
    def build(case_dir):
        from packetbreaker.batch_ingest import ingest_many
        from packetbreaker.store import Project
        from packetbreaker.synthetic import bind_capture_ids, generate

        truth, topology = generate(case_dir, scenario=name, **options)
        project = Project(case_dir / "project")
        ids = ingest_many(project, truth["files"], parallel=False)
        bind_capture_ids(topology, {Path(path).name: cid for path, cid in zip(truth["files"], ids)})
        return project, topology

    return build


def dns_native(case_dir):
    """tests/test_dns.py test_native_dns_tuple_scope_latency_and_export."""
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.realistic import datagram
    from packetbreaker.store import Project
    from packetbreaker.synthetic import write_pcap

    name = b"\x04same\x07example\x04test\0"
    body = struct.pack("!HHHHHH", 77, 0x100, 1, 0, 0, 0) + name + struct.pack("!HH", 1, 1)
    response = struct.pack("!HHHHHH", 77, 0x8180, 1, 0, 0, 0) + name + struct.pack("!HH", 1, 1)

    def packet(client, response_flag=False):
        payload = response if response_flag else body
        return datagram(
            "203.0.113.53" if response_flag else client,
            client if response_flag else "203.0.113.53",
            17,
            struct.pack("!HHHH", 53 if response_flag else 40000, 40000 if response_flag else 53, len(payload) + 8, 0)
            + payload,
            0,
        )

    c1, c2 = f"{10}.0.0.1", f"{10}.0.0.2"
    frames = [
        (1700000000.1, packet(c1)),
        (1700000000.12, packet(c2)),
        (1700000000.15, packet(c1, True)),
        (1700000004, datagram(c1, "203.0.113.53", 17, struct.pack("!HHHH", 45000, 45001, 12, 0) + b"noop", 0)),
    ]
    paths = []
    for i in range(2):
        path = case_dir / f"point{i}.pcap"
        write_pcap(path, frames)
        paths.append(str(path))
    project = Project(case_dir / "project")
    ids = ingest_many(project, paths, parallel=False)
    topology = dict(
        points=[
            dict(id=f"p{i}", label=Path(p).stem, device=f"endpoint{i}", kind="Client" if i == 0 else "Server",
                 capture_id=ids[i])
            for i, p in enumerate(paths)
        ],
        forward=["p0", "p1"],
    )
    return project, topology


def f5(peer_reuse, cidrs=(f"{10}.0.0.0/24",)):
    """tests/test_f5.py test_f5_decode_pair_request_dwell_and_reset (and its 0.0.0.0/0 unknown variant)."""

    def build(case_dir):
        from packetbreaker.ingest import ingest
        from packetbreaker.store import Project
        from vendor_fixtures import f5_capture

        path = f5_capture(case_dir / "f5.pcap", peer_reuse)
        project = Project(case_dir / "project")
        cid = ingest(project, path, f5_trailer=True)
        points = [
            dict(id=role, device="BIG-IP", label=role, capture_id=cid, vendor="f5", vendor_stage=role)
            for role in ("client", "server")
        ]
        return project, dict(points=points, forward=["client", "server"], client_cidrs=list(cidrs))

    return build


def paloalto(case_dir):
    """tests/test_paloalto.py test_paloalto_drop_is_positive_without_path_coverage."""
    from packetbreaker.ingest import ingest
    from packetbreaker.store import Project
    from packetbreaker.synthetic import tcp_packet

    c1 = f"{10}.0.0.1"
    project = Project(case_dir / "project")
    points = []
    for stage in ("receive", "firewall", "transmit", "drop"):
        path = case_dir / (stage + ".pcap")
        data = bytearray(struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1))
        for n in range(5):
            if (stage == "drop" and n != 2) or (stage == "transmit" and n == 2):
                continue
            reverse = n in (1, 3)
            frame = tcp_packet(
                "203.0.113.1" if reverse else c1,
                c1 if reverse else "203.0.113.1",
                443 if reverse else 50000,
                50000 if reverse else 443,
                1000 + n * 20,
                9000,
                24,
                b"x" * 20,
                n,
            )
            data += struct.pack("<IIII", 1700000000 + n * 3, 0, len(frame), len(frame)) + frame
        path.write_bytes(data)
        cid = ingest(project, path)
        points.append(dict(id=stage, label=stage, device="PA", capture_id=cid, vendor="paloalto", vendor_stage=stage))
    return project, dict(points=points, forward=["receive", "firewall", "transmit"], client_cidrs=[f"{10}.0.0.0/8"])


def checkpoint(delayed):
    """tests/test_checkpoint.py test_snoop_adapter_matches_tshark_and_stage_drop."""

    def build(case_dir):
        import json as _json

        from packetbreaker.ingest import ingest
        from packetbreaker.store import Project
        from vendor_fixtures import snoop

        path = snoop(case_dir / "synthetic.snoop", delayed)
        project = Project(case_dir / "project")
        cid = ingest(project, path, checkpoint_uuid=True)
        with project.connect() as db:
            first = _json.loads(db.execute("SELECT vendor FROM packets ORDER BY frame LIMIT 1").fetchone()[0])
        points = [
            dict(id=f"p{n}", label=stage, device="FW", kind="Firewall", capture_id=cid, vendor="checkpoint",
                 vendor_interface=first.get("fw1.interface"), vendor_stage=stage, inspection_complete=True)
            for n, stage in enumerate(("i", "I", "o", "O", "e", "E", "oe", "OE"))
        ]
        return project, dict(points=points, forward=[p["id"] for p in points], client_cidrs=[f"{10}.0.0.0/8"])

    return build


def inputs():
    out = []
    for fault in ("FW1", "FW2", "link"):
        for ip_id, ipv6 in (("zero", False), ("constant", False), ("zero", True)):
            name = f"udp_{fault}_{ip_id}{'_v6' if ipv6 else ''}"
            out.append((name, scenario("udp_transactions", fault_location=fault, ip_id=ip_id, ipv6=ipv6), None))
    for mode in ("healthy", "capture_miss"):
        out.append((f"udp_FW2_media_{mode}", scenario("udp_transactions", fault_location="FW2", ip_id="zero",
                                                      media_mode=mode), None))
    out.append(("udp_transactions", scenario("udp_transactions"), None))
    out.append(("dns_diagnosis", scenario("diagnostics", ip_id="zero", ipv6=False), "client"))
    out.append(("dns_native", dns_native, "p0"))
    out.append(("f5", f5(False), None))
    out.append(("f5_peer_reuse", f5(True), None))
    out.append(("f5_unknown_cidrs", f5(False, ("0.0.0.0/0",)), None))
    out.append(("paloalto", paloalto, None))
    out.append(("checkpoint", checkpoint(False), None))
    out.append(("checkpoint_delayed", checkpoint(True), None))
    for name in ("demo", "nat", "healthy", "pilot", "security", "realistic_loss"):
        out.append((f"scenario_{name}", scenario(name), None))
    return out


# ---------------------------------------------------------------- reference run


def reference_run(case_dir: Path, builder, dns_point):
    import packetbreaker.analysis.matching as AM
    import packetbreaker.analysis.report as R
    import packetbreaker.rtp as RTP
    import packetbreaker.sip as SIP
    import packetbreaker.udp as UDP

    C.force_single_thread()
    if case_dir.exists():
        shutil.rmtree(case_dir)
    case_dir.mkdir(parents=True)
    project, topology = builder(case_dir)
    java_dir = case_dir / "java"
    java_dir.mkdir()
    shutil.copy2(case_dir / "project" / "project.duckdb", java_dir / "project.duckdb")
    (case_dir / "topology.json").write_text(json.dumps(topology, indent=2), encoding="utf-8")

    dumps, detectors = {}, {}
    originals = {}

    def dump(point, db):
        dumps[point] = {t: A.table_dump(db, t) for t in A.SNAPSHOTS[point]}

    def hook(module, name, point=None, after=None, replace=None):
        original = getattr(module, name)
        originals[(module, name)] = original

        def wrapped(*args, **kwargs):
            result = replace(*args, **kwargs) if replace else original(*args, **kwargs)
            if point:
                dump(point, args[0])
            if after:
                after(result, *args, **kwargs)
            return result

        setattr(module, name, wrapped)

    def keep(key):
        def after(result, *args, **kwargs):
            detectors[key] = copy.deepcopy(result)

        return after

    def device_report_after(result, db, *args, **kwargs):
        keep("device")(result)
        dump("det_a", db)

    hook(AM, "select_observations", "selection")
    hook(R, "prepare", "prepare")
    hook(R, "align", "align")
    hook(R, "propagate_translation_unknown", "matching")
    hook(R, "prepare_byte_ranges", "bytes")
    hook(R, "prepare_flow_filters", "flow_filters")
    hook(R, "add_headlines", after=lambda res, db, *a, **k: dump("core", db))
    hook(UDP, "build_udp", after=keep("udp"))
    hook(RTP, "build_rtp", after=keep("rtp"))
    hook(SIP, "build_sip", after=keep("sip"))
    hook(R, "f5_report", after=keep("f5"))
    hook(R, "device_report", after=device_report_after)
    dns = None
    try:
        started = time.monotonic()
        report = R.analyze(project, topology)
        elapsed = time.monotonic() - started
        if dns_point is not None:
            from packetbreaker.dns import dns_page

            dns = dns_page(project, point=dns_point)
    finally:
        for (module, name), original in originals.items():
            setattr(module, name, original)
    (case_dir / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    import duckdb

    # Application-index / proxy tables of the reference run go into the Java copy, as det_a_compare.py does.
    capture_names = A.copy_application_tables(case_dir, project)
    with duckdb.connect(str(case_dir / "project" / "project.duckdb"), read_only=True) as db:
        lane = {t: C.table_dump(db, t) for t in LANE_TABLES}
    sections = dict(
        udp=detectors.get("udp"),
        rtp=detectors["rtp"][0] if "rtp" in detectors else None,
        sip=detectors["sip"][0] if "sip" in detectors else None,
        f5=detectors.get("f5"),
        vendor_device_events=detectors["device"]["vendor_device_events"] if "device" in detectors else None,
        vendor_device_event_count=detectors["device"]["vendor_device_event_count"] if "device" in detectors else None,
    )
    media = (detectors["rtp"][1] if "rtp" in detectors else []) + (detectors["sip"][1] if "sip" in detectors else [])
    return dict(dumps=dumps, report=report, capture_names=capture_names, seconds=elapsed, lane=lane,
                sections=sections, media=media, dns=dns,
                retrans_pages=None)


# ---------------------------------------------------------------- java run


def java_run(case_dir: Path, classpath: str, dns_point):
    java_dir = case_dir / "java"
    parity = java_dir / "parity.duckdb"
    if parity.exists():
        parity.unlink()
    command = [
        os.environ.get("JAVA", "java"), "-Dpacketanalysis.duckdb.threads=1", "-cp", classpath,
        "com.securityexpert.nexus.ui2.packetanalysis.engine.EngineCli",
        "--project", str(java_dir / "project.duckdb"), "--topology", str(case_dir / "topology.json"),
        "--report", str(java_dir / "report.json"), "--parity", str(parity),
    ]
    if dns_point is not None:
        command += ["--dns", str(java_dir / "dns.json"), "--dns-point", dns_point]
    started = time.monotonic()
    result = subprocess.run(command, capture_output=True, text=True)
    elapsed = time.monotonic() - started
    if result.returncode != 0:
        return dict(error=(result.stderr or result.stdout)[-4000:], seconds=elapsed)
    import duckdb

    dumps = {}
    with duckdb.connect(str(parity), read_only=True) as db:
        for point, tables in A.SNAPSHOTS.items():
            dumps[point] = {t: A.table_dump(db, f"{point}__{t}") for t in tables}
    with duckdb.connect(str(java_dir / "project.duckdb"), read_only=True) as db:
        lane = {t: C.table_dump(db, t) for t in LANE_TABLES}
    text = (java_dir / "report.json").read_text(encoding="utf-8")
    report = json.loads(text)
    dns = None
    dns_text_ok = True
    if dns_point is not None:
        dns_text = (java_dir / "dns.json").read_text(encoding="utf-8")
        dns = json.loads(dns_text)
        dns_text_ok = dns_text == json.dumps(dns, indent=2)
    return dict(dumps=dumps, report=report, seconds=elapsed, formatting_ok=text == json.dumps(report, indent=2),
                lane=lane, dns=dns, dns_text_ok=dns_text_ok)


# ---------------------------------------------------------------- comparison


def canon(value, names):
    def rename(v):
        if isinstance(v, str):
            return names.get(v, v)
        if isinstance(v, list):
            return [rename(x) for x in v]
        if isinstance(v, dict):
            return {rename(k): rename(x) for k, x in v.items()}
        return v

    return C.mask(rename(copy.deepcopy(value)))


def unordered(value):
    if isinstance(value, list):
        return sorted((unordered(v) for v in value), key=lambda v: json.dumps(v, sort_keys=True))
    if isinstance(value, dict):
        return {k: unordered(v) for k, v in value.items()}
    return value


def compare_value(a, b, label):
    """(status, details): identical | order | differs."""
    if json.dumps(a) == json.dumps(b):
        return "identical", []
    if json.dumps(unordered(a), sort_keys=True) == json.dumps(unordered(b), sort_keys=True):
        return "order", C.json_diff(a, b, label, limit=4)
    return "differs", C.json_diff(a, b, label)


def compare_case(name, ref, java, verbose):
    lines = []
    names = ref["capture_names"]
    # 1. core + every detector lane's core-report sections (det_a_compare.compare_case), against the reference
    #    final report.
    media_java = [f for f in java["report"]["findings"] if f["type"] in MEDIA]
    core_ok, core_lines = A.compare_case(SAME_INPUT_AS_DET_A.get(name, name), ref, java, verbose)
    lines += ["  core+det-a: " + core_lines[0].split(": ", 1)[1]] + core_lines[1:]
    # 2. lane tables
    statuses = {}
    for table in LANE_TABLES:
        status, detail = C.compare_tables(ref["lane"][table], java["lane"][table])
        statuses[table] = (status, detail)
    compared = [t for t, (s, _) in statuses.items() if s != "absent"]
    identical = [t for t in compared if statuses[t][0] == "identical"]
    rows = sum(len(ref["lane"][t]["rows"]) for t in compared if ref["lane"][t])
    lane_ok = len(identical) == len(compared)
    lines.append(f"  lane tables {len(identical)}/{len(compared)} identical ({rows} reference rows)")
    for t in compared:
        if statuses[t][0] != "identical":
            lines.append(f"    table {t}: {statuses[t][0]} {statuses[t][1] if verbose else statuses[t][1].splitlines()[0]}")
    # 3. sections, media findings, DNS page
    results = {}
    for section in SECTIONS:
        a = canon(ref["sections"][section], names)
        b = canon(java["report"].get(section), names)
        results[section] = compare_value(a, b, section)
    a = canon(sorted(ref["media"], key=lambda f: f["id"]), names)
    b = canon(sorted(media_java, key=lambda f: f["id"]), names)
    results["media_findings"] = compare_value(a, b, "media_findings")
    if ref["dns"] is not None:
        results["dns"] = compare_value(canon(ref["dns"], names), canon(java["dns"], names), "dns")
        if not java["dns_text_ok"]:
            results["dns_text"] = ("differs", ["dns json text differs from json.dumps(indent=2)"])
    summary = ", ".join(f"{k}={s}" for k, (s, _) in results.items())
    lines.append(f"  sections: {summary}")
    for k, (s, details) in results.items():
        if s != "identical":
            for d in details[: (12 if verbose else 3)]:
                lines.append(f"    {k}: {d}")
    verdicts = f"verdict ref={ref['report']['verdict']!r} java={java['report']['verdict']!r}"
    lines.append("  " + verdicts)
    recorded = [k for k, (st, _) in results.items()
                if st == "order" and any(name.startswith(p) and k == sec for p, sec in DELIBERATE_ORDER)]
    if recorded:
        lines.append("  recorded tie-break order (DETERMINISM.md): " + ", ".join(recorded))
    sections_ok = all(st == "identical" or k in recorded for k, (st, _) in results.items())
    ok = core_ok and lane_ok and sections_ok
    return ok, [f"{name}: {'IDENTICAL' if ok else 'DIFFERS'} (ref {ref['seconds']:.1f}s java {java['seconds']:.1f}s)"] + lines


# ---------------------------------------------------------------- Fortinet import


def fortinet_cases(work: Path):
    """The reference test_fortinet.py text fixtures."""
    from datetime import datetime, timezone

    from packetbreaker.synthetic import tcp_packet
    from test_fortinet import pcap_and_text

    cases = []
    for relative in (False, True):
        d = work / ("fortinet_relative" if relative else "fortinet_absolute")
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
        _, text = pcap_and_text(d, relative=relative)
        cases.append((d.name, text, "2023-11-14T22:13:20.000000123Z" if relative else None))
    d = work / "fortinet_tolerant"
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    _, text = pcap_and_text(d)
    text.write_text(text.read_text() + """2023-11-14 22:13:30.000001 port1 -- incomplete
0x0000  0011 2233 4455 6677 8899 aabb 0800 45
0x0010  a
console prompt
2023-11-14 22:13:31.000001 port1 in cooked
linux cooked capture, packet type: 0x1
0x0000  0011 2233 4455 6677 8899 aabb 0800 4500
""")
    cases.append((d.name, text, None))
    ethernet = tcp_packet(f"{10}.0.0.1", "203.0.113.1", 50000, 80, 1000, 9000, 24, b"synthetic", 1)
    mac = b"\x02\x00\x00\x00\x00\x01\x00\x00"
    layouts = {
        1: ethernet,
        113: struct.pack(">HHH8sH", 1, 1, 6, mac, 0x0800) + ethernet[14:],
        276: struct.pack(">HHIHBB8s", 0x0800, 0, 1, 1, 1, 6, mac) + ethernet[14:],
    }
    for link, frame in layouts.items():
        d = work / f"fortinet_cooked_{link}"
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
        lines = [
            "2023-11-14 22:13:21.000000123 port1 in synthetic",
            "linux cooked capture, packet type: 0x1, link layer address type: 0x1, protocol type: 0x800",
        ]
        lines += [f"0x{o:04x}  " + frame[o:o + 16].hex(" ", 2) + "    ........" for o in range(0, len(frame), 16)]
        text = d / f"cooked-{link}.txt"
        text.write_text("\n".join(lines) + "\n")
        cases.append((d.name, text, None))
    d = work / "fortinet_relative_no_zone"
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    _, text = pcap_and_text(d, relative=True)
    cases.append((d.name, text, "2023-11-14T22:13:20"))
    return cases


def fortinet_compare(work: Path, classpath: str):
    import duckdb

    from packetbreaker.fortinet import import_text
    from packetbreaker.store import Project

    C.force_single_thread()
    all_ok = True
    for name, text, start in fortinet_cases(work / "fortinet"):
        case = text.parent
        ref_project = Project(case / "ref")
        try:
            ref = import_text(ref_project, text, start)
            ref_error = None
        except Exception as exc:  # the reference error text is compared
            ref, ref_error = None, f"{type(exc).__name__}: {exc}"
        java_dir = case / "java"
        java_dir.mkdir(exist_ok=True)
        command = [
            os.environ.get("JAVA", "java"), "-Dpacketanalysis.duckdb.threads=1", "-cp", classpath,
            "com.securityexpert.nexus.ui2.packetanalysis.fortinet.FortinetCli", "--project", str(java_dir / "project"),
            "--text", str(text), "--result", str(java_dir / "result.json"),
        ]
        if start:
            command += ["--start-time", start]
        out = subprocess.run(command, capture_output=True, text=True)
        java_error = None
        if out.returncode != 0:
            java_error = (out.stderr.strip().splitlines() or [""])[-1]
        lines = []
        ok = True
        if ref_error or java_error:
            ok = bool(ref_error and java_error and ref_error.split(": ", 1)[1] in java_error)
            lines.append(f"  errors ref={ref_error!r} java={java_error!r}")
        else:
            java = json.loads((java_dir / "result.json").read_text())

            def strip_paths(result):
                r = copy.deepcopy(result)
                for f in r["files"]:
                    f["path"] = Path(f["path"]).name
                    f.pop("capture_id", None)
                    f.pop("clock_group", None)
                return r

            same_result = strip_paths(ref) == strip_paths(java)
            same_bytes = all(
                Path(a["path"]).read_bytes() == Path(b["path"]).read_bytes() for a, b in zip(ref["files"], java["files"])
            ) and len(ref["files"]) == len(java["files"])

            def stored(project_dir):
                with duckdb.connect(str(project_dir / "project.duckdb"), read_only=True) as db:
                    topology = json.loads(db.execute("SELECT value FROM settings WHERE key='topology'").fetchone()[0])
                    conversion = json.loads(
                        db.execute("SELECT value FROM settings WHERE key='fortinet_conversion'").fetchone()[0])
                    report = db.execute("SELECT value FROM settings WHERE key='report'").fetchone()[0]
                    captures = db.execute("SELECT name,state,inventory FROM captures ORDER BY name").fetchall()
                    packets = db.execute("SELECT count(*) FROM packets").fetchone()[0]
                ids = {c["capture_id"]: f"<capture:{i}>" for i, c in enumerate(conversion["files"])}
                groups = {c["clock_group"]: "<clock_group>" for c in conversion["files"] if "clock_group" in c}
                for p in topology["points"]:
                    p["id"] = "forti-" + ids[p["capture_id"]]
                    p["capture_id"] = ids[p["capture_id"]]
                inventories = []
                for cname, state, inv in captures:
                    inv = json.loads(inv)
                    inv.pop("path", None)
                    meta = copy.deepcopy(inv.get("source_metadata"))
                    if isinstance(meta, dict) and "clock_group" in meta:
                        meta["clock_group"] = groups.get(meta["clock_group"], meta["clock_group"])
                    inventories.append([cname, state, meta, inv.get("packet_count")])
                return dict(topology=topology, conversion=strip_paths(conversion), report=report,
                            inventories=inventories, packets=packets)

            a, b = stored(case / "ref"), stored(java_dir / "project")
            same_store = a == b
            ok = same_result and same_bytes and same_store
            lines.append(f"  result {'identical' if same_result else 'DIFFERS'}; pcap bytes "
                         f"{'identical' if same_bytes else 'DIFFER'}; stored topology/conversion/captures "
                         f"{'identical' if same_store else 'DIFFER'}")
            if not same_result:
                lines.append(f"    ref : {json.dumps(strip_paths(ref))[:400]}\n    java: {json.dumps(strip_paths(java))[:400]}")
            if not same_store:
                for key in a:
                    if a[key] != b[key]:
                        lines.append(f"    {key}: ref={json.dumps(a[key])[:300]} java={json.dumps(b[key])[:300]}")
        all_ok &= ok
        print(f"{name}: {'IDENTICAL' if ok else 'DIFFERS'}")
        print("\n".join(lines), flush=True)
    return all_ok


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("workdir", type=Path)
    parser.add_argument("--only", nargs="*")
    parser.add_argument("--java-classpath")
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument("--fortinet", action="store_true", help="also compare the Fortinet text import")
    parser.add_argument("--no-analysis", action="store_true", help="only the Fortinet import comparison")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    classpath = args.java_classpath or C.default_classpath(repo)
    all_ok = True
    summary = []
    if not args.no_analysis:
        for name, builder, dns_point in inputs():
            if args.only and name not in args.only:
                continue
            ref = reference_run(args.workdir / name, builder, dns_point)
            java = java_run(args.workdir / name, classpath, dns_point)
            if "error" in java:
                all_ok = False
                print(f"{name}: JAVA ERROR\n{java['error']}", flush=True)
                summary.append(f"{name}: java error")
                continue
            ok, lines = compare_case(name, ref, java, args.verbose)
            all_ok &= ok
            print("\n".join(lines), flush=True)
            summary.append(lines[0])
    if args.fortinet or args.no_analysis:
        all_ok &= fortinet_compare(args.workdir, classpath)
    print("\nSUMMARY " + ("all identical" if all_ok else "divergences present"))
    for s in summary:
        print("  " + s)
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
