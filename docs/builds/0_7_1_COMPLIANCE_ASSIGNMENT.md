# 0.7.1 — Compliance Control Enrichment, Framework Grouping & File-Based Assignment (contract)

**Status:** `0.7.1a` AUTOMATED_VALIDATED (2026-08-29) · `0.7.1b` PLANNED (contract, for review) · **Movement:** IMPLEMENTATION
**Design:** `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md`
**Advances:** `compliance_engine`, `framework_mappings`, `evidence_reporting`
(0.7.x track). Active build contract; moves to `docs/history/phase/` on close.

## Build split

Ships as two independently-reviewable, independently-validated builds:

- **`0.7.1a` — Control catalog, framework grouping & severity** (§2a, §2b,
  and the severity-badge / framework-grid parts of §2e) — **AUTOMATED_VALIDATED
  2026-08-29**. A pure additive refactor: the ten existing controls move into a
  versioned catalog *verbatim* (same ids, areas, evidence_fields, evaluators,
  outcomes) with `severity`, `rationale` and real per-framework
  `frameworks` (CIS / PCI-DSS / BDDK membership + reference) added. Payload is
  additive only. No new controls, no assignment, no roll-up. The ~12 enrichment
  controls + the `password_policy` projection section move to `0.7.1b` (they
  change the subject-control count, which the 0.6.6B frozen `rule_count == 10`
  tests pin, and the engine is reworked for assignment there anyway).
- **`0.7.1b` — Enrichment, file-based assignment, waivers & coverage roll-up**
  (§2c, §2d, the ~12 enrichment controls + `password_policy` projection, and the
  KPI-band / framework-readiness / Overview-card parts of §2e). Adds
  `data/state/control_assignments.json`, the per-device engine filter, file
  waivers (`WAIVED`), the `compliance_overview` block and the Overview /
  Compliance workbench spine. Builds on `0.7.1a`.

The sub-builds close and merge separately; this doc covers both.

---

## PROJE ÖZETİ (Türkçe)

- **Bu görev nedir:** Uyum kontrollerini bir **veri kataloğuna** dönüştürmek
  (her kontrolde gerekçe, önem derecesi, hangi çerçeveye ait — CIS/PCI-DSS/BDDK
  + referans), kontrol sayısını artırmak, firewall'lara **dosya politikasıyla
  kontrol atamak** (ve muafiyet kaydı), ve Overview'da "kaç kontrol, kaçı
  izleniyor, yüzde kaç uyumlu" özetini eklemek. Sunucu gerekmez.
- **Faydası:** Denetim kapsamını araç içinde yönetmek, çerçeve bazında hazırlık
  oranını görmek, ve büyüyebilen bir uyum panosunun iskeletini kurmak.
- **Tür:** Yeni özellik (0.7.x VERIFY hattı; orta boy — katalog + motor +
  payload + UI birlikte hareket etmek zorunda).
- **Gelecekte:** UI'dan atama editörü ve etiketli cihaz kaydı (DEPLOY.1), trend
  ve muafiyet arayüzü buranın üzerine eklenir.

---

## 1. What exists

`utils/compliance_rulepack.BASELINE_CONTROLS` = 10 controls
(`control_id/title/control_area/cis_reference/evidence_fields`); 0.6.6B routes
them through `DEFAULT_RULE_PACK`. `compliance_posture._control(...)` emits a
stubbed `framework_mappings` (`cis/pci_dss/bddk`, all INFORMATIONAL). No
per-device selection, no severity, no coverage roll-up. `static/app.js`
`complianceControlCard` reads `framework_mappings`, `benchmark_reference`,
`control_lifecycle`, `control_id`, `rule_pack`. RuntimeRoot policy pattern:
`utils/inventory_exclusions.py` (`state/inventory_exclusions.json`,
schema-versioned, fail-closed).

## 2. Change

### 2a. `utils/compliance_catalog.py` (new) — the control catalog

