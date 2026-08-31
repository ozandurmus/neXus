"""RB.3b steps 3–4 — the offline parts of the CP Gaia backup collector.

Contract: ``docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`` (B7, B8,
B10, B11), ``docs/design/BACKUP_RECOVERY_CONTRACTS.md`` §3 rule 5, §7.3
points 3/8/12, §7.7. Every test here is device-free.

Step 5 (``add backup local`` / SCP fetch / digest verify / deletion) and the
``main.py`` step-6 wiring / C6 test are separate.
"""
from __future__ import annotations

import pytest

from checkpoint.checkpoint_recovery_collector import (
    DEFAULT_MIN_FREE_MB,
    HARD_FLOOR_MB,
    BackupPrecheck,
    CheckpointGaiaBackupCollector,
    CpBackupCredentialsUnavailable,
    CpBackupEndpointRefused,
    allowed_backup_entities,
    is_entity_allowed,
    is_vsx_virtual_system,
    min_free_floor_mb,
    parse_var_log_free,
    required_free_mb,
    resolve_backup_credentials,
    resolve_software_version,
)
from utils.recovery_collect import RecoveryCollectionError, RecoveryCollectionTarget

pytestmark = pytest.mark.runtime_platform

_CREDS = {
    "SECURITYEXPERT_CP_BACKUP_SSH_USERNAME": "svc-backup",
    "SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD": "s3cr3t",
}


def _target(entity_id="gw-1", **row):
    return RecoveryCollectionTarget(entity_id=entity_id, vendor="checkpoint", row=row)


# ---------------------------------------------------------------------------
# B10 — pilot allowlist (AC-7)
# ---------------------------------------------------------------------------

def test_allowlist_unset_means_nothing_allowed():
    assert allowed_backup_entities({}) == frozenset()
    assert is_entity_allowed("gw-1", {}) is False


def test_allowlist_empty_string_means_nothing_allowed():
    assert is_entity_allowed("gw-1", {"SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES": "  , ,"}) is False


def test_allowlist_parses_and_trims():
    env = {"SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES": " gw-1 , gw-2,gw-3 "}
    assert allowed_backup_entities(env) == frozenset({"gw-1", "gw-2", "gw-3"})
    assert is_entity_allowed("gw-2", env) is True
    assert is_entity_allowed("gw-9", env) is False


# ---------------------------------------------------------------------------
# B7 — VSX virtual system is never a backup target (AC-8)
# ---------------------------------------------------------------------------

def test_vsx_entity_detected():
    assert is_vsx_virtual_system("vsx-gw-01__vsid_3") is True
    assert is_vsx_virtual_system("cp-core-01") is False


def test_precheck_refuses_vsx_before_contact():
    c = CheckpointGaiaBackupCollector(env={**_CREDS, "SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES": "vsx-gw-01__vsid_3"})
    with pytest.raises(CpBackupEndpointRefused) as exc:
        c.precheck(_target("vsx-gw-01__vsid_3"))
    assert "§7.3 point 3" in str(exc.value)


def test_reject_before_admission_returns_message_for_vsx():
    c = CheckpointGaiaBackupCollector(env=_CREDS)
    assert c.reject_before_admission(_target("x__vsid_1")) is not None
    assert c.reject_before_admission(_target("plain")) is None


# ---------------------------------------------------------------------------
# B11 / D4 — distinct backup credential, fail-closed, no fallback (AC-11)
# ---------------------------------------------------------------------------

def test_credentials_missing_raises():
    with pytest.raises(CpBackupCredentialsUnavailable) as exc:
        resolve_backup_credentials({})
    assert "cp_backup_credentials_unavailable" in str(exc.value)


def test_credentials_never_fall_back_to_config_ssh():
    env = {
        "SECURITYEXPERT_CP_CONFIG_SSH_USERNAME": "svc-config",
        "SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD": "config-pw",
    }
    with pytest.raises(CpBackupCredentialsUnavailable):
        resolve_backup_credentials(env)


def test_credentials_password_env():
    assert resolve_backup_credentials(_CREDS) == ("svc-backup", "s3cr3t")


def test_credentials_password_file_wins(tmp_path):
    pw = tmp_path / "secret"
    pw.write_text("from-file\n", encoding="utf-8")
    env = {
        "SECURITYEXPERT_CP_BACKUP_SSH_USERNAME": "svc-backup",
        "SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD": "from-env",
        "SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD_FILE": str(pw),
    }
    assert resolve_backup_credentials(env) == ("svc-backup", "from-file")


def test_credentials_password_file_unreadable_raises(tmp_path):
    env = {
        "SECURITYEXPERT_CP_BACKUP_SSH_USERNAME": "svc-backup",
        "SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD_FILE": str(tmp_path / "nope"),
    }
    with pytest.raises(CpBackupCredentialsUnavailable):
        resolve_backup_credentials(env)


def test_constructor_fails_closed_without_credentials():
    with pytest.raises(CpBackupCredentialsUnavailable):
        CheckpointGaiaBackupCollector(env={})


# ---------------------------------------------------------------------------
# AC-9 — Spark / Gaia Embedded UNSUPPORTED, zero commands
# ---------------------------------------------------------------------------

