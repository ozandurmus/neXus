# PO Decision Record — 2026-09-13 — Collection transport, identity handling and configuration retention

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-13.** Records decisions the
Product Owner gave in the 2026-09-13 local session, in answer to six
questions the Product Owner assistant put after reading the collection-side
contracts. `AGENTS.md` "Authority hierarchy" item 7 makes session chat
non-authoritative; this file is the durable record. It creates no new
authority, it preserves authority the Product Owner exercised.

It does not amend `PO_DECISION_RECORD_2026_09_12.md` §1 except by the
bounded, per-vendor lift stated in §6 below, exactly as the two discovery
lifts of 2026-09-13 did. It does not amend any FROZEN contract; where a
contract needs a successor clause, §7 names it.

## 1. The management plane is for discovery only

**PO directive.** The Check Point management server (MDS) is used for
**discovery only**: devices, cluster objects and their members, VSX hosts
and which virtual system sits on which host, and the management domains.
After discovery, **every collection read goes to the device itself, over
SSH**, at the device's own management address as the management plane
reported it. The management server is not asked to run commands on a
gateway on the product's behalf (the `cprid_util rexec` path the earlier
Python collector used is know-how only and is not carried forward).

Palo Alto follows `PO_DECISION_RECORD_2026_09_13C` SB-11 for the plane it
addresses: **HTTPS XML API**. The Product Owner accepted the assistant's
recommendation as put: direct firewall XML API at the firewall's management
address for actual/effective evidence, Panorama `target=<serial>` reads for
per-device runtime data, and Panorama's own configuration read for Template /
Template Stack / Device Group provenance. `AGENTS.md` "Palo Alto" already
fixes the evidence roles (Panorama = intent/provenance, direct firewall =
actual, primary configuration evidence = `effective-running`); this record
adopts them for collection without change.

## 2. Identity mismatch: warn, record, continue — the Product Owner decides

**PO directive, in the Product Owner's own framing.** *Why refuse? The device
may have been factory-reset; the SSH keys may have been regenerated. Connect
with the credentials; if you see something inconsistent, warn me and show me
what the MDS shows. The same for Palo Alto. Leave this decision to me.*

**ID-M1.** When a device's presented identity (a Check Point gateway's SSH
host key, a Palo Alto firewall's serial from `show system info`) does not
match what the product recorded earlier, the product **connects anyway**
with the configured credentials.

**ID-M2.** The mismatch is surfaced as a **visible warning** on the device
and on the run, placed side by side with what the management plane currently
reports for that device (MDS object / Panorama entry), and is written to the
audit trail (`UI2_0_C1` §3.5). It is never silent, never auto-resolved, and
never re-recorded as the new baseline without an explicit operator action.

**ID-M3.** A strict mode — refuse the connection on mismatch — remains
available as a **production option, off by default**. `AGENTS.md` "Check
Point: Production SSH requires trusted host keys" is satisfied by the option
existing and by ID-M2's visibility; the default posture is the Product
Owner's operational call, recorded here. Backlog item
`ui2_ssh_strict_host_key_trust_in_production` is re-read accordingly:
*configurable strict enforcement*, not mandatory enforcement.

**ID-M4.** The assistant's one-sentence risk note, recorded so it is not
lost: an interposed host produces the same symptom as a reset device; ID-M2's
visibility is what keeps the two distinguishable by a human.

## 3. Configuration: one read, two outputs

**PO directive.** The configuration view is built from a cleaned copy; the
backup must hold the untouched original, encrypted, with its hash checked
for change.

**CF-1. View copy.** `show configuration` (Check Point) / `effective-running`
(Palo Alto) is parsed in memory into safe fields, and a **sanitized text
copy** (credential material and other secret-bearing values removed) is
kept for the configuration view, cluster difference marking and later
compliance work. This is the copy `AGENTS.md` "Raw-evidence law" governs.

**CF-2. Backup copy.** The same read, **untouched**, is stored as a backup
artefact under `UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md`
(FROZEN): encrypted at rest under `C7` §4's custody model, with its digest
recorded in the `C7` §3.2 manifest, so that a later read whose digest
differs is reported as a configuration change. `C7` §1.4 already separates
this artefact from raw-evidence retention; nothing new is invented here.

**CF-3.** Which reads exist per vendor for CF-2 (Check Point `show
configuration`; Palo Alto `effective-running`, and whether `merged` /
active config are also kept) is fixed by the per-vendor collection contract
after measurement, not here.

## 4. Inventory scope — everything the earlier collector read, nothing less

**PO confirmation.** The inventory read set per device is: software version;
serial and model; HA / cluster role; **interfaces with every IP address on
them (cluster virtual addresses included)**; the routing table; and, for VSX,
the same per virtual system. Nothing the earlier Python collector read is
dropped; the Product Owner asked where the IP addresses were and the answer
is: inside the interface read, as before.

