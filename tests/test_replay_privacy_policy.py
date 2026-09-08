import json

import pytest

from replay.privacy_policy import (
    FieldPolicy,
    FieldTreatment,
    PolicyCoverageError,
    PrivacyFidelityPolicy,
)
from utils.support_bundle import Tokenizer

pytestmark = pytest.mark.replay

CANARY_HOSTNAME = "CANARY-FW-REAL-HOSTNAME-9f2b"
CANARY_IP = "203.0.113.77"
CANARY_SERIAL = "CANARY-SERIAL-0731ab"
CANARY_PASSWORD = "CANARY-super-secret-password-xyz"
CANARY_TAG = "CANARY-internal-project-codename"
CANARY_ERROR = "CANARY-stack-trace-with-path/etc/shadow"


def _record():
    return {
        "device": CANARY_HOSTNAME,
        "management_ip": CANARY_IP,
        "serial": CANARY_SERIAL,
        "credential": {"username": "admin", "password": CANARY_PASSWORD},
        "tags": [CANARY_TAG, "prod"],
        "error": CANARY_ERROR,
        "state": "LIVE",
        "interfaces": [
            {"name": "eth0", "ip": CANARY_IP, "state": "up"},
        ],
    }


def _no_canary_leak(payload: dict) -> None:
    blob = json.dumps(payload, ensure_ascii=False)
    for canary in (CANARY_HOSTNAME, CANARY_IP, CANARY_SERIAL, CANARY_PASSWORD, CANARY_TAG, CANARY_ERROR):
        assert canary not in blob, f"canary value leaked raw into transformed output: {canary!r}"


def test_transform_removes_every_canary_raw_value():
    policy = PrivacyFidelityPolicy()
    tok = Tokenizer(b"test-key-one")
    transformed = policy.transform(_record(), tok)
    _no_canary_leak(transformed)


def test_transform_excludes_credential_subtree_entirely():
    policy = PrivacyFidelityPolicy()
    tok = Tokenizer(b"test-key-one")
    transformed = policy.transform(_record(), tok)
    assert transformed["credential"] is None


def test_transform_excludes_tag_list_and_error_field():
    policy = PrivacyFidelityPolicy()
    tok = Tokenizer(b"test-key-one")
    transformed = policy.transform(_record(), tok)
    assert transformed["tags"] is None
    assert transformed["error"] is None


def test_transform_pseudonymizes_nested_interface_ip():
    policy = PrivacyFidelityPolicy()
    tok = Tokenizer(b"test-key-one")
    transformed = policy.transform(_record(), tok)
    iface = transformed["interfaces"][0]
    assert iface["ip"] != CANARY_IP
    assert iface["state"] == "up"
    assert iface["name"] is not None


def test_pseudonym_is_deterministic_across_calls_same_key():
    policy = PrivacyFidelityPolicy()
    tok = Tokenizer(b"same-export-key")
    first = policy.transform(_record(), tok)
    second = policy.transform(_record(), tok)
    assert first["device"] == second["device"]
    assert first["device"] is not None


def test_pseudonym_changes_with_a_different_export_key():
    policy = PrivacyFidelityPolicy()
    token_a = policy.transform(_record(), Tokenizer(b"export-key-a"))["device"]
    token_b = policy.transform(_record(), Tokenizer(b"export-key-b"))["device"]
    assert token_a != token_b, "pseudonym must depend on the export secret, not just the raw value"


def test_unclassified_field_fails_closed_not_pass_through():
    policy = PrivacyFidelityPolicy()
    tok = Tokenizer(b"test-key-one")
    with pytest.raises(PolicyCoverageError):
        policy.transform({"totally_unclassified_field": CANARY_HOSTNAME}, tok)


def test_synthesize_without_registered_synthesizer_fails_closed():
    policy = PrivacyFidelityPolicy()
    tok = Tokenizer(b"test-key-one")
    with pytest.raises(PolicyCoverageError):
        policy.transform({"timestamp": "2026-09-08T00:00:00Z"}, tok)


def test_synthesize_with_registered_synthesizer_never_returns_raw_value():
    raw_timestamp = "2026-09-08T00:00:00Z"
    policy = PrivacyFidelityPolicy(synthesizers={"timestamp": lambda value: "REPLAY_T+0"})
    tok = Tokenizer(b"test-key-one")
    transformed = policy.transform({"timestamp": raw_timestamp}, tok)
    assert transformed["timestamp"] == "REPLAY_T+0"
    assert raw_timestamp not in json.dumps(transformed)


def test_pseudonymize_field_policy_requires_a_domain():
    with pytest.raises(ValueError):
        FieldPolicy("device", FieldTreatment.PSEUDONYMIZE)


def test_ambiguous_container_under_pseudonymize_fails_closed():
    policy = PrivacyFidelityPolicy(
        field_policies=(FieldPolicy("device", FieldTreatment.PSEUDONYMIZE, "device"),)
    )
    tok = Tokenizer(b"test-key-one")
    with pytest.raises(PolicyCoverageError):
        policy.transform({"device": {"nested": "unexpected-shape"}}, tok)
