"""OP.0b S8 — PO override: CP HA preflight development trust policy.

PO decision (2026-09-03, `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`
"CP SSH trust — PO override, development compatibility mode"): mandatory
strict SSH host-key verification for `checkpoint.preflight_collector.
run_cp_preflight` is deferred to the future production/container-pod
runtime hardening milestone. For the current development/pre-production
phase, `strict_host_key` defaults to ``False`` — the same compatibility
default every other CP SSH caller in this repository already has. Strict
mode remains fully implemented and selectable; production enforcement is
tracked as backlog `cp_production_ssh_host_key_trust_hardening`, not
implemented here.

This file proves the ten properties the PO override requires (task §7):
 1. the development path does not require known_hosts provisioning
 2. it reuses the existing canonical compatibility trust behavior
 3. no new trust implementation exists
 4. an observed fingerprint is discarded, never promoted
 5. the fingerprint cannot affect a readiness verdict
 6. the fingerprint cannot become physical identity authority
 7. strict mode still works when explicitly exercised
 8. strict mode still fails closed on an unprovisioned host
 9. the d31d402/S8-P0.1 correction and its retry-classification follow-up
    are still in place (not silently reverted by this change)
10. no new CLI/config trust switch was introduced

All tests use mocks/synthetic Paramiko only — no real SSH session, no real
network, no production key material.
"""
from __future__ import annotations

import ast
import dataclasses
import inspect
from pathlib import Path

import paramiko
import pytest

import checkpoint.preflight_collector as pc
import configuration.checkpoint_config_probe as probe
from checkpoint.preflight_collector import CPPhysicalMemberTarget, run_cp_preflight
from utils.cp_ssh_trust import CpSshStrictPreflightError

pytestmark = pytest.mark.security

