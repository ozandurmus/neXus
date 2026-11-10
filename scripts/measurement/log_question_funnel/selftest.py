#!/usr/bin/env python3
"""Self-test of LogFunnelMeasure on a synthetic corpus with planted events.

Usage: selftest.py <java> <classpath> <techvocab.txt> [workdir]
It writes synthetic chunk files (synthetic names and RFC 5737 addresses only), runs the program with --selftest and
compares the counts of the planted events. Exit code 0 when every expectation holds.
"""
import datetime as dt
import json
import pathlib
import subprocess
import sys
import tempfile

TZ = dt.timezone(dt.timedelta(hours=3))
END = dt.datetime(2026, 11, 10, 12, 0, 0, tzinfo=TZ)   # newest chunk: the window ends on this day
MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def msg_line(t, host, prog, text):
    return f"{MON[t.month - 1]} {t.day:2d} {t:%H:%M:%S} {t.year} {host} {prog}: {text}"


def elg_line(t, host, proc, text):
    return f"[{proc} 1234 5678]@{host}[{t.day} {MON[t.month - 1]} {t:%H:%M:%S}] {text}"


def pan_line(t, severity, subtype, eventid, opaque):
    return json.dumps({"receive_time": t.strftime("%Y/%m/%d %H:%M:%S"), "severity": severity, "subtype": subtype,
                       "eventid": eventid, "module": "general", "opaque": opaque, "type": "SYSTEM",
                       "serial": "000000000000", "device_name": "fw-synth"})


def days(first, last):
    d = first
    while d <= last:
        yield d
        d += dt.timedelta(days=1)


def at(day, hh, mm=0, ss=0):
    return dt.datetime(day.year, day.month, day.day, hh, mm, ss)


def build(root):
    files = {}

    def put(device, source, lines):
        name = f"{device}__{source}__{int(END.timestamp())}.log"
        files.setdefault(name, []).extend(lines)

    last = END.date()
    for device, host, first in (("cpa", "gw-a", dt.date(2026, 10, 12)), ("cpb", "gw-b", dt.date(2026, 10, 12)),
                                ("cpc", "gw-c", dt.date(2026, 11, 2))):
        lines = []
        for d in days(first, last):
            for hh in range(24):
                for q in range(4):
                    lines.append(msg_line(at(d, hh, q * 15), host, "xpand[1234]", f"process status check completed {q}"))
                lines.append(msg_line(at(d, hh, 30), host, "xpand[1234]", "configuration database busy retry 5"))
            for hh in (0, 6, 12, 18):
                lines.append(msg_line(at(d, hh, 5), host, "sshd[2222]",
                                      "Accepted password for admin from 192.0.2.10 port 5050 ssh2"))
        if device == "cpa":
            lines.append(msg_line(at(dt.date(2026, 11, 6), 3, 10), host, "kernel", "fwk0[4321]: segfault at 0 ip 0 sp 0"))
            for mm in (1, 3, 5):
                lines.append(msg_line(at(dt.date(2026, 11, 7), 10, mm), host, "kernel", "igb eth1: NIC Link is Down"))
            lines.append(msg_line(at(dt.date(2026, 11, 8), 14, 0), host, "routed[900]",
                                  "unexpected adjacency teardown error on instance 3"))
            for i in range(60):
                lines.append(msg_line(at(dt.date(2026, 11, 9), 9, i % 60), host, "xpand[1234]",
                                      "configuration database busy retry 5"))
        if device in ("cpa", "cpb"):
            lines.append(msg_line(at(dt.date(2026, 11, 7), 2, 0), host, "xpand[1234]", "scheduled maintenance task finished"))
        if device == "cpc":
            lines.append(msg_line(at(dt.date(2026, 11, 8), 1, 0), host, "xpand[1234]", "previously unseen marker text"))
        put(device, "cp_messages", lines)
    cpwd = [elg_line(at(d, 0, 0, 1), "gw-b", "cpWatchDog", "monitoring cycle complete") for d in days(dt.date(2026, 10, 12), last)]
    for ss in (1, 2):
        cpwd.append(elg_line(at(dt.date(2026, 11, 9), 11, 0, ss), "gw-b", "cpWatchDog", "Process FWD stopped unexpectedly, restarting"))
    put("cpb", "cp_cpwd_elg", cpwd)
    for device in ("pana", "panb"):
        lines = []
        for d in days(dt.date(2026, 10, 12), last):
            for hh in range(24):
                lines.append(pan_line(at(d, hh), "informational", "general", "general", "Connection to update server completed"))
        if device == "pana":
            for mm in (0, 2, 4):
                lines.append(pan_line(at(dt.date(2026, 11, 5), 8, mm, 10), "critical", "ha", "state-change",
                                      "HA Group 1: Moved from state Active to state Non-Functional"))
            lines.append(pan_line(at(dt.date(2026, 11, 6), 15, 0), "high", "general", "unusual-condition",
                                  "unexpected internal condition detected"))
        put(device, "pan_system", lines)
    for name, lines in files.items():
        (root / name).write_text("\n".join(lines) + "\n", encoding="utf-8")


