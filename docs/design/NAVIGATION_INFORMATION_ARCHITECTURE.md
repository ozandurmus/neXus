# Navigation & product information architecture

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-05.** Reviewed branch head
`ba56d2b` on `claude/left-nav-vertical-redesign-e673q6`.

**What this freeze does and does not do.** It fixes the *architecture* — the
information architecture, the capability model, the entity-workspace model and
the presentation contract — so later work implements against a settled shape
instead of re-deciding it. **It does not authorize any implementation
movement.** `M1`…`M14` remain **separately authorized, bounded movements**
(companion contract §12/§12.1); each still needs its own go-ahead, its own
scope and its own validation. The existing `NAV.1` prototype is **not
implementation-complete**: it may not merge or be called complete until `M2`
closes the four accessibility requirements in §13.1.

Deferred detail below is **implementation-contract work inside a frozen
direction**, never unresolved architecture: exact domain-specific state names
at `M3`, exact SQLite schema at `M4`, exact vendor trust mechanics at `M8`,
exact enrollment API schemas at `M9`.

**Revision history — three revisions, one of them invalid.**

- **Revision 1 (2026-09-05).** Declared itself `FROZEN` on the strength of a
  working prototype and its own tests, with no Product Owner authority behind
  that declaration. **This self-freeze was invalid and is withdrawn.** Tests
  prove a prototype matches the contract it was written against; they do not
  prove the contract is the right product architecture, and they are not a
  substitute for approval.
- **Revision 2 (2026-09-05).** The Product Owner reviewed revision 1 and
  **substantially accepted its direction**, closing eight navigation decisions
  (`PO-NAV-1`…`PO-NAV-8`, §16) plus the runtime, storage, trust and enrollment
  directions in the companion contract. Freeze was withheld at this point for
  two reasons: an **evidence-integrity correction** was required — revision 1
  had misattributed a set of supplied screenshots to third-party products, and
  every conclusion resting on them was withdrawn (research appendix §0.2) —
  and several decisions came back with their **ownership corrected** rather
  than simply approved (`PO-NAV-6`, `PO-NAV-7`, `PO-NAV-8`, `D-NAV13`, the
  freeze-vs-merge gate).
- **Revision 3 (2026-09-05) — the valid Product Owner freeze.** With the
  evidence correction applied and the corrected decisions recorded, the
  Product Owner approved `D-NAV3`, `D-NAV6a`, `D-NAV6b`, `D-NAV7`, `D-NAV10`,
  `D-NAV11` and `D-NAV12` (the remaining rows that had stayed `PROVISIONAL`
  through revision 2), and `D-NAV13`/`D-NAV14` became part of the frozen
  contract with them. **No operative decision row remains provisional.** This
  is the freeze recorded at the top of this section: approved at reviewed
  branch head `ba56d2b`, dated 2026-09-05.

The prototype remains **design evidence**, now demonstrating a frozen
architecture rather than a proposed one. The Product Owner has approved the
architecture; approving the *merge* of the prototype is a separate, later step
gated on `M2` (§13.1), and no roadmap movement is replaced by this freeze.

| | |
| --- | --- |
| **Movement** | `ARCHITECTURE` (navigation + product IA), continuing the `NAV.1` prototype |
| **Revision** | **3 — 2026-09-05, FROZEN.** Revision 2 recorded the Product Owner's review decisions (§16) and withdrew every conclusion that rested on screenshot evidence whose provenance is not durably auditable (research appendix §0.2). Revision 3 closes the remaining `PROVISIONAL` grades and freezes the contract |
| **Authorizes** | nothing to be built. Architecture only |
| **Prototype** | commit `5a5a1f7` on `claude/left-nav-vertical-redesign-e673q6` — runnable, behaviourally unchanged by this review |
| **Implementation** | none in this document. No UI behaviour, no storage, no enrollment, no job, no collector, no production wiring |
| **Companion contract** | `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` (**FROZEN**) — runtime, storage, jobs, enrollment, sequencing |
| **Research appendix** | `docs/design/research/NSPM_NAVIGATION_BENCHMARK.md` — a **research/evidence appendix, not a frozen product contract**. No frozen decision here depends on it |
| **Amends (this freeze)** | `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` (`CON.0`) §4 (new §4.1) and §7 (new rule 11); `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` (`PCP.0`) §10 and §19 (new decided block) and §20 (new §20.1) — narrowly, to record the decisions this freeze approves. Full detail: companion contract §9.2 |
| **Preserves unchanged** | Everything in `CON.0` and `PCP.0` *not* named above, including `CON.0` §3/§6/§9/§10 and `PCP.0` §8/§9/§12/§13 in full, and every named section's content outside its stated amendment; `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10/§10.1/§10.2; `RB.x`; `utils/action_taxonomy.py`; every payload builder's output; every pre-existing route |

### Decision grades used throughout

| Grade | Meaning |
| --- | --- |
| **FROZEN** | Product Owner approved and part of the frozen contract. No operative decision row is left `PROVISIONAL` |
| **RESERVED** | named in the frozen architecture, deliberately **not rendered** today, with a stated promotion trigger |
| **REJECTED** | evaluated and declined, with the reason recorded |
| **IMPLEMENTATION CONTRACT** | the direction is frozen; a named later movement owns the exact schema, names or mechanics (§16.1) |

---

## 0. How to read this document

Three layers, deliberately separate, because collapsing them is what produced
the flat menu this work started from:

```
Layer 1  Product navigation   — what the platform can do across the fleet
Layer 2  Context navigation   — the selected logical entity's workspace
Layer 3  Contextual actions   — what may be done, here, now, to this subject
```

A capability's *existence* (Layer 1), its *applicability to the selected
subject* (Layer 2), its *evidence state* (Layer 2/3) and its *authorization*
(Layer 3, future) are **four different questions** (§7). The prototype answered
only one of them, which is the central finding of this review.

---

## 1. Problem

The shipped shells carried one horizontal `<nav class="module-nav">` with **one
root button per module**: Overview, Network Inventory, Configuration,
Compliance, Discovery, Failover, Exclusions, Project Plan.

1. **A flat action list, not an information architecture.** Eight sibling roots
   say nothing about which are product domains (Configuration) and which are one
   view inside a domain (Exclusions). Below 900 px the CSS already abbreviated
   four labels and let the rest fall off the readable end.
2. **It cannot absorb the Product Control Plane.** `PCP.4` adds a per-device
   experience; `PCP.5` a typed job plane; `RB.x` a recovery surface. Under
   one-root-per-view each becomes another top-level button and device-scoped
   functions duplicate as unrelated roots.
3. **Nothing tied a navigation entry to a real backend.** An entry existed
   because someone wrote a `<button>`.

The prototype addressed (1) and (2) well, and addressed (3) with a rule that is
**necessary but not sufficient** — see §2 and §7.

---

## 2. Challenge review of the `NAV.1` prototype

Deliberately adversarial. The prototype is evidence, not a defendant with
tenure.

### 2.1 Successful and worth preserving

| Finding | Why it holds |
| --- | --- |
| **Left vertical rail** as primary product navigation | Corroborated externally: Tufin's navigation bar is "on the panel to the left of the screen"; SmartConsole's "Gateways & Servers" is "from the left navigation panel". It also removes the horizontal width ceiling that forced label abbreviation |
| **Domain grouping instead of one root per view** | Six roots over nine panels; the rail has room to grow into `PCP.4`/`PCP.5` without a new root each time |
| **Collapse is presentation density only** | Browser-verified: identical `data-module` set collapsed and expanded. This is the right invariant and must survive every later revision |
| **Routes derived from one model** | Removed a real drift bug (`#discovery`/`#failover`/`#exclusions` fell back to Overview from the URL hash). The principle — one declaration, routes derived — is durable |
| **One model shared by the rail and the device tab strip** | Prevents two divergent navigation implementations, which is exactly how the horizontal strip and the config tabs had already diverged |
| **"Add Device" declared but not rendered** | The *location* of a future action is decided and reviewable without a placeholder being drawn. Keep the pattern; §9 corrects how availability is decided |
| **Delegated click dispatch on the rail** | Makes the rail safely re-renderable, which the capability-driven model in §7 will need |
| **Zero console errors, both harnesses green, action-free report intact** | The prototype does not regress any existing invariant |

### 2.2 Good *current-shell implementation detail*, not architecture

These are correct today and must not be mistaken for product law:

- **`[data-module-panel]` existence as the availability test.** A pragmatic
  last-mile integrity check for a two-shell, no-build-step frontend. It is not a
  capability model (§2.4, §7).
- **The `jobs` panel living only in `console.html`.** Correct today because the
  `CON.2` job engine is console-only. It is a *shell-shipping* fact, not a
  statement that job evidence may never be exported (§12).
- **Six roots.** The right *shape*; the membership is provisional (§4).
- **Per-group collapse persisted in `localStorage`.** Fine; per-viewer
  convenience, no product meaning.
- **Icon choice, rail width, chevron behaviour.** Visual detail.

### 2.3 Durable product-IA decisions the prototype got right

