from __future__ import annotations

import hashlib
import json
import os
import re
import socket
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import paramiko

from utils.cp_ssh_trust import CpSshStrictPreflightError, apply_strict_host_key_policy
from utils.logger import info, warn, register_sensitive_value, user_fingerprint

OUTPUT_FILE = Path("output/cp_direct_ssh_probe.json")


READ_ONLY_COMMANDS = {
    # 0.5.1 intentionally does NOT collect configuration. These commands only
    # establish direct-SSH capability and the operational CLI shape required
    # for a later, evidence-driven Spark adapter.
    "version": [
        "show version all",
        "show version",
        'clish -c "show version all"',
        'clish -c "show version"',
    ],
    "interfaces": [
        "show interfaces table",
        "show interfaces",
        'clish -c "show interfaces table"',
        'clish -c "show interfaces"',
    ],
    "routes": [
        "show route all",
        "show route",
        'clish -c "show route all"',
        'clish -c "show route"',
    ],
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


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off", "disabled"}


def _env_int(name: str, default: int, *, minimum: int = 1, maximum: int = 120) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


def _sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8", errors="ignore")).hexdigest()


def _looks_like_cli_error(stdout: str, stderr: str) -> bool:
    haystack = f"{stdout}\n{stderr}".lower()
    return any(pattern in haystack for pattern in CLI_ERROR_PATTERNS)


def _run_session_command(ssh: paramiko.SSHClient, command: str, timeout_seconds: int) -> dict[str, Any]:
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
        # Gaia / Gaia Embedded restricted shells are more consistent when a
        # pseudo-terminal is requested for remote command execution.
        try:
            channel.get_pty(term="vt100", width=160, height=48)
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
            "command": command,
            "error_class": "execution_error",
            "error_detail": type(exc).__name__,
            "timeout": False,
            "exit_status": exit_status,
            "duration_ms": int((time.monotonic() - started) * 1000),
            "stdout": "",
            "stderr": "",
            "stdout_bytes": 0,
            "stdout_lines": 0,
            "stderr_bytes": 0,
            "fingerprint": None,
        }

    stdout = b"".join(stdout_chunks).decode("utf-8", errors="ignore")
    stderr = b"".join(stderr_chunks).decode("utf-8", errors="ignore")
    duration_ms = int((time.monotonic() - started) * 1000)

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
        "command": command,
        "error_class": error_class,
        "error_detail": None,
        "timeout": timed_out,
        "exit_status": exit_status,
        "duration_ms": duration_ms,
        "stdout": stdout,
        "stderr": stderr,
        "stdout_bytes": len(stdout.encode("utf-8", errors="ignore")),
        "stdout_lines": len(stdout.splitlines()),
        "stderr_bytes": len(stderr.encode("utf-8", errors="ignore")),
        "fingerprint": _sha256_text(stdout) if stdout else None,
    }


def _run_command_family(ssh: paramiko.SSHClient, family: str, timeout_seconds: int) -> dict[str, Any]:
    attempts = []
    for command in READ_ONLY_COMMANDS[family]:
        result = _run_session_command(ssh, command, timeout_seconds)
        attempts.append(result)
        if result.get("success"):
            result["attempts"] = len(attempts)
            result["attempted_commands"] = [x.get("command") for x in attempts]
            return result

    final = attempts[-1] if attempts else {
        "success": False,
        "error_class": "not_attempted",
        "stdout": "",
        "stderr": "",
    }
    final = dict(final)
    final["attempts"] = len(attempts)
    final["attempted_commands"] = [x.get("command") for x in attempts]
    return final


def _platform_hint(command_results: dict[str, Any]) -> str:
    text = "\n".join(
        str((command_results.get(name) or {}).get("stdout") or "")
        for name in ("version", "interfaces", "routes")
    ).lower()
    if "gaia embedded" in text or "quantum spark" in text or re.search(r"\bspark\b", text):
        return "quantum_spark"
    # Spark appliance families are commonly named by 15xx/16xx/18xx/19xx/20xx.
    # This remains a hint, never an authoritative device classification.
    if re.search(r"\b(?:15|16|18|19|20)\d{2}\b", text) and "check point" in text:
        return "quantum_spark_candidate"
    if (command_results.get("interfaces") or {}).get("success") and (command_results.get("routes") or {}).get("success"):
        return "gaia_cli_candidate"
    return "unknown"


