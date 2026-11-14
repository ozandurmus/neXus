# PO decision record -- 2026-11-14: Rule recertification, product answers

**Status: RATIFIED -- PO APPROVED (chat, 2026-11-14, relayed by the PO session) for decisions 1-3; decisions 4-9
are PO session recommendations the PO saw and did not object to.** The contract they apply to,
[RULE_RECERTIFICATION_CONTRACT.md](RULE_RECERTIFICATION_CONTRACT.md), stays DRAFT: it goes to the decision council
for its open questions (section 14.3) and then to the PO for freezing. This record decides product parameters only;
it is not implementation authority for the contract.

## Context

The rule recertification contract draft (revision 1, 2026-11-13) listed eleven product questions for the PO. The PO
answered three directly. For six more the PO session gave a recommendation that the PO saw and did not object to; they
are recorded here as recommendations, not as PO quotes. Two questions wait for the bank.

## Decisions by the PO

1. **Q1 Review cadence.** PO: "Yılda bir + riskliler 6 ay". Every security rule is reviewed once a year; high-risk
   rules every 6 months. High-risk rules are any-any rules, internet-facing rules and rules that violate the zone
   matrix. A KEEP decision is valid for at most 12 months (at most 6 months on a high-risk rule). The exact test for
   "internet-facing" is put to the decision council (contract Q20).
2. **Q4 Unused threshold.** PO: "180 gün". A rule is an unused cleanup candidate when its last hit is older than 180
   days. Rules whose hit-counter window is unknown (including "zero collected hits, counter window unknown") do not
   enrol; they are listed as "could not be evaluated".
3. **Q13 Bulk KEEP.** PO: "Evet, en fazla 50". An owner may confirm several rules in one operation, at most 50 per
   operation; each rule gets its own evidence record; high-risk rules are excluded from bulk confirmation.

## PO session recommendations the PO did not object to

4. **Q5** Legacy-mode (upper-bound) hygiene results are shown as a labelled "possible" list that never enrols a rule.
5. **Q10** A rule disabled instead of removed keeps its removal request open until it is removed; disabled rules are
   in scope of periodic review.
6. **Q11** A KEEP invalidated by a rule change joins the open periodic campaign of its scope as a late item.
7. **Q12** An item's due date may be extended once without four-eyes; further extensions need a second person.
8. **Q15** Reminders go to one team mailbox per campaign; owners carry no mail address.
9. **Q17** The auditor report is masked by default; raw content only with `report_raw_view`.

## Still open

- Bank input: Q6 (where owners are recorded today) and Q16 (evidence retention and the audit / BDDK reference for the
  review cycle).
- Decision council: Q2, Q3, Q7, Q8, Q9, Q14, Q18, Q19, and Q20 (definition of internet-facing).

## Effect

Contract revision 2 applies decisions 1-9 in sections 3.1-3.3, 4.1, 5.3, 6.1, 6.4, 6.5, 7, 8.1, 8.2, 9, 11.1, 12, 13
(`RC-16` to `RC-21`) and 14. No code, migration, device command or notification change is made by this record.
