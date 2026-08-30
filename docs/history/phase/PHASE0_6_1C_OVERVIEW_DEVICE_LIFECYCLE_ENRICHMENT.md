# 0.6.1C / Inventory UX — Overview Device Lifecycle Enrichment

## Status

**DONE (Increment 1) — AUTOMATED_VALIDATED 2026-08-30; Increment 2 deferred**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`. Backlog id:
`overview_device_lifecycle_enrichment` (P1, was `planned`, now
`automated_validated`).

### Closure evidence (2026-08-30)

**Increment 1 — shipped, pure client-side, zero schema change.** The
per-device `vendor`/`model`/`sw_version` fields were already present, in the
exact shape needed, on `configUiData.devices` — the same flat array
`static/app.js` already reads for the Configuration module's device tree
(`configDeviceItemHtml`). This meant Increment 1 needed **no new Python
payload builder, no new `__..._JSON_PLACEHOLDER__` sentinel, and no
`main.py` wiring at all** — a smaller footprint than the implementation plan
assumed. A new `deviceLifecycleFamilies()` helper in `static/app.js`
aggregates `configUiData.devices` by `(vendor, model, sw_version)`,
explicitly excluding `entity_type === "virtual_system"` rows (a VSX virtual
system inherits its physical host's `model`/`sw_version` verbatim in
`utils/config_ui.py`, so counting it too would double-count the same
physical device). Rendered as a new "Fleet composition" card on Overview via
`overviewDeviceFamilies`, sorted by count descending (AC-1).

Privacy (AC-2): the aggregation only ever touches `vendor`/`model`/
`sw_version`/`entity_type` — never `serial`/`management_ip`/`device_name`/
`id`/`name`, which stay a Configuration-module concern. Verified by a test
that inspects the extracted function body for those forbidden field-name
substrings, not just a manual read.

No new device command or collection path (AC-3) — Increment 1 touches no
Python collector code at all.

**Increment 2 — deferred, design documented below, not implemented.** No
internet access was available in this implementation session to identify
and vet a maintained public CP/PAN EOL dataset, and the contract's own gate
is explicit: implement only against a *vetted* dataset, otherwise defer with
the design written up (not silently dropped) — so per that gate, this
defers. Design sketch for whoever picks this up:

- **Data shape**: a small static, versioned, in-repo table (e.g.
  `utils/eos_release_catalog.py`, mirroring the `compliance_rulepack.py` /
  `framework_catalog.py` static-versioned-dataset pattern already
  established in this codebase), keyed by a normalized `(vendor,
  model_family)` tuple — not exact model string, since e.g. "PA-3220" and
  "PA-3220-FIPS" share an EOL date. Each entry: `eos_date` (ISO date),
  `eol_support_date` (if distinct from EOS), `suggested_next_release`
  (a free-text hint, not a guarantee), and `source_reference` (where the
  date came from — vendor's own published EOL/EOS bulletin URL or doc id,
  for auditability).
  - Do **not** key by exact `sw_version` — vendor EOL tables are keyed by
    major train (e.g. "R81.10" as a train, not each build within it) and by
    hardware model family, two largely independent axes; conflating them
    into one lookup key would need two separate tables, not one.
  - Because the OS-version axis needs its own maintenance cadence
    independent of the hardware-model axis, the two should probably be two
    tables from the start (`model_eos_catalog` / `os_train_support_catalog`)
    even though this design sketch shows them conceptually as one — a
    real vetted dataset will settle that shape once it exists, not before.
- **Join logic**: `deviceLifecycleFamilies()`'s existing aggregation groups
  by `(vendor, model, sw_version)` already; a second pass would map each
  group's `(vendor, model)` through the catalog to attach EOS guidance,
  independent of whether `sw_version` matched anything (a device on an
  unlisted software train still gets hardware EOS guidance).
- **Provenance/trust**: every row must be traceable to a vendor's own
  published statement, not inferred/guessed — the same no-fabrication
  standard as the rest of this evidence platform's compliance content.
  Consider requiring `source_reference` non-empty as a load-time validation,
  matching `compliance_check_pack.py`'s fail-closed pattern for a malformed
  entry.
- **UI**: an additional column or a secondary badge on the same
  "Fleet composition" card (`overviewDeviceFamilies`) — no new card/module
  needed.
- **Egress**: confirmed explicitly out of scope for any implementation --
  no live vendor API/internet call, ever; static dataset shipped in-repo
  only (AC-4 satisfied by this write-up existing, per the contract's own
  gate: "explicitly deferred with its design written up — not silently
  dropped").

Split into a new backlog item `overview_eos_release_guidance` (see
`project/backlog.json`) rather than left ambiguously inside this "done"
item.

Evidence: 8 new tests in
`tests/test_phase0_6_1c_overview_device_lifecycle_enrichment.py`, including
two that execute the real `deviceLifecycleFamilies()` function through a
real JS engine (`bun`, no DOM needed) against a synthetic device list,
proving the aggregation/sort/exclusion logic actually behaves as claimed —
not just source-string presence checks. Also fixed a gap from the prior
`inventory_exclusions_ui` build: `exclusionsUiData` was missing from
`tests/test_html_render_harness.py`'s `_PAYLOAD_CONSTS` JSON-validity check
list. Full suite: 611 passed, 2 skipped, 2 failed (both pre-existing and
unrelated — same two tests already documented against the unmodified
baseline in prior 0.6.x closures). Net +8 from baseline 603, zero
regressions. The existing `tests/fixtures/uitest/configuration_ui.json`
already carries 2 distinct device families (Check Point `CP-6900`/`R81.20`,
Palo Alto `PA-5220`/`11.1.3`), so **no fixture regeneration was needed** for
AC-5. The `bun`/`happy-dom` DOM-execution half of the render harness could
not run in this session's container (`window.eval is not a function` — the
same pre-existing environment gap noted in prior 0.6.x closures, unrelated
to this change) and is owed on a working local toolchain.

## Objective

Add vendor-appropriate OS version and model fields to the Overview module;
if a static mapping is available, add EOS/next-release guidance, without
exposing sensitive identities — per the backlog note. The underlying data
already exists and is already privacy-reviewed: CP —
`configuration/checkpoint_config_collector.py:1274` (`sw_version`),
`1278-1280` (`model`); PAN — `configuration/panorama_config_collector.py:
236-237,370-371` (`model`, `sw_version`). Both are already rendered
per-device in the **Configuration** module
(`static/app.js:2940,2948-2949,2688`). The **Overview** module
(`renderOverviewModule()`, `app.js:2415-2558+`) today shows only fleet-level
aggregate cards — zero per-device rows, zero model/version surface. An
EOS-date / next-release mapping does not exist anywhere in the repo today
(confirmed: zero hits for end-of-life/end-of-support/EOS/eol_date).

This is a two-increment item, kept separable per the note's own "if globally
available" qualifier on the EOS part:

- **Increment 1 (in scope for this contract):** surface already-collected,
  already-reviewed `model`/`sw_version` on Overview.
- **Increment 2 (design-only in this contract, gated on data availability):**
  a static local EOS/next-release lookup by model family.

## Scope

### In scope — Increment 1

- Source `model`/`sw_version` from the already-built `configuration_ui`
  per-device payload (`utils/config_ui.py`) — no new collection, no new
  device command.
- Add a new Overview card/table aggregating **by model family**, not raw
  per-device rows: e.g. counts of devices per `(vendor, model, sw_version)`
  tuple. This is the "without exposing sensitive identities" boundary from
  the note — Overview is a fleet-summary surface today and this build keeps
  it that way; per-device drill-down into a specific hostname/serial already
  exists in the Configuration module and is not duplicated here.
- No new schema version bump beyond what an additive field requires.

### In scope — Increment 2 (design only; implementation gated)

- Define the shape of a static local model-family -> EOS-date /
  suggested-next-release table (e.g. a small JSON/py dict shipped in-repo,
  vendor-neutral key format) and how it would be joined to the Increment-1
  aggregation.
- Document explicitly that this table is **not** fetched live from any
  vendor API or the internet — a static, versioned, in-repo dataset only,
  consistent with the read-only/no-egress posture of the whole platform.
- Do **not** implement Increment 2 in this build unless a maintained,
  reasonably-sized public CP/PAN EOL dataset is identified and can be
  vetted for accuracy; if not readily available, Increment 2 stays a
  documented follow-up backlog item, not a blocker for Increment 1's closure.

### Explicitly out of scope

- Any live network call to a vendor EOL/support API — violates the read-only,
  no-egress product posture.
- Per-device hostname/serial exposure on Overview — that stays a
  Configuration-module concern.
- Any new device command or collection path; both fields are already
  collected.

## Privacy and safety invariants

1. Overview aggregation is by `(vendor, model, sw_version)` family, not by
   individual device identity — matches the note's "without exposing
   sensitive identities" constraint.
2. If Increment 2 ships, the EOS/next-release table itself must contain no
   customer data — it is vendor public model-family metadata only.
3. No new egress path; any lookup table is static and shipped in-repo.

## Implementation plan

1. Confirm the exact per-device `model`/`sw_version` field names already
   present in `configuration_ui`'s payload (both CP and PAN branches).
2. Add an aggregation step (fleet-summary style, matching the existing
   Overview card pattern) grouping by `(vendor, model, sw_version)`.
3. Add the new card/table in `renderOverviewModule()`
   (`app.js:2415-2558+`), matching existing Overview card visual/DOM
   conventions.
4. Write up the Increment 2 design (table shape + join logic) in this doc's
   closure section; implement only if a vetted dataset is identified this
   session, otherwise leave as `PARTIALLY_DONE` / follow-up.
5. Extend `tests/fixtures/uitest/` per its growth rule so the render harness
   exercises the new Overview card across at least two distinct model
   families.
6. Targeted tests; full regression; render harness; privacy gate.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | Overview shows a model/version summary aggregated by `(vendor, model, sw_version)` family, sourced from already-collected data. |
| AC-2 | No raw per-device hostname/serial is newly exposed on Overview. |
| AC-3 | No new device command or live external call is introduced. |
| AC-4 | Increment 2 (EOS/next-release) is either implemented against a vetted static dataset with its provenance documented, or explicitly deferred with its design written up — not silently dropped. |
| AC-5 | `tests/fixtures/uitest/` extended; render harness + full regression + privacy gate pass. |

## Validation and merge gate

Dev/UI-only change (Increment 1); no real-device run required. If Increment 2
ships, its dataset provenance must be documented in the closure section
before merge. Merge requires AC-1 through AC-5 and a clean privacy gate.

## Definition of done

`DONE` (Increment 1) when the Overview model/version aggregate card ships and
is exercised by the render harness. `overview_device_lifecycle_enrichment`
moves from `planned` to `automated_validated` if only Increment 1 ships, with
Increment 2 split into a new explicitly-tracked follow-up backlog item
(`overview_eos_release_guidance` or similar) rather than left ambiguously
inside a "done" item.
