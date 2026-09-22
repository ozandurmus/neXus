# PO Decision Record — 2026-09-22 — Automation authored in the product; Script Execution module

## Status

**RATIFIED — PRODUCT OWNER DECISION, 2026-09-22.** Amends
`TASK_EXECUTOR_AUTOMATION_CONTRACT.md` §3.2 / §4 (authoring surface) and adds a
second module. The Product Owner's words (paraphrased from Turkish, session
transcript 2026-09-22):

1. *Automation* goes under the Operations tab. "On these firewalls, as in the
   Backbox screens I sent, I want to write a script by adding steps one by
   one."
2. *Script Execution*: "The scripts I put there can be Python, Java, bat —
   it does not matter; I want to run them on schedules: a .sh, a .py, a jar.
   This is firewall-independent. I can write the script there in an IDE-like
   editor or add scripts one by one, execute on a schedule, report the result
   to me, log to syslog or send mail through an SMTP relay."

## Decision 1 — Automation steps are authored in the screen

The automation contract's frozen answer "packs only, imported through an
audited route" is **replaced**: the operator composes steps in the product
(Operations › Automation), field by field as Backbox does — type, command,
timeout, sleep, run-if condition, hide output, save output, set status.

What stays, because it is law and the Product Owner did not ask to change it:

- **Every device command is gated.** The editor's "command" field is not free
  text sent to a device: each step selects a **gated command** from the
  registry for the target vendor (the same rows the backup, inventory and
  configuration reads use), with its declared variable positions filled from
  the task's fields. A command that has no gate row cannot be chosen; a new
  command means a new gate row (network-device command gate), exactly as
  today. This is how "no command originates in the browser" survives an
  in-product editor: the browser chooses and parameterises registered
  commands; it never composes one.
- **Class**: read steps only in the first release; a write-class step needs
  its RB.x contract (unchanged).
- **Secrets** only as credential-store references (unchanged).
- Saving a task produces the same signed pack the contract describes; the
  editor is the authoring surface, the pack is the stored form, so
  export/import/clone come for free.

## Decision 2 — Script Execution module (new)

A **host-side scheduled script runner**, firewall-independent: operator-
supplied `.sh` / `.py` / `.jar` (or inline text from an IDE-like editor) run
on a cron schedule, with the result reported on the Jobs screen and pushed to
syslog and/or SMTP relay.

Constraints the Product Owner accepts by this record (stated plainly):

- The scripts run **inside neXus's own runtime**, not on HOST-A itself and not
  on any incumbent workload: a dedicated, unprivileged runner container in the
  `ui2` namespace with no cluster credentials, no access to the artefact
  store, the credential store or the database, a private scratch volume, an
  egress that the operator declares per script (target hosts/ports), and a
  CPU/memory/time budget. Anything a script needs from neXus it gets through
  `nexus-cli` with its own session — the same gates as an operator.
- Script upload/edit is `role:security_admin`, audited, and every version is
  stored with its SHA-256; a run records which version ran, exit code,
  duration, and the last N KB of stdout/stderr (secret-masking as for job
  output).
- Notifications: syslog (RFC 5424 over TCP/UDP, target declared in settings)
  and SMTP relay (host, port, from, to; no authentication secret in a script —
  the relay credential lives in the credential store).

## What changes in the contracts

- `TASK_EXECUTOR_AUTOMATION_CONTRACT.md`: §3.2 "Who authors, and where" and
  §4 row 1 are superseded by Decision 1 (in-screen editor over registered,
  gated commands). §3.1's pack format is the stored form of what the editor
  saves. Everything else stands.
- A new contract `SCRIPT_EXECUTION_CONTRACT.md` is to be written for Decision
  2 (runner isolation, schedule, notifications, report) before implementation.

## Consequence stated

An automation editor in the product widens who can put commands in front of a
firewall from "whoever can change the repository" to "whoever holds the
automation role" — mitigated by the gate registry (only registered commands),
read-only steps, and the audit of every save and run. A host-side script
runner executes operator code on the neXus host's cluster — mitigated by the
isolated, unprivileged runner and declared egress; it is still the most
powerful thing the product will run, and the Product Owner accepts that.
