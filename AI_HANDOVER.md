# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.7.4 — framework_mappings: Requirement-Level Coverage` —
  AUTOMATED_VALIDATED (2026-08-29). Latest: hotfix `0.7.4.1` (2026-08-30).
- Engineering baseline: `DEV.1` complete; `DEV.2.1` — AUTOMATED_VALIDATED.
- Date: 2026-08-30
- **`origin/main` was at `d3b1384`** (CE.1 fast-follow #2 + its handover sync).
  Then **`0.7.4.1` HTML-export render hotfix** landed on branch
  `fix/html-export-placeholder-collision` — automated-validated, awaiting the
  user's go-ahead to merge to `main` (§2, §6).
- The trend-layer contract (§3) is approved (recommendations A–E) but **not
  started** — the hotfix pre-empted it. Pick it up next.
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
- Full suite: `py -m pytest -q -n auto --dist worksteal` → **534 passed,
  3 skipped, 0 failed** (~35s) — 523 baseline + 6 (fast-follow #2) + 5 (0.7.4.1).
- Repository privacy gate: **PASS / 0 on a clean checkout**. Locally it flags the
  gitignored `data/` + `logs/` + `data/.support_hmac.key` a test run creates —
  delete them before running the gate.

## 2. Recent builds (this session)

- **`0.7.4.1` — HTML export render hotfix (P0)** — branch
  `fix/html-export-placeholder-collision`, **not yet merged**. Record:
  `docs/history/phase/0_7_4_1_HTML_EXPORT_RENDER_HOTFIX.md`.
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

**No active build contract is open** (the CE.1 fast-follow #2 in §2 is
implemented + automated-validated, pending commit/merge). A new build needs a
fresh contract, put to the user for review first.

- **Point-in-time / trend layer for `compliance_overview`** (`Sonnet 5 extended
  thinking` for the contract) — read the existing config history so a past run's
  posture is reproducible; the payload was designed so a `history[]` is additive
  (design `COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §9 "point-in-time", §10
  "Time").
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

- Local render check (no devices): `py -V:3.12 scripts/render_sample.py` —
  0.7.4 verified 2026-08-29 (exit 0, 0 placeholders).
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

## 5. Exact next action

1. **Land `0.7.4.1`** (branch `fix/html-export-placeholder-collision`). Changed:
   `utils/html_export.py`, `tests/test_html_export_placeholder_integrity.py`
   (new), `docs/history/phase/0_7_4_1_HTML_EXPORT_RENDER_HOTFIX.md` (new),
   `project/build_history.json`, `CURRENT_STATE.md`, `AI_HANDOVER.md`.
   `git merge --no-ff` into `main` + `git push`, **or** `gh pr create` →
   `gh pr merge`.
2. Ask the user to regenerate the report on the corporate laptop and click
   through all six modules (real-env confirmation of the hotfix).
3. Then the **trend layer** (§3, approved A–E): `ARCHITECTURE` handoff already
   drafted in chat; write it up as `docs/history/phase/0_7_5_COMPLIANCE_TREND.md`
   and implement on `feature/0-7-5-compliance-trend`.

## 6. main merge decision + Git dispatch

- **CE.1 fast-follow #2: LANDED.** merged `--no-ff` into `main` (`5b5e893`),
  handover sync `d3b1384`, pushed; branch deleted.
- **`0.7.4.1` hotfix: approved on evidence, pending the user's go-ahead to run
  the merge** (standing priority 4). Branch
  `fix/html-export-placeholder-collision`. Evidence: 534p/3s/0f, privacy gate
  PASS/0 clean tree, `render_sample.py` exit 0, fresh sample `index.html` — all
  six payload literals parse as valid JSON.
- Dispatch: `git checkout main && git merge --no-ff
  fix/html-export-placeholder-collision && git push origin main` (or the `gh pr`
  path; `gh` installed + `repo`-scoped, see §1).
- Delete the gitignored `data/` + `logs/` a test run leaves before the privacy
  gate.

## 7. Next movement / model

- `ARCHITECTURE` (**Sonnet 5, extended thinking**) for the point-in-time / trend
  layer contract — the only reason to escalate is the config-history read + the
  additive `history[]` shape.
- Otherwise `IMPLEMENTATION` (**Sonnet 5, normal**) for any standing doable-now
  item. Nothing pending needs Opus.

## 8. Continue or fresh chat

**Continue this chat** through: land `0.7.4.1` → real-env confirmation → the
trend-layer build. Start a fresh chat only if context gets polluted.

## 9. main.py / UI effect

- **0.7.4.1:** the generated `output/index.html` now runs its inline `<script>` —
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
