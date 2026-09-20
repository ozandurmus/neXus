Copilot-specific delta only. Canonical constitution: `/AGENTS.md`; reading
order and role: `/roles/ENGINEER.md`. Follow `/AI_START_HERE.md`'s reading
order exactly; do not improvise a shorter or reordered version of it.

- Model/reasoning tiers for this tool: `/docs/reference/MODEL_TIER_MAP.md`.
- Session-boundary packets: `py scripts/gov_session_transfer.py render`,
  validated, per `/docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md` (FROZEN).
- Relay bootstrap: for `RELAY_READY owner/repository#issue`, follow
  `.github/prompts/relay-bootstrap.prompt.md` and
  `/docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md`; the locator is not authority.
- Toolchain and privacy/DLP deltas: unchanged, see `/AGENTS.md` and
  `/PRIVACY_AND_DATA_HANDLING.md`.
- HOST-A Access & Troubleshooting Authorization: Per PO Decision Record
  Amendment 2026-09-19 (PO APPROVED), you are explicitly authorized to connect
  via SSH to HOST-A to run `bash ~/run_build.sh`, inspect and manage neXus
  resources via `kubectl -n ui2 ...`, and execute non-mutating network
  diagnostic probes (`ping`, `traceroute`, `nc`, SSH banner checks). You MUST NOT
  invoke `sudo` or touch incumbent workloads (`HOST_X`).
- Multi-Model Consultation & External Reviewers: Per constitutional law in `/AGENTS.md`, whenever the Product Owner or a task directive requests consultation with Claude/Fable, Codex/Astra, or another external reviewer, the agent MUST ALWAYS execute the consultation through the authorized scripts in `scripts/` (e.g. `scripts/consult_claude_*.py` via `claude -p`, `scripts/consult_codex_*.py` via `codex exec`). Simulating reviews via internal subagents or personas is strictly prohibited.
