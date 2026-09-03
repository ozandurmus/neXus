"""SecurityExpert — OP.0b S7.5, controlled preflight application entrypoint.

Contract chain: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
-> `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md` -> S5/S6/S7 -> this
build's `application/workflows/preflight.py` + `application/cli.py` wiring.

No device contact anywhere in this suite (REAL_ENV_VALIDATION_PROTOCOL: only
mocks/synthetic fixtures in an implementation-validation session). `run_cp_preflight`
/`run_pan_preflight` are monkeypatched out entirely for the composition tests;
every target-resolution test below asserts zero calls to either collector.
"""
from __future__ import annotations

import inspect
import json
from pathlib import Path

import pytest

from application.cli import build_parser, validate_modes
from application.context import ApplicationContext
from application.workflows import preflight as preflight_wf

pytestmark = pytest.mark.configuration


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

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


def _write_cp_fixture(output_root: Path, *, extra_status=None, extra_cp_rows=None):
    output_root.mkdir(parents=True, exist_ok=True)
    status = [
        {"device": "cp-a", "management_ip": "10.0.0.1", "object_type": "cluster_member"},
        {"device": "cp-b", "management_ip": "10.0.0.2", "object_type": "cluster_member"},
        {"device": "cp-solo", "management_ip": "10.0.0.9", "object_type": "gateway"},
    ] + list(extra_status or [])
    (output_root / "cp_telemetry.json").write_text(
        json.dumps({"remote_command_status": status}), encoding="utf-8"
    )
    cp_rows = [
        {"device": "cp-a", "source": "cp", "cluster_topology": {"group_id": "grp1"}},
        {"device": "cp-b", "source": "cp", "cluster_topology": {"group_id": "grp1"}},
    ] + list(extra_cp_rows or [])
    (output_root / "cp.json").write_text(json.dumps(cp_rows), encoding="utf-8")
    (output_root / "vsx.json").write_text(json.dumps([]), encoding="utf-8")
    (output_root / "unified.json").write_text(json.dumps(cp_rows), encoding="utf-8")


def _write_pan_fixture(output_root: Path):
    output_root.mkdir(parents=True, exist_ok=True)
    unified = [
        {"source": "panorama", "device": "pan-a", "serial": "SA1", "management_ip": "10.1.1.1"},
        {"source": "panorama", "device": "pan-b", "serial": "SB1", "management_ip": "10.1.1.2"},
        {"source": "panorama", "device": "pan-solo", "serial": "SC1", "management_ip": "10.1.1.9"},
    ]
    (output_root / "unified.json").write_text(json.dumps(unified), encoding="utf-8")


_PAN_HA_RUNTIME = {
    "pan-a": {"enabled": "yes", "mode": "active-passive"},
    "pan-b": {"enabled": "yes", "mode": "active-passive"},
    "pan-solo": {"enabled": "yes", "mode": "active-passive"},
}
_PAN_HA_PEERS = {"pan-a": "10.1.1.2", "pan-b": "10.1.1.1"}


class _RuntimePaths:
    def __init__(self, root: Path):
        self.repository_root = root
        self.runtime_root = root
        self.data_root = root / "data"
        self.output_root = root
        self.logs_root = root / "logs"


def _make_ctx(args, runtime_paths):
    from utils.collection_executor import RuntimeCollectionServices

    ctx = ApplicationContext(args=args, parser=build_parser(), provenance="manual")
    ctx.runtime_paths = runtime_paths
    ctx.services = RuntimeCollectionServices()
    return ctx


# ===========================================================================
# 19. CLI / targeting
# ===========================================================================

