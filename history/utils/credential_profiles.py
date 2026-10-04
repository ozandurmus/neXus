"""Credential profile reference model -- `credential_profiles` (P1,
`project/backlog.json`).

`docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` section 6 has the Device
Registry hold only a **named credential profile reference**, resolved "in the
engine process only" to the existing `DEV.2.1`/`DEV.2.2` non-interactive
credential sources (env vars / mounted files) -- this module *is* that
reference model. A profile name maps onto a
`SECURITYEXPERT_CREDENTIAL_PROFILE_<NAME>_PRINCIPAL` / `_SECRET` variable
pair, read through the unmodified DEV.2.1/DEV.2.2 `<VAR>_FILE` > `<VAR>`
source (`utils.runtime_config_source.resolve_value`). No new
credential-sourcing mechanism is introduced and no vault abstraction exists
yet; that remains separately gated future scope.

`CredentialProfile` carries only a name -- by construction no field on it (or
anywhere in this module) can hold a secret value. `resolve_credential_profile`
returns a separate, unpersisted `ResolvedCredential`; this module never logs
or writes it, and callers must not persist it either.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from utils.logger import principal_fingerprint, register_sensitive_value
from utils.runtime_config_source import RuntimeConfigError, resolve_value

# Same bounded shape utils.device_registry already validates for
# DeviceRecord.credential_ref -- a profile name is that same reference, so
# both must accept identically.
_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")
_VAR_UNSAFE_RE = re.compile(r"[^A-Z0-9]")


class CredentialProfileError(RuntimeError):
    """A credential profile name is malformed, or its credential could not be
    resolved without interaction."""


@dataclass(frozen=True)
class CredentialProfile:
    """A named credential reference. No field here can ever hold a secret."""

    name: str

    def __post_init__(self) -> None:
        if not _NAME_RE.match(self.name):
            raise CredentialProfileError(f"invalid credential profile name: {self.name!r}")

    def _var_name(self, suffix: str) -> str:
        safe = _VAR_UNSAFE_RE.sub("_", self.name.upper())
        return f"SECURITYEXPERT_CREDENTIAL_PROFILE_{safe}_{suffix}"

    @property
    def principal_var(self) -> str:
        """DEV.2.1/DEV.2.2 variable name this profile's principal resolves through."""
        return self._var_name("PRINCIPAL")

    @property
    def secret_var(self) -> str:
        """DEV.2.1/DEV.2.2 variable name this profile's secret resolves through."""
        return self._var_name("SECRET")


@dataclass(frozen=True)
class ResolvedCredential:
    """A resolved principal/secret pair, in memory only for the life of the
    call that needed it. Never persisted; the module never logs it."""

    principal: str
    secret: str

    def __repr__(self) -> str:  # a stray log/print must never leak the secret
        return (
            f"ResolvedCredential(principal='[AUTH_PRINCIPAL:{principal_fingerprint(self.principal)}]', "
            "secret='[AUTH_SECRET:REDACTED]')"
        )

    __str__ = __repr__


def resolve_credential_profile(name: str) -> ResolvedCredential:
    """Resolve `name` to a principal/secret pair via the existing
    DEV.2.1/DEV.2.2 `<VAR>_FILE` > `<VAR>` source.

    Raises `CredentialProfileError` when `name` is malformed, either variable
    is unresolved, or a set `<VAR>_FILE` is unreadable/empty (the underlying
    `RuntimeConfigError` is wrapped, never swallowed).
    """
    profile = CredentialProfile(name)
    try:
        principal = resolve_value(profile.principal_var)
        secret = resolve_value(profile.secret_var)
    except RuntimeConfigError as exc:
        raise CredentialProfileError(str(exc)) from exc

    missing = [
        var
        for var, value in ((profile.principal_var, principal), (profile.secret_var, secret))
        if value is None
    ]
    if missing:
        raise CredentialProfileError(
            f"credential profile {name!r} unresolved: set "
            + ", ".join(f"{var} (or {var}_FILE)" for var in missing)
        )

    register_sensitive_value(principal, f"[AUTH_PRINCIPAL:{principal_fingerprint(principal)}]")
    register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")
    return ResolvedCredential(principal=principal, secret=secret)
