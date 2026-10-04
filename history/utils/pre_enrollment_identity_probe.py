"""M9 -- read-only, pre-registration identity probe.

Frozen contract: `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`
§9.1 conditions 9-12 (no device I/O in the enrollment request; first contact
as a queued CLASS 0 read-only job; positive-evidence identity; an explicit
identity preview), §8.2 (identity rules -- vendor/identity require positive
evidence, never a port/banner/endpoint-shape/operator-hint guess).

Distinct from, and NOT a wrapper around, `utils/first_contact_producer.py`
(M8.3): that function requires a `device_id` **already** enrolled as
`ENROLLED_UNVERIFIED` and matches its endpoint against
`checkpoint_config_collector._resolve_targets()`'s existing discovery-seam
output (itself hard-requiring a prior plane-wide collection run) -- it
verifies an *already-known* device, it cannot discover a brand-new one. M9's
frozen sequence requires probing an operator-typed endpoint **before** any
registry write exists (persistence is the last step, gated on positive
evidence -- no phantom devices). This module reuses the exact same low-level
primitives M8.3's producer uses (`lookup_trusted_host_key`, `_collect_host`,
the identical positive-evidence gate), directly against a synthetic,
unregistered target -- no registry lookup, no discovery-seam match, and no
`utils/device_identity_relationships.py` write (there is no `device_id` yet
for it to be keyed by).

`_collect_host` is reused entirely unmodified, including its "show
configuration" read: the identity gate this module (and M8.3) both check
(`identity_gate.accepted`) is `_collector_identity_gate`'s *second*, stronger
gate, which itself requires a successful configuration read as one of its
inputs -- "positive evidence" is not separable from that read in this
codebase's existing identity model. A governed, sanitized CP config evidence
snapshot is therefore a normal, accepted byproduct of a positive probe here,
exactly as it already is for M8.3 -- this module only ever returns the
sanitized preview below to its own caller, never the raw configuration.

Never writes to the Device Registry or to `device_identity_relationships`.
Never returns a raw serial, host-key fingerprint, raw command output, or
observed hostname -- only the closed, sanitized preview shape below.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any, Callable

from configuration import checkpoint_config_collector as cp_collector
from utils.config_evidence import ConfigEvidenceStore
from utils.cp_ssh_trust import lookup_trusted_host_key

#: A target that has no known role yet -- deliberately outside
#: `{"clusterxl_member", "vsx_host"}`, `_collect_host`'s only two
#: `entity_type`-branched code paths (the ClusterXL runtime-role read and a
#: `parent_name` label), so a pre-registration probe of an unclassified
#: endpoint safely skips both rather than guessing a role no evidence
#: supports yet.
_UNCLASSIFIED_ENTITY_TYPE = "unknown_pre_enrollment"
_UNCLASSIFIED_OBJECT_TYPE = "unknown"

#: Sanitized, value-free status tokens -- mirrors
#: `utils/first_contact_producer.py`'s own refusal-token vocabulary so the
#: two producers read as one family.
ENDPOINT_NOT_TRUSTED = "endpoint_not_trusted"
IDENTITY_GATE_REJECTED = "identity_gate_rejected"
IDENTITY_GATE_ACCEPTED_SERIAL_UNAVAILABLE = "identity_gate_accepted_serial_unavailable"
COLLECTOR_ERROR = "collector_error"
POSITIVE_IDENTITY = "positive_identity"


@dataclass(frozen=True)
class IdentityPreview:
    """Condition 12's "identity preview" -- the closed, sanitized shape
    exposed to an operator and, eventually, to the browser. Never the raw
    serial (presence is proven then discarded, "raw-evidence law", same as
    `utils/first_contact_producer.py`), never a host-key fingerprint, never
    raw command output, never the observed hostname."""

    platform_family: str | None
    platform_label: str | None
    platform_confidence: str | None
    model: str | None
    sw_version: str | None
    identity_gate_status: str | None
    identity_gate_confidence: str | None
    ha_role: str | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "platform_family": self.platform_family,
            "platform_label": self.platform_label,
            "platform_confidence": self.platform_confidence,
            "model": self.model,
            "sw_version": self.sw_version,
            "identity_gate_status": self.identity_gate_status,
            "identity_gate_confidence": self.identity_gate_confidence,
            "ha_role": self.ha_role,
        }


@dataclass(frozen=True)
class PreEnrollmentProbeOutcome:
    """Sanitized, value-free result of one probe call. ``preview`` is set
    only for ``POSITIVE_IDENTITY`` -- every refusal token carries ``None``."""

    status: str
    reason: str | None = None
    preview: IdentityPreview | None = None


def run_pre_enrollment_identity_probe(
    *,
    endpoint: str,
    port: int | None,
    resolve_credentials: Callable[[], "tuple[str, str]"],
    connect_timeout: int = 8,
    command_timeout: int = 20,
) -> PreEnrollmentProbeOutcome:
    """Execute the mandatory trust-before-credential sequence against one
    operator-supplied, not-yet-registered endpoint.

    ``endpoint`` must already be normalized by the caller
    (`utils.device_registry.normalize_endpoint`) -- this function does not
    re-derive normalization, same division of responsibility as
    `utils.cp_ssh_trust.lookup_trusted_host_key`. ``resolve_credentials`` is
    called at most once, and only after the trust lookup has already
    succeeded -- never before.
    """
    trust = lookup_trusted_host_key(endpoint, port)
    if not trust.trusted:
        return PreEnrollmentProbeOutcome(status=ENDPOINT_NOT_TRUSTED, reason=trust.reason)

    username, secret = resolve_credentials()

    target = cp_collector.PhysicalTarget(
        device=f"pre-enrollment-{uuid.uuid4().hex}",
        management_ip=endpoint,
        object_type=_UNCLASSIFIED_OBJECT_TYPE,
        entity_type=_UNCLASSIFIED_ENTITY_TYPE,
        selection_source="operator_manual_enrollment",
    )
    evidence_store = ConfigEvidenceStore()
    try:
        rows = cp_collector._collect_host(
            target,
            username=username,
            secret=secret,
            strict_host_key=True,
            connect_timeout=connect_timeout,
            command_timeout=command_timeout,
            store=evidence_store,
        )
    except Exception:
        return PreEnrollmentProbeOutcome(status=COLLECTOR_ERROR)
    finally:
        username = secret = None

    host_row = rows[0] if rows else {}
    identity = host_row.get("identity_gate") or {}
    if identity.get("accepted") is not True:
        return PreEnrollmentProbeOutcome(status=IDENTITY_GATE_REJECTED)

    serial = str(host_row.get("serial") or "").strip()
    if not serial:
        return PreEnrollmentProbeOutcome(status=IDENTITY_GATE_ACCEPTED_SERIAL_UNAVAILABLE)
    serial = None  # discarded immediately once presence is proven (raw-evidence law)

    platform = host_row.get("platform") or {}
    preview = IdentityPreview(
        platform_family=platform.get("family"),
        platform_label=platform.get("label"),
        platform_confidence=platform.get("confidence"),
        model=host_row.get("model"),
        sw_version=host_row.get("sw_version"),
        identity_gate_status=identity.get("status"),
        identity_gate_confidence=identity.get("confidence"),
        ha_role=host_row.get("ha_role"),
    )
    return PreEnrollmentProbeOutcome(status=POSITIVE_IDENTITY, preview=preview)
