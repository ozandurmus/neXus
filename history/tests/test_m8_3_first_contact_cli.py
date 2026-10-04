"""`M8.3` -- CLI wiring for the read-only first-contact identity-evidence producer.

Covers the `--identity-first-contact DEVICE_ID` parser surface, its mutual-
exclusion with every other mode, and the `application/workflows/
first_contact.py` orchestration (credential resolution deferred to a
callback, `Config.clear_credentials()` always called). The actual producer
sequence is `utils/first_contact_producer.py`'s own contract, proven in
`tests/test_m8_3_first_contact_producer.py`; `run_first_contact_producer` is
stubbed out here.
"""
from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest

from application.cli import build_parser, validate_modes
from application.context import ApplicationContext
from application.workflows import first_contact as first_contact_wf

pytestmark = pytest.mark.configuration


def _parse(argv):
    parser = build_parser()
    args = parser.parse_args(argv)
    validate_modes(args, parser)
    return args


def _parse_error(argv):
    parser = build_parser()
    args = parser.parse_args(argv)
    with pytest.raises(SystemExit):
        validate_modes(args, parser)


class TestCliSurface:
    def test_mode_exists(self):
        args = _parse(["--identity-first-contact", "dev-123"])
        assert args.identity_first_contact == "dev-123"

    def test_absent_by_default(self):
        args = _parse([])
        assert args.identity_first_contact is None

    @pytest.mark.parametrize(
        "extra",
        [
            ["--cp-config-collect"],
            ["--cp-config-probe"],
            ["--render-only"],
            ["--only", "cp"],
            ["--registry-list"],
            ["--registry-enroll", "--registry-endpoint", "192.0.2.1"],
            ["--console"],
            ["--scheduler-once"],
            ["--repository-privacy-check"],
            ["--persistent-secret-material-check"],
            ["--cp-ha-preflight-check", "--cp-preflight-targets", "fw-1"],
        ],
    )
    def test_cannot_combine_with_other_modes(self, extra):
        _parse_error(["--identity-first-contact", "dev-123", *extra])

    def test_not_console_submittable_by_construction(self):
        """No console job type ever carries this mode's flag -- the Operator
        Console submits typed intent against `console/registry.py`'s closed
        job vocabulary, which this CLI-only mode is never registered in."""
        from console import registry as console_registry

        assert "identity_first_contact" not in console_registry.JOB_REGISTRY
        assert "identity-first-contact" not in console_registry.JOB_REGISTRY


class _FakeRuntimePaths:
    def __init__(self, root: Path):
        self.data_root = root / "data"
        self.output_root = root
        self.repository_root = root
        self.runtime_root = root
        self.logs_root = root / "logs"


class _FakeConfig:
    def __init__(self, principal, secret):
        self.auth = SimpleNamespace(principal=principal, secret=secret)
        self.cleared = False

    def clear_credentials(self):
        self.cleared = True


