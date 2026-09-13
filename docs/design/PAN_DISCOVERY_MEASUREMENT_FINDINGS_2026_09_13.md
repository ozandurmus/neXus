# Palo Alto discovery — first measurement findings

## Status

**DRAFT — NOT implementation authority.** Answers a subset of
`PAN_DISCOVERY_MEASUREMENT_BRIEF_2026_09_13.md`'s questions from one
Product-Owner-executed read against a live Palo Alto management server on
2026-09-13. It decides nothing and authorizes nothing; it records what was
observed so the observation survives the session that produced it
(`AGENTS.md` item 7 — chat is never authoritative).

**What was run.** The managed-device enumeration, through the management
server's own CLI with the API-syntax debug aid enabled. Within the two
methods `PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md` §2
authorizes: no firewall was contacted, nothing was written.

**What is recorded here.** Field names, counts, shapes and enumerated state
values only. `AGENTS.md` "Sensitive identity reporting law": no serial,
hostname, address, certificate subject or virtual-system display name from
the measured estate appears in this document, and none may be added to it.

**Evidence grade.** One management server, one software generation, one
point in time, and — importantly — the **CLI's own rendering**, not the XML.
The labels below are what the CLI prints. The concrete XML element names are
**not settled by this measurement** (§4 U-1).

## 1. The enumeration's summary columns

Five columns, in this order: **Serial · Hostname · IPv4 · IPv6 · Connected**.

- **F-1. `IPv6` is a distinct column from `IPv4`.** The existing Python's
  shared parser reads one address field only
  (`PAN_DISCOVERY_PYTHON_STEP_MAP_2026_09_13.md` §4). A candidate row that
  carries one address where the vendor supplies two is a narrowing the
  implementing movement must decide deliberately, not inherit.

## 2. The per-device detail block

The enumeration also renders a per-device block. Labels observed, grouped by
whether discovery has any use for them:

**Load-bearing for discovery**

| Label | Shape observed |
| --- | --- |
| `Virtual Systems:` | a list, each entry rendered as an identifier with a display name in parentheses, followed by a shared-policy checksum and a shared-policy version. Four entries on the one device inspected. |
| `HA State` | a single state word |
| `HA Cluster State` | a single state word — **a different field from `HA State`** |
| `Cluster node-id` | **present as a label, empty on the device inspected** |
| `Connected at:` | a timestamp |
| `Device cert present`, `Device cert expiry date`, `Certificate Status`, `Certificate subject Name`, `Certificate expiry at` | certificate condition, carried under its own names |

**Present, and not discovery's business**

`Wildfire Real-time Stream`, local/merged/predefined configuration sizes and
the recommended maximum, `VPN Disable Mode`, `Operational Mode`,
`Last masterkey push status`/`timestamp`, `Express mode`,
`Device cellular ports`, `Autocommit done`.

## 3. What this settles

- **F-2. Virtual systems are carried by the enumeration itself.** Each entry
  carries an identifier and a display name. This answers brief Q-11
  affirmatively and it is the single most consequential finding here:
  virtual-system enumeration needs **no second call and no device contact**.
  Check Point required a two-address invariant (`CP_AND_VSX_DISCOVERY_CONTRACT.md`
  §5.1) to reconstruct which physical device serves a virtual system; this
  vendor nests the relationship structurally, so that invariant has no
  analogue and needs none.
- **F-3. The existing Python reads none of it.** `PAN_DISCOVERY_PYTHON_STEP_MAP_2026_09_13.md`
  §6 recorded the absence and correctly refused to read it as the vendor
  withholding the data. It does not. The collector is blind to a field the
  response carries — a concrete case where writing from scratch beats
  porting.
- **F-4. `HA State` and `HA Cluster State` are two different fields.**
  Vendor documentation states that the cluster-state value observed here
  means a firewall configured as an **HA pair but not as an HA cluster** —
  the vendor's HA pair and its multi-peer HA clustering are separate
  features. `Cluster node-id` was empty, consistent with that reading.
- **F-5. Membership and the peer are BOTH visible.** `HA State` establishes
  that a device participates in an HA pair, and the enumeration also carries
  the peer's **serial** — a stable identifier, not an address. An earlier
  reading of this measurement, taken from the CLI's formatted rendering,
  recorded that no field named the peer. **That was wrong**: the rendering
  omits it, the XML carries it. §3b records the corrected, structured
  measurement and supersedes the earlier reading entirely.
- **F-6. `Connected at:` exists.** This answers brief Q-16 affirmatively: a
  timestamp accompanies the connection state. It is a candidate corroborating
  signal, and Check Point had no analogue for it.
- **F-7. Certificate condition is carried under its own names**, separately
  from the connection state. Check Point measured a console indicator that
  reflected a certificate condition and was read by operators as
  reachability (`CP_AND_VSX_DISCOVERY_CONTRACT.md` §7.1 L-S3). This vendor
  keeps the two apart in the data; a contract must keep them apart in the
  presentation too.

## 3b. The structured measurement — 39 entries, read as XML

