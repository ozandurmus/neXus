# relay/

Local, file-based relay transport for same-machine Product-Owner/engineer
coordination. Contract: `docs/design/LOCAL_RELAY_PROTOCOL.md` (DRAFT).

One tracked JSON file per movement: `NXS-LOCAL-<4-digit-id>-<slug>.json`,
created and appended to only via `scripts/local_relay.py` (`create` /
`append` / `status` / `validate`) -- never hand-edited. These files are
durable governance history, the same category as `project/*.json`, not
scratch or temporary output.

This does not replace `docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md`'s
GitHub-issue transport, which stays the path for cross-machine, cloud-agent,
or human-external-visibility work.