- Product navigation is **left, vertical, grouped by domain**.
- **Device-scoped functions are tabs inside an entity experience, never roots.**
- **"Add Device" is never a navigation root.**
- **Collapse never changes availability or authority.**
- **Navigation location alone grants no action authority.**

### 2.4 Prematurely frozen or pre-decided in revision 1 — corrected here

Historical record of revision 1's errors and how each was resolved on the path
to the revision 3 freeze. None of these rows describes the document's current
status, which is stated at the top of this document.

| Revision 1 claim | Correction |
| --- | --- |
| The document declared itself **FROZEN** (no Product Owner authority) | That self-freeze was invalid and withdrawn. Revision 2 put the document to Product Owner review; revision 3 records the resulting valid freeze at the top of this document |
| **D-NAV6** presented DOM presence as *the* anti-placeholder law | Downgraded to a **last-mile surface-integrity check**, one conjunct of four (§7). A `<section>` in a template is not evidence that a backend contract exists |
| **D-NAV5** stated as fact that browser enrollment "waits for `DEPLOY.1A`" | The prototype's own comment pre-decided a Product Owner question, and was corrected in source. The Product Owner has since decided (`PO-NAV-1`, companion §9.1): a **conditioned local-loopback** direction, not an unconditional wait — so revision 1's assertion was wrong in substance as well as in authority |
| **Six roots** presented as "the information architecture" | Corrected then approved: Recovery and Jobs/Automation were not evaluated in the first pass and are now (§4), and `PO-NAV-8` freezes the six-root baseline with Recovery reserved seventh |
| **Jobs rejected as a root** on the strength of one shell | The reasoning (a root absent from one shell is a bad fact) is sound. Revision 1 cited "BackBox ships Jobs as a top-level menu" as external counter-evidence; **that observation is withdrawn** (appendix §0.2) and no retrievable source establishes any competitor's Jobs placement. Settled instead by decision: `PO-NAV-8` keeps Jobs under Operations, with **no** automatic promotion promise |
| **`NAV.1` recorded as `automated_validated`** in project state | Corrected on this branch: a PO-review movement, non-terminal, prototype status (§ project-state correction in the companion contract §12) |

### 2.5 What breaks once SQLite, enrollment, schedules, recovery, device jobs and RBAC arrive

This is the most important section of the review.

1. **Availability stops being a DOM question.** Once capability projections
   exist (`PCP.3`), a shipped panel may be *inapplicable* to the selected
   entity, or applicable but *unsupported* for its vendor. The prototype has no
   vocabulary for that and would either show a working-looking empty tab or
   (worse, if the rule were extended naively) delete the tab.
2. **Entity selection becomes global state the rail does not model.** Today
   Inventory and Configuration each hold their own independent selection.
   With a device workspace, "selected entity" is cross-module state that must
   survive navigation (§6.6).
3. **A registry-keyed world introduces `NOT_ENROLLED`.** An entity present in
   `unified.json` but absent from the Device Registry — and the reverse — is a
   real state the current model cannot express (§7.3).
4. **Schedules are per-device, per-capability.** FireMon puts "Enable
   Scheduled Retrieval" on the device with its own interval. That does not fit
   anywhere in the current rail and must not be smuggled into navigation state
   (§4.3, companion contract §9).
5. **Recovery becomes a domain, not a tab.** Restore-readiness across the fleet
   (`RB.5`/`CON.4`) is a fleet workflow; leaving it as one device tab under
   Configuration will be wrong then, and is defensible only until then.
6. **RBAC adds a fourth conjunct** that must be *additive*, never retrofitted by
   reusing the DOM check as a proxy for permission (§7.4, §15).
7. **Job history wants to be an entity tab**, and the prototype's Jobs entry is
   a fleet-level console panel. Both will exist; they are different data
   authorities over the same records (§6.7).

### 2.6 Legitimate report/console differences

Legitimate and must be preserved (`CON.0` §1/§3/§6):

- The report is a **portable, action-free evidence artifact**; the console is a
  **local, authenticated, action-capable** surface.
- Therefore: no job submission, no credential/trust selection, no enrollment,
  no mutation form in the report — **absent, not disabled**.

Illegitimate differences to avoid:

- A different *information architecture* between the two. One product IA,
  shared components; the two differ in **action availability and live data**,
  not in how the product is organised.
- Assuming "the report has no Jobs panel" means "job *evidence* may never be
  exported". Read-only, sanitized execution history in an exported report is a
  separate question, now **decided in principle with a ship trigger**
(§12, `PO-NAV-4`).

### 2.7 Navigation instability / capability disappearance risk

**This is the prototype's most dangerous property**, and the reason the DOM rule
is downgraded. As written, `navigationAvailableRoots()` drops any entry whose
panel is missing, and drops a group that loses all children. Extended to a
capability-driven world without §7's separation, that would mean:

- selecting a device without backup evidence could remove a **Recovery** entry
  the operator used a minute ago;
- an empty group could vanish and **reflow the whole rail**, moving every
  entry under the operator's cursor;
- a transient payload failure could look like a **capability that was revoked**.

**Rule adopted here (D-NAV11):** the rendered *root set* is a function of
**product-surface eligibility only** (§7.1) — never of the selected entity,
never of evidence state, never of a payload's contents. The rail is stable
while an operator works. Emptiness is expressed **inside** the module, in
words, never by the module disappearing.

### 2.8 "Unavailable" vs "unsupported for *this* device"

The prototype can only omit. It cannot say:

> Backup is a product capability. **This** device is a Gaia embedded appliance
> outside the `D3` pilot allowlist, so the backup job is `BLOCKED`, and the
> reason is that allowlist — not a missing feature.

Conflating those two is a product-integrity failure, not a cosmetic one: it
tells the operator the product cannot do something it can. §7's four predicates
and §8's presentation matrix exist to make each case say what it actually is.

---

## 3. Decision register

Re-graded. `FROZEN` appears nowhere.

| id | Decision | Grade |
| --- | --- | --- |
| **D-NAV1** | Primary product navigation is a **left vertical rail**; the topbar keeps brand + module-context controls only | **FROZEN** (visual direction accepted by the PO) |
| **D-NAV2** | The rail is collapsible; **collapse changes presentation density only** — never the entry set, never availability, never authority | **FROZEN** |
| **D-NAV3** | Group by **stable product domain**, not by action. A domain with one shipped view renders as a direct root link and becomes a group by gaining children, with no route change | **FROZEN** (`PO-NAV-8`). Note: revision 1 claimed external corroboration for domain group headings; that is withdrawn (appendix §0.2) — this rests on neXus' own domains and the PO's decision |
| **D-NAV4** | **Devices is a first-class root**; the device experience is never a sub-view of Configuration | **FROZEN** |
| **D-NAV5** | **"Add Device" is never a navigation root.** Primary affordance in the Devices/entity-list pane header; **Administration → Device Management** is the lifecycle home; **one enrollment contract**, no duplicate implementation; Configuration never owns device lifecycle | **FROZEN** (`PO-NAV-1`). Rendering it locally is approved *in direction* under the conditions in the companion contract §9; production exposure stays blocked on `DEPLOY.1A` |
| **D-NAV6** | ~~DOM presence is the availability law~~ → **superseded by D-NAV6a/D-NAV6b** | **CORRECTED** |
| **D-NAV6a** | A rendered navigation entry requires **product-surface eligibility**: a declared shipped contract, a shell permitted to expose it, and the surface actually present. DOM presence is the *last* of the three, an integrity check, not the rule | **FROZEN** |
| **D-NAV6b** | **No visual placeholder.** A capability with no product surface is omitted. A capability *with* a surface that is currently inapplicable, unsupported, unconfigured, blocked or evidence-poor is **shown, selectable where useful, and explained in words** — never a bare greyed control (`CON.0` §9 honest affordances) | **FROZEN** |
| **D-NAV7** | **Progressive population**: shipping a capability into navigation is adding its surface plus one model row. No route table, no `switchModule` list, no CSS | **FROZEN** |
| **D-NAV8** | Routes are **preserved and derived** from the model; every pre-existing hash/localStorage route keeps working | **FROZEN** |
| **D-NAV9** | **Authorization-aware, not authorization-implementing.** `navigationAuthorizationContext()` returns `model: "none"`. No role/permission/scope/claim gates any navigation decision until a real OIDC/RBAC model exists | **FROZEN** |
| **D-NAV10** | **Three layers** (product navigation / entity context / contextual actions) are separate concerns with separate rules | **FROZEN** |
| **D-NAV11** | **Root stability**: the rendered root set depends on product-surface eligibility only — never on the selected entity, evidence state or payload contents | **FROZEN** |
| **D-NAV12** | **One selected-entity context** is shared across modules; each module keeps its own **independent data authority** over that subject | **FROZEN** |
| **D-NAV13** | **Device-tab stability (corrected).** Where a workspace surface exists, its canonical tab position is stable across entity types and must not flicker with selection or evidence. A structurally inapplicable tab stays **visible and selectable** with a `NOT_APPLICABLE` explanation and no enabled action. A tab is omitted **only** on a P1 failure — the surface does not exist in that build/shell | **FROZEN** (§6.5) |
| **D-NAV14** | **Colour semantics (corrected by `PO-NAV-6`).** The pale yellow/gold comparison emphasis that makes member differences noticeable is **preserved**; what changes is that colour alone never carries meaning. Every state has an explicit textual label, expected member differences carry **no** warning icon or wording, and red stays reserved for actual fault, unsafe drift or failure | **FROZEN** (§5.4) |

