"""No two migrations may share a version number.

Flyway refuses to start when it finds two files with the same version, and the
service crash-loops on every pod. It happened on 2026-09-14: two movements
running in parallel each took V17, the merges did not conflict because the
file names differ, and the defect only appeared at rollout. This test makes a
parallel-movement collision fail in the build instead.
"""
from __future__ import annotations

import re
from collections import Counter
from pathlib import Path

MIGRATIONS = Path(__file__).resolve().parents[1] / "ui2/service/src/main/resources/db/migration"

_VERSION = re.compile(r"^V(\d+)__")


def test_no_two_migrations_share_a_version():
    versions = Counter()
    by_version: dict[str, list[str]] = {}
    for path in sorted(MIGRATIONS.glob("V*.sql")):
        match = _VERSION.match(path.name)
        assert match, f"{path.name} does not follow the V<version>__<description>.sql shape"
        version = match.group(1)
        versions[version] += 1
        by_version.setdefault(version, []).append(path.name)
    duplicates = {v: names for v, names in by_version.items() if len(names) > 1}
    assert not duplicates, f"two migrations share a version: {duplicates}"
