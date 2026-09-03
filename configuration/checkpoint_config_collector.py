from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import socket
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

import paramiko

from configuration.checkpoint_config_probe import (
    EXPERT_READ_ONLY_COMMANDS,
    ProbeTarget,
    _connect,
    _identity_gate,
    _parse_hostname,
    _run_exec,
    _run_vsx_clish_context,
)
from utils.cp_ssh_trust import CpSshStrictPreflightError
from utils.config_evidence import ConfigEvidenceStore
from utils.logger import info, warn, register_sensitive_value, user_fingerprint
from utils.runtime_paths import default_output_root

BASE_DIR = Path(__file__).resolve().parent.parent
OUTPUT_DIR = default_output_root(repository_root=BASE_DIR)

PHASE = "0.6.1B.1.2"
COLLECTOR_VERSION = "0.6.1B.1.2"
SOURCE = "checkpoint-gaia"
PHYSICAL_ARTIFACT_TYPE = "gaia_show_configuration_redacted"
VSX_ARTIFACT_TYPE = "gaia_vsx_context_show_configuration_redacted"
PHYSICAL_METHOD = "direct_ssh_interactive_adaptive_gaia_clish_show_configuration"
VSX_METHOD = "direct_ssh_expert_vsenv_clish_show_configuration"
VSX_FALLBACK_METHOD = "direct_ssh_expert_clish_set_virtual_system_show_configuration"

# Deliberately conservative. A false-positive redaction costs visibility; a
# false-negative can expose a credential. The initial CP projection therefore
# prefers withholding ambiguous key/secret material.
SECRET_LINE_RE = re.compile(
    r"(?i)(?:password|passwd|secret|community|credential|token|psk|pre[-_ ]?shared|"
    r"private[-_ ]?key|api[-_ ]?key|auth(?:entication)?[-_ ]?key|encrypted[-_ ]?secret|"
    r"(?:^|[\s_-])key(?:$|[\s_-]))"
)

# 0.7.2: `set password-controls <knob> ...` lines match SECRET_LINE_RE ("password")
# but carry only non-secret policy knobs. This allowlist re-admits exactly those
# knob lines; anything else matching SECRET_LINE_RE stays withheld.
PASSWORD_POLICY_SAFE_RE = re.compile(
    r"(?i)^set\s+password-controls\s+(?:"
    r"min-password-length|password-min-length|complexity|palindrome-check|history-check|"
    r"password-history|password-expiration|expiration-warning-days|expiration-lockout-days|"
    r"deny-on-fail|deny-on-nonuse|force-change-when|password-format"
    r")\b"
)

# 0.7.2: the banner / MOTD body is a local-operator (potentially identifying)
# string. Keep the presence/on-off token, drop everything from `msgvalue` on,
# before the line reaches the redacted artifact or the shareable bundle.
MESSAGE_BODY_RE = re.compile(
    r"(?i)^(set\s+message\s+\S+(?:\s+(?:on|off))?)\s+msgvalue\s+.*$"
)

CLI_UNSUPPORTED_PATTERNS = (
    "command not found",
    "unknown command",
    "invalid command",
    "syntax error",
    "not a valid command",
    "unsupported command",
    "not supported",
)

CLI_AUTHORIZATION_PATTERNS = (
    "permission denied",
    "not authorized",
    "authorization failed",
    "insufficient permissions",
)

CLI_ERROR_PATTERNS = CLI_UNSUPPORTED_PATTERNS + CLI_AUTHORIZATION_PATTERNS

SECTION_ORDER = (
    "system",
    "dns",
    "ntp",
    "management",
    "password_policy",
    "banner",
    "services",
    "logging",
    "high_availability",
    "interfaces",
    "routing",
    "snmp",
    "authentication",
    "other",
)
SECTION_LABELS = {
    "system": "System",
    "dns": "DNS",
    "ntp": "NTP",
    "management": "Management",
    "password_policy": "Password Policy",
    "banner": "Login Banner",
    "services": "Management Services",
    "logging": "Logging",
    "high_availability": "High Availability",
    "interfaces": "Interfaces",
    "routing": "Routing",
    "snmp": "SNMP",
    "authentication": "Authentication",
    "other": "Other Gaia Configuration",
}


@dataclass
class VsContext:
    vs_id: str
    vs_name: str | None = None


@dataclass
class PhysicalTarget:
    device: str
    management_ip: str
    object_type: str
    entity_type: str
    cma: str | None = None
    cluster_group_id: str | None = None
    cluster_display_name: str | None = None
    presentation_group_id: str | None = None
    presentation_group_label: str | None = None
    presentation_group_source: str | None = None
    management_state: str | None = None
    selection_source: str = "management_discovery"
    contexts: list[VsContext] = field(default_factory=list)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off", "disabled"}


def _env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


def _load_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return default


def _boolish(value: Any) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def _is_vsx_status(row: dict[str, Any]) -> bool:
    return _boolish(row.get("vsx_cluster_member")) or _boolish(row.get("vs_cluster_member"))


def _sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8", errors="ignore")).hexdigest()


def _canonical_set_lines(stdout: str) -> list[str]:
    rows = []
    for raw in str(stdout or "").splitlines():
        line = raw.strip()
        if not line.startswith("set "):
            continue
        # Interactive VSX Clish may echo the context-selector command. It is
        # control flow, not configuration evidence.
        if re.fullmatch(r"set\s+virtual-system\s+\d+", line, re.IGNORECASE):
            continue
        rows.append(line)
    return rows


def _sanitize_configuration(stdout: str) -> dict[str, Any]:
    """Create a redacted, history-safe representation of Gaia Clish output.

    The raw canonical hash is intentionally embedded in the redacted artifact.
    It lets secret-only changes create a new immutable history event without
    persisting the secret-bearing source line itself.
    """
    set_lines = _canonical_set_lines(stdout)
    raw_canonical = "\n".join(set_lines)
    raw_hash = _sha256_text(raw_canonical) if set_lines else None

    def _withheld(line: str) -> bool:
        # 0.7.2: password-controls policy knobs are re-admitted even though they
        # contain the substring "password".
        if PASSWORD_POLICY_SAFE_RE.match(line):
            return False
        return bool(SECRET_LINE_RE.search(line))

    def _display(line: str) -> str:
        # 0.7.2: collapse the banner / MOTD body; keep the presence token.
        return MESSAGE_BODY_RE.sub(r"\1 msgvalue [SECURITYEXPERT BANNER BODY WITHHELD]", line)

    safe_lines: list[str] = []
    safe_set_lines: list[str] = []
    withheld = 0
    for line in set_lines:
        if _withheld(line):
            withheld += 1
            safe_lines.append("# [SECURITYEXPERT SECRET-BEARING CONFIGURATION LINE WITHHELD]")
            continue
        display = _display(line)
        safe_lines.append(display)
        safe_set_lines.append(display)

    header = [
        "# SecurityExpert Check Point Gaia configuration evidence (redacted)",
        "# schema=checkpoint-gaia-redacted-v1",
        f"# raw-canonical-sha256={raw_hash or 'unavailable'}",
        f"# secret-bearing-lines-withheld={withheld}",
    ]
    sanitized_text = "\n".join(header + safe_lines).rstrip() + "\n"
    return {
        "safe_set_lines": safe_set_lines,
        "sanitized_text": sanitized_text,
        "raw_canonical_sha256": raw_hash,
        "set_line_count": len(set_lines),
        "safe_set_line_count": len(set_lines) - withheld,
        "secret_bearing_line_count": withheld,
    }


def _pretty(value: str) -> str:
    return " ".join(word.capitalize() for word in str(value or "").replace("_", "-").split("-") if word)


def _section_for(tokens: list[str]) -> str:
    if not tokens:
        return "other"
    head = tokens[0].lower()
    if head in {"hostname", "domainname", "timezone", "time", "clock"}:
        return "system"
    if head == "password-controls":
        return "password_policy"
    if head in {"message", "banner"}:
        return "banner"
    if head == "dns":
        return "dns"
    if head == "ntp":
        return "ntp"
    if head in {"interface", "bonding", "bridge", "vlan"}:
        return "interfaces"
    if head in {"static-route", "route", "routing", "ospf", "bgp", "rip"}:
        return "routing"
    if head in {"snmp"}:
        return "snmp"
    if head in {"syslog", "log", "logging"}:
        return "logging"
    if head in {"cluster", "clusterxl", "ha", "high-availability"}:
        return "high_availability"
    if head in {"user", "aaa", "radius", "tacacs", "ldap", "authentication"}:
        return "authentication"
    if head in {"web", "ssh", "allowed-client", "management", "inactivity-timeout", "expert-password"}:
        return "management"
    return "other"


def _setting_value(tokens: list[str]) -> tuple[str, str]:
    if not tokens:
        return "Setting", "—"
    head = tokens[0].lower()
    rest = tokens[1:]
    if head == "password-controls" and rest:
        return "Password · " + _pretty(rest[0]), " ".join(rest[1:]) or "enabled"
    if head == "message" and rest:
        # The banner / MOTD body is redacted upstream; project presence only.
        on_off = next((tok.lower() for tok in rest[1:] if tok.lower() in {"on", "off"}), None)
        return _pretty(rest[0]), "absent" if on_off == "off" else "present"
    if head == "banner" and rest:
        return "Banner", "present"
    if head == "hostname":
        return "Hostname", " ".join(rest) or "—"
    if head == "domainname":
        return "Domain", " ".join(rest) or "—"
    if head == "timezone":
        return "Timezone", " ".join(rest) or "—"
    if head == "dns" and rest:
        qualifier = rest[0].lower()
        label = {"primary": "Primary DNS", "secondary": "Secondary DNS"}.get(qualifier, f"DNS · {_pretty(qualifier)}")
        return label, " ".join(rest[1:]) or "—"
    if head == "ntp" and rest:
        lower = [part.lower() for part in rest]
        if "primary" in lower:
            label = "Primary NTP Server"
            idx = lower.index("primary")
            value_parts = rest[idx + 1:]
        elif "secondary" in lower:
            label = "Secondary NTP Server"
            idx = lower.index("secondary")
            value_parts = rest[idx + 1:]
        else:
            label = "NTP · " + _pretty(rest[0])
            value_parts = rest[1:]
        return label, " ".join(value_parts) or " ".join(rest) or "—"
    if head == "interface" and len(rest) >= 1:
        iface = rest[0]
        setting = "Interface " + iface
        if len(rest) >= 2:
            setting += " · " + _pretty(rest[1])
        return setting, " ".join(rest[2:]) or "—"
    if head in {"static-route", "route"} and rest:
        return "Static Route · " + rest[0], " ".join(rest[1:]) or "—"
    if head == "snmp" and rest:
        return "SNMP · " + _pretty(rest[0]), " ".join(rest[1:]) or "—"
    if head in {"syslog", "log", "logging"} and rest:
        return _pretty(head) + " · " + _pretty(rest[0]), " ".join(rest[1:]) or "—"

    identity = " · ".join(_pretty(part) for part in tokens[:2])
    value = " ".join(tokens[2:]) if len(tokens) > 2 else (" ".join(tokens[1:]) if len(tokens) > 1 else "enabled")
    return identity or "Setting", value or "—"


def _safe_tokens(line: str) -> list[str]:
    try:
        tokens = shlex.split(line, posix=True)
    except ValueError:
        tokens = line.split()
    if tokens and tokens[0].lower() == "set":
        return tokens[1:]
    return []


