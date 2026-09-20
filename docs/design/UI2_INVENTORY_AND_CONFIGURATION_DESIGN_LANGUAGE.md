# UI2 Inventory and Configuration — Design Language

**Status: FROZEN — PRODUCT OWNER APPROVED, 2026-09-20.** Implementation authority for how
the inventory and configuration screens present what the product already holds, within
the scope §1 states and no further. It authorizes nothing device-facing: collection
methods, vendor commands and the network-device command gate are untouched by it (§9).
Drafted from the Product Owner's walkthrough of the Python product's rendered inventory
and configuration screens on 2026-09-20, and from the approved design canvases in
`docs/design/ui2_mockups/` (`M3Inventory.dc.html`, `M3Configuration.dc.html`). The two
decisions it left open, D-UI1 and D-UI2, were answered by the Product Owner in the same
session and are recorded in §8.

Every example here is masked (`FW-TANGO-04`, `CLS-ROMEO-01`, `192.0.2.0/24`). The
screens this was drawn from carry real estate identities; none are reproduced.

## 1. What this document is for

The presentation rules below already existed and worked in the Python product. UI2
reimplemented the screens without them, and the result is a view that cannot answer the
questions an operator actually asks: which cluster is this, do its members agree, is
what I am looking at current, and where did the value come from.

This is a presentation contract. It does not change what is collected, which vendor
methods are used, or what the product may execute. Those are settled elsewhere and are
explicitly out of scope (§9).

## 2. The unit is the operational unit, not the box

A cluster is one row. **Its members are not rows.** What nests under a cluster is its
virtual systems — Check Point VSX virtual systems, Palo Alto VSYS — each carrying its
own parent reference and its own counters.

```
CLS-ROMEO-01                     ClusterXL · 2 members            2 interfaces · 3 routes
  ├─ VS-ROMEO-11                 VSX · Parent: CLS-ROMEO-01      27 interfaces · 990 routes
  └─ VS-ROMEO-47                 VSX · Parent: CLS-ROMEO-01      13 interfaces · 596 routes
FW-TANGO-04                      PA-3420 · PAN-OS 11.1.4          5 interfaces
```

A member appears as a **column or a chip inside the cluster's own detail**, never as a
sibling row in the list. This is the rule the current UI2 inventory breaks most visibly:
it lists members as separate devices and then has nothing left to say about the cluster.

`AGENTS.md`'s evidence law behind this: an evidence entity (a member) is not the
operational unit (the cluster) it participates in. The list presents operational units.

## 3. Agreement and difference — the core rule

Two members of a healthy cluster should hold the same interfaces, the same routes and
the same configuration. The screen's job is to answer *do they* without making the
operator compare two screens by eye.

**When members agree, render one view.** One table, no per-member repetition:

| Interface | IP address | Subnet mask | Network | VSYS | Virtual router | Zone |
|---|---|---|---|---|---|---|

**When members differ, the difference is the presentation.** Two forms are in use and
both are in the source material:

*Per-member columns*, used where nearly every row differs (a cluster's own interfaces,
where each member holds its own address and the cluster holds a VIP):

| Interface | Cluster VIP | FW-ROMEO-01-M1 | FW-ROMEO-01-M2 | Network |
|---|---|---|---|---|
| bond1.100 | 192.0.2.68/27 | 192.0.2.69/27 | 192.0.2.70/27 | 192.0.2.64/27 |
| Mgmt | — | 192.0.2.84/23 | 192.0.2.85/23 | 192.0.2.0/23 |

The Cluster VIP column is visually distinct and reads `—` where the interface has no
VIP. A VIP is not a member address and is never shown in a member column.

*Shared-or-member chips*, used where most rows are common and only a few are not:

| Interface | … | Scope |
|---|---|---|
| ethernet1/19 | … | `Shared` |
| ha1-a | … | `FW-TANGO-04` |

A row present on one member only carries that member's name. The detail header then
carries a **`Interface diff`** badge so the operator sees that a difference exists
before scrolling.

**Difference is never silence.** A cluster whose members disagree must say so. A cluster
whose members have not both been observed says `UNKNOWN`, not "agree" — absence of
evidence is not evidence of absence, and one member's view is not the pair's view.

## 4. Identity is stated, not implied

The detail header names what the thing is in its own vendor's terms, and who it is made
of:

```
CLS-ROMEO-01
Check Point ClusterXL | Members: FW-ROMEO-01-M1, FW-ROMEO-01-M2 | VSYS: default
MANAGEMENT   FW-ROMEO-01-M1 192.0.2.84   FW-ROMEO-01-M2 192.0.2.85
```

