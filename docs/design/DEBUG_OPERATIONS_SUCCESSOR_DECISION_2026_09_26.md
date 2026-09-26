# Debug / Parser and the Operations execution model

**Status: RATIFIED — product direction approved by the Product Owner on 2026-09-26.**
This records the approved successor to the FortiManager-only pilot. It authorizes
design and implementation preparation, not a device command, device write, or
new retention policy. The execution, approval, and output-transfer details below
must be resolved against existing code before their implementation contract is frozen.

## Approved product behavior

- The device selector lists all registered devices. Device visibility does not
  imply a compatible transport or permission to execute against that device.
- The operator can type a command or select a saved command. The catalog is a
  convenience, not the only authoring interface.
- The Output panel is always present: empty, pending approval, queued, running,
  completed, failed, or outcome unknown. It shows which target and command the
  result belongs to even after the operator changes the form.
- A human super administrator sees operational names, addresses, and the actual
  response after credential-secret removal. No additional human approver is
  required for that administrator's permitted diagnostic read.
- The AIView projection preserves response structure and non-sensitive values
  needed for parsing while masking names, addresses, serials, principals, and
  other sensitive values on the server. Replacing every line with a generic
  category is not an adequate parser output.
- An agent can propose a command and use its masked result. Each agent execution
  requires prior PO approval of its exact target, command, parameters, executing
  code/version, and output projection. Changes invalidate that approval.
- Debug performs diagnostic reads only. Configuration changes, recovery writes,
  and failover retain their own operation-specific authorization and checks.

## Shared execution, distinct workflows

Reuse the existing job admission, leases, step attempts, credential references,
worker transports, audit trail, and result presentation. Do not build another
SSH client or executor framework for this screen.

| Surface | Responsibility |
| --- | --- |
| Debug / Parser | One target and one diagnostic command, with a visible result. |
| Automation | Ordered device steps, parameters, conditions, and schedules. |
| Script Execution | Operator programs in the isolated neXus runner; device access uses the controlled neXus operation path. |
| Failover | Fresh preflight, operation authorization, HA-entity lock, transition, and independent verification. |

Shared transport does not make a diagnostic approval a failover authorization.
An unknown post-dispatch outcome is never an instruction to retry a mutation.

## Authoring and execution boundary

A browser may submit command text as a **non-executing proposal**. The proposal
is not a worker request and cannot contact a device. Executable requests refer
to an immutable server-owned command revision and target using opaque IDs.

Before contact, the server must resolve vendor/platform/context, command bytes
and validated parameters, read classification, transport, timeout, retry rule,
frequency, and output policy. A command's prefix is not proof that it is read-only.
Unsupported or unproven semantics are reported explicitly and cannot execute.

The desired successor supports registering a reviewed diagnostic command as data,
without a new application build for each command. **The existing migration and
fixture gate remains in force until the successor's runtime registration gate is
specified and frozen.** This decision does not quietly replace that gate with a
Run button or an approval checkbox.

Authorization must be server-enforced. An agent cannot label its request as a
human action, approve itself, or obtain unmasked output by selecting another
view. Actor classification must use protected identity/session metadata, not a
hard-coded account name. AIView controls projection; it is not execution authority.

## Implementation preparation

The current pilot already provides jobs, rate limiting, and a bounded result
record. Extend those paths where applicable rather than copy them.

Source inspection on 2026-09-26 established:

- `DiagnosticService.targets()` filters to FortiManager and masks every caller.
  Move presentation through the shared server privacy policy and separate device
  listing from executable-command eligibility.
- `PrivacyMaskingResponseBodyAdvice` already owns AIView projection, but
  `TopologyNamePseudonymizer.maskText()` replaces only previously known names.
  This is not a complete sanitizer for arbitrary CLI output or credential secrets.
- `FailoverAuthorizationService`, `FailoverExecutionService`, and
  `FailoverScheduleService` exist in source. The older UI2 failover architecture
  document's roadmap status is not an accurate inventory of these classes.
  Source presence is not evidence of approved real-device execution.
- Failover authorization is operation-specific and includes two-person rules;
  do not reuse its entire policy for human diagnostic reads. Its in-memory
  token store is not a durable diagnostic approval store.
- `ArtefactStore` and `BackupDownloadService` provide encrypted streaming and
  authorized result delivery patterns. Their existence does not authorize
  retaining new diagnostic responses under backup/configuration policies.

Resolve these three concrete code boundaries before freezing the implementation:

1. Runtime command registration and exact approval binding: reuse any existing
   immutable command, authority, and job records; define atomic consumption and
   rejection before contact. No self-declared `approved=true` request field.
2. Protected agent attribution: locate the identity/session owner and define how
   human and agent requests are distinguished, including mixed-role sessions.
3. Response delivery: reuse the common masking and secured result facilities;
   define how the worker delivers the administrator response without plaintext
   logs or unapproved raw retention. Masking must be tested with synthetic
   identities, secrets, multiline output, and unknown vendor fields. If complete
   masking cannot be established, withhold the affected output from the AI view.

Acceptance includes all-device selection, optional catalog and text entry,
always-visible Output, correct human/AI projection, refused unapproved agent
requests, approval invalidation on edits, and no duplicate device dispatch.
Real-device validation remains a separate PO-approved action.

## Existing decisions preserved

- `PO_DECISION_RECORD_2026_09_22_AUTOMATION_EDITOR_AND_SCRIPT_EXECUTION.md`
- `SCRIPT_EXECUTION_CONTRACT.md`
- `PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md`
- `UI2_0_FAILOVER_ENGINE_ARCHITECTURE.md`

The approved authoring direction broadens the diagnostic pilot. It does not
declare the planned Automation, Script Execution, or failover execution modules
implemented, and it does not authorize their write commands through Debug.
