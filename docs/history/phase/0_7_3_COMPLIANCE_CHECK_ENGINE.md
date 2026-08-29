# 0.7.3 — CE.1: User-Authored Compliance Check Engine (data-driven, evidence-only) (contract)

**Status:** `AUTOMATED_VALIDATED 2026-08-29` (see §10 impl record) · **Movement:** ARCHITECTURE → IMPLEMENTATION → UI → VALIDATION
**Design:** `docs/design/COMPLIANCE_CHECK_ENGINE.md` (decisions D1–D16 resolved, §10; trajectory §11; CE.4 §12)
**Advances:** `compliance_engine`, `framework_mappings`, `evidence_reporting` (0.7.x VERIFY track)
**Predecessor:** `0.7.2` (AUTOMATED_VALIDATED 2026-08-29). Buildable now — no server, no new device command.

---

## PROJE ÖZETİ (Türkçe)

- **Bu görev nedir:** Uyum kontrolünü **veri** olarak tanımlamayı mümkün kılmak
  (BackBox/Nipper modeli): kontrolün adı, hangi **zaten toplanmış kanıta**
  bakacağı, beklenen değer/desen, eşleşirse **uyumlu (yeşil)**, eşleşmezse
  **uyumsuz**. Kullanıcı `data/state/compliance_checks.json` dosyasına kendi
  kontrol paketini yazar (aynen `control_assignments.json` deseni), motor onu
  toplanmış kanıta uygular. Kanıt yoksa asla tahmini "uyumlu" yok.
- **Neden:** Bugün her kontrol Python koduyla yazılıyor. Bu, denetçinin/operatörün
  kod yazmadan kendi kontrolünü eklemesini sağlar.
- **Tür:** Büyük özellik / mimari (0.7.x VERIFY). Sunucu yok, yeni cihaz komutu
  yok. Sertifikasyon iddiası yok.
- **Sınır:** Kullanıcı serbest komut yazıp cihazda çalıştıramaz — o CE.2 (komut
  kapısı) ve CE.4 (yazma yetkisi, OP.2 seviyesinde kilitli).

---

## 1. What exists (post-0.7.2)

- `utils/compliance_catalog.py` — 24-entry hand-written declarative catalog
  (`CATALOG_VERSION "0.7.2"`); each entry names a Python `evaluator` string.
- `utils/compliance_evaluators_ext.py` — `ENRICHMENT_EVALUATORS: {id: callable}`;
  each callable reads `device["current_configuration"]["sections"]`.
- `utils/compliance_posture.py` —
  `build_compliance_posture(configuration_ui, project_plan=None, *, data_root=None)`.
  Subject loop: `resolved_ids = policy.resolve(device_name, vendor_key,
  applicable_ids)`; `_subject_controls` (frozen ten) + `_subject_extended_controls`
  (enrichment) → `subject["extended_controls"]`; `_compliance_overview(subjects)`
  rolls up over `controls + extended_controls`; `_assignment_policy_block(policy)`.
  `STATUS_VALUES = (PASS, FINDING, UNKNOWN, NOT_APPLICABLE, PLANNED, WAIVED)`.
- `utils/control_assignment.py` — `load_control_assignments(data_root) ->
  ControlAssignmentPolicy` (`data/state/control_assignments.json`, schema v1,
  fail-closed, unknown control id → error). Waiver / assignment id validation
  uses `all_subject_control_ids()`.
- `data/` is entirely gitignored; the repository privacy gate already reports
  `data/` presence and refuses a commit that carries it.

## 2. Change (additive)

### 2a. `utils/compliance_check_pack.py` (new) — the check pack loader

`load_compliance_checks(data_root: Path | None) -> CompliancePack`. Mirrors
`control_assignment.py`: `POLICY_RELATIVE_PATH = state/compliance_checks.json`,
`SUPPORTED_SCHEMA_VERSION = 1`, `CompliancePackError(RuntimeError)`.

```json
{ "version": 1, "pack_id": "securityexpert.user.local", "pack_version": "1", "enabled": true,
  "checks": [
    { "id": "x_ssh_no_cbc", "title": "SSH management offers no CBC ciphers",
      "rationale": "CBC-mode SSH ciphers are plaintext-recovery vulnerable.",
      "severity": "high", "mode": "enforced",
      "applies_to": { "vendor": ["check_point", "palo_alto"] },
      "frameworks": [ { "framework": "CIS", "reference": "2.1.11", "applies": true },
                      { "framework": "PCI-DSS", "reference": "2.2.5", "applies": true },
                      { "framework": "BDDK", "reference": "Sistem Sıkılaştırma", "applies": true } ],
      "evidence": {
        "combine": "all",
        "steps": [ { "source": "current_configuration.sections[id=management].settings",
                     "select": "value", "assert": { "op": "none_match", "pattern": "(?i)-cbc" } } ] },
      "verdict": { "on_pass": "PASS", "on_fail": "FINDING", "on_no_evidence": "UNKNOWN" } } ] }
```

