"""CON.1/CON.2 — the operator console ASGI application.

Route table (AC-2/AC-3, enumerated deliberately so a later phase adding a
route must classify it):

    GET/HEAD  /                          unauthenticated  shell (templates/console.html)
    GET/HEAD  /assets/app.js             unauthenticated  compose_modules() (utils.html_export)
    GET/HEAD  /assets/style.css          unauthenticated  static/style.css
    GET/HEAD  /assets/console_actions.js unauthenticated  static/console_actions.js
    GET/HEAD  /api/payloads              authenticated    console.payloads.build_console_payloads()
    GET/HEAD  /api/job-types             authenticated    console.registry.JOB_REGISTRY (CON.2)
    GET/HEAD  /api/jobs                  authenticated    console.jobs.ConsoleJobStore.list_all() (CON.2)
    POST      /api/jobs                  authenticated    submit a read-class job (CON.2, C2-6/C2-9)
    GET/HEAD  /api/jobs/{job_id}         authenticated    one job record (CON.2)
    GET       /api/jobs/{job_id}/events  authenticated    SSE job-state stream (CON.2, C2-10)

No other route exists. No method other than GET/HEAD/POST is exposed
anywhere, and POST exists only on ``/api/jobs`` (AC-2). This module imports
no vendor/collector module, transitively (AC-8; ``console.payloads`` ->
``utils.html_export`` -> the same UI-payload builders the exported report
already uses; ``console.jobs``/``console.runner`` -> ``main.main()`` only,
never a collector or vendor module directly, same invariant CON.1
established).
"""
from __future__ import annotations

import json
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, StreamingResponse
from starlette.middleware.base import BaseHTTPMiddleware

from console.auth import extract_bearer_token, origin_is_trusted, token_matches
from console.jobs import TERMINAL_STATES, ConsoleJobStore
from console.payloads import build_console_payloads
from console.registry import JOB_REGISTRY, get_job_type
from console.runner import ConsoleJobRunner
from utils.action_taxonomy import console_refusal
from utils.html_export import compose_modules, read_text_file
from utils.restore_readiness import resolve_entity_id

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