A third Product-Owner-executed read on 2026-09-13 took the same enumeration
through the API rather than the CLI, and reported element paths, occurrence
counts and value distributions. Counts and shapes only; no identity was
reported and none is recorded here.

**F-8. The enumeration returns devices that are not connected.** 39 entries;
the connection state was the affirmative value on 38 and a different value on
one. The Product Owner independently confirmed the estate holds 38 working
devices and that the remaining one is the known-problem device. **This settles
brief Q-9 and the `DI-3` question together**: the vendor's own command returns
everything, so discovery can show everything and leave exclusion to the
operator, exactly as `CP_AND_VSX_DISCOVERY_CONTRACT.md` DI-3 requires. No
second command and no wider gate is needed for it.

**F-9. The peer is carried, as a serial, inside the enumeration.** 35 of 39
entries carry an HA block; each of those 35 carries a peer serial. Four
entries carry no HA block at all — standalone devices. HA state values
distributed as 17 / 17 / 1 across two opposing roles and one suspended role.

**F-10. Reciprocity holds for all but one claim, and it is checkable inside a
single response.** Of the 35 peer claims, **35 resolved to another entry in
the same response** — none named a device absent from it, and none named
itself. **34 were reciprocal**, both sides naming each other, which is
exactly 17 complete pairs. **One was one-sided**: it named a peer that did not
name it back.

This is the finding that changes the design. `AGENTS.md` "Evidence laws"
requires a peer claim to be corroborated by the other side **in the same
evidence-collection pass**. Every member is in this one response, so that
corroboration is available at discovery, from a stable identifier, contacting
no device. The two-sided check the `OP.0a.P7` contract requires before forming
a pair is therefore satisfiable here — which an earlier reading of this
measurement wrongly concluded was impossible.

**F-11. The one-sided claim is a real finding, and the honest outcome for it is
`NOT_EVALUABLE`.** It is not noise to be smoothed over: forming a pair from it
would be forming an operational unit from an uncorroborated claim. The
arithmetic is consistent with the one-sided claimer being the suspended
member — 17 opposing-role pairs account for the 34 reciprocal entries — but
**identity was not verified and this is recorded as consistency, not as a
finding**.

**F-12. A field inventory, by presence.** Every one of the 39 entries carries:
serial, hostname, an IPv4 address element, a **separate IPv6 address
element**, a MAC address, model, family, type, software version, uptime,
domain, tag, operational mode, multi-virtual-system flag, slot count,
cellular-port count, connection state, **connection timestamp**, five
certificate elements (status, expiry, subject name, device-cert presence,
device-cert expiry), configuration-size elements, masterkey-push status and
timestamp, a plugin-version block, and a dozen content-version elements.
Partial presence: a high-speed-mode element on 11, a VPN-client package
version on 26.

**F-13. `type` is present on every entry and empty on every entry.** It is
therefore **not** a candidate-kind discriminator in this estate. Together
with the fact that dedicated log collectors and WildFire appliances are
addressed by their own configuration nodes, this settles brief Q-10: the
enumeration's candidate set is **homogeneous**, and Check Point's ten-kind
classification problem has no Palo Alto analogue. The cost is stated in
`PO_DECISION_RECORD_2026_09_13B` §3: non-firewall appliances are invisible to
the authorized method — absent by scope decision, not absent in fact.

**F-14. Virtual systems are enumerated in full.** 140 virtual-system entries
across the 39 devices, each carrying a display name and three shared-policy
elements (status, checksum, version). The multi-virtual-system flag was the
affirmative value on all 39.

**F-15. Platform and version spread.** Four hardware families and eight
models. Three software versions, of which one appears on a single device.

**F-16. A count coincidence, recorded as a hypothesis.** Exactly one device is
not connected, exactly one is suspended, exactly one peer claim is one-sided,
and exactly one device runs the odd software version. **Whether these are the
same device is not established** — identity was not verified and equal counts
are not a correlation. `CP_AND_VSX_DISCOVERY_CONTRACT.md` CS-5 refused the
same inference for Check Point and this document refuses it here. **What would
settle it**: one read that resolves all four conditions to the same entry key
and reports whether they coincide, as a relationship rather than as a device.

## 3c. A lead on an open P0, offered as a hypothesis

Backlog item `pan_serial_representation_identity_evidence_closure` (P0,
`in_progress`) has stood since 2026-09-04: a peer-serial mismatch on **one
member**, root cause `UNKNOWN`, whose own investigation note records that the
field-level evidence from the original run **was never persisted anywhere
reachable**, so it cannot be re-derived. Its proposed next step awaits Product
Owner authorization for a device-facing capture.

This measurement found **exactly one** non-reciprocal peer claim among 35,
from the management plane, contacting no device.

**Whether it is the same member is UNKNOWN and is not asserted.** The prior
finding came from a per-device runtime read (`show high-availability state`);
this one comes from the management-plane enumeration. They are different
planes and may disagree for reasons neither has established. What makes the
lead worth recording is that this observation is **reproducible, cheap, and
inside the authorized gate**, where the blocked step is none of those.

**What would settle it**: resolve the one-sided claimant here and the
mismatching member there to the same entry key, and report `MATCH` /
`MISMATCH` — never the identities.

