"""PCP.1 — Device Registry manual enrollment foundation.

docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md section 21 is the frozen
contract this file proves (AC-1a..AC-15). Deterministic throughout: the
concurrency-sensitive assertions (AC-10/AC-13/AC-14/AC-15) use a test-only
technique -- pre-creating/rewriting the raw lock file and calling the
module's private lock primitives directly -- rather than timing-based
threads/subprocesses, per section 21's own validation-ladder note that the
exact technique is an implementation detail, not frozen.
"""
from __future__ import annotations

import json
import socket
import subprocess
import sys
import uuid
from pathlib import Path

import pytest

from utils import device_registry as dr
from utils.device_registry import (
    DeviceRecord,
    DeviceRegistry,
    DeviceRegistryError,
    DeviceRegistryLockError,
    LOCK_FILENAME,
    REGISTRY_FILENAME,
    normalize_endpoint,
)

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def data_root(tmp_path):
    return tmp_path / "runtime" / "data"


def _registry_path(data_root: Path) -> Path:
    return data_root / "state" / REGISTRY_FILENAME


def _lock_path(data_root: Path) -> Path:
    return data_root / "state" / LOCK_FILENAME


# ---------------------------------------------------------------------------
# AC-1a: opaque, unique device_id
# ---------------------------------------------------------------------------

def test_device_id_is_opaque_random_and_unique(data_root):
    registry = DeviceRegistry(data_root)
    a = registry.enroll(endpoint="192.0.2.10")
    b = registry.enroll(endpoint="192.0.2.11")

    assert a.device_id != b.device_id
    assert "192.0.2.10" not in a.device_id
    assert "192.0.2.11" not in b.device_id
    # opaque uuid4-hex -- parses as a UUID, not derived from any input
    uuid.UUID(a.device_id)
    uuid.UUID(b.device_id)


def test_device_id_never_derived_from_hostname_or_vendor_hint(data_root):
    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint="CP-SPARK-TEST-01.example.invalid", vendor_hint="checkpoint")
    assert "cp-spark-test-01" not in record.device_id.lower()
    assert "checkpoint" not in record.device_id.lower()


# ---------------------------------------------------------------------------
# AC-1b / duplicate detection (endpoint normalization, vendor/state independence)
# ---------------------------------------------------------------------------

def test_duplicate_enroll_refused_before_device_id_generated(monkeypatch, data_root):
    registry = DeviceRegistry(data_root)
    first = registry.enroll(endpoint="192.0.2.20")

    calls = []
    original_uuid4 = dr.uuid.uuid4
    monkeypatch.setattr(dr.uuid, "uuid4", lambda: (calls.append(1), original_uuid4())[1])

    with pytest.raises(DeviceRegistryError) as excinfo:
        registry.enroll(endpoint="192.0.2.20")
    assert first.device_id in str(excinfo.value)
    assert first.state in str(excinfo.value)
    assert calls == [], "uuid4 must never be called on the refused duplicate path"
    assert len(registry.list()) == 1


@pytest.mark.parametrize("variant", [
    "  192.0.2.20  ",       # whitespace
    "192.0.2.20",           # exact repeat
])
def test_duplicate_detection_ip_literal_after_whitespace_strip(data_root, variant):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.20")
    with pytest.raises(DeviceRegistryError):
        registry.enroll(endpoint=variant)


@pytest.mark.parametrize("variant", [
    "FW1.Example.INVALID",
    "fw1.example.invalid.",   # trailing DNS-root dot
    "  fw1.example.invalid  ",
])
def test_duplicate_detection_hostname_case_and_trailing_dot_insensitive(data_root, variant):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="fw1.example.invalid")
    with pytest.raises(DeviceRegistryError):
        registry.enroll(endpoint=variant)


def test_duplicate_detection_is_vendor_hint_independent(data_root):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.30", vendor_hint="checkpoint")
    with pytest.raises(DeviceRegistryError):
        registry.enroll(endpoint="192.0.2.30", vendor_hint="paloalto")


def test_duplicate_detection_applies_to_disabled_records_too(data_root):
    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint="192.0.2.40")
    registry.disable(record.device_id)
    with pytest.raises(DeviceRegistryError) as excinfo:
        registry.enroll(endpoint="192.0.2.40")
    assert record.device_id in str(excinfo.value)
    assert "DISABLED" in str(excinfo.value)


