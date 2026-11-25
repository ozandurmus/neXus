# Zone matrix: amendment proposal for Cisco ASA and FortiGate coverage (2026-11-25)

## Status

DRAFT - 2026-11-25. A proposal for the Product Owner. It approves nothing, freezes nothing, changes no contract text and
must not be cited as authority. `ZONE_MATRIX_CONTRACT.md` (FROZEN, revision 2) stays exactly as it is: a frozen contract
changes only by an amendment the Product Owner ratifies, appended the way the 2026-11-23 interim staleness amendment was
(no earlier text edited). Written from source, tests and repository documents only: no device was contacted, no
configuration text was read, no command exists or is proposed. Vendor behaviour that the repository does not already
prove is marked `UNKNOWN` here; nothing is filled from general knowledge. Examples are synthetic (`FW-TANGO-04`,
`192.0.2.0/24`, `198.51.100.0/24`).

Trigger: `PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` section 3(a) - an engineering default (not a Product Owner
decision, outside that record's RATIFIED claim): the zone matrix is amended for ASA and FortiGate coverage only after
zone matrix step 4 has shipped; the Product Owner may overrule. Step 4 (durable runs, lifecycle, staleness; contract
10.1 step 4) has shipped in parts: 4a lifecycle and staleness as pure functions (`ZoneMatrixFindingLifecycle`,
`ZoneMatrixStaleness`), 4b-1 durable runs and findings (V267), 4b-2 part 1 the stored-summary read route (history file
`docs/history/backlog/zone_matrix_delivery.md`). Whether step 4b-2 is complete is not checked here; the proposal does not
depend on it except where section 4 says so.

Controlling and supporting texts read: `ZONE_MATRIX_CONTRACT.md` 3.1-3.3, 4.2-4.3, 5.1-5.9, 7.1-7.3, 8.1-8.2, 9, 10.1,
10.2; `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md` (DRAFT; sections 3, 5, 8 step 9, 11);
`PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md` (FROZEN; A0-ASA-n, A0-FG-n, section 15, section 16);
`PO_DECISION_RECORD_2026_11_13_ZONE_MATRIX.md`; `PO_DECISION_RECORD_2026_11_14_ASA_FORTIGATE_POLICY_MODEL.md` (items 4,
6, 8, 11); `ASA_FGT_POLICY_SHAPE_MEASUREMENT_2026_11_14.md` (counts only; not re-measured today).

## 0. Answer in brief

- **The engine pieces exist; the zone matrix does not use them.** The rule-resolution engine already evaluates stored
  ASA and FortiGate snapshots (`PolicyNormalization.java:303` `EVALUATED_VENDORS = CP, PAN, ASA, FGT`; `PolicyLookup`
  ASA chain `PolicyLookup.java:390-429`; FortiGate fall-through `PolicyLookup.java:292-293,307`), and the catalog already
  lists them (`PolicyQueryService.catalogViews`, `PolicyQueryService.java:555-568`, adds `LocalFirewallPolicyService`
  snapshots; `LocalFirewallPolicyService.java:61-62`). The zone matrix refuses them at six code points (section 1.2).
- **The address-block rule of the contract already answers the main design question.** The matrix places a rule's
  addresses into zones by the bank's IP blocks and treats vendor zone and interface names as context only (contract 3.1,
  PO decision 1 in `PO_DECISION_RECORD_2026_11_13_ZONE_MATRIX.md`). ASA `nameif` and FortiGate interface or zone names
  therefore need no typed mapping record for a first delivery. A typed mapping is only needed if the Product Owner wants
  placement to narrow a rule (question Q1).
- **Opening the gates alone is wrong, not merely incomplete.** Three defects would make every ASA rule
  `NOT_EVALUABLE` or silently drop a constraint (section 1.2, rows 3-5; section 6, C3-C5). They are code-versus-contract
  gaps found by reading, listed so the lane that implements an amendment does not rediscover them.
- **Verdict shape:** ASA is largely `DECIDABLE` for IPv4 TCP/UDP rules; ASA identity criteria are `UPPER_BOUND_ONLY`
  (already approved by the Product Owner, A0-ASA-16a); FortiGate is `DECIDABLE` for the measured estate except identity
  criteria (672 of 4,426 measured policies; `UPPER_BOUND_ONLY` only after a Product Owner decision like A0-ASA-16a, else
  `NOT_EVALUABLE`). Implicit behaviour that is vendor logic and not rule text (ASA security-level default, FortiGate
  zone intrazone allow) is a coverage item, never a verdict.
- **No storage change is needed for the recommended path** (section 1.1, 8.1). Option B of Q1 would add one.
- **Largest open risk for the Product Owner:** the 24-hour interim `STALE` threshold (amendment 2026-11-23) against an
  ASA/FortiGate configuration cadence that the design recorded as about 23 runs per device in 30 days (section 6, C8).

## 1. Which contract sections an ASA/FortiGate extension would amend

### 1.1 Section table

"Kind": `ADD` = appended amendment text; `CLARIFY` = wording that states what already holds; `NONE` = no change.

