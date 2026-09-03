"""OP.0b S8-P0.1 — CP SSH strict trust-store preflight correction.

Root cause (reproduced against real Paramiko, not a mock):
``SSHClient.load_system_host_keys()`` populates the read-only *system*
store, while ``SSHClient.get_host_keys()`` exposes the writable *local*
store.  The original 0.6.4 preflight loaded the former and gated on the
latter, so ``strict=True`` was unusable even with a correctly provisioned
``known_hosts``.  The mocks of that era encoded the wrong assumption, which
is why every load-bearing test here uses a REAL ``paramiko.SSHClient`` and
a temporary synthetic ``known_hosts`` with an in-process generated key.

No network: ``connect`` is replaced by a sentinel on every real client.
No production key material: keys are generated per test and discarded.
Synthetic host identities use RFC 5737 addresses only.

Required matrix (task §7):
  1  real client + populated system known_hosts    -> strict PASS
  2  real client + empty/missing system known_hosts -> strict FAIL
  3  RejectPolicy remains active
  4  failure occurs before connect
  5  strict=False is exactly the pre-existing compatibility behavior
  6  explicit trusted source: NOT_PRESENT in canonical policy (documented)
  7  malformed / unreadable trust source fails closed
  8  system-store-only deployment needs no load_host_keys duplication
  9  AutoAddPolicy absent from the strict branch
 10  no TOFU / enrollment behavior
 11  observed-unverified fingerprint is never promoted to trust
 12  all five CP SSH callers use the single shared helper
 13  persistent-secret-material trust check passes with a populated store
 14  Windows-style user-profile semantics without platform branching
"""
from __future__ import annotations

import ast
import inspect
import os
from pathlib import Path
from unittest.mock import MagicMock

import paramiko
import pytest

from utils import cp_ssh_trust
from utils.cp_ssh_trust import (
    REASON_NO_USABLE_HOST_KEYS,
    REASON_TRUST_SOURCE_MALFORMED,
    REASON_TRUST_SOURCE_UNREADABLE,
    CpSshStrictPreflightError,
    apply_strict_host_key_policy,
    load_trusted_host_keys,
)

pytestmark = pytest.mark.security

PROJECT_ROOT = Path(__file__).resolve().parents[1]
HELPER_PATH = PROJECT_ROOT / "utils" / "cp_ssh_trust.py"

