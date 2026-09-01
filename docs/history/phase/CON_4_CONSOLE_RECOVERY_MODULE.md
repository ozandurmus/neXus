# `CON.4` — Recovery module (`RB.5` surface) in both delivery modes

## Status

**CONTRACT FROZEN 2026-08-31**, alongside `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`
(`CON.0`). Implements the UI specified in
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §11 — that section is the
functional spec and is not restated here; this contract adds the delivery-mode,
payload and safety rules around it.

`project/backlog.json` `operator_console` (P1) and `restore_readiness`;
roadmap `RB.5` is completed by this phase. Track `CON.x`.

**Preconditions:** `CON.2` AUTOMATED_VALIDATED; `RB.4` (validation battery)
AUTOMATED_VALIDATED — both already met at freeze time. Independent of `CON.3`:
this phase may ship before any `operational-write` action exists, and doing so is
preferable, because the honest answer to *"are we backed up?"* is more valuable
than the button that changes the answer.

## Objective

A seventh UI module — **Recovery** — that answers, from evidence: what is
protected, what is not, how old each artifact is, what its validation verdict is,
and what retention will delete next. It renders identically in the exported
static report and in the console; the console additionally lets a `read`-class
attestation be triggered from a row (the action registry from `CON.2`, nothing
new).

The coverage view is the single screen that makes the BackBox exit decision
concrete, because it counts the devices this product does **not** protect
instead of omitting them.

## Scope

### In scope

1. `utils/recovery_ui.py` — a payload builder over `compute_restore_readiness`
   (`utils/restore_readiness.py`), recovery manifests
   (`utils/recovery_manifest.py`) and retention policy
   (`utils/recovery_retention.py`).
2. A new `recovery_overview` payload, embedded by `utils/html_export.py` as a
   sixth JSON payload and served by the console at `/api/payloads` — the same
   object, per `CON.1` `C1-4`.
3. `static/recovery_ui.js` — a ninth UI module under
   `codebase_modularization`'s ownership rules.
4. `templates/index.html` + `templates/console.html`: the module's nav entry and
   container; `static/style.css` additions.
5. Overview gains one recovery-posture tile (§11).
6. `tests/fixtures/uitest/` extension covering every readiness state, including
   `UNPROTECTED` and `UNKNOWN`, and at least one device with no recovery record
   at all.
7. `tests/test_con4_recovery_module.py`.

### Explicitly out of scope

- **Any payload, download link, or decrypt path** (`CON.0` §7.6; §11's own
  central rule). Manifests, ages, verdicts and counts only.
- Restore, and anything that resembles preparing for one (`RB.6`, `OP.2`-gated).
- Retention *execution* or policy editing from the UI — the view shows what the
  configured policy will delete; changing it is deployment configuration.
- `compliance_posture` consuming readiness as a control evidence source. §11
  proposes it; it is a separate, additive build with its own control definition.
- Any new collection or attestation command.

## Design decisions

### `C4-1` — one payload, both surfaces, no console-only fields

`recovery_overview` is built once and embedded by the exporter; the console
serves the same object. `CON.1` AC-4's byte-equality test is extended to cover
it. If the console needs a field, the exporter gets it too — a console-only
field would fork the two surfaces on the exact screen where a shared, portable
evidence artifact matters most (this is the screen an auditor asks for).

### `C4-2` — devices with no recovery record are counted, never omitted

The coverage view enumerates every device in `unified.json` and classifies it.
A device with no artifact is `UNPROTECTED`; a device whose state cannot be
determined is `UNKNOWN`. Neither is filtered out of the roll-up, and the two are
never merged into one number. `CON.0` §13 names this as the product risk the
console creates: a polished screen that quietly omits what it does not cover
would make the `D1` gap *harder* to see, not easier.

### `C4-3` — validation verdict is shown per artifact, never summarised away

`RB.4`'s V1–V3 battery produces per-artifact verdicts, and `RESTORE_UNPROVEN` is
the honest ceiling until `D7` provides a restore-proof lab. The UI shows the
verdict verbatim per artifact and never renders a green "verified" state that
the evidence does not support. "Verified backup" is BackBox's flagship claim
(§6 of the architecture doc); overstating it here would be the most damaging
possible inaccuracy in this product.

