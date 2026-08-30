# Compliance Check Engine — User-Authored Checks over Evidence (design)

**Status:** DESIGN. Phased. `CE.1` buildable now (no server, no new device
command); `CE.2` gated on the network-device command gate + a
real-environment validation gate (the CP device-interaction-safety audit
itself closed 2026-08-25, `backlog.json` `cp_device_interaction_safety`);
`CE.3` gated on `DEPLOY.1A`.
**Maps onto roadmap `0.7.x` feature:** `compliance_engine` (currently
`in_progress` — 0.7.1a/b advanced it; this is the next major step).
**Sibling designs:** `docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md`
(§8 "control catalog as a first-class model" — this doc is that idea, generalised
from a Python catalog to a data-driven check pack), the 0.6.6B / 0.7.0 rule packs
(the "static versioned in-repository pack → dynamic/signed pack" direction).

---

## PROJE ÖZETİ (Türkçe)

- **İhtiyaç:** Bugün her uyum kontrolü Python koduyla yazılıyor
  (`utils/compliance_evaluators_ext.py`). İstenen: kontrolü **veri** olarak
  tanımlamak — adı, hangi kanıta bakacağı, beklenen değer/desen, eşleşirse
  **yeşil (uyumlu)**, eşleşmezse **uyumsuz**. BackBox / Nipper / Titania böyle
  çalışır: kontrol bir "test" nesnesidir.
- **Kısıt:** Bu ürün şu an **salt-okunur**. Cihaza yeni komut çalıştırmak
  **komut kapısından** (network-device command gate — her komut için 10 maddelik
  belge, keyfi/yazma komut yok) geçmek zorunda; CP cihaz-etkileşim güvenlik
  denetimi (P0) hâlâ açık. Yani "kullanıcı serbestçe komut yazsın, biz çalıştıralım"
  şu an mümkün değil.
- **Yapılabilir olan (CE.1, sunucu gerekmez):** Kullanıcı, **zaten toplanmış
  kanıt** üzerinde çalışan bir **denetim paketi** yazar
  (`data/state/compliance_checks.json` — aynen `control_assignments.json`
  deseni). Motor, beklenen deseni kanıta uygular → PASS / FINDING / UNKNOWN.
  Kanıt yoksa asla tahmini PASS yok.
- **Sonra (CE.2, komut-kapısıyla):** Vendor onaylı, salt-okunur **komut
  ilkeleri** kütüphanesi. Kullanıcı bu onaylı ilkelerden kontrol kurar — ham
  komut dizesi değil.
- **En son (CE.3, DEPLOY.1A):** UI'dan kontrol editörü, imzalı kurumsal denetim
  paketleri, OIDC/RBAC ile yetkili rol.
- **Tür:** Büyük özellik / mimari (0.7.x VERIFY hattı). Sertifikasyon iddiası yok.

---

## 1. What exists

- `utils/compliance_catalog.py` — a hand-written declarative catalog (24 entries
  after 0.7.2: the frozen 0.6.6B ten + 8 `0.7.1b` + 6 `0.7.2` enrichment
  controls). Each entry names an `evaluator` string.
- `utils/compliance_evaluators_ext.py` — a Python dispatch dict
  `ENRICHMENT_EVALUATORS: {control_id: callable}`; each callable reads
  `device["current_configuration"]["sections"]` and returns
  `(status, summary, coverage)`. Adding a check = new catalog entry **plus new
  Python**.
- `utils/compliance_posture.py` — `_subject_extended_controls(...)` iterates the
  catalog, filters by the resolved assignment set (`0.7.1b`), calls the
  evaluator, emits into `subject["extended_controls"]`; `compliance_overview`
  rolls up. `STATUS_VALUES = ("PASS","FINDING","UNKNOWN","NOT_APPLICABLE",
  "PLANNED","WAIVED")`. Discipline: **evidence absent → UNKNOWN, never an
  inferred PASS**.
- `utils/compliance_rulepack.py` (`securityexpert.baseline.cp-pan @ 0.6.6B`),
  `utils/crypto_rulepack.py` (`securityexpert.crypto.cp-pan @ 0.7.0`) — static,
  versioned, in-repo packs with `certification_claim=false`. The documented
  direction is "dynamic/signed packs, new controls without a release".
- `utils/control_assignment.py` — the RuntimeRoot policy-file pattern
  (`data/state/control_assignments.json`, schema-versioned, fail-closed, unknown
  id → error, missing file → backward-compatible default). `CE.1` reuses this
  pattern verbatim.
