"""Providers: one normalised result dict per (case, model, effort, level) call."""

from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from pathlib import Path
from typing import Any

try:  # package import (repo root on sys.path)
    from . import pricing, prompt, schema
except ImportError:  # pragma: no cover - direct execution fallback
    import pricing  # type: ignore
    import prompt  # type: ignore
    import schema  # type: ignore

CHARS_PER_TOKEN = 3.6
THINKING_TOKENS = {"low": 400, "medium": 900}


def call_id(case_id: str, model: str, effort: str, level: int) -> str:
    return f"{case_id}|{model}|{effort}|L{level}"


def new_result(case_id: str, model: str, effort: str, level: int) -> dict[str, Any]:
    return {
        "call_id": call_id(case_id, model, effort, level),
        "case_id": case_id,
        "model": model,
        "effort": effort,
        "level": level,
        "status": "ERROR",
        "stop_reason": None,
        "refusal_category": None,
        "refusal_explanation": None,
        "response_model": None,
        "request_id": None,
        "latency_s": None,
        "usage": {"input": 0, "cache_read": 0, "cache_write": 0, "output": 0, "thinking": 0},
        "cost_usd": 0.0,
        "output_text": None,
        "output_json": None,
        "error": None,
    }


def classify_output(result: dict[str, Any]) -> None:
    """Set status from stop_reason and the output text (parse + schema check)."""
    stop = result["stop_reason"]
    if stop == "refusal":
        result["status"] = "REFUSED"
        return
    if stop == "max_tokens":
        result["status"] = "TRUNCATED"
        return
    text = result["output_text"]
    try:
        parsed = json.loads(text) if text is not None else None
    except ValueError:
        result["status"] = "SCHEMA_INVALID"
        result["error"] = "output is not valid JSON"
        return
    result["output_json"] = parsed
    errors = schema.validate(parsed)
    if parsed is None or errors:
        result["status"] = "SCHEMA_INVALID"
        result["error"] = "; ".join(errors[:5]) or "empty output"
        return
    result["status"] = "OK"


def credentials_available() -> bool:
    """True if an API key/token env var or an `ant auth login` profile exists. Never reads values."""
    if os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"):
        return True
    cfg = Path.home() / ".config" / "anthropic"
    try:
        return cfg.is_dir() and any(cfg.iterdir())
    except OSError:
        return False


def _usage_from(resp: Any) -> dict[str, int]:
    u = resp.usage
    details = getattr(u, "output_tokens_details", None)
    return {
        "input": int(getattr(u, "input_tokens", 0) or 0),
        "cache_read": int(getattr(u, "cache_read_input_tokens", 0) or 0),
        "cache_write": int(getattr(u, "cache_creation_input_tokens", 0) or 0),
        "output": int(getattr(u, "output_tokens", 0) or 0),
        "thinking": int(getattr(details, "thinking_tokens", 0) or 0),
    }


class ClaudeProvider:
    name = "claude"

    def __init__(self) -> None:
        import anthropic  # lazy: only a real run needs the SDK

        self._anthropic = anthropic
        self._client = anthropic.Anthropic(timeout=120.0, max_retries=2)

    def call(self, case: dict[str, Any], model: str, effort: str, level: int, max_tokens: int = 4096) -> dict[str, Any]:
        result = new_result(case["case_id"], model, effort, level)
        start = time.monotonic()
        try:
            resp = self._client.messages.create(
                model=model,
                max_tokens=max_tokens,
                system=prompt.system_blocks(cache=True),
                messages=prompt.messages(case, level),
                thinking={"type": "adaptive"},
                output_config={"effort": effort, "format": {"type": "json_schema", "schema": schema.SCHEMA}},
            )
        except Exception as exc:  # noqa: BLE001 - recorded, never raised
            result["latency_s"] = round(time.monotonic() - start, 3)
            result["error"] = self._describe_error(exc)
            return result
        result["latency_s"] = round(time.monotonic() - start, 3)
        result["stop_reason"] = resp.stop_reason
        result["response_model"] = getattr(resp, "model", None)
        result["request_id"] = getattr(resp, "_request_id", None)
        details = getattr(resp, "stop_details", None)
        if details is not None:
            result["refusal_category"] = getattr(details, "category", None)
            result["refusal_explanation"] = getattr(details, "explanation", None)
        result["usage"] = _usage_from(resp)
        result["cost_usd"] = pricing.call_cost(model, result["usage"])
        for block in resp.content:
            if getattr(block, "type", None) == "text":
                result["output_text"] = block.text
                break
        classify_output(result)
        return result

    def _describe_error(self, exc: Exception) -> str:
        a = self._anthropic
        known = (
            a.BadRequestError,
            a.AuthenticationError,
            a.PermissionDeniedError,
            a.NotFoundError,
            a.RateLimitError,
            a.APIStatusError,
            a.APIConnectionError,
        )
        label = next((c.__name__ for c in known if isinstance(exc, c)), type(exc).__name__)
        status = getattr(exc, "status_code", None)
        return f"{label} status={status} message={str(exc)[:500]}"

    def count_tokens(self, case: dict[str, Any] | None, model: str, level: int) -> int:
        """Free endpoint. case=None counts the static prefix alone (plus a one-character message)."""
        msgs = [{"role": "user", "content": "."}] if case is None else prompt.messages(case, level)
        resp = self._client.messages.count_tokens(model=model, system=prompt.system_blocks(cache=False), messages=msgs)
        return int(resp.input_tokens)


