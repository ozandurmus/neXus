# Policy shadowing — reducing "earlier rule has unsupported constraints" (2026-11-05)

Status: DRAFT — 2026-11-05. Analysis and plan only. It authorizes nothing beyond what it cites as FROZEN; every
extension below that is not marked IMPLEMENTED needs its own review (and, where marked, a PO decision or a vendor
semantics amendment) before code. UNKNOWNs stay UNKNOWN.

Scope: the stored-snapshot shadow/redundancy analysis
(`ui2/service/.../policy/PolicyHygiene.java`, `PolicyNormalization.java`) and its test oracles
(`LegacyPolicyHygiene`, `PartialPriorOracle`). No device command, no collection change is proposed without being
named as one. Inputs: the live counts in the brief (2026-11-05; counters only, no estate identities), the code at
`origin/cloud-base/1105`, `PATH_ANALYSIS_VENDOR_SEMANTICS.md` (FROZEN 2026-11-04; "A0" below) and
`PO_DECISION_RECORD_2026_11_03_CP_LAYER_SEMANTICS.md` (RATIFIED).

## 0. How the UNKNOWN arises

A rule gets `UNKNOWN "earlier rule has unsupported constraints"` when an earlier enabled (or possibly enabled) rule of
its evaluation scope has no full match (`normalize` reports a problem) and that earlier rule is either

1. **no partial prior at all** (`partial()` returns null: a field of unknown meaning, a CP rule without its time
   column, nothing resolved) — then `Earlier.unknown` is set and it blocks *every* later rule of the scope; or
2. **a partial prior** that the analysis cannot prove disjoint from the later rule on any resolved dimension or
   placement (`Earlier#partialOverlap`).

The "blocking class" in the brief is the blocker's own `normalize` problem. The first thing a blocker fails decides
its class: cells are resolved first (source, destination, service, application), then zone / install-on evidence,
enabled state, action, then the extra fields in stored-map order. A class therefore says what the blocker failed
*first*, not everything it fails.

| Blocking class (live, 2026-11-05) | Count | Where it is raised | Blocks as |
|---|---:|---|---|
| PAN unresolved object | 4,475 | `Resolver.object`: object missing or status ≠ RESOLVED | partial prior (cell null) |
| PAN unsupported rule constraint | 2,484 | `normalize` extras loop | **no partial prior** → every later rule |
| CP dynamic or unsupported object type | 2,000 | `Resolver.object`, `accessRoleBound` | partial prior (cell null; CP source access role bounded) |
| PAN unsupported address representation | 1,578 | `Resolver.address` | partial prior (cell null) |
| CP non-terminal or unknown action | 1,335 | `normalize` action check | partial prior (all cells may be resolved) |
| CP empty or dynamic group | 272 | `Resolver.object` group branch | partial prior (cell null) |
| CP unsupported port expression | 168 | `Resolver.service` | partial prior (service null) |
| CP missing service values | 160 | `Resolver.service` | partial prior (service null) |

Two levers follow from this: give a blocker more resolved dimensions (or sound bounds for the unresolved ones), and
widen what a partial prior may prove. Bounds are only ever used where an *upper* bound suffices: to prove that a
partial prior cannot match, or cannot cover, a later rule. A bound never makes a rule a coverage candidate.

## 0.1 Live X0 split (2026-11-05 evening, aiview, PR 751)

All 99 stored policies answered (HTTP 200, no analysis budget reached): 111,003 rules, 97,391 with an UNKNOWN shadow
status, 60,217 of them "earlier rule has unsupported constraints". Rows: blockers / later rules they block (every
row with at least 100 blocked rules; status UNKNOWN on a PAN object is precedence UNKNOWN).

