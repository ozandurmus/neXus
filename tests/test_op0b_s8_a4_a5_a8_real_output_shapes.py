"""SecurityExpert -- OP.0b S8-A, real output shapes for A4/A5/A8.

Once the persistent Expert shell made A3-A7 actually execute, the S8-A retry
returned every approved read `success` -- and three checks were still
INSUFFICIENT_EVIDENCE for a different reason:

    viable_target             unknown:cp_pnote_any_problem   (A5)
    control_sync_link_health  unknown:cp_link_any_down       (A4)
    flap_history              unknown:cp_failover_count      (A8)

`facts=2` on A4/A5 (one fact per member) is the `observed=False` branch: the
parsers matched *zero* rows. A8 returned `facts=6` -- observed, but with no
count. So these are bounded extraction defects inside already-frozen
semantics, the same class as the `fw stat` column-table gap: the assumed
output shape was not the device's shape.

These tests pin the real shapes AND keep the fail-closed law: an output
matching no known shape must stay unobserved/None, never a guessed value.
No readiness semantics change here -- only what the parsers can recognize.
"""
from __future__ import annotations

import pytest

from checkpoint.cp_preflight_extraction import (
    parse_cp_failover_history,
    parse_cphaprob_a_if,
    parse_cphaprob_ia_list,
)

pytestmark = pytest.mark.configuration


# --- A4: cphaprob -a if ----------------------------------------------------

class TestLinkHealthShapes:

    def test_status_column_table(self):
        out = (
            "Interface Name:      Status:\n"
            "\n"
            "eth1                 UP\n"
            "eth2                 UP\n"
            "Sync                 UP\n"
            "\n"
            "Virtual cluster interfaces: 2\n"
        )
        parsed = parse_cphaprob_a_if(out)
        assert parsed["observed"] is True
        assert parsed["any_down"] is False
        assert parsed["interface_count"] == 3

    def test_annotated_interface_rows(self):
        """The state token is not reliably the second column."""
        out = (
            "Required interfaces: 3\n"
            "Required secured interfaces: 1\n"
            "\n"
            "eth1 (Sync)          UP\n"
            "bond1.100 (non sync) UP\n"
        )
        parsed = parse_cphaprob_a_if(out)
        assert parsed["observed"] is True
        assert parsed["interface_count"] == 2

    def test_a_down_interface_is_detected(self):
        out = "Interface Name:  Status:\n\neth1   UP\neth2   DOWN\n"
        assert parse_cphaprob_a_if(out)["any_down"] is True

    def test_non_monitored_is_neither_up_nor_down(self):
        """Counted as a row, never as a failure -- and never as health."""
        out = "Interface Name:  Status:\n\nMgmt   Non-Monitored\neth1   UP\n"
        parsed = parse_cphaprob_a_if(out)
        assert parsed["any_down"] is False
        assert parsed["interface_count"] == 2

    def test_legacy_second_column_shape_still_parses(self):
        assert parse_cphaprob_a_if("eth0   UP\neth1   DOWN\n")["any_down"] is True

    def test_summary_lines_are_not_counted_as_interfaces(self):
        out = (
            "Required interfaces: 3\n"
            "Virtual cluster interfaces: 2\n"
            "eth1   UP\n"
        )
        assert parse_cphaprob_a_if(out)["interface_count"] == 1

    def test_unrecognized_output_fails_closed(self):
        for out in ("", "   ", "cphaprob: command not found", "no interfaces here"):
            parsed = parse_cphaprob_a_if(out)
            assert parsed == {"observed": False, "any_down": None, "interface_count": None}


# --- A5: cphaprob -ia list -------------------------------------------------

class TestPnoteShapes:

    def test_column_table_form(self):
        out = (
            "Device Name:            Registration number:  Timeout:  "
            "Current state:  Time since last report:\n"
            "Interface Active Check  0                     none      OK"
            "              1190.4 sec\n"
            "Synchronization         1                     none      OK"
            "              1190.4 sec\n"
        )
        parsed = parse_cphaprob_ia_list(out)
        assert parsed["observed"] is True
        assert parsed["device_count"] == 2
        assert parsed["any_problem"] is False

    def test_column_table_detects_a_problem(self):
        out = (
            "Device Name:            Registration number:  Timeout:  "
            "Current state:  Time since last report:\n"
            "Interface Active Check  0                     none      problem"
            "         1190.4 sec\n"
        )
        assert parse_cphaprob_ia_list(out)["any_problem"] is True

    def test_legacy_block_form_still_parses(self):
        out = (
            "Device Name: Interface Active Check\n"
            "Current state: OK\n"
            "\n"
            "Device Name: Synchronization\n"
            "Current state: OK\n"
        )
        parsed = parse_cphaprob_ia_list(out)
        assert parsed["device_count"] == 2
        assert parsed["any_problem"] is False

    def test_unrecognized_output_fails_closed(self):
        for out in ("", "nothing recognizable", "Device Name: only a header"):
            parsed = parse_cphaprob_ia_list(out)
            assert parsed["any_problem"] is None, out
            assert parsed["observed"] is False, out


# --- A8: failover history --------------------------------------------------

class TestFailoverCountShapes:

    @pytest.mark.parametrize("line", [
        "Failover counter: 26",
        "Failover count: 26",
        "Cluster failover count: 26",
        "Number of failovers: 26",
        "Failover counter = 26",
        "Failover counter: 26 (since last reboot)",
    ])
    def test_known_count_wordings(self, line):
        assert parse_cp_failover_history(line)["count"] == 26

    def test_count_with_surrounding_history_block(self):
        out = (
            "Failover counter: 26\n"
            "Last failover event: 3 hours ago\n"
            "Reason: cpstop\n"
        )
        parsed = parse_cp_failover_history(out)
        assert parsed["observed"] is True
        assert parsed["count"] == 26
        assert parsed["last_reason_class"] == "manual_operator_action"

    def test_unrecognized_wording_yields_no_count(self):
        """Fail closed: an unknown wording must not become a guessed number."""
        parsed = parse_cp_failover_history("Some other statistic: 26\nReason: cpstop\n")
        assert parsed["count"] is None

    def test_no_history_at_all_is_unobserved(self):
        parsed = parse_cp_failover_history("")
        assert parsed == {"observed": False, "count": None,
                          "last_reason_class": None, "last_event_time": None}
