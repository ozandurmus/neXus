"""SecurityExpert — RB.3a CP Gaia backup/snapshot attestation collector.

Contract: `docs/history/phase/RB_3A_CP_GAIA_BACKUP_ATTESTATION.md`
(frozen 2026-08-31); command gate `docs/design/BACKUP_RECOVERY_CONTRACTS.md`
§7.5 (signed off 2026-08-31).

This module opens one SSH session per physical Check Point endpoint, runs
**exactly** the two frozen Clish commands `show backups` and `show snapshots`,
parses their listing output into attestation records, and returns them. It

- collects **no** recovery artifact — the commands return a listing, not an
  archive;
- writes **nothing** to the recovery store;
- never lets a backup/snapshot **name** leave this module (design decision A5):
  a name is parsed into `(class, age_days)` and then discarded. The records
  carry `{class, age_days, source}` and nothing else.

Platform gating (A8): Spark / Gaia Embedded is `UNSUPPORTED` and receives no
command at all. The determination comes from the discovery-lifecycle platform
classification (`_classify_platform()` family, propagated via
`cp_config_telemetry.json`), **never** from shell behaviour.

Session/frequency bounds (A7 / §7.5 point 2): one session runs both commands;
60 s per command; 1 retry; the per-endpoint 1/hour ceiling and the per-vendor
concurrency budget of 1 are enforced by the admission coordinator that the
vendor-neutral orchestrator (`utils.recovery_collect.run_recovery_attestation`)
routes through — not here.
"""
from __future__ import annotations

import os
import re
import shlex
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from typing import Any, Mapping

from configuration.checkpoint_config_probe import ProbeTarget, _connect, _run_exec
from utils.logger import info, register_sensitive_value, user_fingerprint
from utils.recovery_collect import RecoveryAttestationError, RecoveryCollectionTarget

# --- frozen command gate ----------------------------------------------------

# §7.5 point 5 / design decision A4: an explicit literal tuple, NOT a `show `
# prefix test. Widening this is a visible diff that re-trips the command gate.
_ATTESTATION_COMMANDS: tuple[str, ...] = ("show backups", "show snapshots")

_ARTIFACT_CLASS_BY_COMMAND = {
    "show backups": "cp_gaia_backup",
    "show snapshots": "cp_gaia_snapshot",
}

# §7.5 point 2.
_COMMAND_TIMEOUT_SECONDS = 60
_COMMAND_RETRIES = 1

# A8: the discovery-lifecycle platform families that get no command at all.
_UNSUPPORTED_PLATFORM_FAMILIES = frozenset({"gaia_embedded"})

_ATTESTATION_SOURCE = "device_reported"

# CLI-rejection markers. A superset of the probe's list, kept local so a change
# there cannot silently widen what this module treats as "the device answered".
_CLI_ERROR_MARKERS = (
    "command not found",
    "unknown command",
    "invalid command",
    "syntax error",
    "not a valid command",
    "permission denied",
    "not authorized",
    "authorization failed",
    "operation not permitted",
)

_EMPTY_LISTING_MARKERS = (
    "no backups",
    "no snapshots",
    "there are no",
    "no such",
)


def _wire_forms(command: str) -> tuple[str, str]:
    """Return `(direct, clish -c wrapped)` wire forms for a frozen command.

    Raises before any wire form is built if `command` is not one of the two
    frozen attestation commands (AC-6)."""
    if command not in _ATTESTATION_COMMANDS:
        raise ValueError(
            f"refusing a command outside the frozen attestation set "
            f"{_ATTESTATION_COMMANDS!r}: {command!r}"
        )
    return command, f"clish -c {shlex.quote(command)}"


# --- bounded, fail-closed listing parser (design decision A6) --------------

_MONTHS = {
    name.lower(): idx
    for idx, name in enumerate(
        ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
        start=1,
    )
}

_DATE_ISO = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})\b")
# ctime-style, e.g. "Sat Feb 10 14:30:00 2024" (day-name optional, already consumed by \b)
_DATE_CTIME = re.compile(r"\b([A-Za-z]{3})\s+(\d{1,2})\s+\d{1,2}:\d{2}:\d{2}\s+(\d{4})\b")
# "Feb 10, 2024" / "Feb 10 2024"
_DATE_MONTH_D_Y = re.compile(r"\b([A-Za-z]{3})\s+(\d{1,2}),?\s+(\d{4})\b")
# "10-May-2024" / "10_May_2024" / "10 May 2024"
_DATE_D_MONTH_Y = re.compile(r"\b(\d{1,2})[-_ ]([A-Za-z]{3})[-_ ](\d{4})\b")

