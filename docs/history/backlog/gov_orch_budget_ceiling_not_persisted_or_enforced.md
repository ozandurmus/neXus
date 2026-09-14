# The dispatch budget ceiling is neither recorded nor enforceable for the default provider

status: planned · target: GOV.ORCH.13 DL-3 budget column; scripts/orchestrator.py state record

Observed on NXS-LOCAL-0180, the first dispatch rendered into the ledger: --max-budget-usd 4.00 was requested, the run cost an estimated 6.63 and was never cut off, and the ledger's budget column reads unknown because the state record does not persist max_budget_usd. Two separate defects. First, the ceiling is not written to the record, so DL-3's budget column can never be filled and ceiling-versus-actual is unreviewable. Second, the default provider proves no budget exhaustion at all (its adapter's budget_exhausted returns None by the vendor-semantics law), so the flag is an intention, not a control. The PO must not describe it as a limit until one of the two is fixed.
