"""Recorded tie-breaks accepted by corpus_run.py (ui2/packet-analysis/DETERMINISM.md, "Deliberate differences").

The lane parity scripts accept a difference when the input and the report section are recorded in their
``DELIBERATE`` tables (core_compare.py, det_a_compare.py, det_b_compare.py, det_c_compare.py) or when a findings
list differs only in the integrity finding id counters (det_a/det_b ``mask_integrity_counters`` / ``order_free``).
The corpus runs the same inputs through the whole pipeline and compares canonical forms (lists sorted, floats to 9
digits), so list-order-only ties vanish there and what remains of a tie is the tied row the two engines picked:
the evidence of an item, its evidence frame / key, a session point's first frame, or an id counter.

A section difference is accepted as RECORDED-TIES only when both hold:

1. the corpus case's scenario and the section are recorded for a tie mechanism: in the lane tables (mapped to the
   corpus scenario and top-level section below), or in ``CORPUS_OBSERVED`` (the same mechanisms observed on corpus
   inputs the lanes did not run; listed in DETERMINISM.md);
2. the reference and Java sections are identical once that mechanism's tied selection is masked
   (``TIED_KEYS`` removed anywhere in the section, integrity id counters dropped) and the result is
   re-canonicalized. Every count, metric, class, verdict and finding must still agree.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import canonical as canon  # noqa: E402
import core_compare as C  # noqa: E402
import det_a_compare as A  # noqa: E402
import det_b_compare as B  # noqa: E402
import det_c_compare as D  # noqa: E402

# The row a tie-break picked, as it shows in a section: evidence lists and the frames / keys they come from.
TIED_KEYS = {
    "evidence", "evidence_refs", "evidence_frame_a", "evidence_frame_b", "evidence_key", "evidence_key_b",
    "evidence_hop", "first_frame", "request_frame", "response_frame",
}
COUNTER = re.compile(r"^(integrity:.*):\d+(:merged)?$")
# Lane stage / section names -> corpus report sections.
LANE_SECTION = {
    "integrity": "findings", "group_downstream_findings": "findings", "tls_checks": "tls",
    "transport_export": "transport",
}
TIER1_SCENARIO = {"dns_diagnosis": "diagnostics"}  # det_c SAME_INPUT_AS_DET_A
# The recorded mechanisms observed on corpus inputs the lanes did not run (DETERMINISM.md, end-to-end corpus):
# (corpus scenario, section) -> mechanism.
_SESSIONS = A.DELIBERATE[("pilot", "sessions.points")]
_HOST = A.DELIBERATE[("real_case_2", "host_path.items[0]")]
_CYCLES = A.DELIBERATE[("diagnostics", "cycles.top")]
CORPUS_OBSERVED: dict[tuple[str, str], str] = {
    ("integrity_all", "sessions"): _SESSIONS,
    ("oct8_replica", "sessions"): _SESSIONS,
    ("fifty_flow_replica", "sessions"): _SESSIONS,
    ("fifty_flow_replica", "delivery"): A._SERIES_TIE,
    ("fifty_flow_replica", "incident"): A._SERIES_TIE,
    ("fifty_flow_replica", "host_path"): _HOST,
    ("duplicate", "delivery"): A._SERIES_TIE,
    ("duplicate", "incident"): A._SERIES_TIE,
    ("onset_delay", "onsets"): A._MAX_PAIR,
    ("onset_delay", "segments"): A._MAX_PAIR,
    ("delay", "cycles"): _CYCLES,
    # Tie-break added by the end-to-end lane (DETERMINISM.md): order_pair_stats arg_min(frame, abs delta).
    ("oct8_replica", "suggested_topology"): (
        "topology order_pair_stats arg_min(a.frame/b.frame, abs(delta)): many packet pairs have delta 0; the "
        "reference keeps the first row its join scans (frames 249/247), Java appends a.frame,b.frame (frames 1/1)"
    ),
}


def scenario(case_name: str) -> str:
    """Corpus case -> generator scenario (``cli-pilot--ip-id-zero`` -> ``pilot``)."""
    name = case_name[4:] if case_name.startswith("cli-") else case_name
    return TIER1_SCENARIO.get(name.split("--")[0], name.split("--")[0])


def _lane_scenario(name: str) -> str:
    name = name[len("scenario_"):] if name.startswith("scenario_") else name
    return name[: -len("_ipv6")] if name.endswith("_ipv6") else name


def _section(where: str) -> str:
    top = re.split(r"[.\[]", where, maxsplit=1)[0]
    return LANE_SECTION.get(top, top)


def recorded() -> dict[tuple[str, str], str]:
    """(scenario, section) -> reason, from the lane scripts' tables plus the integrity id counter rule."""
    out: dict[tuple[str, str], str] = {}
    for (case, where), why in list(C.DELIBERATE.items()) + list(A.DELIBERATE.items()):
        out.setdefault((_lane_scenario(case), _section(where)), why)
    for (case, where, _prefix), why in B.DELIBERATE.items():
        out.setdefault((_lane_scenario(case), _section(where)), why)
    for (prefix, section), why in D.DELIBERATE_ORDER.items():
        out.setdefault((_lane_scenario(prefix.rstrip("_")), section), why)
    out.update(CORPUS_OBSERVED)
    return out


RECORDED = recorded()
COUNTER_REASON = "integrity endpoint_packets output order: finding id counters only (DETERMINISM.md)"


def masked(value):
    if isinstance(value, dict):
        return {k: masked(v) for k, v in value.items() if k not in TIED_KEYS}
    if isinstance(value, list):
        return [masked(v) for v in value]
    if isinstance(value, str):
        return COUNTER.sub(r"\1\2", value)
    return value


def _section_value(tree: dict, path: str):
    parts = path.split(".")
    node = tree[parts[0]]
    for p in parts[1:]:
        if not isinstance(node, dict) or p not in node:
            return None
        node = node[p]
    return node


def accept(case: dict, path: str, ref: dict, java: dict) -> str | None:
    """Reason when the differing section ``path`` (``report.X``, ``export.Y``, ``export.report.X``) is a recorded tie."""
    section = path.split(".")[-1]
    a, b = _section_value(ref, path), _section_value(java, path)
    if a is None or b is None:
        return None
    if canon.dumps(canon.canonical(masked(a))) != canon.dumps(canon.canonical(masked(b))):
        return None
    family = scenario(case["name"])
    key = (family, section)
    if key in RECORDED:
        return f"{family}/{section}: {RECORDED[key]}"
    # Findings (report and FindingsExport) differing only in the integrity id counters are accepted for any input,
    # as det_a_compare.py / det_b_compare.py accept them.
    if section == "findings" and canon.dumps(canon.canonical(masked_counters(a))) == canon.dumps(
            canon.canonical(masked_counters(b))):
        return COUNTER_REASON
    return None


def masked_counters(value):
    if isinstance(value, dict):
        return {k: masked_counters(v) for k, v in value.items()}
    if isinstance(value, list):
        return [masked_counters(v) for v in value]
    if isinstance(value, str):
        return COUNTER.sub(r"\1\2", value)
    return value