Management addresses are shown per member, under their own label, never folded into the
name line.

**A name is a name.** Where no name has been recorded, the field reads an explicit
unknown. An address is never substituted for a name, in any persona — see
`docs/history/backlog/aiview_masking_manufactures_names_from_any_string.md` for what
happens when it is.

## 5. Freshness is a first-class field

Every row and every detail header states how current it is:

- `Live · collected 06:41 UTC` — observed this cycle
- `Stale 6 d` with `last live 09-02` — shown from last-known-good
- `No live data` with the last time it was live

A `Stale` filter sits beside the vendor filters. Staleness is a property of the
observation, never a health verdict about the device: `AGENTS.md` separates collection
failure from a known-bad state, and this screen must not collapse them.

## 6. Counters say what they cover

Each node carries its own scope: `2 members`, `27 interfaces`, `990 routes`, `12 VS`.
The screen header states coverage rather than a bare total:

```
Network inventory
42 of 46 logical views live · 4 shown from last-known-good · collected 06:40–06:58 UTC
```

```
129 of 160 current · 7 devices with overrides · 0 devices with drift
```

A denominator-free percentage is not allowed. "100% protected" with no stated
denominator is the failure this rule exists to prevent.

## 7. Configuration adds provenance and honest withholding

The configuration detail carries everything above, plus:

**A collapsible identity summary.** Expanded, one tile each for vendor, model, software,
management address, serial, HA role, VSYS/VS count, VSX role, plus config freshness and
current source. Collapsed, the same facts as a single chip row. The HA role tile is what
lets an operator read active/standby per member without opening anything.

**Where the value came from.** `Collected <time> · Primary source: <method>`, a
`Current actual` badge, the projected-setting count, and `Origin` and `Context` columns
on every settings table.

**What was withheld, and why.** `8 secret-bearing settings were withheld` stated on the
page, not omitted silently. Where a plane is deliberately deferred — Check Point
expected-versus-actual alignment is, today — the page says so rather than rendering an
empty table that reads as "no differences".

**Cluster configuration follows §3.** Two members' configuration is expected to match;
where it does not — an extra local user, a different DNS server, a different NTP
version — that is rendered as a difference, not as two screens to compare. The source
material makes the case by itself: two members of one cluster reported 352 and 354
projected settings, and the divergence was only findable by opening both.

## 8. Decisions taken

**D-UI1 — both difference forms stay, and the screen chooses.** Per-member columns and
shared-or-member chips answer the same question at different divergence levels, and
each fails where the other works. Columns suit a table where nearly every row differs,
as a cluster's own interfaces do when each member holds its own address; chips suit a
table where rows are overwhelmingly common and a handful are not, as a Palo Alto
cluster's interfaces are. The choice is made from the data, not offered as an operator
setting, and the view states which form it is showing. (Product Owner, 2026-09-20.)

**D-UI2 — virtual systems are not configuration nodes, for either vendor.** The
configuration plane collects nothing that belongs to a virtual system. The Check Point
collector gathers Gaia sections — system, DNS, NTP, management, password policy, login
banner, management services, logging, high availability, interfaces, routing, SNMP,
authentication — over `clish`, with no `vsenv` and no VSID anywhere in its read plan.
Every setting it holds is the physical member's. Rendering a virtual system as a
configuration node therefore repeats the member's own Gaia configuration once per
virtual system, which is what the Product Owner recognised on the live screen. Palo Alto
VSYS carry no configuration of their own either. So in Configuration the unit is the
cluster, with member differences rendered per §3, and virtual systems do not appear.
They remain nodes in Inventory, where interfaces and routing genuinely differ per
virtual system, and in Policy & Objects. (Product Owner, 2026-09-20.)

Recorded alongside D-UI2, not resolved by it: `interfaces` and `routing` appear in the
Check Point configuration section list although they are collected without a virtual
system context, so for VSX what is held under those two sections is the member's view
rather than each virtual system's. That is a collection gap, tracked separately, and it
does not change the rule above.

## 9. Out of scope

Collection methods, vendor commands, polling behaviour, enumeration adapters and the
network-device command gate. The Python product is a reference for **how a view is
composed**, never for how data is gathered: those methods are settled, and reopening
them is not authorized by this document.

The Material 3 palette stays. These presentations are expressed in it — the VIP column
and the member chips map onto the existing container roles rather than introducing a
second visual language.
