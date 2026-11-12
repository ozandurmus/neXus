# PO decision record - 2026-11-11: log funnel, the NOT_EVALUABLE rule and the burst-threshold sweep

**Status: RATIFIED - PO DECISION (chat answer "Ikisini onayla", 2026-11-11 morning, relayed by the PO session).** This record
amends [LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md](LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md) (DRAFT) in section 4.4 only, and it
follows the measurement records [LOG_QUESTION_FUNNEL_MEASUREMENT_2026_11_10.md](LOG_QUESTION_FUNNEL_MEASUREMENT_2026_11_10.md)
and [LOG_FUNNEL_BACKTEST_2026_11_11.md](LOG_FUNNEL_BACKTEST_2026_11_11.md). It approves no product code, schema, device
command, outbound connection or credential.

## 1. PO answers

| # | Question (from the backtest record, section 4) | PO answer |
| --- | --- | --- |
| B1 | Run the burst-threshold sweep (3, 10, 20 and 30 lines in ten minutes) over the stored history? | Approved. In the pod, counts only, nothing raw leaves HOST-A, watched to an end state. Result: [LOG_FUNNEL_BURST_SWEEP_2026_11_11.md](LOG_FUNNEL_BURST_SWEEP_2026_11_11.md). |
| B2 | A device-source pair with fewer than 7 days of history is `NOT_EVALUABLE` and gets no question to the model? | Approved, to be implemented and shipped. |

## 2. The rule

A (device, source) pair is **evaluable** when it has lines on at least 7 distinct days before the evaluated window.
Otherwise its state is `NOT_EVALUABLE`:

1. No line of the pair produces a finding that can become a question to the model. This covers new-template and rate
   findings (already so in the funnel record) and, new with this decision, **signature findings** (crash, restart, HA
   burst, link burst).
2. The state is shown with its day count and is never read as health. Silence caused by missing history is not a
   finding either way (design section 4.1, rule 5).
3. With 7 to 13 usable days the pair is `BASELINE_LEARNING`: signature and new-template findings only, as the design
   already said for devices with fewer than 14 days. The rule adds the band below 7 days; it removes nothing above.

Contradiction reported, not hidden: before this decision design section 4.4 gave a device with fewer than 14 usable days
signature and new-template findings from its first day. For the question stream that sentence no longer holds below 7
days. The design is amended in the same change (section 4.4 and the test outline in section 11).

## 3. What the rule does not decide

- Whether a deterministic signature finding on a `NOT_EVALUABLE` pair (for example a crash line on the third day of
  collection) is shown to an operator as a plain finding without any model question. UNKNOWN until the PO decides; this
  record only says that no model question comes from it. The measurement tool counts the withheld lines so the cost of
  either answer is visible (`sig ... not_evaluable_lines`).
- Whether 7 days is the right number. It is the funnel record's learning rule and is a configuration value; the backtest
  and the sweep give evidence on its effect, not a proof of it.

## 4. Where it is applied

- **Product:** nothing yet. The log agent has no detector in the product; the rule is an acceptance criterion for the
  detector build (design section 4.4 and the evaluability test in section 11).
- **Measurement tool** (`scripts/measurement/log_question_funnel/LogFunnelMeasure.java`): the rule is on by default.
  `--signatures-ungated 1` switches it off to measure what the detector sees on a young pool (the backtest and the first
  sweep run, because no pair had 7 days before the failover runs). Self-test: a planted crash on a pair with two learning
  days is withheld by default and counted with the switch.
- **Effect on the known events:** see the sweep record; the three failover runs of October predate any pair with 7 days,
  so under this rule none of them would have produced a question. That is a property of the young pool, not of the rule.
