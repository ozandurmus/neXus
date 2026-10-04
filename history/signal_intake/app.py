"""event_signal_intake Slice 1 -- the ASGI request boundary.

Design: ``docs/design/EVENT_SIGNAL_INTAKE_ARCHITECTURE.md`` (FROZEN -- Slice
1 only). Route table (deliberately one route):

    POST /events   HMAC-authenticated   validate + resolve identity + enqueue

This module imports no vendor/collector module. It never calls a collector
and never writes evidence -- on a fully validated, identity-resolved,
non-duplicate, non-cooling-down signal it does exactly one privileged thing:
submit a job to the *existing*, unmodified ``CON.2`` job engine
(``console.jobs.ConsoleJobStore`` / ``console.runner.ConsoleJobRunner``),
using the existing ``config_refresh_cp`` job type. Everything after that is
``CON.2``'s own, already-tested admission/execution path -- this module adds
no new path to a collector, a credential, or a device.
"""
from __future__ import annotations

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from console.jobs import ConsoleJobStore
from console.registry import get_job_type
from console.registry_targets import resolve_registry_targets
from console.runner import ConsoleJobRunner
from utils.action_taxonomy import console_refusal
from utils.event_signal_intake import (
    SignalAuthenticationError,
    SignalIntakeError,
    SignalReplayError,
    SignalSchemaError,
    check_and_record_cooldown,
    check_and_record_replay,
    load_signal_secret,
    resolve_device_record,
    validate_schema,
    verify_signature,
)

#: Slice 1's one trigger target: the existing, device_id-targeted,
#: CLASS_0_READ job type console/registry.py already defines. Reusing it
#: means every M6/M7/M8.4 admission/identity-translation invariant already
#: proven for the operator console applies identically here (design doc
#: "Trigger").
_JOB_TYPE_ID = "config_refresh_cp"


