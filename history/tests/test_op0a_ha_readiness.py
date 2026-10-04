"""OP.0a — HA readiness assessment on existing evidence.

Contract: docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md (frozen
2026-09-01). Architecture: docs/design/FAILOVER_ENGINE_ARCHITECTURE.md.

Covers acceptance criteria AC-1 … AC-13. **No device is contacted anywhere in
this file, and none can be** — the assessment module performs no I/O at all,
and the cluster-mode parser is a pure function over text the already-gated
`cphaprob stat` call site already has in hand.

AC-6 is the load-bearing test: it proves by exhaustive generation that
`SAFE_TO_FAILOVER` is unreachable in OP.0a, so a later edit cannot make a
green light reachable by accident.
"""
from __future__ import annotations

import itertools
import json

import pytest

from configuration.checkpoint_config_collector import (
    CLUSTERXL_CLUSTER_MODES,
    _parse_clusterxl_cluster_mode,
    _parse_clusterxl_runtime_role,
)
from utils.collection_executor import ALLOWLISTED_WORKFLOWS, SchedulerPolicyError, load_scheduler_policy
from utils.failover import (
    CHECK_FAIL,
    CHECK_INSUFFICIENT,
    CHECK_PASS,
    OP0A_EVALUABLE_CHECKS,
    STOP_CONDITIONS,
    VERDICT_DEGRADED,
    VERDICT_INSUFFICIENT,
    VERDICT_NOT_A_FAILOVER_UNIT,
    VERDICT_SAFE,
    VERDICT_UNSAFE,
    compute_ha_readiness,
)


# --------------------------------------------------------------------------
# Fixtures / helpers
# --------------------------------------------------------------------------

def _cp(device, cluster=None, vs_id=None, source="cp"):
    row = {"device": device, "source": source, "inventory_status": {"data_state": "ok"}}
    if cluster is not None:
        row["cluster"] = cluster
    if vs_id is not None:
        row["vs_id"] = vs_id
    return row


def _pan(device, management_ip=None, vsys=None):
    row = {
        "device": device,
        "source": "panorama",
        "management_ip": management_ip,
        "inventory_status": {"data_state": "ok"},
    }
    if vsys is not None:
        row["vsys"] = vsys
    return row


def _cp_topo(device, group_id, display_name=None, source="cp", vs_id=None, vsx_flag=None):
    """A row shaped like the REAL pipeline: nested `cluster_topology`
    (`checkpoint/cp_runner.py::enrich_cluster_topology`), no flat `cluster`
    field -- this is the shape the 2026-09-01/02 real-env CP validation
    actually produced and that the legacy `_cp()` fixture above does not
    exercise."""
    row = {
        "device": device, "source": source,
        "inventory_status": {"data_state": "ok"},
        "cluster_topology": {"group_id": group_id, "display_name": display_name or group_id},
    }
    if vs_id is not None:
        row["vs_id"] = vs_id
    if vsx_flag is not None:
        row["vsx_cluster_member"] = vsx_flag
    return row


def _unit_by_id(report, unit_id):
    for unit in report["units"]:
        if unit["unit_id"] == unit_id:
            return unit
    return None


# --------------------------------------------------------------------------
# AC-1 — cluster mode parse; existing role parse undisturbed
# --------------------------------------------------------------------------

_HA_NEW_MODE = """Cluster Mode:   High Availability (Active Up) with IGMP Membership

Number     Unique Address  Assigned Load   State          Name
1 (local)  192.0.2.11      100%            ACTIVE         member-a
2          192.0.2.12      0%              STANDBY        member-b
"""

_LS_UNICAST = """Cluster Mode:   Load Sharing (Unicast) with IGMP Membership

Number     Unique Address  Assigned Load   State          Name
1 (local)  192.0.2.11      50%             ACTIVE         member-a
2          192.0.2.12      50%             ACTIVE         member-b
"""

_LS_MULTICAST = """Cluster Mode:   Load Sharing (Multicast)

Number     Unique Address  Assigned Load   State          Name
1 (local)  192.0.2.11      50%             ACTIVE         member-a
"""

_VRRP = """Cluster Mode:   VRRP

Number     Unique Address  Assigned Load   State          Name
1 (local)  192.0.2.11      100%            ACTIVE         member-a
"""

_UNRECOGNISED = """Some unexpected banner from a future Gaia release

1 (local)  192.0.2.11  100%  ACTIVE  member-a
"""


@pytest.mark.parametrize("stdout,expected", [
    (_HA_NEW_MODE, "ha_new_mode"),
    (_LS_UNICAST, "load_sharing_unicast"),
    (_LS_MULTICAST, "load_sharing_multicast"),
    (_VRRP, "vrrp"),
    (_UNRECOGNISED, "unknown"),
    ("", "unknown"),
])
def test_ac1_cluster_mode_parse(stdout, expected):
    assert _parse_clusterxl_cluster_mode(stdout) == expected


def test_ac1_every_parsed_mode_is_in_the_frozen_vocabulary():
    for stdout in (_HA_NEW_MODE, _LS_UNICAST, _LS_MULTICAST, _VRRP, _UNRECOGNISED, ""):
        assert _parse_clusterxl_cluster_mode(stdout) in CLUSTERXL_CLUSTER_MODES


@pytest.mark.parametrize("stdout", [_HA_NEW_MODE, _LS_UNICAST, _LS_MULTICAST, _VRRP, _UNRECOGNISED])
def test_ac1_existing_role_parse_is_undisturbed(stdout):
    """P2 is an additive parse: the role extraction must behave exactly as it
    did before this build for every fixture the mode parser now also reads."""
    assert _parse_clusterxl_runtime_role(stdout, "member-a") == "ACTIVE"


def test_ac1_mode_parse_leaks_no_identity():
    """The mode parser returns an enum, never device text."""
    for stdout in (_HA_NEW_MODE, _LS_UNICAST, _VRRP):
        mode = _parse_clusterxl_cluster_mode(stdout)
        assert "member" not in mode and "192.0.2" not in mode


# --------------------------------------------------------------------------
# AC-3 / AC-4 — CP unit assembly and VSX separation
# --------------------------------------------------------------------------

def test_ac3_cp_cluster_forms_one_unit_and_standalone_forms_none():
    rows = [
        _cp("cp-core-01", cluster="cp-core"),
        _cp("cp-core-02", cluster="cp-core"),
        _cp("cp-edge-01"),
    ]
    report = compute_ha_readiness(rows)
    cp_units = [u for u in report["units"] if u["vendor"] == "checkpoint"]
    assert len(cp_units) == 1
    assert cp_units[0]["unit_id"] == "cp-core"
    assert cp_units[0]["members"] == ["cp-core-01", "cp-core-02"]
    assert _unit_by_id(report, "cp-edge-01") is None


def test_ac4_vsx_host_and_each_vs_are_distinct_units():
    rows = [
        _cp("vsx-gw-01", source="vsx"),
        _cp("vsx-gw-01", source="vsx", vs_id=10),
        _cp("vsx-gw-01", source="vsx", vs_id=20),
    ]
    report = compute_ha_readiness(rows)
    ids = sorted(u["unit_id"] for u in report["units"])
    assert ids == ["vsx-gw-01", "vsx-gw-01__vsid_10", "vsx-gw-01__vsid_20"]
    types = {u["unit_id"]: u["unit_type"] for u in report["units"]}
    assert types["vsx-gw-01"] == "cp_vsx_host"
    assert types["vsx-gw-01__vsid_10"] == "cp_vsx_virtual_system"


