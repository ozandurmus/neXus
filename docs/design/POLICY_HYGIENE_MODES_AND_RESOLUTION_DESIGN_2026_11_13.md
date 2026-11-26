# Policy hygiene: legacy and identity-aware modes, exact resolution, collection fixes (2026-11-13)

Status note 2026-11-26: written 2026-11-13 and first committed on a side branch (`measure/hygiene-unknown-1112`, commit `12b7bc34`) that was never merged; restored here unchanged below this note so that the source citations of its section 3.2 resolve. It stays DRAFT and is not implementation authority. The shipped source implements only the legacy (IP-level) mode of section 3.2, as three pieces: `PolicyHygiene.analyzeLegacy`, `PolicyLegacyView` (`of` builds the view, `flagged` names the rules that resolve only in it) and `PolicyNormalization.upperBound` (all under `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy/`), pinned by `PolicyHygieneLegacyModeTest`, `PolicyHygieneDefaultPortsTest` and `PolicyHygieneGatewayBoundTest`. That mode is not wired to the policy screen: a grep of `ui2/` on 2026-11-26 finds no caller of `analyzeLegacy` or `PolicyLegacyView` outside tests, and no mode switch, `IDENTITY_IGNORED`/`DYNAMIC_IGNORED` flag or "covered at IP level" reason in `PolicyHygiene.java`, `PolicyQueryService.java`, `PolicyHygieneTab.tsx` or `PolicyRuleViewer.tsx` (the zone matrix has its own `IDENTITY_IGNORED` flag in `ZoneMatrixEvaluator.java` and `ZoneMatrixPolicyBridge.java`, which this draft does not govern). Every other part of this document (section 3.1 identity-aware mode, the resolution models R1-R9, the collection fixes C1-C6, the presentation in section 5, the effect figures in section 7 and the proposed amendment in section 9) is not authority: where the source happens to contain a similar construct, the source and its own contract govern, never this file, and the measurement figures describe a 2026-11-12 run, not today. No decision in section 9 was taken by this document.

Status: DRAFT — 2026-11-13. Design and proposed contract amendment for council review and PO approval. It
authorizes nothing until the PO approves the amendment in §9; every item marked "citation required" or "PO decision"
stays UNKNOWN or unimplemented until that gate is met.

Scope: the stored-snapshot shadow analysis (`ui2/service/.../policy/PolicyHygiene.java`, `PolicyNormalization.java`,
`PolicyQueryService.java`), the policy screen (`ui2/frontend/src/screens/PolicyRuleViewer.tsx`), and the collection
fixes the analysis needs. It amends:
- `PATH_ANALYSIS_VENDOR_SEMANTICS.md` (A0, FROZEN 2026-11-04): new vendor-semantics entries (§9 A0-1..A0-6);
- the shadow verdict rules ratified so far (`PO_DECISION_RECORD_2026_11_05_EVENING_BATCH.md`, X1; the engine's own
  documented contract in `PolicyHygiene`): two named modes and the new reasons (§9 V-1..V-7).

Evidence: Phase 1 measurement and its addendum (`side-session-notes/.../hygiene-unknown-phase1-1112.md`; harness
`scripts/measurement/policy_hygiene_unknown/`, branch `measure/hygiene-unknown-1112`) and the independent reference
analyser (Argon, branch `argon/policy-reference-1112`). Counts only; no policy content was read outside the pod.

## 1. What the measurement says (10 largest policies, 57,391 rules)

- Today 7,271 rules (12.7 %) are decided. 32,549 are UNKNOWN "earlier rule has unsupported constraints", behind 475
  blockers; 5 CP gateway-object rules and 399 identity rules carry 90 % of them.
- The "sound over-approximation" of unknown earlier rules is already in the engine (partial priors, X1): +0.
- The independent reference analyser, written from a spec-only brief without our code, agrees with production on
  every rule both decide: **0 contradictions** in both modes. Where they differ, one side is UNKNOWN; each difference
  class is attributed (§1 of the addendum, section D): our gaps are ICMP, CP port expressions, PAN source port,
  negation, time windows, the upper-bound cover, and the earliest covering rule; the reference's gaps are snapshot
  collection failures and uncollected PAN zone lists.
