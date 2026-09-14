# PO Decision Record — 2026-09-14 G — Configuration collection: the measured forms, one read per host, index view plus encrypted copy

## Status

**FROZEN — PRODUCT OWNER MEASUREMENT APPLIED, 2026-09-14.** Successor to
`PO_DECISION_RECORD_2026_09_14C_INVENTORY_AND_CONFIGURATION_COLLECTION_DESIGN.md`
D-6 and §3 (configuration), not edited in place, under
`PO_DECISION_RECORD_2026_09_13F` §3 (CF-1..CF-3: one read, two outputs;
the backup copy untouched and encrypted with its hash under C7) and
`UI2_0_C7` (FROZEN). The Product Owner ran the configuration reads on both
vendors today (observations: `CP_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md`,
`PAN_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md`, both DRAFT, not
authority) and asked for the configuration service and screen to follow.

## 1. Check Point

- **CG-1.** One read per host: `clish -c 'show configuration'` from the
  Expert landing shell, preceded by `clish -c 'show hostname'`,
  `clish -c 'show version all'`, `clish -c 'cpstat os -f hw_info'` (identity
  refresh; serial and model in the last). No per-virtual-system repetition:
  Gaia `show configuration` is host-level on VSX (measured identical inside
  `vsenv`); 14C D-6's "and per virtual system on VSX" is withdrawn for
  Check Point. The one-Clish-process `set virtual-system` form is dropped
  (it produces no output non-interactively).
- **CG-2. Canonical hash** = SHA-256 over the `set` lines only (the `#`
  header carries a timestamp and changes between reads). The untouched
  bytes carry their own hash and are the backup copy.
- **CG-3. Sanitized view** = the `set` lines with secret-bearing lines
  withheld (keyword list: password, passwd, secret, community, auth-key,
  private-key, pre-shared, psk, credential, token; the count of withheld
  lines is shown) and a section index from the first one or two keywords
  after `set` (interface, ssh server, snmp traps, static-route, bonding
  group, …) with counts. Sizes measured: 11–16 KB, 264–371 lines.

## 2. Palo Alto

- **CG-4.** Per firewall, in this order: `show system info` (identity
  refresh), `type=config&action=show&xpath=/config` (local active, small),
  `<show><config><effective-running/></config></show>` (the configuration
  of record), `<show><config><merged/></config></show>` (device-local
  overlay). All POST, key in header or body, one key per run.
- **CG-5. Streaming.** `effective-running` measured 11.8 MB and stable
  across reads; it is streamed from the transport to the store (compressed,
  encrypted, hashed) and parsed on the stream for the index. Never held as
  one string; never rendered whole in the UI.
- **CG-6. Sanitized view** = a category index: per vsys, each element
  category under `vsys/entry` with its entry count (address, address-group,
  service, rulebase, zone, profiles, …), plus `deviceconfig` and `network`
  subsection counts, plus the `merged` overlay's categories. Secret-bearing
  leaves (keys, passwords, pre-shared keys, certificates) are never part of
  the view.
- **CG-7. Panorama provenance.** Panorama's own `xpath=/config` (measured
  84.8 MB) is streamed and reduced to the assignment index only: device
  serial → template stack, the stack's templates, device groups with their
  parents. The document is not stored. Panorama is never an inventory
  target.

## 2a. Palo Alto local overrides (Product Owner directive, 2026-09-14)

- **CG-7a. Never assume everything is pushed from Panorama.** The
  configuration of record is read from the device (`effective-running`,
  CG-4). Every element there carries provenance: the `src` attribute
  (`tpl` / `template`, `dg` / `device-group`, `shared`, `local`, as the
  earlier product's provenance walk counted them). An element whose source
  is `local` where a Panorama template or device group defines the same
  path is a **local override**.
- **CG-7b. Detection is per device, from the device's own read:** the
  streaming index (CG-6) counts elements per source per category and lists
  the override paths (category and element name, never the value).
- **CG-7c. Cross-check against Panorama.** For every Panorama-managed
  Palo Alto device, the device's effective configuration is compared with
  what Panorama's common templates / template stack and device groups
  define for it (the CG-7 assignment index plus the template and device
  group subtrees for that device, reduced on the stream). Elements present
  locally with `src=local` that Panorama also defines are reported as
  overrides with the Panorama-side definition named (template / device
  group and path).
- **CG-7d. Notification.** Overrides are surfaced through the product's
  notifier (the Notifications surface of the shell; a log line and the
  audit row at minimum until a channel is configured), one notification
  per device per run listing the override paths, and shown on the device's
  configuration view with an "override" marker per element category.
- **CG-7e.** Absence of any `src=local` element is recorded as
  `no_local_override` for the run; the earlier product's semantic-policy
  rule stands: a display-name versus `vsysN` string mismatch is never taken
  as override proof (vsys identity is normalised first).

## 3. Storage and job

- **CG-8.** Job kind `configuration_collect`, capabilities
  `cp_configuration_collect` / `pan_configuration_collect`, admitted only
  against ENROLLED non-disabled devices; the confirm remains the only DRAFT
  exception (14B). One run per device per job.
- **CG-9.** `device_configuration_run(run_id, device_id, job_id,
  collected_at, vendor, read_kind, canonical_hash, raw_hash, raw_bytes,
  artefact_ref, withheld_line_count)` and `device_configuration_index(run_id,
  context, section, entry_count)`; the sanitized Check Point text view is
  stored as a separate artefact (small) referenced from the run; the raw
  copy goes through `C7`'s artefact store (encrypted, hash recorded). A
  `panorama_assignment(run_id, device_serial, template_stack, templates,
  device_groups)` table holds CG-7's index; `device_configuration_override(run_id,
  context, category, element_path, local_source, panorama_source)` holds
  CG-7b/CG-7c's findings.
- **CG-10. Change detection.** Latest run per device is what the screen
  shows; a new run whose canonical hash equals the previous is recorded as
  `unchanged`; a difference marks `changed` and keeps both runs. Diff
  rendering is a later feature.

## 4. Screen

- **CG-11.** The Configuration screen lists devices with last collected
  time, change state and hash; a device opens to the section / category
  index, the withheld-line count, and for Check Point the sanitized text;
  a Collect now button admits the job; a Download backup action is a later
  feature under C7's access rules.

## 5. Gate consequence

The closed command / request set is CG-1 and CG-4; gate rows are authored
per literal, `SIGNED_OFF` on the Product Owner's hardware-run approval of
2026-09-14 (as for inventory), bindings unverified until the first run from
the product.

## 6. Cross-references

- `PO_DECISION_RECORD_2026_09_13F` §3; `PO_DECISION_RECORD_2026_09_14C`
  D-6, §3 — amended; `UI2_0_C7` — the artefact store.
- `CP_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md`,
  `PAN_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md` (DRAFT, not
  authority) — the observations.