def test_ac4_virtual_system_never_inherits_host_verdict():
    """Correctness rule 7: the physical VSX cluster is decisively UNSAFE (one
    member ACTIVE, the other explicitly DOWN -- both observed, so "no viable
    target" is positively established); its VS, observed from one member
    only, must not inherit that verdict.

    Rewritten at OP.0b S7: the original fixture inferred UNSAFE from a single
    ACTIVE member with no peer observation at all, which the evidence law
    "absence of evidence != evidence of absence" forbids -- a one-sided read
    is now INSUFFICIENT_EVIDENCE (`peer_not_independently_observed`), never a
    fabricated `no_viable_target`."""
    rows = [
        _cp("vsx-gw-01", cluster="vsx-c", source="vsx"),
        _cp("vsx-gw-02", cluster="vsx-c", source="vsx"),
        _cp("vsx-gw-01", source="vsx", vs_id=10),
    ]
    runtime = {
        "vsx-gw-01": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
        "vsx-gw-02": {"ha_role": "DOWN", "ha_cluster_mode": "ha_new_mode"},
        "vsx-gw-01__vsid_10": {"ha_role": "STANDBY", "ha_cluster_mode": "ha_new_mode"},
    }
    report = compute_ha_readiness(rows, cp_ha_runtime=runtime)
    host = _unit_by_id(report, "vsx-c")
    vs = _unit_by_id(report, "vsx-c__vsid_10")
    assert host["verdict"] == VERDICT_UNSAFE and host["reason"] == "no_viable_target"
    assert vs["verdict"] != VERDICT_UNSAFE
    assert vs["members"] == ["vsx-gw-01__vsid_10"]
    assert vs["parent_id"] == "vsx-c"


def test_one_sided_evidence_is_insufficient_not_no_viable_target():
    """S7 evidence law: a single ACTIVE observation with no observation of the
    peer at all cannot prove there is no standby. Explicit DOWN on the peer
    (observed) still can."""
    rows = [_cp("m1", cluster="c"), _cp("m2", cluster="c")]
    report = compute_ha_readiness(rows, cp_ha_runtime={"m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"}})
    unit = _unit_by_id(report, "c")
    assert unit["verdict"] == VERDICT_INSUFFICIENT
    checks = {c["id"]: c for c in unit["checks"]}
    assert checks["viable_target"]["status"] == CHECK_INSUFFICIENT
    assert checks["viable_target"]["reason"] == "peer_not_independently_observed"
    assert checks["no_split_brain"]["reason"] == "peer_not_independently_observed"


# --------------------------------------------------------------------------
# CP cluster-centric identity — real pipeline shape (2026-09-02 real-env find)
#
# The real CP pipeline never emits a flat `cluster` field (checkpoint/
# cp_runner.py writes only nested `cluster_topology`); a narrow real-env
# ClusterXL validation run reproduced exactly this and the cluster was
# silently omitted from the Failover module. These tests use the REAL nested
# shape (`_cp_topo`), not the legacy flat-field fixture (`_cp`).
# --------------------------------------------------------------------------

def test_cluster_topology_group_id_forms_one_cluster_unit_with_both_members():
    rows = [
        _cp_topo("FW-1", "grp-abc123", display_name="FW-CLS"),
        _cp_topo("FW-2", "grp-abc123", display_name="FW-CLS"),
    ]
    report = compute_ha_readiness(rows)
    cp_units = [u for u in report["units"] if u["vendor"] == "checkpoint"]
    assert len(cp_units) == 1
    assert cp_units[0]["unit_id"] == "grp-abc123"
    assert sorted(cp_units[0]["members"]) == ["FW-1", "FW-2"]
    assert cp_units[0]["unit_type"] == "cp_clusterxl_cluster"


def test_group_id_is_canonical_display_name_is_presentation_only():
    """Two clusters sharing a display_name (a naming collision) must still
    remain distinct units -- display_name never decides grouping."""
    rows = [
        _cp_topo("FW-1", "grp-aaa", display_name="SAME-LABEL"),
        _cp_topo("FW-2", "grp-aaa", display_name="SAME-LABEL"),
        _cp_topo("FW-3", "grp-bbb", display_name="SAME-LABEL"),
        _cp_topo("FW-4", "grp-bbb", display_name="SAME-LABEL"),
    ]
    report = compute_ha_readiness(rows)
    cp_units = [u for u in report["units"] if u["vendor"] == "checkpoint"]
    assert {u["unit_id"] for u in cp_units} == {"grp-aaa", "grp-bbb"}
    for unit in cp_units:
        assert unit["display_name"] == "SAME-LABEL"


def test_active_standby_role_swap_does_not_change_cluster_identity():
    rows = [_cp_topo("FW-1", "grp-abc123"), _cp_topo("FW-2", "grp-abc123")]
    before = compute_ha_readiness(rows, cp_ha_runtime={
        "FW-1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
        "FW-2": {"ha_role": "STANDBY", "ha_cluster_mode": "ha_new_mode"},
    })
    after = compute_ha_readiness(rows, cp_ha_runtime={
        "FW-1": {"ha_role": "STANDBY", "ha_cluster_mode": "ha_new_mode"},
        "FW-2": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
    })
    assert _unit_by_id(before, "grp-abc123")["unit_id"] == _unit_by_id(after, "grp-abc123")["unit_id"]


def test_seven_checks_roll_up_to_one_cluster_level_verdict():
    rows = [_cp_topo("FW-1", "grp-abc123"), _cp_topo("FW-2", "grp-abc123")]
    runtime = {
        "FW-1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
        "FW-2": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
    }
    report = compute_ha_readiness(rows, cp_ha_runtime=runtime)
    cp_units = [u for u in report["units"] if u["vendor"] == "checkpoint"]
    assert len(cp_units) == 1
    unit = cp_units[0]
    assert len(unit["checks"]) == 7
    assert unit["verdict"] == VERDICT_UNSAFE
    assert unit["reason"] == "split_brain_observed"


def test_standalone_cp_gateway_not_falsely_classified_as_ha_cluster():
    rows = [_cp_topo("FW-1", "grp-abc123"), _cp_topo("FW-2", "grp-abc123"),
            {"device": "FW-EDGE", "source": "cp", "inventory_status": {"data_state": "ok"}}]
    report = compute_ha_readiness(rows)
    assert _unit_by_id(report, "FW-EDGE") is None
    assert len([u for u in report["units"] if u["vendor"] == "checkpoint"]) == 1


def test_legacy_flat_cluster_fixture_remains_compatible():
    """AC-3's exact fixture/assertions must survive unchanged."""
    rows = [_cp("cp-core-01", cluster="cp-core"), _cp("cp-core-02", cluster="cp-core")]
    report = compute_ha_readiness(rows)
    unit = _unit_by_id(report, "cp-core")
    assert unit is not None
    assert unit["display_name"] is None
    assert sorted(unit["members"]) == ["cp-core-01", "cp-core-02"]


def test_missing_and_ambiguous_topology_remains_fail_closed():
    """No group_id, no legacy cluster -> standalone, omitted (fail-closed,
    not guessed into a cluster)."""
    rows = [
        {"device": "FW-1", "source": "cp", "inventory_status": {"data_state": "ok"}},
        {"device": "FW-2", "source": "cp", "inventory_status": {"data_state": "ok"},
         "cluster_topology": "not-a-mapping"},
    ]
    report = compute_ha_readiness(rows)
    assert [u for u in report["units"] if u["vendor"] == "checkpoint"] == []


def test_no_duplicate_cluster_unit_is_produced_across_repeated_rows():
    rows = [_cp_topo("FW-1", "grp-abc123"), _cp_topo("FW-1", "grp-abc123"), _cp_topo("FW-2", "grp-abc123")]
    report = compute_ha_readiness(rows)
    cp_units = [u for u in report["units"] if u["vendor"] == "checkpoint"]
    assert len(cp_units) == 1
    assert cp_units[0]["members"] == ["FW-1", "FW-2"]


