"""Pin default discovery to the reviewed inventory and its explicit PR0 splits."""
import ast
import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_default_discovery_matches_live_inventory(pytestconfig):
    with (ROOT / "docs/repo_inventory.csv").open() as source:
        live = {r["path"] for r in csv.DictReader(source)
                if r["classification"] == "LIVE_TOOLING" and r["path"].startswith("tests/")}
    live.remove("tests/test_action_taxonomy_java_parity.py")
    live.update({"tests/test_gov_po_3_ci_privacy_gate_baseline.py",
                 "tests/test_live_project_governance.py", "tests/test_live_tooling_discovery.py"})
    live = {"tools/" + path for path in live}
    assert pytestconfig.getini("testpaths") == ["tools/tests"]
    assert {p.relative_to(ROOT).as_posix() for p in (ROOT / "tools/tests").glob("test_*.py")} == live
    assert all((ROOT / path).is_file() for path in live)
    assert "history" in pytestconfig.getini("norecursedirs")


def test_live_sources_do_not_import_legacy_product(pytestconfig):
    # Traverse local Python imports without loading the retired product.
    pending = list((ROOT / "tools/tests").glob("*.py"))
    pending.append(ROOT / "tools/tests/conftest.py")
    with (ROOT / "docs/repo_inventory.csv").open() as source:
        pending.extend(next((ROOT / "tools").glob("*/" + Path(row["path"]).name)) for row in csv.DictReader(source)
                       if row["classification"] == "LIVE_TOOLING"
                       and row["python_file"] == "true"
                       and row["path"] not in {"utils/action_taxonomy.py", "utils/__init__.py",
                                           "tests/test_action_taxonomy_java_parity.py"})
    visited = set()
    legacy = {"application", "checkpoint", "panorama", "configuration", "console",
              "replay", "signal_intake", "main", "config", "history", "utils"}
    live_utils = {"tools.delivery.project_plan", "tools.privacy.repository_privacy"}
    while pending:
        path = pending.pop()
        if path in visited:
            continue
        visited.add(path)
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
                if node.module and node.module.startswith("tools"):
                    names += [node.module + "." + alias.name for alias in node.names]
            else:
                continue
            for name in names:
                assert name.split(".")[0] not in legacy, (path.relative_to(ROOT), name)
                assert not name.startswith("utils.") or name in live_utils, (path.relative_to(ROOT), name)
                if name in live_utils:
                    rel = Path(*name.split("."))
                    assert ((ROOT / rel.with_suffix(".py")).is_file()
                            or (ROOT / rel / "__init__.py").is_file()), name
                rel = Path(*name.split("."))
                for local in (ROOT / rel.with_suffix(".py"), ROOT / rel / "__init__.py",
                              path.parent / rel.with_suffix(".py")):
                    if local.is_file():
                        pending.append(local)
