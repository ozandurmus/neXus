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


class RecoveryCollectionBlockedError(Exception):
    """Raised by a vendor collector that is not yet gate-cleared. Carries
    the exact blocker so an operator or a future UI can show *why*, not
    just *that it failed* (contract §10.3)."""


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
    status: str  # "collected" | "failed" | "blocked"
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
    def failed_count(self) -> int:
        return sum(1 for o in self.outcomes if o.status != "collected")


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

        device = {
            "vendor": target.vendor,
            "entity_id": target.entity_id,
            "physical_endpoint": meta.get("physical_endpoint", target.entity_id),
            "vsid": meta.get("vsid"),
            "hostname_fingerprint": meta.get("hostname_fingerprint", ""),
            "platform": meta["platform"],
            "software_version": meta.get("software_version") or "unknown",
            "ha_role": meta.get("ha_role", "unknown"),
        }
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
