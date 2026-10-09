# PO decision record — 2026-10-09: NSPM-class analysis, wave 1

**Status: RATIFIED — PO APPROVED (chat, 2026-10-09: "Ok devam edelim").**
Recorded by the engineering session from the Product Owner's explicit chat decision. The input was a review of a
competitor network-security-policy-manager product. The engineering session produced a prioritised list from it,
and the Product Owner approved that list.

## Decisions

1. **Wave 1 (approved for implementation now).**
   - **W1-A Policy hygiene.** Computed analysis over the policy snapshots neXus already collects (Check Point and
     Palo Alto). Classes:
     - shadowed rules;
     - disabled rules;
     - unused rules (zero hits, or last hit older than a threshold);
     - expired rules;
     - broad rules (any/all or large CIDR).

     Each rule's permissiveness verdict is explained (which selector contributed what). The UI fields that
     today read "requires analysis" (Shadowed, Violations) are filled from this analysis.
   - **W1-B Lifecycle and support.** A fleet view of hardware and software end-of-sale / end-of-support milestones,
     plus the license and support expiry dates neXus already collects.
     - Milestone dates come only from an operator-imported vendor catalog, with recorded provenance.
     - The product never invents a date.
2. **Wave 2 (approved direction, later dispatch):**
   - remediation guidance per compliance finding (shown as text, never applied);
   - an estate-wide change report.
3. **Wave 3 (approved direction, later dispatch):**
   - policy lookup ("does A reach B on port P, and which rule");
   - an offline analyzer over operator-uploaded SIEM CSV.
4. **Rejected:**
   - writing rules to devices ("live apply");
   - restore from the UI;
   - an LLDP topology map;
   - a live log-ingestion pipeline;
   - SIEM-style threat queries;
   - compliance percentage cards without evidence.

## Constraints

- No new device command in wave 1. All analysis runs over already-collected data.
- UNKNOWN / fail-closed:
  - a rule whose objects are unresolved or negated, or whose hit counts were not collected, is reported as
    `UNKNOWN` for that class, never as clean;
  - a device with no catalog match shows "no lifecycle data", never a guessed date.
- Masked (aiview) viewers see pseudonyms only, as on every other screen.
- One fleet mutation rule, job windows and the command gate are untouched.