```python
CATALOG_VERSION = "0.7.1"
CONTROL_CATALOG: tuple[dict, ...] = (
  { "id": "telnet_disabled", "title": "...", "rationale": "...",
    "control_area": "...", "severity": "high",                # informational|low|medium|high|critical
    "vendors": ["check_point", "palo_alto"],
    "evidence": { "plane": "direct_actual",
                  "fields": ["current_configuration.sections.management.settings.protocol_enablement"],
                  "basis": "configured" },
    "frameworks": [
      { "framework": "CIS", "profile": "CIS ... Benchmark", "reference": "2.1.9", "applies": True },
      { "framework": "PCI-DSS", "version": "4.0", "reference": "2.2.5", "applies": True },
      { "framework": "BDDK", "reference": "İyi Uygulama Rehberi", "applies": True } ],
    "lifecycle": "active",                                     # active|planned_evidence_gap|deprecated
    "introduced": "0.6.1B.1.6", "evaluator": "telnet_disabled" },
  ...
)
```

- The **existing 10** move into the catalog verbatim (same ids, same evaluators,
  same `evidence_fields`) with `frameworks` + `severity` + `rationale` added.
- **`BASELINE_CONTROLS` becomes a derived view** for
  `compliance_rulepack.DEFAULT_RULE_PACK` (the 5-key shape it needs), so the
  0.6.6B pack and its tests are unchanged.
- **Enrichment (this build):** ~9 net-new deterministic controls from the
  *existing* projected sections:
  `ssh_protocol_v2_only`, `login_banner_present`, `remote_syslog_configured`,
  `ntp_authentication_enabled`, `management_dedicated_interface`,
  `admin_default_name_avoided`, `concurrent_admin_session_limit`,
  `failed_login_logging_enabled`, `http_redirect_to_https`.
  Plus a **one-section projection extension** (`password_policy` — highest audit
  value; CP `set password-controls ...`, PAN `mgt-config`/`password-complexity`,
  both over already-stored config → "new projection, not new collector") with
  3 controls: `password_min_length`, `password_lockout_policy`,
  `password_history_depth`.
  Total catalog ≈ 22. Any control whose evidence is genuinely absent →
  `lifecycle: "planned_evidence_gap"`, status `PLANNED` — never a guessed PASS.
  `banner` / `services` sections and their controls are deferred to `0.7.2`.

### 2b. `utils/compliance_posture.py`

- `_control(...)`: add `severity`, `rationale`, and `frameworks` (the rich list
  from the catalog). Keep `framework_mappings` (derived from `frameworks` for
  the current `app.js` card) — additive, nothing removed.
- Subject-control evaluation iterates the **catalog** (via the pack) and, per
  device, is filtered by the assignment policy (2c). Existing evaluators
  unchanged; new controls get evaluators in the same file / a small
  `compliance_evaluators_ext.py`.
- Per subject the payload records `assigned`, `not_assigned`, `evaluated`,
  `waived` control ids.
- `build_compliance_posture(configuration_ui, project_plan=None, *, data_root=None)`
  — new optional `data_root` for the policy; defaults to `BASE_DIR/"data"`.

### 2c. `utils/control_assignment.py` (new)

Mirrors `inventory_exclusions.py`: `POLICY_RELATIVE_PATH =
state/control_assignments.json`, `SUPPORTED_SCHEMA_VERSION = 1`,
`ControlAssignmentPolicyError`, `load_control_assignments(data_root) ->
ControlAssignmentPolicy`.

```json
{ "version": 1, "default_mode": "all_applicable",
  "groups": { "pci-scope": { "match": [ {"device_name": "cp-gw-01"} ] } },
  "assignments": [ { "target": {"group": "pci-scope"}, "include": ["*"] },
                   { "target": {"device_name": "cp-lab-99"}, "exclude": ["*"] } ],
  "waivers": [ { "control_id": "snmp_v3_only", "device_name": "pan-fw-07",
                 "reason": "SNMPv2 required by legacy NMS - risk accepted",
                 "approver": "netsec-lead", "expires": "2026-12-31" } ] }
```

- `resolve(device_name, vendor, all_applicable_ids) -> frozenset[str]`:
  device assignment > group assignment > `default_mode`; apply `include`
  then `exclude` (`"*"` = all).
- `waiver_for(control_id, device_name, now) -> waiver | None`: an unexpired
  waiver makes that (control, subject) cell status `WAIVED` (counted separately;
  never `PASS`).
