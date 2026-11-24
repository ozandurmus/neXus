#!/usr/bin/env python3
"""Daily delivery metrics from the runner logs (PO 2026-11-24: compare every day with the 11-23/11-24 baseline).

Usage: python3 daily-metrics.py [YYYY-MM-DD ...]   (default: the last 3 days)
Reads $NEXUS_OPS_STATE/logs/ship-runner.log and lane-runner.log (default ~/Codo/ops-scripts). Counts only; no identifiers.
Versioned copy (tools/delivery/ops, 2026-11-24 review): also counts first BLOCKs returned to the author lane
(review_returned) and lane ALERT lines (lane_alerts: held, failed, worktree failed twice); a missing log counts as empty.
"""
import os
import collections
import datetime as dt
import re
import statistics
import sys
from pathlib import Path

L = Path(os.environ.get("NEXUS_OPS_STATE") or Path.home() / "Codo/ops-scripts") / "logs"
NAME_PREFIX = re.compile(r"^([0-9]{8}-[0-9]{4}-G[0-9]+-D[0-9]+-|[0-9]{1,5}-)")
PRODUCT = {"p1", "ui", "frontend", "java", "docs", "debt", "ci"}


def kind(name: str) -> str:
    rest = NAME_PREFIX.sub("", name)
    head = rest.split("-", 1)[0]
    return {"state": "state-sync"}.get(head, head)


def fail_class(text: str) -> str:
    t = text.lower()
    if "image_build" in t:
        return "preview-build"
    if "unit tests did not pass" in t:
        return "unit-tests"
    if "still pending" in t:
        return "ci-pending"
    if "checks failed" in t:
        return "ci-check"
    if "graphql" in t or "connection reset" in t or "not merged (" in t:
        return "github-api"
    if "nothing to ship" in t:
        return "nothing-to-ship"
    if "preview e2e" in t and "fail" in t:
        return "preview-e2e"
    if "rebase" in t or "conflict" in t:
        return "conflict"
    return "other"


def read_lines(name: str) -> list:
    try:
        return (L / name).read_text(errors="replace").splitlines()
    except OSError:
        return []


def day_metrics(day: str) -> dict:
    m = collections.Counter()
    fails = collections.Counter()
    start, durations = {}, []
    for line in read_lines("ship-runner.log"):
        if not line.startswith(day):
            continue
        g = re.match(r"(\S+ \S+) (SHIPPING|SHIPPED|FAILED) batch/(\S+?):(.*)", line)
        if g:
            t = dt.datetime.strptime(g.group(1), "%Y-%m-%d %H:%M:%S")
            if g.group(2) == "SHIPPING":
                start[g.group(3)] = t
                m["batches_started"] += 1
            elif g.group(2) == "SHIPPED":
                m["batches_shipped"] += 1
                m["items_shipped"] += len(re.sub(r"\(.*?\)", "", g.group(4)).split())
                if g.group(3) in start:
                    durations.append((t - start[g.group(3)]).total_seconds() / 60)
            else:
                m["batches_failed"] += 1
                fails[fail_class(g.group(4))] += 1
        for tag, key in ((" HELD ", "held"), (" B-REVIEW BLOCK ", "review_block"), (" B-REVIEW RETURNED ", "review_returned"), (" B-REVIEW PASS ", "review_pass"),
                         (" CONFLICT ", "conflict"), (" queued lane ", "runner_queued_lanes")):
            if tag in line:
                m[key] += 1
    kinds = collections.Counter()
    for line in read_lines("lane-runner.log"):
        if not line.startswith(day):
            continue
        g = re.search(r" started L\S+ #\d+ (\S+)", line)
        if g:
            kinds[kind(g.group(1))] += 1
        if re.search(r" done .*exit=[12] ", line) or "FAST FAIL" in line:
            m["lane_errors"] += 1
        if " HELD " in line:
            m["lane_held"] += 1
        if " ALERT " in line:
            m["lane_alerts"] += 1
    starts = sum(kinds.values())
    pct = lambda n: round(100 * n / starts) if starts else 0  # noqa: E731
    attempts = m["batches_shipped"] + m["batches_failed"]
    return {
        "day": day,
        "lane_starts": starts,
        "product_pct": pct(sum(v for k, v in kinds.items() if k in PRODUCT)),
        "rework_pct": pct(kinds["fix"] + kinds["port"]),
        "sync_pct": pct(kinds["state-sync"]),
        "refill_pct": pct(kinds["refill"]),
        "batches_shipped": m["batches_shipped"],
        "items_shipped": m["items_shipped"],
        "ship_fail_pct": round(100 * m["batches_failed"] / attempts) if attempts else 0,
        "ci_only_fails": fails["ci-check"] + fails["ci-pending"],
        "held": m["held"],
        "review_block": m["review_block"],
        "review_returned": m["review_returned"],
        "lane_alerts": m["lane_alerts"],
        "conflicts": m["conflict"],
        "lane_errors": m["lane_errors"],
        "ship_median_min": round(statistics.median(durations), 1) if durations else None,
        "fail_classes": dict(fails),
    }


def main() -> None:
    days = sys.argv[1:] or [(dt.date.today() - dt.timedelta(days=i)).isoformat() for i in (2, 1, 0)]
    rows = [day_metrics(d) for d in days]
    cols = ["lane_starts", "product_pct", "rework_pct", "sync_pct", "refill_pct", "batches_shipped", "items_shipped",
            "ship_fail_pct", "ci_only_fails", "held", "review_block", "review_returned", "conflicts", "lane_errors", "lane_alerts", "ship_median_min"]
    print("| metric | " + " | ".join(r["day"] for r in rows) + " |")
    print("|---|" + "---|" * len(rows))
    for c in cols:
        print(f"| {c} | " + " | ".join(str(r[c]) for r in rows) + " |")
    print("| fail_classes | " + " | ".join(", ".join(f"{k} {v}" for k, v in sorted(r["fail_classes"].items())) or "-" for r in rows) + " |")


if __name__ == "__main__":
    main()
