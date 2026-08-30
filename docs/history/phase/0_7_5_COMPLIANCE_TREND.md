# 0.7.5 — Compliance trend layer (append-only ledger)

**Status:** PLANNED -> (impl record in §10) · **Track:** 0.7.x VERIFY ·
**Movement:** ARCHITECTURE -> IMPLEMENTATION · **Model:** Sonnet 5, normal

Resolves `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §11 open decision 9
("Trend / point-in-time roll-up ... design the payload so a `history[]` array is
additive"). Delivers the additive backend + a minimal Overview surface; the full
DEPLOY.1 trend scrubber UI stays deferred.

## 1. Objective

Each full-integration checkpoint appends one compact aggregate record of the
compliance roll-up to a RuntimeRoot ledger. Every render then exposes
`compliance_overview.history[]` + `compliance_overview.trend`, and the Overview
compliance card + the Compliance KPI band show a sparkline and a
"+5.0 pts since 2026-06-30" delta chip.

No server, no collector, no device command. Honest semantics: *"this is what we
reported at run T"* — **no backfill**, the trend fills over subsequent runs.

## 2. Decisions (approved 2026-08-30, recommendations A–E)

- **A — append-only ledger**, not historical reconstruction from stored config
  blobs. Reconstruction is a possible later TRACE-plane build.
- **B — aggregates only.** Fleet + per-framework counts; **no per-subject rows**
  (positional `cp-001` ids churn between runs), **no device identity**.
- **C — write on a full checkpoint only.** `--render-only` / `--only x` /
  partial renders never write (already "NOT A CHECKPOINT").
- **D — minimal Overview sparkline + delta chip now.** No new tab, no scrubber.
- **E — `COMPLIANCE_SCHEMA_VERSION`: NOT bumped.** Overridden from the original
  recommendation to match the established repo convention — 0.7.0–0.7.4 each
  added payload fields additively without a bump. `history` / `trend` key
  presence is the version signal; consumers already treat the payload as
  additive.

## 3. Changes

### `utils/compliance_history.py` (new)
- `HISTORY_SCHEMA_VERSION = "0.7.5"`, `LEDGER_RELATIVE_PATH =
  "state/compliance_history.json"` (under `data_root`), `MAX_RECORDS = 200`,
  `PAYLOAD_RECORD_LIMIT = 30`.
- `load_history(data_root, *, limit=None) -> list[dict]` — oldest first.
  **Fail-safe**: missing / unreadable / malformed ledger -> `[]`, never raises
  (a trend line must not break a render — the opposite of the fail-closed check
  pack).
- `summarise_overview(overview, *, run_id, collected_at=None, schema_version=None)
  -> dict` — one ledger record: `run_id`, `collected_at` (ISO-8601 UTC),
  `compliance_schema_version`, `catalog_version`, `framework_catalog_version`,
  `cells{aligned,finding,unknown,planned,waived}`, `aligned_percent`,
  `risk_weighted_alignment_percent`, `monitored_controls`, `total_controls`,
  `subjects`, `by_framework{name:{aligned,finding,coverage}}`.
- `append_run(data_root, record) -> None` — load, append, cap to `MAX_RECORDS`
  (newest kept), atomic temp-file replace. Best-effort: an `OSError` on write is
  swallowed (the render already succeeded).
- `history_view(history, *, current_aligned=None, current_risk_weighted=None,
  limit=PAYLOAD_RECORD_LIMIT) -> {"records": [...], "trend": {...}|None}` —
  projects records for the payload (`date`, `at`, percents, `cells`, counts,
  versions) and computes `trend` vs the newest prior record when a current value
  is given.

### `utils/compliance_posture.py`
- `build_compliance_posture(..., *, history: list | None = None)` — threaded to
  both overview builders.
- `_compliance_overview(subjects, extra_meta, history=None)` and
  `_empty_overview(history=None)` gain, additively:
  - `history: [{date, at, aligned_percent, risk_weighted_alignment_percent,
    cells, monitored_controls, total_controls, catalog_version,
    framework_catalog_version}]` — oldest -> newest, most-recent `PAYLOAD_RECORD_LIMIT`.
  - `trend: {previous_date, previous_at, delta_aligned_percent,
    delta_risk_weighted_percent, direction} | null` — vs the newest prior
    record; `null` with < 1 prior record or on the not-available path.
- `COMPLIANCE_SCHEMA_VERSION` unchanged (Decision E).

### `utils/html_export.py`
- Read: `history = load_history(compliance_data_root)`, threaded into
  `build_compliance_posture` on every render (`--render-only` included).
- Write: `run_html_export(..., record_checkpoint=False, run_id=None)`. When
  `record_checkpoint and compliance_ui.get("available")`, `append_run(
  compliance_data_root, summarise_overview(...))` after the HTML is written.

### `main.py`
- The one full-checkpoint render (`args.only in ("html","all")` with `run_ctx`)
  passes `record_checkpoint=True, run_id=run_ctx.run_id`. No other call site
  changes — cp-config, render-only, partial and diagnostic renders keep the
  `False` default.

### `static/app.js` + `static/style.css`
- `complianceSparkline(records)` — inline SVG polyline of `aligned_percent`,
  renders `""` for < 2 points. `complianceTrendChip(trend)` — `▲ / ▼ / ·` +
  signed pts + "since <date>", renders `""` when `trend` is null.
- Injected into `#overviewComplianceSummary` and the Compliance module
  `.compliance-kpi-grid`. Empty ledger -> neither element renders; every
  existing empty / first-run state is untouched.

