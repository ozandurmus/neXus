# Path analysis — A6 UDP policy-match proposal

Status: DRAFT — for Product Owner approval. Not implementation authority (AGENTS.md "Contract-status law"). No code,
gate row or migration in this repository issues the literals below until the PO approves them by row number under the
AGENTS.md 2026-09-26 amendment, exactly as rows 1-5 of `PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md` were approved.

Parent: `docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md` (RATIFIED rows 1-5, PO 2026-11-04) and
`docs/design/PATH_ANALYSIS_DESIGN.md` §7 A6 (FROZEN). This document adds two literals; it does not change rows 1-5.

## 1. Problem

Rows 4 and 5 were approved as exact literals that contain `<protocol>6</protocol>`. Protocol is not a parameter of
those rows, so the code sends TCP only:

- `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/pathvalidation/PathValidation.java` —
  `policyMatch(...)` calls `require(protocol == TCP, PROTOCOL_NOT_APPROVED)`.
- `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/pathvalidation/HopPlan.java` — `invalid()` returns
  `POLICY_PARAMETER_INVALID` for a policy check whose `protocol != PathValidation.TCP`.
- `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/pathanalysis/validation/PathValidationPlanner.java` —
  `paloAlto(...)` adds the reason `PROTOCOL_NOT_APPROVED` and plans no `PAN_SECURITY` / `PAN_NAT` check when
  `packetIn().protocol()` is not `"tcp"`.

A UDP path query (`PathQuery.PROTOCOLS = {"tcp", "udp"}`) therefore gets only the FIB check (row 3) on a Palo Alto hop.
Its security and NAT decisions are never compared with the firewall's own answer.

## 2. Proposed literals (for approval)

Two new rows. Each is the approved row 4 or row 5 literal with exactly one change: the text inside `<protocol>` is `17`
instead of `6`. Element order, element names, placeholders and everything else are byte-for-byte the same as rows 4 and 5.

| # | Vendor / context | Exact command | Purpose | Replaces / extends |
|---|---|---|---|---|
| 6 | Palo Alto firewall (XML API op) | `<test><security-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination><protocol>17</protocol><destination-port>PORT</destination-port></security-policy-match></test>` | A6: the firewall's own first-match security rule for a UDP flow | New; UDP sibling of row 4 |
| 7 | Palo Alto firewall (XML API op) | `<test><nat-policy-match><from>Z1</from><to>Z2</to><source>SRC</source><destination>DST</destination><protocol>17</protocol><destination-port>PORT</destination-port></nat-policy-match></test>` | A6: the firewall's own NAT decision for a UDP flow | New; UDP sibling of row 5 |

`Z1`, `Z2`, `SRC`, `DST` and `PORT` are filled and validated exactly as in rows 4 and 5. They come only from a path
result the service computed for a PO-chosen sample. Each value is validated before the command string exists: IPv4
literal, port 1-65535, zone name matching `PathValidation.NAME` and present in the device's stored inventory. The
protocol number is never a placeholder. The code picks one of two fixed literals (6 or 17), so the browser never
supplies it and no third value can be sent.

Synthetic example (masked; illustration only). On `FW-TANGO-04`, from zone `ZONE-A` to `ZONE-B`, source
`192.0.2.10`, destination `192.0.2.53`, port 53, row 6 fills to:
`<test><security-policy-match><from>ZONE-A</from><to>ZONE-B</to><source>192.0.2.10</source><destination>192.0.2.53</destination><protocol>17</protocol><destination-port>53</destination-port></security-policy-match></test>`.

### 2.1 Shape considered and rejected: one literal with a protocol placeholder

The alternative is to replace `6` in rows 4 and 5 with a placeholder `PROTO` drawn from the closed set {6, 17}. That
would turn approved rows 4 and 5 into different literals, so the PO would have to approve them again, and existing
gate rows `path_validation_pan_security_policy_match` / `path_validation_pan_nat_policy_match` would need a migration
that edits a SIGNED_OFF canonical key. With two fixed literals, rows 4 and 5 and their V188 rows stay as they are, and
each protocol has its own gate id in the attempt ledger. Recommendation: two fixed literals (rows 6 and 7).

## 3. Command-gate fields (modelled on the V188 rows)

Both rows copy the V188 values of `path_validation_pan_security_policy_match` / `path_validation_pan_nat_policy_match`.
Only `gate_id`, `canonical_command_key` and `source_document_pointer` differ.

