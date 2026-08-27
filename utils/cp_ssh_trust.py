"""SecurityExpert — CP SSH strict host-key trust policy helper.

Centralizes strict-host-key pre-connection preflight across CP MDS, VSX,
direct-SSH probe, and configuration-collector paths.

Contract (from PHASE0_6_4_CP_SSH_HOST_KEY_TRUST_PRODUCTION_CLOSURE.md):
- Strict enabled + no usable host keys → CpSshStrictPreflightError before
  any ssh.connect() call.
- Never exposes key material, endpoint identity, file paths or credentials.
- Compatibility mode (strict=False) applies AutoAddPolicy without preflight.
"""
from __future__ import annotations

import paramiko


class CpSshStrictPreflightError(Exception):
    """Raised before a connection attempt when strict host-key mode is
    enabled but no trusted host-key material was loaded.

    The message is value-free: no key data, endpoint address or path
    is included.  The caller must treat this as a hard transport failure
    and must not call ``ssh.connect()`` after this exception.
    """


def apply_strict_host_key_policy(ssh: paramiko.SSHClient, strict: bool) -> None:
    """Apply host-key policy to *ssh* and perform a pre-connection preflight.

    Parameters
    ----------
    ssh:
        A freshly-created ``paramiko.SSHClient`` that has not yet connected.
    strict:
        When ``True``, load system host keys, set ``RejectPolicy`` and verify
        that at least one trusted entry was loaded.  When ``False``, set
        ``AutoAddPolicy`` for compatibility mode.

    Raises
    ------
    CpSshStrictPreflightError
        Strict mode is enabled but ``load_system_host_keys()`` produced an
        empty host-key store.  The caller **must not** call ``ssh.connect()``
        after catching this exception.
    """
    if strict:
        ssh.load_system_host_keys()
        ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
        if not ssh.get_host_keys():
            raise CpSshStrictPreflightError(
                "strict_host_key_preflight_failed: no_usable_host_keys_loaded"
            )
    else:
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