def _probe_one(
    row: dict[str, Any],
    *,
    username: str,
    secret: str,
    port: int,
    connect_timeout: int,
    command_timeout: int,
    strict_host_key: bool,
) -> dict[str, Any]:
    device = str(row.get("device") or "")
    ip = str(row.get("management_ip") or "")
    started = time.monotonic()
    result: dict[str, Any] = {
        "device": device,
        "management_ip": ip or None,
        "management_state": row.get("management_state"),
        "cprid_outcome": row.get("collection_outcome"),
        "cprid_interface_error": row.get("interface_error"),
        "cprid_route_error": row.get("route_error"),
        "ssh_port": port,
        "ssh_reachable": False,
        "authenticated": False,
        "host_key_policy": "strict" if strict_host_key else "autoadd_observe_only",
        "platform_hint": "unknown",
        "inventory_cli_capable": False,
        "commands": {},
        "error_class": None,
        "duration_ms": None,
    }

    if not ip:
        result["error_class"] = "management_ip_missing"
        result["duration_ms"] = int((time.monotonic() - started) * 1000)
        return result

    # 0.6.1B.1.3 safety audit finding: same fix as configuration/checkpoint_config_probe.py.
    # One bounded retry on reachability/timeout-class errors only; auth and
    # host-key failures are never retried.
    connect_retries = _env_int("FBUDDY_CP_DIRECT_SSH_CONNECT_RETRIES", 1, minimum=0, maximum=2)
    connect_retry_backoff = _env_int("FBUDDY_CP_DIRECT_SSH_CONNECT_RETRY_BACKOFF_SECONDS", 2, minimum=1, maximum=10)
    attempts_allowed = 1 + connect_retries

    ssh = None
    try:
        # Preflight: verify trusted host-key material before any connection
        # attempt.  This must happen outside the retry loop; a missing
        # known_hosts is not a retry-able condition.
        if strict_host_key:
            _pre = paramiko.SSHClient()
            try:
                apply_strict_host_key_policy(_pre, strict=True)
            except CpSshStrictPreflightError:
                result["error_class"] = "strict_host_key_preflight_failed"
                result["duration_ms"] = int((time.monotonic() - started) * 1000)
                return result
            finally:
                try:
                    _pre.close()
                except Exception:
                    pass
        for attempt in range(1, attempts_allowed + 1):
            ssh = paramiko.SSHClient()
            apply_strict_host_key_policy(ssh, strict_host_key)
            try:
                ssh.connect(
                    ip,
                    port=port,
                    username=username,
                    **{"password": secret},
                    timeout=connect_timeout,
                    banner_timeout=connect_timeout,
                    auth_timeout=connect_timeout,
                    look_for_keys=False,
                    allow_agent=False,
                )
                break
            except (socket.timeout, TimeoutError, paramiko.SSHException, OSError):
                try:
                    ssh.close()
                except Exception:
                    pass
                if attempt < attempts_allowed:
                    time.sleep(connect_retry_backoff)
                    continue
                raise

        result["ssh_reachable"] = True
        result["authenticated"] = True

        commands = {
            family: _run_command_family(ssh, family, command_timeout)
            for family in ("version", "interfaces", "routes")
        }
        result["commands"] = commands
        result["platform_hint"] = _platform_hint(commands)
        result["inventory_cli_capable"] = bool(
            (commands.get("interfaces") or {}).get("success")
            and (commands.get("routes") or {}).get("success")
        )
        result["error_class"] = "none" if result["inventory_cli_capable"] else "inventory_commands_unavailable"
    except paramiko.AuthenticationException:
        # Authentication proves TCP/SSH reachability even if the credential is
        # not accepted. This distinction is useful for future secret routing.
        result["ssh_reachable"] = True
        result["authenticated"] = False
        result["error_class"] = "authentication_failed"
    except paramiko.BadHostKeyException:
        result["ssh_reachable"] = True
        result["error_class"] = "host_key_mismatch"
    except (socket.timeout, TimeoutError):
        result["error_class"] = "connect_timeout"
    except (paramiko.SSHException, OSError) as exc:
        text = str(exc).lower()
        if "unable to connect" in text or "connection refused" in text or "no route" in text:
            result["error_class"] = "ssh_unreachable"
        else:
            result["error_class"] = "ssh_error"
        result["error_detail"] = type(exc).__name__
    finally:
        try:
            if ssh is not None:
                ssh.close()
        except Exception:
            pass

    result["duration_ms"] = int((time.monotonic() - started) * 1000)
    return result


