# M12 — M12: M12 -- per-device / per-capability schedules (D5 producer)

- Prerequisites M7 and M10. M10 complete (M10.1-M10.3, merged). M7 (entity_id-substitution wiring) is now AUTOMATED_VALIDATED (2026-09-08, fixture-proven; real-device end-to-end confirmation still pending Product Owner execution) -- M12 is now reachable on that prerequisite. Owns the D5 capability-policy producer that CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md keeps at POLICY_UNKNOWN today.
- Constraints already frozen: a ten-minute interval floor, default-disabled, editing gated on C-D7; D5 is never derived from LifecycleState.EXCLUDED, Device Registry DISABLED, or the global scheduler flag.
