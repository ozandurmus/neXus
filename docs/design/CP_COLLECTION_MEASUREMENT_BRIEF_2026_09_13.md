# Check Point collection — measurement brief

## Status

**DRAFT — MEASUREMENT BRIEF. NOT AUTHORITY, NOT COMMAND APPROVAL.**

This document authorizes nothing and approves no command. It is the question
set and the exact, ordered, read-only command list the Product Owner runs
**once**, on one Check Point gateway and on one VSX host with one virtual
system, so that a per-vendor Check Point **collection** contract can be
written from measurement instead of from the existing Python or from general
product knowledge. It is the Check Point twin of
`docs/design/CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md`
(DRAFT), written **before** the measurement rather than after it: that
document records what was found; this one records what to look for and in
what order, before anyone looks.

Nothing in this document may be cited as command approval. `AGENTS.md`: a
command's presence in source, or in a measurement brief, is not command
approval. A command line appearing below is a candidate to measure, not a
gate row.

## 1. Scope and authority

### 1.1 What this brief is for

`docs/design/PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_DECISIONS.md`
(FROZEN — "13F" below) §6 lifts the collection gate for Check Point
*inventory* and *configuration* collection at the **method-class** level
only: one SSH session per device per run, to the device's own management
address, read-only Gaia/Expert commands of these classes — version and
identity, HA/cluster state, interfaces, routes, configuration snapshot — plus
the VSX context switch to repeat the reads per virtual system. 13F §6 states
plainly that "every concrete command or API route still needs its own
network-device command gate row … before it is implemented." This brief
supplies the measurement that gate authorship and the collection contract
both need: which concrete command forms actually work on this estate, what
they actually return, and what their completion, timing and secret-bearing
behaviour actually is. It does not itself write a gate row, write a
contract, or run anything.

### 1.2 The exact boundary 13F §6 draws for Check Point

- **Device-facing SSH reads only.** Every collection read goes to the
  gateway's own management address, over SSH, directly — never through the
  management server. 13F §1: "the `cprid_util rexec` path the earlier Python
  collector used is know-how only and is not carried forward." This brief
  therefore does not measure any command issued *through* the management
  server against a gateway; §6 below states this exclusion explicitly.
- **No write of any class.** Every candidate command in §3 and §5 is
  `action_class = read` (class 0, `utils/action_taxonomy.py`). No command
  that mutates state, kernel parameters, or configuration is a candidate
  here, regardless of whether the existing Python issued it.
- **No management-server-mediated execution.** The management server is
  discovery-only per 13F §1; this brief does not ask the Product Owner to
  run anything on it.
- **No script upload.** Every candidate command is typed or piped directly
  over the SSH session this brief describes; nothing is written to the
  gateway's filesystem and executed from there.

Everything the Product Owner records under this brief comes from a live SSH
session to one gateway's own management address, and from one VSX host's own
management address for §5. Nothing else.

## 2. The question set

Grouped by what the eventual Check Point collection contract must be able to
state. Each question is phrased so its answer is a fact about observed
behaviour, not an opinion or a restatement of what the existing Python
assumed.