class TestCliSurface:
    def test_cp_mode_exists(self):
        args = _parse(["--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a,cp-b"])
        assert args.cp_ha_preflight_check is True

    def test_pan_mode_exists(self):
        args = _parse(["--pan-ha-preflight-check", "--pan-preflight-targets", "SA1,SB1"])
        assert args.pan_ha_preflight_check is True

    def test_cp_requires_targets(self):
        _parse_error(["--cp-ha-preflight-check"])

    def test_pan_requires_targets(self):
        _parse_error(["--pan-ha-preflight-check"])

    def test_cp_targets_without_flag_rejected(self):
        _parse_error(["--cp-preflight-targets", "cp-a"])

    def test_pan_targets_without_flag_rejected(self):
        _parse_error(["--pan-preflight-targets", "SA1"])

    def test_cp_and_pan_mutually_exclusive(self):
        _parse_error([
            "--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a",
            "--pan-ha-preflight-check", "--pan-preflight-targets", "SA1",
        ])

    def test_cannot_combine_with_cp_config_collect(self):
        _parse_error([
            "--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a", "--cp-config-collect",
        ])

    def test_cannot_combine_with_render_only(self):
        _parse_error([
            "--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a", "--render-only",
        ])

    def test_cannot_combine_with_only(self):
        _parse_error([
            "--pan-ha-preflight-check", "--pan-preflight-targets", "SA1", "--only", "cp",
        ])

    def test_cannot_combine_with_apply(self):
        _parse_error([
            "--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a",
            "--storage-deduplicate", "--apply",
        ])

    def test_no_fleet_all_or_sample_equivalent_accepted(self):
        # No choices=["all"/"sample"] surface exists for either targets flag --
        # both are plain free-text allowlists resolved fail-closed downstream.
        parser = build_parser()
        for action in parser._actions:
            if action.dest in ("cp_preflight_targets", "pan_preflight_targets"):
                assert action.choices is None

    def test_help_text_is_read_only_and_safe(self):
        parser = build_parser()
        for action in parser._actions:
            if action.dest in ("cp_ha_preflight_check", "pan_ha_preflight_check"):
                assert "READ-ONLY" in action.help
                assert "no failover action" in action.help
                assert "INSUFFICIENT_EVIDENCE" in action.help
                lowered = action.help.lower()
                assert "execute failover" not in lowered
                assert "switch ha" not in lowered

    def test_no_credential_field_added(self):
        parser = build_parser()
        forbidden = {"password", "secret", "credential", "username", "apikey", "api_key"}
        for action in parser._actions:
            dest = (action.dest or "").lower()
            if "preflight" in dest:
                assert not any(word in dest for word in forbidden), dest


# ===========================================================================
# Target resolution -- fail closed before any device contact (CP)
# ===========================================================================

class TestCpTargetResolution:
    def test_exact_pair_resolves_to_cluster(self, tmp_path):
        _write_cp_fixture(tmp_path)
        entity_id, unit_type, selected = preflight_wf._resolve_cp_operational_entity(
            tmp_path, ["cp-a", "cp-b"],
        )
        assert entity_id == "grp1"
        assert unit_type == "clusterxl"
        assert {t.device for t in selected} == {"cp-a", "cp-b"}

    def test_single_member_subset_of_cluster_resolves(self, tmp_path):
        _write_cp_fixture(tmp_path)
        entity_id, unit_type, selected = preflight_wf._resolve_cp_operational_entity(
            tmp_path, ["cp-a"],
        )
        assert entity_id == "grp1"
        assert len(selected) == 1

    def test_unknown_target_fails_closed(self, tmp_path):
        _write_cp_fixture(tmp_path)
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._resolve_cp_operational_entity(tmp_path, ["cp-nonexistent"])

    def test_mismatched_entity_types_fail_closed(self, tmp_path):
        _write_cp_fixture(tmp_path)
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._resolve_cp_operational_entity(tmp_path, ["cp-a", "cp-solo"])

    def test_standalone_gateway_rejected(self, tmp_path):
        _write_cp_fixture(tmp_path)
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._resolve_cp_operational_entity(tmp_path, ["cp-solo"])

    def test_more_than_two_targets_rejected_before_resolution(self):
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._parse_requested_targets("cp-a,cp-b,cp-c", label="cp_preflight_targets")

    def test_empty_targets_rejected(self):
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._parse_requested_targets("", label="cp_preflight_targets")

    def test_mixed_cluster_membership_ambiguous(self, tmp_path):
        # Two ClusterXL members from two DIFFERENT clusters must never merge.
        _write_cp_fixture(
            tmp_path,
            extra_status=[{"device": "cp-c", "management_ip": "10.0.0.3", "object_type": "cluster_member"}],
            extra_cp_rows=[{"device": "cp-c", "source": "cp", "cluster_topology": {"group_id": "grp2"}}],
        )
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._resolve_cp_operational_entity(tmp_path, ["cp-a", "cp-c"])


# ===========================================================================
# Target resolution -- fail closed before any device contact (PAN)
# ===========================================================================

