"""PCP.8 — CLI wiring for ``--runbook-execute``/``--runbook-id``.

Covers the parser surface, its fail-closed validation against the real
``configuration.runbook_catalog.RUNBOOK_CATALOG``, its mutual exclusion with
every other mode, and that it is not console-submittable (no Browser ->
arbitrary shell, per AGENTS.md "Architectural invariants"). The runbook
catalog's own safety boundary (validator, redaction) is proven in
``tests/test_pcp8_diagnostic_runbooks.py``.
"""
from __future__ import annotations

import pytest

from application.cli import build_parser, validate_modes

pytestmark = pytest.mark.compliance


def _parse(argv):
    parser = build_parser()
    args = parser.parse_args(argv)
    validate_modes(args, parser)
    return args


def _parse_error(argv):
    parser = build_parser()
    args = parser.parse_args(argv)
    with pytest.raises(SystemExit):
        validate_modes(args, parser)


class TestCliSurface:
    def test_mode_exists(self):
        args = _parse(["--runbook-execute", "--runbook-id", "cp_basic_health_check"])
        assert args.runbook_execute is True
        assert args.runbook_id == "cp_basic_health_check"

    def test_absent_by_default(self):
        args = _parse([])
        assert args.runbook_execute is False
        assert args.runbook_id is None

    def test_requires_runbook_id(self):
        _parse_error(["--runbook-execute"])

    def test_runbook_id_requires_runbook_execute(self):
        _parse_error(["--runbook-id", "cp_basic_health_check"])

    def test_unknown_runbook_id_is_refused_before_any_dispatch(self):
        _parse_error(["--runbook-execute", "--runbook-id", "not_a_real_runbook"])

    @pytest.mark.parametrize(
        "extra",
        [
            ["--cp-config-collect"],
            ["--cp-config-probe"],
            ["--compliance-probe"],
            ["--render-only"],
            ["--only", "cp"],
            ["--registry-list"],
            ["--console"],
            ["--scheduler-once"],
            ["--repository-privacy-check"],
            ["--persistent-secret-material-check"],
        ],
    )
    def test_cannot_combine_with_other_modes(self, extra):
        _parse_error(["--runbook-execute", "--runbook-id", "cp_basic_health_check", *extra])

    def test_not_console_submittable_by_construction(self):
        """No console job type ever carries this mode's flag -- the Operator
        Console submits typed intent against console/registry.py's closed job
        vocabulary, which this CLI-only mode is never registered in."""
        from console import registry as console_registry

        assert "runbook_execute" not in console_registry.JOB_REGISTRY
        assert "runbook-execute" not in console_registry.JOB_REGISTRY
