"""OP.0b S3 — Check Point preflight parse-scope extraction.

Contract: docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md
(FROZEN WITH REAL-ENV VALIDATION GATES). Proves `_parse_clusterxl_stat_preflight_fields`
and the VSX "Single VS Failover" mode recognition read more of the SAME
already-fetched `cphaprob stat` buffer `_parse_clusterxl_runtime_role`/
`_parse_clusterxl_cluster_mode` already read, issue no new command/SSH
invocation, and degrade safely on absent/malformed output. Synthetic
fixtures only, per the task's §18/§21 requirements.
"""
from __future__ import annotations

from configuration import checkpoint_config_collector as cp_collector

import pytest

pytestmark = pytest.mark.configuration

_HA_TWO_MEMBER = """Cluster Mode:   High Availability (Active Up)

Number     Unique Address  Assigned Load   State          Name
1 (local)  192.0.2.11      100%            ACTIVE         member-a
2          192.0.2.12      0%              STANDBY        member-b
"""

_HA_STANDBY_LOCAL = """Cluster Mode:   High Availability (Active Up)

Number     Unique Address  Assigned Load   State          Name
1          192.0.2.11      100%            ACTIVE         member-a
2 (local)  192.0.2.12      0%              STANDBY        member-b
"""

_HA_ONE_MEMBER_ONLY = """Cluster Mode:   High Availability (Active Up)

Number     Unique Address  Assigned Load   State          Name
1 (local)  192.0.2.11      100%            ACTIVE         member-a
"""

_HA_LOCAL_ATTENTION = """Cluster Mode:   High Availability (Active Up)

Number     Unique Address  Assigned Load   State          Name
1 (local)  192.0.2.11      100%            ACTIVE ATTENTION  member-a
2          192.0.2.12      0%              STANDBY        member-b
"""

_VSX_SINGLE_VS_FAILOVER = """Cluster Mode:   Single VS Failover

Number     Unique Address  Assigned Load   State          Name
1 (local)  192.0.2.11      100%            ACTIVE         member-a
2          192.0.2.12      0%              STANDBY        member-b
"""

_UNRECOGNISED_BANNER = """Some unexpected banner from a future Gaia release

1 (local)  192.0.2.11  100%  UNEXPECTED_FUTURE_STATE  member-a
"""


# 1/2/3 -- role parse for the buffers this build also reads -----------------

def test_1_active_local_role_still_parses():
    assert cp_collector._parse_clusterxl_runtime_role(_HA_TWO_MEMBER, "member-a") == "ACTIVE"


def test_2_standby_local_role_still_parses():
    assert cp_collector._parse_clusterxl_runtime_role(_HA_STANDBY_LOCAL, "member-b") == "STANDBY"


def test_3_unknown_role_stays_unknown():
    assert cp_collector._parse_clusterxl_runtime_role(_UNRECOGNISED_BANNER, "member-z") is None


# 4/5 -- cluster mode, incl. new VSX recognition -----------------------------

def test_4_cluster_mode_parsed_where_frozen():
    assert cp_collector._parse_clusterxl_cluster_mode(_HA_TWO_MEMBER) == "ha_new_mode"


def test_5_unknown_mode_stays_unknown():
    assert cp_collector._parse_clusterxl_cluster_mode(_UNRECOGNISED_BANNER) == "unknown"


def test_single_vs_failover_mode_recognized():
    assert cp_collector._parse_clusterxl_cluster_mode(_VSX_SINGLE_VS_FAILOVER) == "vsx_single_vs_failover"


def test_single_vs_failover_is_a_distinct_enum_value_not_ha_new_mode():
    assert "vsx_single_vs_failover" in cp_collector.CLUSTERXL_CLUSTER_MODES
    assert cp_collector._parse_clusterxl_cluster_mode(_VSX_SINGLE_VS_FAILOVER) != "ha_new_mode"


def test_existing_ha_new_mode_recognition_undisturbed():
    for stdout in (_HA_TWO_MEMBER, _HA_STANDBY_LOCAL, _HA_ONE_MEMBER_ONLY, _HA_LOCAL_ATTENTION):
        assert cp_collector._parse_clusterxl_cluster_mode(stdout) == "ha_new_mode"


