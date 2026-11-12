#!/usr/bin/env python3
"""Markdown tables from the filtered output of LogFunnelMeasure (labels and numbers only).

Usage: tables.py <run.tsv>
"""
import collections
import sys

SOURCES = ["cp_messages", "cp_cpwd_elg", "cp_cpd_elg", "cp_fwd_elg", "cp_fwk_elg", "pan_system"]
CLASSES = ["crash", "proc_restart", "ha_state", "link", "routing", "vpn", "auth_admin", "config_change", "resource",
           "license_update", "time_dns", "log_transport", "cron_routine", "other"]
POLICIES = ["narrow", "base", "strict_rate", "loose_rate", "code_only", "excluded_only", "wide"]


def load(path):
    rows = collections.defaultdict(int)
    for line in open(path, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) < 2 or not f[-1].isdigit():
            continue
        rows[tuple(f[:-1])] += int(f[-1])
    return rows


def n(v):
    return f"{v:,}"


def pct(a, b):
    return "—" if not b else f"{100.0 * a / b:.1f}%"


def table(header, body):
    print("| " + " | ".join(header) + " |")
    print("|" + "|".join("---" if i == 0 else "---:" for i in range(len(header))) + "|")
    for r in body:
        print("| " + " | ".join(str(c) for c in r) + " |")
    print()


def backtest_tables(r):
    keys = sorted({(k[1], k[2]) for k in r if k[0] == "bt" and len(k) == 5 and k[3] == "all"})
    if not keys:
        return
    print("### Backtest: events and where they sit\n")
    rows = []
    for kind, vendor in keys:
        rows.append([kind, vendor, r.get(("bt", kind, vendor, "all", "events"), 0),
                     r.get(("bt", kind, vendor, "all", "device_not_in_read_set"), 0)])
    table(["Event kind", "Vendor", "Event-member rows", "Device not in the read set"], rows)
    print("### Backtest: what the draft detector finds in the event window (device clock read two ways)\n")
    head = ["Event kind", "Vendor", "Clock", "Rows in the test window", "Device active", "Signature", "Question", "Any finding",
            "HA lines", "Link lines"]
    rows = []
    for kind, vendor in keys:
        for reading in ("local", "utc"):
            def g(m):
                return r.get(("bt", kind, vendor, reading, m), 0)
            rows.append([kind, vendor, reading, g("in_test_window"), g("member_active"), g("member_signature"), g("member_question"),
                         g("member_any"), g("ha_lines"), g("link_lines")])
    table(head, rows)
    print("### Backtest: the same detector in windows without a known event (controls)\n")
    head = ["Event kind", "Vendor", "Clock", "Own: windows", "signature", "question", "any", "Fleet: device-windows",
            "signature", "question", "any"]
    rows = []
    for kind, vendor in keys:
        for reading in ("local", "utc"):
            def g(m):
                return r.get(("bt", kind, vendor, reading, m), 0)
            rows.append([kind, vendor, reading, g("control_member_windows"), g("control_member_signature"),
                         g("control_member_question"), g("control_member_any"), g("others_active"), g("others_signature"),
                         g("others_question"), g("others_any")])
    table(head, rows)
    print("### Backtest: which finding kinds fire on the event devices\n")
    bits = ["crash", "restart", "burst_ha", "burst_link", "rate", "rate_excluded", "new_rare_severity", "new_rare",
            "new_rare_excluded", "new_common", "new_signature"]
    rows = []
    for kind, vendor in keys:
        for reading in ("local", "utc"):
            rows.append([kind, vendor, reading] + [r.get(("bt", kind, vendor, reading, "member_bit_" + b), 0) for b in bits])
    table(["Event kind", "Vendor", "Clock"] + bits, rows)


BURST_N = [3, 10, 20, 30]
FLAVOURS = [("ha", "an HA burst alone"), ("sig", "any signature (crash, restart, HA or link burst at the same threshold)"),
            ("question", "the `base` question")]


def ratio(a, b):
    return f"{a} / {b} ({pct(a, b)})" if b else f"{a} / 0"


