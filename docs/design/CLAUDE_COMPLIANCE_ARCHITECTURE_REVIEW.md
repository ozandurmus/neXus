# Architecture Review — `ui2-compliance` Microservice

**Reviewer role:** Enterprise Security Architect (Claude / Fable)
**Document under review:** `docs/design/UI2_COMPLIANCE_MICROSERVICE_ARCHITECTURE.md` (DRAFT, 2026-09-19)
**Review basis:** `AGENTS.md` (level 1), architectural invariants, evidence/identity/privacy law. Framework content assessed against published standard text.

---

## Verdict

# [APPROVED WITH RECOMMENDATIONS]

The service boundary, stateless evaluation model, and — above all — the fail-closed `DATA_UNAVAILABLE` directive are architecturally sound and materially better than commercial practice. I would freeze this shape.

Seven items are **blocking-grade** and must be resolved before contract freeze, because each one produces a *confidently wrong number in front of a bank auditor*, which is worse than no product:

| # | Defect | Section |
|---|---|---|
| B1 | Scoring excludes `DATA_UNAVAILABLE` from the denominator → a device we know nothing about can score 100% | §3 |
| B2 | Assignment selectors key on hostname patterns (`FW-PCI-*`) — direct violation of the presentation-identity law | §4 |
| B3 | Framework mapping references are asserted without provenance; several are numerically wrong against the standard text | §1 |
| B4 | No `evidence_grade` dimension — management-plane intent will be scored as device truth (fatal for the PAN-OS phase) | §5 |
| B5 | No `evaluation_scope` — ClusterXL members / VSX virtual systems have no defined evaluation unit | §2, §5 |
| B6 | COBIT/BDDK rendered as a compliance percentage is a category error, and BDDK has zero mappings behind its card | §1 |
| B7 | Several proposed controls are inherently value-bearing (syslog/NTP/management subnets/admin principals) with no stated sanitization contract | §6 |

Everything else below is a recommendation, ordered by weight.

---

## 1. Financial & Banking Compliance Coverage

### 1.1 The mappings are the product. Right now they are unsourced.

For a bank, the control logic is commodity; the **framework mapping is the asset**, because that is what gets handed to an auditor. The catalog currently asserts references (`CIS 2.1.1`, `PCI-DSS 8.3.6`, `DSS05.04`) with no provenance field and no verification state. Under the vendor-semantics law, a reference number is exactly the kind of load-bearing semantic that must be evidence-backed rather than recalled.

Checking the asserted mappings against the standard text surfaces real errors:

- **"Account lockout after N failed attempts (≤5) [PCI-DSS 8.3.4]"** — 8.3.4 requires lockout after *not more than 10* invalid attempts, with a minimum 30-minute lockout duration. A ≤5 threshold is a legitimate stricter internal baseline, but the mapping as written implies PCI mandates 5. It does not. The lockout *duration* requirement is also missing entirely from the control.
- **"Password history ≥5 generations [PCI-DSS 8.3.7]"** — 8.3.7 prohibits reuse of any of the last **four**. Again stricter-is-fine, mapping-claim-is-wrong.
- **"Inactivity timeout ≤10 min [PCI-DSS 8.2.8]"** — 8.2.8 specifies **15** minutes.
- **"Password complexity required [NIST-800-53 IA-5(1)]"** — in Rev 5, IA-5(1) no longer carries the composition rules it did in Rev 4, and SP 800-63B actively discourages composition and forced rotation. Mapping a complexity control to IA-5(1) as *satisfying* it is defensible for CIS/PCI and misleading for NIST.
- **CIS section numbers** — I cannot verify these without the benchmark text, and neither should the catalog. Treat every CIS reference as unverified until sourced.

**Required fixes:**