# 10 -- peer/member row states ------------------------------------------------

def test_peer_row_state_parsed_for_two_member_cluster():
    fields = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_TWO_MEMBER, "member-a")
    assert fields["peer_row_states"] == ("STANDBY",)


def test_peer_row_state_independent_of_which_member_is_local():
    fields_a = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_TWO_MEMBER, "member-a")
    fields_b = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_STANDBY_LOCAL, "member-b")
    assert fields_a["peer_row_states"] == ("STANDBY",)
    assert fields_b["peer_row_states"] == ("ACTIVE",)


def test_one_physical_member_observation_does_not_synthesize_peer_observation():
    fields = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_ONE_MEMBER_ONLY, "member-a")
    assert fields["peer_row_states"] == ()


def test_malformed_output_degrades_safely_no_peer_rows():
    fields = cp_collector._parse_clusterxl_stat_preflight_fields(_UNRECOGNISED_BANNER, "member-z")
    assert fields["peer_row_states"] == ()
    assert fields["local_attention"] is None


def test_empty_stdout_degrades_safely():
    fields = cp_collector._parse_clusterxl_stat_preflight_fields("", None)
    assert fields["peer_row_states"] == ()
    assert fields["local_attention"] is None


def test_peer_row_state_leaks_no_address_or_name():
    fields = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_TWO_MEMBER, "member-a")
    for state in fields["peer_row_states"]:
        assert "192.0.2" not in state
        assert "member-b" not in state


# local attention (category J corroboration) ---------------------------------

def test_local_attention_true_when_role_is_active_attention():
    fields = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_LOCAL_ATTENTION, "member-a")
    assert fields["local_attention"] is True


def test_local_attention_false_for_plain_active():
    fields = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_TWO_MEMBER, "member-a")
    assert fields["local_attention"] is False


def test_local_attention_unknown_when_role_undetermined():
    fields = cp_collector._parse_clusterxl_stat_preflight_fields(_UNRECOGNISED_BANNER, "unrelated-host")
    assert fields["local_attention"] is None


# --- No fabricated future-command evidence (task §17 test 18) --------------

def test_no_future_command_fields_fabricated():
    fields = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_TWO_MEMBER, "member-a")
    assert set(fields) == {"peer_row_states", "local_attention"}


# --- Regression guard (task §18/§21): zero new command / SSH / retry -------

def test_no_new_cphaprob_variant_or_command_introduced():
    import inspect

    src = inspect.getsource(cp_collector._parse_clusterxl_stat_preflight_fields)
    src += inspect.getsource(cp_collector._parse_clusterxl_cluster_mode)
    for token in (
        "cphaprob state", "cphaprob -ia", "cphaprob -a", "show_failover",
        "syncstat", "fw ctl pstat", "fw stat", "show cluster",
        "vsenv", "ssh.", "paramiko", "subprocess",
    ):
        assert token not in src, f"found a forbidden token: {token!r}"


def test_no_new_thread_pool_executor_or_retry_loop_introduced():
    import inspect

    src = inspect.getsource(cp_collector._parse_clusterxl_stat_preflight_fields)
    for token in ("ThreadPoolExecutor", "for attempt in range", "retry", "requests.Session"):
        assert token not in src


def test_no_raw_command_output_persisted_by_extraction_function():
    """The extraction function returns only classification tokens/bools --
    never the raw stdout buffer itself."""
    fields = cp_collector._parse_clusterxl_stat_preflight_fields(_HA_TWO_MEMBER, "member-a")
    serialized = repr(fields)
    assert "192.0.2" not in serialized
    assert "member-a" not in serialized
    assert "member-b" not in serialized


# --- include_preflight_fields opt-in, dormant by design ---------------------

def test_collect_host_signature_carries_dormant_opt_in_flag():
    import inspect

    sig = inspect.signature(cp_collector._collect_host)
    assert "include_preflight_fields" in sig.parameters
    assert sig.parameters["include_preflight_fields"].default is False


def test_production_call_site_does_not_opt_in():
    import inspect

    src = inspect.getsource(cp_collector.run_checkpoint_config_collection)
    assert "include_preflight_fields=True" not in src
