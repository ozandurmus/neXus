#!/usr/bin/env python3
"""Derive the GET routes the aiview persona (role:replay_viewer) may call from the route authorization golden.

The golden file is the reviewed decision table (every route x persona); a route is kept when the replay-viewer row
for GET is PERMIT.  Routes that are not data surfaces (static assets, health, SAML metadata, error pages) are
dropped.  Run `python3 tools/security/aiview_leak_audit/gen_routes.py` from the repository root to refresh
routes.json; tools/tests/test_aiview_leak_audit_routes.py fails when routes.json is out of date.
"""
import json
import sys
from pathlib import Path

GOLDEN = Path("ui2/service/src/test/resources/authorization/route-authorization-decision-table.golden.txt")
OUT = Path(__file__).with_name("routes.json")
EXCLUDED_EXACT = {"/", "/error", "/healthz", "/favicon.ico", "/index.html", "/unknown-page"}
EXCLUDED_PREFIX = ("/assets/", "/saml2/")


def derive(golden_text: str) -> list[str]:
    routes = set()
    for line in golden_text.splitlines():
        parts = line.split(" | ")
        if len(parts) < 3 or parts[0] != "role-replay_viewer":
            continue
        method, _, path = parts[1].partition(" ")
        if method != "GET" or not parts[2].startswith("PERMIT"):
            continue
        if path in EXCLUDED_EXACT or path.startswith(EXCLUDED_PREFIX):
            continue
        routes.add(path)
    return sorted(routes)


def render(routes: list[str]) -> str:
    return json.dumps({"source": str(GOLDEN), "placeholder": "synthetic-ref", "routes": routes}, indent=1) + "\n"


if __name__ == "__main__":
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    OUT.write_text(render(derive((root / GOLDEN).read_text(encoding="utf-8"))), encoding="utf-8")
