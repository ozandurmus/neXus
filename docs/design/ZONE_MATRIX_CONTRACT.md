# Zone matrix (segmentation policy) - contract

## Status

FROZEN - 2026-11-13, revision 2. Frozen by the Product Owner in the PO session on 2026-11-13 ("FROZEN, başlat"),
relayed by the PO session; implementation authority for the delivery order of section 10.1, one step at a time.
Revision 1 (commit 012fbbf7) was reviewed by the three-seat decision council on 2026-11-13 (network/vendor semantics,
compliance and change-control, security and adversarial; role-diverse review, same model family; seat outputs kept in
the PO session's ops notes). The PO approved incorporating every council change ("Evet, hepsini işle") and decided
four-eyes publishing ("Evet, şart"); the decisions are recorded in `PO_DECISION_RECORD_2026_11_13_ZONE_MATRIX.md`.

Bank inputs stay open as named input fields, not as contract gaps: the regulation reference fields of section 6.1
(`regulationReference`, `referenceStatus`, filled and confirmed by the bank's compliance team), and the inputs of
section 6.2 (regulation title and date, retention, staleness threshold, the team holding compliance_admin). The
product ships with these fields empty or at the stated interim behaviour, and never fills them itself.

## 1. Purpose and non-goals

Answer, for every collected firewall policy: **which enabled allow rules permit traffic between address zones that
the bank's segmentation policy does not allow, against which version of that policy, on which device, since when**,
and give an auditor an evidence report of the answer. This is the "unified security policy" capability of the
sector-leading products (Tufin, AlgoSec, FireMon), built on the neXus rule-resolution engine.

Non-goals (first delivery): no device write, no rule change or suggestion pushed anywhere, no ticket and no
integration with the bank's change system "smart" (PO decision 4), no SWIFT CSP or PCI DSS rule sets (PO decision 2),
no live traffic or log analysis (Rule object usage covers logs), no routing or reachability claim, no new device
command, no new collection, no accepted-deviation (exception) records (section 8.6).

Wording rules on every surface:

- Results are **policy intent as defined in management**, not observed traffic. Panorama sync state and Check Point
  install state are not verified. "Violation" means "this rule, as configured in management, permits traffic the
  matrix does not allow"; it does not say that such traffic happened.
- No surface, export or report says "BDDK compliant", "BDDK pass", or shows a BDDK score or percentage. The absence
  of proven violations is worded "no proven violations in evaluated rules", never "compliant", "0" or green, unless
  coverage is complete (section 7.2).

## 2. Terms

- **Zone**: a named set of IPv4 and IPv6 blocks (CIDR) defined by the bank in neXus. Not a firewall zone.
- **Matrix**: one statement per ordered pair of zones (source zone, destination zone), including a zone to itself.
- **Segmentation policy version** ("matrix version"): the zones, the matrix and the template origin, published
  together as one immutable, numbered, hashed version.
- **Evaluation run**: one run of every in-scope rule of one stored policy snapshot against one matrix version.
- **Finding**: the outcome for one rule in one evaluation run (section 5.4).
- **Policy source**: the stored policy a snapshot belongs to (Check Point package, Palo Alto device group or local
  firewall policy), by its opaque id.
- Synthetic examples in this document use documentation ranges only (`192.0.2.0/24`, `198.51.100.0/24`,
  `203.0.113.0/24`, `2001:db8::/32`) and invented zone names (`Z-USER`, `Z-APP`, `Z-DB`, `Z-DMZ`, `Z-MGMT`,
  `Z-PARTNER`).

## 3. Zone model

### 3.1 Zones

A zone has an opaque id (UUID, never reused), a name (unique, 1-64 characters, no control characters), an optional
description, an optional role from the template (section 6, for example `DATABASE`), and a list of blocks. A block is
an IPv4 or IPv6 CIDR in canonical form (host bits zero; `192.0.2.7/24` is refused, not silently corrected). A single
address is a `/32` or `/128`. Ranges that are not CIDR are entered as their exact CIDR decomposition, which the screen
offers and shows before saving.

Bounds: a total-block cap per version (proposed 200,000, set from the section 9 measurement, well below any size that
stresses the database), a per-zone cap, at most 500 zones, and a request size limit on every edit route. A request
over a bound is refused, never truncated.

Vendor zone or interface names (Palo Alto zones, Check Point security zones, interfaces) are never used to place an
address in a zone (PO decision 1). They may be shown beside a finding as context.

### 3.2 Overlap and the partition

Every address belongs to exactly one zone or to `UNASSIGNED`, per address family (council: accepted).

- **Nesting is allowed; the longest prefix wins.** If `Z-APP` holds `198.51.100.0/24` and `Z-MGMT` holds
  `198.51.100.128/28`, the `/28` addresses are `Z-MGMT`, the rest of the `/24` is `Z-APP`.
- **The same block in two zones is refused** at save time (`ZONE_BLOCK_DUPLICATE`), naming both zones.
- A block of one zone that duplicates or nests in another block of the same zone is accepted with a warning.
- The publish diff shows **addresses that move between zones** ("N addresses move from zone X to zone Y"), with each
  zone's effective address count before and after, not only block-list changes.
- Address lookup ("which zone is this address in, under version N") applies exactly the evaluation's rule. It is
  available only with `zone_matrix_zones_read` and every lookup is audited (3.4).

### 3.3 Unassigned address space

`UNASSIGNED` is a built-in pseudo-zone: every address of a family not covered by any block. It has a row and a column
in the matrix like any zone and its own statements. It is never silently treated as "internet" or "internal".

- `UNASSIGNED -> UNASSIGNED` defaults to `DENY_ALL`; it never inherits a template diagonal `ALLOW_ANY`.
- Assigning every address before publishing is not required (impossible for internet space).
- Rules whose addresses touch unassigned **private** ranges (RFC 1918 and IPv6 unique local addresses) are listed on
  the Zones tab and in the report as a "zone definition gap".
- Shrinking `UNASSIGNED` is a loosening change when the addresses move into a zone whose statements are less strict
  (3.5).

### 3.4 Rights

New actions in `ActionRegistry`:

| Action | Holders | What it allows |
| --- | --- | --- |
| `zone_matrix_read` | security_admin, compliance_admin, onboarding_admin, viewer; replay_viewer masked | Grid, statements, severities, zone names, counts, findings, version history and diffs without blocks. No blocks, no address lookup, no witness addresses. |
| `zone_matrix_zones_read` | security_admin, compliance_admin | Blocks, address lookup, witness addresses. Every raw view and every lookup writes an audit row. Never replay_viewer. |
| `zone_matrix_edit` | security_admin, compliance_admin | Change the draft (zones, blocks, statements, services, severities, reference text). |
| `zone_matrix_reference_confirm` | compliance_admin only | Set a cell's `referenceStatus` to `CONFIRMED`. |
| `zone_matrix_publish` | security_admin, compliance_admin | Publish the draft, under the four-eyes rule below. |
| `zone_matrix_evaluate` | security_admin, compliance_admin | Start an on-demand evaluation of one policy. |

- Rule detail: a holder of `zone_matrix_read` without `policy_read` sees rule number, rule id and the rule name
  through the `PolicyPrivacy` masked projection; object names and addresses are not shown.
- Export (section 7.3) follows the caller's screen view; raw content needs both the screen right and the Reports R1
  raw-view right (`report_raw_view`, security_admin, other roles only by the R1 rule for adding raw roles); never
  replay_viewer.

**Four-eyes publishing (PO decision, 2026-11-13: "Evet, şart").**

- Two different people, compared by canonical immutable principal id (the same canonicalization the failover
  approval uses), never by display name.
- At least one of the two holds compliance_admin: the publisher, or a person who edited the draft since the last
  publish.
- The publisher has not edited the draft and has not confirmed any reference in it since the last publish (not only
  "not the last editor").
- The publish request carries the draft revision and content hash the publisher reviewed; it is refused
  (`REQUEST_CHANGED`) when the draft changed since.
- The local root administrator and replay_viewer never publish, even when the account also holds an admin role.
- Refusals: `SELF_APPROVAL`, `COMPLIANCE_ROLE_MISSING`, `REQUEST_CHANGED`, `PRINCIPAL_NOT_ELIGIBLE`.

**References.** Setting `referenceStatus` to `CONFIRMED` is the separate `zone_matrix_reference_confirm` action
(compliance_admin only). Any later change to that cell's statement, services or severity sets it back to `PROPOSED`
and clears `confirmedBy` and `confirmedAt`.

### 3.5 Audit and versioning

- Audit rows hold change kind, actor, time, version and revision ids and content hashes only (column allowlist on the
  audit trigger). Full draft revisions go to an append-only `zone_matrix_revision` table, readable under
  `zone_matrix_read` (blocks only under `zone_matrix_zones_read`), because the audit log itself is security_admin only.
- Publish, reference confirmation, export, raw view and address lookup are audit events.
- At most one `DRAFT` and exactly one `ACTIVE` version once the first is published; earlier ones are `RETIRED`.
  Published versions are immutable; an edit always goes to the draft.
- A version's content is canonical JSON (zones sorted by id, blocks canonical and sorted, cells sorted by pair); its
  `sha256` is stored and shown.
- Every evaluation run and finding cites the version id, number and hash it was evaluated against.
- **Loosening changes** in the publish diff: statement relaxed (towards `ALLOW_ANY`), services added to a cell, the
  version default changed, severity downgraded, addresses moved between zones, `UNASSIGNED` shrunk. Each needs a
  rationale text before publish; the diff highlights them. Findings resolved by a version change are listed per
  version, and the Overview shows them for 7 days.
- The version default statement is `DENY_ALL` or `ALLOW_LISTED`; `ALLOW_ANY` exists only as an explicit cell.
- Publishing triggers re-evaluation of the latest stored snapshot of every in-scope policy (5.1).

## 4. Matrix

### 4.1 Statements

| Statement | Meaning |
| --- | --- |
| `DENY_ALL` | No allow rule may permit any traffic from the source zone to the destination zone. |
| `ALLOW_LISTED` | Only the listed service entries (4.2) may be permitted. |
| `ALLOW_ANY` | Any traffic may be permitted (explicit cells only). |

Each cell also carries: severity (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), an optional rationale, and the reference
fields of section 6.1. The screen marks cells that use the version default.

### 4.2 Service vocabulary

Entries are aligned with the dimensions the rule-resolution engine produces (`PolicyNormalization.Selector`: merged
ranges per protocol family, plus opaque tokens):

| Entry kind | Form | Evaluable in the first delivery |
| --- | --- | --- |
| TCP / UDP ports | `tcp/443`, `tcp/8000-8099`, `udp/53` | yes |
| ICMP / ICMPv6 type and code | `icmp/8`, `icmp/8/0`, `icmp6/128` | after hygiene model R1 and its A0-1 citation |
| IP protocol number | `ipproto/50` | after hygiene model R4 and collection fix C4 |
| Application (by name, opaque) | `app:pan:<application-name>` | Palo Alto only, by identity (4.3) |
| Named service set | a matrix-local name for a list of the above | yes (expanded before comparison) |

Vendor service objects are not matrix entries. The screen may offer "add the ports of this service object", which
copies resolved values at that time.

### 4.3 Applications

- A Palo Alto rule with application `X` and service `application-default` is COMPLIANT in a cell that lists
  `app:pan:X` (exact string equality, no case folding).
- The same rule in a cell that lists only ports is NOT_EVALUABLE "application ports not collected".
- A rule with application `X` and an explicit service list is evaluated on its service list (the application only
  narrows it).
- A rule with application `any` is a port rule.
- Check Point application/site objects: the port dimension is used as an upper bound; when that bound is not inside
  the cell, NOT_EVALUABLE "application constraint".

## 5. Evaluation

### 5.1 When and how it runs

- After every **completed** policy collection (`cp_policy_collect`, `pan_policy_collect`), for that policy only.
  Never on an intermediate checkpoint (hygiene collection fix C6).
- After a version is published: every policy's latest stored snapshot against the new version.
- On demand (`zone_matrix_evaluate`), same code path.
- Placement (council Q13): in ui2-service, next to the engine, on its **own bounded pool**, never the interactive
  `PolicyBulkhead`. Each run has a hard time limit and a measured heap ceiling; exceeding either ends the run
  `FAILED`. A worker job is preferred once the engine moves to a shared module; an analysis container is rejected
  (stored policy is not untrusted input, and it would add a transport and credential boundary).
- **Durable runs.** Each run has a row: `RUNNING`, `COMPLETE` or `FAILED`. Findings and the `COMPLETE` state are
  written in one transaction. Screens, Overview and reports read `COMPLETE` runs only. A run left `RUNNING` by a pod
  kill is set `FAILED` at startup; partial findings never become visible.
- Snapshots are overwritten by the next collection, so the run stores what evidence needs (8.1) at evaluation time.

### 5.2 What is evaluated

- Rules of the security rulebase only, in the evaluation scopes the engine supports (Check Point access layers
  including inline layers; Palo Alto pre / local / post rulebases). NAT rules and rules outside the security rulebase
  are "not analysed", counted separately.
- Enabled allow rules (Check Point `Accept`, Palo Alto `allow`). Deny-type rules (Check Point `Drop`, `Reject`; Palo
  Alto `deny`, `drop`, `reset-*`) are counted "not applicable". Disabled rules are counted, not evaluated. Unknown
  enabled state: NOT_EVALUABLE "enabled state is unknown".
- Check Point `Ask` / `Inform`: COMPLIANT only through the upper bound; otherwise NOT_EVALUABLE "action semantics not
  modelled".
- **Check Point Apply Layer parent**: evaluated as an Accept upper bound. COMPLIANT when its match is compliant.
  Otherwise, when its inline layer ends in an explicit catch-all rule, the parent is "not applicable (decided by its
  inline layer)" and its child rules carry the findings; when it does not, NOT_EVALUABLE "inline layer cleanup not
  collected". Never "action semantics not modelled".
- **Check Point inline child rule**: its own match is enough to prove COMPLIANT; the intersection with the parent's
  match is required to prove VIOLATION.
- **Palo Alto default-rule overrides** (collected when present in pre, post or local rulebases): one whose action is
  allow is evaluated as an allow rule for any source, destination and service, with flag `DEFAULT_RULE`.
- Not collected, therefore not evaluated, and shown as coverage items (7.1), never only as a footnote:
  - Palo Alto built-in `intrazone-default` allow: per device, "intrazone default allow; not evaluated" (two address
    zones behind one firewall zone are open with no rule).
  - Panorama mode: local firewall rules and firewall-level default overrides (`PAN_LOCAL_RULES_NOT_COLLECTED`,
    `PAN_DEFAULT_RULE_LEVELS_NOT_COLLECTED`). Coverage of a Panorama-sourced policy is never "complete".
  - Check Point implicit cleanup of each ordered and inline layer, and the Global Properties implied rules.
- Device placement (Palo Alto from/to zones, Check Point install-on) only narrows; it is context, not a decision input,
  except for the proven-cover check of `COVERED_BY_EARLIER_DENY` (5.5).

### 5.3 Upper-bound match function

The existing `normalize` returns no match at all for rules with source-user, time, VPN or content constraints, and the
partial pieces of `partial` are only valid for proving disjointness. The evaluation therefore uses a new function
`upperBoundMatch(rule)`:

- It drops narrowing constraints: Palo Alto source-user and schedule; Check Point time, VPN and content columns;
  applications (4.3); device placement. It records which were dropped.
- Every remaining selector (source, destination, service) must resolve; otherwise the rule is NOT_EVALUABLE with the
  resolution reason.
- It returns the exact match when nothing was dropped, else the upper bound with the dropped kinds.
- **Check Point access roles are never replaced by their networks for COMPLIANT.** The 2026-11-04 A0 access-role
  bound is valid only for proving disjointness (X-Forwarded-For detection, not collected, can match a proxied
  connection from outside those networks). For an access-role rule: COMPLIANT only when the rule is compliant with its
  source read as any address; VIOLATION with `IDENTITY_IGNORED` when the rule read with the role's networks violates;
  otherwise NOT_EVALUABLE "depends on user identity".

### 5.4 Outcomes, reasons, flags

| Outcome | Meaning |
| --- | --- |
| `VIOLATION` | Proven: the rule's match (or, with a flag, its upper bound) contains traffic from a source address in zone A to a destination address in zone B that the statement of (A, B) does not allow. Carries every violated cell and, per cell, the part that is not allowed. |
| `COMPLIANT` | Proven: every (A, B) pair the rule's upper bound touches allows every service it permits, in every NAT view (5.6), and no part of it is undecided. |
| `NOT_EVALUABLE` | A construct prevents a decision. Always carries a reason from the fixed list. |
| `UNKNOWN` | The run could not decide the rule: analysis budget, unsupported evaluation scope, unreadable snapshot, internal error. Carries a reason. |
| `NOT_APPLICABLE` | Counted, not a finding: deny-type, disabled, NAT / outside rulebase, Apply Layer parent decided by its layer, one-time schedule ended (with its end date). |

**Never COMPLIANT unless every dimension, every address family and every NAT view was decided.** A violation proven
on one cell stands even when another part is undecided; the undecided part is listed with it.

Identity- and time-limited rules (council Q4): Palo Alto source-user and schedules, Check Point VPN, content and time
columns are dropped by the upper bound. When the upper bound violates, the outcome is VIOLATION with flag
`IDENTITY_IGNORED`, `TIME_IGNORED` or `CONDITION_IGNORED` (VPN, content), worded "conditionally permits", and counted
in the headline. A one-time window that has ended (end before now minus the 14-hour timezone margin) is
`NOT_APPLICABLE` "schedule ended <date>". A schedule that cannot be resolved does not change this: VIOLATION with
`TIME_IGNORED` when the upper bound violates. Dynamic address objects (Check Point dynamic, updatable, DNS domain;
Palo Alto dynamic address group, external dynamic list, FQDN) are not narrowing: NOT_EVALUABLE "dynamic or
unsupported object type", unless the rule is compliant with the object read as any address.

NOT_EVALUABLE reasons (fixed vocabulary): "unresolved object", "group cycle or depth limit", "empty or dynamic group",
"dynamic or unsupported object type", "negated selector" (until hygiene R5 and A0-4), "unsupported service
constraint", "unsupported port expression", "application ports not collected", "application constraint", "IPv6 not
evaluated", "enabled state is unknown", "action semantics not modelled", "inline layer cleanup not collected",
"depends on user identity", "NAT translation unresolved".

Flags (not outcomes): `IDENTITY_IGNORED`, `TIME_IGNORED`, `CONDITION_IGNORED`, `COVERED_BY_EARLIER_DENY`,
`INLINE_LAYER_COVER`, `DENIED_BY_OTHER_ORDERED_LAYER`, `OTHER_ORDERED_LAYERS_UNKNOWN`, `NAT_MAY_APPLY`,
`NAT_PORT_SPLIT`, `DEFAULT_RULE`, `INSTALL_ON_UNRESOLVED`, `BECAME_UNDECIDED`.

### 5.5 Latent rules and ordered layers

- **`COVERED_BY_EARLIER_DENY`**: set only when the hygiene analysis has proven a CONFLICT (one earlier deny rule of the
  same evaluation scope and the same install targets / placement fully covers the rule). The covering rule's id is
  stored in the finding. When hygiene is UNKNOWN, no flag. The finding stays a VIOLATION, counted in the headline and
  shown as a split (7.2). Adding or removing the flag never resets first-observed.
- **`INLINE_LAYER_COVER`**: a rule never reached because an earlier Apply Layer rule covers it. Same treatment as
  `COVERED_BY_EARLIER_DENY` (counted, shown in the same split as "covered by an earlier rule").
- **Check Point multiple ordered layers.** A packet is allowed only when every ordered layer accepts. For a VIOLATION
  in a package with more than one ordered layer, the violation witness tuple (5.7 step 5, with one concrete TCP/UDP
  port from the not-allowed part) is checked in every other ordered layer with the existing lookup:
  - every other layer allows: VIOLATION;
  - any layer denies: flag `DENIED_BY_OTHER_ORDERED_LAYER`;
  - any layer UNKNOWN (cleanup not collected, no TCP/UDP witness, incomplete snapshot): flag
    `OTHER_ORDERED_LAYERS_UNKNOWN`.
  Findings with either flag are listed and reported but **outside the headline** (PO dissent resolution, 2026-11-13).

### 5.6 NAT dual view

Palo Alto security rules match pre-NAT addresses (A0 4a, HIGH); Check Point access versus NAT order is UNKNOWN
(A0 1b). Because a public address left in `UNASSIGNED` would otherwise give a false COMPLIANT, the first delivery
evaluates two views when a rule's destination intersects the original destination of an enabled destination or
static NAT rule of the same policy source:

- the original destination as written, and the translated destination of each matching NAT rule;
- COMPLIANT only when every view is compliant; VIOLATION when any view violates; when only a translated view violates,
  flag `NAT_MAY_APPLY`;
- a NAT rule whose translation does not resolve: the rule cannot be COMPLIANT; NOT_EVALUABLE "NAT translation
  unresolved" unless another view already proves a VIOLATION;
- one public address translated by port to servers in different zones (port-split VIP) is evaluated per translation
  and flagged `NAT_PORT_SPLIT`, because address-only zones cannot express it.

The guidance stays: the bank puts translated public addresses into the zone of their real servers where it can.

### 5.7 Algorithm (per rule, deterministic)

1. `upperBoundMatch` (5.3) in the legacy view, with the engine's per-rule work budget.
2. Source and destination per family. The engine is IPv4 only: an IPv6 value anywhere in a cell fails the whole rule
   today ("unsupported address representation"); this is mapped to NOT_EVALUABLE "IPv6 not evaluated". Per-family
   resolution (so a mixed IPv4/IPv6 object keeps its IPv4 VIOLATION) is a delivery step (10.1). `any` includes IPv6 on
   both vendors, so a rule with `any` in an address cell is never COMPLIANT while IPv6 is not evaluated; its IPv4
   VIOLATION is still reported.
3. Intersect source and destination ranges with the version's partition to get the touched zone pairs (exact interval
   arithmetic, no sampling). Repeat for each NAT view (5.6).
