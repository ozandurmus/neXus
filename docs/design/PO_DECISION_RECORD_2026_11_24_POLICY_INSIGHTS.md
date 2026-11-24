# PO decision record: Policy Insights (2026-11-24)

Status: RATIFIED (PO in chat, 2026-11-24: "Yes, accept all").

The PO accepted every recommended answer in `POLICY_INSIGHTS_DESIGN_2026_11_24.md` section 10 and froze the design.

| Q | Decision |
|---|---|
| Q1 | First vendor: Check Point. |
| Q2 | Reorder across a different security profile is offered, labelled "verdict-safe, inspection changes", never shown as "no behaviour change". |
| Q3 | Rules the engine cannot read (user, application, time) are fixed anchors; nothing moves across them. |
| Q4 | Hit-counter samples are kept at each collection. This adds no new device command; the storage contract comes with phase P3. |
| Q5 | A narrowed broad rule stays as the last rule of its group, with logging on, for one more window; then it is removed. |
| Q6 | Uploaded log tuples are kept 90 days, encrypted, and deleted on demand. Limits are set after a memory measurement on one real export. |
| Q7 | Export: a human-readable change list first. Vendor command scripts need a separate later decision; policy writes stay unapproved. |
| Q8 | G1 engine: interval-box arithmetic with a budget. Budget exhaustion is NOT_PROVEN. Revisit after P2 measures how often the budget is hit. |
| Q9 | Never cross a layer. Cross a section only as an explicit user choice. |

The PO's intent, in the PO's words: `POLICY_INSIGHTS_BRIEF_2026_11_24.md` section 1.

Delivery starts with phase P1, the Check Point Insights tab from existing engines, no logs.

Contradictions the design reports in section 11 are follow-ups, not blockers:
- a missing hygiene-modes design file that the code cites;
- a stale work-budget figure.
