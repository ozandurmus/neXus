"""Local runtime inventory exclusion policy.

Environment-specific inventory identities belong to RuntimeRoot, never to the
source repository.  This module is deliberately vendor-neutral; collectors
consume only the identities for their own vendor.
"""

from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
from typing import Iterable


POLICY_RELATIVE_PATH = Path("state") / "inventory_exclusions.json"
SUPPORTED_SCHEMA_VERSION = 1


class InventoryExclusionPolicyError(RuntimeError):
    """Raised when a local exclusion policy exists but is not safe to use."""


@dataclass(frozen=True)
class InventoryExclusion:
    vendor: str
    identity: str
    reason: str


@dataclass(frozen=True)
class InventoryExclusionPolicy:
    source: str
    entries: tuple[InventoryExclusion, ...]

    def identities_for(self, vendor: str) -> tuple[str, ...]:
        wanted = str(vendor or "").strip().lower()
        return tuple(entry.identity for entry in self.entries if entry.vendor == wanted)

    def count_for(self, vendor: str) -> int:
        return len(self.identities_for(vendor))


def policy_path(data_root: Path) -> Path:
    return Path(data_root) / POLICY_RELATIVE_PATH


def _validate_identity(value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        raise InventoryExclusionPolicyError("inventory exclusion identity must be a non-empty string")
    identity = value.strip()
    if len(identity) > 255 or any(ch in identity for ch in ("\x00", "\r", "\n")):
        raise InventoryExclusionPolicyError("inventory exclusion identity contains unsupported characters")
    return identity


def load_inventory_exclusions(data_root: Path) -> InventoryExclusionPolicy:
    """Load the local-only policy without logging or returning matched values."""
    path = policy_path(data_root)
    if not path.exists():
        return InventoryExclusionPolicy(source="missing", entries=())
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise InventoryExclusionPolicyError("inventory exclusion policy cannot be read safely") from exc

    if not isinstance(raw, dict) or raw.get("version") != SUPPORTED_SCHEMA_VERSION:
        raise InventoryExclusionPolicyError("inventory exclusion policy has an unsupported schema version")
    rows = raw.get("exclusions")
    if not isinstance(rows, list):
        raise InventoryExclusionPolicyError("inventory exclusion policy exclusions must be a list")

    entries: list[InventoryExclusion] = []
    seen: set[tuple[str, str]] = set()
    for row in rows:
        if not isinstance(row, dict):
            raise InventoryExclusionPolicyError("inventory exclusion entry must be an object")
        if row.get("enabled", True) is False:
            continue
        vendor = str(row.get("vendor") or "").strip().lower()
        if not vendor:
            raise InventoryExclusionPolicyError("inventory exclusion vendor is required")
        identity = _validate_identity(row.get("identity"))
        reason = str(row.get("reason") or "manual").strip() or "manual"
        key = (vendor, identity)
        if key in seen:
            continue
        seen.add(key)
        entries.append(InventoryExclusion(vendor=vendor, identity=identity, reason=reason))

    return InventoryExclusionPolicy(source="runtime-policy", entries=tuple(entries))


def checkpoint_transport_value(identities: Iterable[str]) -> str:
    """Encode exact CP object names for the existing comma-delimited shell hook.

    Commas are rejected rather than guessed/escaped because the remote shell
    collector intentionally performs exact-name matching with a simple format.
    """
    values = []
    for value in identities:
        identity = _validate_identity(value)
        if "," in identity:
            raise InventoryExclusionPolicyError(
                "Check Point exclusion identity cannot contain a comma with the current exact-match transport"
            )
        values.append(identity)
    return ",".join(values)
