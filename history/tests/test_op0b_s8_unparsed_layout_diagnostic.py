"""SecurityExpert -- OP.0b S8, value-free layout diagnostic.

Three times in this campaign an approved read returned `success` and its
parser matched nothing (`fw stat`, `cphaprob -a if`, `cphaprob -ia list`).
Each cost a real-environment round trip, and the only way forward on offer
was to guess another output shape -- which is how three wrong diagnoses got
made.

`structural_skeleton` closes that loop without moving a device value off the
device: letters become `A`, digits `9`, structural punctuation survives. That
distinguishes a column table from a `key: value` block, which is exactly what
the parser needs, and reconstructs no hostname, address, policy name,
interface name, role or count.

These tests pin both halves: it must be informative enough to identify a
layout, and value-free enough to never become a raw-output channel.
"""
from __future__ import annotations

import pytest

import checkpoint.preflight_collector as pc
from checkpoint.cp_preflight_extraction import structural_skeleton

pytestmark = pytest.mark.configuration


class TestSkeletonIsValueFree:

    def test_no_device_value_survives(self):
        out = (
            "HOST      POLICY           DATE\n"
            "localhost ArkTest          3Sep2026 22:30:59\n"
            "gw-fw-01  10.230.4.101     ACTIVE\n"
        )
        skeleton = structural_skeleton(out)
        for secret in ("ArkTest", "localhost", "10.230.4.101", "gw-fw-01",
                       "ACTIVE", "Sep", "2026", "230", "101", "22", "30"):
            assert secret not in skeleton, f"{secret!r} leaked into {skeleton!r}"

    def test_digit_runs_do_not_preserve_magnitude(self):
        """`9` per run, not per digit -- a count or an octet must not be
        recoverable from the length of its placeholder."""
        assert structural_skeleton("count: 7") == structural_skeleton("count: 4211")

    def test_letter_runs_do_not_preserve_word_length(self):
        assert structural_skeleton("ab") == structural_skeleton("abcdefghij")

    def test_output_is_bounded(self):
        huge = "\n".join(f"row{i} value{i}" for i in range(500))
        skeleton = structural_skeleton(huge)
        assert skeleton.count("|") < 10, "line count must stay bounded"
        assert len(skeleton) < 1000

    def test_long_line_is_truncated(self):
        assert len(structural_skeleton("x" * 5000)) < 200


class TestSkeletonIsInformative:

    def test_a_column_table_is_distinguishable_from_a_block(self):
        table = structural_skeleton(
            "Device Name:  Registration number:  Current state:\n"
            "Interface Active Check  0  none  OK  56.9 sec\n"
        )
        block = structural_skeleton(
            "Device Name: Interface Active Check\nCurrent state: OK\n"
        )
        assert table != block

    def test_structural_punctuation_survives(self):
        skeleton = structural_skeleton("Current state: OK (Actual)")
        assert ":" in skeleton and "(" in skeleton and ")" in skeleton

    def test_blank_lines_are_dropped(self):
        assert structural_skeleton("a\n\n\nb") == structural_skeleton("a\nb")


class TestDiagnosticFiresOnlyWhenUseful:

    def _capture(self, monkeypatch):
        # Resolve through the module, not a direct symbol import: an
        # unrelated test reloads this module, which would otherwise leave the
        # imported function bound to a stale module object and the patch on
        # the new one.
        seen: list[str] = []
        monkeypatch.setattr(pc, "warn", seen.append)
        return seen

    def test_silent_when_the_read_parsed(self, monkeypatch):
        seen = self._capture(monkeypatch)
        pc._report_unparsed_layout("A5", {"success": True, "stdout": "x"}, {"observed": True})
        assert seen == []

    def test_silent_when_the_read_failed(self, monkeypatch):
        """A failed read is already reported as failed -- this diagnostic is
        only for the confusing case: it ran, and we understood nothing."""
        seen = self._capture(monkeypatch)
        pc._report_unparsed_layout("A5", {"success": False, "stdout": "x"}, None)
        assert seen == []

    def test_reports_when_a_successful_read_yields_nothing(self, monkeypatch):
        seen = self._capture(monkeypatch)
        pc._report_unparsed_layout(
            "A5", {"success": True, "stdout": "Device Name: Sync\nState: OK"},
            {"observed": False},
        )
        assert len(seen) == 1
        assert "A5" in seen[0] and "observed layout" in seen[0]

    def test_the_reported_line_carries_no_device_value(self, monkeypatch):
        seen = self._capture(monkeypatch)
        pc._report_unparsed_layout(
            "A7", {"success": True, "stdout": "localhost ArkTest 3Sep2026"},
            {"observed": False},
        )
        assert "ArkTest" not in seen[0] and "localhost" not in seen[0]