4. Per touched pair: `DENY_ALL` violated; `ALLOW_ANY` compliant; `ALLOW_LISTED` compliant when the rule's service
   ranges are inside the listed entries per family, else violated with the difference as the not-allowed part. A rule
   service `any` is never inside a finite list.
5. Record touched pairs, violated cells, not-allowed parts, and per violated cell one witness tuple (one source range,
   one destination range, one service), masked in masked views.
6. Apply the latent and ordered-layer checks (5.5) and combine as in 5.4.

Same snapshot, version and engine version give identical findings.

### 5.8 Device scope

| Vendor | Covered | Notes |
| --- | --- | --- |
| Check Point (SMS and MDS domains) | yes | Per policy package; the finding lists install targets, narrowed by install-on when it resolves to enrolled devices by equality, else all targets with `INSTALL_ON_UNRESOLVED`. |
| Palo Alto (Panorama device groups; local firewall policy when no Panorama node exists) | yes | Pre / local / post rulebases and collected default overrides. Panorama coverage is never complete (5.2). |
| Cisco ASA | no | No policy collection exists: "not evaluated: policy not collected". |
| FortiGate | no | Same as Cisco ASA. |

### 5.9 Finding identity, lifecycle and staleness

- Identity: (policy source, rule uid, inline parent rule id path, violated zone pair set). Shared Panorama rules and
  shared Check Point layers repeat a rule uid across policy sources; the policy source keeps them apart, and the parent
  path keeps a shared inline layer under several parents apart. Ids are compared exactly (identity law).
