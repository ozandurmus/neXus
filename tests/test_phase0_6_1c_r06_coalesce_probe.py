"""Tests for 0.6.1C R06 same-process coalescing probe."""
from __future__ import annotations

import threading

import pytest

from utils.collection_executor import (
    CollectionAdmissionError,
    CoordinatorDecision,
    Provenance,
    RuntimeCollectionServices,
    execute_admitted_collection,
)

pytestmark = pytest.mark.discovery


# ---------------------------------------------------------------------------
# Unit-level coalescing contract (production path)
# ---------------------------------------------------------------------------


def test_r06_same_process_second_request_coalesces():
    """Second concurrent request to the same endpoint is COALESCED; its
    operation is never called — this is the core R06 contract."""
    services = RuntimeCollectionServices()
    first_admitted = threading.Event()
    second_done = threading.Event()
    second_operation_called = []

    def first_operation():
        first_admitted.set()
        second_done.wait(timeout=5)
        return {"status": "ok"}

    def run_second():
        first_admitted.wait(timeout=5)
        try:
            execute_admitted_collection(
                services,
                vendor="checkpoint",
                workflow_scope="cp",
                canonical_ids=["CP-MGMT-R06-TEST"],
                provenance=Provenance.SCHEDULED.value,
                operation=lambda: second_operation_called.append(True),
            )
        except CollectionAdmissionError as exc:
            assert exc.decision == CoordinatorDecision.COALESCED
            assert exc.job.coalesced_to is not None
        finally:
            second_done.set()

    t2 = threading.Thread(target=run_second, daemon=False)
    t2.start()

    execute_admitted_collection(
        services,
        vendor="checkpoint",
        workflow_scope="cp",
        canonical_ids=["CP-MGMT-R06-TEST"],
        provenance=Provenance.MANUAL.value,
        operation=first_operation,
    )

    t2.join(timeout=5)
    assert second_operation_called == [], "second operation must not be called when coalesced"
    assert services.coordinator.active_jobs() == []


def test_r06_probe_script_returns_pass():
    """The default standalone probe is explicitly synthetic preflight."""
    import _realenv_r06_coalesce_probe as probe

    result = probe._run_probe()

    assert result["r06_mechanism_pass"] is True, f"Probe failed: {result}"
    assert result["r06_real_env_pass"] is False
    assert result["evidence_origin"] == "synthetic_preflight"
    assert result["network_access"] is False
    assert result["credentials_used"] is False
    assert result["second_request_decision"] == CoordinatorDecision.COALESCED.value
    assert result["second_operation_called"] is False
    assert result["first_request_admitted"] is True
    assert result["first_request_completed"] is True
    assert result["active_jobs_after_probe"] == 0
    assert result["failure_family"] is None


def test_r06_no_second_session_flag_set():
    """r06_no_second_session must be True when coalescing works correctly."""
    import _realenv_r06_coalesce_probe as probe

    result = probe._run_probe()
    assert result["r06_no_second_session"] is True


def test_r06_probe_main_exits_zero(capsys):
    """probe.main() must exit 0 on a clean environment."""
    import _realenv_r06_coalesce_probe as probe

    with pytest.raises(SystemExit) as exc:
        probe.main([])

    assert exc.value.code == 0
    captured = capsys.readouterr()
    assert "r06_mechanism_pass" in captured.out
    assert "PASS" in captured.err


def test_r06_real_environment_mode_requires_one_real_operation():
    """A supplied real operation runs once while the overlap is coalesced."""
    import _realenv_r06_coalesce_probe as probe

    operation_calls = []
    result = probe._run_probe(
        lambda: operation_calls.append("called") or {"status": "ok"},
        canonical_id="CP-MGMT-R06-REAL-TEST",
        real_environment=True,
    )

    assert operation_calls == ["called"]
    assert result["evidence_origin"] == "real_environment"
    assert result["network_access"] is True
    assert result["r06_real_env_pass"] is True
    assert result["second_operation_called"] is False


def test_r06_different_endpoints_are_not_coalesced():
    """Requests to different canonical IDs must each be ADMITTED independently."""
    services = RuntimeCollectionServices()

    r1 = execute_admitted_collection(
        services,
        vendor="checkpoint",
        workflow_scope="cp",
        canonical_ids=["CP-MGMT-R06-A"],
        provenance=Provenance.MANUAL.value,
        operation=lambda: {"status": "ok"},
    )
    r2 = execute_admitted_collection(
        services,
        vendor="paloalto",
        workflow_scope="pan-config",
        canonical_ids=["PAN-MGMT-R06-B"],
        provenance=Provenance.MANUAL.value,
        operation=lambda: {"status": "ok"},
    )
    assert r1 == {"status": "ok"}
    assert r2 == {"status": "ok"}
    assert services.coordinator.active_jobs() == []


def test_r06_coordinator_budget_unchanged_after_probe():
    """Concurrency budget must not be modified by the probe."""
    import _realenv_r06_coalesce_probe as probe
    from utils.collection_executor import DEFAULT_CONCURRENCY_BUDGETS

    probe._run_probe()

    # Budget for checkpoint/cp scope must remain at its default value.
    services = RuntimeCollectionServices()
    snapshot = services.coordinator.budget_snapshot()
    cp_key = next((k for k in snapshot if "checkpoint" in k), None)
    if cp_key is not None:
        assert snapshot[cp_key]["capacity"] == DEFAULT_CONCURRENCY_BUDGETS.get(cp_key, 1)
