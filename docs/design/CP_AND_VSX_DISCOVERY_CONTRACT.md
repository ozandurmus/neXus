# Check Point and Check Point VSX discovery contract

## Status

**DRAFT — not implementation authority.** This document decides the design of
Check Point and Check Point VSX discovery. It implements none of it, and it
authorizes no implementation. Applying a status to it is the Product Owner's
act, not this movement's; until a status is applied, `AGENTS.md` "Authority
hierarchy" item 2 and "Contract-status law" both apply in full — nothing here
may be cited as approving a command, a schema or an identity model.

Every rule in §§3–8 was measured against a live multi-domain management server
during the session that commissioned this contract, by the Product Owner, who
ran each query and returned the output. §2 states the exact scope of that
measurement and what it therefore does and does not license. The measurement
existed only in a session transcript, which `AGENTS.md` item 7 makes
non-authoritative; writing it down as a rule set is the whole point of this
movement. No real object name, address, identifier, domain name, certificate
subject or estate topology is reproduced here — the measurement is cited as
having been performed, never quoted.

The twelve `UNKNOWN`s of §10 are open. Applying a status to this document would
not close any of them.

## 1. Scope and authority

**In scope.** Discovery for Check Point and Check Point VSX: the transport and
entry point by which a candidate set is obtained; the classification of every
object the management plane returns into a candidate kind; the structural
rules that resolve a candidate's host and its cluster; the candidate row and
which kinds carry which of its fields; liveness, stated as an open question
rather than answered; and the boundary at which discovery stops and import
begins.

**Out of scope, and not authorized here.**

- Palo Alto in any form. The Product Owner sequenced Check Point and VSX
  first; this is a sequencing decision, not a statement about Palo Alto.
- Collection of any kind — configuration, inventory, running configuration,
  routing or interface state from a device. The collection gate holds.
- Any device contact, any SSH, any remote execution, any management-plane
  write.
- Import: what a device row is, what identity it carries, what happens when
  discovery runs a second time, and how a candidate is reconciled against a
  row that already exists. §8 fixes only the boundary; everything past it is a
  separate contract.
- Any implementation. This movement produces one document: no service, no
  query runner, no schema, no screen.
- The existing Python. `docs/design/PO_DECISION_RECORD_2026_09_12.md` §2 makes
  it know-how only. No rule here is derived from it, no rule here is a
  restatement of it, and nothing here is a plan to port, wrap, transliterate
  or invoke it. Every rule below comes from the measurement of §2.

**Authority.** `AGENTS.md` is the constitution and governs every clause here —
in particular its identity law (identifiers are opaque), its evidence laws
(management-plane observation is not device runtime truth), its
`UNKNOWN`/fail-closed law, its vendor-semantics law, its raw-evidence law and
its sensitive-identity reporting law. This contract does not restate those
laws; it records the measurements that make them concrete for this subsystem
and turns each into a construction rule with a check.
`docs/design/PO_DECISION_RECORD_2026_09_12.md` §4 fixes the product sequence
this contract's first step belongs to: device add, then discovery returning
candidates the operator multi-selects, then import creating the device row,
then collection.

## 2. Evidence basis, and the scope of the measurement

**What was measured.** One multi-domain management server, one software
generation, several domains, in one authenticated session, at one point in
time. Each rule below was produced by running a query against that management
database and reading the output — not inferred from a field name, not taken
from existing source, not recalled from general product knowledge.

**What that licenses.** It licenses the rules as rules about a management
database of that class. It does **not** license any of them as a vendor law.
`AGENTS.md` "Vendor semantics law" is explicit: a field name is not its
contract, and a command returning output does not prove the reader understood
it. Where this contract states a rule, it states a rule that was observed to
hold on one management server; where the measurement did not settle a
question, §10 records it as `UNKNOWN` rather than filling the gap.

**Where official documentation would be required.** This contract governs a
read-only projection of a management database. No rule in it is device-facing,
because discovery contacts no device (§3). That is what makes it freezable
without vendor documentation. The following would each become a
safety-critical vendor semantic, and would require official vendor
documentation before it could be relied on, the moment a movement tried to
make it device-facing:

- **VD-1.** The meaning of the management-address field (§5) as a *connection
  target*. Here it resolves a structural relationship between two management
  database rows. Using it as the address to connect to is a different claim
  about a different plane, and it must go through the network-device command
  gate before any movement acts on it.
- **VD-2.** The meaning of the management-plane connection state (§7). It is
  already measured **not** to be a liveness signal; what it *is* remains
  `UNKNOWN` (U-2).
- **VD-3.** The stability and uniqueness guarantee of the stable identifier
  (§6, U-1) — how long it survives, and across what scope it is unique.
- **VD-4.** The flag semantics that carry the classification of §4, if a
  movement wants to act on a kind rather than merely display it.
- **VD-5.** The schema's compatibility guarantee across software generations
  (U-9).

Nothing in this contract depends on VD-1 to VD-5 being answered, because
nothing in this contract contacts anything. A movement that needs one of them
answered is, by that fact, no longer inside this contract.

## 3. Transport and entry point

