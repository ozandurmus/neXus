# Check Point discovery — what is taken, and how it was measured

## Status

**DRAFT — MEASUREMENT HANDOVER. NOT AUTHORITY, NOT COMMAND APPROVAL.**

This document states, item by item, **what Check Point discovery takes and by
what method**, and separates what was *measured against a live multi-domain
management server* from what is *assumed by the current implementation*. It
authorizes nothing. It exists so that the still-open command gate
(`CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md`, DRAFT) and the merged
domain core can be checked against evidence rather than against each other.

`CP_AND_VSX_DISCOVERY_CONTRACT.md` (FROZEN) is the authority for every rule
named here. That contract is deliberately **tool-agnostic** — §3 names a
management-plane session, a domain enumeration and an object query, and names
no command-line tool anywhere. Choosing the tool is an implementation decision,
and this document is where the evidence for that decision is recorded.

**Nothing in this document may be cited as command approval.** `AGENTS.md`:
a command's presence in source, or in a measurement, is not command approval.

## 0. Two commands that are NOT part of discovery

Stated first because both have been proposed in conversation and either would
break the contract if it reached an implementation.

- **`cprid_util` is not a discovery command.** It is how the *existing Python
  collector* reaches a gateway from the management server to read interfaces,
  routes and cluster state. It contacts the device. Contract **T-4** forbids
  any device contact during discovery, so `cprid_util` cannot appear in a
  discovery path at all. Where it belongs is the collection phase, after
  import — and there it is a transport choice that has not been made.
- **Uploading a script to the management server is not a discovery method.**
  The existing collector does this. The gate lift
  (`PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_GATE.md`) names four
  methods and file upload is not among them.

Everything discovery takes comes from the management database and the
management server's own kernel state. Nothing else.

## 1. The divergence this document exists to surface

| | Tool |
| --- | --- |
| What the measurements below were taken with | `cpmiquerybin` and the management server's shell |
| What the command-gate entries and the merged adapter assume | `mgmt_cli` with `-f json` |

The frozen contract permits either: it constrains *what is read and how it is
interpreted*, not which binary reads it. But every rule in §§4–6 of that
contract — the ten candidate kinds, the host-resolution invariant, the
member-to-cluster join — was derived from field names and response behaviour
observed through the first tool. **Whether the second tool exposes the same
fields, under the same names, with the same absence semantics, is
`UNVERIFIED`.** §5 below lists each binding that has to be confirmed.

This is not a defect in either choice. It is an open verification item that
must be closed before a run against a live server is trusted, and it is the
single most consequential thing in this handover.

## 2. What discovery takes — by purpose, not by field

Six things, and nothing else:

1. **Which management domains exist**, so every later query is scoped to one.
2. **Which objects exist in each domain**, restricted to the object types §4 of
   the contract classifies.
3. **What kind each object is** — the ten kinds, decided from typed flags and
   field presence, never from a name.
4. **Which physical device serves each object** — the host-resolution invariant.
5. **Which cluster each member belongs to** — by stable identifier.
6. **The state of the management server's own monitoring channel** per device
   address — carried under its own name, and explicitly not liveness.

Everything else a discovery run could ask for is out of scope by T-2: a run
performs no other query.

## 3. How each was measured

Method, not command syntax, is the durable part; the syntax is recorded in §4.

| # | What | Method measured |
| --- | --- | --- |
| 1 | Domain list | A management-server utility that lists the domain management servers, cross-checked against the server's own multi-domain status command. Both returned the same set and the same count. The status command additionally reports per-domain process state, which the utility does not |
| 2 | Domain context | A shell function that re-scopes the session to one domain. **It alters the calling shell** and cannot be run in a subprocess — a wrapper that spawns it discards the context silently and every later query then runs against the wrong scope. Measured the hard way: an early probe did exactly this and produced a full set of confidently wrong negatives |
| 3 | Object query | One query per domain per object type against the management object table, requesting a comma-separated attribute list |
| 4–5 | Kind, host and cluster | Derived entirely from attributes returned by the query in row 3. No additional call |
| 6 | Channel state | The management server's **operating-system connection table**, read on the management server, filtered to the port on which the management server's own established channels to devices were observed. Not an object-table query and not a management-tool command |

## 4. The command forms measured

These are the forms that were run and that returned what §5 records. Estate
values are shown as placeholders; nothing here is an approved command.

```bash
# 1 — Domain list. Both were run and returned the same set and count.
#     The status command additionally reports per-domain process state.
mdsstat
$MDSVERUTIL AllCMAs

# 2 — Domain context. MUST run in the session's own shell.
#     Wrapped in a subprocess (timeout, sh -c, a helper function) the
#     context is discarded and every later query silently runs against
#     the wrong scope. This failure produces confident wrong answers,
#     not errors — it was measured by making it.
mdsenv <DOMAIN>

# 3 — The object query. One per domain, per object type.
cpmiquerybin attr "" network_objects \
  "type='gateway'|type='cluster_member'|type='gateway_cluster'" \
  -a __name__,type,ipaddr,mgmt_ip,cluster_object,connection_state,\
appliance_type,svn_version_name,cpver,sic_name,cp_products_installed,\
vsx_netobj,vs_netobj,vsx_cluster_netobj,vs_cluster_netobj,\
vsx_cluster_member,vs_cluster_member

# 4 — Cluster membership.
cpmiquerybin attr "" network_objects "type='gateway_cluster'" \
  -a __name__,ipaddr,cluster_members

# 5 — Full object dump, for anything nested. The `attr` result type
#     returns FIRST-LEVEL FIELDS ONLY, which is why the object's stable
#     identifier is not reachable through it.
cpmiquerybin object "" network_objects "name='<OBJECT>'"

# 6 — Channel state: the management server's own connection table.
#     No packet is sent to any device; kernel state is read.
#     Taken TWICE, separated by an interval (contract CS-3).
netstat -an | awk '$5 ~ /:<CHANNEL_PORT>$/ {split($5,a,":"); print a[1], $6}'

# 6a — Derive the channel port instead of hard-coding it.
netstat -an | awk '$6=="ESTABLISHED"{print $5}' | awk -F: '{print $NF}' \
  | sort | uniq -c | sort -rn | head
```