- Legacy view (identity and dynamic cells ignored, flagged), measured with the unchanged engine: 51 % decided; with
  the stored-data models below and gateway objects bounded: 82.8 %. What remains is mostly a collection gap: PAN
  application default ports (4,854 rules), PAN zone lists (1,293), NAT rules shown as UNKNOWN (2,322).
- Service resolution: 98.6 % of service references already resolve to exact TCP/UDP sets. Unresolved: 88 PAN services
  with a source port, 66 CP ICMP/ICMPv6 services, 19 CP `service-other` (protocol number not stored), 12 CP `>N` port
  expressions, 3 RPC services, 7 PAN precedence-UNKNOWN services. Applications: 31 PAN App-ID applications without
  port definitions (not collected); no CP application/site object in these packages.
- Time: 2,788 rules carry a CP time object stored without a parsed schedule (kind unknown); 326 carry a one-time
  window (103 expired).
- Data integrity found on the way: a running CP collection overwrites the live snapshot row with partial checkpoints
  every 60 s (`cp-policy-rule-drop-1113.md`). Separate fix (§6 C6).

## 2. The verdict contract that does not change

- Single-rule cover. REDUNDANT / CONFLICT: one earlier enabled rule of the same evaluation scope provably matches every
  packet of the rule (same / different action; an Apply Layer cover is REDUNDANT). NOT_SHADOWED: proven that no single
  earlier rule matches every packet of the rule. UNKNOWN otherwise, always with a reason. Never a guess.
- A snapshot with collection failures never yields NOT_SHADOWED ("snapshot collection incomplete"). Kept, and the
  reference analyser gets the same rule.
- An earlier rule whose meaning is not known (an unparsed field) has no bound and blocks every later rule of its
  scope. A partial prior is used only through its sound upper bound (X1). Opaque application identities are never
  disjoint from anything.
- Evaluation scopes, CP layers and inline layers, PAN pre/local/post order: unchanged (A0 §2, §4c).

## 3. Two modes

### 3.1 Identity-aware (the sound mode; today's contract, made explicit)

Identity (CP access role; PAN source-user, dynamic user group) and dynamic objects (CP dynamic object, updatable
object, DNS domain, security zone; PAN dynamic address group, external dynamic list, application filter, FQDN
address) are runtime state.
- An identity- or dynamic-limited earlier rule never makes a later rule REDUNDANT/CONFLICT. It is used only through
  its sound IP/service/application bound (CP: the access role's networks, A0 2026-11-04 amendment; PAN: the rule's own
  addresses). When the later rule is outside that bound on one dimension, the earlier rule is passed over (this is
  X1, already implemented). When the later rule is inside the bound, the later rule is UNKNOWN with the new reason
  **"covered at IP level by an identity-limited rule"** (or "... by a dynamic-object rule") and the earlier rule's id
  (new: today the reason is the generic "earlier rule has unsupported constraints").
- A rule that itself carries identity or a dynamic object: **REDUNDANT/CONFLICT when one earlier fully known rule
  contains its upper bound** (new, sound: everything it can match lies in that bound). Otherwise UNKNOWN "depends on
  user identity" / "depends on dynamic object membership", because whether those users or addresses ever appear is
  runtime state.

### 3.2 Legacy (the PO's priority: classic first match on source, destination, service, application)

A view of the snapshot, then the same engine (exactly what was measured):
- a CP access role counts as the networks it names (any when it names none or any);
- every other dynamic object (list in 3.1) counts as any; PAN source-user and dynamic user groups are ignored;
- every rule that changed in this view carries the flag **IDENTITY_IGNORED** or **DYNAMIC_IGNORED**;
- a verdict that depends on a flagged rule (the rule itself, or its covering rule) carries the flag too, and is shown
  in a separately labelled **IP-level view**: "covered at IP level by rule N (limited to users/roles)", never as proven
  shadowing. In the measurement, 21,770 of the 29,345 legacy decisions depend on a flagged covering rule.