# The five CP SSH client paths that must all inherit the correction through
# the one shared helper (task §10).
CALLER_MODULES = (
    "checkpoint/cp_runner.py",
    "checkpoint/vsx_runner.py",
    "checkpoint/direct_ssh_probe.py",
    "configuration/checkpoint_config_probe.py",
    "utils/persistent_secret_material.py",
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _synthetic_entry(host: str = "192.0.2.10") -> str:
    """One valid OpenSSH known_hosts line for a freshly generated key."""
    key = paramiko.ECDSAKey.generate()
    return f"{host} {key.get_name()} {key.get_base64()}\n"


def _isolated_home(tmp_path: Path, monkeypatch) -> Path:
    """Point the user-profile resolution at an isolated directory.

    Both POSIX (``HOME``) and Windows (``USERPROFILE``) variables are set so
    the same test resolves identically on either platform — the product
    code contains no platform branch, and neither does this test.
    """
    home = tmp_path / "home"
    home.mkdir(exist_ok=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("USERPROFILE", str(home))
    monkeypatch.delenv("HOMEDRIVE", raising=False)
    monkeypatch.delenv("HOMEPATH", raising=False)
    return home


def _provision(tmp_path: Path, monkeypatch, content: str | None) -> Path:
    """Isolated HOME with ``.ssh/known_hosts`` holding *content* (or absent)."""
    home = _isolated_home(tmp_path, monkeypatch)
    ssh_dir = home / ".ssh"
    ssh_dir.mkdir(exist_ok=True)
    known_hosts = ssh_dir / "known_hosts"
    if content is not None:
        known_hosts.write_text(content, encoding="utf-8")
    return known_hosts


def _real_client_with_connect_sentinel():
    """A real SSHClient whose connect() records the call and never opens a socket."""
    ssh = paramiko.SSHClient()
    calls: list = []

    def _sentinel(*args, **kwargs):
        calls.append((args, kwargs))
        raise RuntimeError("connect-sentinel-reached")

    ssh.connect = _sentinel  # instance-level override, no network possible
    return ssh, calls


def _strict_branch(func_ast: ast.FunctionDef) -> tuple[list, list]:
    """Return (strict_body, compat_body) of ``if strict: ... else: ...``."""
    for node in func_ast.body:
        if isinstance(node, ast.If) and isinstance(node.test, ast.Name) and node.test.id == "strict":
            return node.body, node.orelse
    raise AssertionError("apply_strict_host_key_policy must branch on `if strict:`")


def _names_in(nodes) -> set[str]:
    found: set[str] = set()
    for node in nodes:
        for sub in ast.walk(node):
            if isinstance(sub, ast.Name):
                found.add(sub.id)
            elif isinstance(sub, ast.Attribute):
                found.add(sub.attr)
    return found


@pytest.fixture
def helper_ast():
    return ast.parse(HELPER_PATH.read_text(encoding="utf-8"))


def _helper_code_only() -> str:
    """The helper's executable code with every docstring and comment removed,
    so prose that *explains* a forbidden API does not trip a guard."""
    tree = ast.parse(HELPER_PATH.read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            if node.body and isinstance(node.body[0], ast.Expr) and isinstance(
                getattr(node.body[0], "value", None), ast.Constant
            ) and isinstance(node.body[0].value.value, str):
                node.body = node.body[1:] or [ast.Pass()]
    return ast.unparse(tree)


def _called_names(tree: ast.AST) -> set[str]:
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name):
                names.add(node.func.id)
            elif isinstance(node.func, ast.Attribute):
                names.add(node.func.attr)
    return names


# ---------------------------------------------------------------------------
# Root-cause reproducer (must FAIL against the old implementation)
# ---------------------------------------------------------------------------

class TestRootCauseReproducer:
    """The exact defect: system store populated, local store empty."""

    def test_real_paramiko_system_store_is_not_get_host_keys(self, tmp_path, monkeypatch):
        """Pins the Paramiko store semantics the old helper got wrong.

        This is the only place product-adjacent code looks at a private
        Paramiko attribute, and it is a test: it exists to prove why the
        product must not gate on ``get_host_keys()``.
        """
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        ssh = paramiko.SSHClient()
        ssh.load_system_host_keys()  # the pre-existing deployment call, no filename
        assert len(ssh._system_host_keys) == 1, "system store must hold the provisioned entry"
        assert len(ssh.get_host_keys()) == 0, "get_host_keys() is the writable local store, not the system store"

    def test_populated_system_known_hosts_passes_strict_preflight(self, tmp_path, monkeypatch):
        """Matrix 1 — the reproducer.  Old implementation raised here."""
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        ssh, calls = _real_client_with_connect_sentinel()
        apply_strict_host_key_policy(ssh, strict=True)  # must not raise
        assert calls == [], "preflight itself must never call connect()"

    def test_helper_count_equals_client_system_store(self, tmp_path, monkeypatch):
        """The public-parser cardinality equals what the client will verify against."""
        _provision(tmp_path, monkeypatch, _synthetic_entry("192.0.2.10") + _synthetic_entry("192.0.2.11"))
        ssh = paramiko.SSHClient()
        loaded = load_trusted_host_keys(ssh)
        assert loaded == 2
        assert loaded == len(ssh._system_host_keys)
        assert len(ssh.get_host_keys()) == 0


# ---------------------------------------------------------------------------
# Matrix 2 / 4 / 7 — fail closed, before connect, value-free
# ---------------------------------------------------------------------------

class TestStrictFailsClosedBeforeConnect:

    @pytest.mark.parametrize(
        "content, reason",
        [
            (None, REASON_TRUST_SOURCE_UNREADABLE),               # missing file
            ("", REASON_NO_USABLE_HOST_KEYS),                     # empty file
            ("# comment only\n\n", REASON_NO_USABLE_HOST_KEYS),   # no entries
            ("192.0.2.10 ssh-unknown-type AAAA\n", REASON_NO_USABLE_HOST_KEYS),  # skipped by parser
            ("192.0.2.10 ssh-ed25519 !!!not-base64!!!\n", REASON_TRUST_SOURCE_MALFORMED),
        ],
    )
    def test_strict_fails_before_connect(self, tmp_path, monkeypatch, content, reason):
        _provision(tmp_path, monkeypatch, content)
        ssh, calls = _real_client_with_connect_sentinel()
        with pytest.raises(CpSshStrictPreflightError) as excinfo:
            apply_strict_host_key_policy(ssh, strict=True)
        assert excinfo.value.reason == reason
        assert calls == [], "connect() must not be reached after a preflight failure"

    def test_unreadable_source_fails_closed(self, tmp_path, monkeypatch):
        """A known_hosts path that cannot be opened (a directory) is unreadable."""
        home = _isolated_home(tmp_path, monkeypatch)
        (home / ".ssh" / "known_hosts").mkdir(parents=True)
        ssh, calls = _real_client_with_connect_sentinel()
        with pytest.raises(CpSshStrictPreflightError) as excinfo:
            apply_strict_host_key_policy(ssh, strict=True)
        assert excinfo.value.reason == REASON_TRUST_SOURCE_UNREADABLE
        assert calls == []

    def test_binary_garbage_fails_closed(self, tmp_path, monkeypatch):
        known_hosts = _provision(tmp_path, monkeypatch, None)
        known_hosts.write_bytes(b"\xff\xfe\x00garbage")
        ssh, calls = _real_client_with_connect_sentinel()
        with pytest.raises(CpSshStrictPreflightError) as excinfo:
            apply_strict_host_key_policy(ssh, strict=True)
        assert excinfo.value.reason == REASON_TRUST_SOURCE_MALFORMED
        assert calls == []

    def test_malformed_failure_is_value_free_and_unchained(self, tmp_path, monkeypatch):
        """Paramiko's InvalidHostKey carries the raw line; it must not leak via
        the message or the exception chain."""
        secret_marker = "SYNTHKEYMARKER"
        _provision(tmp_path, monkeypatch, f"192.0.2.10 ssh-ed25519 {secret_marker}!!!\n")
        ssh = paramiko.SSHClient()
        with pytest.raises(CpSshStrictPreflightError) as excinfo:
            apply_strict_host_key_policy(ssh, strict=True)
        exc = excinfo.value
        assert secret_marker not in str(exc)
        assert "192.0.2" not in str(exc)
        assert exc.__cause__ is None
        assert exc.__suppress_context__ is True

    def test_failure_message_contains_no_path_or_identity(self, tmp_path, monkeypatch):
        _provision(tmp_path, monkeypatch, None)
        ssh = paramiko.SSHClient()
        with pytest.raises(CpSshStrictPreflightError) as excinfo:
            apply_strict_host_key_policy(ssh, strict=True)
        msg = str(excinfo.value)
        assert msg.startswith("strict_host_key_preflight_failed:")
        for forbidden in (str(tmp_path), "known_hosts", "/", "\\", "192.", "198.", "10."):
            assert forbidden not in msg, f"preflight message must not contain {forbidden!r}"


# ---------------------------------------------------------------------------
# Matrix 3 — RejectPolicy remains active
# ---------------------------------------------------------------------------

class TestRejectPolicyActive:

    def test_reject_policy_after_successful_preflight(self, tmp_path, monkeypatch):
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        ssh = paramiko.SSHClient()
        apply_strict_host_key_policy(ssh, strict=True)
        assert isinstance(ssh._policy, paramiko.RejectPolicy)

    def test_reject_policy_after_failed_preflight(self, tmp_path, monkeypatch):
        """Even on the failure path the client never holds an accepting policy."""
        _provision(tmp_path, monkeypatch, None)
        ssh = paramiko.SSHClient()
        with pytest.raises(CpSshStrictPreflightError):
            apply_strict_host_key_policy(ssh, strict=True)
        assert isinstance(ssh._policy, paramiko.RejectPolicy)

    def test_strict_sets_reject_policy_via_public_api(self, tmp_path, monkeypatch):
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        ssh = MagicMock(spec=paramiko.SSHClient)
        apply_strict_host_key_policy(ssh, strict=True)
        ssh.set_missing_host_key_policy.assert_called_once()
        assert isinstance(ssh.set_missing_host_key_policy.call_args[0][0], paramiko.RejectPolicy)

    def test_unknown_host_is_rejected_by_installed_policy(self, tmp_path, monkeypatch):
        """RejectPolicy refuses an unknown key instead of enrolling it."""
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        ssh = paramiko.SSHClient()
        apply_strict_host_key_policy(ssh, strict=True)
        unknown = paramiko.ECDSAKey.generate()
        ssh._transport = MagicMock()  # policies log through the transport; no socket is opened
        with pytest.raises(paramiko.SSHException):
            ssh._policy.missing_host_key(ssh, "198.51.100.7", unknown)
        assert len(ssh.get_host_keys()) == 0, "rejected key must not be added anywhere"


# ---------------------------------------------------------------------------
# Matrix 5 — compatibility mode is exactly the pre-existing behavior
# ---------------------------------------------------------------------------

class TestCompatModeUnchanged:

    def test_compat_sets_auto_add_and_nothing_else(self):
        ssh = MagicMock(spec=paramiko.SSHClient)
        apply_strict_host_key_policy(ssh, strict=False)
        ssh.set_missing_host_key_policy.assert_called_once()
        assert isinstance(ssh.set_missing_host_key_policy.call_args[0][0], paramiko.AutoAddPolicy)
        ssh.load_system_host_keys.assert_not_called()
        ssh.load_host_keys.assert_not_called()
        ssh.get_host_keys.assert_not_called()
        ssh.save_host_keys.assert_not_called()
        ssh.connect.assert_not_called()

    def test_compat_does_not_touch_known_hosts(self, tmp_path, monkeypatch):
        """strict=False must not read the trust source (no preflight) and
        must not create/write it."""
        known_hosts = _provision(tmp_path, monkeypatch, None)
        ssh = paramiko.SSHClient()
        apply_strict_host_key_policy(ssh, strict=False)
        assert isinstance(ssh._policy, paramiko.AutoAddPolicy)
        assert not known_hosts.exists()
        assert len(ssh._system_host_keys) == 0

    def test_compat_ignores_populated_store_without_preflight(self, tmp_path, monkeypatch):
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        ssh = paramiko.SSHClient()
        apply_strict_host_key_policy(ssh, strict=False)
        assert isinstance(ssh._policy, paramiko.AutoAddPolicy)
        assert len(ssh._system_host_keys) == 0, "compat mode never loads trusted material"


# ---------------------------------------------------------------------------
# Matrix 6 — explicit trusted source: not present in canonical policy
# ---------------------------------------------------------------------------

class TestNoExplicitTrustSourceSurface:
    """The 0.6.4 policy as implemented has no explicit RuntimeRoot
    known_hosts source; this slice must not invent one (task §4)."""

    def test_helper_reads_no_environment_or_config(self, helper_ast):
        src = _helper_code_only()
        for forbidden in ("os.getenv", "os.environ", "argparse", "sys.argv", "runtime_paths", "SECURITYEXPERT_"):
            assert forbidden not in src, f"no new trust-source surface: {forbidden!r}"

    def test_public_surface_is_unchanged_plus_loader(self):
        public = {name for name in dir(cp_ssh_trust) if not name.startswith("_")}
        expected = {
            "CpSshStrictPreflightError",
            "apply_strict_host_key_policy",
            "load_trusted_host_keys",
            "REASON_NO_USABLE_HOST_KEYS",
            "REASON_TRUST_SOURCE_MALFORMED",
            "REASON_TRUST_SOURCE_UNREADABLE",
            "InvalidHostKey",
            "annotations",
            "os",
            "paramiko",
        }
        assert public == expected, f"unexpected public surface change: {sorted(public ^ expected)}"

    def test_apply_signature_unchanged(self):
        params = list(inspect.signature(apply_strict_host_key_policy).parameters)
        assert params == ["ssh", "strict"]


# ---------------------------------------------------------------------------
# Matrix 8 — system store only; no duplication into the local store
# ---------------------------------------------------------------------------

class TestSystemStoreOnly:

    def test_no_load_host_keys_duplication_required(self, tmp_path, monkeypatch):
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        ssh = paramiko.SSHClient()
        apply_strict_host_key_policy(ssh, strict=True)
        assert len(ssh.get_host_keys()) == 0, "the writable local store stays empty"
        assert ssh._host_keys_filename is None, "no load_host_keys() call (would make save_host_keys() a write path)"

    def test_helper_source_never_calls_local_store_apis(self, helper_ast):
        called = _called_names(helper_ast)
        for forbidden in ("load_host_keys", "get_host_keys", "save_host_keys"):
            assert forbidden not in called, f"helper must not touch the writable local store: {forbidden}"
        assert "load_system_host_keys" in called

    def test_preflight_does_not_modify_known_hosts(self, tmp_path, monkeypatch):
        known_hosts = _provision(tmp_path, monkeypatch, _synthetic_entry())
        before = known_hosts.read_bytes()
        ssh = paramiko.SSHClient()
        apply_strict_host_key_policy(ssh, strict=True)
        assert known_hosts.read_bytes() == before


# ---------------------------------------------------------------------------
# Matrix 9 / 10 / 12 (§12) — structural security regression guards
# ---------------------------------------------------------------------------

class TestStructuralSecurityGuards:

    def _apply_fn(self, helper_ast) -> ast.FunctionDef:
        for node in helper_ast.body:
            if isinstance(node, ast.FunctionDef) and node.name == "apply_strict_host_key_policy":
                return node
        raise AssertionError("apply_strict_host_key_policy missing")

    def test_strict_branch_has_no_accepting_policy(self, helper_ast):
        strict_body, compat_body = _strict_branch(self._apply_fn(helper_ast))
        strict_names = _names_in(strict_body)
        assert "AutoAddPolicy" not in strict_names
        assert "WarningPolicy" not in strict_names
        assert "RejectPolicy" in strict_names
        assert "AutoAddPolicy" in _names_in(compat_body), "compat branch is where AutoAddPolicy lives"

    def test_no_strict_false_fallback_inside_strict_branch(self, helper_ast):
        strict_body, _ = _strict_branch(self._apply_fn(helper_ast))
        for node in strict_body:
            for sub in ast.walk(node):
                assert not isinstance(sub, ast.Try), "strict branch must not catch its own preflight failure"
                if isinstance(sub, ast.Call) and isinstance(sub.func, ast.Name):
                    assert sub.func.id != "apply_strict_host_key_policy", "no recursive strict=False retry"

    def test_no_write_enroll_or_keyscan_primitives(self, helper_ast):
        code = _helper_code_only()
        for forbidden in ("keyscan", "subprocess", "socket", "urllib", "requests", "WarningPolicy"):
            assert forbidden not in code, f"forbidden primitive in strict helper: {forbidden!r}"
        called = _called_names(helper_ast)
        for forbidden in ("save_host_keys", "add", "open", "write", "unlink", "rename", "system", "run", "Popen", "connect"):
            assert forbidden not in called, f"forbidden call in strict helper: {forbidden!r}"
        # AutoAddPolicy is instantiated exactly once, in the compat branch.
        assert code.count("paramiko.AutoAddPolicy()") == 1

    def test_helper_imports_are_minimal(self, helper_ast):
        imported: set[str] = set()
        for node in helper_ast.body:
            if isinstance(node, ast.Import):
                imported.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.add(node.module or "")
        assert imported == {"__future__", "os", "paramiko", "paramiko.hostkeys"}

    def test_every_exception_handler_re_raises(self, helper_ast):
        """No fail-open handler: every except body must end in a raise."""
        handlers = [n for n in ast.walk(helper_ast) if isinstance(n, ast.ExceptHandler)]
        assert handlers, "expected the fail-closed handlers around the trust-source load"
        for handler in handlers:
            assert handler.body and isinstance(handler.body[-1], ast.Raise), (
                "exception handler in cp_ssh_trust must fail closed (re-raise)"
            )
            raised = handler.body[-1].exc
            assert isinstance(raised, ast.Call) and getattr(raised.func, "id", None) == "CpSshStrictPreflightError"

    def test_no_paramiko_private_internals_in_product_code(self, helper_ast):
        attrs = {n.attr for n in ast.walk(helper_ast) if isinstance(n, ast.Attribute)}
        private = {a for a in attrs if a.startswith("_") and not a.startswith("__")}
        assert private == set(), f"product code must not touch Paramiko internals: {sorted(private)}"

    def test_no_platform_branching(self):
        src = _helper_code_only()
        for forbidden in ("sys.platform", "os.name", "platform.system", '"win32"', '"nt"', "USERPROFILE", "HOMEDRIVE"):
            assert forbidden not in src, f"no platform branch: {forbidden!r}"

    def test_all_five_callers_use_the_shared_helper_only(self):
        for rel in CALLER_MODULES:
            src = (PROJECT_ROOT / rel).read_text(encoding="utf-8")
            assert "from utils.cp_ssh_trust import" in src, rel
            assert "apply_strict_host_key_policy" in src, rel
            code = "\n".join(
                ln for ln in src.splitlines()
                if not ln.lstrip().startswith("#") and "warn(" not in ln
            )
            for forbidden in (
                "RejectPolicy(", "AutoAddPolicy(", "WarningPolicy",
                "load_system_host_keys", "load_host_keys", "get_host_keys",
                "save_host_keys", "_system_host_keys", "._host_keys",
            ):
                assert forbidden not in code, f"{rel} must inherit trust behavior only via the helper ({forbidden!r})"

    def test_callers_import_the_same_function_object(self):
        import checkpoint.cp_runner as cp_runner
        import checkpoint.direct_ssh_probe as direct_ssh_probe
        import checkpoint.vsx_runner as vsx_runner
        import configuration.checkpoint_config_probe as config_probe
        import utils.persistent_secret_material as psm

        for mod in (cp_runner, vsx_runner, direct_ssh_probe, config_probe, psm):
            assert mod.apply_strict_host_key_policy is apply_strict_host_key_policy, mod.__name__


# ---------------------------------------------------------------------------
# Matrix 11 — observed-unverified fingerprint is never promoted to trust
# ---------------------------------------------------------------------------

class TestObservedFingerprintNotPromoted:

    def test_observed_key_not_in_trusted_store(self, tmp_path, monkeypatch):
        """A key observed in compatibility mode is not trusted by a later
        strict client; only the provisioned entry is."""
        known_hosts = _provision(tmp_path, monkeypatch, _synthetic_entry("192.0.2.10"))
        observed = paramiko.ECDSAKey.generate()

        # Compatibility-mode client "observes" a key via AutoAddPolicy.
        compat = paramiko.SSHClient()
        apply_strict_host_key_policy(compat, strict=False)
        compat._transport = MagicMock()  # policies log through the transport; no socket is opened
        compat._policy.missing_host_key(compat, "198.51.100.9", observed)
        assert compat.get_host_keys().lookup("198.51.100.9") is not None  # local store only

        # The trusted source on disk is untouched and a strict client does not know it.
        assert "198.51.100.9" not in known_hosts.read_text(encoding="utf-8")
        strict = paramiko.SSHClient()
        apply_strict_host_key_policy(strict, strict=True)
        assert strict._system_host_keys.lookup("198.51.100.9") is None
        assert strict._system_host_keys.lookup("192.0.2.10") is not None

    def test_compat_mode_row_semantics_stay_observe_only(self):
        """The pre-existing collector/probe vocabulary for compat mode."""
        from configuration import checkpoint_config_probe as probe
        src = inspect.getsource(probe)
        assert '"strict_known_hosts" if strict_host_key else "observe_and_record_not_production"' in src


# ---------------------------------------------------------------------------
# Matrix 4 (caller level) — callers reach connect only after a passing preflight
# ---------------------------------------------------------------------------

class TestCallersInheritCorrection:

    def test_vsx_connect_reaches_connect_with_populated_store(self, tmp_path, monkeypatch):
        """Real SSHClient; only connect() is a sentinel."""
        import checkpoint.vsx_runner as vsx_runner
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        calls: list = []

        def _sentinel(self, *a, **kw):
            calls.append(kw)
            raise RuntimeError("connect-sentinel-reached")

        monkeypatch.setattr(paramiko.SSHClient, "connect", _sentinel)
        with pytest.raises(RuntimeError, match="connect-sentinel-reached"):
            vsx_runner.connect("192.0.2.2", "user", "pass", strict_host_key=True)
        assert len(calls) == 1

    def test_vsx_connect_fails_before_connect_with_empty_store(self, tmp_path, monkeypatch):
        import checkpoint.vsx_runner as vsx_runner
        _provision(tmp_path, monkeypatch, None)
        calls: list = []
        monkeypatch.setattr(paramiko.SSHClient, "connect", lambda self, *a, **kw: calls.append(1))
        with pytest.raises(CpSshStrictPreflightError):
            vsx_runner.connect("192.0.2.2", "user", "pass", strict_host_key=True)
        assert calls == []

    def test_config_probe_connect_passes_preflight_with_populated_store(self, tmp_path, monkeypatch):
        from configuration import checkpoint_config_probe as probe
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        calls: list = []

        def _sentinel(self, *a, **kw):
            calls.append(1)
            raise RuntimeError("connect-sentinel-reached")

        monkeypatch.setattr(paramiko.SSHClient, "connect", _sentinel)
        target = probe.ProbeTarget(
            role="standalone_gateway", device="SYNTH-CP-GW", management_ip="192.0.2.60",
            object_type="gateway", cma=None, selection_source="management_discovery",
        )
        monkeypatch.setattr(probe, "CONNECT_RETRY_ATTEMPTS", 0)
        with pytest.raises(RuntimeError, match="connect-sentinel-reached"):
            probe._connect(target, "admin", "s3cr3t", strict=True, connect_timeout=1)
        assert len(calls) == 1, "exactly one connect attempt, no retry on the sentinel"

    def test_config_probe_connect_fails_before_connect_with_empty_store(self, tmp_path, monkeypatch):
        from configuration import checkpoint_config_probe as probe
        _provision(tmp_path, monkeypatch, "")
        calls: list = []
        monkeypatch.setattr(paramiko.SSHClient, "connect", lambda self, *a, **kw: calls.append(1))
        target = probe.ProbeTarget(
            role="standalone_gateway", device="SYNTH-CP-GW", management_ip="192.0.2.60",
            object_type="gateway", cma=None, selection_source="management_discovery",
        )
        with pytest.raises(CpSshStrictPreflightError):
            probe._connect(target, "admin", "s3cr3t", strict=True, connect_timeout=1)
        assert calls == []

    def test_direct_probe_passes_preflight_and_stops_at_sentinel(self, tmp_path, monkeypatch):
        from checkpoint.direct_ssh_probe import _probe_one
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        calls: list = []

        def _sentinel(self, *a, **kw):
            calls.append(1)
            raise OSError("synth-unreachable")

        monkeypatch.setattr(paramiko.SSHClient, "connect", _sentinel)
        monkeypatch.setenv("FBUDDY_CP_DIRECT_SSH_CONNECT_RETRIES", "0")
        row = {"device": "SYNTH-CP-GW", "management_ip": "192.0.2.50", "management_state": "up",
               "collection_outcome": None, "interface_error": None, "route_error": None}
        result = _probe_one(row, username="admin", secret="s3cr3t", port=22, connect_timeout=1,
                            command_timeout=1, strict_host_key=True)
        assert result["error_class"] != "strict_host_key_preflight_failed"
        assert result["host_key_policy"] == "strict"
        assert len(calls) == 1

    def test_direct_probe_fails_closed_with_empty_store(self, tmp_path, monkeypatch):
        from checkpoint.direct_ssh_probe import _probe_one
        _provision(tmp_path, monkeypatch, None)
        calls: list = []
        monkeypatch.setattr(paramiko.SSHClient, "connect", lambda self, *a, **kw: calls.append(1))
        row = {"device": "SYNTH-CP-GW", "management_ip": "192.0.2.50", "management_state": "up",
               "collection_outcome": None, "interface_error": None, "route_error": None}
        result = _probe_one(row, username="admin", secret="s3cr3t", port=22, connect_timeout=1,
                            command_timeout=1, strict_host_key=True)
        assert result["error_class"] == "strict_host_key_preflight_failed"
        assert calls == []


# ---------------------------------------------------------------------------
# Matrix 13 — persistent-secret-material trust verification
# ---------------------------------------------------------------------------

class TestPersistentSecretMaterialTrustCheck:

    def _runtime_paths(self, tmp_path):
        from utils.runtime_paths import resolve_runtime_paths
        repo_root = tmp_path / "repo"
        repo_root.mkdir()
        (repo_root / "main.py").write_text("", encoding="utf-8")
        return resolve_runtime_paths(str(tmp_path / "runtime"), environ={}, repository_root=repo_root)

    def test_cp_trust_passes_with_populated_synthetic_store(self, tmp_path, monkeypatch):
        from utils.persistent_secret_material import check_persistent_secret_material
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        report = check_persistent_secret_material(
            self._runtime_paths(tmp_path), environ={"SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY": "1"}
        )
        assert report.cp_strict_host_key_enabled is True
        assert report.cp_trust_status == "PASS"
        assert "cp_strict_host_key_enabled_but_no_trusted_material_mounted" not in report.findings

    def test_cp_trust_fails_with_empty_store(self, tmp_path, monkeypatch):
        from utils.persistent_secret_material import check_persistent_secret_material
        _provision(tmp_path, monkeypatch, None)
        report = check_persistent_secret_material(
            self._runtime_paths(tmp_path), environ={"SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY": "1"}
        )
        assert report.cp_trust_status == "FAIL"
        assert "cp_strict_host_key_enabled_but_no_trusted_material_mounted" in report.findings


# ---------------------------------------------------------------------------
# Matrix 14 — Windows-style user-profile semantics, no product branching
# ---------------------------------------------------------------------------

class TestUserProfileSemantics:

    def test_source_path_is_the_openssh_user_known_hosts(self, tmp_path, monkeypatch):
        home = _isolated_home(tmp_path, monkeypatch)
        resolved = Path(cp_ssh_trust._system_known_hosts_path())
        assert resolved == home / ".ssh" / "known_hosts"

    def test_explicit_path_matches_paramiko_default_resolution(self, tmp_path, monkeypatch):
        """The helper names exactly the file load_system_host_keys() would
        read with no filename, so the deployment contract is unchanged."""
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        default_client = paramiko.SSHClient()
        default_client.load_system_host_keys()          # Paramiko's own resolution
        explicit_client = paramiko.SSHClient()
        load_trusted_host_keys(explicit_client)         # the helper's resolution
        assert len(default_client._system_host_keys) == len(explicit_client._system_host_keys) == 1
        assert default_client._system_host_keys.lookup("192.0.2.10") is not None
        assert explicit_client._system_host_keys.lookup("192.0.2.10") is not None

    def test_profile_variables_only_no_platform_check(self, tmp_path, monkeypatch):
        """Setting the profile variables alone (as a Windows operator profile
        or a POSIX HOME would) is sufficient; nothing consults the platform."""
        _provision(tmp_path, monkeypatch, _synthetic_entry())
        ssh = paramiko.SSHClient()
        apply_strict_host_key_policy(ssh, strict=True)
        assert os.path.expanduser("~") == str(tmp_path / "home")
