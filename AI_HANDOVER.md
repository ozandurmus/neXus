# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.7.6a — Render harness + uitest topology matrix` —
  AUTOMATED_VALIDATED (2026-08-30). Previous: `0.7.6` (harness), `0.7.5` (trend
  layer), `0.7.4`; hotfix `0.7.4a` REAL_ENV_VALIDATED.
- Engineering baseline: `DEV.1` complete; `DEV.2.1` — AUTOMATED_VALIDATED.
- Date: 2026-08-30
- **`origin/main` is at `bf9c605`** — CE.1 fast-follow #2 (`5b5e893`), `0.7.4a`
  render hotfix (`7d991a3`) + real-env doc (`0c89880`), `0.7.5` trend layer
  (`8aa0805`), `0.7.6` render harness (`bf9c605`) are all merged + pushed.
- **`0.7.6a` (uitest topology-matrix expansion) is on branch
  `feature/0-7-6a-uitest-topologies`, automated-validated, not yet merged**
  (§2, §6).
- **Never label a version as four dot-separated numbers** — `_IPV4_RE` in the
  privacy gate flags an `A.B.C.D` numeric label as a `PRIVATE_ENDPOINT_LITERAL`.
  Use a letter suffix (`0.7.4a`). The two `0.7.4a` hotfix *commit messages*
  still say the old form (immutable; the gate scans files only).
- **GitHub tooling (2026-08-29):** `gh` CLI is installed
  (`C:\Program Files\GitHub CLI\gh.exe`, v2.98.0) and authenticated as
  `ozandurmus` — token scopes `gist, read:org, repo, workflow` (`repo` covers
  push / PR create / merge). `gh auth setup-git` has wired git's credential
  helper for `github.com`, so `git push` / `fetch` against
  `https://github.com/ozandurmus/neXus.git` need no prompt. `gh` was added to the
  User PATH; a shell started before that still needs the full exe path. Corporate
  Git push/merge stays human-initiated (`CURRENT_STATE.md` standing priority 4) —
  a real `gh pr create` is now an option in place of a local `--no-ff` merge,
  under the same human go-ahead.
  - `5ca70e4` — `0.7.2` + `0.7.3` + `docs/design/COMPLIANCE_CHECK_ENGINE.md`
    (CE.1→CE.4, decisions D1–D16 resolved)
  - `38b6a74` — CE.1 crypto-source wire
  - `70f86b1` — governance: explicit model + reasoning recommendation to the
    user at every checkpoint (`AGENTS.md`, `CLAUDE.md`)
  - `684adad` — `0.7.4` framework_mappings requirement-level coverage
- Python: bare `py` → 3.14 (no deps). Test deps are `--user` on **3.12**.
  `py -V:3.12 <script>` mis-parses on this box (exit 103); use
  `py -V:3.12 -m pytest` or the 3.12 interpreter directly at
  `%LOCALAPPDATA%\Programs\Python\Python312\python.exe`.
- Full suite: `py -m pytest -q -n auto --dist worksteal` → **551 passed,
  3 skipped, 0 failed** (~35s). +13 (0.7.5) + 3 (0.7.6) since the 534 baseline.
- **HTML render harness** (0.7.6): `py -V:3.12 scripts/render_uitest.py --out D`
  then `bun tools/render-harness/check-render.mjs D/output/index.html`.
  `tests/test_html_render_harness.py` runs both; the bun half `skipif`s when
  `bun` (`~/.bun/bin/bun`) or `tools/render-harness/node_modules` is absent —
  run `bun install` in `tools/render-harness/` once.
- Repository privacy gate: **PASS / 0 on a clean checkout**. Locally it flags the
  gitignored `data/` + `logs/` + `data/.support_hmac.key` a test run creates —
  delete them before running the gate.

## 2. Recent builds (this session)

- **`0.7.6a` — `uitest` fixture expanded to a topology matrix** — branch
  `feature/0-7-6a-uitest-topologies`, automated-validated, **not yet merged**.
  Same phase doc, §4. `build_fixture.py` regenerated so the bundle covers CP
  standalone gateway / ClusterXL (active+standby) / VSX host + virtual systems /
  VSX cluster + shared virtual system / UNAVAILABLE gateway; PAN single / HA pair
  / multi-vsys / multi-vsys HA. Plus cluster-member interface+route divergence,
  `live`/`last_known_good`/`no_data` inventory, the full alignment class set,
  `same`/`changed`/`first` history, crypto `PASS`/`FINDING`/`UNKNOWN`, enforced +
  advisory + WAIVED (`state/control_assignments.json`) compliance. New
  `test_all_topologies_present` (suite 550 → 551).
