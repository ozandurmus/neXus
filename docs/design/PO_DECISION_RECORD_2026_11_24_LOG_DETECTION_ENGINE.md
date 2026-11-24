# PO decision record - 2026-11-24: system-log detection engine

**Status: RATIFIED - PO DECISION (chat, 2026-11-24 ~21:55).** Freezes
[LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md](LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md).

PO direction before the design (translated): decisions should not depend only on scenarios (a disk or DIMM failure is
rare); severity templates (error, warning) must be captured as they are; months of logs from about a hundred devices
are the data to build the engine from. Trigger case: a Check Point gateway's stored `messages` showed 45 EDAC corrected
memory errors on one DIMM slot and machine checks after two unexpected boots; Overwatch raised nothing.

PO answer to the frozen design's §12: "Hepsini kabul, FROZEN" -- every recommendation accepted:

| # | Question (§12) | Decision |
|---|---|---|
| 1 | Hardware findings on a pair with under 7 days of history | Yes, labelled "history N days"; never a model question |
| 2 | Where the job runs | ui2-service now, enqueued on window close; analysis container later on measured load |
| 3 | Composite score | Yes, `100 x S x R x C`, for ordering only; severity from rules; statistics never CRITICAL |
| 4 | Alert budget and precision targets | The PROVISIONAL values of §6.2 and §7.2, reviewed after the P2 backtest |
| 5 | Raw retention during learning | Unchanged; counting starts in P0; a temporary raise only if the census shows disk headroom |
| 6 | Template text storage | Vocabulary form only |
| 7 | Notifications | None through P3; after the P3 check, CRITICAL memory, disk and unexpected-reboot cards go to the existing forwarder |
| 8 | Precision sample labelling | PO and an engineering agent, independently, through the aiview projection |
| 9 | Pool statistics vs the 2026-11-10 non-goal | Outside it: deterministic counts and medians with a frozen catalog, kept on HOST-A; the non-goal is read that way |
| 10 | Prevalence cohort | Vendor and source in P2; model family later if the census shows distortion |
| 11 | Severity of corrected memory errors | ATTENTION; CRITICAL on UE, a reboot or the trigger combination |

Not decided here: any new device command, any outbound connection (P4 still follows the 2026-11-10 decisions and needs
its security review), raw-retention changes.