1. Add to every mapping entry: `source_document`, `document_version`, `section_verified_by`, `verified_at`, `verification_status ∈ {VERIFIED, UNVERIFIED, DISPUTED}`. An `UNVERIFIED` mapping renders as such in the UI and **does not contribute to that framework's card**.
2. Add `mapping_strength ∈ {SATISFIES, CONTRIBUTES_TO, EVIDENCE_FOR, STRICTER_THAN}`. `STRICTER_THAN` cleanly resolves the ≤5/≤10 and ≤10min/15min cases without either weakening the baseline or misquoting the standard.
3. Pin framework versions explicitly. The brief says **PCI-DSS v4.0.1**; the document body says v4.0. v4.0.1 is the current errata revision and the future-dated v4.0 requirements are now in force — the catalog must carry the exact version it was mapped against, and scores must be reproducible against that version.

### 1.2 Missing controls that a financial auditor asks for first

The catalog is a solid **Gaia OS hardening** set. It is missing the controls that carry the most audit weight:

- **MFA for administrative access (PCI 8.4.2 / 8.4.3, 8.5).** Absent. For a CDE-adjacent firewall this is a top-tier finding. Evaluable as: admin authentication delegated to a remote MFA-capable source, local accounts restricted to break-glass.
- **Saved vs. running configuration consistency (PCI 1.2.8).** Absent, and it is the single highest-value drift control — it is a core BackBox/Tufin differentiator and maps directly to a named PCI requirement.
- **Failure detection of critical security controls (PCI 10.7.2 / 10.7.3).** A v4.0 addition that maps almost perfectly onto neXus's existing HA/health evidence plane. This is a differentiator you already have the data for.
- **Configuration backup existence and recency; software/JHF currency and end-of-support date; license, contract, and SIC/certificate expiry.** These are the operational-hygiene checks that make the Indeni/BackBox comparison credible.
- **NTP source restriction and authentication (PCI 10.6.2 / 10.6.3).** "Two NTP servers configured" proves redundancy, not time-source integrity.
- **Log retention ≥12 months / 3 months immediately available (PCI 10.5.1).** Belongs to the log-server plane, not the firewall — model it as `NOT_APPLICABLE (out_of_plane)` rather than omitting it, so the gap is visible.
- **NIST anchoring is thin.** Most hardening controls should anchor on **CM-6 (configuration settings)** and **CM-7 (least functionality)**, plus AC-7, AC-11/AC-12, AC-17, AU-8, AU-9, SC-8, SC-13. Two mappings (IA-5(1), AU-4) across twenty controls understates real coverage.
- **SP 800-41 Rev 1 is guidance, not auditable controls.** Cite it as rationale; never as a scored denominator.

### 1.3 Scope boundary: device plane vs. policy plane

The catalog is ~100% OS hardening and ~0% **rulebase hygiene** — any-any rules, shadowed/duplicate/unused rules, permissive services, rules without logging, rules without owner/comment, expired objects, anti-spoofing. That is where PCI 1.2.5, 1.2.7, 1.3.x and NIST CM-7 actually live, and it is the first thing an auditor requests.

This is *correctly* out of Release 1 scope, because rulebase evidence is management-plane and needs its own collection contract and command gate. But it must be **declared**, not left implicit:

- State the boundary in §1: *"Release 1 evaluates the device/OS configuration plane. Policy-plane compliance is a separate contract."*
- Add a `control_plane ∈ {device_os, security_policy, operational_hygiene}` dimension **now** and reserve the ID namespace, so that adding policy controls later does not silently redefine what "88% PCI" meant last quarter.
- `operational_hygiene` (license expiry, disk, cluster sync) must be scored **separately** from regulatory compliance. Indeni-style predictive checks are readiness signals, not findings; blending them into a PCI number corrupts the metric in both directions.

### 1.4 COBIT / ISO / BDDK (B6)

COBIT 2019 compliance is measured as **process capability (0–5)** against management objectives, not as device pass/fail. A "COBIT: 88%" card computed from firewall settings will not survive contact with an internal audit function. Correct model: firewall evaluation runs are **evidence contributions** to `BAI10` (Managed Configuration), `BAI06` (Managed IT Changes), `DSS05.02/.04`, `MEA03`. Render the card as *"produces evidence for N objectives"*, never a percentage.