| Contract section | Change | Kind | Notes (file references are to `ui2/`) |
| --- | --- | --- | --- |
| 1 purpose, wording rules | Add: ASA/FortiGate results are the stored running configuration of the unit, "configured intent, not runtime truth" (A0 evidence law); no install-state claim | CLARIFY | Evidence laws: configuration intent != runtime truth. |
| 2 terms, "Policy source" | Add ASA (device, single context) and FortiGate (device, one per VDOM) as policy sources by their opaque snapshot id | ADD | Snapshot id `ref("asa", deviceId, "single")` (`AsaPolicyMapper.java:45`); FortiGate snapshot per VDOM (`FortiGatePolicyMapper.java:163` region). A VDOM is a container of the policy source, not an identity gate. |
| 3.1 zone vendor names | None required: "Vendor zone or interface names ... are never used to place an address in a zone" already covers `nameif`, FortiGate interfaces, zones and SD-WAN zones. Optional clarifying list | CLARIFY | Only Q1 option B would change it. |
| 3.2, 3.3, 3.4, 3.5 | None | NONE | Partition, `UNASSIGNED`, rights, versioning do not depend on the vendor. |
| 4.2 service vocabulary | Add: ASA/FortiGate service objects are not matrix entries (same as 4.2 last paragraph); ASA `ip` / FortiGate `ALL` are service `any`; ICMP and IP protocol rows follow hygiene R1/R4 | CLARIFY | `PolicyNormalization` resolves tcp/udp/icmp families only (design 5.4 item 7). |
| 4.3 applications | Add: ASA and FortiGate have no application criterion in the evaluated model (empty application cell = any, `PolicyNormalization.java:312` `EMPTY_APPLICATION_ANY`); FortiGate application lists / ISDB are `DYNAMIC` | ADD | Estate: 0 policies with ISDB or application criteria (measurement record 3). |
| 5.1 when and how | Add the trigger for these vendors: **completed configuration collection**, because no policy collection job exists for them (design section 1, `PolicyCollectionJobExecutor` accepts only `check_point`/`palo_alto`). The "snapshots are overwritten" sentence does not apply: the snapshot is derived on read from the stored artefact | ADD | `ZoneMatrixRunService.submitCompletedCollections` (`:392`) polls `catalogViews()` and compares `collectedAt`; for these vendors `collectedAt` is the artefact's collection time. |
| 5.2 what is evaluated | Define the security rulebase for the two vendors: ASA `ASA access-group` sections; FortiGate `FGT firewall policy` section. Sources `ASA NAT`, `ASA implicit`, `ASA not evaluated`, `FGT NAT`, `FGT implicit`, `FGT not evaluated` are "not analysed" (`PolicySnapshot.java:73-87`). Allow actions: ASA `permit`, FortiGate `accept`; deny-type: `deny`, `drop`... ; disabled: ASA `inactive`, FortiGate `status disable`; unknown enabled: NOT_EVALUABLE. New coverage items (section 2) | ADD | Code sets differ today (section 6, C5). |
| 5.3 upper-bound match | Dropped constraints: ASA `time-range` (time), `user`/`user-group`/`security-group` (identity, A0-ASA-16a); FortiGate `schedule` other than `always`, `vpn` tunnel, users/groups (only after a Product Owner decision, Q7). Placement (`from`/`to`) is not a decision input (already true, `ZoneMatrixPolicyBridge.java:690-731`). ASA FQDN and FortiGate dynamic / fqdn / geography / wildcard / mac objects are `DYNAMIC` or `UNSUPPORTED`: "dynamic or unsupported object type" | ADD | Access-role paragraph of 5.3 is Check Point only. |
| 5.4 outcomes and reasons | Add one fixed reason: "default setting unproven" (FortiGate absent setting without a cited default, `fgt-missing-default`). No new outcome. No new flag is needed: reuse `IDENTITY_IGNORED`, `TIME_IGNORED`, `NAT_MAY_APPLY`, `OTHER_ORDERED_LAYERS_UNKNOWN`, `DEFAULT_RULE` (not used) | ADD | Fixed vocabulary today: contract 5.4. |
| 5.5 ordered layers | ASA "in ++ global ++ out" is the analogue of Check Point ordered layers. Recommended minimum: an `in` or `global` ACE violation gets `OTHER_ORDERED_LAYERS_UNKNOWN` when the device has any `out` access-group, else is a plain VIOLATION. Estate: `out` bindings 0 (measurement record 2). `COVERED_BY_EARLIER_DENY` for these vendors only if hygiene is wired (Q10); until then never set | ADD | `ZoneMatrixPolicyBridge.java:263` gates hygiene to `CP`/`PAN`. |
| 5.6 NAT dual view | ASA: **single view**, the address as written in the ACE, because access rules match real (untranslated) addresses (A0-ASA-6, HIGH); an ACE written with a mapped address is not modelled (residual, `UNKNOWN`). FortiGate: **dual view** for a policy whose destination is a VIP object: original (`extip`) and mapped, as 5.6 already says; unresolved mapped address -> never COMPLIANT, "NAT translation unresolved". Policy-mode SNAT is inert (A0-FG-17/22). Central NAT is `UNSUPPORTED` | ADD | A0-FG-19 (does a VIP `dstaddr` match the external address) is `UNKNOWN`, hence both views. Estate: VIPs 0, central SNAT 0 (measurement record 3). `ZoneMatrixNatBounds.java:20-49` handles `CP`/`PAN` only. |
| 5.7 algorithm step 2 | The sentence "`any` includes IPv6 on both vendors" is about Check Point and Palo Alto. ASA: `any` is both families, `any4` IPv4 only, `any6` IPv6 only (A0-ASA-11). FortiGate: IPv4 and IPv6 are separate cells (`srcaddr` vs `srcaddr6`, A0-FG-16) and the predefined IPv4 `all` object is `0.0.0.0/0` (A0-FG-24), not an any-family object. State the per-vendor reading | ADD | Section 6, C10. |
| 5.8 device scope | Replace the two "no" rows with covered rows and their notes. **The stale sentence is reported in section 6, C1; it is not silently reconciled here** | ADD | Text in section 3. |
| 5.9 finding identity | ASA: see Q3 (an ACE has no vendor id). FortiGate: `uuid` when present else the policy id, as `ZoneMatrixEvaluator.ruleUid` already does (`:1380`). Staleness: artefact `collected_at`; see Q5 and C8. HA pairs: Q2 | ADD | `uuid` present on 4,426 of 4,426 measured FortiGate policies. |
| 6 template, 6.2 bank inputs | None | NONE | |
| 7.1 screen coverage | Vendor filter shows `ASA`/`FGT`; the "not evaluated" list (`ZoneMatrixService.java:942-985`, `ZoneMatrixRunService.java:456-466`) stops listing them once they are covered; new coverage item texts | ADD | Screens read stored findings only. |
| 7.2 headline | HA SAME pair counted once (Q2); ASA ACL bound to several interfaces counted once per ACE (Q4); coverage items keep "at least N" | ADD | |
| 7.3 report | Scope lists ASA/FGT policies with their coverage items; fixed limitations text gains the ASA/FortiGate sentences (section 3) | ADD | Step 6 is not delivered yet (history 2026-11-25 line: step 6a builder only). |
| 8.1 storage | **None** for the recommended path. `zone_matrix_run.vendor` is `TEXT` with `length <= 16` (`V267__zone_matrix_runs_and_findings.sql:47`), `policy_source_id` `TEXT` 1-512 (`:36`), `rule_uid` `TEXT NOT NULL` (`:99`). One check to add as a test: the longest ASA/FortiGate snapshot id and rule id fit 512 (`UNKNOWN` until a fixture proves it). Q1 option B would add a table and a migration | NONE | No raw device response, no snapshot copy: unchanged. |
| 8.2 privacy | Add: `nameif`, interface, VDOM, zone, ACL and policy names are identity text; they pass through `PolicyPrivacy` and the zone-name pseudonymisation in masked views, and never reach logs. `vendorFromZones`/`vendorToZones` (`ZoneMatrixEvaluator.java:109-110`) will carry interface names; whether the masked projection already hides them for Palo Alto zones was not verified in this read (`UNKNOWN`; test row AF-18) | ADD | |
| 9 performance | Re-measure on the largest ASA and FortiGate snapshot: measured largest ASA bound ACL 9,072 ACEs, one ASA unit 16,972 bound ACEs, 80,431 bound ACEs in total; FortiGate largest VDOM 1,399 policies (measurement record, 2026-11-14) | ADD | The 30 s / 512 MB ceilings of 5.1 apply unchanged. |
| 10.1 delivery order | Add steps 8+ (section 4) | ADD | |
| 10.2 acceptance | Keep ZM-1..42; add AF rows (section 5) | ADD | |
| 11.2 remaining questions | Add the `UNKNOWN`s of section 2 | ADD | |

### 1.2 Where the code refuses or would mis-evaluate today (facts, for the lane)

Paths under `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/`.