def build_checkpoint_current_configuration(
    safe_set_lines: list[str],
    *,
    secret_bearing_line_count: int,
    entity_type: str,
    context_label: str | None = None,
) -> dict[str, Any]:
    sections: dict[str, list[dict[str, Any]]] = {name: [] for name in SECTION_ORDER}
    for line in safe_set_lines:
        tokens = _safe_tokens(line)
        if not tokens:
            continue
        section = _section_for(tokens)
        setting, value = _setting_value(tokens)
        sections[section].append({
            "setting": setting,
            "value": value,
            "origin": "local",
            "context": context_label,
            "member_specific": False,
            "_key": " ".join(tokens[:-1] if len(tokens) > 1 else tokens).lower(),
        })

    result_sections: list[dict[str, Any]] = []
    for section_id in SECTION_ORDER:
        rows = sections.get(section_id) or []
        if not rows:
            continue
        rows.sort(key=lambda row: (str(row.get("setting") or "").lower(), str(row.get("value") or "")))
        result_sections.append({
            "id": section_id,
            "label": SECTION_LABELS[section_id],
            "settings": rows,
            "count": len(rows),
        })

    highlights = []
    wanted = (
        ("system", "Hostname"),
        ("system", "Domain"),
        ("system", "Timezone"),
        ("dns", "Primary DNS"),
        ("dns", "Secondary DNS"),
        ("ntp", "Primary NTP Server"),
        ("ntp", "Secondary NTP Server"),
    )
    for section_id, label in wanted:
        match = next((row for row in sections.get(section_id, []) if row.get("setting") == label), None)
        if match:
            highlights.append({
                "label": label,
                "value": match.get("value"),
                "section": section_id,
                "section_label": SECTION_LABELS[section_id],
                "origin": match.get("origin"),
                "context": match.get("context"),
            })

    return {
        "schema_version": "0.6.1B",
        "status": "available",
        "vendor": "check_point",
        "source_plane": "gaia-clish-show-configuration",
        "entity_type": entity_type,
        "sections": result_sections,
        "section_index": [
            {"id": section["id"], "label": section["label"], "count": section["count"]}
            for section in result_sections
        ],
        "highlights": highlights,
        "setting_count": sum(section["count"] for section in result_sections),
        "redacted_secret_setting_count": int(secret_bearing_line_count),
        "projection_scope": "gaia_safe_set_commands_vendor_neutral_sections",
        "native_view": {
            "status": "deferred",
            "reason": "raw_gaia_configuration_contains_secret_bearing_lines_and_requires_authorized_native_view",
        },
        "structured_values_included": True,
        "raw_config_included": False,
        "secrets_redacted": True,
    }


def _parse_gaia_version(stdout: str) -> str | None:
    match = re.search(r"\bR\d{2}(?:\.\d{2})?(?:\.\d{2})?\b", str(stdout or ""), re.IGNORECASE)
    return match.group(0).upper() if match else None


def _parse_asset_field(stdout: str, labels: tuple[str, ...]) -> str | None:
    text = str(stdout or "")
    for label in labels:
        patterns = (
            rf"(?im)^\s*{re.escape(label)}\s*[:=]\s*(\S[^\r\n]*)$",
            rf"(?im)^\s*{re.escape(label)}\s{{2,}}(\S[^\r\n]*)$",
        )
        for pattern in patterns:
            match = re.search(pattern, text)
            if match:
                value = match.group(1).strip()
                if value and value not in {"-", "--", "N/A", "n/a"}:
                    return value
    return None


def _asset_key_values(stdout: str) -> list[tuple[str, str]]:
    """Parse common Gaia/Embedded asset layouts without vendor-schema guessing."""
    pairs: list[tuple[str, str]] = []
    for raw in str(stdout or "").splitlines():
        line = raw.strip()
        if not line:
            continue
        key = value = None
        for sep in (":", "="):
            if sep in line:
                left, right = line.split(sep, 1)
                if left.strip() and right.strip():
                    key, value = left.strip(), right.strip()
                    break
        if key is None:
            match = re.match(r"^(.+?)\s{2,}(\S.*)$", line)
            if match:
                key, value = match.group(1).strip(), match.group(2).strip()
        if key and value:
            pairs.append((key, value))
    return pairs


def _parse_asset_semantic(stdout: str, kind: str) -> str | None:
    """Extract only explicit model/serial identity fields from asset evidence."""
    for key, value in _asset_key_values(stdout):
        norm = re.sub(r"[^a-z0-9]+", " ", key.lower()).strip()
        if kind == "serial" and "serial" in norm:
            return value if value.lower() not in {"n/a", "na", "none", "-", "--"} else None
        if kind == "model" and ("model" in norm or norm in {"appliance", "product name"}):
            return value if value.lower() not in {"n/a", "na", "none", "-", "--"} else None
    return None


# Quantum Spark / Gaia Embedded classification is evidence-driven. Explicit
# product/OS markers are strongest. Exact appliance-family tokens are only a
# medium-confidence hint; they are never used to change configuration values.
SPARK_MODEL_TOKENS = {
    "1500", "1530", "1550", "1570", "1590", "1600", "1800", "1900", "2000"
}


def _result_text(result: dict[str, Any]) -> str:
    return (str(result.get("stdout") or "") + "\n" + str(result.get("stderr") or "")).strip()


def _looks_like_cli_error(result: dict[str, Any]) -> bool:
    text = _result_text(result).lower()
    return any(pattern in text for pattern in CLI_ERROR_PATTERNS)


def _usable_clish_result(result: dict[str, Any], *, require_set_lines: bool = False) -> bool:
    if not result.get("success") or _looks_like_cli_error(result):
        return False
    if require_set_lines:
        return bool(_canonical_set_lines(str(result.get("stdout") or "")))
    return bool(str(result.get("stdout") or "").strip())


ANSI_ESCAPE_RE = re.compile(r"\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])")


def _strip_terminal_control(value: str) -> str:
    text = ANSI_ESCAPE_RE.sub("", str(value or ""))
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _interactive_prompt_candidate(value: str) -> str | None:
    """Return the last plausible prompt line for synchronization only.

    Prompt shape is never used to decide whether the shell is Clish or Expert;
    command success remains the capability evidence. The prompt is only a
    framing token so long-running interactive commands are not cut off on a
    quiet-period heuristic.
    """
    lines = [line.strip() for line in _strip_terminal_control(value).splitlines() if line.strip()]
    for line in reversed(lines):
        if len(line) <= 240 and line.endswith((">", "#", "$")):
            return line
    return None


class InteractiveSshSession:
    """One authenticated PTY-backed SSH shell with bounded read-only execution.

    Some Gaia Embedded / Quantum Spark appliances accept an interactive shell
    but reject SSH exec requests. This adapter mirrors a normal operator SSH
    session while keeping command dispatch explicitly allow-listed by callers.
    """

    def __init__(self, ssh: paramiko.SSHClient, timeout_seconds: int):
        self.ssh = ssh
        self.timeout_seconds = max(3, int(timeout_seconds))
        self.channel = ssh.invoke_shell(term="vt100", width=4096, height=10000)
        try:
            self.channel.settimeout(0.0)
        except Exception:
            pass
        self.prompt = None
        # Drain banner and ask the server to repaint its prompt. This does not
        # execute a device command and works for both Expert and Clish logins.
        self._read_until_quiet(min(self.timeout_seconds, 5), quiet_seconds=0.35)
        try:
            self.channel.send("\n")
        except Exception:
            pass
        painted = self._read_until_quiet(min(self.timeout_seconds, 5), quiet_seconds=0.35)
        self.prompt = _interactive_prompt_candidate(painted)

    def close(self):
        try:
            self.channel.close()
        except Exception:
            pass

    def _drain_ready(self):
        try:
            while self.channel.recv_ready():
                self.channel.recv(65535)
            while self.channel.recv_stderr_ready():
                self.channel.recv_stderr(65535)
        except Exception:
            return

    def _read_until_quiet(self, timeout_seconds: int, *, quiet_seconds: float = 0.75) -> str:
        chunks: list[bytes] = []
        deadline = time.monotonic() + max(1, timeout_seconds)
        last_data = time.monotonic()
        saw_data = False
        while time.monotonic() < deadline:
            got = False
            try:
                while self.channel.recv_ready():
                    chunks.append(self.channel.recv(65535))
                    got = True
                    saw_data = True
                # invoke_shell merges normal terminal output, but retain this
                # defensive path for server implementations exposing stderr.
                while self.channel.recv_stderr_ready():
                    chunks.append(self.channel.recv_stderr(65535))
                    got = True
                    saw_data = True
            except Exception:
                pass
            if got:
                last_data = time.monotonic()
            elif saw_data and time.monotonic() - last_data >= quiet_seconds:
                break
            time.sleep(0.04)
        return b"".join(chunks).decode("utf-8", errors="ignore")

    def run(self, command: str, timeout_seconds: int) -> dict[str, Any]:
        started = time.monotonic()
        normalized = str(command or "").strip()
        if not normalized or "\n" in normalized or "\r" in normalized:
            return {
                "success": False, "error_class": "invalid_interactive_command", "error_detail": None,
                "timeout": False, "exit_status": None, "duration_ms": 0, "stdout": "", "stderr": "",
            }
        self._drain_ready()
        try:
            self.channel.send(normalized + "\n")
        except Exception as exc:
            return {
                "success": False, "error_class": "execution_error", "error_detail": type(exc).__name__,
                "timeout": False, "exit_status": None,
                "duration_ms": int((time.monotonic() - started) * 1000), "stdout": "", "stderr": "",
            }

        chunks: list[bytes] = []
        deadline = time.monotonic() + max(1, timeout_seconds)
        last_data = time.monotonic()
        saw_data = False
        completed = False
        while time.monotonic() < deadline:
            got = False
            try:
                while self.channel.recv_ready():
                    chunks.append(self.channel.recv(65535))
                    got = True
                    saw_data = True
            except Exception:
                pass
            if got:
                last_data = time.monotonic()
                current = _strip_terminal_control(b"".join(chunks).decode("utf-8", errors="ignore"))
                if self.prompt and current.rstrip().endswith(self.prompt):
                    completed = True
                    break
            elif saw_data and not self.prompt and time.monotonic() - last_data >= 1.25:
                # Fallback only when a stable prompt could not be learned.
                completed = True
                break
            time.sleep(0.04)

        timed_out = not completed
        text = _strip_terminal_control(b"".join(chunks).decode("utf-8", errors="ignore"))
        # Refresh prompt if the platform/context changed it. Prompt is framing,
        # not capability evidence.
        observed_prompt = _interactive_prompt_candidate(text)
        if observed_prompt:
            self.prompt = observed_prompt

        lines = text.splitlines()
        # Remove a terminal-echoed command only when it is an exact line match.
        if lines and lines[0].strip() == normalized:
            lines = lines[1:]
        if self.prompt and lines and lines[-1].strip() == self.prompt:
            lines = lines[:-1]
        stdout = "\n".join(lines).strip()
        cli_error = any(pattern in stdout.lower() for pattern in CLI_ERROR_PATTERNS)
        if timed_out:
            error_class = "timeout"
            success = False
        elif cli_error:
            error_class = "cli_rejected"
            success = False
        elif not stdout:
            error_class = "empty_output"
            success = False
        else:
            error_class = "none"
            success = True
        return {
            "success": success,
            "error_class": error_class,
            "error_detail": None,
            "timeout": timed_out,
            "exit_status": None,
            "duration_ms": int((time.monotonic() - started) * 1000),
            "stdout": stdout,
            "stderr": "",
        }


