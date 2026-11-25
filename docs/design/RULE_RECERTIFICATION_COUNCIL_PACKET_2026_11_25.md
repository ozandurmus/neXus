# Rule recertification -- decision-council packet: the ten durable questions of contract section 14.3 (2026-11-25)

**Status: DRAFT -- COUNCIL INPUT ONLY. Not implementation authority.** Prepared 2026-11-25. No code, database migration, gate row, device
command or notification change is authorized by this packet. [RULE_RECERTIFICATION_CONTRACT.md](RULE_RECERTIFICATION_CONTRACT.md)
stays DRAFT (revision 2.1) and is left untouched: this packet neither edits nor freezes it. It prepares the
three-seat decision council that contract names, and the PO session that freezes the contract afterwards.

The PO decided to convene the council "once lane 380 is on main"
(`PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` section 1 item 4; backlog `recertification_council` and
`rule_recertification_campaigns`). Contract revision 2.1 (lane 380) is on main. Everything below was read on this
branch at `b457e7bb` on 2026-11-25; file and line references are to that tree.

Who runs it: the council is convened from a `nexus-po` episode (`GOV_PO_ROLE_MIGRATION.md` section 7, `CLAUDE.md`); an
engineering session never invokes it, and this packet is the whole of this lane's contribution. Examples use masked
synthetic names only (`FW-TANGO-04`, `192.0.2.x`, invented team names).

## 1. Scope: what the council may and may not decide

### 1.1 Already decided -- the council does not reopen these

| Question | Answer | Kind (`PO_DECISION_RECORD_2026_11_14_RULE_RECERTIFICATION_PRODUCT_ANSWERS.md`) |
| --- | --- | --- |
| Q1 cadence and risk tiers | Yearly for all rules, every 6 months for high-risk rules; a KEEP lasts at most 12 months (6 on high-risk) | PO decision |
| Q4 unused threshold | 180 days, on a known last hit only | PO decision |
| Q13 bulk KEEP | At most 50 per operation, each item its own evidence record, high-risk rules excluded | PO decision |
| Q5, Q10, Q11, Q12, Q15, Q17 | Legacy "possible" list that never enrols; disabled-instead-of-removed stays open; late items; one extension without four-eyes; one team mailbox per campaign; masked report by default | PO session recommendations the PO saw and did not object to |

The council may **test** these for an abuse or an inconsistency (section 3 lists prompts that do), and report one, but it
does not re-decide them. A finding against one of them goes to the PO session as a contradiction report, not as a
verdict.

### 1.2 Bank inputs -- not council questions, and this packet cannot settle them

- **Q6** where the bank records rule owners today (rule comments, a CMDB export) and in what form.
- **Q16** retention of decision evidence, and the BDDK / internal-audit reference for the review cycle.
- Two further inputs this packet found it needs (they are not in contract section 14.2): the **bank's internal address
  ranges beyond RFC 1918 / RFC 4193** (Q20) and the **format of the bank's change reference**, if it has one (Q19).
  Neither has a value in the repository, and neither is filled from general knowledge here.

### 1.3 The ten council questions

Q2, Q3, Q7, Q8, Q9, Q14, Q18, Q19, Q20, Q21 -- sections 4.1 to 4.10 below, in contract order. Each section gives the
question, what it touches, options (the engineering recommendation first, marked as a recommendation), what evidence is
missing, and what each option does to the delivery order of contract section 12.

## 2. Verified facts (read on this branch; the council may rely on these and not on the contract's description of them)