### `C4-4` — identity discipline

The module renders `entity_id` and the inventory's existing display fields only.
No management address, no serial, no artifact path, no key material, no operator
`reason` text from a `CON.3` job. The exported report carries this module into
the sanitized support bundle path, so it is held to the report's identity rules,
not the console's.

### `C4-5` — the row action, if any, is `read`-class only

A Recovery row may offer `recovery_attest_cp` from `CON.2`'s registry
(`read` class, `RB.3a`). It offers a backup action only if `CON.3` has shipped,
through `CON.3`'s preflight flow unchanged — this phase adds no action path of
its own.

## Privacy and safety invariants

1. No route and no payload field exposes artifact bytes, artifact filesystem
   paths, wrapping keys, or decrypt material.
2. The static export's privacy posture is unchanged: the new payload must pass
   the repository privacy gate and the support-bundle sanitisation rules that
   already apply to every embedded payload.
3. The module performs no device contact in either mode.

## Acceptance criteria

- **AC-1** The Recovery module renders in the exported static report and in the
  console from the identical `recovery_overview` payload; byte-equality asserted
  (extends `CON.1` AC-4).
- **AC-2** Roll-up counts equal `compute_restore_readiness`'s own output for the
  fixture; no device in `unified.json` is missing from the coverage view.
- **AC-3** `UNPROTECTED` and `UNKNOWN` are distinct in the payload and in the UI;
  a fixture device with no recovery record appears as `UNPROTECTED`.
- **AC-4** Per-artifact validation verdicts render verbatim, including
  `RESTORE_UNPROVEN`; no aggregate label contradicts a constituent verdict.
- **AC-5** No payload field or route exposes bytes, paths, or key material
  (field-by-field assertion over the payload, plus route-table enumeration in
  console mode).
- **AC-6** `tests/fixtures/uitest/` covers every readiness state; the render
  harness is green including the real-Chromium path.
- **AC-7** Hostile labels in device names and artifact metadata are escaped —
  the module is held to `frontend_rendering_boundary`'s escaping contract, with
  at least one hostile-label fixture device flowing through this module.
- **AC-8** Full suite at or above baseline; privacy gate `PASS / 0`.

## Implementation plan

1. `utils/recovery_ui.py` + payload unit tests against synthetic manifests.
2. `tests/fixtures/uitest/` extension (regenerate via `build_fixture.py`).
3. Exporter embedding + console `/api/payloads` inclusion + AC-1 equality.
4. `static/recovery_ui.js` + template/nav/CSS; escaping per AC-7.
5. Overview recovery tile.
6. Render harness, real-Chromium walk, full suite, privacy gate, metadata.

## Validation and merge gate

Full suite at or above baseline, privacy gate `PASS / 0`, render harness green
including real Chromium, both delivery modes walked. No device contact, so no
real-environment gate — `AUTOMATED_VALIDATED` is the correct terminal status
until a human opens it on a real workstation with real artifacts present.

## Risks

- **Reassurance without coverage.** A screen full of green for the four devices
  the product does protect, on an estate of many more, is worse than no screen.
  `C4-2` and AC-2/AC-3 exist for exactly this.
- **Verdict inflation.** Any temptation to render `RESTORE_UNPROVEN` as a
  neutral or positive state must be refused; `C4-3`.
- **Fixture drift.** This is the first new UI module since the render harness
  existed; the uitest fixture must cover the states, not just the happy path, or
  the harness will pass while the module is wrong.

## Rollback

Fully additive. Removing the module, the payload and the fixture extension
returns both surfaces to their prior state; no existing payload shape changes.

## Definition of done

AC-1…AC-8 pass; `RB.5` marked complete in `project/roadmap.json` and
`project/feature_registry.json`; `BACKUP_AND_RECOVERY_ARCHITECTURE.md` §11 gains
a status line pointing at this contract; `CURRENT_STATE.md` and `AI_HANDOVER.md`
updated.

## Next movement / model

`IMPLEMENTATION` at **`Sonnet 5, normal`**. It is a payload builder plus a UI
module against an already-written functional spec (§11) and an existing readiness
engine; the judgement calls (`C4-2`, `C4-3`) are made here and need no further
reasoning at implementation time.
