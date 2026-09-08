"""SecurityExpert — recovery-key custody abstraction boundary.

backlog id `recovery_offhost_key_custody` (P0, target DEPLOY.1): the vault
master key that wraps every artifact's per-artifact DEK
(`utils/recovery_crypto.py`, `docs/design/BACKUP_RECOVERY_CONTRACTS.md`
§9.2) is today resolved from a local env var or a file on `data_root`
(`utils.recovery_store.get_or_create_vault_key`). That is a real local
implementation, not a stand-in for an off-host KMS/secret-manager.

`KeyCustodyBackend` is the extension point a future off-host backend plugs
into: callers exchange only a wrapped-DEK blob and a `key_id` fingerprint,
never raw master-key bytes. `LocalFileKeyCustodyBackend` is the only backend
that exists tonight — it holds the master key in-process (delegating vault
key resolution to `recovery_store.get_or_create_vault_key`, so both share one
tested key-resolution path) and satisfies the interface locally. A real
off-host backend (server-side KMS/secret-manager call under DEPLOY.1) is
**not implemented here** — there is no such infra available in this
environment — but it can implement `KeyCustodyBackend` without any change to
callers of this module.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path

from utils import recovery_crypto


class KeyCustodyError(Exception):
    """Raised on a key-custody resolution or wrap/unwrap failure."""


class KeyCustodyBackend(ABC):
    """Custody boundary for the recovery-vault master key.

    A backend alone holds the master key; it is never returned to a caller.
    Callers only ever see a wrapped-DEK blob (safe to store in a manifest)
    and an opaque `key_id` fingerprint (`recovery_crypto.key_id`).
    """

    @property
    @abstractmethod
    def key_id(self) -> str:
        """Opaque fingerprint of the active master key, safe to store in a manifest."""

    @abstractmethod
    def wrap_data_key(self, dek: bytes) -> str:
        """Wrap a per-artifact DEK under the master key this backend custodies."""

    @abstractmethod
    def unwrap_data_key(self, wrapped_data_key: str) -> bytes:
        """Unwrap a DEK previously sealed by `wrap_data_key` on this backend."""


class LocalFileKeyCustodyBackend(KeyCustodyBackend):
    """Local-only implementation: resolves the master key via
    `recovery_store.get_or_create_vault_key` (env override, else a 0600 file
    on `data_root`) and holds it in-process for the lifetime of this object.

    This is the feasible slice for tonight. It is not off-host and it is not
    a KMS — a real off-host backend needs DEPLOY.1 server infra that does
    not exist yet.
    """

    def __init__(
        self,
        data_root: Path,
        recovery_root: Path,
        *,
        environ: dict[str, str] | None = None,
    ) -> None:
        # Local import: avoids a module-load cycle (recovery_store does not
        # import this module).
        from utils import recovery_store

        try:
            key, key_id = recovery_store.get_or_create_vault_key(
                data_root, recovery_root, environ=environ
            )
        except recovery_store.RecoveryStoreError as exc:
            raise KeyCustodyError(str(exc)) from exc
        self._key = key
        self._key_id = key_id

    @property
    def key_id(self) -> str:
        return self._key_id

    def wrap_data_key(self, dek: bytes) -> str:
        return recovery_crypto.wrap_data_key(self._key, dek)

    def unwrap_data_key(self, wrapped_data_key: str) -> bytes:
        try:
            return recovery_crypto.unwrap_data_key(self._key, wrapped_data_key)
        except recovery_crypto.RecoveryCryptoError as exc:
            raise KeyCustodyError(str(exc)) from exc