def test_ip_literal_and_hostname_never_unified_no_dns_resolution(data_root, monkeypatch):
    def _forbidden(*args, **kwargs):
        raise AssertionError("no DNS resolution may ever be attempted")

    monkeypatch.setattr(socket, "getaddrinfo", _forbidden)
    monkeypatch.setattr(socket, "gethostbyname", _forbidden)

    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.50")
    # A hostname is a distinct normalized endpoint even if an operator
    # believes it resolves to the same device -- never unified.
    other = registry.enroll(endpoint="same-box.example.invalid")
    assert other.state == "ENROLLED_UNVERIFIED"
    assert len(registry.list()) == 2


# ---------------------------------------------------------------------------
# Endpoint normalization unit behavior
# ---------------------------------------------------------------------------

def test_normalize_endpoint_ip_literal_byte_for_byte():
    host, port = normalize_endpoint("  192.0.2.60  ")
    assert (host, port) == ("192.0.2.60", None)


def test_normalize_endpoint_hostname_lowercased_and_trailing_dot_stripped():
    host, port = normalize_endpoint("FW2.Example.INVALID.")
    assert (host, port) == ("fw2.example.invalid", None)


def test_normalize_endpoint_port_is_separate_literal_field():
    host, port = normalize_endpoint("FW3.Example.INVALID:8443")
    assert (host, port) == ("fw3.example.invalid", 8443)


def test_normalize_endpoint_bracketed_ipv6_with_port():
    host, port = normalize_endpoint("[2001:db8::1]:443")
    assert (host, port) == ("2001:db8::1", 443)


def test_normalize_endpoint_bare_ipv6_literal_no_port():
    host, port = normalize_endpoint("2001:db8::1")
    assert (host, port) == ("2001:db8::1", None)


def test_normalize_endpoint_rejects_empty_string():
    with pytest.raises(DeviceRegistryError):
        normalize_endpoint("   ")


def test_normalize_endpoint_rejects_malformed_port():
    with pytest.raises(DeviceRegistryError):
        normalize_endpoint("fw.example.invalid:not-a-port")


def test_normalize_endpoint_rejects_out_of_range_port():
    with pytest.raises(DeviceRegistryError):
        normalize_endpoint("fw.example.invalid:70000")


# ---------------------------------------------------------------------------
# AC-2a: closed schema
# ---------------------------------------------------------------------------

def test_device_record_field_set_is_closed_dataclass():
    fields = {f for f in DeviceRecord.__dataclass_fields__}
    # no field named or shaped to carry a secret payload
    forbidden_names = {"password", "secret", "token", "private_key", "api_key"}
    assert fields.isdisjoint(forbidden_names)


def test_persisted_record_with_unrecognized_field_fails_closed(data_root):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.70")
    document = json.loads(_registry_path(data_root).read_text())
    document["devices"][0]["unexpected_field"] = "x"
    _registry_path(data_root).write_text(json.dumps(document))

    with pytest.raises(DeviceRegistryError):
        DeviceRegistry(data_root).list()


def test_persisted_record_with_missing_field_fails_closed(data_root):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.71")
    document = json.loads(_registry_path(data_root).read_text())
    del document["devices"][0]["credential_ref"]
    _registry_path(data_root).write_text(json.dumps(document))

    with pytest.raises(DeviceRegistryError):
        DeviceRegistry(data_root).list()


def test_enroll_rejects_unsupported_kwarg_structurally(data_root):
    registry = DeviceRegistry(data_root)
    with pytest.raises(TypeError):
        registry.enroll(endpoint="192.0.2.72", not_a_real_field="x")


# ---------------------------------------------------------------------------
# AC-2b: credential_ref is reference-only, format-validated, never resolved
# ---------------------------------------------------------------------------

def test_credential_ref_accepts_bounded_opaque_identifier(data_root):
    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint="192.0.2.80", credential_ref="cp-prod-1")
    assert record.credential_ref == "cp-prod-1"


