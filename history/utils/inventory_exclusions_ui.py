"""Inventory Exclusions UI payload — 0.6.1C / Inventory UX, phase 1 (read-only).

Builds a sanitized, additive UI payload from an already-loaded
``InventoryExclusionPolicy`` (see ``utils.inventory_exclusions``). This module
performs no I/O and no device access — it is a pure projection over an
already-loaded, already-sanitized in-memory policy, modeled directly on
``utils.discovery_capability_ui``'s shape.

Privacy contract
-----------------
* Only ``vendor`` + the excluded ``identity`` string + an optional ``reason``
  are ever emitted — the same fields ``InventoryExclusion`` already stores
  locally. No credentials, raw configuration or management IP ever enter this
  payload.
* An absent/empty policy renders an explicit empty-state payload rather than
  raising, matching ``load_inventory_exclusions()``'s fail-open-to-empty
  behavior.

Phase 1 only: this module is strictly read-only. Add/restore/reason/audit
write workflows are a separate, later, DEPLOY.1A-adjacent contract — see
``docs/history/phase/PHASE0_6_1C_INVENTORY_EXCLUSIONS_UI.md``.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from utils.inventory_exclusions import InventoryExclusionPolicy


EXCLUSIONS_UI_SCHEMA_VERSION = "0.6.1C"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def build_inventory_exclusions_payload(
    policy: InventoryExclusionPolicy | None = None,
) -> dict[str, Any]:
    """Build the sanitized, read-only Inventory Exclusions UI payload.

    ``policy`` is optional; omitting it (or passing a policy with no entries,
    e.g. ``source="missing"`` when no local policy file exists) yields an
    explicit empty-state payload rather than raising.
    """
    entries = policy.entries if policy is not None else ()
    source = policy.source if policy is not None else "missing"

    vendor_counts: dict[str, int] = {}
    entities: list[dict[str, Any]] = []
    for entry in entries:
        vendor_counts[entry.vendor] = vendor_counts.get(entry.vendor, 0) + 1
        entities.append({
            "vendor": entry.vendor,
            "identity": entry.identity,
            "reason": entry.reason,
        })

    entities.sort(key=lambda row: (row["vendor"], row["identity"]))

    return {
        "schema_version": EXCLUSIONS_UI_SCHEMA_VERSION,
        "generated_at": _utc_now(),
        "source": source,
        "fleet_summary": {
            "total_exclusions": len(entities),
            "vendor_counts": vendor_counts,
        },
        "entities": entities,
    }
