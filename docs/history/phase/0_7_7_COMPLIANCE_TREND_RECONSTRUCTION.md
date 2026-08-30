# 0.7.7 — Compliance trend retro-fill (PAN baseline reconstruction)

**Status:** PLANNED -> (impl record in §7) · **Track:** 0.7.x VERIFY (TRACE-adjacent) ·
**Movement:** ARCHITECTURE -> IMPLEMENTATION · **Model:** Sonnet 5, extended thinking (design) / Sonnet 5, normal (implementation)

Follow-up to `0.7.5 — Compliance trend layer`, which deliberately shipped
append-only with **no backfill** (decision A: "Reconstruction is a possible
later TRACE-plane build"). This build is that follow-up.

## 1. Objective

`compliance_overview.history[]` currently only gains depth after the user runs
two real full checkpoints. This build adds an offline, opt-in batch job that
mines the existing content-addressed config store (CAS) for past PAN
effective-running snapshots and appends best-effort historical points to the
same ledger `utils/compliance_history.py` already writes — so a fleet that has
been running PAN config collection for weeks gets a trend line with depth
immediately, without waiting for two more live checkpoints.

## 2. Feasibility finding (why this is narrower than a full re-run)

`build_compliance_posture` computes today's roll-up from several inputs that
are **not stored per historical snapshot**:

| Input | Historically reconstructable? |
| --- | --- |
| PAN `current_configuration` (structured sections) | **Yes** — CAS stores the raw effective-running XML blob per snapshot; `configuration/current_config_projection.py` (`_scalar_rows`) and `utils/config_history.py` (`_project_rows`) already parse it without needing alignment context. |
| CP `current_configuration` | **No** — CP stores redacted Gaia *text*, no structured projection exists. `config_history.py` already marks CP diff `INSUFFICIENT_EVIDENCE` for the same reason. |
| Alignment (`EFFECTIVE_DRIFT`, `PANORAMA_OUT_OF_SYNC`, ...) | No — computed against live device/Panorama state at collection time, not stored as a replayable artifact. |
| `unified.interfaces` / `unified.routes` join (CE.1) | No — `unified.json` isn't versioned per historical config snapshot. |
| Control assignment policy + waivers | No — `control_assignment.py` reads only today's policy file; there is no historical version of "what was assigned on date X." |
| CE.1 user-authored check packs | No — pack content isn't versioned per historical snapshot either. |
| Crypto facts (0.7.0) | Same caveat as row 1 (PAN-only, derivable) but out of scope for this MVP (see §3). |

**Decision (put to the product owner 2026-08-30, approved): narrow, clearly
labeled reconstruction.** Scope is exactly the ten deterministic
`DEFAULT_RULE_PACK` baseline controls (`utils/compliance_rulepack.py`),
**PAN devices only**, evaluated with **today's** rule pack applied
retroactively to historical structured config. No alignment, no CP, no
assignment/waiver replay, no CE.1 checks, no `by_framework` breakdown. Every
reconstructed record is stamped `reconstructed: true` and
`reconstruction_scope: "pan_baseline_rule_pack_only"` so nothing downstream
can mistake it for a live checkpoint's full-catalog roll-up.

Rejected alternatives: (a) drop the build entirely — CAS has real, unused
signal worth surfacing; (b) attempt a broader reconstruction that
approximates alignment/assignment/CP with heuristics and blends it
indistinguishably into the live trend — rejected as the option most likely to
mislead a report viewer into reading an approximation as a factual "what we
reported on date X."

## 3. Design

### Bucketing (no run correlation exists in CAS)

CAS metadata has no `run_id` — every collection call writes an independent
snapshot with its own `collected_at`, even when the fleet is collected in one
`main.py` invocation. Reconstruction buckets snapshots into synthetic
"checkpoints" by **single-linkage time clustering**: sort every PAN effective
snapshot (across every entity) by `collected_at`; start a new bucket whenever
the gap to the previous snapshot exceeds `RECONSTRUCTION_GAP_MINUTES` (default
15 — a full-fleet `main.py` PAN-config collection stage finishes in well under
that; two genuinely separate operator runs are realistically hours/days
apart). Within a bucket, each entity contributes its own snapshot (not a
cross-entity "as of" lookup — a bucket already only contains snapshots that
landed close together in time).

### Per-bucket evaluation

For each PAN entity's snapshot in a bucket: resolve the blob via the same
`object_path` resolution `config_history.py` already uses, parse with
`_safe_xml`, project with `_project_rows` (`_scalar_rows(root,
alignment_index={})` — no alignment context, matching how `config_history.py`
already computes its no-alignment diff view). Wrap as a synthetic device dict
(`{"vendor_key": "palo_alto", "current_configuration": {"status":
"available", "sections": [...]}}`) and run it through the existing
`compliance_posture._evaluate_vendor_neutral_control` dispatch for each of the
ten baseline `control_id`s — the exact same evaluator functions a live
checkpoint uses, so a reconstructed point and a live point agree whenever
their input sections agree. Aggregate cells (`aligned`/`finding`/`unknown`)
and `risk_weighted_alignment_percent` (severity-weighted, same formula as
`_compliance_overview`) across all entities in the bucket. `monitored_controls`
= baseline controls that produced hard evidence (PASS/FINDING) on at least one
entity in the bucket (no assignment-policy replay — see §2).

### Ledger integration

`utils/compliance_history.py` (`HISTORY_SCHEMA_VERSION` -> `"0.7.7"`,
additive):
- `append_reconstructed(data_root, records)` — same atomic-replace write path
  as `append_run`, batch-appends multiple records, deduplicates against
  existing `run_id`s already in the ledger (`run_id` for a reconstructed
  record is the synthetic `"reconstructed:<bucket_start_iso>"`, making re-runs
  of the batch job idempotent).
- `history_view(...)`'s `trend` computation changes to compare the current run
  against the **newest live (non-reconstructed) record**, never a
  reconstructed one — a reconstructed record's narrower methodology (no
  assignment/alignment/CP) makes a delta against it misleading. `_project_record`
  carries the new `reconstructed: bool` field through (defaults `False` for
  every pre-existing live record — untyped/missing key, fully backward
  compatible).

