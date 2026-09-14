# The turn counter reads one turn for a whole multi-million-token default-provider dispatch

status: planned · target: GOV.ORCH.4 section 3.1; scripts/orchestrator_usage.py

The ledger's first two default-provider rows (NXS-LOCAL-0168, NXS-LOCAL-0180) each report turns=1 against 4.0M and 5.7M tokens. One turn for that volume is not credible; the usage reader is counting a single terminal event rather than the provider's turns. It makes per-turn cost, which roles/PO.md budgets by, meaningless for the default provider. Measure the real event stream before changing the counter.

2026-09-15, widened by the first Antigravity row: a participant with no usage stream at all renders turns 0, tokens 0 and cache 0.00 percent rather than unknown. A zero is a claim that nothing was spent; the truth is that nothing was measured. DL-6 says an absent value prints unknown and is never computed into a number, so the usage reader must distinguish an absent usage record from a recorded zero before the ledger can be trusted for any participant that does not emit usage.
