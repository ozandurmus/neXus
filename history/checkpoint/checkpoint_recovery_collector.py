"""SecurityExpert — RB.3b CP Gaia system backup recovery collector.

Contract: ``docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md``; command gate
``docs/design/BACKUP_RECOVERY_CONTRACTS.md`` §7.3 (``add backup local``,
``operational-write``), §7.4 (backup-file fetch, ``read``), §7.7 (``/var/log``
free-space read, ``read``), §7.8 (backup deletion, ``operational-write``).

Steps 3–4 (offline, device-free) **and step 5** (the device-touching core) are
both in this file now:

* **B10 / AC-7** — pilot allowlist ``SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES``
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
  existing evidence; if it cannot be, the artifact is **not stored**.
* **§7.7 / AC-1 / §9.10** — the ``/var/log`` free-space read + 3× threshold;
  an ``UNKNOWN`` reading aborts the backup with no command sent.
* **B4 / AC-6 / §9.13** — the durable ``operational-write`` ledger is read
  **inside** the admission-held section (``collect()`` runs inside
  ``run_under_admission``): an entry inside the 24 h window ⇒
  ``RecoveryCollectionSkipped`` with **zero** device contact; an *unreadable*
  ledger ⇒ blocked, no command sent; a prior ``cleanup_failed`` newest entry ⇒
  the endpoint is **ineligible** until an operator clears it. The ledger entry
  is written once, in ``collect()``'s ``finally``, **iff** ``add backup local``
  was actually sent (§10 g).
* **§7.3 / §7.4 / §7.8 / correctness contract** — one SSH session, in order:
  free-space read → ``add backup local`` (**no retry**, 900 s) → SFTP fetch
  straight into memory (**no plaintext temp file**, B6) → size verify against
  the device-reported size → **store** (persisted here, inside the admitted
  session, *before* the delete — correctness rule 1) → **delete exactly the
  archive this run created**, whose name is held in memory from ``add backup
  local``'s own output — never a listing, never a pattern (§7.8 point 12). Any
  failure *after* ``add backup local`` still runs the delete (§7.3 point 13); a
  delete that will not confirm ⇒ ``CLEANUP_FAILED`` + endpoint ineligible.

**CONFIRM-ON-HARDWARE** (contract §7.7 / §7.8 sign-off notes; estate = R81.10 +
R81.20). The literal Clish forms (``show diskspace`` / ``delete backup <name>``)
are carried alongside the exact Expert forms (``df -P /var/log`` /
``rm -f -- /var/log/CPbackup/backups/<name>``). The R81 doc check found neither
Clish form in the published command lists, so the Expert forms are the primary
path; the first watched real R81.10 / R81.20 run confirms whether the Clish
forms exist on the build. Every form is an explicit literal in a frozen tuple —
never a ``show ``/prefix-rule relaxation (B1). Likewise the exact wording of
``add backup local``'s completion line and the archive-name format are confirmed
on that first run; until then an unparseable name is handled loudly as
``CLEANUP_FAILED`` rather than guessed.
"""
from __future__ import annotations

import functools
import io
import math
import os
import re
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping

from configuration.checkpoint_config_collector import _parse_gaia_version
from configuration.checkpoint_config_probe import ProbeTarget, _connect, _run_exec
from utils.logger import info, register_sensitive_value, user_fingerprint
from utils.recovery_collect import (
    RecoveryCollectionError,
    RecoveryCollectionSkipped,
    RecoveryCollectionTarget,
    build_recovery_device_block,
)
from utils.recovery_operational_ledger import (
    DEFAULT_WINDOW,
    OperationalLedgerUnreadableError,
    RecoveryOperationalLedger,
)

# --- artifact class -----------------------------------------------------------

ARTIFACT_CLASS = "cp_gaia_backup"

# --- B10: pilot allowlist ---------------------------------------------------

ALLOWLIST_ENV = "SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES"

# --- B11 / D4: distinct backup credential identity -------------------------

USERNAME_ENV = "SECURITYEXPERT_CP_BACKUP_SSH_USERNAME"
PASSWORD_FILE_ENV = "SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD_FILE"
PASSWORD_ENV = "SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD"
CREDENTIALS_UNAVAILABLE_REASON = "cp_backup_credentials_unavailable"