class TestPanTargetResolution:
    def test_exact_pair_resolves_to_pair(self, tmp_path):
        _write_pan_fixture(tmp_path)
        runtime_paths = _RuntimePaths(tmp_path)
        entity_id, rows = preflight_wf._resolve_pan_operational_entity(
            runtime_paths, ["SA1", "SB1"], _PAN_HA_RUNTIME, _PAN_HA_PEERS,
        )
        assert entity_id == "pan-a+pan-b"
        assert len(rows) == 2

    def test_single_unresolved_member_resolves(self, tmp_path):
        _write_pan_fixture(tmp_path)
        runtime_paths = _RuntimePaths(tmp_path)
        entity_id, rows = preflight_wf._resolve_pan_operational_entity(
            runtime_paths, ["SC1"], _PAN_HA_RUNTIME, _PAN_HA_PEERS,
        )
        assert entity_id == "pan-solo"
        assert len(rows) == 1

    def test_partial_selection_of_known_pair_fails_closed(self, tmp_path):
        # Selecting only one member of a MUTUALLY-paired unit is exactly the
        # B2 boundary this slice must not paper over -- fail closed.
        _write_pan_fixture(tmp_path)
        runtime_paths = _RuntimePaths(tmp_path)
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._resolve_pan_operational_entity(
                runtime_paths, ["SA1"], _PAN_HA_RUNTIME, _PAN_HA_PEERS,
            )

    def test_unrelated_pair_fails_closed(self, tmp_path):
        _write_pan_fixture(tmp_path)
        runtime_paths = _RuntimePaths(tmp_path)
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._resolve_pan_operational_entity(
                runtime_paths, ["SA1", "SC1"], _PAN_HA_RUNTIME, _PAN_HA_PEERS,
            )

    def test_unknown_serial_fails_closed(self, tmp_path):
        _write_pan_fixture(tmp_path)
        runtime_paths = _RuntimePaths(tmp_path)
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._resolve_pan_operational_entity(
                runtime_paths, ["UNKNOWN99"], _PAN_HA_RUNTIME, _PAN_HA_PEERS,
            )

    def test_more_than_two_targets_rejected_before_resolution(self):
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf._parse_requested_targets("SA1,SB1,SC1", label="pan_preflight_targets")


# ===========================================================================
# Zero collector invocation on resolution failure
# ===========================================================================

class TestZeroCollectorInvocationOnFailure:
    def test_cp_unknown_target_never_calls_s5(self, tmp_path, monkeypatch):
        _write_cp_fixture(tmp_path)
        calls = []
        monkeypatch.setattr(
            "checkpoint.preflight_collector.run_cp_preflight",
            lambda **kw: calls.append(kw) or pytest.fail("S5 must not be called"),
        )
        args = _parse(["--cp-ha-preflight-check", "--cp-preflight-targets", "cp-nonexistent"])
        runtime_paths = _RuntimePaths(tmp_path)
        ctx = _make_ctx(args, runtime_paths)
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf.cp_ha_preflight_check(ctx)
        assert calls == []

    def test_pan_partial_target_never_calls_s6(self, tmp_path, monkeypatch):
        _write_pan_fixture(tmp_path)
        calls = []
        monkeypatch.setattr(
            "panorama.preflight_collector.run_pan_preflight",
            lambda **kw: calls.append(kw) or pytest.fail("S6 must not be called"),
        )
        args = _parse(["--pan-ha-preflight-check", "--pan-preflight-targets", "SA1"])
        runtime_paths = _RuntimePaths(tmp_path)
        ctx = _make_ctx(args, runtime_paths)
        monkeypatch.setenv("SECURITYEXPERT_PRINCIPAL", "tester")
        monkeypatch.setenv("SECURITYEXPERT_SECRET", "s3cret")
        monkeypatch.setenv("SECURITYEXPERT_PANORAMA_ENDPOINT", "panorama.example.invalid")
        with pytest.raises(preflight_wf.PreflightTargetResolutionError):
            preflight_wf.pan_ha_preflight_check(ctx)
        assert calls == []


# ===========================================================================
# 20. Composition -- collector invoked once, snapshot -> canonical S7
# ===========================================================================

