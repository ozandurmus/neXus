"""GOV.RELAY.1 governance convergence tests.

These tests pin relay vocabulary and entry-point convergence without changing
or duplicating the NEXUS_SESSION_PACKET v2 parser contract.
"""
from pathlib import Path

import scripts.gov_session_transfer as session_transfer


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "docs" / "design" / "NEXUS_AGENT_RELAY_PROTOCOL.md"
BOOTSTRAP = ROOT / ".github" / "prompts" / "relay-bootstrap.prompt.md"


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_relay_contract_is_frozen_and_preserves_session_packet_v2():
    contract = _text(CONTRACT)
    assert "FROZEN — PRODUCT OWNER APPROVED" in contract
    assert "does not modify" in contract
    assert session_transfer.PROTOCOL_VERSION == 2
    assert session_transfer.MESSAGE_TYPES == ("SESSION_START", "SESSION_CLOSE")


def test_relay_vocabulary_is_closed_and_start_end_are_invalid():
    contract = _text(CONTRACT)
    bootstrap = _text(BOOTSTRAP)
    for marker in ("RELAY_ACK", "RELAY_NOTE", "RELAY_DECISION", "RELAY_CORRECTION"):
        assert marker in contract
        assert marker in bootstrap
    for invalid in ("RELAY_START", "RELAY_END"):
        assert f"`{invalid}`" in contract
        assert invalid in bootstrap
    assert "Only the Product Owner may authoritatively issue `RELAY_DECISION`" in contract


def test_relay_ready_is_locator_only_and_stored_packets_are_revalidated():
    contract = _text(CONTRACT)
    bootstrap = _text(BOOTSTRAP)
    assert "RELAY_READY owner/repository#issue" in contract
    assert "only as a locator" in bootstrap
    assert "validate the exact stored text" in bootstrap
    assert "final engineering comment must be exactly one" in bootstrap


def test_every_required_agent_entry_point_uses_the_shared_bootstrap():
    required = (
        ROOT / "AGENTS.md",
        ROOT / "AI_START_HERE.md",
        ROOT / "CLAUDE.md",
        ROOT / ".github" / "copilot-instructions.md",
        ROOT / ".github" / "prompts" / "build-start.prompt.md",
        ROOT / ".github" / "prompts" / "build-close.prompt.md",
    )
    for path in required:
        text = _text(path)
        assert ".github/prompts/relay-bootstrap.prompt.md" in text, path
        assert "NEXUS_AGENT_RELAY_PROTOCOL.md" in text or "build-" in path.name

