# 0.6.1C / Inventory UX — Overview Device Lifecycle Enrichment

## Status

**PLANNED — architecture contract frozen 2026-08-30**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`. Backlog id:
`overview_device_lifecycle_enrichment` (P1, `planned`).

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
