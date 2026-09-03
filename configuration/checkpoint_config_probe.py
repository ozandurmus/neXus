from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import socket
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import paramiko

from utils.cp_ssh_trust import CpSshStrictPreflightError, HostKeyNotTrustedError, apply_strict_host_key_policy
from utils.logger import info, warn, register_sensitive_value, user_fingerprint

OUTPUT_DIR = Path("output")

# 0.6.1A is deliberately a probe, not a production configuration collector.
# The login shell is expected to be Expert (/bin/bash) in the user's estate.
# All Gaia reads therefore enter Clish explicitly through a fixed allow-list.
EXPERT_READ_ONLY_COMMANDS = {
    "shell": "printf 'shell=%s\\n' \"$SHELL\"; printf 'user=%s\\n' \"$(id -un)\"",
    "hostname": "clish -c 'show hostname'",
    "version": "clish -c 'show version all'",
    # 0.6.1B.1.3 safety finding, updated: the "show asset" family itself
    # (all and system) was confirmed crashing/hanging Take 120 gateways.
    # "cpstat os -f hw_info" reads the same identity data through a separate
    # subsystem and is documented as safe from Gaia Clish or Expert mode.
    "asset": "clish -c 'cpstat os -f hw_info'",
    "configuration": "clish -c 'show configuration'",
}

CLI_ERROR_PATTERNS = (
    "command not found",
    "unknown command",
    "invalid command",
    "syntax error",
    "not a valid command",
    "permission denied",
    "not authorized",
    "authorization failed",
)

# Raw show-configuration may contain clear or encrypted credentials/community
# strings. The probe NEVER persists raw stdout. These patterns are used only to
# count potentially secret-bearing lines in memory.
SECRET_LINE_RE = re.compile(
    r"(?i)(?:password|passwd|secret|encrypted-secret|community|auth(?:entication)?[-_ ]?key|"
    r"private[-_ ]?key|pre[-_ ]?shared|psk|credential|token)"
)

HOSTNAME_TOKEN_RE = re.compile(r"(?i)^(?:hostname\s*(?::|=)?\s*)?([^\s]+)\s*$")


@dataclass(frozen=True)
class ProbeTarget:
    role: str
    device: str
    management_ip: str
    object_type: str
    cma: str | None = None
    vs_id: str | None = None
    vs_name: str | None = None
    selection_source: str = "management_discovery"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")


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


def _communicating(row: dict[str, Any]) -> bool:
    state = str(row.get("management_state") or "unknown").strip().lower()
    return state in {"communicating", "unknown", ""}


def _is_vsx_status(row: dict[str, Any]) -> bool:
    return _boolish(row.get("vsx_cluster_member")) or _boolish(row.get("vs_cluster_member"))


def _is_non_vsx_gateway(row: dict[str, Any]) -> bool:
    return str(row.get("object_type") or "").lower() == "gateway" and not _is_vsx_status(row)


def _is_non_vsx_cluster_member(row: dict[str, Any]) -> bool:
    return str(row.get("object_type") or "").lower() == "cluster_member" and not _is_vsx_status(row)


