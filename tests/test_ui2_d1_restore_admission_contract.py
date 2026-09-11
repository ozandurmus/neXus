"""Pin the frozen D1 amendment shape without implementing device writes."""
from pathlib import Path
import re

import pytest

pytestmark = pytest.mark.runtime_platform
DESIGN = Path(__file__).resolve().parents[1] / "docs" / "design"


def section(filename, start, end):
    return (DESIGN / filename).read_text(encoding="utf-8").split(start, 1)[1].split(end, 1)[0]


def test_c7_seventh_check_is_separate_and_keeps_existing_order():
    battery = section("UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md",
                      "### 5.3 Preconditions", "### 5.4 Execution")
    rows = re.findall(r"^\| ([1-7]) \| (.*)$", battery, re.MULTILINE)
    assert [number for number, _ in rows] == list("1234567")
    assert [re.search(r"\*\*(.*?)\*\*", row).group(1) for _, row in rows] == [
        "Connectivity", "Backup validity", "Target identity match", "Version match",
        "No concurrent restore or backup against the same target", "Credential resolution",
        "No unreconciled prior restore-write outcome",
    ]
    assert all("has_unreconciled_prior" not in row for _, row in rows[:6])
    assert "C7_RESTORE_NO_UNRECONCILED_PRIOR" in rows[6][1]
    assert "non-authoritative" in rows[6][1]
    assert "unreadable ledger refuses compilation" in rows[6][1]
    assert "NOT_APPLICABLE" in rows[6][1]
    assert "Checks 1–7" in battery


def test_c2_keeps_six_claim_time_checks_with_independent_restore_ledger():
    battery = section("UI2_0_C2_JOB_EXECUTION_CONTRACT.md",
                      "## 6. Pre-execution checks", "## 7. Schedules")
    rows = dict(re.findall(r"^\| ([1-9]) \| (.*)$", battery, re.MULTILINE))
    assert list(rows) == list("123456")
    assert "RestoreConnectivityFreshnessPolicy" in rows["2"]
    assert "RestoreWriteLedger.has_unreconciled_prior" in rows["4"]
    assert "re-read fresh inside this same admission section" in rows["4"]
    assert "unreadable ledger ⇒ `BLOCKED`" in rows["4"]
    assert "Never trust C7's earlier check-7 pass" in rows["4"]
    assert "CLASS_1_RECOVERY_WRITE" in rows["4"]
    assert "minimum interval" in rows["4"]
    for reason in ("TARGET_CLUSTERXL_MEMBER_UNSUPPORTED",
                   "TARGET_TOPOLOGY_EVIDENCE_MISSING_OR_STALE",
                   "TARGET_TOPOLOGY_EVIDENCE_CONFLICTING"):
        assert reason in rows["5"]
    assert "claim-time counterpart of C7's compile-time `UNSUPPORTED_CLUSTERXL_MEMBER`" in rows["5"]
    assert "VSX-context targets remain unsupported" in rows["5"]
    assert "CLAIMED` → `REJECTED`" in battery
    assert "no device is contacted" in battery.lower()
    assert "no step attempt" in battery.lower()


def test_restore_claim_refusal_is_terminal_and_class_1b_never_auto_retries():
    ledger = (DESIGN / "RESTORE_CONTROLLED_WRITE_LEDGER.md").read_text(encoding="utf-8")
    retry_table = section("UI2_0_C2_JOB_EXECUTION_CONTRACT.md",
                          "### 5.3 Retry rules per action class", "### 5.4 Step log")
    assert "terminal `REJECTED`" in ledger
    assert "not selected by claim queries" in ledger
    assert "new job and approval path" in " ".join(ledger.split())
    assert "CLASS_1B_CONTROLLED_RESTORE_WRITE" in retry_table
    assert "Never auto-retries after execution begins" in retry_table
    assert "new job with its own approval path" in retry_table


def test_restore_topology_and_connectivity_never_infer_permission():
    battery = section("UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md",
                      "### 5.3 Preconditions", "### 5.4 Execution")
    battery = " ".join(battery.split())
    for term in ("RESTORE_TARGET_TOPOLOGY_ELIGIBILITY", "STANDALONE_PHYSICAL_DEVICE",
                 "UNSUPPORTED_CLUSTERXL_MEMBER", "NOT_EVALUABLE", "before approval",
                 "VSX-context restore is unsupported", "runtime restore remains disabled"):
        assert term.lower() in battery.lower()
    assert "compile-time `UNSUPPORTED_CLUSTERXL_MEMBER` outcome maps to `TARGET_CLUSTERXL_MEMBER_UNSUPPORTED` at claim" in battery
    assert "Numeric timeout/TTL values remain `UNKNOWN`" in battery
    assert "insufficient cached evidence falls back to the active probe" in battery
    assert "per-vendor/platform council-reviewed" in battery