# Transport tunables — B11: shared with the config-SSH env, only the principal
# and secret are distinct.
_STRICT_HOST_KEY_ENV = "SECURITYEXPERT_CP_CONFIG_SSH_STRICT_HOST_KEY"
_CONNECT_TIMEOUT_ENV = "SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_TIMEOUT_SECONDS"

# --- AC-9: platform gate (same family label the config collector emits) ----

_UNSUPPORTED_PLATFORM_FAMILIES = frozenset({"gaia_embedded"})

# --- §7.7: free-space floor ----------------------------------------------

MIN_FREE_MB_ENV = "SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB"
DEFAULT_MIN_FREE_MB = 3072   # interim value, accepted at sign-off 2026-08-31
HARD_FLOOR_MB = 1024         # the floor can never be configured below this
_FREE_SPACE_MULTIPLIER = 3   # free >= 3x the largest prior backup for this entity

# --- device core: frozen command forms + bounds --------------------------
#
# CONFIRM-ON-HARDWARE (see the module docstring). Each tuple is an explicit
# literal set, tried in order; the Expert form is the reliable one per the
# §7.7 / §7.8 sign-off notes.

_BACKUP_DIR = "/var/log/CPbackup/backups"          # sk108902, §7.8 sign-off note

_FREE_SPACE_FORMS: tuple[str, ...] = ("clish -c 'show diskspace'", "df -P /var/log")
_FREE_SPACE_TIMEOUT = 30                            # §7.7 point 4
_FREE_SPACE_RETRIES = 1                             # §7.7 point 5 (transport only)

_ADD_BACKUP_FORMS: tuple[str, ...] = ("clish -c 'add backup local'", "add backup local")
_ADD_BACKUP_TIMEOUT = 900                           # §7.3 point 4
# §7.3 point 5: NO retry. The second tuple entry is not a retry — it is the
# bare-shell wrapper tried only when the first form was cleanly rejected by the
# CLI (a "command not found" definitively created nothing).

_DELETE_TIMEOUT = 60                                # §7.8 point 4
_DELETE_RETRIES = 1                                 # §7.8 point 5 — retrying a delete is safer

_FETCH_TIMEOUT = 900                                # §7.4

# A shell/CLI rejection that cannot have started a backup.
_SHELL_REJECTION_MARKERS = (
    "command not found",
    "unknown command",
    "invalid command",
    "not a valid command",
    "syntax error",
)

# The archive name is an operational identity (§7.8 point 9). It is parsed ONLY
# from `add backup local`'s own output, validated against this strict pattern,
# registered as a sensitive value, and never written to a manifest or an
# unredacted log line.
_ARCHIVE_PATH_RE = re.compile(r"/var/log/CPbackup/backups/(backup_[A-Za-z0-9][A-Za-z0-9._-]*\.tgz)\b")
_ARCHIVE_NAME_RE = re.compile(r"\b(backup_[A-Za-z0-9][A-Za-z0-9._-]*\.tgz)\b")
_SAFE_ARCHIVE_NAME_RE = re.compile(r"\Abackup_[A-Za-z0-9][A-Za-z0-9._-]*\.tgz\Z")


class CpBackupCredentialsUnavailable(RecoveryCollectionError):
    """No distinct ``SECURITYEXPERT_CP_BACKUP_SSH_*`` identity is configured.
    The whole CP collection request is refused — there is no fallback to the
    collection credential (B11)."""


class CpBackupEndpointRefused(RecoveryCollectionError):
    """A per-target request-time refusal (allowlist, VSX, platform, or an
    unresolvable ``software_version``) — raised before any device contact."""


# ---------------------------------------------------------------------------
# env helpers (operate on the injected mapping, not os.environ directly)
# ---------------------------------------------------------------------------

def _env_bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name)
    if raw is None:
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off", "disabled"}