- Read-only evidence models a check can draw on: the per-vendor
  `current_configuration.sections[].settings[]` projections
  (`configuration/current_config_projection.py`,
  `configuration/checkpoint_config_collector.py`), the unified inventory
  (`utils/merge.py` → `unified.json`), crypto facts
  (`utils/crypto_facts.py`, `0.7.0`), CP management↔actual alignment results
  (`0.6.1D`).

## 2. The hard constraint (why "run any command" is not CE.1)

`AGENTS.md` + `docs/AI_DEVELOPMENT_PROTOCOL.md` — **network-device command gate**:
every added/changed device command must document (1) why, (2) read-only vs
write, (3) vendor/platform/shell/context, (4) timeout, (5) retry, (6) max
execution frequency per endpoint, (7) existing-session reuse, (8) unsupported
behaviour, (9) secret-bearing output risk, (10) safe telemetry. **"No new write
command at the current product maturity."** Plus: the CP device-interaction-safety
audit is an open **P0** that pins the admission-coordinator concurrency budget at
1 per vendor; and the privacy redaction layer (`SECRET_LINE_RE`, the projection
allowlists) is built around *known* command shapes — arbitrary command output has
no redaction contract.

Consequence: a user cannot supply free-form command strings for the engine to run
on a device. The engine can (CE.1) assert over evidence already collected, and
later (CE.2) run a *curated, individually gate-reviewed, read-only* primitive set
that users compose checks from.

## 3. The check model (all phases share this shape)

A check is one JSON object. It is **data** — no code, no `eval`, no free
expression language.

```json
{
  "id": "ssh_no_cbc_ciphers",
  "title": "SSH management does not offer CBC ciphers",
  "rationale": "CBC-mode ciphers over SSH are vulnerable to plaintext-recovery attacks.",
  "severity": "high",                         // informational|low|medium|high|critical
  "applies_to": { "vendor": ["check_point", "palo_alto"] },   // optional platform/entity_type too
  "frameworks": [
    { "framework": "CIS", "reference": "2.1.11", "applies": true },
    { "framework": "PCI-DSS", "version": "4.0", "reference": "2.2.5", "applies": true },
    { "framework": "BDDK", "reference": "Sistem Sıkılaştırma - Güvenli Yönetim", "applies": true }
  ],
  "evidence": {
    "basis": "configured",                    // configured | negotiated(future) | inferred
    "steps": [
      { "source": "current_configuration.sections[id=management].settings",
        "select": "value",
        "assert": { "op": "none_match", "pattern": "(?i)-cbc" } }
    ],
    "combine": "all"                          // all | any  (BackBox-style command series)
  },
  "verdict": { "on_pass": "PASS", "on_fail": "FINDING", "on_no_evidence": "UNKNOWN" }
}
```

- **`evidence.steps[]`** is the "command series" idea, made safe: each step names
  a bounded **`source`** selector into an already-collected read-only evidence
  model, an optional **`select`** (which field/attribute), and one **`assert`**.
  `combine: all` (default) = every step must pass; `any` = at least one.
- **No-evidence rule:** if any step's `source` resolves to nothing, the check is
  `UNKNOWN` (or the configured `on_no_evidence`) — **never** silently `PASS`.
- **`applies_to` miss** → the check is omitted from that subject's list and
  counted in `assignment.not_assigned` (consistent with `0.7.1b`), not
  `NOT_APPLICABLE`.

### 3a. `source` namespaces (read-only evidence selectors)

| namespace | resolves against | example |
| --- | --- | --- |
| `current_configuration.sections[id=<sid>].settings` | the per-vendor projection | `…[id=password_policy].settings` |
| `unified.device` | the merged inventory row for the subject | `unified.device.platform.family` |
| `unified.interfaces` / `unified.routes` | merged inventory collections | filter + assert on count |
| `crypto_facts.<group>` | `utils/crypto_facts.py` output (0.7.0) | `crypto_facts.ike.proposals` |
| `alignment.results` | CP management↔actual (0.6.1D) | assert a classification is absent |

Selectors are a fixed grammar: `namespace` `.` `key` (`[attr=value]` filter on
list elements) `.` `key` … — parsed, not `eval`'d. Unknown namespace/selector →
pack load error (fail-closed).

### 3b. Assertion operators (fixed set)

`present` · `absent` · `equals` · `not_equals` · `matches` · `not_match` ·
`any_match` · `none_match` · `gte` · `lte` · `in` · `not_in` · `count_gte` ·
`count_lte`.

