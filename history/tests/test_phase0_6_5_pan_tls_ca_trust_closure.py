"""Tests for 0.6.5 — PAN TLS/CA Trust Closure.

Acceptance criteria:
AC-1: preflight_pan_tls_ca_bundle is no-op when verify is False (compat mode).
AC-2: preflight_pan_tls_ca_bundle is no-op when verify is True (system bundle).
AC-3: preflight_pan_tls_ca_bundle raises PanTlsStrictPreflightError when
      CA bundle path is a non-existent file.
AC-4: preflight_pan_tls_ca_bundle raises PanTlsStrictPreflightError when
      CA bundle path exists but is not readable.
AC-5: preflight_pan_tls_ca_bundle passes when CA bundle path exists and is
      readable; no network activity occurs.
AC-6: panorama_runtime_runner._tls_verify_setting returns False by default
      (no env vars set).
AC-7: panorama_runtime_runner._tls_verify_setting returns CA bundle path when
      SECURITYEXPERT_PAN_CA_BUNDLE is set.
AC-8: panorama_runtime_runner._tls_verify_setting returns True when
      SECURITYEXPERT_PAN_TLS_VERIFY=1 and no CA bundle is set.
AC-9: panorama_config_collector.run_panorama_config_evidence raises
      PanTlsStrictPreflightError before any API call when Panorama CA bundle
      is configured but not found.
AC-10: panorama_config_collector.run_panorama_config_evidence raises
       PanTlsStrictPreflightError before any API call when direct CA bundle
       is configured but not found.
AC-11: PanTlsStrictPreflightError error message is value-free (no path,
       endpoint, credential).
"""
from __future__ import annotations

import os
import pytest
from unittest.mock import patch, MagicMock
from pathlib import Path

from utils.pan_tls_trust import PanTlsStrictPreflightError, preflight_pan_tls_ca_bundle

pytestmark = pytest.mark.security


###############################################
# AC-1 / AC-2: bool verify → no-op
###############################################
class TestPanTlsPreflightCompatMode:
    def test_false_is_noop(self):
        preflight_pan_tls_ca_bundle(False)  # must not raise

    def test_true_is_noop(self):
        preflight_pan_tls_ca_bundle(True)  # must not raise

    def test_empty_string_is_noop(self):
        preflight_pan_tls_ca_bundle("")  # empty string → no-op


###############################################
# AC-3: path not found
###############################################
class TestPanTlsPreflightBundleNotFound:
    def test_nonexistent_path_raises(self, tmp_path):
        missing = str(tmp_path / "nonexistent.pem")
        with pytest.raises(PanTlsStrictPreflightError) as exc_info:
            preflight_pan_tls_ca_bundle(missing)
        assert "pan_tls_ca_bundle_preflight_failed" in str(exc_info.value)

    def test_nonexistent_path_message_is_value_free(self, tmp_path):
        missing = str(tmp_path / "secret_ca.pem")
        with pytest.raises(PanTlsStrictPreflightError) as exc_info:
            preflight_pan_tls_ca_bundle(missing)
        # Message must not contain the actual path
        assert "secret_ca" not in str(exc_info.value)
        assert str(tmp_path) not in str(exc_info.value)


###############################################
# AC-4: path exists but not readable
###############################################
class TestPanTlsPreflightBundleNotReadable:
    def test_unreadable_file_raises(self, tmp_path, monkeypatch):
        bundle = tmp_path / "corp_ca.pem"
        bundle.write_bytes(b"--- BEGIN CERTIFICATE ---")
        # Simulate read failure by patching Path.open
        original_open = Path.open

        def mock_open(self, *args, **kwargs):
            if str(self) == str(bundle):
                raise OSError("permission denied")
            return original_open(self, *args, **kwargs)

        monkeypatch.setattr(Path, "open", mock_open)
        with pytest.raises(PanTlsStrictPreflightError) as exc_info:
            preflight_pan_tls_ca_bundle(str(bundle))
        assert "pan_tls_ca_bundle_preflight_failed" in str(exc_info.value)
        assert "not_readable" in str(exc_info.value)


###############################################
# AC-5: path exists and readable → no raise
###############################################
class TestPanTlsPreflightBundleReadable:
    def test_readable_bundle_passes(self, tmp_path):
        bundle = tmp_path / "corp_ca.pem"
        bundle.write_bytes(b"--- BEGIN CERTIFICATE ---\nSYNTH\n--- END CERTIFICATE ---")
        preflight_pan_tls_ca_bundle(str(bundle))  # must not raise