- `firstObservedAt` (this finding) and `firstObservedAnyVersion` (earliest observation of the same policy source, rule
  uid and zone pair in any version). Publishing a new version or toggling a flag never hides "since when".
- `lastObservedAt` is updated by each run that reproduces the finding.
- **Resolution** only when the rule is collected in a complete scope and decided not violating, or removed or disabled
  in a completely collected scope (reason: rule changed, rule removed or disabled, matrix version changed). A rule
  missing from a scope that was not collected is never resolved.
- **Became undecided**: a finding whose rule turns NOT_EVALUABLE or UNKNOWN stays open with flag `BECAME_UNDECIDED`,
  highlighted on the screen.
- **Staleness** per policy: `CURRENT` or `STALE`. `STALE` when the snapshot age exceeds the threshold (bank input,
  6.2), the last run is `FAILED`, or the last `COMPLETE` run used a version that is not active. Stale findings stay
  shown with their age; they never count as resolved.
- Coverage per policy: `COMPLETE` or `INCOMPLETE` (collection failures, Panorama, uncollected default or implicit
  rules). A snapshot with collection failures keeps its per-rule outcomes (evaluation is per rule) but is never shown
  as "no violations".

## 6. Template and BDDK references

BDDK's information-systems regulation states principles; it does not publish a zone matrix. neXus ships a template
named **"neXus segmentation template (proposal)"**. The bank's matrix is "a bank-owned segmentation policy seeded from
a neXus template", not a "BDDK rule set". neXus does not state which BDDK article requires what. The zone matrix is
kept separate from the existing `FINANCIAL_BASELINE` compliance mappings, whose section-style labels get their own
clean-up decision.

