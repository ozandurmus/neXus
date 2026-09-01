"""CON.1 — the read-only console ASGI application.

Route table (AC-2/AC-3, enumerated deliberately so a later phase adding a
route must classify it):

    GET/HEAD  /                          unauthenticated  shell (templates/console.html)
    GET/HEAD  /assets/app.js             unauthenticated  compose_modules() (utils.html_export)
    GET/HEAD  /assets/style.css          unauthenticated  static/style.css
    GET/HEAD  /assets/console_actions.js unauthenticated  static/console_actions.js
    GET/HEAD  /api/payloads              authenticated    console.payloads.build_console_payloads()

No other route exists. No method other than GET/HEAD is exposed anywhere
(AC-2). This module imports no vendor/collector module, transitively (AC-8;
``console.payloads`` -> ``utils.html_export`` -> the same UI-payload builders
the exported report already uses, none of which touch ``checkpoint/*``,
``panorama/*`` or ``configuration/*``).
"""
from __future__ import annotations

from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse
from starlette.middleware.base import BaseHTTPMiddleware

from console.auth import extract_bearer_token, origin_is_trusted, token_matches
from console.payloads import build_console_payloads
from utils.html_export import compose_modules, read_text_file

# C1-1: the console's CSP is stricter than the exported report's — served as a
# real response header (not a <meta> tag), so frame-ancestors is honored here.
CONSOLE_CSP = (
    "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
    "img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
)

_REPO_ROOT = Path(__file__).resolve().parent.parent
_CONSOLE_TEMPLATE = _REPO_ROOT / "templates" / "console.html"


class _SecurityHeadersMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        response = await call_next(request)
        response.headers["Content-Security-Policy"] = CONSOLE_CSP
        return response


def create_app(*, runtime_paths, launch_token: str, bound_origin: str) -> FastAPI:
    app = FastAPI(
        title="SecurityExpert Operator Console",
        # No interactive API docs / schema routes — CON.1 privacy invariant 4
        # ("nothing under the recovery root is readable through any route")
        # and C1-7 ("no artifact discovery, no path input") both argue for the
        # smallest possible route table; auto-generated docs are surface area
        # this phase has no use for.
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.add_middleware(_SecurityHeadersMiddleware)

    def _require_api_auth(
        request: Request,
        authorization: str | None = Header(default=None),
        origin: str | None = Header(default=None),
        sec_fetch_site: str | None = Header(default=None, alias="Sec-Fetch-Site"),
    ) -> None:
        candidate = extract_bearer_token(authorization)
        if not token_matches(candidate, launch_token):
            raise HTTPException(status_code=401, detail="missing or invalid bearer token")
        if not origin_is_trusted(origin=origin, sec_fetch_site=sec_fetch_site, bound_origin=bound_origin):
            raise HTTPException(status_code=403, detail="cross-origin request rejected")

    # AC-2 is asserted by enumerating the ASGI route table, not by inspection —
    # every route below is explicit about supporting GET and HEAD only
    # (FastAPI's @app.get shorthand does not add HEAD in this Starlette
    # version, so it is spelled out via api_route instead of relied upon).
    @app.api_route("/", methods=["GET", "HEAD"], response_class=HTMLResponse)
    def get_shell() -> str:
        return read_text_file(_CONSOLE_TEMPLATE)

    @app.api_route("/assets/app.js", methods=["GET", "HEAD"], response_class=PlainTextResponse)
    def get_app_js() -> PlainTextResponse:
        return PlainTextResponse(
            compose_modules(repository_root=runtime_paths.repository_root),
            media_type="text/javascript",
        )

    @app.api_route("/assets/style.css", methods=["GET", "HEAD"], response_class=PlainTextResponse)
    def get_style_css() -> PlainTextResponse:
        style_path = Path(runtime_paths.repository_root) / "static" / "style.css"
        return PlainTextResponse(read_text_file(style_path), media_type="text/css")

    @app.api_route("/assets/console_actions.js", methods=["GET", "HEAD"], response_class=PlainTextResponse)
    def get_console_actions_js() -> PlainTextResponse:
        script_path = Path(runtime_paths.repository_root) / "static" / "console_actions.js"
        return PlainTextResponse(read_text_file(script_path), media_type="text/javascript")

    @app.api_route("/api/payloads", methods=["GET", "HEAD"], dependencies=[Depends(_require_api_auth)])
    def get_payloads() -> JSONResponse:
        return JSONResponse(build_console_payloads(runtime_paths))

    return app