## 5. Clusters and virtual systems

**CL-1.** Every cluster member and every virtual-system context is
collected. The product **never opens a connection to a cluster virtual
address (VIP / floating address)**; each member is reached at its own
management address. The VIP is data — collected from the interface read and
shown — never a destination.

**CL-2. Product intent, recorded from the earlier product.** The cluster is
shown as one view: the members' data unified, with member differences
marked as a difference; routing shown the same way. `PO_DECISION_RECORD_
2026_09_13C` §5 already names the earlier product's screens as the reference
for this; this record adds the Product Owner's confirmation that this is the
target, and `UI2_0_C4` §4's target model (member = endpoint, cluster =
grouping modifier, VS = context modifier) is the shape it is built on.

## 6. The lift — inventory and configuration collection, both vendors

`PO_DECISION_RECORD_2026_09_12.md` §1 lifts only when the Product Owner
states, per vendor, collection type and methods. This section is that
statement. It lifts the gate for the **collection types and method classes
below and nothing else**; every concrete command or API route still needs
its own network-device command gate row (`docs/AI_DEVELOPMENT_PROTOCOL.md`)
before it is implemented, exactly as the discovery lifts required.

**Check Point (including VSX).** Collection types: *inventory* (§4) and
*configuration* (§3). Methods: one SSH session per device per run, to the
device's management address; read-only Gaia/Expert commands of these
classes only — version and identity, HA/cluster state, interfaces, routes,
configuration snapshot; the VSX context switch (`vsenv <VSID>` / Clish
virtual-system selection) to repeat the reads per virtual system. No write of
any class. No command executed through the management server.

**Palo Alto.** Collection types: *inventory* and *configuration*. Methods:
HTTPS XML API — (a) to the firewall's management address: key generation,
`show system info` (identity), interfaces, routes, HA state,
`effective-running` and its sibling configuration reads; (b) to Panorama with
`target=<serial>`: the same runtime reads where the estate prefers them; (c)
Panorama's own configuration read, no `target`, for Template / Template Stack
/ Device Group provenance — the read the Product Owner declined for
*discovery* on 2026-09-13 and accepts here for *configuration*. Credentials
travel in POST bodies or headers, never in a URL (`ui2_pan_transport_no_
credentials_in_url`). No commit, no push, no write of any class.

**What stays closed.** Backup writes (`RB.x` class 1) and everything above
class 0 remain under their own contracts and gates. Failover remains gated
in full. Direct SSH to a Palo Alto firewall is not authorized (SB-11's
reasoning: operational output is not machine-formatted over the CLI).

## 7. Consequences for the contracts

- **Import / enrollment contract — new, dispatchable now.** Candidate row →
  `devices` / `endpoints` under `UI2_0_B1_04B`, with cluster and virtual
  system as `C4` §4.2 target modifiers, never rows; the enrollment confirm
  performs the first contact under §2's warn-and-continue rule.
- **`UI2_0_B1_04B` successor clause:** a `pan_xml_api` endpoint kind;
  discovery-created endpoints; the confirm step's first-contact behaviour.
- **`UI2_0_B1_05` (DRAFT):** superseded by a per-vendor **Collection
  contract** written from a Product-Owner measurement against one gateway
  and one firewall, in the shape of the two discovery contracts; §4's scope
  replaces B1-05's two-command subset.
- **`PAN_DISCOVERY_CONTRACT.md`:** amendment candidate — model and software
  version arrive in the same enumeration response the contract already
  reads; roles to be added after the Product Owner's confirmation.
- **`UI2_0_C1` successor migration:** inventory projection (interfaces,
  routes, identity, HA role) and configuration view storage; `C7` already
  holds the backup side.
- **Backlog:** `ui2_ssh_strict_host_key_trust_in_production` re-titled to a
  configurable option per ID-M3.

## 8. Cross-references

- `PO_DECISION_RECORD_2026_09_12.md` §1–§4; `…_13C` SB-3, SB-11–SB-16, §5;
  `…_13D` §3; `…_13E` (the build order these decisions serve).
- `CP_AND_VSX_DISCOVERY_CONTRACT.md`, `PAN_DISCOVERY_CONTRACT.md` — what
  discovery hands to import.
- `UI2_0_B1_04B_DEVICE_MODEL_AND_ONBOARDING_CONTRACT.md` §2, §3, §6;
  `UI2_0_C4_…` §2.3, §4; `UI2_0_C7_…` §1.4, §3.2, §4; `UI2_0_C1_…` §3.5, §6.
- `DISCOVERY_VS_COLLECTION_PYTHON_KNOW_HOW_AUDIT_2026_09_12.md` §3, §5, §7 —
  the earlier collector's read set, cited as know-how, not authority.
- `AGENTS.md` — Evidence laws, Raw-evidence law, Check Point, Palo Alto,
  Network action taxonomy.