| # | Fact | Where |
| --- | --- | --- |
| F1 | The hygiene classes are `shadowed`, `conflict`, `disabled`, `unused`, `expired`, `broad`, `unknown`. `analyze(snapshot, days, now, workLimit, ...)` takes the unused threshold as `days`; the Policy screen path calls it through `PolicyQueryService.compute` and the zone matrix bridge calls it with 90. A recertification job can therefore pass 180 without changing hygiene. | `ui2/service/.../service/policy/PolicyHygiene.java:26`, `:90-100`; `PolicyQueryService.java:739-741`; `ZoneMatrixPolicyBridge.java:264` |
| F2 | `unused` is emitted for two different reasons: "Last hit predates the threshold at counter collection time." (known last hit; the contract's enrolling case) and "Zero collected hits; counter reset/window is unknown." (same class `unused`, must not enrol, contract 3.2 / RC-18). A third `unused` source is not hit-based: an ASA access list with no binding and no other use, evidence `ASA_ACL_NOT_REFERENCED`. | `PolicyHygiene.java:759-784`, `:47`, `:174-176` |
| F3 | Hit counters are member-specific: the class documents that "a finding carrying one of these strings is member-specific runtime evidence, never proof about an HA pair". For Palo Alto the per-rule total is built only when every target answered (`complete`), and is then the sum over members. | `PolicyHygiene.java:60-68`; `ui2/worker/.../worker/policy/PolicyHitCounts.java` (`aggregate`) |
| F4 | Legacy-mode hygiene exists (`analyzeLegacy`, `PolicyLegacyView`) and is called by tests only, as the contract says. | `PolicyHygiene.java:130-132`; grep of `ui2/*/src/main`: no caller |
| F5 | Hit counters are produced only by the Check Point and Palo Alto collection code (`CheckPointPolicyMapper`, `CpDomainReuse`, `PanoramaPolicyCollector`, all through `PolicyHitCounts`). Neither `AsaPolicyMapper` nor `FortiGatePolicyMapper` constructs or attaches a `HitCounts`. | grep of `new HitCounts(` / `withHitCounts` in `ui2/*/src/main`: only `PolicyHitCounts.java:60,103` and `PolicySnapshot.java` |
| F6 | **ASA rule key is positional.** Within one access-group section the rule id is `ref(snapshotId, sectionKey, aclName, number)` with `number = i + 1` (list position), the rule name is `aclName:number`, and `uuid` is passed as `null`. `snapshotId` is `ref("asa", deviceId, "single")`. An inserted or removed ACE therefore changes the id and name of every later ACE of that ACL. | `ui2/platform-core/.../policy/asa/AsaPolicyMapper.java:45`, `:275-277`, `:321-323` |
| F7 | **FortiGate keeps `policyid`.** The rule id is `ref(deviceId, vdomName, policyId)`, with the position appended only when a `policyid` repeats inside one VDOM; the snapshot id is `ref("fgt", deviceId, vdomName)`; `uuid` is the printed `set uuid` when present. Moving a policy does not change its id. Whether a deleted `policyid` can be reused by a different policy is vendor behaviour this repository does not establish (UNKNOWN). | `.../policy/fortigate/FortiGatePolicyMapper.java:86`, `:204-213`, `:451-453` |
| F8 | ASA and FortiGate snapshots are not stored in `policy_snapshot`. `LocalFirewallPolicyService` derives them on read from the latest stored configuration artefact, in memory, with a version-keyed cache, and discards the text. `policy_rule_history` references `policy_snapshot(policy_id)`, and the zone matrix run service reads completed `policy_snapshot` writes only and evaluates `CP` and `PAN`; ASA / FortiGate entries get the fixed reason "not evaluated: policy not collected". | `ui2/service/.../policy/LocalFirewallPolicyService.java:11-17`, `:85-115`; `V120__policy_rule_history.sql:1-5`; `ZoneMatrixRunService.java:77-82`, `:99` |
| F9 | An ASA snapshot also holds entries that are not access rules: one "not-evaluated" pseudo-rule per access list that has no transit binding (`enabled = null`, action `UNKNOWN`, empty cells, id and position taken from the list order), an "ASA security-level default" section (no rules), and NAT sections. A FortiGate snapshot holds implicit rules (implicit deny, intrazone allow), VIP and central-SNAT rules, and "not evaluated" policies. The snapshot model classifies the ASA and FortiGate NAT, implicit and not-evaluated sections as outside security evaluation, and hygiene skips them. | `AsaPolicyMapper.java:149-155`, `:172-250`; `FortiGatePolicyMapper.java:496-533`, `:554-690`; `PolicySnapshot.java:73-87`; `PolicyHygiene.java:168-184` |
| F10 | `Rule` carries `id`, `uuid`, `number`, `name`, `enabled`, four cells (each `refs` plus a `negated` flag), `action`, `log`, `comment`, `extras`, `hitCounts`. | `PolicySnapshot.java:170-173`, `:202` |
| F11 | The existing rule history is a different change notion from the contract's `ruleHash`: the history key is `uuid:<uid>` or `name:[source, section name, rule name]`; it diffs every rule field except `id`, `uuid`, `last-modified` / `last-modifier` (so name, comment and logging count), compares object **names** (not object contents), skips a key that occurs more than once, and since V150 ignores a change of position number alone while still recording a real reorder. | `V120__policy_rule_history.sql:26-52`, `:62-69`; `V150__policy_history_initial_capture_and_renumbering.sql:1-12` |
| F12 | Zone matrix four-eyes: `ZoneMatrixService.fourEyes` refuses `SELF_APPROVAL` when the actor is among the draft's participants (canonical form) and `COMPLIANCE_ROLE_MISSING` when neither the publisher nor a participant holds compliance_admin; root and replay_viewer get `PRINCIPAL_NOT_ELIGIBLE`; the request carries the revision and content hash (`REQUEST_CHANGED`). Its canonical form is a **local copy**: `strip().toLowerCase(Locale.ROOT)`. | `ZoneMatrixService.java:488-493`, `:533-541`, `:569`, `:572` |
| F13 | The failover `PrincipalCanonicalization` is a **package-private** class (`final class`, `ui2/service/.../service/failover/`), and differs from F12's copy: it rejects empty, control-character and trailing-whitespace principals before `trim().toLowerCase(Locale.ROOT)`. Both fold case. | `PrincipalCanonicalization.java:14`, `:20-34` |
| F14 | `ActionRegistry` models an action as `ActionDescriptor(actionId, consoleSubmittable, requiredRoleToken, alternativeRoleTokens)`: a role-token gate. It cannot express a per-item check such as "acts for this item's owner". The role tokens are a closed list: viewer, operator, onboarding_admin, backup_admin, compliance_admin, security_admin, operation_admin, replay_viewer. | `ActionRegistry.java:396-406`; `ui2/platform-core/.../platform/RoleToken.java:4-11` |
| F15 | Directory authorization: `RbacEvaluator` evaluates a role token against active bindings and the actor's stored group set; a stale set is refreshed once through the service account, else the outcome is `AUTHZ_NOT_EVALUATED` (fail closed). `revalidateDirectoryActor` / `revalidateForStart` force a fresh read (the failover start uses it). Freshness is the stored `validUntil`; the five-minute figure is documented in the design and in the `revalidateForStart` javadoc, not a constant found in the evaluator. The refresh is built from the **role-binding** repository. | `RbacEvaluator.java:205-235`, `:152-165`; `ActorAuthzStateRecord.java:32`; `DirectoryAuthorizationRefresh.java:20-60`; `LDAP_SERVICE_BIND_DESIGN_2026_11_11.md` |
| F16 | Append-only precedent: `fn_zone_matrix_append_only` raises on update/delete, `GRANT SELECT, INSERT` only, and an audit trigger writes an allowlisted `after_state` (ids, kind, hash, a "rationale given" boolean) into `audit_log`. | `V216__zone_matrix_versions.sql:78-105`, `:132-133` |
| F17 | Hash-chain precedent: `failover_schedule_ledger` carries `prev_hash` / `entry_hash`; a before-insert trigger takes a global advisory lock and refuses an entry whose index or `prev_hash` does not extend the tip; update, delete and truncate are refused by a statement trigger. | `V31__failover_engine_durable_schema.sql:68-75`; `V135__failover_schedule_transaction_integrity.sql:20-48` |
| F18 | The zone matrix evaluator's private-range list (`RFC1918_RANGES`) is IPv4 only (the three RFC 1918 blocks: one /8, one /12 and one /16); it is used to report unassigned private space, and IPv6 ULA space is handled separately for that gap report only. A rule whose address cells contain IPv6 gets the undecided reason "IPv6 not evaluated" (last in the reason precedence); `any` is never COMPLIANT. The hygiene `broad` finding knows "any" and "Large CIDR" (IPv4 prefix 16 or shorter) but has no notion of an address being outside an internal set. | `ZoneMatrixEvaluator.java:39`, `:50-54`, `:711`, `:767`, `:1098-1134`; `PolicyRuleMetrics.java:106`, `:187` |
| F19 | Zone matrix delivery steps 4b-1 and 4b-2a are on main: durable runs and findings (V267), the lifecycle wired into `ZoneMatrixRunService`, staleness and coverage projections, publish and completed-collection triggers. Finding identity is exact string equality of (policy source, rule uid, parent path, pair set). | `V267__zone_matrix_runs_and_findings.sql:1-30`; `ZoneMatrixRunService.java:55-85`; `ZoneMatrixFindingLifecycle.java:1-80` |
| F20 | No recertification code, table, action or role exists on main (grep of `ui2/*/src/main` and the migrations for `recert`: no match). The latest migration on this branch is V272. | -- |

## 3. Corrections to the contract text (not edited here; the PO session carries them into revision 3 or the freeze)

1. **Zone matrix status (header, "Builds on", sections 3.2, 3.3, 6.6, 12 step 9).** Revision 2.1 says zone matrix
   delivery step 4 is not built and that findings, runs and staleness are future. F19: steps 4b-1 and 4b-2a are on main.
   The conditions the contract puts on "after zone matrix delivery step 4" read as met in part; whether the whole step 4
   is done is not established by this packet (step 4b-2 is in progress in the backlog queue).
2. **Stored ASA and FortiGate snapshots (section 5.2, Q21).** The text calls them "stored policy snapshots". F8: they are
   derived on read from the stored configuration artefact and are not rows of `policy_snapshot`; there is no
   `policy_id` for them, no `policy_rule_history` capture, no "completed policy collection" event and no zone matrix
   run. The contract's UNKNOWN about history and change actor is resolved for history (not captured) by the foreign
   key and by `LocalFirewallPolicyService`; the change actor for these vendors is not established.
3. **ASA `uuid` (Q21).** The contract says `uuid` is "empty"; the mapper passes `null` (F6). The history key function
   treats both the same way.
4. **ASA and FortiGate non-access rules (sections 3.2, 7).** Section 7 puts NAT and non-security rules out of scope. F9
   adds kinds the text does not name: the ASA "not-evaluated" pseudo-rules per access list, and the FortiGate implicit
   rules and "not evaluated" policies. If the council ever puts these vendors in scope they must be listed as "not in
   scope", never enrolled.
5. **ASA `unused` without a hit count (section 3.2).** F2: hygiene reports `unused` with the evidence "ACL has no
   access-group binding and no other use was found; it is not enforced as an access rule." for ASA. Section 3.2 lists
   only the hit-based `unused` evidence as enrolling and the hit-based strings as "could not be evaluated"; this third
   string and `ASA_ACL_USE_UNKNOWN` are in neither list. Both are ASA-only, so the omission matters only if Q21 brings
   ASA into scope.
6. **`PrincipalCanonicalization` (header, 8.2, RC-7).** The contract names it as the canonicalization to reuse. F13: it is
   package-private to the failover package, and the zone matrix, whose pattern the contract also cites, uses a different
   local function (F12). There is no shared, accessible canonicalization today; section 4.2 (Q3) asks which one the
   recertification package uses.
7. **"300-second group-set validity" (4.1, Q2).** The recertification text and the failover revalidation use "300 s"; the
   evaluator reads a stored `validUntil` (F15). The figure is a design value, not a constant the recertification code can
   rely on.
8. **Rule history and `ruleHash` (5.2, 5.3, 6.2).** The contract links an invalidation to "the `policy_rule_history`
   revision id when one exists". F11: history's notion of change is wider (name, comment, logging) and narrower (object
   names, not contents) than `ruleHash` / `objectsHash`, so a history revision and a hash change are not the same event;
   the link must be described as "the nearest revision of the same rule key", not as the cause.