def _env_int(env: Mapping[str, str], name: str, default: int, lo: int, hi: int) -> int:
    try:
        value = int(env.get(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(lo, min(hi, value))


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
# §7.7 — 3x threshold arithmetic
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
    ``3x`` the largest prior ``cp_gaia_backup`` for this entity; with no prior
    backup, the ``min_free_floor_mb()`` floor (§7.7)."""
    sizes = [int(s) for s in (prior_backup_sizes_bytes or []) if int(s) > 0]
    if not sizes:
        return min_free_floor_mb(env)
    return math.ceil(_FREE_SPACE_MULTIPLIER * max(sizes) / (1024 * 1024))


# ---------------------------------------------------------------------------
# device transport seam (fixture-injectable) — wraps checkpoint_config_probe
# ---------------------------------------------------------------------------

class BackupSshSession:
    """One SSH session for a CP Gaia backup: runs the frozen commands and
    fetches one known file into memory. Wraps
    ``configuration.checkpoint_config_probe`` transport verbatim — no new
    transport, no new credential (B11). Tests inject a fake with the same
    surface via ``session_factory``."""

    def __init__(self, ssh: Any) -> None:
        self._ssh = ssh

    def run(self, command: str, timeout: int) -> dict[str, Any]:
        return _run_exec(self._ssh, command, timeout)

    def remote_size(self, remote_path: str, timeout: int) -> int | None:
        """SFTP ``stat`` of a known path — ``st_size`` in bytes, or ``None``
        when the path does not exist. Not a listing (§7.8 point 12)."""
        sftp = self._ssh.open_sftp()
        try:
            try:
                sftp.get_channel().settimeout(timeout)
            except Exception:
                pass
            try:
                st = sftp.stat(remote_path)
            except FileNotFoundError:
                return None
            return int(getattr(st, "st_size", 0) or 0)
        finally:
            self._safe_close(sftp)

    def fetch(self, remote_path: str, timeout: int) -> bytes:
        """Stream a known path straight into memory via SFTP ``getfo`` — no
        plaintext temp file at any point (B6 / §9.1)."""
        sftp = self._ssh.open_sftp()
        try:
            try:
                sftp.get_channel().settimeout(timeout)
            except Exception:
                pass
            buf = io.BytesIO()
            sftp.getfo(remote_path, buf)
            return buf.getvalue()
        finally:
            self._safe_close(sftp)

    def close(self) -> None:
        self._safe_close(self._ssh)

    @staticmethod
    def _safe_close(handle: Any) -> None:
        try:
            handle.close()
        except Exception:
            pass


def _default_session_factory(
    target: ProbeTarget, *, username: str, secret: str, strict: bool, connect_timeout: int
) -> BackupSshSession:
    ssh, _fingerprint = _connect(
        target, username, secret, strict=strict, connect_timeout=connect_timeout
    )
    return BackupSshSession(ssh)


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


@dataclass
class _DeviceOutcome:
    """Mutable per-endpoint state carried through the device sequence so the
    ``collect()`` ``finally`` can record the ledger correctly."""

    backup_sent: bool = False
    archive_name: str | None = None
    stored_artifact_id: str | None = None
    ledger_outcome: str | None = None   # "completed" | "failed" | "cleanup_failed"
    result: tuple[bytes, dict[str, Any]] | None = None


class CheckpointGaiaBackupCollector:
    """``RecoveryCollector`` for CP Gaia system backup.

    The constructor resolves the distinct backup credential (B11) and fails
    closed if it is absent — so a missing identity refuses the whole CP
    request before target selection, not per endpoint. ``collect()`` then runs
    the offline gate sequence and, if it passes, the one-SSH-session device
    core (see the module docstring). ``collect()`` is always invoked inside
    ``run_recovery_collection``'s ``run_under_admission`` callable, so the
    ledger read and write land inside the admission-held section (§9.13 f).
    """

    def __init__(
        self,
        cfg: Any = None,
        *,
        ledger: RecoveryOperationalLedger | None = None,
        recovery_paths: Any = None,
        vault_key: bytes | None = None,
        vault_key_id: str | None = None,
        run_id: str | None = None,
        platform_by_entity: Mapping[str, str] | None = None,
        version_evidence_by_entity: Mapping[str, Callable[[], str | None]] | None = None,
        prior_backup_sizes_by_entity: Mapping[str, list[int]] | None = None,
        env: Mapping[str, str] | None = None,
        session_factory: Callable[[ProbeTarget], BackupSshSession] | None = None,
        clock: Callable[[], datetime] | None = None,
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

        # B4 — the durable operational-write ledger + RB.1 recovery store. Both
        # are wired by main.py step 6; until then collect() fails closed rather
        # than creating an archive it cannot gate or persist.
        self._ledger = ledger
        self._recovery_paths = recovery_paths
        self._vault_key = vault_key
        self._vault_key_id = vault_key_id
        self._run_id = run_id

        # transport tunables (B11 — shared with the config-SSH env)
        self._strict = _env_bool(self._env, _STRICT_HOST_KEY_ENV, False)
        self._connect_timeout = _env_int(self._env, _CONNECT_TIMEOUT_ENV, 8, 2, 60)
        self._session_factory = session_factory or functools.partial(
            _default_session_factory,
            username=username,
            secret=secret,
            strict=self._strict,
            connect_timeout=self._connect_timeout,
        )
        self._clock = clock or (lambda: datetime.now(timezone.utc))

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
        entity_id = target.entity_id
        precheck = self.precheck(target)              # offline refusals, before any contact

        ledger = self._require_ledger(entity_id)      # fail-closed if unwired (B4)
        self._require_store(entity_id)                # fail-closed before creating an archive

        now = self._clock()

        # B4 / §9.13 — the ledger read is inside the admission-held section
        # (collect() runs inside run_under_admission).
        try:
            last = ledger.last_execution(entity_id=entity_id, command_class=ARTIFACT_CLASS)
        except OperationalLedgerUnreadableError as exc:
            raise RecoveryCollectionError(
                f"{entity_id}: operational_ledger_unreadable — backup blocked fail-closed, "
                f"no command sent (B4 / §9.13(b)): {exc}"
            ) from exc
        if last is not None:
            if last.outcome == "cleanup_failed":
                raise RecoveryCollectionError(
                    f"{entity_id}: endpoint is INELIGIBLE — the last add backup local left an "
                    f"un-deleted archive on the device (CLEANUP_FAILED at "
                    f"{last.executed_at.astimezone(timezone.utc):%Y-%m-%dT%H:%MZ}). An operator "
                    f"must remove the orphaned /var/log/CPbackup/backups archive and clear that "
                    f"ledger entry before backup resumes (correctness rule 3 / AC-3)"
                )
            if (now.astimezone(timezone.utc) - last.executed_at) < DEFAULT_WINDOW:
                raise RecoveryCollectionSkipped(
                    f"{entity_id}: skipped_recent_backup — an add backup local is already "
                    f"recorded within the 24h window; zero device contact "
                    f"(B4 / §7.3 point 6 / AC-6)"
                )

        probe_target = self._probe_target(target)
        session = self._session_factory(probe_target)
        outcome = _DeviceOutcome()
        try:
            self._run_device_sequence(session, target, precheck, outcome)
        finally:
            # §10 (g) — the ledger entry is written iff `add backup local` was
            # actually sent, after any cleanup the sequence attempted. A ledger
            # write failure must never mask the device outcome.
            if outcome.backup_sent and outcome.ledger_outcome:
                try:
                    ledger.record_execution(
                        entity_id=entity_id,
                        command_class=ARTIFACT_CLASS,
                        executed_at=now,
                        outcome=outcome.ledger_outcome,
                        run_id=self._run_id,
                    )
                except Exception as exc:  # noqa: BLE001 — deliberate: outcome wins
                    info(f">>> CP GAIA BACKUP {entity_id}: ledger record failed ({type(exc).__name__})")
            session.close()

        if outcome.result is None:  # unreachable — the sequence raises or sets result
            raise RecoveryCollectionError(f"{entity_id}: internal — device sequence produced no result")
        return outcome.result

    # -- the one-SSH-session device core (step 5) ---------------------------

    def _run_device_sequence(
        self,
        session: BackupSshSession,
        target: RecoveryCollectionTarget,
        precheck: BackupPrecheck,
        outcome: _DeviceOutcome,
    ) -> None:
        entity_id = target.entity_id
        started = time.monotonic()

        # §7.7 / §7.3 point 12 / §9.10 — free-space precondition. No device
        # write has happened yet; any abort here is a clean `failed`.
        usage = self._read_free_space(session)
        if usage is None:
            raise RecoveryCollectionError(
                f"{entity_id}: /var/log free space is UNKNOWN (no parseable figure from any "
                f"frozen form) — backup aborted, no command sent (§7.7 point 8 / §9.10 / AC-1)"
            )
        if usage.free_mb < precheck.required_free_mb:
            raise RecoveryCollectionError(
                f"{entity_id}: /var/log free {usage.free_mb} MB < required {precheck.required_free_mb} MB "
                f"(3x the largest prior backup) — backup aborted, no command sent "
                f"(§7.3 point 12 / AC-1)"
            )
        info(
            f">>> CP GAIA BACKUP {entity_id}: /var/log free {usage.free_mb} MB "
            f">= required {precheck.required_free_mb} MB — proceeding"
        )

        # §7.3 — add backup local (no retry)
        archive_name, sent = self._send_add_backup_local(session)
        outcome.backup_sent = sent
        if not sent:
            raise RecoveryCollectionError(
                f"{entity_id}: add backup local was rejected by the device on every frozen "
                f"wire form; no archive created (§7.3)"
            )
        outcome.ledger_outcome = "failed"           # provisional; upgraded on success
        outcome.archive_name = archive_name
        info(f">>> CP GAIA BACKUP {entity_id}: add backup local sent (archive name redacted)")

        if not archive_name:
            # The command was accepted but we cannot identify the archive we
            # just made — §7.8 point 12 forbids a discovery-based delete, so
            # this is CLEANUP_FAILED and the endpoint becomes ineligible.
            outcome.ledger_outcome = "cleanup_failed"
            raise RecoveryCollectionError(
                f"{entity_id}: add backup local completed but its archive name could not be "
                f"parsed from the command output — CLEANUP_FAILED, endpoint marked ineligible "
                f"(§7.8 point 12 / AC-3 / AC-4)"
            )

        remote_path = f"{_BACKUP_DIR}/{archive_name}"

        # §7.4 — device-reported size (SFTP stat of the known path)
        device_size = session.remote_size(remote_path, _FETCH_TIMEOUT)
        if device_size is None:
            outcome.ledger_outcome = "cleanup_failed"
            raise RecoveryCollectionError(
                f"{entity_id}: add backup local reported success but no archive is present at "
                f"the expected path — cannot verify or clean up; endpoint marked ineligible "
                f"(§7.8 point 12 / AC-3)"
            )

        # §7.4 — fetch straight into memory (no plaintext temp file, B6)
        try:
            plaintext = session.fetch(remote_path, _FETCH_TIMEOUT)
        except Exception as exc:  # noqa: BLE001 — any transport failure is a fetch failure
            raise RecoveryCollectionError(
                f"{entity_id}: backup fetch failed ({type(exc).__name__}); "
                f"{self._cleanup_tail(session, archive_name, outcome)}"
            ) from exc

        verify_error = self._verify(plaintext, device_size)
        if verify_error:
            raise RecoveryCollectionError(
                f"{entity_id}: backup integrity check failed ({verify_error}) — no store write; "
                f"{self._cleanup_tail(session, archive_name, outcome)}"
            )

        # store BEFORE delete (correctness rule 1). Persisted here, inside the
        # admitted SSH session, so a store failure never leaves us with a
        # deleted archive and nothing held.
        duration_ms = int((time.monotonic() - started) * 1000)
        outcome.stored_artifact_id = self._store(target, precheck, plaintext, duration_ms)
        info(
            f">>> CP GAIA BACKUP {entity_id}: stored artifact {outcome.stored_artifact_id} "
            f"({len(plaintext)} plaintext bytes)"
        )

        # §7.8 — delete exactly the archive this run created
        if not self._delete_archive(session, archive_name):
            outcome.ledger_outcome = "cleanup_failed"
            raise RecoveryCollectionError(
                f"{entity_id}: backup stored ({outcome.stored_artifact_id}) but the on-device "
                f"archive deletion could not be confirmed after 1 retry — CLEANUP_FAILED, "
                f"endpoint marked ineligible (§7.8 / AC-3)"
            )

        outcome.ledger_outcome = "completed"
        meta = self._meta(target, precheck)
        meta["stored_artifact_id"] = outcome.stored_artifact_id
        outcome.result = (plaintext, meta)
        info(f">>> CP GAIA BACKUP {entity_id}: complete — on-device archive deleted")

    # -- device-core steps ----------------------------------------------------

    def _read_free_space(self, session: BackupSshSession) -> DiskUsage | None:
        """§7.7 — the frozen forms in order; the first parseable figure wins.
        ``None`` ⇒ UNKNOWN ⇒ §7.3 point 12 aborts."""
        for _attempt in range(_FREE_SPACE_RETRIES + 1):
            for form in _FREE_SPACE_FORMS:
                result = session.run(form, _FREE_SPACE_TIMEOUT)
                if not result.get("success"):
                    continue
                usage = parse_var_log_free(str(result.get("stdout") or ""))
                if usage is not None:
                    return usage
        return None

    def _send_add_backup_local(self, session: BackupSshSession) -> tuple[str | None, bool]:
        """§7.3 — send ``add backup local``. Returns
        ``(archive_name | None, backup_possibly_started)``.

        The Clish form is tried first; the bare form is tried **only** when the
        first was a clean shell/CLI rejection that cannot have created an
        archive (a "command not found" — not a *retry*, §7.3 point 5). Any
        other result (success, timeout, ambiguous error, empty output) means an
        archive may exist: the caller must run cleanup and record the ledger.
        """
        for form in _ADD_BACKUP_FORMS:
            result = session.run(form, _ADD_BACKUP_TIMEOUT)
            stdout = str(result.get("stdout") or "")
            stderr = str(result.get("stderr") or "")
            if result.get("success"):
                return self._parse_archive_name(stdout), True
            blob = f"{stdout}\n{stderr}".lower()
            clean_rejection = (
                not result.get("timeout")
                and result.get("error_class") in ("cli_rejected", "command_error")
                and any(marker in blob for marker in _SHELL_REJECTION_MARKERS)
            )
            if not clean_rejection:
                # timeout / ambiguous / empty output — assume the archive may exist
                return self._parse_archive_name(stdout), True
        # every frozen form was cleanly rejected; nothing was created
        return None, False

    @staticmethod
    def _parse_archive_name(stdout: str) -> str | None:
        """The archive name from ``add backup local``'s **own** output — never
        a listing (§7.8 point 12). Validated against a strict pattern and
        registered as a sensitive value before it can reach a log line."""
        for pattern in (_ARCHIVE_PATH_RE, _ARCHIVE_NAME_RE):
            match = pattern.search(stdout or "")
            if not match:
                continue
            name = match.group(1)
            if _SAFE_ARCHIVE_NAME_RE.match(name) and "/" not in name and ".." not in name:
                register_sensitive_value(name, "[CP_BACKUP_ARCHIVE:REDACTED]")
                return name
        return None

    def _verify(self, plaintext: bytes, device_size: int) -> str | None:
        """Size verify against the device-reported size (§7.4). A digest is
        recorded downstream by ``write_artifact`` from the same bytes, so a
        store write can only ever agree with what was fetched (correctness
        rule 4)."""
        if not plaintext:
            return "fetched archive is empty"
        if device_size <= 0:
            return "device-reported size unavailable (SFTP stat)"
        if len(plaintext) != device_size:
            return f"size mismatch: fetched {len(plaintext)} bytes != device-reported {device_size} bytes"
        return None

    def _store(
        self,
        target: RecoveryCollectionTarget,
        precheck: BackupPrecheck,
        plaintext: bytes,
        duration_ms: int,
    ) -> str:
        from utils.recovery_store import write_artifact  # local import: avoid a store<->collect cycle

        device = build_recovery_device_block(target, self._meta(target, precheck))
        write_result = write_artifact(
            self._recovery_paths,
            vault_key=self._vault_key,
            vault_key_id=self._vault_key_id,
            device=device,
            artifact_class=ARTIFACT_CLASS,
            plaintext=plaintext,
            vendor_native_filename="cp_gaia_backup.tgz",  # generic — the real name is an operational identity
            collected_via="cp_ssh_scp_fetch",
            compression="gzip",
            collection_duration_ms=duration_ms,
            restore_constraints={
                "restores_to_same_version_only": True,
                "restores_to_same_appliance_only": False,
                "requires_superuser_to_apply": True,
            },
        )
        return str(write_result.manifest["artifact_id"])

    def _delete_archive(self, session: BackupSshSession, archive_name: str) -> bool:
        """§7.8 — delete exactly ``archive_name`` and confirm it is gone.
        Never builds a command from an unvalidated token. 1 retry (§7.8
        point 5). Returns ``True`` only when an SFTP stat confirms the path is
        absent."""
        if not _SAFE_ARCHIVE_NAME_RE.match(archive_name or ""):
            return False
        path = f"{_BACKUP_DIR}/{archive_name}"
        # Expert `rm` first — the exact, portable form per the §7.8 sign-off
        # note; the Clish form is a secondary attempt (harmless if absent).
        forms = (
            f"rm -f -- {path}",
            f"clish -c 'delete backup {archive_name}'",
        )
        for _attempt in range(_DELETE_RETRIES + 1):
            for form in forms:
                try:
                    session.run(form, _DELETE_TIMEOUT)
                except Exception:  # noqa: BLE001 — try the next form / confirm by stat
                    continue
            try:
                if session.remote_size(path, _DELETE_TIMEOUT) is None:
                    return True
            except Exception:  # noqa: BLE001 — an unconfirmable delete is a failed delete
                pass
        return False

    def _cleanup_tail(
        self, session: BackupSshSession, archive_name: str, outcome: _DeviceOutcome
    ) -> str:
        """§7.3 point 13 — the on-device archive is deleted after a failure
        too. If the delete will not confirm, the endpoint becomes ineligible
        (ledger outcome ``cleanup_failed``). Returns the sentence tail for the
        raised error so the caller reports plain ``failed`` vs
        ``CLEANUP_FAILED`` accurately."""
        deleted = False
        try:
            deleted = self._delete_archive(session, archive_name)
        except Exception:  # noqa: BLE001 — an unconfirmable delete is a failed delete
            deleted = False
        if deleted:
            return "on-device archive deleted (§7.3 point 13)"
        outcome.ledger_outcome = "cleanup_failed"
        return (
            "on-device archive deletion could not be confirmed — CLEANUP_FAILED, "
            "endpoint marked ineligible (§7.8 / AC-3)"
        )

    # -- helpers ------------------------------------------------------------

    def _meta(self, target: RecoveryCollectionTarget, precheck: BackupPrecheck) -> dict[str, Any]:
        row = target.row or {}
        return {
            "class": ARTIFACT_CLASS,
            "vendor_native_filename": "cp_gaia_backup.tgz",
            "collected_via": "cp_ssh_scp_fetch",
            "compression": "gzip",
            "physical_endpoint": precheck.entity_id,
            "platform": "gaia",
            "software_version": precheck.software_version,   # real R-version — precheck refused otherwise (B8/AC-10)
            "ha_role": str(row.get("ha_role") or "unknown"),
        }

    def _probe_target(self, target: RecoveryCollectionTarget) -> ProbeTarget:
        row = target.row or {}
        management_ip = str(row.get("management_ip") or row.get("device_ip") or "").strip()
        if not management_ip:
            raise RecoveryCollectionError(
                f"{target.entity_id}: management_ip_unavailable (no reachable address in the "
                f"unified.json row) — no device contact"
            )
        return ProbeTarget(
            role="recovery_backup",
            device=str(row.get("device") or target.entity_id),
            management_ip=management_ip,
            object_type=str(row.get("object_type") or "gateway"),
            cma=row.get("cma"),
        )

    def _require_ledger(self, entity_id: str) -> RecoveryOperationalLedger:
        if self._ledger is None:
            raise RecoveryCollectionError(
                f"{entity_id}: the CP backup operational-write ledger is not configured — "
                f"refusing to proceed (fail-closed, B4; main.py step-6 wiring owed)"
            )
        return self._ledger

    def _require_store(self, entity_id: str) -> None:
        if self._recovery_paths is None or self._vault_key is None or not self._vault_key_id:
            raise RecoveryCollectionError(
                f"{entity_id}: the RB.1 recovery store is not bound to the CP backup collector "
                f"— refusing to create an archive it cannot persist (fail-closed; main.py "
                f"step-6 wiring owed)"
            )