**Validation (fail-closed — any failure → `CompliancePackError` before evaluation):**

- `version == 1`; `enabled` bool (default true; `false` → inert empty pack).
- `pack_id` / `pack_version` strings (defaults `securityexpert.user.local` / `1`).
- each `check.id` matches `^x_[a-z0-9_]+$` (**D1/D15** — builtin ids never start
  `x_`), unique within the pack.
- `title` / `rationale` non-empty strings; `severity` in
  `compliance_catalog.SEVERITY_VALUES`; `mode` in `{enforced, advisory}`
  (default `enforced`, **D6**).
- `applies_to` (optional) keys `vendor` / `platform_family` / `entity_type`,
  each a list of strings.
- `frameworks` (optional) — each `{framework in {CIS,PCI-DSS,BDDK}, reference
  non-empty, applies bool}`.
- `evidence.steps` non-empty list; `combine` in `{all, any}` (default `all`,
  **D5**). Each step: `source` (parsed + validated selector, **D13**),
  `select` (optional dotted key), `assert` `{op in the fixed set, …}` (**D2**).
- assertion ops: `present · absent · equals · not_equals · matches · not_match ·
  any_match · none_match · gte · lte · in · not_in · count_gte · count_lte`.
  Pattern ops require `pattern` (str, ≤ 512 chars, compiles, passes the
  complexity linter — **D3**). `equals`/`not_equals` require `value`;
  `gte`/`lte`/`count_gte`/`count_lte` require numeric `value`; `in`/`not_in`
  require `values` list.
- `verdict` (optional) `on_pass` / `on_fail` / `on_no_evidence` in
  `STATUS_VALUES` minus `WAIVED` (defaults `PASS` / `FINDING` / `UNKNOWN`).
- **`remediation` present → `CompliancePackError` "not supported in CE.1"**
  (**§11** — reserved for the write-capable future, cannot be smuggled in early).
- missing file → empty pack `source="missing"`, `enabled=False` (**D16** —
  byte-identical to today).

`CompliancePack.check_ids() -> frozenset[str]`. Selector grammar (**D13**,
hand-written tokenizer, no `eval`, no new dependency):
`NS ('.' SEGMENT)*` where `SEGMENT := KEY | KEY '[' KEY '=' VALUE ']'` and
`NS in {current_configuration, unified, crypto_facts, alignment}`.

### 2b. `utils/compliance_check_engine.py` (new) — the evaluator

Pure functions:

- `parse_selector(text) -> ParsedSelector` (raises `CompliancePackError`).
- `resolve_source(subject_evidence: dict, selector: ParsedSelector) -> Any`
  — walks the namespaced evidence; list segments with `[attr=value]` filter and
  flatten; unknown path → `None`.
- `apply_select(value, dotted_key) -> Any` — map a key over a list / drill a dict.
- `apply_assertion(value, assertion) -> bool | None` — `None` = inconclusive
  (no evidence to judge). Regex ops run behind a wall-clock timeout; a timeout
  or eval error → `None` (logged), **never** `True` (**D3**).
- `evaluate_check(subject_evidence, check) -> (status, summary, coverage)` —
  per step compute `True/False/None`; `combine == "all"` → `on_pass` only if all
  `True`, `on_fail` if any `False`, else `on_no_evidence`; `combine == "any"` →
  `on_pass` if any `True`, `on_fail` if all `False`, else `on_no_evidence`.
  `coverage` = `complete` / `partial` / `not_collected`.

`subject_evidence` (built in `compliance_posture`, in-process only):

| namespace | CE.1 source |
| --- | --- |
| `current_configuration` | `device["current_configuration"]` (the projection) |
| `unified` | `{ "device": <the config-UI device row: platform_family, model, sw_version, ha_role, entity_type, …>, "interfaces": [], "routes": [] }` (device row now; full inventory collections are a fast-follow) |
| `alignment` | `{ "results": device["alignment"]["findings"] }` |
| `crypto_facts` | `{}` in CE.1 (namespace reserved; a check using it → `on_no_evidence`). Wired when `build_compliance_posture` is given crypto facts — fast-follow, non-breaking. |

### 2c. `utils/compliance_posture.py` — wire the engine in

