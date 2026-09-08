"""M10.3 -- D3 vendor/platform capability-support producer.

Frozen contract:
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` section 3.3 D3
("can this capability run against this vendor/platform/entity kind, given
implemented support **and** positive vendor evidence"), section 7.3 ("what
may never prove capability support"), and section 8.2 point 1 ("Absence of a
producer is UNKNOWN, not a favourable default and not a confirmed absence").
D3's own "Evidence today" cell names no producer before this movement. This
module is that producer. It has no consumer wired to it (the resolver is
`M10.2`), no navigation, template, static or payload change, and it does not
import or extend `utils/capability_registry.py` (a distinct, 0.6.1C
collection-capability planner -- namespace-collision risk named in `M10.1`'s
own history).

Section 7.3 governs every entry below: `SUPPORTED`/`UNSUPPORTED` are set only
from positive evidence -- here, a real, wired, non-stub collector/adapter
module cited by exact path -- never from a menu, reachability, enrollment, a
different capability's success, a failed collection, an unrecognized vendor,
or general vendor knowledge not backed by repository evidence.

**No `UNSUPPORTED` entry is populated.** The repository review for this
movement found no citable positive evidence that any vendor/platform *cannot*
perform one of the seven capabilities -- only evidence of what is and is not
yet implemented. `PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` section 8's own
worked example says informally that "Diagnostics stays UNSUPPORTED until
PCP.8 exists"; that phrasing predates this contract and is superseded by
section 8.2 point 1's explicit rule, which this producer follows: an absent
producer is `SUPPORT_UNKNOWN`, never `UNSUPPORTED`. `UNSUPPORTED` stays a
reachable value of the vocabulary for a future entry backed by real evidence
(a frozen vendor contract, official vendor documentation, or real-environment
corroboration); `resolve_d3` never returns it today.

Granularity: the two vendor identifiers below (`checkpoint`, `panorama`) are
the ones real repository code already uses (`console/registry.py`
`JobType.vendor`; `application/cli.py` `--recovery-vendor` choices). No
platform-family split within a vendor (e.g. Gaia vs. Quantum Spark, or a
PAN-OS version train) has any producer evidence in this repository today;
that finer granularity is a named gap, not an invented distinction (`AC-5`),
and stays `SUPPORT_UNKNOWN`.
"""
from __future__ import annotations

from dataclasses import dataclass

SUPPORTED = "SUPPORTED"
UNSUPPORTED = "UNSUPPORTED"
SUPPORT_UNKNOWN = "SUPPORT_UNKNOWN"

#: The closed D3 vocabulary (contract section 4, section 4.1.5 "Validly
#: unknown" row). Nothing outside this set is ever returned.
D3_VALUES: frozenset[str] = frozenset({SUPPORTED, UNSUPPORTED, SUPPORT_UNKNOWN})

#: The seven frozen capability names, `PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`
#: section 8 ("Capability vocabulary (initial)"), FROZEN by `PCP.0`.
CAPABILITY_IDS: frozenset[str] = frozenset({
    "inventory",
    "configuration_collection",
    "backup",
    "ha_readiness",
    "controlled_operations",
    "telemetry",
    "diagnostics",
})

#: The two vendor identifiers real repository code uses today
#: (`console/registry.py::JobType.vendor`; `application/cli.py`
#: `--recovery-vendor` choices).
VENDORS: frozenset[str] = frozenset({"checkpoint", "panorama"})


@dataclass(frozen=True)
class VendorSupportResult:
    """One `(vendor, capability)` pair's D3 answer, plus the exact source
    cited for it -- a real module/function path for `SUPPORTED`, or a named
    gap for `SUPPORT_UNKNOWN`."""

    value: str
    reason: str

    def __post_init__(self) -> None:
        if self.value not in D3_VALUES:
            raise ValueError(f"not a D3 value: {self.value!r}")