## 4. Privacy

Ledger records and the payload carry only integer counts, rounded percents,
ISO-8601 timestamps and framework names (`CIS` / `PCI-DSS` / `BDDK`). No device
name, IP, serial or positional subject id. `data/` is gitignored; the ledger is
caught by the same `RUNTIME_DIRECTORY_PRESENT` gate as `compliance_checks.json`.

## 5. Definition of Done

- `build_compliance_posture` with `history` omitted -> payload identical but for
  `history: []` / `trend: null`; every existing compliance test passes with at
  most that additive diff.
- Two `record_checkpoint=True` renders -> the second payload has a 1-record
  `history` and a real `trend`.
- `--render-only` never writes the ledger; a corrupt ledger file -> render still
  succeeds, `history: []`, `trend: null`.
- `json.dumps(payload)` and the ledger file carry no identity — privacy gate
  PASS / 0 on a clean tree.
- `scripts/render_sample.py` exit 0; full suite green (534 -> +N).
- `main.py` / UI: Overview compliance card + Compliance KPI band show a sparkline
  and a "since <date>" delta once >= 2 checkpoints exist; nothing changes with an
  empty ledger.

## 10. Implementation record — AUTOMATED_VALIDATED (2026-08-30)

Shipped exactly as §3.

- **`utils/compliance_history.py`** (new, 168 lines) — `HISTORY_SCHEMA_VERSION`
  `"0.7.5"`, `LEDGER_RELATIVE_PATH` `state/compliance_history.json`,
  `MAX_RECORDS` 200, `PAYLOAD_RECORD_LIMIT` 30. `load_history` (fail-safe,
  oldest-first, `limit`), `summarise_overview`, `append_run` (cap newest, atomic
  `.json.tmp` replace, `OSError` swallowed), `history_view` (`{records, trend}`;
  `direction` `up`/`down`/`flat`).
- **`utils/compliance_posture.py`** — `history_view` import;
  `build_compliance_posture(..., history=None)`; `_compliance_overview(subjects,
  extra_meta, history=None)` and `_empty_overview(history=None)` each append
  `history` + `trend`. `COMPLIANCE_SCHEMA_VERSION` **unchanged** (Decision E).
- **`utils/html_export.py`** — reads `load_history(compliance_data_root)` into
  `build_compliance_posture` on every render; `run_html_export(...,
  record_checkpoint=False, run_id=None)` appends one record via
  `summarise_overview` after the HTML is written, only when
  `record_checkpoint and compliance_ui["available"]`.
- **`main.py`** — the single full-checkpoint render (`args.only in ("html",
  "all")` with `run_ctx`) passes `record_checkpoint=True,
  run_id=run_ctx.run_id`. No other call site touched.
- **`static/app.js`** — `complianceSparkline(records)` (inline SVG polyline,
  `""` below 2 points) + `complianceTrendChip(trend)` (`▲/▼/·` + signed pts +
  "since <date>", `""` when null), injected into `#overviewComplianceSummary`
  and the Compliance `.compliance-kpi-grid`. **`static/style.css`** —
  `.compliance-trend-row`, `.compliance-sparkline`, `.compliance-trend-chip`
  (+ `.up` / `.down` tones).
- **`tests/test_phase0_7_5_compliance_trend.py`** (13) — ledger round-trip +
  order, `MAX_RECORDS` cap keeps newest, `load_history` fail-safe (missing /
  corrupt / wrong shape), `append_run` best-effort on an unwritable root,
  `history_view` trend + direction + projection/limit, `build_compliance_posture`
  additive-only when `history` omitted (available and not-available paths),
  trend from a prior ledger record, `summarise_overview` carries no identity,
  plain render writes no ledger, corrupt ledger does not break a render.

**Evidence (2026-08-30):**

```
py -m pytest -q -n auto --dist worksteal : 547 passed, 3 skipped, 0 failed
                                           (534 -> +13)
repository privacy gate                   : PASS / 0 on a clean tree
scripts/render_sample.py                  : exit 0; complianceUiData carries
                                           history: [] / trend: null; all six
                                           payload literals valid JSON
```

Behaviour: no visible change until a second full `py .\main.py` checkpoint
exists. `--render-only` never writes the ledger. A corrupt ledger degrades to
"no trend", never an error.