- Comparison operators coerce to number when both sides look numeric, else
  string.
- `matches` / `*_match` take a **`pattern`**: anchored where possible, hard
  length cap (e.g. 512 chars), evaluated with a timeout (the `regex` module's
  `timeout=`, or `re` behind a complexity linter that rejects nested
  quantifiers). A pattern that times out → the step is `UNKNOWN`, logged, never
  `PASS`.
- No backreferences beyond `\1..\9`; no inline flags that change global
  behaviour; no `(?{...})` / recursion.

## 4. CE.1 — evidence-only engine (buildable now)

**No new device command. No server. Additive payload.**

- **`utils/compliance_check_engine.py` (new)** — pure functions:
  `resolve_source(subject_evidence, selector) -> value | list | None`,
  `apply_assertion(value, assertion) -> bool | None` (None = inconclusive),
  `evaluate_check(subject_evidence, check, now) -> (status, summary, coverage)`.
  O(checks × subjects) over already-parsed data; cacheable per
  `(config sha256, pack version)`.
- **`utils/compliance_check_pack.py` (new)** — mirrors
  `utils/control_assignment.py`: `POLICY_RELATIVE_PATH =
  state/compliance_checks.json`, `SUPPORTED_SCHEMA_VERSION = 1`,
  `CompliancePackError`, `load_compliance_checks(data_root) -> CompliancePack`.
  Validation: schema, unique `id`, `id` **must not** collide with a builtin
  catalog id (extend-only), every `source` namespace/selector known, every
  `assert.op` known, `pattern` compiles and passes the complexity linter,
  `severity` / `frameworks` well-formed. Malformed → error **before** any
  evaluation. Missing file → no user checks (byte-identical to today).
  `enabled: false` → inert.
- **`utils/compliance_catalog.py`** — the builtin catalog is re-expressed as the
  first pack: `pack_id = securityexpert.builtin.cp-pan`, `pack_version` =
  `CATALOG_VERSION`, `source = "builtin"`. The existing Python evaluators stay as
  the builtin pack's implementation for the frozen/enrichment ids (no rewrite);
  **new** user checks run through the data-driven engine. (A later CE could
  migrate builtin enrichment evaluators to `evidence.steps` too, but that is not
  required by CE.1.)
- **`utils/compliance_posture.py`** — after `_subject_extended_controls`, run the
  user pack's checks for the subject (respecting `applies_to` and the `0.7.1b`
  assignment/waiver resolution), append to `subject["extended_controls"]` with
  `control_class: "user_check"` and `pack: {pack_id, pack_version, source}`.
  `compliance_overview` counts them like any other extended control. Add
  `check_packs: [{pack_id, pack_version, source, checks, enabled}]` (counts +
  ids only) to the payload — **never** the patterns or selectors.
- **UI** — user checks render through the existing `complianceControlCard`
  (they already carry `severity` / `rationale` / `frameworks` / `evidence_summary`
  / the 0.7.2 Explain panel). A small "user-defined" badge distinguishes them
  from builtin. Framework filter chips (0.7.2) work unchanged. **No editor.**
- **Privacy** — check definitions are RuntimeRoot state, never repo content. The
  shareable support bundle carries pack id/version + per-check
  id/status/severity **only** — a `pattern` can encode an internal hostname or
  IP, so it stays local. `--repository-privacy-check` gains a guard that
  `state/compliance_checks.json` is never committed.
- **Validation** — synthetic fixtures + a sample pack; `pytest` + `--render-only`.
  No network, no real-environment gate.

**CE.1 delivers the user's ask** for every check whose evidence is something we
already collect: name it, point it at the evidence, state the expected pattern →
green / not-compliant, folded into the same coverage roll-up.

## 5. CE.2 — curated read-only command primitives (command-gate work)

**Blocked on: each primitive through the 10-point command gate; a
real-environment validation gate.** (The CP device-interaction-safety audit
that formerly gated this closed 2026-08-25 — `backlog.json`
`cp_device_interaction_safety`.)

- **`configuration/command_primitives.py` (new)** — a static registry. Each
  entry: `primitive_id`, vendor, shell/context (e.g. Gaia Clish `clish -c`,
  PAN XML-API op), the exact **read-only** command, timeout, retry policy, max
  frequency per endpoint, session-reuse rule, and a **declared redaction rule**
  (regex/allowlist for its known output shape). No entry is added without the
  gate doc; no write/config-changing command is ever admitted.
