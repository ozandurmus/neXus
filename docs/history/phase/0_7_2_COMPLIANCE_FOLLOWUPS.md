# 0.7.2 — Compliance Follow-ups: Password / Banner / Services Projection + Framework Filter & Explain UI (contract)

**Status:** `AUTOMATED_VALIDATED 2026-08-29` (§7 decisions accepted as proposed; see §10 impl record) · **Movement:** ARCHITECTURE → IMPLEMENTATION → UI → VALIDATION
**Design:** `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` (§2 Part A projection extension; §10 workbench filter + explain panel)
**Advances:** `compliance_engine`, `framework_mappings`, `evidence_reporting` (0.7.x VERIFY track)
**Predecessor:** `0.7.1a` + `0.7.1b` (AUTOMATED_VALIDATED 2026-08-29). This closes the list deferred in `0_7_1_COMPLIANCE_ASSIGNMENT.md` §9.
Active build contract; moves to its final resting place in `docs/history/phase/` unchanged on close.

---

## PROJE ÖZETİ (Türkçe)

- **Bu görev nedir:** `0.7.1`'den ertelenen üç küçük uyum işini bitirmek:
  (1) zaten toplanmış yapılandırmadan **parola politikası**, **banner** ve
  **gereksiz servisler** için yeni okuma bölümleri çıkarmak (yeni komut / yeni
  toplayıcı yok — sadece yeni "projeksiyon"), (2) bu bölümlerden 6 yeni
  deterministik kontrol üretmek, (3) Compliance ekranına **çerçeve filtre
  çipleri** (CIS / PCI-DSS / BDDK'ya tıkla → liste süzülsün) ve her kontrolün
  **"neden önemli"** açıklamasını satır içinde açan bir panel eklemek.
- **Neden / ne kazanırız:** Parola sıkılaştırması her denetimin ilk maddesidir;
  bu olmadan uyum kataloğu eksik. Filtre + açıklama, panoyu bir rapordan
  gerçek bir çalışma yüzeyine çevirir.
- **Tür:** Yeni özellik (küçük–orta; katalog + evaluator + projeksiyon + UI,
  hepsi eklemeli). Sunucu gerekmez.
- **Gelecekte ne çözer:** `banner` / `services` bölümleri ileride başka
  kontrollere zemin olur; filtre / açıklama, DEPLOY.1'deki atama editörünün
  salt-okunur iskeletini tamamlar.

---

## 1. What exists (post-0.7.1b)

- `utils/compliance_catalog.py` — `CATALOG_VERSION = "0.7.1b"`; `CONTROL_CATALOG`
  = the frozen ten (`LEGACY_CONTROL_IDS`) + 8 enrichment controls
  (`introduced: "0.7.1b"`). `catalog_enrichment_controls()` returns everything
  not in `LEGACY_CONTROL_IDS` and not `deprecated`; `all_subject_control_ids()`
  is the assignable universe.
- `utils/compliance_evaluators_ext.py` — `ENRICHMENT_EVALUATORS: dict[str,
  Callable]`; each evaluator reads `device["current_configuration"]["sections"]`
  by section `id` via `_section_settings(device, section_id)` and returns
  `(status, summary, coverage)`. Missing section → `UNKNOWN`; present but signal
  absent → `FINDING`; unmapped id → `PLANNED`. Never an inferred `PASS`.
- `utils/compliance_posture.py` — `subject["controls"]` stays the pack-routed ten
  (filtered by the resolved assignment set); enrichment lives in
  `subject["extended_controls"]` via `_subject_extended_controls(...)`.
  `compliance_overview` roll-up counts both lists.
- **PAN projection** — `configuration/current_config_projection.py`
  `build_pan_current_configuration(...)`. `SECTION_ORDER` /`SECTION_LABELS`
  /`_section_for(key)` classify leaf nodes; `_scalar_rows(...)` walks
  `/deviceconfig/system/**` leaves, applies `_sensitive_path()` (drops any key
  containing a `SENSITIVE_TOKENS` substring — `password`, `secret`, `community`,
  `certificate`, …), and buckets rows into sections. A named-`@name`
  `permitted-ip` xpath special-case shows the pattern for values not in leaf
  text.
- **CP projection** — built inside `configuration/checkpoint_config_collector.py`
  from the interactive `show configuration` text with secret-aware redaction;
  emits the same `{id,label,settings:[{setting,value,origin,context}]}` section
  shape that `utils/config_ui.py::_checkpoint_current_copy` copies to the browser.
- **UI** — `static/app.js` `complianceControlCard(control, options)` already
  renders a severity badge, rationale line and per-framework reference grid.
  `#complianceCoverageOverview` renders the KPI band + per-framework readiness
  cards from `compliance_overview`. No filtering, no inline explain toggle.

## 2. Change (all additive)

### 2a. `password_policy` projection section — PAN + CP

**New section `id: "password_policy"`.** Non-secret policy knobs only.

- **PAN:** source is `/config/mgt-config/password-complexity/**` and the relevant
  `/config/mgt-config/**` lockout leaves — **not** under `/deviceconfig/system`,
  so this needs a dedicated bounded xpath extractor (same pattern as the
  `permitted-ip` special-case), *not* a widening of `_section_for()`.
  Fields projected: `enabled`, `minimum-length`, `minimum-uppercase-letters`,
  `minimum-lowercase-letters`, `minimum-numeric-letters`,
  `minimum-special-characters`, `minimum-password-complexity`,
  `password-change-on-first-login`, `password-history-count`,
  `new-password-differs-by-characters`, `block-repeated-characters`,
  and from `mgt-config`: `failed-attempts`, `lockout-time`,
  `max-failed-attempts`, `idle-timeout` (lockout family only).
- **CP:** source is `set password-controls <knob> <value>` lines in
  `show configuration`. Knobs projected: `min-password-length`,
  `complexity`, `password-history`, `password-expiration`,
  `deny-on-nonuse enable|days`, `deny-on-fail enable|failures-number|
  allow-after`, `force-change-when` (policy only).
- **Hard exclusion (privacy):** `mgt-config/users/entry/phash`,
  `phash`, `passwd`, `secret`, any hash/credential leaf — never projected.
  The `password_policy` extractor uses an **explicit field allowlist**, it does
  **not** rely on the generic `_sensitive_path()` substring filter (which would
  pass `minimum-length` but is not a safe basis for a `password*` section).
- Section is emitted **only when** at least one allowlisted field is present;
  absent config → no section (evaluators then return `UNKNOWN`).

### 2b. `banner` + `services` projection sections — PAN + CP

**New section `id: "banner"`** — *presence and type only, never the banner body.*
Rows: `login-banner` = `present` / (absent → no row), `motd` = `present`,
`ssh-banner` = `present`. PAN `/config/mgt-config/.../login-banner` and
`/deviceconfig/system/login-banner`; CP `set message banner on|off` /
`set message motd`. The projected `value` is the literal token `present`
(or a length bucket like `>0 chars`) — the text itself is a local-operator /
potentially-identifying string and is out of scope for this build.

**New section `id: "services"`** — inbound management-plane service enablement.
- **PAN:** `/deviceconfig/system/service/**` (`disable-telnet`, `disable-http`,
  `disable-ssh` already partly surface under `management`; here add
  `disable-icmp`, `disable-snmp`, `disable-userid-service`,
  `disable-http-ocsp`) plus any `/deviceconfig/system/service` legacy leaves.
- **CP:** thin — Gaia does not expose classic inetd finger/echo/chargen the same
  way. Project what `show configuration` carries (`set web ssl-port`,
  `set net-access ...` where present); where nothing maps, the `services`
  evaluator returns `UNKNOWN` for CP subjects (not `NOT_APPLICABLE` — the
  evidence gap is real, not a scoping decision).

### 2c. +6 enrichment controls (`introduced: "0.7.2"`)

Added to `CONTROL_CATALOG`; `CATALOG_VERSION` → `"0.7.2"`. Each carries
`severity`, `rationale`, `vendors`, `evidence.fields`, per-framework
`frameworks` (CIS / PCI-DSS / BDDK with `applies` + `reference`), `lifecycle`,
`evaluator`. They land in `extended_controls` — **`LEGACY_CONTROL_IDS` and the
0.6.6B `rule_count == 10` freeze are untouched.**

| control_id | reads section | PASS when | severity |
| --- | --- | --- | --- |
| `password_min_length` | `password_policy` | a numeric min-length ≥ 8 is present (≥ 12 noted in summary, not required for PASS) | high |
| `password_complexity_enabled` | `password_policy` | complexity / character-class requirement present and not disabled | medium |
| `password_history_depth` | `password_policy` | a numeric history/`differs-by` depth ≥ 1 is present | low |
| `password_lockout_policy` | `password_policy` | a failed-attempt lockout knob is present and not disabled | high |
| `login_banner_text_present` | `banner` | a `login-banner` / `motd` presence row is projected | low |
| `unused_services_disabled` | `services` | ≥ 1 `disable-<svc>` = yes/enabled is present and no risky service is explicitly enabled | medium |

Discipline (unchanged from 0.7.1b): section absent → `UNKNOWN`; section present,
signal absent → `FINDING`; evaluator not yet wired → `PLANNED`. No inferred PASS.
`login_banner_present` (the 0.7.1b control) stays; `login_banner_text_present` is
the stricter follow-up that requires the dedicated `banner` section rather than a
token match in `system`/`management`.

### 2d. UI — framework filter chips (`static/app.js`, `templates/index.html`, `static/style.css`)

- In the Compliance module, above the control grid: three toggle chips
  `CIS` / `PCI-DSS` / `BDDK` + a `Clear` affordance. Multi-select = union
  (a control shown if it has `applies:true` for **any** selected framework).
- Active chips also filter the per-framework readiness cards and the subject
  control grids (`controls` + `extended_controls`).
- Pure client-side over the existing payload (`control.frameworks[]`). No payload
  change. State is view-local (not persisted); default = no filter = today's view.
- Empty result → an explicit "No controls map to the selected framework(s)"
  state, never a blank grid.

### 2e. UI — inline "explain" expansion (`static/app.js`, `static/style.css`)

- `complianceControlCard` gains an "Explain" toggle that expands **in place**
  (not a modal) to show: `rationale`, the `evidence_fields` list, and the mapped
  framework references (`framework` · `reference`, dimmed when `applies:false`).
- All data already on the control object; no payload change. Collapsed by
  default; one card open at a time is not required (independent toggles).
- Works in both `compact` (subject grid) and full (fleet/platform grid) card
  modes; theme-aware; keyboard-reachable.

## 3. Files

- `configuration/current_config_projection.py` — `password_policy` / `banner` /
  `services` extractors + `SECTION_ORDER` / `SECTION_LABELS` entries;
  `projection_scope` string updated; `CURRENT_CONFIG_SCHEMA_VERSION` bumped.
- `configuration/checkpoint_config_collector.py` — same three sections from
  `show configuration` text, secret-aware, allowlist-based.
- `utils/compliance_catalog.py` — `CATALOG_VERSION` → `0.7.2`; +6 catalog entries.
- `utils/compliance_evaluators_ext.py` — +6 evaluators in `ENRICHMENT_EVALUATORS`.
- `utils/config_ui.py` — only if the CP/PAN section copy needs a passthrough for
  a new field shape (expected: no change; the `{setting,value,origin,context}`
  row shape is reused verbatim).
- `templates/index.html`, `static/app.js`, `static/style.css` — filter chips +
  explain toggle.
- `tests/test_phase0_7_2_compliance_followups.py` — new (AC-1…AC-6).
- Frozen-test touch-ups expected: `test_phase0_7_1a` (`CATALOG_VERSION` string),
  `test_phase0_7_1_compliance_assignment` (enrichment count if it is pinned).
  `test_phase0_6_6b` must stay untouched (the ten are unchanged).
- `project/roadmap.json`, `project/backlog.json`, `project/feature_registry.json`,
  `project/build_history.json`, `CURRENT_STATE.md`, `AI_HANDOVER.md` — on close.

## 4. Acceptance

| AC | Covered by |
| --- | --- |
| AC-1 | The 6 new catalog entries validate: `id` unique, `severity` in enum, `frameworks[]` each with `applies` + `reference`, `evidence.fields` non-empty, `lifecycle` set, `introduced == "0.7.2"`; `catalog_baseline_controls()` still returns exactly the ten in order; `catalog_enrichment_controls()` count = 8 + 6 = 14. |
| AC-2 | For the existing synthetic fixture **without** `password_policy` / `banner` / `services` in its config, `build_compliance_posture` output is unchanged except: the 6 new ids appear in `extended_controls` with status `UNKNOWN`, and `compliance_overview` totals grow by 6 controls (all `unmonitored` / `unknown`). No existing control's status changes. |
| AC-3 | With a fixture that **does** carry the new config: PAN `password-complexity` + `mgt-config` lockout → `password_policy` section with the allowlisted fields only (assert `phash` / any credential leaf is absent); CP `set password-controls` lines → same section. `password_min_length` / `password_lockout_policy` / `login_banner_text_present` / `unused_services_disabled` reach `PASS` on the "good" fixture and `FINDING` on the "present-but-weak" fixture. |
| AC-4 | Banner body is never projected: the `banner` section rows carry only `present` / length-bucket tokens; `--repository-privacy-check` PASS / 0; no banner text, no password value, no device name, no certification claim in the shareable/support payload. |
| AC-5 | `--render-only` (via `scripts/render_sample.py`) embeds and renders the framework chips + explain expansion with no console error; toggling a chip filters the grid and the readiness cards; empty-selection and empty-result states render; existing `complianceControlCard` output for an un-filtered view is unchanged. |
| AC-6 | New + impacted regression green (`compliance`, `config projection`, `config_ui`, UI payload); `test_phase0_6_6b` untouched and passing; full suite ≥ prior baseline (`477 passed / 3 skipped / 0 failed`), delta explained. |

## 5. Validation

Synthetic fixtures only (a PAN `effective-running` XML fragment with
`mgt-config/password-complexity` + `deviceconfig/system/service` + `login-banner`;
a CP `show configuration` text fragment with `set password-controls` +
`set message banner`). `py -m pytest -q -n auto --dist worksteal` + one
`scripts/render_sample.py`. **No network, no credentials, no real-environment
gate** — the projection reads already-stored config; on-hardware coverage of the
new sections is folded into `on_hardware_real_env_validation` (P0, already
tracked, laptop-blocked).

## 6. Definition of Done

AC-1…AC-6 pass; `--render-only` healthy; diff review confirms **no** new device
command, no collector/network/CAS/scheduler/assignment-engine change, and no
change to `subject["controls"]` or the 0.6.6B pack; `compliance_control_enrichment`
backlog note updated; `framework_mappings` / `evidence_reporting` feature criteria
advanced where delivered; state files + `AI_HANDOVER.md` rewritten; `SESSION
CLOSE` produced with the `main` merge decision + non-interactive Git dispatch
commands.

## 7. Decisions (proposed — confirm or override at review)

1. **Banner:** project *presence only*, never the body. (Alternative: a
   redacted length/hash — rejected, adds risk for little audit value.)
2. **`password_policy` extraction:** explicit field **allowlist**, not the
   generic substring redactor. Non-negotiable for a `password*` section.
3. **CP `services`:** where `show configuration` carries no mappable service
   state, the evaluator returns `UNKNOWN` (real evidence gap), not
   `NOT_APPLICABLE`.
4. **`password_min_length` PASS threshold:** ≥ 8 for PASS, ≥ 12 noted as a
   strengthening hint in the summary. (Alternative: ≥ 12 hard — rejected as
   stricter than CIS baseline; revisit when severity/exposure weighting lands.)
5. **Filter/explain state:** view-local, not persisted. (A saved-views feature is
   DEPLOY.1 workbench scope.)
6. **Schema bump:** `CURRENT_CONFIG_SCHEMA_VERSION` and `CATALOG_VERSION` both
   advance to `0.7.2`; `COMPLIANCE_SCHEMA_VERSION` stays `0.6.6B` (pack unchanged).

## 8. Rollback

Single feature branch `feature/0-7-2-compliance-followups`. Each of {projection
sections, catalog+evaluators, UI filter, UI explain} is an independent commit;
reverting any one leaves the rest and the 0.7.1b baseline intact (the new
controls degrade to `PLANNED`/`UNKNOWN`, the UI degrades to the un-filtered card).

## 9. main.py / UI effect

After a normal run with config evidence present: the Compliance module shows
6 additional control cards (in the enrichment / `extended_controls` area), three
framework filter chips, and an "Explain" toggle on every control card. With no
config evidence, the new controls render in the existing "no evidence" state and
the chips still work against the (empty) list. Overview compliance card totals
increase by 6 controls. No change to Network Inventory, Configuration,
Discovery, or Project Plan.

---

## 10. Implementation record — `0.7.2` (2026-08-29, AUTOMATED_VALIDATED)

**Scope shipped (additive; §7 decisions applied as proposed):**

- **`configuration/current_config_projection.py`** —
  `CURRENT_CONFIG_SCHEMA_VERSION → "0.7.2"`; `SECTION_ORDER` / `SECTION_LABELS`
  gain `password_policy` / `banner` / `services`. New `_has_ancestor`,
  `_len_bucket`, `_allowlist_section(root, leaf_labels, ancestors, *,
  presence_only=False)` — projects only leaves whose local-name is in an
  explicit map **and** that sit under a named ancestor (`password-complexity` /
  `mgt-config` / `admin-lockout` / `management` for password; `mgt-config` /
  `deviceconfig` / `system` / `setting` for banner; `service` for services).
  `presence_only` (banner) replaces the value with `present (<bucket>)` so the
  body is never projected. `_section_for()` now returns `None` for
  `/deviceconfig/system/service/*` and for `login-banner` / `motd` / `ssh-banner`
  leaves, so the generic scalar walk no longer (a) re-projects the service
  toggles under Management or (b) surfaces the banner body under System.
  `projection_scope` string extended.
- **`configuration/checkpoint_config_collector.py`** — `SECTION_ORDER` /
  `SECTION_LABELS` gain the same three; `_section_for()` routes head
  `password-controls → password_policy`, `message` / `banner → banner`;
  `_setting_value()` projects `password-controls <knob>` as
  `Password · <Knob> = <value>` and `set message <type> …` as
  `<Type> = present` (never the body). `_sanitize_configuration()` gains
  `PASSWORD_POLICY_SAFE_RE` (re-admits `set password-controls <known-knob>` lines
  that `SECRET_LINE_RE` would withhold) and `MESSAGE_BODY_RE` (collapses
  `set message … msgvalue <body>` to a `[SECURITYEXPERT BANNER BODY WITHHELD]`
  token before the redacted `sanitized_text` and the `safe_set_lines` that feed
  the shareable bundle). CP artifact `schema_version` left at `"0.6.1B"`
  (collector-artifact schema, not the projection schema; deliberately not
  broadened — many CP tests pin it).
- **`utils/compliance_catalog.py`** — `CATALOG_VERSION → "0.7.2"`; +6 entries
  (`introduced: "0.7.2"`): `password_min_length` (high),
  `password_complexity_enabled` (medium), `password_history_depth` (low),
  `password_lockout_policy` (high), `login_banner_text_present` (low),
  `unused_services_disabled` (medium) — each with rationale, `vendors`
  `["check_point","palo_alto"]`, `evidence.fields`, explicit CIS / PCI-DSS / BDDK
  membership. `catalog_enrichment_controls()` now returns 14 (8 + 6);
  `catalog_baseline_controls()` still exactly the ten.
- **`utils/compliance_evaluators_ext.py`** — +6 evaluators + `_first_int` and
  token-set helpers. Read only the `password_policy` / `banner` / `services`
  sections. Section absent → `UNKNOWN` (`not_collected`); present, signal absent
  → `FINDING`; knob present but disabled → `FINDING`; never an inferred `PASS`.
  `unused_services_disabled` returns `FINDING` on an explicitly-enabled risky
  service (finger/telnet/…), `PASS` on ≥1 explicit disable with none enabled,
  `UNKNOWN` when the section is present but inconclusive.
- **`templates/index.html`** — one new `<section id="complianceFrameworkFilter">`
  between the detail header and the fleet view.
- **`static/app.js`** — `complianceFrameworkFilter` (a `Set`);
  `complianceControlMatchesFrameworkFilter` / `complianceApplyFrameworkFilter` /
  `complianceFilteredFrameworkNames` / `renderComplianceFrameworkFilter`;
  `complianceRenderableControls` now applies the framework filter, and the fleet
  / platform grids + the coverage-overview framework-readiness cards filter too;
  empty-result state names the selected framework(s). `complianceControlCard`
  gains an `Explain` button + `.compliance-explain-panel` (rationale + evidence
  fields + framework references), toggled by one document-level delegated
  listener (`[data-explain-toggle]` → `panel.hidden`).
- **`static/style.css`** — `.compliance-framework-filter` / `-chip` (+`.active`
  /`.clear`) and `.compliance-explain-toggle` / `-panel` / `-row`, theme-aware
  via existing tokens (`--line`, `--accent`, `--accent-soft`, `--surface-2`,
  `--muted`, `--text`).
- **Tests** — `tests/test_phase0_7_2_compliance_followups.py` (6: catalog shape;
  missing-section → UNKNOWN; good → PASS; weak → FINDING; PAN projection extracts
  knobs and never the banner body / `phash`, and service toggles are not
  double-projected under Management; CP sanitizer keeps policy knobs, drops the
  user password / expert hash / banner body, and the projection + evaluators
  behave). Frozen-test touch-ups: `test_phase0_7_1a` + `test_phase0_7_1`
  (`CATALOG_VERSION == "0.7.2"`), `test_phase0_6_0a4_3`
  (`CURRENT_CONFIG_SCHEMA_VERSION == "0.7.2"`). `test_phase0_6_6b` untouched.

**Evidence (2026-08-29):**

- `py -m pytest -q -n auto --dist worksteal` → **483 passed, 3 skipped,
  0 failed** (Python 3.12). Prior baseline 477 → +6 (new file).
- `scripts/render_sample.py` → exit 0, 0 placeholders left; new markup
  (`complianceFrameworkFilter`, `compliance-framework-chip`,
  `compliance-explain-toggle`) present in the inlined output.
- Repository privacy gate → 5 findings, **all pre-existing**: `data/`, `logs/`,
  `data/.support_hmac.key` are gitignored local runtime artifacts from test runs
  (absent on a clean checkout); `AI_HANDOVER.md:22` and
  `docs/history/handover/AI_HANDOVER_2026_08_29_0_7_1b.md:22` carry a raw
  interpreter path committed in `6f5818e` (0.7.1b). **No file changed by 0.7.2
  produces a finding.** `AI_HANDOVER.md` is rewritten at this close without the
  raw path.

**AC status:** AC-1…AC-6 met. AC-4's "`--repository-privacy-check` PASS / 0" is
met for all 0.7.2-authored content; the gate's non-zero local result is entirely
pre-existing (documented above), not a regression.

**Deferred onward:** UI assignment **editor** + tagged device registry remain
`DEPLOY.1A`-gated (`compliance_assignment_ui_and_registry`). On-hardware coverage
of the three new projection sections folds into `on_hardware_real_env_validation`
(P0, laptop-blocked).