| Field | Row 6 | Row 7 |
|---|---|---|
| `gate_id` | `path_validation_pan_security_policy_match_udp` | `path_validation_pan_nat_policy_match_udp` |
| `vendor` | `palo_alto` | `palo_alto` |
| `platform_role_scope` | `pan_firewall` (never `panorama`) | `pan_firewall` (never `panorama`) |
| `shell_context` | `not_applicable` | `not_applicable` |
| `transport_kind` | `PAN_XML_API` (op, `direct_firewall`, existing authenticated transport) | same |
| `canonical_command_key` | the row 6 literal, byte for byte | the row 7 literal, byte for byte |
| `action_class` (class) | `read` (`ActionClass.CLASS_0_READ`) | `read` (`ActionClass.CLASS_0_READ`) |
| `sign_off_state` | `SIGNED_OFF` only after PO approval of row 6 | `SIGNED_OFF` only after PO approval of row 7 |
| `timeout_s` | `30` (`PathValidation.TIMEOUT_S`; `resolve` rejects any other value) | `30` |
| `retry_rule` | `none` | `none` |
| `max_frequency` | "on a PO-chosen sample only: once per hop per path_validation job, at most four hops per validation request, never polled" | same |
| `session_reuse_rule` | "one existing-authentication API key per job; serial per firewall; no retry" | same |
| `unsupported_behavior_ref` | "an API error, an unknown answer shape or a missing field is NOT_EVALUABLE, never a MISMATCH" | same |
| `secret_output_risk` | "output carries addresses, zone and rule names; parsed in memory to a MATCH / MISMATCH / NOT_EVALUABLE comparison only; never stored, transcribed or logged (raw-evidence law)" | same |
| `safe_telemetry_fields` | `["result", "reason"]` | `["result", "reason"]` |
| `source_document_pointer` | `docs/design/PATH_ANALYSIS_A6_UDP_PROPOSAL.md row 6 (RATIFIED; <PO approval reference>)` | `... row 7 (RATIFIED; <PO approval reference>)` |

Shell/context: not applicable. Both rows are XML API `op` requests, so no CLI shell or vsys context switch is involved.
`PathValidationPlanner` already refuses any vsys target other than the default (`VSYS_TARGET_NOT_APPROVED`), and this
proposal does not change that.

Frequency: a UDP hop issues at most the same three PAN calls a TCP hop issues today: FIB, security, NAT. The per-job
call count does not grow. One protocol's literal replaces the other.

## 4. Official Palo Alto documentation for the `protocol` field

