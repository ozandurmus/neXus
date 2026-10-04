"""M8.2 -- endpoint-specific local trusted-key lookup.

Verifies ``utils.cp_ssh_trust.lookup_trusted_host_key`` (frozen M8 contract
§4 step 1): a local-only, read-only, exact normalized endpoint+port check
against the same ``known_hosts`` source ``apply_strict_host_key_policy``
already reads, returning a typed fail-closed outcome. No network/device
contact, no key enrollment, no TOFU. All tests provision a synthetic
``known_hosts`` in an isolated user profile (RFC 5737 addresses only, keys
generated in-process and discarded) -- the same pattern
``tests/test_op0b_s8_p01_cp_ssh_trust_preflight_correction.py`` uses.
"""
from __future__ import annotations

import socket
from pathlib import Path
from types import MappingProxyType

import paramiko
import pytest

from utils.cp_ssh_trust import (
    REASON_ENDPOINT_NOT_TRUSTED,
    REASON_TRUST_SOURCE_MALFORMED,
    REASON_TRUST_SOURCE_UNREADABLE,
    TrustedKeyLookupResult,
    host_key_fingerprint,
    lookup_trusted_host_key,
)

pytestmark = pytest.mark.security


def _isolated_profile(tmp_path: Path, monkeypatch) -> Path:
    home = tmp_path / "home"
    (home / ".ssh").mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("USERPROFILE", str(home))
    monkeypatch.delenv("HOMEDRIVE", raising=False)
    monkeypatch.delenv("HOMEPATH", raising=False)
    return home / ".ssh" / "known_hosts"


