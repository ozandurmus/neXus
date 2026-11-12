# Palo Alto policy-change actor from the Panorama config log — design

**Status: DRAFT (2026-11-11; PO decisions D1-D7 ratified 2026-11-12 in
`PO_DECISION_RECORD_2026_11_12_PAN_CHANGE_ACTOR_FROM_CONFIG_LOG.md`).** The design stays DRAFT: its vendor semantics
(section 8) are not yet established and are not frozen by the decision record. Phase 1 is built under the decision
record, failing closed on every UNKNOWN. This version was written as docs only: no code, no migration, no gate row, and no
device command is added or sent by this document. The Product Owner approved starting this design on 2026-11-11
("Tasarımı başlat"). Every vendor semantic that official Palo Alto Networks documentation could not establish is marked
`UNKNOWN` (vendor semantics law) and is settled by the partial-first measurement of §9 before anything freezes. All
examples use synthetic names (`admin-a`, `DG-EXAMPLE`, `Rule-EXAMPLE`); no real host, address or administrator name
appears here.

## 1. The problem in one paragraph

The policy-changes screen shows `UNKNOWN (observed by collection)` as the actor of every Palo Alto rule change. For
Check Point the actor is a field the vendor's rulebase answer already carries. A Palo Alto rule in the running
configuration carries no "last modified by" field, so neXus has nothing to copy. The administrator's name does exist in
a different place: the Panorama **configuration log** records, for every configuration command, who ran it, when, and
on which configuration path. This design reads that log through the transport and gate rows neXus already has, and
joins its entries to the detected rule change. When the join is not certain, the screen says so (`UNKNOWN` or
`AMBIGUOUS`), never a guess.

## 2. What the code does today (finding 1)

| Step | Where | What it does |
|---|---|---|
| Collection | `ui2/worker/.../policy/PanoramaPolicyCollector.java` and `ui2/platform-core/.../policy/PanoramaPolicyMapper.java` | Reads the shared scope and each device group with `type=config&action=show&xpath=…` (running configuration; `PanPolicyGates.COMMANDS`). Maps each rule to a `Rule`; the rule's `uuid` attribute is kept when present. The mapper sets **no** `last-modified` and **no** `last-modifier` extra. |
| Snapshot | `PolicySnapshotRepository.save`, table `policy_snapshot` | One JSON snapshot per policy per collection. |
| Diff | DB trigger `fn_capture_policy_rule_history` (V120, replaced in V122/V132/V150) on `policy_snapshot` | Compares the new snapshot with `policy_rule_history_baseline`, writes one `policy_rule_history` row per added/modified/removed rule with the field diff in `changes`. **`changed_by` is copied from `rule.extras['last-modifier'][0]`; `changed_on` from `extras['last-modified'][0]`** (V150 lines 94-95). Rows are written inside the snapshot transaction; history is not modified afterwards (`PolicyChangeRepository` header comment). |
| Read | `PolicyChangeRepository` → `PolicyChangesService` (`out.put("changedBy", row.actor() blank ? UNKNOWN_ACTOR : row.actor())`, line 98; constant `UNKNOWN_ACTOR`, line 17) | `UNKNOWN_ACTOR` is shown whenever `changed_by` is null or blank. This is the Palo Alto case today. |
| Time | `fn_policy_change_time(changed_on)`; `observedAtCollection` | With no `changed_on` the screen shows the collection time and says "time observed at collection, not the change time". |
| Feed | `ChangeFeedService.policyRuleHistory` (actor from the same `changedBy`), `ChangeFeedService.evidence` (rows of `policy_change_evidence`), `ChangeFeedService.panConfig` (chunks of the firewall config log) | The note on the history source already says "the actor is the vendor's last-modifier where stored, else UNKNOWN". |
| Masking | `PolicyPrivacy` line 150: `changedBy` → `names.maskPolicyName("last-modifier", text)`; `EstateNameCatalog` line 374 classes `last-modifier` as a USER name | Any administrator name that reaches `changedBy` is already pseudonymized for aiview and masked views. |

