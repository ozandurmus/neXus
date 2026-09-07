---
name: nexus-decision-council
description: Bounded multi-seat adversarial review of one freeze candidate or authority contradiction, run as parallel fresh-context seats. Invoke only from a nexus-po PLAN or DECIDE episode when GOV.PO.1 section 7 triggers; never from an engineering session.
---

# nexus-decision-council — bounded seat review

Authority: `docs/design/GOV_PO_ROLE_MIGRATION.md` §3, §7, §8. The council is
an instrument, not a role: it never decides, never replaces
real-environment evidence, and is never invoked by the engineer.

## Triggers (all must be checked, one must hold)

(a) a contradiction between two authorities; (b) a freeze candidate that
introduces a security, identity, credential, storage-schema or write
boundary; (c) a freeze candidate the Product Owner judges too large or too
coupled to review in one sitting even after slicing.

## Seats

Choose the smallest relevant subset of the `PROJECT_VISION.md` "Product
decision lenses" (Senior Python Architect, Network Security Engineer,
Check Point / VSX Engineer, Palo Alto Engineer, Multi-vendor Automation
Engineer, Configuration Management Specialist, Data / Inventory Architect,
DevSecOps / Platform Engineer, Security Reviewer, Test Automation Engineer,
UI/UX Product Designer, Technical Product Owner, Network/Security Manager,
Business/Executive stakeholder). Security Reviewer is always seated when
trigger (b) holds. Three to six seats is normal.

## Procedure

1. Write one brief per seat: the exact document under review (path and
   section range), its parent authority, the seat's protected concern, and
   the question "what in this candidate would you refuse, and what evidence
   would change your mind?".
2. Launch every seat **in the same message** with the `nexus-council-seat`
   agent definition (fresh context; read-only; cannot spawn agents), one
   brief each. Never pass this session's conversation to a seat.
3. Collect each seat's table: `consent` rows, `dissent` rows (id, claim,
   evidence path, what would resolve it), and `questions for the Product
   Owner`.
4. Synthesize in this session: supported consensus; material dissent left
   unresolved with the seat that holds it; the exact questions that need a
   human decision. Do not resolve a dissent by majority.
5. Record the round with the honest disclosure pattern: which seats ran, as
   fresh same-model-family contexts, with no cross-model independence
   claim, and where the transcript evidence lives (hook log). Historical
   single-author self-critique records stay described as such.

Return the synthesis to the invoking `nexus-po` episode. The council output
enters a decision only through that episode's §3 procedure.