| # | Location | What it does today | Consequence for an amendment |
| --- | --- | --- | --- |
| 1 | `zonematrix/ZoneMatrixEvaluator.java:339-357` | `if (!"CP".equals(vendor) && !"PAN".equals(vendor))` returns a non-evaluated result with `POLICY_NOT_COLLECTED` "not evaluated: policy not collected" | The gate to open; the reason text is wrong for these vendors (C1). |
| 2 | `zonematrix/ZoneMatrixRunService.java:1197-1198` (`isEvaluableVendor`), used at `:366`, `:407`, `:457` | Submits runs and triggers only for `CP`/`PAN`; others are `NotEvaluatedPolicy` with `NOT_EVALUATED_REASON` (`:99`) | Trigger and listing change together (5.1). |
| 3 | `policy/ZoneMatrixPolicyBridge.java:33` `ALLOW_ACTIONS = {Accept, allow}` | An ASA `permit` or FortiGate `accept` rule is classified "action semantics not modelled" (`:553-560`) | Must add `permit`, `accept`; `PolicyNormalization.java:308` already lists them (C5). |
| 4 | `policy/ZoneMatrixPolicyBridge.java:690-731` `inspectExtrasAndFlags` | Any extras key outside its skip list returns "dynamic or unsupported object type". ASA rules always carry `asa-direction` and `asa-acl` (`AsaPolicyMapper.java:287-288`); `user`, `security-group`, `asa-unparsed`; FortiGate `fgt-action`, `fgt-application`, `fgt-vip`, `fgt-match-vip`, `fgt-unparsed`, `fgt-missing-default`, `user` | Every ASA rule would be NOT_EVALUABLE; identity keys would not become `IDENTITY_IGNORED`. The vendor-specific keys need explicit handling (C3). |
| 5 | `policy/ZoneMatrixPolicyBridge.java:622-623` `checkSchedule` reads key `time` for `CP` and `schedule` for everything else | ASA and FortiGate mappers write the key `time` (`AsaPolicyMapper.java:291`, `FortiGatePolicyMapper.java:282`), whose value is a raw name, not an object id; `inspectExtrasAndFlags` skips `time` silently | A time-limited rule would lose its constraint with no `TIME_IGNORED` flag (C4). |
| 6 | `policy/ZoneMatrixPolicyBridge.java:209-214`, `:244`, `:263`, `:283`; `policy/ZoneMatrixNatBounds.java:20-49` | Section classification, Apply Layer, hygiene and ordered layers, NAT bounds are `CP`/`PAN` branches | New ASA and FortiGate classification and NAT branches; hygiene stays off until Q10. |
| 7 | `zonematrix/ZoneMatrixEvaluator.java:1159-1180` `computeCoverageItems` | Coverage items per vendor: `PAN_*`, `CP_*` | New coverage codes (section 2). |
| 8 | `zonematrix/ZoneMatrixService.java:942`, `:972-985` | The policy list shows `CP`/`PAN` as evaluable and builds ASA/FGT "not evaluated" rows from the catalog and from enrolled devices by `vendorHint` | Row source changes; the device fallback stays for devices with no stored snapshot. |

`ZoneMatrixPolicyBridge.prepare` does not call `PolicyNormalization.normalize`; it resolves cells itself
(`computeUpperBound`, `:513-620`). So the FortiGate zone-placement exclusion of `PolicyNormalization.fgtZonePlacement`
(`:245`, "zone membership not evaluated") does not apply to the zone matrix: placement is context there. It would apply
if hygiene is wired (Q10).

## 2. Per vendor: contract concept -> snapshot today -> missing -> verdict class

Verdict classes: `DECIDABLE` (the stored snapshot and a cited or Product Owner-approved semantic decide it);
`UPPER_BOUND_ONLY` (only an upper bound, so a VIOLATION with a flag but COMPLIANT only through the bound);
`NOT_EVALUABLE <reason>` (reason from the fixed list of 5.4, or the one proposed addition); `UNKNOWN` (not decided by the
repository, says so). Grades are those of the A0 amendment (HIGH, MEDIUM, LOW, UNKNOWN). Estate counts are from the
2026-11-14 measurement record and were not re-measured for this proposal.

### 2.1 Cisco ASA