- `build_compliance_posture(...)`: `check_pack = load_compliance_checks(resolved_root)`
  (propagates `CompliancePackError` — fail-closed, same as the assignment policy).
  `policy = load_control_assignments(resolved_root, extra_known_ids=check_pack.check_ids())`.
- Per subject: `applicable_ids |= check_pack ids applicable to this vendor`;
  `resolved_ids = policy.resolve(...)` (so a user check can be de-scoped /
  waived like any control); `_subject_user_checks(device, check_pack, policy,
  device_name, resolved_ids, now)` appends to `subject["extended_controls"]`
  with `control_class: "user_check"`, `pack: {pack_id, pack_version, source}`,
  and `advisory: True` when `mode == "advisory"`. `applies_to` miss → omitted +
  counted in `assignment.not_assigned` (**D14**).
- **Advisory rows are excluded from scoring** (**D6**): a new `_scoring_rows()`
  filter drops `advisory` rows before subject-status derivation, the fleet
  `status_counts`, and `_compliance_overview` (`cells` / `by_framework` /
  `by_subject` / numerator / denominator). They still render.
- New top-level `check_packs: [{pack_id, pack_version, source, checks,
  advisory_checks, enabled}]` — **counts + the pack id only** (**D12**). Per-check
  result carries `severity` / `rationale` / `frameworks` / `evidence_summary` +
  `steps: [{observed, expected}]` for the Explain panel — **never** the raw
  `pattern` or selector string.
- `_empty_overview()` / the `available: False` return also carry
  `check_packs: []`.

### 2d. `utils/control_assignment.py`

`load_control_assignments(data_root, *, extra_known_ids: frozenset[str] =
frozenset())` — `known = all_subject_control_ids() | extra_known_ids`. Keyword-only,
default empty → byte-identical to today. Lets a waiver / include / exclude target
a user check id (**D7**).

### 2e. UI (`static/app.js`, `static/style.css`)

- `complianceControlCard`: when `control.control_class === "user_check"`, add a
  small **"user-defined"** badge (and its `pack.pack_id` in the Explain panel
  provenance line); when `control.advisory`, add an **"advisory"** badge and a
  muted card tone. No new card type — the 0.7.2 Explain panel + framework chips
  already render `severity` / `rationale` / `frameworks`.
- The framework filter (0.7.2) already filters by `control.frameworks` — user
  checks participate with no extra code.
- `complianceRenderableControls` / the roll-up read `advisory` from the payload,
  not recomputed client-side.

## 3. Files

- `utils/compliance_check_pack.py`, `utils/compliance_check_engine.py` — new.
- `utils/compliance_posture.py` — `_subject_user_checks`, `_scoring_rows`,
  `check_packs` block, engine wiring, `extra_known_ids` threading.
- `utils/control_assignment.py` — `extra_known_ids` kwarg.
- `static/app.js`, `static/style.css` — user-defined / advisory badges.
- `tests/test_phase0_7_3_compliance_check_engine.py` — new.
- `project/*`, `CURRENT_STATE.md`, `AI_HANDOVER.md`, `docs/history/INDEX.md`,
  `docs/history/handover/…` — on close.
- `docs/history/phase/0_7_3_COMPLIANCE_CHECK_ENGINE.md` — this contract (§10 impl
  record on close).

## 4. Acceptance

| AC | Covered by |
| --- | --- |
| AC-1 | Pack loader: a well-formed pack loads; every fail-closed rule raises `CompliancePackError` before any evaluation — bad `version`, non-`x_` id, duplicate id, unknown `source` namespace/segment, unknown `assert.op`, over-long / non-compiling / linter-failing `pattern`, bad `severity`/`mode`, malformed `frameworks`, **`remediation` present**. Missing file → empty inert pack. `enabled:false` → inert. |
| AC-2 | Selector + assertion engine: `resolve_source` walks `current_configuration.sections[id=…].settings` + `select:"value"` to a value list; `unified.device.<k>` and `alignment.results` resolve; unknown path → `None`. Each operator returns the right `True/False/None` incl. inconclusive on empty evidence. A pathological pattern is rejected at load; a slow match at eval → step `None`, never `PASS`. |
| AC-3 | `evaluate_check`: `combine:"all"` → PASS iff all steps `True`, FINDING on any `False`, else `on_no_evidence`; `combine:"any"` mirror. No-evidence → `on_no_evidence` (default `UNKNOWN`), **never PASS**. |
| AC-4 | `build_compliance_posture` with a sample pack: user checks appear in `subject["extended_controls"]` with `control_class:"user_check"` + `pack`; an `enforced` user check's PASS/FINDING flows into `compliance_overview` cells / `by_subject` / `by_framework`; an `advisory` user check renders but is **absent** from every `compliance_overview` count and does not change `subject.status` or the fleet `status_counts`. `applies_to` miss → omitted + in `assignment.not_assigned`. |
| AC-5 | Assignment/waiver reuse: a `control_assignments.json` `exclude`/`waiver` targeting `x_…` is accepted (no "unknown control id" error) and de-scopes / `WAIVED`s that user check; with no policy, all applicable user checks evaluate. |
| AC-6 | Privacy: `json.dumps(payload)` contains no `pattern` string, no selector string, no device name/IP; `check_packs` is counts + `pack_id` only; `--repository-privacy-check` PASS (0.7.3 adds no repo finding); `data/state/compliance_checks.json` is under the existing `data/` gitignore. No certification claim. |
| AC-7 | `--render-only` (`scripts/render_sample.py`) embeds and renders with a pack present and with none; user-check cards show the "user-defined" (and "advisory") badge; framework chips + Explain work on them; empty/`available:false` path carries `check_packs: []`. Full suite ≥ prior baseline (`483 passed`), delta explained. `test_phase0_6_6b` untouched. |

