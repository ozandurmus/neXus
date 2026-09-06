"""SecurityExpert — CP SSH strict host-key trust policy helper.

Centralizes strict-host-key pre-connection preflight across CP MDS, VSX,
direct-SSH probe, configuration-collector and persistent-secret-material
paths.  Every CP SSH client path goes through ``apply_strict_host_key_policy``;
no caller sets a host-key policy or loads a host-key store on its own.

Contract (from PHASE0_6_4_CP_SSH_HOST_KEY_TRUST_PRODUCTION_CLOSURE.md):
- Strict enabled + no usable trusted host keys → CpSshStrictPreflightError
  before any ssh.connect() call.
- Never exposes key material, endpoint identity, file paths or credentials.
- Compatibility mode (strict=False) applies AutoAddPolicy without preflight.

Strict invariant (OP.0b S8-P0.1 correction)::

    trusted keys loaded            -> the read-only *system* store
    RejectPolicy installed         -> no missing-key acceptance, no TOFU
    preflight confirms trusted-key material exists
    only then may ssh.connect() occur

Paramiko keeps two host-key stores on ``SSHClient``: a read-only *system*
store populated by ``load_system_host_keys()`` and a writable *local* store
populated by ``load_host_keys()`` / ``AutoAddPolicy`` and exposed by
``get_host_keys()``.  ``connect()`` consults both.  The original 0.6.4 helper
loaded the former and gated on the latter, so strict mode failed even with
a correctly provisioned ``known_hosts`` (S8-P0.1).  The corrected preflight
counts what was actually loaded into the trusted store.

Implementation note — no Paramiko private internals.  Paramiko exposes no
public accessor for the system store, so the helper does not read
``ssh._system_host_keys``.  Instead it names the trusted source explicitly
(the OpenSSH user ``known_hosts`` path, which is exactly what
``load_system_host_keys()`` resolves when called without a filename — the
existing deployment/user-profile contract, ``/root/.ssh/known_hosts`` in the
production container, the operator profile on Windows), loads it into the
client's system store through the public ``load_system_host_keys(filename)``
API, and derives the cardinality by parsing the same file with the public
``paramiko.HostKeys`` parser.  Both loads use the same parser over the same
bytes, so the count is the count of what the client will verify against;
``tests/test_op0b_s8_p01_cp_ssh_trust_preflight_correction.py`` pins that
equivalence against real Paramiko.  Naming the file explicitly also turns
Paramiko's silent "file could not be read" masking into a fail-closed,
value-free preflight failure.

There is no explicit RuntimeRoot trusted-known_hosts source in the 0.6.4
policy as implemented (strict mode is selected by the existing environment
toggles; the trusted source is the system ``known_hosts``).  This module
does not introduce one.
"""
from __future__ import annotations

import base64
import hashlib
import os
from dataclasses import dataclass, field
from types import MappingProxyType

import paramiko
from paramiko.hostkeys import InvalidHostKey

# The OpenSSH user known_hosts location.  This is the same expression
# ``paramiko.SSHClient.load_system_host_keys()`` uses when no filename is
# given ("the user's local known hosts file, as used by OpenSSH");
# ``os.path.expanduser`` resolves it per platform (HOME on POSIX, the user
# profile on Windows) without any product-side platform branching.
_SYSTEM_KNOWN_HOSTS = "~/.ssh/known_hosts"

# Value-free preflight reason tokens.  None of them may ever carry a path,
# a host identity, a fingerprint or key material.
REASON_TRUST_SOURCE_UNREADABLE = "trust_source_unreadable"
REASON_TRUST_SOURCE_MALFORMED = "trust_source_malformed"
REASON_NO_USABLE_HOST_KEYS = "no_usable_host_keys_loaded"

# Endpoint-specific lookup outcome tokens (M8.2, contract §4 step 1).  Also
# value-free: no endpoint, hostname or key material.
REASON_ENDPOINT_NOT_TRUSTED = "endpoint_not_trusted"

# The port at which OpenSSH/Paramiko's known_hosts entries are keyed by the
# bare host, matching paramiko.client.SSHClient.connect()'s own
# ``server_hostkey_name`` derivation (port == this -> bare host; otherwise
# ``"[host]:port"``) so this lookup consults exactly the entry connect()
# itself would consult.
_DEFAULT_SSH_PORT = 22


