# PO decision record -- 2026-11-13: zone matrix (segmentation policy), scope answers

## Status

RATIFIED -- PO answers given in chat on 2026-11-13 and relayed by the PO session. Recorded by the engineering session.
This record fixes four scope answers for the zone matrix feature. It is not implementation authority for anything
else: the contract `ZONE_MATRIX_CONTRACT.md` is DRAFT until it passes the decision council and the PO freezes it.

## Context

The PO chose the zone matrix (segmentation policy) as the first sector-leader feature of neXus because of its audit
value for BDDK. Comparable products: Tufin "Unified Security Policy", and the AlgoSec and FireMon equivalents. neXus
already collects Check Point and Palo Alto policies, resolves objects and services, and has the Trace lookup, the
shadowing/hygiene analysis and Rule object usage. The PO session asked the PO four scope questions; the answers below
are recorded with the PO's own words quoted.

## Decisions (PO's words quoted)

1. **Zone definition: "IP blokları".** Zones are defined by IP blocks, independent of vendor. Firewall zone names
   (Palo Alto zones, Check Point security zones, interface names) are not the zone definition. A zone is a named set of
   IPv4/IPv6 blocks that the bank maintains in neXus.
2. **First rule set: "BDDK".** The first rule set shipped with the feature is a BDDK rule set. SWIFT CSP and PCI DSS
   are not part of the first delivery.
3. **On a violation: "Liste + Overview".** A violation is listed on the zone matrix screen and on the Overview
   attention list, with an exportable evidence report. Nothing is written to any device: no rule change, no ticket, no
   device command.
4. **Change-to-ticket reconciliation: later, separate.** Matching policy changes to change tickets is a separate,
   later feature. The bank's ticket system is an in-house system called "smart". Recorded only; out of scope for the
   zone matrix.

## Consequences for the draft contract

- The zone model is address-based (decision 1); vendor zone names may be shown as context, never used to place an
  address in a zone.
- The BDDK rule set is a template whose regulation references are left empty for the bank's compliance team to fill
  and confirm (decision 2). neXus does not invent clause numbers or requirements.
- Outputs are read-only: screen, Overview row, evidence report (decision 3).
- No integration with "smart" and no change-to-ticket matching in this feature (decision 4).

## Decision council, 2026-11-13

The PO session convened the three-seat decision council on the contract draft (commit 012fbbf7): network/vendor
semantics, compliance and change-control, security and adversarial. This was a role-diverse review by one model
family, not independent external reviewers. The seat outputs are kept in the PO session's ops notes.

### PO decisions on the council outcome (PO's words quoted)

5. **Incorporate every council change: "Evet, hepsini işle".** Every verdict and acceptance criterion of the three
   seats goes into the contract (revision 2), renumbered into one sequence (`ZM-1`..`ZM-42`). The contract stays
   DRAFT until the PO freezes it.
6. **Four-eyes publishing: "Evet, şart".** A matrix version is published only with two different people, at least one
   of whom holds compliance_admin. The publisher has not edited the draft and has not confirmed a reference in it since
   the last publish. People are compared by immutable principal id. The approval is bound to the reviewed draft hash.
   The local root administrator and aiview never publish.

### Council verdicts, in short

- Rule-level evaluation, with a new upper-bound match function. A Check Point access role is never read as its
  networks for COMPLIANT.
- NAT dual view for destination and static NAT.
- Check Point ordered-layer witness check, and Apply Layer parents evaluated as an Accept upper bound.
- Collected Palo Alto default-rule overrides are evaluated (`DEFAULT_RULE`). The intrazone default and Panorama local
  rules are coverage items, and Panorama coverage is never complete.
- An IPv6 failure becomes "IPv6 not evaluated", and `any` is never COMPLIANT. `UNASSIGNED -> UNASSIGNED` is `DENY_ALL`.
- Headline: distinct open (policy source, rule uid) against the active version, never "0" or green on incomplete
  coverage. Staleness: `STALE` per policy.
- Split read rights: IP blocks and address lookup need their own audited right. The export follows the screen view,
  and raw export needs the Reports R1 raw right.
- References are confirmed only by compliance_admin and reset when the cell changes.
- No exceptions in delivery 1. The report says so, loosening changes need a rationale, and an exception record is
  required before external-auditor use.
- Reports are stored files and period-based, with a CSV formula guard. Retention is defined by the bank.
- No "BDDK compliant", "BDDK pass" or BDDK score anywhere. The template is named "neXus segmentation template
  (proposal)"; BDDK appears in a report title only when every referenced cell is CONFIRMED.
- Evaluation runs in ui2-service on its own pool, with durable runs, a transactional COMPLETE, and heap and time
  ceilings.
- Findings that become undecided stay open.

### Dissent and its resolution

The seats disagreed on rules covered by an earlier rule. The vendor seat wanted them outside the critical count; the
compliance and security seats wanted them inside, made visible. The PO session resolved this as follows:
- Rules flagged `COVERED_BY_EARLIER_DENY` (only on a proven hygiene conflict, covering rule id recorded) stay counted in
  the headline. They are shown as a separate split: "C critical, of which L covered by an earlier deny".
- Rules flagged `DENIED_BY_OTHER_ORDERED_LAYER` or `OTHER_ORDERED_LAYERS_UNKNOWN` get the same split display but stay
  outside the headline, as the vendor seat proposed.

### Revisit triggers

- Check Point documents the order of access and NAT evaluation (dual view may be simplified).
- Check Point implicit cleanup values, or Panorama local rules, become collectable (coverage items and
  `OTHER_ORDERED_LAYERS_UNKNOWN` shrink).
- Per-family address resolution or IPv6 evaluation lands in the engine.
- The delivery-step-3 measurement shows identity-, time-limited or covered rules dominate the headline.
- The bank needs the report for an external auditor (the exception record becomes a precondition).
- The "smart" ticket reconciliation feature starts (decision 4).

### Bank inputs still needed

- The regulation's title and date (and version) that the references cite.
- The retention period for runs, findings and stored reports.
- The snapshot staleness threshold.
- Which team holds compliance_admin, and whether that role is bound today; publishing is impossible without it.

## Not decided by this record

The freeze of the contract itself, and the items the contract lists as still open (section 11.2).