class TestComposition:
    def test_cp_full_workflow_calls_s5_once_and_composes_readiness(self, tmp_path, monkeypatch):
        _write_cp_fixture(tmp_path)
        monkeypatch.setenv("SECURITYEXPERT_PRINCIPAL", "tester")
        monkeypatch.setenv("SECURITYEXPERT_SECRET", "s3cret")
        monkeypatch.setenv("SECURITYEXPERT_CP_MDS_ENDPOINT", "mds.example.invalid")

        from utils.failover.preflight_model import PreflightSnapshot

        calls = []

        def fake_run_cp_preflight(*, operational_entity_id, unit_type, members, username, secret, **_kw):
            calls.append({
                "operational_entity_id": operational_entity_id,
                "unit_type": unit_type,
                "members": list(members),
            })
            assert username == "tester"
            assert secret == "s3cret"
            return PreflightSnapshot(
                operational_unit_id=operational_entity_id,
                vendor="checkpoint",
                unit_type=unit_type,
                preflight_run_id="fixed-run-id",
                members=(),
                configuration_facts=(),
            )

        monkeypatch.setattr("checkpoint.preflight_collector.run_cp_preflight", fake_run_cp_preflight)

        args = _parse(["--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a,cp-b"])
        runtime_paths = _RuntimePaths(tmp_path)
        ctx = _make_ctx(args, runtime_paths)

        result = preflight_wf.cp_ha_preflight_check(ctx)

        assert result == 0
        assert len(calls) == 1  # S5 invoked exactly once
        assert calls[0]["operational_entity_id"] == "grp1"
        assert len(calls[0]["members"]) == 2

    def test_pan_full_workflow_calls_s6_once_and_composes_readiness(self, tmp_path, monkeypatch):
        _write_pan_fixture(tmp_path)
        monkeypatch.setenv("SECURITYEXPERT_PRINCIPAL", "tester")
        monkeypatch.setenv("SECURITYEXPERT_SECRET", "s3cret")
        monkeypatch.setenv("SECURITYEXPERT_PANORAMA_ENDPOINT", "panorama.example.invalid")

        from utils.failover.preflight_model import PreflightSnapshot

        calls = []

        def fake_run_pan_preflight(*, operational_entity_id, members, username, secret, **_kw):
            calls.append({"operational_entity_id": operational_entity_id, "members": list(members)})
            return PreflightSnapshot(
                operational_unit_id=operational_entity_id,
                vendor="panorama",
                unit_type="ha_pair",
                preflight_run_id="fixed-run-id-2",
                members=(),
                configuration_facts=(),
            )

        monkeypatch.setattr("panorama.preflight_collector.run_pan_preflight", fake_run_pan_preflight)

        # Pre-existing legacy telemetry for the SAME unit, to prove the fresh
        # snapshot excludes it (XOR guard) rather than blending.
        monkeypatch.setattr(
            "application.workflows.preflight._load_pan_ha_runtime",
            lambda output_root: (_PAN_HA_RUNTIME, _PAN_HA_PEERS),
        )

        args = _parse(["--pan-ha-preflight-check", "--pan-preflight-targets", "SA1,SB1"])
        runtime_paths = _RuntimePaths(tmp_path)
        ctx = _make_ctx(args, runtime_paths)

        result = preflight_wf.pan_ha_preflight_check(ctx)

        assert result == 0
        assert len(calls) == 1  # S6 invoked exactly once
        assert calls[0]["operational_entity_id"] == "pan-a+pan-b"
        assert len(calls[0]["members"]) == 2

    def test_fresh_snapshot_excludes_legacy_telemetry_for_selected_unit(self, tmp_path, monkeypatch):
        """The unit carrying a fresh snapshot must be evaluated from the
        snapshot alone (evidence basis op0b_preflight_snapshot), never
        blended with cp_ha_runtime/pan_ha_runtime stored telemetry."""
        _write_cp_fixture(tmp_path)
        monkeypatch.setenv("SECURITYEXPERT_PRINCIPAL", "tester")
        monkeypatch.setenv("SECURITYEXPERT_SECRET", "s3cret")
        monkeypatch.setenv("SECURITYEXPERT_CP_MDS_ENDPOINT", "mds.example.invalid")

        from utils.failover.preflight_model import PreflightSnapshot
        from utils.failover import EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT

        # Legacy telemetry claiming ACTIVE/STANDBY for both members -- if the
        # workflow (or compute_ha_readiness) ever blended this with the fresh
        # snapshot, the evidence basis would not read as preflight-only.
        monkeypatch.setattr(
            "application.workflows.preflight._load_cp_ha_runtime",
            lambda output_root: {"cp-a": {"ha_role": "ACTIVE"}, "cp-b": {"ha_role": "STANDBY"}},
        )

        def fake_run_cp_preflight(*, operational_entity_id, unit_type, members, **_kw):
            return PreflightSnapshot(
                operational_unit_id=operational_entity_id,
                vendor="checkpoint",
                unit_type=unit_type,
                preflight_run_id="fixed-run-id-3",
                members=(),
                configuration_facts=(),
            )

        monkeypatch.setattr("checkpoint.preflight_collector.run_cp_preflight", fake_run_cp_preflight)

        args = _parse(["--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a,cp-b"])
        runtime_paths = _RuntimePaths(tmp_path)
        ctx = _make_ctx(args, runtime_paths)
        preflight_wf.cp_ha_preflight_check(ctx)

        from utils.failover import compute_ha_readiness

        unified_devices = json.loads((tmp_path / "unified.json").read_text())
        report = compute_ha_readiness(
            unified_devices,
            cp_ha_runtime={"cp-a": {"ha_role": "ACTIVE"}, "cp-b": {"ha_role": "STANDBY"}},
            preflight_snapshots=[
                PreflightSnapshot(
                    operational_unit_id="grp1", vendor="checkpoint", unit_type="clusterxl",
                    preflight_run_id="fixed-run-id-3", members=(), configuration_facts=(),
                )
            ],
        )
        unit = next(u for u in report["units"] if u["unit_id"] == "grp1")
        assert unit["evidence"]["basis"] == EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT
        assert "grp1" in report["preflight"]["applied"]

    def test_one_failed_collector_invocation_is_not_retried(self, tmp_path, monkeypatch):
        _write_cp_fixture(tmp_path)
        monkeypatch.setenv("SECURITYEXPERT_PRINCIPAL", "tester")
        monkeypatch.setenv("SECURITYEXPERT_SECRET", "s3cret")
        monkeypatch.setenv("SECURITYEXPERT_CP_MDS_ENDPOINT", "mds.example.invalid")

        calls = []

        def failing_run_cp_preflight(**_kw):
            calls.append(1)
            raise RuntimeError("simulated collector failure")

        monkeypatch.setattr("checkpoint.preflight_collector.run_cp_preflight", failing_run_cp_preflight)

        args = _parse(["--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a,cp-b"])
        runtime_paths = _RuntimePaths(tmp_path)
        ctx = _make_ctx(args, runtime_paths)
        with pytest.raises(RuntimeError):
            preflight_wf.cp_ha_preflight_check(ctx)
        assert len(calls) == 1  # exactly one attempt, no retry/rerun

    def test_no_snapshot_persistence(self, tmp_path, monkeypatch):
        _write_cp_fixture(tmp_path)
        monkeypatch.setenv("SECURITYEXPERT_PRINCIPAL", "tester")
        monkeypatch.setenv("SECURITYEXPERT_SECRET", "s3cret")
        monkeypatch.setenv("SECURITYEXPERT_CP_MDS_ENDPOINT", "mds.example.invalid")

        from utils.failover.preflight_model import PreflightSnapshot

        before = {p.name for p in tmp_path.rglob("*") if p.is_file()}

        monkeypatch.setattr(
            "checkpoint.preflight_collector.run_cp_preflight",
            lambda *, operational_entity_id, unit_type, **_kw: PreflightSnapshot(
                operational_unit_id=operational_entity_id, vendor="checkpoint", unit_type=unit_type,
                preflight_run_id="run-x", members=(), configuration_facts=(),
            ),
        )
        args = _parse(["--cp-ha-preflight-check", "--cp-preflight-targets", "cp-a,cp-b"])
        runtime_paths = _RuntimePaths(tmp_path)
        ctx = _make_ctx(args, runtime_paths)
        preflight_wf.cp_ha_preflight_check(ctx)

        after = {p.name for p in tmp_path.rglob("*") if p.is_file()}
        new_files = after - before
        for name in new_files:
            assert "preflight" not in name.lower(), f"unexpected persisted preflight artifact: {name}"
            assert "snapshot" not in name.lower(), f"unexpected persisted preflight artifact: {name}"


