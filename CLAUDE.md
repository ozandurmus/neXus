@AGENTS.md

Claude-specific delta only; `roles/ENGINEER.md` carries the reading order.

- Model/reasoning tiers for this tool: `docs/reference/MODEL_TIER_MAP.md`.
- Session-boundary packets: `py scripts/gov_session_transfer.py render`,
  validated, per `docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md` (FROZEN).
- PO assistant role: `docs/design/GOV_PO_ROLE_MIGRATION.md` (FROZEN) —
  `nexus-po` skill in a separate session, or the gated subagent form. An
  engineering session never invokes `nexus-decision-council`.
- Relay bootstrap: for `RELAY_READY owner/repository#issue`, follow
  `.github/prompts/relay-bootstrap.prompt.md` and `NEXUS_AGENT_RELAY_PROTOCOL.md`.