### Comparison with Check Point (finding 1, second half)

| | Check Point | Palo Alto today |
|---|---|---|
| Actor source | `meta-info.last-modifier` and `last-modify-time` inside each rule of the `show-access-rulebase` answer (`CheckPointPolicyMapper` lines 186-187) | none in the rule |
| Where it lands | the rule's `extras` → trigger → `policy_rule_history.changed_by` / `changed_on`, plain text, masked at presentation | `changed_by` null → `UNKNOWN_ACTOR` |
| Extra evidence | `CpShowChangesReader` stores published-session summaries in `policy_change_evidence` with an admin **HMAC** only | `PanoramaConfigLogReader` stores config-log entries in the same table with an admin **HMAC** only, off by default |

So Check Point needs no join: the vendor puts the name on the rule. Palo Alto needs a join, and the one existing Palo
Alto reader stores the admin as a keyed hash, which cannot be shown as a name. That is the gap this design closes.

### What already exists for Palo Alto and is reused (not new)

- `PanoramaConfigLogReader` (`ui2/worker/.../diagnostic/`): reads the Panorama config log incrementally by
  `receive_time` cursor (seed once, then forward pages), redacts with `PanLogRedactor`, stores one
  `policy_change_evidence` row per entry (seqno, device-local time, admin HMAC, shape-checked command and result class,
  path HMAC, opaque device-group ref when the xpath names one). Off by default; reads only the one Panorama named by
  `UI2_POLICY_PAN_CONFIG_LOG_TRIAL_SOURCE` (PO partial-first rule, `PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md`
  §4). Whether the trial has run on the live system is a runtime fact this design did not read: UNKNOWN.
- The three gate rows `pan_policy_config_log_seed` (P-3), `pan_policy_config_log_enqueue` (P-2) and
  `pan_policy_log_job_get` (P-4): `SIGNED_OFF`, CLASS_0 read, scope `panorama`, created by migration V163 under the PO
  approval of 2026-10-18.
- For firewalls, the same literals under scope `pan_firewall` (`pan_syslog_collect_config_seed`,
  `…_config_enqueue`, `…_job_get`; V160, PO 2026-10-17) feed the stored `pan_config` chunks that
  `PanConfigEvents` turns into change events. The measurement of 2026-10-17 saw the field `admin` on all 39 firewalls.
- The ChangeFeed already has a source for them; what is missing is the link from an entry to a rule change.

## 3. Proposal in short

1. **Phase 1 — Panorama (device-group and shared rules).** Widen the existing Panorama config-log reader from one trial
   Panorama to the Panorama sources the PO names, with **no new command literal**: P-3, P-2, P-4 exactly as signed off.
   Add to what the reader keeps: the administrator's name for **configuration-change** entries only, and a keyed hash
   of the rule the entry's path names. Add a resolver step that links `policy_rule_history` rows to entries. The screen
   reads the resolved actor.
2. **Phase 2 — direct firewalls (rules local to a firewall).** No new literal either: the firewall config log is already
   collected (V160 rows). The same resolver reads the stored chunks. Limited by the measurement that `path` was present
   on only 6 of 39 firewalls (§8). Designed here, built after Phase 1 is measured.
3. **No new device command is required.** One optional narrower query is described in §7.4 and is **not** requested now.

## 4. How a log entry is joined to a detected change (finding 2)

### 4.1 Why the join is anchored on commits, not on collection time

The collector reads the **running** configuration (`action=show`). A rule appears changed in the next snapshot after the
**commit** that activated it. The administrator's `edit`/`set`/`delete` entry is written when the command is run against
the **candidate** configuration, which can be hours or days before the commit. A window of "since the previous
collection" would therefore miss the edit and, worse, could pick an unrelated later edit. The window is anchored on the
commit entries instead.

### 4.2 Algorithm (per `policy_rule_history` row H that is not an initial capture)

