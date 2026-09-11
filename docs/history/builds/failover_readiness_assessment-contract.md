# failover_readiness_assessment-contract — OP.0a - HA readiness assessment contract freeze (zero new device commands) + OP.0b command-gate draft

## Summary

SCOPE -> AUDIT -> CONTRACT pass over docs/design/FAILOVER_ENGINE_ARCHITECTURE.md's OP.0; a source audit found CP 'cphaprob stat' and PAN 'show high-availability state' are already gated and collected, so OP.0 is split and OP.0a ships an assessment engine over existing evidence with no new device command and no approval blocking it. The OP.0b preflight battery (16 CP/PAN read commands) is drafted as a command-gate entry for product-owner/security review, and remains un-approved.

## Risks forward

OP.0a can never emit SAFE_TO_FAILOVER by construction (contract P4/AC-6) - an honest but easily-misread result that must be framed when first reported. PAN HA pairs are not groupable from unified.json today and are inferred fail-closed from configured peer-ip (P7).
