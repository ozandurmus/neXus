# Device import and enrollment contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-14.** This document is
implementation authority for device import and the enrollment confirm,
within the scope §1 states and no further. It lifts no collection gate: the
first contact's two identity reads are approved separately, per vendor, in
`DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md`.

**Review of record.** The Product Owner assistant read §2, §3 and §4 clause
by clause on 2026-09-13 and answered the two open items on the relay
(`relay/NXS-LOCAL-0147`: EC-6a, EC-12); the Product Owner approved the freeze
in the 2026-09-14 session after directing, in
`PO_DECISION_RECORD_2026_09_14_DEVICE_ADD_ENTRY_PEER_FOLLOW_AND_CREDENTIAL_STORE.md`
(FROZEN), how the dialog that drives this contract behaves: exactly address,
vendor and credential are entered (no display-name field, DA-1); the device's
facts are read on first contact (DA-2); and a single member's peer is
followed and corroborated before a unit forms (PF-1 to PF-5). Those clauses
are that record's, cited here as the successor clauses this document reads
alongside; nothing in §2–§6 is edited to absorb them. Previous status:
DRAFT — FOR PRODUCT OWNER FREEZE (2026-09-13).

It fixes what happens between discovery and collection: how a candidate row
from either FROZEN discovery contract becomes a device the product owns, how
a cluster, an HA pair and a virtual system are represented without ever
becoming a device row, what the enrollment confirm step does on first
contact, and what a second discovery run means for rows the first one
created. It contains no code, no schema migration and no screen design.

## 1. Scope and authority

### 1.1 In scope

- The mapping from a candidate row (either FROZEN discovery contract) to a
  `devices` / `endpoints` / `credential_references` row set
  (`UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md`, hereafter `B1-04b`).
