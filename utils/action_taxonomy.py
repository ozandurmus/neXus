"""SecurityExpert — the product's action taxonomy.

One place that answers "how dangerous is this operation, and is it permitted
at the current maturity?" — replacing the older, no-longer-true shorthand that
the product is simply "read-only".

Why this exists
---------------
The read-only claim stopped being accurate when the ``RB.x`` recovery plane
shipped: ``checkpoint/checkpoint_recovery_collector.py`` issues
``add backup local`` and a bounded backup deletion, and ``RB.2`` exports PAN
device state. Those are narrow, ledgered, separately-credentialed recovery
operations — but they are writes, and calling the product "read-only" while
they exist hides exactly the distinction an operator needs.

The failure this prevents
-------------------------
Before this module, the console carried a two-value vocabulary:
``"read" | "operational-write"``. Under it, *taking a Gaia backup* and
*failing over a firewall cluster* are the same class. They are not remotely
the same risk, they need different gates, and ``OP.x`` cannot be built on a
vocabulary that cannot tell them apart.

The five classes
----------------
``CLASS_0_READ``
    Discovery, inventory, configuration collection, compliance, verification,
    history, preflight, health/readiness. The overwhelming majority of the
    product. Permitted.

``CLASS_1_RECOVERY_WRITE``
    Narrowly scoped recovery operations only: temporary backup creation,
    exact generated-artifact cleanup, recovery-specific device operations.
    Permitted **only** through the safety contracts that already wrap them
    (``docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md``: per-entity ledger,
    minimum re-execution interval, distinct backup credential, fail-closed
    allowlist). Not reachable from the console.

``CLASS_2_OPERATIONAL_STATE_CHANGE``
    Failover, cluster role transition, other explicitly approved runtime-state
    actions. **No member exists yet.** This is the ``OP.x`` target class and it
    stays empty until ``OP.2``'s prerequisites are met
    (``docs/design/FAILOVER_ENGINE_ARCHITECTURE.md`` §10).

``CLASS_3_CONFIGURATION_WRITE``
    Configuration / object / policy-rule modification. Prohibited.

``CLASS_4_POLICY_DEPLOYMENT``
    Policy install, automated remediation, broad configuration deployment.
    Prohibited.

Relationship to the legacy term
-------------------------------
``"operational-write"`` is this repository's existing name for what is here
``CLASS_1_RECOVERY_WRITE`` — every current use of it is backup creation or
backup deletion. ``LEGACY_COMMAND_CLASS_TO_ACTION_CLASS`` maps it, so the
persisted vocabulary is untouched. In particular the RB.3b ledger's own
``command_class`` column is a different concept (it stores an *artifact*
class, ``"cp_gaia_backup"``) and must not be conflated with an action class.

This module is a classification vocabulary, not an enforcement engine. Each
surface keeps its own gate; they just describe the gate in the same terms.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ActionClass:
    id: str
    level: int
    label: str
    #: May this class execute anywhere in the product today?
    permitted: bool
    #: May the operator console submit it today? Strictly narrower than
    #: ``permitted``: CLASS 1 runs from the CLI under RB.x's contracts but is
    #: deliberately not reachable from a browser.
    console_submittable: bool
    #: Machine-readable reason surfaced when a surface refuses this class.
    refusal_code: str | None
    why: str


CLASS_0_READ = ActionClass(
    id="read",
    level=0,
    label="Read",
    permitted=True,
    console_submittable=True,
    refusal_code=None,
    why="Evidence collection only; no device state is altered.",
)

CLASS_1_RECOVERY_WRITE = ActionClass(
    id="recovery-write",
    level=1,
    label="Controlled recovery write",
    permitted=True,
    console_submittable=False,
    refusal_code="recovery_write_not_console_submittable",
    why=(
        "Permitted only through the RB.x recovery contracts (per-entity ledger, "
        "minimum re-execution interval, distinct backup credential, fail-closed "
        "entity allowlist). Those gates are CLI-side; the console has no "
        "equivalent authorization boundary until DEPLOY.1A's OIDC/RBAC exists."
    ),
)

CLASS_2_OPERATIONAL_STATE_CHANGE = ActionClass(
    id="operational-state-change",
    level=2,
    label="Operational state change",
    permitted=False,
    console_submittable=False,
    refusal_code="operational_state_change_not_enabled",
    why=(
        "Failover and cluster role transition. No member exists yet. Blocked "
        "until every OP.2 prerequisite in FAILOVER_ENGINE_ARCHITECTURE.md §10 "
        "is met, including the network-device command gate for the two write "
        "primitives and their rollbacks."
    ),
)

CLASS_3_CONFIGURATION_WRITE = ActionClass(
    id="configuration-write",
    level=3,
    label="Configuration write",
    permitted=False,
    console_submittable=False,
    refusal_code="configuration_write_prohibited",
    why="Configuration/object/policy-rule modification is prohibited at the current maturity.",
)

CLASS_4_POLICY_DEPLOYMENT = ActionClass(
    id="policy-deployment",
    level=4,
    label="Policy / deployment / remediation",
    permitted=False,
    console_submittable=False,
    refusal_code="policy_deployment_prohibited",
    why="Policy install, automated remediation and broad deployment are prohibited.",
)

ACTION_CLASSES: tuple[ActionClass, ...] = (
    CLASS_0_READ,
    CLASS_1_RECOVERY_WRITE,
    CLASS_2_OPERATIONAL_STATE_CHANGE,
    CLASS_3_CONFIGURATION_WRITE,
    CLASS_4_POLICY_DEPLOYMENT,
)

BY_ID: dict[str, ActionClass] = {c.id: c for c in ACTION_CLASSES}

#: The pre-taxonomy console vocabulary, preserved so no persisted job record
#: or API response has to be migrated. "operational-write" has only ever meant
#: recovery write in this repository.
LEGACY_COMMAND_CLASS_TO_ACTION_CLASS: dict[str, ActionClass] = {
    "read": CLASS_0_READ,
    "operational-write": CLASS_1_RECOVERY_WRITE,
}


def get(action_class_id: str) -> ActionClass | None:
    return BY_ID.get(action_class_id)


def console_refusal(action_class: ActionClass) -> str | None:
    """The refusal code the console must return for this class, or ``None``
    when the class is submittable. Kept here rather than in the route handler
    so the console cannot drift from the taxonomy it claims to enforce."""
    return None if action_class.console_submittable else action_class.refusal_code
