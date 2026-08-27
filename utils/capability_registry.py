"""Capability registry and collection planner — 0.6.1C.

A ``CapabilityProfile`` records what has been directly observed about an
entity's shell and collection interface.  Platform *identity* (e.g. Quantum
Spark vs Enterprise Gaia) is a separate, management-plane concept and must
NOT be inferred solely from shell or collection capability.

The collection planner combines a CapabilityProfile with an entity's
lifecycle state to produce a ``CollectionPlan``: a recommended collection
mode, an allowed flag, and a reason code.  The planner never guesses;
it returns UNKNOWN or DEFERRED when evidence is missing.

Shell type vocabulary
---------------------
EXPERT          Expert shell confirmed (Enterprise Gaia default).
DIRECT_CLISH    Device lands directly in Gaia Clish after SSH login.
                This is a *capability*, not Quantum Spark identity proof.
UNKNOWN         Shell behavior not yet observed.

Collection mode vocabulary
--------------------------
EXPERT_EXPLICIT_CLISH   Expert shell → invoke Gaia Clish explicitly via
                        ``clish -c '...'``.
DIRECT_CLISH_CAPABLE    Device accepts interactive/non-interactive Clish
                        directly; platform identity remains unknown.
VSX_VSENV               VSX context: Expert shell + ``vsenv <VSID>`` required.
PAN_API                 Palo Alto device: REST/XML API.
DEFERRED_STANDBY        ClusterXL/VRRP standby member; skip to avoid
                        unnecessary login before HA role is confirmed.
DEFERRED_LIFECYCLE      Entity not ready for collection per lifecycle state.
UNKNOWN                 Insufficient evidence; do not collect.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any

from utils.discovery_lifecycle import LifecycleState


class ShellType(str, Enum):
    EXPERT       = "expert"
    DIRECT_CLISH = "direct_clish"
    UNKNOWN      = "unknown"


class CollectionMode(str, Enum):
    EXPERT_EXPLICIT_CLISH  = "expert_explicit_clish"
    DIRECT_CLISH_CAPABLE   = "direct_clish_capable"
    VSX_VSENV              = "vsx_vsenv"
    PAN_API                = "pan_api"
    DEFERRED_STANDBY       = "deferred_standby"
    DEFERRED_LIFECYCLE     = "deferred_lifecycle"
    UNKNOWN                = "unknown"


class PlanReasonCode(str, Enum):
    # Allowed
    SHELL_EXPERT_CONFIRMED      = "shell_expert_confirmed"
    SHELL_DIRECT_CLISH          = "shell_direct_clish"
    VSX_VSENV_CAPABLE           = "vsx_vsenv_capable"
    PAN_API_CAPABLE             = "pan_api_capable"
    # Deferred / not allowed
    STANDBY_MEMBER              = "standby_member"
    LIFECYCLE_EXCLUDED          = "lifecycle_excluded"
    LIFECYCLE_REMOVED           = "lifecycle_removed"
    LIFECYCLE_UNDISCOVERED      = "lifecycle_undiscovered"
    IDENTITY_FAILURE_HISTORY    = "identity_failure_history"
    INSUFFICIENT_EVIDENCE       = "insufficient_evidence"
    UNKNOWN_SHELL               = "unknown_shell"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass(frozen=True)
class CapabilityProfile:
    """Observed capability facts for one entity.

    ``canonical_id`` is opaque and must match the discovery_lifecycle key.
    All fields reflect *observed* evidence; None means not yet observed.

    ``standby_member`` is set when cphaprob or equivalent confirms the
    entity is a non-active ClusterXL/VRRP member.  The planner uses this
    to avoid an unnecessary login before HA-role data is confirmed.
    """
    vendor: str
    canonical_id: str
    shell_type: ShellType = ShellType.UNKNOWN
    direct_collection_capable: bool | None = None
    vsx_vsenv_capable: bool | None = None
    pan_vsys_capable: bool | None = None
    standby_member: bool | None = None
    had_identity_failure: bool = False
    confidence: int = 0           # 0-100; accumulated from evidence
    last_evidenced: str = field(default_factory=_utc_now)
    notes: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        if not (0 <= self.confidence <= 100):
            raise ValueError(f"confidence must be 0-100, got {self.confidence}")
        if not self.canonical_id.strip():
            raise ValueError("canonical_id must be non-empty")

    def to_dict(self) -> dict[str, Any]:
        return {
            "vendor": self.vendor,
            "canonical_id": self.canonical_id,
            "shell_type": self.shell_type.value,
            "direct_collection_capable": self.direct_collection_capable,
            "vsx_vsenv_capable": self.vsx_vsenv_capable,
            "pan_vsys_capable": self.pan_vsys_capable,
            "standby_member": self.standby_member,
            "had_identity_failure": self.had_identity_failure,
            "confidence": self.confidence,
            "last_evidenced": self.last_evidenced,
            "notes": list(self.notes),
        }

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "CapabilityProfile":
        return CapabilityProfile(
            vendor=raw["vendor"],
            canonical_id=raw["canonical_id"],
            shell_type=ShellType(raw.get("shell_type", ShellType.UNKNOWN.value)),
            direct_collection_capable=raw.get("direct_collection_capable"),
            vsx_vsenv_capable=raw.get("vsx_vsenv_capable"),
            pan_vsys_capable=raw.get("pan_vsys_capable"),
            standby_member=raw.get("standby_member"),
            had_identity_failure=bool(raw.get("had_identity_failure", False)),
            confidence=int(raw.get("confidence", 0)),
            last_evidenced=raw.get("last_evidenced", _utc_now()),
            notes=list(raw.get("notes", [])),
        )


@dataclass(frozen=True)
class CollectionPlan:
    """Result of the collection planner for one entity."""
    allowed: bool
    mode: CollectionMode
    reason_code: str
    notes: list[str] = field(default_factory=list)


def plan_collection(
    profile: CapabilityProfile,
    lifecycle_state: LifecycleState,
) -> CollectionPlan:
    """Decide whether and how to collect from an entity.

    Rules applied in priority order:

    1. EXCLUDED lifecycle → never collect; return reason.
    2. REMOVED lifecycle → never collect; return reason.
    3. DISCOVERED (unvalidated) + identity failure history → defer.
    4. Standby ClusterXL/VRRP member → defer to avoid unnecessary login.
    5. VSX vsenv capable → VSX_VSENV mode.
    6. PAN API capable → PAN_API mode.
    7. Expert shell confirmed → EXPERT_EXPLICIT_CLISH.
    8. Direct-Clish capability → DIRECT_CLISH_CAPABLE (platform != Spark).
    9. Otherwise → UNKNOWN / insufficient evidence.
    """
    # 1. EXCLUDED
    if lifecycle_state == LifecycleState.EXCLUDED:
        return CollectionPlan(
            allowed=False,
            mode=CollectionMode.DEFERRED_LIFECYCLE,
            reason_code=PlanReasonCode.LIFECYCLE_EXCLUDED.value,
        )

    # 2. REMOVED
    if lifecycle_state == LifecycleState.REMOVED:
        return CollectionPlan(
            allowed=False,
            mode=CollectionMode.DEFERRED_LIFECYCLE,
            reason_code=PlanReasonCode.LIFECYCLE_REMOVED.value,
        )

    # 3. Identity failure history on unvalidated entity → defer
    if profile.had_identity_failure and lifecycle_state == LifecycleState.DISCOVERED:
        return CollectionPlan(
            allowed=False,
            mode=CollectionMode.UNKNOWN,
            reason_code=PlanReasonCode.IDENTITY_FAILURE_HISTORY.value,
        )

    # 4. Standby member → defer (avoids full login before HA role confirmed)
    if profile.standby_member is True:
        return CollectionPlan(
            allowed=False,
            mode=CollectionMode.DEFERRED_STANDBY,
            reason_code=PlanReasonCode.STANDBY_MEMBER.value,
        )

    # 5. VSX vsenv
    if profile.vsx_vsenv_capable is True:
        return CollectionPlan(
            allowed=True,
            mode=CollectionMode.VSX_VSENV,
            reason_code=PlanReasonCode.VSX_VSENV_CAPABLE.value,
        )

    # 6. PAN API
    if profile.pan_vsys_capable is True:
        return CollectionPlan(
            allowed=True,
            mode=CollectionMode.PAN_API,
            reason_code=PlanReasonCode.PAN_API_CAPABLE.value,
        )

    # 7. Expert shell
    if profile.shell_type == ShellType.EXPERT:
        return CollectionPlan(
            allowed=True,
            mode=CollectionMode.EXPERT_EXPLICIT_CLISH,
            reason_code=PlanReasonCode.SHELL_EXPERT_CONFIRMED.value,
        )

    # 8. Direct-Clish capability (NOT a platform identity claim)
    if profile.shell_type == ShellType.DIRECT_CLISH:
        return CollectionPlan(
            allowed=True,
            mode=CollectionMode.DIRECT_CLISH_CAPABLE,
            reason_code=PlanReasonCode.SHELL_DIRECT_CLISH.value,
            notes=["direct_clish_is_capability_not_platform_identity"],
        )

    # 9. Unknown / insufficient evidence
    return CollectionPlan(
        allowed=False,
        mode=CollectionMode.UNKNOWN,
        reason_code=PlanReasonCode.UNKNOWN_SHELL.value
        if profile.shell_type == ShellType.UNKNOWN
        else PlanReasonCode.INSUFFICIENT_EVIDENCE.value,
    )


class CapabilityStore:
    """In-memory store for CapabilityProfiles (mirrors LifecycleStore pattern)."""

    def __init__(self) -> None:
        self._profiles: dict[tuple[str, str], CapabilityProfile] = {}

    def _key(self, vendor: str, canonical_id: str) -> tuple[str, str]:
        return (vendor.strip().lower(), canonical_id)

    def get(self, vendor: str, canonical_id: str) -> CapabilityProfile | None:
        return self._profiles.get(self._key(vendor, canonical_id))

    def put(self, profile: CapabilityProfile) -> None:
        self._profiles[self._key(profile.vendor, profile.canonical_id)] = profile

    def all_profiles(self) -> list[CapabilityProfile]:
        return list(self._profiles.values())

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": 1,
            "profiles": [p.to_dict() for p in self._profiles.values()],
        }

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "CapabilityStore":
        store = CapabilityStore()
        for row in raw.get("profiles", []):
            try:
                profile = CapabilityProfile.from_dict(row)
            except (KeyError, ValueError):
                continue
            store.put(profile)
        return store