def test_platform_gate_marks_gaia_embedded_unsupported():
    c = CheckpointGaiaBackupCollector(env=_CREDS, platform_by_entity={"gw-1": "gaia_embedded"})
    assert c.classify_target(_target("gw-1")) == "unsupported"
    assert c.classify_target(_target("gw-2")) == "supported"


def test_precheck_refuses_unsupported_platform():
    c = CheckpointGaiaBackupCollector(
        env={**_CREDS, "SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES": "gw-1"},
        platform_by_entity={"gw-1": "gaia_embedded"},
    )
    with pytest.raises(CpBackupEndpointRefused) as exc:
        c.precheck(_target("gw-1"))
    assert "UNSUPPORTED" in str(exc.value)


# ---------------------------------------------------------------------------
# B8 / §3 rule 5 — software_version resolution (AC-10)
# ---------------------------------------------------------------------------

def test_version_from_row_key():
    assert resolve_software_version({"sw_version": "R81.10"}) == "R81.10"
    assert resolve_software_version({"version": "Gaia R81.20 build 993"}) == "R81.20"


def test_version_from_injected_evidence():
    assert resolve_software_version({}, version_evidence=lambda: "This is Check Point's ... R81.10 ...") == "R81.10"


def test_version_unresolvable_returns_none():
    assert resolve_software_version({"sw_version": ""}) is None
    assert resolve_software_version({}, version_evidence=lambda: None) is None
    assert resolve_software_version({}, version_evidence=lambda: "no version token here") is None


def test_precheck_refuses_when_version_unresolvable():
    c = CheckpointGaiaBackupCollector(env={**_CREDS, "SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES": "gw-1"})
    with pytest.raises(CpBackupEndpointRefused) as exc:
        c.precheck(_target("gw-1"))  # empty row, no evidence
    assert "§3 rule 5" in str(exc.value)


def test_precheck_success_returns_prechecked_values():
    c = CheckpointGaiaBackupCollector(
        env={**_CREDS, "SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES": "gw-1"},
        platform_by_entity={"gw-1": "gaia"},
    )
    out = c.precheck(_target("gw-1", sw_version="R81.10"))
    assert isinstance(out, BackupPrecheck)
    assert out.software_version == "R81.10"
    assert out.required_free_mb == DEFAULT_MIN_FREE_MB


def test_collect_runs_gate_then_defers_device_step():
    c = CheckpointGaiaBackupCollector(env={**_CREDS, "SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES": "gw-1"})
    # gate refusal still fires from collect()
    with pytest.raises(CpBackupEndpointRefused):
        c.collect(_target("gw-1"))  # no version
    # gate passes -> device step not implemented yet
    with pytest.raises(RecoveryCollectionError) as exc:
        c.collect(_target("gw-1", sw_version="R81.10"))
    assert "step 5" in str(exc.value)


# ---------------------------------------------------------------------------
# §7.7 — /var/log free-space parser
# ---------------------------------------------------------------------------

_DF_P = """Filesystem          1024-blocks     Used Available Capacity Mounted on
/dev/mapper/vg-lv_current  20961280  3512096  16384512      18% /
/dev/mapper/vg-lv_log      52403200  1048576  48708000       3% /var/log
tmpfs                       8200000        0   8200000       0% /dev/shm
"""


def test_parse_df_p_selects_var_log_mount():
    usage = parse_var_log_free(_DF_P)
    assert usage is not None
    assert usage.mount == "/var/log"
    assert usage.free_mb == 48708000 // 1024
    assert usage.total_mb == 52403200 // 1024


def test_parse_df_p_falls_back_to_root_when_no_var_log():
    df = """Filesystem     1024-blocks    Used Available Capacity Mounted on
/dev/sda1         20961280 3512096  16384512      18% /
"""
    usage = parse_var_log_free(df)
    assert usage is not None and usage.mount == "/"


def test_parse_unparseable_returns_none():
    assert parse_var_log_free("") is None
    assert parse_var_log_free("garbage output, device said no") is None
    assert parse_var_log_free("Filesystem blah blah") is None


def test_parse_show_diskspace_best_effort():
    out = "/var/log   50GB total   47GB free\n/   20GB total   16GB free\n"
    usage = parse_var_log_free(out)
    assert usage is not None and usage.mount == "/var/log"
    assert usage.free_mb == 47 * 1024


# ---------------------------------------------------------------------------
# §7.7 — 3× threshold arithmetic
# ---------------------------------------------------------------------------

def test_min_free_floor_default_and_hard_floor():
    assert min_free_floor_mb({}) == DEFAULT_MIN_FREE_MB
    assert min_free_floor_mb({"SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB": "500"}) == HARD_FLOOR_MB
    assert min_free_floor_mb({"SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB": "8000"}) == 8000
    assert min_free_floor_mb({"SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB": "bad"}) == DEFAULT_MIN_FREE_MB


def test_required_free_uses_floor_with_no_prior():
    assert required_free_mb([], {}) == DEFAULT_MIN_FREE_MB
    assert required_free_mb([0, 0], {}) == DEFAULT_MIN_FREE_MB


def test_required_free_is_three_times_largest_prior():
    # 200 MiB largest prior -> 600 MiB required
    assert required_free_mb([50 * 1024 * 1024, 200 * 1024 * 1024, 120 * 1024 * 1024], {}) == 600