---

## 4. Layer 1 — product navigation

### 4.1 Candidate domain evaluation

Every candidate was evaluated against **what the repository can serve today**
and **where the product is going** (`PCP.0` §1). "Serve" means a shipped payload
or contract, not a plan.

| Candidate | Verdict | Reasoning |
| --- | --- | --- |
| **Overview** | **root link** — ACCEPTED DIRECTION | The landing surface, not a domain with views. Every benchmarked product lands on a dashboard |
| **Devices** | **grouped root** — ACCEPTED DIRECTION | First-class (D-NAV4). Today: `Inventory`, `Discovery`. Target: the **entity workspace** becomes its primary child, with the fleet list as its index |
| **Configuration** | **root link today; root + entity tab in target** — ACCEPTED DIRECTION (`PO-NAV-3`) | `AGENTS.md` forbids collapsing Configuration into Inventory: they are separate product planes. Fleet-wide alignment review is a real cross-device workflow — externally, Tufin's Compare Revisions is a first-class view with its own device-tree pane (appendix §2, DOC-EXCERPT). **Decided** by `PO-NAV-3`: the global root keeps fleet-level work; device-level views use the shared entity workspace |
| **Operations** | **grouped root** — ACCEPTED DIRECTION (`PO-NAV-8`) | "What is running, and what may be run, against the fleet." Today: `HA & readiness`, `Jobs`. The mature default set is `Jobs`, `Schedules`, `Queue`, `History` and future typed automation definitions — a **Product Owner decision**, not an observed competitor pattern (revision 1's BackBox citation is withdrawn, appendix §0.2) |
| **Jobs** | **child of Operations — the default, with no promotion promise** — ACCEPTED DIRECTION (`PO-NAV-8`) | Today its only surface is the console job list, which the action-free report must not carry. As Operations gains `Schedules`, `Queue` and `History`, the mature end-state is **several execution surfaces under Operations**, not one promoted page. A root promotion is **not pre-approved**: it would need a new PO-reviewed IA amendment justified by real information density and operator workflow |
| **Automation** | **RESERVED — not a separate domain** | neXus' "automation" is the **typed job definition + schedule** half of the job plane, and belongs under Operations beside `Jobs` (`PO-NAV-8`), not as a rival domain. Named in the target architecture and **not rendered** until `PCP.5` gives it definitions to show |
| **Compliance** | **root link** — ACCEPTED DIRECTION | A shipped product plane with fleet and subject views already built |
| **Recovery** | **RESERVED — future root, not rendered today** | Today the only recovery surface is one device tab (`configBackupPanel`) plus an Overview card. `RB.4`/`RB.5`/`CON.4` bring restore-readiness and recovery-store surfaces that are **fleet** workflows; at that point a device tab is the wrong home. **Decided** by `PO-NAV-2`: promote only when a real fleet-level restore-readiness and/or recovery-store surface ships under its `RB`/`CON` contract. Once it is a root it stays a root even when the selected device is `UNPROTECTED` or `NOT_CONFIGURED` (D-NAV11) |
| **Administration** | **grouped root** — **FROZEN** (`PO-NAV-8`) | Today: `Inventory exclusions`, `Project plan`. Target children in §4.4, each rendered only when its own surface ships |
| **Diagnostics** | **RESERVED** | `PCP.8`, closed runbook catalog. No surface, no entry |
| **Topology / Risk / Policy authoring** | **REJECTED** | Policy authoring is `CLASS 3` (prohibited). Topology/risk analysis is not in `PROJECT_VISION.md`'s scope and inventing a root for it would advertise a capability the product does not have |

### 4.2 Current shipped IA (what the prototype renders today)

| Root | Kind | Children rendered | Backing surface |
| --- | --- | --- | --- |
| Overview | link → `overview` | — | `overview_ui.js` over existing payloads |
| Devices | group | `Inventory` → `inventory`; `Discovery` → `discovery` | `inventory_ui.js`; `discovery_ui.js` |
| Configuration | link → `configuration` | (7 device tabs) | `configuration_ui.js` |
| Operations | group | `HA & readiness` → `failover`; `Jobs` → `jobs` *(console shell only)* | `failover_readiness_ui.js`; `console_actions.js` + `CON.2` API |
| Compliance | link → `compliance` | — | `compliance_ui.js` |
| Administration | group | `Inventory exclusions` → `exclusions`; `Project plan` → `project-plan` | `inventory_exclusions` payload; `project_plan.py` |

Six roots, nine module panels, zero placeholders. This is a **candidate
baseline**, not the approved target.

### 4.3 Target IA (proposal, requires PO approval)

Rendered only as each row's surface actually ships.

| Root | Target children | Promotion trigger | Grade |
| --- | --- | --- | --- |
| **Overview** | — | — | ACCEPTED DIRECTION |
| **Devices** | `Fleet` (entity list + workspace), `Discovery` | entity workspace ships (`PCP.4`) | **FROZEN** |
| **Configuration** | fleet alignment/posture views; device-scoped views move into the workspace | `PCP.4` | **FROZEN** (`PO-NAV-3`) |
| **Operations** | `HA & readiness`, `Jobs`, later `Schedules`, `Queue`, `History`, future typed automation definitions | each on its own surface shipping | **ACCEPTED DIRECTION** (`PO-NAV-8`) |
| **Compliance** | — (fleet + subject views already inside) | — | ACCEPTED DIRECTION |
| **Recovery** | `Restore readiness`, `Recovery store` | a real **fleet-level** restore-readiness and/or recovery-store surface ships under its `RB`/`CON` contract | **RESERVED — promotion rule accepted** (`PO-NAV-2`) |
| **Administration** | `Device management` (registry lifecycle + enrollment), `Credential profiles` *(references only)*, `Trust profiles`, `Inventory exclusions`, `System & runtime health`, `Users & roles` | each on its own backend; `Users & roles` on `DEPLOY.1A` only | **FROZEN** |
| *(Automation, Diagnostics)* | — | `PCP.5` / `PCP.8` | RESERVED, not rendered |

**Jobs stays a child of Operations — corrected.** Revision 1 promised that Jobs
would become a root once it owned definitions, executions, schedules and
history. **That automatic promise is withdrawn** (`PO-NAV-8`). The mature
default is Operations growing several execution children — `Jobs`, `Schedules`,
`Queue`, `History`, future typed automation definitions — not one of them being
promoted. A root promotion is **not pre-approved**; it would require a new
PO-reviewed IA amendment justified by real information density and operator
workflow.

**Recovery's promotion rule, stated exactly** (`PO-NAV-2`): it is a reserved
future global root, **not rendered today**; device/entity Recovery remains a
contextual workspace tab; it promotes only when a real fleet-level
restore-readiness and/or recovery-store surface ships under the relevant
`RB`/`CON` contract; and once the global root exists it **stays visible
regardless** of whether the selected entity is protected, unsupported or not
configured (D-NAV11).

**Reserved domains are written here and rendered nowhere.** A `RESERVED` row is
a commitment about *where* a capability will live, so later work does not
re-litigate the IA — it is never a "coming soon" entry (D-NAV6b).

### 4.4 Administration children — direction vs today

| Child | Today | Direction |
| --- | --- | --- |
| Inventory exclusions | shipped, read-only | stays; write path stays blocked (`inventory_exclusions_management_ui_backend`, `DEPLOY.1A`) |
| Project plan | shipped | see §14 — development visibility now, administration/developer-only later |
| Device management | **none** | registry lifecycle + the enrollment action's second home (`PCP.2`/`PCP.4`) |
| Credential profiles | **none** | **opaque references only**, never secret material (companion contract §10) |
| Trust profiles | **none** | host-key/CA trust references (`pcp_first_contact_trust_policy`) |
| System & runtime health | **none** | control-plane service state, job runner health (companion contract §6) |
| Users & roles | **none** | `DEPLOY.1A` only. **Must not be rendered before a real authorization model exists** — a fake user page is worse than none |

---

## 5. Layer 2 — context navigation (the entity workspace)

### 5.1 What it answers

For **one selected logical subject**: what is this object, what evidence exists
for it, what may be viewed or operated on it, and what state applies.

### 5.2 Logical subject types and default selection level

**The Product Owner's model is logical-entity-first**: a cluster or HA pair is
normally **one managed operational object**, with physical members represented
beneath it. Externally corroborated by the two sources that survive evidence
review — SmartConsole nests cluster members inside the opened cluster object,
and Tufin's device hierarchy shows management devices with the devices they
manage (appendix §7.1, §2). It is also already true of neXus itself: virtual
systems render beneath their VSX parent and HA children beneath their unit
(appendix §1, REPO-VERIFIED).

**No second identity authority is created.** The workspace *selects* an existing
canonical id and *renders* backend-derived relationships. It never computes
identity, pairing, topology or readiness (`PCP.0` §13, `OP.2.0` P4/P14).

| Logical subject | Default selection | Members / children shown | Existing identity + topology authority |
| --- | --- | --- | --- |
| Standalone CP gateway | the device | — | `unified.json` entity id; registry `device_id` once enrolled |
| **CP ClusterXL cluster** | **the cluster** | physical members nested | `cp_runner.enrich_cluster_topology` (`group_id` VIP fingerprint); `OP.2.0` P8 |
| CP ClusterXL member | drilldown from its cluster; **never the default view of the cluster** | — | member token; presentation identity is never a join key |
| **CP VSX host / cluster** | **the VSX cluster** | physical members **and** nested virtual systems | `vsx_parser`; `OP_0B_S4A` |
| CP virtual system (VSID) | nested child of the VSX parent; selectable in its own right | — | VSID is a **readiness** domain under VSLS, and **never** a `CLASS 2` lock subject (`FAILOVER_ENGINE_ARCHITECTURE.md` §10.2) |
| Standalone PAN firewall | the device | vsys as subordinate context | serial (opaque) |
| **PAN HA pair** | **the pair** | 2 members nested | `PCP.0` §12; management-plane correspondence only (S8-C `MATCH`) |
| PAN HA member | drilldown only | — | serial; **`B₂` bidirectional corroboration NOT ESTABLISHED** — the workspace must not imply it is |

**Fail-closed rule:** where the pair/cluster relationship is not established
(the open PAN `B₂` question), the workspace shows the members it can prove and
says the relationship is `UNKNOWN` — it does **not** draw a confident pair.

### 5.3 Shared vs member-specific values

Render a value **once** when it is semantically shared across the logical
entity; show it **side by side** when it legitimately differs per member.

| Shared (render once) | Member-specific (render side by side) |
| --- | --- |
| cluster virtual IP (VIP) | HA role (`ACTIVE` / `STANDBY`) |
| shared/logical interface | member serial numbers |
| common route | member management IP |
| shared policy / configuration fact | sync addresses |
| cluster-level identity (model, version family) | member-local routes and configuration settings |

### 5.4 Difference semantics — one meaning per treatment (`PO-NAV-6`)

Revision 1 recommended that Inventory simply adopt Configuration's blue `info`
tone for `MEMBER_SPECIFIC`. **The Product Owner has rejected that specific
remedy and kept the underlying problem.** The pale yellow/gold emphasis that
makes cluster-member differences easy to *notice* is a real product benefit and
is preserved; what must change is that the colour stops being the only carrier
and stops implying fault.

The defect being fixed is genuine: Inventory paints every member-scoped row with
the warning token (`.difference-row`, `.scope-chip.diff`, `.divergence-badge`
all use `--warning`), while Configuration already separates the cases
(`statusTone()`: `MEMBER_SPECIFIC` → `info`, `LOCAL_OVERRIDE` → `warning`,
`EFFECTIVE_DRIFT` → `danger`). Two planes therefore say different things about
the same fact.

**Adopted contract:**

| Semantic | Canonical state | Treatment | Required label | Icon / wording rule |
| --- | --- | --- | --- | --- |
| **Expected member difference** | `MEMBER_SPECIFIC` | **may keep the pale yellow / gold row emphasis** in member-comparison views | "Expected member difference" or "Member-specific" | **No** warning or failure icon; no failure wording |
| Intentional local override | `LOCAL_OVERRIDE` | stronger attention treatment | "Local override" | attention iconography permitted |
| Unclassified difference | `DIFFERENCE_OBSERVED` | attention / warning semantics | "Difference observed" | attention iconography permitted |
| Effective unexplained drift | `EFFECTIVE_DRIFT` | **danger / error** | "Effective drift" | error iconography |
| Manager/device out of sync | `PANORAMA_OUT_OF_SYNC` | **danger / error** | "Out of sync" | error iconography |
| Contradictory / unsafe state | `IDENTITY_TRANSLATION_REQUIRED`, `RELATIONSHIP_INCONSISTENT` | **danger / error**, with an explicit explanation | "Contradictory evidence" | error iconography |
| Stale or incomparable evidence | `PROVENANCE_UNVERIFIED`, `last_known_good` | muted / provenance | "Stale evidence" + timestamp | none |
| Unknown / insufficient | `UNKNOWN`, `INSUFFICIENT_EVIDENCE` | muted | "Insufficient evidence" | none |

**Rules that bind every row:** red is reserved for **actual fault, unsafe drift
or failed state** — never for a difference that is expected. Colour is never the
only carrier: each state carries its text label, and non-colour differentiation
(chip text, member name, iconography where permitted) must make the state
readable in high contrast and to a colour-blind operator. Unification is of
**semantic meaning across modules**, not of palette: Inventory does not simply
copy Configuration's blue.

**Implementation is not in this movement.** No CSS or runtime change is made
here; the later vocabulary/presentation movement (`M3`) owns it.

---

## 6. The shared Device Workspace

### 6.1 The problem it solves

Inventory and Configuration currently maintain **two independent fleet lists and
two independent selections** over the same real devices. That is two navigation
models over one estate, and it is exactly the duplication D-NAV12 forbids.

### 6.2 One context, several data authorities

```mermaid
flowchart LR
  SEL["Selected logical entity<br/>(canonical id — one selection)"]
  INV["Inventory<br/>identity · topology · interfaces · routing"]
  CFG["Configuration<br/>current · alignment · policy · history · evidence"]
  REC["Recovery<br/>backup · restore readiness"]
  OPS["Operations<br/>HA readiness · jobs"]
  CMP["Compliance<br/>subject posture · findings"]
  SEL --> INV & CFG & REC & OPS & CMP
```

Each module keeps its **own payload builder, its own vocabulary and its own
authority**. They share *which subject is selected* and nothing else. Inventory
does not become the source of configuration truth, and Configuration does not
become an inventory (`AGENTS.md` engineering laws).

### 6.3 Selection mechanism — recommendation

| Option | Assessment |
| --- | --- |
| Persistent in-memory context only | loses selection on reload; no shareable state |
| **Hash route carrying module + entity** (e.g. `#configuration/<entity>`), plus `localStorage` for last selection | **RECOMMENDED.** Extends the existing derived-route model (D-NAV8), keeps the report working from `file://`, needs no server, and is deep-linkable within one document |
| Query parameters | the exported report is opened from disk; query strings are awkward and are stripped by some viewers |
| Server-side session | console-only; breaks report parity; adds state the report cannot have |

**Recommendation:** extend the hash route to `#<module>[/<entity-id>]`, with an
unknown or unresolvable entity segment falling back to "no selection" rather
than an error. Backwards compatible: a bare `#configuration` keeps working.
**Not implemented in this movement.**

### 6.4 Tab allocation — global vs entity

| Tab / view | Global root | Entity tab | Entity types | Notes |
| --- | --- | --- | --- | --- |
| Overview | yes (fleet) | yes (subject summary) | all | Different data authority, same name |
| Network / Inventory | yes (`Devices → Inventory`) | yes | all | Interfaces + routing live here |
| Configuration | yes (fleet posture) | yes | all | Split is `PO-NAV-3` |
| Alignment | no | yes | all with expected-intent evidence | Stays inside Configuration |
| Policy & Objects | no | yes | all | Device-scoped only |
| History | no | yes | all | Config history |
| Evidence | no | yes | all | Provenance/coverage |
| Compliance | yes (fleet posture) | yes (subject) | all | Already fleet + subject shaped |
| Recovery / Backups | RESERVED root | yes | all | Root on `PO-NAV-2` |
| HA / Readiness | yes (`Operations`) | yes — **visible for every entity type** | applies to cluster, VSX cluster, PAN HA pair, VSLS VSID | On a standalone device the tab stays visible and selectable and renders `NOT_APPLICABLE` naming the entity types that support it (§6.5) |
| Jobs | yes (console) | later (`PCP.5`) | all | Entity tab = this subject's runs |
| Diagnostics | no | RESERVED | — | `PCP.8` |
| Sanitized configuration | no | candidate | all | §10, contract required first |

### 6.5 D-NAV13 — device-tab stability (corrected)

Revision 1 left this ambiguous by allowing an inapplicable tab to be "either
omitted or shown". **That ambiguity is closed.**

Where a product/entity workspace surface exists, its **canonical tab position is
stable across managed entity types**. Selected-entity applicability and evidence
state **must not** make a tab flicker in and out.

| Case | Behaviour |
| --- | --- |
| **Structurally non-applicable** (e.g. HA on a standalone firewall) | keep the tab **visible and selectable**; render an explanatory `NOT_APPLICABLE` state; render **no enabled action**; **name which logical entity types support it** |
| **Unsupported / not configured / stale / failed / insufficient evidence** | keep the tab **visible and selectable**; show the **exact state and its reason**; expose only actions backed and permitted by a real contract |
| **No product surface in this build/shell** (P1 failure) | **omit the tab** — this is the only omission case |

This is what makes the Product Owner's requirement operable: **not every
capability need be active on every device**, and an operator's map of the
product must not rearrange itself when they select a different one.

---

## 7. Product-surface eligibility vs entity applicability vs evidence vs authorization

**This section is the correction the review exists for.** The prototype had one
predicate. There are four, and they are answered by different owners.

### 7.1 P1 — Product-surface eligibility *(decides Layer 1 visibility)*

A navigation entry is **eligible** when all three hold:

1. a **declared, shipped read or action contract** exists (a payload builder, an
   API route, a job type in the closed registry — not a plan, not a `<section>`);
2. **this shell is permitted to expose it** (`CON.0` §3/§6 — the report is
   action-free by contract);
3. the **UI surface is actually present** in this shell — the DOM check, kept as
   a last-mile integrity guard so a half-shipped shell fails visibly rather than
   rendering a dead entry.

**Only P1 decides whether a root or a global module is rendered** (D-NAV11).
P1 is a property of the *build*, not of the operator's current selection.

### 7.2 P2 — Selected-entity applicability *(decides content, never root visibility)*

Does this function apply to the selected logical entity **type**? A standalone
firewall has no HA readiness. A PAN device has no VSID. This is a **type**
question, answered from the entity model in §5.2 — not from evidence.

### 7.3 P3 — Capability / evidence state *(decides what the view says)*

Derived from `PCP.0` §8's capability projection plus existing evidence states:
is the capability supported for this vendor/platform, is it configured, is
evidence current, stale, missing, failed or insufficient?

Two concepts this layer needs that no existing state covers. **`PO-NAV-7`
approved the concepts and corrected their ownership**: revision 1 proposed them
as undifferentiated global canonical states, which would have put a
schedule-policy fact into the job lifecycle and a registry-reconciliation fact
into device capability. Both names stay **provisional** until the
capability-state vocabulary movement (`M3`) examines the existing schemas and
their owners.

| Concept (name provisional) | What it expresses | Owning domain — corrected | Explicitly **not** |
| --- | --- | --- | --- |
| *"capability policy disabled"* (working name `POLICY_DISABLED`) | this capability, on this device, is intentionally off — e.g. its schedule was disabled without disabling the device | the **schedule / capability-policy contract** | **not** a job lifecycle state; must **not** join `queued`/`running`/`succeeded`/`failed`/`blocked`/`skipped` |
| *"not enrolled"* (working name `NOT_ENROLLED`) | an entity present in evidence but absent from the Device Registry, or the reverse | the **registry / evidence reconciliation projection** | **not** a generic device capability state |

Neither is a global canonical state, and **no payload contract changes in this
movement.** `M3` owns the final names, owners and schemas.

### 7.4 P4 — Authorization *(future; absent, not permissive)*

`navigationAuthorizationContext()` returns `model: "none"`. There is no role,
permission, scope, claim or tenant check anywhere in the navigation path, and
there is **no "hidden because you lack access" state**, because there is nothing
to grant access. When `DEPLOY.1A` ships OIDC/RBAC, P4 becomes an **additional
conjunct** on actions and, where the PO decides, on entries — a `NAV.2`
amendment, never a silent edit, and never by reusing P1 as a permission proxy.

### 7.5 The rule that ties them together

> **A global product module never disappears because the selected device does
> not support or use that capability.**

P1 governs existence. P2/P3 govern what the module *says*. P4 governs what may
be *done*. Concretely:

- Recovery stays a product module while the selected device reports
  `NOT_CONFIGURED` or `UNSUPPORTED`.
- Configuration stays globally reachable while one device's evidence is stale or
  missing.
- Compliance stays a product plane while one subject is `NOT_APPLICABLE` or
  `INSUFFICIENT_EVIDENCE`.
- Operations/Jobs stays reachable while a specific action is `BLOCKED`.

---

## 8. Capability-state presentation matrix

Built entirely from **existing canonical states** in this repository, plus
`CON.0` §9's honest-affordance law; only the two §7.3 rows are proposals.
Revision 1 claimed external corroboration from competitor state vocabularies;
**that claim is withdrawn** (appendix §0.2) and was never needed — no
competitor's capability-state vocabulary is established by any retrievable
source, and this matrix does not rest on one.

| UX semantic | Existing canonical states to reuse | Nav entry | View / tab | Action | Presentation |
| --- | --- | --- | --- | --- | --- |
| **Current / available** | `live`; config artifact `available`; `PASS`; `READY`; job `succeeded`; action `AVAILABLE` | shown | selectable, populated | enabled | normal |
| **Stale** | `last_known_good`; `STALE`; `PROVENANCE_UNVERIFIED` | shown | **selectable**, data shown **with age + provenance** | enabled, with age stated | muted badge + timestamp; never blank |
| **No evidence / not collected** | `no_data`; config `unavailable` | shown | **selectable**, explanatory empty state naming what would produce it | "collect now" enabled where a contract exists | empty state with a reason |
| **Unsupported** | `UNSUPPORTED(reason)` (`PCP.0` §8, not yet implemented); `UNKNOWN_SHELL` | shown | selectable, states the vendor/platform reason | **disabled, visible, reason shown** | explicit "not supported on this platform" |
| **Not applicable** | `NOT_APPLICABLE`; `NOT_A_FAILOVER_UNIT` | shown | tab may be **omitted for that entity type** or shown as N/A — never shown broken | n/a | "does not apply to a standalone device" |
| **Supported but not configured** | `not_configured`; `UNPROTECTED` | shown | selectable, states what configuring it requires | enabled only if a configure path exists, else absent | actionable empty state |
| **Intentionally disabled by policy** | `EXCLUDED` (polling policy); `DISABLED` (registry lifecycle); *proposed* `POLICY_DISABLED` | shown | selectable, names the policy | **disabled, with the policy named** | never silent |
| **Blocked** | job `blocked`; action `BLOCKED`; `RecoveryCollectionBlockedError`; taxonomy refusal | shown | selectable | **disabled, refusing gate named** (`CON.0` §9 — never a bare greyed button) | e.g. "not in the D3 pilot allowlist" |
| **Failed** | job `failed`; `COLLECTION_FAILURE` | shown | selectable, shows the failure and its time | retry offered **as a new typed job**, never by mutating history | error state + last good evidence retained |
| **Skipped (correct outcome)** | job `skipped`; `RecoveryCollectionSkipped` | shown | selectable | enabled | **not an error** — e.g. ledger window already satisfied |
| **Unknown / insufficient** | `UNKNOWN`; `INSUFFICIENT_EVIDENCE` | shown | selectable, says what is missing | disabled if the action needs the missing fact | explicit, never inferred as "fine" |
| **Not enrolled** *(proposed)* | *proposed* `NOT_ENROLLED` | shown | selectable, offers enrollment where permitted | enrollment action per `pcp_console_registry_write_gate` | explicit |

### 8.1 Which states change navigation itself

| Effect | States |
| --- | --- |
| **Omit the view entirely** | only when **no product surface exists** (P1 fails) — never because of P2/P3 |
| **Keep selectable + explanatory empty state** | stale, no evidence, not configured, insufficient, failed, skipped |
| **Disable the action, keep it visible with a reason** | unsupported, blocked, policy-disabled, insufficient-for-this-action |
| **Omit a tab for an entity type** | not applicable (e.g. HA tab on a standalone device) — omitted **consistently for that type**, so it is a stable property of the type, not a flicker |
| **Explicit warning required** | contradictory evidence, effective drift, out-of-sync |
| **Must fail closed** | anything feeding an action decision: unknown/insufficient never reads as permitted; a stale projection never authorizes a job (`OP.2.0` P4/P14) |

---

## 9. Layer 3 — contextual actions

An action's **existence, visibility and enabled state** derive from: a real
backend/action contract; selected-subject applicability; capability evidence;
current operational state; and — once it exists — authorization.
**Navigation location never grants action authority.**

| Action | Home | Today |
| --- | --- | --- |
| **Add device (enroll)** | Devices / entity-list pane header (primary) **and** Administration → Device Management (lifecycle) — one contract, `PO-NAV-1` | **not rendered.** The local-loopback direction is approved, but nothing is implemented: movement `M9` must implement all seventeen conditions in companion §9.1 first |
| Refresh / collect now | selected entity, in the capability's own view | console `CON.2` read-class job types exist; per-device targeting needs `PCP.6` seams |
| Configure / change schedule | the device capability | `PCP.5`; policy editing from a browser is gated by `C-D7` |
| Run backup | Recovery view of the entity | `CON.3` + `RB.3b`; `recovery-cp` unscheduled by `D3` |
| Assess restore readiness | Recovery view | `compute_restore_readiness` exists (class 0) |
| Start typed job | Jobs / the capability view | `CON.2` closed registry only |
| Begin controlled HA action | entity HA tab (`OP.2.D`) | **`CLASS 2` has no member and is unreachable**; nothing renders |

**The prototype's "declare, don't draw" pattern is kept and corrected**: an
action is declared against a domain with its availability and reason, and the
renderer emits only those whose backend contract exists. The correction is that
the reason must state the *actual* gate (an open PO decision) rather than
asserting a decision that has not been made.

---

## 10. Sanitized configuration view

The Product Owner wants an optional "show configuration" view for the selected
entity. Three candidates, with different security weight:

| Candidate | What it is | Assessment |
| --- | --- | --- |
| **(a) Sanitized projected configuration** | the normalized, already-parsed settings the `configuration_ui` payload carries today, rendered as a readable document | **Product candidate now.** No new evidence class, no new retention, no new redaction boundary — it re-presents what both shells already receive |
| **(b) Redacted source-view representation** | a text-shaped rendering closer to device output, produced by an explicit projection + the existing redaction registry | **Candidate, contract required first.** Touches `AGENTS.md` raw-evidence law (do not persist raw output for convenience), needs a defined projection, redaction proof, retention rule and support-bundle exclusion. Its own movement and security review |
| **(c) Privileged raw configuration retrieval** | unprojected device output | **NOT AUTHORIZED HERE.** A separate future security decision requiring explicit authorization, audit, data-egress and retention rules. This navigation review does not and cannot grant it |

**Non-negotiable for (a) and (b):** never expose credential payloads, private
keys, tokens, password hashes or reversible secrets, secret-bearing raw blocks,
unredacted support-sensitive values, or command output that has not passed an
approved projection/redaction boundary.

**Report vs console:** (a) may appear in both if it carries no new sensitive
field. (b) is **console-only** until its contract says otherwise — the exported
report travels.

---

## 11. Preservation matrix

Every valuable behaviour the current UI has, and what happens to it. "Preserved"
means **behaviourally unchanged by `NAV.1` as prototyped**.

| Behaviour | Current owner | Target global domain | Target entity context | Status | Acceptance criterion |
| --- | --- | --- | --- | --- | --- |
| Cluster interface matrix (`interface \| VIP \| member… \| network`) | `inventory_ui.js` | Devices | Inventory tab | **Preserved** | Matrix renders for a ClusterXL/VSX entity with VIP column when VIPs exist |
| Route comparison modes (`Logical \| member \| Diff only`) | `inventory_ui.js` | Devices | Inventory tab | **Preserved** | All three modes selectable on a divergent multi-member entity |
| Explicit member-scope column + `Shared`/member chips | `inventory_ui.js` (`memberScope`, `.scope-chip`) | Devices | Inventory tab | **Preserved** | Chips keep an explicit text label for every state (§5.4) |
| **Pale yellow/gold member-difference emphasis** | `inventory_ui.js`, `.difference-row` / `.scope-chip.diff` | Devices | Inventory tab | **Preserved — the Product Owner values it** | `MEMBER_SPECIFIC` may keep the pale emphasis, must carry "Expected member difference"/"Member-specific", and must carry **no** warning icon or failure wording; red stays reserved for fault (§5.4, `PO-NAV-6`) |
| Interface/route divergence badges | `inventory_ui.js` | Devices | entity header | **Preserved** | Badge present when divergence exists |
| Expandable VSX hierarchy under its parent | `inventory_ui.js` | Devices | entity tree | **Preserved; strengthened** | VS nested under the VSX parent (externally corroborated) |
| Compact ClusterXL/VSX/failover hierarchy | `inventory_ui.js`, `failover_readiness_ui.js` | Devices / Operations | entity tree | **Preserved** | Hierarchy renders without a second identity model |
| Collapsible device details | `configuration_ui.js` (`configHeaderToggle`) | Configuration | entity header | **Preserved** | Expand/collapse persists per viewer |
| Device facts (vendor, model, software, mgmt endpoint, serial, HA role, VSYS/VS, ClusterXL, config freshness, source) | `configuration_ui.js` | Devices/Configuration | entity header | **Preserved** | All facts still rendered, freshness included |
| Global search + vendor/device filtering | topbar + module sidebars | Devices | entity list | **Preserved; unify later** | Search still filters; a single entity search is a later movement |
| Left context pane + main workspace | Inventory, Configuration, Compliance | all device-centric domains | workspace shell | **Preserved** | Pane + workspace retained under the rail (three-column shape) |
| Current/stale/failure provenance | `snapshot.py` → every module | all | everywhere | **Preserved** | `data_state` still surfaced verbatim |
| Configuration alignment classifications | `configuration_ui.js`, `app_core.js` `statusTone()` | Configuration | Alignment tab | **Preserved; semantics become shared** | Both planes give one meaning per treatment — **semantic** unification, not palette copying (§5.4) |
| Compliance fleet + subject views, framework filter, explain panels | `compliance_ui.js` | Compliance | Compliance tab | **Preserved** | Unchanged |
| HA readiness verdicts and check tables | `failover_readiness_ui.js` | Operations | HA tab | **Preserved** | `SAFE_TO_FAILOVER` remains unreachable |
| Console job types with `BLOCKED` + reason | `console_actions.js` | Operations → Jobs | Jobs tab later | **Preserved** | Blocked types still render their refusing class |
| Dark theme + theme toggle | `app_bootstrap.js` | — | — | **Preserved** | Toggle works in both shells |
| Report/console visual parity | both shells | — | — | **Preserved** | One IA, differing action availability only (§12) |
| Exported report is action-free | `html_export.py` | — | — | **Preserved** | No form, no submit, no fetch in the composed script |
| Collapsible left rail | `navigation_ui.js` (`NAV.1`) | — | — | **New, prototyped** | Identical entry set collapsed and expanded |
| Device tab strip driven by the model | `navigation_ui.js` (`NAV.1`) | — | all | **New, prototyped** | Tabs render in canonical order |
| Per-device schedules | — | Operations / entity capability | capability view | **Deferred** (`PCP.5`) | Not rendered until definitions exist |
| Entity workspace with one shared selection | — | Devices | workspace | **Deferred** (`PCP.4`) | Not implemented in this movement |

---

## 12. Shell parity — exported report vs Operator Console

One product IA, shared components. The shells differ in **action availability
and data liveness**, not in how the product is organised (`CON.0` §6).

| Aspect | Exported static report | Operator Console | Rule |
| --- | --- | --- | --- |
| Navigation IA | identical root/domain model | identical | one model, one source (D-NAV7) |
| Rail, groups, collapse, routes | identical | identical | shared component |
| Evidence projections | inline payloads | `GET /api/payloads` | same builders, same shapes (`CON.0` §6 parity invariant) |
| Job submission | **absent** | typed intent, closed registry | never a disabled control in the report — absent |
| Credential / trust selection | **absent** | future, references only | never in the report |
| Enrollment | **absent** | gated (`pcp_console_registry_write_gate`) | never in the report |
| Live refresh | none (snapshot) | `/api/payloads`, ≥30 s opt-in | report must work from `file://` |
| Jobs module | no panel → no entry | panel → entry under Operations | P1 in action |
| **Read-only job/execution history** | **approved in principle, ship-triggered** (`PO-NAV-4`) | yes | "no Jobs *panel* in the report" ≠ "no job *evidence* may ever be exported" |
| Deep links | within-document hash only | same, plus live state | report has no server to resolve links against |

**`PO-NAV-4` — APPROVED IN PRINCIPLE, WITH A SHIP TRIGGER.** A future exported
report **may** carry a sanitized, identity-lean, read-only job/execution
evidence projection **once `PCP.5`'s job-record/export schema defines its exact
fields**. This permits a future evidence projection; it does **not** create a
Jobs module in the report today and does **not** authorize a schema now.

The projection must contain **none** of:

- an action affordance of any kind;
- live submission;
- a credential or trust reference;
- a management endpoint, unless separately authorized for export;
- raw device output;
- backup or recovery bytes;
- secret-bearing failure text.

`CON.0` §7.8 (job records are identity-lean and never enter the support bundle)
is unchanged; an exported report is a different artifact from the support bundle
and its field list is `PCP.5`'s to define.

---

## 13. Responsive, accessibility and visual contract

Requirements, not implementation notes. Where the prototype already satisfies
one, it is marked ✅ (verified in a real browser this session).

| Requirement | Contract | Prototype |
| --- | --- | --- |
| Expanded rail | labelled entries, grouped, keyboard reachable | ✅ |
| Collapsed icon rail | identical entry set; every entry keeps an accessible name | ✅ (name via `title`; §13.1 strengthens this) |
| Independent scrolling | rail, context pane and workspace scroll independently; the page body never scrolls horizontally | ✅ rail/workspace; context pane pre-existing |
| Route + selection preserved | route survives reload; entity selection survives module switches | ✅ route; entity selection is `PCP.4` |
| `aria-current` on the active entry | required | ✅ |
| Group expanded state exposed | `aria-expanded` on group toggles | ✅ |
| Focus management after navigation | focus moves to the activated panel's heading, not lost to `<body>` | ⚠️ **gap** — specified here, not implemented |
| Keyboard navigation | rail entries reachable and operable by keyboard in DOM order | ✅ (native buttons) |
| Visible focus indicator | every rail control shows a focus ring | ✅ (`:focus-visible`) |
| Accessible name in icon-only mode | a real accessible name, not a `title` alone | ⚠️ **gap** — `title` is not reliably announced; needs `aria-label` or visually-hidden text |
| Screen-reader reading order | rail before workspace; groups announced as groups | ⚠️ **partial** — list semantics present; group labelling to strengthen |
| Narrow viewport | rail condenses to the icon rail; nothing is removed | ✅ |
| Data tables never unusably narrow | wide tables scroll inside their own container | ✅ (`.table-container`) |
| Horizontal overflow for member comparison | cluster/member matrices scroll horizontally **within the table container**, never the page | ✅ |
| Long device / cluster / VS names | truncate with ellipsis, full value available on hover/focus and to assistive tech | ✅ rail; entity headers to verify |
| Empty / loading / error states | every view has all three, in words | **partial** — existing empty states good; loading/error states are a console concern |
| Dark theme | first-class, both shells | ✅ |
| Colour-independent state | every state carries text; never colour alone | ⚠️ **partial** — §5.4 fixes the amber overload |
| High contrast | state chips readable at high contrast; no state conveyed by hue alone | to verify |
| `prefers-reduced-motion` | rail/accordion transitions suppressed when requested | ⚠️ **gap** — CSS transitions are unconditional today |
| Animation discipline | restrained, interruptible, non-authoritative: may support rail collapse, accordions, state changes and progress; must never obscure state or delay operational work | contract |

### 13.1 Named accessibility gaps — and which gate they block

1. **Icon-only accessible names** — replace reliance on `title` with an
   explicit accessible name.
2. **`prefers-reduced-motion`** — guard the rail/accordion transitions.
3. **Focus management** — move focus to the activated workspace heading.
4. **Labelled group semantics** — associate each group's children with its
   label for assistive technology.

**Gate correction.** Revision 1 said these "must close before any freeze". That
conflated two different gates:

| Gate | Requirement |
| --- | --- |
| **Architecture freeze** | the required behaviours and their acceptance criteria are **specified** — which §13 and this list do. Closing the gaps is not a precondition for freezing a sufficiently precise contract |
| **Implementation merge / release** | all four gaps **pass**. The `NAV.1` prototype may not merge or be considered implementation-complete until then |

Movement **`M2`** owns the implementation. It is deliberately **not** done in
this movement.

---

## 14. Project Plan

The Project Plan page is valuable **development** visibility and is not
obviously part of a long-term operator product.

**Decided by `PO-NAV-5`:**

| Question | Position |
| --- | --- |
| Today | **Remains visible under Administration** in the current local development product. It is genuinely useful and honest — it reports the repository's own state |
| Direction | Becomes **administration/developer-only** once real OIDC/RBAC exists |
| Gate | **Do not simulate that restriction before a real authorization model exists** — a simulated role check is exactly the fake RBAC D-NAV9 forbids |
| Home | Administration. A "Developer" group is a candidate once there is more than one such surface |
| Redesign | A **separate movement**; not this one |

**Future design candidates, recorded and not implemented:** clearer
milestone/phase hierarchy; board and timeline views; blocker/dependency
visualization; progress semantics that never imply a calendar ETA; restrained
animation; direct links from a build to its evidence and `SESSION CLOSE`
history. **This movement does not redesign the Project Plan UI.**

---

## 15. Navigation security review

Checked against Part M's list. Findings, not assurances.

| Property | Status in this frozen contract |
| --- | --- |
| Navigation never grants mutation authority | **Held.** §9 — action authority derives from contract + applicability + evidence + (future) authorization. Nothing is authorized by being reachable |
| DOM presence is not authorization | **Corrected.** P1 is a *build* fact and explicitly not a permission (§7.1, §7.4) |
| No credential payload in browser, registry, storage, logs or bundles | **Held.** Navigation carries no credential; profile references only (companion contract §10) |
| No device I/O in a request handler | **Held** — navigation performs no I/O; the runtime rule is the companion contract §7 |
| No vendor identity inferred without positive evidence | **Held.** The workspace renders backend-derived identity; it computes none (§5.2) |
| No device targeting by fleet-wide execution + post-filter | **Held** — restated from `PCP.0` §9; navigation must never offer a per-device action a collector cannot target (companion contract §8) |
| `CLASS 2` / `OP.2` authorization, readiness and locking unweakened | **Held.** `SAFE_TO_FAILOVER` stays unreachable; VSID is never a lock subject; no `CLASS 2` entry renders |
| No raw secret-bearing configuration exposed | **Held.** §10 authorizes (a) only, defers (b), refuses (c) |
| A local pilot exemption never becomes production authorization | **Held.** Recorded as the companion contract's central risk (§11 there) |
| No second console, registry, readiness engine or identity authority | **Held.** §5.2, §6.2 — one console, one registry, one readiness engine |
| A stale UI state never authorizes a job | **Held.** §8.1 fail-closed row; `OP.2.0` P4/P14 preserved |
| Hidden navigation is never a security boundary | **Held.** §7.4 — hiding is presentation; refusal happens server-side |

**Actions that remain blocked, and their exact gates**

| Blocked action | Gate |
| --- | --- |
| Any browser enrollment write | Direction approved (`PO-NAV-1`, companion §9) but **nothing is implemented**: blocked until movement `M9` implements every condition in companion §9. Production/server exposure stays blocked on `DEPLOY.1A` |
| Inventory-exclusion writes from a UI | `inventory_exclusions_management_ui_backend` → `DEPLOY.1A` |
| Scheduler policy editing from a browser | `C-D7` |
| `operational-write` (class 1) from the console | `CON.3` + `RB.3b` real-env + `C-D4`/`C-D6` |
| Any `CLASS 2` action | no taxonomy member; `DenyAllAuthorizer`; `FAILOVER_ENGINE_ARCHITECTURE.md` §10 |
| Console on a non-loopback interface | `C-D5` → `DEPLOY.1` |
| Role/permission-based hiding | `DEPLOY.1A` OIDC/RBAC; `NAV.2` amendment. Simulating it earlier is forbidden (`PO-NAV-5`, D-NAV9) |
| Privileged raw configuration view | separate security decision (§10c) |

---

## 16. Frozen Product Owner decisions

Approved by the Product Owner and **frozen**. They are not open questions and
are not restated as such anywhere in this document.

| id | Decision | Where it now lives |
| --- | --- | --- |
| **`PO-NAV-1`** | **Enrollment location — APPROVED.** "Add Device" is never a navigation root. Primary operator affordance in the **Devices / entity-list pane header**; **Administration → Device Management** is the lifecycle home (list, disable, re-verify, later profile references). Both entry points invoke **one enrollment contract**, no duplicate implementation. **Configuration does not own device lifecycle.** A local loopback console may eventually render enrollment before `DEPLOY.1A` under the companion contract §9 conditions; **production/server exposure stays blocked on `DEPLOY.1A` OIDC/RBAC** | D-NAV5; §9; companion §8.3, §9 |
| **`PO-NAV-2`** | **Recovery — APPROVED.** A **reserved future global root**, not rendered today. Promote only when a real **fleet-level** restore-readiness and/or recovery-store surface ships under its `RB`/`CON` contract. Device/entity Recovery stays a contextual workspace tab. Once the global root ships it **remains visible** whether the selected entity is protected, unsupported or not configured | §4.1, §4.3, §6.4 |
| **`PO-NAV-3`** | **Configuration — APPROVED.** The global domain keeps fleet-level work: configuration posture, alignment/drift overview, cross-device search and filtering, configuration-change and evidence-coverage summaries, and fleet comparison workflows where a real contract exists. Device-level configuration, alignment, policy/object, history, evidence and sanitized-configuration views use the **shared selected-entity workspace**. **No duplicated data authority**; a global view may **deep-link** into the selected entity's Configuration context | §4.1, §6.2, §6.4 |
| **`PO-NAV-4`** | **Exported execution history — APPROVED IN PRINCIPLE, ship-triggered** at `PCP.5`'s job-record/export schema, with a hard exclusion list. Creates no Jobs module in the report today and authorizes no schema now | §12 |
| **`PO-NAV-5`** | **Project Plan — APPROVED.** Remains visible under Administration in the current local development product. Becomes administration/developer-only **once real OIDC/RBAC exists**. **Do not simulate that restriction** beforehand. Its dashboard redesign stays a separate movement | §14 |
| **`PO-NAV-6`** | **Difference presentation — DECIDED (not the binary option offered).** The pale yellow/gold comparison emphasis is **preserved** because it makes member differences easy to notice; colour stops being the only carrier and stops implying fault. Full contract, including the label and no-warning-iconography rules for `MEMBER_SPECIFIC` and the reservation of red for actual fault | §5.4, D-NAV14, §11 |
| **`PO-NAV-7`** | **Proposed states — concepts approved, ownership corrected.** They are **not** added as undifferentiated global canonical states. "Capability policy disabled" belongs to the **schedule/capability-policy contract** and must never join the job lifecycle vocabulary; "not enrolled" belongs to the **registry/evidence reconciliation projection** and is not a generic capability state. Names stay provisional until `M3` | §7.3 |
| **`PO-NAV-8`** | **Root baseline — APPROVED.** The six current roots (Overview, Devices, Configuration, Operations, Compliance, Administration) are the accepted baseline. **Recovery is a reserved seventh** future root. **Jobs remains a child of Operations**, and the automatic promotion promise is **removed**: the mature default is Operations with `Jobs`, `Schedules`, `Queue`, `History` and future typed automation definitions. A root promotion is **not pre-approved** and would need a new PO-reviewed IA amendment | §4.1, §4.3 |

### 16.1 Deferred detail — implementation-contract work inside the frozen direction

These are **not** unresolved architecture. The direction is frozen; a named
later movement owns the exact schema, names or mechanics. Runtime, storage,
enrollment and trust equivalents live in the companion contract §13.

| Deferred detail | Owning movement / contract |
| --- | --- |
| The identity-lean **field list** for exported job history that `PO-NAV-4` is ship-triggered on | `PCP.5` job-record/export schema |
| **Final names, owners and schemas** for the two `PO-NAV-7` concepts, after inventorying existing vocabularies | movement `M3` |
| The four **accessibility implementations** in §13.1 | movement `M2` — blocks implementation merge, not this freeze |

Genuinely separate future decisions, outside this contract:

| Question | Gate |
| --- | --- |
| **Privileged raw configuration access** (§10 option c) | its own security decision — never granted by a navigation contract |
| **Future Jobs root promotion** | not pre-approved; reopen **only** on later evidence about information density and operator workflow, as a new PO-reviewed IA amendment |
| **Authorization-driven visibility** (P4 as a conjunct on entries, not only actions) | `DEPLOY.1A`; a `NAV.2` amendment |

---

## 17. Non-goals of this movement

Freezing this contract changes **no product behaviour**. It authorizes no UI
change, no accessibility implementation (`M2`), no entity workspace, no
per-device schedules, no enrollment path or route, no SQLite or storage change,
no capability payload state, no RBAC/OIDC, no payload builder change, no
collector targeting, no device contact, no new action class, no change to what
any module renders inside its own panel, and no repair of the `PCP.1` uuid
tests. **No merge and no pull request** follow from it: `M1`…`M14` remain
separately authorized movements, and the `NAV.1` prototype is not
implementation-complete until `M2` closes §13.1.

---

## 18. Relationship to the companion contract

`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` (**FROZEN**, same
date and same reviewed head) owns the runtime half of the same product
picture: the long-running local control plane, storage
and the SQLite question, background device-targeted jobs, UI-first enrollment,
credential/trust references, the movement sequence and the project-state
correction. This document owns navigation, grouping, shell visibility,
progressive population, selected-context navigation, accessibility, and the
relationship between navigation and product capability state. Where they meet —
enrollment's location, the Jobs surface, schedules, capability states — this
document states the *presentation* contract and the companion states the
*runtime* contract.

---

## 19. Frozen acceptance criteria

Testable statements of the frozen architecture. They are deliberately
**implementation-agnostic**: this freeze writes no test and names no selector.
A later movement implements each against the surfaces it ships, and a criterion
that cannot yet be exercised (because its surface does not exist) is **not**
waived — it becomes that movement's own acceptance criterion.

### 19.1 Product navigation

| id | Criterion |
| --- | --- |
| `AC-NAV-1` | The rail renders exactly the six frozen roots, in order: **Overview, Devices, Configuration, Operations, Compliance, Administration** (§4.2) |
| `AC-NAV-2` | **Recovery renders nowhere** until a real fleet-level restore-readiness and/or recovery-store surface ships under its `RB`/`CON` contract. No reserved domain is drawn as a placeholder |
| `AC-NAV-3` | **Jobs renders under Operations**, never as a root. Adding `Schedules`, `Queue` or `History` under Operations does not promote it |
| `AC-NAV-4` | **Root-set stability**: the rendered root set is a function of product-surface eligibility (P1) only. Selecting a different entity, a stale payload, an empty payload or a failed collection changes **no** root |
| `AC-NAV-5` | Every rendered entry points at a surface this shell actually ships; **no entry is rendered disabled, greyed or "coming soon"** (D-NAV6b) |
| `AC-NAV-6` | Every pre-existing route resolves, and the valid-module set is **derived** from the one navigation model, not re-listed (D-NAV8) |
| `AC-NAV-7` | Collapsing the rail changes the rendered entry set **not at all** — density only, never availability or authority (D-NAV2) |
| `AC-NAV-8` | Nothing in the navigation path reads a role, permission, scope, claim or tenant while no authorization model exists; there is **no "hidden because you lack access"** state (D-NAV9) |

### 19.2 Entity workspace and capability state

| id | Criterion |
| --- | --- |
| `AC-WS-1` | **One selected logical-entity context** is shared across modules; each module retains its **own independent data authority** over that subject (D-NAV12, §6.2) |
| `AC-WS-2` | The workspace **computes no identity, pairing, topology or readiness**. It selects an existing canonical id and renders backend-derived relationships — no second identity authority (§5.2) |
| `AC-WS-3` | **Logical-entity-first rendering**: a ClusterXL cluster, a VSX host/cluster and a PAN HA pair each select as **one object** with members nested beneath; a member is a drilldown, never the default representation |
| `AC-WS-4` | **VSX/VS hierarchy**: a virtual system renders as a child of its VSX parent, never as an unrelated peer device |
| `AC-WS-5` | Where a pair/cluster relationship is **not established** (e.g. PAN `B₂`), the workspace shows what it can prove and states the relationship is `UNKNOWN` — it draws no confident pair |
| `AC-WS-6` | **Shared vs member-specific**: a value semantically shared across the logical entity renders **once**; a value that legitimately differs per member renders **side by side** or in an explicit member comparison (§5.3) |
| `AC-WS-7` | **Device-tab stability**: a tab's canonical position is stable across entity types and never flickers with selection or evidence. A structurally inapplicable tab stays **visible and selectable**, renders `NOT_APPLICABLE`, names the entity types that support it, and offers **no enabled action** (§6.5, D-NAV13) |
| `AC-WS-8` | A tab is **omitted only** when its product surface does not exist in that build/shell (a P1 failure) — never because of entity applicability or evidence state |
| `AC-WS-9` | **`UNSUPPORTED`, `NOT_CONFIGURED`, stale, `FAILED` and `INSUFFICIENT_EVIDENCE` keep their view selectable** and explain themselves in words; the product surface is never removed to express them (§8, §8.1) |
| `AC-WS-10` | A **global product module never disappears** because the selected device lacks or does not use that capability (§7.5) |
| `AC-WS-11` | Anything feeding an action decision **fails closed**: unknown/insufficient never reads as permitted, and a stale projection never authorizes a job |

### 19.3 Difference presentation (`PO-NAV-6`)

| id | Criterion |
| --- | --- |
| `AC-DIF-1` | An expected `MEMBER_SPECIFIC` value **may keep the pale yellow/gold row emphasis** in member-comparison views |
| `AC-DIF-2` | It **must** carry an explicit textual label — "Expected member difference" or "Member-specific" |
| `AC-DIF-3` | It **must not** carry a warning or failure icon, or failure wording |
| `AC-DIF-4` | `LOCAL_OVERRIDE` uses a **stronger attention** treatment with an explicit "Local override" label |
| `AC-DIF-5` | Unclassified `DIFFERENCE_OBSERVED` uses attention/warning semantics |
| `AC-DIF-6` | `EFFECTIVE_DRIFT`, contradictory/unsafe state and out-of-sync failures use **danger/error** semantics |
| `AC-DIF-7` | Stale or incomparable evidence uses **muted/provenance** semantics with its timestamp |
| `AC-DIF-8` | **Red is reserved** for actual fault, unsafe drift or failed state |
| `AC-DIF-9` | **Colour is never the only carrier**: every state is readable from its text, and in high contrast, without hue |

### 19.4 Shell boundary and accessibility

| id | Criterion |
| --- | --- |
| `AC-SH-1` | The exported report contains **no action affordance**: no form, no submit control, no job submission, no credential or trust selection, no enrollment. Absent, never disabled |
| `AC-SH-2` | The console is the **only** interactive shell. No second console exists |
| `AC-SH-3` | Both shells render the **same information architecture** from one model; they differ only in action availability and data liveness |
| `AC-SH-4` | A future exported job-history projection carries none of: an action affordance, live submission, a credential/trust reference, a management endpoint (unless separately authorized), raw device output, backup/recovery bytes, secret-bearing failure text (`PO-NAV-4`) |
| `AC-A11Y-1` | Every rail entry has a real **accessible name**, including in icon-only mode |
| `AC-A11Y-2` | Rail and accordion motion is suppressed under **`prefers-reduced-motion`** |
| `AC-A11Y-3` | Activating a navigation entry **moves focus** to the activated workspace heading |
| `AC-A11Y-4` | Each group's children are **associated with its label** for assistive technology |
| `AC-A11Y-5` | Wide comparison tables scroll **inside their own container**; the page body never scrolls horizontally |

`AC-A11Y-1`…`AC-A11Y-4` are owned by movement **`M2`** and **block
implementation merge** of the `NAV.1` prototype. They do not block this freeze.