- **`0.7.6` — Automated HTML render harness + `uitest` fixture** — **LANDED** on
  `main` (`bf9c605`). Contract + impl record:
  `docs/history/phase/0_7_6_RENDER_HARNESS.md`.
  - `tests/fixtures/uitest/` — committed, hand-authored, privacy-clean bundle
    (fake names, RFC 5737 ranges): `unified.json` + injected `configuration_ui`
    / `crypto_ui` / `discovery_ui` + `state/*` + `build_fixture.py` +
    `README.md` (growth rule).
  - `scripts/render_uitest.py` — renders from the bundle; injects only the three
    builders whose real inputs are telemetry / PAN XML / live stores.
    `build_compliance_posture`, `_fill_template`, `_script_json` run for real.
  - `tools/render-harness/check-render.mjs` — bun + `happy-dom`: parse the inline
    `<script>`, `window.eval` it, assert `window.switchModule`, click every
    `.module-nav-item` + inner tab (panels switch, zero `console.error`).
    `package.json` + `bun.lock` committed; `node_modules/` gitignored.
  - `tests/test_html_render_harness.py` (3): JSON validity of every embedded
    payload (no JS engine — the `0.7.4a` class), all six modules populated,
    headless nav smoke test (`skipif` no `bun`).
  - Governance: `docs/AI_DEVELOPMENT_PROTOCOL.md` mandatory section; `AGENTS.md`
    project-state-update rule gains `tests/fixtures/uitest/`.
  - `utils/repository_privacy.py` + `test_dev_0_5b_*` skip `node_modules`.
  - Negative-tested: corrupt payload literal → FAIL parse; renamed nav handler →
    FAIL (16), panels don't activate. No `main.py` change.
- **`0.7.5` — Compliance trend layer** — **LANDED** on `main` (`8aa0805`).
  Contract + impl record: `docs/history/phase/0_7_5_COMPLIANCE_TREND.md`.
  Resolves design doc §11 decision 9. Additive; no server/collector/command;
  `COMPLIANCE_SCHEMA_VERSION` unchanged.
  - `utils/compliance_history.py` (new) — append-only ledger
    `data/state/compliance_history.json` (RuntimeRoot state, gitignored).
    `load_history` (**fail-safe** — missing/corrupt → `[]`, never raises),
    `summarise_overview`, `append_run` (cap `MAX_RECORDS` 200, atomic), plus
    `history_view` → `{records, trend}`. Aggregates + ISO dates only; **no
    identity, no per-subject rows**.
  - `utils/compliance_posture.py` — `build_compliance_posture(..., history=None)`;
    `_compliance_overview` + `_empty_overview` append `history` (last 30) +
    `trend` (delta vs newest prior record; `null` with < 1 prior).
  - `utils/html_export.py` — reads the ledger on every render; writes one record
    only when `run_html_export(record_checkpoint=True)`.
  - `main.py` — the one full-checkpoint render passes `record_checkpoint=True,
    run_id=run_ctx.run_id`. No other call site touched (`--render-only` /
    `--only` / diagnostic keep the `False` default).
  - `static/app.js` + `style.css` — `complianceSparkline` + `complianceTrendChip`
    on the Overview compliance card + the Compliance KPI band; render nothing
    below 2 points / null trend.
  - `tests/test_phase0_7_5_compliance_trend.py` (13).
  - **Owed:** a live trend line only appears after a *second* real full
    checkpoint (`on_hardware_real_env_validation`).
- **`0.7.4a` — HTML export render hotfix (P0)** — **LANDED** on `main`
  (`7d991a3`), **REAL_ENV_VALIDATED** (`0c89880`). Record:
  `docs/history/phase/0_7_4A_HTML_EXPORT_RENDER_HOTFIX.md`.
  - Symptom: real `py .\main.py` run rendered the report but every
    `.module-nav-item` button was dead, stuck on Overview.
  - Cause: `utils/html_export.py` chained `str.replace()` per placeholder.
    `project/backlog.json` + `project/build_history.json` carry the literal token
    `__CRYPTO_JSON_PLACEHOLDER__` in a note; once `projectPlanData` was embedded,
    the later `replace("__CRYPTO_JSON_PLACEHOLDER__", …)` spliced the crypto JSON
    object into that string literal → `SyntaxError` → the whole inline `<script>`
    never executed → no listeners. Static Overview panel still rendered.
  - Fix: `_fill_template(template, replacements)` — one `re` alternation pass,
    longest key first, function replacement; inserted content is never
    re-scanned. `run_html_export` calls it once for all 8 sentinels.
  - Tests: `tests/test_html_export_placeholder_integrity.py` (5) — every
    embedded payload round-trips `json.loads`; the token survives as data inside
    `projectPlanData` unexpanded. Suite 529 → 534. Privacy gate PASS/0;
    `render_sample.py` exit 0.
  - **Owed:** real-env click-through on the corporate laptop (regenerate, click
    all six modules). Deterministically reproduced + guarded; on-hardware
    confirmation still under `on_hardware_real_env_validation`.