Same for ISO 27001 — and if you add it, use the **2022 Annex A** numbering (A.8.9 configuration management, A.8.15 logging, A.8.16 monitoring, A.5.15 access control). Citing A.12.x is stale.

**BDDK is the sharpest risk.** It appears as a first-class UI card with no mapped controls and no cited regulation article. For a Turkish financial institution this is the *binding* regulation — COBIT/ISO are merely how you evidence it. Either map it at article level against the Bilgi Sistemleri Yönetimi regulation and its Tebliğ, or remove the card until you do. An unmapped regulator card in a banking UI is an audit and reputational liability. Note also the data-residency consequence: compliance evidence and exports must stay in-region, which constrains any future external export path.

---

## 2. `DATA_UNAVAILABLE` — Sound, and Under-Specified

**Directive 3 is correct and I would defend it strongly.** It is a direct expression of the UNKNOWN/fail-closed law ("absence of evidence is not evidence of absence; collection failure is not a known-bad state"), and it is strictly better than the industry norm of silently omitting unevaluable checks — which is a genuine audit defect in competing products. Keep it, and make it a marketing position.

The flaw is that one bucket collapses seven states with different owners, different remediation, and different audit meanings.

### 2.1 Decompose the unevaluable state

| Sub-state | Meaning | Owner |
|---|---|---|
| `NOT_COLLECTED` | No collector/command exists yet | neXus engineering backlog |
| `COMMAND_NOT_APPROVED` | Collectable in principle; command has not passed the network-device command gate | Gate (constitutional state, not a bug) |
| `COLLECTION_FAILED` | Collector ran and errored/timed out/auth-failed | Operations — **and is itself a finding** |
| `EVIDENCE_STALE` | Collected, but older than the control's `max_evidence_age` | Scheduler |
| `PARSE_UNSUPPORTED` | Output collected, parser did not recognise the shape | Engineering — silent-wrong-answer risk |
| `SEMANTICS_UNPROVEN` | Field present, meaning not established by vendor docs or a frozen contract | Vendor-semantics law |
| `AMBIGUOUS` | Conflicting or multiple candidate values (e.g. cluster members disagree) | Investigation |

`COLLECTION_FAILED` deserves emphasis: **a device you cannot audit is a compliance risk**, not a neutral gap. It should surface as an operational alert, not sit quietly in an amber tile.

### 2.2 `NOT_APPLICABLE` is the score-inflation vector, not `DATA_UNAVAILABLE`

The document treats NA as a clean exclusion ("standalone vs cluster"). But determining that a device is standalone is itself an evidence claim about the operational unit. Un-evidenced NA is the classic way every compliance product inflates its score.

**Require:** `NOT_APPLICABLE` carries a `proof_ref`. Where applicability cannot be proven, the verdict is `APPLICABILITY_UNKNOWN` — a distinct, visible, non-excluded state.

### 2.3 Cluster and VSX scope (B5)

Per the constitution, ClusterXL member differences are `MEMBER_SPECIFIC` unless expected-state evidence proves otherwise, and VSX identity is physical endpoint + VSID. The spec has no evaluation unit at all. Add to every control:

```
evaluation_scope: device | member | operational_unit | virtual_system
```

Password policy divergence between two cluster members is not "one PASS, one FAIL" — it is a `MEMBER_DIVERGENCE` finding in its own right. Without this dimension, a 60-firewall estate containing VSX produces a denominator nobody can explain.

### 2.4 `is_collected` is the wrong shape

`evidence_requirement.is_collected: true` is a static boolean on a control. Collectability is a property of `(vendor, platform, version, collector release, gate status, this specific device)`, resolved at evaluation time from a capability/coverage registry. Remove the boolean; the control declares only its *requirement*.

