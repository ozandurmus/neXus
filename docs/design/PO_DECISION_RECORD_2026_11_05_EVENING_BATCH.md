# PO decision record - 2026-11-05 evening: five open decisions approved as a batch

**Status: RATIFIED - PO DECISION (chat, 2026-11-05 evening).**

## PO answer (verbatim)
"Abi hepsini sıraya koy ve arka arkaya yap" ("Put them all in order and do them one after another").

The answer followed the PO assistant's list of five open decisions, in which item 1 quoted the exact command literal
below. It approves each item exactly as listed and nothing beyond it.

## Decisions

1. **Check Point NAT rulebase read by package UID (new `CLASS_0` gate).** After the probe outcome `UID_ACCEPTED`
   (2 of 2 packages, see `PO_DECISION_RECORD_2026_11_05_CP_NAT_UID_PROBE.md`), the full paged read by package UID
   replaces the failing by-name read, with this exact literal:

   `mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 500 offset '<N>' details-level standard use-object-dictionary true`

   - Trigger: only after the package's by-name NAT read fails with `VENDOR_NAME_NOT_UNIQUE` and the package UID is
     known from `show-packages`; never for another failure code.
   - Paging, timeout, retry and frequency are those of the by-name NAT read it replaces, in the same session.
   - The content is the package's NAT rulebase and is mapped like a by-name read. A vendor error on the first page
     leaves the NAT section `UNKNOWN` (`VENDOR_NAME_NOT_UNIQUE`), as today.
   - Gate migration: V192 (Reports R2 moves to the next free number).
2. **X1 ratified** (`POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md` §1 X1): a partial prior is passed over when one of
   its resolved dimensions provably does not contain the later rule's, which is the "cannot cover" criterion full
   priors already use. The release criterion is the plan's `PartialPriorOracle.assertNoCover`, run as a separate
   path next to `assertReleaseIsSafe`.
3. **A6 device validation sample:** the PO delegates the choice of the sample flow to the PO assistant. The approved
   A6 literals (`PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md` rows 1-5) and their bounds are unchanged: one run per sample,
   validated parameters, enum-only outcome.
4. **Notification identity default `masked`** (`NOTIFICATION_IDENTITY_EXPOSURE_2026_11_05.md` "Proposed option"):
   - a setting `notification_identity` (`masked` | `raw`), default `masked`, applies to e-mail and syslog;
   - free-text fields are masked as well;
   - switching to `raw` is a `security_admin` action, audited;
   - if the pseudonymizer is unavailable, a fixed label is sent.

   The settings migration is V193.
5. **aiview may start the inventory collection:**
   - `device_inventory_collect` (one device or all enrolled devices) gains `replay_viewer` as an alternative role and
     joins the machine-session POST allow-list, like `policy_collect`;
   - it remains a `CLASS_0` read of already approved commands;
   - configuration collection, device register and delete stay refused to a machine session.

## Also queued from the same list (no approval needed)
- A vendor-semantics review of an upper bound for Check Point gateway objects used as rule source or destination
  (23 blockers behind 18,624 rules, plan §0.1). Review only, no semantics change without its own decision.
- Analysis-container M1 under load and M9.

## Cross-references
- `docs/design/PO_DECISION_RECORD_2026_11_05_CP_NAT_UID_PROBE.md`
- `docs/design/POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md`
- `docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md`
- `docs/design/NOTIFICATION_IDENTITY_EXPOSURE_2026_11_05.md`
