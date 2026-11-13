# Rule object usage - contract

**Status: FROZEN - 2026-11-13.** Approved as written by the Product Owner in the PO session on 2026-11-13
("Onaylıyorum"); implementation authority for the delivery order of section 8. Scope decided by the PO on 2026-11-13 (answers R1-R4, relayed by the PO session). Builds on the W3-B
offline analyzer approved in direction by
[PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md](PO_DECISION_RECORD_2026_10_09_NSPM_WAVE1.md) and implemented in
`ui2/service/.../policy/OfflineAnalysis.java` (storage `V146__policy_offline_analysis.sql`).

## 1. Purpose

Answer, for one firewall policy rule over one period: **which of the rule's objects carried traffic, how much, and which
never did, down to nested group members**, so an operator can tighten the rule. The whole-policy mode of today's offline
analyzer stays as a second mode of the same screen.

Non-goals: no live log ingestion, no syslog receiver, no device command, no change to any device or policy, no automatic
rule change. "Not seen" is evidence about one uploaded period, never a deletion verdict.

## 2. Product surface

- Name **Rule object usage**; menu **Policy and changes > Rule object usage**; it replaces Tools > Offline analysis.
  `?screen=offline_analysis` and `?screen=policy&tab=offline|offline-analysis` redirect to it (`legacyRoutes.ts`, tested).
