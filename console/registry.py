"""CON.2 C2-1 — the closed console job-type registry.

``JOB_REGISTRY`` is a module-level constant: not read from disk, not merged
with environment or policy, and not extensible at runtime. It is the only
vocabulary ``POST /api/jobs`` accepts (an unknown ``job_type`` is a 400,
C2-1/AC-3). Every entry either feeds ``utils.collection_executor.workflow_argv``
(``workflow`` must be in ``ALLOWLISTED_WORKFLOWS``) or is one of the two
explicit read-mode job types (``recovery_attest_cp``, ``report_rebuild``)
that main.py already exposes as their own dedicated flags, outside the
scheduler's workflow vocabulary.
"""
from __future__ import annotations

from dataclasses import dataclass

from utils.action_taxonomy import LEGACY_COMMAND_CLASS_TO_ACTION_CLASS, ActionClass


@dataclass(frozen=True)
class JobType:
    id: str
    label: str
    command_class: str            # legacy wire/persistence value: "read" | "operational-write"
    workflow: str                 # feeds workflow_argv(), or an explicit read mode name
    target_mode: str              # "none" | "entity_ids"
    vendor: str | None
    requires_confirmation: bool

    @property
    def action_class(self) -> ActionClass:
        """The `utils.action_taxonomy` class this job type belongs to.

        ``command_class`` stays the declared field because it is already on the
        wire and inside every durable job record; this property is the derived,
        authoritative view. The taxonomy — not a string comparison at a call
        site — decides whether a class may be submitted, so a future CLASS 2
        (failover) entry cannot be mistaken for the CLASS 1 recovery write that
        ``"operational-write"`` has always meant here.
        """
        return LEGACY_COMMAND_CLASS_TO_ACTION_CLASS[self.command_class]


JOB_REGISTRY: dict[str, JobType] = {
    jt.id: jt
    for jt in (
        JobType(
            id="inventory_refresh_cp",
            label="Refresh Check Point inventory",
            command_class="read",
            workflow="cp",
            target_mode="none",
            vendor="checkpoint",
            requires_confirmation=False,
        ),
        JobType(
            id="inventory_refresh_vsx",
            label="Refresh VSX inventory",
            command_class="read",
            workflow="vsx",
            target_mode="none",
            vendor="checkpoint",
            requires_confirmation=False,
        ),
        JobType(
            id="config_refresh_pan",
            label="Refresh PAN configuration",
            command_class="read",
            workflow="pan-config",
            target_mode="none",
            vendor="panorama",
            requires_confirmation=False,
        ),
        JobType(
            id="config_refresh_cp",
            label="Refresh Check Point configuration",
            command_class="read",
            workflow="cp-config",
            target_mode="none",
            vendor="checkpoint",
            requires_confirmation=False,
        ),
        JobType(
            id="recovery_attest_cp",
            label="Attest Check Point backups/snapshots",
            command_class="read",
            workflow="recovery-attest",
            target_mode="entity_ids",
            vendor="checkpoint",
            requires_confirmation=False,
        ),
        JobType(
            id="report_rebuild",
            label="Rebuild report",
            command_class="read",
            workflow="render-only",
            target_mode="none",
            vendor=None,
            requires_confirmation=False,
        ),
        JobType(
            id="cp_gaia_backup",
            label="Collect Check Point Gaia backup",
            command_class="operational-write",
            workflow="recovery-cp",
            target_mode="entity_ids",
            vendor="checkpoint",
            requires_confirmation=True,
        ),
    )
}

# The two explicit read-mode workflow names (C2-1) are not scheduler
# workflows and never go through utils.collection_executor.workflow_argv --
# console/runner.py builds their argv directly from a fixed template.
EXPLICIT_READ_MODES: frozenset[str] = frozenset({"recovery-attest", "render-only"})


def get_job_type(job_type_id: str) -> JobType | None:
    return JOB_REGISTRY.get(job_type_id)
