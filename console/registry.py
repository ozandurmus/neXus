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
    target_mode: str              # "none" | "entity_ids" | "device_ids" (M6) | "raw_endpoint" (M9)
    vendor: str | None
    requires_confirmation: bool
    #: M9 -- which POST route may submit this job type. Every existing entry
    #: defaults to the generic job API (unchanged behavior); the one M9
    #: identity-probe entry is "enrollment_probe_api" only, so `POST
    #: /api/jobs` can explicitly refuse it (400) if ever submitted there,
    #: keeping that route's "never an address" invariant intact even though
    #: this one job type's `target_mode` carries a raw endpoint.
    console_reachable_via: str = "generic_jobs_api"

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
            # M6 (registry_keyed_job_targets, Option D): registry-keyed
            # `device_id` targeting, admission shell only -- see
            # console/registry_targets.py. Every non-empty target set
            # currently refuses with IDENTITY_TRANSLATION_REQUIRED; a
            # target-free job stays M5's plane-wide behavior, unchanged.
            target_mode="device_ids",
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
        # M9 (LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md §9.1 condition 10 /
        # AC-EN-4): the pre-registration identity probe. CLASS 0 read, so it
        # is console-submittable with zero new taxonomy code -- but it is not
        # a scheduler workflow (`workflow` is never added to
        # ALLOWLISTED_WORKFLOWS) and it carries no vendor/collector CLI argv
        # at all: `console/runner.py::_execute` dispatches it directly to
        # `utils.pre_enrollment_identity_probe` via
        # `utils.collection_executor.execute_admitted_collection`, bypassing
        # `_build_argv`/`main.main()` entirely (condition 5, "no command/argv
        # input of any kind" -- there is no argv to construct). Only
        # `POST /api/enrollment/probe` may submit it (`console_reachable_via`);
        # the generic `POST /api/jobs` route refuses it explicitly.
        JobType(
            id="device_enrollment_identity_probe",
            label="Probe device identity (pre-enrollment)",
            command_class="read",
            workflow="device-enrollment-identity-probe",
            target_mode="raw_endpoint",
            vendor=None,
            requires_confirmation=False,
            console_reachable_via="enrollment_probe_api",
        ),
    )
}

# The two explicit read-mode workflow names (C2-1) are not scheduler
# workflows and never go through utils.collection_executor.workflow_argv --
# console/runner.py builds their argv directly from a fixed template.
EXPLICIT_READ_MODES: frozenset[str] = frozenset({"recovery-attest", "render-only"})


def get_job_type(job_type_id: str) -> JobType | None:
    return JOB_REGISTRY.get(job_type_id)