9. **Hit counters on HA pairs (3.2).** F3: the hygiene `unused` evidence is member-specific, and for Palo Alto the total
   exists only when every target answered. Section 3.2 does not say what an `unused` finding means for an HA pair;
   that is a gap, not a contradiction.
10. **Bulk atomicity (6.5, RC-17).** The text refuses an oversized or high-risk selection "as a whole" but does not say
    what happens when one item of an otherwise valid selection has changed version (`RULE_CHANGED_WHILE_REVIEWING`):
    refuse the whole operation, or record the others. A gap for the seat charters (section 5), not a council question.
11. **Question count (header).** The header says "eight open questions of section 14.3, the new Q20 and the new Q21": ten
    in all (Q2, Q3, Q7, Q8, Q9, Q14, Q18, Q19, Q20, Q21). No action; recorded so the table of section 6 is read against
    ten rows.

## 4. The ten questions

Each question lists the clauses and code it touches, the options (the **engineering recommendation is Option A** in every
case and is a recommendation, not a decision), what is missing, and the consequence on the delivery order of contract
section 12 (steps 1 rule version function; 2 owners; 3 periodic campaign and decisions; 4 invalidation and verification;
5 four-eyes and closure; 6 cleanup campaigns; 7 reminders; 8 reports; 9 zone matrix violations).

### 4.1 Q2 -- identity and authorization of rule owners who hold no neXus role

**Question.** How does an application owner with no role get a session that can decide only their own items, and how does a
`DIRECTORY_GROUP` owner prove membership at decision time?

**Touches.** Contract 4.1, 4.3, 8.1 (`recert_decide`), RC-8. Code: `ActionRegistry` (F14), `RbacEvaluator` and
`DirectoryAuthorizationRefresh` (F15), `RoleBindingAdminService` (group references are encrypted and compared exactly).

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation)** Narrow role token plus a per-item owner check, staged by owner kind | One new role token (for example `rule_owner`) bound through the existing role-binding mechanism, giving only "My reviews" and the per-item decide call. The per-item check "acts for this item's owner" lives in the recertification service, not in `ActionRegistry` (F14). `PERSON` and `TEAM` owners from step 3; `DIRECTORY_GROUP` owners only after the group-testability evidence below exists. A directory decision forces a fresh group read first (the failover start precedent, F15) and is refused when the directory is unreachable or membership is lost. | Evidence names the person who decided; reuses the role and binding machinery; fail-closed; the hard case (groups) is isolated | Two increments; a new role token touches the closed list of F14 and the role-binding administration |
| B All three owner kinds at step 3 | Same role, `DIRECTORY_GROUP` included from the start | One increment | Rests on an unproven assumption (below); if the resolved group set cannot contain a group no role binding names, group owners cannot decide at all |
| C No new role; admin roles decide on the owner's behalf | `security_admin` / `compliance_admin` record the decision after the owner agrees elsewhere | No identity work | The audit says "an administrator says the owner agreed": the evidence the product exists to produce is lost; the owner never authenticates |

**Missing evidence (UNKNOWN, not filled).**
- Whether a directory principal's resolved group set can contain a group that no role binding names. F15: the refresh is
  built from the role-binding repository; this read did not establish that arbitrary groups are tested. Decides A versus B.
