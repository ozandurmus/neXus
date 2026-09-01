"""CON.1 C1-5 — cookieless bearer authentication, token in the URL fragment.

Per ``docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`` §7.2 / ``C-D2``: a
per-launch random token, never written to a file, never logged (registered
with the redaction registry so it cannot appear in any log line even by
accident), compared with ``hmac.compare_digest`` to stay constant-time.

``GET /`` and ``/assets/*`` are unauthenticated by design (C1-6) — the first
request cannot carry a header, since the token only exists in the URL
fragment, which browsers never send to the server. Every route that can
return evidence (``/api/*``) is authenticated.
"""
from __future__ import annotations

import hmac
import secrets

from utils.logger import register_sensitive_value

_BEARER_PREFIX = "Bearer "


def generate_launch_token() -> str:
    """A 256-bit URL-safe token for this process's lifetime only."""
    token = secrets.token_urlsafe(32)
    register_sensitive_value(token)
    return token


def extract_bearer_token(authorization_header: str | None) -> str | None:
    if not authorization_header or not authorization_header.startswith(_BEARER_PREFIX):
        return None
    return authorization_header[len(_BEARER_PREFIX):]


def token_matches(candidate: str | None, expected: str) -> bool:
    return hmac.compare_digest((candidate or "").encode("utf-8"), expected.encode("utf-8"))


def origin_is_trusted(*, origin: str | None, sec_fetch_site: str | None, bound_origin: str) -> bool:
    """AC-6: reject cross-origin ``/api/*`` requests even with a valid token.

    Both headers are optional (older/non-browser clients may send neither);
    only a header that is *present and wrong* fails the check.
    """
    if sec_fetch_site is not None and sec_fetch_site != "same-origin":
        return False
    if origin is not None and origin != bound_origin:
        return False
    return True
