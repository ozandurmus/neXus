"""OP.0b S7 -- readiness v2 integration: fresh S1/S5/S6 preflight evidence
interpreted by the ONE canonical readiness evaluator.

Contract chain: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(FROZEN WITH REAL-ENV VALIDATION GATES, slice S7) + `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`
(APPROVED -- which facts exist) + `docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md`
(P4 verdict contract, preserved).

Proves, over synthetic snapshots built through the real S3/S5/S6 projection
seams (so fact names match production): the evidence laws (UNKNOWN /
COLLECTION_FAILED / UNSUPPORTED never PASS and never KNOWN_BAD; positive
evidence required; coherence gates positives without fabricating failure),
the preserved seven-check / five-verdict vocabularies, the unresolved
D-F1/D-F2/D-F3/D-V7b/B2 decisions staying unresolved, CP ClusterXL / VSX and
PAN unit semantics, OP.0a/OP.0c compatibility, single readiness authority,
and the evaluator's zero-I/O purity. No device is contacted anywhere.
"""
from __future__ import annotations

import ast
import dataclasses
import itertools
import json
import re
import sys
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
from panorama.pan_preflight_projection import (
    project_pan_identity_fact,
    project_pan_management_endpoint_fact,
    project_pan_path_monitoring_facts,
    project_pan_preflight_facts,
)
from utils.failover import (
    CHECK_FAIL,
    CHECK_INSUFFICIENT,
    CHECK_PASS,
    EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT,
    EVIDENCE_BASIS_STORED_TELEMETRY,
    FACT_CHECK_MAP,
    STOP_CONDITIONS,
    UNRESOLVED_POLICY_DECISIONS,
    VERDICT_DEGRADED,
    VERDICT_INSUFFICIENT,
    VERDICT_NOT_A_FAILOVER_UNIT,
    VERDICT_SAFE,
    VERDICT_UNSAFE,
    HaUnit,
    compute_ha_readiness,
)
from utils.failover import assessment as assessment_module
from utils.failover import preflight_readiness as readiness_module
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
from utils.failover_readiness_ui import VERDICT_LABELS, build_failover_readiness_payload

ROOT = Path(__file__).resolve().parents[1]


def _code_only(path: Path) -> str:
    """Source with comments and docstrings stripped -- the token scans below
    are about what the code *does*, not what its prose says it does not do."""
    import io
    import tokenize

    text = path.read_text(encoding="utf-8")
    out: list[str] = []
    prev_type = tokenize.NEWLINE
    for tok in tokenize.generate_tokens(io.StringIO(text).readline):
        if tok.type == tokenize.COMMENT:
            continue
        if tok.type == tokenize.STRING and prev_type in (tokenize.NEWLINE, tokenize.INDENT, tokenize.DEDENT, tokenize.NL, tokenize.ENCODING):
            prev_type = tok.type
            continue  # a standalone string statement == docstring
        if tok.type not in (tokenize.NL, tokenize.NEWLINE, tokenize.INDENT, tokenize.DEDENT):
            out.append(tok.string)
        else:
            out.append("\n")
        prev_type = tok.type
    return " ".join(out)


SEVEN = tuple(check_id for check_id, _label in STOP_CONDITIONS)
FIVE = (VERDICT_SAFE, VERDICT_DEGRADED, VERDICT_UNSAFE, VERDICT_INSUFFICIENT, VERDICT_NOT_A_FAILOVER_UNIT)

_T0 = "2026-09-03T10:00:00Z"
_T1 = "2026-09-03T10:00:01Z"

# Synthetic identities only -- no real hostname / IP / serial anywhere below.
_CP_UNIT = "grp-cp-1"
_PAN_UNIT = "pan-a+pan-b"


# =====================================================================
# Fixture builders -- through the real projection seams
# =====================================================================

def _identity_fact(name, *, run_id, at, member, unit, vendor, accepted=True, context=None):
    return PreflightFact(
        name=name, category=FactCategory.PHYSICAL_IDENTITY, state=FactState.KNOWN, value=bool(accepted),
        provenance=Provenance(
            collected_at=at, preflight_run_id=run_id, source_vendor=vendor,
            source_plane=SourceOrigin.DEVICE_RUNTIME,
            transport=Transport.SSH_DIRECT if vendor == "checkpoint" else Transport.DIRECT_API,
            physical_device_identity=OpaqueToken(member), operational_entity_id=unit,
            context=context or FactContext.physical(),
            outcome=Outcome.SUCCESS if accepted else Outcome.IDENTITY_MISMATCH, source_command="gate",
        ),
    )


def _apply_overrides(facts, *, failed=(), unknown=(), unsupported=(), omit=()):
    out = []
    for fact in facts:
        if fact.name in omit:
            continue
        if fact.name in failed:
            fact = dataclasses.replace(fact, state=FactState.COLLECTION_FAILED, value=None)
        elif fact.name in unknown:
            fact = dataclasses.replace(fact, state=FactState.UNKNOWN, value=None)
        elif fact.name in unsupported:
            fact = dataclasses.replace(fact, state=FactState.UNSUPPORTED, value=None)
        out.append(fact)
    return tuple(out)


def cp_member(
    member="tok-m1", *, unit=_CP_UNIT, run_id="run-1", at=_T0, role="ACTIVE", mode="ha_new_mode",
    attention=False, pnote=False, link_down=False, sync="ok", version="R81.10", policy="policy-a",
    failover_count=0, peer_rows=("STANDBY",), gate=True, context=None,
    failed=(), unknown=(), unsupported=(), omit=(), run_id_overrides=None,
):
    kw = dict(preflight_run_id=run_id, collected_at=at, physical_device_identity=member, operational_entity_id=unit, context=context)
    ev = project_cp_preflight_facts(
        {"local_role": role, "cluster_mode": mode, "peer_row_states": tuple(peer_rows), "local_attention": attention}, **kw,
    )
    own = list(ev.own_facts)
    own.append(_identity_fact("cp_identity_gate_accepted", run_id=run_id, at=at, member=member, unit=unit, vendor="checkpoint", accepted=gate, context=context))
    own.append(project_cp_software_version_fact(version, **kw))
    own.extend(project_cp_link_health_facts({"observed": True, "any_down": link_down, "interface_count": 3}, **kw))
    own.extend(project_cp_pnote_facts({"observed": True, "any_problem": pnote, "device_count": 5}, **kw))
    own.extend(project_cp_sync_facts({"observed": True, "status": sync}, dispatch_form="a6_syncstat", **kw))
    own.extend(project_cp_policy_facts({"observed": True, "policy_name": policy}, **kw))
    own.extend(project_cp_failover_history_facts(
        {"observed": True, "count": failover_count, "last_reason_class": "interface", "last_event_time": "t"},
        dispatch_form="a8_clish", **kw,
    ))
    own = _apply_overrides(own, failed=failed, unknown=unknown, unsupported=unsupported, omit=omit)
    if run_id_overrides:
        own = tuple(
            dataclasses.replace(f, provenance=dataclasses.replace(f.provenance, preflight_run_id=run_id_overrides[f.name]))
            if f.name in run_id_overrides else f
            for f in own
        )
    return PreflightMemberEvidence(physical_device_identity=OpaqueToken(member), own_facts=own, peer_claim_facts=ev.peer_claim_facts)


def cp_snapshot(*members, unit=_CP_UNIT, run_id="run-1", unit_type="clusterxl", configuration_facts=()):
    return PreflightSnapshot(
        operational_unit_id=unit, vendor="checkpoint", unit_type=unit_type, preflight_run_id=run_id,
        members=tuple(members), configuration_facts=tuple(configuration_facts),
    )


def _pan_fields(*, state, mode, state_sync, conn_ha1, conn_ha2, running_sync, build_rel, compat, preemptive, flaps, peer_state, serial, peer_serial):
    return {
        "enabled": "yes", "state": state, "mode": mode, "peer_state": peer_state, "state_sync": state_sync,
        "running_sync": running_sync, "running_sync_enabled": "yes", "local_state_sync_type": "ip",
        "local_preemptive": preemptive, "local_priority": "100", "local_preempt_hold": "1", "local_promotion_hold": "2000",
        "local_max_flaps": "3", "local_nonfunc_flap_cnt": str(flaps[0]), "local_preempt_flap_cnt": str(flaps[1]),
        "local_state_duration": "1000", "local_last_error_reason": None, "local_last_error_state": None,
        "local_build_rel": build_rel, "local_app_version": "8800", "local_app_compat": compat,
        "local_av_version": "1", "local_av_compat": compat, "local_threat_version": "1", "local_threat_compat": compat,
        "local_url_version": "1", "local_url_compat": compat,
        "peer_conn_status": "up", "peer_conn_ha1_status": conn_ha1, "peer_conn_ha1_backup_status": None,
        "peer_conn_ha2_status": conn_ha2, "peer_build_rel": build_rel, "peer_app_version": "8800",
        "peer_av_version": "1", "peer_threat_version": "1", "peer_url_version": "1",
        "local_serial_num": serial, "peer_serial_num": peer_serial,
    }


