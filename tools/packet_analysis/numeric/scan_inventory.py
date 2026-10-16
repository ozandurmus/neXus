#!/usr/bin/env python3
"""Scan the frozen PacketBreaker sources for numeric/formatting constructs whose Java port is a trap.

Read-only over the reference tree. Prints the generated part of ``ui2/packet-analysis/NUMERIC_INVENTORY.md``
(everything below its "## Inventory" heading: summary table plus one row per site) to stdout.

    python3 tools/packet_analysis/numeric/scan_inventory.py /path/to/PacketBreaker/src/packetbreaker

Only the Python AST is used: SQL text inside string literals (DuckDB ``median()``, ``quantile_cont``) is not a
Python construct and is listed separately (string constants matched by a regex), because it runs unchanged through DuckDB JDBC.
"""

from __future__ import annotations

import ast
import re
import sys
from collections import Counter
from pathlib import Path

STATISTICS = {"median", "median_low", "median_high", "quantiles", "mean", "fmean", "pstdev", "stdev",
              "pvariance", "variance", "mode", "multimode", "geometric_mean", "harmonic_mean"}
BUILTINS = {"round", "int", "float", "sorted", "min", "max", "sum", "divmod", "abs", "pow"}
SQL_RE = re.compile(r"\b(median|quantile_cont|quantile_disc|stddev_pop|stddev_samp|avg|round|floor|ceil)\s*\(", re.I)


def snippet(source: str, node: ast.AST) -> str:
    text = ast.get_source_segment(source, node) or ""
    text = " ".join(text.split())
    return (text[:110] + "...") if len(text) > 110 else text


