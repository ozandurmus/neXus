#!/usr/bin/env python3
"""CLI for the LLM trial: estimate, count-tokens, run, report. Synthetic data only; not product code."""

from __future__ import annotations

import argparse
import json
import sys
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

_ROOT = Path(__file__).resolve().parents[2]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from tools.llm_trial import pricing, prompt, providers, report, scoring  # noqa: E402

DEFAULT_MODELS = ["claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"]
CHARS_PER_TOKEN = providers.CHARS_PER_TOKEN


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_cases() -> list[dict[str, Any]]:
    from tools.llm_trial import corpus

    return corpus.load_cases()


def add_filters(p: argparse.ArgumentParser) -> None:
    p.add_argument("--models", nargs="+", default=DEFAULT_MODELS)
    p.add_argument("--efforts", nargs="+", default=["low", "medium"])
    p.add_argument("--levels", nargs="+", type=int, default=[1, 2])
    p.add_argument("--cases", default=None, help="comma-separated case ids, default all")
    p.add_argument("--max-tokens", type=int, default=4096)


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="run_trial", description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    est = sub.add_parser("estimate", help="offline call count and cost estimate")
    add_filters(est)
    est.add_argument("--out-tokens-low", type=int, default=1200)
    est.add_argument("--out-tokens-medium", type=int, default=2000)
    cnt = sub.add_parser("count-tokens", help="free token counting through the API")
    add_filters(cnt)
    cnt.add_argument("--provider", choices=["claude"], default="claude")
    cnt.add_argument("--out", required=True)
    run = sub.add_parser("run", help="run the trial")
    add_filters(run)
    run.add_argument("--provider", choices=["mock", "claude"], required=True)
    run.add_argument("--out", required=True)
    run.add_argument("--budget-usd", type=float, default=9.50)
    run.add_argument("--workers", type=int, default=3)
    run.add_argument("--resume", action="store_true")
    run.add_argument("--yes-paid-run", action="store_true")
    rep = sub.add_parser("report", help="write summary.md and CSVs")
    rep.add_argument("--in", dest="in_dir", required=True)
    return ap


def select_cases(cases: list[dict[str, Any]], ids: str | None) -> list[dict[str, Any]]:
    if not ids:
        return cases
    wanted = [i.strip() for i in ids.split(",") if i.strip()]
    by_id = {c["case_id"]: c for c in cases}
    missing = [i for i in wanted if i not in by_id]
    if missing:
        raise SystemExit(f"unknown case ids: {', '.join(missing)}")
    return [by_id[i] for i in wanted]


def est_tokens(text: str) -> int:
    return int(len(text) / CHARS_PER_TOKEN)


def jobs_for(cases, models, efforts, levels):
    """Case-major order so a budget stop leaves every configuration with the same cases."""
    return [(c, m, e, lv) for c in cases for m in models for e in efforts for lv in levels]


# ---------------------------------------------------------------- estimate

def estimate_rows(cases, args) -> list[dict[str, Any]]:
    prefix = est_tokens(prompt.STATIC_PREFIX)
    out_tokens = {"low": args.out_tokens_low, "medium": args.out_tokens_medium}
    rows = []
    for model in args.models:
        p = pricing.prices_for(model)
        calls = expected = worst = 0.0
        in_total = 0
        first = True
        for case, _m, effort, level in jobs_for(cases, [model], args.efforts, args.levels):
            user = est_tokens(prompt.user_message(case, level))
            usage = {"input": user, "cache_read": 0 if first else prefix, "cache_write": prefix if first else 0,
                     "output": out_tokens.get(effort, args.out_tokens_medium)}
            first = False
            expected += pricing.call_cost(model, usage)
            worst += ((user + prefix) * p["input"] + args.max_tokens * p["output"]) / pricing.PER
            in_total += user + prefix
            calls += 1
        rows.append({"model": model, "calls": int(calls), "input_tokens": in_total, "expected": expected, "worst": worst})
    return rows


def cmd_estimate(args, cases) -> int:
    rows = estimate_rows(select_cases(cases, args.cases), args)
    print(f"{'model':<20}{'calls':>7}{'input tok':>12}{'expected $':>12}{'worst $':>10}")
    for r in rows:
        print(f"{r['model']:<20}{r['calls']:>7}{r['input_tokens']:>12}{r['expected']:>12.3f}{r['worst']:>10.3f}")
    print(f"{'TOTAL':<20}{sum(r['calls'] for r in rows):>7}{sum(r['input_tokens'] for r in rows):>12}"
          f"{sum(r['expected'] for r in rows):>12.3f}{sum(r['worst'] for r in rows):>10.3f}")
    return 0


