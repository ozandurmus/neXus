# op_continuity_tolerance — Session/connection continuity tolerance for post-action verification: fixed defaults, or operator-tunable per run? (Originally framed as the auto-rollback trigger; auto-rollback was removed by op_reversal_model on 2026-09-04, so the tolerance now bears only on whether a continuity observation can carry a verdict or a warning.)

## Options

- fixed default (e.g. sessions preserved >= ~90%, zero split-brain)
- operator-tunable per run within bounds

## Recommendation

Fixed conservative default once real-cluster calibration exists (sessions preserved >= ~90%, zero split-brain, sync resumed); tunable-within-bounds only after that. Until decided, continuity observations are recorded on the action record and are not verdict-bearing; no numeric tolerance is invented; nothing they show triggers any action.
