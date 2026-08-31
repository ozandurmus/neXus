"""SecurityExpert — RB.3b CP Gaia system backup recovery collector.

Contract: ``docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`` (cleared for
implementation 2026-08-31); command gate
``docs/design/BACKUP_RECOVERY_CONTRACTS.md`` §7.3 (``add backup local``,
``operational-write``), §7.7 (``/var/log`` free-space read, ``read``), §7.8
(backup deletion, ``operational-write``).

This file currently carries **steps 3–4** of the implementation plan — every
part that is decidable offline and testable without a device:

* **B10 / AC-7** — the pilot allowlist ``SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES``
  (unset or empty ⇒ *no* endpoint may be backed up; refused by name).
* **B11 / AC-11 / D4** — a distinct backup SSH identity
  (``SECURITYEXPERT_CP_BACKUP_SSH_USERNAME`` + ``_PASSWORD_FILE`` / ``_PASSWORD``,
  ``_FILE`` wins), resolved in the constructor, **fail-closed** when absent,
  **never** falling back to ``SECURITYEXPERT_CP_CONFIG_SSH_*``. Only the
  principal and the secret are distinct — transport tunables (port, timeouts,
  strict-host-key) stay shared with the config-SSH env.
* **B7 / AC-8** — a VSX ``<device>__vsid_<id>`` entity is refused before any
  device contact, with a message naming §7.3 point 3.
* **AC-9 / §7.3 point 8** — Spark / Gaia Embedded ⇒ ``UNSUPPORTED``, zero
  commands. The determination is the discovery-lifecycle platform family
  (``cp_config_telemetry.json``), never direct-Clish behaviour.
* **B8 / §3 rule 5 / AC-10** — the Gaia ``software_version`` is resolved from
  existing evidence; if it cannot be, the artifact is **not stored** (no new
  device command for version).
* **§7.7 / AC-1** — the ``/var/log`` free-space parser and the 3× threshold
  arithmetic, run against fixture output.

**Step 5** (the device-touching core: ``add backup local``, SCP fetch into the
encrypting writer, digest verify, deletion) is not in this file yet —
``collect()`` runs the offline gate sequence and then raises until step 5 lands.
"""
from __future__ import annotations

import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping

from configuration.checkpoint_config_collector import _parse_gaia_version
from utils.logger import register_sensitive_value, user_fingerprint
from utils.recovery_collect import RecoveryCollectionError, RecoveryCollectionTarget

# --- artifact class -------------------------------------------------------------

ARTIFACT_CLASS = "cp_gaia_backup"

# --- B10: pilot allowlist -----------------------------------------------------

ALLOWLIST_ENV = "SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES"

# --- B11 / D4: distinct backup credential identity ---------------------------

USERNAME_ENV = "SECURITYEXPERT_CP_BACKUP_SSH_USERNAME"
PASSWORD_FILE_ENV = "SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD_FILE"
PASSWORD_ENV = "SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD"
CREDENTIALS_UNAVAILABLE_REASON = "cp_backup_credentials_unavailable"

# --- AC-9: platform gate (same family label the config collector emits) ------

_UNSUPPORTED_PLATFORM_FAMILIES = frozenset({"gaia_embedded"})

# --- §7.7: free-space floor -------------------------------------------------

MIN_FREE_MB_ENV = "SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB"
DEFAULT_MIN_FREE_MB = 3072   # interim value, accepted at sign-off 2026-08-31
HARD_FLOOR_MB = 1024         # the floor can never be configured below this
_FREE_SPACE_MULTIPLIER = 3   # free ≥ 3× the largest prior backup for this entity


class CpBackupCredentialsUnavailable(RecoveryCollectionError):
    """No distinct ``SECURITYEXPERT_CP_BACKUP_SSH_*`` identity is configured.
    The whole CP collection request is refused — there is no fallback to the
    collection credential (B11)."""


class CpBackupEndpointRefused(RecoveryCollectionError):
    """A per-target request-time refusal (allowlist, VSX, platform, or an
    unresolvable ``software_version``) — raised before any device contact."""