- The representation of clusters, HA pairs and virtual systems as target
  modifiers (`UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  §4.2, hereafter `C4`), never as rows.
- The enrollment confirm's first-contact behaviour under
  `PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_
  DECISIONS.md` §2 (hereafter `13F`).
- Re-discovery semantics: what a second discovery run means for device rows
  the first run's import created.
- What the operator sees, stated as constraints on a future screen, not as a
  screen.

### 1.2 Out of scope, and not authorized here

- Any collection read. `13F` §6's methods (inventory, configuration) belong
  to the per-vendor collection contracts named there; this document does not
  extend, narrow or restate the collection gate.
- Any screen design.
- Any schema migration text. Where a `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md`
  (`C1`) successor column is needed, §10 names it by role and purpose; no
  `CREATE TABLE` or `ALTER TABLE` statement appears anywhere in this
  document.
- Individual Device registration, beyond citing `B1-04b` §4 as the pattern
  the `DRAFT`/`ENROLLED` split already establishes for a single manually
  registered device. This document does not reopen or restate that flow.
- Which service owns the operational unit after import
  (`PO_DECISION_RECORD_2026_09_13C_DISCOVERY_SERVICE_BOUNDARIES.md`
  (hereafter `13C`) §5's first bullet). This stays open, and §11 records it
  as open rather than choosing an answer.
- Credential storage, the secret backend, and `credential_references` itself.
  `B1-04b` §6's reference model is assumed as it stands and is not amended.
- Any field a discovery contract's candidate row does not carry. Where the
  row lacks a fact, import lacks it, and this document says so rather than
  inventing one (`CP_AND_VSX_DISCOVERY_CONTRACT.md`, hereafter `CP-DISCOVERY`,
  and `PAN_DISCOVERY_CONTRACT.md`, hereafter `PAN-DISCOVERY`).
- Applying `FROZEN` to this document, or amending any `FROZEN` document.
  Where a `FROZEN` document needs a successor clause, §10 names it; nothing
  here is a silent amendment.
- Implementation of any kind: no service, no query runner, no schema, no
  screen. This movement produces one document.

### 1.3 Authority chain

1. `AGENTS.md` — the identity law (identifiers are opaque), the evidence
   laws, the `UNKNOWN`/fail-closed law, the sensitive identity reporting law,
   and the authority hierarchy this document's own §11 applies.
2. `13F` §1, §2, §5 and §7 — collection transport (cited for boundary only,
   §1.2), the identity-mismatch rule (§2, the substantive rule this
   document's §4 turns into construction rules), clusters and virtual
   systems (§5), and §7's explicit naming of this contract and the
   successor clauses it requires of `B1-04b`.
3. `13C` §1–§4b (terminology, service decomposition, the device-add entry
   point, and cluster/virtual-system/transport/identity resolution) and §5
   (what stays open).
4. `PO_DECISION_RECORD_2026_09_13E_PRODUCT_SEQUENCE_AND_SERVICE_INDEPENDENCE.md`
   (hereafter `13E`) — the build order this contract's dispatch belongs to,
   and the per-feature independence rule (`SI-2`) this document's own
   boundary respects: it reads the discovery contracts' output shape and
   `B1-04b`'s object model, and introduces no dependency the other direction.
5. `B1-04b` — the three records (`devices`, `endpoints`,
   `credential_references`), the enrollment-state table and its transition
   rule (§3), the manual registration flow this document's import path runs
   alongside rather than replaces (§4), and the credential-reference model
   (§6).
6. `C4` §4 (`CP-D6`, the VSX/ClusterXL target model) and §2.3 (the closed
   step-kind set the enrollment confirm's device contact is shaped by).
7. `CP-DISCOVERY` §4–§8 and `PAN-DISCOVERY` §4–§10 — the candidate row
   shapes this document maps from, and the discovery/import boundary each
   fixes on its own side (`CP-DISCOVERY` §8, `PAN-DISCOVERY` §10).
8. `PO_DECISION_RECORD_2026_09_12.md` §2 — the existing Python
   (`utils/device_registry.py`, `discovery_lifecycle.py`,
   `first_contact_producer.py`, `pre_enrollment_identity_probe.py`) is
   know-how only. No rule below is derived from it, restated from it, or a
   plan to port, wrap, transliterate or invoke it; it is cited here, once,
   to record that this document was written without re-deriving from it.

### 1.4 What this document is written against

The merged Java domain cores, read directly rather than assumed:
`com.securityexpert.nexus.ui2.discovery.cp.CandidateRow` (key = `(owning
domain, stable identifier)`; `kind` one of the ten `CandidateKind` values
plus `UNCLASSIFIED`/`AMBIGUOUS`; own/management `Address`; an `Optional
<ClusterReference>`; an `Optional<HostLink>` sealed as `Linked` /
`Missing` / `Ambiguous`; a `ClusterLink` sealed as `Linked` /
`NotEvaluable`) and `com.securityexpert.nexus.ui2.discovery.pan.CandidateRow`
(`Serial stableIdentifier`; `peerSerialReference`; `PairingOutcome` sealed as
`Paired(peerStableIdentifier)` / `NotEvaluable(Reason)` with reasons
`NO_PEER_SERIAL` / `PEER_NOT_FOUND` / `SELF_REFERENTIAL` /
`ONE_SIDED_CLAIM`; a separate `unreciprocatedInboundClaimantSerials` list;
an `Optional<HostLink>` that is structural, not searched). Every mapping
rule in §2 is written against exactly these shapes. No field is assumed
that these records do not carry.

## 2. Import mapping

### 2.1 Check Point — every K-1 to K-10 kind, and the two outcomes

`CP-DISCOVERY` §4.2's ten candidate kinds, plus its `UNCLASSIFIED` and
`AMBIGUOUS` outcomes, each map to exactly one of: a `devices` row, a target
modifier on a named row, or "not importable" with the reason shown
(`AC-1`).

| Kind | Import outcome |
| --- | --- |
| K-1 standalone product gateway | `devices` row |
| K-2 non-product interoperable device | not importable — it is not a product device (`CP-DISCOVERY` CR-2/§4.1's `PRODUCT` flag false); shown, never imported |
| K-3 standalone virtualization host | `devices` row (the physical chassis) |
| K-4 standalone virtual system | target modifier: `virtual_system_ref` on its resolved host's `devices` row (§2.4) — never a row of its own (`CP-D6`) |
| K-5 virtualization cluster | target modifier: `cluster_member_ref` grouping over its members' `devices` rows (§2.4) — the cluster object itself is never a row |
| K-6 virtual-system cluster | target modifier: `cluster_member_ref` grouping, exactly as K-5 — a cluster of virtualization hosts is still a cluster object, never a row |
| K-7 plain high-availability cluster | target modifier: `cluster_member_ref` grouping, exactly as K-5 |
| K-8 physical virtualization chassis member | `devices` row (a physical cluster member), carrying `cluster_member_ref` |
| K-9 virtual-system member | target modifier: `virtual_system_ref` on its resolved host's `devices` row, where the host is itself a K-8 member carrying `cluster_member_ref` — a virtual system on a cluster member is two modifiers on one physical row, never two rows |
| K-10 plain cluster member | `devices` row, carrying `cluster_member_ref` |
| `UNCLASSIFIED` | not importable — an unmeasured flag combination is shown and marked (`CP-DISCOVERY` CL-2) but the operator has no kind-specific rule to import it against; it is displayed, never silently imported as if it were a nearest kind |
| `AMBIGUOUS` | not importable — a classifier defect or a vendor object-model change (`CP-DISCOVERY` CL-2); shown, never imported |

**IM-1.** This mapping is total: every kind `CP-DISCOVERY` §4.2 defines, plus
`UNCLASSIFIED` and `AMBIGUOUS`, appears in the table above exactly once,
mapped to exactly one of the three outcomes.

**IM-2. A virtual system whose host link is `MISSING` or `AMBIGUOUS`
(`CP-DISCOVERY` HL-1/HL-3) is shown and importable only when its host is
already imported or is imported in the same operation.** K-4 and K-9 map to
a `virtual_system_ref` modifier on the host's row (above); where
`hostLink()` is `Missing` or `Ambiguous`, there is no host row to modify, so
the virtual system cannot be imported on its own. It is still returned by
discovery (`CP-DISCOVERY` HL-3, DI-3) and still shown to the operator, marked
with its host outcome, but its import action is unavailable until a host
resolves. This is a display-and-block rule, never a silent drop: the
candidate is visible, the reason is visible, and nothing about the candidate
set changes.

**IM-3. A K-5/K-6/K-7 cluster object contributes no fields of its own to
any `devices` row.** `CP-DISCOVERY` CR-3a already holds that a cluster
object's own address, where it has one, is never a physical device's
address; this document adds that the cluster object contributes nothing
else either — a `cluster_member_ref` groups the members that reference it
(`CP-DISCOVERY` MC-1), it does not create or modify a row.

### 2.2 Palo Alto — the one candidate shape, and its outcomes

`PAN-DISCOVERY` §4 CL-1 defines one candidate shape, not ten kinds. Every
entry the enumeration returns maps as follows (`AC-1`):

| Row shape | Import outcome |
| --- | --- |
| A physical device entry (not nested inside another) | `devices` row |
| `PairingOutcome.Paired(...)` — the reciprocal case (`PAN-DISCOVERY` HA-1) | both members' `devices` rows share one `cluster_member_ref` |
| `PairingOutcome.NotEvaluable(reason)` — any reason (`NO_PEER_SERIAL`, `PEER_NOT_FOUND`, `SELF_REFERENTIAL`, `ONE_SIDED_CLAIM`) | standalone `devices` row; the pairing outcome and its reason are carried and shown on the row, never silently dropped once imported (§6) |
| A nested virtual-system entry (`VS-1`/`VS-4`) | target modifier: `virtual_system_ref` on the parent device's `devices` row — never a row of its own |

**IM-4.** This mapping is total over the one shape `PAN-DISCOVERY` defines:
a physical entry always becomes a row (whatever its pairing outcome), and a
nested virtual-system entry is always a modifier, never a row.

**IM-5. An unreciprocated inbound claim (`PAN-DISCOVERY` HA-4b) changes no
import outcome.** The claimed candidate's own pairing stands (HA-4a); the
inbound claim is additional information carried on the imported row (§6),
never a reason to withhold import, downgrade a `Paired` outcome, or form a
`cluster_member_ref` from the one-sided side.

**IM-6. `NOT_EVALUABLE` is not a cluster.** A device with a
`NotEvaluable` pairing outcome imports as a standalone `devices` row with no
`cluster_member_ref`. Reason: `CP-D6`'s prohibition already fixes the shape
this echoes for Check Point — a `cluster_member_ref` is formed only from a
corroborated relationship (`13C` SB-7), never assigned speculatively to
carry the possibility that a future run might corroborate it.

### 2.3 Endpoint kinds and the `B1-04b` successor clause

**IM-7. Endpoint mapping, per vendor.**

- Check Point: `endpoints.transport_kind = 'ssh_exec'`,
  `endpoints.address_ref` = the candidate's management address
  (`CP-DISCOVERY` CR-4).
- Palo Alto: `endpoints.transport_kind = 'pan_xml_api'`,
  `endpoints.address_ref` = the candidate's own address (`PAN-DISCOVERY`
  ID-4; whichever of the IPv4/IPv6 elements the implementing movement's
  field binding names — this document does not choose between them, that
  choice is `PAN-DISCOVERY`'s own field-binding verification, §11 Band 3
  check 14).

**IM-8. `pan_xml_api` is a successor clause `B1-04b` needs, not something
this document grants on its own.** `B1-04b` §2 fixes `endpoints
.transport_kind` as `'ssh_exec'` — "the only transport `B1-4` implements" —
at its own scope. This document requires a second value,
`'pan_xml_api'`, and requires that `B1-04b`'s validated-transport check
("`transport_kind` must be one of `B1-4`'s implemented or declared
transports," `B1-04b` §4) be read as extended to it. This document does not
extend `B1-04b` itself; §10 records the successor clause for the Product
Owner and the `B1-04b` owner to apply.

**IM-9. Discovery-created endpoints are a second successor clause.**
`B1-04b` §4's registration flow is written for one manually captured
endpoint per registration. This document's import path creates the
`devices`/`endpoints` pair from a discovery candidate instead of from an
operator-typed address and vendor, with `registration_source` set to a
value distinct from `'manual_registration'` (`B1-04b` §2 fixes
`registration_source = 'manual_registration'` "here," i.e. at `B1-04b`'s own
scope, and anticipates the extension: "`REL-DISCOVERY` adds enrollment
sources later," `C1` §3.2). This document names the need; it does not name
the literal value, which is an implementation choice for the movement that
writes the migration.

**IM-10. `device_id` stays opaque, unconditionally.** Every `devices` row
import creates has an application-generated, opaque `device_id`
(`AGENTS.md` identity law; `B1-04b` §7). The Check Point stable identifier
(`uid`, per `CP-DISCOVERY` CR-1) and its owning domain, and the Palo Alto
serial (`PAN-DISCOVERY` ID-1), are carried as attributes on the imported row
— they are the match key (§3.2) and never the row's own identifier. No rule
in this document casts, trims, normalizes or parses a vendor identifier to
produce or compare against a `device_id`.

## 3. The discovery-set to device-set relation

### 3.1 What import selects

**RD-1.** Import acts on an operator multi-select over a discovery run's
candidate set (`13C` §3's device-add entry point; `CP-DISCOVERY` DI-2,
`PAN-DISCOVERY` DI-2: "only operator selection creates a device row").
Selecting a cluster selects its members (`13C` SB-9: the cluster is shown as
the parent of its members; selecting the parent is selecting the group it
groups). Selecting a virtual system whose host is not also selected, and is
not already imported, is refused for the reason IM-2 states.

**RD-2.** Discovery never deduplicates against an existing `devices` row
(`CP-DISCOVERY` DI-6, `PAN-DISCOVERY` DI-7: "discovery performs no
deduplication... That is import's question"). Import must, and does so as
follows.

### 3.2 Match key per vendor

**RD-3.** The match key is the vendor stable identifier, never a display
name, never an address:

- Check Point: `(owning domain, stable identifier)` — `CP-DISCOVERY` CR-1
  records that uniqueness across domains is `UNKNOWN` (`CP-DISCOVERY` U-1),
  so the pair is the key regardless of that answer.
- Palo Alto: the serial (`PAN-DISCOVERY` ID-1), the only join key that
  contract permits.

**RD-4. Name-blindness.** A run in which every candidate's display name is
replaced by a constant produces identical match-key comparisons and
identical import outcomes (§7 property check). This is the same discipline
`CP-DISCOVERY` NP-5 and `PAN-DISCOVERY` ID-3/check 8 already apply at
discovery; import inherits it rather than reopening the question of whether
a name may ever break a tie.

### 3.3 Three outcomes against the existing device set

**RD-5.** Every selected candidate resolves to exactly one of three
outcomes against the current `devices` set, compared by the match key of
§3.2:

- **New.** No existing `devices` row carries this match key. Import creates
  one, per §2.
- **Already imported.** An existing row carries this match key, and its
  recorded management address (`endpoints.address_ref`) and its recorded
  kind (physical / cluster-member / virtual-system-hosting, as far as §2
  records it) agree with the candidate. Import performs no write; the
  existing row is what the operator already has.
- **Conflicting.** An existing row carries this match key, but its
  recorded management address or its recorded kind disagrees with the
  candidate now presented.

**RD-6. A conflict is shown to the operator and never auto-resolved.** No
rule in this document picks the newer value, the older value, the
management plane's value, or any other precedence. The operator sees both
the existing row's recorded state and the candidate's current state, side
by side, and decides — an explicit act, not a default. This mirrors `13F`
ID-M2's own pattern for a mismatch at enrollment (§4): a disagreement is
surfaced, never silently reconciled.

**RD-7.** A conflict is not an error and does not block the rest of the
selection. The candidates in the same multi-select that are `new` or
`already imported` are unaffected by a sibling candidate's conflict.

### 3.4 What a second discovery run means for rows the first created

**RD-8.** Nothing changes on a `devices` row without an operator action.
A second discovery run that finds a candidate matching an existing row's
key does not update, re-enroll, disable or touch that row; it produces an
`already imported` or `conflicting` outcome per §3.3, and the operator acts
or does not.

**RD-9. A candidate that no longer appears in a later run is reported as
absent-from-this-run.** It is never deleted, and its existing `devices` row
is never marked down, disabled, or moved to `UNREACHABLE` on the strength of
its absence from a discovery run alone (`B1-04b` §3's `UNREACHABLE`
transition is triggered by "a contact attempt fails," a collection-time
event, never by a discovery run's candidate list). `CP-DISCOVERY` LV-1 and
`PAN-DISCOVERY` LV-1 forbid a liveness inference from a discovery signal;
this document extends the same discipline to an *absence* of a candidate,
which is exactly as weak a signal as a management-plane connection-state
field, and for the same reason: `AGENTS.md` "absence of evidence is not
evidence of absence." A missing domain the credential cannot enumerate
(`CP-DISCOVERY` U-11) or a Panorama scope the credential cannot see
(`PAN-DISCOVERY` U-9) each produce exactly this shape of absence, and
neither is a device fact.

**RD-10. Idempotence of the report, not of the row set.** Running discovery
twice with no operator action in between produces the same `new` /
`already imported` / `conflicting` / `absent-from-this-run` classification
both times (discovery itself is unchanged and idempotent per
`CP-DISCOVERY` DI-5, `PAN-DISCOVERY` DI-1); the `devices` table itself
changes only when an operator acts on one of those outcomes.

## 4. The enrollment confirm — first contact

### 4.1 What triggers it, and what it is not

**EC-1.** Import (§2) creates a `devices` row in `DRAFT` (`B1-04b` §3: "every
registration starts here"). The enrollment confirm is the `DRAFT` →
`ENROLLED` transition (`B1-04b` §3's "authorized confirm... succeeds"), and
it is the device's **first contact** — the first time the product connects
to the device itself, as opposed to the management plane discovery read.

**EC-2.** The confirm is not import, and import does not perform it. `13C`
SB-1's principle for manual registration — "registered" and "confirmed
reachable" stay independently auditable, and collapsing the two paths would
destroy that — carries over unchanged to the discovery-sourced path: a
`devices` row can exist in `DRAFT` with no device ever having been
contacted, exactly as a manually registered one can.

### 4.2 Refusal before contact

**EC-3.** The confirm refuses before any contact only when the referenced
credential cannot be `RESOLVED` (`B1-04b` §6; `13C` SB-16). This is the one
pre-contact refusal this document recognizes; nothing else about a `DRAFT`
row blocks the attempt to connect.

### 4.3 Connecting, and recording the presented identity

**EC-4.** The confirm connects with the referenced credential to the
device's own endpoint (`endpoints.address_ref`, per vendor kind, §2.3) —
never to the management plane, and never to a cluster virtual address
(`13F` CL-1, restated here because it applies equally at first contact: each
member is reached at its own management address).

**EC-5. Recorded identity, per vendor.**

- Check Point: the SSH host key fingerprint presented at connection, and the
  observed hostname.
- Palo Alto: the serial read from the device's own identity read, and the
  TLS certificate identity presented at connection.

This is recorded as the device's **recorded identity** on first success —
the baseline every later contact is compared against (`13F` §2's directive,
in its own words: "if you see something inconsistent, warn me and show me
what the MDS shows").

### 4.4 Mismatch: connect anyway, warn, show the management plane's view, audit, continue

**EC-6.** On a later contact where the presented identity does not match
the recorded identity, the product **connects anyway** with the configured
credentials (`13F` ID-M1). It does not refuse by default.

**EC-6a. A first contact that connects and completes the identity read
moves `DRAFT` to `ENROLLED` even when the presented identity does not
match** the recorded or management-plane view — `ENROLLED` with an open
identity-mismatch warning attached (EC-7), never a separate enrollment
state, never `DRAFT` retained, never a refusal in default mode. `B1-04b`
§3's "confirm … succeeds" is read as "the connection and the identity read
completed"; §10 names the successor clause that records that reading.
(Decided by `RELAY_DECISION`, `relay/NXS-LOCAL-0147`, 2026-09-13, from
`13F` ID-M1/ID-M2.)

**EC-7.** The mismatch is surfaced as a **visible warning**, on the device
and on the run, placed side by side with what the management plane
currently reports for that device (`13F` ID-M2). It is written to the audit
trail. It is never silent, never auto-resolved, and never re-recorded as
the new baseline without an explicit operator action (§4.6).

**EC-8. Strict-refuse is a configurable option, off by default** (`13F`
ID-M3). Where it is enabled, a mismatch refuses the connection instead of
warning and continuing; this document does not design the setting's storage
or its screen, only that its default is warn-and-continue and that turning
it on is a deliberate, named choice, not this document's own default.

**EC-9. Re-baselining is an explicit, audited operator action.** The
recorded identity of §4.3 is never silently replaced by a later presented
identity, on match or on mismatch. Only a distinct, named, audited action
moves a device's recorded identity forward.

**EC-10. The interposed-host caveat, stated because `13F` states it.** An
interposed host produces the same symptom as a reset device (`13F` ID-M4).
This document does not resolve that ambiguity — nothing at the level of a
candidate row or a `devices` row can — and repeats `13F`'s own answer:
visibility (EC-7) is the control, not a stronger default refusal.

### 4.5 What the confirm contacts, and what this document does not approve

**EC-11.** The confirm's device contact is, in shape, a `connect` step
followed by one identity read (`show version`/HA-state-adjacent identity
read for Check Point over `ssh_exec`; the identity portion of `show system
info` for Palo Alto over the API `xml_api_call` kind), per `C4` §2.3's
closed step-kind set. This document requires these as **gate entries** — it
does not approve them. Per `docs/AI_DEVELOPMENT_PROTOCOL.md`'s
network-device command gate, each concrete command/call still needs its own
gate row (why required, action class, vendor/platform/shell/context,
timeout, retry, maximum frequency, session reuse, unsupported behavior,
secret-output risk, safe telemetry) before an implementing movement may
issue it. Both reads are `action_class = read` (class 0); no write of any
class is part of the confirm.

**EC-12. Both, layered.** The confirm is a `C3`-authorized action
(`role:onboarding_admin`'s "enrollment preview/confirm", `B1-04b` §3), and
the device-contact steps it performs (EC-11: one connect and one identity
read per vendor) execute as an ordinary `C4` read capability with their own
`gate_registry` rows — so `C4` §3.5's rule that an `UNKNOWN` gate blocks
execution applies to the confirm exactly as to every other device contact.
No second execution path outside `C4` is created. (Decided by
`RELAY_DECISION`, `relay/NXS-LOCAL-0147`, 2026-09-13.)

## 5. Identity and privacy

**PR-1. `CLASS 2` fields this contract's own subject matter touches.**
Every management address (`endpoints.address_ref`), every observed hostname,
every presented SSH host key fingerprint, every TLS certificate identity,
and every device display label is `CLASS 2` (`C1` §7; `AGENTS.md` sensitive
identity reporting law). None of it appears anywhere in this document,
which contains no example value of any kind.

**PR-2. What the audit trigger captures.** The `devices`/`endpoints` INSERT
that import performs, and the enrollment-state transition and any recorded-
identity write the confirm performs, are each an `audit_log` row via
`fn_audit_capture()` (`C1` §3.5), carrying only the mutated row's own
control-plane columns — never a derived narrative, never a resolved
secret, and never a free-text description of the mismatch beyond the
columns §10 names. `C1` §3.5's existing rule ("`audit_context_missing` and
aborts if either is unset") applies unchanged: no `devices` row, and no
enrollment-state transition, is ever written without its audit row.

**PR-3. What may appear in a log line or a report.** Counts and shapes
only — how many candidates classified to each import outcome, how many
conflicts, how many mismatches — never an address, hostname, serial,
fingerprint or certificate identity (`AGENTS.md` sensitive identity
reporting law: "compare locally, report the relationship, not the
values"). A conflict or a mismatch is reported as `MATCH` / `MISMATCH` /
`CONFLICTING`, never as the two compared values rendered into a log or a
report.

## 6. What the operator sees

Stated as constraints a screen must satisfy, not as a screen:

- **OP-1.** The candidate list presents a cluster as the parent of its
  members (`13C` SB-9) and a virtual system nested under its resolved host
  (`CP-DISCOVERY` HL-1/HL-3 shown as the host relationship; `PAN-DISCOVERY`
  VS-1's structural nesting) — never a flat list that hides either
  relationship.
- **OP-2.** The three import outcomes of §3.3 (`new`, `already imported`,
  `conflicting`) are each a distinct, visible state; a `conflicting`
  candidate shows both the existing row's recorded state and the
  candidate's current state, never merged into one value.
- **OP-3.** An identity mismatch (§4.4) shows the warning and the
  management plane's current view of the same device side by side, exactly
  as `13F` ID-M2 requires — never one without the other.
- **OP-4.** No liveness vocabulary. `CP-DISCOVERY` LV-1 and `PAN-DISCOVERY`
  LV-1 ("no field, label, colour, icon, badge, sort key or filter may
  express up, down, online, offline, reachable, unreachable, healthy,
  unhealthy or any synonym") carries over unchanged to every screen this
  document constrains, including the `already imported` / `conflicting` /
  `absent-from-this-run` outcomes of §3 — none of which is a liveness claim
  and none of which may be presented as one.
- **OP-5.** A `MISSING` or `AMBIGUOUS` host (IM-2), and a `NOT_EVALUABLE`
  pairing outcome or an unreciprocated inbound claim (IM-5/IM-6), are shown
  under their own honest outcome vocabulary — the same vocabulary the
  discovery contracts already fix (`MATCH`/`MISMATCH`/`MISSING`/
  `NOT_EVALUABLE`/`AMBIGUOUS`) — never translated into a status color or a
  health indicator.

## 7. Acceptance checks

Three bands, in the shape `CP-DISCOVERY` §9 and `PAN-DISCOVERY` §11 use.

### Band 1 — repository checks, runnable now

1. The status line declares the applied status and its reviewer:
   `grep -n -A 2 '^## Status$' docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md`
   shows a leading `FROZEN — PRODUCT OWNER APPROVED` token with a date.
   (Before 2026-09-14 this check asserted a `DRAFT` token; the freeze
   replaced it rather than leaving it to contradict the status block.)
2. The repository privacy gate reports zero findings:
   `python3 scripts/repository_privacy_check.py`.
3. The document contains no address literal:
   `grep -n -E '([0-9]{1,3}\.){3}[0-9]{1,3}' docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md`
   matches nothing, and no literal resembling a colon-separated IPv6 address
   appears anywhere in the document.
4. The document names no existing Python module of the product, beyond the
   single know-how citation of §1.3 item 8:
   `grep -n -E '\butils/[A-Za-z0-9_/]*\.py' docs/design/DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md`
   matches only that one citation.
5. The diff carries no implementation: `git diff --name-status
   origin/main...HEAD` reports only this document under `docs/design/` plus
   the durable project-state files `AGENTS.md`'s "Project-state update rule"
   requires, written through `scripts/project_queue.py`. No path under
   `utils/`, `scripts/` (other than the queue tool's own JSON writes),
   `ui2/` or `tests/` appears.
6. `git diff --check origin/main...HEAD` is clean, and
   `python3 -m pytest tests/test_contract_authority_status.py
   tests/test_architecture_convergence.py tests/test_project_files_budget.py -q`
   passes.

### Band 2 — property checks, provable over synthetic fixtures

7. **Import mapping totality (`IM-1`/`IM-4`).** Every Check Point candidate
   kind (K-1 to K-10, `UNCLASSIFIED`, `AMBIGUOUS`) and every Palo Alto row
   shape (physical device under each `PairingOutcome` case, nested virtual
   system) maps to exactly one of: `devices` row, target modifier on a
   named row, or not-importable-with-reason. No kind or shape maps to zero
   outcomes or to more than one.
8. **Match-key-only deduplication, name-blindness (`RD-4`).** A run with
   every candidate's display name replaced by a constant, over a
   constructed candidate set containing at least one of each of the three
   §3.3 outcomes, produces identical match-key comparisons and identical
   import outcomes to the same run with real names.
9. **Conflict never auto-resolved (`RD-6`).** Over a constructed candidate
   set containing a management-address disagreement and a kind disagreement
   against an existing row, both resolve to `conflicting`, and no code path
   under test picks a value or writes to the existing row.
10. **Absent-from-run never deletes (`RD-9`).** A second constructed
    discovery run that omits a candidate whose match key an existing row
    already carries leaves that row byte-identical (including
    `enrollment_state`) and reports `absent-from-this-run` for that
    candidate only.
11. **Mismatch never refuses in default mode (`EC-6`/`EC-8`).** A
    constructed confirm attempt whose presented identity disagrees with the
    recorded identity, run with strict-refuse off, connects, and its
    connection outcome is not `refused`; the same input with strict-refuse
    on refuses. Both branches independently write the mismatch warning.
12. **Virtual-system-without-host is shown and blocked (`IM-2`).** A
    constructed candidate set containing a virtual system whose host link
    is `Missing` and one whose host link is `Ambiguous` both appear in the
    candidate list and both are marked import-unavailable, never silently
    omitted and never imported without a host.
13. **`CP-D6` never violated by import.** No import outcome, across the
    full constructed fixture set of checks 7–12, ever produces a `devices`
    or `endpoints` row whose identity is VS-scoped or cluster-scoped; every
    row's identity is a physical device's opaque `device_id`.

### Band 3 — management-server / device checks, unrun at the time of writing

14. First contact against a real gateway: the confirm connects, records the
    identity of §4.3, and transitions `DRAFT` → `ENROLLED`.
15. First contact against a real firewall: the same, over `pan_xml_api`.
16. The mismatch path exercised once, deliberately, against a device whose
    presented identity has been changed since its recorded baseline (a
    reset or a reissued host key/certificate): the warning appears, the
    management plane's current view is shown alongside it, the audit row is
    written, and the connection succeeds rather than refusing, under the
    default (non-strict) mode.

## 8. `UNKNOWN` register

`AGENTS.md` requires explicit `UNKNOWN` over invented certainty.

| Id | `UNKNOWN` | What settles it |
| --- | --- | --- |
| U-1 | Whether the Check Point stable identifier (`uid`) is unique across management domains, or only within one | inherits `CP-DISCOVERY` U-1 unresolved; until settled, the match key stays `(owning domain, uid)`, correct either way |
| U-2 | Whether a Palo Alto virtual-system identifier is stable across a Panorama restart | one enumeration taken before and after a controlled Panorama restart, comparing virtual-system identifiers for the same physical devices |
| U-3 | Whether the SSH host key of a VSX host is shared by its virtual systems, or whether a virtual-system context presents its own | a captured connection to a VSX host's default context and to at least one non-default virtual-system context, comparing the presented host key |
| U-4 | What the TLS identity of a firewall's management interface is in this estate — self-signed or CA-issued, and whether that varies by device | a first-contact confirm run against a representative sample of firewalls, recording the certificate identity's issuance shape (never the value itself, per §5) |
| U-5 | Whether a device can legitimately appear under two management planes (e.g. a Check Point-managed and a Palo Alto-managed estate boundary, or two Check Point domains that both enumerate the same physical box) | an estate with an independently known instance of this, checked against whether import's match keys (per-vendor, §3.2) collide or coexist correctly; until settled, two different vendor match keys are never treated as the same device by any rule in this document |
| U-6 | Whether `B1-04b`'s "authorized confirm... succeeds" (§3's transition table) already includes a warn-and-continue mismatch outcome as a success, or whether that table was written before `13F` existed and needs its own successor clause to say so explicitly | **Decided 2026-09-13** (`RELAY_DECISION`, `relay/NXS-LOCAL-0147`): yes — see EC-6a; `B1-04b` successor clause in §10 records the reading |
| U-7 | Whether the enrollment confirm's device-contact steps are resolved through `C4`'s `capability_registry`/`gate_registry` machinery (their own `capability_id`) or authorized as a distinct `C3`-gated action outside that registry | **Decided 2026-09-13** (`RELAY_DECISION`, `relay/NXS-LOCAL-0147`): both, layered — see EC-12 |

## 9. Cross-references

- `AGENTS.md` — identity law, evidence laws, `UNKNOWN`/fail-closed law,
  sensitive identity reporting law, authority hierarchy, network action
  taxonomy.
- `PO_DECISION_RECORD_2026_09_13F_COLLECTION_TRANSPORT_AND_IDENTITY_
  DECISIONS.md` §1, §2, §5, §7 — the identity-mismatch rule and the
  explicit dispatch of this document.
- `PO_DECISION_RECORD_2026_09_13C_DISCOVERY_SERVICE_BOUNDARIES.md` §1–§5 —
  terminology, service decomposition, the device-add entry point, cluster/
  virtual-system/transport/identity resolution, and what stays open.
- `PO_DECISION_RECORD_2026_09_13E_PRODUCT_SEQUENCE_AND_SERVICE_
  INDEPENDENCE.md` — the build order and the per-feature independence rule.
- `UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` §2, §3, §4, §6, §7 —
  the three records, the enrollment-state table, the manual registration
  flow this import path runs alongside, credential handling, and identity/
  privacy.
- `UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §2.3, §4 — the
  closed step-kind set the confirm's device contact is shaped by, and the
  VSX/ClusterXL target model this document's target-modifier rules are
  built on.
- `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3.2, §3.5, §7 — the three tables'
  current column set, the audit mechanism, and the `CLASS 2` rule.
- `CP_AND_VSX_DISCOVERY_CONTRACT.md` §4–§8 — the candidate kinds, host and
  cluster resolution, liveness prohibition, and the discovery/import
  boundary this document's far side implements.
- `PAN_DISCOVERY_CONTRACT.md` §4–§10 — the one candidate shape, identity and
  addressing, virtual-system structural resolution, HA reciprocity, liveness,
  and the discovery/import boundary.
- `PO_DECISION_RECORD_2026_09_12.md` §2 — the existing Python as know-how
  only, cited once (§1.3 item 8).
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — the network-device command gate the
  confirm's steps must each pass before implementation.

## 10. Successor clauses this document needs from FROZEN documents

This document amends no `FROZEN` document. Each item below is a gap this
document's own scope cannot close, named for the document whose owner
would apply it:

- **`B1-04b` successor clause 1 — a `pan_xml_api` endpoint kind.** `B1-04b`
  §2 fixes `endpoints.transport_kind = 'ssh_exec'` as the only transport at
  its own scope (`IM-8`).
- **`B1-04b` successor clause 2 — discovery-created endpoints.** `B1-04b`
  §2/§4 anticipate but do not define a `registration_source` value, or a
  registration-flow variant, for a device created from an import selection
  rather than a manually typed address (`IM-9`).
- **`B1-04b` successor clause 3 — the confirm step's first-contact
  behaviour under `13F` §2.** `B1-04b` §3's transition table names the
  confirm action but was frozen before `13F`'s warn-and-continue rule
  existed; it does not itself state that a mismatch-but-continue outcome is
  a `DRAFT` → `ENROLLED` success (`U-6`).
- **`C1` successor migration — recorded identity columns.** A column set on
  `devices` (or a linked table) to hold: the recorded identity's fields per
  vendor (§4.3 — a fingerprint/hostname pair for Check Point, a serial/
  certificate-identity pair for Palo Alto), when it was recorded, and by
  which audited action; and a mismatch-event marker sufficient for `C1`
  §3.5's trigger mechanism to produce an `audit_log` row for a mismatch that
  does not otherwise mutate any control-plane column. No column name or SQL
  is fixed here — only the roles a `C1` successor movement must cover.
- **`C1` successor migration — the target-modifier columns `C4` §4.2 names
  but does not place.** `virtual_system_ref` and `cluster_member_ref` are
  defined by `C4` as target-model modifiers over one physical `device_id`/
  `endpoint_id`; `C1` §3.2's current `devices` sketch carries neither. This
  document requires them to exist somewhere reachable from a `devices` row
  (a column, or a small linked table) so that import can persist what
  discovery already resolved (§2), without specifying which shape.
- **Nothing in `C4`.** `C4` §4.2's target model already covers this
  document's cluster/virtual-system representation needs without change;
  this line records that the review was done, per the brief's own
  instruction, not left unstated.

## 11. Contradictions and open items for the Product Owner

**Contradictions checked for, none found requiring reconciliation.**
`13F`, `13C`, `B1-04b`, `C4`, `CP-DISCOVERY` and `PAN-DISCOVERY` were read
against each other for this document's scope. No clause in one asserts
what another's clause forbids.

**Open items.** Items 1 and 2 below were raised as a `RELAY_QUESTION` and
decided on the relay the same day; the decisions are written into EC-6a and
EC-12 and kept here so the question is visible to the freeze review.

1. **Decided — a warned-and-continued mismatch is a successful confirm
   (`U-6`, EC-6a).** `B1-04b` §3's "succeeds" is read as "connection and
   identity read completed"; §10 keeps the successor clause that records it.
2. **Decided — the confirm is `C3`-authorized and its device steps run
   through `C4`'s capability/gate machinery (`U-7`, EC-12).**
3. **Which service owns the operational unit after import** (`13C` §5's
   first bullet) **remains open, unchanged by this document.** This
   document's rules (§2, §3) hold under either answer: nothing here assigns
   ownership of a cluster or HA-pair unit to any service, and every rule is
   phrased over the `devices`/target-modifier shape alone.

This document authorizes nothing. Applying a status to it would prove the
rules stated here are checkable — Band 2 of §7 is that acceptance surface —
and nothing about any artifact: this movement creates none, and Band 3 is
unrun at the time of writing.