- Whether directory principals have an immutable identifier usable as the canonical principal id
  (`ZONE_MATRIX_CONTRACT.md` 11.2 lists it as open; correction 6 / Q3).
- Whether a disabled or expired directory account is caught at revalidation: `ARGON_LDAP_SERVICE_BIND_REVIEW_2026_11_11.md`
  raises it; whether it was resolved on main is not established here.
- The bank's directory structure for application owners (bank input).

**Delivery order.** A: step 2 delivers owners and assignment for `PERSON` / `TEAM`; step 3 delivers "My reviews" on the new
role; the group kind is a later increment before any `DIRECTORY_GROUP` assignment is accepted. B: steps 2-3 carry the whole
owner model, blocked on the evidence above. C: step 3 loses the owner-side evidence and 8.1 `recert_decide` changes.

### 4.2 Q3 -- four-eyes scope

**Question.** Which decisions need a second person (8.2), may a teammate approve, must one of the pair hold compliance_admin
(as zone matrix publishing requires), and does closing a `PERIODIC` campaign need a second person?

**Touches.** Contract 8.2, 8.3, RC-7, RC-21, 6.1 (extensions). Code: the zone matrix pattern (F12), the failover
canonicalization (F13), `ActionRegistry` (F14).

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation)** 8.2 as drafted; approver holds `recert_approve`; no teammate approval; compliance_admin not required in the pair; `PERIODIC` closure by a second person | The approver is a different canonical principal who did not act for the same owner on the same item and holds `recert_approve` (security_admin or compliance_admin). Root and replay_viewer never decide or approve. One shared canonicalization function, chosen once (correction 6). | The decider is often an application owner with no admin role, so a compliance_admin-in-the-pair rule would make the approver the only role-bearing party every time; keeps the approval pool as large as the existing admin roles | The approval pool is two roles; a bank with one administrator deadlocks every `PENDING_APPROVAL` (fail-closed, but the rule stays uncertified) |
| B Zone matrix strictness: the approver must hold compliance_admin | One of the pair compliance_admin, as F12 | Same bar as segmentation-policy publishing; fewer people can approve | Smaller pool; zone matrix 11.2 already lists "no bound compliance_admin" as an open risk; the bar is for a policy change, here it is for a confirmation of need |
| C A teammate of the same owner may approve | Approval inside the owning team | Larger pool, owner-side knowledge | A team approving its own decision defeats the point of a second person; RC-7 and 8.2 exclude it |

Sub-decisions the council states explicitly: (a) the exact set of decisions that need approval (8.2's four triggers, and
whether a KEEP on a `shadowed` or `expired` cleanup item needs none); (b) whether a decision refused for
`PRINCIPAL_NOT_ELIGIBLE` can be re-routed; (c) the single canonicalization (case folding of principals is itself an identity
question: AGENTS.md allows normalization only when the semantics are proven, and for a directory principal they are not,
see Q2).

**Missing evidence.** How many principals hold the approving roles at the bank (zone matrix 11.2: "single administrator or
no bound compliance_admin"); whether the canonical principal id for a directory actor is case-insensitive in the directory
(UNKNOWN).

**Delivery order.** Step 3 records decisions that will need approval as `PENDING_APPROVAL` and uncertified; the wider the
set in (a), the fewer rules read `CERTIFIED` until step 5. A: step 5 implements the pool above. B: step 5 plus a role
precondition check at launch. C is excluded by RC-7 as written and would need a contract change. The canonicalization
choice must land before step 3 (the stored principal id format).

### 4.3 Q7 -- owner liveness for directory principals

**Question.** neXus cannot see that a person left. Is "not signed in for N days" an acceptable signal, and what happens to
their open items?

**Touches.** Contract 4.1 (`OWNER_NOT_SEEN`), 4.3 (fallback queue), 9. Code: `ActorAuthzStateRecord` and sessions (F15).

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation)** Flag only, N chosen per campaign, never automatic reassignment | `OWNER_NOT_SEEN` shown on the owner and on every open item of that owner, counted separately in every headline; the campaign manager decides whether to reassign. | Fail-visible, no silent move of an item to someone who did not agree; consistent with "never retired automatically" | N is a guess; an owner on long leave is flagged |
| B After N days the open items also appear in the fallback queue | Items stay assigned and are also workable by the campaign manager's team | Items cannot stall behind an absent owner | A fallback decision is `DECIDED_WITHOUT_OWNER` and needs four-eyes (8.2); more volume there |
| C No signal; offboarding must retire owners by hand | The bank's leaver process retires owners | Nothing to get wrong in the product | Nothing tells anyone it did not happen; a departed owner's items sit `OVERDUE` |

**Missing evidence.** Whether a per-principal last-sign-in time is stored at all: a search of the migrations and persistence
main found no such column (the device `last_seen_at` columns are unrelated); sessions and `actor_authz_state` exist, and how
long they are retained is UNKNOWN. Whether revalidation catches a disabled directory account (Q2). The value of N is a
product choice with no evidence behind it.

**Delivery order.** A and B: the signal is part of step 2 (owner state) and needs a source of last sign-in, which may be a
small new column or a read of existing session rows -- a migration question for step 2. C: no cost, but the "unknown
owner" count of 4.3 then understates leavers.

### 4.4 Q8 -- shared rules under several policy sources

**Question.** One decision per policy source (draft), or one decision for every occurrence of the same rule uid?

**Touches.** Contract 5.1, 6.2, 6.5. Code: finding identity of the zone matrix (F19: exact equality, policy source keeps
shared rules apart); history key (F11: `uuid:<uid>` repeated across sources, and a key occurring more than once inside one
snapshot is skipped, not guessed).

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation)** One decision per (policy source, rule key, parent path), as drafted | Each occurrence is its own item and decision; the screen shows "same rule uid in N other policy sources" as a hint only | Matches the zone matrix identity; each occurrence is read against its own objects and targets; no new decision shape | The same rule may need several decisions (workload); bulk KEEP (Q13) mitigates it for non-high-risk rules |
| B One decision per uid, covering every occurrence | A KEEP applies to all policy sources that carry the uid | Least work | A shared rule resolves objects and targets per source (`objectsHash` can differ per source); one answer would cover contents nobody compared |
| C One decision per uid, valid only while every occurrence has the same hash pair | B, bound to the set of (`ruleHash`, `objectsHash`) pairs | Saves work while rules stay identical | Needs a decision shape that binds a set of rule versions; any one source changing invalidates all; unproven that real shared rules keep identical hashes |

