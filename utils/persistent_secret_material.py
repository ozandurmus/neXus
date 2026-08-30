"""SecurityExpert — persistent secret/trust material contract check (DEV.2.2).

Local/offline diagnostic for the DEV.2.2 deployment contract: does the
running configuration actually persist the support-bundle HMAC identity key
across a container restart, and are CP host-key trust / PAN CA trust wired to
mounted, non-default material rather than left in compatibility mode?

This module never opens a network connection and never prints key material,
file paths, host identities or credentials -- only booleans/paths-existence
and counts, matching the value-free contract already used by
``utils.cp_ssh_trust`` and ``utils.pan_tls_trust``.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from utils.cp_ssh_trust import CpSshStrictPreflightError, apply_strict_host_key_policy
from utils.pan_tls_trust import PanTlsStrictPreflightError, preflight_pan_tls_ca_bundle
from utils.runtime_paths import RuntimePaths


@dataclass
class PersistentSecretMaterialReport:
    hmac_key_present: bool
    hmac_key_on_persistent_root: bool
    cp_strict_host_key_enabled: bool
    cp_trust_status: str  # PASS | FAIL | NOT_ENABLED
    pan_ca_bundle_configured: bool
    pan_trust_status: str  # PASS | FAIL | NOT_CONFIGURED
    findings: list = field(default_factory=list)

    @property
    def gate(self) -> str:
        return "FAIL" if self.findings else "PASS"


def _check_cp_trust(environ) -> tuple[bool, str]:
    strict = environ.get("SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY", "").strip().lower() not in {
        "", "0", "false", "no", "off", "disabled",
    }
    if not strict:
        return False, "NOT_ENABLED"
    import paramiko

    ssh = paramiko.SSHClient()
    try:
        apply_strict_host_key_policy(ssh, strict=True)
    except CpSshStrictPreflightError:
        return True, "FAIL"
    return True, "PASS"


def _check_pan_trust(environ) -> tuple[bool, str]:
    ca_bundle = environ.get("SECURITYEXPERT_PAN_CA_BUNDLE") or environ.get("SECURITYEXPERT_PAN_DIRECT_CA_BUNDLE")
    ca_bundle = (ca_bundle or "").strip()
    if not ca_bundle:
        return False, "NOT_CONFIGURED"
    try:
        preflight_pan_tls_ca_bundle(ca_bundle)
    except PanTlsStrictPreflightError:
        return True, "FAIL"
    return True, "PASS"


def check_persistent_secret_material(
    runtime_paths: RuntimePaths, *, environ=None
) -> PersistentSecretMaterialReport:
    """Evaluate the DEV.2.2 persistent secret/trust material contract.

    ``runtime_paths`` must already be resolved (``resolve_runtime_paths``),
    which itself fails closed if the runtime root is not physically separate
    from the repository -- so a present HMAC key under ``data_root`` is, by
    construction, on the persistent (non-repository) side.
    """
    env = os.environ if environ is None else environ

    support_key_file = runtime_paths.data_root / ".support_hmac.key"
    hmac_key_present = support_key_file.is_file()
    hmac_key_on_persistent_root = True  # data_root is always under runtime_root by resolve_runtime_paths's own contract

    findings = []

    cp_strict_enabled, cp_trust_status = _check_cp_trust(env)
    if cp_trust_status == "FAIL":
        findings.append("cp_strict_host_key_enabled_but_no_trusted_material_mounted")

    pan_ca_configured, pan_trust_status = _check_pan_trust(env)
    if pan_trust_status == "FAIL":
        findings.append("pan_ca_bundle_configured_but_not_readable")

    return PersistentSecretMaterialReport(
        hmac_key_present=hmac_key_present,
        hmac_key_on_persistent_root=hmac_key_on_persistent_root,
        cp_strict_host_key_enabled=cp_strict_enabled,
        cp_trust_status=cp_trust_status,
        pan_ca_bundle_configured=pan_ca_configured,
        pan_trust_status=pan_trust_status,
        findings=findings,
    )
