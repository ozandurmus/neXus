"""Every migration that writes to an audited table must set the audit context.

The audit trigger (`fn_audit_capture`) raises `audit_context_missing` when
`app.actor_fingerprint` / `app.action_id` are unset in the writing
transaction. A migration that seeds such a table without setting them fails
at startup and crash-loops the service. Observed twice on the live cluster
(V15, then V16), both times only after a full image build and rollout, so
this test buys back that whole round trip.
"""
from __future__ import annotations

import re
from pathlib import Path

MIGRATIONS = Path(__file__).resolve().parents[1] / "ui2/service/src/main/resources/db/migration"

#: Tables whose writes the audit trigger guards. A table is added here when
#: its migration attaches `fn_audit_capture` (or an equivalent trigger).
AUDITED_TABLES = ("gate_registry", "role_bindings", "local_credentials", "credential_references")

#: Migrations exempted with their reason. V10 backfills two columns with
#: `WHERE created_by_actor_fingerprint IS NULL`; at V10's point in the
#: sequence `local_credentials` is still empty (first-boot seeding runs at
#: application startup, after migrations), so the statement matches zero rows
#: and the trigger never fires. That is an accident of row count, not a
#: design, and the exemption is written down rather than silently allowed.
EXEMPT = {"V10__local_identity_administration.sql": "zero-row backfill before any identity exists"}

_SET_CONTEXT = re.compile(r"set_config\(\s*'app\.actor_fingerprint'", re.IGNORECASE)
_SET_ACTION = re.compile(r"set_config\(\s*'app\.action_id'", re.IGNORECASE)


def _guarded_write_position(table: str, sql: str) -> int | None:
    """Position of the first write to `table` that the audit trigger can see,
    or None when there is no such write. A write placed *before* the same
    file's own `CREATE TRIGGER ... ON <table>` runs untriggered and is fine
    (V10 backfills a column, then attaches the trigger)."""
    write = re.search(rf"\b(INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+{table}\b", sql, re.IGNORECASE)
    if write is None:
        return None
    trigger = re.search(rf"CREATE\s+(?:OR\s+REPLACE\s+)?TRIGGER\b[^;]*?\bON\s+{table}\b", sql,
                        re.IGNORECASE | re.DOTALL)
    if trigger is not None and write.start() < trigger.start():
        return None
    return write.start()


def test_every_migration_writing_an_audited_table_sets_the_audit_context():
    offenders = []
    for path in sorted(MIGRATIONS.glob("V*.sql")):
        if path.name in EXEMPT:
            continue
        sql = path.read_text(encoding="utf-8")
        # A migration that only creates the table or its trigger does not write rows.
        writes = [t for t in AUDITED_TABLES if _guarded_write_position(t, sql) is not None]
        if not writes:
            continue
        if not (_SET_CONTEXT.search(sql) and _SET_ACTION.search(sql)):
            offenders.append(f"{path.name} writes {', '.join(writes)} without setting the audit context")
    assert not offenders, "; ".join(offenders)
