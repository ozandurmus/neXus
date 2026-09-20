# Architectural & Security Review — Palo Alto (PAN-OS) Compliance Evaluation

**Reviewer:** Fable, Enterprise Security Architect
**Scope:** Implementation plan for PAN-OS compliance in `ui2-compliance` / `ui2-worker` / `ui2-service`
**Movement type:** `ARCHITECTURE` (pre-implementation review)

The overall shape is right: tri-metric posture, fail-closed defaults, vendor-parallel evaluators behind a stateless server, and parity with the Check Point path are the correct instincts. My objections are not to the shape — they are to a set of decisions that are **data-model decisions, not implementation details**, and which in their current form conflict with constitutional law or would emit materially wrong compliance verdicts to an audit-facing consumer.

---

## 1. Catalog Completeness & Multi-Framework Mapping

**[BLOCKER] The CIS section numbers are asserted, not proven.** Control IDs encode `1.2.1`, `1.3.4`, `4.1.1` as if they were CIS Palo Alto Firewall Benchmark citations. The plan never names the benchmark **document version** (the 9.x, 10.x and 11.x benchmarks differ in both numbering and thresholds), and provides no mapping evidence. A control ID that carries a false citation is worse than an uncited one — an auditor will look it up. Per vendor-semantics law, a load-bearing external citation requires the official document, not model or developer recollection. I cannot confirm these numbers from memory and neither should the implementer. Until each ID is traced to a named benchmark version + section, the mapping is `UNKNOWN`.

**[BLOCKER] "Every control satisfies or contributes to 4 frameworks" is manufactured coverage.** A single control cannot carry one threshold across four frameworks that disagree. Concretely:

