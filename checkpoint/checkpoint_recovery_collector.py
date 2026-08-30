"""SecurityExpert — RB.3 CP Gaia backup recovery collector (BLOCKED).

Contract §7.3 (`add backup local`, `operational-write` class): implementation
is blocked on open decision D3
(`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §13) — **not resolved**.
The P0 `cp_device_interaction_safety` audit that used to co-gate this closed
2026-08-25 (corrected 2026-08-30 — do not cite it as open again). Unlike the
PAN device-state export (§7.1, `read` class), this is `operational-write`: it
writes a multi-MB archive to the device's `/var/log`, which is a real outage
mode if it fills, and `AGENTS.md`'s "New CP commands require the
network-device command gate before implementation" applies at full force.

This module exists so target selection, admission routing, and the recovery
store are already wired correctly for CP — only the actual device call is
missing once D3 is decided and the command gate signs off.
"""
from __future__ import annotations

from typing import Any

from utils.recovery_collect import RecoveryCollectionBlockedError, RecoveryCollectionTarget

BLOCK_REASON = (
    "CP Gaia backup collection is blocked: open decision D3 (docs/design/"
    "BACKUP_AND_RECOVERY_ARCHITECTURE.md §13) is unresolved. The P0 "
    "cp_device_interaction_safety audit that used to co-gate this closed "
    "2026-08-25. See contract §7.3."
)


class CheckpointGaiaBackupCollector:
    """`RecoveryCollector` for CP Gaia backup. Every call is blocked."""

    def collect(self, target: RecoveryCollectionTarget) -> tuple[bytes, dict[str, Any]]:
        raise RecoveryCollectionBlockedError(BLOCK_REASON)