| # | Contract concept | What the stored snapshot gives today | What is missing | Verdict class |
| --- | --- | --- | --- | --- |
| A1 | Rule, order, action (5.2) | One section per `access-group` binding (`in`, `out`, `global`), ACEs in configuration order, `action` `permit`/`deny`, `enabled = !inactive` (`AsaPolicyMapper.java:97,109,119,255-290`). First match per ACL, implicit deny (A0-ASA-1, HIGH) | Bridge allow-action set (C5) | `DECIDABLE` |
| A2 | Source/destination address (5.3, 5.7) | host, subnet (contiguous mask), range, object, object-group (nesting depth max 2 measured), `names` aliases resolved to ranges; `any4` IPv4 universe (A0-ASA-11, MEDIUM) | Per-family resolution is contract step 7 (not delivered) | `DECIDABLE` for IPv4; `any` and `any6`: `NOT_EVALUABLE "IPv6 not evaluated"` for COMPLIANT, an IPv4 VIOLATION still reported (ZM-19/27) |
| A3 | Non-contiguous mask, FQDN object | `UNSUPPORTED` / `DYNAMIC` objects (A0-ASA-12 MEDIUM, A0-ASA-13 `UNKNOWN`). Measured: 15 non-contiguous masks, 208 FQDN objects | none | `NOT_EVALUABLE "dynamic or unsupported object type"` |
| A4 | Service (4.2) | tcp/udp ports with `eq`, `neq`, `lt`, `gt`, `range`; `tcp-udp`; port words via the Cisco tables (A0-ASA-15, HIGH); `ip` = any protocol | ICMP and IP protocol numbers (contract 4.2: after hygiene R1/R4); `port-object` shape (A0-ASA-14, only if measured); 96 port words outside the table in bound ACEs (3 units) | `DECIDABLE` for tcp/udp; icmp/gre: `NOT_EVALUABLE "unsupported service constraint"`; unknown port word: `NOT_EVALUABLE "unsupported port expression"`; `ip` is service `any` (never inside a finite list, 5.7 step 4) |
| A5 | Zone of an address (3.1) | Not in the snapshot and not needed: the matrix partition places the addresses by the bank's blocks | none (Q1 only if placement should narrow) | `DECIDABLE` (address-block derivation) |
| A6 | Interface (`nameif`) and security level as zone evidence | `from`/`to` extras on each rule; `nameif`, `security-level`, `same-security-*` in the synthetic section `ASA security-level default` (`AsaPolicyMapper.java:124-150`) | A bank-typed mapping from `nameif` to a matrix zone (does not exist; must never be inferred from a name or a level) | Context only. Never a decision input (3.1, evidence law: presentation identity != security identity) |
| A7 | Time-limited ACE (5.3, 5.4) | `time` extras with the `time-range` name | The name is not an object in the snapshot; the bridge reads the wrong key (C4) | `UPPER_BOUND_ONLY`: VIOLATION with `TIME_IGNORED` when the bound violates; COMPLIANT only through the bound. A0-ASA-8 grades `time-range` MEDIUM; ended windows cannot be evaluated (no schedule object) -> never `NOT_APPLICABLE` |
| A8 | Identity ACE (5.3, 5.4) | `user`, `security-group` extras (`AsaPolicyMapper.java:295-299`). Measured: 6,592 bound ACEs (8.2 %) carry user or user-group | none; the reading is already approved: A0-ASA-16a (Product Owner, 2026-11-14 evening, record item 11) | `UPPER_BOUND_ONLY`: VIOLATION with `IDENTITY_IGNORED`; COMPLIANT only through the bound. The A0-ASA-16a limit stands: revisit if corroboration shows an ACE matching outside its cells |
| A9 | Multiple ordered lists (5.5) | `in` list, `global` list, `out` list per binding; the packet meets `in ++ global`, then `out`; both directions must permit (design 4.1; A0-ASA-2/3 HIGH). `out` bindings: 0 measured; `global` on 3 units | Egress interface needs routing (A0-ASA-18 `UNKNOWN`; non-goal: no routing claim) | `DECIDABLE` for an `in`/`global` ACE as rule intent; with any `out` binding present: VIOLATION flagged `OTHER_ORDERED_LAYERS_UNKNOWN` (outside the headline). Estate today: not triggered |
| A10 | Security-level checks before ACLs | `same-security-traffic` flags; levels in the synthetic section | Which interface pair a packet takes (routing) | Context. A0-ASA-5 (HIGH) can make an ACE moot; not modelled: stated in the limitations text. No verdict |
| A11 | Implicit allow without rule text | None as a rule: higher to lower is permitted when no inbound and no `global` ACL applies (A0-ASA-4a, HIGH). Lower to higher: A0-ASA-4b LOW; whether a bound ACL removes the permit: A0-ASA-4c `UNKNOWN` | An address-level statement does not exist (the default is per interface pair, no address) | Coverage item `ASA_SECURITY_LEVEL_DEFAULT_ALLOW_NOT_EVALUATED` per interface with neither an inbound nor a `global` ACL (like PAN `PAN_INTRAZONE_DEFAULT_ALLOW`, 5.2). `UNKNOWN` for 4b/4c: never a verdict (Product Owner record item 4) |
| A12 | NAT dual view (5.6) | Three NAT sections (`ASA NAT`), outside security evaluation. Measured: section 1 5,471 (twice NAT with destination 5,048), section 2 307, section 3 0 | ACLs match real addresses (A0-ASA-6, HIGH), so the ACE address is already the real side; an ACE naming a mapped address never matches on 9.x per A0-ASA-6 but the engine cannot prove deadness without a NAT model (A0-ASA-18 `UNKNOWN` for order) | Single view, `DECIDABLE`. Residual `UNKNOWN`: stated in the limitations text. Unparsed NAT lines (`asa-unparsed`) -> coverage item `ASA_NAT_UNPARSED` |
| A13 | ACLs not enforced as access rules | Not in an access section: unbound ACLs sit in `ASA not evaluated` with `asa-acl-use` (`none`, `unknown`, recognised uses), section 16 of the A0 amendment | none | Not evaluated, counted as coverage item `ASA_ACL_NOT_BOUND_NOT_EVALUATED` (count). `ACL_NOT_BOUND` only when no recognised or unparsed use exists; otherwise `UNKNOWN`, never "unused" (PO answer 2026-11-23). Measured: 539 ACLs with no scanned use; 8,007 ACEs |
| A14 | Control-plane, EtherType, webtype ACLs; transparent and multiple-context mode | Listed in `ASA not evaluated`; unsupported modes give failure `ASA_UNSUPPORTED_MODE` (`AsaPolicyMapper.java:59-66`). Estate: 0 | none | Not evaluated; a snapshot failure makes coverage `INCOMPLETE` (5.9, ZM-7) |
| A15 | Unparsed ACE | `asa-unparsed` extras, cells bound to an `UNRESOLVED` object (`AsaPolicyMapper.java:305-313`) | none | `NOT_EVALUABLE "unresolved object"` |
| A16 | Finding identity (5.9) | Rule `id = ref(snapshotId, binding, acl, position)`, `uuid = null` (`AsaPolicyMapper.java:321-323`); `ruleUid` falls back to `id` (`ZoneMatrixEvaluator.java:1380`) | An ACE has no vendor identifier in the stored text (`UNKNOWN` whether any stable one exists in the configuration text). Position changes when an ACE is inserted above | Q3 |
| A17 | One ACL on several interfaces | One section per binding, different rule ids per binding. Measured: 3 ACLs bound to more than one interface | Dedupe at the ACL/ACE level | Q4 |
| A18 | HA pair | One snapshot per enrolled member; `HaPolicyPairing` gives `SAME` / `HA_POLICY_DIVERGENT` / `UNKNOWN` per pair (`HaPolicyPairing.java:76-135`); `PolicyHygieneFleetSummary.java:20` already counts SAME once | Counting rule in the zone matrix; remarks rule (C7) | Q2 |
| A19 | Staleness (5.9) | Snapshot `collectedAt` = configuration artefact time. Cadence per design section 3: about 23 runs per device in 30 days | Per-source threshold or confirmation of cadence | Q5; `UNKNOWN` today |

### 2.2 FortiGate