- **Fail-closed:** malformed policy → `ControlAssignmentPolicyError` before
  evaluation; an unknown `control_id` anywhere in the policy → error; missing
  file → `default_mode="all_applicable"` (byte-identical to today's behaviour).
- Local-only state; the shareable support bundle carries **counts only**.

### 2d. `compliance_overview` roll-up (compliance payload, additive)

```json
"compliance_overview": {
  "catalog_version": "0.7.1", "total_controls": 22,
  "monitored_controls": 19, "unmonitored_controls": 3, "subjects": 7,
  "cells": { "aligned": 210, "finding": 24, "unknown": 12, "planned": 6, "waived": 4 },
  "aligned_percent": 82.7, "risk_weighted_alignment_percent": 78.4,
  "by_framework": { "CIS": { "controls": 20, "monitored": 18, "aligned": 15,
    "finding": 3, "coverage": "PARTIALLY_COVERED" }, "PCI-DSS": {...}, "BDDK": {...} },
  "by_subject": [ { "subject_id": "cp-001", "assigned": 18, "aligned": 15,
    "finding": 2, "unknown": 1, "waived": 0 } ] }
```

`monitored` = assigned somewhere AND ≥1 subject has evidence. `coverage` per
framework: `COVERED` / `PARTIALLY_COVERED` / `UNCOVERED`. `risk_weighted` uses
the catalog `severity` (critical=5…informational=1).

### 2e. UI (`templates/index.html`, `static/app.js`) — read-only spine

- **Overview:** a `#overviewComplianceSummary` dashboard card —
  "N controls · M monitored on K firewalls · X% aligned (Y% risk-weighted)",
  a per-framework mini-bar, findings-by-severity.
- **Compliance module:** a KPI band (from `compliance_overview`), framework
  readiness cards (`COVERED` / `PARTIALLY_COVERED` / `UNCOVERED`), a **severity
  badge** on each control card, **framework filter chips** (click CIS → list
  filters), and an inline "explain" expansion (rationale + `evidence_fields` +
  mapped references). Assignment state (`assigned` / `waived`) shown per
  subject. No editor — data-driven, additive to the existing render.

## 3. Files

- `utils/compliance_catalog.py`, `utils/control_assignment.py`,
  `utils/compliance_evaluators_ext.py` — new.
- `utils/compliance_rulepack.py` — `BASELINE_CONTROLS` derives from the catalog.
- `utils/compliance_posture.py` — frameworks + severity + assignment filter +
  waivers + `compliance_overview`.
- `configuration/current_config_projection.py` — `password_policy` section.
- `utils/html_export.py`, `main.py` — thread `data_root` to
  `build_compliance_posture`.
- `templates/index.html`, `static/app.js` — Overview card + Compliance KPI /
  framework cards / severity / filter / explain.
- `tests/test_phase0_7_1_compliance_assignment.py` — new.
- `project/*`, `CURRENT_STATE.md` — state on close.

This is a larger-than-default build because the catalog, engine, payload and UI
must move together for producer/consumer consistency (AGENTS.md allows this when
explained).

## 4. Acceptance

| AC | Covered by |
| --- | --- |
| AC-1 | `CONTROL_CATALOG`: every entry has `id`, `severity` in the enum, `frameworks[]` with `applies` + `reference`, `evidence.fields`, `lifecycle`; the 10 legacy ids present with unchanged evaluators; `BASELINE_CONTROLS` derived view byte-matches the 0.6.6B shape. |
| AC-2 | With **no** `control_assignments.json`, `build_compliance_posture` output for the existing synthetic fixture is unchanged except the additive keys (`frameworks`, `severity`, `rationale`, `compliance_overview`, per-subject `assigned`/`evaluated`). |
| AC-3 | Assignment policy: `include`/`exclude`/`"*"`, group match, device > group > default precedence; malformed / unknown-control-id → `ControlAssignmentPolicyError` before evaluation; missing file → all-applicable. |
| AC-4 | An unexpired waiver → that (control, subject) cell status `WAIVED` (never `PASS`); an expired waiver is ignored; waiver counts surface in `cells` / `by_subject`. |
| AC-5 | `compliance_overview` math: `monitored + unmonitored == total`; `aligned_percent` and `risk_weighted_alignment_percent` in [0,100]; per-framework `coverage` ∈ {COVERED, PARTIALLY_COVERED, UNCOVERED}; `by_subject` sums reconcile with `cells`. |
| AC-6 | Payload additive: every prior compliance key + type preserved; `app.js` `complianceControlCard` still renders (uses `framework_mappings` fallback); `--render-only` embeds and renders the Overview + Compliance additions with no error; empty/`available:false` path intact. |
| AC-7 | New + impacted compliance/UI regression + `--repository-privacy-check` (no device name / secret / raw config / certification claim in the shareable payload) pass. |

## 5. Validation

Synthetic fixtures (+ a `password_policy` XML/text fixture) → `py -m pytest`
`-n auto` + `--render-only`. No network, no server, no real-environment gate.

## 6. Definition of Done

AC-1…AC-7 pass; `--render-only` healthy; diff review confirms no collector /
network / CAS / scheduler change; `compliance_control_enrichment` →
`automated_validated`; `compliance_engine` / `framework_mappings` /
`evidence_reporting` criteria advanced where delivered.

## 7. Decisions (design §11 — resolved as recommended)

1. `default_mode` default = **`all_applicable`** (backward compatible).
2. Unknown `control_id` in the policy → **hard error**.
3. Projection extension in `0.7.1` = **`password_policy` only** (+3 controls);
   `banner` / `services` → `0.7.2`.
4. `--assign` CLI helper: **no** — file-edit only in `0.7.1`.
5. Framework list = **fixed CIS / PCI-DSS / BDDK**; extensible later.
6. Coverage roll-up = **extend the compliance payload** (one pass); Overview reads it.
7. **Severity in `0.7.1`** — static per-catalog-entry enum; exposure dimension later.
8. **Minimal file-based waivers in `0.7.1`** (`WAIVED` state); UI to raise them later.
9. Trend / point-in-time → **deferred**; payload designed so `history[]` is additive.
10. **Include `PARTIALLY_COVERED`** per-framework state.

---

## 8. Implementation record — `0.7.1a` (2026-08-29, AUTOMATED_VALIDATED)

**Scope shipped (additive-only, zero outcome change):**

- `utils/compliance_catalog.py` (new) — `CATALOG_VERSION = "0.7.1a"`. The ten
  controls as a versioned declarative model: `id`, `cis_reference` (legacy
  verbatim), `title`, `rationale`, `control_area`, `severity`
  (`informational|low|medium|high|critical`, weighted 1–5), `vendors`,
  `evidence` contract (`plane` / `fields` / `basis`), `frameworks` — explicit
  per-framework CIS / PCI-DSS / BDDK membership with `applies` bool + `reference`
  + extras (`version="4.0"`, `profile`), `lifecycle`, `introduced`, `evaluator`.
  Helpers: `catalog_entry`, `severity_weight`, `frameworks_for`,
  `catalog_baseline_controls()` (the 5-key view the 0.6.6B pack consumes,
  verbatim shape + order).
- `utils/compliance_rulepack.py` — `BASELINE_CONTROLS` is now
  `catalog_baseline_controls()` (single source of truth); `DEFAULT_RULE_PACK`
  still 10 rules; 0.6.6B pack + frozen tests untouched.
- `utils/compliance_posture.py` — `_catalog_meta()`; `_control()` emits additive
  `severity` / `rationale` / `frameworks`; `_implemented_control` /
  `_planned_evidence_gap` stamp them from the catalog. Platform / fleet controls
  carry `severity: null`, `frameworks: []` (no catalog entry).
- `static/app.js` — `complianceControlCard` renders a severity badge, a rationale
  line, and a real per-framework reference grid (falls back to the 0.6.6B
  `framework_mappings` render when `control.frameworks` is absent).
- `tests/test_phase0_7_1a_compliance_catalog.py` (new) — 6 tests: catalog schema
  + membership, severity scale, baseline view is verbatim and drives the pack,
  subject controls unchanged + additive metadata present, platform/fleet null
  metadata, no certification claim / leak.

**Evidence:** `py -m pytest -q` 464 passed / 3 skipped / 0 failed (Python 3.12);
`--render-only` PASS (0 placeholders left); repository privacy gate PASS / 0.

**Deferred to `0.7.1b`:** the ~12 enrichment controls, `password_policy`
projection section, `control_assignments.json` + per-device filter + waivers,
`compliance_overview` roll-up, Overview card, KPI band / framework readiness /
framework filter workbench spine.
