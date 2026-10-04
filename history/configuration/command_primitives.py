"""CE.2 — curated read-only command-primitive registry.

``docs/design/COMPLIANCE_CHECK_ENGINE.md`` section 5 (decision D8). A static
registry of individually network-device-command-gate-reviewed, **read-only**
commands the compliance engine may reference as evidence sources it does not
otherwise collect. No entry is added without a full ``CommandGateReview``; no
write/config-changing command is ever admitted, at any maturity
(``docs/AI_DEVELOPMENT_PROTOCOL.md`` "Network-device command gate").

Opt-in only: primitives execute exclusively under ``main.py
--compliance-probe`` (``application/workflows/compliance.py``). They are never
invoked from a normal collection run in this movement — promotion to a
normal-run stage needs its own real-environment validation gate (design
section 5, "Opt-in first").

Diagnostic-path law: both proof primitives below reuse an already-controlled,
already-reviewed transport/command rather than opening a new credential or
network path — the Check Point primitive reuses
``configuration.checkpoint_config_probe``'s existing SSH session/connect/exec
helpers and its exact ``show version all`` command; the Palo Alto primitive
reuses ``panorama.panorama_runtime_runner``'s existing Panorama-proxied XML
API helpers and the exact ``show system info`` op command
``configuration/panorama_config_collector.py`` already issues.

Raw-evidence law: every primitive's ``redact`` callable is applied
immediately after execution and the raw command output is discarded in the
same function that captured it — never persisted, never returned to a caller.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable

from utils.action_taxonomy import CLASS_0_READ

__all__ = [
    "CommandPrimitiveError",
    "CommandGateReview",
    "CommandPrimitive",
    "PrimitiveResult",
    "PrimitiveExecutionTracker",
    "PRIMITIVE_REGISTRY",
    "register_primitive",
    "primitives_for_vendor",
    "run_check_point_primitives",
    "run_palo_alto_primitives",
]


class CommandPrimitiveError(RuntimeError):
    """Raised when a primitive cannot be registered or admitted safely."""


# ---------------------------------------------------------------------------
# The 10-point network-device command gate, captured per primitive
# (docs/AI_DEVELOPMENT_PROTOCOL.md "Network-device command gate"). Every field
# is mandatory: a primitive with no filled-in review is not a primitive.
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class CommandGateReview:
    reason: str
    action_class_id: str  # must equal utils.action_taxonomy.CLASS_0_READ.id
    vendor_platform_shell_context: str
    timeout_seconds: int
    retry_count: int
    max_frequency_per_endpoint_minutes: int
    session_reuse: str
    unsupported_behavior: str
    secret_output_risk: str
    safe_telemetry: str


@dataclass(frozen=True)
class CommandPrimitive:
    primitive_id: str
    vendor: str  # "check_point" | "palo_alto"
    command: str  # the exact, literal, read-only command/op-cmd string
    gate_review: CommandGateReview
    redact: Callable[[str], dict[str, Any]]

    @property
    def source_namespace(self) -> str:
        """The compliance-engine selector namespace this primitive's output
        is exposed under (design section 5): ``primitive.<primitive_id>``."""
        return f"primitive.{self.primitive_id}"


@dataclass(frozen=True)
class PrimitiveResult:
    primitive_id: str
    vendor: str
    endpoint_id: str  # canonical management identifier; never a display name
    success: bool
    error_class: str | None
    executed_at: str
    redacted: dict[str, Any]

    def to_safe_dict(self) -> dict[str, Any]:
        return {
            "primitive_id": self.primitive_id,
            "vendor": self.vendor,
            "success": self.success,
            "error_class": self.error_class,
            "executed_at": self.executed_at,
            "redacted": dict(self.redacted),
        }


# Defense-in-depth atop the human gate review: a literal command/op-cmd string
# matching any of these tokens is refused at registration, regardless of the
# declared action class. Word-boundary matched, case-insensitive.
_WRITE_COMMAND_MARKERS = re.compile(
    r"(?i)\b("
    r"set|add|delete|clear|install|commit|edit|config|reboot|shutdown|reset|"
    r"remove|create|modify|update|push|load|import|restore|apply|save|"
    r"request[-_ ]?system|write[-_ ]?memory"
    r")\b"
)


def _redact_text(raw: str) -> dict[str, Any]:
    """Shared redaction rule: never the raw text, only shape + a fingerprint.

    Mirrors ``configuration.checkpoint_config_probe._safe_command_meta`` —
    the same bounded, already-privacy-reviewed summary shape (byte/line
    counts + a SHA-256 fingerprint of the exact text), so no primitive's
    output shape needs its own new privacy argument.
    """
    text = str(raw or "")
    encoded = text.encode("utf-8", errors="ignore")
    return {
        "bytes": len(encoded),
        "lines": len(text.splitlines()),
        "fingerprint_sha256": hashlib.sha256(encoded).hexdigest() if text else None,
    }


def register_primitive(entry: CommandPrimitive) -> CommandPrimitive:
    """Admit one primitive into the registry. Fail-closed.

    Raises ``CommandPrimitiveError`` if the declared action class is not
    ``CLASS_0_READ``, if the literal command text matches a write-command
    marker, or if the gate review's own numeric fields are not sane. This is
    the single choke point every registry entry passes through — a test can
    call it directly with a hostile candidate without needing to import a
    private registry list.
    """
    review = entry.gate_review
    if review.action_class_id != CLASS_0_READ.id:
        raise CommandPrimitiveError(
            f"primitive {entry.primitive_id!r} declares action class "
            f"{review.action_class_id!r}; only {CLASS_0_READ.id!r} may be "
            "registered in this read-only registry"
        )
    if _WRITE_COMMAND_MARKERS.search(entry.command):
        raise CommandPrimitiveError(
            f"primitive {entry.primitive_id!r} command text matches a "
            "write-command marker and cannot be registered as read-only"
        )
    if review.timeout_seconds < 1 or review.timeout_seconds > 120:
        raise CommandPrimitiveError(
            f"primitive {entry.primitive_id!r} timeout_seconds must be in [1, 120]"
        )
    if review.retry_count < 0 or review.retry_count > 2:
        raise CommandPrimitiveError(
            f"primitive {entry.primitive_id!r} retry_count must be in [0, 2]"
        )
    if review.max_frequency_per_endpoint_minutes < 1:
        raise CommandPrimitiveError(
            f"primitive {entry.primitive_id!r} max_frequency_per_endpoint_minutes "
            "must be >= 1"
        )
    if not entry.command.strip():
        raise CommandPrimitiveError(f"primitive {entry.primitive_id!r} has an empty command")
    return entry


# ---------------------------------------------------------------------------
# Concrete primitives — one per major vendor, each the registry's proof entry.
# ---------------------------------------------------------------------------

_CP_GAIA_SHOW_VERSION_ALL = CommandPrimitive(
    primitive_id="cp_gaia_show_version_all",
    vendor="check_point",
    command="clish -c 'show version all'",
    gate_review=CommandGateReview(
        reason=(
            "Compliance checks need vendor/OS version evidence not otherwise "
            "exposed as a first-class collected field. This is the exact "
            "command already gate-reviewed and executed by the 0.6.1A CP "
            "configuration probe "
            "(configuration/checkpoint_config_probe.py "
            "EXPERT_READ_ONLY_COMMANDS['version']) — CE.2 registers it as the "
            "registry's first Check Point proof primitive rather than "
            "opening a new command class."
        ),
        action_class_id=CLASS_0_READ.id,
        vendor_platform_shell_context=(
            "Check Point Gaia; Expert login shell; invoked explicitly via "
            "'clish -c' — never a bare Expert Gaia command."
        ),
        timeout_seconds=20,
        retry_count=0,
        max_frequency_per_endpoint_minutes=60,
        session_reuse=(
            "reuse_existing_ssh_session — one SSH session per physical "
            "endpoint is opened for the whole probe batch and shared by "
            "every admitted Check Point primitive against that endpoint; "
            "never a second connection for a second primitive."
        ),
        unsupported_behavior=(
            "A device that rejects clish (e.g. an estate landing directly in "
            "Clish, or a Spark/Gaia Embedded shell quirk) surfaces "
            "error_class='cli_rejected' or 'command_error' and the primitive "
            "is marked failed; never retried against a different shell."
        ),
        secret_output_risk=(
            "'show version all' output is version/build/edition text; it "
            "does not carry credential or key material. Redaction still "
            "discards all raw content — only byte/line counts and a "
            "SHA-256 fingerprint of the exact text are retained, matching "
            "the 0.6.1A probe's own _safe_command_meta pattern."
        ),
        safe_telemetry=(
            "success, error_class, bytes, lines, fingerprint_sha256 — the "
            "same shape the 0.6.1A probe already surfaces for this exact "
            "command."
        ),
    ),
    redact=_redact_text,
)

_PAN_SHOW_SYSTEM_INFO = CommandPrimitive(
    primitive_id="pan_show_system_info",
    vendor="palo_alto",
    command="<show><system><info></info></system></show>",
    gate_review=CommandGateReview(
        reason=(
            "Compliance checks need PAN-OS system/version evidence read "
            "through the same Panorama-mediated path the product already "
            "trusts. This is the exact op command "
            "configuration/panorama_config_collector.py already issues "
            "(line ~653) for alignment enrichment — CE.2 registers it as a "
            "durably reviewed primitive rather than a one-off call embedded "
            "in a collector."
        ),
        action_class_id=CLASS_0_READ.id,
        vendor_platform_shell_context=(
            "PAN-OS via the Panorama-proxied XML API, type=op, "
            "target=<device serial>; no direct firewall session is opened "
            "by this primitive."
        ),
        timeout_seconds=10,
        retry_count=0,
        max_frequency_per_endpoint_minutes=60,
        session_reuse=(
            "reuse_api_key — one Panorama API key is generated per probe "
            "batch and reused by every admitted Palo Alto primitive in that "
            "batch; never regenerated per primitive."
        ),
        unsupported_behavior=(
            "A device not connected/reachable through Panorama surfaces the "
            "underlying XML API error and the primitive is marked failed; "
            "never retried against a direct firewall session."
        ),
        secret_output_risk=(
            "'show system info' returns hostname/model/sw-version/uptime — "
            "no credential or key material. Redaction discards the raw XML "
            "text entirely — only byte/line counts and a SHA-256 "
            "fingerprint of the canonicalised text are retained."
        ),
        safe_telemetry="success, error_class, bytes, lines, fingerprint_sha256.",
    ),
    redact=_redact_text,
)

_ENTRIES: tuple[CommandPrimitive, ...] = (
    _CP_GAIA_SHOW_VERSION_ALL,
    _PAN_SHOW_SYSTEM_INFO,
)

PRIMITIVE_REGISTRY: dict[str, CommandPrimitive] = {
    entry.primitive_id: register_primitive(entry) for entry in _ENTRIES
}


def primitives_for_vendor(vendor: str) -> list[CommandPrimitive]:
    return [p for p in PRIMITIVE_REGISTRY.values() if p.vendor == vendor]


# ---------------------------------------------------------------------------
# Admission: once per device per run (design section 5). Not a durable
# ledger — persistence across process restarts is explicitly deferred until
# CE.2 is promoted past opt-in probe mode (see
# docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md for the durable-ledger
# precedent a future promotion would reuse). Each primitive's own
# ``max_frequency_per_endpoint_minutes`` is carried for that future ledger;
# the enforceable limit at this maturity is the coarser "once per run".
# ---------------------------------------------------------------------------

class PrimitiveExecutionTracker:
    def __init__(self) -> None:
        self._executed: dict[tuple[str, str], datetime] = {}

    def admit(self, primitive: CommandPrimitive, endpoint_id: str, *, now: datetime | None = None) -> bool:
        key = (primitive.primitive_id, str(endpoint_id))
        if key in self._executed:
            return False
        self._executed[key] = now or datetime.now(timezone.utc)
        return True

    def executed_pairs(self) -> frozenset[tuple[str, str]]:
        return frozenset(self._executed.keys())


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def run_check_point_primitives(
    target: Any,
    *,
    username: str,
    secret: str,
    strict_host_key: bool,
    connect_timeout: int,
    tracker: PrimitiveExecutionTracker,
    primitives: list[CommandPrimitive] | None = None,
) -> list[PrimitiveResult]:
    """Run every admitted Check Point primitive against one physical target.

    Reuses ``configuration.checkpoint_config_probe``'s existing connect/exec
    helpers verbatim — no new SSH transport, no new credential path
    (diagnostic-path law). One SSH session is opened and shared by every
    admitted primitive (the session-reuse gate rule above).
    """
    from configuration.checkpoint_config_probe import _connect, _run_exec

    candidates = primitives if primitives is not None else primitives_for_vendor("check_point")
    endpoint_id = str(getattr(target, "management_ip", "") or "")
    admitted = [p for p in candidates if tracker.admit(p, endpoint_id)]
    if not admitted or not endpoint_id:
        return []

    results: list[PrimitiveResult] = []
    ssh = None
    try:
        ssh, _fingerprint = _connect(
            target, username, secret, strict=strict_host_key, connect_timeout=connect_timeout,
        )
        for primitive in admitted:
            exec_result = _run_exec(ssh, primitive.command, primitive.gate_review.timeout_seconds)
            raw_stdout = str(exec_result.get("stdout") or "")
            redacted = primitive.redact(raw_stdout)
            exec_result["stdout"] = ""
            exec_result["stderr"] = ""
            results.append(PrimitiveResult(
                primitive_id=primitive.primitive_id,
                vendor=primitive.vendor,
                endpoint_id=endpoint_id,
                success=bool(exec_result.get("success")),
                error_class=exec_result.get("error_class"),
                executed_at=_utc_now(),
                redacted=redacted,
            ))
    finally:
        if ssh is not None:
            try:
                ssh.close()
            except Exception:
                pass
    return results


def run_palo_alto_primitives(
    *,
    cfg: Any,
    target_serial: str,
    verify: bool | str,
    tracker: PrimitiveExecutionTracker,
    primitives: list[CommandPrimitive] | None = None,
) -> list[PrimitiveResult]:
    """Run every admitted Palo Alto primitive against one Panorama-managed serial.

    Reuses ``panorama.panorama_runtime_runner``'s existing API-key + op-cmd
    helpers verbatim — no new HTTPS transport, no new credential path
    (diagnostic-path law). One API key is generated and shared by every
    admitted primitive (the session-reuse gate rule above).
    """
    from lxml import etree

    from panorama.panorama_runtime_runner import fix_host, get_api_key, op_cmd

    candidates = primitives if primitives is not None else primitives_for_vendor("palo_alto")
    endpoint_id = str(target_serial or "")
    admitted = [p for p in candidates if tracker.admit(p, endpoint_id)]
    if not admitted or not endpoint_id:
        return []

    host = fix_host(cfg.panorama_ip)
    key = get_api_key(cfg, host, verify=verify)

    results: list[PrimitiveResult] = []
    for primitive in admitted:
        try:
            root = op_cmd(host, key, primitive.command, endpoint_id, verify=verify)
            raw_xml = etree.tostring(root).decode("utf-8", errors="ignore")
            redacted = primitive.redact(raw_xml)
            results.append(PrimitiveResult(
                primitive_id=primitive.primitive_id,
                vendor=primitive.vendor,
                endpoint_id=endpoint_id,
                success=True,
                error_class=None,
                executed_at=_utc_now(),
                redacted=redacted,
            ))
        except Exception as exc:
            results.append(PrimitiveResult(
                primitive_id=primitive.primitive_id,
                vendor=primitive.vendor,
                endpoint_id=endpoint_id,
                success=False,
                error_class=type(exc).__name__,
                executed_at=_utc_now(),
                redacted={},
            ))
    return results