# ---------------------------------------------------------------------------
# B10 — pilot allowlist
# ---------------------------------------------------------------------------

def allowed_backup_entities(env: Mapping[str, str] | None = None) -> frozenset[str]:
    """The comma-separated ``SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`` set.
    Unset or empty ⇒ empty set ⇒ **no endpoint may be backed up** (AC-7)."""
    raw = (env or os.environ).get(ALLOWLIST_ENV, "") or ""
    return frozenset(part.strip() for part in raw.split(",") if part.strip())


def is_entity_allowed(entity_id: str, env: Mapping[str, str] | None = None) -> bool:
    return entity_id in allowed_backup_entities(env)


# ---------------------------------------------------------------------------
# B7 — a VSX virtual system is never a backup target
# ---------------------------------------------------------------------------

def is_vsx_virtual_system(entity_id: str) -> bool:
    return "__vsid_" in (entity_id or "")


# ---------------------------------------------------------------------------
# B11 / D4 — distinct backup SSH identity, fail-closed, no fallback
# ---------------------------------------------------------------------------

def resolve_backup_credentials(env: Mapping[str, str] | None = None) -> tuple[str, str]:
    """``(username, secret)`` for the backup identity, or raise
    ``CpBackupCredentialsUnavailable``. ``_PASSWORD_FILE`` wins over
    ``_PASSWORD`` when both are set. This function reads **only** the
    ``SECURITYEXPERT_CP_BACKUP_SSH_*`` names — never
    ``SECURITYEXPERT_CP_CONFIG_SSH_*``."""
    source = env or os.environ
    username = (source.get(USERNAME_ENV) or "").strip()

    secret = ""
    password_file = (source.get(PASSWORD_FILE_ENV) or "").strip()
    if password_file:
        try:
            secret = Path(password_file).read_text(encoding="utf-8").strip()
        except OSError as exc:
            raise CpBackupCredentialsUnavailable(
                f"{CREDENTIALS_UNAVAILABLE_REASON}: {PASSWORD_FILE_ENV} is set but unreadable: {exc}"
            ) from exc
    else:
        secret = (source.get(PASSWORD_ENV) or "").strip()

    if not username or not secret:
        raise CpBackupCredentialsUnavailable(
            f"{CREDENTIALS_UNAVAILABLE_REASON}: set {USERNAME_ENV} and "
            f"{PASSWORD_FILE_ENV} (server) or {PASSWORD_ENV} (local dev). "
            f"The CP backup identity never falls back to SECURITYEXPERT_CP_CONFIG_SSH_*."
        )
    return username, secret


# ---------------------------------------------------------------------------
# B8 / §3 rule 5 — resolve software_version from existing evidence only
# ---------------------------------------------------------------------------

_ROW_VERSION_KEYS = ("sw_version", "software_version", "gaia_version", "version", "os_version")


def resolve_software_version(
    row: Mapping[str, Any],
    *,
    version_evidence: Callable[[], str | None] | None = None,
) -> str | None:
    """A normalised Gaia release string (``"R81.10"``) or ``None``.

    Order: known version keys on the ``unified.json`` row, then an optional
    caller-supplied ``version_evidence()`` that yields raw text from the
    configuration evidence store. Every candidate is run through
    ``checkpoint_config_collector._parse_gaia_version`` so only a real
    ``R<nn>[.<nn>[.<nn>]]`` token is accepted. No device command (B8)."""
    for key in _ROW_VERSION_KEYS:
        parsed = _parse_gaia_version(str(row.get(key) or ""))
        if parsed:
            return parsed
    if version_evidence is not None:
        try:
            parsed = _parse_gaia_version(str(version_evidence() or ""))
        except Exception:
            parsed = None
        if parsed:
            return parsed
    return None


# ---------------------------------------------------------------------------
# §7.7 — /var/log free-space read + parser (class: read)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class DiskUsage:
    mount: str
    free_mb: int
    total_mb: int