Fetch limitation (stated plainly): this cloud session could not open `docs.paloaltonetworks.com` or
`knowledgebase.paloaltonetworks.com` (DNS refused by the session's network policy). The statements below come from
search-engine result summaries of these pages, **not from reading them in full**. Before the PO approves rows 6-7, an
engineer with access must read the pages and confirm each statement. Until then each one is `UNVERIFIED`.

| Claim | Source | Status |
|---|---|---|
| The web-interface Security Policy Match tool has a Protocol field (IP protocol number) and a Destination Port field | PAN-OS web interface help, "Security Policy Match": https://docs.paloaltonetworks.com/ngfw/help/12-2/device/device-troubleshooting/security-policy-match (also `origin-docs.paloaltonetworks.com/content/techdocs/en_US/ngfw/help/12-2/device/device-troubleshooting/security-policy-match.html`) | UNVERIFIED (summary only) |
| The NAT Policy Match tool's Protocol field is "the IP protocol used for routing" and takes 0-255; Destination Port is the specific destination port of the traffic | PAN-OS web interface help, "NAT Policy Match": https://docs.paloaltonetworks.com/ngfw/help/11-1/device/device-troubleshooting/nat-policy-match (same field text reported for 10.1, 11.2, 12.1, 12.2) | UNVERIFIED (summary only) |
| CLI `test security-policy-match` / `test nat-policy-match` take `protocol` as an IP protocol number: TCP 6, UDP 17, ICMP 1, ESP 50. Without zones, a result may come from rules for zones the addresses do not belong to | Palo Alto Knowledge Base, "How to Test Security, NAT and PBF Policy": https://knowledgebase.paloaltonetworks.com/articles/en_US/Knowledge/How-To-Test-Security-44-NAT-44-55911 | UNVERIFIED (summary only; article version/date not read) |
| The XML API `op` element for this command is `<protocol>` with a number as its text (the same shape rows 4-5 already send with `6`) | Rows 4-5 are already gated with this shape; no official XML API reference page for this command was found | Shape inherited from rows 4-5; official XML API page: UNKNOWN |

Silent or unknown in the documentation found:

- **UNKNOWN**: whether the answer to `security-policy-match` / `nat-policy-match` for protocol 17 has the same XML
  shape as for protocol 6. Nothing found says it differs. The parser (`MatcherAnswers.matchedRules`) maps any unknown
  shape to `NOT_EVALUABLE`, so the claim fails closed. It still needs real-environment corroboration (§7).
- **UNKNOWN**: how the matcher treats rules whose Application is not `any` when no `<application>` is supplied, and
  how it treats a Service of `application-default` for a UDP port. This is not UDP-specific: it already applies to rows
  4-5 for TCP, and `PATH_ANALYSIS_VENDOR_SEMANTICS.md` §6 already marks the `application-default` interaction
  `UNKNOWN` (decision D-5). This proposal adds no `<application>` element and does not settle the question.
- **UNKNOWN**: whether the firewall accepts `<destination-port>` together with protocol 17 on every PAN-OS release in
  the estate. The CLI example in the search results (`... destination-port 53 protocol 17`) suggests it does, but that
  example is community content, not official documentation.
- Community report (not official, not a semantic claim): the command can return an empty answer instead of "No rule
  matched". Today this is handled by `HopComparison.PAN_DEFAULT_RULES` and `DEFAULT_RULE_NOT_REPORTED`, and is
  protocol-independent.

## 5. ICMP: does it need a separate shape?

Not now, and it is not in scope:

- The path engine accepts only `tcp` and `udp` (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/pathanalysis/engine/PathQuery.java`,
  `PROTOCOLS = Set.of("tcp", "udp")`), so no ICMP hop can reach the A6 planner.
- If ICMP is ever added, it **does** need its own shape. ICMP has no destination port, so a literal with
  `<protocol>1</protocol>` must omit `<destination-port>`. Whether the test commands accept an ICMP type or code is
  **UNKNOWN**: no official page found documents such a field for these commands, and the only ICMP type/code page found
  covers custom applications in the GUI
  (https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-networking-admin/session-settings-and-timeouts/control-specific-icmp-or-icmpv6-types-and-codes).
  The FIB and Check Point `ip route get` rows are destination-only and protocol-independent.
- ICMP path analysis needs a path-engine change first, then its own proposal row(s). It is out of scope here.

## 6. Code changes after approval (none made by this document)

All of these follow approval of rows 6 and 7. The smallest diff keeps every TCP path byte-identical.

1. `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/pathvalidation/PathValidation.java`
   - Add `PAN_SECURITY_POLICY_MATCH_UDP` and `PAN_NAT_POLICY_MATCH_UDP` `Gate` constants (row 6 / row 7 literals), and
     add them to `GATES` and `GATE_IDS`.
   - Add `securityGate(int protocol)` / `natGate(int protocol)`: TCP returns the existing gate, UDP returns the new
     gate, and anything else throws `IllegalArgumentException(PROTOCOL_NOT_APPROVED)`. The command builder and the
     executor's gate resolution both use them, so the command sent and the `gate_id` written to the attempt ledger
     cannot diverge.
   - `policyMatch(...)` replaces `require(protocol == TCP, PROTOCOL_NOT_APPROVED)` with gate selection through those
     helpers. The public `panSecurityPolicyMatch` / `panNatPolicyMatch` signatures stay the same.
   - Update the class Javadoc, which today says UDP is never sent.
2. `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/pathvalidation/HopPlan.java` —
   `invalid()` accepts `PathValidation.protocol(protocol)` (TCP or UDP) for a policy check instead of
   `protocol == PathValidation.TCP`. `PLAN_VERSION` stays 1: no plan with 17 was ever written, because the planner
   refused it.
3. `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/pathanalysis/validation/PathValidationPlanner.java` —
   in `paloAlto(...)`, the `tcp` test becomes "protocol is tcp or udp". `PROTOCOL_NOT_APPROVED` stays as the fail-closed
   reason for any other protocol, which `PathQuery` cannot produce today.
4. `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/pathvalidation/PathValidationJobExecutor.java` —
   the gate switch in the readiness step maps `PAN_SECURITY` to `PathValidation.securityGate(plan.protocol())` and
   `PAN_NAT` to `PathValidation.natGate(plan.protocol())`. `paloAlto(...)` keeps calling the same builder methods.
   No transport, session, timeout or retry change.
5. `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/pathvalidation/MatcherAnswers.java` and
   `HopComparison.java` — no change expected. The answer is parsed protocol-independently, and an unknown shape is
   already `NOT_EVALUABLE`. If real-environment evidence shows a UDP-specific shape, that is a parse-scope change made
   then.
6. Gate seeding — a new Flyway migration at the next free version (V189 is taken today; V190 unless another lane
   claims it first) inserts the two rows with `ON CONFLICT (gate_id) DO NOTHING`, in the V188 shape. V188 is not
   edited. Mirror both rows in
   `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml` next to the V188 block.
7. `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/diagnostic/DiagnosticRead.java` — the existing
   PAN policy-match gates are not listed in `DIAGNOSTIC_EXCLUDED`. They are kept out of the diagnostic catalog in
   another way, which `PathValidationFenceTest` proves for every id in `PathValidation.GATE_IDS`. Once the new ids are
   in `GATE_IDS`, the fence test covers them too. Add them to `DIAGNOSTIC_EXCLUDED` only if that test fails.
8. Project state: `CURRENT_STATE.md`, `project/QUEUE.md` (through `tools/delivery/project_queue.py`) and
   `project/build_history.json`, updated by the implementing build.

No network-device command is issued by any of this before the gate rows are seeded, and they are seeded only after
approval.

## 7. Tests to add or change

Java (all fixture- or in-memory-backed; no device contact):

- `ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/pathvalidation/PathValidationCommandTest.java`
  - `theFilledCommandsAreTheApprovedLiteralsWithTheValuesInPlace`: add exact-string assertions for UDP security and
    NAT, filled with synthetic values (`192.0.2.x`, `ZONE-A`/`ZONE-B`). Assert that the TCP output is unchanged.
  - `injectionAndMalformedValuesAreRefused`: the current "UDP has no approved literal" assertion flips. Protocol 17
    now builds the row 6/7 literal, while protocols 1, 0, 58, 255 and -1 still throw `PROTOCOL_NOT_APPROVED`.
  - `everyGateResolvesSignedOffFromTheFixtureAndAnAlteredGateDoesNot`: covers the new gates automatically through
    `GATES`. Add a negative case where the UDP literal with `<protocol>1</protocol>` has no gate.
  - `aPlanIsCheckedFieldByField`: a PAN policy plan with protocol 17 is valid, while protocol 1 or 0 gives
    `POLICY_PARAMETER_INVALID`.
- `ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/pathvalidation/PathValidationJobExecutorTest.java` —
  a UDP hop resolves `path_validation_pan_*_policy_match_udp`, sends the 17 literal, and writes the UDP `gate_id` on
  its pre-contact attempt. A TCP hop still resolves and records the row 4/5 gate. If the UDP gate is missing from the
  registry, the result is `GATE_UNAVAILABLE` with no transport call.
- `ui2/service/src/test/java/com/securityexpert/nexus/ui2/service/pathanalysis/validation/PathValidationPlannerTest.java` —
  the existing UDP case (today asserts `PROTOCOL_NOT_APPROVED`) now asserts `PAN_SECURITY` / `PAN_NAT` planned with
  `protocol == 17`. Any other protocol string still yields `PROTOCOL_NOT_APPROVED`.
- `ui2/capability-registry/src/test/java/com/securityexpert/nexus/ui2/capability/PathValidationGateTest.java` —
  `everyApprovedLiteralIsOneSignedOffReadWithTheGateFieldsFilled` gains the two rows.
  `aUdpOrOtherwiseAlteredLiteralHasNoGate` is renamed and retargeted: the 17 literal now resolves to the UDP gate, and
  the altered literal used for the negative case becomes `<protocol>1<`. Add a migration test for the new V-file,
  modelled on `v188InsertsTheSameRowsIdempotentlyAndMapsTheModule`.
- `ui2/architecture-tests/src/test/java/com/securityexpert/nexus/ui2/architecture/PathValidationFenceTest.java` —
  no edit expected. `theGatesAreNeverInTheDiagnosticReadCatalogAndThePlatformClaimsTheJob` iterates
  `PathValidation.GATE_IDS` and so covers the new gates.

Python (offline contract):

- `tools/tests/test_path_validation_gate_contract.py` — today it asserts that the fixture's `path_validation_*` rows
  equal exactly the five V188 gates, so it must change. Either add a UDP command map checked against this document
  (status `RATIFIED`, row 6/7 literals) and the new migration, or split it into a V188 test and a V190 test. Its
  "the proposal is ratified" assertion must point at this file for the new rows.

Real environment (separate gate; AGENTS.md "Automated validation != real-environment validation"):

- One PO-chosen UDP sample per approved row on a masked firewall (for example `FW-TANGO-04`). Report only
  `MATCH` / `MISMATCH` / `NOT_EVALUABLE` and the reason code. This is the evidence that settles the §4 answer-shape
  `UNKNOWN`. Until it exists, the UDP behavior stays `AUTOMATED_VALIDATED` at most, never `DONE`.

## 8. PO decision requested

Approve or refuse rows 6 and 7 exactly as written in §2, each by number. Approval covers these two literals and their
validated parameters only. It does not cover ICMP, an `<application>` element, or any other protocol number.
