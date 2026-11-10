"""Summary tables and rubric sheets from results.jsonl."""

from __future__ import annotations

import csv
import json
import math
import random
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from tools.llm_trial import pricing  # repo root on sys.path (run_trial.py and the tests add it)

VOLUMES = (
    ("upper_bound (92 devices x 30 days x 2)", 5520),
    ("on_demand_high (100/day)", 3000),
    ("on_demand_typical (20/day)", 600),
)
SAMPLE_PER_CONFIG = 3
SAMPLE_SEED = "llm-trial-1110"
Config = tuple


def load_results(path: Path) -> list[dict[str, Any]]:
    """Read results.jsonl; the last line per call_id wins (resumed runs append retries)."""
    latest: dict[str, dict[str, Any]] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                latest[row["call_id"]] = row
    return list(latest.values())


def percentile(values: list[float], pct: float) -> float | None:
    """Nearest-rank percentile."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(pct / 100.0 * len(ordered)))
    return ordered[rank - 1]


def _pct(count: int, n: int) -> str:
    return f"{100.0 * count / n:.0f}%" if n else "-"


def _mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def _fmt(value: float | None, digits: int = 1) -> str:
    return "-" if value is None else f"{value:.{digits}f}"


def _flag(row: dict[str, Any], key: str) -> bool:
    return bool((row.get("score") or {}).get(key))


def group(results: list[dict[str, Any]], keyfn) -> dict[Config, list[dict[str, Any]]]:
    out: dict[Config, list[dict[str, Any]]] = defaultdict(list)
    for r in results:
        out[keyfn(r)].append(r)
    return dict(sorted(out.items()))


def _config(r: dict[str, Any]) -> Config:
    return (r["model"], r["effort"], r["level"])


def config_stats(rows: list[dict[str, Any]], cases: dict[str, dict[str, Any]]) -> dict[str, Any]:
    n = len(rows)
    refusals = Counter(r["refusal_category"] or "unspecified" for r in rows if r["status"] == "REFUSED")
    inj_cases = [r for r in rows if (cases.get(r["case_id"], {}).get("expected") or {}).get("injection")]
    lat = [r["latency_s"] for r in rows if r.get("latency_s") is not None]
    usage = lambda key: _mean([r["usage"][key] for r in rows])  # noqa: E731
    nocache = [pricing.nocache_cost(r["model"], r["usage"]) for r in rows]
    total = sum(r["cost_usd"] for r in rows)
    return {
        "n": n,
        "ok": sum(r["status"] == "OK" for r in rows),
        "schema_valid": sum(_flag(r, "schema_valid") for r in rows),
        "refs": sum(_flag(r, "refs_resolve") for r in rows),
        "category": sum(_flag(r, "category_ok") for r in rows),
        "priority": sum(_flag(r, "priority_ok") for r in rows),
        "pass_all": sum(_flag(r, "pass_all") for r in rows),
        "refused": sum(refusals.values()),
        "refusal_cats": ", ".join(f"{k}:{v}" for k, v in sorted(refusals.items())) or "-",
        "truncated": sum(r["status"] == "TRUNCATED" for r in rows),
        "errors": sum(r["status"] == "ERROR" for r in rows),
        "inj_followed": sum(_flag(r, "injection_followed") for r in inj_cases),
        "inj_cases": len(inj_cases),
        "p50": percentile(lat, 50),
        "p95": percentile(lat, 95),
        "in": usage("input"), "cr": usage("cache_read"), "cw": usage("cache_write"),
        "out": usage("output"), "think": usage("thinking"),
        "total": total,
        "per_call": total / n if n else 0.0,
        "nocache_per_call": _mean(nocache),
    }


def md_table(headers: list[str], rows: list[list[Any]]) -> str:
    lines = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(lines)


def config_table(results: list[dict[str, Any]], cases: dict[str, dict[str, Any]]) -> str:
    headers = ["model", "effort", "L", "n", "OK", "schema", "refs", "category", "priority", "pass_all",
               "refused", "trunc", "err", "inj followed", "p50 s", "p95 s", "in", "cache_r", "cache_w",
               "out", "think", "total $", "$/call", "no-cache $/call"]
    rows = []
    for (model, effort, level), grp in group(results, _config).items():
        s = config_stats(grp, cases)
        n = s["n"]
        rows.append([model, effort, level, n, s["ok"], _pct(s["schema_valid"], n), _pct(s["refs"], n),
                     _pct(s["category"], n), _pct(s["priority"], n), _pct(s["pass_all"], n),
                     f"{s['refused']} ({s['refusal_cats']})", s["truncated"], s["errors"],
                     f"{s['inj_followed']}/{s['inj_cases']}", _fmt(s["p50"]), _fmt(s["p95"]),
                     f"{s['in']:.0f}", f"{s['cr']:.0f}", f"{s['cw']:.0f}", f"{s['out']:.0f}", f"{s['think']:.0f}",
                     f"{s['total']:.4f}", f"{s['per_call']:.5f}", f"{s['nocache_per_call']:.5f}"])
    return md_table(headers, rows)


def refusal_table(results: list[dict[str, Any]]) -> str:
    rows = []
    for (model,), grp in group(results, lambda r: (r["model"],)).items():
        ref = Counter(r["refusal_category"] or "unspecified" for r in grp if r["status"] == "REFUSED")
        count = sum(ref.values())
        cats = ", ".join(f"{k}:{v}" for k, v in sorted(ref.items())) or "-"
        rows.append([model, len(grp), count, _pct(count, len(grp)), cats])
    return md_table(["model", "calls", "refusals", "rate", "categories"], rows)


def level_effect_table(results: list[dict[str, Any]]) -> str:
    rows = []
    for (model, effort), grp in group(results, lambda r: (r["model"], r["effort"])).items():
        by_level = {lv: [r for r in grp if r["level"] == lv] for lv in (1, 2)}
        pct = {lv: (100.0 * sum(_flag(r, "category_ok") for r in rs) / len(rs) if rs else None)
               for lv, rs in by_level.items()}
        diff = None if pct[1] is None or pct[2] is None else pct[2] - pct[1]
        rows.append([model, effort, _fmt(pct[1], 0), _fmt(pct[2], 0), _fmt(diff, 0)])
    return md_table(["model", "effort", "category % L1", "category % L2", "L2 - L1 (points)"], rows)


def projection_table(results: list[dict[str, Any]], cases: dict[str, dict[str, Any]]) -> str:
    headers = ["model", "effort", "L"]
    for label, _ in VOLUMES:
        headers += [f"{label} no-cache $", f"{label} measured $"]
    rows = []
    for (model, effort, level), grp in group(results, _config).items():
        s = config_stats(grp, cases)
        row: list[Any] = [model, effort, level]
        for _, calls in VOLUMES:
            row += [f"{calls * s['nocache_per_call']:.2f}", f"{calls * s['per_call']:.2f}"]
        rows.append(row)
    return md_table(headers, rows)


def per_case_table(results: list[dict[str, Any]], cases: dict[str, dict[str, Any]]) -> str:
    rows = []
    for (cid,), grp in group(results, lambda r: (r["case_id"],)).items():
        rows.append([cid, cases.get(cid, {}).get("scenario_class", "?"),
                     f"{sum(_flag(r, 'pass_all') for r in grp)}/{len(grp)}"])
    return md_table(["case", "scenario class", "pass_all / n"], rows)


def build_summary(results: list[dict[str, Any]], cases: dict[str, dict[str, Any]]) -> str:
    total = sum(r["cost_usd"] for r in results)
    parts = [
        "# LLM trial summary",
        f"{len(results)} calls, total cost ${total:.4f}. Percentages are over all calls of the configuration "
        "(refused, truncated and errored calls count as misses).",
        "## Per configuration", config_table(results, cases),
        "## Refusals per model", refusal_table(results),
        "## Level effect (category match)", level_effect_table(results),
        "## Monthly projection (USD)", projection_table(results, cases),
        "## Per case", per_case_table(results, cases),
    ]
    return "\n\n".join(parts) + "\n"


def _answer_fields(r: dict[str, Any]) -> dict[str, Any]:
    o = r["output_json"]
    return {
        "summary": o["summary"],
        "what_happened": o["what_happened"],
        "category": o["likely_cause"]["category"],
        "confidence": o["likely_cause"]["confidence"],
        "priority": o["triage"]["priority"],
        "evidence_refs": ", ".join(e["reference"] for e in o["evidence"]),
        "unknowns": " | ".join(o["unknowns"]),
        "next_checks": " | ".join(f"{c['kind']}:{c['target']}" for c in o["next_checks"]),
    }


def _auto_flags(r: dict[str, Any]) -> str:
    s = r.get("score") or {}
    flags = []
    for key, bad in (("refs_resolve", False), ("category_ok", False), ("priority_ok", False),
                     ("next_checks_ok", False), ("injection_followed", True), ("url_present", True),
                     ("command_like", True)):
        value = s.get(key)
        if value is not None and value == bad:
            flags.append(("" if bad else "not_") + key)
    return ", ".join(flags)


def write_csv(path: Path, header: list[str], rows: list[dict[str, Any]]) -> None:
    with path.open("w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.DictWriter(fh, fieldnames=header, quoting=csv.QUOTE_ALL)
        writer.writeheader()
        writer.writerows(rows)


ANSWER_COLS = ["summary", "what_happened", "category", "confidence", "priority", "evidence_refs",
               "unknowns", "next_checks"]


def full_rows(results: list[dict[str, Any]], cases: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    ok = sorted((r for r in results if r["status"] == "OK" and r.get("output_json")),
                key=lambda r: (r["case_id"], r["model"], r["effort"], r["level"]))
    rows = []
    for i, r in enumerate(ok, 1):
        row = {"blind_id": f"R{i:04d}", "case_id": r["case_id"], "title": cases.get(r["case_id"], {}).get("title", ""),
               "level": r["level"], "model": r["model"], "effort": r["effort"]}
        row.update(_answer_fields(r))
        row.update({"auto_flags": _auto_flags(r), "rating": "", "notes": ""})
        rows.append(row)
    return rows


def sample_blind(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Deterministic stratified sample: 3 OK results per configuration, spreading cases across configs."""
    rng = random.Random(SAMPLE_SEED)
    used: Counter = Counter()
    chosen = []
    for cfg, grp in group([r for r in results if r["status"] == "OK" and r.get("output_json")], _config).items():
        pool = sorted(grp, key=lambda r: r["case_id"])
        rng.shuffle(pool)
        pool.sort(key=lambda r: used[r["case_id"]])  # stable: least-used cases first
        for r in pool[:SAMPLE_PER_CONFIG]:
            used[r["case_id"]] += 1
            chosen.append(r)
    rng.shuffle(chosen)
    chosen.sort(key=lambda r: r["case_id"])
    return chosen


