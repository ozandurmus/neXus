import builtins

import main

# DEV.2.1: _build_runtime_config now prompts only when stdin is a TTY and reads
# SECURITYEXPERT_* env vars first. Force the interactive path and a clean env for
# the prompt-based tests below.
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


def test_prompt_management_endpoint_rejects_blank(monkeypatch, capsys):
    answers = iter(["   ", "cp-management.example.invalid"])
    monkeypatch.setattr(builtins, "input", lambda _prompt: next(answers))

    assert main._prompt_management_endpoint("Check Point Management") == "cp-management.example.invalid"
    assert "is required" in capsys.readouterr().out


def test_build_runtime_config_cp_only(monkeypatch):
    _force_interactive(monkeypatch)
    answers = iter(["192.0.2.10", "synthetic-user"])
    monkeypatch.setattr(builtins, "input", lambda _prompt: next(answers))
    monkeypatch.setattr(main.getpass, "getpass", lambda _prompt: "synthetic-password")

    cfg = main._build_runtime_config(require_cp=True, require_panorama=False)

    assert cfg.mds_ip == "192.0.2.10"
    assert cfg.panorama_ip is None
    assert cfg.auth.principal == "synthetic-user"
    assert cfg.auth.secret == "synthetic-password"
    cfg.clear_credentials()


def test_build_runtime_config_panorama_only(monkeypatch):
    _force_interactive(monkeypatch)
    answers = iter(["198.51.100.20", "synthetic-user"])
    monkeypatch.setattr(builtins, "input", lambda _prompt: next(answers))
    monkeypatch.setattr(main.getpass, "getpass", lambda _prompt: "synthetic-password")

    cfg = main._build_runtime_config(require_cp=False, require_panorama=True)

    assert cfg.mds_ip is None
    assert cfg.panorama_ip == "198.51.100.20"
    cfg.clear_credentials()