Inputs: H (container = the rule's section source, i.e. `shared` or a device group; phase pre/post; rulebase kind;
rule name; change type) and collection time C, the previous collection time P of the same policy (the baseline the
trigger compared against; how the resolver reads P is an implementation detail — UNKNOWN until built).

1. **Commit boundary.** Take the successful `commit` entries (`command_class = commit`, `result_class = Succeeded`) of
   the same Panorama with device time in (P − margin, C + margin]. None → outcome `UNKNOWN`, reason `NO_COMMIT_SEEN`
   (also the outcome when the reader reported `BACKLOG` for that run: the log was not read up to C).
2. **Edit window.** From the previous successful `commit` entry before the first commit of step 1 (call its time B) up
   to the last commit of step 1 (call it E). If no earlier commit is stored (evidence floor later than P), the window
   start is not covered, or lies more than 30 days before E: outcome `UNKNOWN`, reason `WINDOW_NOT_COVERED`. A partial window never produces a name.
3. **Candidates.** Entries in (B, E] from the same source with: `result_class = Succeeded`; a configuration-change
   command class (`add`, `clone`, `delete`, `edit`, `move`, `rename`, `set` — the documented `cmd` values); a rule
   reference equal to H's rule reference (§4.3); a command compatible with H's change type:

   | H change type | compatible commands |
   |---|---|
   | added | `set`, `add`, `clone`, `edit` |
   | removed | `delete` |
   | modified | `edit`, `set`, `move`, `rename` |

   `rename` carries two names (before/after) and neither is stored, so a rename can only match when the path names the
   old or new name; otherwise it is simply not a candidate.
4. **Outcome.**

   | Candidates | Outcome | Shown as |
   |---|---|---|
   | none | `UNKNOWN` (reason `NO_ENTRY`) | `UNKNOWN (observed by collection)` — as today |
   | one or more, all by the same administrator | `MATCHED` | that administrator's name (masked by the existing privacy layer) |
   | two or more different administrators | `AMBIGUOUS` | `AMBIGUOUS (n administrators before the commit)`; candidate names are **not** shown on the list (D6) |

   The commit's own administrator is a different fact (who activated the change) and is not used as the editor.

### 4.3 The rule reference (path → rule)

The entry's `path` (documented, up to 512 bytes) is a configuration path. Panorama's own policy reads use the same
path grammar (`…/device-group/entry[@name='DG-EXAMPLE']/…`), and `PanoramaConfigLogReader.DEVICE_GROUP_PATH` already
extracts the device group from it. For a rule edit the expected form (UNKNOWN until measured on Panorama, §8) is:

```text
/config/devices/entry[@name='localhost.localdomain']/device-group/entry[@name='DG-EXAMPLE']/pre-rulebase/security/rules/entry[@name='Rule-EXAMPLE']
/config/shared/post-rulebase/security/rules/entry[@name='Rule-EXAMPLE']
```

The reader computes the reference **in memory** from the path and keeps only
`HMAC(domain "policy-change-rule-v1", container-ref | phase | rulebase-kind | rule-name)`; the resolver computes the same
value from H and compares. A path deeper than the rule (an edit of one field of the rule) still resolves to the rule
entry. A path that is cut at 512 bytes before the rule entry, names no rule, or names an object outside
`pre-rulebase`/`post-rulebase` (an address, a service) gives **no reference** and is not a candidate. Rule **names**
are the join key because the log path names the rule by `@name`, not by `uuid`; a rule renamed inside the same commit
window is therefore the weakest case (§4.2 step 3).

### 4.4 Time basis

`receive_time` is the device wall clock without a zone (Phase B §4.7); the feed reads it as `Europe/Istanbul` for
ordering (`PanConfigEvents.ORDERING_ZONE`). The window arithmetic above stays **inside** the device clock (commit
entries to commit entries), so a wrong zone does not shift the edit window. The comparison of the commit entries with C
and P does cross clocks; it uses a margin of 5 minutes, and a commit entry that falls outside it is not used. The
sub-second field `high_res_timestamp` (PAN-OS 10.0 and later, ISO-8601 style; whether it carries a zone offset is
UNKNOWN) could remove the zone assumption; the measurement of §9 checks it.