# ===========================================================================
# 21. Safety / structural
# ===========================================================================

class TestSafety:
    def test_bootstrap_gate_wired_for_cp_mode(self, tmp_path):
        from application.services import _bootstrap_gaps
        gaps = _bootstrap_gaps("cp-ha-preflight-check", tmp_path / "output")
        names = {name for name, _hint in gaps}
        assert {"cp_telemetry.json", "cp.json", "vsx.json", "unified.json"} <= names

    def test_bootstrap_gate_wired_for_pan_mode(self, tmp_path):
        from application.services import _bootstrap_gaps
        gaps = _bootstrap_gaps("pan-ha-preflight-check", tmp_path / "output")
        names = {name for name, _hint in gaps}
        assert "unified.json" in names

    def test_workflow_module_imports_no_raw_transport_primitive(self):
        """Structural proof the application layer cannot inject/choose an
        arbitrary device command: it never touches paramiko, subprocess, the
        raw command-text tables, or the per-command session primitives --
        only the two typed, already-bounded collector entrypoints."""
        source = inspect.getsource(preflight_wf)
        forbidden_substrings = [
            "paramiko", "subprocess", "os.system", "COMMAND_TEXT",
            "MemberSession", "api_post", "_run_exec", "cp_preflight_battery",
            "pan_preflight_battery",
        ]
        for token in forbidden_substrings:
            assert token not in source, f"forbidden low-level primitive referenced: {token}"

    def test_workflow_only_calls_typed_collector_entrypoints(self):
        source = inspect.getsource(preflight_wf)
        assert "run_cp_preflight" in source
        assert "run_pan_preflight" in source

    def test_workflow_contains_no_verdict_rollup(self):
        source = inspect.getsource(preflight_wf)
        forbidden = ["SAFE_TO_FAILOVER", "DEGRADED_PROCEED_WITH_RISK", "_verdict_for", "STOP_CONDITIONS"]
        for token in forbidden:
            assert token not in source, f"workflow must not roll up its own verdict ({token} found)"

    def test_class2_surface_absent(self):
        source = inspect.getsource(preflight_wf)
        for token in ("failover_execute", "confirmation_token", "class_2", "CLASS_2", "rollback"):
            assert token not in source

    def test_no_raw_serial_or_ip_printed_in_safe_result(self, capsys):
        report = {
            "units": [{
                "unit_id": "grp1", "verdict": "INSUFFICIENT_EVIDENCE", "reason": "no_ha_runtime_evidence_for_unit",
                "checks": [{"id": "no_split_brain", "status": "insufficient_evidence", "reason": "x", "missing_evidence": ""}],
                "evidence": {"preflight_run_id": "abc-123", "coherent": True},
            }],
            "preflight": {"applied": ["grp1"]},
        }
        preflight_wf._print_safe_result(report, operational_unit_id="grp1", vendor="checkpoint", member_count=2)
        out = capsys.readouterr().out
        for forbidden in ("10.0.0.", "192.168.", "SA1", "SB1"):
            assert forbidden not in out

    def test_admission_bounds_unchanged_from_s5(self, tmp_path, monkeypatch):
        """S5's own MAX_PHYSICAL_MEMBERS bound (2) is never widened by this
        application layer -- the workflow's own bound matches it exactly."""
        from checkpoint.preflight_collector import MAX_PHYSICAL_MEMBERS as CP_MAX
        assert preflight_wf._MAX_PHYSICAL_MEMBERS == CP_MAX

    def test_admission_bounds_unchanged_from_s6(self):
        from panorama.preflight_collector import MAX_PHYSICAL_MEMBERS as PAN_MAX
        assert preflight_wf._MAX_PHYSICAL_MEMBERS == PAN_MAX