- **CE.1 fast-follow #2 — `unified.interfaces` / `unified.routes` wire**
  (on `main`, `e8ca974`; merge `5b5e893`). Record:
  `docs/history/phase/0_7_3_COMPLIANCE_CHECK_ENGINE.md` §12. Additive; no server;
  no collector; no device command; `COMPLIANCE_SCHEMA_VERSION` unchanged; no
  product-version bump.
  - `utils/compliance_posture.py` — `build_compliance_posture(..., *,
    unified_inventory=None)`; `_index_unified_inventory` (identity + PAN serial
    → rows), `_match_unified_rows` (subject `device_name`/`name`/`id`,
    normalised, **vendor-scoped**: `check_point`→`cp`/`vsx`, `palo_alto`→
    `panorama`; fan-in unioned), `_inventory_collection` (`None` when unmatched
    vs `[]` when matched-empty). `_subject_evidence` / `_subject_user_checks`
    take `unified_rows`.
  - `utils/compliance_check_pack.py` — `is_inventory_collection_selector`;
    `_step` rejects any op other than `present` / `absent` / `count_gte` /
    `count_lte` on `unified.interfaces` / `unified.routes` (fail-closed).
  - `utils/compliance_check_engine.py` — `_redact_count_only`; `evaluate_check`
    renders a count-only `observed` (`"N inventory row(s)"`) for those two
    namespaces so no interface address / route target enters the payload.
  - `utils/html_export.py` — threads the already-loaded `unified.json` list
    (`--render-only` covered).
  - `tests/test_phase0_7_3_compliance_check_engine.py` — +6.
  - Behaviour delta: an unmatched subject's `unified.interfaces` / `.routes`
    now → `UNKNOWN` (was a definite False on the old `[]` placeholder). No
    shipped pack used them.