Relatedly, `required_command: "show password-controls"` in the catalog risks becoming a de facto command authority. Per the constitution, **command presence in source is not command approval**. The catalog must reference a **gate entry ID**, and a control whose gate entry is not approved resolves to `COMMAND_NOT_APPROVED`. The commands the document names as gaps (`show snmp v3 users`, `show ssh ciphers`, `show ip interface detail`) need gate entries before any collection work starts.

### 2.5 Auto-activation needs a guard

When collection expands and a control flips from `DATA_UNAVAILABLE` to `FAIL` across 60 devices overnight, the drift metric spikes and someone is paged for a *reporting* change, not a security change. Record `first_evaluable_at` per `(control, device)` and classify `DATA_UNAVAILABLE → FAIL` as **`newly_revealed`**, never as a regression. See §3.3.

---

## 3. Scoring and Drift

### 3.1 The denominator defect (B1)

The verdict table states `DATA_UNAVAILABLE` is *"excluded from denominator."* Consequence: a device with 19 of 20 controls unevaluable and 1 PASS scores **100%**. This is the most dangerous line in the specification and it inverts the intent of Directive 3 — the fail-closed collection posture is undone by a fail-open score.

### 3.2 Publish a vector, derive the headline

Never emit a single number that mixes **posture** with **visibility**. With weight `w(c)` from severity:

- **Compliance (evaluated basis)** = `Σ w·PASS / Σ w·(PASS+FAIL)` — "of what we could check." **Never displayed alone.**
- **Coverage / Assurance** = `Σ w·evaluable / Σ w·assigned_applicable` — the visibility metric.
- **Assured Compliance (audit basis)** = `Σ w·PASS / Σ w·assigned_applicable` — unevaluable counts against you.

These satisfy `Assured = Compliance × Coverage`, which is self-explaining in the UI:

> **74% assured** — 88% of what we can verify, and we can verify 84%.

Make **Assured** the headline tile. It is the only one of the three consistent with a fail-closed constitution, and it makes the visibility gap commercially visible, which is exactly what drives collection-coverage investment.

### 3.3 Drift: two different concepts, currently conflated

- **Configuration drift** — the evaluated *evidence value* changed between runs, regardless of verdict. This is what change-management auditors want (PCI 1.2.2).
- **Compliance drift** — verdict transitions.

Classify transitions into separate, non-interchangeable buckets:

| Transition | Class | Scored? |
|---|---|---|
| `PASS → FAIL` | Regression — the alarm | Yes, full weight |
| `FAIL → PASS` | Remediation | Yes, credited |
| `PASS → unevaluable` | **Visibility loss** — usually a broken collector or failed auth | Operational alert, half weight |
| `DATA_UNAVAILABLE → FAIL` | **`newly_revealed`** — pre-existing finding, now visible | Reported, **not** a regression |
| new control / mapping change | **`catalog_delta`** | Excluded from drift entirely |

Compute drift over the **intersection of controls evaluable in both runs under the same catalog version**. Otherwise every catalog release looks like a security incident. Lead the UI with raw counts; the normalized score is secondary.

Add a **`baseline_run_id`** (approved baseline / last audit snapshot). Banks measure drift against a point-in-time audited baseline, not merely against yesterday.

### 3.4 Reproducibility is an audit requirement

Every run must persist `catalog_version`, `catalog_hash`, `scoring_profile_version`, `assignment_snapshot_id`, `evidence_snapshot_ids`, `engine_version`. **Never recompute a historical score with a new catalog.** An auditor who cannot reproduce last quarter's 88% will discount every number the product emits.

Make severity weights a versioned **scoring profile** object (start: critical 10 / high 5 / medium 2 / low 1) so a bank can align it to its own risk appetite, and support `severity_override` at assignment scope — the same control is critical on a CDE firewall and medium in a lab.

### 3.5 `WAIVED`