# --------------------------------------------------------------------------
# CP VSX — physical cluster/host is a parent; each Virtual System is its own
# unit, linked via parent_id, never flattened, never a top-level sibling of
# unrelated physical gateways.
# --------------------------------------------------------------------------

def test_vsx_physical_pair_forms_one_parent_unit_via_shared_group_id():
    """VSX physical hosts running classic ClusterXL underneath get the same
    cluster_topology.group_id mechanism as a normal cluster -- no VSLS
    assumption, reusing existing evidence. A grouped (2-member) VSX pair is
    typed cp_vsx_cluster, distinct from a single ungrouped cp_vsx_host."""
    rows = [
        _cp_topo("VSX-1", "grp-vsx1", display_name="VSX-CLS", source="vsx"),
        _cp_topo("VSX-2", "grp-vsx1", display_name="VSX-CLS", source="vsx"),
    ]
    report = compute_ha_readiness(rows)
    vsx_units = [u for u in report["units"] if u["unit_type"] == "cp_vsx_cluster"]
    assert len(vsx_units) == 1
    assert vsx_units[0]["unit_id"] == "grp-vsx1"
    assert sorted(vsx_units[0]["members"]) == ["VSX-1", "VSX-2"]


def test_real_pipeline_shape_physical_vsx_detected_by_evidence_not_missing_flag():
    """Reproduces the exact real-env pipeline shape: physical VSX hosts arrive
    as plain source:"cp" rows (checkpoint/cp_runner.py never writes
    vsx_cluster_member into cp.json/unified.json -- see _row_is_vsx). The
    domain layer must still classify them as VSX using the fact that separate
    source:"vsx" rows name them, not a flag that structurally never reaches
    this file."""
    rows = [
        {"device": "VSX-1", "source": "cp", "cluster_topology": {"group_id": "grp-vsx1", "display_name": "VSX-CLS"},
         "inventory_status": {"data_state": "ok"}},
        {"device": "VSX-2", "source": "cp", "cluster_topology": {"group_id": "grp-vsx1", "display_name": "VSX-CLS"},
         "inventory_status": {"data_state": "ok"}},
        {"device": "VSX-1", "source": "vsx", "vs_id": "1", "inventory_status": {"data_state": "ok"}},
        {"device": "VSX-1", "source": "vsx", "vs_id": "2", "inventory_status": {"data_state": "ok"}},
        {"device": "VSX-2", "source": "vsx", "vs_id": "1", "inventory_status": {"data_state": "ok"}},
        {"device": "VSX-2", "source": "vsx", "vs_id": "2", "inventory_status": {"data_state": "ok"}},
    ]
    report = compute_ha_readiness(rows)
    parent = _unit_by_id(report, "grp-vsx1")
    assert parent is not None
    assert parent["unit_type"] == "cp_vsx_cluster"  # not cp_clusterxl_cluster
    vs_units = [u for u in report["units"] if u["unit_type"] == "cp_vsx_virtual_system"]
    # CASE A x2: VSID 1 and VSID 2, each observed by both physical members,
    # collapse into exactly 2 logical units -- not 4 (device, vsid) pairs.
    assert len(vs_units) == 2
    assert {u["unit_id"] for u in vs_units} == {"grp-vsx1__vsid_1", "grp-vsx1__vsid_2"}
    vsid_1 = _unit_by_id(report, "grp-vsx1__vsid_1")
    vsid_2 = _unit_by_id(report, "grp-vsx1__vsid_2")
    assert vsid_1["parent_id"] == "grp-vsx1" and vsid_2["parent_id"] == "grp-vsx1"
    assert sorted(vsid_1["members"]) == ["VSX-1__vsid_1", "VSX-2__vsid_1"]
    assert sorted(vsid_2["members"]) == ["VSX-1__vsid_2", "VSX-2__vsid_2"]


def test_real_pipeline_shape_survives_cp_vs_vsx_device_name_separator_mismatch():
    """Real-env retry finding (post-PR#30): `checkpoint/scripts/cp_inventory.sh`
    derives its `cp.json` device key with `tr -c '[:alnum:]_-' '_'` on the raw
    target name, which appends a cosmetic trailing separator for some
    real-estate management objects (e.g. "FW-CKP-EXTRA-LL-1_"); `vsx_runner.py`
    reads the same physical object's name straight from `cpmiquerybin` without
    that separator (e.g. "FW-CKP-EXTRA-LL-1"). An exact-string join between the
    two collectors' `device` fields must not silently degrade a real VSX pair
    to plain ClusterXL classification with 4 member-scoped orphan VS units --
    it must still resolve to one cp_vsx_cluster parent and 2 merged VS units."""
    rows = [
        {"device": "FW-CKP-EXTRA-LL-1_", "source": "cp",
         "cluster_topology": {"group_id": "grp-extra-ll", "display_name": "FW-CKP-EXTRA-LL-CLS"},
         "inventory_status": {"data_state": "ok"}},
        {"device": "FW-CKP-EXTRA-LL-2_", "source": "cp",
         "cluster_topology": {"group_id": "grp-extra-ll", "display_name": "FW-CKP-EXTRA-LL-CLS"},
         "inventory_status": {"data_state": "ok"}},
        {"device": "FW-CKP-EXTRA-LL-1", "source": "vsx", "vs_id": "1", "vsys": "Extranet-vsx",
         "inventory_status": {"data_state": "ok"}},
        {"device": "FW-CKP-EXTRA-LL-1", "source": "vsx", "vs_id": "2", "vsys": "Leasedline",
         "inventory_status": {"data_state": "ok"}},
        {"device": "FW-CKP-EXTRA-LL-2", "source": "vsx", "vs_id": "1", "vsys": "Extranet-vsx",
         "inventory_status": {"data_state": "ok"}},
        {"device": "FW-CKP-EXTRA-LL-2", "source": "vsx", "vs_id": "2", "vsys": "Leasedline",
         "inventory_status": {"data_state": "ok"}},
    ]
    report = compute_ha_readiness(rows)
    parent = _unit_by_id(report, "grp-extra-ll")
    assert parent is not None
    assert parent["unit_type"] == "cp_vsx_cluster"  # not cp_clusterxl_cluster

    vs_units = [u for u in report["units"] if u["unit_type"] == "cp_vsx_virtual_system"]
    assert len(vs_units) == 2  # not 4 orphaned (device, vsid) singletons
    assert {u["unit_id"] for u in vs_units} == {"grp-extra-ll__vsid_1", "grp-extra-ll__vsid_2"}

    vsid_1 = _unit_by_id(report, "grp-extra-ll__vsid_1")
    vsid_2 = _unit_by_id(report, "grp-extra-ll__vsid_2")
    assert vsid_1["parent_id"] == "grp-extra-ll" and vsid_2["parent_id"] == "grp-extra-ll"
    assert sorted(vsid_1["members"]) == ["FW-CKP-EXTRA-LL-1__vsid_1", "FW-CKP-EXTRA-LL-2__vsid_1"]
    assert sorted(vsid_2["members"]) == ["FW-CKP-EXTRA-LL-1__vsid_2", "FW-CKP-EXTRA-LL-2__vsid_2"]
    assert vsid_1["display_name"] == "Extranet-vsx | FW-CKP-EXTRA-LL-CLS"
    assert vsid_2["display_name"] == "Leasedline | FW-CKP-EXTRA-LL-CLS"