def pan_member(
    member="tok-p1", *, unit=_PAN_UNIT, run_id="run-1", at=_T0, state="active", mode="active-passive",
    state_sync="Complete", conn_ha1="up", conn_ha2="up", path_any_down=False, path_enabled=True,
    running_sync="synchronized", build_rel="11.1.2", compat="Match", preemptive="yes", flaps=(0, 0),
    peer_state="passive", serial="0001A", peer_serial="0002B", gate=True,
    failed=(), unknown=(), unsupported=(), omit=(), p2_failed=False,
    # OP.0b S8-C real-env correction: local/peer runtime management-plane
    # addressing (P2 `mgmt-ip`) and this member's own P1-dialed endpoint --
    # all `None` by default (existing callers/tests unaffected). Values here
    # are synthetic pre-tokenized strings, exactly like `serial`/`peer_serial`
    # above -- the projection layer wraps whatever string it receives in
    # `OpaqueToken`, it never re-tokenizes.
    local_mgmt_ip=None, peer_mgmt_ip=None, ha_group_id=None, local_management_endpoint=None,
):
    kw = dict(preflight_run_id=run_id, collected_at=at, physical_device_identity=member, operational_entity_id=unit, transport=Transport.DIRECT_API)
    fields = None if p2_failed else {
        **_pan_fields(
            state=state, mode=mode, state_sync=state_sync, conn_ha1=conn_ha1, conn_ha2=conn_ha2, running_sync=running_sync,
            build_rel=build_rel, compat=compat, preemptive=preemptive, flaps=flaps, peer_state=peer_state, serial=serial, peer_serial=peer_serial,
        ),
        "local_mgmt_ip": local_mgmt_ip, "peer_mgmt_ip": peer_mgmt_ip, "ha_group_id": ha_group_id,
    }
    ev = project_pan_preflight_facts(fields, source_command="P2", outcome=Outcome.FAILED if p2_failed else Outcome.SUCCESS, **kw)
    own = [project_pan_identity_fact(gate, **kw)]
    if local_management_endpoint is not None:
        own.append(project_pan_management_endpoint_fact(local_management_endpoint, **kw))
    own.extend(ev.own_facts)
    own.extend(project_pan_path_monitoring_facts(
        {"observed": True, "enabled": path_enabled, "path_count": 2, "any_down": path_any_down}, **kw,
    ))
    own = _apply_overrides(own, failed=failed, unknown=unknown, unsupported=unsupported, omit=omit)
    peer = _apply_overrides(ev.peer_claim_facts, failed=failed, unknown=unknown, unsupported=unsupported, omit=omit)
    return PreflightMemberEvidence(physical_device_identity=OpaqueToken(member), own_facts=own, peer_claim_facts=peer)


def pan_snapshot(*members, unit=_PAN_UNIT, run_id="run-1"):
    return PreflightSnapshot(operational_unit_id=unit, vendor="panorama", unit_type="ha_pair", preflight_run_id=run_id, members=tuple(members))


def cp_rows(group=_CP_UNIT, devices=("m1", "m2"), source="cp"):
    return [
        {"device": d, "source": source, "cluster_topology": {"group_id": group, "display_name": "Core"},
         "inventory_status": {"data_state": "ok"}}
        for d in devices
    ]


def vsx_rows(group=_CP_UNIT, devices=("vsx-1", "vsx-2"), vsids=("2",)):
    rows = cp_rows(group, devices)
    for d in devices:
        for vsid in vsids:
            rows.append({"device": d, "source": "vsx", "vs_id": vsid, "vsys": f"vs{vsid}", "inventory_status": {"data_state": "ok"}})
    return rows


def pan_rows():
    return [
        {"device": "pan-a", "source": "panorama", "management_ip": "10.0.0.1", "inventory_status": {"data_state": "ok"},
         "interfaces": [{"vsys": "vsys1"}, {"vsys": "vsys2"}]},
        {"device": "pan-b", "source": "panorama", "management_ip": "10.0.0.2", "inventory_status": {"data_state": "ok"},
         "interfaces": [{"vsys": "vsys1"}]},
    ]


_PAN_RUNTIME = {
    "pan-a": {"enabled": "yes", "state": "active", "mode": "active-passive", "peer_state": "passive", "state_sync": "x"},
    "pan-b": {"enabled": "yes", "state": "passive", "mode": "active-passive", "peer_state": "active", "state_sync": "x"},
}
_PAN_PEERS = {"pan-a": "10.0.0.2", "pan-b": "10.0.0.1"}


def cp_report(*snapshots, rows=None, cp_ha_runtime=None):
    return compute_ha_readiness(rows or cp_rows(), cp_ha_runtime=cp_ha_runtime, preflight_snapshots=list(snapshots))


def pan_report(*snapshots):
    return compute_ha_readiness(pan_rows(), pan_ha_runtime=_PAN_RUNTIME, pan_ha_peers=_PAN_PEERS, preflight_snapshots=list(snapshots))


def unit(report, unit_id):
    return next(u for u in report["units"] if u["unit_id"] == unit_id)


def checks(u):
    return {c["id"]: c for c in u["checks"]}


def happy_cp():
    return cp_snapshot(cp_member("tok-m1", role="ACTIVE"), cp_member("tok-m2", at=_T1, role="STANDBY"))


def happy_pan():
    return pan_snapshot(pan_member("tok-p1", state="active"), pan_member("tok-p2", at=_T1, state="passive", peer_state="active"))


# =====================================================================
# §27 domain tests 1-15
# =====================================================================

def test_01_unknown_fact_never_becomes_pass():
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", unknown=("cp_link_any_down",)))
    c = checks(unit(cp_report(snap), _CP_UNIT))
    assert c["control_sync_link_health"]["status"] == CHECK_INSUFFICIENT
    assert c["control_sync_link_health"]["reason"] == "unknown:cp_link_any_down"
    # The unaffected checks still PASS -- UNKNOWN on one fact never bleeds into another.
    assert c["no_split_brain"]["status"] == CHECK_PASS


def test_02_collection_failed_never_becomes_known_bad():
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", failed=("cp_pnote_any_problem", "cp_sync_status", "cp_link_any_down")))
    u = unit(cp_report(snap), _CP_UNIT)
    assert u["verdict"] == VERDICT_INSUFFICIENT
    c = checks(u)
    for cid in ("viable_target", "state_sync_current", "control_sync_link_health"):
        assert c[cid]["status"] == CHECK_INSUFFICIENT
        assert c[cid]["reason"].startswith("collection_failed:")


def test_03_unsupported_never_becomes_known_bad():
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", unsupported=("cp_sync_status",)))
    c = checks(unit(cp_report(snap), _CP_UNIT))
    assert c["state_sync_current"]["status"] == CHECK_INSUFFICIENT
    assert c["state_sync_current"]["reason"] == "unsupported:cp_sync_status"
    assert not any(x["status"] == CHECK_FAIL for x in c.values())


def test_04_explicit_dangerous_fact_becomes_evaluated_fail():
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", pnote=True))
    u = unit(cp_report(snap), _CP_UNIT)
    assert checks(u)["viable_target"]["status"] == CHECK_FAIL
    assert checks(u)["viable_target"]["reason"] == "critical_device_problem_observed"
    assert u["verdict"] == VERDICT_UNSAFE and u["reason"] == "critical_device_problem_observed"


def test_05_mixed_run_incoherence_blocks_positive_evaluation():
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", run_id_overrides={"ha_local_role": "run-OLD"}))
    u = unit(cp_report(snap), _CP_UNIT)
    assert u["evidence"]["coherent"] is False
    assert u["evidence"]["prerequisites"]["coherence"] == "incoherent"
    assert all(c["status"] != CHECK_PASS for c in u["checks"])
    assert all(c["reason"] == "preflight_snapshot_incoherent" for c in u["checks"] if c["id"] != "preemption_known")


def test_06_incoherence_does_not_fabricate_device_failure():
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", run_id_overrides={"ha_local_role": "run-OLD"}))
    u = unit(cp_report(snap), _CP_UNIT)
    assert u["verdict"] == VERDICT_INSUFFICIENT
    assert not any(c["status"] == CHECK_FAIL for c in u["checks"])
    # ...but an independently known-bad fact (the device's own explicit
    # report) still fails, even inside an incoherent snapshot.
    snap2 = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", pnote=True, run_id_overrides={"ha_local_role": "run-OLD"}))
    u2 = unit(cp_report(snap2), _CP_UNIT)
    assert u2["verdict"] == VERDICT_UNSAFE and u2["reason"] == "critical_device_problem_observed"


def test_07_missing_required_evidence_yields_insufficient_evidence():
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", omit=("cp_link_any_down", "cp_link_interface_count")))
    u = unit(cp_report(snap), _CP_UNIT)
    c = checks(u)
    assert c["control_sync_link_health"]["status"] == CHECK_INSUFFICIENT
    assert c["control_sync_link_health"]["reason"] == "not_collected:cp_link_any_down"
    assert c["control_sync_link_health"]["missing_evidence"]
    assert u["verdict"] == VERDICT_INSUFFICIENT


def test_08_positive_check_requires_explicit_positive_evidence():
    u = unit(cp_report(happy_cp()), _CP_UNIT)
    c = checks(u)
    for cid in ("viable_target", "state_sync_current", "parity", "no_split_brain", "control_sync_link_health"):
        assert c[cid]["status"] == CHECK_PASS, cid
        assert c[cid]["reason"] == "positively_established_in_run"
        assert c[cid]["facts"]  # the facts the PASS rests on are disclosed
    # Remove one positive fact from one member -> that check can no longer PASS.
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", omit=("ha_local_role",)))
    c2 = checks(unit(cp_report(snap), _CP_UNIT))
    assert c2["viable_target"]["status"] == CHECK_INSUFFICIENT
    assert c2["no_split_brain"]["status"] == CHECK_INSUFFICIENT


def test_09_seven_check_vocabulary_unchanged():
    assert SEVEN == ("viable_target", "state_sync_current", "parity", "no_split_brain",
                     "control_sync_link_health", "preemption_known", "flap_history")
    assert {cid for _v, cid in FACT_CHECK_MAP} == set(SEVEN)
    assert len(FACT_CHECK_MAP) == 14
    for report in (cp_report(happy_cp()), pan_report(happy_pan()), cp_report()):
        for u in report["units"]:
            assert tuple(c["id"] for c in u["checks"]) == SEVEN
            assert {c["status"] for c in u["checks"]} <= {CHECK_PASS, CHECK_FAIL, CHECK_INSUFFICIENT}