- **T-1.** One authenticated session to the multi-domain management server,
  opened once per discovery run, used for the domain enumeration and every
  per-domain query, and closed when the run ends. A run that cannot close its
  session reports that; it does not leave one open.
- **T-2.** The entry point is a domain enumeration, followed by a per-domain
  object query for the object types of §4. A run performs no other query.
- **T-3.** The query set is **read-only**. It reads the management database
  and performs no management-plane write of any kind: no object creation or
  change, no policy operation, no publish, and no session state left behind
  holding a lock. A discovery run is invisible in the management database
  except as a read.
- **T-4. No device is contacted at any point in discovery.** No SSH, no device
  API call, no ICMP, no TCP probe, no name resolution performed against a
  candidate's address for the purpose of reaching it. Every address this
  contract handles is a value in a management database row, never a
  destination.

  This is the property the whole design is built on, not a limitation of it.
  Because discovery touches no device: it is safe to run against an entire
  estate, including devices under change control; it cannot disturb a
  production device, so it needs no maintenance window; it requires no device
  credential at all; and it sits entirely outside the collection gate, which
  is why this contract can be written while that gate holds. Every rule in
  §§4–6 is therefore a rule about a management database row — and must be
  readable as one, by a reader who has no device.

- **T-5.** One management-plane credential. Discovery neither requires nor
  accepts a device credential. `AGENTS.md` "Diagnostic-path law" forbids
  standing up a second credential boundary for convenience; there is no second
  path here to stand up.