def test_real_pipeline_shape_survives_cp_ha_runtime_entity_id_separator_mismatch():
    """Real-env retry finding: `configuration/checkpoint_config_collector.py`
    resolves its own `PhysicalTarget.device` independently and can inherit
    the same trailing-separator-suffixed physical name (see the join-mismatch
    test above), so `cp_config_telemetry.json` records HA runtime under
    entity ids like "FW-CKP-EXTRA-LL-1___vsid_2" (triple underscore) while
    this module's own VS entity ids (from `vsx.json` via `resolve_entity_id`)
    are "FW-CKP-EXTRA-LL-1__vsid_2" (double underscore). An exact-string
    lookup into `cp_ha_runtime` must not silently return INSUFFICIENT_EVIDENCE
    for every check on a VS unit whose runtime evidence was actually
    collected, just because the two collectors' entity ids differ by that
    cosmetic separator."""
    rows = [
        {"device": "FW-CKP-EXTRA-LL-1_", "source": "cp",
         "cluster_topology": {"group_id": "grp-extra-ll", "display_name": "FW-CKP-EXTRA-LL-CLS"},
         "inventory_status": {"data_state": "ok"}},
        {"device": "FW-CKP-EXTRA-LL-2_", "source": "cp",
         "cluster_topology": {"group_id": "grp-extra-ll", "display_name": "FW-CKP-EXTRA-LL-CLS"},
         "inventory_status": {"data_state": "ok"}},
        {"device": "FW-CKP-EXTRA-LL-1", "source": "vsx", "vs_id": "2", "vsys": "Extranet-vsx",
         "inventory_status": {"data_state": "ok"}},
        {"device": "FW-CKP-EXTRA-LL-2", "source": "vsx", "vs_id": "2", "vsys": "Extranet-vsx",
         "inventory_status": {"data_state": "ok"}},
    ]
    cp_ha_runtime = {
        "FW-CKP-EXTRA-LL-1_": {"ha_role": "ACTIVE", "ha_cluster_mode": "high_availability"},
        "FW-CKP-EXTRA-LL-2_": {"ha_role": "STANDBY", "ha_cluster_mode": "high_availability"},
        "FW-CKP-EXTRA-LL-1___vsid_2": {"ha_role": "ACTIVE", "ha_cluster_mode": "high_availability"},
        "FW-CKP-EXTRA-LL-2___vsid_2": {"ha_role": "STANDBY", "ha_cluster_mode": "high_availability"},
    }
    report = compute_ha_readiness(rows, cp_ha_runtime=cp_ha_runtime)

    parent = _unit_by_id(report, "grp-extra-ll")
    assert parent["cluster_mode"] == "high_availability"

    vs = _unit_by_id(report, "grp-extra-ll__vsid_2")
    assert vs["cluster_mode"] == "high_availability"
    viable_target = next(c for c in vs["checks"] if c["id"] == "viable_target")
    no_split_brain = next(c for c in vs["checks"] if c["id"] == "no_split_brain")
    assert viable_target["status"] == CHECK_PASS
    assert no_split_brain["status"] == CHECK_PASS


def test_each_virtual_system_is_a_distinct_unit_linked_to_its_physical_parent():
    rows = [
        _cp_topo("VSX-1", "grp-vsx1", source="vsx"),
        _cp_topo("VSX-2", "grp-vsx1", source="vsx"),
        {"device": "VSX-1", "source": "vsx", "vs_id": "10", "inventory_status": {"data_state": "ok"}},
        {"device": "VSX-1", "source": "vsx", "vs_id": "20", "inventory_status": {"data_state": "ok"}},
    ]
    report = compute_ha_readiness(rows)
    vs_a = _unit_by_id(report, "grp-vsx1__vsid_10")
    vs_b = _unit_by_id(report, "grp-vsx1__vsid_20")
    assert vs_a is not None and vs_b is not None
    assert vs_a["unit_id"] != vs_b["unit_id"]
    assert vs_a["parent_id"] == "grp-vsx1"
    assert vs_b["parent_id"] == "grp-vsx1"
    assert vs_a["unit_type"] == "cp_vsx_virtual_system"


def test_physical_member_role_transition_does_not_change_vs_identity():
    rows = [
        _cp_topo("VSX-1", "grp-vsx1", source="vsx"),
        _cp_topo("VSX-2", "grp-vsx1", source="vsx"),
        {"device": "VSX-1", "source": "vsx", "vs_id": "10", "inventory_status": {"data_state": "ok"}},
    ]
    before = compute_ha_readiness(rows, cp_ha_runtime={"VSX-1": {"ha_role": "ACTIVE"}, "VSX-2": {"ha_role": "STANDBY"}})
    after = compute_ha_readiness(rows, cp_ha_runtime={"VSX-1": {"ha_role": "STANDBY"}, "VSX-2": {"ha_role": "ACTIVE"}})
    assert _unit_by_id(before, "grp-vsx1__vsid_10")["parent_id"] == _unit_by_id(after, "grp-vsx1__vsid_10")["parent_id"]
    assert _unit_by_id(before, "grp-vsx1__vsid_10")["unit_id"] == _unit_by_id(after, "grp-vsx1__vsid_10")["unit_id"]


def test_virtual_systems_are_not_flattened_into_unrelated_physical_gateways():
    rows = [
        _cp_topo("VSX-1", "grp-vsx1", source="vsx"),
        _cp_topo("VSX-2", "grp-vsx1", source="vsx"),
        {"device": "VSX-1", "source": "vsx", "vs_id": "10", "inventory_status": {"data_state": "ok"}},
        _cp_topo("FW-EDGE-1", "grp-other"),
        _cp_topo("FW-EDGE-2", "grp-other"),
    ]
    report = compute_ha_readiness(rows)
    vs = _unit_by_id(report, "grp-vsx1__vsid_10")
    other_cluster = _unit_by_id(report, "grp-other")
    assert vs["parent_id"] == "grp-vsx1"
    assert vs["parent_id"] != other_cluster["unit_id"]
    assert "FW-EDGE-1" not in vs["members"]


# --- VSX failure/mismatch semantics (cases A-G) ------------------------------

def _vsx_pair_rows(vsid_map):
    """vsid_map: {device: [vs_id, ...]} -- builds the two physical rows plus
    whichever per-device VS rows are requested, real-pipeline shaped."""
    rows = [
        {"device": d, "source": "cp", "cluster_topology": {"group_id": "grp-vsx1"},
         "inventory_status": {"data_state": "ok"}}
        for d in ("VSX-1", "VSX-2")
    ]
    for device, vs_ids in vsid_map.items():
        for vs_id in vs_ids:
            rows.append({"device": device, "source": "vsx", "vs_id": vs_id, "inventory_status": {"data_state": "ok"}})
    return rows


def test_case_b_one_sided_vs_evidence_never_reads_as_healthy():
    """Only VSX-1 reports VSID 5 -- the aggregated unit has one member
    observation. The existing, unmodified 7-check logic must still never
    produce SAFE_TO_FAILOVER for it."""
    rows = _vsx_pair_rows({"VSX-1": ["5"]})
    for role in ("ACTIVE", "STANDBY", "DOWN", ""):
        report = compute_ha_readiness(rows, cp_ha_runtime={"VSX-1__vsid_5": {"ha_role": role}})
        unit = _unit_by_id(report, "grp-vsx1__vsid_5")
        assert unit is not None
        assert unit["members"] == ["VSX-1__vsid_5"]
        assert unit["verdict"] != VERDICT_SAFE


def test_case_c_different_vsid_sets_between_members_stay_separate_and_fail_closed():
    """VSX-1 exposes VSID 1 and 2; VSX-2 exposes only VSID 1. VSID 1 merges
    (both observed it); VSID 2 stays one-sided -- never silently merged with
    an unrelated observation."""
    rows = _vsx_pair_rows({"VSX-1": ["1", "2"], "VSX-2": ["1"]})
    report = compute_ha_readiness(rows)
    vs1 = _unit_by_id(report, "grp-vsx1__vsid_1")
    vs2 = _unit_by_id(report, "grp-vsx1__vsid_2")
    assert sorted(vs1["members"]) == ["VSX-1__vsid_1", "VSX-2__vsid_1"]
    assert vs2["members"] == ["VSX-1__vsid_2"]
    assert vs2["verdict"] != VERDICT_SAFE


