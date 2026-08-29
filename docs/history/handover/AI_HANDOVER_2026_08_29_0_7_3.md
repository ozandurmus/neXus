# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.7.3 — CE.1: User-Authored Compliance Check Engine
  (data-driven, evidence-only)` — AUTOMATED_VALIDATED (2026-08-29).
- Engineering baseline: `DEV.1` complete; `DEV.2.1` — AUTOMATED_VALIDATED.
- Date: 2026-08-29
- **`0.7.2` + `0.7.3` are on `origin/main`.** Committed `fe461b0`, merged
  `--no-ff` as `5ca70e4`, pushed (`gh` CLI is not installed and the GitHub MCP
  token cannot open PRs, so the merge was done locally and pushed). Local
  feature branch `feature/0-7-3-compliance-check-engine` is kept;
  `feature/0-7-2-compliance-followups` was deleted (unused).
  - `0.7.2` — Compliance Follow-ups (password/banner/services projection +
    framework filter & explain UI).
  - `docs/design/COMPLIANCE_CHECK_ENGINE.md` — CE.1→CE.4, all decisions
    D1–D16 resolved.
  - `0.7.3` — CE.1 user-authored check engine.
- **CE.1 crypto-source follow-up — NOT yet committed.** Working tree on branch
  `feature/0-7-3-1-check-engine-crypto-source` (off the updated `main`).
  `build_compliance_posture` gained `crypto_facts_by_subject`; `html_export`
  threads it. 3 files + doc/state updates. Ready to commit + merge the same way.
- Python: bare `py` → 3.14 (no deps). Test deps are `--user` on **3.12**.
  `py -V:3.12 <script>` mis-parses on this box (exit 103); use
  `py -V:3.12 -m pytest` or the 3.12 interpreter directly at
  `%LOCALAPPDATA%\Programs\Python\Python312\python.exe`.
- Full suite: `py -m pytest -q -n auto --dist worksteal` → **516 passed,
  3 skipped, 0 failed** (~35s).
- Repository privacy gate: **PASS / 0 on a clean checkout**. (Locally it reports
  `data/` + `logs/` + `data/.support_hmac.key` — gitignored runtime artifacts
  that test runs create; delete them before running the gate.)

## 2. Recent builds (this session, on the stacked feature branches)

- **`0.7.3` — CE.1 user-authored compliance check engine.** Contract
  `docs/history/phase/0_7_3_COMPLIANCE_CHECK_ENGINE.md` (§10 impl record);
  design `docs/design/COMPLIANCE_CHECK_ENGINE.md`. A check is **data** (name +
  evidence source + expected pattern + verdict) evaluated over
  **already-collected** evidence. No server, no new device command.
  - `utils/compliance_check_pack.py` (new) — `load_compliance_checks(data_root)
    -> CompliancePack` from `data/state/compliance_checks.json`, schema v1,
    fail-closed; `x_`-prefixed ids; hand-written selector grammar over
    `{current_configuration, unified, crypto_facts, alignment}` (no `eval`, no
    dep); fixed 14-operator assertion set; `evidence.steps[]` + `combine
    all|any`; anchored regex ≤ 512 chars behind a complexity linter +
    eval-time timeout; `mode enforced|advisory`; a `remediation` key is
    **rejected** (reserved for CE.4).
  - `utils/compliance_check_engine.py` (new) — pure `resolve_source` /
    `apply_select` / `apply_assertion` / `evaluate_check`. An unresolvable path
    → `None` → `on_no_evidence` for every operator (never an inferred `PASS`);
    a regex timeout → step inconclusive. `redacted_selector` blanks
    `[attr=value]` filter values.
  - `utils/compliance_posture.py` — `_subject_user_checks` →
    `subject["extended_controls"]` (`control_class:"user_check"`, `advisory`,
    `pack`, `check_steps`, redacted `evidence_fields`); `_scoring_rows` excludes
    `advisory` rows from subject status + fleet counts + `compliance_overview`;
    new top-level `check_packs` (counts + `pack_id` only).
  - `utils/control_assignment.py` — `load_control_assignments(data_root, *,
    extra_known_ids=frozenset())` so an `include`/`exclude`/waiver can target an
    `x_` check id.
  - `static/app.js` + `static/style.css` — "user-defined" + "advisory" badges;
    source-pack + per-step `expected`/`observed` in the Explain panel (pattern
    `expected` shown as "redacted").
  - Frozen touch-ups: `test_phase0_7_1_compliance_assignment` +
    `test_phase0_6_6b_compliance_rulepack` — `check_packs` added to the allowed
    additive top-level key set (precedent: `compliance_overview` /
    `assignment_policy` in 0.7.1b).
- **`0.7.2` — Compliance Follow-ups.** Contract
  `docs/history/phase/0_7_2_COMPLIANCE_FOLLOWUPS.md` §10. `password_policy` /
  `banner` (presence + length bucket only) / `services` projection-extension
  sections over already-stored config (no new collector; PAN allowlist
  extractors, CP `password-controls` allowlist carve-out + banner-body
  redaction). +6 enrichment controls (`CATALOG_VERSION → "0.7.2"`) in
  `extended_controls`. Framework filter chips + inline Explain panel.
  `CURRENT_CONFIG_SCHEMA_VERSION → "0.7.2"` (PAN).
- **Design + decisions** — `docs/design/COMPLIANCE_CHECK_ENGINE.md`: the whole
  check engine phased CE.1→CE.4, every decision resolved (§10), the
  write-capability product trajectory recorded (§11), CE.4 remediation checks
  hard-gated (§12). New backlog: `compliance_check_engine` (CE.1, done),
  `compliance_check_engine_primitives` (CE.2), `compliance_check_engine_ui`
  (CE.3), `compliance_remediation_checks` (CE.4).

## 3. Next work

**No active build contract is open.** `0.7.2` + `0.7.3` are AUTOMATED_VALIDATED on
the stacked feature branch and need a human `main` merge (§6). A new build needs
a fresh contract, reviewed first.

- **CE.1 fast-follow — `crypto_facts` wired (done 2026-08-29).**
  `build_compliance_posture(..., crypto_facts_by_subject=None)`; `html_export`
  threads the privacy-reviewed 0.7.0 fact groups keyed by subject id.
  Remaining: `unified.interfaces` / `unified.routes` still parse and resolve
  empty — wire when `build_compliance_posture` is given the merged inventory
  row (non-breaking optional param).
- **CE.2** (`compliance_check_engine_primitives`) — curated read-only
  command-primitive registry (`configuration/command_primitives.py`), opt-in
  `--compliance-probe`. **Blocked on `cp_device_interaction_safety` (P0) + the
  network-device command gate.**
- **CE.3** (`compliance_check_engine_ui`) — browser check editor + signed
  distributable org packs. `DEPLOY.1A`-gated.
- **CE.4** (`compliance_remediation_checks`) — the write-capable
  `remediation` block. Hard-gated on the OP.2 /
  `FAILOVER_ENGINE_ARCHITECTURE.md` §10 bar.
- **Other `0.7.x`:** `framework_mappings`; a point-in-time / trend layer for
  `compliance_overview` off the config history.
- **Standing doable-now:** `immutable_store_permission` (P1 bug),
  `html_render_performance` (P2), `inventory_exclusions_ui` /
  `overview_device_lifecycle_enrichment` (P1 UI).

**OP.x — Controlled Failover:** design done, approval pending
(`docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`; `roadmap.json` `open_decisions`).

## 3b. NOT YET DONE — real-environment / on-hardware validation

Automated tests green; nothing since the 0.6.1x builds exercised end-to-end.
Backlog `on_hardware_real_env_validation` (P0), laptop-blocked.

- Local render check (no devices): `py -V:3.12 scripts/render_sample.py` —
  0.7.3 verified 2026-08-29 (exit 0, 0 placeholders; with no
  `compliance_checks.json` the Compliance module renders exactly as 0.7.2,
  `check_packs: []`).
- Real collection run: needs an MDS / Panorama + credentials — deferred to the
  server. User checks that assert over `password_policy` / `banner` / `services`
  (0.7.2) or any projection only produce PASS/FINDING once a real configuration
  collection has run.

## 4. Open risks / debt carried forward

- CP device-interaction-safety audit remains P0 — hard prerequisite for CE.2.
- `unified.interfaces` / `unified.routes` selector namespaces parse but resolve
  empty in CE.1 (a check using them → `on_no_evidence`). Not a bug — wire when
  `build_compliance_posture` is given the merged inventory row. (`crypto_facts`
  is now wired — the CE.1 crypto-source follow-up.)
- The regex safety linter (`_REDOS_RE` + quantifier count + `.*.*`) is
  best-effort; the eval-time timeout is the real backstop. `regex` module is
  used when importable, else stdlib `re` with a 20 000-char input cap.
- CP `show configuration` sanitized artifact now redacts the banner body
  (0.7.2 `MESSAGE_BODY_RE`); PAN `login-banner` body is no longer projected
  (0.7.2 presence-only `banner` section). Confirm on the next real-env run that
  no downstream consumer depended on the banner text.
- The CAS / support-key path writes `data/` and `logs/` into the repo dir during
  a test run (`DEV.0.3C` deferred). Gitignored; the privacy gate flags them —
  delete before running the gate.
- `scripts/pytest_one_shot.ps1` calls `py` → 3.14 without deps
  (`dev_python_env_tooling_friction`).

## 5. Exact next action

1. Commit + merge the **CE.1 crypto-source follow-up** (branch
   `feature/0-7-3-1-check-engine-crypto-source`) the same way 0.7.2+0.7.3 were
   landed — see §6.
2. Then: fresh chat, pick a new 0.7.x objective (framework_mappings; a
   point-in-time / trend layer for `compliance_overview` off the config
   history; or wire `unified.interfaces`/`routes` into the check engine),
   write + review a contract, implement.

## 6. main merge decision + Git dispatch

`0.7.2` + `0.7.3` are **already merged and pushed** — `origin/main` at `5ca70e4`
(merge commit), `fe461b0` the squashed build commit.

The **CE.1 crypto-source follow-up** is committed-ready on
`feature/0-7-3-1-check-engine-crypto-source`. Recommendation: **approved for
`main`**. Evidence: 516 passed / 3 skipped / 0 failed; render exit 0 / 0
placeholders; privacy gate PASS / 0 on a clean tree. Additive; `html_export` is
the sole non-test caller; non-breaking optional param.

`gh` is not installed and the GitHub MCP token cannot open PRs — land it locally:

```
git add -A
git commit -m "feat(compliance): CE.1 crypto-source wire — crypto_facts namespace for user checks"
git checkout main && git merge --no-ff feature/0-7-3-1-check-engine-crypto-source
git push origin main
```

## 7. Next movement / model

- Next movement: `ARCHITECTURE` (high reasoning) for a new 0.7.x contract
  (framework_mappings, trend layer), then `IMPLEMENTATION` (normal).

## 8. Continue or fresh chat

**Start a fresh chat** after the follow-up is merged — the next build is a
different objective and needs its own contract.

## 9. main.py / UI effect

With a `data/state/compliance_checks.json` present: the Compliance module shows
the user's checks as extra cards in the enrichment area — a "user-defined" badge
on each, an "advisory" badge where `mode: advisory`; `enforced` checks move the
coverage roll-up, `advisory` ones do not. The Explain panel shows the source
pack and each step's expected/observed (pattern shown as "redacted"). With no
pack file: no visible change from 0.7.2 (`check_packs: []`). A malformed pack
fails the run closed, like a malformed `control_assignments.json`. No change to
Network Inventory, Configuration, Discovery, or Project Plan.
