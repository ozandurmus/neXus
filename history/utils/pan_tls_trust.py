"""SecurityExpert — PAN TLS CA-bundle trust policy helper.

Centralizes CA-bundle preflight for Panorama and direct-firewall HTTPS paths.

Contract (from 0.6.5 PAN TLS/CA Trust Closure):
- CA bundle configured (SECURITYEXPERT_PAN_CA_BUNDLE or
  SECURITYEXPERT_PAN_DIRECT_CA_BUNDLE) + file not found/not readable
  → PanTlsStrictPreflightError before any requests.* call.
- When verify is False (compat mode) preflight is a no-op.
- When verify is True (system bundle) preflight is a no-op.
- Error message is value-free: no path, address or credential.
"""
from __future__ import annotations

from pathlib import Path


class PanTlsStrictPreflightError(Exception):
    """Raised before any HTTPS request when a CA bundle path is configured but
    the file cannot be found or read.

    The message is value-free: no file path, endpoint address or credential is
    included.  The caller must treat this as a hard transport failure and must
    not call requests.get/post after catching this exception.
    """


def preflight_pan_tls_ca_bundle(verify: bool | str) -> None:
    """Check that a configured CA bundle path is accessible before network use.

    Parameters
    ----------
    verify:
        The value that will be passed to ``requests`` as ``verify=``.
        When a non-empty string (CA bundle path), confirm the file exists and
        is at minimum partially readable.  When bool, this function is a no-op.

    Raises
    ------
    PanTlsStrictPreflightError
        A CA bundle path was configured but the file was not found or could
        not be read.  The caller **must not** make any HTTPS requests after
        catching this exception.
    """
    if not isinstance(verify, str) or not verify:
        return
    path = Path(verify)
    if not path.is_file():
        raise PanTlsStrictPreflightError(
            "pan_tls_ca_bundle_preflight_failed: bundle_path_not_found"
        )
    try:
        path.open("rb").read(1)
    except OSError:
        raise PanTlsStrictPreflightError(
            "pan_tls_ca_bundle_preflight_failed: bundle_path_not_readable"
        )