def write_rubrics(out_dir: Path, results: list[dict[str, Any]], cases: dict[str, dict[str, Any]]) -> None:
    full = full_rows(results, cases)
    write_csv(out_dir / "rubric_full.csv",
              ["blind_id", "case_id", "title", "level", "model", "effort"] + ANSWER_COLS
              + ["auto_flags", "rating", "notes"], full)
    blind, key = [], []
    for i, r in enumerate(sample_blind(results), 1):
        bid = f"B{i:03d}"
        case = cases.get(r["case_id"], {})
        row = {"blind_id": bid, "case_id": r["case_id"], "title": case.get("title", ""), "level": r["level"],
               "truth": case.get("truth", "")}
        row.update(_answer_fields(r))
        row.update({"rating": "", "notes": ""})
        blind.append(row)
        key.append({"blind_id": bid, "model": r["model"], "effort": r["effort"]})
    write_csv(out_dir / "rubric_blind.csv",
              ["blind_id", "case_id", "title", "level", "truth"] + ANSWER_COLS + ["rating", "notes"], blind)
    write_csv(out_dir / "rubric_blind_key.csv", ["blind_id", "model", "effort"], key)


def write_report(out_dir: Path, cases: list[dict[str, Any]]) -> Path:
    by_id = {c["case_id"]: c for c in cases}
    results = load_results(out_dir / "results.jsonl")
    summary = out_dir / "summary.md"
    summary.write_text(build_summary(results, by_id), encoding="utf-8")
    write_rubrics(out_dir, results, by_id)
    return summary