def sweep_tables(r):
    keys = sorted({(k[1], k[2]) for k in r if k[0] == "bt" and len(k) == 5 and k[3] == "all"})
    if keys:
        for flavour, label in FLAVOURS:
            print(f"### Sweep: {label}, by burst threshold (device clock read as Istanbul time)\n")
            rows = []
            for kind, vendor in keys:
                def g(m, reading="local"):
                    return r.get(("bt", kind, vendor, reading, m), 0)
                if not g("in_test_window"):
                    continue
                for t in BURST_N:
                    f = f"{flavour}_n{t}"
                    rows.append([kind, vendor, t, ratio(g("m_" + f), g("in_test_window")),
                                 ratio(g("c_" + f), g("control_member_windows")), ratio(g("o_" + f), g("others_active")),
                                 ratio(g("bg_" + f), g("bg_active"))])
            table(["Event kind", "Vendor", "Lines per 10 min", "Event rows caught", "Own device, other days (same clock window)",
                   "Other devices, same window", "Other devices, other days (background)"], rows)
    if any(k[0] == "sweep" for k in r):
        print("### Sweep: share of active device-days with a finding, by burst threshold (all devices of the test window)\n")
        rows = []
        for vendor in ("cp", "pan"):
            days = r.get(("sweep", vendor, "active_device_days"), 0)
            if not days:
                continue
            for t in BURST_N:
                rows.append([vendor, t, n(days)] + [
                    ratio(r.get(("sweep", vendor, f"{flavour}_n{t}", "device_days"), 0), days)
                    for flavour in ("ha", "link", "sig", "question")])
        table(["Vendor", "Lines per 10 min", "Active device-days", "HA burst", "Link burst", "Any signature",
               "`base` question"], rows)
        print("### Sweep: device-hours with a finding, by burst threshold\n")
        rows = []
        for vendor in ("cp", "pan"):
            hours = r.get(("sweep", vendor, "active_device_hours"), 0)
            if not hours:
                continue
            for t in BURST_N:
                rows.append([vendor, t, n(hours)] + [
                    ratio(r.get(("sweep", vendor, f"{flavour}_n{t}", "device_hours"), 0), hours)
                    for flavour in ("ha", "link", "sig", "question")])
        table(["Vendor", "Lines per 10 min", "Active device-hours", "HA burst", "Link burst", "Any signature",
               "`base` question"], rows)
        print("### Sweep: burst episodes in the test window, by threshold\n")
        rows = []
        for label, base in (("HA", "burst_ha"), ("link", "burst_link")):
            rows.append([label] + [n(r.get(("sig", base if t == 3 else f"{base}_n{t}", "episodes"), 0)) for t in BURST_N])
        table(["Class"] + [f"{t} lines" for t in BURST_N], rows)


def rule_tables(r):
    sources = [s for s in SOURCES if r.get(("days", s, "devices_enough_history"), 0) or r.get(("days", s, "devices_short_history"), 0)]
    if not sources:
        return
    print("### The NOT_EVALUABLE rule: device-source pairs and what it withholds\n")
    rows = []
    for s in sources:
        ok = r.get(("days", s, "devices_enough_history"), 0)
        short = r.get(("days", s, "devices_short_history"), 0)
        dropped = sum(v for k, v in r.items() if k[0] == "sig" and k[2] == s and k[3] == "not_evaluable_lines")
        kept = sum(v for k, v in r.items() if k[0] == "sig" and k[2] == s and k[3] == "lines")
        rows.append([f"`{s}`", n(ok + short), n(ok), n(short), pct(short, ok + short), n(dropped), n(kept)])
    table(["Source", "Pairs (devices with the source)", "Evaluable", "NOT_EVALUABLE", "share", "Signature lines withheld",
           "Signature lines kept"], rows)
    rows = []
    for kind, vendor in sorted({(k[1], k[2]) for k in r if k[0] == "bt" and len(k) == 5 and k[3] == "all"}):
        present = r.get(("bt", kind, vendor, "all", "member_pairs_present"), 0)
        ok = r.get(("bt", kind, vendor, "all", "member_pairs_evaluable"), 0)
        if present:
            rows.append([kind, vendor, present, ok, present - ok])
    if rows:
        print("Pairs of the event devices:\n")
        table(["Event kind", "Vendor", "Pairs present", "Evaluable", "NOT_EVALUABLE"], rows)