## 5. Validation

Synthetic fixtures + a sample `compliance_checks.json` written to `tmp_path`.
`py -m pytest -q -n auto --dist worksteal` + one `scripts/render_sample.py`.
**No network, no credentials, no real-environment gate** — the engine reads
already-collected evidence. On-hardware check-authoring folds into
`on_hardware_real_env_validation` (P0, laptop-blocked).

## 6. Definition of Done

AC-1…AC-7 pass; `--render-only` healthy; diff review confirms **no** new device
command, no collector / network / CAS / scheduler change, no change to
`subject["controls"]` or the 0.6.6B pack; `remediation` is validator-rejected;
the design-doc D-decisions are honoured; `compliance_check_engine` backlog note
updated; `compliance_engine` / `evidence_reporting` feature criteria advanced;
state files + `AI_HANDOVER.md` rewritten; `SESSION CLOSE` with the `main` merge
decision + non-interactive Git dispatch.

## 7. Rollback

Feature branch `feature/0-7-3-compliance-check-engine`. `compliance_check_pack.py`
+ `compliance_check_engine.py` are self-contained; the `compliance_posture`
wiring is one call site + one payload key + the advisory filter. Reverting the
wiring leaves 0.7.2 intact (no pack file → the loader already no-ops).

## 8. main.py / UI effect

With a `data/state/compliance_checks.json` present: the Compliance module shows
the user's checks as extra cards in the enrichment area, each with a
"user-defined" badge (and "advisory" where applicable); `enforced` checks move
the coverage roll-up, `advisory` ones do not. With no pack file: no visible
change from 0.7.2 (`check_packs: []`). No change to Network Inventory,
Configuration, Discovery, Project Plan. A malformed pack fails the run closed
(same as a malformed `control_assignments.json`).

---

## 10. Implementation record — `0.7.3` / CE.1 (2026-08-29, AUTOMATED_VALIDATED)

**Scope shipped (additive; decisions D1–D16 honoured):**

- **`utils/compliance_check_pack.py` (new)** — `load_compliance_checks(data_root)
  -> CompliancePack`. `CompliancePackError`; `POLICY_RELATIVE_PATH =
  state/compliance_checks.json`; schema v1. Fail-closed validation: `version`,
  `id` `^x_[a-z0-9_]+$` + uniqueness (D1/D15), `severity` in
  `SEVERITY_VALUES`, `mode` in `{enforced, advisory}` (D6), `applies_to`
  (`vendor` / `platform_family` / `entity_type` lists), `frameworks`
  (`{CIS,PCI-DSS,BDDK}` + non-empty reference + bool `applies`),
  `evidence.steps` non-empty, `combine` in `{all, any}` (D5), every `source`
  parsed by `parse_selector` (`NS('.'SEGMENT)*`, `SEGMENT := KEY | KEY
  '[' KEY '=' VALUE ']'`, `NS in {current_configuration, unified, crypto_facts,
  alignment}`, no `eval` / no dep — D13), every `assert.op` in `VALID_OPS`
  (D2), pattern ops: `pattern` ≤ 512 chars + compiles + `_lint_pattern`
  (rejects `(…[+*]…)[+*?]`, > 12 unbounded quantifiers, `.*.*`) (D3), numeric
  `value` for `gte`/`lte`/`count_*`, `values` list for `in`/`not_in`,
  `verdict` statuses in `STATUS_VALUES − {WAIVED}`. **`remediation` key present
  → `CompliancePackError`** (reserved for the write future — §11). Missing file
  → inert `source="missing"` pack (D16); `enabled:false` → `source="disabled"`.
