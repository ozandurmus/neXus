import builtins

import main
from utils.runtime_auth import RuntimeAuth
import pytest

pytestmark = pytest.mark.runtime_platform

# DEV.2.1: force the interactive prompt path (stdin is not a TTY under pytest)
# and a clean env for the prompt-based tests.
_RUNTIME_VARS = (
    "SECURITYEXPERT_PRINCIPAL",
    "SECURITYEXPERT_SECRET",
    "SECURITYEXPERT_CP_MDS_ENDPOINT",
    "SECURITYEXPERT_PANORAMA_ENDPOINT",
)


def _force_interactive(monkeypatch):
    monkeypatch.setattr(main.sys.stdin, "isatty", lambda: True)
    for name in _RUNTIME_VARS:
        monkeypatch.delenv(name, raising=False)
        monkeypatch.delenv(f"{name}_FILE", raising=False)


def test_runtime_auth_repr_never_exposes_material():
    auth = RuntimeAuth(principal="synthetic-principal", secret="synthetic-secret")
    rendered = repr(auth)
    assert rendered == "RuntimeAuth(<protected>)"
    assert "synthetic-principal" not in rendered
    assert "synthetic-secret" not in rendered


def test_runtime_config_uses_auth_as_single_source_of_truth(monkeypatch):
    _force_interactive(monkeypatch)
    answers = iter(["192.0.2.10", "synthetic-principal"])
    monkeypatch.setattr(builtins, "input", lambda _prompt: next(answers))
    monkeypatch.setattr(main.getpass, "getpass", lambda _prompt: "synthetic-secret")

    cfg = main._build_runtime_config(require_cp=True, require_panorama=False)

    assert cfg.auth.principal == "synthetic-principal"
    assert cfg.auth.secret == "synthetic-secret"
    # DEV.0.5B removes the legacy username/password compatibility surface.
    assert not hasattr(cfg, "username")
    assert not hasattr(cfg, "password")
    assert "synthetic-principal" not in repr(cfg)
    assert "synthetic-secret" not in repr(cfg)

    cfg.clear_credentials()
    assert cfg.auth.principal is None
    assert cfg.auth.secret is None
    assert not hasattr(cfg, "username")
    assert not hasattr(cfg, "password")


def test_runtime_config_prompts_use_general_auth_vocabulary(monkeypatch):
    _force_interactive(monkeypatch)
    prompts = []
    answers = iter(["198.51.100.20", "synthetic-principal"])

    def fake_input(prompt):
        prompts.append(prompt)
        return next(answers)

    secret_prompts = []
    monkeypatch.setattr(builtins, "input", fake_input)
    monkeypatch.setattr(main.getpass, "getpass", lambda prompt: secret_prompts.append(prompt) or "synthetic-secret")

    cfg = main._build_runtime_config(require_cp=False, require_panorama=True)
    assert "Login: " in prompts
    assert secret_prompts == ["Authentication secret: "]
    cfg.clear_credentials()