Must not silently behave as `PASS`. Publish both *with-waivers* and *excluding-waivers* compliance — auditors ask for the latter. Waivers require approver identity, ticket reference, and expiry; **the engine enforces expiry** (expired → auto-revert to `FAIL`), never a human calendar. Cap maximum duration by severity (e.g. critical ≤ 90 days). Add a `compensating_control` field — PCI formally recognises these and assessors will ask.

### 3.6 Per-framework scoring

Compute each framework score over that framework's mapped control set only; a control mapping to four frameworks contributes to four denominators. Only `SATISFIES` mappings count. Crucially, show **requirement coverage** alongside pass rate — *"14 of 31 in-scope NSC requirements are technically evaluated"* — and label the card **"Technical control coverage — not a compliance attestation."** An 88% PCI card implies a PCI position this tool cannot support, and in a bank that is a liability, not a feature.

---

## 4. Per-Device Assignment Architecture

**B2 — hostname selectors must go.** The document proposes *"assign `PCI-DSS` to `FW-PCI-*`."* The constitution is unambiguous: *"Presentation identity != security identity. A hostname, display label, or inferred ordinal is never a join key or an identity gate."* A renamed device silently drops out of PCI scope, and nothing in the record shows it happened. Replace with explicit tags/labels on the device record carrying provenance (who set it, when, from which inventory source). A hostname-pattern helper may *propose* tag assignments in the UI for human confirmation; the stored selector is always the tag.

Beyond that:

1. **Policy-based, snapshot-resolved.** Model `assignment_policy` objects (selector + profile + priority + include/exclude/override) and resolve them at run start into an immutable `assignment_snapshot` referenced by the run. This buys reproducibility, an explainability trace (*"why is this control on this device?"*), and scale beyond 60 devices.
2. **Total, deterministic precedence.** e.g. device override > device group > tag selector > vendor default > global; ties broken by explicit priority then policy ID. Document the algorithm and test it over a **generated matrix** — the same pattern `tests/test_architecture_convergence.py` already applies to OP.0a verdicts.
3. **Separate *assigned* from *applicable*.** Two independent layers; see §2.3.
4. **Assignment changes are governance events.** Who, when, ticket. And critically: *unassigning a control that currently FAILs* is functionally a waiver without a record. Require a reason code for such removals, and surface them in a governance view. Otherwise the score is trivially gameable from inside the UI.

---

## 5. Multi-Vendor SPI (Check Point → Palo Alto)

**Answer to review question 4: a uniform model is sufficient as a *contract*, but only if it stops trying to be a portable expression language.** Split into three layers:

- **Control** (vendor-neutral): intent, rationale, severity, framework mappings, remediation guidance. One control = one security intent — *"administrative sessions terminate after ≤10 minutes idle."*
- **Binding** (vendor/platform-specific, N per control, keyed by `(vendor, platform_family, version_range)`): evidence requirement, gate entry ID, assertion, type rules, applicability constraints.
- **Evaluator SPI** (per vendor): `supports(device_profile) → capability set`, `resolve(requirement, snapshot) → Fact | Unavailable(reason)`, plus vendor normalization rules.

Assertion evaluation stays in the shared engine with the **closed operator set** already established in `COMPLIANCE_CHECK_ENGINE.md`. Keep it closed: no expression DSL, no regex-against-raw-config, no `eval`. The vendor-specific work belongs entirely in *resolution*, not in *assertion*.

Why the split matters concretely: Gaia's idle timeout is a Clish setting; PAN-OS's is `deviceconfig/system/idle-timeout` in the config tree — same intent, different default semantics, different "unset" meaning, different evidence path. One rule shape forced across both yields either a lowest-common-denominator engine or a leaky one.

### 5.1 Evidence grade is the PAN-OS blocker (B4)

The constitution states plainly: *Panorama = discovery/intent/provenance; direct firewall = actual/effective evidence; primary current configuration evidence = `effective-running`.* A control evaluated from a Panorama template is **intent**; from the firewall's `effective-running` it is **actual**. These are different claims with different strength.

Every result item must carry:

```
evidence_grade: DIRECT_EFFECTIVE | DIRECT_CONFIGURED | MANAGEMENT_INTENT
```

