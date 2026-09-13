# Palo Alto discovery contract

## Status

**DRAFT — NOT implementation authority, and authorizes nothing.** This
document is a proposal for Product Owner review, clause by clause. Applying
`FROZEN` (or any other status) to this document is the Product Owner's act,
not this movement's — `AGENTS.md` "Contract-status law" is explicit that a
`DRAFT` "must not be treated as implementation authority, cited as approving
a command/schema/identity model." Nothing in this document lifts, weakens or
touches the collection gate of `docs/design/PO_DECISION_RECORD_2026_09_12.md`
§1, or any boundary the gate lift of §2 below does not itself state.

**A provenance gap, recorded here rather than papered over.** This movement
was dispatched to write this contract "resting on the measurements the
Product Owner took on 2026-09-13." Two documents this movement's own dispatch
brief named as the expected authority and evidence sources —
a decision record fixing terminology/transport/identity/resolution
boundaries, and a committed record of the measurement's answers — do not
exist in this repository or its git history at the time of writing; only the
question list (`PAN_DISCOVERY_MEASUREMENT_BRIEF_2026_09_13.md`, `DRAFT`) and
the gate lift (`PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md`,
`FROZEN`) exist. `AGENTS.md` "Authority hierarchy" item 7 holds chat/session
memory non-authoritative and requires that a new session reconstruct the
project from the repository alone. Every measured claim below is therefore
attributed to **this movement's own dispatch brief**, not to a separately
committed findings document, and this gap is carried into §2 and into the
`UNKNOWN` register (row U-0) rather than resolved by inventing a citation.
The Product Owner reviewing this draft can close the gap either by committing
the missing findings record for this contract to rest on, or by confirming
the dispatch brief's transcription directly.

## 1. Scope and authority

**In scope.** Discovery for Palo Alto Networks devices managed through
Panorama: the transport and entry point by which a candidate set is
obtained; the classification of the objects the one authorized enumeration
returns; the candidate row and the roles it carries; the structural
resolution of a virtual system to its host; high availability peer
resolution and its reciprocity rule; liveness, stated as an open and
honestly less-evidenced question than Check Point's; and the boundary at
which discovery stops and import begins.

**Out of scope, and not authorized here.**

- Any implementation — Java, schema, screen, API, or otherwise. This
  movement produces one document: no service, no query runner, no schema, no
  screen.
- Any method beyond the two `PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md`
  §2 authorizes. In particular: no per-device targeted read, no Panorama
  configuration read (`type=config`, `action=show`, `xpath=/config`),
  no high-availability state call, no `target=` parameter of any kind, and
  no direct firewall contact of any kind. Where a rule in this contract
  seems to need a third method, it is recorded `UNKNOWN` in §10 rather than
  reached for.
- Import: what a device row is, what identity it carries, how a candidate is
  reconciled against a row that already exists, and what a second discovery
  run means for rows the first created. §8 fixes only the boundary.
- Deciding which service owns the resulting operational unit after import.
  That decision is left open for a later movement and is not made here.
- Check Point, beyond citing `CP_AND_VSX_DISCOVERY_CONTRACT.md` as the shape
  this document follows and, where its rules do not transfer, as a contrast.
- The existing Python. `PO_DECISION_RECORD_2026_09_12.md` §2 makes it
  know-how only. No rule here is derived from it, restated from it, or a plan
  to port, wrap, transliterate or invoke it. It is cited exactly once below
  (§8), to name a defect this contract forbids repeating.
- Applying any contract status, to this document or any other.
- Template, Template Stack and Device Group relationships. The Product Owner
  declined the Panorama configuration read that would produce them
  (`PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md` §3);
  they are absent from this contract by that decision, not for want of
  evidence.