| # | Contract concept | What the stored snapshot gives today | What is missing | Verdict class |
| --- | --- | --- | --- | --- |
| F1 | Rule, order, action | `FGT firewall policy` section per VDOM, printed order (not id order: measured on 11 of 11 VDOMs with policies, supports A0-FG-1 MEDIUM), `accept`/`deny`, absent `action` = deny (A0-FG-5 HIGH, cited 7.4 default; measured 36 policies), `status` absent = enabled | Bridge allow-action set (C5); order matters only for hygiene cover, not for rule-level VIOLATION | `DECIDABLE` |
| F2 | Absent setting with no cited default | `fgt-missing-default` extras; snapshot failure `FGT_DEFAULTS_UNPROVEN` on an uncovered minor (`FortiGatePolicyMapper.java:119,445`) | One fixed reason | `NOT_EVALUABLE "default setting unproven"` (proposed new reason); coverage `INCOMPLETE` (any failure, `ZoneMatrixEvaluator.java:1155-1158`) |
| F3 | Address (5.3) | `ipmask`, `iprange` resolved; `fqdn`, `geography`, `dynamic`, `route-tag` `DYNAMIC`; `wildcard`, `mac` `UNSUPPORTED` (A0-FG-23 HIGH); subnet-less `ipmask` = `0.0.0.0 0.0.0.0` (A0-FG-24 defaults HIGH, any-address meaning MEDIUM; measured 39 predefined objects) | Grade of the any-address meaning is MEDIUM | `DECIDABLE` for resolved IPv4; `DYNAMIC`/`UNSUPPORTED`: `NOT_EVALUABLE "dynamic or unsupported object type"`; `interface-subnet` (resolved from the stored interface address, MEDIUM, A0-FG-23): treated as `UNKNOWN` until the amendment says whether MEDIUM resolution may support COMPLIANT (proposal: it may support VIOLATION only; COMPLIANT stays `NOT_EVALUABLE "dynamic or unsupported object type"`). Estate: 0 interface-subnet objects measured |
| F4 | IPv6 | `*addr6` cells kept, unresolved by the IPv4 engine (A0-FG-16 MEDIUM). Measured: 27 IPv6 addresses | Per-family resolution (contract step 7) | `NOT_EVALUABLE "IPv6 not evaluated"` for COMPLIANT; IPv4 VIOLATION still reported |
| F5 | Service (4.2) | tcp/udp/sctp ranges, `dst`, `dst-dst`, `dst:src` forms (A0-FG-26 MEDIUM, measured forms only); predefined services are stored entries (measured: 0 undefined references; A0-FG-27 `UNKNOWN` as a general claim); `ALL` resolves by its stored definition, never by name | icmp, ip protocol numbers | tcp/udp `DECIDABLE`; icmp/ip: `NOT_EVALUABLE "unsupported service constraint"` |
| F6 | Negation | `Cell.negated` | Hygiene R5 (contract 5.4 "negated selector" until then) | `NOT_EVALUABLE "negated selector"`. Estate: 0 |
| F7 | Schedule (5.3, 5.4) | `time` extras only when `schedule` is not `always` (`FortiGatePolicyMapper.java:279-283`); absent schedule is `missing default` (A0-FG-5: schedule has no default). Measured: 40 policies with a schedule other than `always` | Key mismatch (C4); a schedule object (recurring vs one-time) is not in the snapshot | `UPPER_BOUND_ONLY` with `TIME_IGNORED`; ended windows not evaluable; absent schedule: `NOT_EVALUABLE "default setting unproven"` |
| F8 | Identity (users, groups, FSSO) | `user` extras (`FortiGatePolicyMapper.java:285-292`). Measured: 672 of 4,426 policies | A decision like A0-ASA-16a for FortiGate: A0-FG-12 is MEDIUM ("authenticate the policy"); whether such a policy matches only authenticated sessions is `UNKNOWN` | Today `NOT_EVALUABLE "depends on user identity"`. After a Product Owner decision (Q7 option A): `UPPER_BOUND_ONLY` with `IDENTITY_IGNORED` |
| F9 | ISDB, `action ipsec`, VPN | ISDB: `DYNAMIC` destination and service (A0-FG-8 HIGH); `fgt-action` for ipsec (A0-FG-7 MEDIUM); `vpn` extras | none | ISDB: `NOT_EVALUABLE "dynamic or unsupported object type"`; ipsec: `NOT_EVALUABLE "action semantics not modelled"`; `vpn`: `CONDITION_IGNORED` (existing branch `:709-715`). Estate: ISDB 0 |
| F10 | VIP as destination (5.6) | `FGT VIP` NAT section (outside security evaluation); VIP object resolves to `extip`, mapped values kept (`FortiGatePolicyMapper.java:554-603`); `fgt-vip` extras on the policy | Two UNKNOWNs: A0-FG-18 (VIP policy "priority"), A0-FG-19 (VIP `dstaddr` matches the external address). The zone matrix does not need either for ordering (rule-level), only for the view | Dual view: original and mapped; COMPLIANT only when both compliant; mapped-only VIOLATION flagged `NAT_MAY_APPLY` (Q6). Unresolved mapped address: `NOT_EVALUABLE "NAT translation unresolved"`. Estate: VIPs 0 |
| F11 | Port-forward VIP in different zones | `translated-destination-port` extras | The port-split case (5.6 last bullet) | `NAT_PORT_SPLIT` per translation, as in 5.6 |
| F12 | Zones, SD-WAN zones, intrazone | Zone and SD-WAN zone objects and the synthetic `intrazone allow` rule in `FGT implicit` (`FortiGatePolicyMapper.java:502-547,716`). Estate: 0 zones | Whether an intrazone allow is "an allow rule" for the matrix: it has no addresses | Placement is context. Intrazone allow: coverage item `FGT_ZONE_INTRAZONE_ALLOW_NOT_EVALUATED` (like PAN intrazone default). Any zone evaluation: `UNKNOWN` (zone membership not compared by name, `PolicyNormalization.java:338-346`) |
| F13 | Tables outside the model | `FGT not evaluated` section: local-in, proxy, security (NGFW policy-based), multicast, DoS, legacy `policy6` (A0-FG-13/14/15). Estate: local-in 0, proxy 0, NGFW 0; multicast 19 | none | Not evaluated; coverage item `FGT_POLICY_TABLES_NOT_EVALUATED` (count by table kind); NGFW policy-based or central NAT VDOM: failure -> `INCOMPLETE` |
| F14 | Implicit deny | `FGT implicit` section with one deny (A0-FG-3 HIGH) | none | Not an allow; not evaluated, no coverage item needed beyond the section list |
| F15 | VDOM and device identity | One snapshot per VDOM; container `ref(deviceId, vdom)`; 13 VDOMs on 9 units measured | A VDOM name must never be a join key; the device id is the key | Policy source = snapshot id (opaque); VDOM name is display and masked |
| F16 | Finding identity | `Rule.uuid` = `set uuid` (present on all measured policies); else `ref(device, vdom, policyid)` | none | `DECIDABLE`: `ruleUid` as today (`ZoneMatrixEvaluator.java:1380`). Policy id compared as an opaque string (identity law) |
| F17 | HA pair | One measured pair claim whose peer is not enrolled; `HaPolicyPairing` per VDOM context | Q2 | Q2 |
| F18 | Staleness | as A19 | as A19 | Q5 |

## 3. Proposed text for the two scope rows and the limitations sentences

Illustrative only; it becomes contract text only if the Product Owner ratifies an amendment.

5.8 rows (replacing "no ... No policy collection exists"):

| Vendor | Covered | Notes |
| --- | --- | --- |
| Cisco ASA (single context, routed mode) | yes, from the stored running configuration of each enrolled unit | One policy source per unit. ACLs bound by `access-group`; unbound ACLs, control-plane ACLs and NAT are "not analysed", counted. The security-level default and `same-security-traffic` are coverage items. HA pairs per Q2. |
| FortiGate (FortiOS 7.4 with a cited defaults table) | yes, from the stored configuration of each enrolled unit, one policy source per VDOM | Policy table only; VIP dual view; zones and NGFW policy-based mode are not evaluated. HA pairs per Q2. |

Additional fixed sentences (7.3): "ASA and FortiGate results are read from the stored running configuration of the unit;
they are configured intent, not observed traffic or install state." / "ASA access rules match real addresses; an ACE written
with a translated address is not modelled." / "Security-level and same-security checks that run before ACLs are not
evaluated." / "FortiGate policy order and VIP semantics follow the cited sources; where a source does not state them the
finding says UNKNOWN."

## 4. Delivery steps (style of contract 10.1)

Each step is one PR with targeted tests. "Lane" = a worker lane can do it, pure code under the amended contract.
"PO first" = needs a Product Owner decision before any code. Sizes are an engineer estimate, not measured.

| Step | What | Class | Size |
| --- | --- | --- | --- |
| 0 | The Product Owner answers section 7 (at least Q1-Q6 and Q9); a PO session ratifies and appends the amendment to `ZONE_MATRIX_CONTRACT.md`. An engineering session does not convene the decision council | PO first | docs, about 0.5 day once answered |
| 1 | Engine, ASA only, pure: open the vendor gate in `ZoneMatrixEvaluator`, `ZoneMatrixPolicyBridge` (allow set, extras handling for `asa-*`, `user`, `security-group`, `time` key fix, single-view NAT), coverage items for A11, A13, A14, `ASA_NAT_UNPARSED`; synthetic ASA fixtures; CP/PAN golden test unchanged (ZM-9) | Lane (after step 0) | about 4-5 days |
| 2 | Engine, FortiGate, pure: `fgt-*` extras, "default setting unproven" reason, VIP dual view, port-split, ISDB/ipsec/negation reasons, coverage items F12/F13; fixtures | Lane (after step 0; identity per Q7) | about 3-4 days |
| 3 | Triggers, listing and identity: catalog rows for ASA/FGT as evaluable, `submitCompletedCollections` on a new artefact `collectedAt`, publish trigger, HA pair counting (Q2), ASA ACE identity (Q3) and ACL-level dedupe (Q4), device fallback list, "not evaluated" list shrinks | PO first for Q2-Q5, then lane | about 3-4 days |
| 4 | Coverage and Violations tabs: vendor filter, ASA/FGT coverage item texts, masking of interface/VDOM/ACL names (AF-18); frontend only through the standard view, no Estate change | Lane | about 2 days |
| 5 | Measurement, counts only, in the pod, no device command, no new read: largest ASA and FortiGate snapshots against a 500-zone version at the block cap (ZM-12 extension), distribution of snapshot ages (Q5), counts of `IDENTITY_IGNORED`, `TIME_IGNORED`, `NOT_EVALUABLE` reasons. Run once with the PO session's go | PO go, then lane | about 2 days |
| 6 | Evidence report scope and limitations sentences for these vendors, on top of contract step 6 | Lane (after contract step 6) | about 1 day |
| 7 | Optional (Q10): hygiene cover flags for ASA/FGT (`COVERED_BY_EARLIER_DENY` with proven conflict, per ACL / per VDOM) and the `out` witness check beyond the flag | PO first | about 3 days |
| 8 | Only if Q1 option B is chosen: typed mapping record (bank-entered, audited, versioned), migration, rights, contract section; used only to narrow | PO first, council candidate (storage, identity-adjacent) | about 5-7 days; migration number only from `reserve-migration.sh` |

