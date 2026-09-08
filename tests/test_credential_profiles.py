"""`credential_profiles` (P1) -- credential-profile reference model.

`docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` section 6: the Device
Registry holds only a named credential profile reference, resolved in the
engine process to the existing DEV.2.1/DEV.2.2 non-interactive credential
sources. These tests prove: a profile name resolves correctly through that
exact mechanism (AC-3), and that no secret material ever appears on the
profile record itself (AC-3).
"""
from __future__ import annotations

import pytest

from utils import logger as logger_module
from utils.credential_profiles import (
    CredentialProfile,
    CredentialProfileError,
    ResolvedCredential,
    resolve_credential_profile,
)

_ENV_VARS = (
    "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL",
    "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL_FILE",
    "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET",
    "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET_FILE",
)


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    for var in _ENV_VARS:
        monkeypatch.delenv(var, raising=False)


# ---------------------------------------------------------------------------
# Profile record carries only a name -- structurally, no secret field exists
# ---------------------------------------------------------------------------


def test_credential_profile_record_field_set_is_closed_no_secret_field():
    fields = set(CredentialProfile.__dataclass_fields__)
    assert fields == {"name"}


def test_credential_profile_rejects_unsupported_kwarg_structurally():
    with pytest.raises(TypeError):
        CredentialProfile(name="cp-prod-1", secret="oops")  # type: ignore[call-arg]


@pytest.mark.parametrize("bad_name", ["has spaces", "has/slash", "x" * 65, ""])
def test_credential_profile_rejects_malformed_names(bad_name):
    with pytest.raises(CredentialProfileError):
        CredentialProfile(bad_name)


def test_credential_profile_var_names_derived_not_stored():
    profile = CredentialProfile("cp-prod.1")
    assert profile.principal_var == "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL"
    assert profile.secret_var == "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET"


# ---------------------------------------------------------------------------
# Resolution reuses DEV.2.1/DEV.2.2 exactly: <VAR>_FILE > <VAR>
# ---------------------------------------------------------------------------


def test_resolve_via_plain_env_vars(monkeypatch):
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL", "svc-account")
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET", "s3cret-value")

    resolved = resolve_credential_profile("cp-prod-1")
    assert resolved.principal == "svc-account"
    assert resolved.secret == "s3cret-value"


def test_resolve_via_file_mount_takes_precedence_over_plain_var(monkeypatch, tmp_path):
    principal_file = tmp_path / "principal"
    principal_file.write_text("  file-account  \n")
    secret_file = tmp_path / "secret"
    secret_file.write_text("file-secret\n")

    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL", "env-account")
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL_FILE", str(principal_file))
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET", "env-secret")
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET_FILE", str(secret_file))

    resolved = resolve_credential_profile("cp-prod-1")
    assert resolved.principal == "file-account"
    assert resolved.secret == "file-secret"


def test_unreadable_file_mount_fails_closed(monkeypatch, tmp_path):
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL_FILE", str(tmp_path / "missing"))
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET", "env-secret")

    with pytest.raises(CredentialProfileError):
        resolve_credential_profile("cp-prod-1")


def test_missing_variables_named_in_error(monkeypatch):
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL", "svc-account")
    # secret left unset entirely
    with pytest.raises(CredentialProfileError) as excinfo:
        resolve_credential_profile("cp-prod-1")
    assert "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET" in str(excinfo.value)
    assert "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL" not in str(excinfo.value)


def test_fully_unresolved_profile_names_both_variables(monkeypatch):
    with pytest.raises(CredentialProfileError) as excinfo:
        resolve_credential_profile("cp-prod-1")
    message = str(excinfo.value)
    assert "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL" in message
    assert "SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET" in message


def test_malformed_profile_name_rejected_before_any_lookup(monkeypatch):
    calls = []
    from utils import runtime_config_source

    original = runtime_config_source.resolve_value
    monkeypatch.setattr(
        runtime_config_source, "resolve_value", lambda *a, **k: (calls.append(a), original(*a, **k))[1]
    )
    with pytest.raises(CredentialProfileError):
        resolve_credential_profile("has spaces")
    assert calls == []


# ---------------------------------------------------------------------------
# No secret material ever appears in a profile record; resolved credential
# is never logged or persisted by this module
# ---------------------------------------------------------------------------


def test_resolved_credential_repr_and_str_never_leak_secret(monkeypatch):
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL", "svc-account")
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET", "s3cret-value")

    resolved = resolve_credential_profile("cp-prod-1")
    assert "s3cret-value" not in repr(resolved)
    assert "s3cret-value" not in str(resolved)
    assert "svc-account" not in repr(resolved)


def test_resolve_registers_credential_for_log_redaction(monkeypatch):
    logger_module._SENSITIVE_VALUES.clear()
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_PRINCIPAL", "svc-account")
    monkeypatch.setenv("SECURITYEXPERT_CREDENTIAL_PROFILE_CP_PROD_1_SECRET", "s3cret-value")

    resolve_credential_profile("cp-prod-1")
    assert logger_module._redact("leaked s3cret-value here") == "leaked [AUTH_SECRET:REDACTED] here"
    assert "svc-account" not in logger_module._redact("leaked svc-account here")


def test_credential_profile_module_has_no_persistence_or_logging_side_effects():
    """Structural: this module never writes to disk and never calls the
    logger's print/file-write path -- only the redaction registry."""
    from pathlib import Path

    source = Path("utils/credential_profiles.py").read_text(encoding="utf-8")
    for forbidden in ("open(", "Path(", ".write_text(", "json.dump", "logging."):
        assert forbidden not in source


def test_resolved_credential_is_a_distinct_type_from_credential_profile():
    """The reference (name) and the resolved secret pair are never the same
    object -- the registry can only ever hold the former."""
    assert ResolvedCredential is not CredentialProfile
    assert "principal" not in CredentialProfile.__dataclass_fields__
    assert "secret" not in CredentialProfile.__dataclass_fields__