- **Collection** — the admission coordinator
  (`utils/collection_executor.py`) runs referenced primitives once per device
  per run, inside the existing 1-per-vendor budget and the CP stage cooldown.
  Output is stored as a new evidence artifact class, redacted by the primitive's
  rule, and exposed to the engine as a `source` namespace
  `primitive.<primitive_id>`.
- **Opt-in first** — a `--compliance-probe` mode (like `--cp-config-probe`) so
  primitive execution is never implicit in a normal run until real-environment
  validated.
- Checks still reference `primitive.<id>` — never a raw command string from the
  pack.

## 6. CE.3 — UI check editor + signed org packs (`DEPLOY.1A`-gated)

Needs the app server + `DEPLOY.1A` OIDC/RBAC (only an authorised role
creates/edits/loads checks) + a persistent store.

- **Check editor** — a Compliance-module screen: name a check, pick the evidence
  source from a browsable tree of what was actually collected, choose an
  operator, enter the expected value/pattern, **test it live against the last
  run** (shows which subjects pass/fail), preview the coverage delta, save.
  Every save audited (who/when/before/after). The server writes the **same**
  `compliance_checks.json` shape (or its Postgres equivalent).
- **Signed org packs** — a pack can carry a detached signature; a trust store of
  authorised publisher keys; an org distributes vetted check packs without a
  product release (the "dynamic/signed pack" direction the 0.6.6B / 0.7.0 packs
  point at). Signature invalid / key untrusted → pack rejected, fail-closed.
- **Pack marketplace / import** — CIS/PCI/BDDK starter packs shipped as signed
  builtin-adjacent packs the operator enables.

## 7. Now vs gated

| Capability | `CE.1` (now) | `CE.2` (command-gate) | `CE.3` (`DEPLOY.1A`) |
| --- | --- | --- | --- |
| Define a check as data (name + evidence + expected + verdict) | ✅ | ✅ | ✅ |
| Multi-step "command series" (`evidence.steps`, `all`/`any`) | ✅ (over collected evidence) | ✅ (+ primitive sources) | ✅ |
| Assert over `current_configuration` / `unified` / `crypto` / `alignment` | ✅ | ✅ | ✅ |
| Run **new** read-only device commands for a check | — | ✅ (curated, gate-reviewed) | ✅ |
| Arbitrary user-supplied command strings | ❌ (never) | ❌ (never) | ❌ (never) |
| Framework tagging + coverage roll-up for user checks | ✅ | ✅ | ✅ |
| Author/edit checks from the UI, live-test, audit | file only | file only | ✅ |
| Signed, distributable org check packs | — | — | ✅ |
| Certification / attestation claim | ❌ (never — same disclaimer) | ❌ | ❌ |

## 8. Roadmap placement

- `CE.1` is the next `compliance_engine` build — its own contract, no server, no
  command gate. Advances `compliance_engine` and `evidence_reporting`.
- New backlog item `compliance_check_engine` (P1, `0.7.x`) with the three
  sub-phases; `CE.2` cross-references `cp_device_interaction_safety` and the
  command gate; `CE.3` cross-references `deploy1_oidc_viewer` and
  `compliance_assignment_ui_and_registry` (same store/editor workstream).
- Fleet-scale note: evaluation stays O(n·m) pure functions over already-parsed
  evidence; cache per `(config sha256, pack version)` — the same note as the
  assignment design.

## 9. Expert perspectives

**Compliance & risk**
- User checks need the same `severity` / risk-weight and the same
  waiver/exception mechanism (`0.7.1b`) as builtin controls — a user check that
  is knowingly-deviated must be `WAIVED`, not silently failing.
- Point-in-time: a check should be replayable against a past run (the
  content-addressed history already stores the config) — design the payload so a
  `history[]` is additive (same as the assignment design).
- Provenance of a verdict must be legible: which step, which selector, what was
  observed vs expected. The `0.7.2` Explain panel is the surface; the engine must
  emit `observed` / `expected` per step (redaction-aware).
- **False positives are the failure mode** of a user-authored engine — a check
  that is wrong is worse than no check. The editor's "test against last run"
  (CE.3) and a `dry_run`/`advisory` check state (verdict recorded, not counted)
  are the mitigations.

**Network security manager**
- Bring-your-own-benchmark: an org has internal standards beyond CIS/PCI/BDDK —
  a check pack is how they encode them without waiting for us.
- Ownership: a pack (or a check) can name an owning team; unassigned failures are
  a visible gap.
- Trend: "our internal-standard compliance went 71% → 88%" needs the history
  plane, not a new collector.