def _status_map(rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {str(row.get("device") or ""): row for row in rows if row.get("device")}


def _status_ip_map(rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {str(row.get("management_ip") or ""): row for row in rows if row.get("management_ip")}


def _normalize_host_token(value: Any) -> str:
    token = str(value or "").strip().lower().rstrip(".")
    token = token.split(".", 1)[0]
    return re.sub(r"[^a-z0-9]+", "", token)


def _pick_targets() -> tuple[list[ProbeTarget], list[str]]:
    telemetry = _load_json(OUTPUT_DIR / "cp_telemetry.json", {}) or {}
    cp_rows = _load_json(OUTPUT_DIR / "cp.json", []) or []
    vsx_rows = _load_json(OUTPUT_DIR / "vsx.json", []) or []
    statuses = list(telemetry.get("remote_command_status") or [])

    if not statuses:
        raise RuntimeError(
            "0.6.1A probe needs output/cp_telemetry.json from a previous full checkpoint. "
            "Run py.exe -B .\\main.py once before the probe."
        )

    by_name = _status_map(statuses)
    by_ip = _status_ip_map(statuses)
    targets: list[ProbeTarget] = []
    gaps: list[str] = []

    # 1) Standalone/non-cluster physical gateway.
    standalone = next(
        (
            row for row in statuses
            if _is_non_vsx_gateway(row) and _communicating(row) and row.get("management_ip")
        ),
        None,
    )
    if standalone:
        targets.append(ProbeTarget(
            role="standalone",
            device=str(standalone.get("device")),
            management_ip=str(standalone.get("management_ip")),
            object_type="gateway",
            cma=standalone.get("cma"),
            selection_source="management_discovery",
        ))
    else:
        gaps.append("standalone_candidate_unavailable")

    # 2) A complete classic ClusterXL pair from the already proven inventory
    # topology. We prefer two members of the same group, not arbitrary members.
    groups: dict[str, list[str]] = {}
    for item in cp_rows:
        topo = item.get("cluster_topology") or {}
        group_id = str(topo.get("group_id") or "")
        device = str(item.get("device") or "")
        status = by_name.get(device) or {}
        if group_id and device and _is_non_vsx_cluster_member(status):
            groups.setdefault(group_id, [])
            if device not in groups[group_id]:
                groups[group_id].append(device)

    cluster_members: list[dict[str, Any]] | None = None
    for names in groups.values():
        rows = [by_name.get(name) for name in names]
        rows = [row for row in rows if row and _communicating(row) and row.get("management_ip")]
        if len(rows) >= 2:
            cluster_members = rows[:2]
            break

    if cluster_members:
        for idx, row in enumerate(cluster_members, start=1):
            targets.append(ProbeTarget(
                role=f"clusterxl_member_{idx}",
                device=str(row.get("device")),
                management_ip=str(row.get("management_ip")),
                object_type="cluster_member",
                cma=row.get("cma"),
                selection_source="management_discovery_cluster_topology",
            ))
    else:
        gaps.append("clusterxl_pair_candidate_unavailable")

    # 3) VSX: choose a physical member already represented by the mature VSX
    # runtime collector, then one non-zero VSID from that same member. This does
    # not assume that Gaia show configuration is context-specific; the probe
    # explicitly compares host and VS-context fingerprints to discover that.
    vsx_by_device: dict[str, list[dict[str, Any]]] = {}
    for row in vsx_rows:
        device = str(row.get("device") or "")
        if device:
            vsx_by_device.setdefault(device, []).append(row)

    vsx_target_status = None
    vsx_context = None
    vsx_device_name = None
    vsx_device_ip = None
    for device, rows in vsx_by_device.items():
        nonzero = next((r for r in rows if str(r.get("vs_id") or "0") not in {"", "0"}), None)
        if not nonzero:
            continue
        device_ip = str(nonzero.get("device_ip") or "").strip()
        # Prefer the CP management status row by name, then by exact management
        # IP. If neither exists, the mature VSX collector artifact itself is
        # sufficient to select the physical member for this observe-only probe:
        # it could only have been produced after authenticated VSX collection.
        status = by_name.get(device) or by_ip.get(device_ip) or {}
        if status and not _communicating(status):
            continue
        if not device_ip and not status.get("management_ip"):
            continue
        vsx_target_status = status
        vsx_context = nonzero
        vsx_device_name = device
        vsx_device_ip = str(status.get("management_ip") or device_ip)
        break

    if vsx_context and vsx_device_name and vsx_device_ip:
        vsx_source = "management_discovery_vsx" if vsx_target_status else "mature_vsx_artifact"
        targets.append(ProbeTarget(
            role="vsx_host",
            device=vsx_device_name,
            management_ip=vsx_device_ip,
            object_type=str((vsx_target_status or {}).get("object_type") or "cluster_member"),
            cma=(vsx_target_status or {}).get("cma"),
            selection_source=vsx_source,
        ))
        targets.append(ProbeTarget(
            role="vsx_virtual_system",
            device=vsx_device_name,
            management_ip=vsx_device_ip,
            object_type="virtual_system_context_probe",
            cma=(vsx_target_status or {}).get("cma"),
            vs_id=str(vsx_context.get("vs_id")),
            vs_name=str(vsx_context.get("vsys") or "") or None,
            selection_source=vsx_source + "+nonzero_vsid",
        ))
    else:
        gaps.append("vsx_context_candidate_unavailable")

    return targets, gaps


def _sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8", errors="ignore")).hexdigest()


def _host_key_fingerprint(key: paramiko.PKey | None) -> str | None:
    if key is None:
        return None
    digest = hashlib.sha256(key.asbytes()).digest()
    return "SHA256:" + base64.b64encode(digest).decode("ascii").rstrip("=")


def _looks_like_cli_error(stdout: str, stderr: str) -> bool:
    haystack = f"{stdout}\n{stderr}".lower()
    return any(pattern in haystack for pattern in CLI_ERROR_PATTERNS)


def _run_exec(
    ssh: paramiko.SSHClient, command: str, timeout_seconds: int, *, use_pty: bool = True
) -> dict[str, Any]:
    """Execute one command over a new channel of the *existing* transport.

    `use_pty` defaults to `True` -- the long-standing behavior every existing
    caller relies on (some Gaia Embedded/Spark appliances only cooperate with
    a PTY-backed channel). Pass `use_pty=False` for a plain non-interactive
    exec: OP.0b S8-A real-environment evidence showed that a PTY-backed
    channel makes the device run its per-session login/CLI initialization on
    *every* command, which amplified one bounded battery into one extra
    device-side CLI session per read, and can also inject terminal escape
    sequences into output the parsers then have to survive.
    """
    started = time.monotonic()
    stdout_chunks: list[bytes] = []
    stderr_chunks: list[bytes] = []
    timed_out = False
    exit_status = None

    try:
        transport = ssh.get_transport()
        if not transport or not transport.is_active():
            raise RuntimeError("ssh_transport_inactive")
        channel = transport.open_session(timeout=min(timeout_seconds, 10))
        if use_pty:
            try:
                channel.get_pty(term="vt100", width=200, height=60)
            except Exception:
                pass
        channel.exec_command(command)
        deadline = time.monotonic() + timeout_seconds
        while True:
            while channel.recv_ready():
                stdout_chunks.append(channel.recv(65535))
            while channel.recv_stderr_ready():
                stderr_chunks.append(channel.recv_stderr(65535))
            if channel.exit_status_ready() and not channel.recv_ready() and not channel.recv_stderr_ready():
                exit_status = channel.recv_exit_status()
                break
            if time.monotonic() >= deadline:
                timed_out = True
                try:
                    channel.close()
                except Exception:
                    pass
                break
            time.sleep(0.05)
    except Exception as exc:
        return {
            "success": False,
            "error_class": "execution_error",
            "error_detail": type(exc).__name__,
            "timeout": False,
            "exit_status": exit_status,
            "duration_ms": int((time.monotonic() - started) * 1000),
            "stdout": "",
            "stderr": "",
        }

    stdout = b"".join(stdout_chunks).decode("utf-8", errors="ignore")
    stderr = b"".join(stderr_chunks).decode("utf-8", errors="ignore")
    if timed_out:
        error_class = "timeout"
        success = False
    elif exit_status not in (0, None):
        error_class = "command_error"
        success = False
    elif not stdout.strip():
        error_class = "empty_output"
        success = False
    elif _looks_like_cli_error(stdout, stderr):
        error_class = "cli_rejected"
        success = False
    else:
        error_class = "none"
        success = True
    return {
        "success": success,
        "error_class": error_class,
        "error_detail": None,
        "timeout": timed_out,
        "exit_status": exit_status,
        "duration_ms": int((time.monotonic() - started) * 1000),
        "stdout": stdout,
        "stderr": stderr,
    }


def _read_channel_until_exit(channel, timeout_seconds: int) -> tuple[str, str, bool, int | None]:
    stdout_chunks: list[bytes] = []
    stderr_chunks: list[bytes] = []
    deadline = time.monotonic() + timeout_seconds
    while True:
        while channel.recv_ready():
            stdout_chunks.append(channel.recv(65535))
        while channel.recv_stderr_ready():
            stderr_chunks.append(channel.recv_stderr(65535))
        if channel.exit_status_ready() and not channel.recv_ready() and not channel.recv_stderr_ready():
            return (
                b"".join(stdout_chunks).decode("utf-8", errors="ignore"),
                b"".join(stderr_chunks).decode("utf-8", errors="ignore"),
                False,
                channel.recv_exit_status(),
            )
        if time.monotonic() >= deadline:
            try:
                channel.close()
            except Exception:
                pass
            return (
                b"".join(stdout_chunks).decode("utf-8", errors="ignore"),
                b"".join(stderr_chunks).decode("utf-8", errors="ignore"),
                True,
                None,
            )
        time.sleep(0.05)


def _run_vsx_clish_context(ssh: paramiko.SSHClient, vs_id: str, timeout_seconds: int) -> dict[str, Any]:
    """Run a context switch and show configuration in ONE Clish process.

    `set virtual-system` is a context selector in VSX Gaia Clish. It must not be
    split across unrelated `clish -c` processes because context persistence is
    exactly what this probe is trying to validate.
    """
    if not re.fullmatch(r"\d+", str(vs_id)):
        return {"success": False, "error_class": "invalid_vsid", "stdout": "", "stderr": ""}

    started = time.monotonic()
    try:
        transport = ssh.get_transport()
        if not transport or not transport.is_active():
            raise RuntimeError("ssh_transport_inactive")
        channel = transport.open_session(timeout=min(timeout_seconds, 10))
        try:
            channel.get_pty(term="vt100", width=200, height=60)
        except Exception:
            pass
        channel.exec_command("clish")
        # Fixed numeric VSID only; no user-provided command text reaches shell.
        channel.send(f"set virtual-system {vs_id}\n")
        channel.send("show configuration\n")
        channel.send("exit\n")
        stdout, stderr, timed_out, exit_status = _read_channel_until_exit(channel, timeout_seconds)
    except Exception as exc:
        return {
            "success": False,
            "error_class": "execution_error",
            "error_detail": type(exc).__name__,
            "timeout": False,
            "exit_status": None,
            "duration_ms": int((time.monotonic() - started) * 1000),
            "stdout": "",
            "stderr": "",
        }

    has_config = any(line.lstrip().startswith("set ") for line in stdout.splitlines())
    if timed_out:
        success, error_class = False, "timeout"
    elif exit_status not in (0, None):
        success, error_class = False, "command_error"
    elif _looks_like_cli_error(stdout, stderr):
        success, error_class = False, "cli_rejected"
    elif not has_config:
        success, error_class = False, "configuration_not_observed"
    else:
        success, error_class = True, "none"
    return {
        "success": success,
        "error_class": error_class,
        "error_detail": None,
        "timeout": timed_out,
        "exit_status": exit_status,
        "duration_ms": int((time.monotonic() - started) * 1000),
        "stdout": stdout,
        "stderr": stderr,
    }


def _parse_hostname(stdout: str) -> str | None:
    lines = [line.strip() for line in stdout.splitlines() if line.strip()]
    for line in reversed(lines):
        if line.startswith("[") or line.lower().startswith("clish"):
            continue
        match = HOSTNAME_TOKEN_RE.match(line)
        if match:
            value = match.group(1).strip()
            if value and value.lower() not in {"show", "hostname"}:
                return value
    return None


def _identity_relation(expected: str, observed: str | None) -> str:
    if not observed:
        return "unavailable"
    exp = expected.strip().lower().rstrip(".")
    obs = observed.strip().lower().rstrip(".")
    if exp == obs:
        return "exact"
    if exp.split(".", 1)[0] == obs.split(".", 1)[0]:
        return "shortname_match"
    if _normalize_host_token(exp) and _normalize_host_token(exp) == _normalize_host_token(obs):
        return "normalized_match"
    return "different_observed"


def _identity_gate(*, target: ProbeTarget, observed_hostname: str | None, hostname_success: bool,
                   version_success: bool, authenticated: bool) -> dict[str, Any]:
    """CP identity gate anchored on the exact management-selected SSH endpoint.

    Check Point management object names and Gaia hostnames are separate admin
    namespaces, so a name difference is evidence to retain, not sufficient
    reason to reject an authenticated connection to the exact discovered
    management/member IP. Name agreement raises confidence when available.
    """
    relation = _identity_relation(target.device, observed_hostname)
    endpoint_selected = bool(target.management_ip)
    base_ok = bool(endpoint_selected and authenticated and hostname_success and version_success and observed_hostname)
    if not base_ok:
        status = "UNVERIFIED"
        confidence = "LOW"
    elif relation in {"exact", "shortname_match", "normalized_match"}:
        status = "VERIFIED_MANAGEMENT_ENDPOINT_AND_HOSTNAME"
        confidence = "HIGH"
    else:
        status = "VERIFIED_MANAGEMENT_ENDPOINT_HOSTNAME_DIFF_OBSERVED"
        confidence = "MEDIUM"
    return {
        "accepted": bool(base_ok),
        "status": status,
        "confidence": confidence,
        "name_relation": relation,
        "management_endpoint_selected": endpoint_selected,
        "selection_source": target.selection_source,
    }


def _configuration_summary(stdout: str) -> dict[str, Any]:
    lines = stdout.splitlines()
    set_lines = [line.strip() for line in lines if line.strip().startswith("set ")]
    lower = "\n".join(set_lines).lower()
    canonical_set_text = "\n".join(set_lines)
    return {
        "bytes": len(stdout.encode("utf-8", errors="ignore")),
        "lines": len(lines),
        "set_lines": len(set_lines),
        # Raw stdout can contain prompts/export timestamps. Context comparison
        # therefore uses only canonical `set ...` lines.
        "canonical_set_fingerprint_sha256": _sha256_text(canonical_set_text) if set_lines else None,
        "secret_bearing_lines_detected": sum(1 for line in set_lines if SECRET_LINE_RE.search(line)),
        "feature_markers": {
            "hostname": any(line.startswith("set hostname ") for line in set_lines),
            "timezone": any(line.startswith("set timezone ") for line in set_lines),
            "ntp": "set ntp " in lower,
            "dns": "set dns " in lower,
            "interface": "set interface " in lower,
            "static_route": "set static-route " in lower or "set static route " in lower,
            "snmp": "set snmp " in lower,
        },
    }


def _safe_command_meta(result: dict[str, Any], *, include_config_summary: bool = False) -> dict[str, Any]:
    stdout = str(result.get("stdout") or "")
    safe = {
        "success": bool(result.get("success")),
        "error_class": result.get("error_class"),
        "error_detail": result.get("error_detail"),
        "timeout": bool(result.get("timeout")),
        "exit_status": result.get("exit_status"),
        "duration_ms": result.get("duration_ms"),
        "stdout_bytes": len(stdout.encode("utf-8", errors="ignore")),
        "stdout_lines": len(stdout.splitlines()),
        "stdout_fingerprint_sha256": _sha256_text(stdout) if stdout else None,
        "stderr_bytes": len(str(result.get("stderr") or "").encode("utf-8", errors="ignore")),
    }
    if include_config_summary:
        safe["configuration"] = _configuration_summary(stdout)
    return safe


# 0.6.1B.1.3 safety audit finding: connect_timeout was a single attempt, so a
# transient reachability blip (the dominant real-env failure family) always
# became a hard failure. This adds one bounded retry with a short fixed
# backoff, applied only to reachability/timeout-class errors. Authentication
# and host-key failures are never retried: retrying those wastes time without
# a chance of success and looks like credential probing.
CONNECT_RETRY_ATTEMPTS = _env_int("SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_RETRIES", 1, 0, 2)
CONNECT_RETRY_BACKOFF_SECONDS = _env_int("SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_RETRY_BACKOFF_SECONDS", 2, 1, 10)


def _connect(target: ProbeTarget, username: str, secret: str, *, strict: bool, connect_timeout: int):
    port = _env_int("SECURITYEXPERT_CP_CONFIG_SSH_PORT", 22, 1, 65535)
    attempts_allowed = 1 + CONNECT_RETRY_ATTEMPTS

    # Preflight: verify trusted host-key material before any connection attempt.
    # A missing known_hosts is not retry-able; raise before opening the loop.
    if strict:
        _pre = paramiko.SSHClient()
        try:
            apply_strict_host_key_policy(_pre, strict=True)
        finally:
            try:
                _pre.close()
            except Exception:
                pass

    last_exc: Exception | None = None
    for attempt in range(1, attempts_allowed + 1):
        ssh = paramiko.SSHClient()
        apply_strict_host_key_policy(ssh, strict)
        try:
            ssh.connect(
                target.management_ip,
                port=port,
                username=username,
                **{"password": secret},
                timeout=connect_timeout,
                banner_timeout=connect_timeout,
                auth_timeout=connect_timeout,
                look_for_keys=False,
                allow_agent=False,
            )
            transport = ssh.get_transport()
            key = transport.get_remote_server_key() if transport else None
            return ssh, _host_key_fingerprint(key)
        except (paramiko.AuthenticationException, paramiko.BadHostKeyException, HostKeyNotTrustedError):
            # Not retried: a wrong credential, a host-key mismatch, or an
            # untrusted/unknown host key (RejectPolicy) will not change on a
            # second attempt. HostKeyNotTrustedError must be listed ahead of
            # the generic SSHException branch below -- it is a subclass, and
            # Python matches except clauses in order.
            try:
                ssh.close()
            except Exception:
                pass
            raise
        except (socket.timeout, TimeoutError, paramiko.SSHException, OSError) as exc:
            try:
                ssh.close()
            except Exception:
                pass
            last_exc = exc
            if attempt < attempts_allowed:
                warn(
                    f">>> CP CONFIG SSH connect attempt {attempt}/{attempts_allowed} failed "
                    f"({type(exc).__name__}); retrying in {CONNECT_RETRY_BACKOFF_SECONDS}s"
                )
                time.sleep(CONNECT_RETRY_BACKOFF_SECONDS)
                continue
            raise

    # Unreachable in practice (loop always returns or raises), kept for clarity.
    raise last_exc or RuntimeError("CP config SSH connect failed with no captured exception")


def _probe_physical_target(
    target: ProbeTarget,
    *,
    username: str,
    secret: str,
    strict_host_key: bool,
    connect_timeout: int,
    command_timeout: int,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "role": target.role,
        "device": target.device,
        "management_ip": target.management_ip,
        "object_type": target.object_type,
        "cma": target.cma,
        "selection_source": target.selection_source,
        "ssh_reachable": False,
        "authenticated": False,
        "host_key_policy": "strict_known_hosts" if strict_host_key else "observe_and_record_not_production",
        "host_key_fingerprint": None,
        "login_shell": None,
        "identity_relation": "unavailable",
        "identity_gate": {"accepted": False, "status": "UNVERIFIED", "confidence": "LOW"},
        "commands": {},
        "raw_configuration_persisted": False,
        "success": False,
        "error_class": None,
    }
    ssh = None
    try:
        ssh, key_fp = _connect(target, username, secret, strict=strict_host_key, connect_timeout=connect_timeout)
        row["ssh_reachable"] = True
        row["authenticated"] = True
        row["host_key_fingerprint"] = key_fp

        shell_result = _run_exec(ssh, EXPERT_READ_ONLY_COMMANDS["shell"], command_timeout)
        shell_stdout = str(shell_result.get("stdout") or "")
        shell_match = re.search(r"(?m)^shell=(.+)$", shell_stdout)
        if shell_match:
            row["login_shell"] = shell_match.group(1).strip()
        row["commands"]["expert_shell"] = _safe_command_meta(shell_result)

        hostname_result = _run_exec(ssh, EXPERT_READ_ONLY_COMMANDS["hostname"], command_timeout)
        observed_hostname = _parse_hostname(str(hostname_result.get("stdout") or ""))
        row["identity_relation"] = _identity_relation(target.device, observed_hostname)
        host_meta = _safe_command_meta(hostname_result)
        # Do not persist the actual hostname a second time; relation + hash is enough.
        host_meta["observed_hostname_fingerprint"] = _sha256_text(observed_hostname) if observed_hostname else None
        row["commands"]["clish_show_hostname"] = host_meta

        version_result = _run_exec(ssh, EXPERT_READ_ONLY_COMMANDS["version"], command_timeout)
        row["commands"]["clish_show_version_all"] = _safe_command_meta(version_result)

        asset_result = _run_exec(ssh, EXPERT_READ_ONLY_COMMANDS["asset"], command_timeout)
        # Hardware serial/asset output is not required for the current gate
        # because legacy management discovery does not expose a comparable
        # serial field. Keep only a fingerprint/shape as future cross-check
        # evidence; never persist the raw asset output.
        row["commands"]["clish_show_asset_all"] = _safe_command_meta(asset_result)
        asset_result["stdout"] = ""
        asset_result["stderr"] = ""

        row["identity_gate"] = _identity_gate(
            target=target,
            observed_hostname=observed_hostname,
            hostname_success=bool(hostname_result.get("success")),
            version_success=bool(version_result.get("success")),
            authenticated=True,
        )

        config_result = _run_exec(ssh, EXPERT_READ_ONLY_COMMANDS["configuration"], max(command_timeout, 45))
        row["commands"]["clish_show_configuration"] = _safe_command_meta(config_result, include_config_summary=True)
        # Minimize lifetime of potentially secret-bearing raw configuration in
        # Python memory after deriving the safe summary. Strings cannot be
        # reliably zeroized, but references are dropped immediately.
        config_result["stdout"] = ""
        config_result["stderr"] = ""

        config_summary = row["commands"]["clish_show_configuration"].get("configuration") or {}
        row["success"] = bool(
            shell_result.get("success")
            and hostname_result.get("success")
            and version_result.get("success")
            and config_result.get("success")
            and int(config_summary.get("set_lines") or 0) > 0
            and bool((row.get("identity_gate") or {}).get("accepted"))
        )
        row["error_class"] = "none" if row["success"] else "probe_incomplete"
    except CpSshStrictPreflightError:
        row["error_class"] = "strict_host_key_preflight_failed"
        row["failure_family"] = "trust_failure"
    except paramiko.AuthenticationException:
        row["ssh_reachable"] = True
        row["error_class"] = "authentication_failed"
    except paramiko.BadHostKeyException:
        row["ssh_reachable"] = True
        row["error_class"] = "host_key_mismatch"
    except (socket.timeout, TimeoutError):
        row["error_class"] = "connect_timeout"
    except (paramiko.SSHException, OSError) as exc:
        row["error_class"] = "ssh_error"
        row["error_detail"] = type(exc).__name__
    finally:
        if ssh is not None:
            try:
                ssh.close()
            except Exception:
                pass
    return row


def _probe_vsx_context(
    target: ProbeTarget,
    host_result: dict[str, Any] | None,
    *,
    username: str,
    secret: str,
    strict_host_key: bool,
    connect_timeout: int,
    command_timeout: int,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "role": target.role,
        "device": target.device,
        "management_ip": target.management_ip,
        "object_type": target.object_type,
        "cma": target.cma,
        "selection_source": target.selection_source,
        "vs_id": target.vs_id,
        "vs_name": target.vs_name,
        "ssh_reachable": False,
        "authenticated": False,
        "host_key_policy": "strict_known_hosts" if strict_host_key else "observe_and_record_not_production",
        "host_key_fingerprint": None,
        "context_method": "expert_login -> clish session -> set virtual-system <VSID> -> show configuration",
        "raw_configuration_persisted": False,
        "success": False,
        "error_class": None,
        "context_distinct_from_host": None,
        "identity_gate": dict((host_result or {}).get("identity_gate") or {"accepted": False, "status": "UNVERIFIED", "confidence": "LOW"}),
        "commands": {},
    }
    ssh = None
    try:
        ssh, key_fp = _connect(target, username, secret, strict=strict_host_key, connect_timeout=connect_timeout)
        row["ssh_reachable"] = True
        row["authenticated"] = True
        row["host_key_fingerprint"] = key_fp
        result = _run_vsx_clish_context(ssh, str(target.vs_id), max(command_timeout, 60))
        meta = _safe_command_meta(result, include_config_summary=True)
        row["commands"]["clish_set_virtual_system_show_configuration"] = meta

        # Because the estate logs administrators directly into Expert shell,
        # also validate the already-proven VSX context primitive used by the
        # inventory collector. Numeric VSID validation prevents shell injection.
        vsenv_result = _run_exec(
            ssh,
            f"vsenv {target.vs_id} >/dev/null 2>&1; clish -c 'show configuration'",
            max(command_timeout, 60),
        )
        vsenv_meta = _safe_command_meta(vsenv_result, include_config_summary=True)
        row["commands"]["expert_vsenv_then_clish_show_configuration"] = vsenv_meta
        result["stdout"] = ""
        result["stderr"] = ""
        vsenv_result["stdout"] = ""
        vsenv_result["stderr"] = ""

        host_fp = None
        if host_result:
            host_fp = (((host_result.get("commands") or {}).get("clish_show_configuration") or {}).get("configuration") or {}).get("canonical_set_fingerprint_sha256")
        clish_fp = ((meta.get("configuration") or {}).get("canonical_set_fingerprint_sha256"))
        vsenv_fp = ((vsenv_meta.get("configuration") or {}).get("canonical_set_fingerprint_sha256"))

        distinct_candidates = []
        if host_fp and clish_fp:
            distinct_candidates.append(("clish_set_virtual_system", clish_fp != host_fp))
        if host_fp and vsenv_fp:
            distinct_candidates.append(("expert_vsenv_then_clish", vsenv_fp != host_fp))
        if distinct_candidates:
            row["context_distinct_from_host"] = any(flag for _name, flag in distinct_candidates)

        clish_success = bool(result.get("success") and int(((meta.get("configuration") or {}).get("set_lines") or 0)) > 0)
        vsenv_success = bool(vsenv_result.get("success") and int(((vsenv_meta.get("configuration") or {}).get("set_lines") or 0)) > 0)
        if clish_success:
            row["recommended_context_method"] = "clish_set_virtual_system"
        elif vsenv_success:
            row["recommended_context_method"] = "expert_vsenv_then_clish"
        else:
            row["recommended_context_method"] = None
        row["success"] = bool((clish_success or vsenv_success) and (row.get("identity_gate") or {}).get("accepted"))
        row["error_class"] = "none" if row["success"] else result.get("error_class") or vsenv_result.get("error_class") or "probe_incomplete"
    except paramiko.AuthenticationException:
        row["ssh_reachable"] = True
        row["error_class"] = "authentication_failed"
    except paramiko.BadHostKeyException:
        row["ssh_reachable"] = True
        row["error_class"] = "host_key_mismatch"
    except (socket.timeout, TimeoutError):
        row["error_class"] = "connect_timeout"
    except (paramiko.SSHException, OSError) as exc:
        row["error_class"] = "ssh_error"
        row["error_detail"] = type(exc).__name__
    finally:
        if ssh is not None:
            try:
                ssh.close()
            except Exception:
                pass
    return row


def run_checkpoint_config_probe(cfg) -> dict[str, Any]:
    """Validate CP actual-configuration evidence method across platform shapes.

    This phase is intentionally observe-only:
    - no configuration is changed,
    - no raw `show configuration` output is persisted,
    - no CAS/history object is written,
    - no Configuration UI data is promoted yet.
    """
    global OUTPUT_DIR
    OUTPUT_DIR = Path(cfg.runtime_paths.output_root) if getattr(cfg, "runtime_paths", None) is not None else OUTPUT_DIR
    username = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_USERNAME") or cfg.auth.principal
    secret = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD") or cfg.auth.secret
    if not username or not secret:
        raise RuntimeError("CP configuration probe credentials are unavailable")

    register_sensitive_value(username, f"[USER:{user_fingerprint(username)}]")
    register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")

    strict_host_key = _env_bool("SECURITYEXPERT_CP_CONFIG_SSH_STRICT_HOST_KEY", False)
    connect_timeout = _env_int("SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_TIMEOUT_SECONDS", 8, 2, 60)
    command_timeout = _env_int("SECURITYEXPERT_CP_CONFIG_SSH_COMMAND_TIMEOUT_SECONDS", 20, 5, 120)

    targets, selection_gaps = _pick_targets()
    physical_targets = [t for t in targets if t.role != "vsx_virtual_system"]
    vsx_context_targets = [t for t in targets if t.role == "vsx_virtual_system"]

    info(
        ">>> CP CONFIG EVIDENCE PROBE START "
        f"(targets={len(targets)} login_shell=expert raw_config_persisted=false)"
    )
    if not strict_host_key:
        warn(
            ">>> CP CONFIG PROBE host-key policy is observe-and-record compatibility mode; "
            "production collector will require trusted known_hosts or pinned fingerprints"
        )

    results: list[dict[str, Any]] = []
    host_by_device: dict[str, dict[str, Any]] = {}
    for target in physical_targets:
        row = _probe_physical_target(
            target,
            username=username,
            secret=secret,
            strict_host_key=strict_host_key,
            connect_timeout=connect_timeout,
            command_timeout=command_timeout,
        )
        results.append(row)
        if target.role == "vsx_host":
            host_by_device[target.device] = row

    for target in vsx_context_targets:
        row = _probe_vsx_context(
            target,
            host_by_device.get(target.device),
            username=username,
            secret=secret,
            strict_host_key=strict_host_key,
            connect_timeout=connect_timeout,
            command_timeout=command_timeout,
        )
        results.append(row)

    role_map = {row.get("role"): row for row in results}
    required_roles = {"standalone", "clusterxl_member_1", "clusterxl_member_2", "vsx_host", "vsx_virtual_system"}
    missing_roles = sorted(required_roles - set(role_map))
    successful_roles = sorted(role for role, row in role_map.items() if row.get("success"))

    shell_expert_observed = sum(
        1 for row in results
        if row.get("role") != "vsx_virtual_system"
        and str(row.get("login_shell") or "").lower() not in {"", "/etc/clish"}
    )
    secret_lines = sum(
        int(((((row.get("commands") or {}).get("clish_show_configuration") or {}).get("configuration") or {}).get("secret_bearing_lines_detected") or 0))
        + int(((((row.get("commands") or {}).get("clish_set_virtual_system_show_configuration") or {}).get("configuration") or {}).get("secret_bearing_lines_detected") or 0))
        + int(((((row.get("commands") or {}).get("expert_vsenv_then_clish_show_configuration") or {}).get("configuration") or {}).get("secret_bearing_lines_detected") or 0))
        for row in results
    )

    vsx_context_row = role_map.get("vsx_virtual_system") or {}
    summary = {
        "selected_targets": len(targets),
        "selection_gaps": selection_gaps,
        "required_roles_missing": missing_roles,
        "successful_roles": successful_roles,
        "successful_count": sum(1 for row in results if row.get("success")),
        "ssh_reachable_count": sum(1 for row in results if row.get("ssh_reachable")),
        "authenticated_count": sum(1 for row in results if row.get("authenticated")),
        "expert_shell_observed_count": shell_expert_observed,
        "identity_gate_accepted_count": sum(1 for row in results if (row.get("identity_gate") or {}).get("accepted")),
        "identity_hostname_difference_count": sum(1 for row in results if ((row.get("identity_gate") or {}).get("name_relation") == "different_observed")),
        "identity_high_confidence_count": sum(1 for row in results if ((row.get("identity_gate") or {}).get("confidence") == "HIGH")),
        "secret_bearing_lines_detected_in_memory": secret_lines,
        "raw_configuration_persisted": False,
        "host_key_policy": "strict_known_hosts" if strict_host_key else "observe_and_record_not_production",
        "vsx_context_probe_success": vsx_context_row.get("success"),
        "vsx_context_distinct_from_host": vsx_context_row.get("context_distinct_from_host"),
        "probe_gate": bool(
            not missing_roles
            and all((role_map.get(role) or {}).get("success") for role in required_roles)
            and all(((role_map.get(role) or {}).get("identity_gate") or {}).get("accepted") for role in required_roles)
            and not any(gap for gap in selection_gaps)
        ),
    }

    payload = {
        "phase": "0.6.1A.1",
        "title": "Check Point Configuration Identity Gate + VSX Target Resolution",
        "generated_at": _utc_now(),
        "mode": "observe_only",
        "read_only": True,
        "configuration_promoted_to_product": False,
        "raw_configuration_persisted": False,
        "login_shell_contract": "Expert shell; Gaia reads invoked explicitly with clish",
        "transport": "direct_ssh",
        "primary_candidate_method": "expert_ssh_to_gaia_clish_show_configuration",
        "gaia_rest_api": "not_in_this_probe_risk_domain",
        "management_api_role": "selection/topology/intent only; not actual Gaia configuration evidence",
        "settings": {
            "strict_host_key": strict_host_key,
            "connect_timeout_seconds": connect_timeout,
            "command_timeout_seconds": command_timeout,
        },
        "summary": summary,
        "targets": results,
        "sensitivity": "LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE",
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    report_path = OUTPUT_DIR / f"cp_config_probe_{_stamp()}.json"
    tmp = report_path.with_suffix(report_path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(report_path)

    payload["report_path"] = os.fspath(report_path)
    info(
        ">>> CP CONFIG EVIDENCE PROBE DONE "
        f"(success={summary['successful_count']}/{len(results)} gate={summary['probe_gate']} "
        f"vsx_context={summary['vsx_context_probe_success']} raw_config_persisted=false)"
    )
    return payload
