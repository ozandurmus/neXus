# Rule recertification and cleanup campaigns - contract

**Status: DRAFT - 2026-11-13.** Written by an engineering session at the PO session's request, after the PO chose,
following the zone matrix, to plan rule recertification and cleanup campaigns. Not implementation authority. It goes
to the three-seat decision council (identity and authorization, compliance and evidence semantics, security and
adversarial) convened by the PO session, then to the PO for freezing. Every open point is listed in section 14; none
of them is decided by this draft. Examples are synthetic only (`FW-TANGO-04`, documentation address ranges, invented
team names, the nil-like UUID `00000000-0000-4000-8000-000000000001`).

Builds on, without changing them:

- [PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md](PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md) (hygiene classes; "writing
  rules to devices" rejected);
- `ui2/service/.../policy/PolicyHygiene.java` (classes `shadowed`, `conflict`, `disabled`, `unused`, `expired`,
  `broad`, `unknown`; sound mode on `main`, legacy mode on branch `feat/hygiene-legacy-resolution-1113`, not merged);
- [RULE_OBJECT_USAGE_CONTRACT.md](RULE_OBJECT_USAGE_CONTRACT.md) (FROZEN; per-object `USED` / `NOT_SEEN` from an
  uploaded log period);
- `policy_rule_history` (V120, V150) and the change actor (V208, `policy_change_actor_link`; Check Point
  last-modifier, Palo Alto config-log match);
- the zone matrix contract (DRAFT, branch `docs/zone-matrix-contract-1113`, not on `main`), sections 5.9 and 8.6;
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
| `PERIODIC` | Every in-scope security rule whose certification is not `CERTIFIED` at launch, or whose next review date falls before the campaign due date. | Yearly or by risk tier (open question Q1). |
| `CLEANUP` | In-scope rules with at least one **proven** finding of the selected classes (3.2). | On demand, or after a large hygiene change. |

A campaign has: name (1-120 characters, no control characters), type, scope, parameters, due date, campaign manager
(the principal who launched it), state, and a free-text purpose.

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
| Unused N days (`unused`) | Hygiene's `unused` finding with the campaign's N as the threshold. Two evidence forms exist today: "Last hit predates the threshold at counter collection time." and "Zero collected hits; counter reset/window is unknown."; the campaign parameter says whether the second form enrols (open question Q4). | Hit count, last hit, counter collection time, the counter-window text hygiene computes, hit source (management or per firewall); the latest Rule object usage result for the rule, if any, by id and period. |
| Expired (`expired`) | "Stored schedule has expired." Rules whose schedule only expires within 14 days are listed on the campaign screen, not enrolled. | The schedule end as stored. |
| Overly broad (`broad`) | "Accept rule matches any source, destination, service and application." enrols by default; "Broad selectors are present" enrols only when the campaign selects it. | The permissiveness explanation hygiene already produces. |
| Disabled (`disabled`) | Only when the campaign selects it ("Rule is disabled."). | Since when it is disabled, from rule history, when known. |
| Zone matrix violation | Only after zone matrix delivery step 4 and the alignment of section 6.6; outcome `VIOLATION` of a `COMPLETE` run against the active matrix version. | Violated cells, flags, matrix version and hash. |

Never enrolled as a candidate (listed on the campaign screen and in the report as "could not be evaluated", with
counts per reason): hygiene `unknown` findings and every `UNKNOWN` shadow status, unknown enabled state, "Hit counts
were not collected or are invalid.", "Hit counts are missing for a target.", "Zero-hit counter window is shorter than
the threshold.", "Zero hits contradict a recorded last hit.", "Last-hit time was not collected.", "Schedule evidence is
incomplete.", and zone matrix `NOT_EVALUABLE` / `UNKNOWN`. The scale matters: on the ten largest stored policies the
sound-mode shadowing analysis decided about 7,200 rules and left about 47,400 undecided (counts from the hygiene legacy
phase 2 measurement, 2026-11-13); a cleanup campaign must show that the undecided part exists, not only the
candidates.

Legacy-mode hygiene results (upper bound: time, user and other narrowing constraints dropped) do not enrol a rule as
shadowed or conflicting, because an upper bound proves disjointness, not coverage. Whether they may be shown as a
separate, labelled "possible" list is open question Q5.

### 3.3 Rule object usage as supporting evidence

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
| `DIRECTORY_GROUP` | Any signed-in principal whose current directory group set contains the group. | The group reference, stored and compared exactly like the existing `DIRECTORY_GROUP` role bindings (encrypted, exact equality, the 300-second group-set validity and on-demand refresh). neXus does not enumerate the group's members. |

Every owner has: a display label (1-120 characters), an optional contact mailbox for reminders, an optional
escalation mailbox, and a state `ACTIVE` or `RETIRED`. A `PERSON` owner whose local identity is disabled or deleted
becomes `RETIRED` automatically; for directory principals neXus cannot know that a person has left, so a `PERSON`
owner on a directory principal that has not signed in for a configurable number of days is flagged
`OWNER_NOT_SEEN` (open question Q7), never retired automatically.

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
| rule disabled | `INVALIDATED`, reason `RULE_DISABLED` | REMOVE: `DISABLED_NOT_REMOVED` (open question Q10) |

An invalidation records the snapshot `collected_at`, the old and new hashes, and the `policy_rule_history` revision id
when one exists, so the report can show who changed the rule on the manager when the change actor is known. An
invalidated KEEP puts the rule back to `DUE` immediately (it is enrolled in the next campaign, or in an open
`PERIODIC` campaign of its scope as a late item, open question Q11).

## 6. Decisions

### 6.1 The reviewer's choices

| Choice | Required | Optional | Result |
| --- | --- | --- | --- |
| `KEEP` | justification (20-2000 characters); next review date, not later than the campaign's maximum interval (default 12 months, open question Q1); for cleanup items, an answer to the finding (for example why a broad rule is needed) | business or change reference (free text, 1-200 characters); attached Rule object usage result ids | The rule is `CERTIFIED` until the next review date while its version is unchanged. |
| `REMOVE` | reason (20-2000 characters); a target date by which the bank's change process is expected to remove it (default campaign due date + 30 days) | change reference | `REMOVAL_REQUESTED` until verification (6.4). |
| `MODIFY` | description of the intended change (20-2000 characters); target date | change reference; attached Rule object usage result ids (for example the `NOT_SEEN` members to drop) | `MODIFICATION_REQUESTED` until a change is observed; then the new version needs its own KEEP (6.4). |

Non-decision actions on an item:

- `REASSIGN`: "not my rule"; returns the item to the fallback queue with a reason (20-500 characters). Audited.
- `COMMENT`: append a note to the item (does not change its state).
- No "skip", no "defer". The due date of an item can be extended only by the campaign manager, with a reason, once per
  item without four-eyes (open question Q12 for further extensions).

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
  snapshot's `collected_at`, plus the history revision and change actor when known). Disabled but present:
  `DISABLED_NOT_REMOVED`, shown apart (Q10). Absent from an incomplete snapshot: nothing is concluded.
- `MODIFY`: **change observed** when `ruleHash` or `objectsHash` changed. neXus cannot judge whether the change matches
  the intent; the item becomes `CHANGED_REVIEW_PENDING` and the owner must record a new decision (normally KEEP) on the
  new version. Until then the rule is not `CERTIFIED`.
- Target date passed with nothing observed: `CHANGE_OVERDUE`, escalated (section 9).
- The rule's policy source not collected since the decision, or only incompletely: `NOT_VERIFIED_YET`, with the age.

Verification never contacts a device; it reads the snapshot the normal collection stored.

### 6.5 Bulk decisions

An owner may apply one choice with one justification to several selected items at once. Each item still gets its own
decision row with its own rule version, flagged `BULK` with the bulk operation id and size. Bulk KEEP is limited to 50
items per operation and is never available for cleanup items of the classes `conflict`, `broad` (any-any) or zone
matrix violation (open question Q13). Bulk decisions are counted separately in the report so a reviewer who certified
hundreds of rules in minutes is visible.

### 6.6 Alignment with the zone matrix exception record

The zone matrix contract (8.6) requires a minimal exception record before any zone matrix report goes to an external
auditor: rule, cell, hash of the resolved match, reason, four-eyes approval, mandatory expiry, cancelled by any change.
A KEEP decision on a zone matrix violation item has the same shape: owner justification, four-eyes (8.2), next review
date as expiry, invalidation on any rule or object change. This draft proposes that such a KEEP **is** that exception
record (the finding stays `VIOLATION`, flagged `EXCEPTED`, as zone matrix 8.6 says), instead of building two
workflows. That needs a zone matrix contract amendment and is open question Q14 for the council. Until then, zone
matrix violations are not a cleanup source.

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
- Disabled rules are in scope of `PERIODIC` campaigns by default (a disabled rule can be re-enabled without a review),
  counted separately (open question Q10).
- NAT rules and rules outside the security rulebase are out of scope in the first delivery and counted as "not in
  scope".

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
- Report export follows Reports R1: masked by default; raw needs `report_raw_view`.

### 8.2 Four-eyes decisions

A decision needs a second person's approval before it takes effect when any of these holds (proposed; Q3):

- KEEP on a cleanup item of class `conflict`, `broad` (any-any) or zone matrix violation;
- KEEP whose next review date is beyond the campaign's default interval;
- KEEP recorded from the fallback queue (`DECIDED_WITHOUT_OWNER`);
- any decision on a rule the campaign marks high-risk (risk tier, Q1).

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
watermark per route, retry on failure). The routing contract is **type-based with fixed recipient lists**; reminders
to individual owners need an amendment of `NOTIFICATION_ROUTING_CONTRACT.md` (one new type `recertification` whose
recipients are resolved per owner from the owner's contact mailbox, plus a fallback list). neXus does not read mail
addresses from the directory today; a `PERSON` or `DIRECTORY_GROUP` owner without a contact mailbox gets no mail and
is listed as "no reminder address" on the campaign screen (Q15).

Schedule per item (defaults; per campaign):

| When | To |
| --- | --- |
| Launch (one digest per owner) | owner contact mailbox |
| Due date - 14 days, - 3 days | owner contact mailbox, only for items still undecided |
| Due date passed, then every 7 days | owner contact mailbox and owner escalation mailbox |
| Due date + 14 days | campaign manager's route recipients; the item is shown on the Overview attention row |
| `CHANGE_OVERDUE` (6.4) | owner and campaign manager's route recipients |
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

- `recert_owner` (id, kind, principal reference or team id or encrypted group reference, label, contact mailbox,
  escalation mailbox, state, created / updated by and at).
- `recert_team`, `recert_team_member` (team id, canonical principal id, added by / at; removal is a row state, not a
  delete).
- `recert_assignment` (scope kind `RULE` / `SECTION` / `POLICY_SOURCE`, scope ids, owner id, set by / at,
  superseded by / at): append-only.
- `recert_campaign` (id, type, name, purpose, scope JSON, parameters JSON, due date, state, launched by / at, closed
  by / at, closure summary JSON and SHA-256).
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
   REASSIGN, COMMENT, evidence rows, refusal on version change; "My reviews" for owners (Fable). No four-eyes yet:
   decisions that will need it are recorded `PENDING_APPROVAL` and stay uncertified.
4. **Invalidation and verification** after completed collections; certification states and the fail-closed headline.
5. **Four-eyes approvals** and campaign closure sign-off.
6. **Cleanup campaigns** from sound-mode hygiene (shadowed, conflict, unused, expired, broad, disabled), with the
   "could not be evaluated" counts; Rule object usage attachments.
7. **Reminders and escalation** (after the notification routing amendment); Overview attention row.
8. **Evidence reports** RPT-RC1 and RPT-RC2 (after the R1 amendment): stored CSV first, then PDF.
9. **Zone matrix violations** as a cleanup source and the exception alignment of 6.6, after zone matrix delivery step
   4 and its contract amendment.

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

## 14. Open questions

### 14.1 For the PO (product decisions)

- `Q1` Review cadence and risk tiers: one yearly cycle for all rules, or a shorter cycle for high-risk rules (any-any,
  internet-facing, zone matrix violations)? Default maximum KEEP interval (draft: 12 months)? What makes a rule
  high-risk?
- `Q4` Unused N: default N (for example 90 or 180 days), and may "zero collected hits, counter window unknown" enrol a
  rule, or only "last hit older than N"?
- `Q5` Legacy-mode (upper-bound) hygiene results: hide, or show as a labelled "possible" list that never enrols?
- `Q6` Does the bank already record owners in rule comments or a CMDB export we could import, and in what form
  (synthetic sample only)?
- `Q10` A rule disabled instead of removed after a REMOVE request: count as done, or keep it open until removed? Are
  disabled rules in scope of periodic review?
- `Q11` A KEEP invalidated during an open periodic campaign: add the rule to that campaign as a late item, or wait for
  the next one?
- `Q12` Due-date extensions: how many without four-eyes?
- `Q13` Bulk KEEP: allowed at all? Limit per operation (draft: 50)?
- `Q15` Reminder addresses: entered per owner in neXus (draft), read from the directory (a new LDAP attribute read,
  which needs its own review), or one team mailbox per campaign?
- `Q16` Retention of decision evidence and the BDDK / internal-audit reference for the review cycle: to be stated by
  the bank's compliance team.
- `Q17` Who receives the auditor report and in which view: masked by default, raw only with `report_raw_view`?

### 14.2 For the decision council (durable semantics)

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
