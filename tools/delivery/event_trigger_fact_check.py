"""1-Day SIEM / Collector Fact-Check privacy validator and Q1/Q9 selection gate CLI.

Implements the frozen Step 0 checklist schema and deterministic Q1/Q9
selection gate from:
  docs/design/PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md (§3)

Privacy & fail-closed invariants (AGENTS.md):
  - Accepts ONLY the closed FC-1..FC-10 key set and closed boolean/count/enum
    value domains.
  - Rejects unknown keys, duplicate keys, malformed lines, and out-of-domain
    values (IPs, hostnames, emails, raw log lines) while reporting ONLY the
    key name and line number -- never echoing the rejected value.
  - Stdlib-only, offline, zero network or device access.

Exit codes:
  0  SELECT_1A_9A / SELECT_1B_443 / SELECT_1C_9A
  1  Parse or privacy validation error (or unreadable input)
  2  HOLD (gating prerequisites missing or blocking)
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping

EXIT_SELECT = 0
EXIT_ERROR = 1
EXIT_HOLD = 2

_NON_NEG_INT_RE = re.compile(r"^(?:0|[1-9][0-9]*)$")
_RATIO_RE = re.compile(r"^(0|[1-9][0-9]*)/(0|[1-9][0-9]*)$")

FC_ORDER: tuple[str, ...] = (
    "FC-1",
    "FC-2",
    "FC-3",
    "FC-4",
    "FC-5",
    "FC-6",
    "FC-7",
    "FC-8",
    "FC-9",
    "FC-10",
)

COLLECTOR_ENGINES = frozenset({"syslog-ng", "rsyslog", "splunk_uf_only", "other"})
MATCH_ENUM = frozenset({"MATCH", "MISMATCH", "UNKNOWN"})
OPENSHIFT_INGRESS_MODES = frozenset({"route_443", "route_sni_passthrough", "lb_6514"})


class FactCheckParseError(ValueError):
    """Raised when a fact-check file fails syntax, schema, or privacy validation."""


@dataclass(frozen=True)
class FieldSpec:
    """Closed schema specification for a single fact-check key."""

    fc_id: str
    parser: Callable[[str], Any]
    type_label: str


def _parse_bool(raw: str) -> bool:
    if raw == "true":
        return True
    if raw == "false":
        return False
    raise ValueError("expected bool")


def _parse_bool_or_unknown(raw: str) -> bool | str:
    if raw == "UNKNOWN":
        return "UNKNOWN"
    return _parse_bool(raw)


def _parse_non_neg_int(raw: str) -> int:
    if not _NON_NEG_INT_RE.fullmatch(raw):
        raise ValueError("expected non-negative integer")
    return int(raw)


def _parse_non_neg_int_or_na(raw: str) -> int | str:
    if raw == "na":
        return "na"
    return _parse_non_neg_int(raw)


def _parse_non_neg_int_or_unknown(raw: str) -> int | str:
    if raw == "UNKNOWN":
        return "UNKNOWN"
    return _parse_non_neg_int(raw)


def _make_enum_parser(allowed: frozenset[str], label: str) -> Callable[[str], str]:
    def _parse(raw: str) -> str:
        if raw not in allowed:
            raise ValueError(f"expected {label}")
        return raw

    return _parse


def _parse_exporter_targets(raw: str) -> str:
    match = _RATIO_RE.fullmatch(raw)
    if not match:
        raise ValueError("expected '<non-neg-int>/<non-neg-int>'")
    healthy = int(match.group(1))
    total = int(match.group(2))
    if healthy > total:
        raise ValueError("healthy count exceeds total")
    return f"{healthy}/{total}"


FACT_CHECK_SCHEMA: dict[str, FieldSpec] = {
    # FC-1: Pre-SIEM syslog-ng / rsyslog tier
    "pre_siem_syslog_tier": FieldSpec("FC-1", _parse_bool, "bool (true|false)"),
    "collector_engine": FieldSpec(
        "FC-1",
        _make_enum_parser(COLLECTOR_ENGINES, "collector_engine"),
        "enum (syslog-ng|rsyslog|splunk_uf_only|other)",
    ),
    "supports_tls_mutual_6514": FieldSpec("FC-1", _parse_bool, "bool (true|false)"),
    "supports_upstream_audit_filter": FieldSpec("FC-1", _parse_bool, "bool (true|false)"),
    "supports_per_input_emitter_key": FieldSpec("FC-1", _parse_bool, "bool (true|false)"),
    # FC-2: Vendor audit/config log coverage today
    "cp_op7_ingested": FieldSpec("FC-2", _parse_bool, "bool (true|false)"),
    "pan_config_commit_ingested": FieldSpec("FC-2", _parse_bool, "bool (true|false)"),
    "panorama_push_ingested": FieldSpec("FC-2", _parse_bool, "bool (true|false)"),
    "fgt_44546_44547_ingested": FieldSpec("FC-2", _parse_bool, "bool (true|false)"),
    "fmg_21023_21015_ingested": FieldSpec("FC-2", _parse_bool, "bool (true|false)"),
    "asa_111004_111005_ingested": FieldSpec("FC-2", _parse_bool, "bool (true|false)"),
    # FC-3: Opaque non-hostname ID survival
    "syslog_preserves_opaque_id": FieldSpec("FC-3", _parse_bool, "bool (true|false)"),
    "syslog_per_domain_input_possible": FieldSpec("FC-3", _parse_bool, "bool (true|false)"),
    "splunk_preserves_serial_or_devid": FieldSpec("FC-3", _parse_bool, "bool (true|false)"),
    "splunk_preserves_dg_id": FieldSpec("FC-3", _parse_bool, "bool (true|false)"),
    "splunk_only_host_dvc": FieldSpec("FC-3", _parse_bool, "bool (true|false)"),
    # FC-4: Splunk real-time vs scheduled alert latency
    "splunk_realtime_alerts_permitted": FieldSpec("FC-4", _parse_bool, "bool (true|false)"),
    "splunk_scheduled_min_interval_min": FieldSpec(
        "FC-4", _parse_non_neg_int_or_na, "non-neg int or na"
    ),
    "splunk_indexing_lag_p95_sec": FieldSpec(
        "FC-4", _parse_non_neg_int_or_unknown, "non-neg int or UNKNOWN"
    ),
    "splunk_https_443_egress_ok": FieldSpec("FC-4", _parse_bool, "bool (true|false)"),
    # FC-5: Check Point R82 MDS audit-only FilterConfiguration.xml & Log Exporter check
    "cp_r82_audit_only_filter_verified": FieldSpec("FC-5", _parse_bool, "bool (true|false)"),
    "cp_traffic_log_leak_count": FieldSpec("FC-5", _parse_non_neg_int, "non-neg int"),
    "cp_json_has_loguid": FieldSpec("FC-5", _parse_bool_or_unknown, "bool|UNKNOWN"),
    "cp_op7_has_package_or_domain_field": FieldSpec("FC-5", _parse_bool_or_unknown, "bool|UNKNOWN"),
    "cp_exporter_healthy_targets": FieldSpec(
        "FC-5",
        _parse_exporter_targets,
        "<non-neg-int>/<non-neg-int> with healthy <= total",
    ),
    # FC-6: Cisco ASA logging permit-hostdown & transport check
    "asa_total_count": FieldSpec("FC-6", _parse_non_neg_int, "non-neg int"),
    "asa_permit_hostdown_present_count": FieldSpec("FC-6", _parse_non_neg_int, "non-neg int"),
    "asa_direct_tcp_tls_safe": FieldSpec("FC-6", _parse_bool, "bool (true|false)"),
    "asa_mtls_supported": FieldSpec("FC-6", _parse_bool_or_unknown, "bool|UNKNOWN"),
    # FC-7: Palo Alto serial header & Panorama commit-all shape
    "pan_config_header_has_serial": FieldSpec("FC-7", _parse_bool_or_unknown, "bool|UNKNOWN"),
    "panorama_commit_all_marker_observed": FieldSpec("FC-7", _parse_bool_or_unknown, "bool|UNKNOWN"),
    "panorama_push_has_dg_id": FieldSpec("FC-7", _parse_bool_or_unknown, "bool|UNKNOWN"),
    # FC-8: Fortinet 44547 devid, cfgpath, & mTLS check
    "fgt_44547_has_devid": FieldSpec("FC-8", _parse_bool_or_unknown, "bool|UNKNOWN"),
    "fgt_devid_serial_exact_match": FieldSpec(
        "FC-8",
        _make_enum_parser(MATCH_ENUM, "MATCH|MISMATCH|UNKNOWN"),
        "enum (MATCH|MISMATCH|UNKNOWN)",
    ),
    "fgt_policy_cfgpath_prefixes_verified": FieldSpec("FC-8", _parse_bool_or_unknown, "bool|UNKNOWN"),
    "fgt_mtls_client_cert_supported": FieldSpec("FC-8", _parse_bool_or_unknown, "bool|UNKNOWN"),
    # FC-9: HOST-A Traefik DNAT vs input verification
    "hosta_traefik_uses_prerouting_dnat": FieldSpec("FC-9", _parse_bool, "bool (true|false)"),
    "hosta_prerouting_negated_drop_required": FieldSpec("FC-9", _parse_bool, "bool (true|false)"),
    # FC-10: OpenShift TLS SNI & vendor TCP/TLS buffer retention
    "sender_emits_tls_sni": FieldSpec("FC-10", _parse_bool_or_unknown, "bool|UNKNOWN"),
    "openshift_ingress_mode": FieldSpec(
        "FC-10",
        _make_enum_parser(OPENSHIFT_INGRESS_MODES, "openshift_ingress_mode"),
        "enum (route_443|route_sni_passthrough|lb_6514)",
    ),
    "sender_restart_buffer_verified": FieldSpec("FC-10", _parse_bool_or_unknown, "bool|UNKNOWN"),
}


def parse_fact_check_text(text: str) -> dict[str, Any]:
    """Parse and privacy-validate key=value fact-check text.

    Fails closed on syntax errors, unknown keys, duplicate keys, or out-of-domain
    values. Error messages include ONLY the line number and key name (never the
    rejected value or raw line content).
    """
    parsed: dict[str, Any] = {}
    for line_no, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.lstrip("\ufeff") if line_no == 1 else raw_line
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" not in stripped:
            raise FactCheckParseError(f"line {line_no}: expected 'key=value' entry")
        raw_key, _, raw_val = stripped.partition("=")
        key = raw_key.strip()
        val = raw_val.strip()
        if not key:
            raise FactCheckParseError(f"line {line_no}: empty key")
        spec = FACT_CHECK_SCHEMA.get(key)
        if spec is None:
            raise FactCheckParseError(f"line {line_no}: unknown key '{key}'")
        if key in parsed:
            raise FactCheckParseError(f"line {line_no}: duplicate key '{key}'")
        try:
            parsed[key] = spec.parser(val)
        except ValueError as exc:
            detail = str(exc) if "exceeds total" in str(exc) else f"expected {spec.type_label}"
            raise FactCheckParseError(
                f"line {line_no}: invalid value for key '{key}' ({detail})"
            ) from None
    return parsed


def parse_fact_check(path: str | Path) -> dict[str, Any]:
    """Read and parse a UTF-8 key=value fact-check file."""
    file_path = Path(path)
    text = file_path.read_text(encoding="utf-8")
    return parse_fact_check_text(text)


def _get_bool(facts: Mapping[str, Any], key: str) -> bool | None:
    val = facts.get(key)
    return val if isinstance(val, bool) else None


def _get_non_neg_int(facts: Mapping[str, Any], key: str) -> int | None:
    val = facts.get(key)
    if isinstance(val, int) and not isinstance(val, bool) and val >= 0:
        return val
    return None


def _tri_not(val: bool | None) -> bool | None:
    return None if val is None else (not val)


def _tri_and(*vals: bool | None) -> bool | None:
    if any(v is False for v in vals):
        return False
    if all(v is True for v in vals):
        return True
    return None


def _tri_or(*vals: bool | None) -> bool | None:
    if any(v is True for v in vals):
        return True
    if all(v is False for v in vals):
        return False
    return None


def _collect_phase2_warnings(facts: Mapping[str, Any]) -> list[dict[str, str]]:
    warnings: list[dict[str, str]] = []
    if _get_bool(facts, "asa_direct_tcp_tls_safe") is False:
        warnings.append(
            {
                "fc_id": "FC-6",
                "code": "ASA_DIRECT_TCP_TLS_UNSAFE",
                "message": (
                    "FC-6 informational Phase 2 warning: asa_direct_tcp_tls_safe=false; "
                    "direct Cisco ASA TCP/TLS syslog requires logging permit-hostdown."
                ),
            }
        )
    asa_total = _get_non_neg_int(facts, "asa_total_count")
    asa_present = _get_non_neg_int(facts, "asa_permit_hostdown_present_count")
    if asa_total is not None and asa_present is not None and asa_present < asa_total:
        warnings.append(
            {
                "fc_id": "FC-6",
                "code": "ASA_PERMIT_HOSTDOWN_INCOMPLETE",
                "message": (
                    "FC-6 informational Phase 2 warning: "
                    "asa_permit_hostdown_present_count < asa_total_count; "
                    "not all ASAs have logging permit-hostdown verified."
                ),
            }
        )
    return warnings


def _collect_hold_diagnostics(
    facts: Mapping[str, Any],
    q1a: bool | None,
    q1b: bool | None,
    q1c: bool | None,
) -> tuple[list[str], list[str], list[str]]:
    """Return deterministic (blocking_fc_ids, reason_codes, missing_keys) for HOLD."""
    blocking_fcs: set[str] = set()
    reasons: list[str] = []
    missing_keys: list[str] = []

    def _add_reason(fc_id: str, code: str) -> None:
        blocking_fcs.add(fc_id)
        if code not in reasons:
            reasons.append(code)

    def _check_missing(keys: tuple[str, ...]) -> None:
        for key in keys:
            if facts.get(key) is None and key not in missing_keys:
                missing_keys.append(key)
                blocking_fcs.add(FACT_CHECK_SCHEMA[key].fc_id)

    if q1a is None:
        _check_missing(
            (
                "pre_siem_syslog_tier",
                "supports_tls_mutual_6514",
                "supports_upstream_audit_filter",
                "cp_op7_ingested",
            )
        )
        if (
            _get_bool(facts, "syslog_preserves_opaque_id") is not True
            and _get_bool(facts, "syslog_per_domain_input_possible") is not True
        ):
            _check_missing(("syslog_preserves_opaque_id", "syslog_per_domain_input_possible"))

    if q1a is False and q1b is None:
        _check_missing(
            (
                "cp_op7_ingested",
                "splunk_only_host_dvc",
                "splunk_realtime_alerts_permitted",
                "splunk_https_443_egress_ok",
            )
        )
        if (
            _get_bool(facts, "splunk_preserves_serial_or_devid") is not True
            and _get_bool(facts, "splunk_preserves_dg_id") is not True
        ):
            _check_missing(("splunk_preserves_serial_or_devid", "splunk_preserves_dg_id"))

    if q1a is False and q1b is False and q1c is None:
        _check_missing(("cp_r82_audit_only_filter_verified", "cp_traffic_log_leak_count"))

    if missing_keys:
        reasons.append("MISSING_REQUIRED_KEYS")

    if (
        _get_bool(facts, "pre_siem_syslog_tier") is False
        or _get_bool(facts, "supports_tls_mutual_6514") is False
        or _get_bool(facts, "supports_upstream_audit_filter") is False
    ):
        _add_reason("FC-1", "PRE_SIEM_SYSLOG_UNAVAILABLE")

    if _get_bool(facts, "cp_op7_ingested") is False:
        _add_reason("FC-2", "CP_OP7_NOT_INGESTED")

    if (
        _get_bool(facts, "syslog_preserves_opaque_id") is False
        and _get_bool(facts, "syslog_per_domain_input_possible") is False
    ):
        _add_reason("FC-3", "SYSLOG_NO_OPAQUE_IDENTITY")

    if _get_bool(facts, "splunk_only_host_dvc") is True:
        _add_reason("FC-3", "HOSTNAME_ONLY_IDENTITY")

    if (
        _get_bool(facts, "splunk_preserves_serial_or_devid") is False
        and _get_bool(facts, "splunk_preserves_dg_id") is False
    ):
        _add_reason("FC-3", "SPLUNK_NO_OPAQUE_IDENTITY")

    if _get_bool(facts, "splunk_realtime_alerts_permitted") is False:
        _add_reason("FC-4", "SPLUNK_REALTIME_ALERTS_DISALLOWED")

    if _get_bool(facts, "splunk_https_443_egress_ok") is False:
        _add_reason("FC-4", "SPLUNK_HTTPS_443_EGRESS_BLOCKED")

    if _get_bool(facts, "cp_r82_audit_only_filter_verified") is False:
        _add_reason("FC-5", "CP_AUDIT_ONLY_FILTER_NOT_VERIFIED")

    leak_count = _get_non_neg_int(facts, "cp_traffic_log_leak_count")
    if leak_count is not None and leak_count > 0:
        _add_reason("FC-5", "CP_TRAFFIC_LOG_LEAK_NONZERO")

    ordered_fcs = [fc for fc in FC_ORDER if fc in blocking_fcs]
    ordered_missing = [k for k in FACT_CHECK_SCHEMA if k in missing_keys]
    return ordered_fcs, reasons, ordered_missing


def evaluate_gate(facts: Mapping[str, Any]) -> dict[str, Any]:
    """Evaluate the deterministic PO §3.3 Q1/Q9 selection gate on parsed facts."""
    q1a_identity = _tri_or(
        _get_bool(facts, "syslog_preserves_opaque_id"),
        _get_bool(facts, "syslog_per_domain_input_possible"),
    )
    q1a = _tri_and(
        _get_bool(facts, "pre_siem_syslog_tier"),
        _get_bool(facts, "supports_tls_mutual_6514"),
        _get_bool(facts, "supports_upstream_audit_filter"),
        _get_bool(facts, "cp_op7_ingested"),
        q1a_identity,
    )

    q1b_identity = _tri_or(
        _get_bool(facts, "splunk_preserves_serial_or_devid"),
        _get_bool(facts, "splunk_preserves_dg_id"),
    )
    q1b = _tri_and(
        _get_bool(facts, "cp_op7_ingested"),
        _tri_not(_get_bool(facts, "splunk_only_host_dvc")),
        q1b_identity,
        _get_bool(facts, "splunk_realtime_alerts_permitted"),
        _get_bool(facts, "splunk_https_443_egress_ok"),
    )

    leak_count = _get_non_neg_int(facts, "cp_traffic_log_leak_count")
    leak_is_zero: bool | None = None if leak_count is None else (leak_count == 0)
    q1c = _tri_and(
        _get_bool(facts, "cp_r82_audit_only_filter_verified"),
        leak_is_zero,
    )

    phase2_warnings = _collect_phase2_warnings(facts)
    sanitized_facts = {k: facts[k] for k in FACT_CHECK_SCHEMA if k in facts}

    if q1a is True:
        decision = "SELECT_1A_9A"
        q1: str | None = "Option 1A"
        q9: str | None = "9A"
        blocking_fcs: list[str] = []
        reason_codes: list[str] = []
        missing_keys: list[str] = []
        summary = (
            "Selected Q1=Option 1A (pre-SIEM syslog-ng/rsyslog TLS_MUTUAL 6514) "
            "and Q9=9A."
        )
    elif q1a is False and q1b is True:
        decision = "SELECT_1B_443"
        q1 = "Option 1B"
        q9 = "443"
        blocking_fcs = []
        reason_codes = []
        missing_keys = []
        summary = (
            "Selected Q1=Option 1B (Splunk HTTPS webhook POST 443) "
            "and Q9=443."
        )
    elif q1a is False and q1b is False and q1c is True:
        decision = "SELECT_1C_9A"
        q1 = "Option 1C"
        q9 = "9A"
        blocking_fcs = []
        reason_codes = []
        missing_keys = []
        summary = (
            "Selected Q1=Option 1C (Direct CP R82 MDS TLS_MUTUAL 6514 fallback) "
            "and Q9=9A."
        )
    else:
        decision = "HOLD"
        q1 = None
        q9 = None
        blocking_fcs, reason_codes, missing_keys = _collect_hold_diagnostics(
            facts, q1a, q1b, q1c
        )
        summary = (
            "HOLD: Do not proceed to intake implementation "
            f"(blocking={', '.join(blocking_fcs) or 'UNKNOWN'}; "
            f"reasons={', '.join(reason_codes) or 'UNKNOWN'})."
        )

    return {
        "decision": decision,
        "q1": q1,
        "q9": q9,
        "blocking_fc_ids": blocking_fcs,
        "reason_codes": reason_codes,
        "missing_keys": missing_keys,
        "phase2_warnings": phase2_warnings,
        "summary": summary,
        "facts": sanitized_facts,
    }


def _format_fact_value(val: Any) -> str:
    if isinstance(val, bool):
        return "true" if val else "false"
    return str(val)


def format_json(result: Mapping[str, Any]) -> str:
    """Serialize an evaluation result as sorted JSON."""
    return json.dumps(result, indent=2, sort_keys=True)


def format_markdown(result: Mapping[str, Any]) -> str:
    """Render a sanitized Markdown summary suitable for a PO fact-check record."""
    lines = [
        "## Event Trigger 1-Day SIEM Fact-Check Gate Result",
        "",
        "| Gate Field | Value |",
        "|---|---|",
        f"| Decision | `{result['decision']}` |",
        f"| Q1 (Feed Architecture) | `{result['q1'] or 'HOLD'}` |",
        f"| Q9 (Ingress Path) | `{result['q9'] or 'HOLD'}` |",
        (
            "| Blocking FC IDs | "
            + (", ".join(f"`{fc}`" for fc in result["blocking_fc_ids"]) or "None")
            + " |"
        ),
        (
            "| Reason Codes | "
            + (", ".join(f"`{code}`" for code in result["reason_codes"]) or "None")
            + " |"
        ),
        (
            "| Missing Required Keys | "
            + (", ".join(f"`{k}`" for k in result["missing_keys"]) or "None")
            + " |"
        ),
        "",
        f"**Summary:** {result['summary']}",
    ]

    warnings = result.get("phase2_warnings") or []
    if warnings:
        lines.extend(["", "### Phase 2 Informational Warnings", ""])
        for warning in warnings:
            lines.append(f"- `{warning['code']}` ({warning['fc_id']}): {warning['message']}")

    facts = result.get("facts") or {}
    lines.extend(
        [
            "",
            "### Validated Fact-Check Summary (`FC-1`..`FC-10`)",
            "",
            "| FC ID | Key | Value |",
            "|---|---|---|",
        ]
    )
    for key, spec in FACT_CHECK_SCHEMA.items():
        if key in facts:
            lines.append(
                f"| `{spec.fc_id}` | `{key}` | `{_format_fact_value(facts[key])}` |"
            )

    return "\n".join(lines)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="event_trigger_fact_check",
        description="Validate 1-day SIEM fact-check answers and evaluate the Q1/Q9 gate.",
    )
    parser.add_argument(
        "--input",
        required=True,
        help="Path to key=value fact-check file (or '-' for stdin).",
    )
    parser.add_argument(
        "--format",
        choices=("json", "markdown"),
        default="markdown",
        help="Output format (default: markdown).",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.input == "-":
            facts = parse_fact_check_text(sys.stdin.read())
        else:
            facts = parse_fact_check(args.input)
    except (FactCheckParseError, OSError, UnicodeDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_ERROR

    result = evaluate_gate(facts)
    if args.format == "json":
        print(format_json(result))
    else:
        print(format_markdown(result))

    return EXIT_HOLD if result["decision"] == "HOLD" else EXIT_SELECT


if __name__ == "__main__":
    sys.exit(main())
