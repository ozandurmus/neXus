# CLAUDE.md — SecurityExpert

Claude-specific delta only. The canonical law is `AGENTS.md`; the cold-start
entry point and reading order is `AI_START_HERE.md`; the detailed engineering
lifecycle is `docs/AI_DEVELOPMENT_PROTOCOL.md`. This file does not restate them.

## Claude delta

- **Working language: English** for all conversation, analysis, commit messages
  and docs. Vendor CLI commands, API fields and code identifiers stay verbatim.
  Provide a Turkish translation or explanation only when explicitly asked.
- **Reasoning routing.** Use extended thinking for new architecture, storage/CAS,
  security boundaries, vendor-semantic ambiguity, deployment/server/container
  work, cross-subsystem root cause, and phase closure. Use normal reasoning for
  deterministic implementation, tests, documentation and validation. Do not
  spend high reasoning on mechanical work; recommend the next movement type and
  reasoning level at each meaningful checkpoint.
- **Test economy.** One-shot, file-backed runs:
  `py -m pytest -q > pytest_result.log 2>&1`. Do not re-run the full suite while
  the last evidence still holds.
- **Toolchain.** The workspace Python / PowerShell / Git setup is already
  validated. Use the existing `py` command directly; never invoke environment
  bootstrap or interpreter selection. On a real command failure, report it and
  stop.

Everything else — context order, movement types, build lifecycle, privacy / DLP,
the network-device command gate, approval boundaries, and the
`SESSION START` / `SESSION CLOSE` contracts — is in `AGENTS.md` and
`docs/AI_DEVELOPMENT_PROTOCOL.md`.