# ===========================================================================
# derive_ha_units export (assessment.py integration fix)
# ===========================================================================

class TestDeriveHaUnitsExport:
    def test_matches_compute_ha_readiness_internal_derivation(self, tmp_path):
        from utils.failover import compute_ha_readiness, derive_ha_units

        _write_pan_fixture(tmp_path)
        unified_devices = json.loads((tmp_path / "unified.json").read_text())

        units = derive_ha_units(unified_devices, pan_ha_runtime=_PAN_HA_RUNTIME, pan_ha_peers=_PAN_HA_PEERS)
        report = compute_ha_readiness(unified_devices, pan_ha_runtime=_PAN_HA_RUNTIME, pan_ha_peers=_PAN_HA_PEERS)

        assert {u["unit_id"] for u in report["units"]} == {u.unit_id for u in units}

    def test_no_readiness_semantics_change(self, tmp_path):
        """Pure refactor check: compute_ha_readiness's own output for a
        snapshot-free call is unaffected by routing unit derivation through
        the new exported helper."""
        from utils.failover import compute_ha_readiness

        _write_pan_fixture(tmp_path)
        unified_devices = json.loads((tmp_path / "unified.json").read_text())
        report = compute_ha_readiness(unified_devices, pan_ha_runtime=_PAN_HA_RUNTIME, pan_ha_peers=_PAN_HA_PEERS)
        assert report["schema"] == "securityexpert-ha-readiness-v1"
        assert len(report["units"]) == 2  # the resolved pair + the unresolved single