## 5. Frequency, timeout and result size (finding 2)

Unchanged from the signed-off rows; the proposal adds **no** call:

- Runs inside the policy job, which is admitted by `JobWindowPolicy` (windows 00/06/12/18, Europe/Istanbul) and shares
  the six-hour `policy_collection_domain` throttle: at most one run per Panorama per six hours for automatic jobs.
- Per run: seed (P-3) only the first time; otherwise at most 4 pages of `nlogs=1000` (P-2), each page followed by P-4
  polls every 2 s, at most 30 per page. Serial, one Panorama API key already held by the policy job.
- Timeout 30 s per call, no retry. Result bound 8 MiB per page; `nlogs` bound 5000 (the documented maximum; the reader
  uses 1000). At most 4000 entries per run; more is reported `BACKLOG` and the newest changes stay `UNKNOWN` until the
  next run catches up.
- Failure is closed: an API error, an unfinished job or an unreadable answer is `UNKNOWN`, the cursor does not move and
  collection continues without the actor.

Volume: the 2026-10-17 firewall measurement found about 92 percent of config-log entries were `commit-all` and about
60 KB per day for the fleet. Panorama volume is UNKNOWN; if 4000 entries per six hours proves too small, §7.4 is the
remedy.

## 6. What is stored (finding 2)

| Item | Stored | Notes |
|---|---|---|
| Administrator name, change-class entries only | **new**: column `policy_change_evidence.admin_name`, nullable | Only for `add`, `clone`, `delete`, `edit`, `move`, `rename`, `set` with result `Succeeded` and a rule reference. Commits, validations, failed commands and other commands keep the HMAC only. Shape-checked (length ≤ 128, no control characters) else NULL = `UNKNOWN`. Needs a PO decision: it reverses, for this one column, the "HMAC, never names" rule of 2026-10-18 (D2). |
| Administrator HMAC | existing `admin_ref` | unchanged; still the correlation key |
| Rule reference | **new**: `rule_ref_hmac` (64 hex), nullable | §4.3 |
| Commit marker | existing `command_class = commit`, `result_class` | needed by §4.2; commit rows are already stored |
| Link | **new** table `policy_change_actor_link(revision_id → evidence_ids, state MATCHED/AMBIGUOUS/UNKNOWN, reason, resolved_at)` | One row per history row; written by the worker (it holds the fingerprint key; the service does not need it). `policy_rule_history` is **not** modified. |
| Not stored | raw entries, `before-change-detail`, `after-change-detail`, `before/after-change-preview`, raw path, rule names, comments | as today (`PanLogRedactor` rule set) |

Presentation: `PolicyChangesService` reads the link; `MATCHED` puts the name into `changedBy`, which `PolicyPrivacy`
already masks as a `last-modifier` USER name for aiview and masked views (§2). `AMBIGUOUS` is a fixed string without
names. The new columns and table need entries in the audit presentation allowlist (`AuditPresentationAllowlist`) and
the privacy tests; the Check Point `changed_by` precedent (plain text, masked at presentation) is the model. **Retention (PO D5,
2026-11-12): the name lives as long as the policy history row it explains.** Implementation: a link row references the
evidence rows and cascades with its `policy_rule_history` row; the resolver clears `admin_name` from evidence rows that
no link row references once they are older than the 30-day edit lookback (§4.2 caps the edit window at 30 days, so such a
row can no longer explain a change). Neither `policy_rule_history` nor `policy_change_evidence` has a retention job today.

## 7. Command literals and the network-device command gate (finding 3)