def _detect_gaia_shell_interactive(session: InteractiveSshSession, timeout_seconds: int) -> tuple[str, dict[str, Any]]:
    """Prove the current interactive command surface with read-only commands."""
    direct = session.run("show hostname", timeout_seconds)
    if _usable_clish_result(direct):
        return "interactive_direct_clish", direct

    expert = session.run(f"clish -c {shlex.quote('show hostname')}", timeout_seconds)
    if _usable_clish_result(expert):
        direct["stdout"] = direct["stderr"] = ""
        return "interactive_expert_explicit_clish", expert

    chosen = direct if (_looks_like_cli_error(direct) or direct.get("error_class") not in {None, "none"}) else expert
    other = expert if chosen is direct else direct
    other["stdout"] = other["stderr"] = ""
    return "unknown", chosen


# 0.6.1B.1.3 safety finding: "show asset" (both "all" and the narrower
# "system" flavor) was observed crashing/hanging Take 120 gateways — this is
# not scoped to "all"'s extra enumeration, the asset subsystem itself
# (xpand asset_get_proc) is affected regardless of flavor on that estate.
# "cpstat os -f hw_info" reads the same identity data through a completely
# separate Check Point subsystem and is documented as safe to run from Gaia
# Clish, not only Expert mode. It is the ONLY non-"show" command allowed
# through the read-only gates below — an explicit, literal exception, not a
# general widening of what commands may run.
_NON_SHOW_ALLOWED_READ_COMMANDS = frozenset({"cpstat os -f hw_info"})


def _is_allowed_read_command(normalized: str) -> bool:
    return normalized.lower().startswith("show ") or normalized in _NON_SHOW_ALLOWED_READ_COMMANDS


def _run_gaia_interactive_read(
    session: InteractiveSshSession,
    command: str,
    timeout_seconds: int,
    shell_mode: str,
    *,
    require_set_lines: bool = False,
) -> tuple[dict[str, Any], str]:
    normalized = " ".join(str(command or "").strip().split())
    if not _is_allowed_read_command(normalized):
        raise ValueError(f"Non-read-only Gaia command rejected: {normalized!r}")
    if shell_mode == "interactive_direct_clish":
        wire_command = normalized
    elif shell_mode == "interactive_expert_explicit_clish":
        wire_command = f"clish -c {shlex.quote(normalized)}"
    else:
        return {"success": False, "error_class": "unknown_shell", "stdout": "", "stderr": ""}, "unavailable"
    result = session.run(wire_command, timeout_seconds)
    return result, shell_mode if _usable_clish_result(result, require_set_lines=require_set_lines) else "unavailable"


def _run_gaia_interactive_first_supported(
    session: InteractiveSshSession,
    commands: tuple[str, ...],
    timeout_seconds: int,
    shell_mode: str,
) -> tuple[dict[str, Any], str, str | None]:
    last = None
    last_mode = "unavailable"
    for command in commands:
        result, mode = _run_gaia_interactive_read(session, command, timeout_seconds, shell_mode)
        if _usable_clish_result(result):
            return result, mode, command
        if last is not None:
            last["stdout"] = last["stderr"] = ""
        last, last_mode = result, mode
    return last or {"success": False, "error_class": "no_supported_command", "stdout": "", "stderr": ""}, last_mode, None


def _detect_gaia_shell(ssh, timeout_seconds: int) -> tuple[str, dict[str, Any]]:
    """Detect the authenticated SSH command surface using read-only evidence.

    Detection is capability-based rather than prompt-based. A direct Clish
    login is proven by a successful ``show hostname``. Otherwise the same
    command is attempted through explicit ``clish -c`` for Expert-login
    estates. No write verb or interactive prompt parsing is involved.
    """
    direct = _run_exec(ssh, "show hostname", timeout_seconds)
    if _usable_clish_result(direct):
        return "direct_login_clish", direct

    expert = _run_exec(ssh, f"clish -c {shlex.quote('show hostname')}", timeout_seconds)
    if _usable_clish_result(expert):
        direct["stdout"] = direct["stderr"] = ""
        return "expert_explicit_clish", expert

    # Keep the more useful diagnostic result, but never preserve raw output.
    chosen = direct if (_looks_like_cli_error(direct) or direct.get("error_class") not in {None, "none"}) else expert
    other = expert if chosen is direct else direct
    other["stdout"] = other["stderr"] = ""
    return "unknown", chosen


def _run_gaia_read_mode(ssh, command: str, timeout_seconds: int, shell_mode: str, *, require_set_lines: bool = False) -> tuple[dict[str, Any], str]:
    normalized = " ".join(str(command or "").strip().split())
    if not _is_allowed_read_command(normalized):
        raise ValueError(f"Non-read-only Gaia command rejected: {normalized!r}")

    if shell_mode == "direct_login_clish":
        result = _run_exec(ssh, normalized, timeout_seconds)
        return result, shell_mode if _usable_clish_result(result, require_set_lines=require_set_lines) else "unavailable"
    if shell_mode == "expert_explicit_clish":
        result = _run_exec(ssh, f"clish -c {shlex.quote(normalized)}", timeout_seconds)
        return result, shell_mode if _usable_clish_result(result, require_set_lines=require_set_lines) else "unavailable"

    # Unknown shell: one safe adaptive retry sequence. Direct Clish first
    # because that path cannot invoke an Expert shell command.
    direct = _run_exec(ssh, normalized, timeout_seconds)
    if _usable_clish_result(direct, require_set_lines=require_set_lines):
        return direct, "direct_login_clish"
    expert = _run_exec(ssh, f"clish -c {shlex.quote(normalized)}", timeout_seconds)
    if _usable_clish_result(expert, require_set_lines=require_set_lines):
        direct["stdout"] = direct["stderr"] = ""
        return expert, "expert_explicit_clish"
    chosen = direct if (_looks_like_cli_error(direct) or direct.get("error_class") not in {None, "none"}) else expert
    other = expert if chosen is direct else direct
    other["stdout"] = other["stderr"] = ""
    return chosen, "unavailable"


def _run_gaia_read(ssh, command: str, timeout_seconds: int, *, require_set_lines: bool = False, shell_mode: str | None = None) -> tuple[dict[str, Any], str]:
    """Compatibility wrapper.

    Existing callers without an observed shell retain the B.1 Expert-first
    adapter. The production collector passes its handshake result explicitly.
    """
    if shell_mode is not None:
        return _run_gaia_read_mode(ssh, command, timeout_seconds, shell_mode, require_set_lines=require_set_lines)
    normalized = " ".join(str(command or "").strip().split())
    if not normalized.lower().startswith("show "):
        raise ValueError(f"Non-read-only Gaia command rejected: {normalized!r}")
    expert = _run_exec(ssh, f"clish -c {shlex.quote(normalized)}", timeout_seconds)
    if _usable_clish_result(expert, require_set_lines=require_set_lines):
        return expert, "expert_explicit_clish"
    direct = _run_exec(ssh, normalized, timeout_seconds)
    if _usable_clish_result(direct, require_set_lines=require_set_lines):
        expert["stdout"] = expert["stderr"] = ""
        return direct, "direct_login_clish"
    chosen = direct if (_looks_like_cli_error(direct) or direct.get("error_class") not in {None, "none"}) else expert
    other = expert if chosen is direct else direct
    other["stdout"] = other["stderr"] = ""
    return chosen, "unavailable"


def _run_gaia_first_supported(ssh, commands: tuple[str, ...], timeout_seconds: int, shell_mode: str) -> tuple[dict[str, Any], str, str | None]:
    """Run ordered read-only command variants and return the first usable one."""
    last = None
    last_mode = "unavailable"
    for command in commands:
        result, mode = _run_gaia_read_mode(ssh, command, timeout_seconds, shell_mode)
        if _usable_clish_result(result):
            return result, mode, command
        if last is not None:
            last["stdout"] = last["stderr"] = ""
        last, last_mode = result, mode
    return last or {"success": False, "error_class": "no_supported_command", "stdout": "", "stderr": ""}, last_mode, None


def _model_token(value: Any) -> str | None:
    text = str(value or "").upper()
    # Exact 4-digit family/model tokens avoid confusing 1500 with 15000.
    for token in re.findall(r"(?<!\d)(\d{4})(?!\d)", text):
        if token in SPARK_MODEL_TOKENS:
            return token
    return None


def _model_token_from_evidence(value: Any) -> str | None:
    # Do not treat an arbitrary four-digit year/build value as an appliance
    # model. Only inspect lines that actually describe product/model identity.
    for raw in str(value or "").splitlines():
        low = raw.lower()
        if not any(marker in low for marker in ("model", "appliance", "product", "quantum spark")):
            continue
        token = _model_token(raw)
        if token:
            return token
    return None


def _classify_platform(*, version_stdout: str, asset_stdout: str, model: str | None) -> dict[str, Any]:
    evidence_text = "\n".join([str(version_stdout or ""), str(asset_stdout or ""), str(model or "")])
    text = evidence_text.lower()
    explicit_embedded = any(marker in text for marker in ("gaia embedded", "embedded gaia", "quantum spark", "spark appliance"))
    # A Spark family token can appear in version/asset output even when
    # ``show asset system`` uses a layout we do not yet parse into a Model scalar.
    # Exact 4-digit tokens on identity-bearing lines remain a conservative hint
    # (1500 != 15000, and a copyright year is not a model).
    model_hint = _model_token(model) or _model_token_from_evidence(evidence_text)
    if explicit_embedded:
        return {"family": "gaia_embedded", "label": "Quantum Spark / Gaia Embedded", "confidence": "HIGH", "evidence": "explicit_product_or_os_marker"}
    if model_hint:
        return {"family": "gaia_embedded", "label": "Quantum Spark / Gaia Embedded", "confidence": "MEDIUM", "evidence": f"spark_model_token:{model_hint}"}
    if "gaia" in text or _parse_gaia_version(version_stdout):
        return {"family": "gaia", "label": "Gaia", "confidence": "MEDIUM", "evidence": "gaia_version_evidence"}
    return {"family": "unknown", "label": "Check Point platform", "confidence": "LOW", "evidence": "insufficient_platform_evidence"}


CLUSTERXL_RUNTIME_STATES = (
    "ACTIVE ATTENTION", "STANDBY READY", "ACTIVE", "STANDBY", "READY", "DOWN", "BACKUP", "LOST",
)


#: Cluster mode vocabulary (OP.0a contract decision P2). ``cphaprob stat``
#: reports the mode in its header; the distinction is safety-critical, not
#: cosmetic -- a Load Sharing cluster has no standby, so "fail it over" is not
#: a coherent request (FAILOVER_ENGINE_ARCHITECTURE.md 3.1). Fail closed:
#: anything unrecognised is "unknown", never a guess.
CLUSTERXL_CLUSTER_MODES = (
    "ha_new_mode", "load_sharing_unicast", "load_sharing_multicast", "vrrp",
    "vsx_single_vs_failover", "unknown",
)


