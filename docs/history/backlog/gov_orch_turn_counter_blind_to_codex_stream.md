# The turn counter reads one turn for a whole multi-million-token default-provider dispatch

status: planned · target: GOV.ORCH.4 section 3.1; scripts/orchestrator_usage.py

The ledger's first two default-provider rows (NXS-LOCAL-0168, NXS-LOCAL-0180) each report turns=1 against 4.0M and 5.7M tokens. One turn for that volume is not credible; the usage reader is counting a single terminal event rather than the provider's turns. It makes per-turn cost, which roles/PO.md budgets by, meaningless for the default provider. Measure the real event stream before changing the counter.
