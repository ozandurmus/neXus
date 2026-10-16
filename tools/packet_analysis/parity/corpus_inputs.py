"""Analysis inputs of a frozen corpus case, as the reference had them (plain Python, shared by corpus_run.py and
core_baseline.py).

The corpus stores the captures, the generator's own ``topology.json`` (pre-binding, capture file names) and
``analysis-topology.json`` (the topology the reference saved after analysis, capture ids mapped back to file
names). The saved form is NOT a valid analysis input in general: pydantic does not validate defaults, so a
defaulted ``float`` field (``bucket_seconds = 1``) is dumped as the int ``1`` while an explicitly given value is
dumped as a float, and feeding the dump back turns ``1`` into ``1.0`` (visible in report text). The generator's
``topology.json`` is therefore used whenever the corpus has it, with the same edits reference.py applied; only
``integrity_all`` (whose generator writes no topology file) is reconstructed from the saved form by dropping every
int literal in a float-typed field (only an unvalidated default can produce one), which restores the default.
"""

from __future__ import annotations

import json
from pathlib import Path

CAPTURE_SUFFIXES = (".pcap", ".pcapng", ".cap")
# Float-typed fields of the frozen topology models (packetbreaker/topology/models.py at the frozen commit).
TOPOLOGY_FLOATS = {
    "cycle_stall_ms", "rtp_jitter_ms", "udp_timeout_ms", "security_scan_window_seconds",
    "security_scan_failed_ratio", "security_syn_rate", "security_completion_ratio", "session_timeout_seconds",
    "microburst_window_ms", "dns_timeout_ms", "bucket_seconds", "min_eligible_ratio", "duplicate_us", "stall_ms",
    "match_window_ms", "start", "end",
}
POINT_FLOATS = {"x", "y"}
OVERRIDE_FLOATS = {"offset_ms", "drift_ppm"}


def capture_files(input_dir: Path) -> list[str]:
    """Capture file names in the order the reference ingested them (generator ``files``, else path order)."""
    truth = input_dir / "ground-truth.json"
    if truth.is_file():
        files = json.loads(truth.read_text(encoding="utf-8")).get("files")
        if files:
            return [Path(f).name for f in files]
    return sorted(p.name for p in input_dir.iterdir() if p.suffix.lower() in CAPTURE_SUFFIXES)


def _drop_int_floats(obj: dict, floats: set[str]) -> dict:
    return {k: v for k, v in obj.items() if not (k in floats and isinstance(v, int) and not isinstance(v, bool))}


def analysis_topology(input_dir: Path, case_name: str) -> dict:
    """The topology passed to the (first) reference analysis, capture ids as file names."""
    generator = input_dir / "topology.json"
    if generator.is_file():
        topology = json.loads(generator.read_text(encoding="utf-8"))
        if case_name == "fifty_flow_replica":  # reference.py: the mirror point is removed before analysis
            topology["forward"] = [p for p in topology["forward"] if p != "mirror"]
            topology["reverse"] = [p for p in topology["reverse"] if p != "mirror"]
            topology["points"] = [p for p in topology["points"] if p["id"] != "mirror"]
        return topology
    saved = json.loads((input_dir / "analysis-topology.json").read_text(encoding="utf-8"))
    topology = _drop_int_floats(saved, TOPOLOGY_FLOATS)
    topology["points"] = [_drop_int_floats(p, POINT_FLOATS) for p in saved.get("points", [])]
    topology["clock_overrides"] = {
        k: _drop_int_floats(v, OVERRIDE_FLOATS) for k, v in saved.get("clock_overrides", {}).items()
    }
    return topology