def _parse_clusterxl_cluster_mode(stdout: str) -> str:
    """Extract the cluster mode from ``cphaprob stat`` output already in hand.

    OP.0a decision P2: this is a parse-scope extension, NOT a new device
    command. The caller has already run ``cphaprob stat`` for the role parse;
    this reads the mode out of the same buffer before it is discarded. No
    hostname, interface name or address is ever returned -- only one of
    ``CLUSTERXL_CLUSTER_MODES``.

    OP.0b S3: ``"Single VS Failover"`` (sk112712, VSX HA, non-VSLS) is a
    distinct enum value, ``vsx_single_vs_failover`` -- never folded into
    ``ha_new_mode``. It is a different mode string with different semantics
    (per-VS failover scope vs. whole-cluster), and the contract's own
    normalization rule (§12: never rename raw stored enums, never conflate
    a new mode string with an existing one) forbids collapsing it.
    """
    for raw in str(stdout or "").splitlines():
        line = " ".join(raw.strip().split()).lower()
        if not line or "mode" not in line:
            continue
        # Order matters: the load-sharing variants must be tested before the
        # bare "load sharing" fallback, VSX single-VS-failover before the
        # generic "high availability"/"new mode" match (it never contains
        # either phrase, but keeping the ordering explicit documents intent),
        # and "high availability" last so a line naming both cannot be
        # misread as HA.
        if "load sharing" in line or "load-sharing" in line:
            if "multicast" in line:
                return "load_sharing_multicast"
            if "unicast" in line or "pivot" in line:
                return "load_sharing_unicast"
            return "unknown"
        if "vrrp" in line:
            return "vrrp"
        if "single vs failover" in line:
            return "vsx_single_vs_failover"
        if "high availability" in line or "new mode" in line:
            return "ha_new_mode"
    return "unknown"


def _parse_clusterxl_runtime_role(stdout: str, observed_hostname: str | None) -> str | None:
    hostname = str(observed_hostname or "").strip().lower().rstrip(".")
    for raw in str(stdout or "").splitlines():
        line = " ".join(raw.strip().split())
        if not line:
            continue
        low = line.lower()
        local_line = "(local)" in low
        if hostname:
            host_token = hostname.split(".", 1)[0]
            local_line = local_line or bool(re.search(rf"(?<![a-z0-9_.-]){re.escape(host_token)}(?![a-z0-9_.-])", low))
        if not local_line:
            continue
        upper = line.upper()
        for state in CLUSTERXL_RUNTIME_STATES:
            if re.search(rf"(?<![A-Z]){re.escape(state)}(?![A-Z])", upper):
                return state
    return None


def _parse_clusterxl_stat_preflight_fields(stdout: str, observed_hostname: str | None) -> dict[str, Any]:
    """OP.0b S3: parse additional contract-authorized fields out of the same
    ``cphaprob stat`` buffer the caller already holds -- no new command, no
    new SSH invocation, same buffer `_parse_clusterxl_runtime_role`/
    `_parse_clusterxl_cluster_mode` already read.

    Single extraction authority: local role and cluster mode are read via
    those two existing functions (never reinterpreted a second way here).
    This function's only new territory -- nothing else in the repository
    parses it today -- is:

    - ``peer_row_states``: the **State** column of each non-local member row
      (contract "Identity contract / Check Point" -- "'Unique Address' is
      not identity-grade"; peer name is presentation only). Only the state
      token is extracted, never an address or name. Returns ``()`` when only
      the local row is present, the buffer has no recognizable member rows,
      or `stdout` is empty/unparseable -- one member's own read must never
      synthesize a peer observation (contract domain invariant 4; task S3
      §17 test 10).
    - ``local_attention``: a boolean derived from the *already-parsed* local
      role token (``True`` iff it is ``"ACTIVE ATTENTION"`` or ``"DOWN"``,
      ``None`` iff the local role itself could not be determined) -- category
      J (failure/health) corroboration for the same evidence category D
      already captures, per the frozen contract's own command-surface row
      (§24 A3: ``cphaprob stat`` -> "D, corroborating E/J"). No new text is
      read to produce it; it is a reclassification of the existing role
      token, never a guess.
    """
    local_role = _parse_clusterxl_runtime_role(stdout, observed_hostname)

    hostname = str(observed_hostname or "").strip().lower().rstrip(".")
    host_token = hostname.split(".", 1)[0] if hostname else None

    peer_states: list[str] = []
    for raw in str(stdout or "").splitlines():
        line = " ".join(raw.strip().split())
        if not line:
            continue
        low = line.lower()
        if "number" in low and "state" in low and "name" in low:
            continue  # header row, not a member row
        # A member row is only ever recognized by its leading row-number
        # token (the observed real header shape, e.g. "1 (local)  ..." /
        # "2          ..."); anything else (banners, blank separators) is
        # never mistaken for a peer row.
        if not re.match(r"^\d+\s", line):
            continue
        local_line = "(local)" in low
        if host_token:
            local_line = local_line or bool(
                re.search(rf"(?<![a-z0-9_.-]){re.escape(host_token)}(?![a-z0-9_.-])", low)
            )
        if local_line:
            continue
        upper = line.upper()
        for state in CLUSTERXL_RUNTIME_STATES:
            if re.search(rf"(?<![A-Z]){re.escape(state)}(?![A-Z])", upper):
                peer_states.append(state)
                break

    local_attention = None if local_role is None else local_role in {"ACTIVE ATTENTION", "DOWN"}

    return {
        "peer_row_states": tuple(peer_states),
        "local_attention": local_attention,
    }


def _collector_identity_gate(
    *,
    target: ProbeTarget,
    observed_hostname: str | None,
    hostname_success: bool,
    version_success: bool,
    configuration_success: bool,
    authenticated: bool,
) -> dict[str, Any]:
    """Extend the A.1 identity gate without making platform/version a hard gate.

    Some Gaia Embedded releases expose a usable authenticated Clish and
    ``show configuration`` but a different/limited version command surface.
    The exact management-selected endpoint + observed hostname + successful
    read-only configuration capability is sufficient to accept current-state
    evidence at MEDIUM confidence. Platform classification remains independent.
    """
    base = _identity_gate(
        target=target,
        observed_hostname=observed_hostname,
        hostname_success=hostname_success,
        version_success=version_success,
        authenticated=authenticated,
    )
    if base.get("accepted"):
        base["acceptance_basis"] = "hostname_plus_version"
        return base

    endpoint_selected = bool(target.management_ip)
    if endpoint_selected and authenticated and hostname_success and observed_hostname and configuration_success:
        relation = str(base.get("name_relation") or "unavailable")
        matched = relation in {"exact", "shortname_match", "normalized_match"}
        return {
            **base,
            "accepted": True,
            "status": (
                "VERIFIED_MANAGEMENT_ENDPOINT_HOSTNAME_AND_CONFIG_READ"
                if matched else "VERIFIED_MANAGEMENT_ENDPOINT_CONFIG_READ_HOSTNAME_DIFF_OBSERVED"
            ),
            "confidence": "MEDIUM",
            "acceptance_basis": "hostname_plus_read_only_configuration_capability",
        }
    base["acceptance_basis"] = "insufficient_identity_evidence"
    return base


def _configuration_failure_reason(result: dict[str, Any], platform_family: str) -> tuple[str, str]:
    if result.get("timeout"):
        return "command_timeout", "operational_failure"
    text = _result_text(result).lower()
    if any(pattern in text for pattern in CLI_AUTHORIZATION_PATTERNS):
        return "gaia_command_not_authorized", "authorization_failure"
    if any(pattern in text for pattern in CLI_UNSUPPORTED_PATTERNS):
        if platform_family == "gaia_embedded":
            return "gaia_embedded_capability_unsupported", "capability_gap"
        if platform_family == "unknown":
            # Authenticated/read-only command capability is known even when
            # product family is not. Do not call the device operationally down
            # merely because an unclassified platform lacks this command.
            return "gaia_configuration_capability_unsupported", "capability_gap"
        # Preserve the established Enterprise Gaia contract: this command is
        # expected there, so an explicit rejection remains an operational
        # anomaly rather than silently weakening known-platform coverage.
        return "gaia_configuration_command_unsupported", "operational_failure"
    # A successful command whose output does not contain canonical ``set``
    # lines is a capability distinction on Embedded/unknown appliances, while
    # known Enterprise Gaia keeps the pre-B.1.2 operational anomaly semantics.
    if result.get("success") and not _canonical_set_lines(str(result.get("stdout") or "")):
        if platform_family == "gaia_embedded":
            return "gaia_embedded_configuration_shape_unsupported", "capability_gap"
        if platform_family == "unknown":
            return "gaia_configuration_shape_unrecognized", "capability_gap"
        return "gaia_configuration_shape_unrecognized", "operational_failure"
    error_class = str(result.get("error_class") or "configuration_unavailable")
    if error_class in {"none", ""}:
        error_class = "configuration_unavailable"
    return error_class, "operational_failure"


def _vsx_member_base_name(name: str) -> str | None:
    """Return a presentation-only member base for conventional VSX pair names.

    This never becomes an evidence identity key. Authoritative collection still
    uses the exact management endpoint + physical device + VSID. The helper is
    only used to collapse duplicated logical VS rows in the UI when management
    artifacts do not expose a VSX cluster group id.
    """
    text = str(name or "").strip()
    match = re.match(r"^(.*?)(?:[-_.])([1-5])(?:[-_.])?$", text)
    base = (match.group(1) if match else "").rstrip("-_.")
    return base or None


def _vsx_presentation_group(device: str, cma: str | None) -> tuple[str | None, str | None, str | None]:
    base = _vsx_member_base_name(device)
    if not base:
        return None, None, None
    digest = hashlib.sha256(f"{cma or ''}|{base}".encode("utf-8")).hexdigest()[:16]
    return (
        f"vsx-present-{digest}",
        f"{base}-VSX",
        "inferred_member_name_pattern_presentation_only",
    )


def _target_key(device: str, ip: str) -> str:
    return f"{device}|{ip}"


