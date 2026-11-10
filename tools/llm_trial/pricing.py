"""Model prices (USD per 1M tokens, verified on the live pricing page 2026-11-10) and cost helpers."""

from __future__ import annotations

from typing import Any

PRICES: dict[str, dict[str, float]] = {
    "claude-opus-5-5": {"input": 4.00, "cache_write": 5.00, "cache_read": 0.20, "output": 20.00},
    "claude-sonnet-5-5": {"input": 2.00, "cache_write": 2.50, "cache_read": 0.10, "output": 10.00},
    # Haiku prices hold for prompts up to 100K tokens; every trial prompt is far below that.
    "claude-haiku-5-5": {"input": 0.10, "cache_write": 0.125, "cache_read": 0.01, "output": 0.50},
}
PER = 1_000_000.0


def prices_for(model: str) -> dict[str, float]:
    try:
        return PRICES[model]
    except KeyError:
        raise ValueError(f"no price known for model {model!r}") from None


def _tok(usage: dict[str, Any], key: str) -> float:
    return float(usage.get(key) or 0)


def call_cost(model: str, usage: dict[str, Any]) -> float:
    """Actual cost of one call. `output` already includes thinking tokens, so thinking is not added."""
    p = prices_for(model)
    return (
        _tok(usage, "input") * p["input"]
        + _tok(usage, "cache_read") * p["cache_read"]
        + _tok(usage, "cache_write") * p["cache_write"]
        + _tok(usage, "output") * p["output"]
    ) / PER


def nocache_cost(model: str, usage: dict[str, Any]) -> float:
    """Cost if every input-side token had been billed at the base input price (cold-cache production call)."""
    p = prices_for(model)
    side = _tok(usage, "input") + _tok(usage, "cache_read") + _tok(usage, "cache_write")
    return (side * p["input"] + _tok(usage, "output") * p["output"]) / PER