and the scoring layer must not treat `MANAGEMENT_INTENT` as proof of device state. For a bank this is the difference between *"the template says TLS 1.2"* and *"the firewall is running TLS 1.2."* Design this in now — retrofitting it after Check Point ships will require rewriting every persisted result. The same asymmetry exists on Check Point (management server vs. Gaia device); introducing the dimension in Phase 1 costs almost nothing.

### 5.2 Vendor defaults are the silent-wrong-PASS vector

On PAN-OS, a setting may be inherited from a template, overridden locally, or absent — and absent means *the version default applies*. "Absent" is neither non-compliant nor unavailable. Model an explicit `DEFAULT_APPLIED` fact origin sourced from a **versioned vendor-default table**; where the default is not proven for that version, emit `SEMANTICS_UNPROVEN`, never an assumed value. Gaia has the identical problem. This is where commercial tools most often produce a confident wrong answer.

### 5.3 Typing, under the identity law

Assertion operators must be type-aware and must **not coerce**. Comparing `"012"` to `12` is a typed error, not numeric equality — no leading-zero stripping, no truncation, no case normalization on identifiers. Enum-like normalization (cipher names) requires an explicit documented mapping table, never a blind `lower().strip()`. A type mismatch resolves to `PARSE_UNSUPPORTED`.

### 5.4 Catalog as versioned, validated, lifecycle-tracked content

Ship the catalog as hash-pinned content in the repository, not as code and not as mutable DB rows. Then:

- CI validates the catalog schema and every mapping's provenance fields.
- **Every binding carries fixtures**: at least one PASS, one FAIL, one unavailable.
- Apply the repository's own lifecycle law to catalog content: each binding carries `PLANNED → IMPLEMENTED → AUTOMATED_VALIDATED → REAL_ENV_VALIDATED`. Per the constitution, a fixture-built test proves the parser, not the vendor's real output shape — so **a binding that has not reached `REAL_ENV_VALIDATED` must be visibly flagged and must not silently contribute to a score shown to an auditor.**

That last mechanism is what keeps "20+ financial-grade checks" honest as the catalog grows to 300.

---

## 6. Frontend Compliance Screen

### 6.1 Metric tiles

- Replace the bare `88%` with **Assured Compliance** plus its decomposition (§3.2).
- `Data Gaps: 6` is a count without a denominator — make it a **coverage rate**.
- `Evaluated Firewalls: 60/60` is misleading when a device was "evaluated" with 90% of its evidence missing. Split into **full evidence / partial / no evidence**.
- Every tile carries `as of` and `catalog version`.

### 6.2 Table and filters

Add **evidence grade** and **staleness** columns. Render the status breakdown as a stacked bar with the unevaluable portion visually distinct. **The default filter must never hide `DATA_UNAVAILABLE`** — doing so silently reinstates the industry behaviour Directive 3 exists to reject.

### 6.3 Detail drawer and remediation

Read-only remediation text is acceptable, with hard constraints:

- It is **static catalog content**, server-provided, parameterized only by catalog constants — never assembled from device evidence (that is how values leak into the browser).
- No submit path. Per the architectural invariant, no command, argv fragment, path, or API route originates in the browser. A future "remediate" capability is a CLASS 1+ controlled write requiring its own `RB.x` contract and a typed-intent registry entry, not a button added to this drawer.

### 6.4 Privacy — the largest frontend risk (B7)

Several proposed controls are inherently value-bearing: *restrict admin GUI to trusted subnets* (management topology), *dual NTP servers* (internal infrastructure), *remote syslog configured* (SIEM address), *non-default admin username* (principal), *unused interfaces disabled* (interface inventory). Default posture per the sensitive-identity reporting law is **compare locally, report the relationship**:

