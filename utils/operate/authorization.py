"""OP.2.0 authorization boundary (P2, §"Authorization boundary").

One call site, fail-closed, unconditional ``DENY`` until ``DEPLOY.1A``'s
OIDC boundary and ``OPERATE`` role exist. No environment variable, flag,
setting or runtime code path may choose a different ``Authorizer`` --
``ActionCoordinator`` takes it as a constructor argument and defaults to
``DenyAllAuthorizer`` (AC-16). A ``PERMIT``-returning implementation may
exist only under ``tests/`` --
``tests/test_op2_a_b_execution_foundation.py`` source-asserts that.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class AuthorizationDecision:
    permitted: bool
    reason_code: str


def deny(reason_code: str) -> AuthorizationDecision:
    return AuthorizationDecision(permitted=False, reason_code=reason_code)


class Authorizer(Protocol):
    def authorize(
        self, *, actor_ref: str, action_type: str, operational_entity_id: str
    ) -> AuthorizationDecision:
        ...


class DenyAllAuthorizer:
    """The only ``Authorizer`` permitted outside ``tests/``.

    Returns ``DENY`` for every actor, action type and entity, unconditionally
    -- "admin" (the console's per-launch bearer token) means nothing to this
    boundary. This is what keeps ``OP.2.A``/``OP.2.B`` safe to build and merge
    before ``DEPLOY.1A``: with this authorizer wired everywhere, no device is
    ever contacted regardless of what else exists.
    """

    def authorize(
        self, *, actor_ref: str, action_type: str, operational_entity_id: str
    ) -> AuthorizationDecision:
        return deny("authorization_not_configured")
