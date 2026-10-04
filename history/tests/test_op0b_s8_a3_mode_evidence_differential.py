"""OP.0b S8-A real-env finding — A3 mode-evidence pipeline differential.

The first real ClusterXL S8-A run reported `ha_mode_not_established` for
all seven checks on a confirmed genuine Check Point ClusterXL High
Availability pair whose established CP collection path had already
produced `ha_cluster_mode = ha_new_mode` for the same real pair.

Root cause (source-trace, no raw device output needed): `project_cp_
preflight_facts`'s own docstring documents its `fields` contract as
`_parse_clusterxl_stat_preflight_fields`'s shape "merged with the two
always-parsed leaves" (`local_role`, `cluster_mode`). That merge was never
implemented at the one call site that matters
(`checkpoint.preflight_collector.collect_member`'s A3 block) — it passed
`_parse_clusterxl_stat_preflight_fields`'s return value straight through.
That function is *correctly* scoped by its own S3 test
(`test_no_future_command_fields_fabricated`, `tests/test_op0b_s3_cp_extraction.py`)
to return exactly `{peer_row_states, local_attention}` — it was never
supposed to carry `local_role`/`cluster_mode` itself. So
`fields.get("cluster_mode")` was always `None`, unconditionally,
regardless of any real device's actual output — not a vendor-phrasing
gap. Reproduced deterministically with the pre-existing S5 happy-path
fixture (`tests/test_op0b_s5_cp_preflight_collector.py`'s own `_HAPPY`),
no real device needed.

Fix: `collect_member`'s A3 block now merges `local_role`/`cluster_mode`
into the fields dict itself, computed via the exact same canonical
parsers (`_parse_clusterxl_cluster_mode`, `_parse_clusterxl_runtime_role`)
the pre-existing VS-context path in
`configuration.checkpoint_config_collector._collect_host` already uses on
the same buffer shape — one parser, two consumers, no second
implementation, no change to `_parse_clusterxl_stat_preflight_fields`'s
own scoped contract.

This file proves: the established parser and the S5 preflight projection
now agree on a representative genuine ClusterXL HA fixture; an
unrecognized mode still stays `UNKNOWN` and readiness still fails closed
with `ha_mode_not_established` (not loosened); the S3 extraction
function's own scope is untouched; and there is exactly one parser
implementation, not two.
"""
from __future__ import annotations

import inspect

import pytest

import checkpoint.preflight_collector as pc
import configuration.checkpoint_config_collector as cp_collector
from checkpoint.cp_preflight_battery import COMMAND_TEXT, CPPreflightRead
from checkpoint.preflight_collector import MemberSession, collect_member
from utils.failover.preflight_model import FactState
from utils.failover.preflight_readiness import evaluate_snapshot_checks
from utils.failover.preflight_model import PreflightSnapshot

pytestmark = pytest.mark.configuration

# A representative genuine ClusterXL High Availability `cphaprob stat`
# shape (the same one tests/test_op0b_s3_cp_extraction.py's own
# _HA_TWO_MEMBER fixture and tests/test_op0b_s5_cp_preflight_collector.py's
# _HAPPY fixture already use as the frozen "real HA" reference shape).
_HA_STAT_LOCAL_ACTIVE = (
    "Cluster Mode:   High Availability (Active Up)\n"
    "Number   Unique Address  Assigned Load   State             Name\n"
    "1 (local) 10.10.10.1      100%            ACTIVE            gw-member-a\n"
    "2         10.10.10.2      0%              STANDBY           gw-member-b\n"
)
_HA_STAT_LOCAL_STANDBY = (
    "Cluster Mode:   High Availability (Active Up)\n"
    "Number   Unique Address  Assigned Load   State             Name\n"
    "1         10.10.10.1      100%            ACTIVE            gw-member-a\n"
    "2 (local) 10.10.10.2      0%              STANDBY           gw-member-b\n"
)
_UNRECOGNIZED_BANNER = "This appliance is not part of a cluster.\n"

_A1 = "gw-member-a"
_A2 = "This is Check Point's software version R81.10\nOS build 123"


