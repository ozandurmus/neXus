from __future__ import annotations

import re
from typing import Any


PROMPT_RE = re.compile(r"\[Expert@[^\]]+\]#\s*$", re.MULTILINE)
ROUTE_LINE_RE = re.compile(r"^(default\b|\S+(?:/\d+)?\s+(?:via\s+\S+\s+)?dev\s+\S+)")


def _routes(item: dict[str, Any]) -> list[dict[str, Any]]:
    routes = item.get("routes")
    if isinstance(routes, list):
        return routes
    routing = item.get("routing")
    if isinstance(routing, list):
        return routing
    return []


def _interfaces(item: dict[str, Any]) -> list[dict[str, Any]]:
    interfaces = item.get("interfaces")
    return interfaces if isinstance(interfaces, list) else []


def build_vsx_completeness(
    raw_items: list[dict[str, Any]],
    parsed_items: list[dict[str, Any]],
    telemetry: list[dict[str, Any]],
) -> dict[str, Any]:
    """Cross-check VSX command completion and raw-to-parser preservation.

    The collection method is unchanged. This function only inspects artifacts
    already produced by the collector and parser.
    """
    parsed_map = {
        (str(x.get("device")), str(x.get("vsys")), str(x.get("vs_id"))): x
        for x in parsed_items
    }

    telemetry_map = {
        (
            str(x.get("device")),
            str(x.get("context")),
            str(x.get("vs_id")),
            str(x.get("command")),
        ): x
        for x in telemetry
    }

    contexts = []
    for item in raw_items:
        key = (str(item.get("device")), str(item.get("vsys")), str(item.get("vs_id")))
        parsed = parsed_map.get(key, {})
        raw_if = str(item.get("interfaces_raw") or "")
        raw_rt = str(item.get("routes_raw") or "")

        interface_candidates = sum(
            1
            for line in raw_if.splitlines()
            if "Link encap" in line and line.strip().split()[0] != "lo"
        )
        route_candidates = sum(
            1 for line in raw_rt.splitlines() if ROUTE_LINE_RE.match(line.strip())
        )

        if_meta = telemetry_map.get((*key, "interfaces"), {})
        rt_meta = telemetry_map.get((*key, "routes"), {})

        contexts.append(
            {
                "device": item.get("device"),
                "context": item.get("vsys"),
                "vs_id": item.get("vs_id"),
                "raw": {
                    "interface_bytes": len(raw_if.encode("utf-8", errors="ignore")),
                    "interface_lines": len(raw_if.splitlines()),
                    "interface_prompt_seen": bool(PROMPT_RE.search(raw_if)),
                    "interface_candidates": interface_candidates,
                    "route_bytes": len(raw_rt.encode("utf-8", errors="ignore")),
                    "route_lines": len(raw_rt.splitlines()),
                    "route_prompt_seen": bool(PROMPT_RE.search(raw_rt)),
                    "route_candidates": route_candidates,
                },
                "telemetry": {
                    "interface_prompt_seen": if_meta.get("prompt_seen"),
                    "interface_timeout": if_meta.get("timeout"),
                    "interface_duration_ms": if_meta.get("duration_ms"),
                    "route_prompt_seen": rt_meta.get("prompt_seen"),
                    "route_timeout": rt_meta.get("timeout"),
                    "route_duration_ms": rt_meta.get("duration_ms"),
                },
                "parsed": {
                    "interfaces": len(_interfaces(parsed)),
                    "routes": len(_routes(parsed)),
                },
                "delta": {
                    "interfaces": interface_candidates - len(_interfaces(parsed)),
                    "routes": route_candidates - len(_routes(parsed)),
                },
            }
        )

    timeout_count = sum(1 for row in telemetry if row.get("timeout") is True)
    prompt_miss_count = sum(1 for row in telemetry if row.get("prompt_seen") is False)
    expected_telemetry = len(raw_items) * 2
    actual_telemetry = len(telemetry)
    parsed_context_count = len(parsed_items)
    raw_context_count = len(raw_items)

    interface_delta_contexts = [
        row for row in contexts if row["delta"]["interfaces"] != 0
    ]
    route_delta_contexts = [row for row in contexts if row["delta"]["routes"] != 0]

    warnings = []
    if raw_context_count != parsed_context_count:
        warnings.append(
            {
                "code": "VSX_CONTEXT_COUNT_MISMATCH",
                "raw": raw_context_count,
                "parsed": parsed_context_count,
            }
        )
    if telemetry and actual_telemetry != expected_telemetry:
        warnings.append(
            {
                "code": "VSX_TELEMETRY_COUNT_MISMATCH",
                "expected": expected_telemetry,
                "actual": actual_telemetry,
            }
        )
    if timeout_count:
        warnings.append({"code": "VSX_COMMAND_TIMEOUT", "count": timeout_count})
    if prompt_miss_count:
        warnings.append({"code": "VSX_PROMPT_NOT_SEEN", "count": prompt_miss_count})
    if interface_delta_contexts:
        warnings.append(
            {
                "code": "VSX_RAW_PARSED_INTERFACE_DELTA",
                "count": len(interface_delta_contexts),
            }
        )
    if route_delta_contexts:
        warnings.append(
            {
                "code": "VSX_RAW_PARSED_ROUTE_DELTA",
                "count": len(route_delta_contexts),
            }
        )

    telemetry_available = bool(telemetry)
    if not telemetry_available:
        warnings.append(
            {
                "code": "VSX_TELEMETRY_UNAVAILABLE",
                "note": "Legacy/offline verification can still run, but command-completion evidence is unavailable.",
            }
        )

    return {
        "status": "warning" if warnings else "success",
        "raw_contexts": raw_context_count,
        "parsed_contexts": parsed_context_count,
        "context_count_match": raw_context_count == parsed_context_count,
        "telemetry": {
            "available": telemetry_available,
            "expected_samples": expected_telemetry,
            "actual_samples": actual_telemetry,
            "timeout_count": timeout_count,
            "prompt_miss_count": prompt_miss_count,
        },
        "raw_to_parsed": {
            "interface_delta_contexts": len(interface_delta_contexts),
            "route_delta_contexts": len(route_delta_contexts),
        },
        "warnings": warnings,
        "contexts": contexts,
    }