**Authority.** `AGENTS.md` is the constitution and governs every clause
here — in particular its identity law (identifiers are opaque), its evidence
laws (management-plane observation is not device runtime truth), its
`UNKNOWN`/fail-closed law, its vendor-semantics law, its raw-evidence law,
its sensitive-identity-reporting law and its diagnostic-path law. This
contract does not restate those laws; it turns them into construction rules
with checks, for this vendor.
`PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md` (FROZEN) is
the only authority that lifts any part of the collection gate for Palo Alto,
and it lifts exactly the two methods §3 names — nothing here reaches past it.
`PO_DECISION_RECORD_2026_09_12.md` §1, §2 and §4 remain in force: the gate
generally, the existing Python as know-how only, and the discovery-then-
import-then-collection product sequence this contract's first step belongs
to. `CP_AND_VSX_DISCOVERY_CONTRACT.md` (FROZEN for Check Point and VSX
discovery, not for Palo Alto) is cited throughout as the **shape** this
document follows — its structure and its discipline, not its rules; where a
Check Point rule does not transfer, this document says so explicitly rather
than silently omitting the comparison.

## 2. Evidence basis, and the scope of the measurement

**What was measured.** One Panorama server, one software generation, in one
authenticated session, at one point in time, by the Product Owner, on
2026-09-13 — using exactly the two methods
`PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md` §2
authorizes: one authenticated session obtained through the vendor's own key
generation call, and one read-only managed-device enumeration
(`type=op`, `cmd=<show><devices><all></devices></show>`). The question set
run against that session is `PAN_DISCOVERY_MEASUREMENT_BRIEF_2026_09_13.md`
(`DRAFT` — cited here as provenance for *why* these questions were asked,
never as authority for an answer). No firewall was contacted.

**How the answers reached this document.** The Status block above records
that the answers were transcribed directly into this movement's dispatch
brief rather than into a separately committed findings record. Every
measured claim in §§3–8 traces to that transcription; where the dispatch
brief did not state a specific count, presence rate, or shape, this contract
records the gap as `UNKNOWN` (§10) rather than filling it — the same
discipline `CP_AND_VSX_DISCOVERY_CONTRACT.md` §2 applies to its own
measurement, restated here for a measurement with a thinner paper trail.

**What that licenses.** It licenses the rules below as rules about **one
Panorama managed-device enumeration response**, of that class, on one server,
one generation, one session, one point in time. It does **not** license any
of them as a vendor law. `AGENTS.md` "Vendor semantics law" holds regardless
of how the measurement was transcribed: a field name is not its contract,
and a command returning output does not prove the reader understood it.

**Where official documentation would be required.** This contract governs a
read-only projection of a Panorama management-plane response. No rule in it
is device-facing, because discovery contacts no device (§3). The following
would each become a safety-critical vendor semantic, and would require
official vendor documentation before any movement could rely on it — nothing
in this contract depends on any of them being answered, because nothing in
this contract contacts anything:

- **VD-1.** Whether either address role of §5 (own address, IPv4 or IPv6) is
  ever safe to use as a connection target. Here each is a locator on a
  management-database row and nothing else; using either to reach a device
  is a different claim about a different plane and must pass the
  network-device command gate before any movement acts on it.
- **VD-2.** The meaning of the connection-state role of §7. It is not yet
  disproven or proven as a liveness signal for this vendor — see §7.2's
  honest distinction from Check Point's L-S1.
- **VD-3.** The stability and uniqueness guarantee of the stable identifier
  (serial) — across how wide a scope it is unique, and how long it survives.
- **VD-4.** The meaning of the three shared-policy elements a virtual-system
  entry carries (§6), if a future movement wants to act on one rather than
  merely display it.
- **VD-5.** The schema's compatibility guarantee across Panorama and PAN-OS
  software generations. One generation was measured.

## 3. Transport and entry point

- **T-1.** One authenticated session to Panorama, obtained through the
  vendor's own key generation call, opened once per discovery run and closed
  when the run ends. A run that cannot close its session reports that; it
  does not leave one open.
- **T-2.** The entry point is exactly one read-only managed-device
  enumeration call against Panorama (`type=op`,
  `cmd=<show><devices><all></devices></show>`). A run performs no other
  query. Unlike the Check Point contract's per-domain query loop, this
  vendor's authorized candidate set arrives from **one call** — the gate lift
  is deliberately narrower for exactly this reason
  (`PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md` §5).
- **T-3.** The call is **read-only**. It performs no management-plane write
  of any kind: no object creation or change, no policy operation, no commit
  or push, and no session state left behind holding a lock.