**Missing evidence.** How many rules occur under several policy sources, and how often their hash pairs would agree:
unmeasured (step 1's counts-only measurement could take it). Whether a Panorama shared rule's objects resolve identically
per device group: not established here.

**Delivery order.** A: nothing to change in steps 1-3. B and C: `recert_decision` (6.2) binds a uid and a set of rule
versions instead of one version, and the verification of step 4 works per set; the schema must be decided before step 3's
migration.

### 4.5 Q9 -- which fields and objects invalidate a decision

**Question.** Which rule fields invalidate a KEEP (name, comment, logging, move to another section), and should `objectsHash`
change on any reachable object change or only on changes that widen the resolved match?

**Touches.** Contract 5.2, 5.3, RC-2, RC-3; F10 and F11; the decided Q1/Q4/Q13 are untouched. `hashVersion` makes the choice
durable: stored hashes keep their version and are never recomputed in place (5.2).

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation)** Contract 5.2 field list; name, comment and logging recorded as informational events, not invalidations; `objectsHash` on any reachable-object change | `ruleHash` over the security-relevant fields and the section/layer; a change of name, comment or log writes a `NON_SEMANTIC_CHANGE` event and leaves the KEEP valid; position-only renumbering is not a change (V150 precedent, F11); `objectsHash` is deterministic and never needs an analysis | Deterministic and testable (RC-2); an object that grew always invalidates; no dependence on the shadow/match engine | Object churn invalidates KEEPs even when the match did not widen; a pure reorder inside a section is not caught by the field list (see C) |
| B History-equal: every field except id, uuid, last-modified invalidates, including name, comment, logging | Same notion as F11 | One notion of "changed" across the product | A renamed or re-commented rule needs a new review: review fatigue, which is the rubber-stamping abuse of 6.5 |
| C A with relative order inside a section also invalidating | Adds the notion V150 keeps (a real reorder is still recorded) | A reorder can change which rule shadows which; a KEEP made on a different effective behaviour is not an answer to the new behaviour | Needs a stable "relative order among the rules present in both" definition across vendors; positional ASA keys make it vendor-specific |
| D `objectsHash` only when the change widens the resolved match | Needs resolution and an upper-bound match to say "widened" | Fewer invalidations | Fails to `UNKNOWN` more often (unresolved, dynamic or empty groups: `PolicyHygiene.REASONS`); a weaker guarantee for a clever change; the contract already said so |

**Missing evidence.** The invalidation rate that A would cause on real policies (how often a reachable object changes inside a
12-month KEEP): unmeasured, and the one number that would let the council trade A against D. The per-vendor field lists are
"frozen with this contract" (5.2) and do not exist yet; this packet does not invent them. Whether dynamic and empty groups
(reasons "empty or dynamic group", "dynamic or unsupported object type") must force `objectsHash` `UNKNOWN`.

**Delivery order.** Step 1 (the pure function) cannot start before the field list and the `objectsHash` scope are settled,
and a later change is a `hashVersion` bump with two live versions. Recommended: the council fixes the rule fields now (A or
C) and the `objectsHash` scope as "any change" with the step 1 measurement as an explicit gate it may revisit before step 3
stores anything. B changes the field list of step 1 and the `NON_SEMANTIC_CHANGE` event disappears. D delays step 1 on an
analysis dependency. Q21 option 1 waits for this answer (contract 14.3).

### 4.6 Q14 -- is a KEEP on a zone matrix violation the zone matrix exception record?

**Question.** One record (a recertification KEEP is the zone matrix exception, 6.6) or two separate records?

**Touches.** Contract 3.2 (last row), 6.6, 12 step 9; `ZONE_MATRIX_CONTRACT.md` 8.6 (exception: policy source, rule uid, cell,
hash of the resolved match, reason, four-eyes, mandatory expiry, version range, cancelled by any change; the finding stays a
`VIOLATION` flagged `EXCEPTED`, never COMPLIANT) and 3.5 (loosening needs a rationale). Zone matrix is FROZEN, and 8.6 says the
exception record needs its own contract change. F19: runs and findings exist on main.

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation)** Two records; a recertification KEEP never changes a finding's status | Recertification may show a zone violation as an item and record "owner confirmed this rule is needed"; the finding stays `VIOLATION`, not `EXCEPTED`. A zone matrix exception record is built under its own contract change (zone matrix step 7). | Different grain (an exception is per cell and per matrix version; a KEEP is per rule and per rule version); different meaning (KEEP "needed", contract 1; `EXCEPTED` "deviation accepted"); no amendment of a FROZEN contract on the recertification critical path; step 9 is last | Two approval flows to build and two to explain to an auditor |
| B One record, as the contract proposes | A KEEP on a violation item carries the finding id(s) and matrix version hash, is four-eyes, expires at the next review date, and invalidates on any rule, object or matrix-version change; the finding becomes `EXCEPTED` | One workflow, one report line | Needs a zone matrix amendment; a rule with several violated cells would be excused by one KEEP unless the decision binds each cell; "needed" and "accepted deviation" collapse into one record, against the wording rule of contract 1 |
| C Hybrid: the KEEP references a zone matrix exception record | The exception stays the zone matrix's; the KEEP cites its id | Separation kept, one screen | Two records anyway; the exception record must exist first |

**Missing evidence.** How many open `VIOLATION` findings real policies produce and how they distribute over cells (zone matrix
11.2 lists this as unmeasured); whether the bank's auditors accept two records or insist on one (bank/compliance input).

**Delivery order.** Step 9 only. A: step 9 reduces to showing recertification state next to a zone finding and (optionally)
enrolling violations as cleanup items without altering the finding; no zone matrix change. B: step 9 requires the zone
matrix amendment, and step 3's decision evidence must already allow finding ids and a matrix version hash (6.2 lists them, so
no schema change). C: step 9 waits for the zone matrix exception build.

### 4.7 Q18 -- evidence integrity of decision rows

**Question.** Is an append-only table plus the audit trigger enough for the auditor, or should decision rows be hash-chained so
a database administrator's edit is detectable?