- Everything else (collection failures, unknown fields, application identities, zones) follows §2.

Seam: one engine, one `SnapshotView` function (`stored` | `legacy`) applied before analysis, and one flag set carried
into the result. The identity-aware mode needs no further engine change beyond §3.1; later identity work (AD group
membership, dynamic object contents) plugs in as bounds on the same seam.

## 4. Exact resolution models (engine; stored data)

Each model applies wherever a selector is resolved: the rule's own match, full earlier rules, and partial bounds.

| Id | Construct | Model | Data | Measured scope | Gate |
|---|---|---|---|---|---|
| R1 | CP `service-icmp` / `service-icmp6` | protocol families `icmp`, `icmp6`; value = type x code (code absent = every code); disjoint from TCP/UDP | `icmp-type`, `icmp-code` stored | 66 objects, 887 references | A0-1 citation required (CP API `add-service-icmp`) |
| R2 | CP port expressions | `N`, `N-M`, `>N` = N+1..65535, `<N` = 0..N-1; any other shape stays UNKNOWN | stored | 12 objects (all `>N`) | A0-2 citation required (CP service `port` grammar) |
| R3 | PAN source port | a fifth dimension per protocol (`protocol/tcp/source-port`, `.../udp/source-port`); absent = every source port; ranges and lists as for destination ports | stored | 88 objects | A0-3 citation required (PAN service object) |
| R4 | CP `service-other` | IP protocol number family (`ipproto`) | **`ip-protocol` not stored**: mapper parse-scope extension of the same dictionary read (C4) | 19 objects | C4 |
| R4b | CP `service-rpc`, `service-dce-rpc` | stays UNKNOWN (dynamic ports) | - | 3 objects | none |
| R5 | Negated cell (CP negate cell, PAN negate source/destination) | complement within the address universe: IPv4 complement plus every IPv6 address (the engine resolves IPv4 only, so a negated cell also matches IPv6 traffic) | stored | 7 own, 4 blocker cases | A0-4 citation required |
| R6 | Time (CP time object, PAN schedule) | see 4.1 | parsed `PolicySchedule` | 326 one-time; 2,788 unparsed CP | A0-5 citation required; C5 for the unparsed ones |
| R7 | Earliest covering rule | once R1-R5 make those rules full matches, the cover search returns the earliest full rule containing the rule; a test pins "earliest across every candidate bucket" | - | 8 rules named a later covering rule | none |
| R8 | Upper-bound cover (rule without a full match) | REDUNDANT/CONFLICT when one earlier full rule contains the rule's strict partial bound (unknown dimensions any, only fields of known narrowing meaning) | stored | +320 alone; 36 identity rules in the agreement run | none (follows from §2) |
| R9 | PAN custom application with `default/port` | its ports as the application's service set under `application-default` | stored | 2 objects | A0-6 (PAN application-default, A0 §6) |

### 4.1 Time (R6)

- An earlier rule whose schedule has ended at analysis time (one-time window, end before now minus 14 h timezone
  margin, since the gateway timezone is not collected) matches nothing any more: it is not an earlier rule. Its own
  verdict is computed on its selectors and it keeps the existing "expired" finding.
- An earlier rule whose schedule is provably not all of time (a one-time window with an end; a recurring schedule
  with a gap) cannot cover a later rule that has no schedule: passed over.
- A later rule with a schedule that is not provably empty is evaluated on its selectors: covered when an earlier rule
  without a schedule (or with the same schedule object) contains it; NOT_SHADOWED when no earlier rule can contain it.
- Two different schedules are never compared beyond identity (same object) or "one side has none".
- Unparsed schedules (kind unknown, 2,788 rules) stay as today (partial, UNKNOWN own verdict) until C5.

## 5. Presentation (policy screen)

- Mode switch: Legacy (default for the hygiene view, per PO) / Identity-aware. The legacy view shows the flags and a
  separate "IP-level" column: "covered at IP level by rule N (users/roles ignored)".
