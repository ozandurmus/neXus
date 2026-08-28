# Compliance — Control Enrichment, Framework Grouping & Assignment (design)

**Status:** DESIGN. Partly buildable now (`0.7.1`), partly gated on `DEPLOY.1`.
**Maps onto roadmap `0.7.x` features:** `compliance_engine` (assignment +
engine), `framework_mappings` (CIS / PCI-DSS / BDDK grouping),
`evidence_reporting` (coverage roll-up).

---

## PROJE ÖZETİ (Türkçe)

- **İhtiyaç:** Daha fazla CIS kontrolü; her kontrolü CIS / PCI-DSS / BDDK'ya göre
  etiketleyip gruplama; belirli firewall'lara belirli kontrolleri **atama**
  (denetim kapsamı yönetimi); Overview'da "33 kontrol, 29'u Y firewall'da
  izleniyor, %83 uyumlu" özeti.
- **Faydası:** Denetim kapsamını araç içinde yönetmek ("PCI kapsamındaki
  cihazlarda sadece PCI kontrolleri koşsun"), kanıta-dayalı ve tek panelde
  kapsam + uyum oranı. Sertifikasyon iddiası yok.
- **Şimdi yapılabilir (`0.7.1`, sunucu gerekmez):** kontrol zenginleştirme,
  gerçek çerçeve etiketleri, **dosya-tabanlı atama politikası**
  (`data/state/control_assignments.json` — aynen `inventory_exclusions.json`
  deseni), motorun onu okuması, salt-okunur UI + Overview özeti.
- **Sunucu gerektiren (`DEPLOY.1`):** atamayı **UI'dan tıklayarak** yapıp
  **DB'ye** yazmak; etiketli cihaz kaydı (device registry). Politika dosyası,
  sunucunun ileride yazacağı biçimin aynısı.

---

## 1. What exists

`utils/compliance_rulepack.py` — `BASELINE_CONTROLS` = **10** deterministic
vendor-neutral controls (`control_id`, `title`, `control_area`,
`cis_reference`, `evidence_fields`), plus 6 platform + 2 fleet posture controls.
`utils/compliance_posture.py` — `_subject_controls(device)` runs **all 10** on
every device with available current-config; `_mapping(control_area,
cis_reference)` emits stubbed `cis / pci_dss / bddk` blocks (all
`mapping_type: INFORMATIONAL`, only `cis` carries a reference). **No per-device
selection.** No coverage roll-up.

Precedent for RuntimeRoot policy files (schema-versioned, validated,
fail-closed, not repo content): `data/state/inventory_exclusions.json`
(`utils/inventory_exclusions.py`), `data/state/scheduler_policy.json`
(`utils/collection_executor.load_scheduler_policy`).

## 2. Part A — Control enrichment (`0.7.1`)

Grow `BASELINE_CONTROLS` with net-new **deterministic** controls evaluable from
the projected `current_configuration.sections`:

- over existing sections: login banner present; SSH protocol v2 only; remote
  syslog target configured; failed-login logging; stricter session/idle limits;
  NTP authentication; management on a dedicated plane; admin account not the
  default name.
- with a **minimal projection extension** (new sections over *already-stored*
  config — "new projection, not new collector", the 0.7.0 rule): `password_policy`
  (min length / history / lockout / complexity), `banner`, `services`
  (unused services disabled: finger, ident, echo…).

Every added control keeps the state semantics (`PASS / FINDING / UNKNOWN /
NOT_APPLICABLE / PLANNED`), an `evidence_fields` contract, and (Part B)
`frameworks`. Controls whose evidence is genuinely absent stay
`PLANNED_EVIDENCE_GAP` — never guessed PASS.

Target: ~20–28 total baseline controls; the exact set is frozen in the `0.7.1`
contract against what the projection can actually support.

## 3. Part B — Framework grouping (`0.7.1`)

Replace the stubbed `_mapping()`. Each control declares real per-framework
membership:

```json
"frameworks": [
  { "framework": "CIS",     "profile": "CIS Check Point Firewall Benchmark", "reference": "2.1.9", "applies": true },
  { "framework": "PCI-DSS", "version": "4.0", "reference": "2.2.5", "applies": true },
  { "framework": "BDDK",    "reference": "İyi Uygulama Rehberi §X", "applies": false }
]
```

- `applies: true` → the control counts toward that framework's coverage.
- `applies: false` → recorded so gaps are visible ("BDDK has no equivalent").
- Semantics unchanged: **evidence-area mapping only, no certification /
  attestation claim** (same disclaimer the payload already carries).
- Payload gains a `frameworks` rollup (counts per framework); set queries like
  "CIS ∩ BDDK" are derivable client-side from `applies` flags.

## 4. Part C — Control assignment

### 4a. File-based policy + engine (`0.7.1`, no server)

New RuntimeRoot policy `data/state/control_assignments.json` — schema-versioned,
validated, **fail-closed**, not repo content (same class as
`inventory_exclusions.json`).

```json
{
  "version": 1,
  "default_mode": "all_applicable",          // or "assigned_only"
  "groups": {
    "pci-scope": { "match": [ {"device_name": "cp-gw-01"}, {"device_name": "pan-fw-03"} ] },
    "dmz":       { "match": [ {"vendor": "check_point", "tag": "dmz"} ] }
  },
  "assignments": [
    { "target": {"group": "pci-scope"},      "include": ["*"] },
    { "target": {"group": "dmz"},            "include": ["telnet_disabled", "http_management_restricted", "snmp_v3_only"] },
    { "target": {"device_name": "cp-lab-99"}, "exclude": ["*"] }
  ]
}
```

- **Resolution per device:** device-specific assignment > group assignment >
  `default_mode`. Effective control set = start from applicable controls (or ∅
  if `assigned_only`), apply `include` then `exclude` (`"*"` = all).
- Fail-closed: malformed policy → error before any evaluation; an unknown
  `control_id` in the policy → error (a scoping typo must not silently drop a
  control). Missing file → `all_applicable` (fully backward compatible — the
  current behavior).
- Engine: `compliance_posture._subject_controls` filters
  `DEFAULT_RULE_PACK["rules"]` per device by the resolved set. Per subject the
  payload records `assigned` / `not_assigned` / `evaluated` control ids.
- `tag` values are operator annotations carried in the policy file itself until
  a device registry exists (4b).
- Shareable support bundle: **counts only** — no device names, no policy body.

### 4b. UI editor + device registry (`DEPLOY.1`-gated)

"Assign from the UI" = the browser persists a change → needs the app server +
`DEPLOY.1A` OIDC/RBAC (only an authorised role edits scope) + a store. Two
pieces, both DEPLOY.1:

- **Assignment editor** — a Compliance-module screen: pick a device / group,
  toggle controls or whole frameworks, save. The server writes the *same*
  `control_assignments.json` shape (or its Postgres equivalent). Every change
  audited (who / when / before / after).
- **Device registry** — tags / groups (`pci-scope`, `dmz`, `crown-jewel`) as
  first-class annotations on management-plane-discovered devices, so
  `groups.*.match` can use `{"tag": "..."}` without hand-listing names.

Until then: an operator edits the policy file (or a small `--assign` CLI helper,
optional in `0.7.1`).

## 5. Part D — Coverage & alignment roll-up (`0.7.1`)

Additive `compliance_overview` block (in the compliance payload; the Overview
module renders a card):

```json
"compliance_overview": {
  "total_controls": 33,
  "monitored_controls": 29,     // assigned somewhere AND ≥1 subject has evidence
  "unmonitored_controls": 4,
  "subjects": 7,
  "cells": { "aligned": 210, "finding": 24, "unknown": 12, "planned": 6 },  // control×subject
  "aligned_percent": 82.7,
  "by_framework": { "CIS": { "controls": 22, "monitored": 19, "aligned": 15, "finding": 3, "unknown": 1 },
                    "PCI-DSS": { ... }, "BDDK": { ... } },
  "by_subject": [ { "subject_id": "cp-001", "assigned": 18, "aligned": 15, "finding": 2, "unknown": 1 } ]
}
```

"33 checks, 29 monitored on 7 firewalls, 82.7% aligned" → `total_controls`,
`monitored_controls`, `subjects`, `aligned_percent`; per-framework and
per-subject breakdowns for drill-down.

## 6. Now vs DEPLOY.1

| Capability | `0.7.1` (now) | `DEPLOY.1`-gated |
| --- | --- | --- |
| More CIS controls | ✅ | — |
| CIS / PCI-DSS / BDDK per-control membership + rollup | ✅ | — |
| Control assignment **model** + engine consumption | ✅ (file policy) | — |
| Read-only assignment view + framework filter (UI) | ✅ | — |
| Overview coverage / alignment roll-up | ✅ | — |
| **Edit assignments from the UI** (persisted) | ✏️ file / optional CLI | ✅ UI editor + audit |
| Device registry with tags / groups | tags in policy file | ✅ first-class registry |

## 7. Roadmap placement

- `0.7.1` build (this design's "now" column) advances `compliance_engine`,
  `framework_mappings`, `evidence_reporting`.
- New backlog item `compliance_assignment_ui_and_registry` — the UI editor +
  device registry, `P2`, `target DEPLOY.1`, gated on `DEPLOY.1A` OIDC/RBAC +
  the app server + a store; the file-based policy is the interchange format.

## 8. Control catalog as a first-class model (what makes it evolvable)

Today the ten controls are a hardcoded tuple. Product-grade + "improvable along
the way" means the control library becomes a **versioned, declarative catalog**
the engine and UI both read:

```json
{
  "catalog_version": "0.7.1",
  "controls": [
    { "id": "telnet_disabled", "title": "Telnet disabled",
      "rationale": "Telnet exposes credentials and sessions in cleartext.",
      "control_area": "Administrative access restrictions",
      "severity": "high",                     // informational | low | medium | high | critical
      "vendors": ["check_point", "palo_alto"],
      "evidence": { "plane": "direct_actual", "fields": ["...management.settings.protocol_enablement"],
                    "basis": "configured" },
      "frameworks": [ { "framework": "CIS", "reference": "2.1.9", "applies": true },
                      { "framework": "PCI-DSS", "version": "4.0", "reference": "2.2.5", "applies": true },
                      { "framework": "BDDK", "reference": "...", "applies": true } ],
      "lifecycle": "active",                  // active | planned_evidence_gap | deprecated
      "introduced": "0.6.1B.1.6" }
  ]
}
```

- **`0.7.1`:** the catalog is an in-repo module (like `BASELINE_CONTROLS`, but
  this richer shape) + a schema. Adding a control = one catalog entry + its
  evaluator; the UI shows it automatically (it renders from the catalog).
- **Later:** the catalog can move to a signed, versioned pack the way the
  0.6.6B / 0.7.0 rule packs are heading — new controls without a release.
- Framework references are **many-to-one both ways**: one framework requirement
  may need several controls; one control may satisfy parts of several
  requirements. `applies` + `reference` capture membership, not equivalence; the
  UI never claims "PCI-DSS 2.2.5 is met", only "the control(s) mapped to it are
  aligned / have findings".

## 9. Expert perspectives

**Compliance & risk lens**
- Controls need **severity / risk weight** — a weak IKE proposal on an
  internet-facing VPN is not a missing secondary NTP server. Roll-up should show
  risk-weighted alignment, not just a count.
- **Exceptions / waivers**: an accepted-risk record per (control, subject) with
  a reason, an approver and an expiry. Without this the tool is unusable for a
  real audit — every mature estate has justified deviations. `0.7.1` can carry
  waivers in the same policy file; the UI editor comes with DEPLOY.1.
- **Point-in-time evidence**: "what was our PCI posture on 2026-06-30" — the
  content-addressed history already stores the config; the roll-up should be
  reproducible against a past run.
- Partial coverage must be explicit: a framework requirement with *some* mapped
  controls unmonitored is `PARTIALLY_COVERED`, not `PASS`.
- Keep the no-certification-claim boundary loud and permanent.

**Network security manager lens**
- One number for the board (`aligned_percent`, risk-weighted), full drill-down
  for the SOC — same payload, progressive UI.
- **Trend**: "78% → 83% this quarter", regressions highlighted. Needs the
  history plane, not a new collector.
- **Scope as a quarterly artifact**: which devices are in PCI scope *now*;
  scope changes are audited.
- **Ownership**: assign a control-area or a device-group to a team; unassigned
  findings are visible as a gap.
- **False-positive feedback loop**: an operator can flag a finding as
  misclassified; that feeds control tuning (tracked, not silently suppressed).
- Remediation linkage: a finding can carry an external ticket reference.

**Network security architect lens**
- Control library, framework map and assignment policy are all **data, not
  code** — reviewable, diffable, eventually GitOps-managed.
- Evidence-plane separation stays first class: `configured` (0.6.x/0.7.0),
  `negotiated` (future runtime layer), `inferred`. A control states which planes
  it can be satisfied from.
- Composability: this shares the subject/vendor model with alignment
  (expected-vs-actual) and crypto posture; a control can reference an alignment
  outcome as evidence, not just a config value.
- Multi-tenant / per-BU scoping is the same `groups` mechanism at a higher
  level; design the policy so a BU dimension can be added without a schema break.
- Fleet scale: hundreds of devices × dozens of controls — evaluation is O(n·m)
  pure functions over already-parsed evidence; cache per (config sha256,
  catalog version).

**UI / UX lens — see §10.**

## 10. The Compliance workbench — a living surface, not a static pane

Design intent: a **workbench**, not a report. It is expected to grow; the UI
renders from the catalog + payload so new controls / frameworks appear without
UI code.

- **Progressive disclosure**: Fleet KPI band (aligned %, risk-weighted, trend,
  monitored / total, findings by severity) → framework readiness cards
  (CIS / PCI-DSS / BDDK, each with covered / monitored / aligned) → control
  list (filterable, sortable by severity) → subject results → the evidence line
  (field, expected, observed, why it matters, which frameworks care, waiver
  affordance).
- **Cross-filtering**: click a framework chip or a severity → the whole surface
  filters. Click a device on Overview → land here scoped to it.
- **Saved views / scopes**: "PCI scope", "internet-facing", "my team" — a named
  filter + assignment lens the operator returns to.
- **Assignment as direct manipulation** (DEPLOY.1 editor): a device-group ×
  control matrix; toggle a cell, a row (all controls for a group), or a column
  (a control across the fleet); toggle a whole framework at once. Preview the
  coverage delta before saving. Every save audited.
- **Time**: a run selector / scrubber for trend and point-in-time posture.
- **Explain-this-finding** panel: plain-language rationale, the exact evidence
  fields, expected vs observed, severity, mapped framework references, and
  "raise waiver" / "link ticket".
- **States**: first-run (no config evidence), loading, partial (some subjects
  unavailable), error — all explicit, never a blank pane.
- **Density**: a compact mode for a SOC wall display, comfortable for a laptop.
- Theme-aware and accessible (already repo values); no interaction redesign
  needed to add a control — the list is data-driven.
- **`0.7.1` delivers the read-only spine** of this (KPI band + framework cards +
  control list + evidence panel + coverage roll-up on Overview); the assignment
  editor, trend scrubber and waiver UI land with DEPLOY.1. The payload shape is
  designed now so those are additive.

## 11. Open decisions

1. `default_mode` default value: `all_applicable` (current behavior, safest) vs
   `assigned_only` (nothing runs until scoped). *Recommend `all_applicable`.*
2. Unknown `control_id` in the policy → hard error vs warn-and-skip.
   *Recommend hard error* (a scoping typo silently dropping a control is worse
   than a failed run).
3. `0.7.1` control-count target and whether to extend the projection now
   (`password_policy` / `banner` / `services`) or stay within existing sections.
   *Recommend a small projection extension* — otherwise enrichment is thin.
4. Optional `--assign` CLI helper in `0.7.1`, or file-edit only until the UI
   editor. *Recommend file-edit only in `0.7.1`* (keep it small).
5. Framework list fixed at CIS / PCI-DSS / BDDK, or make it extensible
   (config-driven) now. *Recommend fixed three in `0.7.1`*, extensible later.
6. Coverage roll-up home: extend the compliance payload (Overview reads it) vs a
   separate `build_compliance_overview`. *Recommend extend the compliance
   payload* (one evaluation pass).
7. **Severity / risk weight** on every control in `0.7.1`, or a later pass?
   *Recommend in `0.7.1`* — a static `informational|low|medium|high|critical`
   per catalog entry is cheap and the roll-up needs it to be meaningful; the
   internet-facing / exposure dimension is a later enrichment.
8. **Exceptions / waivers** in `0.7.1` (carried in the assignment policy file,
   `{control, subject, reason, approver, expires}`, honoured by the engine as a
   `WAIVED` state) vs deferred to the DEPLOY.1 editor. *Recommend a minimal
   file-based waiver in `0.7.1`* — a compliance surface without waivers is not
   audit-usable; the UI to raise them comes later.
9. Trend / point-in-time roll-up in `0.7.1` (read from the existing config
   history) or deferred with the trend scrubber UI? *Recommend deferred* — keep
   `0.7.1` to the current-run roll-up; design the payload so a `history[]` array
   is additive.
10. `PARTIALLY_COVERED` framework state in `0.7.1` vs covered/uncovered only.
    *Recommend include it* — it is the honest answer and cheap to compute.