- Two modes: **One rule** (default) and **Whole policy** (today's behaviour, unchanged semantics).
- Menu gate unchanged: the Policy plane.

## 3. Inputs

1. A stored policy snapshot (as today) and, in One-rule mode, one rule of it, chosen from the snapshot or by pasting its
   rule UID / rule id. A UID that does not resolve to exactly one rule in the snapshot is refused (`RULE_NOT_FOUND`,
   `RULE_AMBIGUOUS`); no guessing by name similarity (identity law).
2. One uploaded traffic-log export, read once, in memory:
   - **Generic CSV** with operator column mapping (today's path; columns as in `screens/offlineCsv.ts`), plus a new
     optional `application` column.
   - **Vendor presets** (Palo Alto traffic CSV export, Check Point Logs & Monitor CSV export) are added **one vendor at a
     time, each only after a measurement record** of one anonymized sample export of that vendor (column names, value
     forms, time format, count field, rule identity field, version). A preset is a mapping plus value normalizers; it is
     never written from general knowledge. Until its record exists, a vendor's export is used through the generic path.
   - Limits: today's 100 MB / 2 000 000 rows, plus gzip upload (`.csv.gz`) counted on the decompressed size. Raising the
     row limit needs a memory measurement first.

## 4. Attribution (which rows belong to the rule)

- If the export carries a rule identity column (rule UID or id) and it resolves to exactly one rule of the snapshot, a row
  belongs to the chosen rule **when the log says so** (the firewall's own decision is runtime evidence). The lookup engine
  still evaluates the row; agreement / disagreement / unknown counts are reported as today and never override the log.
- Without a rule identity column, a row belongs to the rule when the lookup engine's deciding rule is the chosen rule
  (today's behaviour). Rows the engine cannot decide are counted as UNKNOWN with their reason, never attributed.
- Rows that belong to other rules are counted and set aside (`otherRuleRows`), not analysed further in One-rule mode.
- Rows that are malformed are counted by reason (`parseStats`), as today.

## 5. Output per object

The rule's source, destination, service and application cells are expanded through nested groups (depth 32, cycle guard,
work budget, as today). Every object and member gets one state:

| State | Meaning |
| --- | --- |
| `USED` | At least one attributed flow whose value falls inside this object. Carries `flowCount`, `firstSeen`, `lastSeen` (when the export has times). |
| `NOT_SEEN` | Resolved, and no attributed flow in the uploaded period falls inside it. |
| `NOT_AUDITABLE` | `any`, or a member type the analyzer cannot match (for example IPv6 ranges, non-TCP/UDP services, application filters). The reason is shown. |
| `UNKNOWN` | Unresolved object, group cycle or depth limit, incomplete snapshot collection, or budget exceeded. The reason is shown; never shown as unused. |

Rules:

- Counts are **overlapping, not exclusive**: a flow inside two overlapping objects counts for both. A group's count is
  the flows inside any of its members.
- **Negated cells**: members of a negated cell are reported as `NOT_AUDITABLE` (reason "negated cell"); the analyzer
  does not invert usage.
- **Applications** (new): a member of the application cell is `USED` when the row's `application` value equals the
  application object's name. Application groups, filters, Palo Alto `application-default` and Check Point
  applications/categories are `NOT_AUDITABLE` until their semantics are documented in a vendor measurement record.
  Rules with an application constraint are attributed by the log's rule identity when present; without it, today's
  UNKNOWN behaviour stands.
- Rule summary: period covered by the file (first/last time), rows read / attributed / set aside / unknown / invalid,
  whether logging covered the whole period is **UNKNOWN** unless the export proves it, and a tightening suggestion: the
  `NOT_SEEN` members and, for broad cells, the observed narrower ranges (today's narrowing, capped at 50 entries).
- Wording on every result: "Not seen in the uploaded period. This is not proof that the object is unused: the export
  covers only what was logged in that period."

## 6. Storage and privacy

- The uploaded file is streamed and discarded; nothing raw is persisted, logged or sent anywhere (as today; the V146
  CHECK constraint forbidding `rows`, `raw`, `file`, `csv`, `mapping`, `headers` stays).
- Stored per analysis: the existing aggregate document, plus `mode`, `ruleId` (One-rule mode), `otherRuleRows`, and the
  per-object states and counts of section 5, in the same `aggregates` JSONB. Expected to need **no new table**; if a
  migration is needed it is dry-run in `BEGIN/ROLLBACK` on the live database before rollout.
- Observed address values in results (top observed, narrowing ranges) follow the existing masked projection: masked
  roles, including aiview, see masked values; raw-view roles see real ones. Object names come from the stored policy.
- The Data Sanitizer is not a pre-step: it permutes addresses, so a sanitized export cannot be matched against the real
  stored policy. It stays the tool for files that leave the product (support, sharing).
- Agents and reviewers never read a raw export; vendor measurement uses sanitized samples only, and an end-to-end check
  on real data is run by an authorized operator inside the product, the agent reading only masked aggregates.

## 7. Authorization (unchanged)

- Read results: `POLICY_READ` (Policy plane). Upload and delete: `POLICY_OFFLINE_WRITE` (security_admin,
  onboarding_admin). The replay viewer (aiview) may read masked results and may not upload. One upload at a time; the
  policy bulkhead applies.

## 8. Delivery order

1. One-rule mode on the generic CSV path, with set-aside rows and per-object `USED` / `NOT_SEEN` counts (no sample
   needed). Screen rename and move to Policy and changes with redirects (Fable).
2. First/last seen per object, gzip upload.
3. Vendor preset for whichever vendor's sanitized sample arrives first, after its measurement record; then the other.
4. Application matching by name (Palo Alto first), after the measurement record documents the application field.

## 9. Acceptance criteria

- `ROU-1` A UID that resolves to zero or several rules is refused with a named reason.
- `ROU-2` With a rule identity column, attribution follows the log; disagreements with the lookup engine are counted,
  never silently resolved.
- `ROU-3` Every expanded member has exactly one state of section 5; `UNKNOWN` and `NOT_AUDITABLE` always carry a reason.
- `ROU-4` A member inside an unresolved group, a cycle or an incomplete snapshot is never `NOT_SEEN`.
- `ROU-5` The stored aggregates contain no raw row, header, mapping or file content (CHECK constraint and a test).
- `ROU-6` aiview sees masked observed values; a raw-view role sees real ones; neither sees the file.
- `ROU-7` Every old Offline analysis address lands on Rule object usage with its parameters kept.
- `ROU-8` Whole-policy mode results equal today's for the same input (regression test on a fixture).
- `ROU-9` Each vendor preset cites its measurement record; no preset exists without one.
