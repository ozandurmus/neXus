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
| `HOST-A` | A second product of the same company, whose logger workload runs there today | Dedicated, **no** `sudo`, not in `docker`/`adm`/`wheel` — to be created before the ceiling rises above `HOST_R` | Product Owner | 2026-09-15 | `HOST_R` | `DEV` | Product Owner |

## Notes on `HOST-A`

The Product Owner is the infrastructure authority for this host and authorized
its use in session on 2026-09-15. The host is intended to become entirely
neXus's in future; until then a second product's workload runs on it and every
`HOST_X` prohibition applies to that workload without exception.

The ceiling is `HOST_R` today. It rises to `HOST_W1` when, and only when: the
dedicated non-sudo account exists, the Kubernetes runtime has been installed by
the human under `HOST_W2`, the kubeconfig handed to the agent is scoped to
neXus's own namespace, and the baseline of `EV-1` is recorded as ledger entry
zero.

The key installed during setup sits on an account that holds `sudo`. That is the
operator's account, not an agent identity. It is replaced per `CR-2` and the
account password rotated.