**Network security architect**
- Check packs, the assertion grammar and the primitive registry are all **data /
  declarative**, reviewable and diffable — GitOps-friendly, and eventually
  signed.
- Evidence-plane separation stays first class: a check declares
  `evidence.basis` (`configured` today; `negotiated` is the future runtime
  layer). A check can also assert over an **alignment** outcome, not just a raw
  value.
- The engine is a pure function of `(evidence snapshot, pack)` — trivially
  cacheable, testable, and safe to run at fleet scale.
- **Safety is the whole game for CE.2**: the primitive registry is the single
  chokepoint; if a command is not in it, it cannot run, regardless of what a
  pack says.

**UI / UX**
- User checks must be visually distinct (a "user-defined" badge) and carry a
  provenance line (which pack, which version).
- The editor is progressive: pick evidence from *what was actually collected*
  (no blank text box), choose an operator, type the expected value, see the live
  result. Never a raw-JSON editor as the primary surface.
- First-run / empty-pack / malformed-pack states all explicit.

## 10. Resolved decisions (2026-08-29)

All decision points resolved so CE.1 has a deterministic contract. Each is also
recorded against `project/backlog.json` (`compliance_check_engine` and siblings).

| # | Decision | Ruling | Why |
| --- | --- | --- | --- |
| **D1** | Builtin vs user pack | **Extend-only.** A user check id must match `^x_[a-z0-9_]+$`; builtin catalog ids never start `x_`, so collision is structurally impossible. Override-of-a-builtin is a CE.3-era audited capability, not CE.1. | An un-audited override of a vendor-curated control is exactly the tamper / false-negative risk we cannot govern without the editor's audit trail — and it is far more dangerous once the tool is write-capable. |
| **D2** | Assertion surface | **Fixed operator set + `combine: all\|any` across `steps[]`.** No boolean expression tree, no nesting, in CE.1. Revisit a step-group tree at CE.2 only if real packs need it. | Small, auditable parser/validator; `all`/`any` already covers the BackBox "series"; a full expression language is an injection + complexity surface. |
| **D3** | Regex | **Allowed, anchored, with safeguards:** 512-char pattern cap; compiled at pack load (syntax error → pack rejected); a complexity linter rejecting nested/adjacent unbounded quantifiers and catastrophic alternation; evaluated with a wall-clock timeout. Timeout or eval error → that step is `UNKNOWN` (logged), never `PASS`. No backreferences past `\1..\9`, no global-flag changes, no recursive/conditional constructs. | Pattern matching is the core of the value; the safeguards make it non-dangerous. |
| **D4** | `source` breadth in CE.1 | **All read-only evidence models from day one**, via the namespaced grammar: `current_configuration.sections[id=<sid>].settings`, `unified.device.*`, `unified.interfaces`, `unified.routes`, `crypto_facts.<group>`, `alignment.results`. Unknown namespace/path → pack load error. **Wired:** `current_configuration`, `unified.device`, `alignment.results`, and `crypto_facts.<group>` (the CE.1 crypto-source follow-up — `html_export` threads the privacy-reviewed 0.7.0 fact set keyed by subject id). `unified.interfaces` / `unified.routes` parse and resolve empty until the merged-inventory row is threaded (a later step). | They are all in memory during a posture build; restricting to config projections would force an immediate follow-up. |
| **D5** | Multi-step in CE.1 | **Yes** — `evidence.steps[]`, each = one `source` + optional `select` + one `assert`; `combine: all` (default) / `any`. | Pure data; it is the user's explicit "command series" ask over collected evidence. |
| **D6** | Advisory / dry-run | **`mode: "advisory" \| "enforced"` (default `enforced`).** An `advisory` check's verdict is computed and shown with an "advisory" badge but excluded from the `compliance_overview` numerator **and** denominator and from framework coverage. | The only false-positive guard until the CE.3 live-test editor exists; lets an operator trial a check against real fleet data before it moves the score. |
| **D7** | Waivers for user checks | **Reuse `control_assignments.json` `waivers[]`, keyed by check id.** `utils/control_assignment.py`'s known-id set is extended to include `load_compliance_checks(...)` ids so a dated approved waiver can target a user check. | One waiver mechanism, one audit surface. |
| **D8** | CE.2 primitive execution | **Opt-in `--compliance-probe` mode only.** Promote to a normal-run stage only after a real-environment validation gate. (The CP device-interaction-safety audit (P0) that used to gate this closed 2026-08-25.) | Matches the `--cp-config-probe` precedent; device interaction stays explicit and bounded. |
| **D9** | CE.3 pack signing | **Requirement recorded; mechanism deferred to the `DEPLOY.1` security workstream** (detached signature, publisher trust store, fail-closed on invalid/untrusted). | Signing infra rides on the same trust/secrets work as `deploy1_oidc_viewer` and the CP/PAN trust gates. |
| **D10** | Numbering | **`0.7.3` = the CE.1 build.** `CE.x` stays as design-doc phase labels; CE.2/CE.3/CE.4 get backlog entries, not point-release numbers yet. | — |
| **D11** | Where the engine runs | In `compliance_posture.build_compliance_posture(...)`, a new `_subject_user_checks(...)` after `_subject_extended_controls`, appending to the same `subject["extended_controls"]` with `control_class: "user_check"`. The `compliance_overview` roll-up already iterates `controls + extended_controls`; it filters out `mode == "advisory"` rows from the score. | Minimal new surface; reuses assignment / waiver / roll-up plumbing. |
| **D12** | Payload exposure | New top-level `check_packs: [{pack_id, pack_version, source, checks, advisory_checks, enabled}]` — **counts + ids only**. Per-check result carries `severity` / `rationale` / `frameworks` / `evidence_summary` + a redaction-aware `observed` / `expected` per step for the Explain panel. **Never** the `pattern` or the raw selector string. | A `pattern` can encode an internal hostname / IP — it stays local. |
| **D13** | Selector grammar | `NS ('.' SEGMENT)*`; `SEGMENT := KEY \| KEY '[' KEY '=' VALUE ']'`. Hand-written tokenizer — **no `eval`, no new dependency** (no `jsonpath` lib). | AGENTS.md requires explicit approval for dependency additions; a small parser is auditable and testable. |
| **D14** | `applies_to` miss | Check omitted from the subject's list, counted in `subject["assignment"]["not_assigned"]` — **not** `NOT_APPLICABLE`. | Consistency with the 0.7.1b assignment model. |
| **D15** | Determinism | `evaluate_check(subject_evidence, check)` is a pure function; waiver expiry is applied outside it (`_apply_waiver`, as today). Cache key `(config_sha256, pack_version)` is noted for a later optimisation, not built in CE.1. | Keep CE.1 small; the fleet-scale note stays design-level. |
| **D16** | Pack file location | `data/state/compliance_checks.json` (RuntimeRoot), schema v1, fail-closed, missing file → no user checks (byte-identical to today). `--repository-privacy-check` gains a guard that this file is never committed. | Same class as `control_assignments.json` / `inventory_exclusions.json`. |