Real-environment validation: no device command is involved in any step; step 5 is a stored-data measurement. The
validation of what a finding means against the device's own answer (ASA `packet-tracer` R2, FortiGate equivalent) stays
deferred as in the design (record item 7); without it the new rows stay `AUTOMATED_VALIDATED`.

## 5. Acceptance rows

### 5.1 ZM-1..ZM-42: must stay true (and how an extension touches them)

| Rows | Stay true as is | Extension note |
| --- | --- | --- |
| ZM-1, 2, 3, 29, 36, 37, 40, 41 | yes | Partition, versions, caps, audit do not depend on vendor. Re-run unchanged. |
| ZM-4, 5 | yes | "Every address family and NAT view decided" now includes ASA `any`/`any6` and FortiGate `*addr6` cells. |
| ZM-6 | yes | Disabled (ASA `inactive`, FortiGate `status disable`), deny-type (`deny`), NAT, implicit and not-evaluated sections never produce a VIOLATION and are counted. |
| ZM-7 | yes | A snapshot failure (`ASA_UNSUPPORTED_MODE`, `FGT_UNSUPPORTED_MODE`, `FGT_DEFAULTS_UNPROVEN`) gives `INCOMPLETE`. |
| ZM-8 | yes, with the 5.1 addition | A run starts on a completed configuration collection of the unit, never on a partial one; one run per (policy, collected_at). |
| ZM-9 | yes | Deterministic parse and map; a CP/PAN golden test proves no behaviour change. |
| ZM-10, 33 | yes | Interface, `nameif`, VDOM, ACL names masked; logs hold ids and counts only. |
| ZM-11, 28, 30, 31, 32, 38 | yes (step 6) | Report content for the new vendors follows the same rules. |
| ZM-12 | yes, re-measured | Add the largest ASA and FortiGate snapshot. |
| ZM-13 | yes | No device command and no job-engine dependency: the data is the stored artefact, read through the existing service. |
| ZM-14 | yes | FortiGate VIP analogue is AF-11. |
| ZM-15, 16, 17, 18, 20 | yes | Check Point and Palo Alto only; unchanged. |
| ZM-19, 27 | yes | Per-family resolution remains contract step 7. |
| ZM-21 | yes, extended | Counted once per (policy source, rule uid); ASA ACL on several interfaces per Q4. |
| ZM-22, 23 | yes | Governance. |
| ZM-24 | yes, extended | A time- or identity-limited ASA/FortiGate rule in a `DENY_ALL` pair is a VIOLATION with its flag (AF-4, AF-5). |
| ZM-25 | yes | The flag is never set for these vendors unless hygiene is wired (Q10). |
| ZM-26, 35 | yes, extended | Coverage items of these vendors keep "at least N" and never green; `STALE` per Q5. |
| ZM-34 | yes, extended | A rule in an uncollected scope (an unbound ACL with unknown use, an unparsed table) is never resolved. |
| ZM-39 | yes | Same own pool. |
| ZM-42 | yes, depends on Q3 | `firstObservedAnyVersion` survives a new version for ASA/FortiGate identities. |

### 5.2 New rows an extension would need (proposed ids, each one testable)

- `AF-1` A run on an ASA or FortiGate policy issues no device command and reads only the stored configuration artefact through the existing service (architecture test, like ZM-13).
- `AF-2` Interface, `nameif`, FortiGate zone and VDOM names never place an address in a matrix zone: a fixture whose interface names equal zone names gives findings identical to one with unrelated names.
- `AF-3` An ASA `permit` ACE or FortiGate `accept` policy between a `DENY_ALL` pair is a VIOLATION; ASA `deny`, `inactive` and FortiGate `deny` or `status disable` produce none and are counted.
- `AF-4` An ASA ACE with a `time-range`, or a FortiGate policy with a schedule other than `always`, in a `DENY_ALL` pair is a VIOLATION with `TIME_IGNORED`; a fixture proves the `time` key is read (C4).
- `AF-5` An ASA ACE with `user`, `user-group` or `security-group` gives `IDENTITY_IGNORED`; a FortiGate policy with users or groups gives `NOT_EVALUABLE "depends on user identity"` until the Product Owner decides Q7 option A, then `IDENTITY_IGNORED`.
- `AF-6` An ASA ACL bound to two interfaces yields one headline entry per ACE (Q4).
- `AF-7` Inserting an ACE above an existing violating ACE does not reset its `firstObservedAt` (Q3 option B); editing the ACE resolves it as "rule changed" and opens a new finding.
- `AF-8` An HA pair with outcome `SAME` counts once in the headline; `HA_POLICY_DIVERGENT` and `UNKNOWN` pairs count each member and carry the pair outcome on the finding (Q2).
- `AF-9` An ASA interface with no inbound and no `global` ACL yields coverage item `ASA_SECURITY_LEVEL_DEFAULT_ALLOW_NOT_EVALUATED`; the policy never shows "no violations".
- `AF-10` An ACL with an unparsed reference is counted as use `UNKNOWN`, never "unused" and never clean; `ACL_NOT_BOUND` only per section 16 of the A0 amendment.
- `AF-11` A FortiGate policy whose destination is a VIP is evaluated in the original and the mapped view; a mapped-view-only violation carries `NAT_MAY_APPLY`; an unresolved mapped address is never COMPLIANT.
- `AF-12` A FortiGate setting absent without a cited default gives `NOT_EVALUABLE "default setting unproven"` and coverage `INCOMPLETE`.
- `AF-13` FortiGate `action ipsec`, ISDB and negated cells give their fixed-list reasons, never COMPLIANT.
- `AF-14` An ASA `any` gives "IPv6 not evaluated" for COMPLIANT while `any4` does not; a FortiGate rule with an `*addr6` cell is never COMPLIANT while IPv6 is not evaluated.
- `AF-15` An `in` or `global` ASA violation on a device with an `out` access-group carries `OTHER_ORDERED_LAYERS_UNKNOWN` and is outside the headline; without one it is a plain VIOLATION.
- `AF-16` Staleness of an ASA/FortiGate policy uses the artefact's `collected_at`, is labelled interim where the 24-hour value applies, and a stale policy never counts as resolved.
- `AF-17` A run starts once per new artefact `collected_at` and never twice for the same one.
- `AF-18` Masked views and aiview show pseudonymised interface, VDOM and ACL names; a planted-name log test finds none in logs.
- `AF-19` Until the amendment is live, ASA/FGT policies stay in the "not evaluated" list with their reason; after it, none is listed there while evaluable, and the 5.8 table matches the code (documentation test).
- `AF-20` The largest ASA and FortiGate snapshot against a 500-zone version at the block cap run within 30 seconds and 512 MB additional heap.
- `AF-21` A golden test: findings of a Check Point and a Palo Alto fixture are byte-identical before and after the change.