### New module: `utils/compliance_trend_reconstruction.py`

`reconstruct_pan_baseline_records(config_root=None, artifact_root=None, *,
gap_minutes=RECONSTRUCTION_GAP_MINUTES) -> list[dict]` — pure read of CAS,
returns ledger-record-shaped dicts (same shape `summarise_overview` produces,
plus the two reconstruction-marker fields). No network, no credentials, no
device identity in the output (aggregates only, same privacy contract as
0.7.5).

### CLI

`main.py --compliance-trend-reconstruct` — a maintenance mode alongside
`--repository-privacy-check` / `--storage-analyze` (lazy imports, returns
before touching vendor/network code, no credentials required). Runs the
reconstruction, appends new (non-duplicate) records, prints a one-line
summary (`N buckets found, M new records appended, K already present`).
Never runs automatically from a full checkpoint or `--render-only` — retro-fill
is an explicit, opt-in, one-time (or re-run-safe) operator action.

### UI (`static/app.js` + `static/style.css`)

`complianceSparkline` gains a `reconstructed` boolean per point (from
`ov.history[].reconstructed`) and renders reconstructed runs of the polyline
with `stroke-dasharray` + reduced opacity and hollow point markers, versus a
solid stroke + filled markers for live points — a live/reconstructed run never
shares one continuous solid segment. No change to `complianceTrendChip` (it
already only ever compares to a live record, per the ledger-side fix above).

## 4. Privacy

Reconstructed records carry the same fields as a live ledger record (integer
counts, rounded percents, ISO-8601 timestamps) plus two marker fields
(`reconstructed`, `reconstruction_scope`) — no device name, IP, serial, or
positional subject id. The reconstruction job never touches raw Gaia text (CP
is out of scope) and only reads PAN structured projections that
`config_history.py` already exposes safely.

## 5. Definition of Done

- `reconstruct_pan_baseline_records` on an empty/missing CAS -> `[]`, no error.
- Two PAN entities collected 2 minutes apart -> one bucket; the same two
  collected 3 hours apart in two separate operator runs -> two buckets.
- A reconstructed record's per-control PASS/FINDING agrees with what
  `_evaluate_vendor_neutral_control` would return live given the same
  `current_configuration` sections (characterization test: build a device dict
  by hand, compare live vs. reconstructed evaluation path directly).
- Re-running `--compliance-trend-reconstruct` twice appends zero duplicate
  records the second time.
- `history_view` trend never uses a reconstructed record as `prev`, even when
  it is the newest record in the ledger.
- No credential, device name, IP, serial, or raw configuration in the CLI
  output or the ledger file; privacy gate PASS / 0 on a clean tree.
- Full suite green; two pre-existing unrelated failures
  (`test_run_html_export_embeds_discovery_payload_without_leftover_placeholder`,
  `test_checkpoint_render_appends_one_record`) stay the same, no new failures.

## 6. Explicit non-goals (carried forward, not silently dropped)

- CP reconstruction — blocked on a structured CP config projection existing at
  all (separate, larger build).
- Alignment-derived reconstruction, assignment/waiver replay, CE.1 check
  replay, crypto-posture reconstruction — all blocked on data that CAS does
  not version historically; each would need its own historical-versioning
  design before this technique could extend to it.

## 7. Implementation record — AUTOMATED_VALIDATED (2026-08-30)

Shipped exactly as §3.

