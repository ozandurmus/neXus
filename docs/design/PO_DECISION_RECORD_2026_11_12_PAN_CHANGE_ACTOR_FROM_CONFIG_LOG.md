# PO decision record - 2026-11-12: Palo Alto change actor from the Panorama config log

**Status: RATIFIED - PO DECISION (chat answers, 2026-11-12).** Recorded by the engineering session from the Product
Owner's answers, relayed verbatim by the PO session. It decides the open questions D1 to D8 of
`PAN_CHANGE_ACTOR_FROM_PANORAMA_CONFIG_LOG_2026_11_11.md` (the design, which stays DRAFT; see section 4).

## 1. The Product Owner's answers (verbatim, with English rendering)

| Decisions | Answer | English rendering |
|---|---|---|
| D1, D3 | "Evet, önce tek Panorama" | "Yes, one Panorama first." |
| D2, D5 | "Evet, policy geçmişi kadar" | "Yes, as long as the policy history." |
| D4, D6 | "AMBIGUOUS + adaylar" | "AMBIGUOUS plus the candidates." |
| D7 | "Panorama'dan sonra" | "After Panorama." |
| D8 | not asked | no new literal is requested |

## 2. Decisions

| # | Decision |
|---|---|
| D1 | The three existing Panorama config-log literals are approved for standing use, text unchanged: `type=log&log-type=config&nlogs=<n>` (gate `pan_policy_config_log_seed`), `type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')` (`pan_policy_config_log_enqueue`) and `type=log&action=get&job-id=<id>` (`pan_policy_log_job_get`). **Only on the current trial Panorama** (`UI2_POLICY_PAN_CONFIG_LOG_TRIAL_SOURCE`). Widening to other Panorama sources is a separate decision, taken after the first measurement confirms the three open vendor semantics: the `path` form, the `cmd` value of a commit, and the timestamp basis. |
| D2 | The administrator name may be stored in `policy_change_evidence.admin_name`, for change-class entries only (`add`, `clone`, `delete`, `edit`, `move`, `rename`, `set`, result `Succeeded`, with a rule reference). Commits, validations, failed commands and every other entry keep the keyed hash only. aiview sees the name masked, through the existing `last-modifier` masking. |
| D3 | The first target is the current trial Panorama; no second Panorama is read. The first live run is one measurement on it, counts only. |
| D4 | When two or more different administrators edited the same rule before one commit, the screen shows `AMBIGUOUS (n administrators before the commit)`. It never picks one. |
| D5 | The stored name is kept as long as the policy change history row it explains (it is deleted with, never before or after, that history). The retention of the rest of `policy_change_evidence` is not changed by this record. |
| D6 | The detail view lists the candidate administrators of an `AMBIGUOUS` change, for authorised roles only (those that may read unmasked names, audited as other unmasked reads); the list and every masked view show the count only. |
| D7 | The direct-firewall phase (stored `pan_config` chunks, no new command) is built after Phase 1 and is measured separately. It is not authorised by this record to be built now. |
| D8 | No narrower literal is requested. |

## 3. What this record does not decide

- It adds no device command; every call is the existing policy job's, class 0 read, under the three signed-off rows.
- It does not change the vendor UNKNOWNs of design section 8. Until the first measurement settles them, the implementation
  fails closed: a path, command or time that does not have the expected shape gives `UNKNOWN`, never a name.
- It does not switch the reader on in production. The reader is off unless the trial setting names the Panorama; turning
  the setting on is an operational act, and the first run is one measurement.

## 4. Status of the design document

The design stays `DRAFT` (Contract-status law): its vendor semantics (design section 8, items 1 to 8) are not yet
established, so it must not be read as freezing them. Implementation of Phase 1 rests on this decision record, which
authorises the scope, the storage column and the retention, and on the fail-closed behaviour above; it does not rest on
any unresolved semantic. The design is to be frozen only after the first measurement, by a later amendment.

## 5. Enforcement

- Migration V208 (`policy_change_evidence.admin_name`, `rule_ref_hmac`, `policy_change_actor_link`), with the live-database
  `BEGIN ... ROLLBACK` dry run before rollout.
- `PanoramaConfigLogReader` keeps the name only under D2's conditions; `PolicyChangeActorResolver` links only under the
  design's section 4.2; `PolicyChangesService` shows `MATCHED` names through the existing masking and `AMBIGUOUS` as a
  count, candidates in the detail view only.
- Tests pin: no name for a commit or a failed command; no link without a covering window; two administrators give
  `AMBIGUOUS`; the list never carries candidate names.