- Rules outside the security rulebase (CP NAT, PAN NAT/default): "Not analysed (NAT rule)", not UNKNOWN; excluded from
  the "not provable" count (2,322 rules in the 10 packages).
- While a collection is running and the stored row is a checkpoint (failures present): a banner "collection in
  progress; showing partial policy" until C6 lands.
- New reasons (fixed vocabulary, `PolicyHygiene.REASONS` and `PolicyPrivacy`): "covered at IP level by an
  identity-limited rule", "covered at IP level by a dynamic-object rule", "depends on user identity", "depends on
  dynamic object membership", "application ports not collected" (replacing "application default ports are unknown"
  in the UI wording only), "zone list not collected".

## 6. Collection fixes (each its own change; none is a new device command unless marked)

| Id | Gap | Effect (legacy, measured upper bound) | Source | Gate |
|---|---|---|---|---|
| C1 | PAN App-ID default ports (per application: protocol/port list, ident-by-ip-protocol/icmp) | 4,854 rules | content catalogue on the firewall or Panorama; **not read today** | **new read: command gate + PO approval** |
| C2 | PAN vsys zone lists, so that a zone-name list can be proven to be "every zone" | 1,293 rules | already read by inventory (`PaloAltoConfiguredInterfaceParser`, `vsys/entry/zone`); needs an identity-safe join from the device group's target firewalls (enrolled device id + vsys, never hostnames) | review of the join (identity law); rule: a name list equals any only when it holds every zone of every target vsys |
| C3 | CP gateway / cluster object addresses (main, all interfaces, cluster virtual and member addresses) | 14,615 blocked rules (sound); +12,336 decided in legacy | the approved `show-gateways-and-servers details-level full` read (PO 2026-11-06, VSX VIP stage 1 measured field presence); reuse for a new purpose = gate review, no new command | **PO decision** + A0 amendment: an upper bound only (the gateway object matches at most its own addresses); the review `CP_GATEWAY_OBJECT_BOUND_REVIEW_2026_11_05.md` found no official statement, so this is a PO semantics decision, stated as such |
| C4 | CP `service-other` IP protocol number | 19 objects | same rulebase dictionary read; mapper stores `ip-protocol` | parse-scope extension (AGENTS.md: not a command addition) |
| C5 | CP time objects stored without a parsed schedule | 2,788 rules | same dictionary read; first measure which time-object fields are present (names only), then fix `CheckPointSchedule` | measurement first |
| C6 | Running CP collection overwrites the live snapshot with partial checkpoints | readers see fewer rules for the length of every collection | `CpPolicyParallelCollection.checkpoint()` -> `PolicyCollectionRepository.checkpoint()` | keep checkpoints in a staging row, swap at final publish; separate change |

## 7. Expected effect (upper bounds, measured on the same 10 snapshots)

| Step | Decided | % |
|---|---:|---:|
| today | 7,271 | 12.7 |
| legacy view | 29,345 | 51.1 |
| + R1-R9 (stored data) | 32,202 | 56.1 |
| + C3 gateway bound | 47,498 | 82.8 |
| + C1 App-ID ports, C2 zone lists, NAT shown as not analysed | about 53,600 of 55,069 analysable* | ~97 |

\* 57,391 rules minus 2,322 NAT rules. The last row is an estimate from the reason counts of the previous row
(4,854 + 1,293 rules move to decided, an upper bound); about 1,400 stay UNKNOWN (service representations not covered
by R1-R4, PAN precedence UNKNOWN objects, empty groups, IPv6). Every other row is a measured counterfactual run.

Identity-aware mode, same order: 7,271 -> 7,731 (R6 own time) / 7,591 (R8) -> 21,746 with C3 -> it stays below legacy by
design: about 10,600 rules carry identity themselves and 13,500 are covered at IP level by an identity rule.

## 8. Tests and parity gates (Phase 3)

- Per model: synthetic fixtures (documentation addresses only), positive and negative cases, both modes.
- Differential: with every new model switched off, verdicts equal today's on the existing differential corpora
  (`PolicyHygieneDifferentialTest`, `PartialPriorOracle`); with a model on, only rules touching that construct change.