_SUPPORT_TABLE: dict[tuple[str, str], VendorSupportResult] = {
    ("checkpoint", "inventory"): VendorSupportResult(
        SUPPORTED,
        "checkpoint/cp_runner.py::run_cp and checkpoint/vsx_runner.py::run_vsx, "
        "wired application/workflows/checkpoint.py::integration_checkpoint",
    ),
    ("panorama", "inventory"): VendorSupportResult(
        SUPPORTED,
        "panorama/panorama_runtime_runner.py::run_panorama_runtime, wired "
        "application/workflows/checkpoint.py::integration_checkpoint (the "
        "'panorama' collection stage, distinct from 'pan-config')",
    ),
    ("checkpoint", "configuration_collection"): VendorSupportResult(
        SUPPORTED,
        "configuration/checkpoint_config_collector.py::run_checkpoint_config_collection, "
        "wired application/workflows/checkpoint.py::cp_config_collect",
    ),
    ("panorama", "configuration_collection"): VendorSupportResult(
        SUPPORTED,
        "configuration/panorama_config_collector.py::run_panorama_config_evidence, "
        "wired application/workflows/checkpoint.py (the 'pan-config' stage)",
    ),
    ("checkpoint", "backup"): VendorSupportResult(
        SUPPORTED,
        "checkpoint/checkpoint_recovery_collector.py::CheckpointGaiaBackupCollector, "
        "wired application/workflows/recovery.py::recovery_collect "
        "(console job type cp_gaia_backup, console/registry.py)",
    ),
    ("panorama", "backup"): VendorSupportResult(
        SUPPORTED,
        "panorama/panorama_recovery_collector.py::PanDeviceStateCollector, wired "
        "application/workflows/recovery.py::recovery_collect under "
        "--recovery-vendor panorama (application/cli.py choices=['panorama','checkpoint'])",
    ),
    ("checkpoint", "ha_readiness"): VendorSupportResult(
        SUPPORTED,
        "utils/failover/assessment.py _UNIT_CP_CLUSTER / _UNIT_CP_VSX_HOST / "
        "_UNIT_CP_VSX_CLUSTER / _UNIT_CP_VSX_VS, vendor='checkpoint'",
    ),
    ("panorama", "ha_readiness"): VendorSupportResult(
        SUPPORTED,
        "utils/failover/assessment.py _UNIT_PAN_PAIR / _derive_pan_units, "
        "vendor='panorama'",
    ),
}
#: controlled_operations, telemetry, diagnostics for either vendor: reviewed
#: and deliberately left out of the table. `utils/operate/adapter.py` is
#: D3's own cited input for controlled_operations (contract section 3.3 D3
#: "Owner" row) and carries zero concrete vendor implementation -- only a
#: `Protocol` and dataclasses, its own module docstring stating "No concrete
#: implementation exists in this module or anywhere in this package" -- so a
#: (vendor, "controlled_operations") lookup is exactly the "no producer"
#: case section 8.2 point 1 names, not a vendor capability conclusion.
#: telemetry and diagnostics have no collector/producer anywhere in the
#: repository (diagnostics awaits PCP.8). Every one of these six pairs
#: resolves SUPPORT_UNKNOWN via the table-miss path below.


def resolve_d3(*, vendor: str | None, capability: str) -> VendorSupportResult:
    """Resolve one `(vendor, capability)` pair's D3 value from the closed
    table above (`AC-2`).

    `vendor=None` means the vendor/platform itself is not established and
    resolves `SUPPORT_UNKNOWN` without consulting the table -- the same
    fail-closed handling section 7.3 requires for a vendor hint that has not
    been positively confirmed. An unrecognized `vendor` or `capability`
    value resolves `SUPPORT_UNKNOWN`, never `UNSUPPORTED` (section 7.3: "an
    unrecognized platform must never be read as UNSUPPORTED"). Everything
    else with no closed-table entry is a genuine, named gap.
    """
    if vendor is None:
        return VendorSupportResult(SUPPORT_UNKNOWN, reason="vendor_not_established")
    if vendor not in VENDORS:
        return VendorSupportResult(SUPPORT_UNKNOWN, reason=f"unrecognized_vendor:{vendor!r}")
    if capability not in CAPABILITY_IDS:
        return VendorSupportResult(SUPPORT_UNKNOWN, reason=f"unrecognized_capability:{capability!r}")

    entry = _SUPPORT_TABLE.get((vendor, capability))
    if entry is not None:
        return entry
    return VendorSupportResult(
        SUPPORT_UNKNOWN,
        reason=f"no_positive_evidence_found_for:({vendor},{capability})",
    )
