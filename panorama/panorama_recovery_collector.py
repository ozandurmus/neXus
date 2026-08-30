"""SecurityExpert — RB.2 PAN device-state recovery collector.

Implements contract §7.1 (`GET /api/?type=export&category=device-state`).
`read` class per the network-device command gate
(`docs/AI_DEVELOPMENT_PROTOCOL.md`) — the "no new write command at the
current product maturity" prohibition does not apply; the 10-point gate
documentation for this command was written in
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.1 before this file existed.

Open decision D2 (`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §13) is
**RESOLVED 2026-08-30**: the platform's PAN service account is permitted to
hold superuser for this call.

**Real-environment validation remains owed.** This cloud sandbox has no
device reachability — the same gap class as every other
`on_hardware_real_env_validation` item in this repository. Automated tests
exercise this module against a fixture HTTP transport only, never a live
firewall.
"""
from __future__ import annotations

from typing import Any

import requests

from panorama.panorama_runtime_runner import fix_host, get_api_key
from utils.pan_tls_trust import preflight_pan_tls_ca_bundle
from utils.recovery_collect import RecoveryCollectionTarget

EXPORT_TIMEOUT_SECONDS = 300  # contract §7.1 point 4


class PanDeviceStateCollector:
    """`RecoveryCollector` for PAN device-state export.

    One instance per collection run; reuses one API key per host for the
    whole run (contract §7.1 point 7: existing-session reuse) rather than
    re-authenticating per target.
    """

    def __init__(self, cfg, *, verify: bool | str = False):
        # Fail closed before any request, per utils.pan_tls_trust's own
        # contract -- a configured CA bundle path that is missing/unreadable
        # must never silently fall through to an unverified connection.
        preflight_pan_tls_ca_bundle(verify)
        self.cfg = cfg
        self.verify = verify
        self._key_cache: dict[str, str] = {}

    def _key_for(self, host: str) -> str:
        if host not in self._key_cache:
            self._key_cache[host] = get_api_key(self.cfg, host, verify=self.verify)
        return self._key_cache[host]

    def collect(self, target: RecoveryCollectionTarget) -> tuple[bytes, dict[str, Any]]:
        row = target.row
        management_ip = row.get("management_ip")
        if not management_ip:
            raise RuntimeError(
                "PAN device-state export requires a management_ip in unified.json for this target"
            )
        host = fix_host(management_ip)
        key = self._key_for(host)

        response = requests.get(
            f"{host}/api/",
            params={"type": "export", "category": "device-state", "key": key},
            verify=self.verify,
            timeout=EXPORT_TIMEOUT_SECONDS,
        )
        if response.status_code == 403:
            # Contract §7.1 point 5: never retry a 403 -- a privilege
            # failure is a decision, not a transient. run_recovery_collection
            # does not retry .collect() at all today; this guard documents
            # the intent so it survives a future retry wrapper.
            raise RuntimeError(
                "PAN device-state export returned 403 (privilege/permission failure) -- not retried"
            )
        response.raise_for_status()
        plaintext = response.content
        if not plaintext:
            raise RuntimeError("PAN device-state export returned an empty response body")

        return plaintext, {
            "class": "pan_device_state",
            "vendor_native_filename": "device_state_cfg.tgz",
            "collected_via": "pan_xml_api_export",
            "compression": "gzip",
            "physical_endpoint": target.entity_id,
            "platform": "pan-os",
            # unified.json carries no software_version field for PAN devices
            # today (no gate-documented version command exists yet) -- record
            # the honest "unknown" sentinel rather than fabricate one.
            # utils.recovery_validation treats an "unknown" artifact version
            # as NOT_APPLICABLE for V3 version-match, not a false FAIL.
            "software_version": "unknown",
            "ha_role": "unknown",
        }