def test_case_d_conflicting_member_roles_for_same_vsid_fail_closed():
    rows = _vsx_pair_rows({"VSX-1": ["1"], "VSX-2": ["1"]})
    runtime = {"VSX-1__vsid_1": {"ha_role": "ACTIVE"}, "VSX-2__vsid_1": {"ha_role": "ACTIVE"}}
    report = compute_ha_readiness(rows, cp_ha_runtime=runtime)
    unit = _unit_by_id(report, "grp-vsx1__vsid_1")
    assert unit["verdict"] == VERDICT_UNSAFE
    assert unit["reason"] == "split_brain_observed"


def test_case_e_conflicting_member_ha_mode_for_same_vsid_becomes_unknown():
    rows = _vsx_pair_rows({"VSX-1": ["1"], "VSX-2": ["1"]})
    runtime = {
        "VSX-1__vsid_1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
        "VSX-2__vsid_1": {"ha_role": "STANDBY", "ha_cluster_mode": "vrrp"},
    }
    report = compute_ha_readiness(rows, cp_ha_runtime=runtime)
    unit = _unit_by_id(report, "grp-vsx1__vsid_1")
    assert unit["cluster_mode"] == "unknown"


def test_case_f_missing_physical_parent_never_triggers_hostname_grouping():
    """Two VS rows for the same VSID, but neither device ever resolves into a
    physical unit -- both stay independent evidence-keyed singletons, never
    merged just because the VSID number matches (no hostname heuristic)."""
    rows = [
        {"device": "ORPHAN-A", "source": "vsx", "vs_id": "9", "inventory_status": {"data_state": "ok"}},
        {"device": "ORPHAN-B", "source": "vsx", "vs_id": "9", "inventory_status": {"data_state": "ok"}},
    ]
    report = compute_ha_readiness(rows)
    unit_a = _unit_by_id(report, "ORPHAN-A__vsid_9")
    unit_b = _unit_by_id(report, "ORPHAN-B__vsid_9")
    assert unit_a is not None and unit_b is not None
    assert unit_a["unit_id"] != unit_b["unit_id"]
    assert unit_a["parent_id"] is None and unit_b["parent_id"] is None
    assert unit_a["members"] == ["ORPHAN-A__vsid_9"]
    assert unit_b["members"] == ["ORPHAN-B__vsid_9"]


def test_case_g_ambiguous_parent_never_merges_across_different_clusters():
    """Same VSID number under two DIFFERENT resolved physical clusters must
    remain two distinct logical VS units -- grouping key is
    (parent_unit_id, vsid), never vsid alone."""
    rows = [
        {"device": "A-1", "source": "cp", "cluster_topology": {"group_id": "grp-a"}, "inventory_status": {"data_state": "ok"}},
        {"device": "A-2", "source": "cp", "cluster_topology": {"group_id": "grp-a"}, "inventory_status": {"data_state": "ok"}},
        {"device": "B-1", "source": "cp", "cluster_topology": {"group_id": "grp-b"}, "inventory_status": {"data_state": "ok"}},
        {"device": "B-2", "source": "cp", "cluster_topology": {"group_id": "grp-b"}, "inventory_status": {"data_state": "ok"}},
        {"device": "A-1", "source": "vsx", "vs_id": "1", "inventory_status": {"data_state": "ok"}},
        {"device": "B-1", "source": "vsx", "vs_id": "1", "inventory_status": {"data_state": "ok"}},
    ]
    report = compute_ha_readiness(rows)
    vs_units = [u for u in report["units"] if u["unit_type"] == "cp_vsx_virtual_system"]
    assert len(vs_units) == 2
    assert {u["unit_id"] for u in vs_units} == {"grp-a__vsid_1", "grp-b__vsid_1"}


def test_no_new_command_in_vsx_aggregation_code():
    import inspect

    from utils.failover import assessment as assessment_module

    source = inspect.getsource(assessment_module._derive_cp_units)
    for forbidden in ("paramiko", "_run_exec", "vsenv", "ssh."):
        assert forbidden not in source


def test_vs_missing_physical_parent_evidence_yields_no_parent_id_not_a_guess():
    """A VS row whose physical host never resolved into any unit (e.g. the
    host row is entirely absent from this run's evidence) gets parent_id=None
    -- it still stands on its own with its own verdict, never silently
    attached to the wrong parent."""
    rows = [{"device": "VSX-ORPHAN", "source": "vsx", "vs_id": "10", "inventory_status": {"data_state": "ok"}}]
    report = compute_ha_readiness(rows)
    vs = _unit_by_id(report, "VSX-ORPHAN__vsid_10")
    assert vs is not None
    assert vs["parent_id"] is None


def test_no_vsls_assumption_is_introduced_by_vsx_grouping():
    """Source-level guard, scoped to this module: `utils.failover.assessment`
    stays generic verdict/unit-derivation logic and never gains a VSLS-named
    attribute or load-sharing-set entry of its own -- the vendor-specific
    `vsx_vsls` mode token lives in `preflight_readiness.CP_SUPPORTED_FAILOVER_MODES`
    (OP.0b S4-A', real-env finding 2026-09-04: this estate DOES run VSLS;
    see that module's own tests). `_CP_LOAD_SHARING_MODES` here specifically
    must never include it -- that set means "no standby exists", which is
    false for VSLS."""
    import utils.failover.assessment as assessment_module

    names = " ".join(dir(assessment_module)).lower()
    assert "vsls" not in names
    assert not any("vsls" in mode for mode in assessment_module._CP_LOAD_SHARING_MODES)


# --------------------------------------------------------------------------
# PAN — VSYS is subordinate context only, never an independently
# failoverable top-level unit (already true structurally; locked in here).
# --------------------------------------------------------------------------

def test_pan_vsys_metadata_never_produces_a_top_level_unit():
    rows = [_pan("pan-ha-01", "10.0.0.1", vsys="vsys1"), _pan("pan-ha-02", "10.0.0.2", vsys="vsys2")]
    runtime = {"pan-ha-01": _pan_runtime(), "pan-ha-02": _pan_runtime("passive", "active")}
    peers = {"pan-ha-01": "10.0.0.2", "pan-ha-02": "10.0.0.1"}
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    pan_units = [u for u in report["units"] if u["vendor"] == "panorama"]
    assert len(pan_units) == 1
    assert pan_units[0]["members"] == ["pan-ha-01", "pan-ha-02"]
    ids = {u["unit_id"] for u in report["units"]}
    assert "vsys1" not in ids and "vsys2" not in ids


def _pan_with_interfaces(device, management_ip, vsys_values):
    """Real-pipeline shape: VSYS names live on `row["interfaces"][].vsys`
    (panorama/panorama_runtime_runner.py::parse_interfaces), never as a flat
    top-level field -- unlike the legacy `_pan()` fixture's `vsys` kwarg,
    which no real collector ever populates."""
    row = _pan(device, management_ip)
    row["interfaces"] = [{"name": f"eth1/{i}", "vsys": v} for i, v in enumerate(vsys_values)]
    return row