## 11. Product trajectory — write capability

The product's stated end-state (owner, 2026-08-29) is a **write-capable device
administration platform**. The current read-only maturity is a deliberate staging
phase along `SEE → VERIFY → TRACE → RECOVER → OPERATE`, **not the ceiling**.

Design consequence, binding on this and every VERIFY-plane design: the models
must be shaped so a future **enforce / remediate** capability is an **additive
layer**, never a rework. Concretely for the check engine:

- A check object reserves an **optional** `remediation` block (unused in
  CE.1–CE.3, rejected by the CE.1 pack validator with a clear "not yet
  supported" error so packs cannot smuggle it in early).
- The verdict vocabulary already has room: a future `REMEDIABLE` sub-state on a
  `FINDING` is additive.
- The `evidence.steps` / `source` / `assert` grammar is reused verbatim for a
  future `verify_after` step (post-remediation re-check).
- The primitive registry (CE.2) is the single chokepoint; a **write** primitive
  is the same registry with `mode: "write"` and the full OP.2 gate stack.

## 12. CE.4 (design label) — remediation checks (HARD-GATED, not scheduled)

A check gains `remediation: { primitive: "<write_primitive_id>", parameters: {…},
verify_after: [ …steps… ] }`. On an operator-authorised, in-window run the engine
may execute the write primitive, then re-run `verify_after`, then record an
immutable audit entry; auto-rollback on `verify_after` failure.

**Gated on every prerequisite in `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`
§10** (the same bar as `failover_controlled_execution` / OP.2): mature
VERIFY/TRACE/RECOVER, the `DEPLOY.1` server, `DEPLOY.1A` OIDC + an RBAC OPERATE
role + full audit, the CP device-interaction-safety audit, the network-device
command gate for every read **and write** primitive, and a signed
change-management / safety review with the network-security leads. No CE.4
implementation until all are met. Backlog: `compliance_remediation_checks`
(`deferred`).
