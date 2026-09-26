# Device Debug — Phase 1 scope

**Status: RATIFIED PRODUCT SCOPE — narrowed by the Product Owner on 2026-09-26.**
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
text masking, and agent-specific approval enforcement. Do not import failover
approval rules or require a second human approver for a human diagnostic read.
No new raw-output retention policy is authorized by this scope decision.

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

## Implementation checkpoint

The feature branch now lists all devices, returns real names to authorized
administrators and masked names to AIView, permits masked read inspection, and
keeps Output visible and bound to the submitted command when the form changes.
The existing execution gate is unchanged: this is not yet a general command
runner, agent approval implementation, or complete administrator-output path.
The output-lifetime choice (encrypted retained result or session-only result)
has been requested from the PO before freezing that boundary. No worker has
been dispatched, migration applied, deployment made, or device command sent.