def test_pan_context_vsys_surfaces_real_interface_vsys_context():
    """VSYS context (informational only, never identity) is carried on
    `context_vsys` from the real interface-level field, mirroring the CP VSX
    "vsys | cluster" precedent for context data -- but (OP.0b S9, PAN UI
    debt item 1) it must never compose the unit's own `display_name`/label:
    the canonical `unit_id` (member entity ids) stays the presentation
    identity, VSYS is strictly subordinate."""
    rows = [
        _pan_with_interfaces("pan-ha-01", "10.0.0.1", ["vsys1", "0"]),
        _pan_with_interfaces("pan-ha-02", "10.0.0.2", ["vsys2", "0"]),
    ]
    runtime = {"pan-ha-01": _pan_runtime(), "pan-ha-02": _pan_runtime("passive", "active")}
    peers = {"pan-ha-01": "10.0.0.2", "pan-ha-02": "10.0.0.1"}
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    unit = _unit_by_id(report, "pan-ha-01+pan-ha-02")
    assert unit["display_name"] is None  # identity is unit_id alone, never VSYS-composed
    assert "vsys1" in unit["context_vsys"] and "vsys2" in unit["context_vsys"]
    assert unit["unit_id"] == "pan-ha-01+pan-ha-02"  # identity untouched


def test_pan_context_vsys_empty_when_no_vsys_evidence():
    rows = [_pan("pan-solo", "10.0.0.1")]
    runtime = {"pan-solo": _pan_runtime()}
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime)
    unit = _unit_by_id(report, "pan-solo")
    assert unit["display_name"] is None
    assert unit["context_vsys"] == []


# --------------------------------------------------------------------------
# AC-5 — PAN pairing (contract P7)
# --------------------------------------------------------------------------

def _pan_runtime(state="active", peer_state="passive", mode="active-passive"):
    return {
        "enabled": "yes", "state": state, "mode": mode,
        "peer_state": peer_state, "state_sync": "Synchronization Enabled",
    }


def test_ac5_peer_ip_resolving_to_exactly_one_entity_pairs():
    rows = [_pan("pan-ha-01", "10.0.0.1"), _pan("pan-ha-02", "10.0.0.2")]
    runtime = {"pan-ha-01": _pan_runtime(), "pan-ha-02": _pan_runtime("passive", "active")}
    peers = {"pan-ha-01": "10.0.0.2", "pan-ha-02": "10.0.0.1"}
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    pan_units = [u for u in report["units"] if u["vendor"] == "panorama"]
    assert len(pan_units) == 1
    assert pan_units[0]["members"] == ["pan-ha-01", "pan-ha-02"]


def test_healthy_pan_pair_is_not_reported_as_split_brain():
    """Regression, found by the OP.0a smoke run against the uitest fixture.

    Each PAN peer reports its own `state` AND its view of the peer's. Counting
    both from every member turned a perfectly healthy active/passive pair into
    two observed "active" states -> a false split-brain alarm on a healthy
    pair, which is the worst possible direction for this build to be wrong in.
    """
    rows = [_pan("pan-ha-01", "10.0.0.1"), _pan("pan-ha-02", "10.0.0.2")]
    runtime = {
        "pan-ha-01": _pan_runtime("active", "passive"),
        "pan-ha-02": _pan_runtime("passive", "active"),
    }
    peers = {"pan-ha-01": "10.0.0.2", "pan-ha-02": "10.0.0.1"}
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    unit = _unit_by_id(report, "pan-ha-01+pan-ha-02")
    assert unit["reason"] != "split_brain_observed"
    assert unit["verdict"] == VERDICT_INSUFFICIENT
    checks = {c["id"]: c for c in unit["checks"]}
    assert checks["no_split_brain"]["status"] == CHECK_PASS
    assert checks["viable_target"]["status"] == CHECK_PASS


def test_genuinely_split_brained_pan_pair_is_still_caught():
    """The fix above must not blind the check: two peers both reporting
    themselves active is a real split-brain and must still fail."""
    rows = [_pan("pan-ha-01", "10.0.0.1"), _pan("pan-ha-02", "10.0.0.2")]
    runtime = {
        "pan-ha-01": _pan_runtime("active", "passive"),
        "pan-ha-02": _pan_runtime("active", "passive"),
    }
    peers = {"pan-ha-01": "10.0.0.2", "pan-ha-02": "10.0.0.1"}
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    unit = _unit_by_id(report, "pan-ha-01+pan-ha-02")
    assert unit["verdict"] == VERDICT_UNSAFE
    assert unit["reason"] == "split_brain_observed"


def test_ac5_unresolvable_peer_ip_yields_single_member_unit_never_a_guess():
    rows = [_pan("pan-ha-01", "10.0.0.1"), _pan("pan-ha-02", "10.0.0.2")]
    runtime = {"pan-ha-01": _pan_runtime()}
    peers = {"pan-ha-01": "192.168.99.99"}  # not inventoried
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    pan_units = [u for u in report["units"] if u["vendor"] == "panorama"]
    assert len(pan_units) == 1
    assert pan_units[0]["members"] == ["pan-ha-01"]
    assert pan_units[0]["reason"] == "pan_ha_peer_unresolved"


def test_ac5_ambiguous_peer_ip_does_not_merge():
    rows = [_pan("pan-a", "10.0.0.9"), _pan("pan-b", "10.0.0.9"), _pan("pan-c", "10.0.0.3")]
    runtime = {"pan-c": _pan_runtime()}
    peers = {"pan-c": "10.0.0.9"}  # matches two entities
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    unit = _unit_by_id(report, "pan-c")
    assert unit["members"] == ["pan-c"]
    assert unit["reason"] == "pan_ha_peer_unresolved"


def test_ac5_ha_disabled_device_is_not_a_unit():
    rows = [_pan("pan-solo", "10.0.0.1")]
    runtime = {"pan-solo": {"enabled": "no", "state": None, "mode": None}}
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime)
    assert [u for u in report["units"] if u["vendor"] == "panorama"] == []


# --------------------------------------------------------------------------
# PAN HA peer-pairing identity closure (OP.0a.P7 revision) — mutual
# CONFIGURATION agreement required before pairing. `peer_ip` is
# configuration intent, never runtime proof (contract Q1/Q3); this is
# Grade A (READ-ONLY OP.0a pairing) only, never sufficient corroboration
# for any future CLASS 2 decision.
# --------------------------------------------------------------------------

def test_mutual_configuration_agreement_required_asymmetric_fails_closed():
    """A declares B as peer; B declares no peer at all. A one-sided
    relationship must not form a pair -- distinguishable from the generic
    unresolved reason."""
    rows = [_pan("pan-ha-01", "10.0.0.1"), _pan("pan-ha-02", "10.0.0.2")]
    runtime = {"pan-ha-01": _pan_runtime(), "pan-ha-02": _pan_runtime("passive", "active")}
    peers = {"pan-ha-01": "10.0.0.2"}  # pan-ha-02 declares nothing back
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    pan_units = [u for u in report["units"] if u["vendor"] == "panorama"]
    assert len(pan_units) == 2  # never a guessed pair -- each stays its own unresolved unit
    assert all(u["members"] != ["pan-ha-01", "pan-ha-02"] for u in pan_units)
    unit_a = _unit_by_id(report, "pan-ha-01")
    assert unit_a["members"] == ["pan-ha-01"]
    assert unit_a["reason"] == "pan_ha_peer_asymmetric"


def test_mutual_configuration_agreement_required_contradictory_fails_closed():
    """A declares B as peer; B declares C as peer (not A). Contradictory
    configuration must not form A+B, and must not guess."""
    rows = [_pan("pan-a", "10.0.0.1"), _pan("pan-b", "10.0.0.2"), _pan("pan-c", "10.0.0.3")]
    runtime = {
        "pan-a": _pan_runtime(), "pan-b": _pan_runtime("passive", "active"),
        "pan-c": _pan_runtime(),
    }
    peers = {"pan-a": "10.0.0.2", "pan-b": "10.0.0.3", "pan-c": "10.0.0.2"}
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    unit_a = _unit_by_id(report, "pan-a")
    assert unit_a["members"] == ["pan-a"]
    assert unit_a["reason"] == "pan_ha_peer_asymmetric"
    # pan-a+pan-b must never appear as a guessed pair.
    assert all(u["unit_id"] != "pan-a+pan-b" for u in report["units"])


