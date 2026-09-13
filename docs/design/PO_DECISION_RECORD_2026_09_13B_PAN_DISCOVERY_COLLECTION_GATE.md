# PO Decision Record — 2026-09-13 — Palo Alto discovery collection gate

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-13.** This document records a
Product Owner decision given in the 2026-09-13 local session that existed only
in session chat. `AGENTS.md` "Authority hierarchy" item 7 makes chat
non-authoritative, so an unrecorded verbal decision is lost at the session
boundary. This file is the durable record; it creates no new authority, it
preserves authority the Product Owner exercised.

It does not amend `PO_DECISION_RECORD_2026_09_12.md`. That record's §1 gate
stands in full except for the single, bounded lift stated in §2 below. It does
not amend `PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_GATE.md`
either: that record speaks for Check Point, this one speaks for Palo Alto, and
§1 of the original gate requires exactly that — a **per-vendor** statement.

## 1. What the gate said

`PO_DECISION_RECORD_2026_09_12.md` §1 stopped all vendor data collection until
the Product Owner specifies, **per vendor**, the collection type and the
collection methods, across every collection and extraction path,
"discovery-driven or manual, SSH/API/SNMP". Discovery is inside that gate, not
beside it. The gate lifts only when the Product Owner makes that statement, and
no other event lifts it. This document is that statement, for Palo Alto and for
one collection type.

## 2. The lift — Palo Alto, discovery only

**PO directive, 2026-09-13.** The Product Owner approved the following as
written, and stated the operative priority: **obtain device and address
information from Panorama.**

**Vendor.** Palo Alto Networks, through Panorama.

**Collection type.** *Candidate enumeration (discovery).* Read-only,
management-plane only. It creates no device record, collects no configuration,
no inventory, no running configuration, no interface or route state, and stores
no evidence.

**Methods.** Exactly these two, and nothing else:

1. One authenticated session to Panorama, obtained through the vendor's own key
   generation call.
2. One read-only managed-device enumeration against Panorama
   (`type=op`, `cmd=<show><devices><all></devices></show>`).

**No device is contacted.** No session is opened to any firewall. No command is
executed on any firewall. No direct-firewall identity gate is run. No write of
any kind is performed against Panorama.

## 3. What stays closed — including one the Product Owner was asked about

Everything else `PO_DECISION_RECORD_2026_09_12.md` §1 covers remains gated:

- Every direct-firewall path, including the `show system info` identity gate,
  `effective-running`, `merged`, active config and pushed-template reads.
- Panorama-side per-device reads: the targeted `xpath=/config` and the targeted
  high-availability state call.
- Interface and route collection through Panorama's `target=` parameter.
- Check Point paths beyond the separate Check Point lift.

**Panorama's own configuration read (`type=config`, `action=show`,
`xpath=/config`, no `target`) is explicitly NOT authorized.** The Product Owner
was asked about it as a distinct third method, with the trade-off stated — it
contacts no device, and it is the only source of Template, Template Stack and
Device Group provenance, but
that step is classified as **configuration** rather than discovery in the
existing behaviour map — and the Product Owner declined it for this round. The
decision is the Product Owner's own; the classification is cited below as
provenance and is **not authority** for it.

The consequence is recorded here so no later movement mistakes it for an
oversight: **Template, Template Stack and Device Group relationships are out of
scope for Palo Alto discovery by Product Owner decision, not for want of
evidence.** A later movement that needs them needs a new statement, not an
inference from this one.

## 4. What this lift does not by itself authorize

- **It is not a contract.** Implementation authority comes from a FROZEN
  contract for the scope, per `AGENTS.md` "Authority hierarchy" item 2. No such
  contract exists for Palo Alto discovery at the time of writing.
- **It is not a command gate.** Any management or device command a later
  movement introduces still requires the network-device command gate in
  `docs/AI_DEVELOPMENT_PROTOCOL.md` for the purpose it is used for. A command's
  presence in the existing Python is not command approval.
- **It is not real-environment validation.** An enumeration against Panorama is
  management-plane observation, which `AGENTS.md` "Evidence laws" holds
  distinct from direct-device runtime truth.
- **It does not authorize the existing Python.** The implementation language
  decision stands: new features are Java written from scratch, and the Python
  is know-how only.

## 5. Why the boundary is drawn here

The `PO_DECISION_RECORD_2026_09_12.md` §4 build sequence puts discovery before
import and import before collection, so an operator sees candidates and chooses
what enters the product. Lifting for discovery alone matches that sequence: it
authorizes the step that produces a choice and leaves gated every step that
acts on one.

The boundary is narrower than the Check Point lift, deliberately. Check Point's
lift covers four methods because its candidate set required a domain
enumeration and a connection-table read to be meaningful. Palo Alto's candidate
set arrives from **one call**. Two methods is what the vendor's shape actually
needs; a wider lift would have authorized more than the work requires.

The behaviour map that made this shape visible is
`DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md` §5 and §6.1,
which classifies exactly one device-free Palo Alto step as discovery (step 25).
That document is `DRAFT` and is cited here as provenance — why the Product
Owner was shown this shape — and is **not authority** for this lift or for any
clause in it. The lift stands on the Product Owner's directive alone.

## 6. Cross-references

- `PO_DECISION_RECORD_2026_09_12.md` §1 — the gate this lifts one case of; §2 —
  the Java-from-scratch rule; §4 — the build sequence it follows.
- `PO_DECISION_RECORD_2026_09_13_CP_DISCOVERY_COLLECTION_GATE.md` — the Check
  Point statement this one parallels and does not amend.
- `DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md` §5, §6.1 — the
  Palo Alto behaviour map and the discovery/collection classification this lift
  is drawn from.
- `PAN_DISCOVERY_MEASUREMENT_BRIEF_2026_09_13.md` — the measurement this lift
  makes possible, and the input to the contract that must follow.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — the network-device command gate, which
  this does not touch.
