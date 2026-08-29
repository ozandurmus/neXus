# 0.7.4 — framework_mappings: Requirement-Level Coverage (contract)

**Status:** `AUTOMATED_VALIDATED 2026-08-29` (see §10 impl record) · **Movement:** ARCHITECTURE → IMPLEMENTATION → UI → VALIDATION
**Design:** `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md` §3 (Part B), §9 ("a
requirement with some mapped controls unmonitored is `PARTIALLY_COVERED`" — deferred
then, delivered now), §8 (catalog as a first-class declarative model).
**Advances:** `framework_mappings`, `evidence_reporting` (0.7.x VERIFY track).
**Predecessor:** `0.7.3` (+ CE.1 crypto wire) — AUTOMATED_VALIDATED. Additive; no
server; no new device command; no certification claim.

---

## PROJE ÖZETİ (Türkçe)

- **Bu görev:** Bugün her kontrol "CIS 2.1.9" gibi bir çerçeve numarasıyla etiketli.
  Çerçeve tarafını modelliyoruz: her çerçeve (CIS / PCI-DSS / BDDK) → maddeler
  listesi, her madde → onu kanıtlayan kontrol(ler), ve **madde bazında kapsam**
  (`COVERED / PARTIALLY_COVERED / UNCOVERED / NOT_APPLICABLE`). Hiç kontrolü olmayan
  bir madde artık görünür bir boşluk.
- **Faydası:** "CIS'e %83 uyumluyuz" yerine "24 maddeden 12'si tam, 4'ü kısmi, 3'ü
  hiç izlenmiyor" — denetçinin istediği görünüm.
- **Tür:** Yeni özellik (0.7.x VERIFY). Eklemeli; yeni toplayıcı yok, sunucu yok.
  Sertifikasyon iddiası yok. Çerçeve metinleri birebir kopyalanmaz (telif) —
  sadece madde numarası + kısa kendi başlığımız.

---

## 1. What exists

`utils/compliance_catalog.py` — each control carries
`frameworks: [{framework, reference, applies}]`; structured refs added in 0.7.1a
are clean (`"2.1.9"`, `"2.2.5"`, `"2.1.x"`; BDDK refs are stable Turkish
phrases). `utils/compliance_posture.py::_compliance_overview` →
`by_framework[name] = {controls, monitored, aligned, finding, coverage}` at
**framework** granularity; `_catalog_control_frameworks(extra_meta)` =
`{control_id: {framework: applies}}` (includes 0.7.3 enforced user checks).
`static/app.js::renderComplianceCoverageOverview` renders one readiness card per
framework + the 0.7.2 filter chips. **No framework catalog, no requirement
model.** `_FRAMEWORKS = ("CIS", "PCI-DSS", "BDDK")`.

## 2. Change (all additive)

### 2a. `utils/framework_catalog.py` (new) — declarative framework model

```python
FRAMEWORK_CATALOG_VERSION = "0.7.4"
FRAMEWORKS = (
  {"id": "CIS", "name": "CIS Firewall Benchmark", "version": "generic (CP/PAN aligned)",
   "profile": "Level 1",
   "requirements": (
     {"id": "2.1.1", "section": "2.1", "title": "Administrative login banner configured", "applies": True},
     {"id": "2.1.9", "section": "2.1", "title": "Telnet disabled", "applies": True},
     ... )},
  {"id": "PCI-DSS", "name": "PCI-DSS", "version": "4.0", "profile": None, "requirements": (...)},
  {"id": "BDDK", "name": "BDDK İyi Uygulama Rehberi", "version": "—", "profile": None, "requirements": (...)},
)
```

- The **authored requirement list** covers every distinct non-`"not applicable"`
  `reference` currently used by a catalog control (list captured at contract
  time), **plus** a modest curated gap set (~5–8 per framework) — important
  requirements with no control yet, so `UNCOVERED` is visible.
- `title` is our own one-line paraphrase. **No verbatim CIS / PCI-DSS benchmark
  text.** `section` + `id` + our title only.
- BDDK requirements are the phrase-refs (kept lean; deduplicated).
- Helpers: `framework_entry(id)`, `requirements_for(id)`,
  `normalize_ref(s)` — strips a `"CIS "` / `"PCI-DSS "` / `"PCI "` prefix + case;
  `"not applicable"` → sentinel (no match); `"2.1.x"` is its own requirement id
  ("section 2.1 — unspecified management-plane hardening").

### 2b. `utils/compliance_posture.py` — requirement roll-up

`_compliance_overview` `by_framework[name]` gains:

