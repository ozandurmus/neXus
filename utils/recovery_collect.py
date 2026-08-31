"""SecurityExpert — RB.2/RB.3 recovery collection orchestration.

docs/design/BACKUP_RECOVERY_CONTRACTS.md §10; architecture §9.1. Product
direction (2026-08-30): recovery collection must not be logic inlined in
`main.py`. This module owns target selection, vendor dispatch and the
encrypt-and-store call; `main.py`, the scheduler
(`utils.collection_executor`), and any future UI-triggered action all call
`run_recovery_collection` identically — this is the single place that
decides "who gets collected".

Vendor collectors never touch this module's internals beyond implementing
`RecoveryCollector.collect`; this module never speaks PAN XML or CP Clish
itself (contract §10.2).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Mapping, Protocol, Sequence

from utils.restore_readiness import resolve_entity_id, resolve_vendor

_SELECTOR_MODES = {"all", "targets"}


class RecoveryCollectionError(Exception):
    """A request-time failure (e.g. an unresolvable explicit target) —
    raised before any device is contacted."""


class RecoveryAttestationError(Exception):
    """A per-endpoint attestation failure (bad address, session failure,
    credentials unavailable). Recorded against the endpoint; the batch
    continues (RB.3a correctness contract item 3)."""


class RecoveryCollectionBlockedError(Exception):
    """Raised by a vendor collector that is not yet gate-cleared. Carries
    the exact blocker so an operator or a future UI can show *why*, not
    just *that it failed* (contract §10.3)."""


class RecoveryCollectionSkipped(Exception):
    """A per-endpoint skip that is **not** a failure and **not** a block:
    the collector deliberately did nothing this run and that is the correct
    outcome. RB.3b B4 — the durable ``operational-write`` ledger already
    records an ``add backup local`` for this endpoint inside the 24 h window,
    so the run is skipped with zero device contact. Reported as status
    ``"skipped"``; it does not count toward ``failed_count``."""


@dataclass(frozen=True)
class RecoveryCollectionTarget:
    entity_id: str
    vendor: str
    row: Mapping[str, Any]  # the matched unified.json row, for collector use


@dataclass(frozen=True)
class RecoveryCollectionRequest:
    vendor: str                  # "panorama" | "checkpoint"
    selector: Mapping[str, Any]  # {"mode": "all"} | {"mode": "targets", "entity_ids": [...]}
    provenance: str = "manual"   # "manual" | "scheduled"


@dataclass
class RecoveryCollectionOutcome:
    entity_id: str
    status: str  # "collected" | "failed" | "blocked" | "skipped"
    artifact_id: str | None = None
    error: str | None = None


@dataclass
class RecoveryCollectionResult:
    request: RecoveryCollectionRequest
    outcomes: list[RecoveryCollectionOutcome] = field(default_factory=list)

    @property
    def collected_count(self) -> int:
        return sum(1 for o in self.outcomes if o.status == "collected")

    @property
    def skipped_count(self) -> int:
        return sum(1 for o in self.outcomes if o.status == "skipped")

    @property
    def failed_count(self) -> int:
        # A deliberate skip (RB.3b B4 — already backed up inside the 24 h
        # window) is a success-equivalent outcome, not a failure.
        return sum(1 for o in self.outcomes if o.status not in ("collected", "skipped"))


def select_recovery_targets(
    unified_devices: Sequence[Mapping[str, Any]],
    *,
    vendor: str,
    selector: Mapping[str, Any],
) -> list[RecoveryCollectionTarget]:
    """Resolve a request's selector against `unified.json`.

    `selector.mode == "all"`: every admitted device of `vendor`.
    `selector.mode == "targets"`: an explicit `entity_id` list -- the
    "selective for gateways" requirement. An entity_id that does not resolve
    is a `RecoveryCollectionError`, raised here, before any device is
    touched — never a silent skip.
    """
    mode = selector.get("mode")
    if mode not in _SELECTOR_MODES:
        raise RecoveryCollectionError(f"unknown selector mode: {mode!r} (expected one of {sorted(_SELECTOR_MODES)})")

    by_entity: dict[str, Mapping[str, Any]] = {}
    for row in unified_devices:
        if resolve_vendor(row) != vendor:
            continue
        entity_id = resolve_entity_id(row)
        if entity_id:
            by_entity[entity_id] = row

    if mode == "all":
        return [
            RecoveryCollectionTarget(entity_id=entity_id, vendor=vendor, row=row)
            for entity_id, row in sorted(by_entity.items())
        ]

    entity_ids = list(selector.get("entity_ids") or [])
    if not entity_ids:
        raise RecoveryCollectionError("selector.mode == 'targets' requires a non-empty entity_ids list")

    targets: list[RecoveryCollectionTarget] = []
    unresolved: list[str] = []
    for entity_id in entity_ids:
        row = by_entity.get(entity_id)
        if row is None:
            unresolved.append(entity_id)
            continue
        targets.append(RecoveryCollectionTarget(entity_id=entity_id, vendor=vendor, row=row))

    if unresolved:
        raise RecoveryCollectionError(
            f"unresolvable {vendor} entity_id(s) (not present in unified.json): {sorted(unresolved)}"
        )
    return targets


class RecoveryCollector(Protocol):
    def collect(self, target: RecoveryCollectionTarget) -> tuple[bytes, dict[str, Any]]:
        """Return `(plaintext_bytes, artifact_meta)`. `artifact_meta`
        supplies the `artifact.class` / `vendor_native_filename` /
        `collected_via` / `compression` fields §3 requires, plus
        `platform` / `software_version` / `ha_role` (and optionally
        `physical_endpoint`, `vsid`, `hostname_fingerprint`) for the
        `device` block. Raises `RecoveryCollectionBlockedError` if this
        vendor is not yet gate-cleared."""
        ...


def build_recovery_device_block(
    target: RecoveryCollectionTarget, meta: Mapping[str, Any]
) -> dict[str, Any]:
    """The ``manifest.device`` block for a collected artifact (contract §3),
    from the target identity plus the collector's returned ``meta``.

    One definition, shared by ``run_recovery_collection`` and by any collector
    that persists its own artifact **inside** ``collect()`` — RB.3b: the CP
    Gaia backup collector calls ``recovery_store.write_artifact`` within the
    admitted SSH session so the store write lands *before* the on-device
    archive is deleted (correctness contract rule 1)."""
    return {
        "vendor": target.vendor,
        "entity_id": target.entity_id,
        "physical_endpoint": meta.get("physical_endpoint", target.entity_id),
        "vsid": meta.get("vsid"),
        "hostname_fingerprint": meta.get("hostname_fingerprint", ""),
        "platform": meta["platform"],
        "software_version": meta.get("software_version") or "unknown",
        "ha_role": meta.get("ha_role", "unknown"),
    }


def run_recovery_collection(
    request: RecoveryCollectionRequest,
    *,
    unified_devices: Sequence[Mapping[str, Any]],
    collector: RecoveryCollector,
    recovery_paths,
    vault_key: bytes,
    vault_key_id: str,
    run_under_admission: Callable[[str, Callable[[], tuple[bytes, dict[str, Any]]]], tuple[bytes, dict[str, Any]]] | None = None,
) -> RecoveryCollectionResult:
    """Select targets, run each target's `collector.collect` under
    `run_under_admission` (the caller-supplied admission hook —
    `collection_executor.execute_admitted_collection`'s per-endpoint lock and
    concurrency budget, contract §9.12), then store a successful result via
    `utils.recovery_store.write_artifact`.

    `run_under_admission(entity_id, operation) -> operation()`'s result;
    `main.py` supplies the real one wired to `execute_admitted_collection`.
    Tests may pass a pass-through no-op, or one that raises to exercise a
    rejected/locked/budget-exhausted admission decision. `None` (the default)
    skips admission entirely -- only ever appropriate for a caller that
    already holds admission itself.

    A per-target failure (admission rejection, collector error, blocked
    vendor) is recorded in the result and does NOT abort the rest of the
    run — one gateway's failure must not silently drop the others (the same
    reasoning `utils.collection_executor` already applies per endpoint).
    """
    from utils.recovery_store import write_artifact  # local import: avoid a store<->collect cycle

    targets = select_recovery_targets(unified_devices, vendor=request.vendor, selector=request.selector)
    result = RecoveryCollectionResult(request=request)

    for target in targets:
        def _do_collect(target=target):
            return collector.collect(target)

        try:
            if run_under_admission is not None:
                plaintext, meta = run_under_admission(target.entity_id, _do_collect)
            else:
                plaintext, meta = _do_collect()
        except RecoveryCollectionSkipped as exc:
            result.outcomes.append(RecoveryCollectionOutcome(
                entity_id=target.entity_id, status="skipped", error=str(exc),
            ))
            continue
        except RecoveryCollectionBlockedError as exc:
            result.outcomes.append(RecoveryCollectionOutcome(
                entity_id=target.entity_id, status="blocked", error=str(exc),
            ))
            continue
        except Exception as exc:
            result.outcomes.append(RecoveryCollectionOutcome(
                entity_id=target.entity_id, status="failed", error=str(exc),
            ))
            continue

        # A collector that persisted its own artifact inside collect() — the CP
        # Gaia backup path, where the store write must land inside the admitted
        # SSH session and before the on-device archive is deleted — reports its
        # artifact_id here; the orchestrator does not write a second copy.
        stored_artifact_id = meta.get("stored_artifact_id")
        if stored_artifact_id:
            result.outcomes.append(RecoveryCollectionOutcome(
                entity_id=target.entity_id, status="collected",
                artifact_id=str(stored_artifact_id),
            ))
            continue

        device = build_recovery_device_block(target, meta)
        write_result = write_artifact(
            recovery_paths,
            vault_key=vault_key, vault_key_id=vault_key_id,
            device=device, artifact_class=meta["class"], plaintext=plaintext,
            vendor_native_filename=meta["vendor_native_filename"],
            collected_via=meta["collected_via"], compression=meta.get("compression", "none"),
        )
        result.outcomes.append(RecoveryCollectionOutcome(
            entity_id=target.entity_id, status="collected",
            artifact_id=write_result.manifest["artifact_id"],
        ))

    return result


# ---------------------------------------------------------------------------
# RB.3a — recovery *attestation* (a sibling of collection, not a variant of it)
#
# Contract: docs/history/phase/RB_3A_CP_GAIA_BACKUP_ATTESTATION.md, decision A2.
# `RecoveryCollector.collect()` returns `(bytes, meta)` and
# `run_recovery_collection` unconditionally calls `write_artifact`. An
# attestation has no plaintext: forcing it through that protocol would mean
# fabricating bytes. So this is a separate entry point that shares target
# selection, the admission hook and the batch-failure semantics, but has its
# own `RecoveryAttester` protocol and writes nothing to the recovery store.
# ---------------------------------------------------------------------------


class RecoveryAttester(Protocol):
    def classify_target(self, target: RecoveryCollectionTarget) -> str:
        """`"supported"` or `"unsupported"` — the platform gate (RB.3a A8).
        Local only: it must not contact the device (correctness item 1 —
        an A8-excluded target is never admitted, never contacted)."""
        ...

    def attest(self, target: RecoveryCollectionTarget) -> list[dict[str, Any]]:
        """Open one session, run the frozen read commands, and return
        attestation records `[{class, age_days, source}, ...]` — no artifact
        name, no payload. Raise on a session/address failure; the batch
        continues."""
        ...


@dataclass
class RecoveryAttestationOutcome:
    entity_id: str
    # "attested"      — >=1 device-reported artifact parsed
    # "no_evidence"   — session succeeded, nothing parsed (errored/empty/unknown format)
    # "unsupported"   — A8 platform gate: Spark / Gaia Embedded, no command sent
    # "skipped_virtual_system" — A3: a VS entity is never contacted
    # "failed"        — session/address failure (recorded; batch continues)
    status: str
    records: list = field(default_factory=list)
    error: str | None = None


@dataclass
class RecoveryAttestationResult:
    request: RecoveryCollectionRequest
    outcomes: list[RecoveryAttestationOutcome] = field(default_factory=list)

    @property
    def attested_count(self) -> int:
        return sum(1 for o in self.outcomes if o.status == "attested")

    @property
    def failed_count(self) -> int:
        return sum(1 for o in self.outcomes if o.status == "failed")

    def as_attestation_map(self) -> dict[str, list]:
        """`entity_id -> records`, ready for
        `restore_readiness.compute_restore_readiness(attestations=)` and for
        `data/state/recovery_attestations.json`. Only endpoints that actually
        attested something appear."""
        return {
            o.entity_id: o.records
            for o in self.outcomes
            if o.status == "attested" and o.records
        }


def run_recovery_attestation(
    request: RecoveryCollectionRequest,
    *,
    unified_devices: Sequence[Mapping[str, Any]],
    attester: RecoveryAttester,
    run_under_admission: Callable[[str, Callable[[], list[dict[str, Any]]]], list[dict[str, Any]]] | None = None,
) -> RecoveryAttestationResult:
    """Select targets, drop VS entities (A3) and platform-unsupported ones
    (A8), then run each remaining physical endpoint's `attester.attest`
    under `run_under_admission`. Writes nothing — the caller persists
    `result.as_attestation_map()`.

    One endpoint's failure is recorded and the batch continues (correctness
    item 3). An unresolvable explicit `--recovery-gateways` entry is a
    request-time `RecoveryCollectionError` from `select_recovery_targets`,
    before any device is contacted (correctness item 2)."""
    targets = select_recovery_targets(unified_devices, vendor=request.vendor, selector=request.selector)
    result = RecoveryAttestationResult(request=request)
    explicit = request.selector.get("mode") == "targets"

    for target in targets:
        # A3 — attestation is per physical endpoint. A VSX virtual-system
        # entity (`<device>__vsid_<vs_id>`) is never contacted and never
        # credited with its host's attestation.
        if "__vsid_" in target.entity_id:
            if explicit:
                result.outcomes.append(RecoveryAttestationOutcome(
                    entity_id=target.entity_id, status="skipped_virtual_system",
                    error="per physical endpoint only (contract §7.5 point 7)",
                ))
            continue

        # A8 — platform gate. UNSUPPORTED endpoints are never admitted and
        # never contacted (correctness item 1).
        if attester.classify_target(target) == "unsupported":
            result.outcomes.append(RecoveryAttestationOutcome(
                entity_id=target.entity_id, status="unsupported",
                error="platform UNSUPPORTED (Spark / Gaia Embedded); no command sent",
            ))
            continue

        def _do_attest(target=target):
            return attester.attest(target)

        try:
            if run_under_admission is not None:
                records = run_under_admission(target.entity_id, _do_attest)
            else:
                records = _do_attest()
        except Exception as exc:  # admission rejection, session failure, bad address
            result.outcomes.append(RecoveryAttestationOutcome(
                entity_id=target.entity_id, status="failed", error=str(exc),
            ))
            continue

        records = list(records or [])
        result.outcomes.append(RecoveryAttestationOutcome(
            entity_id=target.entity_id,
            status="attested" if records else "no_evidence",
            records=records,
        ))

    return result