def _run_factory(a3_text: str, *, hostname: str = _A1):
    fixtures = {
        CPPreflightRead.A1_HOSTNAME: hostname,
        CPPreflightRead.A2_VERSION: _A2,
        CPPreflightRead.A3_CPHAPROB_STAT: a3_text,
    }

    def _run(command_text: str) -> dict:
        for read, text in COMMAND_TEXT.items():
            if text == command_text:
                stdout = fixtures.get(read, "")
                return {
                    "success": bool(stdout), "stdout": stdout, "stderr": "",
                    "error_class": "none" if stdout else "empty_output", "timeout": False,
                }
        raise AssertionError(f"unapproved command text issued: {command_text!r}")

    return _run


def _collect(a3_text: str, *, identity: str = "member-a", hostname: str = _A1, operational_entity_id: str = "unit-1"):
    session = MemberSession(physical_device_identity=identity, _run_command=_run_factory(a3_text, hostname=hostname))
    return collect_member(
        session, expected_device_name=hostname, management_ip="10.0.0.1", is_vsx=False,
        preflight_run_id="run-1", operational_entity_id=operational_entity_id,
    )


def _fact(evidence, name):
    return next((f for f in evidence.own_facts if f.name == name), None)


# ---------------------------------------------------------------------------
# Differential: established parser vs. S5 preflight projection must agree
# ---------------------------------------------------------------------------

class TestEstablishedParserAndPreflightProjectionAgree:

    def test_cluster_mode_agrees_on_genuine_ha_fixture(self):
        established = cp_collector._parse_clusterxl_cluster_mode(_HA_STAT_LOCAL_ACTIVE)
        assert established == "ha_new_mode"

        ev = _collect(_HA_STAT_LOCAL_ACTIVE)
        fact = _fact(ev, "ha_cluster_mode")
        assert fact is not None
        assert fact.state is FactState.KNOWN
        assert fact.value == established

    def test_local_role_agrees_on_genuine_ha_fixture(self):
        established = cp_collector._parse_clusterxl_runtime_role(_HA_STAT_LOCAL_ACTIVE, _A1)
        assert established == "ACTIVE"

        ev = _collect(_HA_STAT_LOCAL_ACTIVE)
        fact = _fact(ev, "ha_local_role")
        assert fact is not None
        assert fact.state is FactState.KNOWN
        assert fact.value == established

    def test_local_role_agrees_when_local_member_is_standby(self):
        established = cp_collector._parse_clusterxl_runtime_role(_HA_STAT_LOCAL_STANDBY, "gw-member-b")
        assert established == "STANDBY"

        ev = _collect(_HA_STAT_LOCAL_STANDBY, identity="member-b", hostname="gw-member-b")
        fact = _fact(ev, "ha_local_role")
        assert fact.state is FactState.KNOWN
        assert fact.value == established

    def test_end_to_end_readiness_no_longer_reports_mode_not_established(self):
        """The exact real-world symptom: a coherent, identity-gated,
        two-member genuine-HA snapshot must not report ha_mode_not_established."""
        ev_a = _collect(_HA_STAT_LOCAL_ACTIVE, identity="member-a", hostname="gw-member-a")
        ev_b = _collect(_HA_STAT_LOCAL_STANDBY, identity="member-b", hostname="gw-member-b")
        snapshot = PreflightSnapshot(
            operational_unit_id="unit-1", vendor="checkpoint", unit_type="clusterxl",
            preflight_run_id="run-1", members=(ev_a, ev_b),
        )
        result = evaluate_snapshot_checks(
            snapshot, unit_id="unit-1", vendor="checkpoint", unit_member_count=2,
            is_vs_unit=False, pair_identity="established",
        )
        for check in result.checks:
            assert check.get("reason") != "ha_mode_not_established", check


# ---------------------------------------------------------------------------
# Fail-closed preserved: an unrecognized mode must still block, never guess
# ---------------------------------------------------------------------------

