"""cp_identity_edges (0.6.1B.1.2 / 0.6.1C) review-and-document build.

Contract: docs/history/phase/PHASE0_6_1C_CP_IDENTITY_EDGES_REVIEW.md.
No production code changed by this build -- these tests prove the two
findings the review reached: (1) the MEDIUM-confidence identity-gate
fallback is anchored on the exact management-selected IP, never on any
hostname/naming pattern; (2) the pre-poll exclusion filter and the
post-connect identity gate are structurally decoupled and cannot interact.
All identities below are fabricated.
"""
from configuration import checkpoint_config_collector as collector
from utils.inventory_exclusions import checkpoint_transport_value
import pytest

pytestmark = pytest.mark.configuration


# ---------------------------------------------------------------------------
# Finding 1: _collector_identity_gate's MEDIUM-confidence acceptance path is
# anchored on target.management_ip (the already-selected, already-connected
# endpoint), not on hostname/naming pattern agreement.
# ---------------------------------------------------------------------------

def test_medium_confidence_acceptance_survives_a_completely_unrelated_hostname():
    """A totally unrelated observed hostname must still be MEDIUM-accepted,
    because acceptance here is anchored on being connected to the exact
    selected management IP, not on any naming resemblance. This is the
    proof for AC-2/the review's second in-scope concern.
    """
    target = collector.ProbeTarget(
        role="standalone_gateway",
        device="MGMT-OBJECT-NAME",
        management_ip="192.0.2.61",
        object_type="gateway",
        selection_source="management_discovery",
    )
    gate = collector._collector_identity_gate(
        target=target,
        observed_hostname="totally-unrelated-hostname",
        hostname_success=True,
        version_success=False,
        configuration_success=True,
        authenticated=True,
    )
    assert gate["accepted"] is True
    assert gate["confidence"] == "MEDIUM"
    assert gate["name_relation"] == "different_observed"
    assert gate["acceptance_basis"] == "hostname_plus_read_only_configuration_capability"


def test_medium_confidence_acceptance_requires_a_selected_management_endpoint():
    """Without an actual selected management IP, the fallback must never
    accept -- proving the IP is the real identity anchor, not the hostname
    comparison, even when the hostname matches exactly.
    """
    target = collector.ProbeTarget(
        role="standalone_gateway",
        device="MGMT-OBJECT-NAME",
        management_ip=None,
        object_type="gateway",
        selection_source="management_discovery",
    )
    gate = collector._collector_identity_gate(
        target=target,
        observed_hostname="MGMT-OBJECT-NAME",  # exact match, but no endpoint selected
        hostname_success=True,
        version_success=False,
        configuration_success=True,
        authenticated=True,
    )
    assert gate["accepted"] is False
    assert gate["acceptance_basis"] == "insufficient_identity_evidence"


def test_medium_confidence_acceptance_requires_successful_configuration_read():
    """The fallback must not accept on hostname evidence alone -- a
    successful read-only configuration capability is also required."""
    target = collector.ProbeTarget(
        role="standalone_gateway",
        device="MGMT-OBJECT-NAME",
        management_ip="192.0.2.61",
        object_type="gateway",
        selection_source="management_discovery",
    )
    gate = collector._collector_identity_gate(
        target=target,
        observed_hostname="MGMT-OBJECT-NAME",
        hostname_success=True,
        version_success=False,
        configuration_success=False,
        authenticated=True,
    )
    assert gate["accepted"] is False


# ---------------------------------------------------------------------------
# Finding 2: the pre-poll exclusion filter and the post-connect identity gate
# are structurally decoupled -- they act on disjoint data flows at different
# pipeline stages and can never interact.
# ---------------------------------------------------------------------------

def test_exclusion_transport_is_exact_match_only_never_touches_observed_hostname():
    """checkpoint_transport_value() only ever encodes management-object
    names for the server-side exact-match filter in cp_inventory.sh
    (awk skip[name]=1 / !($1 in skip)) -- it has no parameter for, and never
    sees, an observed Gaia hostname. The identity gate's normalized/shortname
    matching logic is therefore structurally unreachable from this path.
    """
    value = checkpoint_transport_value(["fw-core-01", "FW-CORE-02"])
    assert value == "fw-core-01,FW-CORE-02"
    # No normalization applied -- exact strings only, case preserved verbatim,
    # unlike _identity_relation's lower()/normalize() comparison.
    assert "fw-core-01" in value and "FW-CORE-02" in value


def test_excluded_device_never_reaches_the_identity_gate_because_it_is_never_a_target():
    """An excluded device is filtered out server-side (cp_inventory.sh's
    awk pass) before output/cp.json / vsx.json / cp_telemetry.json are ever
    written. configuration_config_collector's target selection
    (_pick_targets) reads only those already-filtered artifacts, so an
    excluded device structurally cannot become a ProbeTarget/PhysicalTarget
    and therefore never reaches _identity_gate / _identity_relation at all --
    there is no code path where the two mechanisms compare the same name.

    This test documents the finding structurally: excluded_device_names is
    consumed exclusively by checkpoint/cp_runner.py (the raw inventory
    collector) and nowhere in configuration/checkpoint_config_collector.py
    or configuration/checkpoint_config_probe.py.
    """
    import inspect

    from checkpoint import cp_runner
    from configuration import checkpoint_config_collector as ccc
    from configuration import checkpoint_config_probe as ccp

    assert "excluded_device_names" in inspect.getsource(cp_runner)
    assert "excluded_device_names" not in inspect.getsource(ccc)
    assert "excluded_device_names" not in inspect.getsource(ccp)
    assert "load_inventory_exclusions" not in inspect.getsource(ccc)
    assert "load_inventory_exclusions" not in inspect.getsource(ccp)