- **T-4. No device is contacted at any point in discovery.** No SSH, no
  device API call, no ICMP, no TCP probe, no name resolution performed
  against a candidate's address for the purpose of reaching it. Every
  address this contract handles is a value in a Panorama response row, never
  a destination. This is the property the whole design is built on, exactly
  as `CP_AND_VSX_DISCOVERY_CONTRACT.md` T-4 states for Check Point: it is
  safe to run against an entire estate, it needs no maintenance window, it
  requires no device credential, and it sits entirely outside the collection
  gate.
- **T-5.** One management-plane credential, to Panorama. Discovery neither
  requires nor accepts a device credential (`AGENTS.md` "Diagnostic-path
  law"). No `target=` parameter is ever set on the enumeration call — a
  `target=` parameter is a per-device path and is out of scope by T-2.
- **T-6.** The single call is issued once per run at the current maturity.
  Any increase in call frequency or concurrency requires the relevant vendor
  interaction-safety gate first (`AGENTS.md` "Engineering laws").
- **T-7.** No raw Panorama response is persisted. `AGENTS.md` "Raw-evidence
  law": response in memory → parse the minimum semantics this contract names
  → safe fields and relationships → discard the raw response.

## 4. Classification

**CL-1. The measured set is homogeneous.** Every entry the enumeration
returns carries a device-type marker element, and that element was measured
present on every entry and **empty on every entry**. Unlike Check Point's
three object types crossed with three flags, there is no second dimension
here to cross: nothing in the measured response distinguishes one entry's
kind from another's. **This contract therefore defines no kind lattice.**
There is one candidate shape, not ten, and §6's candidate row is written for
that one shape.

**CL-2. The blind spot this creates, stated as a scope consequence, not a
data claim.** Dedicated log collectors and WildFire appliances are addressed
by their own configuration nodes in Panorama — nodes the gate lift does not
authorize a read against (§1). They are therefore **invisible to the
authorized method**: this contract's candidate set cannot and does not
contain them. This is an **absence by scope decision**, not an absence in
fact — `AGENTS.md` "`UNKNOWN`/fail-closed law": absence of evidence is not
evidence of absence. A reader of a candidate set produced under this
contract must not infer that an estate has no log collectors or WildFire
appliances merely because none appear in it.

**CL-3. The device-type marker is carried, not discarded.** Even though it
is measured empty on every entry today, it is carried under its own name in
the candidate row (§6) rather than dropped, exactly because an empty
measured value is itself a finding (CL-1) and a future non-empty value would
be a finding too (U-3 in §10) — it is not filled in, inferred, or replaced
with a computed kind.

**CL-4. No kind is determined by a display name, a name pattern, a label, an
ordinal, a model string, a version string or an address.** This is the same
prohibition `CP_AND_VSX_DISCOVERY_CONTRACT.md` CL-1 states, and it holds even
though there is no kind lattice to protect it against: it constrains any
future movement that tries to build one from a field this contract did not
measure as a discriminator.

## 5. Identity and addressing

- **ID-1. The stable identifier (serial) is the only join key.** No
  relationship in this contract — host resolution, peer pairing, or
  anything else — may be derived from any other field. It is **opaque**:
  not cast, not trimmed of leading zeroes, not normalized, not parsed for
  meaning (`AGENTS.md` "Identity law").
- **ID-2. Where the serial is absent from a candidate, no relationship that
  depends on it may be formed for that candidate.** The outcome is
  `NOT_EVALUABLE`, not a guess and not a silent drop — the candidate is
  still returned (§8). Whether the serial is present on every entry or only
  some was not settled by the measurement transcribed into this document;
  §10 U-1 records it.
- **ID-3. The display name is never a join key.** It is carried on the
  candidate row and shown to a human. That is its only use — it is never
  compared, never used to break a tie, and never an input to a relationship
  rule. This mirrors `CP_AND_VSX_DISCOVERY_CONTRACT.md` §5.4 in full, and
  §9 check 8 below is how it is protected by a test rather than intention.
- **ID-4. Two address roles, kept separate.** A candidate's own address is
  measured to arrive as **two separate elements — an IPv4 one and a
  separate IPv6 one** — not a single overloaded address field. Both are
  **locators, never join keys**. Neither is ever compared, against a peer's
  address or against a peer's own address, to derive a relationship of any
  kind: not host resolution, not peer pairing, not deduplication.
- **ID-5. Consequence of ID-4 for host and peer resolution.** Because
  neither address role is a join key, this contract's structural
  virtual-system resolution (§6) and its high-availability resolution (§7)
  are written entirely without reference to either address. Where a future
  movement finds itself needing to compare an address to form a
  relationship, it has left this contract (`AGENTS.md` "Identity law" —
  "presentation identity != security identity" applies to a locator exactly
  as it applies to a name).

## 6. Virtual systems — resolved structurally, at discovery

- **VS-1. Virtual systems arrive inside the enumeration.** Where a device
  entry hosts virtual systems, the virtual systems arrive as **entries nested
  inside that device's own entry in the same response** — one nested entry
  per virtual system. No second call is made and no device is contacted to
  obtain them (T-2, T-4).
- **VS-2. Each virtual-system entry carries its own identifier, its own
  display name, and three shared-policy elements.** The identifier and
  display name follow ID-1/ID-3: the identifier is a join key (scoped to its
  parent, VS-3), the display name is not. The meaning of the three
  shared-policy elements individually was not settled by the measurement
  transcribed into this document; they are carried under their own names
  (role, not vendor field name) and are not merged, interpreted, or composed
  into anything else. Acting on one of them rather than displaying it is
  VD-4 and is out of scope.
- **VS-3. The host relationship is structural, not resolved.** Because a
  virtual-system entry is nested inside its host's entry, the host
  relationship is given directly by the response's own structure — it is
  read off, not computed from two fields. `CP_AND_VSX_DISCOVERY_CONTRACT.md`
  §5.1's two-address host-resolution invariant (HR-1 to HR-4) exists because
  Check Point's virtual systems arrive as sibling objects that must be
  matched to a host by address. **That problem does not exist here**: this
  vendor's virtual systems never need a two-address invariant, and this
  contract deliberately does not build one for a mismatch that cannot occur
  under VS-1.
- **VS-4. A virtual-system entry is still a candidate in its own right.** It
  is returned, shown, and carries the candidate row of §5–§7 exactly as a
  physical device entry does (subject to whichever roles VS-2 does not
  apply to it). Nesting is a structural fact about where it was found, not a
  reason to omit it from the candidate set (§8).

## 7. High availability — peer resolution and its reciprocity rule

**HA-0. What is carried.** Where a device entry participates in a
high-availability pair, the enumeration carries its **peer as a serial** —
not a name, not an address. Consistent with ID-1, the identifier is the only
thing this contract will ever compare to form the pair.

**HA-1. The reciprocity rule.** A pair is formed **only when** candidate A's
peer-serial names candidate B **and** candidate B's peer-serial names
candidate A, **both resolved within the same enumeration response**. A claim
in one direction alone never forms a pair.

**HA-2. A claim resolving to no entry is `NOT_EVALUABLE`.** Where a
candidate's peer-serial does not match the serial of any candidate in the
same response, the pairing outcome for that candidate is `NOT_EVALUABLE` —
never `MISSING` treated as a device fact, and never silently ignored.

**HA-3. A claim naming the candidate itself is `NOT_EVALUABLE`.** Where a
candidate's peer-serial equals its own serial, the pairing outcome is
`NOT_EVALUABLE`. It is recorded as a finding worth surfacing (a
self-referential claim is not a value to silently normalize away), never
resolved as "no peer" and never resolved as "paired with itself."

**HA-4. A one-sided claim is `NOT_EVALUABLE`.** Where candidate A names
candidate B but candidate B's own peer-serial does not name A back — whether
B names a third candidate, names nothing, or carries no peer-serial at all —
the outcome for both A and B is `NOT_EVALUABLE`, and **no pair is ever
formed from one side alone**. `AGENTS.md` "Evidence laws" is the reason,
stated exactly: "a member's report about its peer != independent peer
observation. One side's claim about the other is one-sided until the other
side independently corroborates it in the same evidence-collection pass."
HA-4 is that law turned into a construction rule for this field.

**HA-5. `NOT_EVALUABLE` is an outcome, not a drop.** In every case of HA-2,
HA-3 and HA-4 the candidate is still returned with its honest outcome
attached (§8). A `NOT_EVALUABLE` pairing is never hidden, and it is never
retried by relaxing the rule — HA-1 does not have a fallback.

## 8. Liveness — a question this vendor has not yet had the search to answer

### 8.1 The one signal measured

**LV-0.** The enumeration carries a connection-state element per candidate.
This is the vendor's analogue of `CP_AND_VSX_DISCOVERY_CONTRACT.md` §7's
disproven L-S1, and it carries **the same prohibition**, stated fresh for
this vendor rather than inherited by assumption:

- **LV-1.** No candidate-row field, label, colour, icon, badge, sort key or
  filter derived from the connection-state element — or from any other
  field this contract carries — may express up, down, online, offline,
  reachable, unreachable, healthy, unhealthy, or any synonym of them. Not
  derived, not computed, not aggregated.
- **LV-2.** Where carried, the connection-state element is shown under its
  own name, with its own meaning, and is never merged into a single
  "status" with anything else this contract names.
- **LV-3.** No derived value may be computed from the connection-state
  element, alone or combined with any other field, that has liveness
  semantics — no health score, no traffic light, no "N of M reachable"
  roll-up.
- **LV-4.** Where an operator asks whether a candidate is up, discovery
  answers `UNKNOWN`. Absence of evidence is not evidence of absence
  (`AGENTS.md`).
- **LV-5.** A candidate whose connection-state element reports anything
  other than an established state — or carries no such element at all — is
  still returned and still shown. LV-1 forbids labelling it unreachable; it
  does not permit hiding it.

### 8.2 What makes this vendor's `UNKNOWN` weaker than Check Point's, stated honestly

**LV-6. This vendor has not had Check Point's three-plane negative search.**
`CP_AND_VSX_DISCOVERY_CONTRACT.md` §7.1a compared 372 first-level fields of a
confirmed-powered-off device's full object record against a confirmed-live
device's, of the same model, version and role, and searched two further
planes, all three negative — which is what makes Check Point's liveness
`UNKNOWN` (LV-4 there) an **evidenced** absence rather than a merely
declared one. No equivalent search has been run for Palo Alto at the time of
writing. This contract's LV-4 is therefore a **declared** `UNKNOWN`, not yet
an **evidenced** one, and no clause in this document may be read as claiming
otherwise.

**LV-7. What would evidence it.** Two things, neither authorized by the
current gate lift and neither performed here:

1. A field-by-field comparison of the full object record the enumeration
   returns for a device independently confirmed powered off against the
   full object record for a device confirmed live, of matching model,
   software version and role — the same method as Check Point's L-S4,
   applied to this vendor's own response shape.
2. A search of every further plane a future, separately authorized
   collection method reads, for any field that moves with a device's power
   state — the same method as Check Point's L-S5/L-S6, applied to whatever
   this vendor's equivalent planes turn out to be. Which planes those are is
   itself unmeasured (§10 U-9).

Until both are done, LV-4's `UNKNOWN` stands as declared, and VD-2's
question — what the connection-state element actually means — stands
unanswered rather than assumed safe or assumed unsafe.

## 9. The connection timestamp and certificate elements — kept apart, as measured

- **CS-1.** Where the enumeration carries a connection timestamp element, it
  is carried under its own name and never merged into a status, and never
  read as a liveness signal on its own or in combination with anything else
  this contract names. §8's prohibition (LV-1 to LV-3) applies to it exactly
  as it applies to the connection-state element.
- **CS-2.** Where the enumeration carries certificate elements, they are
  carried under their own names and never merged into a status, and never
  read as liveness.
- **CS-3. Why this is worth stating even though nothing here proves a
  relationship between them.** `CP_AND_VSX_DISCOVERY_CONTRACT.md` §7.1's
  L-S3 measured a Check Point console indicator that in fact reflected a
  certificate condition, and was read by operators as reachability — a
  presentation choice that merged two unrelated facts into one misleading
  signal. This vendor's measured response already keeps a connection
  timestamp, certificate condition and connection state as separate
  elements. CS-1 and CS-2 exist so a future presentation layer does not
  re-introduce the same merge this vendor's own data does not make.

## 10. The discovery/import boundary

- **DI-1.** A discovery run returns a candidate set and nothing else. It
  writes no device row, creates no persistent record, starts no collection,
  and retains no raw Panorama response (T-7).
- **DI-2.** Only operator selection creates a device row. Import is a
  separate act, with its own contract, outside this one (§1).
- **DI-3. Discovery shows everything it found.** The measurement settles
  that the enumeration returns entries for devices that are not connected —
  the connection-state element (§8) is not always the established value,
  and an entry with a non-established or absent connection-state value is
  still returned by the same call. Nothing authorizes filtering it out, so
  none is permitted.
- **DI-4. Forbidden filter one: dropping an entry for having no serial.**
  The existing collector drops an entry from its candidate list when the
  entry carries no serial. This contract forbids that: ID-2 already gives
  the correct treatment of a missing serial — `NOT_EVALUABLE` for any
  relationship that needs it — and a candidate that only fails ID-1's join
  key is exactly the kind of candidate DI-3 requires to be shown, not
  dropped.
- **DI-5. Forbidden filter two: selecting connected entries first.** The
  existing collector's other path prioritizes or selects entries whose
  connection-state element reports the established value, ahead of or
  instead of the rest. This contract forbids that: §8 has already measured
  and stated that the connection-state element is not a liveness signal
  proven safe to filter on, and DI-3 requires the full returned set.
- **DI-6.** A candidate is never dropped for being incomplete,
  unclassifiable (there is none to be, per §4), unresolvable (HA-2 to HA-4)
  or otherwise inconvenient. It is returned with the honest outcome
  vocabulary of §§5–8 attached.
- **DI-7.** Discovery performs no deduplication against existing device rows
  and makes no claim about whether a candidate is already imported. That is
  import's question (§1).
- **DI-8.** Nothing in discovery is a device-facing behaviour, so nothing in
  this document lifts, weakens or touches the collection gate beyond the
  bounded lift `PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md`
  §2 already states. **This contract enables nothing by itself.**

## 11. Acceptance checks

Three bands, following `CP_AND_VSX_DISCOVERY_CONTRACT.md` §9's amended split:
what is runnable now with no fixture and no server, what is a decidable
property over constructed input, and what needs a live Panorama server and
is therefore unrun at the time of writing.

### Band 1 — repository checks, runnable now

1. The status line declares `DRAFT` and states plainly that the document
   authorizes nothing:
   `grep -n -A 2 '^## Status$' docs/design/PAN_DISCOVERY_CONTRACT.md` shows a
   leading `DRAFT` token.
2. The repository privacy gate reports zero findings:
   `python3 scripts/repository_privacy_check.py`.
3. The document contains no address literal:
   `grep -n -E '([0-9]{1,3}\.){3}[0-9]{1,3}' docs/design/PAN_DISCOVERY_CONTRACT.md`
   matches nothing, and no literal resembling a colon-separated IPv6 address
   appears anywhere in the document.
4. The document names no existing Python module of the product:
   `grep -n -E '\butils/[A-Za-z0-9_/]*\.py' docs/design/PAN_DISCOVERY_CONTRACT.md`
   matches nothing — the existing Python is know-how only (§1), cited in
   prose in §10 and nowhere else.
5. The diff is exactly one added file under `docs/design/`:
   `git diff --name-status origin/main...HEAD` reports exactly one `A` line,
   naming this document and no other path.
6. `git diff --check origin/main...HEAD` is clean, and
   `python3 -m pytest tests/test_contract_authority_status.py tests/test_architecture_convergence.py tests/test_project_files_budget.py -q`
   passes.

### Band 2 — property checks, provable over synthetic fixtures

These are decidable over constructed input, not over any real estate, and
**this is the acceptance surface the implementing movement can actually be
held to** — checks 7, 8 and 9 in particular, because HA-1, ID-3 and LV-1 are
each a rule about how a fixture behaves, not about what a live Panorama
server returns.

7. **HA-1 to HA-4 reciprocity (the decisive check).** Over a constructed set
   of candidates covering all four cases — a genuine reciprocal pair, a
   peer-serial resolving to no entry, a peer-serial naming the candidate
   itself, and a one-sided claim — the pairing outcome is `PAIRED` only for
   the reciprocal case and `NOT_EVALUABLE` for the other three, and no run
   ever produces a pair from the one-sided case alone.
8. **ID-3 name-blindness (the decisive check).** The same constructed
   candidate set is resolved twice: once as given, and once with every
   display name (device and virtual-system) replaced by a single constant.
   The pairing outcomes of check 7 and the structural virtual-system links
   of check 12 are identical between the two runs. Any difference proves a
   rule read a name.
9. **LV-1/LV-3 vocabulary (the decisive check).** Over a constructed
   candidate set, no field name and no enumerated field value produced by
   this contract's rules belongs to the vocabulary up, down, online,
   offline, reachable, unreachable, healthy, unhealthy, or a synonym; and no
   field is a function of the connection-state element alone.
10. **ID-4 address non-join.** Over a constructed set where two distinct
    candidates share an identical own-address value (IPv4, IPv6, or both),
    no relationship — pairing, host link, or any other — is formed between
    them from that fact alone.
11. **ID-1/ID-2 identifier-only join.** A run with every serial replaced by a
    differently formatted but representation-equal value (leading/trailing
    whitespace only) produces identical pairing outcomes to check 7. A run
    with a serial removed entirely produces `NOT_EVALUABLE` for every
    relationship depending on it — never a name-based or address-based
    fallback.
12. **VS-3 structural resolution.** Over a constructed device entry carrying
    nested virtual-system entries, each virtual-system candidate's host link
    is exactly its parent entry, established with zero simulated calls
    beyond the one enumeration — proving VS-3's claim that no second call is
    needed.

### Band 3 — management-server checks, unrun at the time of writing

These prove the model against a real Panorama estate; a fixture cannot
discharge them.

13. **DI-3 to DI-5 no filtering.** The returned candidate count equals the
    number of entries the one enumeration call returned. The number of
    candidates dropped for missing serial or for a non-established
    connection-state value is zero.
14. **Field-binding verification.** Every role this contract names is bound
    to exactly one concrete field of Panorama's own response schema, the
    binding is recorded and marked `UNVERIFIED` until confirmed against a
    live response, and this check is what turns `UNVERIFIED` into
    confirmed.
15. **T-3/T-4 no device contact.** A full run performed with every
    candidate's addresses made unroutable completes and returns a candidate
    set identical to a run performed without that condition.
16. **T-2 bounded request count.** The number of Panorama requests a run
    issues equals exactly two — one session and one enumeration call —
    regardless of estate size.

## 12. `UNKNOWN` register

Every clause the measurement, as transcribed into this document, did not
settle, with what would settle it. `AGENTS.md` requires explicit `UNKNOWN`
over invented certainty.

| Id | `UNKNOWN` | What settles it |
| --- | --- | --- |
| U-0 | Whether this document's transcription of the Product Owner's 2026-09-13 measurement matches a record the Product Owner can independently confirm — no separately committed findings document exists in the repository at the time of writing | the Product Owner committing a findings record this contract can be checked against, or confirming the transcription directly in review |
| U-1 | Whether the stable identifier (serial) is present on every enumeration entry, or only some | one full enumeration, counting presence. ID-2 holds regardless of the answer |
| U-2 | What the connection-state element actually means | official vendor documentation (VD-2), plus the two-part negative search of LV-7, corroborated against a device whose power state is independently known |
| U-3 | Whether the device-type marker (CL-1) is ever non-empty, on this or a different software generation or estate | a repeat enumeration on a wider estate or later generation; a non-empty value would require revisiting §4 entirely, not patching it |
| U-4 | Whether a dedicated log collector, a WildFire appliance, or Panorama itself can ever appear as an entry in the same managed-device enumeration, rather than being confined to its own configuration node | vendor documentation of the enumeration's scope, or an enumeration run against an estate independently known to include such appliances |
| U-5 | Whether both address roles (ID-4) can ever be absent together, leaving a candidate with no locator at all | a field-presence count over one enumeration |
| U-6 | Whether the high-availability peer-serial reference (§7) is carried on every entry or only on entries configured for high availability | a count over one enumeration, cross-checked against an independently known set of high-availability-configured devices |
| U-7 | The individual meaning of each of the three shared-policy elements a virtual-system entry carries (VS-2), and whether that meaning is stable across software generations | vendor documentation (VD-4), or the same enumeration run against a second generation |
| U-8 | Whether the connection timestamp or certificate elements (§9) are ever populated for a virtual-system entry, which has no connection or certificate of its own the way a physical device does | a field-presence count scoped specifically to virtual-system entries |
| U-9 | Whether the managed-device enumeration returns every device Panorama manages, or only a subset visible to the credential or scope used | comparison against an independently known device count; it matters because a missing device produces a silently missing candidate, not a reported one |
| U-10 | Whether a non-firewall appliance type beyond a dedicated log collector or a WildFire appliance exists and is likewise excluded from the enumeration by configuration-node scope | vendor documentation of Panorama's configuration-node taxonomy, or an explicit Product Owner scope statement extending CL-2 |
| U-11 | Whether the flags, elements and structure this contract relies on are stable across Panorama and PAN-OS software generations; one generation was measured | the same enumeration run against a second generation, or vendor documentation of the schema's compatibility guarantee (VD-5) |

## 13. What this contract defers, and what it does not prove

**Deferred, with the reason each cannot be decided here:**

- **Import.** What a device row is, how a candidate is reconciled against a
  row that already exists, and what a second discovery run means for rows
  the first created. §10 fixes the boundary; the far side of it needs its
  own contract and its own measurement.
- **Collection.** Every device-facing behaviour. The collection gate holds
  and this contract does not touch it (DI-8).
- **Liveness.** §8, deliberately and with its rules stated, and honestly
  weaker than Check Point's — see LV-6.
- **The operator screen.** LV-1 and ID-3 constrain what a screen may show;
  nothing here designs one.
- **Which service owns the operational unit after import**, and
  re-discovery cadence, scheduling and change detection — each depends on
  import existing, and the operational-unit ownership question is left open
  for a decision council.
- **Estates with more than one Panorama server**, and how candidates from
  two of them relate. One server was measured (§2).
- **Management-credential handling** — storage, scope and rotation. T-5
  fixes that there is exactly one and that it is not a device credential; it
  decides nothing about how it is held.

**What applying a status to this contract would not prove:**

- It would prove the rules are stated and checkable. It would prove nothing
  about any artifact — this movement creates none, and Band 3 of §11 is
  therefore unrun at the time of writing.
- It proves no device fact whatsoever. Every rule here is about a Panorama
  response row. Management-plane observation is not direct-device runtime
  truth (`AGENTS.md` "Evidence laws").
- It is not a vendor law. One Panorama server, one software generation, one
  session, one point in time, and a transcription with an acknowledged
  provenance gap (U-0). A reader applying this to a different estate is
  applying a rule set observed once, through one paraphrase, and checks 13
  and 14 are how they find out whether it held.
- It would close none of the twelve `UNKNOWN`s of §12, and none of them may
  be closed by assertion.

## 14. Cross-references

- `AGENTS.md` — Identity law, Evidence laws, `UNKNOWN`/fail-closed law,
  Vendor semantics law, Raw-evidence law, Sensitive identity reporting law,
  Diagnostic-path law, Authority hierarchy.
- `CP_AND_VSX_DISCOVERY_CONTRACT.md` — the shape this document follows;
  FROZEN for Check Point and VSX discovery, not for Palo Alto.
- `PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md` — the
  gate lift this contract stays inside of; the two authorized methods and
  the scope decision on Template/Template Stack/Device Group data.
- `PO_DECISION_RECORD_2026_09_12.md` §1, §2, §4 — the collection gate
  generally, the existing Python as know-how only, and the product
  sequence.
- `PAN_DISCOVERY_MEASUREMENT_BRIEF_2026_09_13.md` — the question set behind
  the measurement this contract rests on; `DRAFT`, cited as provenance and
  not as authority.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — the network-device command gate that
  VD-1 to VD-5 would each have to pass before becoming device-facing.