def test_peer_pointing_to_self_fails_closed():
    """A device whose configured peer_ip equals its own management_ip must
    never pair with itself."""
    rows = [_pan("pan-solo", "10.0.0.1"), _pan("pan-other", "10.0.0.2")]
    runtime = {"pan-solo": _pan_runtime()}
    peers = {"pan-solo": "10.0.0.1"}  # points at itself
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    unit = _unit_by_id(report, "pan-solo")
    assert unit["members"] == ["pan-solo"]
    assert unit["reason"] == "pan_ha_peer_unresolved"


def test_mutual_agreement_pair_identity_stable_across_active_passive_swap():
    """The pair's unit_id must not depend on which member is currently
    ACTIVE vs STANDBY -- only on the (stable, alphabetically-ordered)
    identity strings of its two members."""
    rows = [_pan("pan-ha-01", "10.0.0.1"), _pan("pan-ha-02", "10.0.0.2")]
    peers = {"pan-ha-01": "10.0.0.2", "pan-ha-02": "10.0.0.1"}

    before = compute_ha_readiness(
        rows,
        pan_ha_runtime={"pan-ha-01": _pan_runtime("active", "passive"), "pan-ha-02": _pan_runtime("passive", "active")},
        pan_ha_peers=peers,
    )
    after = compute_ha_readiness(
        rows,
        pan_ha_runtime={"pan-ha-01": _pan_runtime("passive", "active"), "pan-ha-02": _pan_runtime("active", "passive")},
        pan_ha_peers=peers,
    )
    unit_before = _unit_by_id(before, "pan-ha-01+pan-ha-02")
    unit_after = _unit_by_id(after, "pan-ha-01+pan-ha-02")
    assert unit_before is not None and unit_after is not None
    assert unit_before["unit_id"] == unit_after["unit_id"]
    assert sorted(unit_before["members"]) == sorted(unit_after["members"])


def test_pan_ha_pair_unit_identity_never_uses_management_ip_serial_or_vsys():
    """Canonical PAN HA pair identity is the two members' entity ids only --
    never a management address, a single member's serial, a display label,
    or a vsys value."""
    rows = [_pan("pan-ha-01", "10.0.0.1", vsys="vsys1"), _pan("pan-ha-02", "10.0.0.2", vsys="vsys2")]
    runtime = {"pan-ha-01": _pan_runtime(), "pan-ha-02": _pan_runtime("passive", "active")}
    peers = {"pan-ha-01": "10.0.0.2", "pan-ha-02": "10.0.0.1"}
    report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
    unit = _unit_by_id(report, "pan-ha-01+pan-ha-02")
    assert unit is not None
    assert "10.0.0.1" not in unit["unit_id"] and "10.0.0.2" not in unit["unit_id"]
    assert "vsys1" not in unit["unit_id"] and "vsys2" not in unit["unit_id"]


# --------------------------------------------------------------------------
# AC-6 — SAFE_TO_FAILOVER is unreachable (the load-bearing invariant, P4)
# --------------------------------------------------------------------------

def test_ac6_safe_verdict_is_unreachable_over_an_exhaustive_matrix():
    """Contract P4. Generate every combination of cluster mode x member-role
    assignment x pairing shape and assert no input reaches SAFE_TO_FAILOVER or
    DEGRADED_PROCEED_WITH_RISK.

    This is what stops a future edit from quietly making a green light
    reachable without also changing OP0A_EVALUABLE_CHECKS and its gate.
    """
    cp_roles = ["ACTIVE", "STANDBY", "STANDBY READY", "READY", "BACKUP", "DOWN", "LOST", ""]
    modes = list(CLUSTERXL_CLUSTER_MODES)
    seen = set()

    for mode, role_a, role_b in itertools.product(modes, cp_roles, cp_roles):
        rows = [_cp("m1", cluster="c"), _cp("m2", cluster="c")]
        runtime = {
            "m1": {"ha_role": role_a, "ha_cluster_mode": mode},
            "m2": {"ha_role": role_b, "ha_cluster_mode": mode},
        }
        report = compute_ha_readiness(rows, cp_ha_runtime=runtime)
        for unit in report["units"]:
            seen.add(unit["verdict"])
            assert unit["verdict"] not in (VERDICT_SAFE, VERDICT_DEGRADED), (
                f"OP.0a produced {unit['verdict']} for mode={mode!r} "
                f"roles=({role_a!r}, {role_b!r}) -- contract P4 violated"
            )

    pan_states = ["active", "passive", "active-primary", "active-secondary", "suspended", ""]
    for state, peer_state in itertools.product(pan_states, pan_states):
        rows = [_pan("p1", "10.0.0.1"), _pan("p2", "10.0.0.2")]
        runtime = {
            "p1": _pan_runtime(state, peer_state),
            "p2": _pan_runtime(peer_state, state),
        }
        peers = {"p1": "10.0.0.2", "p2": "10.0.0.1"}
        report = compute_ha_readiness(rows, pan_ha_runtime=runtime, pan_ha_peers=peers)
        for unit in report["units"]:
            seen.add(unit["verdict"])
            assert unit["verdict"] not in (VERDICT_SAFE, VERDICT_DEGRADED)

    # Sanity: the matrix genuinely exercised more than one outcome, so the
    # assertion above is not passing vacuously.
    assert {VERDICT_UNSAFE, VERDICT_INSUFFICIENT} <= seen


def test_ac6_evaluable_check_set_is_the_documented_two():
    """If this set grows, OP0A_EVALUABLE_CHECKS' gate reasoning must be
    revisited -- the failure message is the point of the test."""
    assert OP0A_EVALUABLE_CHECKS == frozenset({"viable_target", "no_split_brain"})


# --------------------------------------------------------------------------
# AC-7 / AC-8 — decisive negative verdicts
# --------------------------------------------------------------------------

def test_ac7_two_active_members_is_split_brain_unsafe():
    rows = [_cp("m1", cluster="c"), _cp("m2", cluster="c")]
    runtime = {
        "m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
        "m2": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
    }
    report = compute_ha_readiness(rows, cp_ha_runtime=runtime)
    unit = _unit_by_id(report, "c")
    assert unit["verdict"] == VERDICT_UNSAFE
    assert unit["reason"] == "split_brain_observed"


def test_ac8_load_sharing_is_not_a_failover_unit_and_not_unsafe():
    """P3: 'fail this cluster over' is not a coherent request for an LS
    cluster, so it must get neither a safe nor an unsafe answer."""
    for mode in ("load_sharing_unicast", "load_sharing_multicast"):
        rows = [_cp("m1", cluster="c"), _cp("m2", cluster="c")]
        runtime = {
            "m1": {"ha_role": "ACTIVE", "ha_cluster_mode": mode},
            "m2": {"ha_role": "ACTIVE", "ha_cluster_mode": mode},
        }
        report = compute_ha_readiness(rows, cp_ha_runtime=runtime)
        unit = _unit_by_id(report, "c")
        assert unit["verdict"] == VERDICT_NOT_A_FAILOVER_UNIT
        assert unit["verdict"] != VERDICT_UNSAFE
        assert unit["reason"] == "load_sharing_member_evacuation_not_failover"


