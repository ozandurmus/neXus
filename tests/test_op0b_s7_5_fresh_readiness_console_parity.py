"""OP.0b S7.5 -- fresh CLI readiness == Operator report readiness.

Real-environment defect this closes: after an explicit `--cp-ha-preflight-check`
the CLI printed the fresh, evidence-based result for the ClusterXL unit
(three checks PASS, A3 mode established), while the Operator Console for the
SAME unit still showed the stored-telemetry reasons
(`not_evaluable_without_preflight_battery`, `no_ha_runtime_evidence_for_unit`)
and `MODE = unknown`. Two renderers, two evidence generations.

Product invariant (PO, OP.0b closure law):

    collect fresh evidence ONCE -> canonical S7 evaluation ONCE
        -> one readiness record -> SAFE CLI summary AND generated report

The `PreflightSnapshot` is never persisted; the report is an evaluation
artifact of the invocation that produced it. The seam is one parameter --
`readiness_report` -- threaded from the workflow through `run_html_export`
into `build_failover_readiness_payload`, which then evaluates nothing and
projects the record it was handed.

Covered:
  - §8  contradictory/stale legacy telemetry + one fresh snapshot, through
        the REAL `run_html_export` path: fresh wins; all seven checks
        status+reason identical between the canonical record and the
        embedded report payload; fresh mode wins; no legacy reason leaks.
  - §9  the same inputs WITHOUT a fresh record: the OP.0a stored-telemetry
        report is byte-for-byte what it was -- report generation did not
        become a network workflow and stored evidence is not reinterpreted.
  - the projection is an identity over the record (no second evaluation),
    deterministic, and refuses a snapshot alongside a finished record.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from checkpoint.cp_preflight_projection import (
    project_cp_failover_history_facts,
    project_cp_link_health_facts,
    project_cp_pnote_facts,
    project_cp_policy_facts,
    project_cp_preflight_facts,
    project_cp_software_version_fact,
    project_cp_sync_facts,
)
from utils.failover import STOP_CONDITIONS, compute_ha_readiness
from utils.failover.preflight_model import (
    FactCategory,
    FactContext,
    FactState,
    OpaqueToken,
    Outcome,
    PreflightFact,
    PreflightMemberEvidence,
    PreflightSnapshot,
    Provenance,
    SourceOrigin,
    Transport,
)
from utils.failover_readiness_ui import build_failover_readiness_payload
from utils.html_export import run_html_export

pytestmark = pytest.mark.configuration

ROOT = Path(__file__).resolve().parents[1]
_UNIT = "grp-parity-1"
_T0 = "2026-09-04T10:00:00Z"
_LEGACY_REASONS = {"not_evaluable_without_preflight_battery", "no_ha_runtime_evidence_for_unit"}


# --- fixtures ----------------------------------------------------------------

def _gate_fact(member: str, run_id: str) -> PreflightFact:
    return PreflightFact(
        name="cp_identity_gate_accepted", category=FactCategory.PHYSICAL_IDENTITY,
        state=FactState.KNOWN, value=True,
        provenance=Provenance(
            collected_at=_T0, preflight_run_id=run_id, source_vendor="checkpoint",
            source_plane=SourceOrigin.DEVICE_RUNTIME, transport=Transport.SSH_DIRECT,
            physical_device_identity=OpaqueToken(member), operational_entity_id=_UNIT,
            context=FactContext.physical(), outcome=Outcome.SUCCESS, source_command="gate",
        ),
    )


def _member(member: str, *, role: str, peer: str, run_id: str = "run-parity") -> PreflightMemberEvidence:
    kw = dict(preflight_run_id=run_id, collected_at=_T0, physical_device_identity=member,
              operational_entity_id=_UNIT, context=None)
    ev = project_cp_preflight_facts(
        {"local_role": role, "cluster_mode": "ha_new_mode", "peer_row_states": (peer,), "local_attention": False}, **kw)
    own = list(ev.own_facts)
    own.append(_gate_fact(member, run_id))
    own.append(project_cp_software_version_fact("R81.10", **kw))
    own.extend(project_cp_link_health_facts({"observed": True, "any_down": False, "interface_count": 3}, **kw))
    # Mirror the accepted real S8-A state: pnote UNKNOWN, everything else known.
    own.extend(project_cp_pnote_facts({"observed": False, "any_problem": None, "device_count": None}, **kw))
    own.extend(project_cp_sync_facts({"observed": True, "status": "ok"}, dispatch_form="a6_syncstat", **kw))
    own.extend(project_cp_policy_facts({"observed": True, "policy_name": "policy-a"}, **kw))
    own.extend(project_cp_failover_history_facts(
        {"observed": True, "count": 2, "last_reason_class": "interface", "last_event_time": "t"},
        dispatch_form="a8_clish", **kw))
    return PreflightMemberEvidence(physical_device_identity=OpaqueToken(member),
                                   own_facts=tuple(own), peer_claim_facts=ev.peer_claim_facts)


def _fresh_snapshot() -> PreflightSnapshot:
    return PreflightSnapshot(
        operational_unit_id=_UNIT, vendor="checkpoint", unit_type="clusterxl",
        preflight_run_id="run-parity",
        members=(_member("m1", role="STANDBY", peer="ACTIVE"), _member("m2", role="ACTIVE", peer="STANDBY")),
    )


def _unified_rows() -> list[dict]:
    return [
        {"device": d, "source": "cp", "inventory_status": {"data_state": "live"},
         "cluster_topology": {"group_id": _UNIT, "display_name": "Parity"}}
        for d in ("m1", "m2")
    ]


def _contradictory_legacy_telemetry() -> dict:
    """Stale stored telemetry that DISAGREES with the fresh snapshot on every
    axis it can: a different mode and roles that cannot yield a standby."""
    return {"devices": [
        {"entity_id": "m1", "ha_role": "DOWN", "ha_cluster_mode": "load_sharing"},
        {"entity_id": "m2", "ha_role": "DOWN", "ha_cluster_mode": "load_sharing"},
    ]}


def _write_inputs(tmp_path: Path) -> tuple[Path, Path, dict]:
    unified = tmp_path / "unified.json"
    unified.write_text(json.dumps(_unified_rows()), encoding="utf-8")
    return unified, tmp_path / "index.html", _contradictory_legacy_telemetry()


def _embedded_payload(html_path: Path) -> dict:
    """Parse `failoverReadinessData` back out of the generated report, the
    way the console script receives it."""
    html = html_path.read_text(encoding="utf-8")
    marker = "failoverReadinessData: "
    start = html.index(marker) + len(marker)
    payload, _end = json.JSONDecoder().raw_decode(html, start)
    return payload


def _unit(payload_or_report: dict) -> dict:
    return next(u for u in payload_or_report["units"] if u["unit_id"] == _UNIT)


def _canonical_report(legacy: dict) -> dict:
    from utils.failover_readiness_ui import extract_cp_ha_runtime
    return compute_ha_readiness(
        _unified_rows(), cp_ha_runtime=extract_cp_ha_runtime(legacy),
        preflight_snapshots=[_fresh_snapshot()],
    )


# --- §8: fresh wins, CLI == report, no leakage -------------------------------

class TestFreshReadinessReachesTheReport:

    def test_all_seven_checks_identical_between_cli_record_and_report(self, tmp_path):
        unified, html, legacy = _write_inputs(tmp_path)
        report = _canonical_report(legacy)
        run_html_export(unified, html, checkpoint_config_result=legacy,
                        repository_root=ROOT, data_root=tmp_path / "data",
                        failover_readiness_report=report)

        cli_unit, html_unit = _unit(report), _unit(_embedded_payload(html))
        cli = {c["id"]: (c["status"], c["reason"]) for c in cli_unit["checks"]}
        web = {c["id"]: (c["status"], c["reason"]) for c in html_unit["checks"]}
        assert set(cli) == {cid for cid, _ in STOP_CONDITIONS} == set(web)
        assert cli == web, "CLI.status/reason must equal Console.status/reason for every check"
        assert cli_unit["verdict"] == html_unit["verdict"]
        assert cli_unit["reason"] == html_unit["reason"]

    def test_fresh_snapshot_wins_over_contradictory_legacy_telemetry(self, tmp_path):
        unified, html, legacy = _write_inputs(tmp_path)
        report = _canonical_report(legacy)
        run_html_export(unified, html, checkpoint_config_result=legacy,
                        repository_root=ROOT, data_root=tmp_path / "data",
                        failover_readiness_report=report)
        payload = _embedded_payload(html)
        assert _UNIT in payload["preflight"]["applied"]
        checks = {c["id"]: c for c in _unit(payload)["checks"]}
        assert checks["state_sync_current"]["status"] == "PASS"
        assert checks["parity"]["status"] == "PASS"
        assert checks["no_split_brain"]["status"] == "PASS"
        assert checks["control_sync_link_health"]["status"] == "PASS"
        assert checks["viable_target"]["reason"].startswith("unknown:cp_pnote_any_problem")

    def test_fresh_mode_wins_over_legacy_unknown_or_contradictory_mode(self, tmp_path):
        unified, html, legacy = _write_inputs(tmp_path)
        report = _canonical_report(legacy)
        run_html_export(unified, html, checkpoint_config_result=legacy,
                        repository_root=ROOT, data_root=tmp_path / "data",
                        failover_readiness_report=report)
        html_unit = _unit(_embedded_payload(html))
        assert html_unit["cluster_mode"] == _unit(report)["cluster_mode"]
        assert html_unit["cluster_mode"] == "ha_new_mode"
        assert html_unit["cluster_mode"] not in ("unknown", "load_sharing")

    def test_no_legacy_reason_leaks_into_the_fresh_report(self, tmp_path):
        unified, html, legacy = _write_inputs(tmp_path)
        run_html_export(unified, html, checkpoint_config_result=legacy,
                        repository_root=ROOT, data_root=tmp_path / "data",
                        failover_readiness_report=_canonical_report(legacy))
        reasons = {c["reason"] for c in _unit(_embedded_payload(html))["checks"]}
        assert not (reasons & _LEGACY_REASONS), reasons


# --- §9: without a fresh record, nothing changes -----------------------------

class TestNoFreshRecordLeavesTheReportUnchanged:

    def test_stored_telemetry_basis_is_untouched(self, tmp_path):
        unified, html, legacy = _write_inputs(tmp_path)
        run_html_export(unified, html, checkpoint_config_result=legacy,
                        repository_root=ROOT, data_root=tmp_path / "data")
        payload = _embedded_payload(html)
        assert payload["preflight"].get("applied", []) == []
        reasons = {c["reason"] for c in _unit(payload)["checks"]}
        assert reasons & _LEGACY_REASONS, "the honest no-preflight answer must remain"
        assert _unit(payload)["cluster_mode"] != "ha_new_mode"

    def test_default_path_is_the_same_payload_the_console_builds(self, tmp_path):
        """The report and the console share one builder; a None record must
        leave that builder's output exactly as before."""
        from utils.html_export import build_report_payloads
        unified, _html, legacy = _write_inputs(tmp_path)
        a = build_report_payloads(unified, checkpoint_config_result=legacy,
                                  repository_root=ROOT, data_root=tmp_path / "data")
        b = build_report_payloads(unified, checkpoint_config_result=legacy,
                                  repository_root=ROOT, data_root=tmp_path / "data",
                                  failover_readiness_report=None)
        for p in (a, b):
            p["failoverReadinessData"].pop("generated_at", None)
        assert a["failoverReadinessData"] == b["failoverReadinessData"]


