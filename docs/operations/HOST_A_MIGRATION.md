# HOST-A migration — the decision tree

Operational companion to
`docs/design/PO_DECISION_RECORD_2026_09_15A_THE_DEVELOPMENT_HOST_AND_WHAT_AN_AGENT_MAY_DO_ON_IT.md`,
which is the authority. This file decides nothing. It is the order the steps run
in, who performs each one, and the condition that stops the sequence — so that
the tier of a step is settled before anyone is mid-migration and inclined to
settle it conveniently.

Every step carries its tier (`15A` §4). Read the rule there, not the shorthand
here. The holder of the assistant seat performs the `HOST_R` and `HOST_W1`
steps and no others (`15A` SE-1, SE-2); the human performs every `HOST_W2`
step from commands the assistant prepared.

## Before anything

| # | Step | Tier | Who |
|---|---|---|---|
| 0 | The host is in `docs/design/HOST_REGISTER.md` with a stated ceiling | — | Product Owner |
| 1 | Baseline ledger entry exists: counts and shapes, no identities | `HOST_R` | assistant |

An unregistered host authorizes no command at all, including a read
(`AGENTS.md`, host action boundary). If step 1 has not been written, nothing
below may start: without a baseline, §7's incident question — what changed —
has no answer.

## Phase A — measure, decide nothing

| # | Step | Tier | Who |
|---|---|---|---|
| 2 | Read the incumbent's network blocks and listening ports as counts and ranges | `HOST_R` | assistant |
| 3 | Determine whether a CNI would collide with the daemon-managed rules already present | `HOST_R` | assistant |
| 4 | Confirm the chosen CGNAT blocks (`15A` PL-3) collide with nothing on the host or the corporate network | `HOST_R` | assistant |
| 5 | Record 2-4 as one ledger entry, including what could not be determined | — | assistant |

**Stop condition.** If step 3 cannot be answered from reads alone, it is not
escalated by trying it. It becomes a question for the incumbent's
administrators, and the sequence halts at step 5 with `CAUSE: UNKNOWN`
(`15A` IN-1). A collision discovered after installation is an incident on
someone else's production service.

Reading the incumbent's data, logs, volumes or database to answer any of these
is `HOST_X` and has no authorization form — the shape of its networking is not
the content of its traffic.

## Phase B — install, which the agent does not do

| # | Step | Tier | Who |
|---|---|---|---|
| 6 | Prepare the exact installation commands, with the CGNAT CIDRs, and the validation plan | `HOST_R` | assistant |
| 7 | Run them | `HOST_W2` | **human** |
| 8 | Issue a kubeconfig scoped to neXus's own namespace, and nothing wider | `HOST_W2` | **human** |
| 9 | Confirm the incumbent is still serving, from its own health surface, not from its data | `HOST_R` | assistant |
| 10 | Ledger entry: what was run, what the validation plan showed, what the assistant now holds | — | assistant |

After step 8 the assistant has no host access at all (`15A` HA-4). Everything
below is a cluster operation that happens to be on this host.

**Stop condition.** If step 9 shows the incumbent degraded, the migration stops
and the incident procedure runs (`15A` §7). Rolling forward to "finish the
migration first" is not available: the incumbent is a production service and
this is not our product's outage to spend.

## Phase C — carry the state across

| # | Step | Tier | Who |
|---|---|---|---|
| 11 | Export the hand-created Secrets from the old cluster, into the operator's custody outside the repository | — | assistant |
| 12 | Take the database dump and verify it is readable, not merely taken | — | assistant |
| 13 | Build images in-cluster | `HOST_W1` | assistant |
| 14 | Create the Secrets in the new namespace | `HOST_W1` | assistant |
| 15 | Deploy, let the migrations run, restore the dump | `HOST_W1` | assistant |
| 16 | Verify against the product's own surfaces; ledger entry | `HOST_W1` | assistant |

The dump alone decrypts nothing. Step 11 precedes step 12 and both precede any
teardown of the old cluster, which is not part of this sequence and happens only
after the new environment has served real work.

Step 14 never re-applies a Secret manifest over an existing Secret — it wipes
the value (`roles/PO.md` §1b). Key material created here is not printed, logged
or committed.

## What is never in this sequence

The incumbent's containers, volumes, networks, database or logs; a host root
shell; `sudo` under any justification; a container runtime socket; a global
prune; a daemon restart; a host reboot. These are `HOST_X`. They are not
unlikely steps that need care — there is no form in which they are authorized,
including one the Product Owner signs, because the data is not ours to hold.

## After

The environment is `DEV` until the Product Owner moves it (`15A` §6). The
profile is a property of the workload, not of the host: running on a host that
also carries production does not make our workload production, and does not make
the incumbent's data reachable.