@pytest.mark.parametrize("bad_ref", [
    "has spaces",
    "has/slash",
    "x" * 65,
    "",
])
def test_credential_ref_rejects_malformed_values(data_root, bad_ref):
    registry = DeviceRegistry(data_root)
    with pytest.raises(DeviceRegistryError):
        registry.enroll(endpoint="192.0.2.81", credential_ref=bad_ref)


def test_credential_ref_never_resolved_no_code_path_fetches_it(data_root):
    """PCP.1 defines no separate credential-payload field and resolves
    nothing -- proven structurally: DeviceRecord has no such field, and
    utils.device_registry never imports a credential-resolution module."""
    source = (ROOT / "utils" / "device_registry.py").read_text(encoding="utf-8")
    for forbidden in ("config.Config", "getpass", "requests", "paramiko"):
        assert forbidden not in source


def test_credential_ref_persisted_verbatim_is_not_a_secret_detection_guarantee(data_root):
    """AC-2b is explicit: the format check constrains shape, it cannot prove
    a supplied value is not itself a secret -- document that with a direct
    round-trip rather than assuming it."""
    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint="192.0.2.82", credential_ref="looks-like-a-ref")
    reloaded = DeviceRegistry(data_root).list()[0]
    assert reloaded.credential_ref == "looks-like-a-ref"


# ---------------------------------------------------------------------------
# AC-2c: free-text fields length-bounded and redaction-filtered
# ---------------------------------------------------------------------------

def test_tags_are_length_bounded(data_root):
    registry = DeviceRegistry(data_root)
    with pytest.raises(DeviceRegistryError):
        registry.enroll(endpoint="192.0.2.90", tags={"k": "v" * 300})


def test_tags_reject_control_characters(data_root):
    registry = DeviceRegistry(data_root)
    with pytest.raises(DeviceRegistryError):
        registry.enroll(endpoint="192.0.2.91", tags={"k": "line1\nline2"})


def test_tag_count_is_bounded(data_root):
    registry = DeviceRegistry(data_root)
    too_many = {f"k{i}": "v" for i in range(64)}
    with pytest.raises(DeviceRegistryError):
        registry.enroll(endpoint="192.0.2.92", tags=too_many)


# ---------------------------------------------------------------------------
# AC-3: lifecycle transitions -- exact table, unreachable states structural
# ---------------------------------------------------------------------------

def test_enroll_produces_enrolled_unverified(data_root):
    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint="192.0.2.100")
    assert record.state == "ENROLLED_UNVERIFIED"


def test_disable_transitions_enrolled_unverified_to_disabled(data_root):
    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint="192.0.2.101")
    disabled, already = registry.disable(record.device_id)
    assert disabled.state == "DISABLED"
    assert already is False


def test_no_code_path_reaches_retired_contact_verified_or_observed():
    """Structural (AC-3), like OP.0a AC-6: no code path in this module ever
    assigns these three states -- proven by scanning the module's own
    source rather than merely asserting current behavior."""
    source = (ROOT / "utils" / "device_registry.py").read_text(encoding="utf-8")
    for unreachable in ("RETIRED", "CONTACT_VERIFIED", "OBSERVED"):
        # allowed to appear only inside the frozenset/comment vocabulary and
        # the defensive fail-closed disable() branch -- never as an
        # assignment target (`state="X"` / `state = "X"`)
        assert f'state="{unreachable}"' not in source
        assert f"state = \"{unreachable}\"" not in source


def test_no_registry_enable_or_retire_cli_verb_exists():
    source = (ROOT / "application" / "cli.py").read_text(encoding="utf-8")
    assert "--registry-enable" not in source
    assert "--registry-retire" not in source


# ---------------------------------------------------------------------------
# AC-4: atomic persistence, RuntimeRoot-resident, repository separation
# ---------------------------------------------------------------------------

def test_persistence_is_runtime_root_resident_and_atomic(data_root):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.110")
    registry_path = _registry_path(data_root)
    assert registry_path.exists()
    # atomic tmp-then-replace leaves no lingering tmp artifact
    leftover = list(registry_path.parent.glob("*.tmp*"))
    assert leftover == []
    document = json.loads(registry_path.read_text())
    assert document["schema_version"] == dr.SCHEMA_VERSION
    assert len(document["devices"]) == 1