def _resolve_targets() -> tuple[list[PhysicalTarget], list[dict[str, Any]]]:
    telemetry = _load_json(OUTPUT_DIR / "cp_telemetry.json", {}) or {}
    cp_rows = _load_json(OUTPUT_DIR / "cp.json", []) or []
    vsx_rows = _load_json(OUTPUT_DIR / "vsx.json", []) or []
    statuses = list(telemetry.get("remote_command_status") or [])
    if not statuses:
        raise RuntimeError(
            "Check Point configuration collection needs output/cp_telemetry.json from a current or previous inventory checkpoint"
        )

    cp_by_device = {str(row.get("device") or ""): row for row in cp_rows if row.get("device")}
    targets: dict[str, PhysicalTarget] = {}
    skipped: list[dict[str, Any]] = []

    for row in statuses:
        device = str(row.get("device") or "").strip()
        ip = str(row.get("management_ip") or "").strip()
        if not device or not ip:
            skipped.append({"device": device or None, "reason": "management_ip_unavailable"})
            continue
        is_vsx = _is_vsx_status(row)
        object_type = str(row.get("object_type") or "unknown")
        cp_item = cp_by_device.get(device) or {}
        topology = cp_item.get("cluster_topology") or {}
        if is_vsx:
            entity_type = "vsx_host"
        elif object_type == "cluster_member":
            entity_type = "clusterxl_member"
        elif object_type == "gateway":
            entity_type = "standalone_gateway"
        else:
            # Do not guess unsupported management object semantics.
            skipped.append({"device": device, "management_ip": ip, "reason": "unsupported_object_type", "object_type": object_type})
            continue
        key = _target_key(device, ip)
        presentation_group_id = presentation_group_label = presentation_group_source = None
        if entity_type == "vsx_host" and not topology.get("group_id"):
            presentation_group_id, presentation_group_label, presentation_group_source = _vsx_presentation_group(device, row.get("cma"))
        targets[key] = PhysicalTarget(
            device=device,
            management_ip=ip,
            object_type=object_type,
            entity_type=entity_type,
            cma=row.get("cma"),
            cluster_group_id=str(topology.get("group_id") or "") or None,
            management_state=str(row.get("management_state") or "") or None,
            cluster_display_name=str(topology.get("display_name") or "") or None,
            presentation_group_id=presentation_group_id,
            presentation_group_label=presentation_group_label,
            presentation_group_source=presentation_group_source,
            selection_source="management_discovery",
        )

    # Mature VSX runtime is the authoritative source for contexts already
    # proven collectable. Add any physical member missing from CP telemetry,
    # then attach only observed non-zero VSIDs. Never invent peer contexts.
    by_ip = {target.management_ip: target for target in targets.values()}
    by_device = {target.device: target for target in targets.values()}
    for row in vsx_rows:
        device = str(row.get("device") or "").strip()
        ip = str(row.get("device_ip") or "").strip()
        vs_id = str(row.get("vs_id") or "").strip()
        if not device or not ip:
            continue
        host = by_device.get(device) or by_ip.get(ip)
        if host is None:
            pg_id, pg_label, pg_source = _vsx_presentation_group(device, None)
            host = PhysicalTarget(
                device=device,
                management_ip=ip,
                object_type="cluster_member",
                entity_type="vsx_host",
                presentation_group_id=pg_id,
                presentation_group_label=pg_label,
                presentation_group_source=pg_source,
                management_state="unknown",
                selection_source="mature_vsx_artifact",
            )
            targets[_target_key(device, ip)] = host
            by_device[device] = host
            by_ip[ip] = host
        if vs_id and vs_id != "0" and re.fullmatch(r"\d+", vs_id):
            if not any(ctx.vs_id == vs_id for ctx in host.contexts):
                host.contexts.append(VsContext(vs_id=vs_id, vs_name=str(row.get("vsys") or "").strip() or None))

    result = list(targets.values())
    result.sort(key=lambda target: (target.entity_type, target.device.lower(), target.management_ip))
    for target in result:
        target.contexts.sort(key=lambda ctx: int(ctx.vs_id))
    return result, skipped


def _safe_command_meta(result: dict[str, Any]) -> dict[str, Any]:
    stdout = str(result.get("stdout") or "")
    stderr = str(result.get("stderr") or "")
    return {
        "success": bool(result.get("success")),
        "error_class": result.get("error_class"),
        "error_detail": result.get("error_detail"),
        "timeout": bool(result.get("timeout")),
        "exit_status": result.get("exit_status"),
        "duration_ms": result.get("duration_ms"),
        "stdout_bytes": len(stdout.encode("utf-8", errors="ignore")),
        "stdout_lines": len(stdout.splitlines()),
        "stderr_bytes": len(stderr.encode("utf-8", errors="ignore")),
    }


def _snapshot_view(snapshot, *, method: str, source_plane: str) -> dict[str, Any]:
    return {
        "status": "success",
        "role": "Primary actual Gaia configuration evidence (secret-aware redacted history)",
        "method": method,
        "transport": "direct_ssh",
        "source_plane": source_plane,
        "change_state": snapshot.change_state,
        "size_bytes": snapshot.size_bytes,
        "schema_status": "redacted_text_valid",
        "artifact_type": PHYSICAL_ARTIFACT_TYPE if source_plane == "gaia-host" else VSX_ARTIFACT_TYPE,
    }


def _entity_id(target: PhysicalTarget, context: VsContext | None = None) -> str:
    if context is None:
        return target.device
    return f"{target.device}__vsid_{context.vs_id}"