### 6.1 Template content

Zone roles (the bank maps its own zones to roles inside the product; real zone names never enter the repository):
`INTERNET_UNTRUSTED` (by default the `UNASSIGNED` space), `DMZ`, `USER`, `APPLICATION`, `DATABASE`, `MANAGEMENT`,
`PARTNER`, `NON_PRODUCTION`.

Proposed default statements (neXus proposal based on general segmentation practice, **not on a BDDK clause**):

| Source role -> destination role | Default | Severity | Reference | Confirmed by |
| --- | --- | --- | --- | --- |
| INTERNET_UNTRUSTED -> DMZ | ALLOW_LISTED (empty list) | HIGH | *compliance input* | *compliance_admin* |
| INTERNET_UNTRUSTED -> any internal role except DMZ | DENY_ALL | CRITICAL | *compliance input* | *compliance_admin* |
| UNASSIGNED -> UNASSIGNED | DENY_ALL | MEDIUM | *compliance input* | *compliance_admin* |
| DMZ -> APPLICATION | ALLOW_LISTED (empty list) | HIGH | *compliance input* | *compliance_admin* |
| DMZ -> DATABASE | DENY_ALL | CRITICAL | *compliance input* | *compliance_admin* |
| USER -> DATABASE | DENY_ALL | CRITICAL | *compliance input* | *compliance_admin* |
| USER -> MANAGEMENT | DENY_ALL | HIGH | *compliance input* | *compliance_admin* |
| USER -> APPLICATION | ALLOW_LISTED (empty list) | MEDIUM | *compliance input* | *compliance_admin* |
| APPLICATION -> DATABASE | ALLOW_LISTED (empty list) | HIGH | *compliance input* | *compliance_admin* |
| MANAGEMENT -> any role | ALLOW_LISTED (empty list) | MEDIUM | *compliance input* | *compliance_admin* |
| any role -> MANAGEMENT except MANAGEMENT | DENY_ALL | HIGH | *compliance input* | *compliance_admin* |
| PARTNER -> any role except DMZ | DENY_ALL | CRITICAL | *compliance input* | *compliance_admin* |
| NON_PRODUCTION <-> production roles (both directions) | DENY_ALL | HIGH | *compliance input* | *compliance_admin* |
| same role -> same role (diagonal, assigned zones only) | ALLOW_ANY (explicit cells) | LOW | *compliance input* | *compliance_admin* |
| internal role -> INTERNET_UNTRUSTED | ALLOW_LISTED (empty list) | MEDIUM | *compliance input* | *compliance_admin* |
| any other pair | DENY_ALL (version default) | MEDIUM | *compliance input* | *compliance_admin* |