Two syntax facts worth carrying, both measured by getting them wrong first:

- The query grammar accepts `name` as a filter field; it does **not** accept
  the display-name attribute that the attribute list uses. The two are
  different identifiers and substituting one for the other returns a syntax
  error, not an empty result.
- The tool's usage text is written to standard error, so a wrapper that
  discards standard error hides the very message that would have corrected the
  syntax.

## 5. Field bindings — what each gave, and what must be re-confirmed

Every row was observed through the first tool. Every row is `UNVERIFIED` under
the second until a live run confirms it.

| Binding | Observed behaviour | Why it matters |
| --- | --- | --- |
| Display name | Present on every object | Never a join key (contract §5.4) |
| Own address | Present on most kinds; **empty** on clustered virtual-system members | The empty case is not a missing value to repair — it is one arm of HR-2 |
| Management address | Present and equal to the own address on physical devices; present and **different** on virtual systems, where it is the address of the serving chassis; absent on cluster objects | This single field carries the whole host-resolution invariant |
| Cluster reference | Present on every member, carrying a **stable identifier alongside the display name** | The identifier is the join key for §5.3 |
| Object type | Three values across the estate measured | Feeds §4's classification with the flags |
| Virtualization flags, six of them | Boolean-valued, overlapping deliberately — a virtualization cluster also carries the virtual-system-cluster flag, and a virtualization host also carries the virtual-system flag | §4's kinds are a *lattice*, not a partition; an implementation that treats the flags as mutually exclusive will mis-sort the overlapping kinds |
| Product-installed flag | Distinguishes product gateways from interoperable devices sharing the same object type | Without it, a discovery run reports foreign devices as candidates. In the estate measured, the majority of one object type were not product devices |
| Model | Present on physical and cluster objects; a distinct software value on virtual systems | Available at discovery time — earlier sessions wrongly assumed it was not |
| Software version | Present on cluster objects; **absent on members** | The candidate row must tolerate a version that lives on the parent, not the member |
| Management connection state | Present; **disproved as a liveness signal** — reported its most positive value for devices confirmed powered off, in two different domains and two device classes | Contract §7.1 L-S1 |
| Absent attribute | Returned as an explicit placeholder token in the value position, **not** as an omitted column | Column count stays stable, so a positional parser does not shift — except where a value is genuinely empty, which is the defect recorded against the existing collector |

## 6. What the channel-state read gave

- Two readings separated by an interval, as CS-3 requires. Every address
  produced the same state in both readings; no address disagreed.
- A large majority of channels established; a small minority not completing.
- The not-completing set contained **every device the Product Owner had
  independently identified as powered off**, and matched the management
  console's own view of which devices were reporting.
- The port was **measured, not assumed** — taken from the management server's
  own established channels. It must stay derived or configurable. A
  version-pinned constant is a defect this repository has already measured
  elsewhere, in the existing collector's environment-profile path.
- Counting requires reduction per address: management servers and cluster
  addresses appear many times over.

**Recorded as hypothesis, not finding:** the count of not-completing channels
equalled the count of devices whose collection failed in an earlier run of the
existing Python collector. Identity was not verified — that run's addresses are
pseudonymized. Confirming it would make this signal a predictor of collection
failure, which is worth knowing but is not yet known.

## 7. The open verification items

In the order they should be closed:

1. **Does the implemented tool expose every field in §5, under a bindable
   name?** Especially the management address, the cluster reference's stable
   identifier, the six virtualization flags and the product-installed flag. If
   any is absent, the contract rule that depends on it cannot be satisfied as
   built, and that is a contract-level finding, not an implementation bug.
2. **How does the implemented tool report an absent attribute** — placeholder,
   omitted key, or null? §5's last row is a behaviour of the first tool only.
3. **Does the implemented tool expose the connection table at all?** The
   channel state was measured from an operating-system facility on the
   management server, not from a management-tool command. Command-gate entry 7
   assumes the latter. If it does not, the method changes and the gate entry
   changes with it.
4. **Does the domain context switch survive the implemented transport?** §3
   row 2's failure mode is silent and produces confident wrong answers, not
   errors.
5. **Is the flag lattice preserved?** The overlapping kinds are the ones most
   likely to be mis-sorted by an implementation that assumed a partition.

## 8. What this document does not do

It does not approve a command, lift a gate, freeze a clause, or authorize a run
against a live server. It does not generalize: every observation came from one
management server, one software generation, several domains — the contract's §2
scope statement governs. And it does not replace the command gate: that
document's ten items per command remain the approval path, and this one only
supplies the evidence some of them should be checked against.

## 9. Cross-references

- `CP_AND_VSX_DISCOVERY_CONTRACT.md` (FROZEN) — §3 transport, §4 classification,
  §5 relationships, §7 liveness and the channel state.
- `CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md` (DRAFT) — the seven entries
  these measurements should be checked against before approval.
- `PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_GATE.md` (FROZEN) —
  the four methods this stays inside.
- `DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md` — the existing
  collector's steps, and the defects measured in it.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — the network-device command gate.