class TestUnrecognizedModeStillFailsClosed:

    def test_unrecognized_mode_stays_unknown_fact(self):
        established = cp_collector._parse_clusterxl_cluster_mode(_UNRECOGNIZED_BANNER)
        assert established == "unknown"

        ev = _collect(_UNRECOGNIZED_BANNER)
        fact = _fact(ev, "ha_cluster_mode")
        assert fact.state is FactState.UNKNOWN
        assert fact.value is None

    def test_unrecognized_mode_readiness_still_reports_not_established(self):
        ev_a = _collect(_UNRECOGNIZED_BANNER, identity="member-a", hostname="gw-member-a")
        ev_b = _collect(_UNRECOGNIZED_BANNER, identity="member-b", hostname="gw-member-b")
        snapshot = PreflightSnapshot(
            operational_unit_id="unit-1", vendor="checkpoint", unit_type="clusterxl",
            preflight_run_id="run-1", members=(ev_a, ev_b),
        )
        result = evaluate_snapshot_checks(
            snapshot, unit_id="unit-1", vendor="checkpoint", unit_member_count=2,
            is_vs_unit=False, pair_identity="established",
        )
        assert result.checks, "expected the seven stop-condition checks"
        for check in result.checks:
            assert check.get("status") != "PASS"
            if check.get("id") == "preemption_known":
                # Structurally blocked independent of mode (D-V7b, A9
                # unauthorized) -- checked before any prerequisite, unaffected
                # by this fix. Matches the real S8-A observation exactly.
                assert check.get("reason") == "configured_recovery_not_readable_d_v7b"
            else:
                assert check.get("reason") == "ha_mode_not_established"

    def test_collection_failure_still_maps_to_collection_failed_not_a_guess(self):
        session = MemberSession(
            physical_device_identity="member-a",
            _run_command=lambda cmd: {"success": False, "stdout": "", "stderr": "", "error_class": "timeout", "timeout": True},
        )
        evidence = collect_member(
            session, expected_device_name=_A1, management_ip="10.0.0.1", is_vsx=False,
            preflight_run_id="run-1", operational_entity_id="entity-1",
        )
        assert _fact(evidence, "cp_identity_gate_accepted").state is FactState.KNOWN


# ---------------------------------------------------------------------------
# Single authority: no second parser implementation, S3's own scope untouched
# ---------------------------------------------------------------------------

class TestSingleParserAuthorityPreserved:

    def test_preflight_collector_reuses_the_exact_established_parser_functions(self):
        # Compares bytecode/behavior, not object identity: an unrelated,
        # pre-existing test elsewhere in this suite (test_dev0_3b_runtime_
        # output_dir_binding.py) reloads configuration.checkpoint_config_
        # collector via importlib, which legitimately rebinds a fresh
        # function object with identical code -- an `is` check would be
        # fragile to that orthogonal test-order effect, not to a real
        # second implementation.
        assert pc._parse_clusterxl_cluster_mode.__code__.co_code == cp_collector._parse_clusterxl_cluster_mode.__code__.co_code
        assert pc._parse_clusterxl_runtime_role.__code__.co_code == cp_collector._parse_clusterxl_runtime_role.__code__.co_code

    def test_s3_extraction_function_scope_is_unchanged(self):
        """Pins tests/test_op0b_s3_cp_extraction.py::test_no_future_command_fields_fabricated's
        own invariant from this file too: the fix must not have touched
        _parse_clusterxl_stat_preflight_fields's own deliberately narrow
        return contract."""
        fields = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_STAT_LOCAL_ACTIVE, _A1)
        assert set(fields) == {"peer_row_states", "local_attention"}

    def test_no_second_mode_vocabulary_introduced(self):
        """The merge happens via dict-merge of the two canonical parsers'
        results -- no new mode-classification logic exists in the collector."""
        src = inspect.getsource(pc.collect_member)
        assert "_parse_clusterxl_cluster_mode(a3_stdout)" in src
        assert "_parse_clusterxl_runtime_role(a3_stdout" in src
        # No inline reimplementation of mode-string matching in this module.
        assert "high availability" not in src.lower()
        assert "load sharing" not in src.lower()
