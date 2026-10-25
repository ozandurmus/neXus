#!/usr/bin/env python3
"""Strict, offline security report projection and baseline comparison (stdlib only)."""
from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta, timezone
import json
import re
from pathlib import Path

TOOLS = ("semgrep", "gitleaks", "trivy", "zap")
SEVERITIES = ("UNKNOWN", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")


REVIEW_WINDOW_DAYS = 3
REVIEW_ITEM_CAP = 50


def finding(tool, rule, location, severity, commit="", fix_available=False):
    if tool not in TOOLS or severity not in SEVERITIES:
        raise ValueError("unknown tool or severity")
    if not all(isinstance(v, str) and v for v in (rule, location)):
        raise ValueError("missing finding identity")
    # Identity never includes source lines, matches, descriptions or secret values.
    location = location.removeprefix("/source/").removeprefix("./")
    row = dict(tool=tool, rule=rule, location=location, severity=severity, commit=commit)
    # Only a boolean crosses into durable output (never the fixed version string); absent means not fixed, so
    # reports written before this field existed stay valid trend evidence.
    if fix_available:
        row["fix_available"] = True
    return row


def project(tool, report, target=""):
    """Discard scanner snippets/secret-bearing fields before persisting any report."""
    rows = []
    if not isinstance(report, list if tool == "gitleaks" else dict):
        raise ValueError("invalid scanner report type")
    if tool == "semgrep":
        # A file Semgrep cannot parse is reported, not fatal; any other error type fails the scan.
        tolerated = ("Syntax error", "Lexical error", "Partial parsing", "Other syntax error", "Timeout")
        errors = report.get("errors") or []
        if not isinstance(report.get("results"), list) or any(
                not str(e.get("type", "")).startswith(tolerated) and str(e.get("level", "error")) == "error"
                for e in errors if isinstance(e, dict)):
            raise ValueError("incomplete semgrep scan")
        for r in report["results"]:
            extra = r["extra"]
            severity = extra.get("metadata", {}).get("impact", extra["severity"]).upper()
            severity = {"ERROR": "HIGH", "WARNING": "MEDIUM", "INVENTORY": "INFO"}.get(severity, severity)
            rows.append(finding(tool, r["check_id"], f'{r["path"]}:{r["start"]["line"]}', severity))
    elif tool == "gitleaks":
        if not isinstance(report, list):
            raise ValueError("invalid gitleaks scan")
        for r in report:
            rows.append(finding(tool, r["RuleID"], r["File"], "HIGH", r.get("Commit", "")))
    elif tool == "trivy":
        if report.get("SchemaVersion") != 2 or not isinstance(report.get("Results", []), list):
            raise ValueError("invalid trivy scan")
        for r in report.get("Results", []):
            for v in r.get("Vulnerabilities") or []:
                # Image digest is deliberately excluded: the same CVE/package survives a rebuild.
                loc = f'{target or r["Target"]}: {v["PkgName"]}'
                fixed = bool(v.get("FixedVersion")) or str(v.get("Status", "")).lower() == "fixed"
                rows.append(finding(tool, v["VulnerabilityID"], loc, v["Severity"].upper(), fix_available=fixed))
            for v in r.get("Misconfigurations") or []:
                if v.get("Status", "FAIL") == "FAIL":
                    rows.append(finding(tool, v["ID"], r["Target"], v["Severity"].upper()))
    elif tool == "zap":
        if not isinstance(report.get("site"), list) or not report["site"]:
            raise ValueError("invalid zap scan")
        for site in report["site"]:
            for alert in site["alerts"]:
                # No URLs, cookies, parameters, request/response bodies in durable output.
                rows.append(finding(tool, str(alert["pluginid"]), "internal-ui",
                                    {"0": "INFO", "1": "LOW", "2": "MEDIUM", "3": "HIGH"}[str(alert["riskcode"])]))
    else:
        raise ValueError("unknown scanner")
    return rows


def identity(row):
    return tuple(row[k] for k in ("tool", "rule", "location", "commit"))


def read_baseline(path):
    """Every validated acceptance, expired ones included."""
    # JSON is a YAML 1.2 subset, permitting a real baseline.yaml without a YAML dependency.
    baseline = json.loads(Path(path).read_text())
    if set(baseline) != {"accepted"} or not isinstance(baseline["accepted"], list):
        raise ValueError("invalid baseline")
    for row in baseline["accepted"]:
        for key in ("tool", "rule", "location", "reason", "owner", "review_date"):
            if not isinstance(row.get(key), str) or not row[key].strip():
                raise ValueError("incomplete acceptance")
        if row["tool"] not in TOOLS:
            raise ValueError("unknown acceptance tool")
        date.fromisoformat(row["review_date"])
    return baseline["accepted"]


def load_baseline(path, today):
    # The review date is inclusive: an acceptance stops suppressing the day after it, which is what makes an
    # expired acceptance block the ship gate again.
    return [row for row in read_baseline(path) if date.fromisoformat(row["review_date"]) >= today]


def accepted_review(current, acceptances, today):
    """Accepted-findings review: fixes now reported by the scanner and acceptances near or past review.

    Counts and CVE ids/package names only. It never changes `passed` or `blocking`: a past-review acceptance
    already blocks the gate because load_baseline stops honouring it; a fix or an approaching date only needs a
    human, so it is surfaced through the summary's `attention` flag (and the notification route) instead.
    """
    def package(row):
        # Trivy locations are "<image or file>: <package>"; other tools carry no package, only the rule id.
        return row["location"].rpartition(": ")[2] if row["tool"] == "trivy" and ": " in row["location"] else ""

    fixed_keys = set()
    for row in current:
        if row["tool"] == "trivy" and row.get("fix_available"):
            fixed_keys.add((row["rule"], row["location"]))
    reported = {(row["tool"], row["rule"], row["location"]) for row in current}
    horizon = today + timedelta(days=REVIEW_WINDOW_DAYS)
    seen = set()
    groups = {"fix_available": set(), "due_soon": set(), "past_review": set(), "not_reported": set()}
    for entry in acceptances:
        key = (entry["tool"], entry["rule"], package(entry))
        review = date.fromisoformat(entry["review_date"])
        if review < today:
            groups["past_review"].add(key)
        elif review <= horizon:
            groups["due_soon"].add(key)
        if entry["tool"] != "trivy":
            continue
        if (entry["rule"], entry["location"]) in fixed_keys:
            groups["fix_available"].add(key)
        if (entry["tool"], entry["rule"], entry["location"]) in reported:
            seen.add(key)
        else:
            groups["not_reported"].add(key)
    # An acceptance listed for several images only counts as unreported when no image reports it any more.
    groups["not_reported"] -= seen
    result = {name: len(keys) for name, keys in groups.items()}
    # not_reported (acceptance no longer matches any finding: cleanup candidate) is informational only.
    result["attention"] = any(result[name] for name in ("fix_available", "due_soon", "past_review"))
    # Restricted to the character classes the notification reader accepts.
    def clean(text, limit):
        return re.sub(r"[^A-Za-z0-9._+/@-]", "_", text)[:limit]

    items = [dict(id=clean(rule, 40), package=clean(pkg, 80), status=name)
             for name in ("fix_available", "past_review", "due_soon")
             for _, rule, pkg in sorted(groups[name])]
    result["items"] = items[:REVIEW_ITEM_CAP]
    return result


def summarize(current, previous, accepted, *, failures=()):
    def indexed(rows):
        result = {}
        for r in rows:
            clean = finding(**r)
            key = identity(clean)
            if key not in result or SEVERITIES.index(clean["severity"]) > SEVERITIES.index(result[key]["severity"]):
                clean["fix_available"] = clean.get("fix_available", False) or result.get(key, {}).get("fix_available", False)
                if not clean["fix_available"]:
                    del clean["fix_available"]
                result[key] = clean
            elif clean.get("fix_available"):
                result[key]["fix_available"] = True
        return result

    now, before = indexed(current), indexed(previous)
    counts = {t: {s: dict(new=0, fixed=0, accepted=0, existing=0) for s in SEVERITIES} for t in TOOLS}
    unaccepted_high = 0
    for key, row in now.items():
        match = any(all(row[k] == a[k] for k in ("tool", "rule", "location"))
                    and (not a.get("commit") or row["commit"] == a["commit"]) for a in accepted)
        is_new = key not in before or SEVERITIES.index(row["severity"]) > SEVERITIES.index(before[key]["severity"])
        status = "accepted" if match else "new" if is_new else "existing"
        counts[row["tool"]][row["severity"]][status] += 1
        # Yesterday is trend evidence, not acceptance: yesterday's untriaged HIGH still blocks ship.
        if not match and row["severity"] in ("HIGH", "CRITICAL", "UNKNOWN"):
            unaccepted_high += 1
    for key, row in before.items():
        if key not in now and row["tool"] not in failures:
            counts[row["tool"]][row["severity"]]["fixed"] += 1
    return {"schema_version": 1, "generated_at": datetime.now(timezone.utc).isoformat(), "counts": counts,
            "scan_errors": len(set(failures)), "blocking": unaccepted_high,
            "passed": not failures and unaccepted_high == 0}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--reports", type=Path, required=True)
    p.add_argument("--baseline", type=Path, required=True)
    p.add_argument("--previous", type=Path)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args(argv)
    failures, current, previous, causes = [], [], [], []
    review = None
    today = date.today()
    try:
        acceptances = read_baseline(args.baseline)
        accepted = [row for row in acceptances if date.fromisoformat(row["review_date"]) >= today]
        manifest = json.loads((args.reports / "manifest.json").read_text())
        if not isinstance(manifest, list) or not manifest:
            raise ValueError("empty scan manifest")
        for item in manifest:
            tool = item["tool"]
            if tool not in TOOLS:
                raise ValueError("unknown scanner")
            try:
                name = item["file"]
                if Path(name).name != name:
                    raise ValueError("invalid report path")
                rc = int((args.reports / (name + ".exit")).read_text())
                allowed = (0, 1) if tool == "gitleaks" else (0, 1, 2) if tool == "zap" else (0,)
                if rc not in allowed:
                    raise ValueError("scanner failed")
                current.extend(project(tool, json.loads((args.reports / name).read_text()), item.get("target", "")))
            except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
                failures.append(tool)
                causes.append({"file": item.get("file", "?"), "cause": str(error) if isinstance(error, ValueError) else type(error).__name__})
        if args.previous and args.previous.exists():
            previous = json.loads(args.previous.read_text())
        summary = summarize(current, previous, accepted, failures=failures)
        review = accepted_review(current, acceptances, today)
        if causes:
            summary["failed"] = causes
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        summary = summarize([], [], [], failures=TOOLS)
        current = []
    if review is not None:
        summary["accepted_review"] = review
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "findings.json").write_text(json.dumps(current, indent=2) + "\n")
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
