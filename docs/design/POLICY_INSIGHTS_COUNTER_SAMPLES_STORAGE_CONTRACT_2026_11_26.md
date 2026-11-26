# Policy Insights: counter samples storage contract (phase P3)

Status: DRAFT -- DO NOT FREEZE (2026-11-26). Proposal only; not implementation authority.

Date: 2026-11-26. Backlog id `policy_insights_p2`. Controlling text: `POLICY_INSIGHTS_DESIGN_2026_11_24.md` (FROZEN)
section 3.3 "Counter age and resets", row I10 (section 4), phase P3 (section 9: "Storage contract; one month of
samples before any claim"), question Q4; `PO_DECISION_RECORD_2026_11_24_POLICY_INSIGHTS.md` item Q4 ("Hit-counter
samples are kept at each collection. This adds no new device command; the storage contract comes with phase P3").
Shape and tone follow `LIFECYCLE_LICENSE_STORAGE_CONTRACT.md`; acceptance rows follow the `ZM-n` rows of
`ZONE_MATRIX_CONTRACT.md` section 10.2.

This document decides nothing. It names no migration number (a migration lane takes one with
`reserve-migration.sh` only after the Product Owner freezes a contract), adds no code, route, gate row or device
command, and authorizes no implementation. Every "proposal" below is a candidate answer for the Product Owner; the
last section lists the questions. Sizes are **estimates** unless a measured record is cited; no estate measurement of
sample volume exists yet. Examples are synthetic (`FW-TANGO-04`, `192.0.2.x`).

## 1. Purpose and scope

**Why.** Today a stored hit counter has no window: `HitCounts` carries no reset or window-start field and zero hits
is worded "counter reset/window is unknown" (design F9). Insight I10 (zero hits, old last hit) is therefore
`INDICATED` at best. A series of samples taken at each collection gives a product-observed window ("no hit
observed between T1 and T2") without logs and without a new device command (design 3.3, PO Q4).

**A sample** is one row per rule key, per target, per collection: an opaque rule key, the hit count, the last hit,
the collected-at time of the evidence, a source and a target reference, and a marker (section 3). Proposal: also the
first hit (section 9, question 7).

**A sample is not** rule text. No rule name, comment, section name, address, object name, zone name, user, device
name or serial is stored in any sample column. The rule key is opaque and is never parsed, cast, truncated, padded or
normalised (AGENTS.md "Identity law"); two keys are the same only when their stored bytes are equal.

**No new device command, and no new read.** Samples are derived from counters the existing collections already
store in the parsed policy snapshot, inside the same transaction that publishes it. Repository facts, verified
2026-11-26:

| What | Where | Fact |
|---|---|---|
| Check Point read | `V121__policy_hit_counts.sql` gate `cp_policy_access_rulebase_hits` (`show-access-rulebase ... show-hits true`); `V124__cp_policy_object_inventory.sql` approves it; `V130__cp_policy_access_layer_uid.sql` gate `cp_policy_access_rulebase_uid_hits` | Existing, signed-off reads; automatic at most once per domain per 6 hours |
| Palo Alto read | `V121__policy_hit_counts.sql` gate `pan_policy_rule_hit_count`; signed off by `V155__pan_rule_hit_count_signoff.sql` | Security rulebase only; per firewall and virtual system |
| Palo Alto NAT hit read | `V163__policy_completeness.sql` gate `pan_policy_nat_rule_hit_count` | **Out of this contract** (security rules only, as `PolicyHitCounts.aggregate` already does) |
| Where the counters sit | `policy_snapshot.snapshot` (`V115__management_policy_snapshot.sql`), JSON path `sections[].rules[].hitCounts` | Fields `hits`, `firstHit`, `lastHit`, `source`, `collectedAt`, `level`, `firewalls[]`; each `firewalls[]` entry has `deviceId`, `context`, `hits`, `firstHit`, `lastHit`, `createdAt`, `modifiedAt`, `collectedAt` (`PolicySnapshot.HitCounts`, `PolicySnapshot.FirewallHits`) |
| Domain-level evidence time | `cp_policy_domain_run.hits_collected_at` (`V125__cp_policy_changed_domains.sql`) | Latest hit-evidence time per Check Point domain run |
| Counters are not revisions | `V121__policy_hit_counts.sql` (`fn_policy_history_fields` drops `hitCounts`) | "Operational counters are not policy configuration revisions"; rule history therefore holds no series (design F10) |
| Null is not zero | `PolicySnapshot.HitCounts` javadoc | "Null counters mean insufficient evidence, never zero." |
| Existing rule key | `V120__policy_rule_history.sql` `fn_policy_history_rules` | `uuid:<uuid>` when the rule has a uuid, else `name:` plus the JSON array of container, section and rule name |

The existing rule key for a rule without a uuid contains rule text. Proposal: a sample never stores it; it stores a
digest of it (section 2). The raw-evidence law is met in the form it prefers: counters, tokens and relationships,
no raw vendor response (AGENTS.md "Raw-evidence law").

**Consumer.** `PolicyInsights.analyze(..., windowDays, thresholdDays)` already accepts "a counter window in days that
something other than the stored counters established, by rule id"; phase P1 passes none
(`PolicyInsights.java`, javadoc of `analyze`). The read model of section 8 is what fills that parameter.

**Out of scope.** Log tuples (phase P4, its own retention contract), Cisco ASA and FortiGate (no counters are
collected, design F11), the Palo Alto NAT rulebase, any user interface (P3 has none), any device read.

## 2. Table shape (proposal)

Two append-only tables. Names are proposals.

**`policy_counter_collection`** - one row per attempted sampling of one policy snapshot.

| Column | Type | Notes |
|---|---|---|
| `collection_id` | `BIGINT GENERATED ALWAYS AS IDENTITY` | Primary key. |
| `policy_id` | `TEXT NOT NULL` | The snapshot's package id. **No foreign key** to `policy_snapshot` (proposal, question 9): `policy_snapshot` rows are replaced and may be deleted, and an append-only table must not make a policy deletion fail; orphaned rows leave by retention. |
| `snapshot_collected_at` | `TIMESTAMPTZ NOT NULL` | `policy_snapshot.collected_at` of the snapshot this collection was derived from. |
| `recorded_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | Database clock; used for retention and for the clock checks of section 6. |
| `outcome` | `TEXT NOT NULL` | `SAMPLED` or `NOT_SAMPLED`. |
| `reason` | `TEXT` | Null when `SAMPLED`. Otherwise a token from a closed set checked by the schema: `SNAPSHOT_INCOMPLETE`, `HITS_NOT_COLLECTED`, `CLOCK_NOT_MONOTONIC`, `DAILY_CAP`. Regex-checked like `packet_analysis_run.failure_reason` (`V154__packet_analysis_shell.sql`). |
| `targets_expected` | `SMALLINT` | From snapshot `metadata.targets`; null when `NOT_SAMPLED`. |
| `sample_count` | `INTEGER NOT NULL CHECK (sample_count >= 0)` | Rows written; 0 when `NOT_SAMPLED`. |

Unique `(policy_id, snapshot_collected_at)`: the idempotency key of section 6. A `NOT_SAMPLED` row is stored on
purpose: it is the only record that a collection happened and yielded no evidence, so a reader can say "no sample at
T" instead of staying silent. It carries no counter and no zero.

**`policy_counter_sample`** - one row per rule key, target and collection.

| Column | Type | Notes |
|---|---|---|
| `policy_id` | `TEXT NOT NULL` | Same value as the collection row (kept here so the history index is one index). |
| `rule_key` | `BYTEA NOT NULL CHECK (length(rule_key) = 32)` | SHA-256 of the UTF-8 bytes of the stored rule key of `V120__policy_rule_history.sql` (`fn_policy_history_rules`), computed in the database from the same function so the two always agree. A representation-only, byte-exact transformation; no case, whitespace or numeric normalisation. |
| `target_ref` | `BYTEA NOT NULL CHECK (length(target_ref) = 32)` | Check Point: SHA-256 of the fixed token `mds` (one management-reported value per rule; cluster aggregation is UNKNOWN, section 3). Palo Alto (second lane, question 2): SHA-256 of the length-prefixed pair (`deviceId`, `context`) built like `PolicySnapshot.ref`, so a member and a virtual system are separate targets. |
| `evidence_at` | `TIMESTAMPTZ NOT NULL` | The counter's own `collectedAt` (the product's read time), not `recorded_at`. |
| `hits` | `BIGINT NOT NULL CHECK (hits >= 0)` | A null or unparseable counter writes **no row** (section 6). |
| `last_hit` | `TIMESTAMPTZ` | From `lastHit`; null when the device gives none. |
| `first_hit` | `TIMESTAMPTZ` | Proposal (question 7): from `firstHit`, needed to see a reset that `hits` alone hides. |
| `marker` | `TEXT NOT NULL` | Closed set of section 3, checked by the schema. Written at insert from the previous sample of the same key and target; the raw facts stay in the row, so a corrected rule set can recompute it without losing evidence. |
| `collection_id` | `BIGINT NOT NULL REFERENCES policy_counter_collection(collection_id)` | `ON DELETE` default (restrict): the pruner removes samples before their collection row, in one transaction. |

Primary key `(policy_id, rule_key, target_ref, evidence_at)`. This is both the idempotency key of section 6 and the
index for "samples of one rule key over time": one index range scan, newest last, no sort. One more index on
`collection_id` (foreign key and pruner). The collection table needs an index on `recorded_at` for the pruner.

No sample column holds text from a rule: `rule_key` and `target_ref` are fixed-length digests, `marker` and
`reason` are tokens, the rest are numbers and times.

**Append-only enforcement.** A `BEFORE UPDATE` trigger on both tables refuses every update (name:
`policy_counter_sample_immutable`, same idea as `sanitize_vault_entry_immutable` in
`V156__data_sanitizer_vault.sql`). A `BEFORE DELETE` trigger refuses a delete unless the transaction carries the
audit context of the retention pruner (`app.action_id = 'policy_counter_sample_prune'` and its reserved actor), the
check shape of `fn_audit_cutover_segment` in `V217__cutover_assessment.sql`. Grants for `ui2_app`: `SELECT, INSERT,
DELETE` on both tables, no `UPDATE` (the grant shape of `sanitize_vault_entry`, `V156`; `V154`'s ledger goes further
with `REVOKE ALL` then `SELECT, INSERT`, which is not possible here because the pruner deletes). Audit: a
statement-level trigger writing one `audit_log` row per policy and statement with counts only, never a rule key or a
counter, copying the pattern of `trg_audit_sanitize_vault_entry` (`V156`) because a per-row audit of millions of rows
is not acceptable; `audit_redaction_policy` classifies `rule_key` and `target_ref` (tier chosen by the implementation
lane under the V5 contract).

**Sizes (ESTIMATES; nothing here is measured).**

- Row size, estimate: about 190 bytes heap (24-byte tuple header, 40-byte `policy_id`, two 33-byte digests, three
  8-byte times, one 8-byte counter, one 8-byte `collection_id`, a short marker) plus about 130 bytes of primary-key
  index plus about 30 bytes of `collection_id` index: **about 350 bytes per row**.
- Scale source: `ZONE_MATRIX_STEP3_SCALE_MEASUREMENT.md` sizes ten synthetic policy snapshots from 2,500 to 16,500
  rules (five Check Point: 47,000 rules in total; five Palo Alto: 39,500 rules in total; 86,500 overall). These are
  fixtures at the contract's scale ceiling, **not** a measurement of the live estate; the real rule counts are
  UNKNOWN until a counts-only measurement runs (that document's own boundary 3).
- Cadence ceiling, estimate: the signed-off hit reads allow an automatic read at most once per domain per 6 hours
  (gate rows above), so at most 4 collections a day; manual collection requests add more (section 4 proposes a daily
  cap). The real cadence is UNKNOWN and is not restated from any setting here.

| Case | Rows per collection | Rows per day (4 collections) | Rows over 30 days | Rows over 120 days | Storage over 120 days (about 350 bytes per row) |
|---|---|---|---|---|---|
| Largest single synthetic policy (16,500 rules, one target) | 16,500 | 66,000 | 1.98 million | 7.92 million | about 2.8 GB |
| Five Check Point policies, one target each (47,000 rules) | 47,000 | 188,000 | 5.64 million | 22.6 million | about 7.9 GB |
| All ten, Palo Alto as one HA pair each (47,000 + 2 x 39,500 = 126,000) | 126,000 | 504,000 | 15.1 million | 60.5 million | about 21 GB |

The first row group (Check Point only) is the first lane of section 8. The last row is the ceiling if Palo Alto joins
with HA pairs; with Palo Alto single-firewall it is 86,500 rows per collection. A rule whose counter could not be
read adds no row, so these are upper bounds for the stated rule counts.

## 3. Reset detection and window rules

**Principle (fail closed).** A signal the repository cannot prove still ends a window; it never extends one. Breaking
a window can only lower a grade. A window is therefore "no reset **observed**", never "no reset occurred": a counter
that was reset and then grew past its earlier value between two collections is invisible to `hits` alone, which is
why proposal question 7 adds `first_hit`. The product's wording must say "observed" (the later read model carries
that caveat, as the design carries `INSTALL_STATE_NOT_VERIFIED`).

**Window.** For one rule key, the window is the longest suffix of its samples such that, for every expected target:
(W1) a sample exists at every `SAMPLED` collection in the suffix; (W2) no sample after the first carries a marker
other than `CONTINUES`; (W3) consecutive `evidence_at` values are no more than `MAX_GAP` apart (proposal: 72 hours,
question 8); (W4) the rule key is present in every collection in the suffix. The window length is the time from the
first sample of the suffix to the newest. The window of a rule is the shortest of its targets' windows.

**Markers** (closed set, written at insert):

| Marker | Condition against the previous sample of the same key and target | Window effect | Grade it allows |
|---|---|---|---|
| `FIRST_SAMPLE` | No earlier sample of this key and target | Starts a window; length 0 | `INDICATED` |
| `CONTINUES` | Later `evidence_at`; `hits` not lower; `last_hit` not earlier; `first_hit` not later; none of the rows below | Window continues | `SUPPORTED` possible, see grade rule |
| `HITS_DECREASED` | `hits` lower than before | Closes the window; a new window starts at this sample | `INDICATED` |
| `LAST_HIT_BACKWARDS` | `last_hit` earlier than before, or present before and null now | Same as above. Whether this is a reset, an aggregation change or a clock effect is UNKNOWN | `INDICATED` |
| `FIRST_HIT_FORWARD` | `first_hit` later than before (proposal column) | Same; catches a reset that `hits` hides | `INDICATED` |
| `REAPPEARED` | The key was absent from at least one `SAMPLED` collection of the policy since its previous sample | New window. A deleted-and-recreated rule with the same key is indistinguishable from a rule whose counter was merely unreadable once; storage does not tell them apart and both break the window | `INDICATED` |
| `LAST_HIT_IN_FUTURE` | `last_hit` later than `evidence_at` plus 5 minutes (proposal; clock skew between the device or manager and the product) | Row stored as read; the window is broken at this sample | `INDICATED` |

**Cases the design names**

| Case | What the contract stores | Window and grade |
|---|---|---|
| Hits decrease | `HITS_DECREASED` | New window starts at the later sample; the lost interval is unobserved. `INDICATED` until the new window reaches the threshold |
| Last hit goes backwards | `LAST_HIT_BACKWARDS` | As above; cause UNKNOWN |
| Rule key disappears and reappears | Nothing for the absent collections (no row exists for an absent key); `REAPPEARED` on return | New window |
| Rule key changes across a policy version | Old key stops, new key gets `FIRST_SAMPLE`. The two series are **never linked**: equality is not inferred from similar names or positions (AGENTS.md "Identity law"). A rule with a uuid keeps its key across versions; a name-based key changes when the container, section or name changes | The new key has a new window. Whether an install or a rename resets the vendor counter is UNKNOWN in the repository, so a changed policy version is **not** used as a window break by itself |
| Target absent in one collection | A target missing for a rule means no row for that rule in that collection (all targets or none per rule, section 6). The collection stays `SAMPLED` if other rules were sampled | W1 fails for that rule: it is a gap. Palo Alto already refuses a partial sum (`PolicyHitCounts.aggregate`: "summed only when every target answered") and the contract keeps that rule |
| Target set changes (member added or removed) | The new target starts with `FIRST_SAMPLE`; the removed one stops | The rule's window is the shortest over its current targets, so adding a target restarts it |
| HA members | Separate `target_ref` per member and virtual system (Palo Alto lane). Never summed in storage, and no assumption that one member's counter includes the other's | Per-target windows; the displayed sum remains the existing display rule |
| Check Point cluster members | One management-reported value per rule (`source` `mds`, no per-gateway breakdown, design F8). How management aggregates members and when it resets counters is **UNKNOWN** in the repository (design section 11, "Check Point counter aggregation") | The marker rules above still apply, but the grade stays `INDICATED` for Check Point until a measurement record exists |
| Counter unreadable, null or overflowed | No row; never a zero | Gap for that rule (W1, W4) |
| Policy revision changed with the same key | Not recorded as a marker | UNKNOWN whether it resets counters; no window effect |
| Palo Alto rule modified | Proposal (question 7): store `modifiedAt` as a sample column and break the window when it changes | Conservative; whether a modification resets a counter is UNKNOWN |

**Grade rule (proposal).** Samples can only produce `INDICATED` or `SUPPORTED`, never `PROVEN` (design 3.1: `PROVEN`
is set semantics over the policy). `SUPPORTED` for I10 needs all of: (G1) the window of section 3 is at least the
threshold the hygiene result was computed with (`PolicyInsights` `thresholdDays`); (G2) every expected target is
inside the window (W1); (G3) the hygiene result has an `unused` finding and no `unknown` finding for the rule (the
gap rule `PolicyInsights` already applies); (G4) for Check Point, a measurement record establishing the aggregation
and reset behaviour exists - **today none does, so a Check Point rule never exceeds `INDICATED` from samples**; for
Palo Alto, the Product Owner accepts the "no reset observed" wording (question 6). Anything else is `INDICATED`.
The vendor reset semantics of both vendors stay UNKNOWN in this document; this contract does not fill them from
general knowledge (AGENTS.md "Vendor semantics law").

## 4. Retention

**Proposal: 120 days default**, switchable, with the reasoning:

- The design gate needs at least one month of samples before any claim (design section 9, P3); 120 days is four times
  that.
- A window can never be longer than retention, and a claim needs a window at least as long as the hygiene unused
  threshold (a parameter of the hygiene result; its value is not restated here). Retention must exceed the largest
  threshold in use with margin, or the pruner removes the evidence the claim needs. 120 days covers a 90-day
  threshold plus one month, and one quarter end.
- The Product Owner's 90 days for flows (`PO_DECISION_RECORD_2026_11_14_ACI_SEGMENTATION_SUPPORT.md`, item 5, Q7:
  "90 days for flows and watch records") is **not copied**. It protects retained traffic metadata. A counter sample
  holds no address and no rule text; its length is driven by the window it must prove, not by privacy. If the
  threshold ever exceeds 90 days, a 90-day retention would make `SUPPORTED` unreachable.
- Cost, estimate: section 2 gives about 7.9 GB (Check Point only) to about 21 GB (with Palo Alto HA pairs) at the
  cadence ceiling. The first prune happens on day 121, after the one-month acceptance month has measured the real
  row size and cadence, so the Product Owner can still change the length before any data is removed.

**Pruning job (proposal), `PolicyCounterSampleRetention`**, the shape of `CutoverFlowRetention`
(`ui2/service/.../policy/CutoverFlowRetention.java`):

- Hourly `@Scheduled`; removes collections whose `recorded_at` is older than retention, oldest first.
- Bounded pass: at most 50 collections and at most 200,000 sample rows per pass (starting values, to be set from the
  measured row counts), one short transaction per collection (its samples, then its collection row; never split).
- The log line carries counts and exception class names only: never a policy id, rule key or counter.
- Two replicas pruning at once remove each row once (the delete is idempotent).
- **Failure behaviour.** A failed collection removal is retried in the next pass; a failed read of the expired set
  ends the pass as "skipped" with a warning, as `CutoverFlowRetention` does. The pass line always reports
  `failures`; a pass never silently skips. When the oldest remaining collection is more than retention plus 7 days
  old, each pass logs a warning naming only the count of overdue collections.
- **Read side is retention-exact.** The read model of section 8 ignores any sample older than retention even when the
  pruner is behind, so an overdue row can never lengthen a window.
- Switch properties (names are proposals): `ui2.policy-insights.counter-samples.enabled` (sampling; proposal default
  `false` until the Product Owner starts the month), `ui2.policy-insights.counter-samples.retention-days` (default
  120), `ui2.policy-insights.counter-samples.retention.enabled` (pruner; default `true`; `false` stops pruning, never
  sampling or reading).
- Daily cap (proposal): at most 24 `SAMPLED` collections per policy and UTC day; further ones are stored as
  `NOT_SAMPLED` with reason `DAILY_CAP`. This bounds the estimate of section 2 against repeated manual collections.
  It sits against the PO's "kept at each collection" (Q4) and is question 10.
- On-demand deletion is not proposed: samples hold nothing a person typed. A purge would need its own decision.

## 5. Access and masking

- **Who reads.** Samples are read only through the Insights detail read model (section 8), under the existing
  `POLICY_READ` action (`ActionRegistry`: security admin, onboarding admin, and `replay_viewer` for the masked view,
  as for the other policy reads, design F22). No separate samples route, no export, and no sample is ever written by a
  request: only the collection hook writes (proposal, question 3).
- **aiview** (`role:replay_viewer`) sees the masked projection only. The response is an allowlist by field name
  (design F19): rule identity through the existing policy pseudonyms, then counts and closed vocabularies - window
  days, number of samples, number of resets, marker names, grade, first and last evidence age. It never receives
  `rule_key`, `target_ref`, `policy_id` as stored, or any digest; targets appear as a count. A field the allowlist
  does not name is withheld by default.
- **Audit capture** names only ids, kinds and hashes: the statement trigger of section 2 records the policy id, the
  operation and a row count. It never records a counter, a rule key or a time series. A read through the Insights
  route is audited like any policy read (no new row shape).
- **Logs and errors** carry counts and exception class names only (as `CutoverFlowRetention`). No rule name, key,
  address or counter.
- **Sweep requirement for the implementation lane.** `AiviewRawVersusMaskedSweepTest`
  (`ui2/integration-tests/.../http/`) must seed a sampled policy whose rule names, uuids and target names are planted
  synthetic raw values, GET every aiview-reachable route, and find none of them; the static route audit
  (`AiviewMaskingLeakAuditTest`, through `AiviewReachableRoutes`) must list the new read as reachable and masked.
  `KNOWN_LEAKS` must not gain an entry. This is acceptance row CS-14.
- Evidence classification: counters and digests only, no CLASS 2 identity value (PRIVACY_AND_DATA_HANDLING.md
  "Classification"); aiview reporting stays "relationship, not values" (AGENTS.md "Sensitive identity reporting law").

## 6. Concurrency and failure

- **Two replicas, one collection.** The first transaction inserts the collection row; the second hits the unique
  `(policy_id, snapshot_collected_at)` and does nothing (`INSERT ... ON CONFLICT DO NOTHING`; the conflicting
  transaction writes no samples). Sample rows use `ON CONFLICT DO NOTHING` on the primary key as well. This also
  covers evidence that was reused: `CpDomainReuse` republishes a domain's earlier parsed evidence, and then
  `hitCounts.collectedAt` is the old read time, so the same `evidence_at` is not sampled twice.
- **All-or-nothing per collection.** The collection row and all its samples are written in one transaction, in the
  same transaction that publishes the snapshot (the pattern of `fn_capture_policy_rule_history` in
  `V120__policy_rule_history.sql`, which also skips a snapshot with collection failures). A failure of the sampling
  step rolls back its own writes only if the implementation lane proves, by test, that it cannot block or roll back
  the publication of the snapshot itself (CS-17): a sampling fault must never cost a policy collection.
- **Incomplete snapshot.** A snapshot with any collection failure writes a `NOT_SAMPLED` row,
  reason `SNAPSHOT_INCOMPLETE`, and no samples.
- **A collection whose hit read failed** (every rule has null counters; the gate's own rule is "UNKNOWN; retain
  policy without hit metrics") writes `NOT_SAMPLED`, reason `HITS_NOT_COLLECTED`. **A zero is never written for a
  collection that failed.** A rule with a null counter inside an otherwise sampled collection gets no row.
- **Per-rule targets.** A rule's rows are written for all of its expected targets or none (Palo Alto: when the
  breakdown has fewer entries than `metadata.targets`, or any `hits` is null, no row for that rule).
- **Clock.** `evidence_at` is the product's read time, `last_hit` and `first_hit` come from the device or manager.
  (a) A collection whose `evidence_at` is not later than the newest recorded for the policy is not sampled
  (`NOT_SAMPLED`, `CLOCK_NOT_MONOTONIC`), because append-only order by `evidence_at` is the basis of every window;
  (b) `last_hit` more than 5 minutes after `evidence_at` gets `LAST_HIT_IN_FUTURE` and breaks the window; (c)
  `recorded_at` is the database clock and is used only for retention. A wall-clock step back in the product therefore
  costs samples, never correctness.
- **Missing sample is not a failed rule.** Absence of a sample in the read model is `UNKNOWN`, never "unused"
  (AGENTS.md "UNKNOWN / fail-closed law").

## 7. Acceptance criteria

Each row says what a test must prove, with synthetic data (rules named `FW-TANGO-04-RULE-n`, addresses in
`192.0.2.0/24`). Rows needing PostgreSQL run against the local PostgreSQL 16 of the delivery model.

- `CS-1` The migration creates exactly the two tables, their checks, indexes and triggers, and nothing else; no gate
  row, route or capability changes (migration test, architecture test). Primary key and `collection_id` index exist.
- `CS-2` An `UPDATE` of any column of either table, by any role, fails; a `DELETE` fails unless the transaction
  carries the pruner's action and actor; `ui2_app` has no `UPDATE` privilege.
- `CS-3` No sample column holds text from a rule: a rule whose name, comment, section and object names are planted
  strings produces rows whose every column is a number, a time, a fixed-length digest or a token from the closed
  sets; no planted string occurs in the table dump, in `audit_log`, or in any log line of the run.
- `CS-4` `rule_key` equals the SHA-256 of the key `fn_policy_history_rules` returns for the same rule, for a uuid rule
  and for a name-based rule; two rules whose names differ only by case or whitespace get different keys (no
  normalisation).
- `CS-5` A collection with a failed hit read, or a snapshot with a collection failure, writes one `NOT_SAMPLED` row
  with the right reason and no sample row. No row with `hits = 0` exists for it. **A zero is never written for a
  collection that failed.**
- `CS-6` A rule whose counter is null, unparseable or overflowed gets no row while the other rules of the same
  collection are sampled.
- `CS-7` A genuine zero counter (`hits` 0 from a successful read) is stored as 0 and is distinguishable from the absent
  row of CS-6.
- `CS-8` Two writers presenting the same collection at once (two connections, barrier) leave exactly one collection
  row and one set of samples; the second writes nothing and does not fail the publication.
- `CS-9` A reused domain (older `collectedAt`) adds no duplicate sample rows for the unchanged evidence time.
- `CS-10` `hits` decreasing yields `HITS_DECREASED` and a window that restarts at that sample; `last_hit` going
  backwards yields `LAST_HIT_BACKWARDS`; `first_hit` moving later yields `FIRST_HIT_FORWARD` even when `hits` grew.
- `CS-11` A key absent from one `SAMPLED` collection and present again gets `REAPPEARED`; a renamed name-based rule
  gets a new key with `FIRST_SAMPLE` and its two series are not linked; a uuid rule moved to another section keeps
  its key and continues.
- `CS-12` A target missing for a rule in one collection writes no row for that rule; the window of that rule ends at
  the gap; adding a target restarts the rule's window; two HA members are two `target_ref` values and are never summed
  in storage.
- `CS-13` The window read model returns the longest suffix meeting W1-W4 and the grade rule of section 3; a Check
  Point rule never reaches `SUPPORTED` while the aggregation record is absent; a window shorter than the threshold is
  `INDICATED`; a missing sample is `UNKNOWN`, never "unused".
- `CS-14` The aiview raw-versus-masked sweep (`AiviewRawVersusMaskedSweepTest` family) seeds a sampled policy with
  planted raw rule and target values, finds none of them in any aiview-reachable response, and `KNOWN_LEAKS` gains no
  entry; the static route audit lists the read as masked.
- `CS-15` Retention: with retention set to N days and synthetic collections older and newer, one pass removes exactly
  the older ones, samples before collection row, one transaction each; a second pass removes nothing; two concurrent
  passes remove each row once.
- `CS-16` Pruner failure: a collection whose removal throws is retried next pass and counted in `failures`; the log
  line carries counts and class names only; a failed expired-set read gives a "skipped" line, never a silent pass.
- `CS-17` A fault injected in the sampling step does not prevent or roll back the snapshot publication, and the next
  collection samples normally.
- `CS-18` The read model ignores samples older than retention even before the pruner removes them.
- `CS-19` Clock: a collection whose `evidence_at` is not later than the newest of the policy is stored as
  `NOT_SAMPLED` (`CLOCK_NOT_MONOTONIC`); a `last_hit` 10 minutes after `evidence_at` is stored with
  `LAST_HIT_IN_FUTURE` and ends the window.
- `CS-20` With `ui2.policy-insights.counter-samples.enabled=false` no row is written and the read model answers
  "not sampled"; with the pruner switch off nothing is deleted and sampling and reading continue.
- `CS-21` Daily cap: the 25th `SAMPLED` collection of a policy in a UTC day is stored `NOT_SAMPLED`, `DAILY_CAP`.
- `CS-22` No code path of this feature issues a device command or reads a device (architecture test): the sampling
  step reads only the in-transaction snapshot and the two tables.
- `CS-23` Sizing: an integration test on synthetic data at the 16,500-rule scale writes one collection within a
  stated time budget and the measured row size and index size are recorded in the build record, replacing the
  estimates of section 2 (measurement attached before the contract is frozen).

## 8. Implementation outline for the later lane

Only after the Product Owner freezes a contract, and one lane, in this order:

1. **One migration**, number taken with `reserve-migration.sh`: the two tables, checks, indexes, triggers, grants,
   audit trigger and `audit_redaction_policy` rows of section 2. Additive; no existing table changes.
2. **Repository** (`persistence`): `PolicyCounterSampleRepository` - record a collection from a snapshot,
   read the samples of one rule key, read the collection headers of a policy, prune. Rule key and marker
   computation are SQL over `fn_policy_history_rules` so the key cannot drift from the history key.
3. **Collector hook** (proposal: a trigger on `policy_snapshot` beside `fn_capture_policy_rule_history`, which gives
   same-transaction atomicity and no collector code change; alternative: a repository call in the publisher). Check
   Point first. No new read, no change to `PolicyHitCounts`, no gate or frequency change.
4. **Pruner** `PolicyCounterSampleRetention` with its three switches (section 4).
5. **Read model** for the Insights detail: per rule, the window in days, sample count, markers seen, the targets
   covered, the evidence age and the section 3 grade, handed to `PolicyInsights.analyze` as `windowDays`. Masked
   projection for aiview (section 5). **No UI in P3**; the Insights detail screen shows the evidence in a later
   phase.
6. Tests CS-1 to CS-23; governance tests; the architecture test of CS-22; the PostgreSQL integration tests.

**Stays out:** log intake and tuple storage (P4); Palo Alto samples in the first lane unless the Product Owner answers
question 2 otherwise (the table already carries `target_ref`, so a later Palo Alto lane needs no schema change);
Palo Alto NAT counters; Cisco ASA and FortiGate; any change to which counters are read or how often; any user
interface; export of samples.

**Gate before any claim.** One month of samples on the first lane, with the real cadence, row size and number of
resets measured and recorded (design P3 gate), before any insight is promoted from `INDICATED` on the strength of a
sample.

## 9. Questions for the PO

Recommendation first in each row. Nothing below is decided until the Product Owner answers; this draft assumes the
recommendation only to make the text concrete.

| # | Question | Recommendation | Alternative and cost |
|---|---|---|---|
| 1 | How long are samples kept? | **120 days** (section 4): it exceeds the one-month gate and a 90-day unused threshold with margin; the first prune comes after the measuring month | 90 days (the flow precedent): cheaper by a quarter, but a 90-day threshold could then never reach `SUPPORTED`; 180 days: about 1.5 times the storage |
| 2 | Do Palo Alto samples start together with Check Point? | **No: Check Point first** (Q1 of the design is Check Point first). The table carries `target_ref`, so Palo Alto joins in a small second lane with no schema change | Together: the month clock starts for both, but rows can reach about 126,000 per collection (HA pairs) and the Palo Alto reset semantics are unproven |
| 3 | Who may read samples? | **`POLICY_READ`, through the Insights detail read model only; aiview masked; no separate route or export** | A new action limited to security and onboarding admins: extra action row and role work, no privacy gain because no sample holds an identity |
| 4 | Is sampling switched on at deploy or by the Product Owner? | **Off at deploy; the Product Owner switches it on**, which starts the one-month clock deliberately | On at deploy: the month starts at once; a sizing surprise is then discovered in production |
| 5 | Are rule keys stored as a digest? | **Yes**: SHA-256 of the history key, because a name-based key contains rule text | Store the history key as is: simpler join, but sample columns would carry rule names (CS-3 fails) |
| 6 | May Palo Alto reach `SUPPORTED` on "no reset observed", with Check Point staying `INDICATED` until a measurement record exists? | **Yes, with the fixed caveat that an unobserved reset is possible** | Both vendors stay `INDICATED` until a measurement per vendor: safer, but the samples then never raise a grade |
| 7 | Store `first_hit` (both vendors) and `modifiedAt` (Palo Alto lane) as extra sample columns? | **`first_hit` yes; `modifiedAt` decided with question 2.** They are the only signals that expose a reset hidden by growth | Neither: smaller rows, but a reset followed by growth past the old value is invisible |
| 8 | Window gap limit (`MAX_GAP`)? | **72 hours**, an unmeasured starting value; reset after the measuring month | A multiple of the measured cadence; 24 hours breaks windows on every missed collection |
| 9 | Foreign key from the collection table to `policy_snapshot`? | **None** (an append-only table must not block deleting a policy; rows expire by retention) | `ON DELETE CASCADE` needs the delete trigger to admit cascades, which weakens the append-only check |
| 10 | Daily cap of 24 `SAMPLED` collections per policy? | **Yes**, as a storage guard; PO Q4 says "at each collection" and the cap only bites after repeated manual collections | No cap: strictly every collection, with unbounded growth under repeated manual runs |
| 11 | Thin old samples (for example keep one a day after 30 days) to cut storage? | **No for now**: keep every sample, measure the first month, then decide with real numbers | Thinning from the start: about 4 times less after 30 days, but loses the exact collection times a reset marker uses |
| 12 | A partial Palo Alto collection (one HA member silent): drop that rule's rows for the collection? | **Yes** (all targets or none per rule), as `PolicyHitCounts.aggregate` already refuses a partial sum | Keep the answered members' rows: more evidence, but per-member windows then diverge and the rule grade needs a rule per member |