def stable_int(*parts: Any) -> int:
    digest = hashlib.sha256("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()
    return int(digest[:12], 16)


# Deterministic special cases so a mock run exercises every code path.
MOCK_REFUSAL = ("C03", "claude-opus-5-5", "medium", 2)
MOCK_TRUNCATION = ("C05", "claude-sonnet-5-5", "low", 2)


class MockProvider:
    name = "mock"

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._cached_models: set[str] = set()

    def call(self, case: dict[str, Any], model: str, effort: str, level: int, max_tokens: int = 4096) -> dict[str, Any]:
        cid = case["case_id"]
        result = new_result(cid, model, effort, level)
        h = stable_int(cid, model, effort, level)
        result["latency_s"] = round(2.0 + (h % 7000) / 1000.0, 3)
        result["response_model"] = model
        result["request_id"] = f"mock-{h:x}"
        result["usage"] = self._usage(case, model, effort, level)
        key = (cid, model, effort, level)
        if key == MOCK_REFUSAL:
            result["stop_reason"] = "refusal"
            result["refusal_category"] = "cyber"
            result["refusal_explanation"] = "mock refusal"
            result["usage"]["output"] = 0
            result["usage"]["thinking"] = 0
        elif key == MOCK_TRUNCATION:
            result["stop_reason"] = "max_tokens"
            result["output_text"] = '{"finding_id": "'
            result["usage"]["output"] = max_tokens
        else:
            result["stop_reason"] = "end_turn"
            result["output_text"] = json.dumps(self._answer(case, model, level, h))
        result["cost_usd"] = pricing.call_cost(model, result["usage"])
        classify_output(result)
        return result

    def _usage(self, case: dict[str, Any], model: str, effort: str, level: int) -> dict[str, int]:
        prefix = int(len(prompt.STATIC_PREFIX) / CHARS_PER_TOKEN)
        user = int(len(prompt.user_message(case, level)) / CHARS_PER_TOKEN)
        with self._lock:
            warm = model in self._cached_models
            self._cached_models.add(model)
        thinking = THINKING_TOKENS.get(effort, 600)
        return {
            "input": user,
            "cache_read": prefix if warm else 0,
            "cache_write": 0 if warm else prefix,
            "output": 300 + thinking,
            "thinking": thinking,
        }

    def _answer(self, case: dict[str, Any], model: str, level: int, h: int) -> dict[str, Any]:
        exp = case["expected"]
        cats = exp["categories_l1"] if level == 1 else exp["categories_l2"]
        category = cats[0]
        priority = exp["priorities"][0]
        refs = sorted(prompt.allowed_references(case, level))
        evidence = refs[:2]
        roll = h % 100
        if roll >= 75 and roll < 90:
            category = next(c for c in schema.CAUSE_CATEGORIES if c not in cats)
        elif roll >= 90 and roll < 95:
            evidence = [evidence[0], "E99"]
        summary = f"Mock explanation for {case['finding']['kind']}."
        inj = exp.get("injection")
        if inj and "haiku" in model:
            summary = f"{inj['marker']} {summary}"
            priority = inj["forbidden_priorities"][0]
        return {
            "finding_id": case["finding"]["finding_id"],
            "summary": summary,
            "what_happened": "Mock: the detector found unusual log activity in the window.",
            "likely_cause": {"category": category, "statement": "Mock cause statement.", "confidence": "medium"},
            "evidence": [{"reference": r, "why": "Mock evidence."} for r in evidence],
            "unknowns": ["Mock: the peer state is not shown."],
            "triage": {"priority": priority, "reason": "Mock triage reason."},
            "next_checks": [{"kind": "open_screen", "target": "screen:system_health", "why": "Mock check."}],
        }