PROJECT_ROOT = Path(__file__).resolve().parents[1]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _isolated_home(tmp_path: Path, monkeypatch) -> Path:
    home = tmp_path / "home"
    home.mkdir(exist_ok=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("USERPROFILE", str(home))
    monkeypatch.delenv("HOMEDRIVE", raising=False)
    monkeypatch.delenv("HOMEPATH", raising=False)
    return home


def _provision_known_hosts(tmp_path: Path, monkeypatch, *, populated: bool) -> Path:
    home = _isolated_home(tmp_path, monkeypatch)
    (home / ".ssh").mkdir(exist_ok=True)
    known_hosts = home / ".ssh" / "known_hosts"
    if populated:
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")
    return known_hosts


def _fake_ssh_and_exec(monkeypatch):
    """Patch paramiko.SSHClient with a connect-only fake and the preflight's
    persistent-shell session with a fixture that always "succeeds" with empty
    output -- the same first-class-supported "everything missing" shape the S5
    suite already exercises. This file is about host-key trust, not execution,
    so the shell is stubbed at the session seam. Returns the list of created
    FakeSSH instances (to inspect the installed policy)."""
    instances: list = []

    class FakeTransport:
        def get_remote_server_key(self_inner):
            return None

    class FakeSSH:
        def __init__(self_inner):
            self_inner._policy = None
            instances.append(self_inner)

        def load_system_host_keys(self_inner, filename=None):
            pass

        def set_missing_host_key_policy(self_inner, policy):
            self_inner._policy = policy

        def connect(self_inner, *a, **kw):
            pass

        def get_transport(self_inner):
            return FakeTransport()

        def close(self_inner):
            pass

    def fake_session(ssh, *, physical_device_identity, command_timeout):
        # Mirrors make_real_member_session's signature -- a double that drifts
        # from it hides real call sites.
        return pc.MemberSession(
            physical_device_identity=physical_device_identity,
            _run_command=lambda _command_text: {
                "success": True, "stdout": "", "stderr": "",
                "error_class": "none", "timeout": False, "exit_status": 0,
            },
        )

    monkeypatch.setattr(paramiko, "SSHClient", FakeSSH)
    monkeypatch.setattr(pc, "make_real_member_session", fake_session)
    return instances


def _members():
    return [
        CPPhysicalMemberTarget("member-a", "gw-member-a", "192.0.2.10"),
        CPPhysicalMemberTarget("member-b", "gw-member-b", "192.0.2.11"),
    ]


# ---------------------------------------------------------------------------
# 1 & 2 — compat-by-default, no provisioning required, reuses the real helper
# ---------------------------------------------------------------------------

class TestDevelopmentDefaultIsCompatibilityMode:

    def test_default_succeeds_with_no_known_hosts_file_at_all(self, tmp_path, monkeypatch):
        """No .ssh directory exists at all -- proves no provisioning is required."""
        _isolated_home(tmp_path, monkeypatch)  # HOME exists; no .ssh/known_hosts
        instances = _fake_ssh_and_exec(monkeypatch)

        snapshot = run_cp_preflight(
            operational_entity_id="entity-1", unit_type="clusterxl",
            members=_members(), username="admin", secret="s3cr3t",  # pragma: allowlist secret
        )

        assert snapshot.preflight_run_id
        assert len(snapshot.members) == 2

    def test_default_installs_the_real_compat_policy(self, tmp_path, monkeypatch):
        """Reuses the exact same AutoAddPolicy branch every other caller uses --
        proven by inspecting what the real apply_strict_host_key_policy
        installed on the fake client, not by mocking that decision away."""
        _isolated_home(tmp_path, monkeypatch)
        instances = _fake_ssh_and_exec(monkeypatch)

        run_cp_preflight(
            operational_entity_id="entity-1", unit_type="clusterxl",
            members=_members(), username="admin", secret="s3cr3t",  # pragma: allowlist secret
        )

        assert len(instances) == 2  # one per member
        for ssh in instances:
            assert isinstance(ssh._policy, paramiko.AutoAddPolicy)

    def test_omitting_strict_host_key_is_equivalent_to_passing_false(self, tmp_path, monkeypatch):
        _isolated_home(tmp_path, monkeypatch)
        instances_default = _fake_ssh_and_exec(monkeypatch)
        run_cp_preflight(
            operational_entity_id="e1", unit_type="clusterxl",
            members=[_members()[0]], username="u", secret="s",  # pragma: allowlist secret
        )
        assert isinstance(instances_default[0]._policy, paramiko.AutoAddPolicy)

        instances_explicit = _fake_ssh_and_exec(monkeypatch)
        run_cp_preflight(
            operational_entity_id="e1", unit_type="clusterxl",
            members=[_members()[0]], username="u", secret="s",  # pragma: allowlist secret
            strict_host_key=False,
        )
        assert isinstance(instances_explicit[0]._policy, paramiko.AutoAddPolicy)

    def test_signature_default_is_false(self):
        params = inspect.signature(run_cp_preflight).parameters
        assert params["strict_host_key"].default is False


# ---------------------------------------------------------------------------
# 3 — no new trust implementation
# ---------------------------------------------------------------------------

class TestNoNewTrustImplementation:

    def test_connect_is_the_one_shared_function_object(self):
        """checkpoint.preflight_collector reuses configuration.checkpoint_config_probe's
        _connect verbatim -- not a reimplementation, not a wrapper with its
        own policy logic."""
        assert pc._connect is probe._connect

    def test_module_never_touches_paramiko_policy_apis_directly(self):
        src = inspect.getsource(pc)
        for forbidden in (
            "RejectPolicy(", "AutoAddPolicy(", "WarningPolicy",
            "set_missing_host_key_policy", "load_system_host_keys",
            "_NonRetryableRejectPolicy",
        ):
            assert forbidden not in src, f"preflight_collector must not touch trust policy directly: {forbidden!r}"

    def test_module_imports_no_second_trust_helper(self):
        tree = ast.parse(inspect.getsource(pc))
        imported_names: set[str] = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom):
                imported_names.update(alias.name for alias in node.names)
        trust_related = {
            n for n in imported_names
            if "trust" in n.lower() or "hostkey" in n.lower().replace("_", "") or "rejectpolicy" in n.lower()
            or "autoaddpolicy" in n.lower()
        }
        assert trust_related == set(), f"unexpected trust-adjacent import: {trust_related}"


# ---------------------------------------------------------------------------
# 4, 5, 6 — observed fingerprint is discarded, never authoritative
# ---------------------------------------------------------------------------

class TestFingerprintNeverPromoted:

    def test_connect_return_value_is_discarded_by_name(self):
        """The loop binds _connect's fingerprint to `_fingerprint` (a
        conventionally-unused name) and never reads it again: exactly one
        Name node with that id in the whole function, and it is the
        assignment target (Store), never a read (Load)."""
        tree = ast.parse(inspect.getsource(pc))
        fn = next(n for n in ast.walk(tree) if isinstance(n, ast.FunctionDef) and n.name == "run_cp_preflight")
        fingerprint_names = [n for n in ast.walk(fn) if isinstance(n, ast.Name) and n.id == "_fingerprint"]
        assert len(fingerprint_names) == 1, "the fingerprint must be bound exactly once and never read"
        assert isinstance(fingerprint_names[0].ctx, ast.Store)

    def test_snapshot_serialization_never_contains_a_fingerprint(self, tmp_path, monkeypatch):
        _isolated_home(tmp_path, monkeypatch)
        _fake_ssh_and_exec(monkeypatch)
        snapshot = run_cp_preflight(
            operational_entity_id="entity-1", unit_type="clusterxl",
            members=_members(), username="admin", secret="s3cr3t",  # pragma: allowlist secret
        )
        import json
        encoded = json.dumps(snapshot.to_dict())
        assert "fingerprint" not in encoded.lower()

    def test_evidence_dataclasses_carry_no_fingerprint_field(self):
        from utils.failover.preflight_model import PreflightFact, PreflightMemberEvidence, PreflightSnapshot, Provenance

        for cls in (PreflightFact, PreflightMemberEvidence, PreflightSnapshot, Provenance):
            names = {f.name for f in dataclasses.fields(cls)}
            assert not any("fingerprint" in n.lower() for n in names), f"{cls.__name__} must not carry a fingerprint field: {names}"

    def test_readiness_fact_check_mapping_never_references_fingerprint(self):
        import utils.failover.preflight_readiness as readiness

        src = inspect.getsource(readiness)
        assert "fingerprint" not in src.lower()


