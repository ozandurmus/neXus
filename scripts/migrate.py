#!/usr/bin/env python3
"""CLI wrapper around utils.db_migrations -- the DEPLOY.1 reviewed migration path.

Usage::

    py scripts/migrate.py schema --dsn postgresql://...
    py scripts/migrate.py roles  --dsn postgresql://...   # apply DML-only role grants

``--dsn`` falls back to the ``SECURITYEXPERT_MIGRATIONS_POSTGRES_DSN``
environment variable if omitted. This script is the explicit, operator-run
step the invariant in project/backlog.json id deploy1_database_migrations_and_roles
calls for -- migrations are never applied implicitly by application startup.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from utils.db_migrations import MIGRATIONS_DIR, MigrationError, apply_migrations, apply_role_grants

ROLES_DIR = MIGRATIONS_DIR / "roles"
ENV_DSN = "SECURITYEXPERT_MIGRATIONS_POSTGRES_DSN"


def _resolve_dsn(args_dsn: str | None) -> str:
    dsn = args_dsn or os.environ.get(ENV_DSN)
    if not dsn:
        raise SystemExit(f"error: pass --dsn or set {ENV_DSN}")
    return dsn


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["schema", "roles"])
    parser.add_argument("--dsn", default=None)
    args = parser.parse_args(argv)

    dsn = _resolve_dsn(args.dsn)

    try:
        if args.command == "schema":
            applied = apply_migrations(dsn)
            if applied:
                print(f"applied {len(applied)} migration(s): {', '.join(applied)}")
            else:
                print("schema already current, nothing applied")
        else:
            role_files = sorted(ROLES_DIR.glob("*.sql"))
            if not role_files:
                raise SystemExit(f"error: no role grant files found under {ROLES_DIR}")
            for path in role_files:
                apply_role_grants(dsn, path)
                print(f"applied role grants: {path.name}")
    except MigrationError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