def _collect_host(target: PhysicalTarget, *, username: str, secret: str, strict_host_key: bool,
                  connect_timeout: int, command_timeout: int, store: ConfigEvidenceStore,
                  include_preflight_fields: bool = False) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    ssh = None
    interactive = None
    started_at = _utc_now()
    host_row: dict[str, Any] = {
        "entity_id": _entity_id(target),
        "entity_type": target.entity_type,
        "device": target.device,
        "display_name": target.device,
        "management_ip": target.management_ip,
        "object_type": target.object_type,
        "cma": target.cma,
        "cluster_group_id": target.cluster_group_id,
        "cluster_display_name": target.cluster_display_name,
        "presentation_group_id": target.presentation_group_id,
        "presentation_group_label": target.presentation_group_label,
        "presentation_group_source": target.presentation_group_source,
        "parent_name": (target.cluster_display_name or target.presentation_group_label) if target.entity_type in {"clusterxl_member", "vsx_host"} else None,
        "management_state": target.management_state,
        "selection_source": target.selection_source,
        "platform": {"family": "unknown", "label": "Check Point platform", "confidence": "LOW", "evidence": "not_probed"},
        "gaia_command_mode": None,
        "failure_family": None,
        "ha_role": None,
        "ha_role_source": None,
        "started_at": started_at,
        "completed_at": None,
        "status": "failed",
        "error_class": None,
        "identity_gate": {"accepted": False, "status": "UNVERIFIED", "confidence": "LOW"},
        "host_key_policy": "strict_known_hosts" if strict_host_key else "observe_and_record_not_production",
        "host_key_fingerprint": None,
        "raw_configuration_persisted": False,
        "sanitized_configuration_persisted": False,
        "current_configuration": {"status": "unavailable", "reason": "collection_not_completed"},
        "evidence": {},
        "history": {},
    }
    try:
        probe_target = ProbeTarget(
            role=target.entity_type,
            device=target.device,
            management_ip=target.management_ip,
            object_type=target.object_type,
            cma=target.cma,
            selection_source=target.selection_source,
        )
        ssh, key_fp = _connect(probe_target, username, secret, strict=strict_host_key, connect_timeout=connect_timeout)
        host_row["host_key_fingerprint"] = key_fp

        # B.1.2 uses the same PTY-backed session an operator receives after
        # login. This is required for Gaia Embedded/Spark appliances that allow
        # interactive Clish but reject SSH exec requests. Shell type is still
        # proven by command capability, never inferred from the prompt.
        try:
            interactive = InteractiveSshSession(ssh, command_timeout)
        except Exception:
            # Compatibility fallback for SSH servers that genuinely reject a
            # PTY/shell request. Interactive is primary because direct-Clish
            # Spark evidence requires it; exec mode remains a bounded fallback
            # for already-proven enterprise Gaia behavior.
            interactive = None

        interactive_mode = False
        if interactive is not None:
            shell_mode, hostname_result = _detect_gaia_shell_interactive(interactive, command_timeout)
            interactive_mode = shell_mode in {"interactive_direct_clish", "interactive_expert_explicit_clish"}
        else:
            shell_mode, hostname_result = "unknown", {"success": False, "error_class": "interactive_session_unavailable", "stdout": "", "stderr": ""}

        # Do not regress already-proven enterprise Gaia hosts if a server accepts
        # a PTY but the interactive command surface cannot be framed reliably.
        # The exec-channel adapter remains a compatibility fallback only after
        # the interactive handshake fails.
        if not interactive_mode:
            hostname_result["stdout"] = hostname_result["stderr"] = ""
            shell_mode, hostname_result = _detect_gaia_shell(ssh, command_timeout)

        if shell_mode in {"interactive_direct_clish", "interactive_expert_explicit_clish"}:
            hostname_mode = shell_mode if _usable_clish_result(hostname_result) else "unavailable"
            version_result, version_mode, version_command = _run_gaia_interactive_first_supported(
                interactive, ("show version all", "show version"), command_timeout, shell_mode
            )
            # 0.6.1B.1.3 safety finding, updated: "show asset system" was
            # tried as a lighter replacement for "show asset all" but was
            # confirmed to also crash/hang at least one Take 120 gateway —
            # the asset subsystem (xpand asset_get_proc) itself is affected
            # on that estate regardless of "show asset" flavor, not just the
            # extra disk/memory/line-card enumeration "all" performs.
            # "cpstat os -f hw_info" reads Serial/Model through a separate
            # Check Point subsystem entirely and is documented as safe from
            # Gaia Clish. Deliberately no fallback to any "show asset ..."
            # variant on failure: falling back could re-trigger the crash.
            asset_result, asset_mode = _run_gaia_interactive_read(
                interactive, "cpstat os -f hw_info", command_timeout, shell_mode
            )
        else:
            hostname_mode = shell_mode if _usable_clish_result(hostname_result) else "unavailable"
            version_result, version_mode, version_command = _run_gaia_first_supported(
                ssh, ("show version all", "show version"), command_timeout, shell_mode
            )
            asset_result, asset_mode = _run_gaia_read_mode(ssh, "cpstat os -f hw_info", command_timeout, shell_mode)
        host_row["ssh_shell_mode"] = shell_mode
        host_row["version_command"] = version_command
        hostname_stdout = str(hostname_result.get("stdout") or "")
        version_stdout = str(version_result.get("stdout") or "")
        asset_stdout = str(asset_result.get("stdout") or "")
        observed_hostname = _parse_hostname(hostname_stdout)
        hostname_success = _usable_clish_result(hostname_result)
        version_success = _usable_clish_result(version_result)
        preliminary_identity = _identity_gate(
            target=probe_target,
            observed_hostname=observed_hostname,
            hostname_success=hostname_success,
            version_success=version_success,
            authenticated=True,
        )
        preliminary_identity["acceptance_basis"] = "hostname_plus_version" if preliminary_identity.get("accepted") else "preliminary"
        host_row["identity_gate"] = preliminary_identity
        host_row["sw_version"] = _parse_gaia_version(version_stdout)
        host_row["serial"] = _parse_asset_field(
            asset_stdout, ("Appliance SN", "Serial Number", "Serial", "Serial No", "Serial No.", "Chassis Serial Number", "Chassis Serial", "Product Serial Number", "Appliance Serial Number")
        ) or _parse_asset_semantic(asset_stdout, "serial") or _parse_asset_semantic(version_stdout, "serial")
        host_row["model"] = _parse_asset_field(
            asset_stdout, ("Appliance Name", "Model", "Model Name", "Product Model", "Appliance", "Appliance Type", "Appliance Model", "Product Name")
        ) or _parse_asset_semantic(asset_stdout, "model") or _parse_asset_semantic(version_stdout, "model")
        host_row["platform"] = _classify_platform(
            version_stdout=version_stdout, asset_stdout=asset_stdout, model=host_row.get("model")
        )
        used_modes = [mode for mode in (hostname_mode, version_mode, asset_mode) if mode != "unavailable"]
        host_row["gaia_command_mode"] = shell_mode if shell_mode != "unknown" else (used_modes[0] if used_modes else "unavailable")

        # ClusterXL role is runtime evidence. Never infer ACTIVE/STANDBY from
        # member names or static configuration. Run the estate-proven
        # ``cphaprob stat`` through the authenticated interactive session so
        # Expert-login gateways behave the same way as an operator terminal.
        if target.entity_type in {"clusterxl_member", "vsx_host"}:
            try:
                ha_result = (
                    interactive.run("cphaprob stat", command_timeout)
                    if shell_mode == "interactive_expert_explicit_clish" and interactive is not None
                    else _run_exec(ssh, "cphaprob stat", command_timeout)
                )
                if ha_result.get("success") and not _looks_like_cli_error(ha_result):
                    ha_stdout = str(ha_result.get("stdout") or "")
                    role = _parse_clusterxl_runtime_role(ha_stdout, observed_hostname)
                    if role:
                        host_row["ha_role"] = role
                        host_row["ha_role_source"] = "interactive_cphaprob_stat_runtime"
                    # OP.0a P2: same command, same buffer -- read the cluster
                    # mode out before the stdout discard below.
                    mode = _parse_clusterxl_cluster_mode(ha_stdout)
                    if mode != "unknown":
                        host_row["ha_cluster_mode"] = mode
                        host_row["ha_cluster_mode_source"] = "interactive_cphaprob_stat_runtime"
                    # OP.0b S3: opt-in, dormant by design (default False, never
                    # passed True by run_checkpoint_config_collection today --
                    # production invocation is S5/S6's job, per the contract's
                    # own collector-reuse decision). Same buffer, no new read.
                    if include_preflight_fields:
                        host_row["preflight_fields"] = _parse_clusterxl_stat_preflight_fields(
                            ha_stdout, observed_hostname
                        )
                if host_row.get("ha_role"):
                    host_row["ha_runtime_status"] = "success"
                elif shell_mode == "interactive_direct_clish" and _looks_like_cli_error(ha_result):
                    # cphaprob is an Expert/bash-level command; a direct-Clish
                    # session with no proven Expert access cannot reach it.
                    # This is a known capability boundary, not an operational
                    # anomaly -- keep it distinct from an unexplained failure.
                    host_row["ha_runtime_status"] = "capability_gap"
                    host_row["ha_runtime_error_class"] = "cphaprob_unavailable_in_direct_clish"
                else:
                    host_row["ha_runtime_status"] = "unavailable"
                ha_result["stdout"] = ha_result["stderr"] = ""
            except Exception as exc:
                # Runtime role is useful header evidence, never a reason to lose
                # otherwise-valid configuration evidence.
                host_row["ha_runtime_status"] = "unavailable"
                host_row["ha_runtime_error_class"] = type(exc).__name__

        # Drop non-required raw identity outputs as soon as scalar evidence was parsed.
        hostname_result["stdout"] = hostname_result["stderr"] = ""
        version_result["stdout"] = version_result["stderr"] = ""
        asset_result["stdout"] = asset_result["stderr"] = ""
        hostname_stdout = version_stdout = asset_stdout = ""

        # Do not reject only because the platform/version command surface is
        # incomplete. A read-only configuration capability can complete the
        # collector identity gate after hostname evidence is established.
        if shell_mode in {"interactive_direct_clish", "interactive_expert_explicit_clish"} and interactive is not None:
            config_result, config_mode = _run_gaia_interactive_read(
                interactive, "show configuration", max(command_timeout, 60), shell_mode, require_set_lines=True
            )
        else:
            config_result, config_mode = _run_gaia_read_mode(
                ssh, "show configuration", max(command_timeout, 60), shell_mode, require_set_lines=True
            )
        host_row["configuration_command_mode"] = config_mode
        raw_config = str(config_result.get("stdout") or "")
        config_usable = _usable_clish_result(config_result, require_set_lines=True)
        identity = _collector_identity_gate(
            target=probe_target,
            observed_hostname=observed_hostname,
            hostname_success=hostname_success,
            version_success=version_success,
            configuration_success=config_usable,
            authenticated=True,
        )
        host_row["identity_gate"] = identity
        if not identity.get("accepted"):
            host_row["error_class"] = "identity_gate_rejected"
            host_row["failure_family"] = "identity_failure"
            host_row["current_configuration"] = {"status": "unavailable", "reason": "identity_gate_rejected"}
        elif not config_usable:
            reason, family = _configuration_failure_reason(config_result, str((host_row.get("platform") or {}).get("family") or "unknown"))
            host_row["error_class"] = reason
            host_row["failure_family"] = family
            host_row["current_configuration"] = {"status": "unavailable", "reason": reason}
        else:
            sanitized = _sanitize_configuration(raw_config)
            current = build_checkpoint_current_configuration(
                sanitized["safe_set_lines"],
                secret_bearing_line_count=sanitized["secret_bearing_line_count"],
                entity_type=target.entity_type,
            )
            snapshot = store.write_text_snapshot(
                source=SOURCE,
                entity_id=_entity_id(target),
                artifact_type=PHYSICAL_ARTIFACT_TYPE,
                content=sanitized["sanitized_text"],
                method=PHYSICAL_METHOD,
                device_name=target.device,
                management_ip=target.management_ip,
                collector_version=COLLECTOR_VERSION,
                artifact_name="gaia-show-configuration.redacted.txt",
                extra_metadata={
                    "entity_type": target.entity_type,
                    "platform_family": (host_row.get("platform") or {}).get("family"),
                    "platform_evidence": (host_row.get("platform") or {}).get("evidence"),
                    "cluster_group_id": target.cluster_group_id,
                    "cluster_display_name": target.cluster_display_name,
                    "presentation_group_id": target.presentation_group_id,
                    "presentation_group_label": target.presentation_group_label,
                    "presentation_group_source": target.presentation_group_source,
                    "identity_status": identity.get("status"),
                    "identity_confidence": identity.get("confidence"),
                    "host_key_policy": host_row["host_key_policy"],
                    "raw_configuration_persisted": False,
                    "redaction_contract": "secret-bearing lines withheld; full raw canonical SHA256 retained only as change fingerprint",
                    "raw_canonical_sha256": sanitized["raw_canonical_sha256"],
                    "secret_bearing_line_count": sanitized["secret_bearing_line_count"],
                },
                additional_validation={
                    "set_line_count": sanitized["set_line_count"],
                    "safe_set_line_count": sanitized["safe_set_line_count"],
                    "secret_bearing_line_count": sanitized["secret_bearing_line_count"],
                    "identity_gate_accepted": True,
                },
            )
            host_row["status"] = "success"
            host_row["error_class"] = "none"
            host_row["failure_family"] = "none"
            host_row["sanitized_configuration_persisted"] = True
            host_row["current_configuration"] = current
            host_row["evidence"]["actual"] = _snapshot_view(snapshot, method=PHYSICAL_METHOD, source_plane="gaia-host")
            host_row["history"]["actual_change_state"] = snapshot.change_state
            host_row["history"]["effective_change_state"] = snapshot.change_state
            host_row["secret_bearing_line_count"] = sanitized["secret_bearing_line_count"]
            host_row["safe_setting_count"] = current.get("setting_count", 0)

        # The raw configuration is no longer needed after sanitization/snapshot.
        config_result["stdout"] = config_result["stderr"] = ""
        raw_config = ""

        # Context collection is permitted only after the physical host identity
        # gate succeeded. Use the estate-proven Expert-shell vsenv primitive as
        # primary; fall back to the Clish context selector validated in A.1.
        if not identity.get("accepted"):
            host_row["completed_at"] = _utc_now()
            return [host_row]
        for context in target.contexts:
            ctx_started = _utc_now()
            ctx_row: dict[str, Any] = {
                "entity_id": _entity_id(target, context),
                "entity_type": "virtual_system",
                "device": target.device,
                "display_name": context.vs_name or f"VSID {context.vs_id}",
                "management_ip": target.management_ip,
                "object_type": "virtual_system",
                "cma": target.cma,
                "parent_name": target.device,
                "parent_entity_id": _entity_id(target),
                "cluster_group_id": target.cluster_group_id,
                "cluster_display_name": target.cluster_display_name,
                "presentation_group_id": target.presentation_group_id,
                "presentation_group_label": target.presentation_group_label,
                "presentation_group_source": target.presentation_group_source,
                "management_state": target.management_state,
                "platform": dict(host_row.get("platform") or {}),
                "model": host_row.get("model"),
                "serial": host_row.get("serial"),
                "sw_version": host_row.get("sw_version"),
                "gaia_command_mode": host_row.get("gaia_command_mode"),
                # A virtual system can hold independent ClusterXL state from its
                # physical member (VSX per-VS High Availability). Default to the
                # physical member's role only as a labeled fallback -- never
                # present it as VS-specific runtime evidence until a per-VS
                # probe (below) actually confirms it.
                "ha_role": host_row.get("ha_role"),
                "ha_role_source": "inherited_from_physical_member" if host_row.get("ha_role") else None,
                "ha_runtime_status": "unavailable",
                "failure_family": None,
                "vs_id": context.vs_id,
                "vs_name": context.vs_name,
                "selection_source": target.selection_source + "+mature_vsx_context",
                "started_at": ctx_started,
                "completed_at": None,
                "status": "failed",
                "error_class": None,
                "identity_gate": dict(identity),
                "host_key_policy": host_row["host_key_policy"],
                "host_key_fingerprint": key_fp,
                "raw_configuration_persisted": False,
                "sanitized_configuration_persisted": False,
                "current_configuration": {"status": "unavailable", "reason": "collection_not_completed"},
                "evidence": {},
                "history": {},
            }
            if not re.fullmatch(r"\d+", context.vs_id):
                ctx_row["error_class"] = "invalid_vsid"
                ctx_row["failure_family"] = "selection_failure"
                ctx_row["completed_at"] = _utc_now()
                rows.append(ctx_row)
                continue

            method = VSX_METHOD
            ctx_result = _run_exec(
                ssh,
                f"vsenv {context.vs_id} >/dev/null 2>&1; clish -c 'show configuration'",
                max(command_timeout, 60),
            )
            ctx_raw = str(ctx_result.get("stdout") or "")
            if not ctx_result.get("success") or not _canonical_set_lines(ctx_raw):
                ctx_result["stdout"] = ctx_result["stderr"] = ""
                ctx_result = _run_vsx_clish_context(ssh, context.vs_id, max(command_timeout, 60))
                ctx_raw = str(ctx_result.get("stdout") or "")
                method = VSX_FALLBACK_METHOD

            if not ctx_result.get("success") or not _canonical_set_lines(ctx_raw):
                reason, family = _configuration_failure_reason(ctx_result, str((host_row.get("platform") or {}).get("family") or "unknown"))
                if family == "capability_gap":
                    # VSX itself is not a Spark capability. Keep VSX context
                    # failure explicit rather than mislabeling it as Embedded.
                    reason, family = "vsx_context_configuration_unavailable", "context_failure"
                ctx_row["error_class"] = reason
                ctx_row["failure_family"] = family
                ctx_row["current_configuration"] = {"status": "unavailable", "reason": reason}
            else:
                sanitized = _sanitize_configuration(ctx_raw)
                current = build_checkpoint_current_configuration(
                    sanitized["safe_set_lines"],
                    secret_bearing_line_count=sanitized["secret_bearing_line_count"],
                    entity_type="virtual_system",
                    context_label=f"VSID {context.vs_id}",
                )
                snapshot = store.write_text_snapshot(
                    source=SOURCE,
                    entity_id=_entity_id(target, context),
                    artifact_type=VSX_ARTIFACT_TYPE,
                    content=sanitized["sanitized_text"],
                    method=method,
                    device_name=context.vs_name or target.device,
                    management_ip=target.management_ip,
                    collector_version=COLLECTOR_VERSION,
                    artifact_name="gaia-vsx-show-configuration.redacted.txt",
                    extra_metadata={
                        "entity_type": "virtual_system",
                        "parent_device": target.device,
                        "vs_id": context.vs_id,
                        "vs_name": context.vs_name,
                        "platform_family": (host_row.get("platform") or {}).get("family"),
                        "cluster_group_id": target.cluster_group_id,
                        "cluster_display_name": target.cluster_display_name,
                        "presentation_group_id": target.presentation_group_id,
                        "presentation_group_label": target.presentation_group_label,
                        "presentation_group_source": target.presentation_group_source,
                        "identity_status": identity.get("status"),
                        "identity_confidence": identity.get("confidence"),
                        "host_key_policy": ctx_row["host_key_policy"],
                        "raw_configuration_persisted": False,
                        "redaction_contract": "secret-bearing lines withheld; full raw canonical SHA256 retained only as change fingerprint",
                        "raw_canonical_sha256": sanitized["raw_canonical_sha256"],
                        "secret_bearing_line_count": sanitized["secret_bearing_line_count"],
                    },
                    additional_validation={
                        "set_line_count": sanitized["set_line_count"],
                        "safe_set_line_count": sanitized["safe_set_line_count"],
                        "secret_bearing_line_count": sanitized["secret_bearing_line_count"],
                        "identity_gate_accepted": True,
                        "vs_id_numeric": True,
                    },
                )
                ctx_row["status"] = "success"
                ctx_row["error_class"] = "none"
                ctx_row["failure_family"] = "none"
                ctx_row["sanitized_configuration_persisted"] = True
                ctx_row["current_configuration"] = current
                ctx_row["evidence"]["actual"] = _snapshot_view(snapshot, method=method, source_plane="vsx-context")
                ctx_row["history"]["actual_change_state"] = snapshot.change_state
                ctx_row["history"]["effective_change_state"] = snapshot.change_state
                ctx_row["secret_bearing_line_count"] = sanitized["secret_bearing_line_count"]
                ctx_row["safe_setting_count"] = current.get("setting_count", 0)

            ctx_result["stdout"] = ctx_result["stderr"] = ""
            ctx_raw = ""

            # Independent per-VS HA role. Never infer it from the physical
            # member's role or from VS naming; probe cphaprob inside this VS's
            # own vsenv context, the same estate-proven primitive already used
            # for per-VS configuration collection above.
            try:
                vs_ha_result = _run_exec(
                    ssh, f"vsenv {context.vs_id} >/dev/null 2>&1; cphaprob stat", command_timeout
                )
                if vs_ha_result.get("success") and not _looks_like_cli_error(vs_ha_result):
                    vs_ha_stdout = str(vs_ha_result.get("stdout") or "")
                    vs_role = _parse_clusterxl_runtime_role(vs_ha_stdout, observed_hostname)
                    if vs_role:
                        ctx_row["ha_role"] = vs_role
                        ctx_row["ha_role_source"] = "interactive_cphaprob_stat_runtime_per_vs"
                        ctx_row["ha_runtime_status"] = "success"
                    # OP.0a P2: per-VS cluster mode from the same buffer.
                    vs_mode = _parse_clusterxl_cluster_mode(vs_ha_stdout)
                    if vs_mode != "unknown":
                        ctx_row["ha_cluster_mode"] = vs_mode
                        ctx_row["ha_cluster_mode_source"] = "interactive_cphaprob_stat_runtime_per_vs"
                    # OP.0b S3: same opt-in, dormant field family as the
                    # physical path above -- this VS's own buffer only, never
                    # the physical member's (contract §11: a VS fact must be
                    # based only on that VS context's own observation).
                    if include_preflight_fields:
                        ctx_row["preflight_fields"] = _parse_clusterxl_stat_preflight_fields(
                            vs_ha_stdout, observed_hostname
                        )
                if ctx_row.get("ha_runtime_status") != "success":
                    ctx_row["ha_runtime_status"] = (
                        "unavailable_inherited" if ctx_row.get("ha_role") else "unavailable"
                    )
                vs_ha_result["stdout"] = vs_ha_result["stderr"] = ""
            except Exception as exc:
                ctx_row["ha_runtime_status"] = "unavailable_inherited" if ctx_row.get("ha_role") else "unavailable"
                ctx_row["ha_runtime_error_class"] = type(exc).__name__

            ctx_row["completed_at"] = _utc_now()
            rows.append(ctx_row)

    except CpSshStrictPreflightError:
        host_row["error_class"] = "strict_host_key_preflight_failed"
        host_row["failure_family"] = "trust_failure"
    except Exception as exc:
        exc_name = type(exc).__name__
        if exc_name == "AuthenticationException":
            host_row["error_class"] = "ssh_authentication_failed"
            host_row["failure_family"] = "authentication_failure"
        elif exc_name == "BadHostKeyException":
            host_row["error_class"] = "host_key_mismatch"
            host_row["failure_family"] = "trust_failure"
        elif isinstance(exc, (socket.timeout, TimeoutError)):
            host_row["error_class"] = "ssh_connect_timeout"
            host_row["failure_family"] = "reachability_failure"
        elif exc_name == "SSHException" or isinstance(exc, OSError):
            host_row["error_class"] = "ssh_transport_error"
            host_row["failure_family"] = "reachability_failure"
            host_row["error_detail"] = exc_name
        else:
            host_row["error_class"] = "collector_error"
            host_row["failure_family"] = "collector_failure"
            host_row["error_detail"] = exc_name
    finally:
        if interactive is not None:
            interactive.close()
        if ssh is not None:
            try:
                ssh.close()
            except Exception:
                pass
        host_row["completed_at"] = _utc_now()
        rows.insert(0, host_row)
    return rows