def _known_entity_ids(unified_path: Path) -> set[str]:
    """C2-2/AC-3: the same identity resolver every recovery/config/inventory
    path uses, so an ``entity_id`` accepted here is guaranteed to resolve the
    same way inside ``main.main()``. A missing/unreadable ``unified.json``
    means no entity_id resolves -- the same fail-closed posture
    ``select_recovery_targets`` already has."""
    try:
        rows = json.loads(unified_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return set()
    if not isinstance(rows, list):
        return set()
    return {resolve_entity_id(row) for row in rows if isinstance(row, dict) and resolve_entity_id(row)}


def create_app(
    *,
    runtime_paths,
    launch_token: str,
    bound_origin: str,
    job_store: ConsoleJobStore | None = None,
    runner: ConsoleJobRunner | None = None,
) -> FastAPI:
    """``job_store``/``runner`` are optional so a caller that only needs the
    read-only CON.1 surface (or a test exercising route wiring in isolation)
    can omit them; ``console.server.run_console`` always passes its own
    process-lifetime instances so admission state stays consistent across
    every job (CON.2)."""
    if job_store is None:
        job_store = ConsoleJobStore(runtime_paths.data_root)
    if runner is None:
        from utils.collection_executor import CollectionCoordinator, RuntimeCollectionServices

        runner = ConsoleJobRunner(
            job_store=job_store,
            runtime_paths=runtime_paths,
            services=RuntimeCollectionServices(coordinator=CollectionCoordinator()),
        )
        runner.start()

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

    # -- CON.2: job engine + read-class actions --------------------------

    @app.api_route("/api/job-types", methods=["GET", "HEAD"], dependencies=[Depends(_require_api_auth)])
    def get_job_types() -> JSONResponse:
        return JSONResponse([
            {
                "id": jt.id,
                "label": jt.label,
                "command_class": jt.command_class,
                "target_mode": jt.target_mode,
                "vendor": jt.vendor,
                "requires_confirmation": jt.requires_confirmation,
                # C2-6: the UI renders an honest BLOCKED state instead of a
                # button that would 409 on click. The block decision and its
                # reason both come from utils.action_taxonomy, so this surface
                # cannot drift from the taxonomy it claims to enforce.
                "action_class": jt.action_class.id,
                "action_class_level": jt.action_class.level,
                "blocked": console_refusal(jt.action_class) is not None,
                "blocked_reason": console_refusal(jt.action_class),
            }
            for jt in JOB_REGISTRY.values()
        ])

    @app.api_route("/api/jobs", methods=["GET", "HEAD"], dependencies=[Depends(_require_api_auth)])
    def get_jobs() -> JSONResponse:
        return JSONResponse([record.to_dict() for record in job_store.list_all()])

    @app.post("/api/jobs", dependencies=[Depends(_require_api_auth)])
    async def post_job(
        request: Request,
        idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    ) -> JSONResponse:
        if not idempotency_key:
            raise HTTPException(status_code=400, detail="missing Idempotency-Key header")
        try:
            body = await request.json()
        except Exception:
            raise HTTPException(status_code=400, detail="request body must be JSON")
        if not isinstance(body, dict):
            raise HTTPException(status_code=400, detail="request body must be a JSON object")

        job_type_id = body.get("job_type")
        job_type = get_job_type(job_type_id) if isinstance(job_type_id, str) else None
        if job_type is None:
            raise HTTPException(status_code=400, detail=f"unknown job_type: {job_type_id!r}")

        targets = body.get("targets") or []
        if not isinstance(targets, list) or not all(isinstance(t, str) and t for t in targets):
            raise HTTPException(status_code=400, detail="targets must be a list of non-empty strings")

        if job_type.target_mode == "entity_ids":
            known = _known_entity_ids(Path(runtime_paths.output_root) / "unified.json")
            unresolved = sorted(t for t in targets if t not in known)
            if unresolved:
                raise HTTPException(
                    status_code=400,
                    detail=f"unresolvable entity_id(s) (not present in unified.json): {unresolved}",
                )
        elif targets:
            raise HTTPException(status_code=400, detail=f"job_type {job_type.id!r} does not accept targets")

        # C2-6: anything above CLASS 0 is a deliberate staging gate, refused
        # here before any job record is even created. The refusal code names
        # the actual class, so a CLASS 1 recovery write and a future CLASS 2
        # operational state change do not report the same reason.
        refusal = console_refusal(job_type.action_class)
        if refusal is not None:
            raise HTTPException(
                status_code=409,
                detail={"error": refusal, "action_class": job_type.action_class.id},
            )

        record, is_new = job_store.submit(
            job_type=job_type.id,
            command_class=job_type.command_class,
            targets=targets,
            idempotency_key=idempotency_key,
        )
        if is_new:
            runner.enqueue(record.job_id)
        return JSONResponse(record.to_dict())

    @app.api_route("/api/jobs/{job_id}", methods=["GET", "HEAD"], dependencies=[Depends(_require_api_auth)])
    def get_job(job_id: str) -> JSONResponse:
        record = job_store.get(job_id)
        if record is None:
            raise HTTPException(status_code=404, detail="unknown job_id")
        return JSONResponse(record.to_dict())

    @app.api_route("/api/jobs/{job_id}/events", methods=["GET"], dependencies=[Depends(_require_api_auth)])
    async def get_job_events(job_id: str) -> StreamingResponse:
        if job_store.get(job_id) is None:
            raise HTTPException(status_code=404, detail="unknown job_id")

        async def _stream():
            import asyncio

            last_state = None
            while True:
                current = job_store.get(job_id)
                if current is None:
                    break
                if current.state != last_state:
                    # C2-10: job-record state only, never collector output.
                    yield f"data: {json.dumps(current.to_dict())}\n\n"
                    last_state = current.state
                if current.state in TERMINAL_STATES:
                    break
                await asyncio.sleep(0.5)

        return StreamingResponse(_stream(), media_type="text/event-stream")

    return app
