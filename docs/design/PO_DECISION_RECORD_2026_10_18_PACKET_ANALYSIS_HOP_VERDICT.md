# PO decision record 2026-10-18 — Packet Analysis hop markers "temiz" / "gecikme burada"

## Status

**RATIFIED — PRODUCT OWNER, 2026-10-18**, in the chat question form, relayed to the implementing lane: the Product Owner
approved the hop verdict proposal `docs/design/PACKET_ANALYSIS_HOP_VERDICT_PROPOSAL.md` (prepared 2026-10-16 on branch
`analysis/hop-thresholds`, commit `c254d660`; carried into the repository with status APPROVED).

## Decision

1. **Rule.** The proposal's recommended rule (family C, "breadth", section 7 "Recommended"): a hop is marked
   `gecikme burada` when the mean of one of its signals (forward or reverse transit, request RX or response TX
   component) grew by **at least 5 ms** and to **at least 2 times** the earlier mean, with **at least 10 samples** in
   each window, **at least 10 late packets** (5 ms above the earlier median) whose share grew by at least 1 percentage
   point (the breadth rule), and the increase is larger than **2 times the hop's clock uncertainty**. A hop is
   `temiz` only when the mean moved by **less than 1 ms**, the share of packets 1 ms above the earlier median grew by
   less than 1 point, and 2 times the clock uncertainty is below 5 ms (the floor: a 5 ms change must be visible).
   Everything else is `belirsiz`.
2. **Words and meaning** (proposal section 8 and 9): two visible words, `gecikme burada` and `temiz`; `temiz` means *no
   measured increase in delay*, never "no loss", and is shown with the loss/match figures; a marker is never a root
   cause and is printed with the window it was computed for.
3. **No word** for `belirsiz` and for `ölçüm yok`; the reason line is in the Hop Analizi sheet.
4. **No onset, no window:** no markers (as proposed); a user-given window produces them.
5. **Evidence the decision rests on** (proposal section 6): on the 206 frozen corpus cases the rule marked 21 of the 24
   injected hop delays and raised 0 false alarms on 745 hops without an injected fault; it never called a delayed hop
   `temiz`.
6. **Not decided:** the optional labelled calibration set (proposal section 11, item 5) stays open; the thresholds
   remain a starting point to be revised when labelled real incidents exist.

## Implementation

`ui2/packet-analysis` expert report: `export/HopVerdict` (the pure rule), `ExpertReportBuilder.readHopMarks` (signals,
exact medians and late counts), `ExpertWorkbook` (strip marker row, Hop Analizi `Karar` / `Gerekçe` / `Pencere`,
Yöntem thresholds section, reworded limits), `ExpertFindings` (`hop-mark`). The choices the proposal left open, all
taken conservatively, are listed in `ui2/packet-analysis/README.md` "Hop markers". The engine's report and
FindingsExport are unchanged; the expert report has no counterpart in the frozen reference (port contract section 4).