def _setting_map(row: dict[str, Any]) -> dict[str, tuple[str, dict[str, Any]]]:
    mapping: dict[str, tuple[str, dict[str, Any]]] = {}
    current = row.get("current_configuration") or {}
    for section in current.get("sections") or []:
        for setting in section.get("settings") or []:
            key = str(setting.get("_key") or "")
            if key:
                mapping[key] = (str(setting.get("value") or ""), setting)
    return mapping


def _apply_cluster_member_semantics(rows: list[dict[str, Any]]) -> None:
    groups: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        group_id = str(row.get("cluster_group_id") or "")
        if group_id and row.get("entity_type") == "clusterxl_member" and row.get("status") == "success":
            groups.setdefault(group_id, []).append(row)

    for members in groups.values():
        if len(members) < 2:
            continue
        maps = [_setting_map(member) for member in members]
        all_keys = set().union(*(mapping.keys() for mapping in maps))
        differing: set[str] = set()
        for key in all_keys:
            values = [mapping.get(key, (None, None))[0] for mapping in maps]
            if len(set(values)) > 1:
                differing.add(key)
        for member, mapping in zip(members, maps):
            count = 0
            for key in differing:
                item = mapping.get(key)
                if not item:
                    continue
                setting = item[1]
                setting["origin"] = "member_specific"
                setting["member_specific"] = True
                count += 1
            member["member_specific_setting_count"] = count
            for highlight in (member.get("current_configuration") or {}).get("highlights") or []:
                label = highlight.get("label")
                for section in (member.get("current_configuration") or {}).get("sections") or []:
                    match = next((s for s in section.get("settings") or [] if s.get("setting") == label), None)
                    if match:
                        highlight["origin"] = match.get("origin")
                        break


def _strip_internal_projection_keys(rows: list[dict[str, Any]]) -> None:
    for row in rows:
        current = row.get("current_configuration") or {}
        for section in current.get("sections") or []:
            for setting in section.get("settings") or []:
                setting.pop("_key", None)


def _sample_targets(targets: list[PhysicalTarget]) -> list[PhysicalTarget]:
    result: list[PhysicalTarget] = []
    standalone = next((t for t in targets if t.entity_type == "standalone_gateway"), None)
    if standalone:
        result.append(standalone)
    clusters: dict[str, list[PhysicalTarget]] = {}
    for target in targets:
        if target.entity_type == "clusterxl_member" and target.cluster_group_id:
            clusters.setdefault(target.cluster_group_id, []).append(target)
    pair = next((items[:2] for items in clusters.values() if len(items) >= 2), [])
    result.extend(pair)
    vsx = next((t for t in targets if t.entity_type == "vsx_host" and t.contexts), None)
    if vsx:
        # One validated context is enough for the development sample.
        clone = PhysicalTarget(**{**vsx.__dict__, "contexts": vsx.contexts[:1]})
        result.append(clone)
    # Deduplicate if a future topology causes overlap.
    unique: dict[str, PhysicalTarget] = {}
    for target in result:
        unique[_target_key(target.device, target.management_ip)] = target
    return list(unique.values())


def _apply_cp_target_selector(
    targets: list[PhysicalTarget],
    requested_entity_ids: Sequence[str] | None,
) -> list[PhysicalTarget]:
    """OP.0d exact, fail-closed narrowing of already-resolved CP candidates.

    Matches on the same physical-host `entity_id` convention `_entity_id`
    already uses (`target.device`) -- never a display label, never a
    substring/regex/wildcard. Every requested id must resolve to exactly one
    candidate before this returns; an unknown or ambiguous id raises here,
    before `run_checkpoint_config_collection` opens a single SSH connection.
    Narrows only -- never adds a target `stage`/`_sample_targets` did not
    already resolve.
    """
    requested = list(dict.fromkeys(str(i).strip() for i in requested_entity_ids if str(i).strip()))
    if not requested:
        raise ValueError("cp_config_targets: no valid entity_id supplied")

    by_entity_id: dict[str, list[PhysicalTarget]] = {}
    for target in targets:
        by_entity_id.setdefault(_entity_id(target), []).append(target)

    unknown = sorted(rid for rid in requested if rid not in by_entity_id)
    if unknown:
        raise ValueError(
            "cp_config_targets: unknown entity_id(s), refusing to contact any device: "
            + ", ".join(unknown)
        )

    ambiguous = sorted(rid for rid in requested if len(by_entity_id[rid]) > 1)
    if ambiguous:
        raise ValueError(
            "cp_config_targets: ambiguous entity_id(s) resolve to more than one candidate, "
            "refusing to contact any device: " + ", ".join(ambiguous)
        )

    return [by_entity_id[rid][0] for rid in requested]


def _management_state_is_down(value: Any) -> bool:
    normalized = str(value or "").strip().lower().replace("-", "_").replace(" ", "_")
    return normalized in {"down", "management_down", "not_connected", "disconnected", "offline"}


