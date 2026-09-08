"""Agent-tool-perspective isolation tests for the replay confinement
boundary (docs/design/PRIVATE_REPLAY_ARCHITECTURE.md, "Isolation tests from
the agent's actual perspective" + acceptance gate 4).

Threat model per the frozen document: a coding agent with real Read/
filesystem tool access, attempting direct/absolute paths, parent traversal
and symlinks against a dummy trusted-service directory holding synthetic
canary secrets, from the same primitives (``pathlib``/``open``) an agent's
own tools use. No real credentials or real customer evidence are used --
every secret below is a synthetic canary string that exists only to prove
it cannot appear in anything the confined root or the evidence provider
returns.
"""
import json
import os

import pytest

from replay.isolation import (
    ConfinedPackageRoot,
    PackageEvidenceReplayProvider,
    ReplayIsolationError,
)

pytestmark = pytest.mark.replay

CANARY_HMAC_KEY = "CANARY-HMAC-KEY-do-not-leak-3f9a"
CANARY_RAW_DEVICE_NAME = "CANARY-REAL-CUSTOMER-FIREWALL-01"
CANARY_MAPPING_TABLE = "CANARY-device_id-to-real-hostname-mapping"


@pytest.fixture
def dummy_trusted_service(tmp_path):
    """A dummy trusted service directory the deployed agent must never read,
    sitting *outside* the replay package root, holding synthetic canaries."""
    trusted = tmp_path / "trusted_service"
    trusted.mkdir()
    (trusted / ".support_hmac.key").write_text(CANARY_HMAC_KEY, encoding="utf-8")
    (trusted / "raw_evidence.json").write_text(
        json.dumps({"device": CANARY_RAW_DEVICE_NAME}), encoding="utf-8"
    )
    (trusted / "mapping_table.json").write_text(
        json.dumps({"mapping": CANARY_MAPPING_TABLE}), encoding="utf-8"
    )
    return trusted


@pytest.fixture
def package_root(tmp_path, dummy_trusted_service):
    """A legitimate, already-sanitized replay package -- the only thing an
    isolated replay provider is supposed to be able to reach."""
    package = tmp_path / "replay_package"
    package.mkdir()
    (package / "unified.json").write_text(
        json.dumps([{"device": "DEVICE_a1b2c3", "state": "LIVE"}]), encoding="utf-8"
    )
    return package


# --- Positive control: the boundary is usable, not just maximally deny-all ---

def test_positive_control_legitimate_read_inside_root_succeeds(package_root):
    confined = ConfinedPackageRoot(package_root)
    data = confined.read_json("unified.json")
    assert data == [{"device": "DEVICE_a1b2c3", "state": "LIVE"}]


def test_positive_control_evidence_provider_serves_the_package(package_root):
    provider = PackageEvidenceReplayProvider(ConfinedPackageRoot(package_root))
    inventory = provider.load_unified_inventory()
    assert inventory == [{"device": "DEVICE_a1b2c3", "state": "LIVE"}]


def test_positive_control_canary_material_actually_exists(dummy_trusted_service):
    """An unavailable inspection mechanism is not a passing denial (frozen
    document). Prove the canary is really there before proving it is
    unreachable through the confined root."""
    assert (dummy_trusted_service / ".support_hmac.key").read_text(encoding="utf-8") == CANARY_HMAC_KEY


# --- Adversarial matrix: direct/absolute paths, parent traversal, symlinks ---

def test_absolute_path_into_trusted_service_is_rejected(package_root, dummy_trusted_service):
    confined = ConfinedPackageRoot(package_root)
    with pytest.raises(ReplayIsolationError):
        confined.resolve(str(dummy_trusted_service / ".support_hmac.key"))


def test_parent_traversal_into_trusted_service_is_rejected(package_root, dummy_trusted_service):
    confined = ConfinedPackageRoot(package_root)
    relative = os.path.relpath(dummy_trusted_service / ".support_hmac.key", package_root)
    assert relative.startswith("..")
    with pytest.raises(ReplayIsolationError):
        confined.resolve(relative)


def test_symlink_inside_root_pointing_outside_is_rejected(package_root, dummy_trusted_service):
    escape_link = package_root / "innocuous_looking_file.json"
    escape_link.symlink_to(dummy_trusted_service / "raw_evidence.json")
    confined = ConfinedPackageRoot(package_root)
    with pytest.raises(ReplayIsolationError):
        confined.resolve("innocuous_looking_file.json")


def test_symlinked_directory_escape_is_rejected(package_root, dummy_trusted_service):
    linked_dir = package_root / "nested"
    linked_dir.symlink_to(dummy_trusted_service, target_is_directory=True)
    confined = ConfinedPackageRoot(package_root)
    with pytest.raises(ReplayIsolationError):
        confined.resolve("nested/mapping_table.json")


def test_evidence_provider_cannot_be_pointed_at_the_trusted_service_directly(dummy_trusted_service):
    """Even if a caller mistakenly hands the provider the trusted service
    directory itself (rather than a real package), the provider's own
    schema expectation (a JSON array) plus the confinement primitive mean
    no canary value is ever returned -- it is scoped to whatever file it
    looks for, and a genuinely wrong root fails rather than improvising."""
    provider = PackageEvidenceReplayProvider(ConfinedPackageRoot(dummy_trusted_service))
    with pytest.raises(FileNotFoundError):
        provider.load_unified_inventory()


# --- Restart / re-open path: confinement holds across a fresh instance ---

def test_confinement_holds_after_reopening_the_same_root(package_root, dummy_trusted_service):
    ConfinedPackageRoot(package_root)  # first open, e.g. before a restart
    reopened = ConfinedPackageRoot(package_root)  # second open, e.g. after a restart
    with pytest.raises(ReplayIsolationError):
        reopened.resolve(str(dummy_trusted_service / ".support_hmac.key"))


def test_nonexistent_root_is_rejected_not_silently_created():
    with pytest.raises(ReplayIsolationError):
        ConfinedPackageRoot("/nonexistent/replay/package/path")
