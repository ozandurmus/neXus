"""Reviewed, versioned PostgreSQL schema migrations (DEPLOY.1 slice).

Precedent this mirrors: ``utils/control_plane_store.py``'s SQLite ledger is
schema-versioned by construction (one file, one ``CREATE TABLE`` reviewed at
change time). Before this module, the Postgres backends in
``utils/evidence_backend.py`` and ``utils/coordinator_backend.py`` had no
migration mechanism at all -- each backend ran its own ``CREATE TABLE IF NOT
EXISTS`` tuple inline at construction time (see their own ``_ensure_schema``
helpers, unchanged by this module -- still the active path tonight; see
``project/backlog.json`` id ``deploy1_database_migrations_and_roles`` for why
switching the *startup* path over to this runner is DEPLOY.1-remainder, not
done here).

This module is the reviewed, explicit path: SQL files under
``migrations/postgres/*.sql``, numbered in apply order, tracked in a
``schema_migrations`` table so re-running is a no-op. It reuses the same
transaction-scoped advisory-lock pattern already established in
``utils.evidence_backend._ensure_schema`` (two processes racing schema
creation is exactly the hazard that pattern exists for).

Usage (see ``scripts/migrate.py`` for the CLI wrapper)::

    from utils.db_migrations import apply_migrations
    applied = apply_migrations(dsn)  # -> list of newly-applied version strings
"""
from __future__ import annotations

from pathlib import Path

MIGRATIONS_DIR = Path(__file__).resolve().parent.parent / "migrations" / "postgres"

# Distinct namespace from evidence_backend._SCHEMA_LOCK_KEY (0x5EC0DE33) and
# coordinator_backend's derived lock keys -- this lock only ever guards the
# migration runner's own apply loop.
_MIGRATION_LOCK_KEY = 0x5EC0DE44


class MigrationError(RuntimeError):
    """A migration file failed to apply, or the migrations directory is invalid."""


def _psycopg():
    try:
        import psycopg
    except ImportError as exc:  # pragma: no cover - exercised only without the driver
        raise MigrationError(
            "utils.db_migrations requires the 'psycopg' package (psycopg[binary]>=3.1); "
            "it is not installed."
        ) from exc
    return psycopg


def discover_migrations(migrations_dir: Path = MIGRATIONS_DIR) -> list[tuple[str, Path]]:
    """Every ``*.sql`` file directly under ``migrations_dir``, sorted by filename.

    The filename stem (e.g. ``0001_config_snapshot``) is the migration's
    ``version`` -- what gets recorded in ``schema_migrations``. Does not
    recurse, so ``migrations/postgres/roles/*.sql`` (role grants, applied
    separately and deliberately not tracked as schema versions) is excluded.
    """
    if not migrations_dir.is_dir():
        raise MigrationError(f"migrations directory not found: {migrations_dir}")
    files = sorted(p for p in migrations_dir.glob("*.sql") if p.is_file())
    return [(p.stem, p) for p in files]


def apply_migrations(dsn: str, migrations_dir: Path = MIGRATIONS_DIR) -> list[str]:
    """Apply every not-yet-applied migration file, in order, in one transaction.

    Returns the versions newly applied this call (empty if already current).
    Safe to call repeatedly and from multiple processes concurrently -- the
    advisory lock serializes them, and each file is only ever applied once.
    """
    psycopg = _psycopg()
    migrations = discover_migrations(migrations_dir)

    applied: list[str] = []
    with psycopg.connect(dsn, autocommit=False) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT pg_advisory_xact_lock(%s)", (_MIGRATION_LOCK_KEY,))
            cur.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations ("
                "    version TEXT PRIMARY KEY,"
                "    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()"
                ")"
            )
            cur.execute("SELECT version FROM schema_migrations")
            already_applied = {row[0] for row in cur.fetchall()}

            for version, path in migrations:
                if version in already_applied:
                    continue
                sql_text = path.read_text(encoding="utf-8")
                try:
                    cur.execute(sql_text)
                except Exception as exc:
                    raise MigrationError(f"migration {path.name} failed: {exc}") from exc
                cur.execute(
                    "INSERT INTO schema_migrations (version) VALUES (%s)", (version,)
                )
                applied.append(version)
        conn.commit()
    return applied


def applied_versions(dsn: str) -> set[str]:
    """Versions already recorded in ``schema_migrations`` (empty set if the table doesn't exist yet)."""
    psycopg = _psycopg()
    with psycopg.connect(dsn, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT EXISTS (SELECT FROM information_schema.tables "
                "WHERE table_schema = 'public' AND table_name = 'schema_migrations')"
            )
            (table_exists,) = cur.fetchone()
            if not table_exists:
                return set()
            cur.execute("SELECT version FROM schema_migrations")
            return {row[0] for row in cur.fetchall()}


def apply_role_grants(dsn: str, path: Path) -> None:
    """Apply a role/grant SQL file (e.g. ``migrations/postgres/roles/0001_dml_only_app_role.sql``).

    Deliberately separate from ``apply_migrations``: role/grant statements
    are not schema versions and are not recorded in ``schema_migrations`` --
    they are idempotent by construction (``DO $$ ... IF NOT EXISTS ...``,
    ``REVOKE``/``GRANT`` restated) and safe to re-run whenever privileges
    need reasserting, independent of the schema migration cursor.
    """
    psycopg = _psycopg()
    sql_text = path.read_text(encoding="utf-8")
    with psycopg.connect(dsn, autocommit=True) as conn:
        with conn.cursor() as cur:
            try:
                cur.execute(sql_text)
            except Exception as exc:
                raise MigrationError(f"role grant file {path.name} failed: {exc}") from exc