# ---------------------------------------------------------------- credentials

def require_credentials() -> None:
    if not providers.credentials_available():
        raise SystemExit("no Anthropic credentials found: set ANTHROPIC_API_KEY (or ANTHROPIC_AUTH_TOKEN) "
                         "or run `ant auth login`; nothing was sent")


# ---------------------------------------------------------------- count-tokens

def cmd_count_tokens(args, cases) -> int:
    require_credentials()
    cases = select_cases(cases, args.cases)
    prov = providers.ClaudeProvider()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    counts: dict[str, Any] = {}
    for model in args.models:
        entry: dict[str, Any] = {"prefix": prov.count_tokens(None, model, 1), "cases": {}}
        for case in cases:
            entry["cases"][case["case_id"]] = {
                f"L{lv}": prov.count_tokens(case, model, lv) for lv in args.levels
            }
        counts[model] = entry
    (out_dir / "token_counts.json").write_text(json.dumps(counts, indent=1), encoding="utf-8")
    for model, entry in counts.items():
        print(summarise_counts(model, entry, cases))
    return 0


def summarise_counts(model: str, entry: dict[str, Any], cases) -> str:
    def mean(level: str) -> float | None:
        vals = [c[level] for c in entry["cases"].values() if level in c]
        return sum(vals) / len(vals) if vals else None

    extra = lines = 0
    for case in cases:
        c = entry["cases"][case["case_id"]]
        if "L1" in c and "L2" in c:
            extra += c["L2"] - c["L1"]
            lines += len(case.get("excerpts", []))
    per_line = f"{extra / lines:.1f}" if lines else "-"
    l1, l2 = mean("L1"), mean("L2")
    return (f"{model}: prefix {entry['prefix']} tok, mean L1 {l1 if l1 is None else round(l1)}, "
            f"mean L2 {l2 if l2 is None else round(l2)}, tokens per excerpt line {per_line}")


# ---------------------------------------------------------------- run

class BudgetGuard:
    """Thread-safe worst-case reservation against a USD budget."""

    def __init__(self, budget: float) -> None:
        self.budget = budget
        self.spent = 0.0
        self.reserved = 0.0
        self.stopped = False
        self._lock = threading.Lock()

    def reserve(self, amount: float) -> bool:
        with self._lock:
            if self.stopped or self.spent + self.reserved + amount > self.budget:
                self.stopped = True
                return False
            self.reserved += amount
            return True

    def settle(self, reservation: float, actual: float) -> float:
        with self._lock:
            self.reserved -= reservation
            self.spent += actual
            return self.spent


def worst_case_cost(model: str, case, level: int, max_tokens: int) -> float:
    p = pricing.prices_for(model)
    tokens = est_tokens(prompt.STATIC_PREFIX) + est_tokens(prompt.user_message(case, level))
    return (tokens * p["input"] + max_tokens * p["output"]) / pricing.PER


def done_keys(results_path: Path) -> set[str]:
    keys: dict[str, str] = {}
    if results_path.exists():
        for line in results_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                keys[row["call_id"]] = row["status"]
    return {k for k, status in keys.items() if status != "ERROR"}


def prior_spend(results_path: Path) -> float:
    """USD already spent by earlier invocations writing to this results file (every line counts:
    an errored call that was retried was still billed for whatever it used)."""
    total = 0.0
    if results_path.exists():
        for line in results_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                total += float(json.loads(line).get("cost_usd") or 0.0)
    return total


