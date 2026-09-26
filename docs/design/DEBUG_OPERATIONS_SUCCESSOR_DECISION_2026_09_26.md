# Device Debug — Phase 1 scope

**Status: FROZEN — Phase 1, PO implementation and persistent-history direction, 2026-09-26.**
This supersedes the broader scope previously recorded in this file. The task is
an interface for trying diagnostic commands on FortiManager and other firewalls.
It is not an Automation, Script Execution, configuration-change, or failover build.

## Phase 1 — device, command, output

- List registered devices; resolve execution support separately. Never silently
  choose SSH for a device managed through another transport.
- Provide a command text field and Run action. A saved-command catalog is not
  required in this phase.
- Keep the Output panel visible before, during, and after execution. Bind each
  result to the target and command that produced it.
- Human super administrators can run permitted diagnostic reads directly and see
  actual operational output after removal of credential secrets.
- AIView and agent sessions receive server-masked output. Preserve the line
  structure and non-sensitive values needed for parser work; mask names,
  addresses, serials, principals, and secrets. Generic line labels alone are
  insufficient. Unproven masking must not expose the original text to the agent.
- Each agent-initiated command requires the PO's prior approval of the exact
  target, command, parameters, executing code/version, and output projection.
  Approval is not transferable to another command or target.
- Reuse existing neXus jobs, credentials, transports, and privacy components.
  No parallel SSH client, generalized executor framework, or device writes.
- Keep the existing one-command-per-target-per-minute rule and no automatic retry.

## Execution boundary

Command authoring is not execution authorization. A typed command must resolve
to a reviewed read operation before contact. The existing command gate remains
in force; entering text or clicking Run does not approve an unknown command.
The deployed FortiManager pilot remains the current execution implementation.

Implementation must address the actual gaps: hard-coded FortiManager filtering,
unconditional name masking, missing pre-run Output panel, incomplete arbitrary
text masking, and preserving the agent's per-command PO approval requirement. Do not import failover
approval rules or require a second human approver for a human diagnostic read.
The PO explicitly requests persistent execution history and administrator-readable output. Store bounded, credential-scrubbed responses through the existing encrypted artefact store; persist the actor, command, target, time and outcome on the audited job. No automatic deletion is added. AI output is a server-masked projection; plaintext output never enters operational logs or audit snapshots.

## Phase 2 — deferred

Optional saved commands and reuse of diagnostic runs can be designed after Phase 1
is useful and validated. Automation, scripts, configuration writes, and failover
remain separate backlog items and are not prerequisites for Phase 1.

## Validation

Use synthetic output for authorization and masking tests. Validate all-device
listing, visible output states, human/AI projections, refusal before contact for
unapproved agent commands, and no duplicate dispatch. Any real device command
still requires its own exact PO approval. Do not mark real-device validation done
from tests or deployment alone.

## Implementation boundary

Typed command input is a proposal. The server matches an explicitly permitted
read against the existing signed command gate before admitting an existing job.
Initial SSH support is FortiManager, FortiGate and Cisco ASA; other devices remain
visible and report unsupported commands/transports rather than inventing SSH
support. No script execution, write command, unrestricted shell, or catalog UI.
The admission and worker both verify the exact command and target scope. Existing
one-minute target limit, lease, pre-contact attempt and no-retry behavior remain.

History is paginated and includes failed jobs. Original response and storage keys
are server-only; admin output is decrypted after authorization. AIView output
preserves lines and known operational tokens while masking unknown identity tokens,
addresses and secrets. Old pilot results remain readable without fabricated raw
responses. Failure to store a response must be visible in the terminal outcome.

Phase 1 does not add an approval workflow platform. The agent must obtain exact
PO approval before every device command as directed in AGENTS.md; a super admin
has no second product approver. No device command is approved by this code change.

## Delivery evidence

Code `655bc26` was merged with explicit PO approval and deployed through
`scripts/hosta_deploy.sh` on 2026-09-26. Schema 91 succeeded; service, worker and
configuration were ready 1/1, configuration matched the service digest, and the
output store was accessible. Diagnostic jobs: zero. First real-command and
AIView visual acceptance remain pending; status is automated-validated.