class Scanner(ast.NodeVisitor):
    def __init__(self, rel: str, source: str):
        self.rel = rel
        self.source = source
        self.rows: list[tuple[str, int, str, str]] = []
        self.statistics_names: set[str] = set()
        self.sql: list[tuple[str, int, str, str]] = []

    def add(self, node: ast.AST, construct: str, text: str | None = None) -> None:
        self.rows.append((self.rel, node.lineno, construct, text if text is not None else snippet(self.source, node)))

    def visit_ImportFrom(self, node: ast.ImportFrom) -> None:
        if node.module == "statistics":
            self.statistics_names.update(a.asname or a.name for a in node.names)
        self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> None:
        func = node.func
        kwargs = {k.arg for k in node.keywords if k.arg}
        if isinstance(func, ast.Name):
            name = func.id
            if name in self.statistics_names and name in STATISTICS:
                detail = ""
                if name == "quantiles":
                    kw = {k.arg: snippet(self.source, k.value) for k in node.keywords}
                    detail = f"(n={kw.get('n', '4')}, method={kw.get('method', 'exclusive')})"
                self.add(node, f"statistics.{name}{detail}")
            elif name == "round":
                self.add(node, "round(x)" if len(node.args) == 1 else "round(x, n)")
            elif name in ("int", "float") and node.args:
                arg = node.args[0]
                kind = "str" if isinstance(arg, ast.Constant) and isinstance(arg.value, str) else "expr"
                self.add(node, f"{name}({kind})")
            elif name == "sorted":
                self.add(node, "sorted(" + ",".join(sorted(kwargs)) + ")" if kwargs else "sorted()")
            elif name in ("min", "max"):
                self.add(node, f"{name}(" + ",".join(sorted(kwargs)) + ")" if kwargs else f"{name}()")
            elif name in ("sum", "divmod", "pow"):
                self.add(node, f"{name}()")
            elif name == "Decimal":
                self.add(node, "Decimal()")
            elif name == "str" and node.args:
                self.add(node, "str(x)")
        elif isinstance(func, ast.Attribute):
            owner = func.value
            owner_name = owner.id if isinstance(owner, ast.Name) else None
            if owner_name == "math":
                self.add(node, f"math.{func.attr}")
            elif owner_name == "statistics":
                self.add(node, f"statistics.{func.attr}")
            elif owner_name == "json" and func.attr in ("dumps", "dump"):
                self.add(node, f"json.{func.attr}(" + ",".join(sorted(kwargs)) + ")")
            elif func.attr == "sort":
                self.add(node, ".sort(" + ",".join(sorted(kwargs)) + ")" if kwargs else ".sort()")
            elif func.attr in ("fromtimestamp", "utcfromtimestamp", "strftime", "isoformat"):
                self.add(node, f"datetime.{func.attr}")
            elif func.attr == "format" and isinstance(owner, ast.Constant) and isinstance(owner.value, str):
                specs = re.findall(r"\{[^{}]*?(:[^{}]*)\}", owner.value)
                self.add(node, "str.format" + (" " + " ".join(sorted(set(specs))) if specs else ""))
        self.generic_visit(node)

    def visit_Constant(self, node: ast.Constant) -> None:
        if isinstance(node.value, str):
            for offset, line in enumerate(node.value.splitlines() or [node.value]):
                for m in SQL_RE.finditer(line):
                    self.sql.append((self.rel, node.lineno + offset, f"DuckDB SQL {m.group(1).lower()}()",
                                     " ".join(line.split())[:110]))

    def visit_JoinedStr(self, node: ast.JoinedStr) -> None:
        for value in node.values:
            if isinstance(value, ast.FormattedValue) and value.format_spec is not None:
                spec = "".join(
                    v.value if isinstance(v, ast.Constant) else "{...}" for v in value.format_spec.values
                )
                self.add(value, f"f-string :{spec}", snippet(self.source, value.value) + f" -> :{spec}")
        self.generic_visit(node)

    def visit_BinOp(self, node: ast.BinOp) -> None:
        if isinstance(node.op, ast.Mod):
            if isinstance(node.left, ast.Constant) and isinstance(node.left.value, (str, bytes)):
                self.add(node, "printf %")
            else:
                self.add(node, "a % b (modulo)")
        elif isinstance(node.op, ast.FloorDiv):
            self.add(node, "a // b")
        elif isinstance(node.op, ast.Div):
            # pathlib joins (`directory / "x.pcap"`, `root / f"..."`) are not arithmetic.
            if not any(isinstance(side, ast.JoinedStr) or (isinstance(side, ast.Constant) and isinstance(side.value, str))
                       for side in (node.left, node.right)):
                self.add(node, "a / b (true division)")
        self.generic_visit(node)


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else "../PacketBreaker/src").resolve()
    files = sorted(p for p in root.rglob("*.py") if "__pycache__" not in p.parts and ".egg-info" not in str(p))
    rows: list[tuple[str, int, str, str]] = []
    sql: list[tuple[str, int, str, str]] = []
    for path in files:
        rel = path.relative_to(root).as_posix()
        source = path.read_text(encoding="utf-8")
        scanner = Scanner(rel, source)
        scanner.visit(ast.parse(source, filename=rel))
        rows.extend(scanner.rows)
        sql.extend(scanner.sql)

    counts = Counter(r[2] for r in rows)
    print("| Construct | Sites |")
    print("|---|---:|")
    for construct, n in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"| `{construct}` | {n} |")
    sql_counts = Counter(r[2] for r in sql)
    for construct, n in sorted(sql_counts.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"| `{construct}` (runs in DuckDB, not ported) | {n} |")
    print(f"\nPython files scanned: {len(files)}. Python sites: {len(rows)}. SQL text sites: {len(sql)}.\n")

    true_div = Counter(r[0] for r in rows if r[2] == "a / b (true division)")
    print("### True division sites per file (rows omitted; see note above)\n")
    print("| File | Sites |")
    print("|---|---:|")
    for rel, n in sorted(true_div.items()):
        print(f"| `{rel}` | {n} |")
    print("\n### Site list\n")
    print("| File:line | Construct | Source |")
    print("|---|---|---|")
    for rel, line, construct, text in sorted(r for r in rows if r[2] != "a / b (true division)"):
        print(f"| `{rel}:{line}` | `{construct}` | `{text.replace('|', '\\|').replace('`', "'")}` |")
    print("\n### DuckDB SQL aggregate sites (Python string literals)\n")
    print("| File:line | Construct | Source |")
    print("|---|---|---|")
    for rel, line, construct, text in sorted(sql):
        print(f"| `{rel}:{line}` | `{construct}` | `{text.replace('|', '\\|').replace('`', "'")}` |")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
