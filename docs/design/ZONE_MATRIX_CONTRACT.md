# Zone matrix (segmentation policy) - contract

## Status

DRAFT - 2026-11-13. Written by the engineering session for the decision council and the PO. Not implementation
authority. Scope answers come from `PO_DECISION_RECORD_2026_11_13_ZONE_MATRIX.md` (RATIFIED: zones are IP blocks,
first rule set BDDK, list + Overview + evidence report, nothing written to devices, ticket reconciliation later).
Every item in section 11 stays open until the council has reviewed it and the PO has decided; the BDDK references of
section 6 stay empty until the bank's compliance team fills and confirms them.

## 1. Purpose and non-goals

Answer, for every collected firewall policy: **which enabled allow rules permit traffic between address zones that
the bank's segmentation policy does not allow, against which version of that policy, on which device, since when**,
and give an auditor an evidence report of the answer. This is the "unified security policy" capability of the
sector-leading products (Tufin, AlgoSec, FireMon), built on the neXus rule-resolution engine.

Non-goals (first delivery): no device write, no rule change or suggestion pushed anywhere, no ticket and no
integration with the bank's change system "smart" (PO decision 4), no SWIFT CSP or PCI DSS rule sets (PO decision 2),
no live traffic or log analysis (Rule object usage covers logs), no routing or reachability claim, no new device
command, no new collection.

Wording rule on every surface: results are **policy intent**, not observed traffic. "Violation" means "this rule, as
configured, permits traffic the matrix does not allow"; it does not say that such traffic happened.

## 2. Terms

- **Zone**: a named set of IPv4 and IPv6 blocks (CIDR) defined by the bank in neXus. Not a firewall zone.
- **Matrix**: one statement per ordered pair of zones (source zone, destination zone), including a zone to itself.
- **Segmentation policy version** ("matrix version"): the zones, the matrix and the rule-set template origin, published
  together as one immutable, numbered, hashed version.
- **Evaluation**: one run of every in-scope rule of one stored policy snapshot against one matrix version.
- **Finding**: the outcome for one rule in one evaluation (section 5.3).
- Synthetic examples in this document use documentation ranges only (`192.0.2.0/24`, `198.51.100.0/24`,
  `203.0.113.0/24`, `2001:db8::/32`) and invented zone names (`Z-USER`, `Z-APP`, `Z-DB`, `Z-DMZ`, `Z-MGMT`,
  `Z-PARTNER`).

## 3. Zone model

### 3.1 Zones

A zone has an opaque id (UUID, never reused), a name (unique, 1-64 characters, no control characters), an optional
description, an optional role from the template (section 6, for example `DATABASE`), and a list of blocks. A block is
an IPv4 or IPv6 CIDR in canonical form (host bits zero; `192.0.2.7/24` is refused, not silently corrected). A single
address is a `/32` or `/128`. Ranges that are not CIDR are entered as their exact CIDR decomposition, which the screen
offers and shows before saving. A zone may hold up to 10,000 blocks; a version up to 500 zones (bounds to be confirmed
by the performance measurement of section 9).

Vendor zone or interface names (Palo Alto zones, Check Point security zones, interfaces) are never used to place an
address in a zone (PO decision 1). They may be shown beside a finding as context.

### 3.2 Overlap and the partition

Every address belongs to exactly one zone or to `UNASSIGNED`, per address family. Proposed rule (open question
Q5):

- **Nesting is allowed; the longest prefix wins.** If `Z-APP` holds `198.51.100.0/24` and `Z-MGMT` holds
  `198.51.100.128/28`, the `/28` addresses are `Z-MGMT`, the rest of the `/24` is `Z-APP` (the same rule a routing
  table uses, so operators can predict it).
- **The same block in two zones is refused** at save time (`ZONE_BLOCK_DUPLICATE`), naming both zones.
- **A block inside one zone that duplicates or nests in another block of the same zone** is accepted and reported as
  redundant (warning, not error).