- **`0.7.4` — framework_mappings: requirement-level coverage** (on `main`,
  `684adad`). Contract `docs/history/phase/0_7_4_FRAMEWORK_REQUIREMENTS.md` §10;
  design `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §9. Additive; no
  server; no new control/collector.
  - `utils/framework_catalog.py` (new) — `FRAMEWORK_CATALOG_VERSION "0.7.4"`.
    CIS / PCI-DSS / BDDK, each an authored requirement list covering every
    structured control `frameworks[].reference` + a curated gap set; our own
    one-line titles (**no verbatim benchmark text**); process-only requirements
    carry `applies:false`. `normalize_ref` join key; `framework_entry`,
    `requirements_for`, `FRAMEWORK_IDS`.
  - `utils/compliance_posture.py` — `_control_framework_refs`; a
    `per_control_unknown` accumulator; `_compliance_overview.by_framework[name]`
    gains `version` / `profile` + `requirements: [{id, section, title,
    control_ids, applicable, monitored, aligned, finding, unknown, coverage,
    posture}]`, `requirement_counts`, `unmapped_control_refs`. Top-level
    `framework_catalog_version`. `_empty_framework_block` for the empty path.
    Catalog + `x_` user checks join by `normalize_ref`. `_user_check_meta` gains
    `framework_refs`.
  - `static/app.js` + `static/style.css` — framework readiness cards get a
    version line, a 4-segment `compliance-req-bar` mini-bar, a `Requirements (N)`
    expand (reuses the 0.7.2 `[data-explain-toggle]` listener) → per-requirement
    rows with a `coverage` pill + a `posture` pill (`compliancePostureTone`) +
    mapped control ids.
  - `tests/test_phase0_7_4_framework_requirements.py` (7). No frozen touch-up.
- **`0.7.3` — CE.1 user-authored compliance check engine** (on `main`).
  `utils/compliance_check_pack.py` + `utils/compliance_check_engine.py` (pure) +
  `compliance_posture` wiring + `control_assignment.extra_known_ids` + UI badges.
  A check is *data* over already-collected evidence; fail-closed pack; `x_` ids;
  safe selector grammar over `{current_configuration, unified, crypto_facts,
  alignment}`; 14 fixed operators; `combine all|any`; regex behind a linter +
  timeout; `enforced|advisory`; `remediation` rejected (reserved for CE.4).
  Crypto-source follow-up wired `crypto_facts` via `html_export`.
- **`0.7.2` — Compliance Follow-ups** (on `main`). `password_policy` / `banner`
  (presence only) / `services` projection-extension sections (no new collector);
  +6 enrichment controls; framework filter chips + Explain panel;
  `CURRENT_CONFIG_SCHEMA_VERSION → "0.7.2"` (PAN).
- **`docs/design/COMPLIANCE_CHECK_ENGINE.md`** (on `main`) — CE.1→CE.4, all
  decisions D1–D16 resolved (§10), write-capability trajectory (§11), CE.4
  hard-gated (§12).
- **Governance** (on `main`) — the agent must state an explicit `model +
  reasoning tier` recommendation to the user at every checkpoint and flag when a
  pre-picked tier is overkill (`AGENTS.md` "AI reasoning / movement routing",
  `CLAUDE.md`).

## 3. Next work

**No active build contract is open** once `0.7.5` (§2) merges. A new build needs
a fresh contract, put to the user for review first.

- **`compliance_overview.trend` retro-fill / reconstruction** (optional, later) —
  a TRACE-plane build that recomputes past posture from the content-addressed
  config history so the trend has depth before the first two live checkpoints.
  `0.7.5` deliberately did not do this (append-only ledger, no backfill).
- **CE.2** (`compliance_check_engine_primitives`) — curated read-only
  command-primitive registry, opt-in `--compliance-probe`. **Blocked on
  `cp_device_interaction_safety` (P0) + the command gate.**
- **CE.3** (`compliance_check_engine_ui`) + **CE.4**
  (`compliance_remediation_checks`) — `DEPLOY.1A` / OP.2-gated.
- **Standing doable-now:** `immutable_store_permission` (P1 bug),
  `html_render_performance` (P2), `inventory_exclusions_ui` /
  `overview_device_lifecycle_enrichment` (P1 UI).

**OP.x — Controlled Failover:** design done, approval pending
(`docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`; `roadmap.json` `open_decisions`).

## 3b. NOT YET DONE — real-environment / on-hardware validation

Automated tests green; nothing since the 0.6.1x builds exercised end-to-end.
Backlog `on_hardware_real_env_validation` (P0), laptop-blocked.

- **Automated render check (0.7.6):** `scripts/render_uitest.py` +
  `tools/render-harness/check-render.mjs` — a full six-module render, script
  parse + execute + nav-click smoke, no human needed. `scripts/render_sample.py`
  stays the empty-state check.
- **`0.7.4a`**: **REAL_ENV_VALIDATED (2026-08-30)** — product owner ran a full
  `py .\main.py` on the corporate laptop, regenerated `output/index.html`, and
  confirmed all six modules and every tab work. Dead-button fix done.
- **`0.7.5`**: the trend sparkline/chip only appears after a *second* real full
  `py .\main.py` checkpoint (the ledger starts empty; no backfill).
- Real collection run: needs an MDS / Panorama + credentials — deferred to the
  server. Requirement coverage / user-check outcomes only become PASS/FINDING
  once a real configuration collection has run.

## 4. Open risks / debt carried forward

- CP device-interaction-safety audit remains P0 — hard prerequisite for CE.2.
- `unified.interfaces` / `unified.routes` now resolve, but the subject→inventory
  join is an exact normalised-identity match on the config-UI device name — if a
  real run's config-subject name and `unified.json` `device` string diverge, the
  namespace stays `UNKNOWN` (fail-closed, not wrong). VSX cluster-name matching
  and any per-VSID scoping are not attempted; confirm the join lands on the
  first real-env run before authoring interface/route packs.
- The regex safety linter (`_REDOS_RE` + quantifier count + `.*.*`) is
  best-effort; the eval-time timeout is the real backstop.
- CP `show configuration` sanitized artifact redacts the banner body (0.7.2
  `MESSAGE_BODY_RE`); PAN `login-banner` body is no longer projected. Confirm on
  the next real-env run that no downstream consumer needed the banner text.
- `0.7.4` framework catalog requirement lists are hand-authored — new controls
  need their `frameworks[].reference` added as (or matched to) a requirement, or
  they surface in `by_framework[name].unmapped_control_refs`.
- The CAS / support-key path writes `data/` + `logs/` into the repo dir during a
  test run (`DEV.0.3C` deferred). Gitignored; delete before the privacy gate.
- `scripts/pytest_one_shot.ps1` calls `py` → 3.14 without deps
  (`dev_python_env_tooling_friction`).
- **Version labels must not be four dot-separated numbers** — `_IPV4_RE` in
  the repository privacy gate flags an `A.B.C.D` numeric label as a
  `PRIVATE_ENDPOINT_LITERAL`. Use a letter suffix (`0.7.4a`, matching
  `0.7.1a` / `0.7.1b`).
- `0.7.5` trend ledger: aggregates only, no backfill. A framework catalog /
  control-set version change between runs is recorded per-record but the trend
  line does not annotate it — a jump may be a rule change, not a posture change.

## 5. Exact next action

1. **Land `0.7.6a`** (branch `feature/0-7-6a-uitest-topologies`). Modified:
   `tests/fixtures/uitest/build_fixture.py` + its regenerated
   `unified.json` / `configuration_ui.json` / `crypto_ui.json` /
   `discovery_ui.json` / `state/*.json` (+ new `state/control_assignments.json`),
   `tests/fixtures/uitest/README.md`, `tests/test_html_render_harness.py`,
   `docs/history/phase/0_7_6_RENDER_HARNESS.md`, `project/build_history.json`,
   `CURRENT_STATE.md`, `AI_HANDOVER.md`. `git merge --no-ff` + `git push`.
2. From now on, run the render harness for any UI/payload change (it's in the
   `AGENTS.md` close checklist + `AI_DEVELOPMENT_PROTOCOL.md`).
3. `0.7.5` real-env confirmation still owed: the trend chip appears after the
   user's *next two* real full checkpoints.
4. New build → fresh contract for user review first. Candidates in §3.

## 6. main merge decision + Git dispatch

- **CE.1 fast-follow #2, `0.7.4a` hotfix, `0.7.5` trend layer, `0.7.6` render
  harness: LANDED** on `origin/main` (`bf9c605`).
- **`0.7.6a` uitest topology-matrix expansion: approved on evidence, pending the
  user's go-ahead to run the merge** (standing priority 4). Branch
  `feature/0-7-6a-uitest-topologies`. Evidence: 551p/3s/0f,
  `check-render.mjs` PASS on the 16-device render, privacy gate PASS/0.
- Dispatch: `git checkout main && git merge --no-ff
  feature/0-7-6a-uitest-topologies && git push origin main`.
- Delete the gitignored `data/` + `logs/` a test run leaves before the privacy
  gate. `bun.lock` + `package.json` are committed; `node_modules/` is not.

## 7. Next movement / model

- `IMPLEMENTATION` (**Sonnet 5, normal**) for any standing doable-now item
  (§3). Nothing pending needs Opus or extended thinking.
- A new 0.7.x/0.8.x design (trend reconstruction, OP.0) would want
  **Sonnet 5, extended thinking** for the contract only.

## 8. Continue or fresh chat

**Continue this chat** to land `0.7.6a`. Start a fresh chat for the next distinct
build.

## 9. main.py / UI effect

- **0.7.6 / 0.7.6a:** none — dev/CI tooling + test fixture only. `main.py`
  unchanged.
- **0.7.5:** no visible change until a *second* full `py .\main.py` checkpoint
  exists. From then, the Overview compliance card and the Compliance KPI band
  show an aligned-% sparkline and a "±N pts since <date>" chip. `--render-only`
  never writes the ledger; a corrupt ledger degrades to "no trend", never an
  error. Payload gains `compliance_overview.history[]` + `.trend` (additive).
- **0.7.4a:** the generated `output/index.html` now runs its inline `<script>` —
  the module-nav buttons, tab switching and every interactive panel work again.
  Before the fix the page rendered the static Overview shell only and no button
  responded. No payload/schema change; a re-render of an existing run is enough.
- **0.7.4:** each framework readiness card in the Compliance module shows a
  `version · profile` line, a COVERED/PARTIALLY/UNCOVERED/N-A requirement
  mini-bar, and a `Requirements (N)` expand → a per-requirement list with
  coverage + posture pills and mapped control ids. Framework-level percentages
  unchanged. With no config evidence the cards render the empty requirement
  shape. No change to Network Inventory, Configuration, Discovery, Project Plan.
- **0.7.3:** with a `data/state/compliance_checks.json`, user checks render as
  extra enrichment cards ("user-defined" / "advisory" badges); `enforced` checks
  move the roll-up, `advisory` do not. No pack file → no visible change from
  0.7.2. A malformed pack fails the run closed.
- **CE.1 fast-follow #2:** no visible change unless a pack ships an
  `unified.interfaces` / `unified.routes` check — then that check now evaluates
  against real merged inventory (previously always `UNKNOWN`). Its Explain
  `observed` line reads `"N inventory row(s)"` (count only). No change to any
  module for a run with no such check.
