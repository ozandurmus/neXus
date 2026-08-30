"""DEV.2.1 — non-interactive runtime configuration.

Covers the pure resolver (utils/runtime_config_source.resolve_value) and the
rewritten main._build_runtime_config: env / secret-file sourcing with an
interactive fallback only when stdin is a TTY, and a fail-closed
RuntimeConfigError naming every missing variable otherwise.
"""
import builtins

import pytest

import main
from utils.runtime_config_source import RuntimeConfigError, resolve_value

pytestmark = pytest.mark.runtime_platform

PRINCIPAL = "SECURITYEXPERT_PRINCIPAL"
SECRET = "SECURITYEXPERT_SECRET"
CP = "SECURITYEXPERT_CP_MDS_ENDPOINT"
PAN = "SECURITYEXPERT_PANORAMA_ENDPOINT"
ALL_VARS = (PRINCIPAL, SECRET, CP, PAN)


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    for name in ALL_VARS:
        monkeypatch.delenv(name, raising=False)
        monkeypatch.delenv(f"{name}_FILE", raising=False)


def _non_interactive(monkeypatch):
    monkeypatch.setattr(main.sys.stdin, "isatty", lambda: False)


def _forbid_prompts(monkeypatch):
    monkeypatch.setattr(builtins, "input", lambda _p: pytest.fail("input() called"))
    monkeypatch.setattr(main.getpass, "getpass", lambda _p: pytest.fail("getpass() called"))


# --- resolve_value ---------------------------------------------------------

def test_resolve_value_none_when_unset():
    assert resolve_value(PRINCIPAL, environ={}) is None


def test_resolve_value_reads_plain_env(monkeypatch):
    assert resolve_value(PRINCIPAL, environ={PRINCIPAL: "svc-account"}) == "svc-account"


def test_resolve_value_reads_and_strips_file(tmp_path):
    f = tmp_path / "secret"
    f.write_text("s3cr3t-value\n\n", encoding="utf-8")
    assert resolve_value(SECRET, environ={f"{SECRET}_FILE": str(f)}) == "s3cr3t-value"


def test_resolve_value_file_beats_plain_var(tmp_path):
    f = tmp_path / "endpoint"
    f.write_text("mds-from-file.example.invalid\n", encoding="utf-8")
    env = {CP: "mds-from-env.example.invalid", f"{CP}_FILE": str(f)}
    assert resolve_value(CP, environ=env) == "mds-from-file.example.invalid"


def test_resolve_value_missing_file_fails_closed(tmp_path):
    env = {f"{SECRET}_FILE": str(tmp_path / "does-not-exist")}
    with pytest.raises(RuntimeConfigError) as excinfo:
        resolve_value(SECRET, environ=env)
    assert f"{SECRET}_FILE" in str(excinfo.value)


def test_resolve_value_empty_file_fails_closed(tmp_path):
    f = tmp_path / "empty"
    f.write_text("   \n", encoding="utf-8")
    with pytest.raises(RuntimeConfigError):
        resolve_value(SECRET, environ={f"{SECRET}_FILE": str(f)})


# --- _build_runtime_config: non-interactive -------------------------------

def test_build_runtime_config_from_env_no_prompts(monkeypatch):
    _non_interactive(monkeypatch)
    _forbid_prompts(monkeypatch)
    monkeypatch.setenv(PRINCIPAL, "svc-account")
    monkeypatch.setenv(SECRET, "svc-secret")
    monkeypatch.setenv(CP, "mds.example.invalid")
    monkeypatch.setenv(PAN, "panorama.example.invalid")

    cfg = main._build_runtime_config(require_cp=True, require_panorama=True)

    assert cfg.auth.principal == "svc-account"
    assert cfg.auth.secret == "svc-secret"
    assert cfg.mds_ip == "mds.example.invalid"
    assert cfg.panorama_ip == "panorama.example.invalid"
    cfg.clear_credentials()


def test_build_runtime_config_secret_file(monkeypatch, tmp_path):
    _non_interactive(monkeypatch)
    _forbid_prompts(monkeypatch)
    secret_file = tmp_path / "secret"
    secret_file.write_text("file-secret\n", encoding="utf-8")
    monkeypatch.setenv(PRINCIPAL, "svc-account")
    monkeypatch.setenv(f"{SECRET}_FILE", str(secret_file))
    monkeypatch.setenv(CP, "mds.example.invalid")

    cfg = main._build_runtime_config(require_cp=True, require_panorama=False)

    assert cfg.auth.secret == "file-secret"
    assert cfg.panorama_ip is None
    cfg.clear_credentials()


def test_build_runtime_config_missing_required_lists_all(monkeypatch):
    _non_interactive(monkeypatch)
    _forbid_prompts(monkeypatch)
    monkeypatch.setenv(CP, "mds.example.invalid")  # only the endpoint is set

    with pytest.raises(RuntimeConfigError) as excinfo:
        main._build_runtime_config(require_cp=True, require_panorama=False)

    message = str(excinfo.value)
    assert PRINCIPAL in message
    assert SECRET in message
    assert f"{SECRET}_FILE" in message


def test_build_runtime_config_endpoint_not_required_for_mode(monkeypatch):
    _non_interactive(monkeypatch)
    _forbid_prompts(monkeypatch)
    monkeypatch.setenv(PRINCIPAL, "svc-account")
    monkeypatch.setenv(SECRET, "svc-secret")
    # No CP / PAN endpoint set, but the mode requires neither.

    cfg = main._build_runtime_config(require_cp=False, require_panorama=False)

    assert cfg.mds_ip is None
    assert cfg.panorama_ip is None
    cfg.clear_credentials()


# --- _build_runtime_config: interactive path still works ------------------

def test_build_runtime_config_interactive_still_prompts(monkeypatch):
    monkeypatch.setattr(main.sys.stdin, "isatty", lambda: True)
    answers = iter(["mds.example.invalid", "svc-account"])
    monkeypatch.setattr(builtins, "input", lambda _p: next(answers))
    monkeypatch.setattr(main.getpass, "getpass", lambda _p: "typed-secret")

    cfg = main._build_runtime_config(require_cp=True, require_panorama=False)

    assert cfg.mds_ip == "mds.example.invalid"
    assert cfg.auth.principal == "svc-account"
    assert cfg.auth.secret == "typed-secret"
    cfg.clear_credentials()


# --- redaction registration regardless of source ------------------------

def test_secret_and_principal_registered_for_redaction(monkeypatch):
    _non_interactive(monkeypatch)
    _forbid_prompts(monkeypatch)
    registered = []
    monkeypatch.setattr(main, "register_sensitive_value", lambda value, replacement="[REDACTED]": registered.append(value))
    monkeypatch.setenv(PRINCIPAL, "svc-account")
    monkeypatch.setenv(SECRET, "svc-secret")
    monkeypatch.setenv(CP, "mds.example.invalid")

    cfg = main._build_runtime_config(require_cp=True, require_panorama=False)

    assert "svc-account" in registered
    assert "svc-secret" in registered
    cfg.clear_credentials()