- **`utils/compliance_check_engine.py` (new)** — pure functions.
  `resolve_source(evidence, selector)` — a path that can't be walked (missing
  key, or a `[attr=value]` filter matching nothing) → `None`, distinct from a
  genuinely empty list. `apply_select(value, dotted_key)`. `apply_assertion(value,
  step) -> bool | None` — `value is None` → `None` for **every** operator
  (a missing evidence section is never a pass or a fail-by-absence); an empty
  resolved list *is* judged (`present`→False, `absent`→True, `count_*`→0).
  Regex ops run behind a timeout (the `regex` module's `timeout=` when present,
  else stdlib `re` with a 20 000-char input cap); timeout / error → step
  `None`, never `PASS` (D3). `evaluate_check(...) -> (status, summary, coverage,
  step_details)` — `combine` `all` / `any`; no conclusive step →
  `on_no_evidence` (default `UNKNOWN`). `redacted_selector(source)` blanks
  `[attr=value]` filter values (D12).
- **`utils/compliance_posture.py`** — `check_pack = load_compliance_checks(...)`
  (propagates `CompliancePackError`, fail-closed); `policy =
  load_control_assignments(resolved_root, extra_known_ids=check_pack.check_ids())`;
  `_user_check_meta(pack)` (enforced only — advisory omitted from the roll-up,
  D6); `_check_pack_block(pack)` (counts + `pack_id` only, D12);
  `_subject_evidence(device)` (`current_configuration` full, `unified.device`
  row, `alignment.results` = `device["alignment"]["findings"]`, `crypto_facts`
  `{}` reserved — D4); `_subject_user_checks(...)` appends to
  `subject["extended_controls"]` with `control_class:"user_check"`,
  `advisory`, `pack`, `check_steps`, `evidence_fields` = **redacted** selectors;
  `applies_to` miss → omitted + in `assignment.not_assigned` (D14).
  `_scoring_rows()` drops advisory rows from subject-status derivation, the
  fleet `status_counts`, and `_compliance_overview` (which now takes
  `extra_meta` and extends `all_ids` / `by_framework` / `_control_severity`
  with the enforced user checks). New top-level `check_packs: []`.
- **`utils/control_assignment.py`** — `load_control_assignments(data_root, *,
  extra_known_ids=frozenset())`; `known = all_subject_control_ids() |
  extra_known_ids` (D7) — keyword-only, default empty → byte-identical to prior.
- **`static/app.js` + `static/style.css`** — `complianceControlCard` shows a
  "user-defined" badge on `control_class === "user_check"` and an "advisory"
  badge on `advisory === true`; the Explain panel adds a source-pack line and
  a per-step `expected` / `observed` line (`expected` for pattern ops is
  `"<op> (regex pattern, redacted)"`). No new CSS classes — reuses
  `.statuspill` + `.compliance-explain-row`.
- **Tests** — `tests/test_phase0_7_3_compliance_check_engine.py` (31). Frozen
  touch-ups: `test_phase0_7_1_compliance_assignment` +
  `test_phase0_6_6b_compliance_rulepack` (`check_packs` added to the allowed
  additive top-level key set, same precedent as `compliance_overview` /
  `assignment_policy` in 0.7.1b).

**Evidence (2026-08-29):**

- `py -m pytest -q -n auto --dist worksteal` → **514 passed, 3 skipped,
  0 failed** (Python 3.12). Prior baseline 483 → +31 (new file).
- `scripts/render_sample.py` → exit 0, 0 placeholders; with no pack file the
  Compliance module renders exactly as 0.7.2 (`check_packs: []`).
- Repository privacy gate → **PASS / 0** on a clean tree. `json.dumps(payload)`
  carries no raw `pattern`, no raw selector filter value, no device name / IP;
  `check_packs` is `{pack_id, pack_version, source, enabled, checks,
  advisory_checks}` only.

**AC status:** AC-1…AC-7 met.

**Deferred (fast-follows / later phases):**

- `crypto_facts` and full `unified.interfaces` / `unified.routes` resolvers —
  the namespaces parse and are reserved; they resolve empty in CE.1 (a check
  using them → `on_no_evidence`). Wire when `build_compliance_posture` is given
  crypto facts / the merged inventory row (non-breaking optional param).
- `CE.2` (`compliance_check_engine_primitives`), `CE.3`
  (`compliance_check_engine_ui`), `CE.4` (`compliance_remediation_checks`) — see
  the design doc and backlog.