EXPECT = {
    "meta\tdevices_cp": 3, "meta\tdevices_pan": 2,
    "days\tcp_messages\tdevices_enough_history": 2, "days\tcp_messages\tdevices_short_history": 1,
    "sig\tcrash\tcp_messages\tlines": 1, "kind\tcrash\tdevice_hours": 1,
    "sig\tburst_link\tepisodes": 1, "sig\tburst_ha\tepisodes": 1, "sig\trestart\tcp_cpwd_elg\tlines": 2,
    "ratescope\tcp_messages\tdevices_evaluable": 3,
    "new\tcp_messages\tdevice_only\trouting\tsev_error\tevents": 1,
    "new\tcp_messages\tfleet_common\tother\tsev_none\tevents": 2,
    "new\tcp_cpwd_elg\tdevice_only\tproc_restart\tsev_none\tevents": 1,
    "new\tpan_system\tdevice_only\tha_state\tsev_error\tevents": 1,
    "new\tpan_system\tdevice_only\tother\tsev_error\tevents": 1,
    "rate\tbase\tcp_messages\tconfig_change\tfindings": 1, "rate\tstrict\tcp_messages\tconfig_change\tfindings": 1,
    "rate\tloose\tcp_messages\tconfig_change\tfindings": 1,
    # a rare new template of a signature class (crash, link, HA, cpwd restart) stays "answered by code"
    "q\tnarrow\tcp\tepisodes": 1, "q\tnarrow\tpan\tepisodes": 1,
    "q\tbase\tcp\tepisodes": 2, "q\tbase\tpan\tepisodes": 1,
    "q\tcode_only\tcp\tepisodes": 3, "q\tcode_only\tpan\tepisodes": 1,
    "q\twide\tcp\tepisodes": 7, "q\twide\tpan\tepisodes": 2,
    "kind\tnew_rare_signature_class\tdevice_hours": 4,
    "self\ttop_source_devices": 3,
}
ABSENT = ["fatal", "new\tcp_messages\tdevice_only\tother\tsev_none\tevents"]


def main():
    java, classpath, tech = sys.argv[1:4]
    work = pathlib.Path(sys.argv[4]) if len(sys.argv) > 4 else pathlib.Path(tempfile.mkdtemp(prefix="funnel-selftest-"))
    work.mkdir(parents=True, exist_ok=True)
    build(work)
    out = subprocess.run([java, "-cp", classpath, "com.securityexpert.nexus.ui2.service.systemlog.LogFunnelMeasure",
                          "--tech", tech, "--selftest", str(work)], capture_output=True, text=True, check=False).stdout
    got = {}
    for line in out.splitlines():
        key, _, value = line.rpartition("\t")
        got[key] = value
    failures = [f"{k!r}: expected {v}, got {got.get(k)}" for k, v in EXPECT.items() if got.get(k) != str(v)]
    failures += [f"{k!r}: expected absent, got {got[k]}" for k in ABSENT if k in got and got[k] != "0"]
    unsafe = [line for line in out.splitlines() if any(c not in "abcdefghijklmnopqrstuvwxyz0123456789_.\t" for c in line)]
    if unsafe:
        failures.append(f"{len(unsafe)} output lines carry characters outside [a-z0-9_.]")
    print(f"selftest: {len(got)} output rows, {len(EXPECT)} expectations, {len(failures)} failures")
    for f in failures:
        print("  " + f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
