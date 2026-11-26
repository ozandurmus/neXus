# Rule recertification and cleanup campaigns - contract

**Status: DRAFT - 2026-11-26, revision 2.2 (factual corrections before the council; no semantic change).** Revision 1 (2026-11-13) was written by an engineering session at the PO
session's request, after the PO chose, following the zone matrix, to plan rule recertification and cleanup campaigns.
Revision 2 folds in the PO's product answers of 2026-11-14 (Q1, Q4, Q13 as PO decisions; Q5, Q10, Q11, Q12, Q15, Q17
as PO session recommendations the PO saw and did not object to), recorded in
[PO_DECISION_RECORD_2026_11_14_RULE_RECERTIFICATION_PRODUCT_ANSWERS.md](PO_DECISION_RECORD_2026_11_14_RULE_RECERTIFICATION_PRODUCT_ANSWERS.md);
section 14.1 lists them. Not implementation authority. It goes next to the three-seat decision council (identity and
authorization, compliance and evidence semantics, security and adversarial) convened by the PO session for the open
questions of section 14.3 — ten in all (Q2, Q3, Q7, Q8, Q9, Q14, Q18, Q19, Q20, Q21), including Q20 (definition of
internet-facing, added in revision 2) and Q21 (Cisco ASA and FortiGate scope, added in revision 2.1) — then to the PO
for freezing. Two points wait for bank input (section 14.2). Examples are
synthetic only (`FW-TANGO-04`, documentation address ranges, invented team names, the UUID
`00000000-0000-4000-8000-000000000001`).

**Revision 2.2 change note (2026-11-26).** Factual corrections from
[RULE_RECERTIFICATION_COUNCIL_PACKET_2026_11_25.md](RULE_RECERTIFICATION_COUNCIL_PACKET_2026_11_25.md) section 3
(re-verified against `origin/main` at `e53301d5`). No semantic change was made — no rule, decision, state, question,
answer or wording rule changed, no question was answered, and the status stays DRAFT; packet section 3 items 6, 8, 9
and 10 (`PrincipalCanonicalization` reuse, `policy_rule_history` revision link to `ruleHash`, hit-counter `unused`
semantics on HA pairs, and bulk-decision atomicity on `RULE_CHANGED_WHILE_REVIEWING`) are left open untouched for the
council seats and the PO session. Corrections applied:

- **Packet item 1 (zone matrix status — header, "Builds on", sections 3.2, 3.3, 6.6, 12 step 9):** updated from
  "delivery step 4 is not built" to what is on `main` today — steps 4a (`ZoneMatrixFindingLifecycle.java`,
  `ZoneMatrixStaleness.java`), 4b-1 (`V267__zone_matrix_runs_and_findings.sql`, `ZoneMatrixRunService.java`), 4b-2
  (`ZoneMatrixRunService.java`, `ZoneMatrixSummaryView.java`, `ZoneMatrixController.java`, `ViolationsTab.tsx`,
  `CoverageTab.tsx`), 5 (`zoneMatrixAttention.ts`), 6a/6b-1 (`ZoneMatrixEvidenceReport.java`,
  `ZoneMatrixEvidenceBlocksAppendix.java`, `ZoneMatrixEvidenceReportInputs.java`) and 7a/7b
  (`ZoneMatrixPolicyBridge.java`, `ServiceEntry.java`, `ZoneMatrixEvaluator.java`), per
  `docs/history/backlog/zone_matrix_delivery.md` and [ZONE_MATRIX_CONTRACT.md](ZONE_MATRIX_CONTRACT.md). Whether the
  whole of step 4 is done is not established (`zone_matrix_step_4b2` remains `in_progress`), Reports R1 wiring (step
  6b-2) and the section 8.6 exception record (step 7) are not built, and the conditions "after zone matrix delivery
  step 4" and "alignment of section 6.6" remain as written.
- **Packet item 2 (ASA and FortiGate snapshots — "Builds on", section 5.2, Q21 description):** Cisco ASA and FortiGate
  snapshots are derived on read in memory from the stored configuration artefact (`LocalFirewallPolicyService.java`),
  not stored as rows of `policy_snapshot` — no `policy_snapshot.policy_id`, no `policy_rule_history` capture
  (`V120__policy_rule_history.sql` foreign key to `policy_snapshot`), no completed-collection event and no zone matrix
  run (`ZoneMatrixRunService.java`). Whether a change actor (`V208__policy_change_actor_link.sql`) or hit counts can be
  established for these vendors remains `UNKNOWN`.
- **Packet item 3 (ASA `uuid` — Q21 description):** `AsaPolicyMapper.java` passes `null` for `Rule.uuid`, not an empty
  string (`V120__policy_rule_history.sql` `fn_policy_history_rules` treats both the same way).
- **Packet item 4 (ASA and FortiGate non-access rules — sections 3.2, 7):** named the ASA "not-evaluated" pseudo-rules
  per access list (`"ASA not evaluated"`, plus `"ASA implicit"`) and the FortiGate implicit rules (`"FGT implicit"`)
  and "not evaluated" policies (`"FGT not evaluated"`) as kinds that must be listed as "not in scope" and never
  enrolled if Q21 ever brings those vendors into scope (`AsaPolicyMapper.java`, `FortiGatePolicyMapper.java`,
  `PolicySnapshot.java`, `PolicyHygiene.java`).
- **Packet item 5 (ASA `unused` evidence and `ASA_ACL_USE_UNKNOWN` — section 3.2):** added hygiene's third `unused`
  evidence string ("ACL has no access-group binding and no other use was found; it is not enforced as an access rule.",
  `PolicyHygiene.ASA_ACL_NOT_REFERENCED`) and `ASA_ACL_USE_UNKNOWN` ("ACL is named by a configuration feature the parser
  does not read; whether it is used is UNKNOWN."), marked ASA-only and relevant only if Q21 brings ASA into scope
  (`PolicyHygiene.java`, `AsaPolicyMapper.java`).
- **Packet item 7 (300-second group-set validity — section 4.1):** reworded as a design value of the LDAP login
  observation (`LDAP_SERVICE_BIND_DESIGN_2026_11_11.md` section 6, `DirectoryAuthorizationRefresh.java` javadoc) rather
  than a code constant in the evaluator, which reads the stored `validUntil` (`RbacEvaluator.java`,
  `ActorAuthzStateRecord.java`); Q2's question text in section 14.3 is left unchanged.
- **Packet item 11 (question count — header):** header updated to state "ten in all (Q2, Q3, Q7, Q8, Q9, Q14, Q18, Q19,
  Q20, Q21)" (`RULE_RECERTIFICATION_CONTRACT.md` section 14.3).

**Revision 2.1 change note (2026-11-23).** A factual refresh so the council reads the draft against `main` as it is
today (checked against `origin/main` at `fb373545`). No rule, decision, state, question or wording rule changed, no
question was answered, and the status stays DRAFT. Corrections:

- The zone matrix contract is no longer a draft on a branch: it is `ZONE_MATRIX_CONTRACT.md`, FROZEN 2026-11-13
  revision 2, with delivery steps 1-3 on `main` (V216, V220, V228; commits `5bfd6fd1`, `31babb26`, `addc3ff2`). The
  cross-references to its sections 5.9 and 8.6 still point at the right clauses. Steps 4-7 are not built, so the
  conditions this contract puts on "after zone matrix delivery step 4" and on the exception record stay as written.
- The hygiene legacy mode is on `main` (commit `2ea30808`, no longer "on a branch, not merged") but is not wired to
  any screen or summary. Details in "Builds on".