## 6. Contradictions found

Per the authority rule of AGENTS.md these are reported, not silently reconciled. None is resolved by this document.

| # | Contradiction or gap | Where | Higher authority / proposed resolution (not applied) |
| --- | --- | --- | --- |
| C1 | **Stale sentence.** Contract 5.8 says for Cisco ASA and FortiGate: "no ... No policy collection exists: 'not evaluated: policy not collected'." ASA and FortiGate policy snapshots exist: `LocalFirewallPolicyService.java:61-62,74,126`, `PolicyQueryService.java:555-568`, design sections 1 and 5, the frozen A0 amendment (incl. section 16), the backlog history of `asa_fortigate_policy_model`. The sentence is also the runtime reason text: `ZoneMatrixRunService.java:98-99,466`, `ZoneMatrixEvaluator.java:355-357`, `ZoneMatrixService.java:963,988`, so the product prints a wrong reason for these vendors today. | `ZONE_MATRIX_CONTRACT.md:340-341` (FROZEN, level 2) vs level 5 source and the A0 amendment (FROZEN) | Level 2 wins for what is *evaluated* (they are not evaluated by the zone matrix, which is true); the stated *reason* is wrong. Fix by a ratified amendment of the row and of the reason text, never by editing the frozen row. Until then the reason text could be reworded by a PO-approved wording change only. |
| C2 | Contract 5.1 starts runs "after every completed policy collection (`cp_policy_collect`, `pan_policy_collect`)" and says "snapshots are overwritten by the next collection". For ASA/FortiGate no policy collection job exists (`PolicyCollectionJobExecutor` is Check Point / Palo Alto only, design section 2) and the snapshot is derived on read from the configuration artefact. 5.1 has no trigger for these vendors. | `ZONE_MATRIX_CONTRACT.md:190-203` vs `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md` (DRAFT) sections 1, 5.1 | Amendment adds the trigger (section 1.1, 5.1 row). |
| C3 | The bridge's extras handling returns "dynamic or unsupported object type" for every key outside its skip list. Every mapped ASA rule carries `asa-direction` and `asa-acl`; the identity keys `user` and `security-group` (ASA) and `user` (FortiGate) are not mapped to `IDENTITY_IGNORED`. `PolicyNormalization` handles the same keys explicitly (`:347-356`, `:321-346`). | `ZoneMatrixPolicyBridge.java:690-731` vs `PolicyNormalization.java:283,347-356` | Code gap; the lane handles it under an amendment (step 1, 2). |
| C4 | Schedule key mismatch: the bridge reads `time` for Check Point and `schedule` for everything else (`:622-623`); the ASA and FortiGate mappers write `time` (`AsaPolicyMapper.java:291`, `FortiGatePolicyMapper.java:282`); `time` is in the bridge's skip list. Result if only the vendor gate were opened: the time constraint of an ASA or FortiGate rule is dropped with no `TIME_IGNORED` flag, which contract 5.4 and ZM-24 require. | `ZoneMatrixPolicyBridge.java:622-623,690-731` vs contract 5.4 / ZM-24 | Contract wins; code to read the key per vendor. |
| C5 | Two action sets disagree: `ZoneMatrixPolicyBridge.ALLOW_ACTIONS = {Accept, allow}` (`:33`) and `PolicyNormalization.ALLOWING_ACTIONS` including `permit`, `accept` (`:308`). Unreachable today, wrong the moment the gate opens. | `ZoneMatrixPolicyBridge.java:33` vs `PolicyNormalization.java:305-308` | Code gap. |
| C6 | Contract 3.1/5.2 treat placement as context only; the ASA/FortiGate design 5.4 item 1 and the ACI use of `PolicyLookup` make the interface (`nameif`) a **query input** (`PolicyLookup.Query.sourceZone/destinationZone`, `PolicyLookup.java:32,390-402`). Not a conflict (different consumers) but a reader could import the lookup's "interface required" rule into the matrix. | `ZONE_MATRIX_CONTRACT.md` 3.1, 5.2 vs `PolicyLookup.java:390-402` | State in the amendment that the matrix evaluates without the interface (section 1.1). |
| C7 | HA pair content hash: `HaPolicyPairing` hashes the rule comment (`HaPolicyPairing.java:55,206`), which carries ASA remark lines; the measurement record's consequence 9 says to "compare per policy family, ignore remarks; all 5 pairs then match" and records one pair differing only in remarks. With the code as written that pair would read `HA_POLICY_DIVERGENT`. Record item 6 of the 2026-11-14 decision says "policy-relevant content identical". Whether a remark is policy-relevant is not decided anywhere. | `HaPolicyPairing.java:55,73,206` vs `ASA_FGT_POLICY_SHAPE_MEASUREMENT_2026_11_14.md` section 4 item 9 | Report to the Product Owner session; decision belongs to the record's owner, not to this proposal (Q2 follow-up). |
| C8 | Staleness: the interim threshold is 24 hours (amendment 2026-11-23, 5.9). The design (section 3) recorded ASA/FortiGate configuration collection at "about 23 runs per device in 30 days", which is more than 24 hours apart on average. If still true, nearly every ASA/FortiGate policy would be `STALE` and the Overview would say "at least N" permanently. Cadence today is `UNKNOWN` (2026-11-14 figure, not re-measured). | `ZONE_MATRIX_CONTRACT.md` Amendment 2026-11-23 vs `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md` section 3 | Q5. The collection frequency is not changed here: a frequency change needs its own gate (AGENTS.md). |
| C9 | ASA/FortiGate design section 2 says "Zone matrix: no code on `main`; DRAFT contract on branch" and section 8 step 9 says the zone matrix consumes the snapshots "when its contract is frozen". The contract was frozen on 2026-11-13 and the code is on main. Step 9 of the design (2026-11-23 line) records only the ACI consumption as done. | `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md:69,349` | Stale text in a DRAFT document; this proposal is the zone-matrix half of its step 9. |
| C10 | Contract 5.7 step 2: "`any` includes IPv6 on both vendors" (Check Point, Palo Alto). On FortiGate the IPv4 `all` object is `0.0.0.0 0.0.0.0` (A0-FG-24) and IPv6 is a separate cell (A0-FG-16); on ASA `any4` is IPv4 only (A0-ASA-11). The sentence does not carry over. | `ZONE_MATRIX_CONTRACT.md:318-322` vs A0-FG-16/24, A0-ASA-11 | Amendment states the per-vendor reading (section 1.1, 5.7 row). |

## 7. Questions for the Product Owner

Recommended option first in each row.

