# ASA readiness: record the PO approval of four reads

status: in_progress · target: PR 857 open; PO approved 11-14

2026-11-14: PR 857 builds ASA Phase 1 readiness, switched off, reads DRAFTED (V212). PO approved 11-14 the four reads for permanent readiness use; a migration must move the gate rows from DRAFTED to approved with the decision reference before the switch is turned on.

2026-11-14: in ship batch 1 (V212).

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Built: 'Sign off the four Cisco ASA readiness reads (PO approval 2026-11-14)', migration now V222__asa_failover_readiness_signoff.sql ('Renumber the migration V215 -> V222 for the ship order'). Not closed: ship pending, V222 live dry run pending; UI2_ASA_FAILOVER_READINESS stays off by default (open Q3).

2026-11-15 (PO): ASA sessions (inventory, readiness, backup/configuration, failover reads) no longer send terminal pager 0 (Cisco CPU warning); the shell answers '<--- More --->' itself, bounded at 100000 pages; gate asa_terminal_pager_0 BLOCKED by V233 (removed per PO 2026-11-15). Review of the inventory session's pager use closed.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): CLOSED. V222__asa_failover_readiness_signoff.sql is applied (schema V242 live). The readiness switch stays off by default (open Q3) and is a separate decision; the terminal pager 0 command was removed by commit 'ASA sessions answer the pager instead of sending terminal pager 0' and V233. AUTOMATED_VALIDATED.