| Vendor | Blocker class, cell, stored status, stored type | Partial prior | Blockers | Blocked rules |
|---|---|---|---:|---:|
| CP | unresolved object, source, DYNAMIC, access-role | yes | 502 | 16,081 |
| PAN | user or category constraint, extra:source-user | yes | 208 | 15,106 |
| CP | dynamic or unsupported object type, source, RESOLVED, gateway | yes | 18 | 13,589 |
| CP | dynamic or unsupported object type, destination, RESOLVED, gateway | yes | 5 | 5,035 |
| PAN | unresolved object, source, UNKNOWN, address | yes | 81 | 1,935 |
| PAN | unsupported address representation, destination, RESOLVED, address | yes | 11 | 1,580 |
| CP | non-terminal or unknown action, action | no | 1 | 1,472 |
| PAN | unresolved object, destination, UNKNOWN, address | yes | 38 | 1,338 |
| CP | empty or dynamic group, source, RESOLVED, group | yes | 9 | 1,021 |
| PAN | unresolved object, destination, UNKNOWN, address-group | yes | 13 | 497 |
| CP | missing service values, service | yes | 39 | 466 |
| CP | unsupported service constraint, service | yes | 45 | 439 |
| PAN | unresolved object, source, UNKNOWN, address-group | yes | 18 | 408 |
| CP | unsupported port expression, service | yes | 17 | 316 |
| PAN | unresolved object, destination, DYNAMIC, external-list | yes | 1 | 180 |
| PAN | unresolved object, service, UNKNOWN, service | yes | 9 | 100 |
| PAN | negated selector, destination, RESOLVED, address | yes | 1 | 100 |

Reading:
- 58,745 of the 60,217 blocked rules sit behind a **partial prior**, so X1 (pass-over by non-containment) is the
  lever for almost all of them; the only no-partial-prior blocker is one CP rule with an unknown action (1,472 rules).
- Three rows hold 74 % of the blocked rules: CP source access roles (16,081), PAN source-user (15,106) and CP
  **gateway objects** as source or destination (23 blockers, 18,624 rules). "Stays UNKNOWN" below lists CP gateway
  objects as addresses; with 23 blockers behind 18,624 rules, a sound upper bound for a gateway object (its
  collected interface addresses) deserves its own vendor-semantics review.
- The PAN "unresolved object" rows are mostly precedence UNKNOWN (U2), not names missing from every scope.
- Check Point `native-type` is absent on these failing objects (UNKNOWN in the split), so it gives no sub-split.

**After X1 (PR 759, measured 2026-11-06 00:45 on the same 99 policies):** "earlier rule has unsupported constraints"
60,217 -> 56,085 (-4,132, -6.9 %); UNKNOWN shadow status 97,391 -> 93,259. The three large rows remain: PAN
source-user 15,094, CP gateway objects 22,298 (source and destination; blockers can now be reported later in the
rulebase, which moved rules between rows), CP source access roles 10,985. Most later rules sit inside the blocker's
resolved dimensions, so X1 cannot pass over those blockers; the gateway rows need the bound in
`CP_GATEWAY_OBJECT_BOUND_REVIEW_2026_11_05.md`, and the access-role and source-user rows need identity-dimension bounds.

## 1. Cross-cutting items

### X0 — diagnostic split of each class (counters only)

The counts above cannot be attributed to sub-causes (e.g. how much of PAN "unresolved object" is precedence
`UNKNOWN`, a dynamic address group, a predefined region or an application group with predefined applications). Add
a counter projection over stored snapshots: per blocking class, the failing cell, the stored object status, stored
type and (CP) `native-type`, and whether the blocker is a partial prior. Counters only, no names, no values,
matching the A0 "split the count by status and native type (counters only)" pattern. No semantics change; it sizes
every item below. Risk: none beyond the privacy gate.

### X1 — partial-prior pass-over by non-containment (single-rule cover)