def test_no_viable_target_is_unsafe():
    rows = [_cp("m1", cluster="c"), _cp("m2", cluster="c")]
    runtime = {
        "m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
        "m2": {"ha_role": "DOWN", "ha_cluster_mode": "ha_new_mode"},
    }
    report = compute_ha_readiness(rows, cp_ha_runtime=runtime)
    assert _unit_by_id(report, "c")["reason"] == "no_viable_target"


def test_no_ha_runtime_evidence_is_insufficient_not_unsafe():
    rows = [_cp("m1", cluster="c"), _cp("m2", cluster="c")]
    report = compute_ha_readiness(rows)
    assert _unit_by_id(report, "c")["verdict"] == VERDICT_INSUFFICIENT


# --------------------------------------------------------------------------
# AC-9 — no write path exists in the package (P5)
# --------------------------------------------------------------------------

def test_ac9_failover_package_exposes_no_write_capable_symbol():
    import utils.failover as failover

    forbidden = ("executor", "plan", "action", "rollback", "execute", "failover_now")
    for name in dir(failover):
        lowered = name.lower()
        assert not any(token in lowered for token in forbidden), (
            f"utils.failover exposes {name!r} -- OP.0a is write-free (contract P5)"
        )


def test_ac9_failover_package_contains_only_assessment_and_preflight_model():
    """P5's actual boundary is "no write-capable surface" (see
    utils/failover/__init__.py docstring), not "exactly one module forever".
    `preflight_model` (OP.0b S1, frozen contract, pure/zero-I/O evidence
    model) is the one deliberate, contract-named addition; the structural
    assertion is updated in the same build that added it so the boundary
    stays enforced rather than merely current."""
    from pathlib import Path

    import utils.failover as failover

    package_dir = Path(failover.__file__).parent
    modules = sorted(p.name for p in package_dir.glob("*.py"))
    # OP.0b S7 added `preflight_readiness.py` -- the one typed fact->check
    # mapping over PreflightSnapshot; pure, zero-I/O, verdict-free (proven by
    # tests/test_op0b_s7_readiness_v2.py). Updated in the same build.
    assert modules == ["__init__.py", "assessment.py", "preflight_model.py", "preflight_readiness.py"]
    assert not (package_dir / "adapters").exists()


# --------------------------------------------------------------------------
# AC-10 — insufficient-evidence completeness (correctness rule 6)
# --------------------------------------------------------------------------

def test_ac10_every_unit_carries_all_seven_stop_conditions():
    rows = [
        _cp("m1", cluster="c"), _cp("m2", cluster="c"),
        _pan("p1", "10.0.0.1"), _pan("p2", "10.0.0.2"),
    ]
    runtime = {"m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
               "m2": {"ha_role": "STANDBY", "ha_cluster_mode": "ha_new_mode"}}
    pan_runtime = {"p1": _pan_runtime(), "p2": _pan_runtime("passive", "active")}
    report = compute_ha_readiness(
        rows, cp_ha_runtime=runtime, pan_ha_runtime=pan_runtime,
        pan_ha_peers={"p1": "10.0.0.2", "p2": "10.0.0.1"},
    )
    expected_ids = [cid for cid, _ in STOP_CONDITIONS]
    assert report["units"]
    for unit in report["units"]:
        assert [c["id"] for c in unit["checks"]] == expected_ids


def test_ac10_unevaluated_conditions_name_the_missing_command():
    rows = [_cp("m1", cluster="c"), _cp("m2", cluster="c")]
    runtime = {"m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
               "m2": {"ha_role": "STANDBY", "ha_cluster_mode": "ha_new_mode"}}
    report = compute_ha_readiness(rows, cp_ha_runtime=runtime)
    for check in _unit_by_id(report, "c")["checks"]:
        if check["status"] == CHECK_INSUFFICIENT:
            assert check["missing_evidence"], f"{check['id']} has no missing_evidence label"
        else:
            assert check["status"] in (CHECK_PASS, CHECK_FAIL)


# --------------------------------------------------------------------------
# AC-11 — privacy
# --------------------------------------------------------------------------

def test_ac11_report_carries_no_management_address_or_device_text():
    rows = [
        _cp("cp-core-01", cluster="cp-core"), _cp("cp-core-02", cluster="cp-core"),
        _pan("pan-ha-01", "203.0.113.77"), _pan("pan-ha-02", "203.0.113.78"),
    ]
    runtime = {"cp-core-01": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
               "cp-core-02": {"ha_role": "STANDBY", "ha_cluster_mode": "ha_new_mode"}}
    pan_runtime = {"pan-ha-01": _pan_runtime(), "pan-ha-02": _pan_runtime("passive", "active")}
    report = compute_ha_readiness(
        rows, cp_ha_runtime=runtime, pan_ha_runtime=pan_runtime,
        pan_ha_peers={"pan-ha-01": "203.0.113.78", "pan-ha-02": "203.0.113.77"},
    )
    serialized = json.dumps(report)
    assert "203.0.113" not in serialized
    assert "Cluster Mode" not in serialized
    assert "192.0.2" not in serialized


# --------------------------------------------------------------------------
# AC-2 / AC-12 / AC-13 — offline, fail-safe, unscheduled
# --------------------------------------------------------------------------

def test_ac2_assessment_performs_no_socket_io(monkeypatch):
    """AC-2: the module must be pure derivation. Make any socket use explode."""
    import socket

    def _boom(*args, **kwargs):  # pragma: no cover - must never run
        raise AssertionError("OP.0a attempted network I/O")

    monkeypatch.setattr(socket, "socket", _boom)
    monkeypatch.setattr(socket, "create_connection", _boom)

    rows = [_cp("m1", cluster="c"), _cp("m2", cluster="c")]
    report = compute_ha_readiness(rows, cp_ha_runtime={
        "m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
        "m2": {"ha_role": "STANDBY", "ha_cluster_mode": "ha_new_mode"},
    })
    assert report["schema"] == "securityexpert-ha-readiness-v1"
    assert report["units"]


def test_ac12_corrupt_state_files_degrade_to_no_evidence(tmp_path):
    from application.workflows.failover import _load_cp_ha_runtime, _load_pan_ha_runtime

    (tmp_path / "cp_config_telemetry.json").write_text("{not json", encoding="utf-8")
    (tmp_path / "pan_config_telemetry.json").write_text("[]", encoding="utf-8")
    assert _load_cp_ha_runtime(tmp_path) == {}
    assert _load_pan_ha_runtime(tmp_path) == ({}, {})
    # Absent files behave identically.
    assert _load_cp_ha_runtime(tmp_path / "nope") == {}


def test_ac13_ha_readiness_is_not_an_allowlisted_workflow(tmp_path):
    assert "ha-readiness" not in ALLOWLISTED_WORKFLOWS
    policy_dir = tmp_path / "state"
    policy_dir.mkdir()
    (policy_dir / "scheduler_policy.json").write_text(json.dumps({
        "version": 1, "enabled": True,
        "schedule": [{"workflow": "ha-readiness", "interval_minutes": 60}],
    }), encoding="utf-8")
    with pytest.raises(SchedulerPolicyError, match="non-allowlisted"):
        load_scheduler_policy(tmp_path)


# --------------------------------------------------------------------------
# CLI wiring
# --------------------------------------------------------------------------

def test_ha_readiness_check_is_a_mutually_exclusive_maintenance_mode():
    import main

    parser = main.build_parser() if hasattr(main, "build_parser") else None
    if parser is None:  # pragma: no cover - parser accessor differs
        from application.cli import build_parser as _bp
        parser = _bp()
    args = parser.parse_args(["--ha-readiness-check"])
    assert args.ha_readiness_check is True


def test_ha_readiness_check_rejects_combination_with_collection():
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    args = parser.parse_args(["--ha-readiness-check", "--render-only"])
    with pytest.raises(SystemExit):
        validate_modes(args, parser)