"Empty list" means the bank lists the allowed services; until it does, every allow rule in that cell is a violation.

Per cell: `regulationReference` (free text: document, date or version, article as the compliance team cites it),
`referenceStatus` (`EMPTY`, `PROPOSED`, `CONFIRMED`), `confirmedBy`, `confirmedAt`. Only compliance_admin confirms;
a change to the cell resets the status to `PROPOSED` (3.4). neXus never fills these fields.

**BDDK labelling rule.** The word BDDK appears in a report title only when the bank has named the regulation (title
and date) and every cell that carries a reference is `CONFIRMED`. Otherwise the title is "Segmentation policy evidence
report". Every report header prints "N of M cells confirmed by compliance".

### 6.2 Input needed from the bank (PO / compliance)

1. The regulation the bank maps to: title, date and version of the BDDK regulation and any internal standard
   derived from it.
2. Which team holds compliance_admin, and whether that role is bound today (four-eyes needs it, 3.4).
3. The zone roles the bank uses, and whether the eight template roles fit.
4. For each template cell: statement, severity and regulation reference.
5. For each `ALLOW_LISTED` cell: the allowed services (and Palo Alto applications by name).
6. The IP blocks of each zone, entered in the product by an authorized user (never sent to engineering), including
   the zone of each translated public address.
7. Whether IPv6 is in scope for the first audit.
8. Retention of evaluations, findings and stored reports.
9. The snapshot staleness threshold (5.9).
10. The report fields, language (Turkish, English or both) and signature the auditors expect, and whether they need
    zone blocks in the report.
11. The bank's current list of accepted deviations (kept outside neXus until 8.6 is delivered).

## 7. Output

### 7.1 Screen

Menu **Policy and changes > Zone matrix** (name to confirm in the navigation review). Tabs:

- **Matrix**: zones x zones grid of the active version: statement (shape plus text, not colour alone), severity,
  number of open violations; a cell opens its violations. Any published version can be shown; the draft only to
  editors, labelled "draft, not evaluated".
- **Violations**: findings of the latest `COMPLETE` run of each policy. Filters: outcome, source zone, destination
  zone, severity, vendor, policy source, device, flag, reason, first observed, state (open, resolved, became
  undecided), staleness. Row: rule number, name, id, policy, devices, violated cells, not-allowed part, flags,
  covering rule id where flagged, first / first-any-version / last observed, matrix version. Actions: open the rule in
  the policy viewer; open Trace with the witness tuple (only with `zone_matrix_zones_read`).
- **Zones**: zones, effective sizes, overlap warnings, "zone definition gap" rules; blocks and address lookup only
  with `zone_matrix_zones_read`.
- **Versions**: history, diff with loosening changes and moved addresses, publish, reference confirmation,
  resolved-by-version lists.
- **Coverage** on every tab: per policy `CURRENT`/`STALE` and `COMPLETE`/`INCOMPLETE` with the coverage items of 5.2;
  vendors and devices not evaluated with reasons; snapshot ages.

### 7.2 Headline and Overview attention row

Headline = number of **distinct open (policy source, rule uid)** with outcome VIOLATION in the latest `COMPLETE` runs
against the **active version**, excluding `DENIED_BY_OTHER_ORDERED_LAYER` and `OTHER_ORDERED_LAYERS_UNKNOWN`. A rule's
severity is the highest severity of the cells it violates.

