"""aiview leak audit: routes.json follows the authorization golden; the SQL export is read-only and hash-only."""
import importlib.util
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIR = ROOT / "tools" / "security" / "aiview_leak_audit"
MIGRATIONS = ROOT / "ui2" / "service" / "src" / "main" / "resources" / "db" / "migration"

# (table, column) pairs that export_reference.sql may name although no migration file creates them as plain DDL
# (a table made by code or by a dynamic EXECUTE, or a column renamed outside a migration). Empty on purpose: every
# entry needs a one-line justification here, because the information_schema guard in the SQL skips a missing column
# silently and the audit would then report a clean zero for it.
SQL_SOURCE_ALLOW_LIST: set[tuple[str, str]] = set()


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


# ---- drift guard: every (table, column) the reference SQL names must exist in a migration ----


def _sql_sources():
    """(kind, category, table, column, shape) for every VALUES row of the plain(...) and json(...) source blocks."""
    sql = (DIR / "export_reference.sql").read_text(encoding="utf-8")
    code = "\n".join(re.sub(r"--.*$", "", line) for line in sql.splitlines())
    blocks = {}
    for kind in ("plain", "json"):
        m = re.search(rf"\b{kind}\(([a-z_, ]+)\) AS \(VALUES(.*?)^\)", code, flags=re.M | re.S)
        assert m, f"{kind}(...) block not found in export_reference.sql"
        blocks[kind] = m.group(2)
    rows = []
    for m in re.finditer(r"\('([A-Z_]+)',\s*'([a-z_0-9]+)',\s*'([a-z_0-9]+)'\)", blocks["plain"]):
        rows.append(("plain", m.group(1), m.group(2), m.group(3), None))
    for m in re.finditer(r"\('([A-Z_]+)',\s*'([a-z_0-9]+)',\s*'([a-z_0-9]+)',\s*'([a-z_]+)'\)", blocks["json"]):
        rows.append(("json", m.group(1), m.group(2), m.group(3), m.group(4)))
    return rows


def _split_top_level(body):
    parts, depth, cur = [], 0, ""
    for ch in body:
        depth += (ch == "(") - (ch == ")")
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    return parts


def _migration_columns():
    """table -> {column: declared type}, replaying CREATE TABLE, ADD / RENAME / DROP COLUMN, RENAME TO and DROP TABLE
    over the migration files in version order. Text search only; nothing is executed."""
    tables = {}
    files = sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda f: int(re.match(r"V(\d+)__", f.name).group(1)))
    assert len(files) > 200, "migration directory not found"
    ident = r'(?:public\.)?"?([a-z_0-9]+)"?'
    for f in files:
        text = re.sub(r"--[^\n]*", "", f.read_text(encoding="utf-8"))
        for m in re.finditer(rf"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?{ident}\s*\(", text, flags=re.I):
            depth, i = 1, m.end()
            while depth and i < len(text):
                depth += (text[i] == "(") - (text[i] == ")")
                i += 1
            cols = tables.setdefault(m.group(1), {})
            for part in _split_top_level(text[m.end():i - 1]):
                words = part.split()
                if words and words[0].upper() not in ("PRIMARY", "UNIQUE", "CONSTRAINT", "FOREIGN", "CHECK", "EXCLUDE", "LIKE"):
                    cols[words[0].strip('"')] = words[1] if len(words) > 1 else ""
        for m in re.finditer(rf"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?{ident}\s+([^;]*);", text, flags=re.I | re.S):
            table, body = m.group(1), m.group(2)
            cols = tables.setdefault(table, {})
            for a in re.finditer(r"ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?\"?([a-z_0-9]+)\"?\s+([A-Za-z_\[\]]+)", body, flags=re.I):
                cols[a.group(1)] = a.group(2)
            for a in re.finditer(r"RENAME\s+COLUMN\s+\"?([a-z_0-9]+)\"?\s+TO\s+\"?([a-z_0-9]+)\"?", body, flags=re.I):
                if a.group(1) in cols:
                    cols[a.group(2)] = cols.pop(a.group(1))
            for a in re.finditer(r"DROP\s+COLUMN\s+(?:IF\s+EXISTS\s+)?\"?([a-z_0-9]+)\"?", body, flags=re.I):
                cols.pop(a.group(1), None)
            renamed = re.match(r"RENAME\s+TO\s+\"?([a-z_0-9]+)\"?\s*$", body.strip(), flags=re.I)
            if renamed:
                tables[renamed.group(1)] = tables.pop(table)
        for m in re.finditer(rf"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?{ident}", text, flags=re.I):
            tables.pop(m.group(1), None)
    return tables


def test_reference_sql_sources_exist_in_the_migrations():
    rows = _sql_sources()
    assert len(rows) > 60, "the source blocks were not parsed"
    tables = _migration_columns()
    text_types = ("text", "varchar", "character", "citext")
    missing, wrong_type = [], []
    for kind, _cat, table, column, shape in rows:
        if (table, column) in SQL_SOURCE_ALLOW_LIST:
            continue
        declared = tables.get(table, {}).get(column)
        if declared is None:
            missing.append(f"{table}.{column}")
        elif shape == "host_port_array":
            if not declared.endswith("[]"):
                wrong_type.append(f"{table}.{column} is {declared}, not an array")
        elif kind == "json":
            if not declared.lower().startswith(text_types + ("jsonb", "json")):
                wrong_type.append(f"{table}.{column} is {declared}, not text/json")
        elif not declared.lower().startswith(text_types):
            wrong_type.append(f"{table}.{column} is {declared}, not text")
    assert not missing, f"export_reference.sql names columns no migration creates (the audit would skip them silently): {missing}"
    assert not wrong_type, wrong_type
    # No source is listed twice for one category (a duplicated row hides a typo'd twin).
    keys = [(r[1], r[2], r[3], r[4]) for r in rows]
    assert len(keys) == len(set(keys))


def test_reference_sql_categories_are_the_ones_the_probe_knows():
    core = (DIR / "core.mjs").read_text(encoding="utf-8")
    known = set(re.findall(r"'([A-Z_0-9]+)'", re.search(r"export const CATEGORIES = \[(.*?)\];", core, flags=re.S).group(1)))
    assert len(known) == 7
    used = {r[1] for r in _sql_sources()}
    assert used <= known, f"categories the probe does not know: {sorted(used - known)}"
    # the SQL's own derived categories are known too
    sql = (DIR / "export_reference.sql").read_text(encoding="utf-8")
    code = "\n".join(re.sub(r"--.*$", "", line) for line in sql.splitlines())
    assert {"IPV4", "NAME_WORD"} <= known and all(f"'{c}'" in code for c in ("IPV4", "NAME_WORD"))