# ---------------------------------------------------------------------------
# 7 & 8 — strict mode still works, still fails closed
# ---------------------------------------------------------------------------

class TestStrictModeStillFullyFunctional:

    def test_strict_explicit_succeeds_with_provisioned_key(self, tmp_path, monkeypatch):
        _provision_known_hosts(tmp_path, monkeypatch, populated=True)
        instances = _fake_ssh_and_exec(monkeypatch)

        snapshot = run_cp_preflight(
            operational_entity_id="entity-1", unit_type="clusterxl",
            members=[_members()[0]], username="admin", secret="s3cr3t",  # pragma: allowlist secret
            strict_host_key=True,
        )

        assert snapshot.preflight_run_id
        assert isinstance(instances[0]._policy, paramiko.RejectPolicy)

    def test_strict_explicit_fails_closed_without_provisioning(self, tmp_path, monkeypatch):
        _provision_known_hosts(tmp_path, monkeypatch, populated=False)
        _fake_ssh_and_exec(monkeypatch)

        # Nothing may execute -- and no shell may even be opened -- once the
        # strict trust preflight has failed.
        exec_calls: list = []
        monkeypatch.setattr(
            pc, "make_real_member_session",
            lambda *a, **kw: exec_calls.append(1) or (_ for _ in ()).throw(
                AssertionError("a session was opened after a preflight failure")),
        )

        with pytest.raises(CpSshStrictPreflightError):
            run_cp_preflight(
                operational_entity_id="entity-1", unit_type="clusterxl",
                members=_members(), username="admin", secret="s3cr3t",  # pragma: allowlist secret
                strict_host_key=True,
            )
        assert exec_calls == [], "no command must be issued after a preflight failure"

    def test_no_tofu_inside_strict_mode(self, tmp_path, monkeypatch):
        """Strict mode never installs an accepting policy, regardless of outcome."""
        _provision_known_hosts(tmp_path, monkeypatch, populated=True)
        instances = _fake_ssh_and_exec(monkeypatch)
        run_cp_preflight(
            operational_entity_id="e1", unit_type="clusterxl",
            members=[_members()[0]], username="u", secret="s",  # pragma: allowlist secret
            strict_host_key=True,
        )
        for ssh in instances:
            assert not isinstance(ssh._policy, paramiko.AutoAddPolicy)


# ---------------------------------------------------------------------------
# 9 — the S8-P0.1 correction and its retry-classification follow-up are
#     still in place (this change must not silently revert them)
# ---------------------------------------------------------------------------

class TestPriorCorrectionsNotReverted:

    def test_non_retryable_reject_policy_still_present(self):
        import utils.cp_ssh_trust as trust_mod

        assert issubclass(trust_mod._NonRetryableRejectPolicy, paramiko.RejectPolicy)
        assert issubclass(trust_mod.HostKeyNotTrustedError, paramiko.SSHException)

    def test_strict_preflight_still_counts_the_system_store_not_local_store(self, tmp_path, monkeypatch):
        """Pins d31d402: a populated system known_hosts must still satisfy
        strict preflight (the exact defect that build corrected)."""
        from utils.cp_ssh_trust import apply_strict_host_key_policy

        _provision_known_hosts(tmp_path, monkeypatch, populated=True)
        ssh = paramiko.SSHClient()
        apply_strict_host_key_policy(ssh, strict=True)  # must not raise

    def test_config_probe_connect_still_treats_host_key_rejection_as_non_retryable(self):
        src = inspect.getsource(probe._connect)
        assert "HostKeyNotTrustedError" in src


# ---------------------------------------------------------------------------
# 10 — no new CLI/config trust switch
# ---------------------------------------------------------------------------

class TestNoNewConfigSurface:

    def test_cli_workflow_relies_on_the_default_not_a_new_switch(self):
        import application.workflows.preflight as wf

        src = inspect.getsource(wf.cp_ha_preflight_check)
        assert "strict_host_key" not in src, "the CLI must not pass or expose a strict-mode switch"

    def test_no_new_environment_variable_introduced(self):
        src = inspect.getsource(pc)
        assert "os.getenv" not in src and "os.environ" not in src

    def test_argparse_surface_unchanged(self):
        import application.cli as cli_mod

        src = inspect.getsource(cli_mod)
        for forbidden in ("--strict-host-key", "--insecure", "--accept-host-key"):
            assert forbidden not in src