```json
{ "controls": 20, "monitored": 18, "aligned": 15, "finding": 3, "coverage": "PARTIALLY_COVERED",
  "version": "CIS Firewall Benchmark (generic profile)", "profile": "Level 1",
  "requirements": [
    { "id": "2.1.9", "section": "2.1", "title": "Telnet disabled",
      "control_ids": ["telnet_disabled"], "applicable": true,
      "monitored": 1, "aligned": 1, "finding": 0, "unknown": 0,
      "coverage": "COVERED", "posture": "ALIGNED" },
    { "id": "2.3.5", "section": "2.3", "title": "...", "control_ids": [], "applicable": true,
      "monitored": 0, "aligned": 0, "finding": 0, "unknown": 0,
      "coverage": "UNCOVERED", "posture": "UNKNOWN" } ],
  "requirement_counts": { "COVERED": 12, "PARTIALLY_COVERED": 4, "UNCOVERED": 3, "NOT_APPLICABLE": 1 },
  "unmapped_control_refs": ["PanOS Verify Update Server Identity"] }
```

- `control_ids` per requirement = every catalog **and** user (`x_`) control whose
  `frameworks[]` has `framework == name` and `normalize_ref(reference)` equals
  this requirement id.
- **`coverage`** (monitoring completeness): `NOT_APPLICABLE` (requirement
  `applies:false`, or every mapped control is `applies:false` for this
  framework) · `UNCOVERED` (no mapped control, or none monitored) ·
  `PARTIALLY_COVERED` (some mapped controls monitored) · `COVERED` (all
  applicable mapped controls monitored).
- **`posture`** (evidence outcome, orthogonal): `FINDING` if any mapped control
  is FINDING, else `ALIGNED` if ≥1 aligned and none finding, else `UNKNOWN`.
- `unmapped_control_refs` = distinct `frameworks[].reference` strings for this
  framework that matched no requirement (a drift guard; normally empty once the
  catalog covers the refs — the non-numeric PAN-OS refs are added as
  requirements, so only genuine typos surface).
- `compliance_overview.framework_catalog_version` added.
  `COMPLIANCE_SCHEMA_VERSION` unchanged (additive).
- Advisory user checks stay out of the roll-up via the existing `_scoring_rows`.

### 2c. `_empty_overview()`

`by_framework[name]` gets `requirements: []`, `requirement_counts` (all 0),
`version` / `profile` from the framework catalog; `framework_catalog_version`
added.

### 2d. UI (`static/app.js`, `static/style.css`)

Each framework readiness card in `renderComplianceCoverageOverview` gains:
- a `requirement_counts` mini-bar (COVERED / PARTIALLY / UNCOVERED / N/A);
- an **expand** toggle (reusing the 0.7.2 `[data-explain-toggle]`-style
  delegated pattern) → requirement rows: `section·id · title`, a `coverage`
  pill, a `posture` pill, and the mapped control ids.
- The 0.7.2 framework filter chips already choose which cards render.

No payload change beyond 2b.

## 3. Files

- `utils/framework_catalog.py` — new.
- `utils/compliance_posture.py` — `_compliance_overview` requirement roll-up +
  `_empty_overview`.
- `static/app.js`, `static/style.css` — requirement drill-down.
- `tests/test_phase0_7_4_framework_requirements.py` — new.
- Frozen touch-up only if a `by_framework` sub-shape is pinned by
  `test_phase0_7_1` / `test_phase0_6_6b` (checked at impl). `test_phase0_6_6b`
  otherwise untouched.
- `project/*`, `CURRENT_STATE.md`, `AI_HANDOVER.md`, `docs/history/INDEX.md` — on close.

## 4. Acceptance

| AC | Covered by |
| --- | --- |
| AC-1 | `framework_catalog.py`: every requirement has `id` / `section` / `title` / `applies`; `normalize_ref` maps the known control ref forms (`"CIS 2.1.9"` → `"2.1.9"`, phrase → lower, `"not applicable"` → no match); every `_FRAMEWORKS` id present with `version`. |
| AC-2 | `by_framework[name].requirements`: a mapped + monitored + aligned control → `COVERED` / `ALIGNED`; a mapped control with a FINDING → `PARTIALLY_COVERED` / `FINDING`; a requirement with **no** mapped control → `UNCOVERED` / `UNKNOWN`; `applies:false` requirement → `NOT_APPLICABLE`. |
| AC-3 | `requirement_counts` sums to `len(requirements)`; each requirement's `coverage` ∈ the four values; `posture` ∈ {ALIGNED, FINDING, UNKNOWN}. `unmapped_control_refs` is a list (empty in the happy path). |
| AC-4 | A user `x_` check with `frameworks:[{framework:"CIS","reference":"2.1.9"}]` appears in requirement `2.1.9`'s `control_ids` and can move its `coverage` / `posture`. Advisory user checks do not. |
| AC-5 | Payload additive: every prior `by_framework` key + type preserved; framework-level `coverage` unchanged; `available:false` path carries the empty requirement shape; `--repository-privacy-check` PASS (no device identity, no verbatim benchmark text, no certification claim). |
| AC-6 | `--render-only` renders the requirement drill-down + mini-bar with no console error; framework filter chips still scope the cards; full suite ≥ `516` baseline, delta explained; `test_phase0_6_6b` untouched. |

## 5. Validation

Synthetic compliance fixtures + the real `framework_catalog.py`.
`py -m pytest -q -n auto --dist worksteal` + one `scripts/render_sample.py`.
No network, no credentials, no real-environment gate.