_PROMPT_RE = re.compile(r"^(\[[^\]]*\][>#]?|\S+>|clish)\b")


@dataclass
class ParsedListing:
    """Result of parsing one command's stdout."""

    records: list[dict[str, Any]] = field(default_factory=list)
    status: str = "parsed"  # "parsed" | "empty_listing" | "cli_error"


def _utc_today() -> date:
    return datetime.now(timezone.utc).date()


def _try_date(text: str, *, now: date) -> date | None:
    """Extract one unambiguous UTC calendar date from `text`, or None.

    Fail-closed: a purely numeric date (`2_10_2024`, `10/05/24`) is *not*
    parsed — month/day order is not knowable — so it yields None and the
    caller records `age_days: null` rather than a guess. A date more than a
    day in the future is treated as unparseable garbage."""
    for pattern in (_DATE_ISO, _DATE_CTIME, _DATE_MONTH_D_Y, _DATE_D_MONTH_Y):
        for match in pattern.finditer(text):
            groups = match.groups()
            try:
                if pattern is _DATE_ISO:
                    year, month, day = int(groups[0]), int(groups[1]), int(groups[2])
                elif pattern is _DATE_D_MONTH_Y:
                    day = int(groups[0])
                    month = _MONTHS.get(groups[1].lower(), 0)
                    year = int(groups[2])
                else:  # _DATE_CTIME, _DATE_MONTH_D_Y
                    month = _MONTHS.get(groups[0].lower(), 0)
                    day = int(groups[1])
                    year = int(groups[2])
                if not month:
                    continue
                parsed = date(year, month, day)
            except ValueError:
                continue
            if (parsed - now).days > 1:
                continue
            return parsed
    return None


def _is_noise_line(line: str) -> bool:
    low = line.lower()
    if _PROMPT_RE.match(low):
        return True
    if low.rstrip(":") in {"name", "backups", "snapshots", "system backups", "local backups"}:
        return True
    if low.endswith(":") and ("backup" in low or "snapshot" in low):
        return True
    if "following" in low and ("backup" in low or "snapshot" in low):
        return True
    # column header row
    if "created" in low and ("size" in low or "name" in low):
        return True
    return False


def parse_gaia_listing(
    stdout: str,
    *,
    artifact_class: str,
    now: date | None = None,
) -> ParsedListing:
    """Parse `show backups` / `show snapshots` output into attestation records.

    One record per artifact the device reports it holds:
    `{"class": artifact_class, "age_days": <int|None>, "source":
    "device_reported"}`. The artifact **name is never included** (A5).

    - CLI rejection / permission denied  -> `([], "cli_error")` (no records).
    - Empty / "no backups" listing       -> `([], "empty_listing")`.
    - An entry whose date does not parse  -> record with `age_days = None`
      (A6: still evidence — dropping it would misreport UNPROTECTED).
    """
    now = now or _utc_today()
    text = stdout or ""
    low = text.lower()

    if any(marker in low for marker in _CLI_ERROR_MARKERS):
        return ParsedListing(records=[], status="cli_error")
    if any(marker in low for marker in _EMPTY_LISTING_MARKERS):
        return ParsedListing(records=[], status="empty_listing")

    records: list[dict[str, Any]] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or _is_noise_line(line):
            continue
        parsed = _try_date(line, now=now)
        age_days = (now - parsed).days if parsed is not None else None
        records.append(
            {"class": artifact_class, "age_days": age_days, "source": _ATTESTATION_SOURCE}
        )

    if not records:
        return ParsedListing(records=[], status="empty_listing")
    return ParsedListing(records=records, status="parsed")


# --- attester --------------------------------------------------------------


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


