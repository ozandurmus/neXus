# Brief: effectiveness review of the navigation and Administration redesign (Argon, 2026-11-12)

You are reviewing, read-only, a redesign of the left navigation and the Administration area of neXus, an on-premises
operations platform for a bank's firewall estate (Check Point, Palo Alto and others). Product principle:
SEE -> VERIFY -> TRACE -> RECOVER -> OPERATE. The Product Owner (a security expert, not a software engineer) said the old
menu had become a tangled mess. Screens are judged by the purpose they serve, not by feature count.

Read:
- `docs/design/NAVIGATION_REDESIGN_2026_11_12.md` (the approved design; sections 2 to 5 at least).
- `docs/design/PO_DECISION_RECORD_2026_11_12_NAVIGATION_AND_ADMINISTRATION_REDESIGN.md`.
- `ui2/frontend/src/shell/navModel.ts` (the one navigation model as built).
- `ui2/frontend/src/shell/NavigationRail.tsx` and `ui2/frontend/src/screens/AdministrationScreen.tsx` (as built).
- For the old shape: `git show c1fdc4d6^:ui2/frontend/src/shell/NavigationRail.tsx` and
  `git show c1fdc4d6^:ui2/frontend/src/screens/AdministrationScreen.tsx` (the parent of the navigation commit).

Answer, concisely (at most about 1,200 words):

1. For each of the nine top-level entries and each child: does it serve one clear operator purpose, and is the operator
   question in design section 2 the right one? Verdict per entry: CLEAR / UNCLEAR / MISPLACED, one line of reason.
2. Is anything still reachable only by typing an address, duplicated in two places, or hidden in a way an operator would
   not guess? Name the entry and where.
3. Administration: does each of the five pages have one purpose, and are its tabs the right split? Any tab that belongs
   elsewhere?
4. Naming: any label an operator in a bank's network-security team would misread?
5. Role gating and locks as built: any case where a session sees an entry it cannot use without a lock, or loses an
   entry it used to have? Plane gates must be unchanged from the previous rail.
6. Your top 5 follow-ups, in priority order, each with impact and effort (S/M/L). Mark anything you cannot judge from
   code and documents alone as UNKNOWN; do not invent behaviour.

Rules: never write raw IPs, real hostnames or serials. English. Do not edit any file.
