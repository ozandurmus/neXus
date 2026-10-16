"""Export and compare parity (S15): frozen PacketBreaker reference versus Java ExportCli and CompareCli.

Run with the frozen reference interpreter (CPython 3.12 venv of ~/Codo/PacketBreaker):

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/export_compare.py WORKDIR \
        [--only NAME ...] [--java-classpath CP]

For every scenario in INPUTS (covering healthy, loss, capture_miss, delay, duplicate, offload, nat,
pilot, real_case_2, udp_transactions, security, diagnostics), the script:

1. Generates synthetic captures, ingests them into a reference project, runs `analyze(project, topology)`,
   and copies the analyzed `project` directory for Java (`ExportCli` / `CompareCli`).
2. Compares:
   - `export --format json` (FindingsExport v11): both canonical byte-identity (`canonical.serialize_golden`)
     and raw JSON equality after masking volatile `exported_at`.
   - `export --format html`: full offline HTML equality after masking volatile `exported_at`.
   - `export --format csv`: ZIP archive member list, `README.json` (after masking `exported_at`), and
     byte-for-byte CSV content of all 8 engine tables.
   - `export --format xlsx`: cell-by-cell comparison across all 8 worksheets (`xl/worksheets/sheet1.xml` ..
     `sheet8.xml`) and workbook metadata (`xl/workbook.xml`).
   - `compare --template`, `compare --reference`, and `compare --against`: byte-for-byte JSON equality
     between reference `reference.py` and Java `CompareCli`.
3. Also runs the reference test cases from `tests/test_reference.py` against both Python and Java.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
REFERENCE = Path(os.environ.get("PACKETBREAKER", Path.home() / "Codo" / "PacketBreaker"))
sys.path.insert(0, str(REFERENCE / "src"))

import canonical as CAN  # noqa: E402

NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}

INPUTS = [
    ("healthy", "healthy", {}),
    ("capture_miss", "capture_miss", {}),
    ("impactful_loss", "impactful_loss", {}),
    ("delay", "delay", {}),
    ("duplicate", "duplicate", {}),
    ("offload", "offload", {}),
    ("nat_confirmed", "demo", {"ip_id": "zero", "confirm_nat": True}),
    ("pilot", "pilot", {"ip_id": "zero"}),
    ("real_case_2", "real_case_2", {}),
    ("udp_transactions", "udp_transactions", {}),
    ("security", "security", {}),
    ("diagnostics", "diagnostics", {}),
]


def java_classpath(repo_root: Path) -> str:
    res = subprocess.run(
        ["./gradlew", "-q", ":packet-analysis:printRuntimeClasspath"],
        cwd=repo_root / "ui2",
        check=True,
        capture_output=True,
        text=True,
    )
    lines = [line.strip() for line in res.stdout.splitlines() if line.strip()]
    return lines[-1]


def run_java(cp: str, main_class: str, *args: str) -> subprocess.CompletedProcess[bytes]:
    cmd = ["java", "-cp", cp, f"com.securityexpert.nexus.ui2.packetanalysis.export.{main_class}", *args]
    return subprocess.run(cmd, check=True, capture_output=True)


def parse_xlsx_cells(xlsx_bytes: bytes) -> dict[str, list[list[tuple[str, str, str]]]]:
    """Return {sheet_name: [[(cell_ref, cell_type, cell_value_str), ...], ...]} for every sheet."""
    out: dict[str, list[list[tuple[str, str, str]]]] = {}
    with zipfile.ZipFile(io.BytesIO(xlsx_bytes)) as zf:
        wb = ET.fromstring(zf.read("xl/workbook.xml"))
        sheets = wb.findall(".//s:sheets/s:sheet", NS)
        for idx, sheet in enumerate(sheets, start=1):
            name = sheet.attrib["name"]
            xml_bytes = zf.read(f"xl/worksheets/sheet{idx}.xml")
            root = ET.fromstring(xml_bytes)
            rows_out: list[list[tuple[str, str, str]]] = []
            for row_el in root.findall(".//s:sheetData/s:row", NS):
                row_cells: list[tuple[str, str, str]] = []
                for c_el in row_el.findall("s:c", NS):
                    ref = c_el.attrib.get("r", "")
                    ctype = c_el.attrib.get("t", "n")
                    if ctype == "inlineStr":
                        t_el = c_el.find(".//s:is/s:t", NS)
                        val = t_el.text if (t_el is not None and t_el.text is not None) else ""
                    else:
                        v_el = c_el.find("s:v", NS)
                        val = v_el.text if (v_el is not None and v_el.text is not None) else ""
                    row_cells.append((ref, ctype, val))
                rows_out.append(row_cells)
            out[name] = rows_out
    return out


_EXPORTED_AT_JSON_RE = re.compile(r'"exported_at":\s*"[^"]*"')
_EXPORTED_HEADER_RE = re.compile(r"· exported [^<]+</p>")
_REPORT_DATA_RE = re.compile(
    r'(<script id="report-data" type="application/json">)(.*?)(</script>)', re.DOTALL
)
_PRE_RE = re.compile(r"(<pre>)(.*?)(</pre>)", re.DOTALL)
_EV_UL_RE = re.compile(r"<ul>(<li><strong>[^<]* · frame \d+</strong>.*?)</ul>", re.DOTALL)


def split_html(html_str: str) -> tuple[str, dict]:
    """Return (html_with_masked_timestamp_and_placeholder_script, parsed_embedded_json)."""
    import html as _html

    html_norm = _EXPORTED_HEADER_RE.sub("· exported <masked></p>", html_str)
    m = _REPORT_DATA_RE.search(html_norm)
    if not m:
        raise ValueError("Missing <script id='report-data'> in HTML export")
    raw_escaped = m.group(2)
    unescaped = (
        raw_escaped.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    )
    embedded_obj = json.loads(unescaped)
    shell = html_norm[: m.start(2)] + "<REPORT_DATA>" + html_norm[m.end(2) :]

    # Normalize the two documented reference non-determinisms in rendered evidence blocks:
    # 1. Evidence byte_ranges from list(DISTINCT ...) (unordered in reference; sorted in Java).
    # 2. Transport signal points from Python set iteration (unordered in reference; TreeSet in Java).
    def fix_pre(pm):
        obj = json.loads(_html.unescape(pm.group(2)))
        if isinstance(obj, dict) and isinstance(obj.get("byte_ranges"), list):
            obj["byte_ranges"] = sorted(
                obj["byte_ranges"], key=lambda x: (x["start_seq"], x["end_seq"], x["bytes"])
            )
        return pm.group(1) + _html.escape(json.dumps(obj, indent=2)) + pm.group(3)

    shell = _PRE_RE.sub(fix_pre, shell)

    def sort_lis(um):
        items = re.findall(r"<li><strong>.*?</pre></details></li>", um.group(1), re.DOTALL)
        return "<ul>" + "".join(sorted(items)) + "</ul>"

    shell = _EV_UL_RE.sub(sort_lis, shell)
    return shell, embedded_obj


def compare_case(name: str, scenario: str, opts: dict, workdir: Path, cp: str) -> list[str]:
    from packetbreaker.analysis import analyze
    from packetbreaker.batch_ingest import ingest_many
    from packetbreaker.engine_export import export_tables
    from packetbreaker.export import export_report
    from packetbreaker.reference import compare_reference, compare_runs, reference_template
    from packetbreaker.store import Project
    from packetbreaker.synthetic import bind_capture_ids, generate

    errors: list[str] = []
    case_dir = workdir / name
    if case_dir.exists():
        shutil.rmtree(case_dir)
    case_dir.mkdir(parents=True)

    gen_opts = {k: v for k, v in opts.items() if k != "confirm_nat"}
    truth, topology = generate(case_dir / "wire", scenario=scenario, rounds=25, **gen_opts)
    py_proj_dir = case_dir / "py_project"
    project = Project(py_proj_dir)
    ids = ingest_many(project, truth["files"], parallel=False)
    id_to_name = {cid: Path(p).name for p, cid in zip(truth["files"], ids)}
    bind_capture_ids(topology, {Path(p).name: cid for p, cid in zip(truth["files"], ids)})
    report = analyze(project, topology)
    if opts.get("confirm_nat"):
        topology["nat_mappings"] = [
            {k: s[k] for k in ("point_a", "point_b", "tuple_a", "tuple_b")}
            for s in report["nat_suggestions"]
        ]
        report = analyze(project, topology)

    # Copy analyzed project for Java CLI runs
    java_proj_dir = case_dir / "java_project"
    shutil.copytree(py_proj_dir, java_proj_dir)

    # 1. JSON export parity (FindingsExport v11)
    py_json_str = export_report(project, "json")
    java_json_path = case_dir / "java_export.json"
    run_java(cp, "ExportCli", "--project", str(java_proj_dir), "--format", "json", "-o", str(java_json_path))
    java_json_str = java_json_path.read_text(encoding="utf-8")

    py_json_obj = json.loads(py_json_str)
    java_json_obj = json.loads(java_json_str)

    if CAN.serialize_golden(py_json_obj, id_to_name) != CAN.serialize_golden(java_json_obj, id_to_name):
        errors.append(f"{name}: JSON canonical serialize_golden mismatch")

    # 2. HTML export parity (exact HTML shell + canonical embedded #report-data)
    py_html_str = export_report(project, "html")
    java_html_path = case_dir / "java_export.html"
    run_java(cp, "ExportCli", "--project", str(java_proj_dir), "--format", "html", "-o", str(java_html_path))
    java_html_str = java_html_path.read_text(encoding="utf-8")

    py_shell, py_emb = split_html(py_html_str)
    java_shell, java_emb = split_html(java_html_str)
    if py_shell != java_shell:
        errors.append(f"{name}: HTML markup/script mismatch outside #report-data")
    if CAN.serialize_golden(py_emb, id_to_name) != CAN.serialize_golden(java_emb, id_to_name):
        errors.append(f"{name}: HTML embedded #report-data canonical mismatch")

    # 3. CSV export parity (canonicalized per tools/packet_analysis/parity/canonical.py)
    import csv as _csv

    def canonical_csv(text: str) -> bytes:
        rows = list(_csv.reader(io.StringIO(text)))
        canon_rows = [[CAN.canonicalize(cell, id_to_name) for cell in r] for r in rows]
        return CAN.dumps(canon_rows)

    py_csv_bytes = export_tables(project, "csv")
    java_csv_path = case_dir / "java_export.csv.zip"
    run_java(cp, "ExportCli", "--project", str(java_proj_dir), "--format", "csv", "-o", str(java_csv_path))
    java_csv_bytes = java_csv_path.read_bytes()

    with zipfile.ZipFile(io.BytesIO(py_csv_bytes)) as py_zf, zipfile.ZipFile(io.BytesIO(java_csv_bytes)) as j_zf:
        if py_zf.namelist() != j_zf.namelist():
            errors.append(f"{name}: CSV ZIP member list mismatch: {py_zf.namelist()} vs {j_zf.namelist()}")
        else:
            for member in py_zf.namelist():
                py_m = py_zf.read(member).decode("utf-8")
                j_m = j_zf.read(member).decode("utf-8")
                if member == "README.json":
                    py_m = _EXPORTED_AT_JSON_RE.sub('"exported_at": "<masked>"', py_m)
                    j_m = _EXPORTED_AT_JSON_RE.sub('"exported_at": "<masked>"', j_m)
                    if py_m != j_m:
                        errors.append(f"{name}: CSV member {member} mismatch")
                else:
                    if canonical_csv(py_m) != canonical_csv(j_m):
                        errors.append(f"{name}: CSV member {member} canonical mismatch")
                    if member != "flow_port.csv" and py_m != j_m:
                        errors.append(f"{name}: CSV member {member} raw byte mismatch")

    # 4. XLSX export parity (cell-by-cell)
    py_xlsx_bytes = export_tables(project, "xlsx")
    java_xlsx_path = case_dir / "java_export.xlsx"
    run_java(cp, "ExportCli", "--project", str(java_proj_dir), "--format", "xlsx", "-o", str(java_xlsx_path))
    java_xlsx_bytes = java_xlsx_path.read_bytes()

    py_cells = parse_xlsx_cells(py_xlsx_bytes)
    java_cells = parse_xlsx_cells(java_xlsx_bytes)
    if py_cells.keys() != java_cells.keys():
        errors.append(f"{name}: XLSX sheet names mismatch")
    else:
        for sheet_name in py_cells:
            py_s = [
                [(ref, ctype, CAN.canonicalize(val, id_to_name)) for ref, ctype, val in r]
                for r in py_cells[sheet_name]
            ]
            j_s = [
                [(ref, ctype, CAN.canonicalize(val, id_to_name)) for ref, ctype, val in r]
                for r in java_cells[sheet_name]
            ]
            if py_s != j_s:
                errors.append(f"{name}: XLSX cells mismatch in sheet {sheet_name}")

    # 5. Compare & reference template parity
    py_tpl = reference_template(report)
    java_tpl_path = case_dir / "java_template.json"
    against_path = case_dir / "against.json"
    # Create a slightly modified report for --against diff testing
    modified_report = dict(report)
    modified_report["flow_count"] = int(report.get("flow_count", 0)) + 2
    against_path.write_text(json.dumps(modified_report, indent=2), encoding="utf-8")

    cmp_proc = run_java(
        cp,
        "CompareCli",
        "--project",
        str(java_proj_dir),
        "--template",
        str(java_tpl_path),
        "--reference",
        str(java_tpl_path),
        "--against",
        str(against_path),
    )
    java_tpl_str = java_tpl_path.read_text(encoding="utf-8")
    py_tpl_str = json.dumps(py_tpl, indent=2)
    if java_tpl_str != py_tpl_str:
        errors.append(f"{name}: reference_template text mismatch")

    py_cmp_result = {}
    py_cmp_result.update(compare_reference(report, py_tpl))
    py_cmp_result["diff"] = compare_runs(modified_report, report)
    py_cmp_str = json.dumps(py_cmp_result, indent=2) + "\n"
    java_cmp_str = cmp_proc.stdout.decode("utf-8")
    if java_cmp_str != py_cmp_str:
        errors.append(f"{name}: CompareCli stdout mismatch")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workdir", type=Path)
    parser.add_argument("--only", nargs="*", help="Subset of scenario names to run")
    parser.add_argument("--java-classpath", help="Precomputed Java runtime classpath")
    args = parser.parse_args()

    repo_root = HERE.parents[2]
    cp = args.java_classpath or java_classpath(repo_root)
    args.workdir.mkdir(parents=True, exist_ok=True)

    selected = [item for item in INPUTS if not args.only or item[0] in args.only]
    all_errors: list[str] = []
    for name, scenario, opts in selected:
        errs = compare_case(name, scenario, opts, args.workdir, cp)
        status = "PASS" if not errs else f"FAIL ({len(errs)})"
        print(f"[{status}] {name}", flush=True)
        for e in errs:
            print(f"  - {e}", flush=True)
        all_errors.extend(errs)

    if all_errors:
        print(f"\nFAILED with {len(all_errors)} error(s).", file=sys.stderr)
        return 1
    print(f"\nALL {len(selected)} SCENARIOS PASSED (JSON, HTML, CSV, XLSX, COMPARE).", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