def run_checkpoint_config_collection(
    cfg,
    *,
    stage: str = "all",
    max_workers: int = 6,
    orchestration_run_id: str | None = None,
    target_entity_ids: Sequence[str] | None = None,
) -> dict[str, Any]:
    global OUTPUT_DIR
    OUTPUT_DIR = Path(cfg.runtime_paths.output_root) if getattr(cfg, "runtime_paths", None) is not None else OUTPUT_DIR
    collection_started_monotonic = time.monotonic()
    username = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_USERNAME") or cfg.auth.principal
    secret = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD") or cfg.auth.secret
    if not username or not secret:
        raise RuntimeError("CP configuration credentials are unavailable")
    register_sensitive_value(username, f"[USER:{user_fingerprint(username)}]")
    register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")

    strict_host_key = _env_bool("SECURITYEXPERT_CP_CONFIG_SSH_STRICT_HOST_KEY", False)
    connect_timeout = _env_int("SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_TIMEOUT_SECONDS", 8, 2, 60)
    command_timeout = _env_int("SECURITYEXPERT_CP_CONFIG_SSH_COMMAND_TIMEOUT_SECONDS", 20, 5, 120)
    max_workers = max(1, min(int(max_workers or 1), 12))

    if stage not in ("all", "sample"):
        raise ValueError(f"Unsupported CP config stage: {stage}")

    targets, skipped = _resolve_targets()
    requested_count = len(list(dict.fromkeys(str(i).strip() for i in (target_entity_ids or []) if str(i).strip())))
    if target_entity_ids:
        # OP.0d: an explicit selector is a narrower, operator-approved request
        # than stage -- it takes precedence and stage's own sample/all
        # selection is not applied on top of it.
        targets = _apply_cp_target_selector(targets, target_entity_ids)
    elif stage == "sample":
        targets = _sample_targets(targets)

    store = ConfigEvidenceStore()
    entity_target_count = sum(1 + len(target.contexts) for target in targets)
    target_selection = f"explicit({len(targets)}/{requested_count})" if target_entity_ids else stage
    info(
        ">>> CP CONFIG COLLECTION START "
        f"(phase={PHASE} physical_hosts={len(targets)} entities={entity_target_count} workers={max_workers} "
        f"target_selection={target_selection} ssh_session=interactive_adaptive raw_config_persisted=false)"
    )
    if not strict_host_key:
        warn(
            ">>> CP CONFIG SSH host-key verification is compatibility mode; "
            "production deployment must enable trusted known_hosts/pinned host keys"
        )

    rows: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {
            pool.submit(
                _collect_host,
                target,
                username=username,
                secret=secret,
                strict_host_key=strict_host_key,
                connect_timeout=connect_timeout,
                command_timeout=command_timeout,
                store=store,
            ): target
            for target in targets
        }
        completed_hosts = 0
        for future in as_completed(futures):
            target = futures[future]
            completed_hosts += 1
            try:
                host_rows = future.result()
            except Exception as exc:
                host_rows = [{
                    "entity_id": _entity_id(target),
                    "entity_type": target.entity_type,
                    "device": target.device,
                    "display_name": target.device,
                    "management_ip": target.management_ip,
                    "status": "failed",
                    "error_class": "worker_error",
                    "error_detail": type(exc).__name__,
                    "failure_family": "collector_failure",
                    "platform": {"family": "unknown", "label": "Check Point platform", "confidence": "LOW", "evidence": "worker_error"},
                    "raw_configuration_persisted": False,
                    "sanitized_configuration_persisted": False,
                    "current_configuration": {"status": "unavailable", "reason": "worker_error"},
                    "evidence": {},
                    "history": {},
                    "completed_at": _utc_now(),
                }]
            rows.extend(host_rows)
            info(f">>> CP CONFIG [{completed_hosts}/{len(targets)}] host={target.device} entities={len(host_rows)}")

    _apply_cluster_member_semantics(rows)
    _strip_internal_projection_keys(rows)
    rows.sort(key=lambda row: (
        str(row.get("parent_name") or row.get("display_name") or row.get("device") or "").lower(),
        1 if row.get("entity_type") == "virtual_system" else 0,
        str(row.get("display_name") or "").lower(),
    ))

    selected = len(rows)
    planned_entities = entity_target_count
    unmaterialized_entities = max(0, planned_entities - selected)
    success = sum(1 for row in rows if row.get("status") == "success")
    unavailable = selected - success
    failure_reason_counts = Counter(
        str(row.get("error_class") or "unknown")
        for row in rows if row.get("status") != "success"
    )
    failure_family_counts = Counter(
        str(row.get("failure_family") or "unknown")
        for row in rows if row.get("status") != "success"
    )
    capability_gaps = int(failure_family_counts.get("capability_gap", 0))
    operational_failures = max(0, unavailable - capability_gaps)

    entity_type_counts: dict[str, dict[str, int]] = {}
    for entity_type in ("standalone_gateway", "clusterxl_member", "vsx_host", "virtual_system"):
        subset = [row for row in rows if row.get("entity_type") == entity_type]
        entity_type_counts[entity_type] = {
            "selected": len(subset),
            "success": sum(1 for row in subset if row.get("status") == "success"),
            "unavailable": sum(1 for row in subset if row.get("status") != "success"),
        }

    platform_counts: dict[str, dict[str, int]] = {}
    for row in rows:
        family = str((row.get("platform") or {}).get("family") or "unknown")
        bucket = platform_counts.setdefault(family, {"selected": 0, "success": 0, "unavailable": 0})
        bucket["selected"] += 1
        if row.get("status") == "success":
            bucket["success"] += 1
        else:
            bucket["unavailable"] += 1

    management_reported_down_entities = sum(
        1 for row in rows
        if row.get("status") != "success" and _management_state_is_down(row.get("management_state"))
    )
    management_reported_down_hosts = sum(
        1 for row in rows
        if row.get("entity_type") != "virtual_system"
        and row.get("status") != "success"
        and _management_state_is_down(row.get("management_state"))
    )
    platform_unknown_entities = int((platform_counts.get("unknown") or {}).get("selected", 0))

    changes = {"first": 0, "same": 0, "changed": 0}
    for row in rows:
        state = str((row.get("history") or {}).get("actual_change_state") or "")
        if state in changes:
            changes[state] += 1
    summary = {
        "run_id": orchestration_run_id,
        "stage": stage,
        "physical_hosts": len(targets),
        "selected": selected,
        "planned_entities": planned_entities,
        "unmaterialized_entities": unmaterialized_entities,
        "success": success,
        # Backward-compatible unavailable count. B.1 adds reason/family
        # breakdown so unsupported capability is not conflated with transport
        # or authentication failure.
        "failed": unavailable,
        "unavailable": unavailable,
        "operational_failures": operational_failures,
        "capability_gaps": capability_gaps,
        "failure_reason_counts": dict(sorted(failure_reason_counts.items())),
        "failure_family_counts": dict(sorted(failure_family_counts.items())),
        "entity_type_counts": entity_type_counts,
        "platform_counts": platform_counts,
        "identity_gate_accepted": sum(1 for row in rows if (row.get("identity_gate") or {}).get("accepted")),
        "identity_high_confidence": sum(1 for row in rows if (row.get("identity_gate") or {}).get("confidence") == "HIGH"),
        "identity_hostname_differences": sum(1 for row in rows if (row.get("identity_gate") or {}).get("name_relation") == "different_observed"),
        "shell_mode_counts": dict(Counter(str(row.get("ssh_shell_mode") or "unknown") for row in rows if row.get("entity_type") != "virtual_system")),
        "standalone_gateways": sum(1 for row in rows if row.get("entity_type") == "standalone_gateway"),
        "clusterxl_members": sum(1 for row in rows if row.get("entity_type") == "clusterxl_member"),
        "vsx_hosts": sum(1 for row in rows if row.get("entity_type") == "vsx_host"),
        "vsx_virtual_systems": sum(1 for row in rows if row.get("entity_type") == "virtual_system"),
        "secret_bearing_lines_withheld": sum(int(row.get("secret_bearing_line_count") or 0) for row in rows),
        "safe_projected_settings": sum(int(row.get("safe_setting_count") or 0) for row in rows),
        "member_specific_settings": sum(int(row.get("member_specific_setting_count") or 0) for row in rows),
        "model_covered": sum(1 for row in rows if row.get("entity_type") != "virtual_system" and row.get("model")),
        "serial_covered": sum(1 for row in rows if row.get("entity_type") != "virtual_system" and row.get("serial")),
        "ha_role_covered": sum(1 for row in rows if row.get("entity_type") in {"clusterxl_member", "vsx_host"} and row.get("ha_role")),
        # Per-virtual-system HA role confirmed by an independent per-VS probe
        # (ha_runtime_status "success"), never counting a virtual_system row
        # that only carries its physical member's role as a labeled fallback.
        "ha_role_covered_virtual_systems": sum(
            1 for row in rows if row.get("entity_type") == "virtual_system" and row.get("ha_runtime_status") == "success"
        ),
        "ha_role_inherited_virtual_systems": sum(
            1 for row in rows if row.get("entity_type") == "virtual_system" and row.get("ha_runtime_status") == "unavailable_inherited"
        ),
        "gaia_embedded_entities": int((platform_counts.get("gaia_embedded") or {}).get("selected", 0)),
        "gaia_embedded_success": int((platform_counts.get("gaia_embedded") or {}).get("success", 0)),
        "management_reported_down_entities": management_reported_down_entities,
        "management_reported_down_hosts": management_reported_down_hosts,
        "platform_unknown_entities": platform_unknown_entities,
        "successful_unknown_platform_entities": sum(
            1 for row in rows
            if row.get("status") == "success" and str((row.get("platform") or {}).get("family") or "unknown") == "unknown"
        ),
        "first": changes["first"],
        "same": changes["same"],
        "changed": changes["changed"],
        "raw_configuration_persisted": False,
        "sanitized_configuration_persisted": True,
        "host_key_policy": "strict_known_hosts" if strict_host_key else "observe_and_record_not_production",
        "collector_gate": bool(planned_entities > 0 and operational_failures == 0 and unmaterialized_entities == 0),
        "coverage_complete": bool(planned_entities > 0 and success == planned_entities),
        "production_trust_ready": bool(strict_host_key and planned_entities > 0 and success == planned_entities),
        "selection_skipped": len(skipped),
        "workers": max_workers,
        "duration_seconds": round(time.monotonic() - collection_started_monotonic, 3),
    }
    payload = {
        "phase": PHASE,
        "title": "Interactive CP SSH Session + Coverage Closure + Project Roadmap UI",
        "generated_at": _utc_now(),
        "mode": "read_only_collection",
        "read_only": True,
        "login_shell_contract": "Interactive SSH capability handshake; direct Clish when proven, otherwise Expert shell with explicit clish -c; VSX contexts retain validated numeric vsenv",
        "source": SOURCE,
        "transport": "direct_ssh",
        "management_api_role": "selection/topology/intent only; not actual Gaia configuration evidence",
        "raw_configuration_persisted": False,
        "sanitized_configuration_persisted": True,
        "summary": summary,
        "devices": rows,
        "skipped_targets": skipped,
        "sensitivity": "LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE",
    }
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUTPUT_DIR / "cp_config_telemetry.json"
    tmp = out.with_suffix(out.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(out)
    info(
        ">>> CP CONFIG COLLECTION DONE "
        f"(success={success}/{planned_entities} observed={selected} unmaterialized={unmaterialized_entities} unavailable={unavailable} operational_failures={operational_failures} "
        f"capability_gaps={capability_gaps} spark={summary['gaia_embedded_success']}/{summary['gaia_embedded_entities']} "
        f"first={changes['first']} same={changes['same']} changed={changes['changed']} "
        f"secrets_withheld={summary['secret_bearing_lines_withheld']} gate={summary['collector_gate']} coverage={summary['coverage_complete']})"
    )
    return payload
