# PO Decision Record — 2026-09-13 — Discovery service boundaries and vendor terminology

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-13.** Records decisions the
Product Owner gave in the 2026-09-13 local session. `AGENTS.md` "Authority
hierarchy" item 7 makes session chat non-authoritative, so an unrecorded
verbal decision is lost at the session boundary. This file is the durable
record; it creates no new authority, it preserves authority the Product Owner
exercised.

It does not amend `PO_DECISION_RECORD_2026_09_12.md` §4, whose ordered
sequence stands in full. It adds the service decomposition that sequence runs
inside, fixes the vendor terminology, and settles where cluster and
virtual-system resolution belong.

## 1. Terminology, fixed

Two management planes, named by role rather than by product edition:

- **Check Point Management** — the Check Point management server, whether
  multi-domain or single-domain. `CP_AND_VSX_DISCOVERY_CONTRACT.md` §3 T-1
  says "multi-domain management server"; that clause describes the estate
  measured, not a restriction, and this record does not narrow it.
- **Palo Alto Management** — the Palo Alto management server.

**Individual Device** is the third type, and it is not a management plane.

## 2. Service decomposition

**PO directive.** Three separately deployable services:

| Service | Owns | Does not own |
| --- | --- | --- |
| **Discovery and device add** | Finding candidates behind a management plane, and registering a device the operator already knows. Returns a candidate set; creates a device row only on operator selection | Configuration. Inventory. Any device contact. Any topology it cannot read from the management plane |
| **Configuration engine** | Retrieving configuration and parsing it | Discovery. Inventory/route evidence |
| **Inventory and route engine** | Retrieving address and route evidence and parsing it | Discovery. Configuration |

The two engines are separate microservices from each other and from
discovery. `PO_DECISION_RECORD_2026_09_12.md` §4's step 4 ("configuration and
IP/route evidence follow") is therefore two services, not one, and both stay
behind §1's collection gate.

## 3. The device-add entry point

**PO directive.** One entry point, not two. There is no separate manual-add
module; manual registration is a **type** within the same screen.

```
Device add → address → type
                       ├── Check Point Management   → enumerate candidates
                       ├── Palo Alto Management     → enumerate candidates
                       └── Individual Device        → then vendor
                                                      (Check Point | Palo Alto)
```

The type and vendor chosen here **determine which command set and which
scripts later collection uses**. That is the decision's operational purpose,
not a labelling convenience.

**SB-1. One entry point, two different operations.** Enumeration and
registration are unified in the interface and must not be unified beneath it.

- The two management types **enumerate**: they contact a management plane,
  return candidates, and touch no device.
- **Individual Device registers**: nothing is discovered. It lands on
  `UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` (FROZEN), whose
  registration flow performs **no connectivity check** and keeps
  `DRAFT` → `ENROLLED` a separate authorized confirm action, precisely so
  that "registered" and "confirmed reachable" stay independently auditable.
  Collapsing the two paths into one would destroy that distinction.

**SB-2. Manual registration captures address, vendor and a display name.**
`B1-04B` already specifies the dialog as device name, vendor, transport and
credential profile, and fixes that the name is a display label and never
`device_id`. This record adds nothing to it and defers to it.

**SB-3. Credentials are selected, never typed into the device record.**
`B1-04B` §6: a credential reference names a credential and never holds one;
the dialog takes an opaque reference by id, and the value is resolved only at
execution time in the worker process. The Product Owner's requirement for a
selectable store is satisfied by that design; what is still missing is the
store behind it, tracked as backlog item `credential_profiles`.

**SB-4. Adding the management planes themselves as devices is out of scope
here.** The Product Owner raised it and deferred it in the same breath. It is
recorded as deferred, not as unconsidered.

## 4. Where cluster and virtual-system resolution belong

This is the decision this record exists for, and it splits by vendor
capability rather than by preference.

**SB-5. Virtual-system resolution belongs to discovery.** For Palo Alto the
virtual-system list arrives **inside the managed-device enumeration itself**,
one entry per virtual system, each carrying an identifier and a display name,
costing no second call and no device contact. The Product Owner observed this
directly against a live management server on 2026-09-13; that observation is
the evidence for this clause. For Check Point, virtual systems are candidates
in their own right and their host resolves from the management plane by
`CP_AND_VSX_DISCOVERY_CONTRACT.md` §5.1's measured invariant. Both vendors
therefore support it at discovery, by different mechanisms, and neither needs
a device.

`PAN_DISCOVERY_MEASUREMENT_FINDINGS_2026_09_13.md` records that observation in
full. That document is `DRAFT`, is cited throughout this record as provenance
— where the measurement is written down — and is not authority for any clause
here. Every clause rests on the Product Owner's own read and on the FROZEN
documents §6 names.

**SB-6. Cluster and HA-pair formation does not belong to discovery — for
Palo Alto by construction, and for Check Point by consistency.**

- Palo Alto: the enumeration establishes that a device participates in an HA
  pair and **names no peer** — the Product Owner's 2026-09-13 read found no
  field in it identifying the other member. The configured peer is an
  **address**, not an identifier, and is reachable only through a per-device
  targeted read the gate does not authorize. This project already decided,
  under the `OP.0a.P7` contract, that a pair is formed only from a **mutual**
  configuration-agreement check across both members and never from one side.
  A discovery run holds no device rows and cannot arrange a two-sided check.
- Check Point: membership *is* a management-plane fact, joined on a stable
  identifier (`MC-1`), so discovery **may carry it** — and does, under the
  frozen contract. What discovery still must not do is *form the operational
  unit* from it.

**SB-7. The distinction that makes SB-6 coherent.** Discovery **carries what
the management plane states** and **forms nothing**. Check Point's cluster
reference is carried because the management plane states it; Palo Alto's peer
is `NOT_EVALUABLE` because the management plane does not. The operational
unit — the thing that fails over — is formed later, from corroborated
evidence, by whichever service owns it.

**SB-8. The Product Owner's standing rule applies to the unit, not to the
candidate: clusters fail over, devices do not.** That is the reason SB-6 is
strict. An operational unit built from an uncorroborated one-sided claim
would be a failover target assembled from evidence this constitution does not
accept.

## 5. What this record does not decide

- **Which service forms the operational unit** once devices are imported —
  import, the configuration engine, or a separate topology service. The
  Product Owner has raised a decision council for this question and it stays
  open here.
- Any collection gate. §2's engines remain gated;
  `PO_DECISION_RECORD_2026_09_13B` lifted Palo Alto discovery only, for two
  methods.
- Any transport, schema, screen or deployment topology for the three
  services.
- Whether the management planes are themselves importable (SB-4).

## 6. Cross-references

- `PO_DECISION_RECORD_2026_09_12.md` §1, §2, §4 — the collection gate, the
  Java-from-scratch rule, and the ordered sequence this decomposition runs
  inside.
- `PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md` — the two
  Palo Alto methods.
- `UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` — the registration
  flow, the credential-reference model, and the `DRAFT`/`ENROLLED` split SB-1
  protects.
- `CP_AND_VSX_DISCOVERY_CONTRACT.md` §5.1, §5.3, §8 — Check Point's host and
  cluster resolution, and the discovery/import boundary.
- `PAN_DISCOVERY_MEASUREMENT_FINDINGS_2026_09_13.md` — where the Product
  Owner's 2026-09-13 measurements are written down. `DRAFT`, cited as
  provenance, **not authority**.
- `AGENTS.md` — evidence laws, identity law, authority hierarchy item 7.
