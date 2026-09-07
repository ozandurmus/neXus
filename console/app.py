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
    POST      /api/enrollment/probe      authenticated    queue a pre-registration identity probe (M9)
    GET/HEAD  /api/registry/devices      authenticated    read-only Device Registry list (M9)
    POST      /api/registry/enrollments  authenticated    confirm + persist an enrollment (M9)

No other route exists. No method other than GET/HEAD/POST is exposed
anywhere, and POST exists only on ``/api/jobs``, ``/api/enrollment/probe``
and ``/api/registry/enrollments`` (AC-2). This module imports no vendor/
collector module, transitively (AC-8; ``console.payloads`` ->
``utils.html_export`` -> the same UI-payload builders the exported report
already uses; ``console.jobs``/``console.runner`` -> ``main.main()`` for
every job type except one -- see ``console/runner.py``'s own docstring for
the M9 identity-probe job type's one named exception, which still never
imports a vendor module from *this* module's own top-level scope).
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, StreamingResponse
from starlette.middleware.base import BaseHTTPMiddleware

from console.auth import extract_bearer_token, origin_is_trusted, token_matches
from console.jobs import TERMINAL_STATES, ConsoleJobStore
from console.payloads import build_console_payloads
from console.registry import JOB_REGISTRY, get_job_type
from console.registry_targets import DEVICE_ID_TARGET_MODE, resolve_registry_targets
from console.runner import ConsoleJobRunner
from utils.action_taxonomy import console_refusal
from utils.enrollment_audit import (
    EnrollmentAuditConflictError,
    EnrollmentAuditError,
    EnrollmentAuditStore,
)
from utils.device_registry import (
    VENDOR_VALUES,
    DeviceRegistry,
    DeviceRegistryError,
    DeviceRegistryLockError,
    normalize_endpoint,
)
from utils.html_export import compose_modules, read_text_file
from utils.restore_readiness import resolve_entity_id

# M9 condition 17 -- production/server mode refuses the enrollment write
# routes unless a future DEPLOY.1A-authorized profile says otherwise. No
# runtime path sets this today (`console/server.py::run_console` only ever
# binds 127.0.0.1); it exists as a pre-wired, enforced refusal point rather
# than depending on a later change remembering to add one.
_DEPLOYMENT_PROFILE_ENV = "SECURITYEXPERT_DEPLOYMENT_PROFILE"
_DEPLOYMENT_PROFILES = ("local", "server")

# M9 condition 7 -- "trust-profile reference only". There is exactly one
# legal value: no named-trust-profile system exists anywhere in this
# repository (`utils/cp_ssh_trust.py` has exactly one trust source, the
# system SSH `known_hosts`) and building one is explicitly out of scope. The
# console therefore accepts this one sentinel and refuses every other value,
# rather than silently accepting an operator-typed value that selects
# nothing.
_TRUST_PROFILE_SENTINEL = "system_known_hosts"

# M9 condition 6 -- "credential-profile reference only", closed to the one
# value that actually selects the executed credential source. Round 1 only
# format-validated this field (mirroring utils/device_registry.py's own
# module-private `_CREDENTIAL_REF_RE`) without the field ever selecting
# anything: `console/runner.py::_resolve_probe_credentials` always resolves
# the single global SECURITYEXPERT_CP_CONFIG_SSH_USERNAME/_PASSWORD source
# regardless of what was submitted, so an arbitrary accepted string made the
# audit trail disagree with the credentials actually used. Corrected to the
# same closed-sentinel pattern `_TRUST_PROFILE_SENTINEL` already uses below:
# no named-credential-profile system exists anywhere in this repository, and
# building one is explicitly out of scope, so the console accepts this one
# sentinel and fails closed on every other value.
_CREDENTIAL_PROFILE_SENTINEL = "system_cp_config_ssh"

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


# ---------------------------------------------------------------------------
# M9 -- enrollment request validation and the preview/confirmation binding
# ---------------------------------------------------------------------------

def _normalize_vendor_hint(value) -> str:
    text = str(value or "unknown").strip().lower()
    if text not in VENDOR_VALUES:
        raise HTTPException(status_code=400, detail=f"unsupported vendor_hint: {value!r}")
    return text


def _require_credential_ref(value) -> str:
    if value != _CREDENTIAL_PROFILE_SENTINEL:
        raise HTTPException(
            status_code=400,
            detail=(
                f"credential_profile_ref must be {_CREDENTIAL_PROFILE_SENTINEL!r} -- no other "
                "credential profile exists yet (exactly one Check Point SSH credential source "
                "is configured system-wide)"
            ),
        )
    return value


def _require_trust_ref(value) -> str:
    if value != _TRUST_PROFILE_SENTINEL:
        raise HTTPException(
            status_code=400,
            detail=(
                f"trust_profile_ref must be {_TRUST_PROFILE_SENTINEL!r} -- no other trust "
                "profile exists yet (utils.cp_ssh_trust has exactly one trust source)"
            ),
        )
    return value


def _require_tags(value) -> dict:
    if value is None:
        return {}
    if not isinstance(value, dict) or not all(
        isinstance(k, str) and isinstance(v, str) for k, v in value.items()
    ):
        raise HTTPException(status_code=400, detail="tags must be an object of string to string")
    return value


def _deployment_profile() -> str:
    value = (os.environ.get(_DEPLOYMENT_PROFILE_ENV) or "local").strip().lower()
    # Fail closed on an unrecognized value -- never silently treated as local.
    return value if value in _DEPLOYMENT_PROFILES else "server"


def _require_local_deployment_profile() -> None:
    """Condition 17. Always permits today (nothing sets
    ``SECURITYEXPERT_DEPLOYMENT_PROFILE``) -- a pre-wired, enforced refusal
    point for a future ``DEPLOY.1A`` server-mode flag, not a control that
    does anything yet."""
    if _deployment_profile() != "local":
        raise HTTPException(status_code=403, detail={"error": "enrollment_blocked_non_local_profile"})


def _probe_intent_token(*, endpoint: str, port, vendor_hint: str, credential_ref: str, trust_ref: str) -> str:
    """Binds a queued identity-probe job to the exact intent it was run
    against, without ever persisting the endpoint on the job record itself
    (`console/jobs.py`'s own forbidden-field list). Stored as the job's sole
    ``targets`` entry at probe time; recomputed and compared at confirm time
    -- a mismatch means a stale or substituted confirmation (condition 13)."""
    payload = f"{endpoint}|{port}|{vendor_hint}|{credential_ref}|{trust_ref}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


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
        if job_type.console_reachable_via != "generic_jobs_api":
            # M9: the identity-probe job type carries a raw endpoint in its
            # own submission path (`POST /api/enrollment/probe`), never
            # through this generic, address-free job API.
            raise HTTPException(
                status_code=400,
                detail=f"job_type {job_type.id!r} is not submittable through this route",
            )

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
        elif job_type.target_mode == DEVICE_ID_TARGET_MODE:
            # M6 (registry_keyed_job_targets, Option D): fail-closed
            # admission against the PCP.1 Device Registry. A target-free
            # request (targets == []) is unaffected -- resolve_registry_
            # targets returns None -- and stays M5's plane-wide behavior.
            refusal = resolve_registry_targets(targets, data_root=runtime_paths.data_root)
            if refusal is not None:
                raise HTTPException(
                    status_code=400,
                    detail={"error": refusal.error, "reason": refusal.reason, "detail": refusal.detail},
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

    # -- M9: enrollment preview + confirmation -----------------------------

    @app.post("/api/enrollment/probe", dependencies=[Depends(_require_api_auth)])
    async def post_enrollment_probe(
        request: Request,
        idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
    ) -> JSONResponse:
        _require_local_deployment_profile()
        if not idempotency_key:
            raise HTTPException(status_code=400, detail="missing Idempotency-Key header")
        try:
            body = await request.json()
        except Exception:
            raise HTTPException(status_code=400, detail="request body must be JSON")
        if not isinstance(body, dict):
            raise HTTPException(status_code=400, detail="request body must be a JSON object")

        # Condition 3/4: closed typed intent, strict schema -- unknown fields
        # refused rather than silently ignored.
        allowed_keys = {"endpoint", "vendor_hint", "credential_profile_ref", "trust_profile_ref"}
        unknown = set(body) - allowed_keys
        if unknown:
            raise HTTPException(status_code=400, detail=f"unknown field(s): {sorted(unknown)}")

        raw_endpoint = body.get("endpoint")
        if not isinstance(raw_endpoint, str) or not raw_endpoint.strip():
            raise HTTPException(status_code=400, detail="endpoint must be a non-empty string")
        try:
            endpoint, port = normalize_endpoint(raw_endpoint)
        except DeviceRegistryError as exc:
            raise HTTPException(status_code=400, detail=str(exc))

        vendor_hint = _normalize_vendor_hint(body.get("vendor_hint"))
        credential_ref = _require_credential_ref(body.get("credential_profile_ref"))
        trust_ref = _require_trust_ref(body.get("trust_profile_ref"))

        # Condition 6/8: no credential payload of any kind, from this or any
        # other field -- there is no field this schema would even accept one
        # into (`allowed_keys` above is closed).

        job_type = get_job_type("device_enrollment_identity_probe")
        token = _probe_intent_token(
            endpoint=endpoint, port=port, vendor_hint=vendor_hint,
            credential_ref=credential_ref, trust_ref=trust_ref,
        )
        # Condition 9: this request contacts no device -- it validates,
        # queues a job record, and returns. Condition 10: first contact runs
        # as a separate queued CLASS 0 read-only job, via the runner.
        record, is_new = job_store.submit(
            job_type=job_type.id,
            command_class=job_type.command_class,
            targets=[f"probe-intent:{token}"],
            idempotency_key=idempotency_key,
        )
        if is_new:
            runner.enqueue_probe(record.job_id, endpoint=endpoint, port=port)
        return JSONResponse(record.to_dict())

    @app.api_route("/api/registry/devices", methods=["GET", "HEAD"], dependencies=[Depends(_require_api_auth)])
    def get_registry_devices() -> JSONResponse:
        try:
            records = DeviceRegistry(runtime_paths.data_root).list()
        except DeviceRegistryError:
            raise HTTPException(status_code=503, detail={"error": "device_registry_unavailable"})
        return JSONResponse([record.to_dict() for record in records])

    @app.post("/api/registry/enrollments", dependencies=[Depends(_require_api_auth)])
    async def post_registry_enrollment(request: Request) -> JSONResponse:
        _require_local_deployment_profile()
        try:
            body = await request.json()
        except Exception:
            raise HTTPException(status_code=400, detail="request body must be JSON")
        if not isinstance(body, dict):
            raise HTTPException(status_code=400, detail="request body must be a JSON object")

        allowed_keys = {
            "probe_job_id", "endpoint", "vendor_hint", "credential_profile_ref",
            "trust_profile_ref", "tags", "candidate_id", "confirm",
        }
        unknown = set(body) - allowed_keys
        if unknown:
            raise HTTPException(status_code=400, detail=f"unknown field(s): {sorted(unknown)}")

        # Condition 13: explicit operator confirmation. Never implicit,
        # never inferred from the presence of the other fields alone.
        if body.get("confirm") is not True:
            raise HTTPException(
                status_code=400,
                detail="confirm must be true -- explicit operator confirmation is required",
            )

        # M9 scope decision, reconfirmed by explicit Product Owner
        # RELAY_DECISION (relay ozandurmus/nexus-agent-relay#3, 2026-09-07):
        # the registry<->evidence reconciliation join that would produce a
        # real "candidate" list is §9.2 amendment A6 in the runtime/
        # enrollment contract -- explicitly M10's job, not M9's, and this
        # movement must not invent, synthesize, infer or persist a
        # candidate-id source of its own. This schema accepts the field
        # (AC-EN-1's literal wording: "and/or a closed candidate id") and
        # would hold it to the identical trust/audit/confirmation bar if it
        # were ever populated (AC-EN-10, no exemption) -- but there is no
        # data source for it yet, so any actually-populated value is refused
        # honestly, with a stable error code naming the exact dependency,
        # rather than silently accepted or silently ignored.
        if body.get("candidate_id"):
            raise HTTPException(
                status_code=409,
                detail={
                    "error": "candidate_enrollment_not_available_pending_m10",
                    "dependency": "M10 registry<->evidence reconciliation projection (§9.2 amendment A6)",
                },
            )

        probe_job_id = body.get("probe_job_id")
        if not isinstance(probe_job_id, str) or not probe_job_id.strip():
            raise HTTPException(status_code=400, detail="probe_job_id must be a non-empty string")

        raw_endpoint = body.get("endpoint")
        if not isinstance(raw_endpoint, str) or not raw_endpoint.strip():
            raise HTTPException(status_code=400, detail="endpoint must be a non-empty string")
        try:
            endpoint, port = normalize_endpoint(raw_endpoint)
        except DeviceRegistryError as exc:
            raise HTTPException(status_code=400, detail=str(exc))

        vendor_hint = _normalize_vendor_hint(body.get("vendor_hint"))
        credential_ref = _require_credential_ref(body.get("credential_profile_ref"))
        trust_ref = _require_trust_ref(body.get("trust_profile_ref"))
        tags = _require_tags(body.get("tags"))

        # Condition 12/AC-EN-6: identity preview must exist and be positive.
        # The server re-derives identity from the job's own durable result --
        # it never trusts a client-supplied identity claim.
        job_record = job_store.get(probe_job_id)
        if job_record is None:
            raise HTTPException(status_code=404, detail="unknown probe_job_id")
        if job_record.job_type != "device_enrollment_identity_probe":
            raise HTTPException(
                status_code=400, detail="probe_job_id does not reference an identity-probe job"
            )
        if job_record.state != "succeeded" or job_record.preview is None:
            raise HTTPException(
                status_code=409,
                detail={"error": "no_positive_identity_evidence", "job_state": job_record.state},
            )

        # Preview-to-intent binding: a confirmation for a different endpoint,
        # vendor hint, or opaque reference than the one actually probed is a
        # stale or substituted confirmation, refused (condition 13).
        expected_token = _probe_intent_token(
            endpoint=endpoint, port=port, vendor_hint=vendor_hint,
            credential_ref=credential_ref, trust_ref=trust_ref,
        )
        actual = job_record.targets[0] if job_record.targets else None
        if actual != f"probe-intent:{expected_token}":
            raise HTTPException(status_code=409, detail={"error": "probe_intent_mismatch"})

        audit_store = EnrollmentAuditStore(runtime_paths.data_root)
        # Single-use (condition 13/`AC-EN-13`), atomic under concurrency:
        # `record_confirmation` itself checks-and-creates the confirmation
        # row under one lock, keyed deterministically on `probe_job_id`, so
        # two concurrent confirmations of the same probe can no longer both
        # pass a check before either writes -- the second one always loses
        # here, before `DeviceRegistry.enroll` is ever called. Condition 14:
        # this row is durable BEFORE the registry mutation below.
        try:
            confirmation = audit_store.record_confirmation(
                probe_job_id=probe_job_id, endpoint=endpoint, port=port,
                vendor_hint=vendor_hint, credential_ref=credential_ref,
                trust_ref=trust_ref, tags=tags,
            )
        except EnrollmentAuditConflictError:
            raise HTTPException(status_code=409, detail={"error": "probe_already_consumed"})
        except EnrollmentAuditError:
            raise HTTPException(status_code=503, detail={"error": "enrollment_audit_unavailable"})

        # Condition 15/16: the one existing DeviceRegistry enrollment path --
        # the exact function `application/workflows/registry.py::registry_enroll`
        # already calls -- with its duplicate-detection and mutation-lock
        # contract unchanged.
        registry = DeviceRegistry(runtime_paths.data_root)
        try:
            device_record = registry.enroll(
                endpoint=endpoint, vendor_hint=vendor_hint,
                credential_ref=credential_ref, tags=tags,
            )
        except DeviceRegistryLockError:
            audit_store.record_outcome(
                confirmation_audit_id=confirmation.audit_id, probe_job_id=probe_job_id,
                outcome="refused_lock",
            )
            raise HTTPException(status_code=503, detail={"error": "registry_locked_retry"})
        except DeviceRegistryError as exc:
            outcome = "refused_duplicate" if "duplicate" in str(exc).lower() else "refused_other"
            audit_store.record_outcome(
                confirmation_audit_id=confirmation.audit_id, probe_job_id=probe_job_id,
                outcome=outcome,
            )
            raise HTTPException(
                status_code=409 if outcome == "refused_duplicate" else 500,
                detail={"error": outcome},
            )

        audit_store.record_outcome(
            confirmation_audit_id=confirmation.audit_id, probe_job_id=probe_job_id,
            outcome="enrolled", device_id=device_record.device_id,
        )
        return JSONResponse(device_record.to_dict())

    return app