def create_signal_intake_app(
    *,
    runtime_paths,
    job_store: ConsoleJobStore | None = None,
    runner: ConsoleJobRunner | None = None,
) -> FastAPI:
    """``job_store``/``runner`` are optional so a test exercising route
    wiring in isolation can inject its own -- mirrors
    ``console.app.create_app``'s own composition exactly, including the
    default construction of a fresh ``CollectionCoordinator``/
    ``RuntimeCollectionServices`` pair when the caller supplies neither."""
    if job_store is None:
        job_store = ConsoleJobStore(runtime_paths.data_root)
    if runner is None:
        from utils.collection_executor import CollectionCoordinator, RuntimeCollectionServices
        from utils.coordinator_backend import Provenance

        runner = ConsoleJobRunner(
            job_store=job_store,
            runtime_paths=runtime_paths,
            services=RuntimeCollectionServices(coordinator=CollectionCoordinator()),
            provenance=Provenance.EVENT.value,
        )
        runner.start()

    app = FastAPI(
        title="SecurityExpert Event Signal Intake",
        # Same posture as the operator console (console/app.py): smallest
        # possible route table, no auto-generated docs/schema surface.
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    @app.post("/events")
    async def post_event(
        request: Request,
        x_signal_timestamp: str | None = Header(default=None, alias="X-Signal-Timestamp"),
        x_signal_nonce: str | None = Header(default=None, alias="X-Signal-Nonce"),
        x_signal_signature: str | None = Header(default=None, alias="X-Signal-Signature"),
    ) -> JSONResponse:
        raw_body = await request.body()

        # Authentication first, over the raw body -- a request that cannot
        # prove who sent it is refused before any of its content is even
        # parsed as JSON (AC-2).
        try:
            secret = load_signal_secret()
            verify_signature(
                secret=secret,
                timestamp=x_signal_timestamp or "",
                nonce=x_signal_nonce or "",
                raw_body=raw_body,
                signature_header=x_signal_signature,
            )
        except SignalAuthenticationError as exc:
            raise HTTPException(status_code=401, detail=str(exc))

        # Replay protection: timestamp window + nonce, both bound into the
        # signature already verified above.
        try:
            check_and_record_replay(
                data_root=runtime_paths.data_root,
                timestamp=x_signal_timestamp or "",
                nonce=x_signal_nonce or "",
            )
        except SignalReplayError as exc:
            raise HTTPException(status_code=401, detail=str(exc))
        except SignalIntakeError as exc:
            raise HTTPException(status_code=503, detail=str(exc))

        try:
            body = await request.json()
        except Exception:
            raise HTTPException(status_code=400, detail="request body must be JSON")
        try:
            signal = validate_schema(body)
        except SignalSchemaError as exc:
            raise HTTPException(status_code=400, detail=str(exc))

        # CON.2's own idempotency contract (C2-9): a resend of a signal this
        # module already accepted -- same signal_id, necessarily a fresh
        # nonce/timestamp since replay protection already required that --
        # returns the original job untouched. Checked before identity
        # resolution/admission/cooldown so a genuine resend never re-runs
        # any of them or consumes a second cooldown window.
        idempotency_key = f"event-signal:{signal.signal_id}"
        existing = next(
            (r for r in job_store.list_all() if r.idempotency_key == idempotency_key), None
        )
        if existing is not None:
            return JSONResponse({
                "status": "triggered",
                "detail": "a bounded, coordinator-managed read-only collection was already queued for this signal_id",
                "job_id": existing.job_id,
                "device_id": existing.targets[0] if existing.targets else None,
            })

        # Canonical identity resolution (AC-3) -- an honest fact, not a
        # refusal: a signal for a device the registry has never heard of is
        # not an error, it is "unknown_identity".
        try:
            record = resolve_device_record(signal.device_reference, data_root=runtime_paths.data_root)
        except SignalIntakeError as exc:
            raise HTTPException(status_code=503, detail=str(exc))
        if record is None:
            return JSONResponse({
                "status": "unknown_identity",
                "detail": "no registry match for device_reference",
            })

        # Gate-verification finding 4 (design doc): no per-device PAN
        # config target seam exists yet -- refuse honestly rather than run
        # plane-wide or silently do nothing.
        if record.vendor != "checkpoint":
            return JSONResponse({
                "status": "unsupported_vendor_target_seam",
                "detail": (
                    f"vendor {record.vendor!r} has no bounded per-device collection "
                    "target seam yet (Slice 1 is Check Point only)"
                ),
                "device_id": record.device_id,
            })

        job_type = get_job_type(_JOB_TYPE_ID)
        # Defense in depth (mirrors console/app.py's own C2-6 check): the
        # taxonomy, not a hardcoded assumption about this one job type,
        # decides whether it may ever be enqueued (AC-4).
        taxonomy_refusal = console_refusal(job_type.action_class)
        if taxonomy_refusal is not None:
            raise HTTPException(
                status_code=409,
                detail={"error": taxonomy_refusal, "action_class": job_type.action_class.id},
            )

        # M6/M8.4 admission -- the exact function console/app.py's own
        # POST /api/jobs calls for this job type, unmodified.
        refusal = resolve_registry_targets([record.device_id], data_root=runtime_paths.data_root)
        if refusal is not None:
            return JSONResponse({
                "status": refusal.reason,
                "detail": refusal.detail,
                "device_id": record.device_id,
            })

        # Dedup/cooldown (AC-2) -- distinct from replay protection: a fresh,
        # validly-signed, non-replayed, admission-eligible signal can still
        # be rate-limited per (device, event_type).
        try:
            allowed = check_and_record_cooldown(
                data_root=runtime_paths.data_root,
                device_id=record.device_id,
                event_type=signal.event_type,
            )
        except SignalIntakeError as exc:
            raise HTTPException(status_code=503, detail=str(exc))
        if not allowed:
            return JSONResponse({
                "status": "ignored_cooldown",
                "detail": "a signal for this device/event_type already triggered within the cooldown window",
                "device_id": record.device_id,
            })

        # The one privileged action this module ever performs: submit to the
        # existing CON.2 job engine. No collector, credential or device is
        # ever reachable from this module (AC-4) -- everything from here is
        # console.jobs/console.runner's own, unmodified, already-tested path.
        job_record, is_new = job_store.submit(
            job_type=job_type.id,
            command_class=job_type.command_class,
            targets=[record.device_id],
            idempotency_key=idempotency_key,
        )
        if is_new:
            runner.enqueue(job_record.job_id)
        return JSONResponse({
            "status": "triggered",
            "detail": "a bounded, coordinator-managed read-only collection has been queued",
            "job_id": job_record.job_id,
            "device_id": record.device_id,
        })

    return app