- Oracle: `PartialPriorOracle.assertNoCover` extended with ICMP, source port, negation and schedules.
- Reference parity on the 10 live snapshots (in the pod, counts only): **contradictions must stay 0**; "ours UNKNOWN,
  reference decided" must shrink to the reference's own gaps; any new contradiction blocks the release.
- Before/after measurement on the same 10 snapshots (harness `variants`/`legacy`/`agree` modes) attached to the PR.

## 9. Proposed contract amendment (for PO approval)

Vendor semantics (A0), each frozen only with an official citation read in full; without it the item stays UNKNOWN:
- **A0-1** CP ICMP services: a `service-icmp`/`service-icmp6` object matches ICMP/ICMPv6 packets of its type and, when
  given, code; no code means every code.
- **A0-2** CP service port grammar: `N`, `N-M`, `>N`, `<N` as stated in the official service object documentation.
- **A0-3** PAN service source port: a service with `source-port` matches only packets whose source port is in it.
- **A0-4** Negated cells (CP "Negate Cell", PAN "Negate" on source/destination): the rule matches every address not
  in the cell, of every address family.
- **A0-5** Schedules: a time-limited rule matches only inside its windows; an ended one-time window matches nothing.
- **A0-6** PAN application-default with a custom application: its `default/port` list is its standard ports (A0 §6).

Verdict rules:
- **V-1** Two named modes, legacy and identity-aware, as defined in §3. Legacy is the default hygiene view; every
  verdict that depends on an ignored identity/dynamic condition carries its flag and is shown as IP-level, never as
  proven shadowing.
- **V-2** Identity-aware: an identity- or dynamic-limited earlier rule never covers; inside its IP bound the later rule
  is UNKNOWN "covered at IP level by ..." naming that rule; outside it, the rule is passed over (X1, unchanged).
- **V-3** Upper-bound cover: a rule without a full match is REDUNDANT/CONFLICT when one earlier fully known rule
  contains its strict partial bound.
- **V-4** Time rules of §4.1.
- **V-5** Rules outside the security rulebase are "not analysed", not UNKNOWN.
- **V-6** Unchanged and restated: a snapshot with collection failures never yields NOT_SHADOWED.
- **V-7** The reported covering rule is the earliest earlier rule that provably contains the rule.

PO decisions requested:
- **D-1** C3 gateway objects: accept "a gateway object matches at most its own addresses" as an upper bound (used only to
  pass over, never to cover) without an official statement, and approve reusing the `show-gateways-and-servers` read
  for it.
- **D-2** C1: approve designing a new read of the PAN App-ID default ports (command gate entry to follow).
- **D-3** Legacy as the default view of the hygiene screen.

## 10. Implementation order (Phase 3, after approval)

1. Seam and legacy view (§3), flags and IP-level labels, NAT "not analysed", new reasons. No semantics change in the
   identity-aware mode beyond V-2 naming.
2. R8 upper-bound cover, R7 earliest cover test.
3. R1 ICMP, R2 port grammar, R3 source port, R9 custom application ports (each after its citation).
4. R5 negation, R6 time (after citations); C5 measurement and `CheckPointSchedule` fix.
5. C4 mapper field; C6 checkpoint staging (separate change, worker/persistence).
6. C2 zone-list join; C3 gateway bound; C1 App-ID read (each behind its gate/decision).
Each step: targeted tests, differential, reference parity on the 10 snapshots, before/after counts.

## Open UNKNOWNs

- Gateway object matching (C3) has no official statement (see the 2026-11-05 review); D-1 is a PO risk decision.
- Gateway timezone is not collected: time windows use a 14 h margin and never cover across different schedules.
- App-ID applications stay opaque identities until C1; two application names are never disjoint.
- Multiple CP ordered layers stay "not evaluated" in the current engine; the reference treats each ordered layer as its
  own scope (A0 §2a supports it); no disagreement was observed on the 10 snapshots; out of scope here.