**Shell landing and invocation (13F §6's "read-only Gaia/Expert commands")**

- **Q-a.** Which shell does a validated administrator land in immediately
  after SSH authentication on this estate's gateways: Gaia Clish, or
  Expert? `AGENTS.md` "Check Point" states the *validated enterprise
  administrator login shell is Expert* and that *Gaia Clish must be invoked
  explicitly with `clish -c` where required* — this question is whether that
  general statement holds for the specific gateway and VSX host measured
  here, not an assumption to carry forward unmeasured.

**Version and identity**

- **Q-b.** Which command reports software version, and does its output
  differ between the candidate forms (`show version`, `show version all`,
  and their `clish -c` equivalents if the landing shell is Expert)?
- **Q-c.** Which command carries the serial and the hardware model, and do
  both appear in one read, or does completing the identity picture require
  more than one command?

**HA / cluster role**

- **Q-d.** Which command reports HA/cluster role, and what is its
  vocabulary on a standalone gateway versus a ClusterXL member — same
  command, different output shape, or a command that behaves differently
  (errors, empty output, a different field set) depending on role?

**Interfaces and addresses**

- **Q-e.** Which command enumerates interfaces **with every address on
  them**, including a cluster's virtual (floating) address where one
  exists, and how is a virtual address distinguished in that output from a
  member's own address? 13F §5 CL-1 makes this load-bearing: the cluster
  virtual address is collected as data from this read and is never a
  connection target — the answer must show where in the output the virtual
  address appears and by what marker it is told apart from an interface's
  own address.

**Routes**

- **Q-f.** Which command prints the routing table, and does it need a wider
  table selector (an "all tables" form) to show every route, or does the
  narrower form already return everything relevant?

**Configuration**

- **Q-g.** What is the configuration read called, does it need a raised
  timeout relative to the other reads, and what is a successful output's
  canonical shape (what a reader can check for, without reproducing any
  actual configuration line, to know the read succeeded rather than
  returned an error or a truncated fragment)?

**VSX**

- **Q-h.** For VSX: how is a virtual-system context entered; does that
  context survive across more than one subsequent command issued on the
  **same** SSH channel, or must it be re-entered before every single
  command; and which of Q-b through Q-g change once a virtual-system
  context is active (different command literals, different output shape,
  or a field that is present physically but absent — or differently valued
  — per virtual system)?

**Secrets**

- **Q-i.** For each read in §3 and §5 individually — not only the
  configuration read — can its output carry a credential, private key,
  shared secret, or other secret-bearing value? 13F CF-1 and `AGENTS.md`
  "Raw-evidence law" already establish this for the configuration read;
  this question asks it of every other read too, rather than assuming only
  the configuration read is sensitive.

**Completion signalling**

- **Q-j.** For each read: how is command completion actually signalled —
  SSH channel exit status, a shell prompt reappearing, an idle-timeout
  fallback, or none of these reliably? Where a command is issued via
  `clish -c` as a one-shot process versus typed into an already-open Clish
  or Expert shell, does the completion signal differ?

## 3. The command sequence

**Before running any of this: no address, hostname, serial or configuration
line is to be pasted back anywhere — not into this document, not into a
chat, not into a ticket.** Record only field/column names, output shape,
line counts, and whether an absent value appears as an explicit placeholder
or as a dropped column, per `AGENTS.md` "Sensitive identity reporting law."
Where a step's evidence would require reproducing a real value to prove a
point, record instead that the value was present/absent/plausible-shaped and
move on.

Run on one already-enrolled, already-credentialed Check Point gateway
(standalone or a ClusterXL member — record which), reached at its own
management address exactly as 13F §1 requires. Each line's placeholder is
shown in `<ANGLE_BRACKETS>`; nothing here is an approved command.

| # | Command (as typed) | Answers | Class | RECORD |
|---|---|---|---|---|
| 1 | `ssh <ADMIN_USER>@<GATEWAY_MGMT_ADDRESS>` | Q-a | read-only, class 0 | which shell the prompt lands in (Clish/Expert); whether a banner appears; whether a host-key prompt appears |
| 2 | `show version all` | Q-b | read-only, class 0 | whether the command is accepted as typed in the landing shell without `clish -c`; field names present (e.g. a version-string field, a build/OS field); line count |
| 3 | `show version` | Q-b | read-only, class 0 | whether output differs in shape or field count from #2; if identical, record that explicitly rather than omitting the row |
| 4 | `clish -c "show version all"` | Q-a, Q-b | read-only, class 0 | whether this form is needed at all (i.e. whether #2 already worked directly); whether output shape matches #2 |
| 5 | `cpstat os -f hw_info` | Q-c | read-only, class 0 | whether a serial field and a model field both appear in this one read, or whether one is missing; field names; line count |
| 6 | `cphaprob stat` | Q-d | read-only, class 0 | the role vocabulary observed (field names and value tokens only, never the device's own name); whether the command errors or returns an empty/short output on a standalone gateway |
| 7 | `show interfaces table` | Q-e | read-only, class 0 | column headers; whether a cluster virtual address, if one exists on this gateway, appears in this output and by what column/marker it is distinguished from an interface's own address; row count |
| 8 | `show interfaces` | Q-e | read-only, class 0 | whether this narrower/legacy form's shape differs from #7; whether the virtual-address marker (if any) is the same |
| 9 | `cphaprob -a -m if` | Q-e | read-only, class 0 | whether this command runs at all outside the management-server-mediated CPRID path it was previously observed under (§6 excludes that path — this row measures whether the *same command literal*, issued directly over this brief's own SSH session, still reports cluster virtual interfaces); field names; whether it errors on a standalone (non-cluster) gateway |
| 10 | `show route` | Q-f | read-only, class 0 | column headers; row count; whether every expected route class (e.g. a default route) appears |
| 11 | `show route all` | Q-f | read-only, class 0 | whether this wider selector returns strictly more rows than #10, and what additional route class(es) appear only here |
| 12 | `show hostname` | Q-a (corroboration) | read-only, class 0 | whether this command succeeds without `clish -c` in the landing shell — cheap corroboration of Q-a before relying on it for §4 |
| 13 | `show configuration` | Q-g | read-only, class 0 | see §4 — this row's recording requirements are stated separately in full |
| 14 | `clish -c "show configuration"` | Q-g (corroboration) | read-only, class 0 | only run if #13 required `clish -c` to succeed; whether shape matches #13 |

Run rows 2–13 a **second time**, unmodified, before ending the session, to
answer Q-j: for each, record whether the command's own completion (prompt
reappearing, or the one-shot process exiting) was unambiguous both times, and
whether any output arrived after the shell appeared to be ready for the next
command.

## 4. The configuration read — what must be recorded

13F §3 requires **one read, two outputs**: a sanitized view copy (CF-1) and
an untouched, encrypted backup copy whose digest is compared for change
(CF-2). Both outputs come from the same underlying read (`show
configuration`), so this section is the measurement that lets the collection
contract state what that one read actually behaves like, independent of
either output's own handling.

- **Size.** The approximate line count and byte size of a typical `show
  configuration` output on this gateway. A count and a size, not the
  content.
- **Stability across two reads.** Run `show configuration` twice in
  immediate succession on the same, otherwise-unchanged gateway (this is
  the CF-2 "digest question" — CF-2 depends on a later read's digest
  differing meaning a real configuration change, not read-to-read noise).
  Record: do the two outputs have the same line count? If a checksum tool
  is available in the shell (record which, if any, without treating its
  use as a new command needing its own gate row beyond this measurement),
  do the two digests match? If they do not match on an unchanged device,
  record what kind of line differs (see next bullet's categories) rather
  than assuming the mismatch is noise.
- **Line categories for the sanitized view copy.** 13F CF-1 requires
  credential material and other secret-bearing values to be removed from
  the sanitized copy. Record, **by category only, never by example line**:
  which kinds of configuration lines were observed to carry
  secret-shaped material (e.g. a password/hash field, a pre-shared key, a
  certificate or private-key block, an SNMP community string) so the
  collection contract's redaction rule can name categories it has actually
  seen exist on this output, rather than a category list copied from
  general Check Point knowledge.
- **Canonical successful shape.** What, structurally, tells a reader the
  read succeeded and returned a real configuration rather than an error,
  a permission-denied message, or a truncated fragment — for example
  (record only whether this or an equivalent shape held, never the actual
  lines): does the output consist of a recognizable, repeated line-command
  pattern throughout, or does a successful read look different in kind
  from a failed one in some other way?
- **Timeout.** Whether the default session/command timeout used for the
  earlier reads (§3) was sufficient for `show configuration` to complete,
  or whether it required a longer wait — record the approximate elapsed
  time, not a precise benchmark.

## 5. VSX and one virtual system

Repeat on one VSX host, with one virtual system selected for measurement.
Kept separate from §3–§4 because 13F §4 requires per-virtual-system
inventory, and `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_
CONTRACT.md` (FROZEN — "C4" below) §5.2 already flags the VSX context switch
as this repository's own precedent for a command whose *later* commands
depend on shell state established earlier in the *same* session — a
fundamentally different mechanism from the physical-gateway reads in §3,
which `docs/design/UI2_0_B1_05_CP_INVENTORY_EXTRACTION_CONTRACT.md` (DRAFT,
superseded in scope by 13F §7) measured as needing no such shared state. Do
not assume the VSX context behaves like that document's non-VSX subset, and
do not assume it behaves like any discovery-side domain context (the
discovery handover §3 row 2 describes a *different* mechanism, on a
*different* plane — the management server's own shell function, not a
gateway's Expert/Clish context).

| # | Command (as typed) | Answers | Class | RECORD |
|---|---|---|---|---|
| 15 | `ssh <ADMIN_USER>@<VSX_HOST_MGMT_ADDRESS>` | Q-a | read-only, class 0 | landing shell; whether it matches §3 row 1's gateway |
| 16 | `vsenv <VSID>` | Q-h | read-only, class 0 (context switch) | whether the command succeeds and what confirmation, if any, it prints; whether the prompt itself changes to show the active virtual-system context |
| 17 | `show version all` (issued immediately after #16, same channel) | Q-h | read-only, class 0 | whether output differs from a physical-context read on the same host; whether the VSX context from #16 is still evidently active |
| 18 | `cphaprob stat` (same channel, same context as #16) | Q-h | read-only, class 0 | role vocabulary inside the virtual-system context; whether it differs from a physical-context or standalone-gateway read |
| 19 | `show interfaces table` (same channel, same context) | Q-h, Q-e | read-only, class 0 | whether only the virtual system's own interfaces appear, or the physical host's interfaces also appear; virtual-address marker behaviour inside this context |
| 20 | `show route` (same channel, same context) | Q-h, Q-f | read-only, class 0 | whether only the virtual system's own routes appear |
| 21 | *(new SSH channel, no `vsenv` re-issued)* `show version all` | Q-h | read-only, class 0 | **the decisive row**: does this new channel land back in the physical/default context, proving the context does not survive a fresh channel, or does it retain the virtual-system context from #16 somehow? Record which |
| 22 | `vsenv <VSID>`; `clish -c 'show configuration'` (same line, same channel, as one composite) | Q-g, Q-h | read-only, class 0 | whether the composite form (context switch immediately followed by the read, one command line) succeeds where a split-channel attempt would not; canonical-shape and size questions from §4, repeated for this virtual system's own configuration |

Additionally record, once, for the VSX host as a whole:

- **Context re-entry cost.** Whether re-issuing `vsenv <VSID>` before every
  single command (rather than once per channel) changes any observed
  output or timing, if the Product Owner has time to try both patterns.
- **Per-virtual-system scope of Q-b through Q-g.** For each of version,
  identity, HA role, interfaces, routes and configuration: does the
  virtual-system read return a value that plausibly differs from the
  physical host's own (e.g. a distinct interface set), or does it appear
  to inherit the physical host's answer unchanged? Record per field,
  since 13F §4 requires "the same [inventory] per virtual system" and the
  collection contract needs to know which fields are actually
  virtual-system-scoped versus physically inherited on this estate.

## 6. What this brief deliberately does not ask

- **No write of any class.** No command that adds, deletes, sets, or
  otherwise mutates gateway state is a candidate anywhere in this
  document, regardless of whether the existing Python or any other source
  issues it for a different purpose.
- **No policy operation.** Nothing touching Check Point security policy,
  installed rules, or policy push/install is asked here; that is a wholly
  separate product surface this brief does not touch.
- **No backup command.** `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_
  ENGINE_CONTRACT.md` (FROZEN) owns backup and restore commands (the
  `RB.x` sequence); this brief's configuration-read measurement (§4) is
  about the *view* and *digest* behaviour of `show configuration`, not
  about producing or restoring a backup artefact.
- **No failover-readiness battery.** The wider HA-readiness command set
  (e.g. `cphaprob syncstat`, `cphaprob -a if`, `cphaprob -ia list`,
  `cphaprob tablestat`) has its own gate row and its own measurement
  history under the OP.0b preflight work; this brief asks only for the
  single `cphaprob stat` role read that 13F §4's inventory scope actually
  needs, not the readiness battery.
- **No Panorama or Palo Alto question.** 13F §6's Palo Alto paragraph is a
  separate transport (HTTPS XML API) and a separate measurement exercise;
  nothing here concerns it.
- **No reachability test.** Every command in §3 and §5 is asked because
  its output is a fact the collection contract needs to state (a version
  string, a role, an address, a route, a configuration shape) — never
  because running it merely proves the gateway answers SSH. A command
  whose only purpose would be "is the device up" is out of scope here;
  13F's own liveness caution (mirrored from the discovery side, where
  management connection state was "disproved as a liveness signal") is a
  reason to be careful about reading liveness into any of these answers
  incidentally, not a reason to add a dedicated reachability probe.

## 7. The acceptance shape of the answer

A complete measurement record — the follow-on findings document — mirrors
`docs/design/CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md`'s
own structure, so the eventual collection contract has a fixed target:

1. **Method table.** For each of Q-a through Q-j, the method actually used
   to answer it (which command, in which shell, over which kind of
   channel) — method first, exact syntax second, exactly as the discovery
   handover's §3/§4 pairing does.
2. **Command forms measured.** The literal command lines run (as §3/§5
   list them, with placeholders), annotated with which succeeded, which
   needed a `clish -c` wrapper, and which needed a raised timeout.
3. **Field behaviour table.** One row per extracted field (version,
   serial, model, HA role, each interface/address, each route, the
   configuration read's shape) stating: which command produced it, its
   observed presence/absence behaviour (placeholder token, dropped
   column, or genuinely empty — the discovery handover §5's last row shows
   why this distinction matters for a positional or semi-structured
   parser), and whether it changed under a virtual-system context.
4. **The cluster-virtual-address marker**, stated explicitly as its own
   line: what distinguishes a virtual address from a member's own address
   in the interface read's output.
5. **The VSX context-survival finding**, stated explicitly as its own
   line: does `vsenv <VSID>` survive across more than one command on the
   same channel, and does a fresh channel require it to be re-issued —
   row 21 of §5 is the decisive evidence for this.
6. **The configuration read's size/stability/category findings** from §4,
   as their own subsection.
7. **Open verification items** — anything §2's questions could not be
   fully answered from one gateway and one VSX host (for example, a
   ClusterXL-member vocabulary for Q-d if the measured gateway was
   standalone, or vice versa), listed in the order they should be closed,
   exactly as the discovery handover's own §7 does.
8. **A secret-bearing-output line** for every read in §3 and §5, per Q-i —
   not only the configuration read — even where the answer is "none
   observed."

Only once a record in this shape exists does the per-vendor Check Point
collection contract get written from measurement; this brief's own
completion is that a Product Owner has run §3 and §5 and produced a record
in this shape, not that any particular answer came out one way or another.

## 8. Cross-references

- `docs/design/PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_
  IDENTITY_DECISIONS.md` (FROZEN) — §1 device-facing SSH transport, §3
  configuration one-read/two-output rule, §4 inventory scope, §5 cluster
  and VSX multiplicity, §6 the collection-gate lift this brief's
  measurement feeds, §7 consequences for `UI2_0_B1_05`.
- `docs/design/PO_DECISION_RECORD_2026_09_13E_PRODUCT_SEQUENCE_AND_
  SERVICE_INDEPENDENCE.md` (FROZEN) — §1 step 3 names collection as
  gated on exactly the per-vendor method statement 13F supplies; this
  brief's measurement is the evidence base for the contract that
  discharges that gate, not the gate lift itself.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  (FROZEN) — §2.3 closed step-kind set and gate-reference applicability
  (every `exec` step in the eventual capability spec names a gate
  reference, exactly as this brief's §3/§5 tables annotate each command);
  §4 the VSX/ClusterXL target model (`virtual_system_ref`,
  `cluster_member_ref`) this brief's per-virtual-system and per-member
  recording discipline serves; §5.2 the interactive-SSH evidence bar
  `vsenv` is this repository's own precedent for, which §5 of this brief
  measures directly rather than assumes.
- `docs/design/CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_
  13.md` (DRAFT) — the discovery-side twin of this document and the
  structural model §7 above mirrors; its own §3 row 2 and §7 item 4
  describe a *different*, management-server-side context mechanism that
  this brief's §5 explicitly does not assume behaves the same way as
  `vsenv`.
- `docs/design/UI2_0_B1_05_CP_INVENTORY_EXTRACTION_CONTRACT.md` (DRAFT,
  superseded in scope by 13F §7) — the narrow non-VSX `show version`/
  `cphaprob stat` subset, its channel-drain `UNKNOWN` (§4 of that
  document), and its `ssh_exec` transport choice for that narrow subset;
  read here as the prior measurement whose command forms this brief's §3
  re-tests against the wider 13F §4 scope, not as an answer this brief
  copies forward unmeasured.
- `docs/design/DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md`
  §3.5, §3.6, §4.1, §4.3, §8 — the existing Python collector's command
  literals and session shapes for the direct-SSH probe, the CP
  configuration collector, the VSX inventory path, the VSX configuration
  path, and the full gate-status table; cited throughout §2, §3 and §5
  above as know-how that raises a question, never as a source this brief
  copies an answer from.
- `AGENTS.md` — "Check Point" (validated login shell, `vsenv` as a
  validated context mechanism), "Evidence laws," "Raw-evidence law,"
  "Sensitive identity reporting law," "Identity law," "Network action
  taxonomy."

## Contradiction check

No contradiction was found among `PO_DECISION_RECORD_2026_09_13F`,
`PO_DECISION_RECORD_2026_09_13E`, `UI2_0_C4`, and the discovery handover
within this brief's scope. 13F §6, 13E §1 step 3 and C4 §4/§5.2 are
consistent layers — 13E gates the step, 13F states the per-vendor method
class the gate needed, and C4 supplies the runtime schema and the
interactive-transport evidence bar that method class will eventually be
checked against. `UI2_0_B1_05`'s narrower, non-VSX `ssh_exec` finding is
explicitly superseded in scope by 13F §7, not contradicted by it: 13F
widens the inventory set §4 requires and defers the transport-per-command
question (§5 above) to this brief's own VSX measurement rather than
assuming the narrower document's finding extends to VSX. No `RELAY_QUESTION`
is raised.