**Touches.** Contract 6.2 (immutable rows), 8.3 (closure hash), 10 (RPT-RC2), 11.1, RC-6, RC-14, RC-15. Code: F16 (append-only
plus allowlisted audit), F17 (hash chain, global lock).

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation)** Append-only plus audit, plus a per-campaign hash chain anchored in the closure summary | `recert_decision` and `recert_decision_event` carry `prev_hash` / `row_hash`; the chain is per campaign and the insert takes a per-campaign lock; the tip hash is part of the closure summary and of RPT-RC2, so a copy outside the database fixes it | Detects an edit or removal in the middle of a campaign; the anchor in a report the auditor holds covers a rewrite of the whole chain; the lock is per campaign, so 50-item bulk operations of different campaigns do not queue behind each other | An administrator who can edit the table and recompute every later hash is detected only against an anchor that left the database; the chain is a schema element that is hard to add later |
| B Append-only plus audit, with a content hash per row, no chain | The V216 pattern (F16) | Simplest; same as the zone matrix | A database administrator can alter or delete a row, or disable the trigger, with nothing to compare against |
| C One global chain, as the failover ledger | F17 shape | Strongest linearization | A global lock on every decision, including 50-row bulk operations; the failover ledger is a low-rate table, this one is not |

**Missing evidence.** The bank's threat model for its own database administrators and what the auditor expects (bank / audit
input, not Q6 or Q16); whether the audit log itself is protected against the same administrator: `audit_log` is written by
trigger in the same database (F16), so it does not by itself answer the question.

**Delivery order.** Must be decided before step 3's migration: chaining columns and the insert path cannot be added to existing
rows later without a chain that starts mid-table. A: step 3 migration carries the columns, a per-campaign lock and a
verification routine that step 5's closure uses. B: nothing extra. C: adds a global lock to the step 3 hot path.

### 4.8 Q19 -- can a change reference be checked

**Question.** Stay free text, or be validated (for example a ticket id format)?

**Touches.** Contract 6.1 (change reference, 1-200 characters, optional), 1 (no ticket integration). Product rule: warn, do not
refuse, operator input on policy grounds (PO 2026-11-18, `PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md`;
applied again in `PO_DECISION_RECORD_2026_11_23_PAN_IDLE_SESSION_SYNC.md`).

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation)** Free text with length and control-character checks only; an optional bank-supplied pattern produces a warning, never a refusal | The decision records the text as typed; a non-matching value shows a warning on the screen and is stored with a flag | Follows the product rule; no change-system integration; no format invented | The reference is not proof that a change exists |
| B Refuse a non-matching value | Strict format | Cleaner data | Contradicts the product rule; blocks a decision on a formatting ground |
| C Integration with the bank's change system | Existence check | Strongest | Out of scope in contract 1; needs its own contract and bank input |

**Missing evidence.** The bank's change reference format, or whether one exists (a bank input of 1.2; not in the repository).

**Delivery order.** None for A (a pattern setting can arrive at step 3 or later). B would add a refusal code to step 3. C is a
separate contract.

### 4.9 Q20 -- what "internet-facing" means (and which internal ranges apply)

**Question.** Is "the resolved source or destination cell is `any` or contains an address outside the declared internal
ranges" the right test, and which ranges apply before a zone matrix version is active?

**Touches.** Contract 3.3 (high-risk, fail-closed `RISK_UNDECIDED`), 6.5 (high-risk excluded from bulk KEEP), 8.2 (any decision
on a high-risk rule is four-eyes), RC-16, RC-17. Decided and not reopened: Q1's category list (any-any, internet-facing, zone
matrix violation) and the 6-month interval. Code: F18 (existing range list, IPv6 handling), F10 (`Cell.negated`), F19 (zone
matrix is evaluated for CP and PAN only, F8).

**What main knows today.** No code on main classifies a rule cell as "outside an internal set". Hygiene `broad` recognises
"any" and large IPv4 prefixes (F18), which is not an internet test. The only internal-range list is the zone matrix
evaluator's three IPv4 RFC 1918 blocks (used for the unassigned-space report); IPv6 values make a zone matrix outcome
"IPv6 not evaluated", and ULA space appears only in that gap report. No other special-purpose range is listed. The
contract's "RFC 1918, RFC 4193" internal set therefore has no ready-made classifier on main.

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation)** Either side, fail-closed | High-risk when the resolved source or destination is `any`, contains an address outside the internal set, or is a negated cell (a negated internal block matches everything outside it); IPv6 values, unresolved, dynamic and depth/cycle-limited cells give `RISK_UNDECIDED` (high-risk). Internal set before a zone matrix version is active: RFC 1918 IPv4 (as F18) plus RFC 4193 plus a bank-confirmed list; once a version is active, every zone except `UNASSIGNED`. | Conservative and consistent with the contract and with the zone matrix's IPv6 stance; one rule for both directions | Every outbound rule to `any` (ordinary web access) becomes high-risk: a 6-month interval, no bulk KEEP, and four-eyes for every decision on it, possibly a large share of all rules |
| B Inbound exposure only | High-risk when the source is outside the internal set and the destination is inside | Matches the literal meaning "facing the internet" | A rule from inside to `any` is high-risk only through any-any; outbound exposure is not tiered |
| C Either side, tiered | As A, but a destination-only external rule gets the 6-month interval without four-eyes on every decision | Keeps the review frequency, eases approval load | A second risk tier to explain; 8.2 and 3.3 both change |

**Missing evidence.** The share of rules each definition would classify high-risk on real policies (no count exists; step 1's
counts-only measurement could produce it); the bank's internal ranges beyond RFC 1918 / RFC 4193 and whether carrier-grade
NAT, link-local or loopback space counts as internal (a bank input; UNKNOWN here); how many rules have cells made only of
dynamic or FQDN objects; who may edit the bank-confirmed list and under what approval (the zone matrix treats loosening of
its address space as a rationale-bearing, four-eyes act, 3.5 -- the same should apply here).

**Delivery order.** Step 3 includes "high-risk classification with `RISK_UNDECIDED`", so Q20 must be settled before step 3. The
internal-set source is another dependency: until zone matrix zones are available the bank-confirmed list needs a home
(part of step 2 or 3). A: the heaviest approval load at step 5 and the largest bulk-KEEP exclusion at step 3. B: smallest
high-risk set; leaves Q1's any-any as the only outbound tier. C: adds a tier to the model of step 3.

### 4.10 Q21 -- are Cisco ASA and FortiGate rules in scope, and which fields form their rule version

**Question.** In scope now, out of scope until a later revision, or split by vendor?

**Touches.** Contract 5.1, 5.2, 14.3 Q21, section 12; `ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md` (5.2 ASA, 5.3 FortiGate).

**What main knows today (verified, F5-F9).**
- **ASA rule key is positional.** The id is built from the snapshot id, the binding, the ACL name and the list position;
  the name is `aclName:position`; `uuid` is `null`. An insertion or removal changes the key of every later ACE of that
  ACL (`AsaPolicyMapper.java:275-277`, `:321-323`).