def _safe_command_summary(result: dict[str, Any]) -> dict[str, Any]:
    """Return command metadata without copying command output."""
    return {
        "success": result.get("success"),
        "command": result.get("command"),
        "attempts": result.get("attempts"),
        "attempted_commands": result.get("attempted_commands"),
        "error_class": result.get("error_class"),
        "timeout": result.get("timeout"),
        "exit_status": result.get("exit_status"),
        "duration_ms": result.get("duration_ms"),
        "stdout_bytes": result.get("stdout_bytes"),
        "stdout_lines": result.get("stdout_lines"),
        "stderr_bytes": result.get("stderr_bytes"),
        "fingerprint": result.get("fingerprint"),
    }


def probe_direct_ssh_fallback(cfg, collection_status: list[dict[str, Any]]) -> dict[str, Any]:
    output_file = (Path(cfg.runtime_paths.output_root) / "cp_direct_ssh_probe.json") if getattr(cfg, "runtime_paths", None) is not None else OUTPUT_FILE
    """Observe direct SSH capability for CP devices that CPRID could not collect.

    Phase 0.5.1 is deliberately probe-only: it does not promote the SSH output
    into cp.json and does not make a failed device LIVE. The purpose is to prove
    reachability/authentication/CLI compatibility before a vendor-specific
    parser is allowed to affect inventory correctness.
    """
    enabled = _env_bool("FBUDDY_CP_DIRECT_SSH_PROBE_ENABLED", True)
    candidates = [
        row for row in collection_status
        if row.get("collection_outcome") in {"collection_failed", "partial"}
        and str(row.get("management_state") or "unknown").lower() in {"communicating", "unknown", ""}
    ]

    payload: dict[str, Any] = {
        "phase": "0.5.1",
        "mode": "observe_only",
        "generated_at": _utc_now(),
        "enabled": enabled,
        "read_only": True,
        "configuration_collected": False,
        "candidate_count": len(candidates),
        "summary": {},
        "devices": [],
    }

    if not enabled or not candidates:
        payload["summary"] = {
            "candidates": len(candidates),
            "probed": 0,
            "ssh_reachable": 0,
            "authenticated": 0,
            "inventory_cli_capable": 0,
        }
        output_file.parent.mkdir(parents=True, exist_ok=True)
        tmp = output_file.with_suffix(output_file.suffix + ".tmp")
        tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        tmp.replace(output_file)
        return payload

    username = os.getenv("FBUDDY_CP_DIRECT_SSH_USERNAME") or cfg.auth.principal
    secret = os.getenv("FBUDDY_CP_DIRECT_SSH_PASSWORD") or cfg.auth.secret
    if not username or not secret:
        payload["summary"] = {
            "candidates": len(candidates),
            "probed": 0,
            "ssh_reachable": 0,
            "authenticated": 0,
            "inventory_cli_capable": 0,
            "credential_state": "unavailable",
        }
        output_file.parent.mkdir(parents=True, exist_ok=True)
        tmp = output_file.with_suffix(output_file.suffix + ".tmp")
        tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        tmp.replace(output_file)
        return payload

    # Register optional environment credentials with the central redactor. The
    # actual credential values are never written into the probe artifact.
    register_sensitive_value(username, f"[USER:{user_fingerprint(username)}]")
    register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")

    port = _env_int("FBUDDY_CP_DIRECT_SSH_PORT", 22, maximum=65535)
    connect_timeout = _env_int("FBUDDY_CP_DIRECT_SSH_CONNECT_TIMEOUT_SECONDS", 8, maximum=60)
    command_timeout = _env_int("FBUDDY_CP_DIRECT_SSH_COMMAND_TIMEOUT_SECONDS", 20, maximum=120)
    parallelism = _env_int("FBUDDY_CP_DIRECT_SSH_PARALLELISM", 4, maximum=12)
    strict_host_key = _env_bool("FBUDDY_CP_DIRECT_SSH_STRICT_HOST_KEY", False)

    info(f">>> CP DIRECT SSH FALLBACK PROBE ({len(candidates)} candidates, parallelism={parallelism}, read-only)")
    if not strict_host_key:
        warn(">>> CP DIRECT SSH PROBE host-key verification is compatibility mode (AutoAddPolicy); production hardening must use trusted known_hosts")

    results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=parallelism, thread_name_prefix="cp-direct-ssh") as executor:
        futures = {
            executor.submit(
                _probe_one,
                row,
                username=username,
                secret=secret,
                port=port,
                connect_timeout=connect_timeout,
                command_timeout=command_timeout,
                strict_host_key=strict_host_key,
            ): row
            for row in candidates
        }
        for future in as_completed(futures):
            results.append(future.result())

    # Keep deterministic order for repeatable support diagnostics.
    results.sort(key=lambda row: str(row.get("device") or ""))
    payload["devices"] = results
    payload["settings"] = {
        "port": port,
        "connect_timeout_seconds": connect_timeout,
        "command_timeout_seconds": command_timeout,
        "parallelism": parallelism,
        "strict_host_key": strict_host_key,
        "credential_source": "environment_override" if os.getenv("FBUDDY_CP_DIRECT_SSH_USERNAME") or os.getenv("FBUDDY_CP_DIRECT_SSH_PASSWORD") else "primary_runtime_credential",
    }
    payload["summary"] = {
        "candidates": len(candidates),
        "probed": len(results),
        "ssh_reachable": sum(1 for row in results if row.get("ssh_reachable")),
        "authenticated": sum(1 for row in results if row.get("authenticated")),
        "inventory_cli_capable": sum(1 for row in results if row.get("inventory_cli_capable")),
        "spark_hints": sum(1 for row in results if str(row.get("platform_hint") or "").startswith("quantum_spark")),
        "authentication_failed": sum(1 for row in results if row.get("error_class") == "authentication_failed"),
        "ssh_unreachable": sum(1 for row in results if row.get("error_class") in {"ssh_unreachable", "connect_timeout"}),
    }

    output_file.parent.mkdir(parents=True, exist_ok=True)
    tmp = output_file.with_suffix(output_file.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(output_file)

    # Do not log identity or IP; only aggregate evidence is emitted.
    info(
        ">>> CP DIRECT SSH PROBE DONE "
        f"(reachable={payload['summary']['ssh_reachable']} auth={payload['summary']['authenticated']} "
        f"cli_capable={payload['summary']['inventory_cli_capable']} spark_hints={payload['summary']['spark_hints']})"
    )
    return payload


def support_safe_probe(probe: dict[str, Any]) -> dict[str, Any]:
    """Strip raw CLI output before support-bundle anonymization."""
    safe = {
        key: value
        for key, value in probe.items()
        if key != "devices"
    }
    safe_devices = []
    for row in probe.get("devices") or []:
        safe_row = {
            key: value
            for key, value in row.items()
            if key != "commands"
        }
        safe_row["commands"] = {
            family: _safe_command_summary(result or {})
            for family, result in (row.get("commands") or {}).items()
        }
        safe_devices.append(safe_row)
    safe["devices"] = safe_devices
    return safe