- Cisco ASA and FortiGate have stored policy snapshots on `main`, which section 5.2 did not mention. Section 5.2 now
  says it lists Check Point and Palo Alto only, and the new question Q21 (section 14.3) asks the council whether ASA
  and FortiGate are in scope.
- The scale figures of section 3.2 come from a measurement note that is not on `main`; the text now says so.

Builds on, without changing them:

- [PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md](PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md) (hygiene classes; "writing
  rules to devices" rejected);
- `ui2/service/.../policy/PolicyHygiene.java` (classes `shadowed`, `conflict`, `disabled`, `unused`, `expired`,
  `broad`, `unknown`; the sound mode is what the Policy screen, the fleet summary and the zone matrix bridge call
  (`PolicyQueryService`, `ZoneMatrixPolicyBridge`); the legacy mode (`PolicyHygiene.analyzeLegacy`, `PolicyLegacyView`,
  commit `2ea30808`) is on `main` but called only by tests, so no screen or summary shows legacy results yet, which
  is why section 3.2 keeps them out of every count; hygiene also runs on ASA and FortiGate snapshots derived on read
  from stored configuration artefacts, see section 5.2);
- [RULE_OBJECT_USAGE_CONTRACT.md](RULE_OBJECT_USAGE_CONTRACT.md) (FROZEN; per-object `USED` / `NOT_SEEN` from an
  uploaded log period);
- `policy_rule_history` (V120, V150) and the change actor (V208, `policy_change_actor_link`; Check Point
  last-modifier, Palo Alto config-log match);
- [ZONE_MATRIX_CONTRACT.md](ZONE_MATRIX_CONTRACT.md) (FROZEN 2026-11-13, revision 2, with Amendment 2026-11-23; on
  `main` today: delivery steps 1-3 (V216, V220, V228), step 4a pure finding lifecycle and staleness, step 4b-1 durable
  runs and findings (V267), step 4b-2 lifecycle wiring, triggers, stored summary route and Violations/Coverage tabs
  (whether the whole of step 4 is done is not established; `zone_matrix_step_4b2` remains in progress), step 5 Overview
  attention row, steps 6a/6b-1 pure evidence report builders and input assembler (Reports R1 wiring in step 6b-2 not
  built), and steps 7a/7b per-family IPv4 resolution and Palo Alto `app:pan:<name>` applications), section 5.9 (finding
  identity) and section 8.6 (exceptions: not built, part of delivery step 7);
- [REPORTS_R1_CONTRACT.md](REPORTS_R1_CONTRACT.md) (FROZEN) and
  [NOTIFICATION_ROUTING_CONTRACT.md](NOTIFICATION_ROUTING_CONTRACT.md) (FROZEN), both of which need a small amendment
  (sections 9 and 10);
- the four-eyes pattern of the failover engine (`PrincipalCanonicalization`) and of the zone matrix (section 3.4).

## 1. Purpose and non-goals

Give the bank a workflow that turns what neXus already knows about firewall rules into **owner decisions with audit
evidence**: every security rule has a named owner; the owner periodically confirms that the rule is still needed (or
asks for it to be removed or changed), with a justification and a next review date; cleanup candidates found by the
analyses are put in front of their owners; and an auditor can be given, for any period, a report of who decided what,
when, on which version of which rule, and whether the requested changes were later seen on the firewall.

This is the "rule recertification" capability of the sector-leading products (FireMon, AlgoSec, Tufin): rule owners,
periodic review, justification, expiry, cleanup. The comparison is in direction only; nothing in this contract is
taken from their documentation.

Non-goals:

- **neXus never changes a device.** No device command, no rule write, no policy push, no "apply" button. A decision is
  a recorded intent. The change itself goes through the bank's own change process, outside neXus. neXus later checks,
  from its normal policy collection, whether the change is visible (section 6).
- No new collection and no new device command. Everything is computed from stored policy snapshots, rule history,
  hit counts already collected, hygiene results, Rule object usage results and (later) zone matrix findings.
- No ticket creation in, or integration with, the bank's change system in the first delivery. A change reference is a
  free text field.
- No automatic decision of any kind. A finding never becomes a decision without a person.
- No compliance score or percentage that hides undecided rules (NSPM wave 1: "compliance percentage cards without
  evidence" rejected).

Wording rules on every surface:

- A KEEP decision says "the owner confirmed this rule is needed", never "the rule is safe" or "compliant".
- A REMOVE or MODIFY decision says "removal requested" / "change requested" until a later collection shows it; then
  "removal observed on <date>" / "change observed on <date>". neXus never says "removed" for a rule it has not seen
  disappear from a completely collected policy.
- Rule content is policy intent as defined in management (Check Point management, Panorama, local firewall policy),
  as everywhere in the Policy plane. Install state and Panorama push state are not verified.

## 2. Terms

- **Rule**: one security rule of one stored policy, identified by (policy source id, rule key, inline parent path).
  The policy source is `policy_snapshot.policy_id` (opaque). The rule key is the one `policy_rule_history` uses
  (`uuid:<uid>`, or the name-based fallback with `identity_fallback = true`). The inline parent path keeps a Check
  Point inline-layer rule under several parents apart, as in the zone matrix (5.9). Ids are compared exactly
  (identity law).
- **Rule version**: the pair (`ruleHash`, `objectsHash`) of section 5.2.
- **Owner**: who answers for a rule: a person, a team or a directory group (section 4).
- **Campaign**: a bounded review exercise with a type, a scope, parameters, a due date and a state (section 3).
- **Review item**: one rule enrolled in one campaign, with the evidence that put it there.
- **Decision**: one immutable answer to one review item: `KEEP`, `REMOVE` or `MODIFY` (section 6).
- **Certification**: the derived per-rule state that says whether the rule currently has a valid KEEP decision
  (section 7).
- **Verification**: the later observation, from a completed policy collection, that a requested removal or change is
  (or is not) visible.

## 3. Campaigns

### 3.1 Campaign types

| Type | Enrols | Typical cadence |
| --- | --- | --- |
| `PERIODIC` | Every in-scope security rule whose certification is not `CERTIFIED` at launch, or whose next review date falls before the campaign due date. | Yearly for all rules; every 6 months for high-risk rules (3.3, PO 2026-11-14). |
| `CLEANUP` | In-scope rules with at least one **proven** finding of the selected classes (3.2). | On demand, or after a large hygiene change. |

A campaign has: name (1-120 characters, no control characters), type, scope, parameters, due date, campaign manager
(the principal who launched it), the campaign's team mailbox for reminders (section 9), state, and a free-text
purpose.

**Review intervals (PO 2026-11-14, Q1).** A KEEP decision is valid for at most 12 months; on a high-risk rule (3.3)
for at most 6 months. The reviewer may choose an earlier next review date, never a later one; the screen refuses a
later date (`REVIEW_INTERVAL_EXCEEDED`). Disabled rules are in scope of `PERIODIC` campaigns (PO session
recommendation, Q10): a disabled rule can be re-enabled without a review.

Scope: a set of policy sources (by opaque id), optionally narrowed to sections or layers by stored id, and to vendors.
A scope is frozen at launch: rules added to the policy later are not enrolled in that campaign (they become
`NEVER_REVIEWED`, section 7, and are picked up by the next `PERIODIC` campaign). The launch records, per policy
source, the snapshot `collected_at` it enrolled from and whether that snapshot was complete.

States: `DRAFT` (editable, nothing sent) -> `ACTIVE` (items enrolled, owners notified) -> `CLOSED` (by the campaign
manager or a second person, section 8.3) or `CANCELLED` (with a reason; decisions already made stay valid). A closed
campaign is immutable and has a closure summary with its content hash.

Enrolment refuses a policy source whose latest snapshot has collection failures for the part in scope, unless the
campaign manager explicitly accepts "enrol from an incomplete snapshot"; such items are flagged
`ENROLLED_FROM_INCOMPLETE` and no rule missing from that snapshot is ever treated as removed (6.4).

### 3.2 Cleanup candidate sources

Each cleanup item records the finding(s) that enrolled it, by class, evidence string, analysis run or computation time,
and the snapshot `collected_at` the finding was computed on. Only these proven outcomes enrol a rule:

| Source | Enrols when | Shown to the reviewer |
| --- | --- | --- |
| Shadowed (`shadowed`, status `REDUNDANT`) | Sound-mode hygiene proves an earlier enabled rule fully covers the rule with the same action, or an Apply Layer rule covers it (`INLINE_LAYER_COVER`). | Covering rule number, name and id. |
| Conflict (`conflict`, status `CONFLICT`) | Sound-mode hygiene proves an earlier rule fully covers it with a different action. | Covering rule; "this rule is never reached and its action is not applied". |
| Unused 180 days (`unused`) | Hygiene's `unused` finding with evidence "Last hit predates the threshold at counter collection time." computed with a threshold of **180 days** (PO 2026-11-14, Q4). "Zero collected hits; counter reset/window is unknown." does **not** enrol: a rule whose counter window is unknown is listed under "could not be evaluated". Hygiene's third `unused` evidence string, "ACL has no access-group binding and no other use was found; it is not enforced as an access rule." (`ASA_ACL_NOT_REFERENCED`, ASA-only, relevant only if Q21 brings Cisco ASA into scope), is not hit-based and is emitted on an "ASA not evaluated" pseudo-rule rather than an access rule, so it does **not** enrol either. | Hit count, last hit, counter collection time, the counter-window text hygiene computes, hit source (management or per firewall); the latest Rule object usage result for the rule, if any, by id and period. |
| Expired (`expired`) | "Stored schedule has expired." Rules whose schedule only expires within 14 days are listed on the campaign screen, not enrolled. | The schedule end as stored. |
| Overly broad (`broad`) | "Accept rule matches any source, destination, service and application." enrols by default; "Broad selectors are present" enrols only when the campaign selects it. | The permissiveness explanation hygiene already produces. |
| Disabled (`disabled`) | Only when the campaign selects it ("Rule is disabled."). | Since when it is disabled, from rule history, when known. |
| Zone matrix violation | Only after zone matrix delivery step 4 and the alignment of section 6.6 (steps 4a, 4b-1 and 4b-2 durable runs, findings, lifecycle, triggers and Violations/Coverage tabs, plus steps 5, 6a/6b-1 and 7a/7b, are on `main`; whether the whole of step 4 is done is not established here, and the section 8.6 exception record is not built); outcome `VIOLATION` of a `COMPLETE` run against the active matrix version. | Violated cells, flags, matrix version and hash. |

Never enrolled as a candidate (listed on the campaign screen and in the report as "could not be evaluated", with
counts per reason): hygiene `unknown` findings and every `UNKNOWN` shadow status, unknown enabled state, "Hit counts
were not collected or are invalid.", "Hit counts are missing for a target.", "Zero-hit counter window is shorter than
the threshold.", "Zero hits contradict a recorded last hit.", "Zero collected hits; counter reset/window is unknown.",
"Last-hit time was not collected.", "Schedule evidence is
incomplete.", zone matrix `NOT_EVALUABLE` / `UNKNOWN`, and — ASA-only, relevant only if Q21 brings Cisco ASA into
scope — "ACL has no access-group binding and no other use was found; it is not enforced as an access rule."
(`ASA_ACL_NOT_REFERENCED`, non-hit `unused` on an unbound ACL) and `ASA_ACL_USE_UNKNOWN` ("ACL is named by a
configuration feature the parser does not read; whether it is used is UNKNOWN."). Also never enrolled, and listed as
"not in scope" (section 7) if Q21 ever brings Cisco ASA or FortiGate into scope: the ASA "not-evaluated" pseudo-rules
per access list (`"ASA not evaluated"`), the FortiGate implicit rules (`"FGT implicit"`, intrazone allow and implicit
deny) and the FortiGate "not evaluated" policies (`"FGT not evaluated"`), alongside NAT and other
outside-security-evaluation sections. The scale matters: on the ten largest stored policies the
sound-mode shadowing analysis decided about 7,200 rules and left about 47,400 undecided (counts from the hygiene legacy
phase 2 measurement, 2026-11-13; the measurement note is not on `main` and the counts were not re-derived in
revision 2.1); a cleanup campaign must show that the undecided part exists, not only the
candidates.

Legacy-mode hygiene results (upper bound: time, user and other narrowing constraints dropped) never enrol a rule as
shadowed or conflicting, because an upper bound proves disjointness, not coverage. They are shown on the campaign
screen as a separate list labelled "Possible (legacy analysis, not proven); not enrolled", with the constraints the
analysis dropped (PO session recommendation, Q5). The list is not counted as candidates on any headline, and a rule
on it can reach a review only through a `PERIODIC` campaign or an explicit, audited manual enrolment by the campaign
manager in `DRAFT` state (item flag `MANUALLY_ENROLLED`, with the reason).

### 3.3 High-risk rules

A rule is high-risk when any of these holds (PO 2026-11-14, Q1, categories named by the PO):

- **any-any**: hygiene's `broad` finding "Accept rule matches any source, destination, service and application.";
- **internet-facing**: the rule's resolved source or destination cell is `any` or contains an address outside the
  ranges the bank declares internal. The internal ranges come from the zone matrix (every zone except `UNASSIGNED`,
  once a matrix version is active) or, until then, from the private ranges (RFC 1918, RFC 4193) plus a list the bank
  confirms. The exact definition is frozen with this contract after the council (open question Q20);
- **zone matrix violation**: the rule has an open `VIOLATION` finding against the active matrix version (only after
  zone matrix delivery step 4; steps 4a, 4b-1, 4b-2, 5, 6a/6b-1 and 7a/7b are on `main`, while whether the whole of
  step 4 is done is not established here).

Fail-closed: a rule whose risk cannot be decided (an unresolved object, a group cycle or depth limit, an incomplete
snapshot, a zone matrix `NOT_EVALUABLE` or `UNKNOWN` outcome) is treated as high-risk, with the flag
`RISK_UNDECIDED` and its reason. Risk is recomputed at enrolment and at every decision; a KEEP's maximum interval
follows the risk at decision time, and a rule that becomes high-risk later has its next review date shortened to at
most 6 months from the decision (event `RISK_RAISED`).

### 3.4 Rule object usage as supporting evidence

A reviewer of an unused or broad candidate can open the rule in Rule object usage and attach the id of a stored
One-rule result to the decision as evidence. The attachment is a reference (analysis id, period, counts); no row of
the uploaded file exists to attach. The frozen wording of Rule object usage section 5 is shown with it ("Not seen in
the uploaded period. This is not proof that the object is unused ..."). An attached result never makes a decision.

## 4. Rule ownership

### 4.1 Owner kinds

| Kind | Who can act for it | How it is identified |
| --- | --- | --- |
| `PERSON` | That principal. | A neXus principal: local identity or directory principal, by the canonical principal id used for four-eyes (never by display name). |
| `TEAM` | Any member of a team defined in neXus (a named list of principals kept in neXus). | Opaque team id; membership changes are audited. |
| `DIRECTORY_GROUP` | Any signed-in principal whose current directory group set contains the group. | The group reference, stored and compared exactly like the existing `DIRECTORY_GROUP` role bindings (encrypted, exact equality, freshness checked against the stored `validUntil` — the 300-second group-set validity is a design value of the LDAP login observation, not a constant in the evaluator — and on-demand refresh). neXus does not enumerate the group's members. |

Every owner has: a display label (1-120 characters) and a state `ACTIVE` or `RETIRED`. Owners carry no mail address:
reminders go to one team mailbox per campaign (section 9, PO session recommendation Q15). A `PERSON` owner whose
local identity is disabled or deleted becomes `RETIRED` automatically; for directory principals neXus cannot know that
a person has left, so a `PERSON` owner on a directory principal that has not signed in for a configurable number of
days is flagged `OWNER_NOT_SEEN` (open question Q7), never retired automatically.

### 4.2 Assignment

A rule's owner is resolved in this order, first match wins:

1. an explicit assignment to the rule (policy source, rule key, inline parent path);
2. an assignment to the rule's section or layer (by stored id);
3. an assignment to the policy source;
4. none: `OWNER_UNKNOWN`.

Assignments are made on screen, one by one or in bulk over a filtered rule list, or by CSV import (columns: policy
source id, rule key, owner label; every row is validated and the whole file is refused with per-row reasons when any
row does not resolve to exactly one rule and one owner; no partial import). Every assignment change is audited with
before and after owner ids.

**Owner hints (never assignments).** The screen shows, next to an unowned rule:

- the change actor of the rule's latest revision, when `policy_change_actor_link` says `MATCHED` (Palo Alto) or the
  Check Point last-modifier is stored, labelled "last changed on the manager by <administrator name>". The name is a
  manager administrator account, not a neXus principal; neXus never turns it into an owner by name matching (identity
  law). `AMBIGUOUS` and `UNKNOWN` links show as such.
- the rule's comment, when the bank adopts a comment convention for owners (open question Q6); shown as text only.

### 4.3 Unknown owner

- A rule with `OWNER_UNKNOWN` is still enrolled. Its item goes to the campaign's **fallback queue**, worked by the
  campaign manager's team (holders of `recert_campaign_manage`), who either assign an owner (the item then moves to
  that owner) or decide it themselves; such a decision is marked `DECIDED_WITHOUT_OWNER` in the evidence.
- Unknown-owner items are counted separately on every surface and in every report ("N rules without an owner").
- A rule is never certified because nobody answered. An item without a decision at the due date is `OVERDUE`, never
  `KEEP`.
- An owner change during a campaign moves open items to the new owner; decisions already made keep the owner that
  made them.

## 5. Rule identity and rule version

### 5.1 Identity

- Rule key and policy source as in section 2. Shared rules (Panorama shared rulebases, Check Point shared layers) that
  appear under several policy sources are separate rules with separate items and decisions (open question Q8 asks
  whether one decision may cover them all).
- Rules with `identity_fallback = true` (no uid, name-based key) can be reviewed, but their items and decisions carry
  the flag `IDENTITY_FALLBACK`: a rename makes a new rule, the old one is seen as removed, and the report lists them
  apart.
- A rule key that resolves to zero or several rules in the snapshot is refused (`RULE_NOT_FOUND`, `RULE_AMBIGUOUS`),
  as in Rule object usage.

### 5.2 Rule version

This section lists fields for Check Point and Palo Alto rules only. Cisco ASA and FortiGate snapshots on `main` are
derived on read from the stored configuration artefact (`LocalFirewallPolicyService`), not rows of `policy_snapshot`
(so there is no `policy_snapshot.policy_id`, no `policy_rule_history` capture, no completed-collection event and no
zone matrix run); whether they are in scope, and which of their fields form the rule version, is open question Q21.

A decision is bound to the exact rule it was made on. The rule version is two SHA-256 hashes, computed from the stored
snapshot at enrolment and again at decision time:

- **`ruleHash`**: the canonical JSON of the rule's security-relevant fields: enabled state, action, the source,
  destination, service and application references (by object id) with their negation flags, from and to zones,
  install-on / targets, users / access roles, time and schedule references, VPN, content, inline-layer reference, and
  the section or layer it belongs to. Excluded: rule number and position (renumbering is not a change, as in V150),
  name, comment, hit counts, last-modified fields, logging settings. The exact field list per vendor is frozen with
  this contract (open question Q9 asks whether name, comment, logging or a move to another section should invalidate).
- **`objectsHash`**: the canonical JSON of the stored definitions of every object reachable from the rule's cells
  (groups expanded recursively, by id, with the existing depth and cycle limits). An object the snapshot does not
  contain is hashed as a "missing <id>" marker, so the hash is deterministic even when resolution fails. A depth or
  cycle limit makes `objectsHash` `UNKNOWN`.

Why two hashes: a rule can keep every field and still permit more traffic because a group it uses grew. Both are part
of the evidence, and either changing invalidates a KEEP decision (5.3).

`ruleHash` and `objectsHash` are computed by one pure function with a version number (`hashVersion`). A change of that
function is a contract change; stored hashes keep their `hashVersion` and are never recomputed in place.

### 5.3 Invalidation

After every **completed** policy collection (never an intermediate checkpoint) the rule versions of the policy source
are recomputed for rules that have an open item or a current decision. Then, per rule:

| Observation | Effect on a KEEP decision | Effect on an open REMOVE / MODIFY request |
| --- | --- | --- |
| `ruleHash` and `objectsHash` unchanged | stays valid | still waiting (6.4) |
| `ruleHash` changed | `INVALIDATED`, reason `RULE_CHANGED` | MODIFY: change observed (6.4); REMOVE: still waiting, flag `CHANGED_NOT_REMOVED` |
| only `objectsHash` changed | `INVALIDATED`, reason `OBJECTS_CHANGED` | as above, flag `OBJECTS_CHANGED` |
| `objectsHash` becomes `UNKNOWN` | stays recorded, certification becomes `UNKNOWN` (7) | unchanged |
| rule absent from a **complete** snapshot of its scope | `INVALIDATED`, reason `RULE_REMOVED` | REMOVE: removal observed (6.4) |
| rule absent from an incomplete snapshot | nothing changes; certification `UNKNOWN` | nothing changes |
| rule disabled | `INVALIDATED`, reason `RULE_DISABLED` | REMOVE: stays open, flag `DISABLED_NOT_REMOVED` (Q10) |

An invalidation records the snapshot `collected_at`, the old and new hashes, and the `policy_rule_history` revision id
when one exists, so the report can show who changed the rule on the manager when the change actor is known. An
invalidated KEEP puts the rule back to `DUE` immediately and enrols it as a **late item** in the open `PERIODIC`
campaign whose scope covers it (item flag `LATE`, due date = the campaign due date or 30 days from enrolment, whichever
is later); with no such campaign open, it waits for the next one (PO session recommendation, Q11). Late items are
counted separately in the campaign and closure summaries.

## 6. Decisions

### 6.1 The reviewer's choices

| Choice | Required | Optional | Result |
| --- | --- | --- | --- |
| `KEEP` | justification (20-2000 characters); next review date, at most 12 months ahead, at most 6 months for a high-risk rule (3.1, 3.3); for cleanup items, an answer to the finding (for example why a broad rule is needed) | business or change reference (free text, 1-200 characters); attached Rule object usage result ids | The rule is `CERTIFIED` until the next review date while its version is unchanged. |
| `REMOVE` | reason (20-2000 characters); a target date by which the bank's change process is expected to remove it (default campaign due date + 30 days) | change reference | `REMOVAL_REQUESTED` until verification (6.4). |
| `MODIFY` | description of the intended change (20-2000 characters); target date | change reference; attached Rule object usage result ids (for example the `NOT_SEEN` members to drop) | `MODIFICATION_REQUESTED` until a change is observed; then the new version needs its own KEEP (6.4). |

Non-decision actions on an item:

- `REASSIGN`: "not my rule"; returns the item to the fallback queue with a reason (20-500 characters). Audited.
- `COMMENT`: append a note to the item (does not change its state).
- No "skip", no "defer". The due date of an item can be extended only by the campaign manager, with a reason. The
  first extension of an item needs no second person; every further extension of the same item needs four-eyes
  approval (8.2) (PO session recommendation, Q12). Extensions are counted in the closure summary and the report.

Free text is checked for control characters and length only. It is shown masked to `replay_viewer` (as "justification
present", not its content), because it may name systems or people. Every text field carries the warning "Do not
enter passwords, keys or personal data."

### 6.2 Evidence recorded with every decision

A decision row is immutable and holds:

- the item, campaign, policy source id, rule key, inline parent path, `IDENTITY_FALLBACK` flag;
- the rule version decided on (`ruleHash`, `objectsHash`, `hashVersion`) and the snapshot `collected_at` it was read
  from, plus the rule number and name **as stored at that time** (masked for `replay_viewer` through `PolicyPrivacy`);
- the evidence the reviewer saw: finding classes and evidence strings with their computation time, hit count and last
  hit with collection time, zone matrix finding ids and matrix version hash, attached Rule object usage result ids;
- the choice and every field of 6.1;
- the decider: canonical principal id, display name at that time, authentication authority (local or directory), the
  role tokens the session held, and the owner the decider acted for (owner id and kind; `DECIDED_WITHOUT_OWNER` when
  the fallback queue decided);
- decided at (UTC, server clock);
- for four-eyes decisions (8.2): the approver with the same identity fields, approved at, and the decision content
  hash the approver saw;
- `supersedes` (the decision it replaces, when an owner corrects a decision before the item closes) and, later,
  `invalidatedAt` / `invalidationReason` / the snapshot and hashes that caused it. Invalidation adds a separate row;
  the decision row itself is never updated.

A decision whose rule version at decision time differs from the version shown when the reviewer opened the item is
refused (`RULE_CHANGED_WHILE_REVIEWING`); the screen reloads the new version. The request carries the version the
reviewer saw, as the zone matrix publish request does.

### 6.3 What happens to the device

Nothing. The screen, the mail and the report say: "neXus records this decision. It does not change the firewall. Raise
the change through the bank's change process; neXus will show it when a later policy collection sees it."

### 6.4 Verification by the next collection

After each completed policy collection, for every open `REMOVE` or `MODIFY` request of that policy source:

- `REMOVE`: **removal observed** when the rule is absent from a complete snapshot of its scope (date = that
  snapshot's `collected_at`, plus the history revision and change actor when known). Disabled but present: the
  request **stays open** with flag `DISABLED_NOT_REMOVED`, shown apart, and still escalates as `CHANGE_OVERDUE` after
  its target date; a disabled rule is never counted as removed (PO session recommendation, Q10). Absent from an
  incomplete snapshot: nothing is concluded.
- `MODIFY`: **change observed** when `ruleHash` or `objectsHash` changed. neXus cannot judge whether the change matches
  the intent; the item becomes `CHANGED_REVIEW_PENDING` and the owner must record a new decision (normally KEEP) on the
  new version. Until then the rule is not `CERTIFIED`.
- Target date passed with nothing observed: `CHANGE_OVERDUE`, escalated (section 9).
- The rule's policy source not collected since the decision, or only incompletely: `NOT_VERIFIED_YET`, with the age.

Verification never contacts a device; it reads the snapshot the normal collection stored.

### 6.5 Bulk decisions

An owner may apply one choice with one justification to several selected items at once. Each item still gets its own
decision row with its own evidence record (6.2) and its own rule version, flagged `BULK` with the bulk operation id
and size. **Bulk KEEP is limited to 50 items per operation** (PO 2026-11-14, Q13); a larger selection is refused
(`BULK_LIMIT_EXCEEDED`), never truncated. **High-risk rules (3.3, including `RISK_UNDECIDED`) are excluded from bulk
KEEP** (PO 2026-11-14): a selection containing one is refused with the rules named, and each must be decided on its
own. Bulk decisions are counted separately in the report so a reviewer who certified many rules in minutes is
visible.

### 6.6 Alignment with the zone matrix exception record

The zone matrix contract (8.6) requires a minimal exception record before any zone matrix report goes to an external
auditor: rule, cell, hash of the resolved match, reason, four-eyes approval, mandatory expiry, cancelled by any change.
A KEEP decision on a zone matrix violation item has the same shape: owner justification, four-eyes (8.2), next review
date as expiry, invalidation on any rule or object change. This draft proposes that such a KEEP **is** that exception
record (the finding stays `VIOLATION`, flagged `EXCEPTED`, as zone matrix 8.6 says), instead of building two
workflows. On `main` today, durable zone matrix runs and findings (steps 4a, 4b-1 V267, 4b-2), the step 5 Overview
attention row, steps 6a/6b-1 pure report builders and steps 7a/7b are built (whether the whole of step 4 is done is not
established here), while the section 8.6 exception record (part of delivery step 7) is not built. That alignment needs
a zone matrix contract amendment and is open question Q14 for the council. Until then, zone matrix violations are not a
cleanup source.

## 7. Certification state (fail-closed)

Derived per rule, at read time, from the latest decision, the current rule version and the freshness of the policy
source:

| State | Meaning |
| --- | --- |
| `CERTIFIED` | A valid KEEP on the current rule version (both hashes equal, neither `UNKNOWN`), next review date not passed, and the policy source's latest snapshot is complete and not stale. |
| `DUE` | Next review date within the reminder window (default 30 days), or enrolled in an active campaign without a decision. |
| `OVERDUE` | Next review date passed, or campaign item past its due date without a decision. |
| `INVALIDATED` | The last KEEP was invalidated (5.3) and no new decision exists. |
| `REMOVAL_REQUESTED`, `MODIFICATION_REQUESTED`, `CHANGED_REVIEW_PENDING`, `CHANGE_OVERDUE` | Section 6.4. |
| `NEVER_REVIEWED` | No decision has ever been recorded for this rule. |
| `UNKNOWN` | The state cannot be proven: snapshot stale or incomplete for the rule's scope, `objectsHash` `UNKNOWN`, rule key ambiguous, or the policy source not collected since the decision. Always carries a reason. |

Rules:

- **Only `CERTIFIED` counts as reviewed.** `UNKNOWN`, `NEVER_REVIEWED`, `INVALIDATED` and every "requested" state are
  never shown as reviewed, never green and never folded into a reviewed percentage.
- A headline is "N of M in-scope rules certified; X due, Y overdue, Z never reviewed, U unknown, R removal requested",
  always together. No headline without the `UNKNOWN` and `NEVER_REVIEWED` counts beside it.
- Staleness uses the single product-wide freshness scale (UI review item B1; until it lands, the same interim threshold
  the reports use). A certification read from a stale snapshot is `UNKNOWN` "policy not collected since <date>", not
  `CERTIFIED`.
- Disabled rules are in scope of `PERIODIC` campaigns (a disabled rule can be re-enabled without a review), counted
  separately (PO session recommendation, Q10).
- NAT rules and rules outside the security rulebase are out of scope in the first delivery and counted as "not in
  scope" (if Q21 ever brings Cisco ASA or FortiGate into scope, the ASA "not-evaluated" pseudo-rules per access list,
  the ASA implicit section, and the FortiGate implicit rules and "not evaluated" policies must also be listed as "not
  in scope", never enrolled).

## 8. Authorization and four-eyes

### 8.1 Actions (new, in `ActionRegistry`)

| Action | Holders (proposed) | What it allows |
| --- | --- | --- |
| `recert_read` | security_admin, compliance_admin, operation_admin, viewer; replay_viewer masked | Campaigns, items, states, decisions, counts. Rule detail through `PolicyPrivacy` like the Policy plane; free text masked for replay_viewer. |
| `recert_campaign_manage` | security_admin, compliance_admin | Create, launch, extend, cancel and close campaigns; work the fallback queue; reassign items. |
| `recert_owner_manage` | security_admin, compliance_admin | Owners, teams, assignments, CSV import. |
| `recert_decide` | Any signed-in principal **who acts for the item's owner** (4.1), checked per item; and holders of `recert_campaign_manage` for fallback-queue items | Record KEEP / REMOVE / MODIFY, REASSIGN, COMMENT. |
| `recert_approve` | security_admin, compliance_admin | Second approval of four-eyes decisions (8.2) and campaign closure (8.3). |

- Rule owners are often application owners who hold no neXus role today. How they get a session and a read view of
  only their own rules (a new narrow role, for example `rule_owner`, bound to a directory group; or an existing role)
  is an identity-authority decision for the council (open question Q2).
- replay_viewer (aiview) reads masked results only; it never decides, approves, assigns, launches, imports or exports
  raw. The local root identity never decides or approves (break-glass account; same rule as zone matrix publishing).
- Report export follows Reports R1: masked by default; raw needs `report_raw_view` (PO session recommendation, Q17).

### 8.2 Four-eyes decisions

A decision needs a second person's approval before it takes effect when any of these holds (proposed; Q3):

- KEEP on a cleanup item of class `conflict`, `broad` (any-any) or zone matrix violation;
- KEEP recorded from the fallback queue (`DECIDED_WITHOUT_OWNER`);
- any decision on a high-risk rule (3.3), including `RISK_UNDECIDED`;
- a second or later due-date extension of the same item (6.1, Q12).

A KEEP beyond the maximum interval (12 months; 6 months for high-risk rules) is refused outright, not approvable.

Rules (same pattern as the zone matrix publish and the failover approvals):

- Decider and approver are different people, compared by canonical immutable principal id
  (`PrincipalCanonicalization`), never by display name. The refusal is `SELF_APPROVAL`.
- The approver did not act for the same owner on the same item (a team member does not approve a teammate's decision
  for that team; Q3).
- The approval carries the decision content hash the approver reviewed and is refused (`REQUEST_CHANGED`) when the
  decision or the rule version changed since.
- Until approved, the decision is `PENDING_APPROVAL`, the rule is not `CERTIFIED`, and the item counts as undecided.
  A rejected decision returns the item to the owner with the approver's reason.
- Refusals: `SELF_APPROVAL`, `SAME_OWNER_APPROVER`, `REQUEST_CHANGED`, `PRINCIPAL_NOT_ELIGIBLE`.

### 8.3 Campaign closure

Closing a campaign with undecided items is allowed; they are listed as "closed without decision" and keep their
certification state (never certified by closure). Closure is a second-person action when the campaign is `PERIODIC`
(the closer is not the launcher; Q3), so the annual evidence is signed off by two people. The closure summary (counts
per state, per owner, per choice, bulk counts, unknown counts) is stored as canonical JSON with its SHA-256.

## 9. Reminders and escalation

Mail goes through the existing SMTP relay and the notification forwarder (single leader, advisory lock, own
watermark per route, retry on failure). Reminders go to **one team mailbox per campaign** (PO session recommendation,
Q15): the campaign manager enters it at launch (validated with the existing e-mail rule; launch is refused without
it). Owners carry no mail address, and neXus reads no mail attribute from the directory. The routing contract is
type-based with fixed recipient lists, so this needs a small amendment of `NOTIFICATION_ROUTING_CONTRACT.md`: one new
type `recertification` whose recipients are the campaign's team mailbox for campaign events, and the type's route
list for escalations. The team behind the mailbox passes the reminders to the owners.

Schedule per campaign (defaults; per campaign):

| When | To |
| --- | --- |
| Launch (one digest: items per owner) | campaign team mailbox |
| Due date - 14 days, - 3 days | campaign team mailbox, counts of items still undecided per owner |
| Due date passed, then every 7 days | campaign team mailbox |
| Due date + 14 days | the `recertification` route recipients; the item is shown on the Overview attention row |
| `CHANGE_OVERDUE` (6.4) | campaign team mailbox and the `recertification` route recipients |
| `PENDING_APPROVAL` older than 3 days | `recert_approve` route recipients |

Mail content: campaign name, counts per state, due date, the link path. **No rule names, object names, addresses or
justification text in mail** (masked-notification decision, V193). One digest per recipient per day at most.

The Overview attention row (coordinated with the overview-attention work) shows: overdue items, changes overdue, rules
never reviewed, unknown, in that order, always with counts beside each other.

## 10. Reports for auditors

Through the Reports R1 pipeline (one masking pass, stored file, audit row with the file's SHA-256 written before the
first byte is streamed, CSV formula guard, size cap). New templates need an R1 amendment:

- **RPT-RC1 Rule recertification evidence (period).** For a from-to period and a scope:
  - header: product version, generated at (UTC), generated by, masked or raw, period, scope, freshness (newest and
    oldest snapshot per policy source);
  - summary: the headline of section 7 at period end, plus decisions recorded in the period by choice, bulk decisions,
    fallback-queue decisions, four-eyes approvals and rejections, invalidations by reason, removals and changes
    requested versus observed;
  - per rule: policy source, rule number / name / id as stored (masked view by default), owner, state at period end,
    every decision in the period with decider, approver, time, choice, justification (raw view only), next review
    date, rule version hashes and snapshot time, invalidation if any, verification outcome and date;
  - lists that are never dropped: rules never reviewed, rules `UNKNOWN` with reason, rules without owner, campaigns
    closed with undecided items;
  - fixed sentences: "neXus records owner decisions; it does not change firewalls. A requested removal or change is
    shown as observed only when a later complete policy collection shows it." and "Rule content is policy intent as
    defined in management; install state is not verified."
- **RPT-RC2 Campaign closure.** One campaign: scope, parameters, enrolment snapshot times and completeness, every item
  with its outcome, the closure summary and its hash, who launched and who closed.

The annual review evidence is RPT-RC1 for the year plus RPT-RC2 for each campaign closed in it. No surface or report
says "100% recertified" or shows a green total unless every in-scope rule is `CERTIFIED`; the BDDK and internal-audit
references for the review cycle are left empty for the bank's compliance team to fill (Q16), as in the zone matrix.

## 11. Storage, privacy, retention

### 11.1 Storage (one migration per delivery step, each dry-run in BEGIN/ROLLBACK on the live database before rollout)

- `recert_owner` (id, kind, principal reference or team id or encrypted group reference, label, state, created /
  updated by and at).
- `recert_team`, `recert_team_member` (team id, canonical principal id, added by / at; removal is a row state, not a
  delete).
- `recert_assignment` (scope kind `RULE` / `SECTION` / `POLICY_SOURCE`, scope ids, owner id, set by / at,
  superseded by / at): append-only.
- `recert_campaign` (id, type, name, purpose, scope JSON, parameters JSON (including the unused threshold and the
  review intervals in force at launch), team mailbox, due date, state, launched by / at, closed by / at, closure
  summary JSON and SHA-256).
- `recert_item` (id, campaign id, policy source id, rule key, inline parent path, flags, enrolment rule version and
  snapshot time, enrolling evidence JSON (ids, classes, evidence strings, times; no rule copy), owner id at enrolment,
  current owner id, due date, state, state changed at).
- `recert_decision` (append-only; every field of 6.2).
- `recert_decision_event` (append-only: approval, rejection, invalidation, verification, with the snapshot time,
  hashes and history revision id).
- `recert_rule_version` (policy source id, rule key, inline parent path, `hashVersion`, `ruleHash`, `objectsHash`,
  computed from snapshot `collected_at`): only for rules with an open item or a current decision.

No raw device response, no full rule or snapshot copy, no uploaded log content. No delete route for campaigns, items,
decisions or events; a retention janitor removes rows only past the bank-defined retention (11.3). Audit rows for
every create, change, decision, approval, assignment, import, export and raw view (column allowlist on the audit
trigger: ids, kinds, hashes, counts, times; never free text or rule names).

### 11.2 Privacy

- Rule and object names pass through `PolicyPrivacy`; replay_viewer sees pseudonyms and no free text.
- Owner labels and team names may name people or departments: shown to `recert_read` holders, pseudonymised for
  replay_viewer (`OWNER-<n>` by stable id order).
- Logs and job records carry ids, hashes and counts only.
- Repository, tests, fixtures and screenshots use synthetic names, documentation ranges and synthetic UUIDs only.

### 11.3 Retention

Set by the bank (Q16). Decision evidence for an audited year must outlive the rule it concerns, so `recert_*` rows are
**not** deleted with the policy snapshot or the rule history (unlike `policy_change_actor_link`, whose candidate names
follow the history row, PO D5). A decision row keeps only the change-actor reference, not the name, when the history
row expires. Until the bank states a retention, nothing is deleted; stored reports follow the R1 retention.

## 12. Delivery order (small steps; each its own PR with targeted tests)

1. **Rule version function**: `ruleHash` / `objectsHash` per vendor as a pure function with `hashVersion`; synthetic
   fixtures for each field that does and does not invalidate; counts-only measurement on the ten largest stored
   policies (how many rules have `objectsHash` `UNKNOWN`, compute time) attached to the PR. No storage, no screen.
2. **Owners and assignment**: owner kinds, teams, assignment precedence, CSV import, owner hints from the change actor,
   unknown-owner listing; Policy and changes > Rule owners (Fable).
3. **Periodic campaign and decisions**: campaign model, enrolment from complete snapshots, KEEP / REMOVE / MODIFY,
   REASSIGN, COMMENT, evidence rows, refusal on version change, high-risk classification with `RISK_UNDECIDED` and
   the 12 / 6-month intervals, bulk KEEP (50, high-risk excluded), late items; "My reviews" for owners (Fable). No
   four-eyes yet: decisions that will need it are recorded `PENDING_APPROVAL` and stay uncertified.
4. **Invalidation and verification** after completed collections; certification states and the fail-closed headline.
5. **Four-eyes approvals** and campaign closure sign-off.
6. **Cleanup campaigns** from sound-mode hygiene (shadowed, conflict, unused 180 days on a known last hit, expired,
   broad, disabled), with the "could not be evaluated" counts and the labelled legacy "possible" list; Rule object
   usage attachments.
7. **Reminders and escalation** to the campaign team mailbox (after the notification routing amendment); Overview
   attention row.
8. **Evidence reports** RPT-RC1 and RPT-RC2 (after the R1 amendment): stored CSV first, then PDF.
9. **Zone matrix violations** as a cleanup source and the exception alignment of 6.6, after zone matrix delivery step
   4 and its contract amendment (steps 4a, 4b-1, 4b-2, 5, 6a/6b-1 and 7a/7b are on `main`; whether the whole of step 4
   is done is not established here, and the section 8.6 exception record is not built).

Each step lands behind the menu gate of the Policy plane; nothing is shown to owners before step 3.

## 13. Acceptance criteria

- `RC-1` A rule key that resolves to zero or several rules is refused with a named reason; identity-fallback rules
  carry `IDENTITY_FALLBACK` on every item, decision and report line.
- `RC-2` `ruleHash` changes for every field listed in 5.2 and does not change for rule number, name, comment, hit
  counts, last-modified fields or logging (per-vendor fixture tests); `objectsHash` changes when a nested group member
  changes; a depth or cycle limit gives `UNKNOWN`.
- `RC-3` A KEEP decision is `INVALIDATED` after a completed collection that changes either hash or removes the rule
  from a complete snapshot; an incomplete snapshot never invalidates and never verifies a removal.
- `RC-4` Only `CERTIFIED` counts as reviewed: a test over every other state proves none of them is counted, coloured
  green or folded into a percentage; every headline carries the unknown and never-reviewed counts.
- `RC-5` A decision request carrying a rule version other than the current one is refused
  (`RULE_CHANGED_WHILE_REVIEWING`).
- `RC-6` Decision rows are immutable (no update or delete grant; a test proves an update is refused); corrections and
  invalidations are new rows.
- `RC-7` Four-eyes: the same principal (after canonicalization, including trailing-space and case variants) cannot
  decide and approve; a changed decision or rule version refuses the approval; root and replay_viewer can neither
  decide nor approve.
- `RC-8` A principal who does not act for the item's owner cannot decide it (person, team member, directory group
  member checked per request, with the directory group-set validity rules).
- `RC-9` Cleanup enrolment uses only the proven outcomes of 3.2; every hygiene `UNKNOWN` reason appears in the "could
  not be evaluated" counts; legacy-mode results never enrol a rule as shadowed or conflicting.
- `RC-10` REMOVE is "removal observed" only after the rule is absent from a complete snapshot; MODIFY moves to
  `CHANGED_REVIEW_PENDING` on any hash change and is never certified without a new decision.
- `RC-11` No code path issues a device command or writes to a device (architecture test: the recertification package
  depends on no transport, collector or job-dispatch class).
- `RC-12` Mail contains no rule name, object name, address or free text (test with planted names).
- `RC-13` replay_viewer sees pseudonymised rule and owner names and no free text; logs contain none of them (log
  masking test with planted names).
- `RC-14` RPT-RC1 contains every never-reviewed, unknown and owner-less rule of the scope, the fixed sentences, and
  the hashes of every decision; the stored file's SHA-256 is in the audit row before the first byte is streamed.
- `RC-15` A closed campaign and its closure summary are immutable; the summary hash recomputes from stored content.
- `RC-16` A KEEP with a next review date more than 12 months ahead, or more than 6 months ahead on a high-risk rule, is
  refused (`REVIEW_INTERVAL_EXCEEDED`); a rule whose risk cannot be decided is treated as high-risk (`RISK_UNDECIDED`).
- `RC-17` A bulk KEEP over 50 items, or containing a high-risk rule, is refused as a whole with the reason; every item
  of an accepted bulk operation has its own decision row and evidence.
- `RC-18` Only "Last hit predates the threshold" with a 180-day threshold enrols an unused rule; "Zero collected hits;
  counter reset/window is unknown." appears in the "could not be evaluated" counts and never enrols.
- `RC-19` Legacy-mode results appear only in the labelled "possible" list and in no candidate count or headline.
- `RC-20` A disabled rule never satisfies a REMOVE request; the request stays open with `DISABLED_NOT_REMOVED`.
- `RC-21` A second extension of the same item without an approval is refused; the first needs none.

## 14. Questions: answered and open

### 14.1 Answered by the PO (2026-11-14)

Recorded in
[PO_DECISION_RECORD_2026_11_14_RULE_RECERTIFICATION_PRODUCT_ANSWERS.md](PO_DECISION_RECORD_2026_11_14_RULE_RECERTIFICATION_PRODUCT_ANSWERS.md).

| Question | Answer | Kind | Where |
| --- | --- | --- | --- |
| `Q1` Cadence, risk tiers, maximum KEEP interval | Yearly for all rules; every 6 months for high-risk rules (any-any, internet-facing, zone matrix violation); a KEEP is valid at most 12 months | PO decision | 3.1, 3.3, 6.1 |
| `Q4` Unused threshold | 180 days, on a known last hit only; rules whose counter window is unknown do not enrol | PO decision | 3.2 |
| `Q13` Bulk KEEP | Allowed, at most 50 per operation, each item its own evidence record, high-risk rules excluded | PO decision | 6.5 |
| `Q5` Legacy-mode results | A labelled "possible" list that never enrols | PO session recommendation, not objected to | 3.2 |
| `Q10` Disabled instead of removed | The REMOVE request stays open until the rule is removed; disabled rules are in periodic scope | PO session recommendation, not objected to | 3.1, 5.3, 6.4, 7 |
| `Q11` KEEP invalidated during an open campaign | Joins the open periodic campaign as a late item | PO session recommendation, not objected to | 5.3 |
| `Q12` Due-date extensions | At most one extension without four-eyes | PO session recommendation, not objected to | 6.1, 8.2 |
| `Q15` Reminder addresses | One team mailbox per campaign | PO session recommendation, not objected to | 4.1, 9 |
| `Q17` Auditor report view | Masked by default; raw only with `report_raw_view` | PO session recommendation, not objected to | 8.1, 10 |

### 14.2 Waiting for bank input (do not block the council)

- `Q6` Does the bank already record owners in rule comments or a CMDB export we could import, and in what form
  (synthetic sample only)?
- `Q16` Retention of decision evidence and the BDDK / internal-audit reference for the review cycle: to be stated by
  the bank's compliance team.

### 14.3 For the decision council (durable semantics)

- `Q2` Identity and authorization authority for rule owners who hold no neXus role: a new narrow role bound to a
  directory group, scoped to their own items; or decisions only by existing admin roles on the owner's behalf (which
  weakens the evidence)? How does a `DIRECTORY_GROUP` owner prove membership at decision time, given the 300-second
  group-set validity?
- `Q3` Four-eyes scope: which decisions need a second person (8.2), whether a teammate may approve, whether one of the
  two must hold compliance_admin (as for zone matrix publishing), and whether periodic campaign closure needs a second
  person.
- `Q7` Owner liveness for directory principals: neXus cannot see that a person left. Is "not signed in for N days" an
  acceptable signal, and what happens to their open items?
- `Q8` Shared rules under several policy sources: one decision per policy source (draft) or one decision for every
  occurrence of the same rule uid?
- `Q9` Rule version: which fields invalidate a decision (name, comment, logging, move to another section)? Should
  `objectsHash` invalidate on any reachable object change, or only on changes that widen the resolved match (which
  needs resolution and fails on `UNKNOWN` more often)?
- `Q14` Is a KEEP on a zone matrix violation the zone matrix exception record (6.6), with its four-eyes and expiry
  rules, or are they two separate records?
- `Q18` Evidence integrity: is the append-only table plus audit trigger enough for the auditor, or should decision rows
  be hash-chained (each row carrying the previous row's hash) so a database administrator's edit is detectable?
- `Q19` Can a change reference ever be checked (for example a ticket id format), or does it stay free text until a
  change-system integration is decided?

New in revision 2, for the council together with Q3:

- `Q20` "Internet-facing" (3.3): is "source or destination is `any` or contains an address outside the declared
  internal ranges" the right test, and which internal ranges apply before a zone matrix version is active (private
  ranges plus a bank-confirmed list, as drafted)?

New in revision 2.1, for the council:

- `Q21` Are Cisco ASA and FortiGate rules in recertification scope, and which fields form their rule version?
  Evidence (snapshots derived on read from the stored configuration artefact on `main`, not rows of `policy_snapshot` —
  `LocalFirewallPolicyService`, `AsaPolicyMapper`, `FortiGatePolicyMapper`, `PolicyLookup`, hygiene; no
  `policy_snapshot.policy_id`, no `policy_rule_history` capture, no completed-collection event and no zone matrix run;
  design `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md`, section 5.2 for ASA, 5.3 for FortiGate):
  - Cisco ASA, one section per access-group binding. Rule key: derived from the snapshot id, the binding, the ACL
    name and the ACE position; no per-rule uid is stored (`uuid` is `null` in the mapper), so inserting or removing an
    ACE changes the key of every later ACE of that ACL. Stored fields: enabled (inactive ACE = disabled), action
    (`permit`/`deny`, `UNKNOWN` for an ACE the parser did not understand), source, destination and service cells (by
    object id, no negation flag), no application, log, comment (preceding remark lines), and `extras`: `from`, `to`,
    `asa-direction`, `asa-acl`, `time` (time-range), `user`, `security-group`, `asa-unparsed`. Hit counts: the mapper
    sets none.
  - FortiGate, one snapshot per VDOM. Rule key: derived from the device, the VDOM and the `policyid`, with the
    position appended when a `policyid` repeats; `uuid` is the printed `set uuid` when present. Stored fields:
    enabled (`status`), action (`accept`/`deny`, raw value kept in `fgt-action` when unusual), source, destination and
    service cells (with negation flags per design 5.3), no application, log (`logtraffic`), comment (`comments`),
    and `extras`: `from`, `to` (interfaces or zones), `vpn`, `time` (schedule), `user`, `fgt-application`,
    `source-translation-type`, `translated-source`, `fgt-match-vip`, `fgt-match-vip-only`, `fgt-vip`,
    `fgt-unparsed`, `fgt-missing-default`. Hit counts: the mapper sets none.
  - Rule history (V120, V150) references `policy_snapshot(policy_id)` and therefore does not capture derived ASA or
    FortiGate snapshots (`LocalFirewallPolicyService`). UNKNOWN, not established here: whether a change actor (V208
    `policy_change_actor_link` references `policy_rule_history`) can be established for ASA and FortiGate; whether any
    hit counts are available or collected for these vendors, which the 180-day `unused` source (3.2) needs. The ASA and
    FortiGate objects behind the cells are not covered by the Rule object usage contract.
  - Options. **(1, recommended) Out of scope until a later revision:** ASA and FortiGate rules are not enrolled in any
    campaign in this contract; revision 3 (or the freeze) states the field lists once Q9 has settled which fields
    invalidate for Check Point and Palo Alto and once the ASA rule key question above is decided. Reason: the ASA
    key is positional, so every ACE insertion would orphan the decisions of the later ACEs, and the open questions
    Q8 and Q9 would be decided twice. **(2) In scope now:** the rule version for ASA and FortiGate is the stored
    fields listed above, minus whatever Q9 excludes, with an `IDENTITY_FALLBACK`-style flag on ASA rules (5.1); the
    council would also have to say how a positional ASA key survives an ACE insertion.
