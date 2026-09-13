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

**SB-6. Cluster and HA-pair resolution belongs to discovery, under a
reciprocity rule.** An earlier draft of this record said the opposite for Palo
Alto. It was written before the enumeration had been read as XML, and it was
wrong; the correction is recorded rather than quietly replaced.

- **Palo Alto.** The enumeration carries each member's peer as a **serial** —
  a stable identifier, not an address. Every member is in the same response,
  so the two-sided corroboration `AGENTS.md` "Evidence laws" requires **in the
  same evidence-collection pass** is available at discovery, contacting no
  device. Measured 2026-09-13: of 35 peer claims, all 35 resolved within the
  response, 34 were reciprocal — 17 complete pairs — and one was one-sided.
- **Check Point.** Membership is a management-plane fact joined on a stable
  identifier (`CP_AND_VSX_DISCOVERY_CONTRACT.md` MC-1), available at discovery
  already.

**SB-7. The rule, stated once for both vendors: a unit is formed only from
corroborated identity, never from one side.**

- The join key is a **stable identifier**. Never a display name, never an
  address (`AGENTS.md` identity law; `CP_AND_VSX_DISCOVERY_CONTRACT.md` HL-2).
- For Palo Alto, corroboration means **reciprocity**: A names B *and* B names
  A. Each member carries its own claim, so both must agree.
- For Check Point, corroboration is structural: members reference one shared
  cluster object, so there is no second side to disagree.
- A claim that does not corroborate is **`NOT_EVALUABLE`**, and the candidate
  is still returned and still shown. This is
  `CP_AND_VSX_DISCOVERY_CONTRACT.md` MC-2 and DI-3 unchanged — no new
  vocabulary is introduced.
- The one non-reciprocal claim measured on 2026-09-13 is the case this clause
  exists for. Forming that pair from one side would have assembled an
  operational unit from an uncorroborated claim, silently.

**SB-8. The Product Owner's standing rule is why SB-7 is strict: clusters fail
over, devices do not.** The unit is the failover target, so it may not rest on
evidence this constitution does not accept.

**SB-9. Discovery presents the cluster as the parent of its members.** When a
unit is formed under SB-7, the operator sees the cluster object with the
devices attached to it, not a flat list that hides the relationship.

**SB-10. Virtual systems are shown at discovery where the vendor supplies
them.** Measured for Palo Alto: 140 virtual-system entries across 39 devices,
each with a display name, arriving in the same enumeration. For Check Point
they are candidates in their own right (§4 of the frozen contract). The
Product Owner asked to see them at this stage where they are available; where
a vendor does not supply them without contacting a device, they are absent and
said to be absent, never inferred.

## 4a. Transport

**SB-11. Transport is chosen per vendor; the shared abstraction is the
management-plane session, not a protocol.**

- **Check Point Management** — a shell session to the management server.
- **Palo Alto Management** — HTTPS to the management server's XML API.

The Product Owner raised the alternative of one transport for both. It was
assessed and declined on a measured ground: `set cli config-output-format xml`
applies to configuration commands only and **not** to operational commands, so
taking the Palo Alto enumeration over a shell would mean parsing a
human-formatted table — column positions and a free-text block — where the API
returns named elements. `AGENTS.md` "Vendor semantics law" holds that a field
name is not its contract; a column heading is further still from one.

Each vendor's management plane is reached the way that plane exposes
structured data. What is shared is the session and identity model, not the
protocol.

## 4b. Identity

**SB-12. One identity model, indifferent to what backs it.** The product
holds an **opaque credential reference** and never a secret
(`UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` §6). What sits behind
that reference is deliberately not the product's concern: a directory account
reached through RADIUS, LDAP or TACACS, or a local account on the management
plane. The account type is verified by the management plane itself, not
modelled here.

**SB-13. Both a directory account and a local account must be selectable.**
The Product Owner's rule is that productisation offers two options where doing
so adds no confusion, and here it adds none: the distinction lives in the
transport's authentication step, never in the store, which holds one opaque
reference either way.

**SB-14. Two management-plane records may reference the same credential.**
The Product Owner's estate uses one directory account for both vendors, and
the productised service account will also be a directory account. The
reference model already permits this and needs no change.

**SB-15. Key-based authentication is supported as an option, never as the
default, and is recorded as a distinct identity.** It removes password expiry
from the collection path, which is a real operational gain. Its costs are
stated so a later movement does not rediscover them: a key is **its own
identity**, not an extension of the directory account, so management-plane
audit attribution changes; key custody becomes a problem of its own
(`recovery_offhost_key_custody`); and on the Check Point plane a key is in
practice bound to a **local** account rather than a directory-authenticated
one. Choosing it is choosing those trade-offs.

**SB-16. Credential resolution failure is refused before any contact.**
`UI2_0_B1_04B` §6 already fixes this and it carries over: a single directory
account backing both vendors is a single point of rotation, and the correct
behaviour when it cannot be resolved is a clean refusal, never a partial run.

## 5. What this record does not decide

- **Which service owns the operational unit after discovery has proposed
  it.** SB-6 settles that discovery *resolves* the unit from corroborated
  management-plane identity; it does not settle which service *owns* that unit
  once devices are imported, nor where a later runtime confirmation of it
  lives. The Product Owner has raised a decision council for this and it stays
  open here.
- **How the cluster view, the common configuration/route/address presentation
  and its difference marking are built.** The Product Owner has named the
  previous product's screens as the reference and the existing Python as
  where its parsing, matching and difference logic can be read. That is a
  separate read-only audit and a separate contract.
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
