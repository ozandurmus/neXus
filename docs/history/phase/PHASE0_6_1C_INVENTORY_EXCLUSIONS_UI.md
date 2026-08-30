# 0.6.1C / Inventory UX — Inventory Exclusions View (Read-Only Phase)

## Status

**PLANNED — architecture contract frozen 2026-08-30**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`. Backlog id:
`inventory_exclusions_ui` (P1, `planned`).

## Objective

`utils/inventory_exclusions.py` is a mature, real-environment-validated,
read-only local policy store (`InventoryExclusionPolicy`,
`InventoryExclusion`, `load_inventory_exclusions()`,
`identities_for(vendor)`/`count_for(vendor)`) that already gates pre-poll
collection (`checkpoint/cp_runner.py:296,802,835`). Today its effect is only
visible as an aggregate count buried in collection telemetry — there is no
vendor-neutral Exclusions view a user can actually inspect. This backlog item
is explicitly two-phased in its own note: **(1) expose exclusions in a
read-only view now; (2) add controlled add/restore/reason/audit workflows
later.** This contract covers **phase 1 only**.

## Scope

### In scope

- A new pure-projection payload builder (e.g. `utils/inventory_exclusions_ui.py`),
  modeled directly on `utils/discovery_capability_ui.py`'s shape
  (`build_discovery_capability_payload()`,
  `discovery_capability_ui.py:135-188`): no I/O beyond reading the already-
  loaded `InventoryExclusionPolicy`, returns `schema_version` +
  `fleet_summary` (per-vendor counts, reusing `count_for(vendor)`) +
  `entities` (vendor + identity + reason, if a reason is recorded) — same
  additive, privacy-clean projection contract as the Discovery payload.
- Wire it through `utils/html_export.py` exactly like `discovery_ui`: a new
  `__EXCLUSIONS_JSON_PLACEHOLDER__` sentinel, a new keyword arg threaded from
  `main.py` the same way `lifecycle_store`/`capability_store` are today.
- Add a new nav module following the exact existing pattern:
  `templates/index.html` nav button (`data-module="exclusions"`) + panel
  section (`data-module-panel="exclusions"`), a `renderExclusionsModule()` in
  `static/app.js` modeled on `renderDiscoveryModule()`
  (`app.js:4474-4578`), and adding the new module name to the hardcoded list
  in `switchModule()` (`app.js:2559`).
- Render per-vendor exclusion counts + the exclusion list (identity + reason
  where present) read-only.

### Explicitly out of scope (deferred to phase 2, per the backlog note)

- Any add/restore/reason/audit write workflow, or any write path into
  `state/inventory_exclusions.json` at all — phase 1 is strictly read-only,
  same posture the existing policy store already has.
- Any change to `InventoryExclusionPolicy`'s validation rules
  (`_validate_identity()`, `inventory_exclusions.py:48-54` — no control
  chars, 255-char cap) — those already exist and only become load-bearing
  once a write path is added.
- Any change to how exclusions are applied pre-poll (`cp_runner.py`) — this
  build only makes existing behavior visible, it does not change it.
- OIDC/RBAC — the future edit path is `DEPLOY.1A`-gated by design (same class
  as `compliance_assignment_ui_and_registry`); this phase-1 read view has no
  such gate since it adds no write capability.

## Privacy and safety invariants

1. No credential, raw configuration, or management IP enters the payload —
   only vendor + excluded identity string + optional reason, matching what
   `InventoryExclusion` already stores locally.
2. Vendor-neutral presentation — no CP/PAN-specific assumption baked into the
   UI beyond what `identities_for(vendor)` already provides generically.
3. Missing/absent exclusion policy file must render an explicit empty state
   (zero exclusions), never an error — matching the existing fail-open-to-
   empty behavior of `load_inventory_exclusions()`.

## Implementation plan

1. Write `utils/inventory_exclusions_ui.py`'s pure payload builder + its
   schema version constant.
2. Wire the sentinel through `html_export.py` and `main.py`'s call site.
3. Add the nav/module/panel/render wiring in `templates/index.html` +
   `static/app.js`, copying the Discovery module's structure.
4. Add `tests/fixtures/uitest/` coverage per its growth rule (a nonzero
   exclusion set for at least one vendor) so the render harness exercises the
   new module — mandatory per `AGENTS.md`'s "HTML render harness" section for
   any `templates/`/`app.js`/`style.css`/payload-builder change.
5. Targeted tests for the new builder; full regression; render harness
   (`check-render.mjs` must click the new nav item with zero console errors);
   privacy gate.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | A read-only Exclusions module exists, reachable from the module nav, showing per-vendor counts + the identity/reason list. |
| AC-2 | No write path exists yet — this build cannot modify `state/inventory_exclusions.json`. |
| AC-3 | An absent/empty exclusion policy renders an explicit empty state, not an error. |
| AC-4 | `tests/fixtures/uitest/` extended per its growth rule; render harness passes with the new module clicked, zero console errors. |
| AC-5 | Full regression + privacy gate pass; no credential/raw-config/IP in the new payload. |

## Validation and merge gate

Dev/UI-only change; no real-device run required (same posture as 0.7.6).
Merge requires AC-1 through AC-5, the render harness, and a clean privacy
gate.

## Definition of done

`DONE` when the read-only Exclusions view ships and is exercised by the
render harness. `inventory_exclusions_ui` moves from `planned` to
`automated_validated`; the deferred add/restore/reason/audit phase becomes a
new, separately-contracted backlog item (its own `DEPLOY.1A`-adjacent write
boundary needs a dedicated design pass, not bundled here).