Shown together, in one row, never apart:

- "N rules violate the zone matrix (vK) across P zone pairs; C critical, of which L covered by an earlier rule and
  I conditional (identity / time)";
- "+X outside the headline (other ordered layer denies or unknown)";
- NOT_EVALUABLE count, UNKNOWN count, policies `STALE`, `INCOMPLETE`, not evaluated, or still on an older version;
- findings resolved by a version change in the last 7 days.

Never "0" or green unless every covered policy is `CURRENT` and `COMPLETE` and nothing is NOT_EVALUABLE or UNKNOWN;
otherwise "at least N" and, when N is zero, "no proven violations in evaluated rules". No active version: no row.
Per-cell counts appear on the Violations tab and in the report. The row shape is coordinated with the Overview
attention work in progress.

### 7.3 Evidence report

Generated through the Reports R1 pipeline and **stored as a file** in its file store; the audit row carries the
stored file's sha256, written before the first byte is streamed. CSV and PDF. CSV output passes the R1 formula guard
(cells starting with `=`, `+`, `-`, `@` and the like are escaped).

- **Period-based**: a from-to period; includes findings open at any time in the period and findings resolved within
  it, with first, first-any-version, last and resolved dates.
- **View**: the content follows the caller's screen view (masked or raw; blocks and witness addresses only with
  `zone_matrix_zones_read`). Raw content also needs `report_raw_view`. The blocks appendix is opt-in.
- Header: product version, generated at (UTC), generated by, masked or raw, period, filter (with counts for the
  filtered and the whole scope), "N of M cells confirmed by compliance", title per the BDDK labelling rule (6.1).
- Matrix version: number, hash, publish time, editors and publisher, template origin, every cell with statement,
  services, severity and reference fields exactly as entered, unconfirmed cells marked.
- Scope: each policy with vendor, devices, snapshot time, run time, `CURRENT`/`STALE`, `COMPLETE`/`INCOMPLETE` with
  coverage items; vendors and devices not evaluated with reasons.
- Summary: the headline of 7.2 with all its splits; counts per outcome, severity and cell.
- Findings: VIOLATION (including flagged and outside-headline ones, labelled), NOT_EVALUABLE and UNKNOWN with reasons,
  never dropped.
- Fixed sentences: "Accepted deviations are not recorded in neXus; every rule that conflicts with the matrix is
  listed." and the limitations text (policy intent as defined in management; implicit and default rules listed in
  coverage; IPv4 only in the first delivery; rule-level evaluation; NAT dual view).

## 8. Storage, privacy, retention

### 8.1 Storage (one migration, dry-run in BEGIN/ROLLBACK on the live database before rollout)

- `zone_matrix_version`: id, number, state, content (canonical JSONB), content_sha256, published by and at.
- `zone_matrix_revision`: append-only draft revisions (revision id, version id, actor, time, change kind, content
  hash, content, rationale for loosening changes).
- `zone_matrix_reference_confirmation`: cell, status, confirmed by and at, the cell hash it confirmed.
- `zone_matrix_run`: id, version id, policy source id, snapshot collected_at and size, engine version, state
  (`RUNNING`, `COMPLETE`, `FAILED`), started / finished, coverage, counts.
- `zone_matrix_finding`: run id, identity fields of 5.9, rule number / name / section as stored, targets, outcome,
  reason, flags, covering rule id, violated cells with not-allowed parts, witness tuples, NAT view, observed and
  resolved dates.
- No raw device response, no full rule copy, no snapshot copy. No delete route for runs, findings or revisions; only
  the retention janitor removes rows past the bank-defined retention.

### 8.2 Privacy

- Zone names and blocks describe the bank's network and are treated like device identities. For replay_viewer, zone
  names are pseudonymised (`ZONE-<n>` by stable order of id), and blocks and witness addresses are never shown; rule
  and object names pass through `PolicyPrivacy`.
- Logs and job records carry ids, version numbers, hashes and counts only, never zone names, blocks, addresses or rule
  names.
- Repository, tests, fixtures and screenshots use synthetic names and documentation ranges only.

### 8.3 Retention

Set by the bank (6.2 item 8) for runs, findings, revisions and stored reports. Versions are kept as long as anything
stored cites them. Until the bank states it, the Reports R1 retention applies to stored reports, and runs and findings
are not deleted. A zone-matrix report class with a bank-defined retention needs a Reports R1 contract change.

### 8.4 Authorization summary

Section 3.4. Evaluation runs as a system job with no user credential and no device access. aiview reads masked results
only and never edits, confirms, publishes, looks up addresses or exports raw.

### 8.5 Abuse cases the contract closes

Violation hidden by a broad deny above it (counted, split, 5.5); by a user or schedule condition (VIOLATION with flag,
5.4); by a nested `/32` or a widened cell (loosening diff with rationale, 3.5); by a default `ALLOW_ANY` (not allowed,
3.5); by severity downgrade (loosening diff); by merging rules (headline shows zone pairs too, 7.2); by resetting
"since when" (first-observed any version, 5.9); by breaking collection (`STALE`, 5.9); by an incomplete snapshot
(never resolved, 5.9); by two accounts of one person (canonical principal ids, compliance_admin in the pair, 3.4).
Map leakage: blocks, lookup and witnesses behind `zone_matrix_zones_read` with audited views; masked export by
default; CSV formula guard.

### 8.6 Exceptions (accepted deviations)