Gate fields from `docs/AI_DEVELOPMENT_PROTOCOL.md` ("Network-device command gate"): 1 why, 2 class, 3
vendor/platform/shell/context, 4 timeout, 5 retry, 6 maximum frequency per endpoint, 7 session reuse, 8 unsupported
behavior, 9 secret-bearing output risk, 10 safe telemetry. Transport for all: `PAN_XML_API`, HTTP `POST` to the XML API
with the form parameters shown, the policy job's own API key sent as the existing transport already does for the
policy reads (`PanoramaApiRoutes.KEY_HEADER_NAME`); the key is never logged or stored.

### 7.1 Literals requested for this feature

| Id | Literal | Gate id | Status |
|---|---|---|---|
| P-3 | `type=log&log-type=config&nlogs=<n>` | `pan_policy_config_log_seed` | `SIGNED_OFF` 2026-10-18 (V163); **scope widening requested** |
| P-2 | `type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')` | `pan_policy_config_log_enqueue` | `SIGNED_OFF` 2026-10-18 (V163); **scope widening requested** |
| P-4 | `type=log&action=get&job-id=<id>` | `pan_policy_log_job_get` | `SIGNED_OFF` 2026-10-18 (V163); **scope widening requested** |

The text is byte-identical to the signed-off rows. What changes is which Panorama sources the reader may read (today:
one trial source). Under `AGENTS.md` ("Network action taxonomy", PO amendment 2026-09-26) the PO's approval of the
exact literal is still the first decision (D1) because the use changes from a measurement trial to a standing read.

### 7.2 Gate fields (identical for P-3, P-2, P-4 except where noted)

| # | Field | P-2 (enqueue) | P-3 (seed) | P-4 (job get) |
|---|---|---|---|---|
| 1 | Why | read who/when/path of Panorama configuration commands to name the administrator of a detected rule change | establish the first cursor | fetch the asynchronous result of P-2/P-3 |
| 2 | Class | `utils/action_taxonomy.py` class 0 (read) | class 0 | class 0 |
| 3 | Vendor / platform / shell / context | `palo_alto` / `panorama` / `not_applicable` / `PAN_XML_API` | same | same |
| 4 | Timeout | 30 s | 30 s | 30 s per call |
| 5 | Retry | none | none | none |
| 6 | Max frequency per endpoint | ≤ 4 pages per Panorama per job window; automatic runs at most once per six hours; `<n>` digits 1-5000 (reader: 1000); `<ts>` matches `^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$` (stored cursor) | once per Panorama, first run only | every 2 s until FIN, at most 30 polls per enqueued job; `<id>` digits only |
| 7 | Session reuse | the policy job's existing Panorama API key; serial | same | same |
| 8 | Unsupported behavior | API error, unfinished job or unreadable answer → `UNKNOWN`; cursor unchanged; collection proceeds without the actor; a returned entry older than the cursor → `QUERY_NOT_HONORED` | API error → `UNKNOWN`, no cursor stored | not `FIN` within the bound → `UNKNOWN` |
| 9 | Secret-bearing output risk | administrator names, paths with device-group/object/rule names, before/after previews, possible secrets inside previews; redacted on write by `PanLogRedactor`; this design stores the name only for change-class entries (§6); masked for aiview; never in a transcript (`JobTranscriptScope.withoutRecording`) | as P-2 | the answer carries the same entries; handled identically |
| 10 | Safe telemetry | `entries`, `resultBytes`, `enqueueToFinMs`, `pages` (note `PAN_CONFIG_LOG outcome=… reason=…`) | `entries`, `resultBytes`, `enqueueToFinMs` | `jobStatus`, `entries`, `resultBytes` |

New telemetry of this design (counts only, no names): per resolver run, `linked`, `matched`, `ambiguous`,
`unknown` by reason code.

### 7.3 Phase 2 (direct firewalls): no new literal

The firewall rows `pan_syslog_collect_config_seed`, `pan_syslog_collect_config_enqueue` and
`pan_syslog_collect_job_get` (scope `pan_firewall`, `SIGNED_OFF` 2026-10-17, V160) already run on their own schedule and
store the redacted entries as chunks. The resolver would read those stored chunks; the device is not called again. The
same widening question does not arise because nothing is added to the firewall reads.

