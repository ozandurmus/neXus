"""`M8.3` -- read-only first-contact identity-evidence producer, CLI dispatch.

Thin orchestration only: the actual sequence (registry resolution, candidate
selection, the mandatory trust-before-credential gate, the existing
`_collect_host` primitive, the positive write gate) lives in
`utils/first_contact_producer.py`; this module supplies the one thing that
module cannot resolve itself -- CP credentials, through the existing
DEV.2.1/DEV.2.2 runtime source (`application.services.make_runtime_config`)
-- but only via a callback the producer calls itself, strictly after its own
trust lookup has already succeeded.

Narrow CLI maintenance/bootstrap invocation only, consistent with the
`--registry-*` and `--cp-config-probe` conventions. Not exposed through the
Operator Console: `console/` imports no vendor/collector module, and
`console/registry.py`'s closed job vocabulary has no entry for this.
"""
from __future__ import annotations

import os

from utils.logger import register_sensitive_value, user_fingerprint

from application.services import _require_bootstrap, make_runtime_config


def identity_first_contact(ctx):
    from utils.first_contact_producer import run_first_contact_producer

    args = ctx.args
    runtime_paths = ctx.runtime_paths
    device_id = args.identity_first_contact
    _runtime_config = make_runtime_config(ctx)

    print("=== SECURITYEXPERT M8.3 FIRST-CONTACT IDENTITY-EVIDENCE PRODUCER ===\n")
    _require_bootstrap("identity-first-contact", runtime_paths.output_root)
    print(
        "Read-only, single-device: resolves the current Device Registry record, "
        "selects the one CMA/MDS-discovered physical candidate whose endpoint matches "
        "it, enforces the mandatory trust-before-credential sequence, then reuses the "
        "existing physical-host collection primitives. Not available in the Operator "
        "Console.\n"
    )

    cfg_box: dict = {}

    def _resolve_credentials():
        cfg = _runtime_config(require_cp=True, require_panorama=False)
        cfg_box["cfg"] = cfg
        username = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_USERNAME") or cfg.auth.principal
        secret = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD") or cfg.auth.secret
        if not username or not secret:
            raise RuntimeError("CP configuration credentials are unavailable")
        register_sensitive_value(username, f"[USER:{user_fingerprint(username)}]")
        register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")
        return username, secret

    try:
        outcome = run_first_contact_producer(
            device_id=device_id,
            data_root=runtime_paths.data_root,
            output_root=runtime_paths.output_root,
            resolve_credentials=_resolve_credentials,
        )
    finally:
        cfg = cfg_box.get("cfg")
        if cfg is not None:
            cfg.clear_credentials()

    print(f"device_id:        {outcome.device_id}")
    print(f"outcome:          {outcome.status}")
    if outcome.reason:
        print(f"reason:           {outcome.reason}")
    if outcome.relationship_id:
        print(f"relationship_id:  {outcome.relationship_id}")
    if outcome.status not in {"NEW", "SUPERSEDED"}:
        print("\nNo proven relationship was written. IDENTITY_TRANSLATION_REQUIRED stands.")
    return None