class CheckpointRecoveryAttester:
    """`RecoveryAttester` for Check Point Gaia — see module docstring.

    Reuses the established CP SSH identity (`SECURITYEXPERT_CP_CONFIG_SSH_*`
    env, falling back to the runtime principal/secret) and the strict
    host-key preflight from `configuration.checkpoint_config_probe._connect`
    verbatim — no new credential, no new transport (privacy invariant)."""

    def __init__(
        self,
        cfg,
        *,
        platform_by_entity: Mapping[str, str] | None = None,
        strict_host_key: bool | None = None,
        connect_timeout: int | None = None,
    ) -> None:
        username = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_USERNAME") or getattr(cfg.auth, "principal", None)
        secret = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD") or getattr(cfg.auth, "secret", None)
        if not username or not secret:
            raise RecoveryAttestationError("CP attestation SSH credentials are unavailable")
        register_sensitive_value(username, f"[USER:{user_fingerprint(username)}]")
        register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")
        self._username = username
        self._secret = secret
        self._platform_by_entity = {str(k): str(v).strip().lower() for k, v in dict(platform_by_entity or {}).items()}
        self._strict = (
            _env_bool("SECURITYEXPERT_CP_CONFIG_SSH_STRICT_HOST_KEY", False)
            if strict_host_key is None
            else bool(strict_host_key)
        )
        self._connect_timeout = connect_timeout or _env_int(
            "SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_TIMEOUT_SECONDS", 8, 2, 60
        )

    # -- A8 platform gate: local, no device contact -------------------------

    def classify_target(self, target: RecoveryCollectionTarget) -> str:
        family = self._platform_by_entity.get(target.entity_id, "")
        return "unsupported" if family in _UNSUPPORTED_PLATFORM_FAMILIES else "supported"

    # -- one session, the two frozen commands ------------------------------

    def attest(self, target: RecoveryCollectionTarget) -> list[dict[str, Any]]:
        row = target.row or {}
        management_ip = str(row.get("management_ip") or row.get("device_ip") or "").strip()
        if not management_ip:
            raise RecoveryAttestationError(
                f"{target.entity_id}: management_ip_unavailable (no reachable address in unified.json row)"
            )
        probe_target = ProbeTarget(
            role="recovery_attestation",
            device=str(row.get("device") or target.entity_id),
            management_ip=management_ip,
            object_type=str(row.get("object_type") or "gateway"),
            cma=row.get("cma"),
        )

        ssh = None
        records: list[dict[str, Any]] = []
        now = _utc_today()
        try:
            ssh, _fingerprint = _connect(
                probe_target,
                self._username,
                self._secret,
                strict=self._strict,
                connect_timeout=self._connect_timeout,
            )
            wrapped_first = False
            for command in _ATTESTATION_COMMANDS:
                result, wrapped_first = self._run_one(ssh, command, prefer_wrapped=wrapped_first)
                parsed = parse_gaia_listing(
                    str(result.get("stdout") or ""),
                    artifact_class=_ARTIFACT_CLASS_BY_COMMAND[command],
                    now=now,
                )
                info(
                    f">>> CP RECOVERY ATTEST {target.entity_id}: {command!r} -> "
                    f"{parsed.status} ({len(parsed.records)} record(s))"
                )
                records.extend(parsed.records)
        finally:
            if ssh is not None:
                try:
                    ssh.close()
                except Exception:
                    pass
        return records

    def _run_one(self, ssh, command: str, *, prefer_wrapped: bool) -> tuple[dict[str, Any], bool]:
        """Run one frozen command, reusing the session. Returns
        `(result, wrapped_was_used)`; the caller carries `wrapped_was_used`
        to the next command so a single session settles on one wire form.

        §7.5 point 2: 1 retry. The estate logs admins into Expert bash, so a
        bare `show ...` may be rejected there — one `clish -c` fallback is
        tried, and that pair is retried once."""
        direct, wrapped = _wire_forms(command)
        order = (wrapped, direct) if prefer_wrapped else (direct, wrapped)

        last: dict[str, Any] = {}
        for attempt in range(_COMMAND_RETRIES + 1):
            for form in order:
                result = _run_exec(ssh, form, _COMMAND_TIMEOUT_SECONDS)
                if result.get("success"):
                    return result, form.startswith("clish -c ")
                last = result
                # release any captured output we are not going to parse
                last_stdout = str(last.get("stdout") or "")
                if not last_stdout.strip():
                    last["stdout"] = last["stderr"] = ""
        return last, prefer_wrapped