def execute_jobs(jobs, provider, args, guard: BudgetGuard, results_path: Path) -> dict[str, Any]:
    write_lock = threading.Lock()
    totals = {"calls": 0, "skipped": 0, "by_status": {}}

    def work(job) -> None:
        case, model, effort, level = job
        reservation = worst_case_cost(model, case, level, args.max_tokens)
        if not guard.reserve(reservation):
            with write_lock:
                totals["skipped"] += 1
            return
        try:
            result = provider.call(case, model, effort, level, args.max_tokens)
        except Exception as exc:  # noqa: BLE001 - a provider bug must not kill the run
            result = providers.new_result(case["case_id"], model, effort, level)
            result["error"] = f"{type(exc).__name__}: {str(exc)[:300]}"
        spent = guard.settle(reservation, result["cost_usd"])
        result["score"] = scoring.score(case, level, result)
        with write_lock:
            with results_path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(result, ensure_ascii=False) + "\n")
            totals["calls"] += 1
            totals["by_status"][result["status"]] = totals["by_status"].get(result["status"], 0) + 1
            print(f"{case['case_id']} {model} {effort} L{level} {result['status']} "
                  f"${result['cost_usd']:.5f} cumulative ${spent:.4f}", flush=True)

    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        list(pool.map(work, jobs))
    return totals


def cmd_run(args, cases_loader: Callable[[], list[dict[str, Any]]]) -> int:
    if args.provider == "claude":
        if not args.yes_paid_run:
            raise SystemExit("--provider claude is a paid run: add --yes-paid-run to confirm")
        require_credentials()
    cases = select_cases(cases_loader(), args.cases)
    provider = providers.ClaudeProvider() if args.provider == "claude" else providers.MockProvider()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    results_path = out_dir / "results.jsonl"
    if not args.resume and results_path.exists():
        raise SystemExit(f"{results_path} exists: use --resume or a new --out directory")
    skip = done_keys(results_path) if args.resume else set()
    jobs = [j for j in jobs_for(cases, args.models, args.efforts, args.levels)
            if providers.call_id(j[0]["case_id"], j[1], j[2], j[3]) not in skip]
    (out_dir / "cases_snapshot.json").write_text(json.dumps(cases, ensure_ascii=False), encoding="utf-8")
    guard = BudgetGuard(args.budget_usd)
    if args.resume:
        guard.spent = prior_spend(results_path)  # the ceiling covers the whole results file
        print(f"resume: {len(skip)} calls already done, ${guard.spent:.4f} already spent")
    started = utc_now()
    totals = execute_jobs(jobs, provider, args, guard, results_path)
    if totals["skipped"]:
        print(f"budget stop: {totals['skipped']} calls skipped (spent ${guard.spent:.4f} of ${args.budget_usd:.2f})")
    write_meta(out_dir, args, cases, started, totals, guard, len(skip))
    print(f"done: {totals['calls']} calls, spent ${guard.spent:.4f}, results in {results_path}")
    return 0


def sdk_version() -> str | None:
    try:
        from importlib.metadata import version

        return version("anthropic")
    except Exception:  # noqa: BLE001
        return None


def write_meta(out_dir: Path, args, cases, started, totals, guard, resumed) -> None:
    meta = {
        "run_id": uuid.uuid4().hex[:12], "started_utc": started, "ended_utc": utc_now(),
        "provider": args.provider, "models": args.models, "efforts": args.efforts, "levels": args.levels,
        "case_ids": [c["case_id"] for c in cases], "max_tokens": args.max_tokens, "budget_usd": args.budget_usd,
        "prompt_version": prompt.PROMPT_VERSION, "sdk_version": sdk_version(), "resumed_skipped": resumed,
        "totals": {"calls": totals["calls"], "skipped_for_budget": totals["skipped"],
                   "by_status": totals["by_status"], "spent_usd": round(guard.spent, 6)},
    }
    (out_dir / "run_meta.json").write_text(json.dumps(meta, indent=1), encoding="utf-8")


# ---------------------------------------------------------------- report

def cmd_report(args, cases_loader) -> int:
    in_dir = Path(args.in_dir)
    snap = in_dir / "cases_snapshot.json"
    cases = json.loads(snap.read_text(encoding="utf-8")) if snap.exists() else cases_loader()
    path = report.write_report(in_dir, cases)
    print(f"wrote {path}")
    return 0


def main(argv: list[str] | None = None, cases_loader: Callable[[], list[dict[str, Any]]] | None = None) -> int:
    args = build_parser().parse_args(argv)
    loader = cases_loader or load_cases
    if args.cmd == "estimate":
        return cmd_estimate(args, loader())
    if args.cmd == "count-tokens":
        return cmd_count_tokens(args, loader())
    if args.cmd == "run":
        return cmd_run(args, loader)
    return cmd_report(args, loader)


if __name__ == "__main__":
    sys.exit(main())