### 7.4 Optional narrower query — NOT requested now

If the measured Panorama volume makes §5's 4000-entry bound too small, a filtered form could keep only
change-class entries, for example `…&query=(receive_time geq '<ts>') and (cmd neq commit)`. It is mentioned only so the
PO knows a fix exists. It is a **new canonical key** that needs its own gate row and approval. Whether the filter
syntax accepts `neq` on `cmd` for the API (the Monitor-tab filter and the CLI `show log config` list `equal` and
`not-equal` on `cmd`), and whether the commit entries the algorithm needs (§4.2 step 1) would still be returned, is
`UNKNOWN`; the filter would have to keep commits, so a plain `neq` of one value is not enough. No literal is proposed
for approval.

## 8. Vendor documentation and UNKNOWN list (finding 3)

Official sources read for this design:

- Config log fields (PAN-OS 11.1 Admin Guide, "Config Log Fields"):
  <https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/monitoring/use-syslog-for-monitoring/syslog-field-descriptions/config-log-fields>.
  States: `cmd` is one of add, clone, commit, delete, edit, move, rename, set; `admin` is the administrator's user
  name; `client` is Web or CLI; `result` is Submitted, Succeeded, Failed or Unauthorized; `path` is the configuration
  path, up to 512 bytes; `before-change-detail` / `after-change-detail` exist in custom formats only; `seqno` is a
  64-bit counter with its own number space per log type; `dg_hier_level_1..4` and `dg_id` are device-group IDs
  (names resolvable with the `show dg-hierarchy` operation); `comment` is the audit comment of a policy rule change;
  `high_res_timestamp` exists from PAN-OS 10.0.
- API log retrieval parameters (PAN-OS 10.0 Panorama API guide; that release is end-of-life):
  <https://docs.paloaltonetworks.com/pan-os/10-0/pan-os-panorama-api/pan-os-xml-api-request-types/retrieve-logs-api/api-log-retrieval-parameters>.
  States: `log-type` includes `config`; `query` takes Monitor-tab filter syntax (URL-encoded); `nlogs` default 20,
  maximum 5000; `skip`; `dir=forward` oldest first, default backward; retrieval is asynchronous, `action=get` and
  `action=finish`. It states no rate limit and no result-size cap beyond `nlogs`.
- Retrieve Logs (XML API request types):
  <https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/retrieve-logs>. States: a
  request creates a job and returns its id; `action=get&job-id=` returns results only once the job is finished
  (`FIN`); the job is deleted after successful retrieval. It says nothing about Panorama versus firewall.
- Filter Logs (PAN-OS 11.1): <https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/monitoring/view-and-manage-logs/filter-logs>
  (attribute, operator and value form; `and`/`or`; time clauses).
- Running versus candidate configuration: `action=show` reads the active configuration and `action=get` the
  candidate. This is the repo's own documented use (`PanPolicyGates`); the vendor page was not re-read for this draft.

`UNKNOWN` (each is closed by the §9 measurement, never guessed):

1. Whether Panorama's config log writes the same field names as the firewall measurement (`admin`, `cmd`, `result`,
   `path`) — the plan section 8 UNKNOWN, still open.
2. Whether `path` on a Panorama entry names the device group and the rulebase (`pre-rulebase`/`post-rulebase`) and the
   rule `@name` in the form of §4.3, and for which commands. On the firewall measurement `path` and `full-path` were
   present on only 6 of 39 firewalls and 19 of 3,900 entries carried a changed path.
3. The `cmd` value of a Panorama commit (`commit` is documented; the firewall measurement saw `commit-all` for pushes,
   which the documented list does not contain).
4. Whether editing a rule by a field (for example its source) writes the path of the rule entry or of the field.
5. Whether `high_res_timestamp` carries a zone offset; the device clock's zone (the product assumes `Europe/Istanbul`).
6. Whether a PAN-OS "revert", "load" or "import" writes entries that the `cmd` list does not name; such changes would
   appear in a snapshot with no candidate (outcome `UNKNOWN`, safe).