def test_data_root_equal_to_repository_root_is_refused():
    from utils.runtime_paths import discover_repository_root

    with pytest.raises(DeviceRegistryError):
        DeviceRegistry(discover_repository_root())


def test_data_root_nested_inside_repository_root_is_refused():
    from utils.runtime_paths import discover_repository_root

    nested = discover_repository_root() / "data"
    with pytest.raises(DeviceRegistryError):
        DeviceRegistry(nested)


# ---------------------------------------------------------------------------
# AC-5: never in the support bundle
# ---------------------------------------------------------------------------

def test_registry_and_lock_files_never_enumerated_by_support_bundle(data_root, monkeypatch):
    pytest.importorskip("paramiko")
    from utils import support_bundle

    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.120")
    # simulate a lock file left behind by a crashed holder (AC-14) so it
    # would be enumerated too, if anything ever enumerated data/state/*.
    _lock_path(data_root).parent.mkdir(parents=True, exist_ok=True)
    _lock_path(data_root).write_text(json.dumps({"pid": 1, "owner_token": "x"}))

    run_dir = data_root / "runs" / "20260101T000000Z"
    (run_dir / "stage").mkdir(parents=True, exist_ok=True)
    (run_dir / "raw").mkdir(parents=True, exist_ok=True)

    bundle_path = support_bundle.run_support_bundle(run_dir, data_root=data_root, output_root=data_root.parent / "output")

    import zipfile
    with zipfile.ZipFile(bundle_path) as zf:
        names = zf.namelist()
    assert not any("device_registry" in name for name in names)


# ---------------------------------------------------------------------------
# AC-6: CLI mode exclusivity, no vendor import, no credential, no socket
# ---------------------------------------------------------------------------

def test_registry_modes_mutually_exclusive_with_each_other():
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    args = parser.parse_args(["--registry-enroll", "--registry-endpoint", "192.0.2.130", "--registry-list"])
    with pytest.raises(SystemExit):
        validate_modes(args, parser)


def test_registry_enroll_requires_endpoint():
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    args = parser.parse_args(["--registry-enroll"])
    with pytest.raises(SystemExit):
        validate_modes(args, parser)


@pytest.mark.parametrize("other_flag", [
    "--repository-privacy-check",
    "--render-only",
    "--recovery-store-check",
    "--console",
    "--scheduler-once",
])
def test_registry_list_cannot_combine_with_other_modes(other_flag):
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    args = parser.parse_args(["--registry-list", other_flag])
    with pytest.raises(SystemExit):
        validate_modes(args, parser)


def test_show_endpoints_requires_registry_list():
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    args = parser.parse_args(["--show-endpoints"])
    with pytest.raises(SystemExit):
        validate_modes(args, parser)


def test_registry_vendor_hint_requires_registry_enroll():
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    args = parser.parse_args(["--registry-list", "--registry-vendor-hint", "checkpoint"])
    with pytest.raises(SystemExit):
        validate_modes(args, parser)


def test_registry_modes_are_valid_alone():
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    for argv in (
        ["--registry-enroll", "--registry-endpoint", "192.0.2.131"],
        ["--registry-list"],
        ["--registry-list", "--show-endpoints"],
        ["--registry-disable", "abc123"],
    ):
        args = parser.parse_args(argv)
        validate_modes(args, parser)  # must not raise


def _run_subprocess(code: str) -> str:
    result = subprocess.run([sys.executable, "-c", code], cwd=ROOT, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    return result.stdout


_FORBIDDEN_PREFIXES = ("checkpoint", "panorama", "configuration", "paramiko", "lxml", "requests")


def _forbidden(modules):
    return sorted(m for m in modules if m.split(".")[0] in _FORBIDDEN_PREFIXES)


@pytest.mark.parametrize("argv", [
    "['--registry-enroll', '--registry-endpoint', '192.0.2.140']",
    "['--registry-list']",
    "['--registry-disable', 'deadbeef']",
])
def test_registry_cli_modes_import_no_vendor_module(tmp_path, argv):
    runtime_root = tmp_path / "runtime"
    code = (
        "import contextlib, io, sys, json\n"
        "import main\n"
        "with contextlib.redirect_stdout(io.StringIO()):\n"
        "    try:\n"
        f"        main.main(['--runtime-root', {str(runtime_root)!r}] + {argv})\n"
        "    except SystemExit:\n"
        "        pass\n"
        "print(json.dumps(sorted(sys.modules)))\n"
    )
    modules = json.loads(_run_subprocess(code))
    assert _forbidden(modules) == []


def test_no_socket_connection_attempted_during_any_registry_operation(data_root, monkeypatch):
    def _forbidden_connect(*args, **kwargs):
        raise AssertionError("no socket connection may ever be attempted by the registry")

    monkeypatch.setattr(socket.socket, "connect", _forbidden_connect)
    monkeypatch.setattr(socket, "create_connection", _forbidden_connect)

    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint="192.0.2.150", credential_ref="ref-1")
    registry.list()
    registry.disable(record.device_id)


