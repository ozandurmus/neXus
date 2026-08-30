"""SecurityExpert — RB.1 recovery-plane envelope encryption.

AES-256-GCM envelope encryption: a random per-artifact Data Encryption Key
(DEK) encrypts the artifact; the DEK itself is encrypted ("wrapped") by a
vault master key that this module never persists and that
`utils/recovery_store.py` resolves separately from the recovery volume
(docs/design/BACKUP_RECOVERY_CONTRACTS.md §9.2). `SCHEME_ID` is stored per
manifest (`crypto.scheme`) so a future algorithm change is a recorded
migration, not a silent compatibility break.
"""
from __future__ import annotations

import base64
import hashlib
import secrets

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SCHEME_ID = "aes256gcm-envelope-v1"

KEY_BYTES = 32    # AES-256
_NONCE_BYTES = 12  # standard GCM nonce size


class RecoveryCryptoError(Exception):
    """Decryption/unwrap failure: wrong key, or tampered/corrupt ciphertext."""


def generate_key() -> bytes:
    return secrets.token_bytes(KEY_BYTES)


def key_id(key: bytes) -> str:
    """Opaque fingerprint safe to store in a manifest — never the key itself."""
    return hashlib.sha256(key).hexdigest()[:16]


def _seal(key: bytes, plaintext: bytes) -> bytes:
    nonce = secrets.token_bytes(_NONCE_BYTES)
    return nonce + AESGCM(key).encrypt(nonce, plaintext, None)


def _open(key: bytes, sealed: bytes) -> bytes:
    if len(sealed) < _NONCE_BYTES:
        raise RecoveryCryptoError("sealed blob shorter than the nonce")
    nonce, ciphertext = sealed[:_NONCE_BYTES], sealed[_NONCE_BYTES:]
    try:
        return AESGCM(key).decrypt(nonce, ciphertext, None)
    except Exception as exc:  # cryptography raises InvalidTag on tamper/wrong key
        raise RecoveryCryptoError("decryption failed: wrong key or tampered ciphertext") from exc


def encrypt_artifact(dek: bytes, plaintext: bytes) -> bytes:
    """Returns nonce||ciphertext — the exact bytes written to `artifact.enc`."""
    return _seal(dek, plaintext)


def decrypt_artifact(dek: bytes, sealed: bytes) -> bytes:
    return _open(dek, sealed)


def wrap_data_key(vault_key: bytes, dek: bytes) -> str:
    """Returns base64(nonce||ciphertext) — `manifest.crypto.wrapped_data_key`."""
    return base64.b64encode(_seal(vault_key, dek)).decode("ascii")


def unwrap_data_key(vault_key: bytes, wrapped_data_key: str) -> bytes:
    return _open(vault_key, base64.b64decode(wrapped_data_key.encode("ascii")))
