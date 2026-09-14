# GOV.ORCH.4-A — Cost from the requested model when the provider stream is silent

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-14.** Extension of
`docs/design/GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md`
§3.2, which is FROZEN and is not edited in place. §3.2's rule — a
provider-reported cost wins, else a price-table entry for the **observed**
model, else `unavailable`, never guessed — stands for every provider that
reports its model.

## 1. The measurement

Codex (`codex exec --json`, codex-cli 0.154.0) reports token counts but
carries **no model field** in its event stream; `orchestrator_providers.py`
records `observed_model = (None, None)` and the dispatch is already marked
`audit_exception: "provider_default_used"`. The first Codex movement
(`NXS-LOCAL-0166`) therefore showed 5,822,076 tokens and
`cost_source: unavailable`, although the price table holds an entry for the
model the dispatch asked for.

Token counts are captured correctly; only the price lookup fails, because
its key is absent.

## 2. The decision

- **CU-1.** When the stream reports no model and the process record carries
  a `model_requested`, the price lookup uses `model_requested`, and the
  result's `cost_source` is **`estimated_from_requested_model`** — a third
  value beside `reported` and `estimated`, never conflated with either.
- **CU-2.** This is not a guess: `model_requested` is what the orchestrator
  put on the command line. The residual risk — that the provider served a
  different model — is exactly what the existing
  `audit_exception: provider_default_used` already records, and both appear
  together on the same movement.
- **CU-3.** With no `model_requested` either, the answer stays
  `unavailable`. §3.2's prohibition on guessing is unchanged.
- **CU-4.** A subscription-billed provider's estimate is a comparable, not
  an invoice; the workbench labels it by its `cost_source` and nothing
  downstream may treat it as billed spend.

## 3. Cross-references

- `GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md` §3.1, §3.2.
- `GOV_ORCH_2_PROVIDER_ADAPTER.md` §2.3 — where the missing model field is recorded.
- `config/model_prices.json` — the Product-Owner-owned table this reads.