def test_10_five_verdict_vocabulary_unchanged():
    assert FIVE == ("SAFE_TO_FAILOVER", "DEGRADED_PROCEED_WITH_RISK", "UNSAFE_DO_NOT_FAILOVER",
                    "INSUFFICIENT_EVIDENCE", "NOT_A_FAILOVER_UNIT")
    report = cp_report(happy_cp())
    assert tuple(report["summary"]) == FIVE
    for token in ("READY", "HEALTHY", "GREEN", "PASS"):
        assert token not in report["summary"]
    source = (ROOT / "utils" / "failover" / "preflight_readiness.py").read_text(encoding="utf-8")
    for token in ('"READY"', '"HEALTHY"', '"GREEN"'):
        assert token not in source


def _module_numeric_constants(path: Path) -> list[str]:
    """Module-level assignments of a bare number to a name that smells like a
    threshold/TTL -- the shape a silently-invented policy would take."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    hits = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, (int, float)):
            for target in node.targets:
                name = getattr(target, "id", "").lower()
                if any(t in name for t in ("ttl", "age", "skew", "flap", "threshold", "tolerance", "minutes", "seconds", "max_")):
                    hits.append(name)
    return hits


def test_11_d_f1_ttl_not_invented():
    for path in ("utils/failover/preflight_readiness.py", "utils/failover/assessment.py"):
        assert _module_numeric_constants(ROOT / path) == [], path
    intent = PreflightFact(
        name="cluster_recovery_intent", category=FactCategory.CONFIGURATION_INTENT, state=FactState.KNOWN, value="maintain",
        provenance=Provenance(
            collected_at="2026-09-01T00:00:00Z", preflight_run_id="run-1", source_vendor="checkpoint",
            source_plane=SourceOrigin.MANAGEMENT_INTENT, transport=Transport.CPRID_MDS,
            physical_device_identity=OpaqueToken("tok-m1"), operational_entity_id=_CP_UNIT,
            context=FactContext.physical(), outcome=Outcome.SUCCESS, collection_run_id="cfg-run-9",
        ),
    )
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY"), configuration_facts=(intent,))
    u = unit(cp_report(snap), _CP_UNIT)
    assert u["evidence"]["configuration_intent_freshness"] == "not_evaluable:D-F1"
    assert u["evidence"]["stale_intent_present"] is True
    assert "D-F1" in u["evidence"]["unresolved_policy_gates"]
    # The category-C fact is never a check input (AC-4).
    assert not any("cluster_recovery_intent" in c["facts"] for c in u["checks"])


def test_12_d_f2_skew_threshold_not_invented():
    u = unit(cp_report(happy_cp()), _CP_UNIT)
    assert u["evidence"]["member_skew_ms"] == 1000
    assert u["evidence"]["member_skew_policy"] == "recorded_not_bounded:D-F2"
    assert "D-F2" in u["evidence"]["unresolved_policy_gates"]
    # Cross-member checks still PASS on same-run evidence -- skew is exposed, not judged.
    assert checks(u)["no_split_brain"]["status"] == CHECK_PASS
    snap = cp_snapshot(cp_member("tok-m1", at="not-a-timestamp"), cp_member("tok-m2", role="STANDBY"))
    assert unit(cp_report(snap), _CP_UNIT)["evidence"]["member_skew_ms"] is None


def test_13_d_f3_flap_threshold_not_invented():
    for snap, uid in ((happy_cp(), _CP_UNIT), (happy_pan(), _PAN_UNIT)):
        report = cp_report(snap) if uid == _CP_UNIT else pan_report(snap)
        c = checks(unit(report, uid))["flap_history"]
        assert c["status"] == CHECK_INSUFFICIENT
        assert c["reason"] == "threshold_policy_unresolved:D-F3"
    # A large count is still only observed -- no "N flaps = bad" rule exists.
    snap = cp_snapshot(cp_member("tok-m1", failover_count=9000), cp_member("tok-m2", role="STANDBY", failover_count=9000))
    u = unit(cp_report(snap), _CP_UNIT)
    assert checks(u)["flap_history"]["status"] == CHECK_INSUFFICIENT
    assert u["evidence"]["observed"]["cp_failover_count"] == [9000, 9000]
    assert u["verdict"] != VERDICT_UNSAFE


def test_14_d_v7b_remains_unresolved():
    c = checks(unit(cp_report(happy_cp()), _CP_UNIT))["preemption_known"]
    assert c["status"] == CHECK_INSUFFICIENT
    assert c["reason"] == "configured_recovery_not_readable_d_v7b"
    assert "A9" in c["missing_evidence"] and "not authorized" in c["missing_evidence"]
    spec = FACT_CHECK_MAP[("checkpoint", "preemption_known")]
    assert spec.positive_facts == () and spec.not_evaluable_reason
    # The canonical roll-up still requires it: SAFE stays blocked by this check.
    assert unit(cp_report(happy_cp()), _CP_UNIT)["verdict"] == VERDICT_INSUFFICIENT


def test_15_pan_b2_remains_not_established():
    u = unit(pan_report(happy_pan()), _PAN_UNIT)
    assert u["evidence"]["prerequisites"]["pair_identity"] == "established_configuration_intent"
    assert u["members"] == ["pan-a", "pan-b"]  # hostname-keyed fallback preserved
    consulted = {f for c in u["checks"] for f in c["facts"]}
    assert not any("serial" in f for f in consulted)
    assert not any("serial" in rule.fact for spec in FACT_CHECK_MAP.values() for rule in spec.positive_facts)
    assert "B2" not in json.dumps(u["evidence"])


# =====================================================================
# §28 Check Point tests 16-25
# =====================================================================

def test_16_two_cp_members_one_clusterxl_unit():
    report = cp_report(happy_cp())
    cp_units = [u for u in report["units"] if u["vendor"] == "checkpoint"]
    assert len(cp_units) == 1
    assert cp_units[0]["unit_type"] == "cp_clusterxl_cluster"
    assert cp_units[0]["members"] == ["m1", "m2"]


def test_17_physical_member_not_emitted_as_separate_failover_unit():
    report = cp_report(happy_cp())
    assert {u["unit_id"] for u in report["units"]} == {_CP_UNIT}
    assert report["preflight"] == {"snapshots_supplied": 1, "applied": [_CP_UNIT], "unmatched": [], "ambiguous": []}


def test_18_fresh_s5_member_evidence_feeds_cluster_assessment():
    # Stored telemetry says nothing; the fresh snapshot alone drives the checks.
    u = unit(cp_report(happy_cp()), _CP_UNIT)
    assert u["evidence"]["basis"] == EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT
    assert u["evidence"]["preflight_run_id"] == "run-1"
    assert u["evidence"]["prerequisites"]["members_observed"] == 2
    assert checks(u)["viable_target"]["status"] == CHECK_PASS
    # Stored telemetry that disagrees is not blended in: snapshot basis wins for this unit.
    stale = {"m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"}, "m2": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"}}
    u2 = unit(cp_report(happy_cp(), cp_ha_runtime=stale), _CP_UNIT)
    assert u2["verdict"] != VERDICT_UNSAFE
    # A unit with no snapshot keeps the stored-telemetry basis.
    u3 = unit(cp_report(cp_ha_runtime=stale), _CP_UNIT)
    assert u3["evidence"]["basis"] == EVIDENCE_BASIS_STORED_TELEMETRY and u3["verdict"] == VERDICT_UNSAFE


def test_19_explicit_split_brain_evidence_is_fail_closed_dangerous():
    snap = cp_snapshot(cp_member("tok-m1", role="ACTIVE"), cp_member("tok-m2", role="ACTIVE"))
    u = unit(cp_report(snap), _CP_UNIT)
    assert u["verdict"] == VERDICT_UNSAFE and u["reason"] == "split_brain_observed"
    assert checks(u)["no_split_brain"]["status"] == CHECK_FAIL


def test_20_collection_failure_does_not_masquerade_as_split_brain():
    snap = cp_snapshot(cp_member("tok-m1", role="ACTIVE"), cp_member("tok-m2", failed=("ha_local_role",)))
    u = unit(cp_report(snap), _CP_UNIT)
    c = checks(u)
    assert c["no_split_brain"]["status"] == CHECK_INSUFFICIENT
    assert c["no_split_brain"]["reason"] == "collection_failed:ha_local_role"
    assert c["viable_target"]["status"] == CHECK_INSUFFICIENT
    assert u["verdict"] == VERDICT_INSUFFICIENT
    # Two ACTIVE observations from different runs are not split-brain either.
    snap2 = cp_snapshot(cp_member("tok-m1", role="ACTIVE"), cp_member("tok-m2", role="ACTIVE", run_id_overrides={"ha_local_role": "run-OLD"}))
    assert unit(cp_report(snap2), _CP_UNIT)["verdict"] == VERDICT_INSUFFICIENT


def _vsx_snapshot(**overrides):
    return cp_snapshot(
        cp_member("tok-v1", mode="vsx_single_vs_failover", **overrides),
        cp_member("tok-v2", role="STANDBY", mode="vsx_single_vs_failover"),
        unit_type="vsx",
    )


def test_21_vsx_physical_cluster_remains_one_parent_operational_unit():
    report = cp_report(_vsx_snapshot(), rows=vsx_rows())
    parent = unit(report, _CP_UNIT)
    assert parent["unit_type"] == "cp_vsx_cluster" and parent["parent_id"] is None
    assert parent["members"] == ["vsx-1", "vsx-2"]
    assert parent["cluster_mode"] == "vsx_single_vs_failover"
    assert checks(parent)["viable_target"]["status"] == CHECK_PASS
    assert report["preflight"]["applied"] == [_CP_UNIT]


def test_22_vsid_remains_subordinate():
    report = cp_report(_vsx_snapshot(), rows=vsx_rows())
    vs = unit(report, f"{_CP_UNIT}__vsid_2")
    assert vs["unit_type"] == "cp_vsx_virtual_system" and vs["parent_id"] == _CP_UNIT
    assert sorted(vs["members"]) == ["vsx-1__vsid_2", "vsx-2__vsid_2"]
    assert len([u for u in report["units"] if u["parent_id"] is None]) == 1


def test_23_vs_verdict_does_not_inherit_physical_parent_verdict():
    report = cp_report(_vsx_snapshot(pnote=True), rows=vsx_rows())
    assert unit(report, _CP_UNIT)["verdict"] == VERDICT_UNSAFE
    vs = unit(report, f"{_CP_UNIT}__vsid_2")
    assert vs["verdict"] == VERDICT_INSUFFICIENT
    assert vs["evidence"]["basis"] == EVIDENCE_BASIS_STORED_TELEMETRY  # the physical snapshot never reached it


def test_23b_vs_gets_honest_reason_when_its_physical_parent_has_fresh_evidence():
    """S8-B VSX operator-review finding: after a real `--cp-ha-preflight-check`
    on the VSX physical parent, its VS children (out of that battery's scope
    -- B1 is enumeration only) must not read as though no preflight ever ran.
    Same verdict/basis as test_23 -- only the reason text changes."""
    report = cp_report(_vsx_snapshot(), rows=vsx_rows())
    assert report["preflight"]["applied"] == [_CP_UNIT]
    vs = unit(report, f"{_CP_UNIT}__vsid_2")
    assert vs["verdict"] == VERDICT_INSUFFICIENT
    assert vs["evidence"]["basis"] == EVIDENCE_BASIS_STORED_TELEMETRY
    for check in vs["checks"]:
        assert check["status"] == CHECK_INSUFFICIENT
        assert check["reason"] == "vs_state_out_of_physical_scope_preflight_battery"
        assert check["missing_evidence"]


def test_23c_vs_keeps_legacy_reason_when_no_fresh_preflight_ran_at_all():
    """Stored-telemetry-only path (no `preflight_snapshots` given): OP.0a
    behavior is unchanged -- the new reason only replaces the legacy one when
    THIS run's fresh evidence actually reached the VS's physical parent."""
    report = compute_ha_readiness(vsx_rows())
    vs = unit(report, f"{_CP_UNIT}__vsid_2")
    reasons = {check["reason"] for check in vs["checks"]}
    assert reasons <= {"not_evaluable_without_preflight_battery", "no_ha_runtime_evidence_for_unit"}
    assert "vs_state_out_of_physical_scope_preflight_battery" not in reasons


def test_24_no_vsls_mutation_behavior():
    """OP.0b S4-A' (real-env VSLS finding, PO correction 2026-09-04): VSLS is
    a legitimate, supported CP failover mode now (`vsx_vsls`) -- this
    replaces the earlier blanket "no vsls" source ban. What stays banned is
    any VSLS *mutation*/execution primitive; this evaluator computes
    readiness only, never a CLASS 2 action."""
    for path in ("utils/failover/preflight_readiness.py", "utils/failover/assessment.py"):
        code = _code_only(ROOT / path).lower()
        for forbidden in ("vsx_util", "clusterxl_admin", "cphastop"):
            assert forbidden not in code
    assert "vsx_single_vs_failover" in readiness_module.CP_SUPPORTED_FAILOVER_MODES
    assert "vsx_vsls" in readiness_module.CP_SUPPORTED_FAILOVER_MODES
    # A VS is never keyed as its own physical failover target: its unit id is subordinate to the parent.
    report = cp_report(_vsx_snapshot(), rows=vsx_rows())
    assert all(u["unit_id"].startswith(_CP_UNIT) for u in report["units"])


def test_25_contradictory_non_vs0_evidence_is_unknown_never_known_bad():
    vs_unit = f"{_CP_UNIT}__vsid_2"
    ctx = FactContext.vsid("2")
    snap = cp_snapshot(
        cp_member("tok-v1", unit=vs_unit, role="DOWN", attention=True, mode="vsx_single_vs_failover", context=ctx),
        cp_member("tok-v2", unit=vs_unit, role="STANDBY", mode="vsx_single_vs_failover", context=ctx),
        unit=vs_unit, unit_type="vsx",
    )
    report = cp_report(snap, rows=vsx_rows())
    vs = unit(report, vs_unit)
    assert vs["evidence"]["basis"] == EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT
    c = checks(vs)
    assert c["viable_target"]["status"] == CHECK_INSUFFICIENT
    assert c["viable_target"]["reason"].startswith("non_vs0_context_read_not_trusted")
    assert vs["verdict"] == VERDICT_INSUFFICIENT
    # The same DOWN/attention read in the PHYSICAL context is trusted.
    phys = cp_snapshot(cp_member("tok-m1", role="DOWN", attention=True), cp_member("tok-m2", role="STANDBY"))
    assert unit(cp_report(phys), _CP_UNIT)["verdict"] == VERDICT_UNSAFE


# =====================================================================
# §29 Palo Alto tests 26-34
# =====================================================================

def test_26_two_independently_observed_pan_members_remain_one_pair_unit():
    report = pan_report(happy_pan())
    pan_units = [u for u in report["units"] if u["vendor"] == "panorama"]
    assert len(pan_units) == 1 and pan_units[0]["unit_id"] == _PAN_UNIT
    c = checks(pan_units[0])
    for cid in ("viable_target", "state_sync_current", "parity", "no_split_brain", "control_sync_link_health", "preemption_known"):
        assert c[cid]["status"] == CHECK_PASS, cid
    assert pan_units[0]["verdict"] == VERDICT_INSUFFICIENT  # flap_history (D-F3) still blocks


def test_27_peer_claim_alone_does_not_synthesize_peer():
    # One member's own state plus its claim about the peer: no second member.
    snap = pan_snapshot(pan_member("tok-p1", state="active", peer_state="passive"))
    u = unit(pan_report(snap), _PAN_UNIT)
    c = checks(u)
    assert c["viable_target"]["status"] == CHECK_INSUFFICIENT
    assert c["viable_target"]["reason"] == "peer_not_independently_observed"
    assert c["no_split_brain"]["status"] == CHECK_INSUFFICIENT
    assert u["evidence"]["prerequisites"]["members_observed"] == 1
    assert "peer_state_claim" not in {f for ch in u["checks"] for f in ch["facts"]}
    # The OP.0a stored-telemetry path no longer uplifts a single member from `peer_state` either (AC-5).
    rows = pan_rows()
    single = compute_ha_readiness(rows, pan_ha_runtime={"pan-a": _PAN_RUNTIME["pan-a"]}, pan_ha_peers={"pan-a": "192.0.2.99"})
    su = unit(single, "pan-a")
    assert su["members"] == ["pan-a"] and su["reason"] == "pan_ha_peer_unresolved"
    assert checks(su)["viable_target"]["status"] == CHECK_INSUFFICIENT
    assert checks(su)["no_split_brain"]["status"] == CHECK_INSUFFICIENT


def test_28_b2_not_established_by_s7():
    # A two-member snapshot for a unit the hostname-keyed fallback left single
    # (asymmetric peer-ip) cannot promote itself to a pair: attribution fails closed.
    rows = pan_rows()
    snap = pan_snapshot(pan_member("tok-p1", unit="pan-a"), pan_member("tok-p2", unit="pan-a", state="passive"), unit="pan-a")
    report = compute_ha_readiness(rows, pan_ha_runtime=_PAN_RUNTIME, pan_ha_peers={"pan-a": "10.0.0.2", "pan-b": "10.0.0.7"}, preflight_snapshots=[snap])
    u = unit(report, "pan-a")
    assert u["members"] == ["pan-a"] and u["unresolved_reason"] == "pan_ha_peer_asymmetric"
    assert u["evidence"]["prerequisites"]["attribution"] == "invalid"
    assert "more_members_than_unit" in u["evidence"]["prerequisites"]["attribution_problems"]
    assert all(c["status"] == CHECK_INSUFFICIENT for c in u["checks"])
    assert u["verdict"] == VERDICT_INSUFFICIENT
    assert "serial" not in _code_only(ROOT / "utils" / "failover" / "preflight_readiness.py").lower()


def test_29_p2_p4_facts_feed_only_frozen_checks():
    by_fact: dict[str, set[str]] = {}
    for (vendor, cid), spec in FACT_CHECK_MAP.items():
        if vendor != "panorama":
            continue
        for rule in spec.positive_facts:
            by_fact.setdefault(rule.fact, set()).add(cid)
    assert by_fact["pan_path_monitoring_any_down"] == {"control_sync_link_health"}
    assert by_fact["peer_conn_ha1_status"] == {"control_sync_link_health"}
    assert by_fact["peer_conn_ha2_status"] == {"state_sync_current", "control_sync_link_health"}
    assert by_fact["local_state"] == {"viable_target", "no_split_brain"}
    assert "peer_state_claim" not in by_fact and "peer_conn_status" not in by_fact
    assert set(itertools.chain.from_iterable(by_fact.values())) <= set(SEVEN)


def test_30_unknown_path_monitor_state_never_becomes_healthy():
    snap = pan_snapshot(pan_member("tok-p1", unknown=("pan_path_monitoring_any_down",)), pan_member("tok-p2", state="passive"))
    c = checks(unit(pan_report(snap), _PAN_UNIT))
    assert c["control_sync_link_health"]["status"] == CHECK_INSUFFICIENT
    assert c["control_sync_link_health"]["reason"] == "unknown:pan_path_monitoring_any_down"
    # Unrecognised link vocabulary is not healthy either (D-V1: only "up" is).
    snap2 = pan_snapshot(pan_member("tok-p1", conn_ha1="purple"), pan_member("tok-p2", state="passive"))
    c2 = checks(unit(pan_report(snap2), _PAN_UNIT))
    assert c2["control_sync_link_health"]["status"] == CHECK_INSUFFICIENT
    assert c2["control_sync_link_health"]["reason"] == "value_not_established:peer_conn_ha1_status"


def test_30b_unrecognised_role_vocabulary_is_never_no_viable_target_or_split_brain():
    """An unknown vendor state token is UNKNOWN, not PASS -- and not evidence
    of absence either: it must never be counted as "no standby exists"."""
    snap = pan_snapshot(pan_member("tok-p1", state="active"), pan_member("tok-p2", state="purple"))
    u = unit(pan_report(snap), _PAN_UNIT)
    c = checks(u)
    assert c["viable_target"]["status"] == CHECK_INSUFFICIENT
    assert c["viable_target"]["reason"] == "value_not_established:local_state"
    assert c["no_split_brain"]["status"] == CHECK_INSUFFICIENT
    assert u["verdict"] == VERDICT_INSUFFICIENT
    cp = cp_snapshot(cp_member("tok-m1", role="ACTIVE"), cp_member("tok-m2", role="LOST"))
    cu = unit(cp_report(cp), _CP_UNIT)
    assert checks(cu)["viable_target"]["reason"] == "value_not_established:ha_local_role"
    assert cu["verdict"] == VERDICT_INSUFFICIENT


def test_31_explicit_known_path_failure_contributes_known_bad_evidence():
    snap = pan_snapshot(pan_member("tok-p1", path_any_down=True), pan_member("tok-p2", state="passive"))
    u = unit(pan_report(snap), _PAN_UNIT)
    assert checks(u)["control_sync_link_health"]["status"] == CHECK_FAIL
    assert u["verdict"] == VERDICT_UNSAFE and u["reason"] == "monitored_path_down_observed"
    snap2 = pan_snapshot(pan_member("tok-p1", conn_ha1="down"), pan_member("tok-p2", state="passive"))
    assert unit(pan_report(snap2), _PAN_UNIT)["reason"] == "ha1_link_down_observed"
    snap3 = pan_snapshot(pan_member("tok-p1", state="non-functional"), pan_member("tok-p2", state="passive"))
    assert unit(pan_report(snap3), _PAN_UNIT)["reason"] == "member_non_functional_state_observed"


def test_32_vsys_not_emitted_as_independent_failover_unit():
    report = pan_report(happy_pan())
    assert [u["unit_id"] for u in report["units"]] == [_PAN_UNIT]
    assert report["units"][0]["display_name"].startswith("VSYS ")
    assert "vsys" not in json.dumps([c for u in report["units"] for c in u["checks"]])


def test_33_serial_representation_remains_opaque():
    snap = pan_snapshot(pan_member("tok-p1", serial="0001A", peer_serial="0002B"), pan_member("tok-p2", state="passive", serial="0002B", peer_serial="0001A"))
    report = pan_report(snap)
    text = json.dumps(report)
    assert "0001A" not in text and "0002B" not in text
    assert "tok-p1" not in json.dumps(report["units"][0]["checks"])


def test_34_no_leading_zero_normalization():
    src = _code_only(ROOT / "utils" / "failover" / "preflight_readiness.py")
    assert "lstrip" not in src and "zfill" not in src and re.search(r"\bint\s*\(", src) is None
    # Two members whose opaque identities differ only by a leading zero stay distinct members.
    snap = pan_snapshot(pan_member("0001A"), pan_member("1A", state="passive"))
    u = unit(pan_report(snap), _PAN_UNIT)
    assert u["evidence"]["prerequisites"]["attribution"] == "ok"
    assert u["evidence"]["prerequisites"]["members_observed"] == 2


# =====================================================================
# OP.0b S8-C real-env correction: dedicated-HA1 pairing, explicit bounded
# candidate resolution, fresh reciprocal correspondence, management-as-HA1
# regression, and fail-closed negatives
# =====================================================================

#: A realistic dedicated-HA1 topology (the approved real S8-C pair's shape):
#: HA1 peer addressing is NOT the management address on either side, so
#: `_derive_pan_units`'s config-intent (`peer-ip` == `management_ip`)
#: heuristic forms two separate single-member units, exactly as designed
#: (task §22 "management-as-HA1 regression" -- this fixture is deliberately
#: the OTHER, non-regression case).
_DEDICATED_HA1_ROWS = [
    {"device": "pan-d1", "source": "panorama", "management_ip": "10.9.9.1", "inventory_status": {"data_state": "ok"}},
    {"device": "pan-d2", "source": "panorama", "management_ip": "10.9.9.2", "inventory_status": {"data_state": "ok"}},
]
_DEDICATED_HA1_RUNTIME = {
    "pan-d1": {"enabled": "yes", "state": "active", "mode": "active-passive", "peer_state": "passive", "state_sync": "x"},
    "pan-d2": {"enabled": "yes", "state": "passive", "mode": "active-passive", "peer_state": "active", "state_sync": "x"},
}
#: Configured HA1 peer-ip -- a DIFFERENT address family from management_ip
#: above, so `_derive_pan_units` cannot and must not resolve a pair from it.
_DEDICATED_HA1_PEERS = {"pan-d1": "10.250.0.22", "pan-d2": "10.250.0.21"}


def _dedicated_ha1_member(member, *, state, peer_state, mgmt_token, peer_mgmt_token, endpoint_token, **kw):
    return pan_member(
        member, unit="pan-d1+pan-d2", state=state, peer_state=peer_state,
        local_mgmt_ip=mgmt_token, peer_mgmt_ip=peer_mgmt_token, ha_group_id="20",
        local_management_endpoint=endpoint_token, **kw,
    )


def test_41_dedicated_ha1_explicit_candidate_resolves_and_evaluates():
    # The old universal invariant (peer-ip == management_ip) genuinely fails
    # for this topology -- proven first, so the rest of the test cannot be
    # accidentally validating a scenario the legacy path already handled.
    legacy_units = assessment_module.derive_ha_units(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
    )
    assert {u.unit_id for u in legacy_units} == {"pan-d1", "pan-d2"}
    assert all(len(u.members) == 1 for u in legacy_units)

    snap = pan_snapshot(
        _dedicated_ha1_member("tok-d1", state="active", peer_state="passive",
                               mgmt_token="MGMTA", peer_mgmt_token="MGMTB", endpoint_token="MGMTA"),
        _dedicated_ha1_member("tok-d2", state="passive", peer_state="active", at=_T1,
                               mgmt_token="MGMTB", peer_mgmt_token="MGMTA", endpoint_token="MGMTB"),
        unit="pan-d1+pan-d2",
    )
    report = compute_ha_readiness(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        preflight_snapshots=[snap], pan_explicit_candidate_members=["pan-d1", "pan-d2"],
    )
    # The two now-redundant legacy single-member units for THIS invocation's
    # own report are replaced by the one bounded candidate pair (real-env
    # UI finding, same session: showing all three was unreadable next to
    # Check Point's one row per cluster) -- _derive_pan_units's own output
    # (asserted above) is untouched; only this report's rendered units differ.
    unit_ids = {u["unit_id"] for u in report["units"] if u["vendor"] == "panorama"}
    assert unit_ids == {"pan-d1+pan-d2"}

    u = unit(report, "pan-d1+pan-d2")
    assert u["explicit_candidate"] is True
    assert u["evidence"]["prerequisites"]["pair_identity"] == "explicit_bounded_candidate_pending_correspondence"
    c = checks(u)
    for cid in ("viable_target", "state_sync_current", "no_split_brain"):
        assert c[cid]["status"] == CHECK_PASS, (cid, c[cid])

    correspondence = u["evidence"]["pan_pair_correspondence"]
    assert correspondence["state"] == "MATCH"
    assert correspondence["self_management_correspondence"] == {"tok-d1": "MATCH", "tok-d2": "MATCH"}
    assert correspondence["reciprocal_peer_management_correspondence"] == {
        "tok-d1_claims_tok-d2": "MATCH", "tok-d2_claims_tok-d1": "MATCH",
    }
    assert correspondence["mode_correspondence"] == "MATCH"


def test_42_dedicated_ha1_correspondence_is_not_pan_b2():
    # The MATCH result above is genuine, fresh, reciprocal management-plane
    # correspondence -- deliberately NOT PAN B2 (that stays the frozen
    # serial-based bidirectional requirement). No mechanism here writes,
    # sets, or infers a B2 flag anywhere.
    src = _code_only(ROOT / "utils" / "failover" / "preflight_readiness.py")
    assert "serial" not in src.lower()
    assert "b2" not in src.lower()


def test_43_management_as_ha1_regression_still_works_unaided():
    # The pre-existing, already-covered (test_26) topology: peer-ip ==
    # management_ip, so the LEGACY derivation alone already forms the pair.
    # Passing pan_explicit_candidate_members for the SAME pair must not
    # create a second, weaker-graded duplicate unit.
    report = compute_ha_readiness(
        pan_rows(), pan_ha_runtime=_PAN_RUNTIME, pan_ha_peers=_PAN_PEERS,
        preflight_snapshots=[happy_pan()], pan_explicit_candidate_members=["pan-a", "pan-b"],
    )
    pan_units = [u for u in report["units"] if u["vendor"] == "panorama"]
    assert len(pan_units) == 1 and pan_units[0]["unit_id"] == _PAN_UNIT
    assert pan_units[0]["explicit_candidate"] is False
    assert pan_units[0]["evidence"]["prerequisites"]["pair_identity"] == "established_configuration_intent"


def test_44_explicit_candidate_requires_exactly_two_members():
    units_one = assessment_module.derive_ha_units(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        pan_explicit_candidate_members=["pan-d1"],
    )
    assert not any(u.explicit_candidate for u in units_one)

    units_unknown = assessment_module.derive_ha_units(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        pan_explicit_candidate_members=["pan-d1", "pan-nonexistent"],
    )
    assert not any(u.explicit_candidate for u in units_unknown)


def test_45_self_management_mismatch_never_blocks_collection_but_is_reported():
    # A candidate's P2 self-reported mgmt-ip disagrees with the endpoint P1
    # actually dialed for it -- collection/checks still run (§9/§10: "collection
    # itself must not be prevented"), the disagreement is disclosed honestly.
    snap = pan_snapshot(
        _dedicated_ha1_member("tok-d1", state="active", peer_state="passive",
                               mgmt_token="WRONG_SELF_REPORT", peer_mgmt_token="MGMTB", endpoint_token="MGMTA"),
        _dedicated_ha1_member("tok-d2", state="passive", peer_state="active", at=_T1,
                               mgmt_token="MGMTB", peer_mgmt_token="MGMTA", endpoint_token="MGMTB"),
        unit="pan-d1+pan-d2",
    )
    report = compute_ha_readiness(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        preflight_snapshots=[snap], pan_explicit_candidate_members=["pan-d1", "pan-d2"],
    )
    u = unit(report, "pan-d1+pan-d2")
    assert checks(u)["no_split_brain"]["status"] == CHECK_PASS  # collection not blocked
    correspondence = u["evidence"]["pan_pair_correspondence"]
    assert correspondence["state"] == "MISMATCH"
    assert correspondence["self_management_correspondence"]["tok-d1"] == "MISMATCH"


def test_46_reciprocal_peer_mismatch_is_reported_asymmetrically():
    # A claims B correctly; B's claim about A does not match A's own dialed
    # endpoint -- an asymmetric relationship, reported per-direction, never
    # collapsed into a false MATCH from one side alone.
    snap = pan_snapshot(
        _dedicated_ha1_member("tok-d1", state="active", peer_state="passive",
                               mgmt_token="MGMTA", peer_mgmt_token="MGMTB", endpoint_token="MGMTA"),
        _dedicated_ha1_member("tok-d2", state="passive", peer_state="active", at=_T1,
                               mgmt_token="MGMTB", peer_mgmt_token="SOME_OTHER_ADDRESS", endpoint_token="MGMTB"),
        unit="pan-d1+pan-d2",
    )
    report = compute_ha_readiness(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        preflight_snapshots=[snap], pan_explicit_candidate_members=["pan-d1", "pan-d2"],
    )
    correspondence = unit(report, "pan-d1+pan-d2")["evidence"]["pan_pair_correspondence"]
    assert correspondence["state"] == "MISMATCH"
    assert correspondence["reciprocal_peer_management_correspondence"]["tok-d1_claims_tok-d2"] == "MATCH"
    assert correspondence["reciprocal_peer_management_correspondence"]["tok-d2_claims_tok-d1"] == "MISMATCH"


def test_47_ha_group_mismatch_never_gates_correspondence_state():
    # group-id is corroborating-only (unconfirmed XML path) -- a mismatch
    # there must never flip the overall correspondence state on its own.
    snap = pan_snapshot(
        pan_member("tok-d1", unit="pan-d1+pan-d2", state="active", peer_state="passive",
                    local_mgmt_ip="MGMTA", peer_mgmt_ip="MGMTB", ha_group_id="20", local_management_endpoint="MGMTA"),
        pan_member("tok-d2", unit="pan-d1+pan-d2", state="passive", peer_state="active", at=_T1,
                    local_mgmt_ip="MGMTB", peer_mgmt_ip="MGMTA", ha_group_id="21", local_management_endpoint="MGMTB"),
        unit="pan-d1+pan-d2",
    )
    report = compute_ha_readiness(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        preflight_snapshots=[snap], pan_explicit_candidate_members=["pan-d1", "pan-d2"],
    )
    correspondence = unit(report, "pan-d1+pan-d2")["evidence"]["pan_pair_correspondence"]
    assert correspondence["group_id_correspondence"] == "MISMATCH"
    assert correspondence["state"] == "MATCH"  # group-id never gates the roll-up


def test_48_group_id_absent_is_not_evaluable_never_missing():
    # Full management-plane correspondence, group-id specifically absent --
    # isolates that "absent" reads as NOT_EVALUABLE (best-effort field,
    # unconfirmed XML path), never MISSING (which would read as "should be
    # there and isn't"), and never gates the roll-up either way.
    snap = pan_snapshot(
        pan_member("tok-d1", unit="pan-d1+pan-d2", state="active", peer_state="passive",
                   local_mgmt_ip="MGMTA", peer_mgmt_ip="MGMTB", local_management_endpoint="MGMTA"),
        pan_member("tok-d2", unit="pan-d1+pan-d2", state="passive", peer_state="active", at=_T1,
                   local_mgmt_ip="MGMTB", peer_mgmt_ip="MGMTA", local_management_endpoint="MGMTB"),
        unit="pan-d1+pan-d2",
    )
    report = compute_ha_readiness(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        preflight_snapshots=[snap], pan_explicit_candidate_members=["pan-d1", "pan-d2"],
    )
    correspondence = unit(report, "pan-d1+pan-d2")["evidence"]["pan_pair_correspondence"]
    assert correspondence["group_id_correspondence"] == "NOT_EVALUABLE"
    assert correspondence["state"] == "MATCH"


def test_49_only_one_member_passing_p1_yields_not_evaluable_correspondence():
    snap = pan_snapshot(
        _dedicated_ha1_member("tok-d1", state="active", peer_state="passive",
                               mgmt_token="MGMTA", peer_mgmt_token="MGMTB", endpoint_token="MGMTA"),
        _dedicated_ha1_member("tok-d2", state="passive", peer_state="active", at=_T1, gate=False,
                               mgmt_token="MGMTB", peer_mgmt_token="MGMTA", endpoint_token="MGMTB"),
        unit="pan-d1+pan-d2",
    )
    report = compute_ha_readiness(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        preflight_snapshots=[snap], pan_explicit_candidate_members=["pan-d1", "pan-d2"],
    )
    u = unit(report, "pan-d1+pan-d2")
    assert all(c["status"] == CHECK_INSUFFICIENT for c in u["checks"])
    assert u["evidence"]["pan_pair_correspondence"]["state"] == "NOT_EVALUABLE"


def test_50_explicit_candidate_never_widens_target_boundary():
    # Structural proof: nothing in the S8-C correction path contacts, derives,
    # or evaluates a THIRD PAN member beyond an explicit two-id request.
    units = assessment_module.derive_ha_units(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        pan_explicit_candidate_members=["pan-d1", "pan-d2"],
    )
    candidate = next(u for u in units if u.explicit_candidate)
    assert set(candidate.members) == {"pan-d1", "pan-d2"}
    assert len(candidate.members) == 2


def test_50b_suppression_never_touches_an_unrelated_device():
    # A third PAN device NOT part of the explicit selection keeps its own
    # single-member unit untouched -- only the two selected devices' orphan
    # halves are replaced.
    rows = [*_DEDICATED_HA1_ROWS, {
        "device": "pan-solo3", "source": "panorama", "management_ip": "10.9.9.3",
        "inventory_status": {"data_state": "ok"},
    }]
    runtime = {**_DEDICATED_HA1_RUNTIME, "pan-solo3": {"enabled": "yes", "mode": "active-passive"}}
    units = assessment_module.derive_ha_units(
        rows, pan_ha_runtime=runtime, pan_ha_peers=_DEDICATED_HA1_PEERS,
        pan_explicit_candidate_members=["pan-d1", "pan-d2"],
    )
    unit_ids = {u.unit_id for u in units if u.vendor == "panorama"}
    assert unit_ids == {"pan-d1+pan-d2", "pan-solo3"}


def test_51_running_sync_enabled_never_gates_parity():
    # OP.0b S8-C real-env correction §15: real evidence showed one member
    # with Configuration Synchronization Enabled=no and the other Enabled=
    # yes, while BOTH report Running Configuration: synchronized. Verified,
    # not assumed: no defect exists here -- `group_running_sync_enabled` was
    # never consulted by the parity predicate at all (only `group_running_sync`
    # per-member, and `local_build_rel` for cross-member equality).
    spec = FACT_CHECK_MAP[("panorama", "parity")]
    fact_names = {rule.fact for rule in spec.positive_facts} | set(spec.predicate_facts)
    assert "group_running_sync_enabled" not in fact_names
    assert "group_running_sync" in fact_names

    snap = pan_snapshot(
        pan_member("tok-d1", unit="pan-d1+pan-d2", state="active", peer_state="passive"),
        pan_member("tok-d2", unit="pan-d1+pan-d2", state="passive", peer_state="active", at=_T1),
        unit="pan-d1+pan-d2",
    )
    report = compute_ha_readiness(
        _DEDICATED_HA1_ROWS, pan_ha_runtime=_DEDICATED_HA1_RUNTIME, pan_ha_peers=_DEDICATED_HA1_PEERS,
        preflight_snapshots=[snap], pan_explicit_candidate_members=["pan-d1", "pan-d2"],
    )
    assert checks(unit(report, "pan-d1+pan-d2"))["parity"]["status"] == CHECK_PASS


# =====================================================================
# §30 OP.0a / OP.0c regression 35-40
# =====================================================================

_V1_UNIT_KEYS = {"unit_id", "unit_type", "vendor", "members", "cluster_mode", "display_name", "parent_id", "verdict", "reason", "checks"}
_V1_CHECK_KEYS = {"id", "label", "status", "reason", "missing_evidence"}


def test_35_op0a_public_readiness_result_contract_remains_compatible():
    for report in (cp_report(), cp_report(happy_cp()), pan_report(happy_pan())):
        assert report["schema"] == "securityexpert-ha-readiness-v1"
        assert {"schema", "generated_at", "units", "summary"} <= set(report)
        for u in report["units"]:
            assert _V1_UNIT_KEYS <= set(u)
            for c in u["checks"]:
                assert _V1_CHECK_KEYS <= set(c)
    # Calling without the new keyword is the unchanged OP.0a path.
    legacy = compute_ha_readiness(cp_rows(), cp_ha_runtime={"m1": {"ha_role": "ACTIVE"}, "m2": {"ha_role": "STANDBY"}})
    assert legacy["preflight"]["snapshots_supplied"] == 0
    assert unit(legacy, _CP_UNIT)["evidence"]["basis"] == EVIDENCE_BASIS_STORED_TELEMETRY


def test_36_op0c_receives_canonical_result_without_client_side_verdict_logic():
    payload = build_failover_readiness_payload(cp_rows(), preflight_snapshots=[happy_cp()])
    report = cp_report(happy_cp())
    assert [u["verdict"] for u in payload["units"]] == [u["verdict"] for u in report["units"]]
    assert payload["units"][0]["evidence"]["basis"] == EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT
    assert payload["preflight"]["applied"] == [_CP_UNIT]
    js = (ROOT / "static" / "failover_readiness_ui.js").read_text(encoding="utf-8")
    for forbidden in ('"PASS"', '"FAIL"', "split_brain", ".every(", ".some(", "status ===", "verdict ===", "checks.filter"):
        assert forbidden not in js, forbidden
    ui = (ROOT / "utils" / "failover_readiness_ui.py").read_text(encoding="utf-8")
    assert "evaluate_snapshot_checks" not in ui and "PreflightFact" not in ui


def test_37_five_verdict_labels_still_render():
    assert tuple(VERDICT_LABELS) == FIVE
    js = (ROOT / "static" / "failover_readiness_ui.js").read_text(encoding="utf-8")
    for verdict in FIVE:
        assert verdict in js
    payload = build_failover_readiness_payload(cp_rows(), preflight_snapshots=[happy_cp()])
    assert tuple(payload["verdict_labels"]) == FIVE


def test_38_seven_check_rows_still_render():
    payload = build_failover_readiness_payload(pan_rows(), config_result=None, preflight_snapshots=[happy_pan()])
    js = (ROOT / "static" / "failover_readiness_ui.js").read_text(encoding="utf-8")
    assert "unit.checks" in js and "check.label" in js and "check.missing_evidence" in js
    for u in payload["units"]:
        assert len(u["checks"]) == 7
        assert all(c["label"] for c in u["checks"])


def test_39_blocking_reasons_remain_visible():
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", pnote=True, link_down=True))
    payload = build_failover_readiness_payload(cp_rows(), preflight_snapshots=[snap])
    u = payload["units"][0]
    assert u["verdict"] == VERDICT_UNSAFE and u["reason"] == "critical_device_problem_observed"
    c = checks(u)
    assert c["control_sync_link_health"]["status"] == CHECK_FAIL  # the second failure is still listed
    assert all(c[cid]["missing_evidence"] for cid in SEVEN if c[cid]["status"] == CHECK_INSUFFICIENT)
    assert "INSUFFICIENT_EVIDENCE" in payload["framing_note"] and "SAFE_TO_FAILOVER" in payload["framing_note"]


def test_40_no_execution_controls_appear():
    forbidden = ("execute", "failover_now", "authorize", "confirmation_token", "rollback", "mutation", "lock")
    payload = build_failover_readiness_payload(cp_rows(), preflight_snapshots=[happy_cp()])
    keys = json.dumps(sorted(payload)) + json.dumps(sorted(payload["units"][0]))
    assert not any(token in keys for token in forbidden)
    import utils.failover as failover

    for name in dir(failover):
        assert not any(t in name.lower() for t in ("executor", "plan", "action", "rollback", "execute"))
    src = _code_only(ROOT / "utils" / "failover" / "preflight_readiness.py").lower()
    for token in ("confirmation_token", "operational_lock", "mutation_plan", "class_2", "executor"):
        assert token not in src


# =====================================================================
# §31 single authority / §32 pure zero-I/O / §21 reachability / misc
# =====================================================================

def _functions_returning_verdicts(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    found = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            for sub in ast.walk(node):
                if isinstance(sub, ast.Return) and sub.value is not None:
                    for name in ast.walk(sub.value):
                        if isinstance(name, ast.Name) and name.id.startswith("VERDICT_"):
                            found.add(node.name)
    return found


def test_single_canonical_readiness_rollup_implementation():
    package = ROOT / "utils" / "failover"
    verdict_functions = {
        f"{path.stem}.{fn}" for path in package.glob("*.py") for fn in _functions_returning_verdicts(path)
    }
    assert verdict_functions == {"assessment._verdict_for"}
    src = _code_only(package / "preflight_readiness.py")
    for token in ("VERDICT_", "SAFE_TO_FAILOVER", "UNSAFE_DO_NOT_FAILOVER", "NOT_A_FAILOVER_UNIT", "rollup", "roll_up"):
        assert token not in src, token
    # No second roll-up under another name anywhere in the package.
    for path in package.glob("*.py"):
        assert not re.search(r"def \w*(rollup|roll_up|verdict_v2|readiness_v2)\w*\(", _code_only(path)), path


def test_evaluator_source_contains_no_io_primitives():
    src = _code_only(ROOT / "utils" / "failover" / "preflight_readiness.py")
    for token in ("paramiko", "requests", "api_post", "subprocess", "os.system", "sleep", "socket", "open",
                  "SSHClient", "_connect", "urllib", "http", "run_cp_preflight", "run_pan_preflight", "getpass", "credential",
                  "Path", "read_text", "json"):
        assert token not in src, token
    tree = ast.parse((ROOT / "utils" / "failover" / "preflight_readiness.py").read_text(encoding="utf-8"))
    imported = {
        (n.module or "") for n in ast.walk(tree) if isinstance(n, ast.ImportFrom)
    } | {alias.name for n in ast.walk(tree) if isinstance(n, ast.Import) for alias in n.names}
    assert all(m in {"__future__", "dataclasses", "typing"} or m.startswith("utils.failover.") for m in imported), imported


def test_evaluator_performs_no_socket_io_and_imports_no_collector(monkeypatch):
    import socket

    def _boom(*args, **kwargs):  # pragma: no cover - must never run
        raise AssertionError("S7 evaluator attempted network I/O")

    monkeypatch.setattr(socket, "socket", _boom)
    monkeypatch.setattr(socket, "create_connection", _boom)
    for name in ("checkpoint.preflight_collector", "panorama.preflight_collector"):
        sys.modules.pop(name, None)
    report = cp_report(happy_cp())
    pan_report(happy_pan())
    assert report["units"]
    assert "checkpoint.preflight_collector" not in sys.modules
    assert "panorama.preflight_collector" not in sys.modules


def test_safe_and_degraded_unreachable_over_snapshot_matrix():
    """§21: with every check S7 can evaluate green, SAFE/DEGRADED must still
    be unreachable -- CP: check 6 (D-V7b) + check 7 (D-F3); PAN: check 7
    (D-F3). Asserted over a generated matrix, not by reading."""
    seen = set()
    cp_roles = ["ACTIVE", "STANDBY", "DOWN", "ACTIVE ATTENTION"]
    for role_a, role_b, pnote, sync in itertools.product(cp_roles, cp_roles, (False, True), ("ok", "not_ok", None)):
        snap = cp_snapshot(cp_member("tok-m1", role=role_a, attention=role_a in {"DOWN", "ACTIVE ATTENTION"}, pnote=pnote, sync=sync),
                           cp_member("tok-m2", role=role_b, attention=role_b in {"DOWN", "ACTIVE ATTENTION"}, sync=sync))
        for u in cp_report(snap)["units"]:
            seen.add(u["verdict"])
            assert u["verdict"] not in (VERDICT_SAFE, VERDICT_DEGRADED)
    pan_states = ["active", "passive", "non-functional", "suspended"]
    for state_a, state_b, ha1, path_down in itertools.product(pan_states, pan_states, ("up", "down", "odd"), (False, True)):
        snap = pan_snapshot(pan_member("tok-p1", state=state_a, conn_ha1=ha1, path_any_down=path_down),
                            pan_member("tok-p2", state=state_b))
        for u in pan_report(snap)["units"]:
            seen.add(u["verdict"])
            assert u["verdict"] not in (VERDICT_SAFE, VERDICT_DEGRADED)
    assert {VERDICT_UNSAFE, VERDICT_INSUFFICIENT} <= seen


def test_rollup_refuses_safe_while_a_numeric_policy_is_open():
    """The one roll-up is intact (all-PASS with no open gate is SAFE) and the
    open-policy gate is explicit: all-PASS evidence that still depends on an
    unresolved D-F decision is INSUFFICIENT_EVIDENCE, never SAFE."""
    all_pass = [{"id": cid, "label": label, "status": CHECK_PASS, "reason": "x", "missing_evidence": ""} for cid, label in STOP_CONDITIONS]
    ha_unit = HaUnit(unit_id="u", unit_type="cp_clusterxl_cluster", vendor="checkpoint", members=["a", "b"], cluster_mode="ha_new_mode")
    assert assessment_module._verdict_for(ha_unit, all_pass, {"unresolved_policy_gates": []}) == (VERDICT_SAFE, "all_stop_conditions_passed")
    verdict, reason = assessment_module._verdict_for(ha_unit, all_pass, {"unresolved_policy_gates": ["D-F2", "D-F3"]})
    assert verdict == VERDICT_INSUFFICIENT and reason == "positive_verdict_blocked_by_unresolved_policy:D-F2,D-F3"
    assert UNRESOLVED_POLICY_DECISIONS == frozenset({"D-F1", "D-F2", "D-F3"})


def test_identity_gate_failure_excludes_member_evidence():
    snap = cp_snapshot(cp_member("tok-m1"), cp_member("tok-m2", role="STANDBY", gate=False, pnote=True))
    u = unit(cp_report(snap), _CP_UNIT)
    assert u["evidence"]["prerequisites"]["identity_gate"] == "failed"
    assert u["verdict"] == VERDICT_INSUFFICIENT  # the unattributable pnote is not used, even though it looks bad
    assert all(c["reason"] == "identity_gate_failed" for c in u["checks"] if c["id"] != "preemption_known")


def test_mode_gate_blocks_positive_results_for_unsupported_or_unknown_mode():
    vrrp = cp_snapshot(cp_member("tok-m1", mode="vrrp"), cp_member("tok-m2", role="STANDBY", mode="vrrp"))
    u = unit(cp_report(vrrp), _CP_UNIT)
    assert u["evidence"]["prerequisites"]["mode"] == "unsupported"
    assert all(c["status"] != CHECK_PASS for c in u["checks"])
    unknown = cp_snapshot(cp_member("tok-m1", mode="unknown"), cp_member("tok-m2", role="STANDBY"))
    u2 = unit(cp_report(unknown), _CP_UNIT)
    assert u2["evidence"]["prerequisites"]["mode"] == "not_established"
    assert all(c["status"] != CHECK_PASS for c in u2["checks"])
    ls = cp_snapshot(cp_member("tok-m1", mode="load_sharing_unicast"), cp_member("tok-m2", mode="load_sharing_unicast"))
    u3 = unit(cp_report(ls), _CP_UNIT)
    assert u3["verdict"] == VERDICT_NOT_A_FAILOVER_UNIT and u3["cluster_mode"] == "load_sharing_unicast"
    aa = pan_snapshot(pan_member("tok-p1", mode="active-active"), pan_member("tok-p2", state="passive", mode="active-active"))
    assert unit(pan_report(aa), _PAN_UNIT)["evidence"]["prerequisites"]["mode"] == "unsupported"


def test_parity_mismatch_is_explicit_and_unknown_is_not():
    snap = cp_snapshot(cp_member("tok-m1", version="R81.10"), cp_member("tok-m2", role="STANDBY", version="R81.20"))
    u = unit(cp_report(snap), _CP_UNIT)
    assert checks(u)["parity"]["reason"] == "cp_software_version_mismatch_observed" and u["verdict"] == VERDICT_UNSAFE
    snap2 = cp_snapshot(cp_member("tok-m1", policy="pol-a"), cp_member("tok-m2", role="STANDBY", policy="pol-b"))
    assert checks(unit(cp_report(snap2), _CP_UNIT))["parity"]["reason"] == "cp_installed_policy_token_mismatch_observed"
    pan = pan_snapshot(pan_member("tok-p1", compat="Mismatch"), pan_member("tok-p2", state="passive"))
    assert unit(pan_report(pan), _PAN_UNIT)["reason"] == "content_version_mismatch_observed"
    pan2 = pan_snapshot(pan_member("tok-p1", running_sync="not synchronized"), pan_member("tok-p2", state="passive"))
    c = checks(unit(pan_report(pan2), _PAN_UNIT))["parity"]
    assert c["status"] == CHECK_INSUFFICIENT and c["reason"] == "value_not_established:group_running_sync"


def test_unmatched_and_ambiguous_snapshots_are_reported_not_guessed():
    stray = cp_snapshot(cp_member("tok-x", unit="grp-unknown"), unit="grp-unknown")
    report = cp_report(happy_cp(), stray)
    assert report["preflight"]["unmatched"] == ["grp-unknown"]
    assert [u["unit_id"] for u in report["units"]] == [_CP_UNIT]
    dup = cp_report(happy_cp(), happy_cp())
    assert dup["preflight"]["ambiguous"] == [_CP_UNIT] and dup["preflight"]["applied"] == []
    assert unit(dup, _CP_UNIT)["evidence"]["basis"] == EVIDENCE_BASIS_STORED_TELEMETRY


def test_readiness_artifact_is_not_a_raw_evidence_dump():
    u = unit(cp_report(happy_cp()), _CP_UNIT)
    text = json.dumps(u)
    assert "tok-m1" not in json.dumps(u["checks"])
    assert "own_facts" not in text and "provenance" not in text and "collected_at" not in text
    assert set(u["evidence"]) == {
        "basis", "preflight_run_id", "coherent", "coherence_reasons", "member_skew_ms", "member_skew_policy",
        "stale_intent_present", "configuration_intent_freshness", "prerequisites", "unresolved_policy_gates", "observed",
    }
    assert u["unresolved_reason"] is None  # X-4: serialised now


def test_evidence_source_exclusivity_fresh_preflight_xor_legacy_telemetry():
    """PO regression guard (S7 approval): for one unit, a supplied and
    selected PreflightSnapshot is the ONLY evidence source -- stored
    cp_config_telemetry / pan_config_telemetry facts must not also
    contribute. Legacy telemetry here would produce a materially different
    result on its own (split-brain UNSAFE for CP; unresolved single member
    plus split-brain for PAN); the snapshot says healthy. Both vendor
    dispatch paths are exercised. Complements test_18 (CP only)."""
    # -- Check Point: legacy says both ACTIVE (UNSAFE split-brain), snapshot says ACTIVE/STANDBY.
    stale_cp = {"m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
                "m2": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"}}
    legacy_only = unit(cp_report(cp_ha_runtime=stale_cp), _CP_UNIT)
    assert legacy_only["verdict"] == VERDICT_UNSAFE and legacy_only["reason"] == "split_brain_observed"
    with_snapshot = unit(cp_report(happy_cp(), cp_ha_runtime=stale_cp), _CP_UNIT)
    snapshot_only = unit(cp_report(happy_cp()), _CP_UNIT)
    assert with_snapshot["evidence"]["basis"] == EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT
    assert with_snapshot["verdict"] == VERDICT_INSUFFICIENT
    assert with_snapshot["checks"] == snapshot_only["checks"]  # byte-identical: legacy contributed nothing
    assert checks(with_snapshot)["no_split_brain"]["status"] == CHECK_PASS
    # A conflicting legacy mode does not leak into the roll-up either.
    stale_ls = {k: {**v, "ha_cluster_mode": "load_sharing_unicast"} for k, v in stale_cp.items()}
    assert unit(cp_report(happy_cp(), cp_ha_runtime=stale_ls), _CP_UNIT)["verdict"] == VERDICT_INSUFFICIENT
    assert unit(cp_report(happy_cp(), cp_ha_runtime=stale_ls), _CP_UNIT)["cluster_mode"] == "ha_new_mode"

    # -- Palo Alto: legacy says both active (UNSAFE split-brain), snapshot says active/passive.
    stale_pan = {
        "pan-a": {**_PAN_RUNTIME["pan-a"], "state": "active", "peer_state": "active"},
        "pan-b": {**_PAN_RUNTIME["pan-b"], "state": "active", "peer_state": "active"},
    }
    legacy_pan = unit(compute_ha_readiness(pan_rows(), pan_ha_runtime=stale_pan, pan_ha_peers=_PAN_PEERS), _PAN_UNIT)
    assert legacy_pan["verdict"] == VERDICT_UNSAFE and legacy_pan["reason"] == "split_brain_observed"
    with_pan = unit(compute_ha_readiness(pan_rows(), pan_ha_runtime=stale_pan, pan_ha_peers=_PAN_PEERS,
                                         preflight_snapshots=[happy_pan()]), _PAN_UNIT)
    pan_only = unit(pan_report(happy_pan()), _PAN_UNIT)
    assert with_pan["evidence"]["basis"] == EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT
    assert with_pan["verdict"] == VERDICT_INSUFFICIENT and with_pan["reason"] != "split_brain_observed"
    assert with_pan["checks"] == pan_only["checks"]
    assert checks(with_pan)["no_split_brain"]["status"] == CHECK_PASS
    # And the converse: a unit WITHOUT a snapshot never picks up snapshot facts from another unit.
    stray = cp_snapshot(cp_member("tok-x", unit="grp-other", pnote=True), unit="grp-other")
    assert unit(cp_report(stray, cp_ha_runtime=stale_cp), _CP_UNIT)["evidence"]["basis"] == EVIDENCE_BASIS_STORED_TELEMETRY


def test_snapshot_never_creates_or_reshapes_a_unit():
    report = compute_ha_readiness([], preflight_snapshots=[happy_cp()])
    assert report["units"] == [] and report["preflight"]["unmatched"] == [_CP_UNIT]