None in the first delivery. Before any report goes to an external auditor, a minimal exception record must exist,
under its own contract change: (policy source, rule uid, cell, hash of the rule's resolved match), reason, four-eyes
approval, mandatory expiry, version range. Any change to the rule or expiry cancels it. An excepted finding stays a
VIOLATION, flagged `EXCEPTED`, counted separately and always printed; it is never COMPLIANT. Until then, a widened
cell is not a substitute for an exception: every loosening change needs its rationale (3.5).

## 9. Performance budget

Measured before release on the 10 largest stored policies (counts only, in the pod; the largest has about 16,500
rules):

- The largest policy against a 500-zone version at the block cap: at most 30 seconds and 512 MB additional heap; all
  10 within 3 minutes. These are the hard ceilings of 5.1; a run that exceeds one ends `FAILED`. A rule over the
  per-rule work budget is UNKNOWN "analysis budget", never dropped.
- Own pool: at most one run per policy and two in total. Interactive policy screens keep their p95 during a fleet
  re-evaluation after a publish (measured).
- Screens read stored findings only: Matrix and Violations first page within 2 seconds at p95 with 50,000 findings;
  Overview row from a stored count within 200 ms.
- The block cap (3.1) is set from this measurement, including the cost of hashing and storing a revision.

## 10. Delivery order and acceptance criteria

### 10.1 Delivery order (small steps; each its own PR with targeted tests)

1. Zone model, versions, revisions, four-eyes publish, reference confirmation, audit, rights, Zones tab with audited
   address lookup. No evaluation.
2. Matrix statements and service vocabulary; template import with empty reference fields; loosening diff; Matrix tab
   (Fable).
3. Evaluation engine as a pure function: `upperBoundMatch`, IPv4, TCP/UDP, outcomes, reasons and flags, NAT dual view,
   ordered-layer witness check, Apply Layer handling, Palo Alto default overrides; synthetic fixtures; counts-only
   measurement on the 10 largest policies attached to the PR.
4. Durable runs on the own pool, triggers, finding lifecycle and staleness, Violations and Coverage tabs.
5. Overview attention row.
6. Evidence report (stored CSV, then PDF) through Reports R1.
7. Per-family address resolution (IPv4 kept for mixed objects); then, as prerequisites land: Palo Alto applications
   by name, ICMP / IP protocol (hygiene R1, R4), negation (R5), IPv6 evaluation, exception record (8.6).

### 10.2 Acceptance criteria

Origin in brackets: `R1` revision 1; `N`, `C`, `S` the network/vendor, compliance and security seats.

- `ZM-1` A version's partition puts every address in exactly one zone or `UNASSIGNED`; duplicate blocks across zones
  are refused; nesting follows the longest prefix (property test over random synthetic blocks). [R1]
- `ZM-2` Published versions are immutable; edits go to the draft. [R1]
- `ZM-3` Every run and finding cites a version id, number and hash; the hash recomputes from stored content. [R1]
- `ZM-4` A rule is COMPLIANT only when every touched pair allows every permitted service in every NAT view and every
  address family is decided; any undecided part gives NOT_EVALUABLE or UNKNOWN with a reason from the fixed list
  (unless a decided part proves VIOLATION). [R1]
- `ZM-5` A rule with service `any` in an `ALLOW_LISTED` pair is a VIOLATION; a rule in a `DENY_ALL` pair is a
  VIOLATION whatever its service. [R1]
- `ZM-6` Disabled, deny-type and NAT rules never produce a VIOLATION and are counted separately. [R1]
- `ZM-7` A snapshot with collection failures gives coverage `INCOMPLETE`; the policy never shows "no violations". [R1]
- `ZM-8` Runs start on completed collections only, never on a checkpoint. [R1]
- `ZM-9` Same snapshot, version and engine version give identical findings. [R1]
- `ZM-10` aiview sees pseudonymised zone names, no blocks or witness addresses, masked rule names; logs contain none of
  them (log masking test with planted names). [R1]
- `ZM-11` The report contains the version hash, every NOT_EVALUABLE and UNKNOWN finding, the limitations text and the
  reference fields exactly as entered; unconfirmed cells are marked. [R1]
- `ZM-12` The performance budget of section 9 holds on the 10 largest stored policies (measurement attached). [R1]
- `ZM-13` No code path of this feature issues a device command or writes to a device (architecture test). [R1]
- `ZM-14` A Palo Alto rule to a destination-NAT public address whose translated server is in a `DENY_ALL` cell is never
  COMPLIANT. [N1]
- `ZM-15` A Check Point access-role rule is COMPLIANT only when compliant with its source read as any (synthetic
  fixture with an outside "proxy" address). [N2]
- `ZM-16` An "Any Any Accept" in a second ordered layer, where layer 1 denies the witness, is not counted in the
  headline. [N3]
- `ZM-17` An Apply Layer parent is never "action semantics not modelled". [N4]
- `ZM-18` A collected Palo Alto default override with action allow produces findings with `DEFAULT_RULE`. [N5]
- `ZM-19` A mixed IPv4/IPv6 object yields "IPv6 not evaluated", and its IPv4 violation is reported once per-family
  resolution lands. [N6]
- `ZM-20` Panorama-sourced policies always show the coverage items "local rules not collected" and "intrazone
  default". [N7]
- `ZM-21` A rule uid shared across scopes is counted once per policy source in the headline. [N8]
- `ZM-22` Publishing is refused (`SELF_APPROVAL`) for any principal who edited the draft or confirmed a reference in it
  since the last publish (canonical principal ids); refused (`COMPLIANCE_ROLE_MISSING`) when neither the publisher nor
  an editor holds compliance_admin; refused for the local root administrator and for replay_viewer even with an admin
  role; refused (`REQUEST_CHANGED`) on a revision or hash mismatch. [C14, S1, PO]
- `ZM-23` Only compliance_admin sets `CONFIRMED`; a later change to the cell's statement, services or severity resets
  it to `PROPOSED` and clears `confirmedBy`. [C15, S2]
- `ZM-24` A user- or time-limited rule in a `DENY_ALL` pair gives a VIOLATION with its flag; an ended one-time window
  gives `NOT_APPLICABLE` with its date and is counted. [C16]
- `ZM-25` `COVERED_BY_EARLIER_DENY` is set only on a proven hygiene conflict with the same install targets, carries the
  covering rule id, and toggling it keeps first-observed. [C17]
- `ZM-26` Headline = distinct open (policy source, rule uid) against the active version; a fixture with incomplete
  coverage, a stale policy or one NOT_EVALUABLE rule never renders "0" or green. [C18, S5]
- `ZM-27` IPv4 compliant plus IPv6 `any` gives NOT_EVALUABLE "IPv6 not evaluated". [C19]
- `ZM-28` The stored report file's sha256 equals the hash in its audit row; CSV formula escaping passes a test with a
  planted `=cmd`. [C20, S8]
- `ZM-29` The publish diff lists addresses that move between zones, with effective counts. [C21]
- `ZM-30` No surface shows "BDDK compliant", "BDDK pass" or a BDDK score; the report title omits BDDK while any
  referenced cell is unconfirmed, and the header prints "N of M cells confirmed by compliance". [C22]
- `ZM-31` The report content follows the caller's screen view (rights test). [C23]
- `ZM-32` The report supports a period and includes findings resolved within it, with first, last and resolved
  dates. [C24]
- `ZM-33` viewer and aiview get no blocks, no address lookup and no witness ranges; every raw view or lookup writes an
  audit row. [S3]
- `ZM-34` VIOLATION turning NOT_EVALUABLE or UNKNOWN keeps the finding open with `BECAME_UNDECIDED`; a rule missing from
  an uncollected scope is never resolved. [S4]
- `ZM-35` A policy is `STALE` when its snapshot is older than the threshold, its last run is `FAILED`, or its version
  is not active; the Overview shows "at least N" unless every covered policy is `CURRENT` and `COMPLETE`. [S5]
- `ZM-36` The publish diff lists loosening changes (statement relaxed, services added, default changed, severity
  downgraded, addresses moved, `UNASSIGNED` shrunk), each with a required rationale; findings resolved by a version
  change are listed per version and shown on the Overview for 7 days. [S6]
- `ZM-37` Audit rows hold hashes and change kind only (column allowlist trigger); full revisions are in the
  append-only table; no delete route exists for runs, findings or revisions. [S7]
- `ZM-38` The blocks appendix is opt-in; raw export only for roles holding `report_raw_view`. [S8]
- `ZM-39` The run pool is separate from `PolicyBulkhead`; interactive policy p95 holds during a fleet re-evaluation;
  the heap ceiling and timeout end a run `FAILED`; a pod kill leaves `FAILED`, never partial findings. [S9]
- `ZM-40` A total-block cap per version and a request size limit are enforced (refused, never truncated). [S10]
- `ZM-41` `UNASSIGNED -> UNASSIGNED` defaults to `DENY_ALL`; the version default cannot be `ALLOW_ANY`. [C, S]
- `ZM-42` A first-observed date survives a new version: `firstObservedAnyVersion` equals the earliest observation of
  the same policy source, rule uid and zone pair. [C]

## 11. Council verdicts and remaining questions

### 11.1 Verdicts incorporated (council 2026-11-13, PO approval "Evet, hepsini işle")

| Question | Verdict | Where |
| --- | --- | --- |
| Q1 rule-level or flow-level | Rule-level, with the upper-bound match function and the ordered-layer check; flow-level stays a later view (the lookup refuses incomplete snapshots, caps address cuts, TCP/UDP only) | 5.3, 5.5 |
| Q2 NAT | Dual view for destination and static NAT in the first delivery; port-split VIPs flagged | 5.6 |
| Q3 rule covered by an earlier deny | VIOLATION + `COVERED_BY_EARLIER_DENY` on proven conflict only, covering rule id stored; counted in the headline, shown as a split (dissent resolved by the PO) | 5.5, 7.2 |
| Q4 identity / time | VIOLATION + flag, counted; access roles never read as their networks for COMPLIANT; ended windows `NOT_APPLICABLE` with date | 5.3, 5.4 |
| Q5 overlap | Longest prefix wins, duplicates refused, moved addresses in the publish diff | 3.2 |
| Q6 unassigned | Pseudo-zone; `UNASSIGNED -> UNASSIGNED` `DENY_ALL`; private unassigned ranges listed as gaps | 3.3 |
| Q7 rights | Four-eyes by canonical principal, one of the two holds compliance_admin (PO), hash-bound, root and aiview excluded; confirmation compliance_admin only | 3.4 |
| Q8 rule detail and blocks | Split read right; export follows the screen view; raw via `report_raw_view` | 3.4, 7.3 |
| Q9 exceptions | None in delivery 1; report sentence; loosening rationale; record required before external-auditor use | 7.3, 8.6 |
| Q10 ordered layers, Apply Layer | Witness check in other ordered layers, flagged findings outside the headline (PO); Apply Layer as Accept upper bound | 5.2, 5.5 |
| Q11 implicit and default rules | Palo Alto default overrides evaluated (`DEFAULT_RULE`); intrazone default and Panorama local rules as coverage items; Panorama never complete | 5.2 |
| Q12 IPv6 | Store blocks; map the engine's failure to "IPv6 not evaluated"; `any` never COMPLIANT; per-family resolution as a delivery step | 5.7 |
| Q13 placement | ui2-service on its own pool with durable runs and ceilings; analysis container rejected | 5.1 |
| Q14 headline | Distinct open (policy source, rule uid) against the active version, with splits, undecided counts and coverage alongside | 7.2 |

### 11.2 Still open (evidence or bank input needed; none blocks freezing)

- Check Point access versus NAT order (A0 1b): handled by the dual view; revisit if documented.
- Whether Check Point implicit cleanup values can be collected (would shrink `OTHER_ORDERED_LAYERS_UNKNOWN`).
- Whether the mapper duplicates shared inline layers per parent (finding identity uses the parent path either way).
- Panorama default-rule placement (pre or post rulebase), marked unverified in the mapper.
- Whether any gateway has X-Forwarded-For detection on (access-role rule stays conservative either way).
- Whether Check Point `Ask` / `Inform` permit non-HTTP traffic (stays upper bound only).
- Whether some installations have a single administrator or no bound compliance_admin, which blocks publishing.
- Whether directory principals can be compared by an immutable directory identifier yet.
- Root and break-glass alerting is not implemented yet (backlog NXS-LOCAL-0383).
- How often identity-, time-limited and covered rules occur in real policies (measured in delivery step 3).
- Whether hygiene results are reachable from the run within its budget (measured in delivery step 3).
- A rule with `any` on one side and IPv4-only values on the other: whether its IPv6 part can be treated as empty
  needs vendor evidence (NAT64); until then it is not COMPLIANT.

## Amendment 2026-11-23 (interim staleness threshold)

PO answer, chat 2026-11-23 (authorization ref PO-2026-11-23-ayyas-midday), recorded in
`PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md`. Until the bank states its own value for the snapshot staleness
threshold (6.2 item 9), a policy snapshot older than 24 hours is `STALE` (5.9). The value is interim and set by the
Product Owner, not the bank; the product labels it as interim and never presents it as the bank's value. It stays interim
until the bank answers; 6.2 item 9 remains a bank input. No earlier text of this contract is changed.