## 3a. The management appliance's own HA block — a separate thing, kept for the record

*Superseded in its conclusion by §3b. The schema comparison below stands; the
inference drawn from it at the time did not.*

A second Product-Owner read on 2026-09-13 inspected the management server's
own configuration entry and found an HA peer block carrying an address **and
a serial**. That is the management appliance's *own* HA configuration, not a
managed firewall's, and the two schemas differ:

| Whose HA block | Sub-path | Children observed |
| --- | --- | --- |
| The management appliance itself | `deviceconfig/high-availability/`**`peer`** | address, **serial**, encryption, monitor-hold-time |
| A managed firewall | `deviceconfig/high-availability/`**`group`** | peer address (v4 and v6) — **no serial** |

The firewall path is not a new discovery: the existing collector already
parses exactly it (`configuration/panorama_config_collector.py`,
`parse_ha_peer_ip_from_config`), and its own docstring states the rule this
project settled under the `OP.0a.P7` contract:

> Configuration intent, not a runtime observation and not proof of a live
> peer relationship … it is one half of the **mutual configuration-agreement
> check** `utils/failover/assessment.py::_derive_pan_units` requires before
> forming a pair, **never used alone**.

- **F-5a. A managed firewall's peer is identified by address, not by
  identifier.** `AGENTS.md` identity law and
  `CP_AND_VSX_DISCOVERY_CONTRACT.md` HL-2 both hold that an address is a
  locator and never a join key. Check Point's member-to-cluster join had a
  stable identifier (`MC-1`); this vendor's firewall HA configuration does
  not offer one.
- **F-5b. Pair formation requires two-sided evidence, by a prior product
  decision.** Forming a pair from one member's configured peer address alone
  is explicitly refused. That rule stands.
- **F-5c. SUPERSEDED by F-10.** This section originally concluded that a
  discovery run could not satisfy the two-sided check and that the peer
  question was therefore outside discovery's reach by construction. The
  structured measurement disproves it: the peer serial is in the enumeration,
  every member is in the same response, and reciprocity is checkable there.
  The conclusion was drawn from the CLI's formatted rendering, which omits the
  peer element. Recorded as an error rather than deleted, because the
  reasoning was sound and only the evidence was incomplete.

## 4. `UNKNOWN` — what this measurement did not settle

| Id | `UNKNOWN` | What settles it |
| --- | --- | --- |
| U-1 | The concrete **XML element names**. This was the CLI's rendering; the labels above are not element names | the same enumeration read through the management server's own API browser, reporting element names only |
| U-2 | Whether a device that **is** in an HA cluster populates `Cluster node-id`, and with what shape | the same read against a device in an HA cluster. The measured estate uses HA pairs, so this may not be answerable on it at all |
| U-3 | What `Connected` reports for devices **independently known to be powered off**, and what `Connected at:` shows for them — brief Q-14, the vendor analogue of Check Point's disproven L-S1 | the same read, with the outcome reported as a relationship over counts |
| U-4 | Whether the `IPv6` column is ever populated in this estate, and what it denotes when it is | a count over one enumeration |
| U-5 | Whether the virtual-system list appears for **every** kind of managed device or only for multi-virtual-system ones, and what a single-virtual-system device renders | a count over one enumeration, grouped by whether the list is present |
| U-6 | Whether the enumeration returns anything that is **not** a firewall. Vendor documentation indicates dedicated log collectors are addressed by a different command | brief Q-9/Q-10, over one enumeration |

## 5. Consequence for the design

Recorded so the next movement does not re-derive it:

1. **Virtual-system mapping belongs in discovery.** It arrives with the
   candidate set, costs nothing extra, and contacts no device.
2. **HA pair formation does not.** The enumeration cannot name a peer, and
   the field that can (`peer-info/serial-num`, reached by a per-device call
   outside the gate) is, by the existing code's own account, a one-sided
   runtime claim. `AGENTS.md` "Evidence laws" holds a member's report about
   its peer to be uncorroborated until the other side reports in the same
   pass — which a discovery run, by construction, cannot arrange.
3. **The honest discovery output for HA is therefore**: this device
   participates in a pair (from `HA State`), its partner is
   `NOT_EVALUABLE`. That is the same shape `CP_AND_VSX_DISCOVERY_CONTRACT.md`
   MC-2 already fixes for a missing identifier, and it needs no new
   vocabulary.

## 6. Cross-references

- `PAN_DISCOVERY_MEASUREMENT_BRIEF_2026_09_13.md` — the questions; Q-11, Q-16
  answered here, Q-9/Q-10/Q-12/Q-14 still open (§4).
- `PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md` — the two
  methods this read stayed inside.
- `PAN_DISCOVERY_PYTHON_STEP_MAP_2026_09_13.md` — what the existing collector
  reads, and does not.
- `CP_AND_VSX_DISCOVERY_CONTRACT.md` §5.1, §5.3, §7 — the Check Point
  analogues each finding is compared against.
- `AGENTS.md` — evidence laws, identity law, sensitive identity reporting law.
