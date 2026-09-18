# Host register

## Status

**FROZEN — PRODUCT OWNER DIRECTIVE, 2026-09-15.** The allowlist named by
`PO_DECISION_RECORD_2026_09_15A_THE_DEVELOPMENT_HOST_AND_WHAT_AN_AGENT_MAY_DO_ON_IT.md`
HR-1. A host absent from this file authorizes no command on it, including a
read. Silence is refusal.

This file carries tokens, roles and postures. It **never** carries an address,
hostname, username, port or key fingerprint; those live in the operator's own
store (`AGENTS.md` sensitive identity reporting law, `PRIVACY_AND_DATA_HANDLING.md`
CLASS 2). A reader who needs to reach a host asks its credential holder.

| Token | Incumbent workload owner | Agent account posture | Credential holder | Admitted | Tier ceiling | Profile | Incident route |
|---|---|---|---|---|---|---|---|
| `HOST-A` | A second product of the same company, whose logger workload runs there today | Dedicated, **no** `sudo`, not in `docker`/`adm`/`wheel` — or operator account used without sudo per 2026-09-19 Amendment | Product Owner | 2026-09-15 | `HOST_W1 + TROUBLESHOOT` | `DEV` | Product Owner |

## Notes on `HOST-A`

The Product Owner is the infrastructure authority for this host and authorized
its use in session on 2026-09-15. The host is intended to become entirely
neXus's in future; until then a second product's workload runs on it and every
`HOST_X` prohibition applies to that workload without exception.

**PO Amendment (2026-09-19):** Per Product Owner directive on 2026-09-19, the
tier ceiling is raised from `HOST_R` to `HOST_W1 + TROUBLESHOOT`. Reasoning
agents in the assistant seat (including Claude, Codex/Copilot, and Antigravity)
are authorized to connect via SSH to `HOST-A` using the operator account for:
- Executing in-cluster build and deployment rollouts (`bash ~/run_build.sh`).
- Managing neXus namespace resources (`kubectl -n ui2 ...`, `kubectl -n ui2-build ...`).
- Running non-mutating network reachability diagnostic probes (`ping`, `nc`,
  `traceroute`, `curl`, and raw SSH banner tests) to troubleshoot device connectivity.

The agent MUST NOT invoke `sudo`, modify `/etc` or system packages, touch
incumbent workloads, or run commands outside the neXus development workspace.