###############################################
# AC-6 / AC-7 / AC-8: _tls_verify_setting routing
###############################################
class TestRuntimeRunnerTlsVerifySetting:
    def test_default_is_false(self, monkeypatch):
        monkeypatch.delenv("SECURITYEXPERT_PAN_CA_BUNDLE", raising=False)
        monkeypatch.delenv("SECURITYEXPERT_PAN_TLS_VERIFY", raising=False)
        from panorama import panorama_runtime_runner as runner
        assert runner._tls_verify_setting() is False

    def test_ca_bundle_env_returns_path(self, monkeypatch, tmp_path):
        bundle = tmp_path / "ca.pem"
        bundle.write_bytes(b"SYNTH")
        monkeypatch.setenv("SECURITYEXPERT_PAN_CA_BUNDLE", str(bundle))
        monkeypatch.delenv("SECURITYEXPERT_PAN_TLS_VERIFY", raising=False)
        from panorama import panorama_runtime_runner as runner
        assert runner._tls_verify_setting() == str(bundle)

    def test_tls_verify_true_returns_bool_true(self, monkeypatch):
        monkeypatch.delenv("SECURITYEXPERT_PAN_CA_BUNDLE", raising=False)
        monkeypatch.setenv("SECURITYEXPERT_PAN_TLS_VERIFY", "1")
        from panorama import panorama_runtime_runner as runner
        assert runner._tls_verify_setting() is True

    def test_tls_verify_false_returns_bool_false(self, monkeypatch):
        monkeypatch.delenv("SECURITYEXPERT_PAN_CA_BUNDLE", raising=False)
        monkeypatch.setenv("SECURITYEXPERT_PAN_TLS_VERIFY", "0")
        from panorama import panorama_runtime_runner as runner
        assert runner._tls_verify_setting() is False


###############################################
# AC-9: panorama_config_collector preflight — Panorama CA bad path
###############################################
class TestConfigCollectorPanoramaPreflight:
    def test_panorama_bad_ca_bundle_raises_before_api_call(self, monkeypatch, tmp_path):
        missing = str(tmp_path / "missing_panorama_ca.pem")
        monkeypatch.setenv("SECURITYEXPERT_PAN_CA_BUNDLE", missing)
        monkeypatch.delenv("SECURITYEXPERT_PAN_DIRECT_CA_BUNDLE", raising=False)
        monkeypatch.delenv("SECURITYEXPERT_PAN_TLS_VERIFY", raising=False)

        import configuration.panorama_config_collector as collector

        # Patch get_api_key so we can detect if it is ever called.
        api_called = []
        def spy_get_api_key(*args, **kwargs):
            api_called.append(True)
            return "SYNTHETIC_KEY"
        monkeypatch.setattr(collector, "get_api_key", spy_get_api_key)

        cfg = MagicMock()
        cfg.panorama_ip = "192.0.2.200"

        with pytest.raises(PanTlsStrictPreflightError) as exc_info:
            collector.run_panorama_config_evidence(cfg, limit=1)

        assert "pan_config_panorama_tls_preflight_failed" in str(exc_info.value)
        assert not api_called, "get_api_key must not be called when preflight fails"


###############################################
# AC-10: panorama_config_collector preflight — direct CA bad path
###############################################
class TestConfigCollectorDirectPreflight:
    def test_direct_bad_ca_bundle_raises_before_api_call(self, monkeypatch, tmp_path):
        missing_direct = str(tmp_path / "missing_direct_ca.pem")
        monkeypatch.delenv("SECURITYEXPERT_PAN_CA_BUNDLE", raising=False)
        monkeypatch.setenv("SECURITYEXPERT_PAN_DIRECT_CA_BUNDLE", missing_direct)
        monkeypatch.delenv("SECURITYEXPERT_PAN_TLS_VERIFY", raising=False)

        import configuration.panorama_config_collector as collector

        api_called = []
        def spy_get_api_key(*args, **kwargs):
            api_called.append(True)
            return "SYNTHETIC_KEY"
        monkeypatch.setattr(collector, "get_api_key", spy_get_api_key)

        cfg = MagicMock()
        cfg.panorama_ip = "192.0.2.200"

        with pytest.raises(PanTlsStrictPreflightError) as exc_info:
            collector.run_panorama_config_evidence(cfg, limit=1, direct_compare=True)

        assert "pan_config_direct_tls_preflight_failed" in str(exc_info.value)
        assert not api_called, "get_api_key must not be called when direct preflight fails"


###############################################
# AC-11: error message value-free
###############################################
class TestPanTlsErrorMessageValueFree:
    def test_error_contains_no_path_or_credential(self, tmp_path):
        missing = str(tmp_path / "secret_enterprise_ca.pem")
        with pytest.raises(PanTlsStrictPreflightError) as exc_info:
            preflight_pan_tls_ca_bundle(missing)
        msg = str(exc_info.value)
        assert "secret_enterprise_ca" not in msg
        assert str(tmp_path) not in msg
        assert "pan_tls_ca_bundle_preflight_failed" in msg