## 6. Definition of Done

AC-1…AC-6 pass; `--render-only` healthy; diff review confirms no new control /
collector / network / assignment-engine change and no change to
`subject["controls"]` or the 0.6.6B pack; `framework_mappings` feature note
advanced; state files + `AI_HANDOVER.md` rewritten; `SESSION CLOSE` with the
`main` merge decision + dispatch. `§10` impl record on close.

## 7. Decisions

1. Requirement list = authored in `framework_catalog.py` (covers every current
   control ref + a modest curated gap set), not auto-derived — so `UNCOVERED`
   gaps are a deliberate statement.
2. Non-numeric PAN-OS refs (`"PanOS Verify Update Server Identity"`) are added as
   requirements (real benchmark items), not left unmapped.
3. `"2.1.x"` stays its own requirement id.
4. BDDK phrase-refs **are** requirement-modelled (lean list) — consistency with
   CIS / PCI-DSS.
5. `coverage` (monitoring) and `posture` (outcome) are separate dimensions.
6. In-repo module now; a signed / user-authored framework pack (custom
   frameworks, a UI mapping editor) is `DEPLOY.1A`-gated, same class as the
   assignment editor.

## 8. main.py / UI effect

After a normal run: each framework readiness card shows a
COVERED/PARTIALLY/UNCOVERED/N-A mini-bar and expands to a per-requirement list
with coverage + posture pills and the mapped control ids. With no config
evidence the cards render the empty requirement shape. No change to Network
Inventory, Configuration, Discovery, Project Plan, or the framework-level
percentages.

---

## 10. Implementation record — `0.7.4` (2026-08-29, AUTOMATED_VALIDATED)

**Scope shipped (additive; §7 decisions applied):**

- **`utils/framework_catalog.py` (new)** — `FRAMEWORK_CATALOG_VERSION = "0.7.4"`.
  `FRAMEWORKS` = CIS (28 requirements) / PCI-DSS (18) / BDDK (22), each an
  authored requirement list covering every distinct structured `frameworks[].reference`
  currently used by a catalog control **plus** a modest curated gap set;
  process-only requirements (`PCI 1.2.1`, `PCI 10.4.1`, BDDK "Yetki Ayrıştırma")
  carry `applies: False`. Titles are our own paraphrase — no verbatim benchmark
  text. `normalize_ref` (prefix/case strip, `"/ description"` trim, N/A → `""`),
  `framework_entry`, `requirements_for`, `requirement_index`, `FRAMEWORK_IDS`.
- **`utils/compliance_posture.py`** — `_control_framework_refs(control_id,
  extra_meta)` → `{framework: reference}` (catalog entry, or a user check's new
  `framework_refs` in `_user_check_meta`). `_compliance_overview`: a
  `per_control_unknown` accumulator; a `norm_refs` map; per framework a
  requirement roll-up — `control_ids` (catalog + `x_` user checks joined by
  `normalize_ref`), `coverage` (`COVERED` / `PARTIALLY_COVERED` / `UNCOVERED` /
  `NOT_APPLICABLE`, monitoring completeness over *applicable* mapped controls),
  `posture` (`ALIGNED` / `FINDING` / `UNKNOWN`, orthogonal), `requirement_counts`,
  `unmapped_control_refs` (drift guard, empty in the happy path). `by_framework[name]`
  also gains `version` / `profile`. Top-level `framework_catalog_version`. New
  `_empty_framework_block` builds the empty-path requirement shape.
  `COMPLIANCE_SCHEMA_VERSION` unchanged.
- **`static/app.js` + `static/style.css`** — `renderComplianceCoverageOverview`
  framework cards gain a `version · profile` line, a 4-segment
  `compliance-req-bar` mini-bar, and a `Requirements (N)` expand toggle (reusing
  the 0.7.2 `[data-explain-toggle]` delegated listener + `.compliance-explain-panel`)
  → a `.compliance-req-list` of rows (`section · id`, title + mapped control
  ids, a coverage pill + a `posture` pill via the new `compliancePostureTone`).
  Framework filter chips still scope the cards.
- **Tests** — `tests/test_phase0_7_4_framework_requirements.py` (7). No frozen
  touch-up needed — `test_phase0_7_1` / `test_phase0_6_6b` pin top-level additive
  keys and framework-level `coverage`, both preserved.

**Evidence (2026-08-29):**

- `py -m pytest -q -n auto --dist worksteal` → **523 passed, 3 skipped,
  0 failed** (Python 3.12). Prior baseline 516 → +7 (new file).
- `scripts/render_sample.py` → exit 0, 0 placeholders; the requirement markup +
  CSS embed.
- Repository privacy gate → **PASS / 0** on a clean tree. `compliance_overview`
  carries no device identity and no verbatim benchmark text; no certification
  claim.

**AC status:** AC-1…AC-6 met.

**Deferred:** a signed / user-authored framework pack (custom frameworks, a UI
mapping editor) — `DEPLOY.1A`-gated, same class as the assignment editor and the
CE.3 check editor.
