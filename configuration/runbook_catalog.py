"""PCP.8 — closed catalog of read-only diagnostic runbooks.

``docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`` section 15. A runbook is
a closed, ordered sequence of primitive_id references into
``configuration.command_primitives.PRIMITIVE_REGISTRY`` — never a raw command,
never a user-authored script. This module and ``configuration.command_
primitives`` share exactly **one** registry (section 15, "Reuse"); a runbook
step is only ever resolved by looking up an existing registry entry, never by
declaring a new command here.

Validator (AC-3): ``register_runbook``/``validate_runbook_steps`` reject any
step that is not already an admitted ``CLASS_0_READ`` registry entry — the
same fail-closed posture ``configuration.command_primitives.register_primitive``
already applies to the registry itself, applied a second time at the runbook
layer so a runbook can never reach a class 1+ or unregistered step even if the
underlying registry were ever compromised.

Execution reuses ``configuration.command_primitives.run_check_point_primitives``
/ ``run_palo_alto_primitives`` unchanged, passing this module's own ordered
``primitives=`` list — the vendor-generic extension point CE.2 already
provides (``project/backlog.json`` id ``diagnostic_runbooks_read_only``).
Deterministic framing, output limits, and redaction are therefore CE.2's own
mechanism, not a new one: every step's result is the same ``PrimitiveResult``
shape, and ``redact()`` still discards raw output in the same function that
captured it.
"""
from __future__ import annotations

from dataclasses import dataclass

from configuration.command_primitives import CommandPrimitive, PRIMITIVE_REGISTRY
from utils.action_taxonomy import CLASS_0_READ

__all__ = [
    "RunbookValidationError",
    "Runbook",
    "RUNBOOK_CATALOG",
    "register_runbook",
    "validate_runbook_steps",
]


class RunbookValidationError(RuntimeError):
    """Raised when a runbook cannot be admitted into the catalog safely."""


@dataclass(frozen=True)
class Runbook:
    runbook_id: str
    vendor: str  # "check_point" | "palo_alto" — must match every step's own vendor
    title: str
    purpose: str
    step_ids: tuple[str, ...]  # ordered primitive_id references, PRIMITIVE_REGISTRY only

    def resolve_steps(self, registry: dict[str, CommandPrimitive] = None) -> list[CommandPrimitive]:
        """Re-validate and resolve this runbook's steps against the live
        registry, in declared order. Called again at execution time (not just
        at catalog-load time) so a run never trusts an earlier validation."""
        return validate_runbook_steps(
            list(self.step_ids), self.vendor, registry if registry is not None else PRIMITIVE_REGISTRY
        )


def validate_runbook_steps(
    step_ids: list[str], vendor: str, registry: dict[str, CommandPrimitive] = PRIMITIVE_REGISTRY
) -> list[CommandPrimitive]:
    """Resolve ``step_ids`` to registry entries. Fail-closed.

    Raises ``RunbookValidationError`` if the sequence is empty, if a step_id
    is not already a registered primitive, if a resolved primitive is not
    ``CLASS_0_READ``, or if a resolved primitive's vendor does not match the
    runbook's declared vendor. This is the single choke point every runbook
    step passes through, mirroring ``command_primitives.register_primitive``'s
    own defense-in-depth posture at the runbook layer.
    """
    if not step_ids:
        raise RunbookValidationError("a runbook must reference at least one primitive step")
    resolved: list[CommandPrimitive] = []
    for step_id in step_ids:
        primitive = registry.get(step_id)
        if primitive is None:
            raise RunbookValidationError(
                f"runbook step {step_id!r} is not a registered CE.2 primitive"
            )
        if primitive.gate_review.action_class_id != CLASS_0_READ.id:
            raise RunbookValidationError(
                f"runbook step {step_id!r} declares action class "
                f"{primitive.gate_review.action_class_id!r}; only "
                f"{CLASS_0_READ.id!r} steps are runbook-eligible"
            )
        if primitive.vendor != vendor:
            raise RunbookValidationError(
                f"runbook step {step_id!r} is vendor {primitive.vendor!r}; "
                f"runbook declares vendor {vendor!r}"
            )
        resolved.append(primitive)
    return resolved


def register_runbook(runbook: Runbook) -> Runbook:
    """Admit one runbook into the catalog. Fail-closed — validates every step
    against the live registry before the runbook_id becomes resolvable."""
    validate_runbook_steps(list(runbook.step_ids), runbook.vendor)
    return runbook


# ---------------------------------------------------------------------------
# Catalog entries. One concrete example runbook (AC-4): a single-step Check
# Point health check reusing CE.2's own proof primitive — opt-in only via
# ``main.py --runbook-execute --runbook-id cp_basic_health_check``, never
# wired into a normal collection run.
# ---------------------------------------------------------------------------

_CP_BASIC_HEALTH_CHECK = Runbook(
    runbook_id="cp_basic_health_check",
    vendor="check_point",
    title="Check Point — basic version/build health check",
    purpose=(
        "Read the managed gateway's own reported version/build/edition, for "
        "an operator confirming what is actually running before a support "
        "escalation — no config, no state change, exactly the CE.2 proof "
        "primitive already gate-reviewed for this transport/command."
    ),
    step_ids=("cp_gaia_show_version_all",),
)

_RUNBOOKS: tuple[Runbook, ...] = (_CP_BASIC_HEALTH_CHECK,)

RUNBOOK_CATALOG: dict[str, Runbook] = {
    runbook.runbook_id: register_runbook(runbook) for runbook in _RUNBOOKS
}