- The evaluator receives values, decides, and emits verdict + **safe descriptors** — `syslog_target_count: 2`, `all_targets_in_approved_set: MATCH`, `mgmt_access_scope: RESTRICTED` — never the addresses.
- Where an operator genuinely needs a value to remediate, gate it behind a role **and** an audit-log entry, and exclude it unconditionally from exports and replay.

**Answer to review question 5 (`aiview`):** do not build a masked copy of the privileged object. Masking-after-the-fact is precisely how leaks occur — one new field, one serializer change, and the mask is bypassed. Build the **sanitized projection as the default shape**, and make the privileged view an additive, role-gated exception. `aiview` then receives a structurally different object that never contained the values.

On HMAC: it is appropriate for **stable pseudonymous correlation** ("same value appears on 14 devices") but it is **not anonymization over a small domain**. An HMAC of an RFC1918 /24 or of the username `admin` is trivially recoverable by an adversary who obtains the key, and the domain is enumerable. Prefer **coarse classes** (`RFC1918` / `PUBLIC` / `IN_APPROVED_SET`) wherever correlation is not actually required; reserve HMAC for cases where it is, with a per-tenant secret.

Finally: **define the audit-export contract now.** A bank will request PDF/CSV on day one, and its sanitization posture, privacy-gate coverage, and in-region storage (BDDK/KVKK) are architecture, not a later feature.

---

## Answers to the Document's Own Review Questions

**1. Storage & isolation.** PostgreSQL owned by `ui2-service`; `ui2-compliance` stays stateless with **zero DB access and zero egress**. But separate the two lifecycles: the *catalog* is a code-grade artifact baked hash-pinned into the image and reviewed like code; *assignments, runs, items, waivers* are data in PG. If customer-authored controls are later required, model them as a distinct overlay with its own ID namespace, and never let a custom control claim a framework mapping without provenance. Add a NetworkPolicy allowing only `ui2-service` to reach `:8085`.

**2. Stateless evaluation.** Yes — POST `/api/v1/compliance/evaluate` with persistence in `ui2-service` matches the `ui2-configuration` pattern and is correct. Three caveats: the request carries an **evidence snapshot reference**, not inline raw config; the engine is **deterministic** (no clock or randomness except an injected `evaluated_at`); and define chunking plus idempotency keys now — 60 × 20 is trivial, 2000 × 300 is not. Return `engine_version` and `catalog_hash` in the response and persist them on the run.

**3. Scoring formula.** §3.2 — publish Compliance, Coverage, and Assured; headline Assured; never exclude unevaluable from the audit-basis denominator.

**4. Multi-vendor SPI.** §5 — uniform *Control*, vendor-specific *Binding*, per-vendor *resolution*, shared closed-operator *assertion*. Add `evidence_grade` and `evaluation_scope` in Phase 1.

**5. `aiview` integration.** §6.4 — sanitized projection as the default shape, not a mask over the privileged one.

---

## Pre-Freeze Checklist

1. Resolve B1–B7.
2. Reconcile against `COMPLIANCE_CHECK_ENGINE.md` and `COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md`. **I did not read those documents in this review** (no-tools constraint), so any overlap or contradiction with them is unverified. Per the authority hierarchy, report any contradiction for human resolution — do not silently reconcile.
3. Open network-device command-gate entries for every `DATA_UNAVAILABLE` command pointer (`show snmp v3 users`, `show ssh ciphers`, `show ip interface detail`, and the saved-vs-running and MFA-source controls) **before** collection work begins.
4. Source and verify every framework mapping; mark the remainder `UNVERIFIED` rather than displaying it.
5. Confirm whether `tests/fixtures/uitest/` and the HTML render harness apply to the React `ui2/frontend` screen, or whether a distinct fixture gate is needed.
6. Freeze the contract. This introduces new vendor semantics, new commands, and a new scoring/identity model — implementation must not start from a `DRAFT`.

**Recommended next movement:** `CONTRACT` at medium reasoning to fold B1–B7 into a freeze candidate. The remaining catalog expansion (§1.2) is `DOCS`-class content work at low reasoning and does not need this tier.