class TestWorkflowOrchestration:
    def _ctx(self, tmp_path, monkeypatch, *, device_id="dev-123"):
        args = _parse(["--identity-first-contact", device_id])
        ctx = ApplicationContext(args=args, parser=build_parser(), provenance="manual")
        ctx.runtime_paths = _FakeRuntimePaths(tmp_path)
        # Bootstrap prerequisites: a prior CP/VSX inventory checkpoint already
        # exists in this RuntimeRoot (application.services._require_bootstrap).
        for name in ("cp_telemetry.json", "cp.json", "vsx.json"):
            (tmp_path / name).write_text("{}", encoding="utf-8")
        for name in (
            "SECURITYEXPERT_CP_CONFIG_SSH_USERNAME",
            "SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD",
        ):
            monkeypatch.delenv(name, raising=False)
        return ctx

    def test_producer_receives_device_id_and_runtime_paths(self, tmp_path, monkeypatch):
        ctx = self._ctx(tmp_path, monkeypatch)
        captured = {}

        def _fake_runtime_config(ctx_arg):
            def _inner(*, require_cp, require_panorama):
                assert require_cp is True
                assert require_panorama is False
                return _FakeConfig("principal-x", "secret-y")

            return _inner

        monkeypatch.setattr(first_contact_wf, "make_runtime_config", _fake_runtime_config)

        def _fake_producer(*, device_id, data_root, output_root, resolve_credentials):
            captured["device_id"] = device_id
            captured["data_root"] = data_root
            captured["output_root"] = output_root
            captured["credentials"] = resolve_credentials()
            return SimpleNamespace(status="NEW", device_id=device_id, reason=None, relationship_id="rel-1")

        import utils.first_contact_producer as producer_module

        monkeypatch.setattr(producer_module, "run_first_contact_producer", _fake_producer)

        first_contact_wf.identity_first_contact(ctx)

        assert captured["device_id"] == "dev-123"
        assert captured["data_root"] == ctx.runtime_paths.data_root
        assert captured["output_root"] == ctx.runtime_paths.output_root
        assert captured["credentials"] == ("principal-x", "secret-y")

    def test_config_credentials_are_always_cleared(self, tmp_path, monkeypatch):
        ctx = self._ctx(tmp_path, monkeypatch)
        cfg_holder = {}

        def _fake_runtime_config(ctx_arg):
            def _inner(*, require_cp, require_panorama):
                cfg = _FakeConfig("principal-x", "secret-y")
                cfg_holder["cfg"] = cfg
                return cfg

            return _inner

        monkeypatch.setattr(first_contact_wf, "make_runtime_config", _fake_runtime_config)

        def _fake_producer(*, device_id, data_root, output_root, resolve_credentials):
            resolve_credentials()
            return SimpleNamespace(status="ENDPOINT_NOT_TRUSTED", device_id=device_id, reason="x", relationship_id=None)

        import utils.first_contact_producer as producer_module

        monkeypatch.setattr(producer_module, "run_first_contact_producer", _fake_producer)

        first_contact_wf.identity_first_contact(ctx)

        assert cfg_holder["cfg"].cleared is True

    def test_credentials_env_override_takes_precedence(self, tmp_path, monkeypatch):
        ctx = self._ctx(tmp_path, monkeypatch)
        monkeypatch.setenv("SECURITYEXPERT_CP_CONFIG_SSH_USERNAME", "env-user")
        monkeypatch.setenv("SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD", "env-secret")

        def _fake_runtime_config(ctx_arg):
            def _inner(*, require_cp, require_panorama):
                return _FakeConfig("principal-x", "secret-y")

            return _inner

        monkeypatch.setattr(first_contact_wf, "make_runtime_config", _fake_runtime_config)

        captured = {}

        def _fake_producer(*, device_id, data_root, output_root, resolve_credentials):
            captured["credentials"] = resolve_credentials()
            return SimpleNamespace(status="NEW", device_id=device_id, reason=None, relationship_id="rel-1")

        import utils.first_contact_producer as producer_module

        monkeypatch.setattr(producer_module, "run_first_contact_producer", _fake_producer)

        first_contact_wf.identity_first_contact(ctx)

        assert captured["credentials"] == ("env-user", "env-secret")

    def test_no_credential_resolution_when_producer_never_calls_back(self, tmp_path, monkeypatch, capsys):
        """Proves the CLI layer never resolves credentials eagerly -- it is
        entirely the producer's decision, made only after its own trust
        lookup succeeds (frozen contract Section 4, Section 8 AC2)."""
        ctx = self._ctx(tmp_path, monkeypatch)
        runtime_config_calls = []

        def _fake_runtime_config(ctx_arg):
            def _inner(*, require_cp, require_panorama):
                runtime_config_calls.append(True)
                return _FakeConfig("principal-x", "secret-y")

            return _inner

        monkeypatch.setattr(first_contact_wf, "make_runtime_config", _fake_runtime_config)

        def _fake_producer(*, device_id, data_root, output_root, resolve_credentials):
            # Refused before ever invoking resolve_credentials.
            return SimpleNamespace(status="endpoint_not_trusted", device_id=device_id, reason="endpoint_not_trusted", relationship_id=None)

        import utils.first_contact_producer as producer_module

        monkeypatch.setattr(producer_module, "run_first_contact_producer", _fake_producer)

        first_contact_wf.identity_first_contact(ctx)

        assert runtime_config_calls == []
        out = capsys.readouterr().out
        assert "principal-x" not in out
        assert "secret-y" not in out