- **`utils/compliance_trend_reconstruction.py`** (new) — `reconstruct_pan_baseline_records(config_root=None, artifact_root=None, *, gap_minutes=15)`. Walks `CONFIG_ROOT`, filters snapshots to `PAN_EFFECTIVE_ARTIFACT_TYPES`, single-linkage time-clusters them into buckets, resolves each snapshot's blob via the same `_blob_path_for_metadata` resolution `config_history.py` already uses, projects sections with `_scalar_rows(root, alignment_index={})` (no alignment context — same no-alignment path `config_history.py`'s own diff view already uses), evaluates the ten `DEFAULT_RULE_PACK` baseline controls per entity via the live `compliance_posture._evaluate_vendor_neutral_control` dispatch (byte-for-byte the same evaluator a live checkpoint uses), aggregates cells + severity-weighted percent. Every record carries `reconstructed: True`, `reconstruction_scope: "pan_baseline_rule_pack_only"`, `total_controls: 10`.
- **`utils/compliance_history.py`** — `HISTORY_SCHEMA_VERSION` → `"0.7.7"`. New `append_reconstructed(data_root, records)` (idempotent on `run_id`, same atomic-replace write, `MAX_RECORDS` cap, best-effort `OSError` swallow). `_project_record` gains `reconstructed` / `reconstruction_scope` (defaults `False`/`None` for every pre-0.7.7 record — additive, backward compatible). `history_view`'s trend now always compares against the newest **live** (non-reconstructed) record, never a reconstructed one, even when a reconstructed record is chronologically newest in the ledger.
- **`main.py`** — new maintenance mode `--compliance-trend-reconstruct` (mutually exclusive with the other repository/storage maintenance modes and with collection/render modes), placed after `resolve_runtime_paths` (needs `runtime_paths.data_root` for the ledger write) but before any collection-service wiring. No network, no credentials, no `RunContext`. Prints buckets found / new records appended / already-present count.
- **`static/app.js`** — `complianceSparkline` now splits the polyline into contiguous live/reconstructed runs: a reconstructed run renders dashed (`stroke-dasharray`) at reduced opacity with hollow point markers; a live run is unchanged (solid, filled markers). A live and a reconstructed point never share one continuous solid segment. No `complianceTrendChip` change needed — it already only ever reflects the ledger-side trend fix above.
- **`tests/test_phase0_7_7_compliance_trend_reconstruction.py`** (10) — empty CAS → `[]`; single/multi-entity bucketing; time-gap bucket separation; a hand-built characterization test asserting the reconstruction path's per-control status is identical to calling the live evaluator dispatch directly; `append_reconstructed` idempotency; trend-never-uses-reconstructed (even when newest); trend absent with reconstructed-only history; pre-0.7.7 records default `reconstructed: False`; no device identity/IP/hostname in the reconstructed JSON.
- **`tests/test_phase0_7_5_compliance_trend.py`** — one pre-existing exact-key-set assertion updated to include the two new additive `_project_record` keys.

**Evidence (2026-08-30):**

```
py -m pytest -q            : 645 passed, 2 skipped, 2 failed (635 -> +10;
                              same two pre-existing, unrelated failures
                              documented in every prior 0.7.x closure this
                              session — test_run_html_export_embeds_discovery_
                              payload_without_leftover_placeholder and
                              test_checkpoint_render_appends_one_record)
render harness (Playwright) : PASS — full uitest bundle, page loads, executes
                              clean, every nav module + inner tab switches,
                              zero console errors
repository privacy gate      : run against a tree still holding this session's
                              gitignored data/ + logs/ test-run byproducts
                              (documented pre-existing friction, DEV.0.3C
                              deferred); no new finding class introduced by
                              this build's own files
```

Manual sanity check (not a fixture, throwaway synthetic XML): one PAN
effective-running snapshot with hostname/DNS present and only a primary NTP
server produced `cells: {aligned: 2, finding: 1, unknown: 7}` — hostname and
DNS PASS, NTP FINDING (secondary missing), the other seven baseline controls
UNKNOWN (no matching evidence in that XML), exactly matching what the live
evaluators would return for the same sections.

Behaviour: `main.py` gains one new opt-in offline CLI mode; nothing else
changes for an operator who never runs it. `--render-only` / a full
checkpoint / `--only x` are all unaffected. `compliance_overview.trend`
continues to reflect only live checkpoints, per the 0.7.5 contract, even
once retro-fill has populated `history[]` with reconstructed points.

**Not yet done — real-environment validation:** this build has no live-device
dependency (it reads only already-stored CAS blobs), so the standard
`on_hardware_real_env_validation` gate does not apply the way it does to a
collector change. What is still owed is validating `--compliance-trend-
reconstruct` against a real fleet's accumulated CAS history once a server
exists (deferred to `DEPLOY.1`, same class as every other real-device gap).