class HostKeyNotTrustedError(paramiko.SSHException):
    """Raised in place of Paramiko's generic ``SSHException`` when
    ``RejectPolicy`` refuses a host key with no entry in the trusted store
    at all (OP.0b S8 real-env finding, follow-up to S8-P0.1).

    Paramiko's own ``RejectPolicy.missing_host_key`` raises a bare
    ``paramiko.SSHException`` -- indistinguishable, to a caller's ``except``
    clause, from a transient transport failure.  A host-key trust refusal
    is a security decision and must never enter a connection retry loop,
    but "untrusted key" and "transient failure" were not structurally
    distinguishable without parsing exception text.  This type makes that
    distinction structural, at the one shared seam
    (``apply_strict_host_key_policy``) every CP SSH caller already goes
    through -- no caller re-implements its own classification.  A key
    *mismatch* (an entry exists but disagrees) is already the separate,
    distinct ``paramiko.BadHostKeyException`` and is unaffected.
    """


class _NonRetryableRejectPolicy(paramiko.RejectPolicy):
    """``RejectPolicy`` whose missing-host-key rejection is deterministically
    classifiable as non-retryable, without parsing exception text.

    ``isinstance(policy, paramiko.RejectPolicy)`` still holds (it *is* one);
    only the exception type raised on rejection changes.
    """

    def missing_host_key(self, client, hostname, key):
        try:
            super().missing_host_key(client, hostname, key)
        except paramiko.SSHException:
            # Paramiko's own message embeds the hostname ("Server %r not
            # found in known_hosts") -- value-free law forbids that leaking
            # into a caller's exception chain/traceback, so this is
            # deliberately not chained (``from None``), same precedent as
            # CpSshStrictPreflightError above.
            raise HostKeyNotTrustedError("host_key_not_trusted") from None


class CpSshStrictPreflightError(Exception):
    """Raised before a connection attempt when strict host-key mode is
    enabled but no trusted host-key material could be loaded.

    The message is value-free: no key data, endpoint address or path
    is included.  ``reason`` carries the safe category token.  The caller
    must treat this as a hard transport failure and must not call
    ``ssh.connect()`` after this exception.
    """

    def __init__(self, reason: str = REASON_NO_USABLE_HOST_KEYS) -> None:
        self.reason = reason
        super().__init__(f"strict_host_key_preflight_failed: {reason}")


def _system_known_hosts_path() -> str:
    return os.path.expanduser(_SYSTEM_KNOWN_HOSTS)


def load_trusted_host_keys(ssh: paramiko.SSHClient) -> int:
    """Load every configured trusted host-key source into *ssh*'s read-only
    system store and return the number of trusted host entries loaded.

    The only configured trusted source is the system/user ``known_hosts``
    (see module docstring).  Nothing is ever written back, enrolled or
    fetched from the network.

    Raises
    ------
    CpSshStrictPreflightError
        ``trust_source_unreadable`` when the source is missing or cannot be
        opened; ``trust_source_malformed`` when it cannot be parsed as an
        OpenSSH ``known_hosts`` file.  The original exception is deliberately
        not chained (``from None``): Paramiko's parse errors carry the
        offending line, i.e. key material, which must never reach a log.
    """
    path = _system_known_hosts_path()
    try:
        ssh.load_system_host_keys(path)
        trusted = paramiko.HostKeys()
        trusted.load(path)
    except (InvalidHostKey, ValueError, paramiko.SSHException):
        raise CpSshStrictPreflightError(REASON_TRUST_SOURCE_MALFORMED) from None
    except OSError:
        raise CpSshStrictPreflightError(REASON_TRUST_SOURCE_UNREADABLE) from None
    return len(trusted)


def apply_strict_host_key_policy(ssh: paramiko.SSHClient, strict: bool) -> None:
    """Apply host-key policy to *ssh* and perform a pre-connection preflight.

    Parameters
    ----------
    ssh:
        A freshly-created ``paramiko.SSHClient`` that has not yet connected.
    strict:
        When ``True``, load the trusted (system) host keys, set
        ``RejectPolicy`` and verify that at least one trusted entry was
        loaded.  When ``False``, set ``AutoAddPolicy`` for compatibility
        mode (pre-existing behavior, unchanged).

    Raises
    ------
    CpSshStrictPreflightError
        Strict mode is enabled but no trusted host-key entry was loaded
        (source unreadable, malformed, or empty).  The caller **must not**
        call ``ssh.connect()`` after catching this exception.
    """
    if strict:
        loaded = load_trusted_host_keys(ssh)
        ssh.set_missing_host_key_policy(_NonRetryableRejectPolicy())
        if loaded < 1:
            raise CpSshStrictPreflightError(REASON_NO_USABLE_HOST_KEYS)
    else:
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())


