"""Retired parity gate — PO decision 2026-10-04 (PR0).

Java ActionClass, ActionRegistry and the command gate registry are authoritative.
No live tool consumes utils/action_taxonomy.py; that implementation stays legacy
pending PR2. The two Python/Java comparison tests are replaced by this note.
Java coverage remains in GateChainInterceptorRouteResolutionTest,
Ui2ArchitectureTest and worker/service/job-engine failover tests. See tests/README.md.
"""