- The screen shows, for each zone, its effective address count after nesting, and an address lookup ("which zone is
  this address in, under version N") that applies exactly the evaluation's rule.

### 3.3 Unassigned address space

`UNASSIGNED` is a built-in pseudo-zone: every address of a family not covered by any block. It has a row and a column
in the matrix like any zone and its own statements. It is never silently treated as "internet" or as "internal".
The template proposes treating it as untrusted (deny to every internal zone unless stated), marked as PO/compliance
input (Q6). Rules whose addresses fall in `UNASSIGNED` are counted on the screen ("rules touching unassigned space"),
because they usually point at a zone definition gap.

### 3.4 Who may edit, audit, versioning

- New actions in `ActionRegistry` (proposed; Q7):
  - `zone_matrix_read`: view zones, matrix, versions, findings. security_admin, compliance_admin, onboarding_admin,
    viewer; replay_viewer (aiview) masked.
  - `zone_matrix_edit`: change the draft version (zones, blocks, statements, severities, reference fields).
    security_admin, compliance_admin.
  - `zone_matrix_publish`: publish a draft as the active version. security_admin, compliance_admin, and **never the
    same person as the last editor of that draft** (four-eyes; Q7).
  - `zone_matrix_export`: export an evidence report with real names and addresses. security_admin,
    compliance_admin. Masked roles export masked reports only.
- Rule detail inside findings (rule names, object names, resolved addresses) is policy content. Today `policy_read` is
  security_admin, onboarding_admin and replay_viewer; compliance_admin and viewer do not hold it. Proposed: a holder of
  `zone_matrix_read` without `policy_read` sees findings with rule number, rule id, device and zone pair, but not
  object names or addresses (Q8).
- Audit: every draft change is an audit event (actor, time, change kind, zone id or cell, before and after values in
  the audit store, which is access-controlled like the matrix itself). Publish is an audit event carrying the version
  number, content hash, editor and publisher. Export is an audit event carrying the report hash and scope.
- Versioning:
  - At most one `DRAFT` and exactly one `ACTIVE` version once the first is published; earlier ones are `RETIRED`.
    Published versions are immutable; an edit always goes to the draft.
  - A version's content is canonical JSON (zones sorted by id, blocks canonical and sorted, cells sorted by pair);
    its `sha256` is stored and shown.
  - Every evaluation and every finding cites the version id, number and hash it was evaluated against. A finding is
    never re-labelled to a later version; a later version produces new findings.
  - Publishing a version triggers re-evaluation of the latest stored snapshot of every in-scope policy (section 5.1).
  - The screen offers a diff between any two versions (zones, blocks, cells).

## 4. Matrix

### 4.1 Statements

Each ordered pair (source zone, destination zone), including `UNASSIGNED` and the diagonal, has one statement:

| Statement | Meaning |
| --- | --- |
| `DENY_ALL` | No allow rule may permit any traffic from the source zone to the destination zone. |
| `ALLOW_LISTED` | Only the listed service entries (4.2) may be permitted. |
| `ALLOW_ANY` | Any traffic may be permitted. |

The version carries one default statement for pairs without an explicit cell; the screen marks cells that use the
default. Each cell also carries: severity (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`; from the template, editable), an
optional free-text rationale, and the BDDK reference fields of section 6 (empty until compliance fills them).

### 4.2 Service vocabulary

Entries are aligned with the dimensions the rule-resolution engine already produces (`PolicyNormalization.Selector`:
merged ranges per protocol family, plus opaque tokens), so a matrix entry and a rule's resolved service are compared
in one representation:

| Entry kind | Form | Evaluable in the first delivery |
| --- | --- | --- |
| TCP / UDP ports | `tcp/443`, `tcp/8000-8099`, `udp/53` | yes |
| ICMP / ICMPv6 type and code | `icmp/8`, `icmp/8/0`, `icmp6/128` | after hygiene model R1 and its A0-1 citation |
| IP protocol number | `ipproto/50` | after hygiene model R4 and collection fix C4 |
| Application (by name, opaque) | `app:pan:<application-name>` | Palo Alto only, by identity (4.3) |
| Named service set | a matrix-local name for a list of the above (for example `SET-DB-PORTS`) | yes (expanded before comparison) |

Vendor service objects are not matrix entries: the matrix is vendor-independent. The screen may offer "add the ports
of this service object" as a convenience that copies resolved values at that time.

### 4.3 Applications

The engine treats applications as opaque identities that are never disjoint from anything, and Palo Alto App-ID
default ports are not collected (hygiene note, collection fix C1). Therefore:

- A Palo Alto rule with application `X` and service `application-default` is COMPLIANT in a cell when the cell lists
  `app:pan:X` (same identity, exact string equality, no case folding).
- The same rule in a cell that lists only ports is NOT_EVALUABLE "application ports not collected".
- A rule with application `X` and an explicit service list is evaluated on its service list (the application only
  narrows it): COMPLIANT when its services are inside the cell's port entries.
- A rule with application `any` is a port rule.
- Check Point application/site objects: the rule is evaluated on its port dimension as an upper bound (the
  application only narrows); when that bound is not inside the cell, NOT_EVALUABLE "application constraint" (first
  delivery).

## 5. Evaluation

### 5.1 When

- After every **completed** policy collection (`cp_policy_collect`, `pan_policy_collect`, scheduled or manual) for that
  policy only. Never on an intermediate checkpoint (hygiene collection fix C6: a running Check Point collection
  overwrites the live row with partial checkpoints).
- After a version is published: every policy's latest stored snapshot against the new version.
- On demand by a holder of `zone_matrix_edit` for one policy (same code path).
- Snapshots are overwritten by the next collection (only the latest is kept), so the evaluation stores what evidence
  needs (5.5) at evaluation time; it never relies on re-reading an old snapshot.

### 5.2 What is evaluated

- Rules of the security rulebase only, in the evaluation scopes the engine already supports (Check Point access
  layers including inline layers; Palo Alto pre / local / post rulebases). NAT rules and rules outside the security
  rulebase are "not analysed", counted separately, never NOT_EVALUABLE.
- Enabled rules with an allow action (Check Point `Accept`, Palo Alto `allow`). Deny-type actions (Check Point `Drop`,
  `Reject`; Palo Alto `deny`, `drop`, `reset-*`) cannot violate and are counted as "not applicable". Disabled rules are
  counted, not evaluated. Unknown enabled state: NOT_EVALUABLE "enabled state is unknown". Any other action (for
  example Check Point `Ask`, `Inform`): NOT_EVALUABLE "action semantics not modelled".
- A rule inside a Check Point inline layer is evaluated on its own match intersected with its parent rule's match.
- Implicit and default rules (Check Point implied rules and cleanup, Palo Alto `intrazone-default` /
  `interzone-default`) are not collected and are not evaluated; this is a stated limitation on every result (Q11).
- Device placement (Palo Alto from/to zones, Check Point install-on) only narrows where a rule applies; it is not used
  to decide compliance and is shown as context (rule-level evaluation, Q1).

### 5.3 Outcomes

Exactly one per evaluated rule:

| Outcome | Meaning |
| --- | --- |
| `VIOLATION` | Proven: the rule's resolved match contains traffic from a source address in zone A to a destination address in zone B that the statement of (A, B) does not allow. Carries every violated cell and, per cell, the part that is not allowed (services, or "any traffic" under `DENY_ALL`). |
| `COMPLIANT` | Proven: every (A, B) pair the rule's resolved addresses touch allows every service the rule permits. |
| `NOT_EVALUABLE` | The engine resolved the rule but a construct prevents a decision. Always carries a reason from the fixed list below. |
| `UNKNOWN` | The evaluation itself could not run for the rule: analysis budget exceeded, unsupported evaluation scope, snapshot unreadable, internal error. Carries a reason. |

**Never COMPLIANT unless every dimension was decided.** A rule with any unresolved or undecided part is
NOT_EVALUABLE or UNKNOWN, unless a decided part already proves a VIOLATION (a violation proven on one cell stands even
if another part is undecided; the undecided part is listed with it).

NOT_EVALUABLE reasons (fixed vocabulary, extended only by contract amendment; most reuse the engine's own reasons):
"unresolved object", "group cycle or depth limit", "empty or dynamic group", "dynamic or unsupported object type"
(Check Point dynamic objects, updatable objects, DNS domains; Palo Alto dynamic address groups, external dynamic
lists, FQDN objects), "negated selector" (until hygiene model R5 and its A0-4 citation), "unsupported service
constraint" (ICMP, IP protocol, RPC until R1/R4), "unsupported port expression", "application ports not collected",
"application constraint", "IPv6 not evaluated", "enabled state is unknown", "action semantics not modelled",
"depends on user identity" (see 5.4), "time or content constraint" (see 5.4).

Additional flags (not outcomes) on a finding: `IDENTITY_IGNORED`, `TIME_IGNORED`, `COVERED_BY_EARLIER_DENY`,
`NAT_MAY_APPLY`, `INSTALL_ON_UNRESOLVED` (sections 5.4 and 11).

### 5.4 The legacy IP model first

The PO priority is the classic first-match model on source, destination and service ("legacy" mode of the hygiene
design). Constraints that only **narrow** an allow rule have a useful asymmetry for compliance:

- **Upper bound for COMPLIANT (sound).** Dropping a narrowing constraint (user identity, time window, application,
  device placement) gives a superset of what the rule permits. If that superset is compliant, the rule is compliant.
  So these rules can be proven COMPLIANT without modelling the constraint.
- **VIOLATION needs more care.** If only the superset violates, the forbidden traffic exists only when the user,
  window or application condition holds. Proposed (Q4): report VIOLATION with flag `IDENTITY_IGNORED` or
  `TIME_IGNORED` ("permits this at IP level when the user/role or time condition holds"), shown under a separate
  filter, counted in the headline with the flag visible. A Check Point access role counts as the networks it names
  (A0 amendment 2026-11-04); a Palo Alto source-user condition is ignored with the flag. A one-time window that has
  ended (with the 14-hour timezone margin of the hygiene design) makes the rule match nothing: not applicable, no
  finding. Alternative for the council: NOT_EVALUABLE "depends on user identity" / "time or content constraint".
- **Dynamic address objects are not narrowing** in this sense: their membership is runtime state and may be any
  address. They give NOT_EVALUABLE unless the rule is COMPLIANT even with the object read as "any address".

### 5.5 Algorithm (per rule, deterministic)

1. Resolve the rule through the existing engine (`PolicyNormalization` resolver, the same one used by Trace and Rule
   object usage), in the legacy view, with the engine's per-rule work budget. Resolution problems map to the reasons
   of 5.3.
2. Source and destination: per family, the merged ranges of the cell (negation not supported in the first delivery).
   IPv4 only in the first delivery; a cell holding only IPv6 values gives NOT_EVALUABLE "IPv6 not evaluated"; a cell
   holding `any` covers every IPv4 zone and the IPv6 part is listed as not evaluated (Q12).
3. Intersect the source ranges with the version's partition (3.2) to get the set of source zones touched, likewise
   destination zones. This is exact interval arithmetic over a sorted, non-overlapping partition: no sampling.
4. For each touched pair (A, B): compare the rule's service selector (and application tokens, 4.3) with the statement.
   `DENY_ALL`: violated (the whole service set is not allowed). `ALLOW_ANY`: compliant. `ALLOW_LISTED`: compliant when
   the rule's service ranges are contained in the union of the listed entries per family (the engine's `contains`);
   otherwise violated, with the difference (rule ranges minus allowed ranges) as the not-allowed part. A rule service
   `any` is never inside a finite list.
5. Combine as in 5.3. Record the touched pairs, the violated cells with their not-allowed part, and a short address
   witness per violated cell (one source range and one destination range inside the pair, masked in masked views).

Determinism: same snapshot, same version, same engine version give the same findings byte for byte (test ZM-9).

### 5.6 Device scope

| Vendor | Covered | Notes |
| --- | --- | --- |
| Check Point (SMS and MDS domains) | yes | Per policy package; the finding lists the package's install targets, narrowed by the rule's install-on when it resolves to enrolled devices (equality only, as the lookup engine does), else all targets with `INSTALL_ON_UNRESOLVED`. Multiple ordered layers: each rule is evaluated on its own (Q10). |
| Palo Alto (Panorama device groups; local firewall policy when no Panorama node exists) | yes | Per device group / firewall; targets from the snapshot metadata. Pre / local / post rulebases. |
| Cisco ASA | no | No policy collection exists. Listed on the screen as "not evaluated: policy not collected". Adding one is its own decision and command gate. |
| FortiGate | no | Same as Cisco ASA. |

Coverage is always shown: devices and policies evaluated, not evaluated (with reason: vendor not covered, no snapshot,
snapshot incomplete), and the time of the snapshot each result is based on.

### 5.7 Incomplete snapshots

Evaluation is per rule and does not depend on other rules, so per-rule outcomes of a snapshot with collection failures
remain valid for the rules that were collected. The policy's coverage is then `INCOMPLETE` ("N layers not collected"):
the policy never shows "no violations", and the Overview row counts it as not fully evaluated.

### 5.8 Finding lifecycle

A finding's identity is (policy source, rule id or uid as stored, violated cell set). The first evaluation that
produces it sets `firstObservedAt`; each later evaluation that reproduces it updates `lastObservedAt`; the first later
evaluation of the same policy (same or later version) that does not reproduce it sets `resolvedAt` and the reason
(rule changed, rule removed or disabled, matrix version changed). Rule ids are compared exactly (identity law).

## 6. BDDK rule set (template)

BDDK's information-systems regulation states principles (for example network segmentation, least privilege, separation
of environments, protection of critical systems); it does not publish a zone matrix. neXus therefore ships a
**template**, not a regulation mapping. neXus does not state which BDDK article requires what.

### 6.1 Template content

Zone roles (the bank maps its own zones to roles inside the product; real zone names never enter the repository):
`INTERNET_UNTRUSTED` (by default the `UNASSIGNED` space), `DMZ`, `USER`, `APPLICATION`, `DATABASE` (critical data),
`MANAGEMENT`, `PARTNER` (third-party connections), `NON_PRODUCTION` (development / test).

Proposed default statements (neXus proposal based on general segmentation practice, **not on a BDDK clause**):

| Source role -> destination role | Default | Severity | BDDK reference | Confirmed by |
| --- | --- | --- | --- | --- |
| INTERNET_UNTRUSTED -> DMZ | ALLOW_LISTED (empty list) | HIGH | *compliance input* | *compliance input* |
| INTERNET_UNTRUSTED -> any internal role except DMZ | DENY_ALL | CRITICAL | *compliance input* | *compliance input* |
| DMZ -> APPLICATION | ALLOW_LISTED (empty list) | HIGH | *compliance input* | *compliance input* |
| DMZ -> DATABASE | DENY_ALL | CRITICAL | *compliance input* | *compliance input* |
| USER -> DATABASE | DENY_ALL | CRITICAL | *compliance input* | *compliance input* |
| USER -> MANAGEMENT | DENY_ALL | HIGH | *compliance input* | *compliance input* |
| USER -> APPLICATION | ALLOW_LISTED (empty list) | MEDIUM | *compliance input* | *compliance input* |
| APPLICATION -> DATABASE | ALLOW_LISTED (empty list) | HIGH | *compliance input* | *compliance input* |
| MANAGEMENT -> any role | ALLOW_LISTED (empty list) | MEDIUM | *compliance input* | *compliance input* |
| any role -> MANAGEMENT except MANAGEMENT | DENY_ALL | HIGH | *compliance input* | *compliance input* |
| PARTNER -> any role except DMZ | DENY_ALL | CRITICAL | *compliance input* | *compliance input* |
| NON_PRODUCTION <-> production roles (both directions) | DENY_ALL | HIGH | *compliance input* | *compliance input* |
| same role -> same role (diagonal) | ALLOW_ANY | LOW | *compliance input* | *compliance input* |
| internal role -> INTERNET_UNTRUSTED | ALLOW_LISTED (empty list) | MEDIUM | *compliance input* | *compliance input* |
| any other pair | DENY_ALL (version default) | MEDIUM | *compliance input* | *compliance input* |

"Empty list" means the bank must list the allowed services; until it does, every allow rule in that cell is a
violation, which is the intended effect of a deny-by-default template.

Per cell, three reference fields: `regulationReference` (free text: the document, its date or version and the
article as the compliance team cites it), `referenceStatus` (`EMPTY`, `PROPOSED`, `CONFIRMED`), `confirmedBy` and
`confirmedAt` (a user holding `zone_matrix_publish`, set when status becomes `CONFIRMED`). The evidence report prints
the reference fields as entered and marks `EMPTY` and `PROPOSED` cells as "not confirmed by compliance". neXus never
fills these fields.

### 6.2 Input needed from the bank (PO / compliance)

1. The exact regulation text the bank maps to: title, date and version of the BDDK regulation and any internal
   standard derived from it.
2. The list of zone roles the bank uses, and whether the eight template roles fit (add, remove, rename).
3. For each template cell: confirm or change the statement, the severity, and the regulation reference.
4. For each `ALLOW_LISTED` cell: the allowed services (and, for Palo Alto, allowed applications by name).
5. The IP blocks of each zone, entered in the product by an authorized user (never sent to engineering).
6. How to treat unassigned address space (Q6), and whether public addresses translated by NAT belong to the zone of
   their real servers (Q2).
7. Whether IPv6 is in scope for the first audit.
8. Who may edit and who may publish the matrix, and whether four-eyes publishing is required (Q7).
9. How long evaluations, findings and reports must be kept (retention, 8.3).
10. The evidence report fields and language the auditors expect (Turkish, English or both), and whether a signed
    report is needed.
11. Approved exceptions that exist today (rules the bank has already accepted as deviations), until an exception
    feature exists (Q9).

## 7. Output

### 7.1 Screen

Menu **Policy and changes > Zone matrix** (name to confirm in the navigation review). Tabs:

- **Matrix**: zones x zones grid of the active version. Each cell shows the statement (shape plus text, not colour
  alone), severity, and the number of open violations; clicking a cell opens the violations of that pair. A version
  selector shows any published version; the draft is shown only to editors, clearly labelled "draft, not evaluated".
- **Violations**: table of findings of the latest evaluation of each policy. Filters: outcome (VIOLATION by default,
  NOT_EVALUABLE, UNKNOWN, COMPLIANT), source zone, destination zone, severity, vendor, policy / package, device,
  flag (`IDENTITY_IGNORED`, `TIME_IGNORED`, `COVERED_BY_EARLIER_DENY`, `NAT_MAY_APPLY`), reason, first observed
  (range), state (open / resolved). Row: rule number, name, id, policy, devices, violated cells, not-allowed part,
  flags, first and last observed, matrix version. Row action: open the rule in the policy viewer; open Trace with a
  witness address pair from the violated cell.
- **Zones**: zones, blocks, effective sizes, overlap warnings, address lookup, "rules touching unassigned space".
- **Versions**: history, diff, publish (four-eyes), audit trail.
- **Coverage** strip on every tab: policies evaluated / incomplete / not evaluated, vendors not covered, snapshot ages.

### 7.2 Overview attention row

One row in the Overview attention list when an active version exists: "Segmentation: N rules violate the zone matrix
(vK) on M devices; C critical" with a link to the Violations tab filtered to open VIOLATION findings. When any covered
policy's latest evaluation is older than its latest completed collection, or a covered policy has no evaluation, the
row adds "UNKNOWN for P policies". No active version: no row. Coordinate the row shape with the Overview attention
work in progress (separate session).

### 7.3 Evidence report

Exported as CSV and PDF (PDF through the Reports area when it exists). Contents:

- Header: product version, generated at (UTC), generated by (user), masked or raw, report hash (sha256 of the content).
- Matrix version: number, hash, published at, editor and publisher, template origin, every cell with statement,
  services, severity and the BDDK reference fields exactly as entered, unconfirmed cells marked.
- Scope: each policy evaluated with vendor, device(s), snapshot collection time, evaluation time, coverage status;
  vendors and devices not evaluated with reasons.
- Summary: counts per outcome, per severity, per cell.
- Violations: one line per finding with rule id, number, name, policy, device(s), violated cell, not-allowed part,
  flags, first observed, last observed, evaluation id.
- NOT_EVALUABLE and UNKNOWN findings with reasons: always included, never dropped from a report.
- Limitations: the fixed text of section 5 (policy intent only, NAT and routing not evaluated, implicit rules not
  collected, IPv4 only in the first delivery, rule-level evaluation).

An export is an audit event (8.4). The report may be filtered (one zone pair, one device); the filter is printed in
the header and counts are printed for the filtered scope and for the whole scope.

## 8. Storage, privacy, authorization

### 8.1 Storage (new tables, one migration, dry-run in BEGIN/ROLLBACK on the live database before rollout)

- `zone_matrix_version`: id, number, state, content (canonical JSONB: zones, blocks, cells, default, template origin),
  content_sha256, created/edited/published by and at.
- `zone_matrix_evaluation`: id, version id, policy source id, snapshot collected_at and size (the row version the
  engine already uses), engine version, started/finished, coverage status, counts per outcome.
- `zone_matrix_finding`: evaluation id, rule id / uid / number / name / section as stored, targets, outcome, reason,
  flags, violated cells with not-allowed part, witness ranges, first/last observed, resolved at and reason.
- No raw device response, no full rule copy, no snapshot copy: only the fields listed (raw-evidence law).

### 8.2 Privacy

- Zone names and blocks describe the bank's network and are treated like device identities: masked for
  replay_viewer (zone names pseudonymised as `ZONE-<n>` by stable order of id; blocks and witness addresses through
  the existing subnet-preserving IP masker; rule and object names through the existing `PolicyPrivacy` projection).
- Logs and job records carry ids, version numbers and counts only, never zone names, blocks, addresses or rule names.
- Masked reports are produced through the same projection; raw reports need `zone_matrix_export`.
- Repository, tests, fixtures and screenshots use synthetic names and documentation ranges only.

### 8.3 Retention (PO / compliance input)

Proposed until the bank states its requirement: matrix versions kept while any stored finding cites them and at least
as long as findings; evaluations and findings kept 400 days after the finding is resolved; summary counts per
evaluation kept for the same period. Reports are not stored server-side (generated on demand from stored findings);
only the export audit event with the report hash is kept.

### 8.4 Authorization summary

See 3.4. Evaluation runs as a system job (no user credential, no device access). Every export, publish and on-demand
evaluation is attributed to the requesting user in the audit log. aiview may read masked results and may not edit,
publish or export raw.

## 9. Performance budget

Measured before release on the 10 largest stored policies (counts only, in the pod; the largest has about 16,500
rules):

- Evaluation of the largest policy against a 500-zone version: at most 30 seconds and 512 MB additional heap; all 10
  within 3 minutes in sequence. Per-rule work budget as in the engine; a rule that exceeds it is UNKNOWN "analysis
  budget", never dropped.
- Evaluation runs off the request path, at most one at a time per policy and two in total, under the existing policy
  bulkhead; where it runs (service or worker) is Q13.
- Screens read stored findings only: Matrix and Violations first page within 2 seconds at p95 with 50,000 findings;
  Overview row from a stored count within 200 ms.

## 10. Delivery order and acceptance criteria

### 10.1 Delivery order (small steps; each its own PR with targeted tests)

1. Zone model, versions, publish, audit, RBAC, Zones tab with address lookup. No evaluation.
2. Matrix statements and service vocabulary; BDDK template import with empty reference fields; Matrix tab (Fable).
3. Evaluation engine as a pure function (IPv4, TCP/UDP, legacy model, outcomes and reasons) with synthetic fixtures,
   plus a counts-only measurement on the 10 largest policies attached to the PR.
4. Storage, triggers (collection completed, version published, on demand), finding lifecycle, Violations tab.
5. Overview attention row.
6. Evidence report (CSV, then PDF).
7. Extensions as their prerequisites land: Palo Alto applications by name, ICMP / IP protocol (hygiene R1, R4),
   negation (R5), NAT annotation (Q2), IPv6, exceptions (Q9).

### 10.2 Acceptance criteria

- `ZM-1` A version's partition puts every address in exactly one zone or `UNASSIGNED`; duplicate blocks across zones
  are refused; nesting follows the longest prefix (property test over random synthetic blocks).
- `ZM-2` Published versions are immutable; edits go to the draft; publish by the last editor is refused.
- `ZM-3` Every evaluation and finding cites a version id, number and hash; the hash recomputes from stored content.
- `ZM-4` A rule is COMPLIANT only when every touched pair allows every permitted service; any undecided part gives
  NOT_EVALUABLE or UNKNOWN with a reason from the fixed list (unless a decided part proves VIOLATION).
- `ZM-5` A rule with service `any` in an `ALLOW_LISTED` pair is a VIOLATION; a rule in a `DENY_ALL` pair is a
  VIOLATION whatever its service.
- `ZM-6` Disabled rules, deny-type rules and NAT rules never produce a VIOLATION and are counted separately.
- `ZM-7` A snapshot with collection failures gives coverage `INCOMPLETE`; the policy never shows "no violations".
- `ZM-8` Evaluation runs on completed collections only, never on a checkpoint.
- `ZM-9` Same snapshot, version and engine version give identical findings.
- `ZM-10` aiview sees masked zone names, blocks, addresses and rule names; logs contain none of them (log masking
  test with planted names).
- `ZM-11` The evidence report contains the version hash, every NOT_EVALUABLE and UNKNOWN finding, the limitations
  text, and the BDDK reference fields exactly as entered; unconfirmed cells are marked.
- `ZM-12` The performance budget of section 9 holds on the 10 largest stored policies (measurement attached).
- `ZM-13` No code path of this feature issues a device command or writes to a device (architecture test).

## 11. Open questions for the decision council

- **Q1 Rule-level or flow-level evaluation.** Proposed: rule-level (each allow rule on its own match), as the
  sector products report "violating rules", and because it does not depend on resolving every earlier rule. Flow-level
  (what the whole rulebase effectively permits per pair) is more exact but inherits every UNKNOWN of earlier rules
  (hygiene measurement: 12.7 % decided today in the sound mode). Option: flow-level as a later "effective exposure"
  view per pair using the Trace engine.
- **Q2 NAT.** Palo Alto security rules match pre-NAT addresses (A0 section 4a, HIGH); Check Point access on the
  pre-NAT tuple is UNKNOWN (A0 1b). A destination-NAT rule for a public address may look like `UNASSIGNED ->
  UNASSIGNED` while the real server is in `Z-DMZ`. Proposed for the first delivery: evaluate addresses as written in
  the rule; ask the bank to put translated public addresses into the zone of their real servers (zone membership is
  by blocks, so this is a data decision, not code); later, flag `NAT_MAY_APPLY` when a rule's destination intersects
  the original destination of a collected NAT rule. No NAT translation in the evaluation until Check Point semantics
  are documented.
- **Q3 Rules shadowed by an earlier deny.** A rule fully covered by an earlier deny (hygiene verdict CONFLICT) has no
  effect. Proposed: keep it a VIOLATION with flag `COVERED_BY_EARLIER_DENY` (it is still a latent permission that a
  reorder or a deleted deny activates), filterable and excluded from the critical count. Alternative: not
  applicable. Partially covered rules: no change.
- **Q4 Identity- and time-limited rules.** VIOLATION with `IDENTITY_IGNORED` / `TIME_IGNORED` (proposed, 5.4) or
  NOT_EVALUABLE.
- **Q5 Overlapping blocks.** Longest prefix wins with duplicates refused (proposed), or refuse every overlap.
- **Q6 Unassigned space.** A pseudo-zone with its own statements (proposed), or must every address used by a rule be
  assigned before a version can be published.
- **Q7 Edit and publish rights.** Roles of 3.4 and four-eyes publishing (proposed), or a single role.
- **Q8 Rule detail for compliance users.** compliance_admin and viewer without `policy_read` see findings without
  object names and addresses (proposed), or grant them a masked or full policy read for this screen.
- **Q9 Exceptions.** Accepted deviations (rule, cell, reason, approver, expiry) before the "smart" ticket feature
  exists, or none in the first delivery (proposed: none; the bank's list of accepted deviations, input 11, is kept
  outside until a contract amendment adds them).
- **Q10 Check Point multiple ordered layers.** A packet must be accepted by every ordered layer; rule-level
  evaluation reports an allow rule in one layer even if another layer drops the traffic. Proposed: evaluate per rule
  and print the layer; a flow-level view (Q1) resolves it later.
- **Q11 Implicit and default rules.** Palo Alto `intrazone-default` allows traffic within a firewall zone by default
  and its override is not collected; Check Point implied rules are not collected. Proposed: out of scope, stated as a
  limitation; collecting the overrides is its own decision.
- **Q12 IPv6.** Blocks stored from day one, evaluation IPv4 only with "IPv6 not evaluated" (proposed), or IPv6 out of
  the model until the engine resolves it.
- **Q13 Where the evaluation runs.** In ui2-service next to the engine under the policy bulkhead (proposed), or as a
  worker job type with the engine moved to a shared module.
- **Q14 Headline count.** Count open VIOLATION findings per rule (proposed) or per (rule, cell).