def host_key_fingerprint(key: paramiko.PKey) -> str:
    """The single, shared ``SHA256:<base64>`` host-key fingerprint format.

    This is the one implementation: ``configuration/checkpoint_config_probe.
    py`` imports and calls this function for its own live-connection
    fingerprint (``_connect()``) rather than defining a second copy, so a
    later M8 slice comparing a live fingerprint against this module's
    ``lookup_trusted_host_key`` result is comparing two outputs of the same
    code path, not two independently-implemented formats that happen to
    agree today."""
    digest = hashlib.sha256(key.asbytes()).digest()
    return "SHA256:" + base64.b64encode(digest).decode("ascii").rstrip("=")


def _known_hosts_lookup_key(endpoint: str, port: int | None) -> str:
    """The exact string a ``known_hosts`` entry for *endpoint*:*port* is
    keyed under (mirrors ``paramiko.client.SSHClient.connect()``'s own
    ``server_hostkey_name`` derivation, so this lookup consults exactly the
    entry a real ``ssh.connect()`` to the same endpoint/port would)."""
    if port is None or port == _DEFAULT_SSH_PORT:
        return endpoint
    return f"[{endpoint}]:{port}"


@dataclass(frozen=True)
class TrustedKeyLookupResult:
    """Typed, fail-closed outcome of :func:`lookup_trusted_host_key`.

    ``trusted`` is the only field later M8 slices may act on as a gate
    decision. ``fingerprints`` is the minimum evidence a later slice needs
    (the ``SHA256:``-fingerprint, via :func:`host_key_fingerprint`, of every
    key type already trusted for this exact endpoint/port) -- never the raw
    key material, never populated when ``trusted`` is ``False``. It is a
    read-only ``MappingProxyType``, never a plain ``dict``: this result is
    handed to later M8 slices as evidence of what was trusted at lookup
    time, and a caller mutating it in place would silently corrupt that
    evidence for anyone else holding the same result. ``reason`` is a
    value-free token, populated only when ``trusted`` is ``False``.
    """

    trusted: bool
    reason: str | None
    fingerprints: MappingProxyType[str, str] = field(
        default_factory=lambda: MappingProxyType({})
    )

    def __post_init__(self) -> None:
        object.__setattr__(self, "fingerprints", MappingProxyType(dict(self.fingerprints)))


def lookup_trusted_host_key(endpoint: str, port: int | None) -> TrustedKeyLookupResult:
    """Local-only, read-only check: does the exact normalized *endpoint*
    (already normalized by the caller -- e.g.
    ``utils.device_registry.normalize_endpoint``) at *port* already have a
    trusted host-key entry in the same ``known_hosts`` source
    ``apply_strict_host_key_policy`` reads (see module docstring)?

    This is `M8` contract §4 step 1: the mandatory, blocking check that must
    precede credential resolution for a new first-contact target. It never
    opens a socket, never contacts a device, never adds or accepts a key,
    and never performs TOFU -- a miss is a hard refusal, not an invitation
    to trust on first use.

    Parameters
    ----------
    endpoint:
        Already-normalized host/address (the caller's responsibility -- this
        function does not re-derive normalization; that authority stays
        with ``utils.device_registry.normalize_endpoint``).
    port:
        Explicit port, or ``None`` for the default SSH port -- the same
        shape ``normalize_endpoint`` returns.

    Returns
    -------
    TrustedKeyLookupResult
        ``trusted=False`` (with a value-free ``reason``) for: an empty
        *endpoint*, an unreadable/malformed trust source (mirroring
        :func:`load_trusted_host_keys`'s own fail-closed reasons), or no
        entry for the exact endpoint/port. ``trusted=True`` with the
        fingerprint of every key type already trusted for that exact
        endpoint/port otherwise.
    """
    if not isinstance(endpoint, str) or not endpoint.strip():
        return TrustedKeyLookupResult(trusted=False, reason=REASON_ENDPOINT_NOT_TRUSTED)

    path = _system_known_hosts_path()
    trusted = paramiko.HostKeys()
    try:
        trusted.load(path)
    except (InvalidHostKey, ValueError, paramiko.SSHException):
        return TrustedKeyLookupResult(trusted=False, reason=REASON_TRUST_SOURCE_MALFORMED)
    except OSError:
        return TrustedKeyLookupResult(trusted=False, reason=REASON_TRUST_SOURCE_UNREADABLE)

    lookup_key = _known_hosts_lookup_key(endpoint, port)
    entry = trusted.lookup(lookup_key)
    if not entry:
        return TrustedKeyLookupResult(trusted=False, reason=REASON_ENDPOINT_NOT_TRUSTED)

    fingerprints = {
        key_type: host_key_fingerprint(key)
        for key_type, key in entry.items()
        if key is not None
    }
    if not fingerprints:
        return TrustedKeyLookupResult(trusted=False, reason=REASON_ENDPOINT_NOT_TRUSTED)
    return TrustedKeyLookupResult(trusted=True, reason=None, fingerprints=fingerprints)
