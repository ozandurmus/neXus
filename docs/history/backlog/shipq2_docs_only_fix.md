# shipq2: a docs-only fast-path ship counts as shipped

status: done · target: Closed 2026-11-13: done

2026-11-13: ops-scripts/shipq2.sh (backup shipq2.sh.bak-1113) now treats a ship log with fast_path docs_only, deployed false and a merged commit as SHIPPED instead of FAILED (no e2e runs for docs-only ships).
