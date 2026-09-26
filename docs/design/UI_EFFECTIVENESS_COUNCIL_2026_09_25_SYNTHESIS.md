# UI effectiveness council — engineering synthesis (2026-09-25)

**Status:** RATIFIED — Product Owner decisions recorded below (2026-09-25; recorded 2026-09-26). The analysis sections
remain the council's input. Inputs: `UI_EFFECTIVENESS_COUNCIL_2026_09_25_ASTRA.md` (gpt-6-astra,
codex exec, one author speaking six seats), `UI_EFFECTIVENESS_COUNCIL_2026_09_25_FABLE.md` (claude-fable-5-1, review of
Astra), 19 aiview screenshots (kept outside the repository). Synthesis by the engineering session (Opus).

## Product Owner decisions (2026-09-25, RATIFIED)
1. **One device screen.** Inventory and Configuration are tabs of the selected device or cluster; the Config screen goes.
2. **Overview is an executive summary**, with no operational noise (job failures, "could not reach the device").
   Final design: `EXEC_OVERVIEW_DESIGN_2026_09_25_FABLE.md`.
3. **Each vendor in its own terms: data a vendor or device does not have is not shown** — no empty tab, chip, column
   or step for a kind of data that does not exist for that device (the PO's words: "if a vendor lacks data it should
   not be shown").
4. **Cisco ASA: both backups**, the configuration text and then the archive; a failing part shows as missing and is
   completed later (`CISCO_ASA_CONTRACT.md`).
5. The five engineering proposals of this synthesis are approved as the UI direction.

## Agreed by both reviewers
- One device screen: Devices keeps one tree; Inventory and Configuration become tabs of the selected device or
  cluster. The data planes, their read jobs and timestamps stay separate (AGENTS.md). The Config nav item goes.
- Compliance and Backups stay fleet screens; the device screen shows that device's compliance and backup status
  and links to them.
- Onboarding flow: approve with changes (below). ASA: keep the text backup; the flash archive is out of scope.

## Where they differ, and the engineering view
| Topic | Astra | Fable | Engineering view |
|---|---|---|---|
| Overview | Eight defined measures | Three plain triage sentences; drop the duplicate cards | Fable: the PO's complaint is "too technical" |
| Biggest cause of "mixed" | Two device trees | Seven different counts of one estate | Both; the counts first, they are cheap |
| Admin device list | Retire | Keep as a registry without browsing | Fable |

## Verified before writing (live DB, 2026-09-25)
- 110 backup targets are all ENROLLED (Fable's "two drafts" guess is wrong). The Backups table showed 108 because
  its device filter left out two management servers: the Blue Coat MC (fixed in 95cbe9d) and Panorama (still out).
- "Live" = identity verified AND at least one interface read (InventoryScreen); 102 of 110.
- The Operations job log leaked real names to aiview (JobPage record passed the masking advice) — fixed in 95cbe9d,
  verified live.

## Onboarding changes requested
1. "Completed · configuration not available for this vendor" instead of implying full coverage (PO to accept the
   wording; the contract's acceptance line is amended accordingly).
2. After submit, land on the new device in Devices; the progress shows there.
3. Stopped reason as visible text, not hover; a filter for stopped onboardings.
4. Dialog: no "Create credential" link without permission; drop "pilot allowlist" wording.
5. Status stays AUTOMATED_VALIDATED until a new add, a batch import and a browser close are shown under aiview.

## ASA
Split the contract: reads + text backup FROZEN; the archive step a separate DRAFT. The text backup is labelled
"Configuration text (running + startup) — certificates, VPN images and profiles not included" and flagged
secret-bearing (never rendered raw in Contents/Compare). neXus never runs `ssh scopy enable`.

## Proposed phases
1. Words and counts (small): one count vocabulary; plain failure causes; Not read yet / Read failed / Not applicable;
   label rewrites; defects listed by both reviewers.
2. One device screen: turn "This cluster in: Inventory · Configuration · Backups · Readiness" into tabs; remove the
   Config tree; header with read ages and one "Read now" action.
3. Overview first screenful rewritten; charts move to Devices.