# ---------------------------------------------------------------------------
# AC-9: --registry-list output never includes the endpoint unless requested
# ---------------------------------------------------------------------------

def test_registry_list_hides_endpoint_by_default(capsys, data_root):
    from application.context import ApplicationContext
    from application.workflows.registry import registry_enroll, registry_list

    class _Paths:
        def __init__(self, root):
            self.data_root = root

    class _Args:
        registry_endpoint = "203.0.113.10"
        registry_vendor_hint = "unknown"
        registry_credential_profile = None
        registry_tag = None
        show_endpoints = False

    ctx = ApplicationContext(args=_Args(), parser=None, runtime_paths=_Paths(data_root))
    registry_enroll(ctx)
    capsys.readouterr()

    registry_list(ctx)
    out = capsys.readouterr().out
    assert "203.0.113.10" not in out
    assert "tags=" in out


def test_registry_list_shows_endpoint_with_show_endpoints_flag(capsys, data_root):
    from application.context import ApplicationContext
    from application.workflows.registry import registry_enroll, registry_list

    class _Paths:
        def __init__(self, root):
            self.data_root = root

    class _EnrollArgs:
        registry_endpoint = "203.0.113.11"
        registry_vendor_hint = "unknown"
        registry_credential_profile = None
        registry_tag = None

    class _ListArgs:
        show_endpoints = True

    ctx = ApplicationContext(args=_EnrollArgs(), parser=None, runtime_paths=_Paths(data_root))
    registry_enroll(ctx)
    capsys.readouterr()

    ctx.args = _ListArgs()
    registry_list(ctx)
    out = capsys.readouterr().out
    assert "203.0.113.11" in out


# ---------------------------------------------------------------------------
# AC-10: concurrent enrollments produce at most one record/device_id
# ---------------------------------------------------------------------------

def test_concurrent_enroll_fails_closed_on_lock_contention_before_any_write(data_root):
    lock_path = _lock_path(data_root)
    token = dr._acquire_lock(lock_path)  # simulate a concurrent holder
    try:
        registry = DeviceRegistry(data_root)
        with pytest.raises(DeviceRegistryLockError):
            registry.enroll(endpoint="192.0.2.160")
        assert not _registry_path(data_root).exists()
    finally:
        dr._release_lock(lock_path, token)


def test_concurrent_enroll_second_writer_refused_by_ordinary_duplicate_check(data_root):
    """The other AC-10 branch: the non-winning invocation acquires the lock
    only after the winner's commit, and is refused by AC-1b's ordinary
    duplicate-detection check -- never a second record."""
    registry_a = DeviceRegistry(data_root)
    winner = registry_a.enroll(endpoint="192.0.2.161")

    registry_b = DeviceRegistry(data_root)  # a second process's fresh instance
    with pytest.raises(DeviceRegistryError) as excinfo:
        registry_b.enroll(endpoint="192.0.2.161")
    assert winner.device_id in str(excinfo.value)

    all_records = DeviceRegistry(data_root).list()
    matching = [r for r in all_records if r.endpoint == "192.0.2.161"]
    assert len(matching) == 1
    assert len({r.device_id for r in matching}) == 1


# ---------------------------------------------------------------------------
# AC-11: corrupt / unsupported persisted data fails closed, whole-document
# ---------------------------------------------------------------------------

def test_unreadable_file_fails_closed(data_root):
    path = _registry_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("not json at all {{{")
    with pytest.raises(DeviceRegistryError):
        DeviceRegistry(data_root).list()


