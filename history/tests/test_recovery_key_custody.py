"""Key-custody abstraction boundary — restore-drill round-trip.

backlog id `recovery_offhost_key_custody` (P0). Proves
`utils.recovery_key_custody.LocalFileKeyCustodyBackend` round-trips a DEK and
an artifact through the `KeyCustodyBackend` interface, including across a
simulated process restart (the shape a real off-host/KMS retrieval would
take: a fresh process resolves custody, then must still unwrap material
sealed by an earlier process). All key material here is synthetic and
generated fresh per test -- never a real credential.
"""
import pytest

from utils import recovery_crypto
from utils.recovery_key_custody import (
    KeyCustodyBackend,
    KeyCustodyError,
    LocalFileKeyCustodyBackend,
)
from utils.runtime_paths import resolve_recovery_root, resolve_runtime_paths

pytestmark = pytest.mark.recovery


def _paths(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    runtime = resolve_runtime_paths(str(tmp_path / "runtime"), environ={}, repository_root=repo)
    recovery = resolve_recovery_root(
        str(tmp_path / "recovery"), environ={}, repository_root=repo, runtime_root=runtime.runtime_root
    )
    return runtime, recovery


def test_local_backend_satisfies_the_interface(tmp_path):
    runtime, recovery = _paths(tmp_path)
    backend = LocalFileKeyCustodyBackend(runtime.data_root, recovery.recovery_root)
    assert isinstance(backend, KeyCustodyBackend)
    assert backend.key_id == recovery_crypto.key_id(backend._key)  # noqa: SLF001 -- test-only introspection


def test_restore_drill_wrap_seal_unwrap_decrypt_round_trip(tmp_path):
    """The full artifact path through the interface: generate a synthetic
    DEK, wrap it via custody, seal a synthetic artifact, "store" both blobs,
    then retrieve + unwrap + decrypt and recover the original bytes."""
    runtime, recovery = _paths(tmp_path)
    backend = LocalFileKeyCustodyBackend(runtime.data_root, recovery.recovery_root)

    plaintext = b"SYNTHETIC-RESTORE-DRILL-ARTIFACT-BYTES" * 25
    dek = recovery_crypto.generate_key()  # synthetic per-artifact key, never persisted raw
    sealed_artifact = recovery_crypto.encrypt_artifact(dek, plaintext)
    wrapped_dek = backend.wrap_data_key(dek)

    # "Store": only ciphertext + wrapped key + key_id would ever be written
    # to a manifest -- assert none of them expose the raw DEK or vault key.
    stored = {"sealed_artifact": sealed_artifact, "wrapped_dek": wrapped_dek, "key_id": backend.key_id}
    assert dek.hex() not in stored["wrapped_dek"]
    assert dek not in stored["sealed_artifact"]

    # "Retrieve" + restore.
    recovered_dek = backend.unwrap_data_key(stored["wrapped_dek"])
    recovered_plaintext = recovery_crypto.decrypt_artifact(recovered_dek, stored["sealed_artifact"])
    assert recovered_dek == dek
    assert recovered_plaintext == plaintext


def test_restore_drill_survives_a_simulated_process_restart(tmp_path):
    """Backend instance A wraps+seals (the "backup" side); a fresh backend
    instance B, constructed later against the same custody location (the
    "restore" side, as if a new process resolved custody again), must still
    unwrap and decrypt what A sealed."""
    runtime, recovery = _paths(tmp_path)
    backend_a = LocalFileKeyCustodyBackend(runtime.data_root, recovery.recovery_root)

    plaintext = b"SYNTHETIC-RESTART-DRILL-PAYLOAD" * 10
    dek = recovery_crypto.generate_key()
    sealed_artifact = recovery_crypto.encrypt_artifact(dek, plaintext)
    wrapped_dek = backend_a.wrap_data_key(dek)

    # Simulate a restart: nothing but the two on-disk paths carries over.
    backend_b = LocalFileKeyCustodyBackend(runtime.data_root, recovery.recovery_root)
    assert backend_b.key_id == backend_a.key_id

    recovered_dek = backend_b.unwrap_data_key(wrapped_dek)
    assert recovery_crypto.decrypt_artifact(recovered_dek, sealed_artifact) == plaintext


def test_unwrap_rejects_a_dek_wrapped_by_a_different_custody_backend(tmp_path):
    """Cross-custody tamper/misconfiguration guard: a DEK wrapped under one
    master key must not unwrap under an unrelated one."""
    runtime, recovery = _paths(tmp_path)
    backend_a = LocalFileKeyCustodyBackend(
        runtime.data_root, recovery.recovery_root,
        environ={"SECURITYEXPERT_RECOVERY_VAULT_KEY": recovery_crypto.generate_key().hex()},
    )
    backend_b = LocalFileKeyCustodyBackend(
        runtime.data_root, recovery.recovery_root,
        environ={"SECURITYEXPERT_RECOVERY_VAULT_KEY": recovery_crypto.generate_key().hex()},
    )
    assert backend_a.key_id != backend_b.key_id

    dek = recovery_crypto.generate_key()
    wrapped_dek = backend_a.wrap_data_key(dek)
    with pytest.raises(KeyCustodyError):
        backend_b.unwrap_data_key(wrapped_dek)


def test_env_override_key_length_error_surfaces_as_key_custody_error():
    with pytest.raises(KeyCustodyError, match="32 bytes"):
        LocalFileKeyCustodyBackend(
            "/tmp/does-not-matter-data", "/tmp/does-not-matter-recovery",
            environ={"SECURITYEXPERT_RECOVERY_VAULT_KEY": "ab"},
        )
