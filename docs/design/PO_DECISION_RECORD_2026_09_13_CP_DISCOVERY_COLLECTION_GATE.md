# PO Decision Record — 2026-09-13 — Check Point discovery collection gate

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-13.** This document records a
Product Owner decision given in the 2026-09-12/13 local session that existed
only in session chat. `AGENTS.md` "Authority hierarchy" item 7 makes chat
non-authoritative, so an unrecorded verbal decision is lost at the session
boundary. This file is the durable record; it creates no new authority, it
preserves authority the Product Owner exercised.

It does not amend `PO_DECISION_RECORD_2026_09_12.md`. That record's §1 gate
stands in full except for the single, bounded lift stated in §2 below.

## 1. What the gate said

`PO_DECISION_RECORD_2026_09_12.md` §1 stopped all vendor data collection until
the Product Owner specifies, **per vendor**, the collection type and the
collection methods. Its scope line is explicit that this covers every
collection and extraction path, **"discovery-driven or manual, SSH/API/SNMP"**.
Discovery was therefore inside the gate, not beside it.

The gate's own lift condition is equally explicit: it lifts when the Product
Owner states, per vendor, collection type and methods, and **no other event
lifts it**. This document is that statement, for one vendor and one collection
type.

## 2. The lift — Check Point, discovery only

**PO directive, 2026-09-13.** The Product Owner approved the following as
written.

**Vendor.** Check Point, including Check Point VSX.

**Collection type.** *Candidate enumeration (discovery).* Read-only,
management-plane only. It creates no device record, collects no configuration,
no inventory, no running configuration, no interface or route state, and
stores no evidence.

**Methods.** Exactly these, and nothing else:

1. One authenticated session to the multi-domain management server.
2. Enumeration of the management domains.
3. Read-only object queries against the management database.
4. Reading the management server's own connection table.

**No device is contacted.** No session is opened to any gateway, cluster
member or virtual system. No remote command is executed anywhere. No file is
uploaded to the management server. This is not a limitation of the method — it
is the property the discovery design is built on, and
`CP_AND_VSX_DISCOVERY_CONTRACT.md` rests on it throughout.

## 3. What stays closed

Everything else the §1 gate covers remains gated and is **not** lifted here:

- Configuration, inventory, running-configuration, interface and route
  collection from any device, by any transport.
- Any Check Point path that opens a session to a device or executes a command
  on one, whether directly or by asking the management server to do it.
- Palo Alto in every form. §1 requires a **per-vendor** statement, and this
  document speaks only for Check Point.

Each of those needs its own Product Owner statement of collection type and
methods before it may be implemented.

## 4. What this lift does not by itself authorize

- **It is not a contract.** Implementation authority comes from a FROZEN
  contract for the scope, per `AGENTS.md` "Authority hierarchy" item 2.
- **It is not a command gate.** Any device command or management command that
  a later movement introduces still requires the network-device command gate
  in `docs/AI_DEVELOPMENT_PROTOCOL.md` for the purpose it is used for.
  `AGENTS.md` is explicit that a command's presence in source is not command
  approval.
- **It is not real-environment validation.** A discovery run against a
  management server is management-plane observation, which
  `AGENTS.md` "Evidence laws" holds distinct from direct-device runtime truth.

## 5. Why the boundary is drawn here

The Product Owner's §4 build sequence puts discovery before import and import
before collection, so that an operator sees candidates and chooses what enters
the product. Lifting the gate for discovery alone matches that sequence
exactly: it authorizes the step that produces a choice, and leaves gated every
step that acts on one.

The measurement behind it is recorded in
`CP_AND_VSX_DISCOVERY_CONTRACT.md` and
`DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md`: identity,
addressing, domain ownership, object kind, cluster topology, virtualization
topology, model and software version all resolve from the management plane
without contacting a device. The lift is bounded to what was measured.

## 6. Cross-references

- `PO_DECISION_RECORD_2026_09_12.md` §1 — the gate this lifts one case of, §4 —
  the build sequence it follows.
- `CP_AND_VSX_DISCOVERY_CONTRACT.md` — the design this authorizes work against,
  once that contract carries a status permitting implementation.
- `DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md` — the audit
  that drew the discovery/collection line.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — the network-device command gate, which
  this does not touch.
