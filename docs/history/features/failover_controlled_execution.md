# failover_controlled_execution — Controlled Failover Execution

## summary

The single write-capable component: one authorised, confirmed action with fresh same-workflow preflight, independent post-action verification, no automatic rollback (reversal is a new confirmed action), OUTCOME_UNKNOWN quarantine, full audit, and per-vendor adapters. Vendor-independent architecture FROZEN 2026-09-04 (docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md); several foundations implemented and unit-tested but structurally unreachable and unwired (no taxonomy member, unconditional DENY authorizer, no production entry point). Product execution not started.

## why

The payoff of a trustworthy SEE/VERIFY/TRACE/RECOVER stack; the tool's first OPERATE capability.
