"""M10.3 -- D2 entity-applicability producer.

Frozen contract:
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` section 3.3 D2
(values, meaning, evidence, ownership) -- "a **type** question, answered from
the entity model, never from evidence". This module is that producer
(`AC-CS-3`-adjacent; D2's own "Evidence today" cell names no producer before
this movement). It has no consumer wired to it (the resolver is `M10.2`), no
navigation, template, static or payload change, and it does not import or
extend `utils/capability_registry.py` (a distinct, 0.6.1C collection-capability
planner -- namespace-collision risk named in `M10.1`'s own history).

Entity-type model: `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md`
section 5.2's table, the closed set of eight logical entity types the
Product Owner's model recognizes today. Every table entry below cites its
own exact source; a `(entity_type, capability)` pair with no citable source
resolves `APPLICABILITY_UNKNOWN` naming that gap (`AC-5`) -- it is never
inferred from a capability id substring, a vendor guess, or plausible-sounding
product knowledge.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

APPLICABLE = "APPLICABLE"
NOT_APPLICABLE = "NOT_APPLICABLE"
APPLICABILITY_UNKNOWN = "APPLICABILITY_UNKNOWN"

#: The closed D2 vocabulary (contract section 4, section 4.1.5 "Validly
#: unknown" row). Nothing outside this set is ever returned.
D2_VALUES: frozenset[str] = frozenset({APPLICABLE, NOT_APPLICABLE, APPLICABILITY_UNKNOWN})

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


class EntityType(str, Enum):
    """The eight logical-entity types `NAVIGATION_INFORMATION_ARCHITECTURE.md`
    section 5.2's table names -- closed, and not extended here. A `None`
    passed as `entity_type` to `resolve_d2` means the type itself is not
    established (contract section 3.3 D2: "`APPLICABILITY_UNKNOWN` when the
    type itself is not established, e.g. a PAN pair whose B2 corroboration is
    NOT ESTABLISHED")."""

    CP_STANDALONE_GATEWAY = "cp_standalone_gateway"
    CP_CLUSTERXL_CLUSTER = "cp_clusterxl_cluster"
    CP_CLUSTERXL_MEMBER = "cp_clusterxl_member"
    CP_VSX_HOST_CLUSTER = "cp_vsx_host_cluster"
    CP_VIRTUAL_SYSTEM = "cp_virtual_system"  # VSID, nested under CP_VSX_HOST_CLUSTER
    PAN_STANDALONE_FIREWALL = "pan_standalone_firewall"
    PAN_HA_PAIR = "pan_ha_pair"
    PAN_HA_MEMBER = "pan_ha_member"


@dataclass(frozen=True)
class ApplicabilityResult:
    """One `(entity_type, capability)` pair's D2 answer, plus the exact
    source cited for it -- a doc section + row for `APPLICABLE`/
    `NOT_APPLICABLE`, or a named gap for `APPLICABILITY_UNKNOWN`."""

    value: str
    reason: str

    def __post_init__(self) -> None:
        if self.value not in D2_VALUES:
            raise ValueError(f"not a D2 value: {self.value!r}")


_NAV = "NAVIGATION_INFORMATION_ARCHITECTURE.md"

_ALL_ENTITY_TYPES: tuple[EntityType, ...] = tuple(EntityType)

_APPLICABILITY_TABLE: dict[tuple[EntityType, str], ApplicabilityResult] = {}


def _applicable_for_every_entity_type(capability: str, citation: str) -> None:
    for entity_type in _ALL_ENTITY_TYPES:
        _APPLICABILITY_TABLE[(entity_type, capability)] = ApplicabilityResult(APPLICABLE, citation)


# inventory / configuration_collection / backup: section 6.4's "Tab
# allocation" table lists "all" under Entity types for the Network/Inventory,
# Configuration and Recovery/Backups rows respectively.
_applicable_for_every_entity_type(
    "inventory", f"{_NAV} section 6.4, 'Network / Inventory' row, Entity types: all"
)
_applicable_for_every_entity_type(
    "configuration_collection", f"{_NAV} section 6.4, 'Configuration' row, Entity types: all"
)
_applicable_for_every_entity_type(
    "backup", f"{_NAV} section 6.4, 'Recovery / Backups' row, Entity types: all"
)

# ha_readiness: section 6.4's "HA / Readiness" row names exactly which entity
# types it applies to ("cluster, VSX cluster, PAN HA pair, VSLS VSID"), and
# section 7.2's P2 worked example gives the explicit negative case ("a
# standalone firewall has no HA readiness"). Cluster-member-level applicability
# (CP_CLUSTERXL_MEMBER, PAN_HA_MEMBER) is not addressed by either source --
# left out of the table, so a lookup resolves APPLICABILITY_UNKNOWN rather
# than guessing member-level scoping.
for _entity_type, _result in {
    EntityType.CP_CLUSTERXL_CLUSTER: ApplicabilityResult(
        APPLICABLE, f"{_NAV} section 6.4, 'HA / Readiness' row: applies to cluster"
    ),
    EntityType.CP_VSX_HOST_CLUSTER: ApplicabilityResult(
        APPLICABLE, f"{_NAV} section 6.4, 'HA / Readiness' row: applies to VSX cluster"
    ),
    EntityType.CP_VIRTUAL_SYSTEM: ApplicabilityResult(
        APPLICABLE, f"{_NAV} section 6.4, 'HA / Readiness' row: applies to VSLS VSID"
    ),
    EntityType.PAN_HA_PAIR: ApplicabilityResult(
        APPLICABLE, f"{_NAV} section 6.4, 'HA / Readiness' row: applies to PAN HA pair"
    ),
    EntityType.CP_STANDALONE_GATEWAY: ApplicabilityResult(
        NOT_APPLICABLE,
        f"{_NAV} section 7.2, P2 worked example: 'a standalone firewall has no HA readiness'",
    ),
    EntityType.PAN_STANDALONE_FIREWALL: ApplicabilityResult(
        NOT_APPLICABLE,
        f"{_NAV} section 7.2, P2 worked example: 'a standalone firewall has no HA readiness'",
    ),
}.items():
    _APPLICABILITY_TABLE[(_entity_type, "ha_readiness")] = _result

# controlled_operations: the one citable structural fact is that a VSID is
# never a CLASS 2 lock subject -- a narrower, entity-type-scoped claim than
# "CLASS 2 has no member yet" (that is a D3/product-maturity fact, not this
# dimension's type question, and stays out of this table). Every other
# (entity_type, controlled_operations) pair has no citable source here.
_APPLICABILITY_TABLE[(EntityType.CP_VIRTUAL_SYSTEM, "controlled_operations")] = ApplicabilityResult(
    NOT_APPLICABLE,
    "FAILOVER_ENGINE_ARCHITECTURE.md section 10.2: a VSID is a readiness "
    "domain under VSLS and never a CLASS 2 lock subject",
)

# telemetry, diagnostics: no entity-type applicability source found anywhere
# reviewed for this movement (the Diagnostics tab is section 6.4's
# "RESERVED" row, gated by product-surface eligibility (D1), not by entity
# type). Both stay entirely out of the table -- every lookup resolves
# APPLICABILITY_UNKNOWN naming the gap.


def resolve_d2(*, entity_type: EntityType | None, capability: str) -> ApplicabilityResult:
    """Resolve one `(entity_type, capability)` pair's D2 value from the
    closed table above (`AC-1`).

    `entity_type=None` means the logical-entity type itself is not
    established (contract section 3.3 D2) and resolves
    `APPLICABILITY_UNKNOWN` without consulting the table. An unrecognized
    `capability` id does the same -- never guessed from a substring or a
    vendor hint (`AC-5`). Everything else that has no closed-table entry is
    a genuine, named gap, not a favourable or unfavourable default.
    """
    if entity_type is None:
        return ApplicabilityResult(APPLICABILITY_UNKNOWN, reason="entity_type_not_established")
    if capability not in CAPABILITY_IDS:
        return ApplicabilityResult(APPLICABILITY_UNKNOWN, reason=f"unrecognized_capability:{capability!r}")

    entry = _APPLICABILITY_TABLE.get((entity_type, capability))
    if entry is not None:
        return entry
    return ApplicabilityResult(
        APPLICABILITY_UNKNOWN,
        reason=f"no_closed_table_entry_for:({entity_type.value},{capability})",
    )
