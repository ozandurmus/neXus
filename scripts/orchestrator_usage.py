"""GOV.ORCH.4 section 3.1 -- per-movement token/cost accounting from
`.nexus/engineer.log` via the provider adapters
(docs/design/GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md).

Pure accumulation: this module never parses a provider's raw event shape
itself -- `ProviderAdapter.usage_from_event(obj)` (scripts/orchestrator_providers.py)
normalizes one already-JSON-decoded event into a small, provider-neutral
record (`{"kind": ..., "usage": {...}, ...}`); everything here only ever
combines those normalized records. Never guesses a price: an estimated cost
is only ever computed from `config/model_prices.json`, a PO-owned,
hand-maintained table that ships with only a `_comment` key.

Incremental: state is persisted per movement in
`<state-dir>/usage/<movement>.json` (a byte offset into the log plus running
totals) so a long-running `engineer.log` is never fully re-parsed on every
dashboard poll. Corrupt or partial lines are skipped, never raised -- the
same tolerance `orchestrator_providers.py::_iter_json_lines` already has. A
truncated last line (a write in progress) is left unconsumed -- the byte
offset never advances past it -- and is picked up whole on the next call.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import local_relay as lr  # noqa: E402
import orchestrator_providers as op  # noqa: E402

#: The four raw token fields every provider's usage normalizes to (section
#: 3.1's own JSON shape, minus the derived fields computed in `_public_shape`).
USAGE_FIELDS = (
    "input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens",
)

_EMPTY_CACHE: dict[str, Any] = {
    "byte_offset": 0, "provider": None, "seen_message_ids": [], "turns": 0,
    "input_tokens": 0, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0,
    "output_tokens": 0, "model": None, "result_usage": None, "result_cost_usd": None,
    "last_event_at": None,
}


def _empty_cache() -> dict[str, Any]:
    cache = dict(_EMPTY_CACHE)
    cache["seen_message_ids"] = []
    return cache


def _usage_path(state_dir: Path, movement_id: str) -> Path:
    return Path(state_dir) / "usage" / f"{movement_id}.json"


def load_usage_cache(state_dir: Path, movement_id: str) -> dict:
    path = _usage_path(state_dir, movement_id)
    if not path.is_file():
        return _empty_cache()
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return _empty_cache()
    if not isinstance(obj, dict):
        return _empty_cache()
    cache = _empty_cache()
    cache.update(obj)
    cache["seen_message_ids"] = list(obj.get("seen_message_ids") or [])
    return cache


def save_usage_cache(state_dir: Path, movement_id: str, cache: dict) -> None:
    lr._atomic_write(_usage_path(state_dir, movement_id), json.dumps(cache, sort_keys=True, indent=2) + "\n")


def _iso_from_epoch(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _read_new_complete_lines(log_path: Path, byte_offset: int) -> tuple[list[str], int]:
    """Reads `log_path` from `byte_offset` to EOF and returns
    `(new_complete_lines, new_byte_offset)`. AC-3: a final line not yet
    terminated by a newline (a write in progress) is never returned and the
    offset never advances past it; a missing/unreadable file yields no
    lines and the unchanged offset -- this never raises."""
    try:
        with open(log_path, "rb") as fh:
            fh.seek(byte_offset)
            data = fh.read()
    except OSError:
        return [], byte_offset
    if not data:
        return [], byte_offset
    parts = data.split(b"\n")
    if data.endswith(b"\n"):
        complete, consumed = parts[:-1], len(data)
    else:
        complete, consumed = parts[:-1], len(data) - len(parts[-1])
    lines = [p.decode("utf-8", errors="replace") for p in complete]
    return lines, byte_offset + consumed


def _apply_event(cache: dict, provider: str, obj: dict) -> None:
    adapter = op.build_adapter(provider)
    normalized = adapter.usage_from_event(obj)
    if not normalized:
        return
    kind = normalized.get("kind")
    usage = normalized.get("usage") or {}
    if kind == "model":
        if not cache.get("model") and normalized.get("model"):
            cache["model"] = normalized["model"]
    elif kind == "assistant":
        message_id = normalized.get("message_id")
        seen = cache["seen_message_ids"]
        if message_id is not None:
            if message_id in seen:
                return
            seen.append(message_id)
        cache["turns"] += 1
        for field in USAGE_FIELDS:
            cache[field] += int(usage.get(field) or 0)
    elif kind == "turn":
        cache["turns"] += 1
        for field in USAGE_FIELDS:
            cache[field] += int(usage.get(field) or 0)
    elif kind == "result":
        cache["result_usage"] = {field: int(usage.get(field) or 0) for field in USAGE_FIELDS}
        cost = normalized.get("total_cost_usd")
        cache["result_cost_usd"] = float(cost) if isinstance(cost, (int, float)) and not isinstance(cost, bool) else None
        if not cache.get("model") and normalized.get("model"):
            cache["model"] = normalized["model"]


def load_price_table(path: Path) -> dict:
    """Section 3.2: `config/model_prices.json`, hand-maintained by the PO.
    A missing file, an unreadable file, or a non-object body all yield an
    empty table -- never raises. The `_comment` key (the only key the
    shipped, empty table carries) is dropped."""
    path = Path(path)
    if not path.is_file():
        return {}
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    if not isinstance(obj, dict):
        return {}
    return {k: v for k, v in obj.items() if k != "_comment" and isinstance(v, dict)}


def _cost_for(model: str | None, tokens: dict, reported_cost: float | None, price_table: dict) -> tuple[float | None, str]:
    """Section 3.2's cost resolution: a provider-reported cost always wins
    (`reported`); otherwise a price-table entry for the observed model
    yields an `estimated` cost via the per-million-token formula; absent
    either, the honest answer is `unavailable` -- never guessed."""
    if reported_cost is not None:
        return reported_cost, "reported"
    entry = (price_table or {}).get(model) if model else None
    if isinstance(entry, dict):
        cost = (
            tokens["input_tokens"] / 1_000_000 * (entry.get("input_per_mtok") or 0)
            + tokens["cache_creation_input_tokens"] / 1_000_000 * (entry.get("cache_write_per_mtok") or 0)
            + tokens["cache_read_input_tokens"] / 1_000_000 * (entry.get("cache_read_per_mtok") or 0)
            + tokens["output_tokens"] / 1_000_000 * (entry.get("output_per_mtok") or 0)
        )
        return round(cost, 6), "estimated"
    return None, "unavailable"


def _public_shape(cache: dict, provider: str, price_table: dict) -> dict:
    """Section 3.1's JSON shape. When a `result` event was seen (Claude),
    its own usage totals are preferred over the accumulated per-turn sums
    (AC-1) -- the cumulative session totals it reports are the authoritative
    ones."""
    if cache.get("result_usage") is not None:
        tokens = {field: cache["result_usage"].get(field, 0) for field in USAGE_FIELDS}
    else:
        tokens = {field: cache.get(field, 0) for field in USAGE_FIELDS}
    total_tokens = sum(tokens.values())
    denom = tokens["input_tokens"] + tokens["cache_creation_input_tokens"] + tokens["cache_read_input_tokens"]
    cache_hit_ratio = round(tokens["cache_read_input_tokens"] / denom, 4) if denom else 0.0
    cost_usd, cost_source = _cost_for(cache.get("model"), tokens, cache.get("result_cost_usd"), price_table)
    return {
        "provider": provider,
        "turns": cache.get("turns", 0),
        "input_tokens": tokens["input_tokens"],
        "cache_creation_input_tokens": tokens["cache_creation_input_tokens"],
        "cache_read_input_tokens": tokens["cache_read_input_tokens"],
        "output_tokens": tokens["output_tokens"],
        "total_tokens": total_tokens,
        "uncached_input_tokens": tokens["input_tokens"],
        "cache_hit_ratio": cache_hit_ratio,
        "cost_usd": cost_usd,
        "cost_source": cost_source,
        "model": cache.get("model"),
        "last_event_at": cache.get("last_event_at"),
    }


def update_usage(state_dir: Path, movement_id: str, log_path: Path, provider: str,
                  price_table: dict | None = None) -> dict:
    """Incrementally parses `log_path` from the last recorded byte offset,
    accumulates into the persisted per-movement cache, and returns the
    current public usage shape (section 3.1). Never raises: a missing log,
    a corrupt cache file, or an unparsable line all degrade to "no new
    data" rather than an exception."""
    cache = load_usage_cache(state_dir, movement_id)
    cache["provider"] = provider
    lines, new_offset = _read_new_complete_lines(Path(log_path), cache.get("byte_offset", 0))
    growth = False
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(obj, dict):
            continue
        _apply_event(cache, provider, obj)
        growth = True
    cache["byte_offset"] = new_offset
    if growth:
        try:
            cache["last_event_at"] = _iso_from_epoch(Path(log_path).stat().st_mtime)
        except OSError:
            pass
    save_usage_cache(state_dir, movement_id, cache)
    return _public_shape(cache, provider, price_table or {})


def usage_summary_only(state_dir: Path, movement_id: str, provider: str, price_table: dict | None = None) -> dict:
    """Read-only: the last computed usage shape for `movement_id`, without
    touching the log (used when there is no worktree/log to read yet)."""
    cache = load_usage_cache(state_dir, movement_id)
    return _public_shape(cache, provider, price_table or {})


def compute_usage(state_dir: Path, movement_id: str, worktree_path: str | None, provider: str,
                   price_table: dict | None = None) -> dict:
    """The one entry point callers (the dashboard, `orchestrator.py run`,
    the `usage` CLI) use: refreshes from `<worktree>/.nexus/engineer.log`
    when a worktree is known, otherwise returns the last cached totals."""
    if worktree_path:
        log_path = Path(worktree_path) / ".nexus" / "engineer.log"
        return update_usage(state_dir, movement_id, log_path, provider, price_table)
    return usage_summary_only(state_dir, movement_id, provider, price_table)