def test_missing_schema_version_fails_closed(data_root):
    path = _registry_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"devices": []}))
    with pytest.raises(DeviceRegistryError):
        DeviceRegistry(data_root).list()


def test_wrong_schema_version_fails_closed(data_root):
    path = _registry_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"schema_version": 999, "devices": []}))
    with pytest.raises(DeviceRegistryError):
        DeviceRegistry(data_root).list()


def test_non_list_devices_fails_closed(data_root):
    path = _registry_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"schema_version": 1, "devices": {}}))
    with pytest.raises(DeviceRegistryError):
        DeviceRegistry(data_root).list()


def test_one_malformed_record_fails_the_whole_document_not_row_by_row(data_root):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.170")
    document = json.loads(_registry_path(data_root).read_text())
    document["devices"].append({"garbage": True})
    _registry_path(data_root).write_text(json.dumps(document))

    with pytest.raises(DeviceRegistryError):
        DeviceRegistry(data_root).list()  # not [the one good record]


def test_missing_registry_file_is_the_empty_registry_not_an_error(data_root):
    assert DeviceRegistry(data_root).list() == []


def test_corrupt_data_fails_closed_from_every_entry_point(data_root):
    path = _registry_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("{{{not json")

    registry = DeviceRegistry(data_root)
    with pytest.raises(DeviceRegistryError):
        registry.list()
    with pytest.raises(DeviceRegistryError):
        registry.enroll(endpoint="192.0.2.171")
    with pytest.raises(DeviceRegistryError):
        registry.disable("anything")


# ---------------------------------------------------------------------------
# AC-12: repeated-operation / idempotency behavior
# ---------------------------------------------------------------------------

def test_duplicate_enroll_always_refused_never_idempotent_success(data_root):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.180")
    for _ in range(3):
        with pytest.raises(DeviceRegistryError):
            registry.enroll(endpoint="192.0.2.180")
    assert len(registry.list()) == 1


def test_disable_is_idempotent_on_already_disabled_no_write(data_root, monkeypatch):
    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint="192.0.2.181")
    registry.disable(record.device_id)

    calls = []
    original_save = registry._save_document
    monkeypatch.setattr(registry, "_save_document", lambda doc: (calls.append(1), original_save(doc))[1])

    disabled_again, already = registry.disable(record.device_id)
    assert already is True
    assert disabled_again.state == "DISABLED"
    assert calls == [], "an already-DISABLED disable must not write"


def test_disable_unknown_id_is_a_distinct_failure_not_a_no_op(data_root):
    registry = DeviceRegistry(data_root)
    with pytest.raises(DeviceRegistryError, match="no such device"):
        registry.disable("does-not-exist")


def test_registry_list_is_trivially_idempotent(data_root):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.182")
    first = [r.device_id for r in registry.list()]
    second = [r.device_id for r in registry.list()]
    assert first == second


# ---------------------------------------------------------------------------
# AC-13: lock contention fails closed immediately, before load/validate/write
# ---------------------------------------------------------------------------

def test_lock_contention_fails_closed_before_any_load_or_write(data_root):
    lock_path = _lock_path(data_root)
    token = dr._acquire_lock(lock_path)
    try:
        registry = DeviceRegistry(data_root)
        with pytest.raises(DeviceRegistryLockError):
            registry.disable("anything")
        assert not _registry_path(data_root).exists()
    finally:
        dr._release_lock(lock_path, token)


def test_lock_contention_on_enroll_never_generates_a_device_id(monkeypatch, data_root):
    lock_path = _lock_path(data_root)
    token = dr._acquire_lock(lock_path)
    calls = []
    original_uuid4 = dr.uuid.uuid4
    monkeypatch.setattr(dr.uuid, "uuid4", lambda: (calls.append(1), original_uuid4())[1])
    try:
        registry = DeviceRegistry(data_root)
        with pytest.raises(DeviceRegistryLockError):
            registry.enroll(endpoint="192.0.2.190")
        assert calls == []
    finally:
        dr._release_lock(lock_path, token)


# ---------------------------------------------------------------------------
# AC-14: no automatic staleness detection/recovery; manual only
# ---------------------------------------------------------------------------