Status: IMPLEMENTED (pending live measurement), 2026-11-05. Ratified by the PO on 2026-11-05 evening
(`PO_DECISION_RECORD_2026_11_05_EVENING_BATCH.md`). Implementation note: `PolicyNormalization#cannotCover` is consulted by
`Earlier#partialOverlap` (the only question X1 changes: may a partial prior be the single rule that covers this rule;
the CONFLICT/REDUNDANT cover search, "zone or install-on coverage is unproven", application default-port/overlap
reasons and the no-partial-prior blocker `Earlier.unknown` are untouched). A prior is passed over when its resolved
source or destination does not contain the rule's, or, with neither service application-default, its service, or, with
no opaque application identity on either side, its application -- exactly where `holds` answers FALSE; an unresolved
dimension or a token in play gives no verdict, and an access-role bound counts as an upper bound. A package-private
`PolicyHygiene.analyze(..., nonContainment)` overload switches it off as the reference. The blocker named for a rule that
stays blocked may move to a later prior. Verified by `PolicyHygieneNonContainmentTest` (unit cases plus a differential
over ~6,800 seeded snapshots with `PartialPriorOracle.assertNoCover`); the older partial-prior oracles now run with X1 off.
Live effect is not measured.

Semantics already in the engine: `NOT_SHADOWED` claims that **no single earlier rule fully covers** the rule
(`zonesCannotCover`, `Earlier#undecidedMayCover`, the A0 2026-11-04 amendment: "which is all a single-rule
NOT_SHADOWED verdict claims"). A fully resolved earlier rule that only partly overlaps a later rule is passed over;
a partial prior, however, must today be proven *disjoint*.

Safe extension: a partial prior is also passed over when one of its resolved dimensions provably does **not
contain** the later rule's. Proof: the prior's match lies inside the product of its resolved selectors (unresolved
dimensions are the universe; narrowing constraints, unknown action and unknown enabled state only shrink it; an
access-role bound is an upper bound). The later rule is a full match, a product of non-empty dimensions; a value of
the later rule outside the prior's selector on dimension *d* gives a packet of the later rule that the prior cannot
match, in any placement and whatever the unresolved dimensions are. So the prior cannot cover the rule.

Fail-closed details (mirroring `PolicyNormalization#holds`): "does not contain" counts only where `holds` would
answer FALSE — source and destination always; service only when neither side is `application-default`; application
only when neither side carries opaque application identities. Any token on that dimension gives no verdict.

- Effect (qualitative): the largest single lever; it applies to every class, and makes every bound below useful for
  cannot-cover as well as for disjointness. A blocker naming one host or one port no longer blocks later rules on
  wider networks or port ranges.
- Tests/oracle: the release criterion for an X1 release is `PartialPriorOracle.assertNoCover` (brute force over
  every earlier rule, resolved or not, in the open universe and the pair's smallest world, every access-role identity
  selection), not `assertReleaseIsSafe` (no shared packet). The two paths must stay separate: run the analysis with
  X1 disabled (package-private switch) as the reference, demand `assertReleaseIsSafe` for rules that reference
  releases, and `assertNoCover` only for the rules X1 adds. A differential run like `PolicyHygieneDifferentialTest`
  with per-vendor release/kept counters.
- Risk: MEDIUM governance, LOW technical. It relaxes the partial-prior criterion from "disjoint" to "cannot cover",
  which is the criterion full priors already use. This is a semantics broadening and needs PO ratification before
  code, not only a review.

## 2. Per class

### 2.1 PAN — unresolved object (4,475)

Semantics. `PanoramaPolicyMapper` stores a non-RESOLVED object for: a name defined in no visible scope (predefined
regions, built-in external dynamic lists, predefined App-ID applications), stored `UNRESOLVED`/type `unresolved`;
an object the two precedence orders disagree on (`mapUnverifiedPrecedence`), stored `UNKNOWN`; and dynamic objects
(dynamic address groups, external dynamic lists, application filters, dynamic user groups), stored `DYNAMIC`.
`Resolver.object` rejects all of them. Predefined applications named *directly* in a rule's application cell are
already opaque identities (`Resolver#contentApplication`), and application-default rules read application groups
through `Resolver#applications` — but a rule with explicit or any service that names an **application group** (or a
custom application) still goes through `Resolver.object`, which fails on the group's predefined members.

Safe extensions.
- **U1 — application groups and custom applications as identities in explicit-service rules.** Read the
  application cell of every rule exactly as `Resolver#applications` already reads an application-default rule's:
  a group is the union of its members, a predefined or custom application one opaque identity, compared by identity
  only and never disjoint. Vendor: an application group "is an object that contains applications that you want to
  treat similarly in policy" (PA-APPGRP). Repository semantics: the identity reading and its fail-closed `holds`
  are the ones shipped for content-provided applications (commit 4193fd4). Application filters stay unresolvable.
  Effect: a blocker becomes a full match, so later rules it only partly overlaps become NOT_SHADOWED and contained
  ones REDUNDANT/CONFLICT or `application overlap is unknown`. Tests: the oracle already reads groups and custom
  applications through `Universe#identities`; add a transform like `SyntheticPolicies#contentApplications` with
  groups of predefined applications, compared against the former reading. Risk: LOW (same reasoning as shipped code).
- **U2 — precedence-UNKNOWN objects bounded by both candidates.** The effective value is one of exactly two
  definitions: descendant-wins (default) or ancestor-wins (the "Objects defined in ancestors will take higher
  precedence" setting), both cited in `PanoramaPolicyMapper`. The union of both resolutions is an upper bound, used
  for disjointness and (with X1) cannot-cover only. Needs a mapper change to keep both candidates' values (today
  they are dropped). Effect: unknown until X0. Risk: LOW technical; a stored-shape change (worker and service).
- **Stays UNKNOWN.** Dynamic address groups: members are "populated dynamically using look ups for tags and
  tag-based filters" and tags can be "registered at runtime" (PA-DAG) — runtime state, not in the snapshot. External
  dynamic lists (fetched list content), predefined regions (content-release geolocation data, no documented XML
  identifiers, see the mapper's own note), application filters, FQDN members (see 2.4). No bound exists for these
  from collected data; X1 still lets the other dimensions of such a blocker decide.

### 2.2 PAN — unsupported rule constraint (2,484) — IMPLEMENTED (E1)

Semantics. In the PAN mapper's security-rule extras the only keys `normalize` rejects as "unsupported rule
constraint" are the Panorama target fields (`target/negate` other than the untargeted `no`, `target-selectors`,
`target-tags`, `target-vsys`, `target-unrecognized`): every other stored key is accepted or raises its own reason
(`source-user`, `category`, `rule-type`, `schedule`). So this class is exactly "the rule has a Panorama target".
Such a rule was also no partial prior, so each one blocked every later rule of its scope.

A0 §4d (FROZEN, HIGH): with no firewall selected the rule "is added to all of the (unchecked) firewalls in the device
group"; negate pushes it "to all managed firewalls associated with the device group except for the selected
device(s) and tag(s)"; negate with nothing selected adds it to no firewall. Every target form therefore puts the
rule on a subset of the device group's firewalls, and on each such firewall it matches exactly its selectors.

**E1 (IMPLEMENTED in this change).** A documented target (devices, devices restricted to virtual systems, tags,
negate yes or no) is a narrowing condition for partial priors (`PolicyNormalization#targetNarrows`, read only by
`partial()`). The rule stays "unsupported rule constraint" itself and never covers. `target-unrecognized` and a
negate value other than yes/no stay of unknown meaning (no partial prior). The `target-vsys` shape rests on the
repository parser only; it selects a subset of a selected device's contexts, so it narrows further.

- Effect: each targeted rule now blocks only the later rules it overlaps on every resolved dimension (in practice
  all four cells and the zones resolve for this class, since cells fail before extras), instead of every later rule.
  With X1 it would block only later rules it contains.
- Tests/oracle: `PartialPriorOracle.known` reads documented target fields as narrowing (ignoring them only enlarges
  the prior's match). `PolicyHygienePanTargetTest`: mapper-to-analysis cases for every documented and unparsed
  shape; a disjoint later rule is released for documented shapes and kept for unparsed ones; a seeded run
  (`SyntheticPolicies#panTargets` vs `#opaqueTargets`, the former reading) where every change must be a release
  confirmed by the brute-force oracle — 1,925 PAN snapshots, 58,917 rules, 144 released, 5,156 kept.
  `PolicyHygienePartialPriorTest` now uses the unparsed shapes as its "unknown meaning" example.
- Risk: LOW. A wrong reading would need a target form that adds a firewall outside the device group or changes what
  a rule matches on a firewall; §4d excludes both.

Next, for the targeted rule's **own** verdict (2,484 rules UNKNOWN for themselves):
- **E1b — covered by an earlier untargeted rule.** An earlier untargeted full match reaches every firewall of the
  device group: device-group pre-rules are pushed "to all the firewalls in a particular device group and its
  descendant device groups", shared pre-rules "to all the firewalls in all device groups" (PA-DGH). The later
  targeted rule exists on a subset of those firewalls, so an earlier rule that covers its match there covers it
  wherever it exists: REDUNDANT/CONFLICT as usual (vacuously true for negate with nothing selected). Read the
  targeted rule as a covered-only match, exactly like `PolicyNormalization#typed` (never an earlier rule, never a
  partial-to-full upgrade). A NOT_SHADOWED verdict for it is equally sound once every earlier rule is decided.
  Tests: oracle `coverDimensions` with targets read as narrowing for the *later* rule is not conservative, so the
  cover check must model a firewall dimension (targets as a subset, untargeted as all) rather than ignore targets.
  Risk: LOW-MEDIUM (the descendant-group statement is from Panorama 9.1–11.1 overview pages; confirm in A0 before code).
- **E1c — equal targets as a placement key.** Two rules with the same stored target fields (exact opaque equality,
  including negate) are on the same firewalls; such a pair can be compared as `install-on` pairs are. Small effect.
- Stays UNKNOWN: `target-unrecognized`, unknown negate values; which firewalls a target reaches is never resolved
  (tags and serials are not mapped to devices here; that is path analysis G8).

### 2.3 CP — dynamic or unsupported object type (2,000)

Semantics. Raised for: DYNAMIC objects (`access-role`, `dynamic-object`, `dns-domain`, `security-zone`,
`updatable-object`; `CpObjectTypes.DYNAMIC_TYPES`) outside the access-role source bound (an access role in the
destination, or one without readable networks); gateway and cluster objects (type `gateway`) used as source or
destination; application/URL objects (`application-site`, `-category`, `-group`) in the service column, which the
service resolver rejects; and any group whose type does not fit the column. The split is unknown until X0.

Safe extensions.
- **D1 — access role in the destination column, bounded by its networks.** Same claim as the 2026-11-04 amendment
  (a role's match lies within its networks), applied to the destination column. The amendment covers the source
  only and its X-Forwarded-For residual is source-specific, so this needs an A0 amendment first. Risk: MEDIUM.
- **Stays UNKNOWN.**
  - Gateway/cluster objects: which addresses a gateway object stands for in a rule (main address, every interface,
    cluster virtual addresses) is not established by the documentation read so far, and interfaces are not
    collected; the stored main address is a lower bound, useless for disjointness. Revisit only with a collected
    topology read (command gate) and a cited semantics.
  - `dns-domain`, `updatable-object`, `dynamic-object`, `security-zone`: membership is decided at enforcement time
    (DNS, feeds, runtime registration, interface topology).
  - Applications in the CP service column: the column matches a service *or* an application, and an application's
    ports depend on gateway settings not collected; no bound. The column stays unresolved (X1 still works on the
    other dimensions).

### 2.4 PAN — unsupported address representation (1,578)

Semantics. `Resolver.address` accepts IPv4 `ip-netmask`, `ip-range`, literal and the CP field names. PAN address
objects come in four types: IP Netmask and IP Range (IPv4 *or* IPv6), IP Wildcard Mask (IPv4 only, "can be used only
in a Security policy rule"), and FQDN, "resolved to IP addresses through DNS ... refreshed based on its TTL"
(PA-ADDR). So this class is IPv6 values and FQDN objects (wildcard values are not collected by the mapper and would
raise "missing address values").

Safe extensions.
- **A1 — IPv6 as its own address family.** Exact IPv6 ranges in an `ipv6` family (128-bit bounds; `Range` holds
  `long`, so a two-word or `BigInteger` range is needed). A range object has both ends in one IP version (PA-ADDR);
  security rules match the original (pre-NAT) addresses (A0 §4a), so an IPv4 packet never matches an IPv6 object and
  families stay disjoint, as TCP and UDP already are. Effect: IPv6 and mixed groups become resolvable; IPv6-only
  blockers stop blocking IPv4 rules. Tests: oracle universe gets a small IPv6 block (documentation prefix
  2001:db8::/32). Risk: LOW semantics, MEDIUM engineering (bounds array and `bounded()` index assume three
  families).
- **A2 — IP Wildcard Mask as an interval hull (bound only).** Collect `ip-wildcard` (mapper) and bound it by
  [address AND NOT mask, address OR mask]. The real set is not contiguous, so this is an upper bound only. Small.
- **Stays UNKNOWN.** FQDN: resolved at runtime and refreshed by TTL; the collected configuration holds no address.
- Open question (existing semantics, not this class): `PolicyRuleQuery.interval` masks host bits of an `ip-netmask`;
  one old community report says such an object did not match as the network. Not established either way; flag for A6.

### 2.5 CP — non-terminal or unknown action (1,335)

Semantics. `normalize` accepts Accept, Drop, Reject, and Apply Layer with exactly one inline-layer reference. Other
values: the UserCheck actions **Ask** and **Inform**, legacy authentication or encryption actions, an Apply Layer
rule without exactly one inline layer, and `UNKNOWN` (the mapper could not resolve the action reference). These rules
are already partial priors with, typically, every dimension resolved.

Safe extensions.
- **X1 applies directly:** a later rule not contained in such a blocker is released whatever its action does.
- **N1 — Ask and Inform as first-match rules (cover only as "never reached").** R82: the gateway "enforces the first
  rule that matches a connection" (CP-MATCH); Ask "asks the user a question and adds a confirmatory check box, or a
  reason box" and Inform "sends a message to the user attempting to access the application or the content", both
  through a UserCheck object (CP-USERCHECK). If matching ends at an Ask/Inform rule, an Ask/Inform rule that fully
  covers a later rule makes it unreachable: REDUNDANT with an evidence string like `INLINE_LAYER_COVER`, never
  CONFLICT (the user's answer decides the outcome). Not proven yet: the pages were read only through search
  snippets (`sc1.checkpoint.com` was not reachable from this session); whether a UserCheck rule ends matching in its
  layer for every traffic type (non-HTTP traffic, unidentified users) needs the page read and A6 evidence (SmartConsole
  Packet Search on PO-chosen samples). Risk: MEDIUM; requires an A0 amendment.
- **Stays UNKNOWN.** Legacy authentication/encryption actions, `UNKNOWN` actions, malformed Apply Layer rules. X0
  should count the `UNKNOWN` share: it is a collection gap (action reference not in the dictionary), not a semantics gap.

### 2.6 CP — empty or dynamic group (272)

Semantics. Check Point groups carry no values, so this is a group stored with no members. The repository already
records that the rulebase objects-dictionary can carry a group without members whose members the domain inventory has
(`PolicyRuleMetrics.VALUES_INVENTORY`), and shadowing deliberately does not read the inventory join. The mapper also
cannot tell an absent `members` field from an empty one (`texts()` returns an empty list for both).

Safe extensions.
- **G1 — fill an empty dictionary group from the inventory at mapping time.** Use the mapper's existing
  `InventoryLookup` and `INVENTORY_MAX_AGE` (48 h) rule, which already resolves references the dictionary lacks
  (`POLICY_COLLECTION_COMPLETENESS_PLAN.md`, amendment 2026-11-04), and record the provenance. Same staleness risk as
  that accepted join, applied to members. Risk: MEDIUM; a PO decision, because it extends inventory evidence into
  shadowing-visible objects.
- **Stays UNKNOWN.** Whether a group that is empty in both sources matches nothing: no vendor statement found, and
  "empty" may still be a collection artifact. Never treated as an empty set. Record "members field absent" vs
  "present and empty" in the mapper first (X0).

### 2.7 CP — unsupported port expression (168)

Semantics. `Resolver.service` accepts `N` and `N-M` lists. SmartConsole is commonly used with `>N` / `<N` port
expressions, but this session found no official page stating that syntax or its bounds: the API and CLI references
found give single ports and hyphen ranges only (CP-SVC).

Safe extensions.
- **P1 — protocol-family bound.** A `service-tcp` object matches TCP only (its native type), whatever its port
  expression; bound it as TCP 0-65535 (and `service-udp` as UDP 0-65535). Proves disjointness from UDP/ICMP rules and,
  with X1, nothing more (it contains every TCP port). Risk: LOW. Small effect.
- **P2 — `>N` / `<N` as exact ranges.** Only after an official citation of the syntax and of the strictness of the
  bound. Until then UNKNOWN.

### 2.8 CP — missing service values (160)

Semantics. A service with no TCP/UDP port values: `service-other` (any IP protocol, optionally an INSPECT match
expression; `ip-protocol` is not collected), `service-rpc`, `service-dce-rpc` (dynamic ports), and services stored
without a port.

Safe extensions.
- **S1 — protocol-family bounds where the native type fixes the protocol** (as P1): `service-icmp` as an ICMP family
  (today it fails as "unsupported service constraint"), disjoint from TCP/UDP. RPC services: their ports are dynamic,
  so at most "TCP or UDP, any port"; the transport is not established from documentation here — keep UNKNOWN until
  cited.
- **Stays UNKNOWN.** `service-other` (protocol not collected, match expressions not modelled), services without
  values.

## 3. Ranked implementation plan

Ranked by expected effect over risk and cost; each step is its own build with differential and oracle tests.

| # | Item | Effect | Risk | Gate before code |
|---|---|---|---|---|
| 1 | E1 PAN targets as narrowing partial priors | High for PAN (2,484 blockers stop blocking every later rule) | LOW | **IMPLEMENTED** (A0 §4d, FROZEN) |
| 2 | X0 counter split per class | Enables sizing; no verdict change | none | none |
| 3 | X1 partial-prior pass-over by non-containment | Highest, all classes | MEDIUM (governance) | **IMPLEMENTED** 2026-11-05 (PO ratified; live measurement pending) |
| 4 | E1b/E1c targeted rules' own verdict | High for PAN (2,484 own UNKNOWNs) | LOW-MEDIUM | A0 note on descendant groups; oracle firewall dimension |
| 5 | U1 PAN application groups as identities | Medium-high (share of 4,475, size via X0) | LOW | review |
| 6 | N1 CP Ask/Inform as first-match | Medium (share of 1,335) | MEDIUM | page-level citation + A6 + A0 amendment |
| 7 | A1 IPv6 family | Medium (share of 1,578) | LOW/MEDIUM eng. | review |
| 8 | G1 CP empty groups from inventory | Low-medium (272) | MEDIUM | PO decision |
| 9 | U2 precedence hull bounds | Unknown (X0) | LOW | mapper/stored-shape review |
| 10 | P1/S1/A2 protocol-family and wildcard bounds | Low | LOW | review |
| — | D1 destination access roles | Low-medium | MEDIUM | A0 amendment |

Stays UNKNOWN (no safe extension from collected data): FQDN addresses, dynamic address groups, external dynamic
lists, predefined regions, application filters, CP dynamic objects / DNS domains / updatable objects / security
zones, CP gateway objects as addresses, CP applications in the service column, `service-other`, unknown and legacy
actions, unparsed Panorama target shapes, truly empty groups.
CP gateway objects: reviewed 2026-11-05 in `CP_GATEWAY_OBJECT_BOUND_REVIEW_2026_11_05.md` (DRAFT) — no official
statement of the address set a gateway/cluster object matches, and the object's interface list is not collected, so no
sound upper bound exists; stays UNKNOWN pending an A0 amendment and a PO decision on collecting gateway interfaces.

## 4. Open issues

- **Reason attribution is order-dependent.** `PolicySnapshot.Rule` stores extras through `Map.copyOf`, whose
  iteration order is unspecified (it can differ between JVM runs), and `normalize` reports the first failing field.
  A rule failing on two fields can therefore report either reason; verdicts are unaffected, the per-class counts are
  slightly noisy. A deterministic order (e.g. sorted keys in `normalize` and `LegacyPolicyHygiene`) is a separate,
  reason-only change.
- **Citations.** `sc1.checkpoint.com` was not reachable from the cloud session; CP statements marked CP-MATCH,
  CP-USERCHECK, CP-SVC come from search-result extracts of the official pages and must be read in full before any
  of N1, P2, S1 is frozen. PAN statements come from official pages via search extracts as well.
- Live effect of E1 is not measured here (no live access); expected only.

## Sources

Repository: A0 = `docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS.md` (FROZEN; §4a, §4d, §6, 2026-11-04 amendment);
`docs/design/PO_DECISION_RECORD_2026_11_03_CP_LAYER_SEMANTICS.md`; `docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md`.

Palo Alto (official):
- PA-TGT, PA-SUB: as listed in A0 §Sources.
- PA-DGH https://docs.paloaltonetworks.com/panorama/11-1/panorama-admin/panorama-overview/centralized-firewall-configuration-and-update-management/device-groups.html
  and https://docs.paloaltonetworks.com/panorama/9-1/panorama-admin/panorama-overview/centralized-firewall-configuration-and-update-management/device-groups/device-group-policies
- PA-ADDR https://docs.paloaltonetworks.com/content/techdocs/en_US/pan-os/11-1/pan-os-admin/policy/use-address-object-to-represent-ip-addresses/address-objects.html
  and https://docs.paloaltonetworks.com/pan-os/10-1/pan-os-web-interface-help/objects/objects-addresses
- PA-APPGRP https://docs.paloaltonetworks.com/network-security/security-policy/administration/objects/application-groups
- PA-DAG https://docs.paloaltonetworks.com/network-security/security-policy/administration/objects/address-groups/use-dynamic-address-groups-in-policy-panorama

Check Point (official; read through search extracts only, see §4):
- CP-MATCH https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_SecurityManagement_AdminGuide/Content/Topics-SECMG/Rule-Matching-in-the-Access-Control-Policy.htm
- CP-USERCHECK https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/CP_R82_SecurityManagement_AdminGuide/Content/Topics-SECMG/UserCheck/UserCheck-Interaction-Objects.htm
- CP-SVC https://sc1.checkpoint.com/documents/SMB_R80.20.15/CLI/Content/Topics/165275.htm (add service-tcp; hyphen ranges)
- CP-API show-access-role, add-group-with-exclusion: https://sc1.checkpoint.com/documents/latest/APIs/index.html