7. Any API rate limit; Panorama config-log volume per six hours.
8. Whether `rule-modification-timestamp` (already read per firewall by the hit-count call and stored in
   `FirewallHits.modifiedAt`) could narrow the window per rule. It gives no administrator. Not used by this design.

## 9. Rollout, safety and verification

1. **Phase 0 — measurement, no new code path.** The existing reader is switched on for the one trial Panorama
   (`UI2_POLICY_PAN_CONFIG_LOG_TRIAL_SOURCE`, an operational setting the PO or an engineering session on request
   changes; nothing in this document changes it). Evidence rows already carry `command_class`, `result_class`, the
   opaque device-group ref and the path HMAC. One review of those rows answers UNKNOWN items 1-3 without any new command.
2. **Phase 1 — build** after the PO accepts the decisions below: migration (`policy_change_evidence.admin_name`,
   `rule_ref_hmac`, `policy_change_actor_link`; next free `V*` number at build time), reader extension, resolver,
   `PolicyChangesService` read, allowlist and privacy tests. Migration dry run on the live database inside
   `BEGIN … ROLLBACK` before any rollout (standing rule). No-fabrication tests: no entry, one entry, two administrators,
   edit before the window, failed command, truncated path, `BACKLOG` run, initial capture.
3. **Partial-first.** The resolver is off by default and starts on the trial Panorama; widening is a separate decision
   after the result is reviewed (same rule as 2026-10-18).
4. **Phase 2 — firewalls** only after Phase 1 is measured and only for firewalls where `path` is present.
5. **Never a write.** Every call is a CLASS_0 read; nothing here edits, commits or installs on a device.

## 10. Decisions needed from the Product Owner (finding 4)

| # | Decision | Recommendation |
|---|---|---|
| **D1** | **Approve the exact literals** of §7.1 for standing use on Panorama sources beyond the trial source: `type=log&log-type=config&nlogs=<n>` (P-3), `type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')` (P-2), `type=log&action=get&job-id=<id>` (P-4). Text unchanged; only the scope widens. | Approve; keep partial-first (one Panorama first). |
| D2 | Allow storing the **administrator name** (change-class entries only) in `policy_change_evidence.admin_name`, reversing the 2026-10-18 "HMAC only" rule for that one column. Without it the screen cannot show a name. | Yes; masked for aiview like the Check Point `last-modifier`. |
| D3 | Which Panorama is the first trial target, and when to run Phase 0 (an operational switch). | One Panorama, next window after approval. |
| D4 | `AMBIGUOUS` rule: two administrators edited the same rule before one commit — show `AMBIGUOUS (n)` (proposed), or show the **last** editor (a judgment, not a certainty). | Show `AMBIGUOUS`. |
| D5 | Retention of `admin_name` (same as the rest of `policy_change_evidence`, or a shorter period). | Same as evidence; confirm the evidence retention exists. |
| D6 | May the detail view (not the list) list the candidate administrators of an `AMBIGUOUS` change, to users who may see unmasked names? | Yes, detail only, audited like other unmasked reads. |
| D7 | Phase 2 (direct firewalls, stored chunks, no new command): build after Phase 1, or leave out. | After Phase 1, only if `path` is present. |
| D8 | §7.4 optional narrower literal: ask for it only if the measured volume needs it. | Do not ask now. |

## 11. Out of scope

Check Point (already has an actor), Fortinet and other vendors, NAT-rule actor semantics beyond what the rule
reference resolves, object (address/service) changes, any device write, and any change to how the snapshot or the
history trigger work.

## 12. Inputs read

`AGENTS.md` (command gate, privacy); `docs/AI_DEVELOPMENT_PROTOCOL.md` ("Network-device command gate"); `docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md` (§6, §8);
`docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md`; `docs/design/DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md` (§5);
`docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md`; migrations V120, V122, V132, V150, V163; the classes named in §2.