def test_no_automatic_stale_lock_recovery_source_scan():
    source = (ROOT / "utils" / "device_registry.py").read_text(encoding="utf-8")
    for forbidden in ("psutil", "os.kill", "pid_exists", "time.time() -"):
        assert forbidden not in source


def test_crashed_holder_lock_blocks_every_subsequent_mutation_indefinitely(data_root):
    lock_path = _lock_path(data_root)
    dr._acquire_lock(lock_path)  # simulates a crashed holder: never released

    registry = DeviceRegistry(data_root)
    for _ in range(3):
        with pytest.raises(DeviceRegistryLockError):
            registry.enroll(endpoint="192.0.2.200")
    assert lock_path.exists()  # never auto-cleared


def test_manual_deletion_of_a_stale_lock_unblocks_mutation(data_root):
    lock_path = _lock_path(data_root)
    dr._acquire_lock(lock_path)  # simulate a crash

    registry = DeviceRegistry(data_root)
    with pytest.raises(DeviceRegistryLockError):
        registry.enroll(endpoint="192.0.2.201")

    lock_path.unlink()  # the documented manual human recovery step
    record = registry.enroll(endpoint="192.0.2.201")
    assert record.state == "ENROLLED_UNVERIFIED"


def test_every_non_crash_exit_releases_the_lock(data_root):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.202")  # success path
    assert not _lock_path(data_root).exists()

    with pytest.raises(DeviceRegistryError):
        registry.enroll(endpoint="192.0.2.202")  # a refusal (duplicate) path
    assert not _lock_path(data_root).exists()

    with pytest.raises(DeviceRegistryError):
        registry.disable("does-not-exist")  # a raised DeviceRegistryError path
    assert not _lock_path(data_root).exists()


# ---------------------------------------------------------------------------
# AC-15: instance-safe lock release (owner_token equality)
# ---------------------------------------------------------------------------

def test_release_deletes_only_when_owner_token_matches(data_root):
    lock_path = _lock_path(data_root)
    token = dr._acquire_lock(lock_path)
    dr._release_lock(lock_path, token)
    assert not lock_path.exists()


def test_release_never_deletes_a_different_writers_recreated_lock(data_root):
    lock_path = _lock_path(data_root)
    original_token = dr._acquire_lock(lock_path)

    # A human believed the holder dead and deleted the lock; a different
    # writer's mutation created a fresh instance under the same path.
    lock_path.unlink()
    new_token = dr._acquire_lock(lock_path)
    assert new_token != original_token

    # The original (slow, non-crashed) holder's own release now runs.
    dr._release_lock(lock_path, original_token)

    assert lock_path.exists(), "release must never delete a different writer's active lock"
    current = json.loads(lock_path.read_text())
    assert current["owner_token"] == new_token


def test_release_with_missing_lock_file_is_a_silent_no_op(data_root):
    lock_path = _lock_path(data_root)
    dr._release_lock(lock_path, "some-token")  # never created -- must not raise


def test_lock_content_carries_pid_hostname_timestamp_and_owner_token(data_root):
    lock_path = _lock_path(data_root)
    token = dr._acquire_lock(lock_path)
    try:
        content = json.loads(lock_path.read_text())
        assert content["owner_token"] == token
        assert isinstance(content["pid"], int)
        assert isinstance(content["hostname"], str)
        assert "acquired_at_utc" in content
    finally:
        dr._release_lock(lock_path, token)


def test_lock_uses_atomic_exclusive_create_primitive():
    source = (ROOT / "utils" / "device_registry.py").read_text(encoding="utf-8")
    assert "O_CREAT" in source and "O_EXCL" in source
    # no third-party locking library
    for forbidden in ("filelock", "portalocker", "fasteners"):
        assert forbidden not in source


def test_registry_list_never_takes_the_lock(data_root, monkeypatch):
    registry = DeviceRegistry(data_root)
    registry.enroll(endpoint="192.0.2.210")

    def _forbidden_acquire(*args, **kwargs):
        raise AssertionError("--registry-list must never take the mutation lock")

    monkeypatch.setattr(dr, "_acquire_lock", _forbidden_acquire)
    registry.list()  # must not raise