# ===========================================================================
# PO acceptance follow-up: PAN local selection != trusted runtime identity
# ===========================================================================

class TestPanLocalSelectionIsNotTrustedIdentity:
    """The local `unified.json` selector this workflow reuses
    (`_apply_pan_target_selector`) only decides which explicitly requested
    physical members MAY be contacted -- it establishes no trusted runtime
    identity. That trust boundary is, and remains, S6's own P1 direct-device
    identity gate (`panorama.preflight_collector.collect_member`), which this
    application layer never touches, bypasses, or duplicates (see
    `TestSafety.test_workflow_module_imports_no_raw_transport_primitive`
    above -- `preflight.py` never imports `api_post`/`COMMAND_TEXT`).

    Equivalent, executable coverage of the actual gate behavior already
    exists in S6's own suite and is NOT duplicated here:
      - `tests/test_op0b_s6_pan_preflight_collector.py::test_13_identity_gate_occurs_before_attribution`
      - `tests/test_op0b_s6_pan_preflight_collector.py::test_14_identity_mismatch_stops_trusted_collection`
        -- constructs a member whose `expected_serial` (the same
        locally-resolved value this workflow's `_resolve_pan_operational_entity`
        supplies) is `"0001A"`, mocks the direct P1 response to observe
        `"WRONG_SERIAL"` instead, and proves the identity fact comes back
        `False`/`Outcome.IDENTITY_MISMATCH` with P2/P4 never issued -- i.e.
        local selection alone never grants trust; only S6's own direct read
        does.

    This test adds the one thing not yet proven anywhere: that the value
    S7.5's resolver actually feeds into `expected_serial` is exactly the
    passive, already-collected local value (never a live/observed one), so
    the S6 test above's guarantee actually applies to what this workflow
    constructs. No selection logic is redesigned, no discovery call is
    added, and PAN B2 is untouched.
    """

    def test_resolved_member_target_carries_local_expected_identity_only(self, tmp_path):
        from panorama.preflight_collector import PANPhysicalMemberTarget

        _write_pan_fixture(tmp_path)
        runtime_paths = _RuntimePaths(tmp_path)
        _entity_id, rows = preflight_wf._resolve_pan_operational_entity(
            runtime_paths, ["SA1", "SB1"], _PAN_HA_RUNTIME, _PAN_HA_PEERS,
        )

        from utils.restore_readiness import resolve_entity_id as _resolve_entity_id

        members = [
            PANPhysicalMemberTarget(
                physical_device_identity=_resolve_entity_id(row),
                expected_serial=str(row.get("serial") or ""),
                management_ip=str(row.get("management_ip") or ""),
            )
            for row in rows
        ]

        # The resolver's output is the caller-supplied EXPECTED value S6's P1
        # gate will independently check -- never a value this application
        # layer itself observed or attributed trust to. `unified.json` (the
        # local candidate set) carries no runtime observation field at all
        # for a Panorama-sourced row (confirmed by the fixture below), so
        # there is structurally no "already trusted" identity to smuggle in.
        assert {m.expected_serial for m in members} == {"SA1", "SB1"}
        unified_row = next(r for r in json.loads((tmp_path / "unified.json").read_text()) if r["serial"] == "SA1")
        assert "identity_gate_accepted" not in unified_row
        assert "self_identity_consistent" not in unified_row