class TestFound:
    def test_bare_default_port_entry_matches_no_port_lookup(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", None)

        assert isinstance(result, TrustedKeyLookupResult)
        assert result.trusted is True
        assert result.reason is None
        assert result.fingerprints == {key.get_name(): host_key_fingerprint(key)}

    def test_bare_entry_matches_explicit_default_port_22(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", 22)

        assert result.trusted is True
        assert result.fingerprints == {key.get_name(): host_key_fingerprint(key)}

    def test_non_default_port_uses_bracketed_openssh_form(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"[192.0.2.10]:2222 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", 2222)

        assert result.trusted is True
        assert result.fingerprints == {key.get_name(): host_key_fingerprint(key)}

    def test_multiple_key_types_all_returned(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        ecdsa_key = paramiko.ECDSAKey.generate()
        rsa_key = paramiko.RSAKey.generate(2048)
        known_hosts.write_text(
            f"192.0.2.11 {ecdsa_key.get_name()} {ecdsa_key.get_base64()}\n"
            f"192.0.2.11 {rsa_key.get_name()} {rsa_key.get_base64()}\n",
            encoding="utf-8",
        )

        result = lookup_trusted_host_key("192.0.2.11", None)

        assert result.trusted is True
        assert result.fingerprints == {
            ecdsa_key.get_name(): host_key_fingerprint(ecdsa_key),
            rsa_key.get_name(): host_key_fingerprint(rsa_key),
        }


class TestFailClosed:
    def test_no_entry_for_endpoint_refuses(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.99", None)

        assert result.trusted is False
        assert result.reason == REASON_ENDPOINT_NOT_TRUSTED
        assert result.fingerprints == {}

    def test_entry_exists_only_at_default_port_does_not_match_other_port(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", 2222)

        assert result.trusted is False
        assert result.reason == REASON_ENDPOINT_NOT_TRUSTED

    def test_missing_known_hosts_file_refuses(self, tmp_path, monkeypatch):
        _isolated_profile(tmp_path, monkeypatch)  # directory exists, file does not

        result = lookup_trusted_host_key("192.0.2.10", None)

        assert result.trusted is False
        assert result.reason == REASON_TRUST_SOURCE_UNREADABLE

    def test_malformed_known_hosts_file_refuses(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        known_hosts.write_text("not a valid known_hosts line at all\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", None)

        assert result.trusted is False
        assert result.reason == REASON_TRUST_SOURCE_MALFORMED

    def test_empty_endpoint_refuses_without_reading_trust_source(self, tmp_path, monkeypatch):
        _isolated_profile(tmp_path, monkeypatch)

        result = lookup_trusted_host_key("", None)

        assert result.trusted is False
        assert result.reason == REASON_ENDPOINT_NOT_TRUSTED
        assert result.fingerprints == {}

    def test_reason_and_fingerprints_are_value_free(self, tmp_path, monkeypatch):
        """No returned string may ever be the raw endpoint or key material."""
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        miss = lookup_trusted_host_key("192.0.2.42", None)
        assert "192.0.2" not in (miss.reason or "")

        hit = lookup_trusted_host_key("192.0.2.10", None)
        for fp in hit.fingerprints.values():
            assert fp.startswith("SHA256:")
            assert key.get_base64() not in fp


class TestNoNetworkOrDeviceContact:
    def test_lookup_never_opens_a_socket(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        def _forbidden_socket(*args, **kwargs):
            raise AssertionError("lookup_trusted_host_key must not open a socket")

        monkeypatch.setattr(socket, "socket", _forbidden_socket)
        monkeypatch.setattr(socket, "create_connection", _forbidden_socket)

        result_hit = lookup_trusted_host_key("192.0.2.10", None)
        result_miss = lookup_trusted_host_key("192.0.2.99", None)

        assert result_hit.trusted is True
        assert result_miss.trusted is False

    def test_deliberately_untrusted_endpoint_never_reaches_credential_or_connect(
        self, tmp_path, monkeypatch
    ):
        """Contract §8 acceptance criterion 2, the M8.2-testable half: a
        target with no trusted-key entry must refuse at the lookup itself,
        before any caller could reach credential resolution or
        ``ssh.connect()``. This module exposes no credential-resolution or
        connect entry point of its own (that belongs to the future M8.3
        producer) -- this test pins that the lookup's own refusal is total
        and synchronous, with no partial/pending state and no exception
        requiring a connect attempt to observe."""
        _isolated_profile(tmp_path, monkeypatch)  # no known_hosts file provisioned

        result = lookup_trusted_host_key("192.0.2.200", 22)

        assert result.trusted is False
        assert result.reason == REASON_TRUST_SOURCE_UNREADABLE
        assert result.fingerprints == {}


class TestFingerprintsAreImmutable:
    def test_fingerprints_is_a_mapping_proxy(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", None)

        assert isinstance(result.fingerprints, MappingProxyType)

    def test_item_assignment_is_refused(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", None)

        with pytest.raises(TypeError):
            result.fingerprints[key.get_name()] = "tampered"

    def test_deletion_is_refused(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", None)

        with pytest.raises(TypeError):
            del result.fingerprints[key.get_name()]

    def test_constructing_with_a_plain_dict_still_yields_an_immutable_result(self):
        """The dataclass field accepts a plain dict at construction (as
        lookup_trusted_host_key itself passes) but always normalizes to a
        MappingProxyType -- a caller cannot bypass immutability by handing
        the constructor a mutable mapping and keeping a reference to it."""
        source = {"ssh-ed25519": "SHA256:AAAA"}
        result = TrustedKeyLookupResult(trusted=True, reason=None, fingerprints=source)

        assert isinstance(result.fingerprints, MappingProxyType)
        source["ssh-ed25519"] = "tampered"
        assert result.fingerprints["ssh-ed25519"] == "SHA256:AAAA"

    def test_result_itself_stays_frozen(self, tmp_path, monkeypatch):
        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", None)

        with pytest.raises(Exception):  # dataclasses.FrozenInstanceError
            result.trusted = False


class TestFingerprintFormatMatchesLiveConnectionEvidence:
    """Contract §6/§7 requires comparing this lookup's fingerprint against a
    live connection's captured fingerprint -- so the two must be provably
    byte-for-byte identical, not merely two implementations that happen to
    agree today. utils.cp_ssh_trust.host_key_fingerprint is the single
    shared implementation: configuration/checkpoint_config_probe.py imports
    and calls it directly for its own live-connection fingerprint
    (`_connect()`), rather than defining a second copy."""

    def test_checkpoint_config_probe_imports_the_same_function_object(self):
        import configuration.checkpoint_config_probe as probe

        assert probe.host_key_fingerprint is host_key_fingerprint

    def test_same_key_yields_the_same_fingerprint_via_both_module_references(self):
        import configuration.checkpoint_config_probe as probe

        key = paramiko.ECDSAKey.generate()

        via_cp_ssh_trust = host_key_fingerprint(key)
        via_checkpoint_config_probe = probe.host_key_fingerprint(key)

        assert via_cp_ssh_trust == via_checkpoint_config_probe
        assert via_cp_ssh_trust.startswith("SHA256:")

    def test_lookup_result_fingerprint_matches_a_live_connection_fingerprint_for_the_same_key(
        self, tmp_path, monkeypatch
    ):
        """End-to-end: the fingerprint lookup_trusted_host_key returns for a
        known_hosts entry is exactly what a live connection presenting the
        same key would produce via checkpoint_config_probe's own code path
        -- proven without a real connection, since both sides call the one
        shared function on the one shared key object."""
        import configuration.checkpoint_config_probe as probe

        known_hosts = _isolated_profile(tmp_path, monkeypatch)
        key = paramiko.ECDSAKey.generate()
        known_hosts.write_text(f"192.0.2.10 {key.get_name()} {key.get_base64()}\n", encoding="utf-8")

        result = lookup_trusted_host_key("192.0.2.10", None)
        live_connection_fingerprint = probe.host_key_fingerprint(key)

        assert result.fingerprints[key.get_name()] == live_connection_fingerprint
