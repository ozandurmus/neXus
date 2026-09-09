# UI 2.0 mockup reference notes (2026-09-09) -- design canvas, 19 artboards

Status: REFERENCE (not a contract). Visual design language decision recorded in `UI2_0_BASELINE_CONTRACT.md` §2 row `VISUAL-DESIGN-LANGUAGE`: **Material Design 3** ("M3 kalacak", Product Owner, 2026-09-09).

Source: claude.ai/code/artifact/1e3d728e-0837-4fa8-a5da-38390232796e, "neXus 2026 Design Refresh".
Full artboard sources (`*.dc.html`, plus `canvas.json` index) are committed under `docs/design/ui2_mockups/` so a session without access to the claude.ai artifact can still read them. The neXus logo files were shared by the Product Owner as an image in chat only; they are NOT in the repository -- ask the Product Owner for them before any logo-bearing screen is built.

## Structure
- page-1 (7 artboards): same Overview screen in 7 competing design languages (Instrument/cool-neutral, Editorial paper, Operator grid/dark console, Tailwind/shadcn, Material Design 3, Carbon/IBM, Bento grid). Exploration only.
- page-2 (4 artboards): "Instrument" direction refined -- dark theme, configuration alignment, design tokens, an early sketch.
- page-3 (7 artboards): **Material Design 3 direction, full product screen set** -- Overview, Devices->Inventory, Configuration->Alignment, Compliance, Operations, Administration, Navigation & components. This is the concrete, most-developed set.

## Content of the M3 (page-3) screens -- ground truth for the baseline directory (B0-8) and B1/REL screens

Left nav rail (all screens): Overview | Devices | Config | Compliance | Operations | Admin -- matches design's shell boundary + left-rail navigation.

**Overview**: posture tiles (Network inventory 42/46 live, Configuration 40/42 evidence, Local overrides 6 intentional, Effective drift 2 unexplained), configuration alignment roll-up donut (Aligned/Member-specific/Local override/Difference observed/Effective drift/Out of sync), a drift table (device/setting/expected/effective/state), Evidence trust tile (primary evidence, identity verified, fresh-this-cycle, TLS peer verification), HA readiness tile ("Readiness is observed. No class 2 action exists in this build."), Compliance coverage tile, Recovery artifacts tile (37/42 protected, "Blocked by policy · outside D3"), Fleet composition by vendor/version.

**Devices -> Inventory**: search/filter by vendor, device list (management server, ClusterXL, cluster member, VSX cluster, virtual system, stale device with "last-known-good" fallback), device detail tabs (Interfaces, Routing, Cluster members, Identity & provenance), an interface table, "Interface and routing evidence read over SSH at 06:41 UTC. Values are observed, never written."

**Configuration -> Alignment**: device list with drift/override counts, per-device tabs (Overview, Current state, Alignment, Policy & objects, History, Evidence, Backup), an alignment table (Setting | Expected · CMA intent | Effective per member | State) with classification tints, footnote "Red is reserved for unexplained drift and failure; expected member differences carry no warning."

**Compliance**: framework filter chips (CIS, ISO 27001, internal baseline), coverage tiles, control-family table with coverage bars, findings list (severity, subjects, control id, linkage to a drift). "A control with no evidence is never counted as passing." "Findings are observations against the assigned framework. Remediation is out of scope for this product."

**Operations**: HA & readiness cards per cluster (Ready/Not ready/Undetermined, member health, sync state, "Authorization: Not evaluated -- readiness not evaluated for this subject"), a job history table (job id, type, target, started, duration, outcome incl. Succeeded/Partial/Blocked) with an explicit **Backup creation job blocked by "Outside the D3 pilot allowlist"**, footnote "Read jobs are class 0. Backup creation is the only class 1 write and runs under its own contract." / "Jobs cannot be submitted from the exported report. Submission is a console capability and is subject to the action taxonomy."

**Administration**: device registry table (device, vendor, transport, credential profile, last contact, enrollment state: Enrolled/Unreachable/Degraded/Draft), "A draft entry is never collected from. Credentials are stored outside the repository and are never written into a report or a support bundle.", collection-scope switches (Inventory collection / Configuration collection / **Backup creation · class 1**, "stays off until a device is inside the pilot allowlist"), runtime info (registry path, evidence store = content-addressed, identity tokens = HMAC rotated).

**Navigation & components**: expanded nav drawer with counts per section, device overflow menu ("Collect now" shown greyed with "console only" label -- **RBAC visible-but-refused pattern, exactly design §5**), enrollment dialog (device name, vendor, transport, credential profile, "Enrolling a device grants read collection only. Backup creation stays off until the device enters the pilot allowlist."), button hierarchy (filled = one primary action per screen, tonal = secondary, outlined = neutral alternative, text = inside cards), a state-vocabulary legend (Aligned/healthy, Member-specific, Local override [intentional], Difference observed [unclassified], Effective drift [fault], Not applicable, Not configured, Stale/provenance) with the rule "Red is reserved for real fault, unsafe drift, out-of-sync and failure. Expected member differences carry no warning icon and no failure wording."

## Why this matters for the frozen baseline/contracts

- Confirms and gives concrete shape to design §5 RBAC (visible-but-refused, never a bare greyed control) and the action-taxonomy class 0/1 split -- this is now UI ground truth, not just contract text.
- "D3 pilot allowlist" blocking backup creation in the mock == UI-OPERATIONAL-RUN-NOW / APPROVAL-MODEL enforcement surface (C2, C5) made visible.
- Administration's device registry + enrollment states (Enrolled/Unreachable/Degraded/Draft) is close to B1-4b's minimal Device/Endpoint/CredentialReference model -- use these states as the starting enum, subject to C1/C4 review.
- Configuration/Alignment screen's classification vocabulary (Aligned, Member-specific, Local override, Difference observed, Effective drift, Out of sync) should be checked against/adopted into the baseline directory's shared screen-state vocabulary (B0-8, UX-D1..D5) rather than re-invented.
- Evidence provenance footnotes ("read over SSH ... values are observed, never written", "Credentials are stored outside the repository") match P-4/vendor-semantics law and RAW-RETENTION direction -- reinforcing, not contradicting, the baseline.
- Compliance and Operations screens are screen-shaped previews of REL-CHECKS and REL-DISCOVERY/REL-BACKUP; not yet contract, but a strong existing-in-substance UX reference for those slices' B0-8 entries and later B1/REL integrate steps.

## Disposition
Reference only; not a freeze. Folded into the B0-8 baseline directory (`UI2_0_BASELINE_DIRECTORY.md`, NXS-LOCAL-0058, PR #172) as UX ground truth for the shared screen-state vocabulary and product objects/navigation, and cited as UX reference for B1 step 9 (device workspace / first read screen) and the REL-* integrate steps. Design-language choice IS made: Material Design 3 (page-3 artboards `M3*.dc.html`). The page-1 explorations and page-2 "Instrument" refinements are historical alternatives, kept for provenance only.
