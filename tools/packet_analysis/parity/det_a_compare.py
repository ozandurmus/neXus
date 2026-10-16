"""L2 + L3 det-a parity: frozen PacketBreaker reference versus the Java engine (core + time/event detectors).

Run with the frozen reference interpreter (CPython 3.12 venv of ~/Codo/PacketBreaker):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/det_a_compare.py WORKDIR \
        [--only NAME ...] [--java-classpath CP] [--skip-java] [-v]
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

DET_A_INPUTS = [
    # Core + onset / loss / delay / stall / reset scenarios
    ("demo", "demo", {}),
    ("demo_nat_confirmed", "demo", {"confirm_nat": True}),
    ("demo_ipid_zero_nat_confirmed", "demo", {"ip_id": "zero", "confirm_nat": True}),
    ("healthy", "healthy", {}),
    ("capture_miss", "capture_miss", {}),
    ("recovered_loss", "recovered_loss", {}),
    ("impactful_loss", "impactful_loss", {}),
    ("delay", "delay", {}),
    ("duplicate", "duplicate", {}),
    ("syn_blocked", "syn_blocked", {}),
    ("unrecovered_reset", "unrecovered_reset", {}),
    ("unrecovered_stall", "unrecovered_stall", {}),
    ("nat", "nat", {}),
    ("offload", "offload", {}),
    # Realistic scenarios
    ("realistic_loss", "realistic_loss", {}),
    ("realistic_capture_miss", "realistic_capture_miss", {}),
    ("realistic_healthy", "realistic_healthy", {}),
    ("realistic_syn_blocked", "realistic_syn_blocked", {}),
    # Onset scenarios
    ("onset_loss", "onset_loss", {"loss_hop": 1, "ip_id": "zero"}),
    ("onset_delay", "onset_delay", {"loss_hop": 1, "ip_id": "zero"}),
    ("onset_propagation", "onset_propagation", {"loss_hop": 1, "ip_id": "zero"}),
    ("onset_capture_miss", "onset_capture_miss", {"loss_hop": 1, "ip_id": "zero"}),
    ("onset_random_loss", "onset_random_loss", {"loss_hop": 1, "ip_id": "zero"}),
    ("onset_signals", "onset_signals", {"loss_hop": 1, "ip_id": "zero", "ipv6": True}),
    ("onset_intermittent_2", "onset_intermittent_2", {"loss_hop": 1, "ip_id": "zero"}),
    ("onset_intermittent_3", "onset_intermittent_3", {"loss_hop": 1, "ip_id": "zero"}),
    ("onset_intermittent_4", "onset_intermittent_4", {"loss_hop": 1, "ip_id": "zero"}),
    ("onset_intermittent_5", "onset_intermittent_5", {"loss_hop": 1, "ip_id": "zero"}),
    # Security & vendor device-drop scenarios
    ("security", "security", {"fault_location": "FW2", "ip_id": "zero", "rounds": 20}),
    ("security_fw1", "security", {"fault_location": "FW1", "ip_id": "zero", "rounds": 20}),
    ("security_link", "security", {"fault_location": "link", "ip_id": "zero", "rounds": 20}),
    ("security_reply", "security_reply", {"fault_location": "FW2", "ip_id": "zero", "rounds": 20}),
    # Host-path, cycles, delivery, hold_release, aggregates, incident scenarios
    ("pilot", "pilot", {"ip_id": "zero"}),
    ("pilot_ipv6", "pilot", {"ip_id": "zero", "ipv6": True, "pilot_shared": False}),
    ("pilot_healthy", "pilot_healthy", {"ip_id": "zero"}),
    ("real_case_2", "real_case_2", {"ip_id": "increment"}),
    ("incident_replica", "custom_incident_replica", {}),
    # Diagnostics (retransmissions)
    ("diagnostics", "diagnostics", {"ip_id": "zero"}),
    # Reference test fixture builders
    ("burst_path_v4", "custom_burst_path", {"ipv6": False, "ip_id": "zero"}),
    ("burst_path_v6", "custom_burst_path", {"ipv6": True, "ip_id": "constant"}),
    ("session_case_v4", "custom_session_case", {"ipv6": False}),
    ("session_case_v6", "custom_session_case", {"ipv6": True}),
]

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
    "det_a": [
        "device_proofs",
        "events",
        "flow_summary",
        "segment_buckets",
        "bucket_obs",
        "session_syns",
        "session_replies",
        "session_acks",
        "session_lifecycle",
        "session_points",
        "session_buckets",
        "lifecycle_window",
        "handshake_stops",
        "burst_clocks",
        "burst_wire",
        "microburst_windows",
        "burst_baseline",
        "burst_candidates",
        "microbursts",
        "security_sessions",
        "security_cohorts",
        "security_points",
        "security_fanout",
        "pressure_baseline",
        "pressure_spikes",
        "host_edges",
        "host_transit",
        "host_path_series",
        "host_bursts",
        "cycle_points",
        "cycle_windows",
        "cycle_series",
        "cycle_flow_ports",
        "cycle_periods",
        "cycle_edges",
        "cycle_pairs",
        "cycle_top",
        "cycle_top_points",
        "cycle_degradations",
        "delivery_positions",
        "delivery_events",
        "delivery_series",
        "adjacent_match_rates",
        "copy_trains",
        "aggregate_boundaries",
        "aggregate_cycles",
        "aggregate_added",
        "aggregate_copies",
        "aggregate_host_clocks",
        "aggregate_host_packets",
        "cycle_details",
        "cycle_flow_detail",
        "sess_obs",
        "sess_flows",
    ],
}

SECTIONS = [
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
    "timeseries",
    "onsets",
    "microbursts",
    "host_path",
    "cycles",
    "incident",
    "delivery",
    "vendor_device_events",
    "vendor_device_event_count",
    "confirmed_device_drops",
    "confirmed_device_drop_count",
    "sessions",
    "session_events",
    "security",
]

DELIBERATE = {
    ("offload", "quality[0].evidence[2].byte_ranges"): (
        "evidence range_peers: list(DISTINCT ...) has no order in the reference; Java orders the ranges"
    ),
    ("realistic_capture_miss", "findings[3].evidence"): (
        "classification first_event: ORDER BY ts LIMIT 1 tie between two events at the same timestamp; "
        "Java appends id,packet_key (reference frame 1225/1219, Java 1226/1220)"
    ),
}

DELIBERATE[("diagnostics", "cycles.top")] = (
    "cycle_top_points: two turns of one flow share the response ACK (same cycle_id); the reference orders them "
    "only by max_ms,cycle_id,point, Java appends request_frame"
)
DELIBERATE[("diagnostics", "incident.cycle_bars")] = DELIBERATE[("diagnostics", "cycles.top")]
DELIBERATE[("onset_propagation", "cycles.top")] = DELIBERATE[("diagnostics", "cycles.top")]
DELIBERATE[("onset_propagation", "incident.cycle_bars")] = DELIBERATE[("diagnostics", "cycles.top")]
_MAX_PAIR = (
    "onset max_pair ORDER BY time_b-time_a DESC LIMIT 1: equal maximum transit in the onset window; Java appends "
    "time_a,key_a,key_b (the reference keeps the first row it scans: the earliest in delay, a later one here)"
)
DELIBERATE[("onset_propagation", "segments[2].onsets[0].evidence")] = _MAX_PAIR
DELIBERATE[("onset_propagation", "onsets.items[2].evidence")] = _MAX_PAIR
_SERIES_TIE = (
    "delivery_series arg_max(frame_b,time_b): two copies share the match time_b; the reference keeps the first row "
    "it scans (frame 453 in pilot, the later frame in duplicate), Java appends frame_b (largest frame)"
)
_HOST_SERIES_TIE = (
    "host_path arg_max(key_a,delay_ms): equal maximum delays in one series bucket / stream; Java appends "
    "key_a,key_b (table host_path_series differs only in evidence_key)"
)
# Tables whose only difference is one of the recorded tie-breaks: (input, "point.table") -> reason.
DELIBERATE_TABLES = {}
for _case in ("pilot", "pilot_ipv6", "pilot_healthy", "real_case_2", "incident_replica"):
    # The pilot family takes the smaller frame_b of the two tied copies, duplicate the larger one: no single
    # frame order reproduces the reference (DETERMINISM.md).
    DELIBERATE[(_case, "delivery.series")] = _SERIES_TIE
    DELIBERATE[(_case, "incident")] = _SERIES_TIE
    DELIBERATE_TABLES[(_case, "det_a.delivery_series")] = _SERIES_TIE
    DELIBERATE_TABLES[(_case, "det_a.adjacent_match_rates")] = _SERIES_TIE
for _case in ("real_case_2", "incident_replica"):
    DELIBERATE_TABLES[(_case, "det_a.host_path_series")] = _HOST_SERIES_TIE
for _case in ("real_case_2", "incident_replica"):
    DELIBERATE[(_case, "host_path.items[0]")] = (
        "host_path arg_max(key_a,delay_ms): equal maximum delays in one series bucket / stream; Java appends "
        "key_a,key_b (table host_path_series differs only in evidence_key)"
    )
    DELIBERATE[(_case, "incident.timeline")] = "timeline evidence carries the host_path series evidence above"
for _case in ("pilot", "pilot_healthy", "pilot_ipv6", "real_case_2", "incident_replica"):
    DELIBERATE[(_case, "sessions.points")] = (
        "security lifecycle_points arg_min(syn_frame,start): two flows' SYNs share one corrected start at a point; "
        "Java appends syn_frame (smallest frame), the reference takes the first row it scans"
    )

HEX32 = re.compile(r"\b[0-9a-f]{32}\b")
# Integrity finding ids end in a per-run counter that follows the output order of integrity.sql endpoint_packets;
# the reference has no outer ORDER BY there, Java orders by corrected, frame (DETERMINISM.md, deliberate
# differences). A findings difference that disappears when only those counters are masked is that tie-break.
INTEGRITY_COUNTER = re.compile(r"^(integrity:.*):\d+(:merged)?$")


def mask_integrity_counters(findings):
    if not isinstance(findings, list):
        return findings
    out = copy.deepcopy(findings)
    for f in out:
        if isinstance(f, dict) and isinstance(f.get("id"), str):
            f["id"] = INTEGRITY_COUNTER.sub(r"\1\2", f["id"])
    return out


def canon_value(v):
    if v is None or isinstance(v, (bool, str)):
        return v
    if isinstance(v, int):
        return v
    if isinstance(v, float):
        if math.isnan(v):
            return "f:nan"
        return "f:" + repr(0.0 if v == 0.0 else v)
    if isinstance(v, (list, tuple)):
        return [canon_value(x) for x in v]
    if isinstance(v, dict):
        return {str(k): canon_value(x) for k, x in v.items()}
    return "t:" + str(v)


def canon_per_hop(text):
    """incident cycle_flow_detail.per_hop_added: list() without ORDER BY in the reference (hash order); Java
    orders by hop, component (DETERMINISM.md). Compared as a sorted list."""
    if not isinstance(text, str):
        return text
    items = json.loads(text)
    return json.dumps(sorted(items, key=lambda x: (x["hop"], x["component"])), separators=(",", ":"))


def table_dump(db, table):  # noqa: C901
    exists = db.execute(
        "SELECT count(*) FROM information_schema.tables WHERE table_name=?", [table]
    ).fetchone()[0]
    if not exists:
        return None
    cursor = db.execute(f'SELECT * FROM "{table}"')
    columns = [c[0] for c in cursor.description]
    data = [list(r) for r in cursor.fetchall()]
    if table.endswith("aggregate_added") and "frames" in columns:
        i = columns.index("frames")
        for r in data:
            r[i] = sorted(r[i] or [], key=lambda x: json.dumps(x, sort_keys=True, default=str))
    if table.endswith("cycle_flow_detail") and "per_hop_added" in columns:
        i = columns.index("per_hop_added")
        for r in data:
            r[i] = canon_per_hop(r[i])
    rows = sorted(json.dumps(canon_value(r), separators=(",", ":")) for r in data)
    return dict(columns=columns, rows=rows)


def compare_tables(ref, java):
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
        if isinstance(v, str):
            return capture_names.get(v, v)
        if isinstance(v, list):
            return [names(x) for x in v]
        if isinstance(v, dict):
            return {names(k): names(x) for k, x in v.items()}
        return v

    value = names(copy.deepcopy(value))
    def local_service(v):
        # host_path service / transport turnaround: GROUP BY point,flow output order in the reference; Java
        # orders by point,flow (DETERMINISM.md).
        if isinstance(v, dict):
            for key in ("flows", "transport_turnaround"):
                if key in v and "samples" in v and isinstance(v[key], list):
                    v[key] = sorted(v[key], key=lambda r: json.dumps([r.get("point"), r.get("flow")]))
            for x in v.values():
                local_service(x)
        elif isinstance(v, list):
            for x in v:
                local_service(x)

    local_service(value)
    if name == "session_events" and isinstance(value, dict) and isinstance(value.get("connections"), list):
        # connection_lifecycle ORDER BY f.first_t: Java appends flow.
        value["connections"] = sorted(
            value["connections"], key=lambda r: json.dumps([r.get("first_seen"), r.get("flow")]))
    if name == "clocks" and isinstance(value, dict):
        # Declared clock domains: the reference copies a domain model over a hash-ordered set (DETERMINISM.md,
        # "Other ordering facts"); key insertion order differs, values are identical.
        value = dict(sorted(value.items()))
    if name in ("coverage", "quality", "analysis_scope", "offload_points", "tunnel_points"):
        value = _sorted_by(value, "point")
    # Lists whose reference ORDER BY leaves ties: Java appends stable keys (DETERMINISM.md); both sides are
    # compared sorted by the Java key, a refinement of the reference order.
    tie_sorted = {
        "delivery": [("series", ("start", "point_a", "point_b", "flow", "direction"))],
        "host_path": [("streams", ("hop", "direction", "flow"))],
        "incident": [("delivery", ("start", "point_a", "point_b", "direction")),
                     ("quality", ("start", "point_a", "point_b", "direction"))],
    }
    for key, fields in tie_sorted.get(name, []):
        if isinstance(value, dict) and isinstance(value.get(key), list):
            value[key] = sorted(value[key], key=lambda r: json.dumps([r.get(f) for f in fields], default=str))
    if name == "host_path" and isinstance(value, dict):
        for item in value.get("items") or []:
            if isinstance(item.get("flow_details"), list):
                item["flow_details"] = sorted(
                    item["flow_details"], key=lambda r: json.dumps([r.get(f) for f in ("hop", "direction", "flow")]))
    if name == "cycles" and isinstance(value, dict):
        # host_local ORDER BY max_ms DESC: Java appends point,flow,port.
        if isinstance(value.get("host_local"), list):
            value["host_local"] = sorted(
                value["host_local"], key=lambda r: json.dumps([r.get("point"), r.get("flow"), r.get("port")]))
        # aggregate_added frames: list() order without ORDER BY in the reference; evidence follows the frames.
        for scope in ("analysis", "event"):
            best = (value.get(scope) or {}).get("max_added") if isinstance(value.get(scope), dict) else None
            for target in [best, value.get("max_added") if scope == "analysis" else None]:
                if isinstance(target, dict):
                    target["frames"] = sorted(target.get("frames") or [], key=lambda r: json.dumps(r, sort_keys=True))
                    target["evidence"] = sorted(target.get("evidence") or [], key=lambda r: json.dumps(
                        [r.get("point"), r.get("frame")]))
        for r in value.get("flow_ports") or []:
            if "per_hop_added" in r:
                r["per_hop_added"] = canon_per_hop(r["per_hop_added"])
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


def force_single_thread():
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


def build_input(case_dir: Path, scenario: str, options: dict):
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.ingest import ingest
    from packetbreaker.store import Project
    from packetbreaker.synthetic import bind_capture_ids, generate

    options = dict(options)
    if scenario == "custom_burst_path":
        # From PacketBreaker/tests/capture_realism_fixtures.py::burst_path
        tests_dir = Path("/Users/OzanDur/Codo/PacketBreaker/tests")
        if str(tests_dir) not in sys.path:
            sys.path.insert(0, str(tests_dir))
        import random
        import struct
        from packetbreaker.realistic import datagram
        from packetbreaker.synthetic import write_pcap

        ipv6 = options.get("ipv6", False)
        ip_id = options.get("ip_id", "zero")
        rng = random.Random(713)
        names = ["client", "fw_in", "fw_out", "lb_in", "lb_out", "server"]
        captures = [[] for _ in names]
        server = "2001:db8:2::2" if ipv6 else "203.0.113.2"
        pid = 0 if ip_id == "zero" else 777

        def emit(t, src, port, body, stop=6, reverse=False):
            packet = datagram(
                server if reverse else src,
                src if reverse else server,
                17,
                struct.pack("!HHHH", 5000 if reverse else port, port if reverse else 5000, len(body) + 8, 0)
                + body,
                pid,
            )
            for point in range(stop):
                captures[point].append((1700000000 + t + (5 - point if reverse else point) * 0.001, packet))

        for n, stop in enumerate([2, 4, 3, 6]):
            src = f"2001:db8:1::{n + 1}" if ipv6 else f"198.51.100.{n + 1}"
            for k in range(120):
                t = 0.2 + k * 0.25 + n * 0.003 + rng.uniform(-0.0002, 0.0002)
                lost = stop < 6 and k >= 48 and k % 4 == 0
                body = struct.pack("!II", n, k) + b"p" * 184
                emit(t, src, 44000 + n, body, stop if lost else 6)
                if not lost:
                    emit(t + 0.012, src, 44000 + n, b"r" + body, reverse=True)
        src = "2001:db8:1::99" if ipv6 else "198.51.100.99"
        for t in (6.795, 12.196):
            for k in range(24):
                emit(
                    t + k * 0.00002 + rng.uniform(-0.000002, 0.000002),
                    src,
                    45000,
                    struct.pack("!I", k) + b"b" * 796,
                )
        project = Project(case_dir / "project")
        points = []
        for i, name in enumerate(names):
            path = case_dir / (name + ".pcap")
            write_pcap(path, captures[i])
            cid = ingest(project, path)
            points.append(
                dict(
                    id=name,
                    label=name,
                    device="FW" if name.startswith("fw") else "LB" if name.startswith("lb") else name,
                    kind="Generic",
                    capture_id=cid,
                )
            )
        topology = dict(
            points=points,
            forward=names,
            client_cidrs=["2001:db8:1::/64"] if ipv6 else ["198.51.100.0/24"],
        )
        return project, topology, False

    if scenario == "custom_session_case":
        from packetbreaker.session_synthetic import make_session_case
        from packetbreaker.topology_suggest import assemble_topology

        ipv6 = options.get("ipv6", False)
        wire_dir = case_dir / "wire"
        truth = make_session_case(wire_dir, ipv6=ipv6)
        project = Project(case_dir / "project")
        files = sorted(wire_dir.glob("*.pcap"))
        ingest_many(project, files, parallel=False)
        assembled = assemble_topology(project)
        return project, assembled["topology"], False

    if scenario == "custom_incident_replica":
        from packetbreaker.pilot_synthetic import make_pilot

        truth, topology = make_pilot(
            case_dir,
            flows=50,
            affected_flows=25,
            slow_local_flow=True,
            host_jitter_us=150,
            ip_id="increment",
        )
        topology["forward"] = [p for p in topology["forward"] if p != "mirror"]
        topology["reverse"] = [p for p in topology["reverse"] if p != "mirror"]
        topology["points"] = [p for p in topology["points"] if p["id"] != "mirror"]
        project = Project(case_dir / "project")
        ids = ingest_many(project, truth["files"], parallel=False)
        bind_capture_ids(topology, {Path(f).name: c for f, c in zip(truth["files"], ids)})
        return project, topology, False

    confirm_nat = options.pop("confirm_nat", False)
    options.setdefault("rounds", 100)
    truth, topology = generate(case_dir, scenario=scenario, **options)
    project = Project(case_dir / "project")
    ids = ingest_many(project, truth["files"], parallel=False)
    bind_capture_ids(topology, {Path(path).name: cid for path, cid in zip(truth["files"], ids)})
    return project, topology, confirm_nat


def copy_application_tables(case_dir: Path, project):
    """If the reference ran application_index / proxy_requests (e.g., host_capture scenarios), copy the cached
    application tables into java/project.duckdb so HostPath / applyTcpAnalysis run with identical input (the
    application-index and proxy lanes are not ported). Returns the capture id -> name map."""
    import duckdb

    java_dir = case_dir / "java"
    with project.connect() as ref_db:
        capture_names = dict(ref_db.execute("SELECT id,name FROM captures").fetchall())
        has_app_cache = ref_db.execute(
            "SELECT count(*) FROM information_schema.tables WHERE table_name='app_protocol_cache'"
        ).fetchone()[0] > 0
        has_proxy_req = ref_db.execute(
            "SELECT count(*) FROM information_schema.tables WHERE table_name='proxy_requests'"
        ).fetchone()[0] > 0
    if has_app_cache or has_proxy_req:
        with duckdb.connect(str(java_dir / "project.duckdb")) as jdb:
            ref_path = str((case_dir / "project" / "project.duckdb").resolve()).replace("'", "''")
            jdb.execute(f"ATTACH '{ref_path}' AS ref_db (READ_ONLY)")
            for tbl in ("app_protocol", "app_protocol_cache", "tcp_point_analysis", "proxy_requests"):
                exists = jdb.execute(
                    "SELECT count(*) FROM duckdb_tables() WHERE database_name='ref_db' AND table_name=?", [tbl]
                ).fetchone()[0]
                if exists:
                    jdb.execute(f'CREATE OR REPLACE TABLE "{tbl}" AS SELECT * FROM ref_db."{tbl}"')
            jdb.execute("DETACH ref_db")
    return capture_names


def reference_run(case_dir: Path, scenario: str, options: dict):
    import duckdb
    import packetbreaker.analysis.matching as AM
    import packetbreaker.analysis.report as R
    force_single_thread()
    if case_dir.exists():
        shutil.rmtree(case_dir)
    case_dir.mkdir(parents=True)
    project, topology, confirm_nat = build_input(case_dir, scenario, options)
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

    def dump(point, db):
        dumps[point] = {t: table_dump(db, t) for t in SNAPSHOTS[point]}

    originals = {}

    def hook(module, name, point=None, before=None, after=None, replace=None):
        original = getattr(module, name)
        originals[(module, name)] = original

        def wrapped(*args, **kwargs):
            if before:
                before(*args, **kwargs)
            result = replace(*args, **kwargs) if replace else original(*args, **kwargs)
            if point:
                dump(point, args[0])
            if after:
                after(result, *args, **kwargs)
            return result

        setattr(module, name, wrapped)

    hook(AM, "select_observations", "selection")
    hook(R, "prepare", "prepare")
    hook(R, "align", "align")
    hook(R, "propagate_translation_unknown", "matching")
    hook(R, "prepare_byte_ranges", "bytes")
    hook(R, "prepare_flow_filters", "flow_filters")
    hook(R, "add_headlines", after=lambda res, db, *a: dump("core", db))
    hook(R, "device_report", after=lambda res, db, *a: dump("det_a", db))
    # Every detector lane is ported: the reference runs unmodified (no finding producer is stubbed).
    try:
        started = time.monotonic()
        report = R.analyze(project, topology)
        elapsed = time.monotonic() - started
    finally:
        for (module, name), original in originals.items():
            setattr(module, name, original)
    (case_dir / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    capture_names = copy_application_tables(case_dir, project)

    retrans_pages = None
    if scenario == "diagnostics":
        from packetbreaker.retransmissions import report as retrans_report
        from packetbreaker.topology import Topology as RefTopology
        from packetbreaker.transport import prepare as prepare_transport

        retrans_pages = {}
        ref_topo = RefTopology.model_validate(topology)
        with project.connect() as ref_db:
            prepare_transport(ref_db, project, ref_topo, report)
            retrans_pages["__all__"] = retrans_report(ref_db, ref_topo, None)
            flows = [r[0] for r in ref_db.execute("SELECT flow FROM flow_summary ORDER BY flow").fetchall()]
            for flow in flows:
                retrans_pages[flow] = retrans_report(ref_db, ref_topo, flow)

    return dict(
        dumps=dumps,
        report=report,
        capture_names=capture_names,
        seconds=elapsed,
        retrans_pages=retrans_pages,
    )


def java_run(case_dir: Path, classpath: str, check_retrans: bool = False):
    java_dir = case_dir / "java"
    parity = java_dir / "parity.duckdb"
    if parity.exists():
        parity.unlink()
    retrans_path = java_dir / "retransmissions.json"
    if retrans_path.exists():
        retrans_path.unlink()
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
    if check_retrans:
        command.extend(["--retransmissions", str(retrans_path)])
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
    formatting_ok = text == json.dumps(report, indent=2)
    retrans_pages = json.loads(retrans_path.read_text(encoding="utf-8")) if check_retrans and retrans_path.exists() else None
    return dict(dumps=dumps, report=report, seconds=elapsed, formatting_ok=formatting_ok, retrans_pages=retrans_pages)


def default_classpath(repo: Path):
    out = subprocess.run(
        ["./gradlew", "-q", ":packet-analysis:printRuntimeClasspath"],
        cwd=repo / "ui2",
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip().splitlines()
    return out[-1]


def compare_case(name, ref, java, verbose):
    lines = []
    table_status = {}
    tied_tables = []
    first_divergence = None
    for point, tables in SNAPSHOTS.items():
        for table in tables:
            status, detail = compare_tables(ref["dumps"][point][table], java["dumps"][point][table])
            if status == "differs" and (name, f"{point}.{table}") in DELIBERATE_TABLES:
                status = "deliberate"
                tied_tables.append(f"{point}.{table}")
            table_status[f"{point}.{table}"] = (status, detail)
            if status in ("differs", "missing_java", "missing_ref") and first_divergence is None:
                first_divergence = f"{point}.{table}: {status} {detail}"
    names = ref["capture_names"]
    section_diffs = {}
    counter_ties = []
    for section in SECTIONS:
        java_value = java["report"].get(section)
        ref_value = ref["report"].get(section)
        a = canon_section(section, ref_value, names)
        b = canon_section(section, java_value, names)
        if a != b or json.dumps(a) != json.dumps(b):
            if section == "findings" and json.dumps(mask_integrity_counters(a)) == json.dumps(mask_integrity_counters(b)):
                counter_ties.append(section)
                continue
            section_diffs[section] = json_diff(a, b, section)
    if ref.get("retrans_pages") is not None:
        a = canon_section("retransmissions", ref["retrans_pages"], names)
        b = canon_section("retransmissions", java.get("retrans_pages"), names)
        if a != b or json.dumps(a) != json.dumps(b):
            section_diffs["retransmissions"] = json_diff(a, b, "retransmissions")
    deliberate = [f"{s} (integrity finding id counters only: endpoint_packets output order)" for s in counter_ties]
    deliberate += [f"table {t} ({DELIBERATE_TABLES[(name, t)]})" for t in tied_tables]
    for section in list(section_diffs):
        paths = [d.split(":")[0] for d in section_diffs[section]]
        reasons = [r for (case, prefix), r in DELIBERATE.items() if case == name and all(p.startswith(prefix) for p in paths)]
        if reasons:
            deliberate.append(f"{section} ({reasons[0]})")
            del section_diffs[section]
    identical_tables = sum(1 for s, _ in table_status.values() if s == "identical")
    compared = sum(1 for s, _ in table_status.values() if s != "absent")
    rows = sum(len(ref["dumps"][p][t]["rows"]) for p, ts in SNAPSHOTS.items() for t in ts if ref["dumps"][p][t])
    lines.append(
        f"{name}: tables {identical_tables}/{compared} identical ({rows} reference rows); "
        f"report {'IDENTICAL' if not section_diffs else 'DIFFERS ' + ','.join(section_diffs)}; "
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
        for section, diffs in section_diffs.items():
            for d in diffs:
                lines.append(f"  section {d}")
    ok = not first_divergence and not section_diffs and java["formatting_ok"]
    return ok, lines


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("workdir", type=Path)
    parser.add_argument("--only", nargs="*")
    parser.add_argument("--java-classpath")
    parser.add_argument("--skip-java", action="store_true")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    classpath = None
    if not args.skip_java:
        classpath = args.java_classpath or default_classpath(repo)
    all_ok = True
    for name, scenario, options in DET_A_INPUTS:
        if args.only and name not in args.only:
            continue
        case_dir = args.workdir / name
        ref = reference_run(case_dir, scenario, options)
        if args.skip_java:
            print(f"{name}: reference only ({ref['seconds']:.1f}s)")
            continue
        java = java_run(case_dir, classpath, check_retrans=(scenario == "diagnostics"))
        if "error" in java:
            all_ok = False
            print(f"{name}: JAVA ERROR\n{java['error']}", flush=True)
            continue
        ok, lines = compare_case(name, ref, java, args.verbose)
        all_ok &= ok
        print("\n".join(lines), flush=True)
    if not args.skip_java:
        print("\nSUMMARY " + ("all identical" if all_ok else "divergences present"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
