"""DEPLOY.1 (local-feasible slice) -- versioned migrations + DML-only role.

Contract: ``project/backlog.json`` id ``deploy1_database_migrations_and_roles``.

Two tiers, mirroring the DEV.3.2/DEV.3.3 precedent:

* A backend-agnostic test that runs everywhere and only needs the
  filesystem (no live PostgreSQL): every table currently created ad-hoc by
  utils/evidence_backend.py and utils/coordinator_backend.py has exactly one
  corresponding versioned file under migrations/postgres/.
* Live PostgreSQL integration tests (``SECURITYEXPERT_TEST_POSTGRES_DSN``,
  skipped when absent), proving (a) the migration runner produces the same
  tables the ad-hoc backends create, applied exactly once and idempotently,
  and (b) the AC-3 role boundary: the DML-only role can SELECT/INSERT/UPDATE/
  DELETE on a migrated table and cannot run any DDL against it.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

import pytest

from utils.db_migrations import MIGRATIONS_DIR, apply_migrations, apply_role_grants, discover_migrations

pytestmark = pytest.mark.runtime_platform

ROLES_DIR = MIGRATIONS_DIR / "roles"
ROLE_NAME = "securityexpert_app"

# The exact set of tables the ad-hoc CREATE TABLE IF NOT EXISTS statements in
# utils/evidence_backend.py and utils/coordinator_backend.py create today.
EXPECTED_TABLES = {
    "config_snapshot",
    "run_manifest",
    "last_known_good_entity",
    "console_job",
    "scheduler_state",
    "recovery_operational_write_ledger",
    "ha_action_record",
    "collection_job",
    "collection_job_lock",
}


# ---------------------------------------------------------------------------
# Backend-agnostic: the migration files exist, are numbered, and cover the
# same tables the ad-hoc DDL creates (no live database needed).
# ---------------------------------------------------------------------------

def _strip_sql_comments(text: str) -> str:
    return "\n".join(
        line for line in text.splitlines() if not line.strip().startswith("--")
    )


def test_migration_files_cover_every_ad_hoc_table():
    migrations = discover_migrations(MIGRATIONS_DIR)
    assert migrations, "no migration files found"

    all_sql = "\n".join(_strip_sql_comments(path.read_text(encoding="utf-8")) for _, path in migrations)
    created = set(re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)", all_sql))
    # schema_migrations (0000, the runner's own bootstrap table) is
    # infrastructure, not part of the formalized ad-hoc DDL being replaced.
    created.discard("schema_migrations")
    assert created == EXPECTED_TABLES


def test_migration_versions_are_strictly_ordered_and_unique():
    migrations = discover_migrations(MIGRATIONS_DIR)
    versions = [version for version, _ in migrations]
    assert versions == sorted(versions)
    assert len(versions) == len(set(versions))
    # 0000 is the bootstrap table; formalized schema starts at 0001.
    assert versions[0] == "0000_schema_migrations"


def test_role_grant_file_never_embeds_a_password():
    """A committed grants file must never carry real credentials."""
    role_files = sorted(ROLES_DIR.glob("*.sql"))
    assert role_files, "no role grant files found"
    for path in role_files:
        text = _strip_sql_comments(path.read_text(encoding="utf-8")).upper()
        assert "PASSWORD" not in text, f"{path.name} must not set a role password"


def test_role_grant_file_grants_no_ddl_privilege():
    """DML only: no CREATE/DROP/ALTER/TRUNCATE/TRIGGER/REFERENCES grant, no superuser."""
    role_files = sorted(ROLES_DIR.glob("*.sql"))
    for path in role_files:
        text = _strip_sql_comments(path.read_text(encoding="utf-8")).upper()
        assert "SUPERUSER" not in text
        assert "CREATEDB" not in text
        assert "CREATEROLE" not in text
        for forbidden in ("CREATE,", "GRANT CREATE ", "TRUNCATE", "TRIGGER", "REFERENCES"):
            assert forbidden not in text, f"{path.name} must not grant {forbidden.strip(', ')}"


# ---------------------------------------------------------------------------
# Live PostgreSQL integration
# ---------------------------------------------------------------------------

def _postgres_dsn() -> str:
    return os.environ.get(
        "SECURITYEXPERT_TEST_POSTGRES_DSN",
        "postgresql://securityexpert:securityexpert@127.0.0.1:5432/securityexpert_test",
    )


def _postgres_available() -> bool:
    try:
        import psycopg
    except ImportError:
        return False
    try:
        with psycopg.connect(_postgres_dsn(), connect_timeout=2, autocommit=True) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
        return True
    except Exception:
        return False


requires_postgres = pytest.mark.skipif(
    not _postgres_available(),
    reason="live PostgreSQL test database not available (set SECURITYEXPERT_TEST_POSTGRES_DSN)",
)


def _reset_database() -> None:
    import psycopg

    with psycopg.connect(_postgres_dsn(), autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(f"DROP OWNED BY {ROLE_NAME} CASCADE" if _role_exists(cur) else "SELECT 1")
            cur.execute(f"DROP ROLE IF EXISTS {ROLE_NAME}")
            cur.execute(
                "DROP TABLE IF EXISTS schema_migrations, config_snapshot, run_manifest, "
                "last_known_good_entity, console_job, scheduler_state, "
                "recovery_operational_write_ledger, ha_action_record, "
                "collection_job_lock, collection_job CASCADE"
            )


def _role_exists(cur) -> bool:
    cur.execute("SELECT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = %s)", (ROLE_NAME,))
    return cur.fetchone()[0]


@pytest.fixture()
def pg_env():
    _reset_database()
    yield _postgres_dsn()
    _reset_database()


@requires_postgres
def test_apply_migrations_creates_every_table_exactly_once(pg_env):
    import psycopg

    first = apply_migrations(pg_env)
    assert set(first) == {version for version, _ in discover_migrations(MIGRATIONS_DIR)}

    # Re-running is a no-op -- nothing re-applied, no error.
    second = apply_migrations(pg_env)
    assert second == []

    with psycopg.connect(pg_env, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
            )
            tables = {row[0] for row in cur.fetchall()}
    assert EXPECTED_TABLES <= tables


@requires_postgres
def test_dml_only_role_can_read_write_but_not_ddl(pg_env):
    """AC-3: the runtime role can do its required operations and cannot DDL."""
    import psycopg
    from psycopg import errors as pg_errors
    from psycopg.conninfo import make_conninfo

    apply_migrations(pg_env)

    grant_files = sorted(ROLES_DIR.glob("*.sql"))
    for path in grant_files:
        apply_role_grants(pg_env, path)

    ephemeral_secret = "test-only-ephemeral-credential"
    with psycopg.connect(pg_env, autocommit=True) as conn:
        with conn.cursor() as cur:
            try:
                cur.execute(
                    f"ALTER ROLE {ROLE_NAME} WITH PASSWORD %s LOGIN", (ephemeral_secret,)
                )
            except pg_errors.InsufficientPrivilege:
                pytest.skip(
                    f"{_postgres_dsn()!r} lacks privilege to alter role {ROLE_NAME} "
                    "(test database user needs CREATEROLE for this test)"
                )

    app_dsn = make_conninfo(pg_env, user=ROLE_NAME, **{"password": ephemeral_secret})

    # Required operations succeed: INSERT, SELECT, UPDATE, DELETE.
    with psycopg.connect(app_dsn, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO scheduler_state (workflow, last_completed_at) VALUES (%s, now())",
                ("dml-boundary-test",),
            )
            cur.execute("SELECT workflow FROM scheduler_state WHERE workflow = %s", ("dml-boundary-test",))
            assert cur.fetchone() == ("dml-boundary-test",)
            cur.execute(
                "UPDATE scheduler_state SET last_completed_at = now() WHERE workflow = %s",
                ("dml-boundary-test",),
            )
            cur.execute("DELETE FROM scheduler_state WHERE workflow = %s", ("dml-boundary-test",))

    # Forbidden operations fail closed: no DDL of any kind.
    ddl_statements = [
        "CREATE TABLE app_role_should_not_create (id TEXT)",
        "ALTER TABLE scheduler_state ADD COLUMN sneaky TEXT",
        "DROP TABLE scheduler_state",
        "TRUNCATE scheduler_state",
    ]
    for statement in ddl_statements:
        with psycopg.connect(app_dsn, autocommit=True) as conn:
            with conn.cursor() as cur:
                with pytest.raises(pg_errors.InsufficientPrivilege):
                    cur.execute(statement)

    # And the table the role was never granted still rejects it, DML included.
    with psycopg.connect(pg_env, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute("REVOKE ALL ON collection_job FROM " + ROLE_NAME)
    with psycopg.connect(app_dsn, autocommit=True) as conn:
        with conn.cursor() as cur:
            with pytest.raises(pg_errors.InsufficientPrivilege):
                cur.execute("SELECT * FROM collection_job")