- **Idle timeout ≤ 15 min** is the PCI-DSS value. CIS's PAN-OS recommendation is, to my recollection, stricter — verify. Branding a PCI threshold as CIS is a mapping defect.
- **Password complexity (#6)** and **90-day expiration (#8)** run *against* NIST SP 800-63B, and NIST SP 800-53 Rev 5 revised IA-5 to align with it. Mapping "password must expire every 90 days" to "NIST SP 800-53 Rev 5" needs the exact control/enhancement identifier, and may not survive the check.
- **Password length ≥ 12** matches PCI-DSS v4.0.1 (8.3.6); it may or may not match the CIS benchmark's value.

**Required model change:** framework mapping must be many-to-many with **per-framework parameters** and a per-framework relation of `SATISFIES` vs `CONTRIBUTES_TO`. One control, one threshold, four framework badges is not an auditable structure — it is a badge wall.

**[BLOCKER] "Financial Baseline (BDDK)" has no source of record.** BDDK obligations derive from a published regulation with article numbers. A framework that scores banking customers must point at a `FROZEN` repository document citing those articles, or it must not ship as a scored framework. Shipping an uncitable "financial baseline" percentage to a regulated institution is the highest-consequence item in this plan.

**[MAJOR] The score's denominator misrepresents benchmark coverage.** `Assured Compliance = Pass / Total Assigned` where `Total Assigned` is 24 controls yields "100% CIS compliant" for a device measured against a small fraction of the actual benchmark. The tri-metric model correctly handles *uncollected evidence* but has no dimension for *unmodeled controls* — and unmodeled controls are not passing controls. Add an explicit **catalog-coverage denominator** (`24 of N benchmark controls implemented`) surfaced everywhere a score is surfaced.

**[MAJOR] Catalog is device-hardening-only.** No security-policy hygiene (any-any rules, log-at-session-end, security profiles attached to allow rules), no threat-prevention profile state, no **content/threat signature update currency** — the highest-value single indicator on a PAN firewall and directly reachable by PCI-DSS 6.3.3 / NIST SI-2 — no admin role separation or MFA on admin auth, no certificate expiry. A bounded v1 is acceptable; an *undeclared* bounded v1 is not.

**[MAJOR] Control #22 is unimplementable as written.** "Unused network interfaces administratively down" — "unused" is not determinable from configuration. Either define it structurally (no zone, no IP, not an aggregate member, not an HA link) or the control is `NOT_EVALUABLE` by construction.

---

## 2. PAN-OS XML Parsing & DLP

**[BLOCKER] Persisting the sanitized config to `device_configuration_run.sanitized_text` and evaluating against it inverts the raw-evidence law.** The mandated lifecycle is *response in memory → parse minimum semantics → safe enums/counters/relationships → discard raw*. The plan proposes retaining a full PAN-OS running configuration with a denylist sanitizer bolted on. Even with perfect secret removal, that artifact is the complete security rulebase, NAT topology, internal address objects, VPN peer endpoints, zone/interface layout, admin usernames, LDAP bind DNs and syslog targets — a network blueprint, not evidence.

**Required:** evaluate in-worker, in-memory, and persist **only** per-control verdict records — `control_id`, `status`, framework refs, `evidence_kind`, `read_kind`, `panos_version`, and an observed value *only when it is a bounded safe scalar* (a timeout integer, a server count). If the Configuration plane independently needs a stored artifact, that is a separate contract with its own retention and privacy definition; compliance must not inherit it by convenience.

**[BLOCKER] The denylist is incomplete and "etc." is not a contract.** Beyond the four named tags, PAN-OS carries at minimum: `<password>`/`<passwd>`, `<secret>` (RADIUS/TACACS), `<bind-password>`, `<auth-key>` and `<priv-key>` (SNMPv3), `<community>`, manual IPsec `<esp>`/`<ah>` keys, `<passphrase>`, `<api-key>`, `<client-secret>`, `<master-key>`, certificate and key blobs, HSM credentials, telemetry tokens, and PAN's `-AQ==`-prefixed encrypted values (still credential material). **Use an allowlist extraction strategy, not a denylist**: extract the ~40 XPaths the 24 controls need; never carry the remainder forward. A denylist fails open on the next PAN-OS release that adds a tag.

**[BLOCKER] The XML parser must be hardened against untrusted input.** This XML arrives over the network from a device. Disable DTDs and external general/parameter entities, disable XInclude, enable secure processing, and bound entity expansion. Absent from the plan; must be explicit and test-covered.

**[MAJOR] "Fail-safe pattern extraction" (regex fallback) must never produce a PASS.** Regex over XML matches inside comments, CDATA, a different vsys subtree, or a Panorama template block. Under *collection success ≠ semantic correctness*, a regex-derived PASS is fabricated certainty. On DOM parse failure the verdict is `COLLECTION_FAILED` — full stop. Regex may at most raise `AMBIGUOUS`.

**[MAJOR] FAIL results must report relationships, not values.** A `permitted_ip_addresses` failure must emit `MISSING` or a count — never the address list. A `login_banner` failure must not echo banner text. Apply the sensitive-identity reporting vocabulary (`MATCH` / `MISMATCH` / `MISSING` / `NOT_EVALUABLE` / `AMBIGUOUS`) to observed values in both the API and the UI.

---

## 3. Fail-Closed & the `PAN-GATE-*` Entries

**[BLOCKER] No PAN-OS version awareness anywhere in the design.** XML paths, default values and benchmark thresholds all move across 9.x/10.x/11.x. Without a recorded `panos_version` and a declared proven-version range, every verdict is unqualified. Outside the proven range → `NOT_EVALUABLE`.

**[BLOCKER] Control #24 reads "absent = compliant."** Absence of an SSH/TLS cipher block means *platform defaults*, and defaults vary by version. `none_match` over an absent element is precisely *field presence ≠ field semantic proof*. This must be `UNKNOWN` unless the per-version default set is documented in a frozen contract.

**[MAJOR] Three of the four gates are probably unnecessary — and one may be real.** SNMPv3 mode, interface admin state, and DDNS client state are all present in the configuration read the product already performs. Per the command-gate rule, *a parse-scope extension of an already-issued command is not a command addition*. Filing gates for these manufactures `DATA_UNAVAILABLE` for evidence already in hand, which understates compliance — an inaccuracy in the other direction. Conversely, if #22 is redefined to require runtime interface state, that *is* a new command and needs a real gate entry before the control ships. Resolve each gate individually against the existing approved read; do not carry four placeholders.

**[MAJOR] Collapsing distinct unknowns into one status destroys operator signal.** Keep `DATA_UNAVAILABLE` (evidence absent), `COLLECTION_FAILED` (transport), `NOT_EVALUABLE` (semantics unproven / version out of range), `GATE_PENDING` (command unapproved) and `NOT_APPLICABLE` (platform/mode) distinct. The tri-metric denominators depend on the distinction, and so does the remediation path.

**[MAJOR] HA1 encryption (#20) is a one-sided claim.** A single member's configuration is not proof about the pair. Evaluate per member and emit a relationship verdict; a peer's self-report is not corroboration.

---

## 4. Routing & Mixed-Fleet Safety

**[BLOCKER] Evidence-source ambiguity: `ACTIVE` *or* `EFFECTIVE_RUNNING`.** These are different evidence grades, and repository law names `effective-running` as primary. Worse, they produce different *answers*: a Panorama-template-pushed setting appears in effective-running but not in the local `<deviceconfig>`, so evaluating `ACTIVE` yields **false FAILs across every template-managed device in the estate**. Pick one authoritative read kind, record it on every verdict, and never silently substitute the other.

**[BLOCKER] No VSYS scoping.** The plan reads only `<shared><log-settings>`. On multi-vsys devices, log forwarding, permitted-IP and admin scope are vsys-scoped — producing both false FAILs (config lives under `<vsys>`) and false PASSes (shared exists, vsys overrides). The product already models vsys context in inventory. Compliance result identity must be `(device_id, vsys_context)`, or the catalog must declare device-scope-only and mark vsys-scoped controls `NOT_EVALUABLE`. Per identity law, do not infer vsys identity from display labels.

**[MAJOR] `X-Nexus-Vendor` is a client-supplied routing key at a trust boundary.** Derive vendor **server-side from the persisted device record**; validate against a closed enum; reject unknown/missing with 400. Never default to `check_point` — evaluating PAN XML against Gaia rules fails silently, not loudly. Have the evaluator confirm the config dialect and return `RELATIONSHIP_INCONSISTENT` on mismatch.

**[MAJOR] `getControls()` "skip `NOT_APPLICABLE`" is the most dangerous line in the plan.** Silently dropping results changes denominators and inflates scores. `NOT_APPLICABLE` must remain *visible and reasoned*, excluded from numerator and denominator by a disclosed rule — explicit `UNKNOWN` over invented certainty.

**[MAJOR] Do not merge two different benchmarks into one "CIS Benchmark" card.** The CIS Check Point Firewall Benchmark and the CIS Palo Alto Firewall Benchmark are distinct documents with distinct control sets; a single blended percentage has no auditable meaning. Keep per-benchmark scoring; any estate roll-up must be labeled as a weighted aggregate with the breakdown one click away. Also specify whether the estate score averages over devices or over controls — the plan doesn't say, and the two differ.

**[MINOR]** `CatalogHandler`'s implicit combined default should be explicit; keep vendor ID prefixes collision-free under test. `controls_count` should become a per-vendor map.

---

## 5. Process & Verification Gaps

- **No FROZEN contract.** New vendor semantics, a new framework-mapping schema, possible new commands, and a user-visible scoring model — the lifecycle requires a frozen contract before implementation. `docs/design/CLAUDE_PALO_ALTO_COMPLIANCE_REVIEW.md` is untracked; a `DRAFT` cannot authorize these load-bearing semantics.
- **"Commit and push to `main`"** — push/merge is PO-controlled. Cite the authorizing directive rather than assuming it, and do not push before real-env validation; `REAL_ENV_VALIDATED` precedes `DONE`.
- **Missing gates:** the HTML render harness (`compliance_overview` payload and UI change), `tests/fixtures/uitest/` updates, and project-state updates via `scripts/project_queue.py` plus `CURRENT_STATE.md` / `build_history.json`.
- **Missing tests:** a sanitizer corpus asserting zero leakage *including* a tag not on the list; a matrix test that no input can yield PASS when the evidence source is absent (mirroring the `OP.0a` generated-matrix pattern); vendor-routing fail-closed negatives; a Panorama-template fixture proving no false FAIL.
- **Fixtures must be synthetic.** No real device configuration, ever — and a Playwright screenshot of the live compliance dashboard carries real hostnames and addresses into a shareable artifact. Use a redacted path.

---

## Verdict

### [REJECTED] — as an implementation plan; approved in shape, pending a frozen contract.

This is a scoped rejection, not a redirection. Sections 1–5's architecture — vendor-parallel evaluators, stateless routing, tri-metric posture, Check Point parity — is the right design and I endorse it. What blocks implementation is that roughly a dozen findings above are **data-model and contract decisions that cannot be retrofitted cheaply**, and several are direct conflicts with constitutional law rather than matters of polish.

**Path to approval — produce a `FROZEN` contract document resolving, at minimum:**

1. Benchmark **version** named; every control ID traced to a real section, with per-framework thresholds and `SATISFIES` / `CONTRIBUTES_TO` relations. Unprovable mappings ship as `UNKNOWN` or not at all.
2. BDDK source-of-record document with article citations, or drop it from the scored frameworks in v1.
3. One authoritative `ConfigurationReadKind`, recorded on every verdict. No fallback.
4. Compliance result identity: `(device_id, vsys_context, panos_version)`, with a declared proven-version range.
5. Allowlist XPath extraction; **no persisted configuration text for compliance**; verdict records only; hardened XML parser.
6. Status vocabulary kept distinct; `absent ≠ compliant` for every control; each `PAN-GATE-*` individually resolved as "already-approved parse scope" or "real gate, filed."
7. Server-side vendor derivation; fail-closed on unknown vendor; dialect confirmation.
8. Catalog-coverage denominator disclosed alongside every score; `NOT_APPLICABLE` visible; no blended cross-benchmark card.
9. Control #22 defined structurally or removed from v1.

**Recommended next movement:** `ARCHITECTURE` / medium reasoning to draft the contract. Escalate to high reasoning only for the framework-mapping freeze in §1–2, where the external-citation and regulated-context risk actually lives. The evaluator implementation itself, once the contract is frozen, is routine work at low tier.