- **T-6.** Queries are issued sequentially within the one session at the
  current maturity. Any increase in concurrency or query rate requires the
  relevant vendor interaction-safety gate first (`AGENTS.md`, "Engineering
  laws"); stability is more important than discovery speed.
- **T-7.** No raw management-plane response is persisted. `AGENTS.md`
  "Raw-evidence law" gives the lifecycle: response in memory → parse the
  minimum semantics this contract names → safe fields, enums and relationships
  → discard the raw response. A management database response is not retained
  to make debugging easier.

## 4. Object classification

### 4.1 What the management plane returns

Three object types carry security devices in this management database: a
**gateway type**, a **cluster type**, and a **cluster-member type**. Crossed
with two virtualization flags and one Check-Point-product flag, they yield ten
distinct candidate kinds. All ten were measured.

The two virtualization flags are independent booleans and are named here by
role, not by vendor field name (§6.4):

- `VIRT_HOST` — the object is a virtualization host: a physical device that
  hosts virtual systems.
- `VIRT_SYSTEM` — the object is a virtual system.

`PRODUCT` is the Check-Point-product flag: whether the object represents a
product device, as opposed to an interoperable device the management plane
tracks but does not run.

### 4.2 The ten candidate kinds

| Id | Candidate kind | Object type | `PRODUCT` | `VIRT_HOST` | `VIRT_SYSTEM` |
| --- | --- | --- | --- | --- | --- |
| K-1 | standalone product gateway | gateway | true | false | false |
| K-2 | non-product interoperable device | gateway | false | false | false |
| K-3 | standalone virtualization host | gateway | true | **true** | false |
| K-4 | standalone virtual system | gateway | true | false | **true** |
| K-5 | virtualization cluster | cluster | true | **true** | false |
| K-6 | virtual-system cluster | cluster | true | false | **true** |
| K-7 | plain high-availability cluster | cluster | true | false | false |
| K-8 | physical virtualization chassis member | member | true | **true** | false |
| K-9 | virtual-system member | member | true | false | **true** |
| K-10 | plain cluster member | member | true | false | false |

### 4.3 Classification rules

- **CL-1.** A candidate's kind is determined by its object type, its flag
  values and the presence or absence of the fields of §6 — by nothing else.
  No kind is determined by a display name, a name pattern, a label, an
  ordinal, a model string, a version string or an address.
- **CL-2.** The mapping is total and exclusive over the measured estate: each
  object maps to exactly one kind. An object that maps to **zero** kinds — a
  flag combination this contract has not measured — is a candidate of kind
  `UNCLASSIFIED`. It is returned, shown, and marked; it is never dropped, and
  it is never assigned to the nearest kind. An object that maps to **more
  than one** kind is a defect in the classifier or a change in the vendor's
  object model; it is reported as `AMBIGUOUS` and never resolved by preference
  order.
- **CL-3.** `UNCLASSIFIED` and `AMBIGUOUS` are outcomes, not errors. A run
  that produces them completes and returns its full candidate set.
  `AGENTS.md` "`UNKNOWN` / fail-closed law": a classification that cannot be
  proven is reported, not guessed.
- **CL-4.** The flag set this contract reads is closed: `PRODUCT`,
  `VIRT_HOST`, `VIRT_SYSTEM`. A future movement that needs a fourth flag adds
  it here with its measurement, and re-runs the check of §9 item 7; it does
  not read one opportunistically because it happens to be in the response.

## 5. Relationship resolution

The measurement produced two independent relationships. Neither is derived
from the other, and neither is derived from a name.

### 5.1 Host resolution — one invariant, not a set of special cases

Every object carries a **management-address** field whose value is the address
of the physical device that serves it, alongside its **own address** field.
The relationship is a single invariant over those two fields:

- **HR-1.** Where the management-address field is present and its value equals
  the object's own address field, **the object is that physical device**.
- **HR-2.** Where the management-address field is present and its value
  differs from the object's own address field — **including the case where the
  object's own address field is empty** — the object is a **virtual system
  hosted on the physical device at that management address**. An empty own
  address is the same case as a differing one; it is not a separate rule and
  it is not a missing value to be repaired.
- **HR-3.** Where the management-address field is **absent**, the object is a
  cluster object or is not a product device. Host resolution yields
  `NOT_APPLICABLE` — never `UNKNOWN` and never a guess — and §4's kind already
  says which of the two it is.
- **HR-4.** HR-1 to HR-3 are exhaustive and mutually exclusive. They are a
  function of the two address fields alone: **host resolution does not branch
  on object type.** The clustered form and the standalone form were measured
  separately and agreed, which is why this is one invariant rather than two.
  An implementation that needs an object-type test inside host resolution has
  departed from the measurement and must say so.

**How equality is decided.** Both values come from the same response and are
compared locally. Only a representation-only normalization is permitted, and
only when applied identically to both sides before comparison — surrounding
whitespace and nothing else. `AGENTS.md` "Identity law" forbids the rest:
no numeric casting, no zero-stripping, no truncation or padding, no
case or punctuation normalization, and no equivalence rule invented to make
two values match. Where equality cannot be decided this way, the outcome is
`NOT_EVALUABLE`.

**What is reported.** The *relationship*, not the values. `AGENTS.md`
"Sensitive identity reporting law" gives the pattern — compare locally, report
the relationship — and the vocabulary: `MATCH`, `MISMATCH`, `MISSING`,
`NOT_EVALUABLE`, `AMBIGUOUS`.

### 5.2 Host linking — resolving the address to a candidate

HR-1 to HR-4 say what an object *is*. Turning that into a **link** between two
candidate rows is a separate step, and it can fail:

- **HL-1.** For a candidate resolved by HR-2 as a virtual system, its host is
  the candidate in the same domain whose own address equals its management
  address — **where exactly one such candidate exists**. Where zero exist, the
  host is `MISSING`. Where more than one exists, the host is `AMBIGUOUS`. In
  neither case is a host chosen.
- **HL-2.** Once resolved, the link is recorded by the host candidate's
  **stable identifier** (§6). The address resolved the link; it is not the
  link. An address is a locator, never a join key.
- **HL-3.** A `MISSING` or `AMBIGUOUS` host is displayed as such. The
  candidate is still returned (§8). A virtual system whose host is not in the
  candidate set is a real and reportable finding — most often a domain the
  credential could not enumerate (U-11) — and hiding it would convert a
  reportable gap into a silent one.

### 5.3 Member-to-cluster resolution

- **MC-1.** A member object carries a reference to its own cluster object, and
  that reference carries a **stable unique identifier alongside a display
  name**. **The identifier is the join key. The display name is never the join
  key**, is never compared, and is never used to break a tie. This is a
  construction rule with its own check (§9 item 12), not advice.
- **MC-2.** Where the reference is absent, or carries a display name but no
  identifier, the member's cluster is `NOT_EVALUABLE`. There is no fallback:
  an implementation must not match on the display name, on a shared name
  prefix, or on anything else, when the identifier it was told to use is
  missing.
- **MC-3.** The tree is built from the **member side only**. Whether a cluster
  object also carries a member list was not measured (U-3); until it is, a
  cluster's members are exactly the members that reference it, and a cluster
  with no referencing member is a cluster with no known members — not an
  empty cluster.

### 5.4 Prohibition: no relationship from a name, a label or an ordinal

- **NP-1.** No relationship, identity, grouping, ordering or membership may be
  derived from a display name, a label, a comment field, or an ordinal suffix.
  Not cluster membership, not host-to-virtual-system, not peer pairing, not
  member ordering, not site or role grouping.
- **NP-2.** Specifically forbidden as implementation techniques: splitting a
  name on a separator; stripping or parsing a trailing ordinal; pairing two
  candidates because their names differ only in a suffix; grouping candidates
  by a common name prefix; and sorting by name to imply a member order or a
  primary/secondary role.
- **NP-3.** `AGENTS.md` "Identity law — identifiers are opaque" and
  "Presentation identity != security identity" are the authority. What this
  section adds is the **measurement that makes it concrete**: the estate
  measured for this contract already contains **two different ordinal
  separators in use**, and composite names whose split point is ambiguous —
  the same name can be read as two different decompositions, and nothing in
  the name says which. A name-parsing rule written against that estate would
  be wrong on that estate, today, before it was ever carried anywhere else.
- **NP-4.** A display name is carried on the candidate row and shown to the
  operator (§6). That is its only use: it is a label for a human, never an
  input to a rule.
- **NP-5.** This prohibition is machine-checkable, and §9 item 8 is the check:
  replacing every display name with a constant must change no classification
  and no relationship.

## 6. The candidate row

### 6.1 What a candidate row is

A candidate row is everything the management plane can supply about one object
**before any device is contacted**. It is not a device row: a device row is
created by import (§8), and only by import. A candidate row carries no
evidence, no collected state, and no claim about a device's current condition.

### 6.2 Field-by-field, and which kinds carry which

Legend: `Y` measured present · `Y∅` measured present but observed empty ·
`—` measured absent · `?` not measured for that kind, recorded `UNKNOWN`.

| Field | K-1 | K-2 | K-3 | K-4 | K-5 | K-6 | K-7 | K-8 | K-9 | K-10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stable identifier | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y |
| display name | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y |
| own address | Y | Y | Y | `Y∅` | ? | ? | ? | Y | `Y∅` | Y |
| management address | Y | — | Y | Y | — | — | — | Y | Y | Y |
| owning domain | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y |
| candidate kind | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y |
| cluster reference | — | — | — | — | — | — | — | Y | Y | Y |
| model | ? | — | ? | ? | ? | ? | ? | ? | ? | ? |
| software version | ? | — | ? | ? | ? | ? | ? | ? | ? | ? |
| management-plane connection state | ? | ? | ? | ? | ? | ? | ? | ? | ? | ? |

- **CR-0.** A `?` cell means the presence of that field for that kind was not
  measured. An implementation treats `?` as *may be absent* and handles the
  absence; it never treats `?` as *present*. A `?` that turns out to be
  present is a measurement to record here, not a licence to rely on it.

- **CR-1. Stable identifier.** Carried by every kind. It is the candidate's
  identity and the only join key this contract permits (HL-2, MC-1). It is
  **opaque**: not cast, not trimmed of leading zeroes, not normalized, not
  parsed for meaning. Whether it is unique across domains or only within one
  is `UNKNOWN` (U-1); until that is settled, the candidate key is the pair
  *(owning domain, stable identifier)*, which is unique if either component
  is.

- **CR-2. Display name.** Carried by every kind. Display only — NP-1 to NP-5.

- **CR-3. Own address.** Carried by the gateway and member kinds that are
  physical devices (K-1, K-2, K-3, K-8, K-10). **Measured present but empty on
  the virtual-system kinds (K-4, K-9)**, and this is the design, not a data
  quality problem: a virtual system need not have a management address of its
  own, because it is served by the physical device its management-address
  field names. HR-2 already covers the empty case, so nothing downstream has
  to special-case it. For the cluster kinds (K-5, K-6, K-7) the presence and
  the meaning of an own address were not measured (U-4) — and regardless of
  U-4, **CR-3a** holds: an address on a cluster-type candidate is not the
  address of any physical device, is never resolved through HL-1, and is never
  displayed as a device address.

- **CR-4. Management address.** Carried by every product device kind, physical
  and virtual, clustered and standalone (K-1, K-3, K-4, K-8, K-9, K-10). Not
  carried by the cluster kinds (K-5, K-6, K-7), which are not served by a
  physical device, nor by the non-product kind (K-2), which the management
  plane does not run. Its absence is therefore informative, and HR-3 uses it.
  It is a relationship field here and nothing else; making it a connection
  target is VD-1 and is out of scope.

- **CR-5. Owning domain.** Carried by every kind — supplied by the enumeration
  context, i.e. the domain whose query returned the object, not by the object
  itself. A candidate therefore always knows its domain even if its object
  does not name one.

- **CR-6. Candidate kind.** Derived (§4), never supplied. Carried by every
  kind, including the `UNCLASSIFIED` and `AMBIGUOUS` outcomes of CL-2.

- **CR-7. Cluster reference.** Carried by the member kinds only (K-8, K-9,
  K-10). Not carried by gateway kinds, which belong to no cluster, nor by
  cluster kinds, which are the target of the reference rather than its source
  (MC-3 and U-3). Identifier and display name (MC-1).

- **CR-8. Model.** Not carried by K-2, which is not a product device. For the
  remaining kinds, presence was not measured per kind (U-6). Two rules hold
  regardless: **CR-8a**, a model is never inherited — a virtual system's row
  never carries its host's model, because a virtual system has no hardware of
  its own; and **CR-8b**, a cluster object's model, where present, is a
  property of the cluster object and is never presented as a member's.

- **CR-9. Software version.** As CR-8, with the same two rules: never
  inherited from host to virtual system, and a cluster object's version is
  never presented as a member's version.

- **CR-10. Management-plane connection state.** May be carried; presence per
  kind was not measured (U-6). **It is not a liveness field.** §7 governs it
  entirely, and §7 is the only place in this contract that may be cited when
  deciding how it is displayed.

### 6.3 What a candidate row may not carry

- **CR-11.** No field derived from a name (NP-1).
- **CR-12.** No up/down, online/offline, reachable/unreachable, healthy/
  unhealthy field, label, colour, icon or sort key (§7).
- **CR-13.** No raw management-plane response, and no field carrying one
  (T-7).
- **CR-14.** No collected device state of any kind. If a value would require
  contacting a device to obtain, it is not a candidate row field; it is
  collection, and the collection gate holds.

### 6.4 Field binding

- **FB-1.** The fields above are named by **role**, not by vendor field name.
  This contract deliberately does not assert a concrete API field name: a
  field name is not its contract (`AGENTS.md`, "Vendor semantics law"), and
  the rules here are about measured behaviour.
- **FB-2.** The implementing movement binds each role to exactly one concrete
  field of the management API's own schema, records the binding once, and
  justifies each binding by the behaviour measured here — never by a field
  name that resembles the role.
- **FB-3.** A binding that cannot be justified by measured behaviour is
  recorded `UNKNOWN` and the field is not carried. An unbound role is a
  missing field, not a field to infer.

## 7. Liveness — an open question, deferred

### 7.1 The finding

**Discovery cannot tell an operator whether a device is up.** Three
management-plane signals were measured during this session, and **none of them
is a liveness signal**:

- **L-S1. The management-plane connection state.** A state reported as
  *communicating* was observed on devices that were **powered off**. The
  measurement disproves the field as a liveness signal outright: the field can
  report its most positive value about a device that is not running.
- **L-S2. A cluster object's own state.** A cluster object is not a device. Its
  state is the cluster object's state and is **not its members' state** —
  neither the state of any one member nor an aggregate over them.
- **L-S3. The management console's red status indicator.** Measured to reflect
  a **certificate condition**, not reachability. An operator who reads it as
  "that device is down" is reading a different fact than the one displayed.

### 7.1a The completed negative search

L-S1 to L-S3 measured three signals and disproved each of them as liveness.
Beyond those three, a further search asked a narrower question: does any
plane discovery already reads carry *any* field, anywhere, that moves with a
device's power state? Three planes were searched during this session, and
all three are negative.

- **L-S4. The object database, field by field.** The full object record for a
  device the Product Owner confirmed powered off was compared, field by
  field, against the full object record for a device confirmed live, of the
  same model, version and role. **372 first-level fields were compared;
  exactly two differed, and both were the object's own identity fields — not
  a state field.** Nothing in the object database moved between the
  powered-off device and its live counterpart.
- **L-S5. The per-device configuration directories on the management
  server.** These directories hold policy and schema artefacts for the
  object — configuration intent, not device status. There is nothing in them
  to move with a device's power state, because nothing in them is a status
  field to begin with.
- **L-S6. The management server's own status command.** It reports the
  management server's own state and the administrator sessions currently
  connected to it — not gateway state, and not any field indexed by a
  managed device at all.

**Three planes were searched — the object database, the per-device
configuration directories, and the management server's own status surface —
and all three are negative.** This is what makes §7.3's `UNKNOWN` an
*evidenced* absence rather than a merely declared one: the search for a
liveness signal on the planes discovery already reads was completed, not
abandoned. It does not narrow §7.3: a direct, identity-verified device read
remains the only evidence grade that settles liveness. And it licenses
neither L-S4 nor L-S5 nor L-S6 as a positive signal about anything — each is
negative evidence that a signal is absent from that plane, never a
substitute answer.

### 7.2 The rules this produces

- **LV-1.** No candidate row field, label, colour, icon, badge, sort key or
  filter may express up, down, online, offline, reachable, unreachable,
  healthy, unhealthy or any synonym of them. Not derived, not computed, not
  aggregated.
- **LV-2.** L-S1, L-S2 and L-S3 may be carried and shown, but only under their
  own names, with their own meaning, and never merged into a single "status".
  A management-plane channel state is shown as a management-plane channel
  state. A cluster object's declared state is shown as the cluster object's.
  A certificate condition is a certificate condition.
- **LV-3.** No derived value may be computed from L-S1, L-S2 or L-S3 that has
  liveness semantics — no health score, no traffic light, no "N of M
  reachable" roll-up. Composing three signals that are individually not
  liveness signals does not produce one.
- **LV-4.** Where an operator asks whether a candidate is up, discovery
  answers `UNKNOWN`. `AGENTS.md`: absence of evidence is not evidence of
  absence, and collection failure is not a known-bad state. An honest
  `UNKNOWN` is the correct answer here, not a degraded one.
- **LV-5.** The measurement disproves L-S1 as a liveness signal. It does
  **not** establish what L-S1 *does* mean — that is U-2, and no movement may
  fill it from general product knowledge.
- **LV-6.** An unreachable candidate is still returned and still shown (§8).
  LV-1 forbids labelling it unreachable; it does not permit hiding it.

### 7.3 What would settle it

Liveness is a fact about a device, and the only evidence grade that settles it
is a **direct, identity-verified read from the device itself** —
management-plane observation is not device runtime truth (`AGENTS.md`,
"Evidence laws"). Discovery, by construction (T-4), does not do that and will
never do that.

Therefore: **liveness is deferred out of discovery entirely.** It is answered,
if at all, by a movement that is authorized to contact a device — which
requires the collection gate to be lifted for that purpose and the relevant
commands to pass the network-device command gate. Separately, official vendor
documentation of L-S1's and L-S3's semantics (VD-2) would settle what those
two signals actually mean, which is a different and smaller question than
liveness, and worth answering on its own.

Until then this contract states the question, refuses to answer it, and
forbids any presentation that implies it has been answered. That refusal is
the deliverable of this section.

### 7.4 A new management-plane signal: the connection-table channel state — not liveness

§7.1's L-S1 to L-S6 exhaust what this session found by *looking for* a
liveness signal. Separately, while looking at something else, the same
session found a signal this contract did not previously carry: the
management server's own **connection table** — not the object database —
records, per device management address, the state of the management
server's monitoring channel to that address. This section adds it under its
own name and its own meaning, deliberately distinct from L-S1's
management-plane connection state: a different field, on a different plane
(the object database's connection-state field, already disproven at L-S1 as
a liveness signal in its own right).

**CS-1. Four states, named for what they observe.**

| State | What it observes |
| --- | --- |
| established | The management server's monitoring channel to this address is currently established. |
| failing to complete | An attempt to establish the channel to this address exists and has not completed. |
| in transition | Two observations of this address, taken as CS-3 requires, disagreed. |
| absent | The connection table carries no entry at all for this address. |

**CS-2. What this signal is, and what it is not.** It is an observation of
the management server's own channel to a device, taken entirely from the
management server: zero new network traffic and zero device contact produce
it. **It is not liveness.** §7.2's prohibition (LV-1 to LV-6) applies to it
exactly as it applies to L-S1 to L-S3: no field, label, colour, icon, badge,
sort key or filter derived from CS-1 may express up, down, online, offline,
reachable, unreachable, healthy, unhealthy or a synonym of them, and no
roll-up computed from it may carry liveness semantics. It is carried under
its own name — the connection-table channel state — and its own meaning, and
it is never merged into a single "status" with L-S1, L-S2, L-S3 or anything
else.

**CS-3. The sampling rule.** *Failing to complete* is a transient state by
construction: a single observation can catch a healthy device's channel
mid-handshake and report it as failing when it is not. **Two observations,
separated by an interval, are required before *failing to complete* is
reported for an address.** A pair of observations that disagrees is reported
as **in transition** — never resolved to either state by preference, and
never resolved by simply taking the more recent observation.

**CS-4. The measurement, as counts and shapes only.** One management server,
one software generation, all domains read in one pass. A large majority of
observed channels were established; a small minority were failing to
complete. Every observation was stable across the sampling interval — no
address produced a disagreeing pair in this run. The failing set contained
every device the Product Owner had independently identified as powered off.
No address, object name or domain name is reproduced here — counts and
shapes only, per `AGENTS.md` "Sensitive identity reporting law".

**CS-5. A hypothesis, not a finding.** The count of failing channels in this
measurement equalled the count of devices whose collection failed in an
earlier run of the existing collector. This is recorded as a **hypothesis**
that the two sets are the same devices, not as a finding that they are:
identity was not verified, because the earlier run's addresses are
pseudonymized and cannot be compared address-for-address against this
session's connection-table read. **What would confirm or refute it:** one
read that resolves both sets to the same, non-pseudonymized address form and
compares them member for member — either the earlier run's pseudonymization
is reversed under a controlled procedure, or a fresh collector run and a
fresh connection-table read are taken in the same session so both start from
the same address form. Until then the equal count is a coincidence of
counts, not a correlation of devices.

**CS-6. Three implementation constraints the measurement revealed.**

- **CS-6a. Per-address reduction.** The connection table carries many rows
  per address for management servers and cluster addresses. An
  implementation reduces the table to one state per address before it is
  usable, and considers only addresses that are also present in the object
  database — an address in the connection table with no corresponding object
  is not a candidate and is not reported.
- **CS-6b. The channel port is derived, never hard-coded.** The port on which
  the channel is observed is taken from the observed established channels
  themselves, or from configuration — never written into an implementation
  as a fixed, version-pinned number.
  `docs/design/DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md`
  §14 records a hard-coded, version-pinned defect of exactly this shape,
  already measured in the existing collector; this constraint exists so the
  new signal does not repeat it.
- **CS-6c. Addressing, as a Product Owner statement of record.** The Product
  Owner states, of record, that in this estate a management server and a
  gateway always communicate from their own addresses — never through
  address translation between them. This is what makes the join between the
  connection table and the object database, both keyed by address, safe on
  this estate; it is an estate statement, not a vendor guarantee, and a
  different estate would need its own statement before the same join could
  be trusted there.

## 8. The discovery/import boundary

- **DI-1.** A discovery run **returns a candidate set and nothing else**. It
  writes no device row, creates no persistent record, starts no collection,
  stores no evidence, and retains no raw management-plane response (T-7).
- **DI-2.** **Only operator selection creates a device row.** The operator
  reviews the candidate set and multi-selects; import acts on that selection.
  Import is a separate act, with its own contract, outside this one (§1).
- **DI-3.** **Discovery shows everything it found.** Including candidates it
  cannot reach, candidates whose host is `MISSING` or `AMBIGUOUS` (HL-3),
  candidates it could not classify (CL-2), and candidates whose cluster is
  `NOT_EVALUABLE` (MC-2). The Product Owner chose honesty over filtering:
  **exclusion is an operator act**. Discovery does not decide on the
  operator's behalf that something is not worth seeing.
- **DI-4.** A candidate is never dropped for being incomplete, unclassifiable,
  unresolvable or inconvenient. It is returned with the honest outcome
  vocabulary of §5 attached.
- **DI-5.** A discovery run is **idempotent because it has no output but its
  return value**. Running it twice changes nothing, because running it once
  changed nothing.
- **DI-6.** Discovery performs no deduplication against existing device rows
  and makes no claim about whether a candidate is already imported. That
  reconciliation is import's question and is deliberately not answered here.
- **DI-7.** Nothing in discovery is a device-facing behaviour, so nothing in
  this document lifts, weakens or touches the collection gate. **This contract
  enables nothing by itself.**

## 9. Acceptance checks

Runnable from the repository root or against a management database. None names
an absolute filesystem path, a developer account, or any identity. Checks 1–6
are repository checks and are runnable now; checks 7–18 require a management
server and are the ones that actually prove the model — they are unrun at the
time of writing (§11).

1. The document's status line declares `DRAFT`:
   `grep -n -A 3 '^## Status$' docs/design/CP_AND_VSX_DISCOVERY_CONTRACT.md`
   shows a leading `DRAFT` token and no applied status.
2. The repository privacy gate reports zero findings, invoked through the
   repository's own documented command:
   `python3 scripts/repository_privacy_check.py`.
3. The document contains no address literal:
   `grep -n -E '([0-9]{1,3}\.){3}[0-9]{1,3}' docs/design/CP_AND_VSX_DISCOVERY_CONTRACT.md`
   matches nothing.
4. The document names no existing Python module of the product:
   `grep -n -E '\butils/[A-Za-z0-9_/]*\.py' docs/design/CP_AND_VSX_DISCOVERY_CONTRACT.md`
   matches nothing — the existing Python is know-how only (§1).
5. The diff is exactly one new document:
   `git diff --name-status origin/main...HEAD` reports exactly one line, an
   `A` against a path under `docs/design/`.
6. `git diff --check origin/main...HEAD` is clean, and
   `python3 -m pytest tests/test_contract_authority_status.py tests/test_architecture_convergence.py tests/test_project_files_budget.py -q`
   passes.
7. **CL-1/CL-2 totality.** Over a full enumeration, every object maps to
   exactly one of K-1 to K-10, or to `UNCLASSIFIED`/`AMBIGUOUS`. The counts of
   zero-kind and multi-kind objects are reported; a non-zero count is a
   finding to record, never a reason to fall back to a nearest kind.
8. **NP-5 name-blindness (the decisive check).** The same enumeration is
   classified twice: once as returned, and once with **every display-name
   field replaced by a single constant**. The kind assignment, every host
   resolution and every cluster link are **identical** between the two runs.
   Any difference proves a rule read a name.
9. **HR-1/HR-4 exhaustiveness.** Every candidate produces exactly one of the
   three host-resolution outcomes, and the per-outcome counts are reported. No
   candidate produces two.
10. **HR-4 type-blindness.** Host resolution computed without any test on
    object type produces the same outcome for every candidate as the same
    computation with such a test available. This is the machine proof that the
    clustered and standalone forms agree.
11. **HL-1 fail-closed.** For every candidate resolved as a virtual system,
    the number of same-domain candidates whose own address equals its
    management address is reported. Where that number is not exactly one, the
    candidate carries `MISSING` or `AMBIGUOUS` and **no host identifier is
    recorded**.
12. **MC-1/MC-2 identifier-only join.** Every member candidate's cluster link
    is recorded by identifier. A run with every cluster display name replaced
    by a constant produces identical links. A run with the identifier removed
    from every cluster reference produces **zero links and `NOT_EVALUABLE`** —
    never a name-based fallback.
13. **LV-1/LV-3 vocabulary.** Over the returned candidate set, no field name
    and no enumerated field value belongs to the vocabulary up, down, online,
    offline, reachable, unreachable, healthy, unhealthy, or a synonym; and no
    field is a function of L-S1, L-S2 or L-S3 alone.
14. **DI-1 no writes.** The product's own store is byte-identical before and
    after a full discovery run: no device row, no evidence record, no retained
    response. The run writes no file.
15. **T-4 no device contact.** A full run performed with **every candidate
    address made unroutable** completes and returns a candidate set identical
    to a run performed without that condition. A run that changes is a run
    that touched a device.
16. **T-3 read-only.** The management server's own audit of the run's session
    shows reads only, and no object change, policy operation or publish.
17. **T-2 bounded.** The number of management-plane requests a run issues is
    reported, and equals one session plus one domain enumeration plus, per
    domain and per queried object type, the number of pages required. It grows
    with domains and pages and with nothing else.
18. **DI-3/DI-4 no filtering.** The returned candidate count equals the number
    of objects the per-domain queries enumerated. The number of candidates
    dropped for being unreachable, unclassifiable or unresolvable is **zero**.

## 10. `UNKNOWN` register

Every clause the measurement did not settle, with what would settle it.
`AGENTS.md` requires explicit `UNKNOWN` over invented certainty; a plausible
value here would be a guess wearing a decision's clothes.

| Id | `UNKNOWN` | What settles it |
| --- | --- | --- |
| U-1 | Whether the stable identifier is unique across domains, or only within one domain | one enumeration across every domain, checked for a repeated identifier; or vendor documentation of the identifier's scope (VD-3). Until then CR-1's *(domain, identifier)* pair is the key, which is correct either way |
| U-2 | What the management-plane connection state actually means, given that a *communicating* state was measured on powered-off devices | vendor documentation of the field (VD-2), corroborated against a device whose power state is independently known. LV-5 forbids filling it from product knowledge |
| U-3 | Whether a cluster object carries a member list — the cluster→member direction. Only member→cluster was measured | one enumeration inspecting a cluster object's fields for a member collection. Until then MC-3 builds the tree from the member side only |
| U-4 | Whether a cluster-type candidate carries an own address, and what it denotes | field enumeration over cluster objects of K-5, K-6 and K-7. CR-3a holds regardless of the answer |
| U-5 | Whether the management-address value is unique among candidates within a domain, or may be shared by several | a count over one enumeration. HL-1's fail-closed outcome holds regardless |
| U-6 | Whether model, software version and connection state are supplied per kind, and what a model or version *means* on a virtual system, which has no hardware of its own | field enumeration per kind. CR-8a/CR-8b/CR-9 forbid inheritance regardless |
| U-7 | Whether the three object types are the complete set that can represent a security device in this management database | enumeration of the object-type vocabulary, checked against vendor documentation. A fourth type would be a silently missing candidate class, not an error |
| U-8 | Whether K-1 to K-10 are exhaustive — whether other flag combinations occur on other estates | check 7 run over an estate with a wider mix. CL-2's `UNCLASSIFIED` outcome is what makes an unmeasured combination visible instead of silent |
| U-9 | Whether the flags and fields this contract relies on are stable across software generations; one generation was measured | the same enumeration against a second generation, or vendor documentation of the schema's compatibility guarantee (VD-5) |
| U-10 | Whether the physical device serving a virtual system can change — so that a host link is a snapshot rather than a durable fact | two enumerations taken across a member state change. Until settled, a host link is true as of its run and is not carried forward |
| U-11 | Whether the domain enumeration returns every domain that exists, or only every domain the credential can see | comparison against an independently known domain count. It matters because a missing domain produces a silently missing candidate, not an error (HL-3 is where it surfaces) |
| U-12 | Whether a `MISSING` host (HL-1) indicates an unenumerated domain, a cross-domain host, or a genuinely absent object | one enumeration in which a known-absent host is added to the queried scope. Until then `MISSING` is reported as `MISSING` and not interpreted |

## 11. What this contract defers, and what it does not prove

**Deferred, with the reason each cannot be decided here:**

- **Import.** What a device row is, what identity it carries, how a candidate
  is reconciled against a row that already exists, and what a second discovery
  run means for rows created by the first. §8 fixes the boundary; the far side
  of it needs its own contract and its own measurement.
- **Collection.** Every device-facing behaviour. The collection gate holds and
  this contract does not touch it (DI-7).
- **Liveness.** §7, deliberately and with its rules stated. It is deferred to
  a movement authorized to contact a device, not merely postponed.
- **The operator screen.** What the candidate set looks like, how it pages,
  sorts, filters and multi-selects. LV-1 and NP-4 constrain what a screen may
  show; nothing here designs one.
- **Re-discovery cadence, scheduling and change detection.** Each depends on
  import existing.
- **Estates with more than one management server**, and how candidates from
  two of them relate. One server was measured (§2).
- **Management-credential handling** — storage, scope and rotation. T-5 fixes
  that there is exactly one and that it is not a device credential; it decides
  nothing about how it is held.
- **Palo Alto.** Sequenced after, by the Product Owner (§1).

**What applying a status to this contract would not prove:**

- It would prove the rules are **stated and checkable**. It would prove
  nothing about any artifact, because this movement creates none: there is no
  discovery implementation at the time of writing, and checks 7–18 of §9 are
  therefore **unrun**.
- It proves **no device fact whatsoever**. Every rule here is about a
  management database row. `AGENTS.md` "Evidence laws" is explicit that
  management-plane observation is not direct-device runtime truth, and
  configuration intent is not runtime truth; a complete and correct candidate
  set is still zero real-environment device evidence.
- It is **not a vendor law**. One multi-domain management server, one software
  generation, several domains, one session, one point in time (§2). A reader
  applying this to a different estate is applying a rule set that was observed
  once, and check 7 and check 8 are how they find out whether it held.
- It would close **none** of the twelve `UNKNOWN`s of §10, and none of them may
  be closed by assertion.
- It authorizes **no implementation**. `AGENTS.md` "Contract-status law": a
  `DRAFT` document may guide investigation and must not be treated as
  implementation authority.

## 12. Cross-references

- `AGENTS.md` — Identity law, Evidence laws, `UNKNOWN`/fail-closed law, Vendor
  semantics law, Raw-evidence law, Sensitive identity reporting law,
  Diagnostic-path law, Network action taxonomy, Check Point section.
- `docs/design/PO_DECISION_RECORD_2026_09_12.md` §2, §4 — the existing Python
  as know-how only, and the product sequence this contract's first step
  belongs to.
- `docs/design/DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md`
  §14 — the existing collector's own hard-coded, version-pinned path defect,
  the reason CS-6b requires the channel port to be derived rather than
  hard-coded.
- `PRIVACY_AND_DATA_HANDLING.md` — the data-sensitivity vocabulary and the
  repository privacy gate §9 check 2 invokes.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — the network-device command gate that
  VD-1 to VD-5 would each have to pass before becoming device-facing.