def _parse_df_p(stdout: str) -> list[DiskUsage]:
    """POSIX ``df -P`` output. ``-P`` guarantees one physical line per
    filesystem and columns: Filesystem, 1024-blocks, Used, Available,
    Capacity, Mounted-on. Available/Used are 1024-byte blocks."""
    rows: list[DiskUsage] = []
    for line in (stdout or "").splitlines():
        parts = line.split()
        if len(parts) != 6:
            continue
        if parts[1].lower().startswith("1024") or parts[0].lower() == "filesystem":
            continue
        try:
            total_kb = int(parts[1])
            avail_kb = int(parts[3])
        except ValueError:
            continue
        if not parts[5].startswith("/"):
            continue
        rows.append(DiskUsage(mount=parts[5], free_mb=avail_kb // 1024, total_mb=total_kb // 1024))
    return rows


_DISKSPACE_UNIT_MB = {"mb": 1, "gb": 1024, "kb": 1 / 1024, "k": 1 / 1024, "m": 1, "g": 1024}


def _parse_show_diskspace(stdout: str) -> list[DiskUsage]:
    """Best-effort ``show diskspace`` (Clish) parser. The R81 doc-check found
    this form in no published Gaia Clish command list, so ``df -P`` is the
    reliable path; this is kept only for the first watched real-gateway run.
    Recognises rows of the shape ``<mount> <total><unit> <free/avail><unit>``
    or an explicit ``Free`` figure alongside a mount path."""
    import re

    rows: list[DiskUsage] = []
    num_unit = r"([\d.]+)\s*([KkMmGg][Bb]?)"
    for line in (stdout or "").splitlines():
        low = line.lower()
        if "/var/log" not in low and "/ " not in f"{line} " and " / " not in f" {line} ":
            continue
        mount_match = re.search(r"(/[\w./-]*)", line)
        nums = re.findall(num_unit, line)
        if not mount_match or len(nums) < 2:
            continue
        try:
            total_mb = int(float(nums[0][0]) * _DISKSPACE_UNIT_MB[nums[0][1].lower()])
            free_mb = int(float(nums[-1][0]) * _DISKSPACE_UNIT_MB[nums[-1][1].lower()])
        except (KeyError, ValueError):
            continue
        rows.append(DiskUsage(mount=mount_match.group(1).rstrip("/") or "/", free_mb=free_mb, total_mb=total_mb))
    return rows


def parse_var_log_free(stdout: str) -> DiskUsage | None:
    """The mount backing ``/var/log`` → else ``/`` → else ``None``. A ``None``
    return makes §7.3 point 12 abort the backup — an unparseable disk reading
    is never treated as "probably fine" (§7.7 point 8)."""
    rows = _parse_df_p(stdout) or _parse_show_diskspace(stdout)
    if not rows:
        return None
    by_mount = {r.mount.rstrip("/") or "/": r for r in rows}
    # longest mount prefix of /var/log wins (…/var/log, then /var, then /)
    best: DiskUsage | None = None
    for mount, usage in by_mount.items():
        if mount == "/" or "/var/log".startswith(mount + "/") or mount == "/var/log":
            if best is None or len(mount) > len(best.mount.rstrip("/") or "/"):
                best = usage
    if best is not None:
        return best
    return by_mount.get("/")


# ---------------------------------------------------------------------------
# §7.7 — 3× threshold arithmetic
# ---------------------------------------------------------------------------

def min_free_floor_mb(env: Mapping[str, str] | None = None) -> int:
    """``SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB`` clamped to ``>= HARD_FLOOR_MB``."""
    raw = (env or os.environ).get(MIN_FREE_MB_ENV)
    try:
        value = int(raw) if raw is not None and str(raw).strip() != "" else DEFAULT_MIN_FREE_MB
    except (TypeError, ValueError):
        value = DEFAULT_MIN_FREE_MB
    return max(HARD_FLOOR_MB, value)


def required_free_mb(prior_backup_sizes_bytes: list[int], env: Mapping[str, str] | None = None) -> int:
    """Free space required on ``/var/log`` before ``add backup local`` runs:
    ``3×`` the largest prior ``cp_gaia_backup`` for this entity; with no prior
    backup, the ``min_free_floor_mb()`` floor (§7.7)."""
    sizes = [int(s) for s in (prior_backup_sizes_bytes or []) if int(s) > 0]
    if not sizes:
        return min_free_floor_mb(env)
    return math.ceil(_FREE_SPACE_MULTIPLIER * max(sizes) / (1024 * 1024))


# ---------------------------------------------------------------------------
# collector
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class BackupPrecheck:
    """The offline gate outcome for one endpoint — everything resolved before
    a single byte crosses the wire."""

    entity_id: str
    software_version: str
    required_free_mb: int


class CheckpointGaiaBackupCollector:
    """``RecoveryCollector`` for CP Gaia system backup.

    The constructor resolves the distinct backup credential (B11) and fails
    closed if it is absent — so a missing identity refuses the whole CP
    request before target selection, not per endpoint.
    """

    def __init__(
        self,
        cfg: Any = None,
        *,
        platform_by_entity: Mapping[str, str] | None = None,
        version_evidence_by_entity: Mapping[str, Callable[[], str | None]] | None = None,
        prior_backup_sizes_by_entity: Mapping[str, list[int]] | None = None,
        env: Mapping[str, str] | None = None,
    ) -> None:
        self._env = dict(env or os.environ)
        username, secret = resolve_backup_credentials(self._env)
        register_sensitive_value(username, f"[USER:{user_fingerprint(username)}]")
        register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")
        self._username = username
        self._secret = secret
        self._platform_by_entity = {
            str(k): str(v).strip().lower() for k, v in dict(platform_by_entity or {}).items()
        }
        self._version_evidence_by_entity = dict(version_evidence_by_entity or {})
        self._prior_backup_sizes_by_entity = dict(prior_backup_sizes_by_entity or {})

    # -- local platform gate, no device contact (AC-9) --------------------

    def classify_target(self, target: RecoveryCollectionTarget) -> str:
        family = self._platform_by_entity.get(target.entity_id, "")
        return "unsupported" if family in _UNSUPPORTED_PLATFORM_FAMILIES else "supported"

    # -- B7: refused before admission / before any device contact --------

    def reject_before_admission(self, target: RecoveryCollectionTarget) -> str | None:
        if is_vsx_virtual_system(target.entity_id):
            return (
                f"{target.entity_id}: a VSX virtual system is never a backup target — "
                f"backup runs per physical endpoint only (contract §7.3 point 3)"
            )
        return None

    # -- the offline gate sequence: allowlist → platform → version -------

    def precheck(self, target: RecoveryCollectionTarget) -> BackupPrecheck:
        entity_id = target.entity_id

        vsx = self.reject_before_admission(target)
        if vsx:
            raise CpBackupEndpointRefused(vsx)

        if not is_entity_allowed(entity_id, self._env):
            raise CpBackupEndpointRefused(
                f"{entity_id}: not in the {ALLOWLIST_ENV} pilot allowlist "
                f"(the allowlist is empty by default and fail-closed — B10 / AC-7)"
            )

        if self.classify_target(target) == "unsupported":
            raise CpBackupEndpointRefused(
                f"{entity_id}: platform UNSUPPORTED (Spark / Gaia Embedded); "
                f"no command sent (contract §7.3 point 8 / AC-9)"
            )

        version = resolve_software_version(
            target.row or {},
            version_evidence=self._version_evidence_by_entity.get(entity_id),
        )
        if not version:
            raise CpBackupEndpointRefused(
                f"{entity_id}: Gaia software_version is unresolvable from existing "
                f"evidence — a version-locked cp_gaia_backup is NOT stored "
                f"(contract §3 rule 5 / B8 / AC-10)"
            )

        floor = required_free_mb(
            self._prior_backup_sizes_by_entity.get(entity_id, []), self._env
        )
        return BackupPrecheck(entity_id=entity_id, software_version=version, required_free_mb=floor)

    # -- collect() ------------------------------------------------------------

    def collect(self, target: RecoveryCollectionTarget) -> tuple[bytes, dict[str, Any]]:
        self.precheck(target)
        raise RecoveryCollectionError(
            f"{target.entity_id}: RB.3b step 5 (add backup local / SCP fetch / delete) "
            f"is not implemented in this build"
        )
