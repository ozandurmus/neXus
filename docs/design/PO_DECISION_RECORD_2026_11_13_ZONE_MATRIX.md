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

## Not decided by this record

Everything in the contract's open-questions section (rule-level vs flow-level evaluation, NAT, rules shadowed by an
earlier deny, identity-limited rules, overlap rule, edit and publish rights, retention, exceptions). These go to the
decision council convened by the PO session, then to the PO.
