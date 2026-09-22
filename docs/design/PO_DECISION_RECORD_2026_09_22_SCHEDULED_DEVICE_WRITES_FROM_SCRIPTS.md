# PO Decision Record — 2026-09-22 — Scheduled, ledgered device writes from a script run

## Status

**RATIFIED — PRODUCT OWNER DECISION, 2026-09-22** ("you may write the
amendment for the device-write side; this product will do this in the
future"). Amends `AGENTS.md` "Network action taxonomy". Resolves the
contradiction reported in `SCRIPT_EXECUTION_CONTRACT.md` §6.

## What the Product Owner wants

"As a security admin I must be able to say: with this user, go and do this.
Cisco ASA, for example: clustering does not carry the policy to the other
device, so I must be able to say *take the active member's configuration
weekly and push it to this device* — to replicate it to the DR site. Or a new
feature needs periodic debugging the vendor does not support; I run my own
scripts on a schedule and mail the output."

## The rule before this record

`AGENTS.md`: "No automatic (unscheduled-trigger, non-ledgered) network-device
write/change operation is permitted at the current maturity; class 1
controlled recovery writes are permitted only through their `RB.x`
contracts, are never console-submittable."

## The amendment

A network-device write **may** run from a scheduled script run when **all**
of the following hold; a write that lacks any one of them is refused before
the command is issued:

1. **Named target.** The device (or devices) the write goes to is recorded on
   the script's own record when a `security_admin` saves it — never chosen
   or discovered by the script at run time.
2. **Named actor and credential.** The run acts as a neXus actor who holds a
   write role for that target, and reaches the device with a credential-store
   reference the Product Owner granted write rights for that target. A script
   never carries a credential literal.
3. **Ledgered.** Every write command, its target, the script version, the
   actor, the credential reference and the outcome are recorded on the job
   ledger before the command is issued and after it returns; the audit trail
   shows the write under the actor, not under "the scheduler".
4. **Gated command.** The write command has its own network-device command
   gate row with `action_class: write` and a per-vendor contract of the
   `RB.x` kind — measured on a real device first (for Cisco ASA: the
   configuration-replace mechanism and its rollback shape). Free text still
   never reaches a device.
5. **Read-only by default.** A script run's device access is read-only unless
   its record says `writes: allowed` for the named target, set by a
   `security_admin` and audited; the schedule, the actor and the write
   permission are three separate, visible fields on the screen and in
   `nexus-cli script-list`.

What does **not** change: no write is console-submittable in the sense of
"typed and sent"; the browser still only submits typed intent; class 2+
(policy changes, failover) stays outside this amendment and needs its own
record.

## Consequence stated

The product will change firewall configuration on a timer, under the actor
the security admin named. A wrong script or a wrong target is now a change on
a production device, not a report. The five conditions above are what makes
that change traceable and bounded; they do not make it safe by themselves.
The Product Owner accepts that, and named the DR replication use case as the
reason.
