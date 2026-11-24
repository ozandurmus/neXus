# PO decision record: the 2026-11-23 midday answers that govern the P1 items (2026-11-23)

**Status:** RATIFIED -- Product Owner, chat 2026-11-23, authorization ref PO-2026-11-23-ayyas-midday.
Applies to sections 1-2 only. Sections 3-4 are not part of the ratified claim (an engineering note and a verification
statement); section 5 lists open consequences. This record supersedes nothing (section 6).

Backlog items it serves: `zone_matrix_delivery`, `rule_recertification_campaigns` / `recertification_council`,
`policy_collection_gaps`, `script_execution_module`, `asa_fortigate_policy_model`, `aiview_masking_leak_audit`.
The answers were given in chat; this record makes them findable from the repository alone (AGENTS.md authority
hierarchy, item 7).

## 1. Answers

1. **Zone matrix STALE threshold (interim).** Until the bank states its own value, a policy's stored snapshot is `STALE`
   when it is older than 24 hours. This is an interim value set by the Product Owner, not the bank's value: the product
   labels it as interim wherever it shows or exports the threshold, and never presents it as a bank input
   (`ZONE_MATRIX_CONTRACT.md` 6.2 item 9 stays a bank input; 5.9 defines `STALE`). It stays interim until the bank
   answers. Governs the staleness of finding lifecycle (5.9) and the `CURRENT`/`STALE` coverage and report columns
   (7.1, 7.3). Contract text: `ZONE_MATRIX_CONTRACT.md`, "Amendment 2026-11-23 (interim staleness threshold)".
   Implementation: by the zone matrix step 4a lane (durable runs, finding lifecycle and staleness, contract 10.1
   step 4); no staleness code for it exists on main yet.
2. **Cisco ASA `ACL_NOT_BOUND`.** The verdict means: an access list referenced by no access-group and by no other
   parsed feature (NAT, crypto map, VPN filter, class-map, route-map). If the configuration holds a feature of that
   kind that the parser does not read, the verdict is UNKNOWN, never `ACL_NOT_BOUND`. Governs the "unused ACL" finding
   of the ASA policy model. Normative text and implementation are already on main: section 16 of
   `PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md` (FROZEN, appended); this record is a
   cross-reference only.
3. **VSX "fail over all virtual systems" stays sequential**, one VS after another, never in parallel. Already recorded
   in `PO_DECISION_RECORD_2026_11_23_VSX_VS0_INDEPENDENT.md` section 2 item 6; cross-reference only, nothing new
   decided here.
4. **Recertification council.** The PO session convenes the decision council for the recertification contract
   (`RULE_RECERTIFICATION_CONTRACT.md`) once the recertification contract refresh is on main. No engineering session
   convenes it. Governs `recertification_council` and `rule_recertification_campaigns`.
5. **PAN-OS below 12 zone sets** (`PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md` section 6, "Limits"). Measure first: counts
   only, inside the pod, no device command. Whether the stored-document read is widened to every firewall waits for
   those counts; until then that widening is not decided and the "Limits" paragraph stands. Governs
   `policy_collection_gaps`. Implementation of the measurement: by a measurement lane, counts only.
6. **Script Execution real-environment validation.** The PO runs the real-environment validation of slices 1-3 with a
   harmless non-device script once slice 3 is live. No lane does that, and no device is involved. Governs
   `script_execution_module`; the item's status does not advance to real-environment validated before the PO reports it.

## 2. What the answers do not change
- No command, gate row, frequency or command context is added or changed by any answer above.
- The interim threshold of answer 1 is not a bank value and is never entered into a bank-input field by the product.

## 3. Engineering defaults (NOT Product Owner decisions)
Taken by engineering without asking; the Product Owner may overrule either. They are outside the RATIFIED claim.
- (a) The zone matrix is amended for ASA and FortiGate coverage only after zone matrix step 4 has shipped.
- (b) The masked read-model idea for the aiview persona is decided after the result of the aiview raw-versus-masked
  sweep is known.

## 4. Verification statement (Product Owner; not a decision)
The Product Owner verified the first scheduled MDS export: 10 runs COMPLETED since 2026-11-18, 1 FAILED before the fix.
This statement does **not** cover the ASA saved-state half of `verify_first_scheduled_mds_export_and_asa_saved_state`;
that half is not verified by it and stays open.

## 5. Open consequences
- Zone matrix step 4a must show the interim label for the 24-hour threshold and keep the value replaceable by the bank's.
- The aiview sweep result decides default (b); the PO's reply to default (a) or (b) supersedes it.
- The PAN-OS counts of answer 5 are not yet taken; the widening decision follows them.
- The PO's Script Execution validation of answer 6 is pending slice 3 being live.
- The ASA saved-state verification (section 4) is open.

## 6. What it supersedes
Nothing. It adds a dated interim value to `ZONE_MATRIX_CONTRACT.md` by appended amendment (no existing text edited) and
cross-references two earlier records.