- **FortiGate keeps `policyid`.** The id is derived from the device, the VDOM and `policyid`, with the position appended
  only for a repeated `policyid`; `uuid` is the printed `set uuid` when present (`FortiGatePolicyMapper.java:210-213`,
  `:451-453`). Whether a `policyid` can be reused after deletion is not established (UNKNOWN).
- **Hit counts for these vendors are UNKNOWN.** Neither mapper sets any (F5), and nothing else on main attaches counters to
  an ASA or FortiGate snapshot. Whether the devices expose usable per-rule counters is a vendor-documentation question this
  repository has not answered. The 180-day `unused` source (3.2) therefore has no evidence for them, and the ASA
  access-list-not-referenced finding is not a hit-based source (F2).
- **The snapshots are derived on read, not stored or collected** (F8): no `policy_id`, no history, no completed-collection
  event, no zone matrix run. The campaign's "enrolled from a complete snapshot" (3.1), the invalidation trigger (5.3) and
  the verification trigger (6.4) have no event to hang on.
- **Non-access rules exist in these snapshots** (F9) and must never be enrolled.

| Option | What it does | For | Against |
| --- | --- | --- | --- |
| **A (engineering recommendation, and the contract's own option 1)** Out of scope until a later revision | No ASA or FortiGate rule is enrolled; revision 3 states the field lists once Q9 and the ASA key question are settled | Verified facts strengthen the contract's reasons: positional ASA keys orphan decisions on every insertion; there is no stored snapshot or collection event for either vendor; no hit counts; Q8 and Q9 would otherwise be decided twice | These devices are not recertified until revision 3; the bank may need them sooner |
| B In scope now, both vendors | Rule version from the stored fields (contract 14.3) minus what Q9 excludes; ASA rules flagged `IDENTITY_FALLBACK` | Complete coverage | The council must also settle: how a positional ASA key survives an insertion (every later decision `INVALIDATED` or, worse, a different ACE inherits a KEEP whose `ruleHash` happens to match); what "completed collection" means for a derived snapshot; the `unused` source for ASA / FortiGate; correction 4 |
| C FortiGate only | FortiGate in scope (key survives insertions and moves), ASA deferred | A usable half; FortiGate's key and optional `uuid` are stable | Same missing event, hit counts and `policyid`-reuse unknowns; two scopes to explain; not offered by the contract, so it is new design |

An option the packet does **not** propose: a content-derived ASA identity. It would be a new identity rule with no contract or
evidence behind it (AGENTS.md identity law: no invented equivalence), and a new design in its own right.

**Missing evidence.** Vendor documentation for per-rule counters on both devices (and, if they exist, whether they are
collected without a new command, which would be a gate question); whether a FortiGate `policyid` is ever reused; whether any
stable per-ACE identifier exists on ASA; whether the change actor (V208) can ever be linked for these vendors (no evidence
found); a definition of "complete" for a derived snapshot (the mapper's `failures` list exists, but no recertification event
reads it).

**Delivery order.** A: nothing changes in steps 1-9; a later revision adds a step per vendor. B / C: step 1 grows a vendor
field list and a "derived snapshot" event, step 4 gains a trigger source that does not exist, step 6 loses the `unused`
source for these vendors. B/C cannot start before Q9 and the ASA key decision.

## 5. Seat charters

The council has three seats, run as fresh-context, read-only seats from the PO session (`nexus-decision-council`). Each seat
owns the questions below, reads the contract, this packet and the sources it cites, and returns a verdict per question
(accept the engineering recommendation / change / reject, with the reason) plus any abuse it found. The seat that is not the
owner of a question is its second reader for the prompts marked there. The model for the prompts is the abuse-case list of
`ZONE_MATRIX_CONTRACT.md` 8.5 (an abuse, and the clause that closes it).

### 5.1 Seat 1 -- identity and authorization

**Owns:** Q2, Q3, Q7, Q8; second reader of Q21's rule key.

Adversarial prompts (contract sections 4, 6.5, 8):
1. *Two accounts of one person.* "alice", "Alice", "alice " as decider and approver: which canonicalization stops
   `SELF_APPROVAL`, and is case folding allowed for directory principals at all (identity law)? F12 versus F13.
2. *Team stuffing.* A campaign manager adds themselves to the owning team, decides, and removes themselves. Is the membership
   change audited with before/after, and does the evidence record the membership at decision time (6.2)?
3. *Stale group set.* A directory member removed from a group decides inside the freshness window; what refuses it, and what
   happens when the directory is unreachable?
4. *Fallback laundering.* A manager assigns an item to themselves from the fallback queue and decides it; is
   `DECIDED_WITHOUT_OWNER` plus 8.2 enough, and can the same person approve?
5. *Owner sinks.* A CSV import assigns every rule to one owner (or a retired owner) to hide them; are the unknown-owner and
   per-owner counts visible enough that this shows?
6. *Break-glass.* Root decides or approves; is the refusal (`PRINCIPAL_NOT_ELIGIBLE`) tested, and does replay_viewer see
   only masked results (8.1, RC-7, RC-13)?
7. *Reassignment mid-campaign.* An owner change moves open items to the new owner (4.3): can an owner be swapped to escape a
   pending decision?
8. *Shared rule.* Q8 option B: could one decision cover content nobody compared?

### 5.2 Seat 2 -- compliance and evidence semantics

**Owns:** Q9, Q14, Q18, Q19, Q21 (evidence side); second reader of Q20's definition.

Adversarial prompts (contract sections 3.2, 5, 6, 7, 10):
1. *Keeping a KEEP alive.* Change what a rule matches without changing `ruleHash` or `objectsHash`: a dynamic or empty group,
   an object nested past the depth limit, an object the snapshot does not carry (the "missing marker" case, 5.2). Which of
   these must turn the certification `UNKNOWN`?
2. *Stale certification.* Break or delay collection so a certification looks current: does the 24-hour interim staleness of
   the zone matrix (`PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md` answer 1) or the product-wide scale apply, and is
   the interim value labelled as interim wherever it shows?
3. *Disabled, then re-enabled.* A REMOVE request met by disabling (Q10): is `DISABLED_NOT_REMOVED` shown and counted, and does
   re-enabling after a "removal observed" claim stay impossible (RC-20)?
4. *Positional keys.* ASA: a KEEP for ACE 5, an ACE inserted above, a different ACE now at position 5 whose `ruleHash` equals
   the old one: what is shown? (F6; relevant only if Q21 option B or C is chosen.)
5. *Hit-count semantics.* A rule `unused` on one HA member, used on the other; "zero collected hits, window unknown" mistaken
   for "unused" (RC-18); PAN totals existing only when every target answered (F3).
6. *Evidence rewrite.* A database administrator edits a decision, disables the append-only trigger, or deletes a campaign
   (no delete route, 11.1): what detects it (Q18)? What does the auditor hold outside the database?
7. *Report that hides.* An RPT-RC1 for a period that omits unknown, never-reviewed or owner-less rules, or prints a green total
   (RC-4, RC-14); "removed" said for a rule not seen to disappear from a complete snapshot (RC-10).
8. *Two records.* Q14: a KEEP on a violation read by an auditor as "the deviation is accepted".
9. *History link.* Correction 8: an invalidation presented as caused by a history revision that is only the nearest one.

### 5.3 Seat 3 -- security and adversarial

**Owns:** Q20; second reader of Q3, Q18 and Q21; first reader of the prompts below on every question.

Adversarial prompts (contract sections 6.5, 7, 8, 9, 11):
1. *Evading high-risk.* Split a broad rule into two halves, hide public space inside an object that looks internal, edit the
   bank-confirmed internal list to include public space, use a negated cell, `::/0`, or an FQDN/dynamic object. Which
   classification result does each give (`RISK_UNDECIDED` must be high-risk)? Who may edit the internal list, with what
   approval?
2. *Per-item authorization.* Guess or enumerate item ids (IDOR); is the owner check per request and per item (RC-8), and is
   the item content masked for a reader who is not the owner?
3. *Races.* A decision submitted as a collection completes (`RULE_CHANGED_WHILE_REVIEWING`); an approval after a change
   (`REQUEST_CHANGED`); two approvers at once; a double submit; a bulk operation in which one of 50 items changed (correction
   10: whole refusal or partial recording?).
4. *Chunked bulk.* Bulk KEEP is limited per operation, not per reviewer or per hour (Q13 is decided): does the report make
   a reviewer who certified many rules in minutes visible (6.5)? This is a reporting prompt, not a reopening of Q13.
5. *Leakage.* Rule names, object names, addresses or free text in mail (RC-12), logs (RC-13), error messages, CSV exports
   (formula injection), audit rows (column allowlist), or to replay_viewer.
6. *Mail and escalation abuse.* Flooding the team mailbox, a recipient list that includes an outsider, escalation routes that
   disclose counts to a wider audience than the campaign.
7. *No device path.* Verification and enrolment read stored snapshots only; is the architecture test of RC-11 enough, and
   does the ASA / FortiGate derive-on-read path (F8) re-open a configuration read that must not happen?
8. *Retention janitor.* The janitor removes rows only past the bank's retention (11.3): can it be pointed at a campaign that
   is still open or at the chain anchor of Q18?
9. *Dormant-owner abuse (Q7).* An attacker's account becomes an owner and stays unseen: does the flag expire a decision's
   validity or only decorate it?

## 6. Decision table and what the PO session writes afterwards

Both right-hand columns stay empty in this packet: the council fills "council verdict" and the PO fills "PO decision".

| Question | Engineering recommendation (Option A) | Owner seat | Council verdict | PO decision |
| --- | --- | --- | --- | --- |
| Q2 owner identity and authorization | Narrow role token plus per-item owner check; `PERSON` / `TEAM` first, `DIRECTORY_GROUP` after the group-testability evidence | Seat 1 | | |
| Q3 four-eyes scope | 8.2 as drafted; approver holds `recert_approve`; no teammate; no compliance_admin requirement; second person closes a `PERIODIC` campaign; one shared canonicalization | Seat 1 | | |
| Q7 owner liveness | Flag `OWNER_NOT_SEEN` after N days (N per campaign), never retire or reassign automatically | Seat 1 | | |
| Q8 shared rules | One decision per policy source and rule; same-uid hint only | Seat 1 | | |
| Q9 rule version | Contract 5.2 fields; name, comment, logging are informational events; `objectsHash` on any reachable-object change, revisited after the step 1 measurement | Seat 2 | | |
| Q14 zone matrix exception | Two records; a KEEP never changes a finding's status | Seat 2 | | |
| Q18 evidence integrity | Append-only plus audit plus a per-campaign hash chain anchored in the closure summary | Seat 2 (Seat 3 second) | | |
| Q19 change reference | Free text; a bank pattern only warns | Seat 2 | | |
| Q20 internet-facing | Either side, fail-closed, negated and IPv6 cells `RISK_UNDECIDED`; RFC 1918 plus RFC 4193 plus a bank list; zones once active | Seat 3 | | |
| Q21 ASA and FortiGate | Out of scope until a later revision | Seat 2 (Seats 1 and 3 second) | | |

**Artefacts the PO session writes afterwards (in this order; none is written by this lane):**
1. A decision record `docs/design/PO_DECISION_RECORD_<date>_RULE_RECERTIFICATION_COUNCIL_VERDICTS.md`, status `RATIFIED` only
   after the PO says so in chat, recording per question: the council's verdict, the PO decision, its source and the
   authorization reference. Council seat outputs are kept as the PO session's ops notes, as for the zone matrix
   (`ZONE_MATRIX_CONTRACT.md` Status).
2. Contract revision 3 of `RULE_RECERTIFICATION_CONTRACT.md` folding in the verdicts and corrections 1-11 above (the bank
   inputs Q6 and Q16 stay open as named input fields, as in the zone matrix contract), then the PO's freeze, which turns the
   status line to `FROZEN` with implementation authority for the delivery order of section 12, one step at a time.
3. Backlog updates through `tools/delivery/project_queue.py` (`recertification_council` done, `rule_recertification_campaigns`
   next step), and `project/build_history.json` through its own tooling.

## 7. Non-goals

- No change to `RULE_RECERTIFICATION_CONTRACT.md`; this packet does not edit, freeze or amend it.
- No reopening of Q1, Q4, Q5, Q10, Q11, Q12, Q13, Q15, Q17; no attempt to answer Q6 or Q16.
- No code, migration, gate row, device command, notification route or Reports R1 amendment from this packet or the council.
- No filling of an UNKNOWN from general knowledge: vendor behaviour for ASA and FortiGate counters, FortiGate `policyid` reuse,
  directory immutable identifiers and the bank's address ranges stay UNKNOWN until evidence exists.
- No new migration number is chosen here; a later step takes its number from `reserve-migration.sh` when it is built.
