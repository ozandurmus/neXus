"""Responsibility-owned CLI mode groups.

``maintenance`` — privacy / storage / render / diagnostic and scheduler modes.
``recovery``    — restore-readiness / recovery store / validate / collect / attest.
``checkpoint``  — the full staged integration checkpoint, ``--only`` partials and
                 the Check Point configuration modes.

No workflow module imports another workflow module (AC-3).
"""