# --- the seam itself ---------------------------------------------------------

class TestProjectionIsIdentityNotEvaluation:

    def test_report_units_are_projected_verbatim(self):
        report = _canonical_report(_contradictory_legacy_telemetry())
        payload = build_failover_readiness_payload(_unified_rows(), readiness_report=report)
        assert payload["units"] == report["units"]
        assert payload["preflight"] == report["preflight"]
        assert payload["generated_at"] == report["generated_at"]

    def test_projection_is_deterministic(self):
        report = _canonical_report(_contradictory_legacy_telemetry())
        one = build_failover_readiness_payload(_unified_rows(), readiness_report=report)
        two = build_failover_readiness_payload(_unified_rows(), readiness_report=report)
        assert one == two

    def test_snapshot_alongside_a_finished_record_is_refused(self):
        """Evaluate once, project once: a snapshot next to a finished record
        would be a second evaluation of the same evidence."""
        report = _canonical_report(_contradictory_legacy_telemetry())
        with pytest.raises(ValueError):
            build_failover_readiness_payload(
                _unified_rows(), readiness_report=report, preflight_snapshots=[_fresh_snapshot()])

    def test_no_snapshot_persistence_anywhere_in_the_seam(self):
        """The snapshot stays invocation-scoped: no file, cache, TTL or expiry."""
        import inspect
        import application.workflows.preflight as wf
        import utils.failover_readiness_ui as ui
        for module in (wf, ui):
            src = inspect.getsource(module)
            for forbidden in ("preflight_snapshots.json", "ttl", "TTL", "expiry", "expires_at", "pickle"):
                assert forbidden not in src, f"{module.__name__}: {forbidden!r}"