| # | Question | Options, pros and cons | Recommendation |
| --- | --- | --- | --- |
| Q1 | **How is an ASA or FortiGate rule's interface or zone tied to a matrix zone?** | **A. Address-block derivation only** (the contract's rule today): a rule's addresses are placed by the bank's IP blocks, interface/zone names are shown beside the finding. + No new storage, no bank input, no inference, matches PO decision 1 and contract 3.1. - A rule such as `permit ip any any` on one interface is judged on `any` (many zone pairs), so it reports broad violations that its interface would narrow. Palo Alto and Check Point rules behave the same today. **B. Typed mapping record**: the bank types, per device and interface or zone, which matrix zones it can carry, and a rule placed there is narrowed to those zones. + Fewer broad false positives. - New table, rights, audit, four-eyes, bank input work and a contract section; a wrong or stale mapping hides violations (fail-open) unless the mapping is itself versioned and shown; larger estimate (step 8). **C. Derive from interface addresses and routes.** + No bank input. - An inference from presentation identity and incomplete routing evidence: rejected by the evidence laws (presentation identity != security identity; no routing claim, contract 1). | **A.** Revisit B only with a measured count of broad `any` rules that A over-reports (step 5 can count them). |
| Q2 | **Do HA pair members count once in the headline?** The 2026-11-14 record (item 6) already says a pair counts once when both members' policy-relevant content is identical and a difference is flagged `HA_POLICY_DIVERGENT`, never merged. Open: how, and the remarks rule. | **A. Count once for `SAME`** using the existing deterministic representative (as `PolicyHygieneFleetSummary.java:20`), show the other member as "same content"; `HA_POLICY_DIVERGENT` and `UNKNOWN` count each member and carry the pair outcome. + Matches the record and hygiene; no storage change (derived at read time from `HaPolicyPairService`). - A member's staleness or failed run needs a rule (the pair is counted once only when both members are `CURRENT`). **B. Count every member.** + Simplest. - Doubles every finding on five ASA pairs, against the record. **C. Count once by device-pair claim without comparing content.** - Hides a divergence: rejected (a member's report about its peer != independent observation). Follow-up for any option: decide whether remark lines are policy-relevant (C7). | **A**, with remarks treated as not policy-relevant only after the record's owner confirms (C7). |
| Q3 | **What identifies an ASA ACE across collections** (the stored text has no vendor id; `UNKNOWN` whether one exists)? FortiGate uses `uuid`/policy id. | **A. ACL name plus position** (today's `id`). + No new derivation. - Inserting an ACE resets `firstObservedAt` for every ACE below it, which falsely resets "since when" (5.9). **B. ACL name plus a canonical digest of the ACE's resolved content plus an occurrence index for identical ACEs** (an engineering fingerprint, never a vendor identity). + Stable under insertion and reordering. - An edit is a new identity ("rule changed" then a new finding); new derived field to specify and test. **C. Position, but carry `firstObserved` forward by matching digests.** - Two mechanisms, more risk. | **B** |
| Q4 | **An ASA ACL bound to several interfaces** (3 measured): one finding per ACE or per binding? | **A. One per (ACL, ACE)**, bindings listed as context. + The headline counts rules, not bindings; consistent with ZM-21. - The violated zone pairs do not depend on the binding in the matrix, so nothing is lost. **B. One per binding.** + Mirrors the stored rule ids. - Double counts the same ACE. | **A** |
| Q5 | **Staleness for ASA/FortiGate** given the cadence in C8: | **A. Keep 24 hours and show `STALE` honestly**, measure the real age distribution first (step 5). + No new number, never green. - Overview may read "at least N" permanently until resolved. **B. Per-vendor interim threshold set by the Product Owner** after the measurement, labelled interim like the 24-hour value. + Honest and workable. - Another interim number. **C. Raise collection frequency.** - Not an engineering choice: a frequency change needs its own gate and the Product Owner's approval; also a load question. | **A now, B after the measurement** |
| Q6 | **FortiGate VIP: a violation proven only in the mapped view** (A0-FG-19 is `UNKNOWN`). | **A. Counted in the headline with `NAT_MAY_APPLY`**, as 5.6 says. + One rule for all vendors; a hidden violation is worse than a flagged one. - May over-report where the VIP semantic differs. **B. Outside the headline**, like the unproven ordered-layer findings. + Conservative. - Needs a 7.2 change; hides a possible exposure from the headline. **C. `NOT_EVALUABLE "NAT translation unresolved"`.** - Loses the finding. Estate exposure today: 0 VIPs. | **A** |
| Q7 | **FortiGate users, groups, FSSO groups** (15 % of measured policies; A0-FG-12 MEDIUM; no decision like A0-ASA-16a). | **A. Extend the A0-ASA-16a reading to FortiGate for the zone matrix**: criteria only narrow; VIOLATION with `IDENTITY_IGNORED`, COMPLIANT through the bound. + Same treatment as ASA, Check Point roles (with 5.3's caution) and Palo Alto users; fewer undecided rules. - A MEDIUM source; needs an A0 amendment row and the Product Owner's decision. **B. `NOT_EVALUABLE "depends on user identity"`** for every such policy. + No new semantic claim. - 672 measured policies stay undecided. | **A**, as a separate Product Owner decision; code defaults to B until it is given |
| Q8 | **ASA security-level default and `same-security-traffic`**: | **A. Coverage item only** (no verdict). + Honest: the default has no address, so no zone pair can be proven (A0-ASA-4a HIGH; 4b LOW and 4c `UNKNOWN` stay `UNKNOWN`). - The matrix cannot say an unfiltered high-to-low interface pair breaks a `DENY_ALL` cell. **B. Evaluate as allow-any for such interface pairs.** + Catches the implicit allow. - Needs interface-to-zone knowledge, which brings back Q1 option B. | **A** |
| Q9 | **Order and scope**: | **A. ASA first, FortiGate second as its own PR**, as the 2026-11-14 record item 8 decided for the policy model. + ASA holds most ACEs and all five measured HA pairs; FortiGate has the larger defaults risk. - Two PRs. **B. Both in one step.** + One review. - A larger blast radius. | **A** |
| Q10 | **Hygiene cover flags** (`COVERED_BY_EARLIER_DENY`, `INLINE_LAYER_COVER`) for these vendors: | **A. Later**, flag never set meanwhile (ZM-25 holds). + Smaller first delivery; shadowing per ACL/VDOM needs its own tests (design step 7). - Covered rules are plain violations in the headline split. **B. In the same amendment.** + Complete parity. - Larger step and a hygiene budget question per ACL (largest ACL 9,072 ACEs). | **A** |
| Q11 | **Residual semantics stay `UNKNOWN`, stated in the limitations text**: ASA ACE written with a mapped address (A0-ASA-6/18), security-level checks before ACLs (A0-ASA-5), FortiGate VIP priority (A0-FG-18) and VIP address match (A0-FG-19), FortiGate `interface-subnet` MEDIUM resolution. | **A. Accept as listed**: findings say UNKNOWN or carry a flag; the report says so. **B. Require device corroboration first** (`packet-tracer` R2, a FortiGate equivalent): both deferred by record item 7, so B blocks the whole amendment. | **A** |

## 8. What this proposal does not do

- It edits no frozen document, adds no code, no migration and no command, and changes no gate row, frequency or context.
- It does not decide any question above, rank the Product Owner's priorities, or claim that the answers to Q1-Q11 are the
  only reasonable ones.
- It does not re-measure the estate; every count is quoted from the 2026-11-14 measurement record or the 2026-11-14
  design and is stale by that date.
- It has not run any decision council (an engineering session never invokes it). Q1 option B, if chosen, is a council
  candidate under the storage and identity triggers of GOV.PO.1.