def main():
    r = load(sys.argv[1])
    g = lambda *k: r.get(tuple(k), 0)  # noqa: E731

    print("### Stored pool (chunk ledger, every source)\n")
    stored = sorted({k[1] for k in r if k[0] == "stored"})
    body = [[f"`{s}`", n(g("stored", s, "devices")), n(g("stored", s, "chunks")), n(g("stored", s, "lines")),
             f"{g('stored', s, 'bytes') / 1e9:.2f}", "yes" if s in SOURCES else "no"] for s in stored]
    body.append(["**all**", "", n(sum(g("stored", s, "chunks") for s in stored)), n(sum(g("stored", s, "lines") for s in stored)),
                 f"{sum(g('stored', s, 'bytes') for s in stored) / 1e9:.2f}", ""])
    table(["Source", "Devices", "Chunks", "Lines", "GB", "Read by this run"], body)

    print("### What was read, and where each line's time came from\n")
    body = []
    for s in SOURCES:
        lines = g("cov", s, "lines")
        body.append([f"`{s}`", n(g("tpl", s, "devices")), n(lines), pct(g("cov", s, "time_on_line"), lines),
                     pct(g("cov", s, "time_inherited"), lines), pct(g("cov", s, "time_from_collection"), lines),
                     n(g("cov", s, "unparsed_lines")), n(g("cov", s, "lines_over_key_cap"))])
    table(["Source", "Devices", "Lines", "Time on the line", "Inherited in chunk", "From collection time", "Unparsed",
           "Over key cap"], body)

    print("### History per device and source (days with lines)\n")
    body = [[f"`{s}`", g("days", s, "learn_min"), g("days", s, "learn_median"), g("days", s, "learn_max"),
             g("days", s, "test_median"), g("days", s, "devices_enough_history"), g("days", s, "devices_short_history"),
             g("ratescope", s, "devices_evaluable"), g("ratescope", s, "devices_not_evaluable")] for s in SOURCES]
    table(["Source", "Learning days min", "median", "max", "Test days median", "Devices with enough history",
           "Devices with short history", "Hourly rate evaluable (devices)", "not evaluable"], body)

    print("### Templates\n")
    body = []
    for s in SOURCES:
        keyed = g("tpl", s, "keyed_lines")
        body.append([f"`{s}`", n(g("tpl", s, "templates_vocabulary")),
                     n(g("tpl", s, "templates_typed")) + ("+" if g("tpl", s, "typed_set_saturated") else ""),
                     pct(g("tpl", s, "lines_top10"), keyed), pct(g("tpl", s, "lines_top100"), keyed),
                     pct(g("tpl", s, "lines_top1000"), keyed), n(g("tpl", s, "templates_fleet_common")),
                     pct(g("tpl", s, "lines_fleet_common"), keyed), n(g("tpl", s, "templates_one_device")),
                     pct(g("tpl", s, "lines_one_device"), keyed)])
    table(["Source", "Templates (vocabulary form)", "Finer templates", "Lines in top 10", "top 100", "top 1,000",
           "Fleet-common templates", "their lines", "One-device templates", "their lines"], body)

    print("### Line classes (share of each source's lines; PROVISIONAL keyword classes)\n")
    tot = {s: sum(v for k, v in r.items() if k[0] == "cls" and k[1] == s) for s in SOURCES}
    body = []
    for c in CLASSES:
        row = [c]
        for s in SOURCES:
            row.append(pct(sum(v for k, v in r.items() if k[0] == "cls" and k[1] == s and k[2] == c), tot[s]))
        row.append(n(sum(v for k, v in r.items() if k[0] == "cls" and k[2] == c)))
        body.append(row)
    row = ["with an error word or high severity"]
    for s in SOURCES:
        row.append(pct(sum(v for k, v in r.items() if k[0] == "cls" and k[1] == s and k[3] == "sev_error"), tot[s]))
    row.append(n(sum(v for k, v in r.items() if k[0] == "cls" and k[3] == "sev_error")))
    body.append(row)
    table(["Class"] + [f"`{s}`" for s in SOURCES] + ["Lines, all sources"], body)

    print("### Routine share of the test week's lines\n")
    body = [[f"`{s}`", n(g("routine", s, "test_lines")), pct(g("routine", s, "test_lines_routine"), g("routine", s, "test_lines")),
             pct(g("routine", s, "test_lines_short_history"), g("routine", s, "test_lines"))] for s in SOURCES]
    table(["Source", "Lines in the test week", "In routine templates", "On devices with short history"], body)

    print("### Processes in `cp_messages` and Palo Alto subtypes (allowlisted names only)\n")
    procs = sorted(((v, k[2]) for k, v in r.items() if k[0] == "proc" and k[1] == "cp_messages"), reverse=True)
    total = sum(v for v, _ in procs)
    table(["Process", "Lines", "Share"], [[f"`{p}`", n(v), pct(v, total)] for v, p in procs[:12]])
    subs = sorted(((v, k[1]) for k, v in r.items() if k[0] == "pansub"), reverse=True)
    total = sum(v for v, _ in subs)
    table(["Palo Alto subtype", "Lines", "Share"], [[f"`{p}`", n(v), pct(v, total)] for v, p in subs[:14]])

    print("### Findings in the test week\n")
    body = []
    for k, v in sorted(r.items()):
        if k[0] == "sig" and v:
            body.append([" / ".join(k[1:-1]), k[-1], n(v)])
    table(["Signature", "Measure", "Count"], body)
    agg = collections.defaultdict(int)
    for k, v in r.items():
        if k[0] == "new" and k[-1] == "events":
            group = "excluded class" if k[3] in ("auth_admin", "cron_routine") else "signature class" if k[3] in (
                "crash", "ha_state", "link") or (k[3] == "proc_restart" and k[1] == "cp_cpwd_elg") else "other classes"
            agg[(k[1], k[2], group, "with severity" if k[4] != "sev_none" else "no severity word")] += v
    body = [[f"`{k[0]}`", k[1], k[2], k[3], n(v)] for k, v in sorted(agg.items())]
    table(["Source", "Prevalence", "Class group", "Severity", "New-template events"], body)
    body = []
    for s in SOURCES:
        row = [f"`{s}`"]
        for t in ("base", "strict", "loose"):
            row.append(n(sum(v for k, v in r.items() if k[0] == "rate" and k[1] == t and k[2] == s and k[4] == "findings")))
        row.append(n(sum(v for k, v in r.items() if k[0] == "rate" and k[1] == "base" and k[2] == s and k[4] == "findings"
                         and k[3] in ("auth_admin", "cron_routine"))))
        body.append(row)
    table(["Source", "Rate findings, base thresholds", "strict", "loose", "of base: excluded classes"], body)
    body = [[k[1], n(v)] for k, v in sorted(r.items()) if k[0] == "kind"]
    table(["Finding kind", "Device-hours"], body)

    print("### The funnel (test week)\n")
    days = g("qdist", "base", "observed_device_days")
    table(["Measure", "Check Point", "Palo Alto"],
          [["Devices", n(g("meta", "devices_cp")), n(g("meta", "devices_pan"))],
           ["Device-hours with lines", n(g("hours", "cp", "device_hours_with_lines")), n(g("hours", "pan", "device_hours_with_lines"))]])
    body = []
    for p in POLICIES:
        ep = g("q", p, "cp", "episodes") + g("q", p, "pan", "episodes")
        body.append([p, n(g("q", p, "cp", "device_hours") + g("q", p, "pan", "device_hours")), n(g("q", p, "cp", "episodes")),
                     n(g("q", p, "pan", "episodes")), n(ep), f"{ep / max(days, 1):.2f}", f"{ep / 7.0:.0f}"])
    table(["Policy", "Device-hours", "Episodes CP", "Episodes PAN", "Episodes", "per observed device-day", "per day, fleet"], body)
    body = []
    for p in POLICIES:
        d = g("qdist", p, "observed_device_days")
        body.append([p, n(d), pct(g("qdist", p, "q0"), d), pct(g("qdist", p, "q1"), d), pct(g("qdist", p, "q2"), d),
                     pct(g("qdist", p, "q3_5"), d), pct(g("qdist", p, "q6_10"), d), pct(g("qdist", p, "q11_plus"), d),
                     g("qdist", p, "p50"), g("qdist", p, "p90"), g("qdist", p, "p99"), g("qdist", p, "max")])
    table(["Policy", "Observed device-days", "0 episodes", "1", "2", "3-5", "6-10", "11+", "p50", "p90", "p99", "max"], body)
    body = [[p] + [n(g("qday", p, f"d{d}")) for d in range(7)] for p in POLICIES]
    body.append(["devices observed"] + [n(g("qday", "observed_devices", f"d{d}")) for d in range(7)])
    table(["Episodes per test day"] + [f"day {d + 1}" for d in range(7)], body)

    print("### Session lines and the most common source address\n")
    table(["Measure", "Count"],
          [["Lines of the admin and session class", n(g("self", "auth_lines"))],
           ["of them with an IPv4 address", n(g("self", "auth_lines_with_ipv4"))],
           ["Distinct addresses in those lines", n(g("self", "distinct_sources"))],
           ["Devices on which the most widespread address occurs", n(g("self", "top_source_devices"))],
           ["Lines carrying that address", n(g("self", "top_source_lines"))]])
    backtest_tables(r)
    sweep_tables(r)
    rule_tables(r)
    meta = {k[1]: v for k, v in r.items() if k[0] == "meta"}
    print("Meta: " + ", ".join(f"{k}={v}" for k, v in sorted(meta.items())))


if __name__ == "__main__":
    main()
