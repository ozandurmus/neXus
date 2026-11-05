# Release notes — 2026-11-05

Operator-facing notes for the changes since commit `ed068bbb` (the UI night batch of 2026-11-05) plus the
related path-analysis, backup and policy changes that ship with it. Plain English; examples use masked names only
(`FW-TANGO-04`, `CLS-ROMEO-01`, addresses in `192.0.2.x`). Screen locations are given as "Screen → area"; where the
code does not make a label certain, the note says so.

## 1. Device Collect menu

- **What changed:** a device now has one **Collect** menu with four actions: **Inventory** (interfaces, routes,
  platform facts), **Configuration** (running configuration and settings), **Backup** (stores a backup archive) and
  **System logs**. Each entry shows a one-line description.
- **Where:** Devices → the device detail header (e.g. `FW-TANGO-04`).
- **Caveats:**
  - An entry that cannot run is greyed out with the reason shown (enrollment not confirmed yet; vendor has no
    collection for that plane; management server or other unsupported role).
  - Inventory only appears if your role holds the Collect plane; other entries follow your role's permissions.
  - On a cluster, choose the member to collect from using the member pills.

## 2. Collect logs now

- **What changed:** system logs can be collected on demand from the device's Logs panel instead of waiting for the
  schedule. The result is stated plainly: admitted, already running for this device (deduplicated), or refused with
  the reason and, where applicable, when the next window opens.
- **Where:** Devices → device detail → Logs.
- **Caveats:**
  - Only Check Point and Palo Alto gateways are supported; management servers show "Collection disabled for
    management servers" and other vendors show "Logs are not collected for this device".
  - Fleet-wide log collection was added with the same batch; the exact UI entry point for it is UNKNOWN from the
    diffs read and should be confirmed on the running system.
  - Scheduled system-log collection no longer depends on the global scheduling switch (PO decision 2026-11-05,
    manifest setting `UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_INDEPENDENT=true`). Logs are therefore collected on
    schedule even while global scheduling is off.

## 3. Path diagram

- **What changed:** a path result now includes an interactive left-to-right diagram: Source on the left, each branch
  on its own lane (parallel lanes for ECMP), network segments as pills with their masked prefix, hops with masked
  node name, verdict colour and ingress/egress interfaces, Destination on the right. Unknown stops end in a dashed
  edge to a "?" node. Clicking a hop scrolls to and highlights its card in the hop-by-hop list.
- **Where:** Path analysis → a completed path result.
- **Options and caveats:**
  - "Return path" lanes and "Show neighbours" (directly attached segments, greyed) are optional; neighbours are
    capped at 40 with a "N more not shown" note.
  - If the topology cannot be read, only the path's own segments are drawn (fail closed).
  - Any raw-looking address is redacted before display; only documentation-range (`192.0.2.x`) or masked-range
    addresses render.

## 4. Table sorting

- **What changed:** column headers sort with the cycle ascending → descending → default. Sorting on the Device
  registry, and on server-paged tables such as job logs, now orders **all** rows across all pages, not just the page
  on screen. Sorting on server-paged tables is limited to a fixed list of columns. The Last collection column reads
  "oldest first / newest first".
- **Where:** Devices → Registry; Operations → Job logs; other paged tables.
- **Caveats:** an earlier change (61c527e) made client-side lists sort their whole list rather than the page;
  both now behave the same way for the user. A column outside the allow-list is not sortable on server-paged tables.

## 5. Job targets

- **What changed:** each job row now names what it acted on: a device, a cluster or failover unit, a policy source or
  container, a packet-analysis case, or `Fleet (N devices)`. Rows no longer show an unknown device.
- **Where:** Operations → Job logs.
- **Caveat:** a job that truly recorded no target reads `No target recorded`. Names are masked for the aiview role
  (e.g. `CLS-ROMEO-01`).

## 5a. Stuck panels

- **What changed:** System health, Policy changes and the Change feed could stay on "Loading…" forever. Root causes
  were fixed and each panel now has a bounded wait: after about 20 seconds it shows a plain state instead (for
  example "System health could not be read", or "Not computed yet (scheduled computation is off)") with a Retry
  button where relevant.
- **Where:** System health; Policy → Changes; Change feed.
- **Caveat:** "Not computed yet" means scheduled computation is off, not that the system is healthy or failed.

## 6. Backup size alerts

- **What changed:** a new notification type, `backup_size_anomaly` (enabled by default; recipients fall back to the
  global setting), is sent as a daily digest. It covers three alert kinds, each raised once per backup:
  - **size_anomaly:** the backup grew or shrank against that device's own history (unchanged behaviour).
  - **fleet_large:** a standard backup at least 10x its vendor's fleet median and at least 1 GiB (same rule as the
    Large marker on the Backups screen); catches a device that has always been large.
  - **firewall_logs:** a Check Point standard backup whose stored content listing holds at least 100 MiB of firewall
    logs.
- **Where:** Settings → Notifications (a new row); alert content arrives through the normal notification channels.
- **Caveats:** alerts show sizes and a percentage only, never file names. They do not change what backups are kept
  or pruned. The migration is V189.

## 7. Check Point group members resolved from inventory

- **What changed:** group members that the rulebase did not carry were stored as UNRESOLVED (about 99.8% of the
  32,429 unresolved objects in the latest snapshots were group members), which suppressed shadow analysis. They are
  now resolved from the object inventory neXus already collects (same domain first, then the Global domain).
- **Where:** Policy → Check Point rules and groups.
- **Caveats:**
  - No new device command is issued.
  - Inventory older than 48 hours is not used; the member stays UNRESOLVED. An incomplete or unreadable inventory
    also leaves it UNRESOLVED (fail closed).
  - Stored snapshots are not migrated; they update on the next full policy read of each domain.
  - Kill switch: `UI2_POLICY_CP_RESOLVE_FROM_INVENTORY=false`.

## 8. VSX cluster addresses

- **What changed:** on a VSX cluster-member Virtual System, the addresses listed by the cluster interface check are
  now recorded as cluster (virtual) addresses rather than member addresses. Before, one address appeared on both
  members, which confused topology and path analysis.
- **Where:** Inventory → interfaces of a cluster-member Virtual System; Path analysis.
- **Caveat:** stored rows change only on the next inventory collection. Standalone VSX and the physical context are
  unchanged.

## 9. Path pseudonym fixes

- **What changed:** Path analysis queries typed with the masked address shown in the topology (as the aiview role
  does) were often refused as `AMBIGUOUS_PSEUDONYM` or `PSEUDONYM_REFUSED`. In testing, 11 of 20 queries were
  refused. A masked host now resolves to the host it was rendered from, for /23, /25 and /30 segments and for
  addresses under broad summary routes.
- **Where:** Path analysis → query form.
- **Caveats:** two genuinely different real hosts that mask to the same value are still refused as ambiguous. Masking
  of responses did not change, so pseudonyms you already know stay the same. Inventory interface and route bodies
  also gained routing-context, evidence-grade and ECMP/metric facts, with the virtual-router name masked for aiview.

## Not covered / UNKNOWN

- No live-system verification was possible for these notes; they come from the git log, commit messages and diffs.
- Exact menu wording in the running UI, and the fleet log-collection entry point, should be checked once on the live
  system.
- The commit range `ed068bbb..HEAD` contains only the UI batch and documentation. Items 6-9 sit at or just before
  `ed068bbb` and are included at the brief's request.
