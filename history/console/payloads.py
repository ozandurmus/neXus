"""CON.1 C1-4 — payload parity is a test, not a convention.

``build_console_payloads`` calls the exact same builder function
``utils.html_export.run_html_export`` calls (``build_report_payloads``), with
the same inputs. It may not reshape, filter, enrich or reorder — that is what
keeps ``/api/payloads`` and the exported report's embedded payloads from
forking. No lifecycle/capability/coordinator state is threaded through: a
freshly launched console process has no in-memory collection state of its
own (CON.1 contacts no device and starts no collector), so the discovery/
exclusions payloads render their existing explicit empty state, exactly as
``--render-only`` does before a coordinator exists.
"""
from __future__ import annotations

import json
from pathlib import Path

from utils.html_export import build_report_payloads


def _load_output_json(name: str, output_root: Path):
    path = Path(output_root) / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def build_console_payloads(runtime_paths) -> dict:
    """The same seven-key payload dict the report embeds, read live off
    ``runtime_paths`` (C1-7: resolved runtime paths only — no path input, no
    artifact discovery)."""
    config_result = _load_output_json("pan_config_telemetry.json", runtime_paths.output_root)
    checkpoint_config_result = _load_output_json("cp_config_telemetry.json", runtime_paths.output_root)
    return build_report_payloads(
        runtime_paths.output_root / "unified.json",
        config_result=config_result,
        checkpoint_config_result=checkpoint_config_result,
        workflow_context=None,
        repository_root=runtime_paths.repository_root,
        data_root=runtime_paths.data_root,
    )
