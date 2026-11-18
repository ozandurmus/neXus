"""aiview leak audit: routes.json follows the authorization golden; the SQL export is read-only and hash-only."""
import importlib.util
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIR = ROOT / "tools" / "security" / "aiview_leak_audit"


def _gen():
    spec = importlib.util.spec_from_file_location("aiview_leak_audit_gen_routes", DIR / "gen_routes.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_routes_json_is_derived_from_the_golden_decision_table():
    gen = _gen()
    golden = (ROOT / gen.GOLDEN).read_text(encoding="utf-8")
    assert (DIR / "routes.json").read_text(encoding="utf-8") == gen.render(gen.derive(golden)), (
        "routes.json is stale: run python3 tools/security/aiview_leak_audit/gen_routes.py")


def test_routes_are_get_data_routes_only():
    routes = json.loads((DIR / "routes.json").read_text(encoding="utf-8"))["routes"]
    assert len(routes) > 100 and routes == sorted(set(routes))
    assert all(r.startswith("/") and not r.startswith(("/assets/", "/saml2/")) for r in routes)
    assert "/api/v2/overview" in routes and "/healthz" not in routes


def test_sql_export_is_read_only_and_prints_only_hashes_and_source_names():
    sql = (DIR / "export_reference.sql").read_text(encoding="utf-8")
    code = "\n".join(line for line in sql.splitlines() if not line.lstrip().startswith("--"))
    assert "BEGIN READ ONLY;" in code and code.rstrip().endswith("ROLLBACK;")
    assert not re.search(r"\b(INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|GRANT|COPY)\b", code), "write statement"
    outputs = re.findall(r"^SELECT (?:DISTINCT )?(.+)$", code, flags=re.M)
    assert any("sha256(" in o for o in outputs)
    assert any(o.startswith("'#src'") for o in outputs)


def test_readme_documents_the_run_commands():
    text = (DIR / "README.md").read_text(encoding="utf-8")
    for needle in ("export_reference.sql", "probe.mjs --ref", "kubectl -n ui2 delete pod aiview-leak-audit"):
        assert needle in text
