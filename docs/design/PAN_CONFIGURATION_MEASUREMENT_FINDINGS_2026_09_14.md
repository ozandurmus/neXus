# Palo Alto configuration — measurement findings (14C section 3 "Palo Alto, configuration")

## Status

**DRAFT — NOT implementation authority.** Records what the Product Owner
observed on 2026-09-14 running `scripts/measurement/pan_inventory_shape.py
--config` against one Panorama-managed multi-vsys firewall and `--config
--panorama` against its Panorama. Sizes, top-level categories, counts and
hash behaviour only; no content.

## 1. Firewall

- `type=config&action=show&xpath=/config` (active, local): **5 KB**. Top
  level: `devices/entry/deviceconfig`, `network`, `vsys` (with `import`
  only), `mgt-config`, `shared`. On a Panorama-managed firewall the local
  candidate/active tree is nearly empty.
- `<show><config><effective-running/></config></show>`: **11.8 MB**,
  `status=success`. Two consecutive reads returned the **same byte count
  and the same SHA-256** — the read is stable and is the configuration of
  record for backup. Under each of the five `vsys/entry` elements: `address`,
  `address-group`, `application`, `application-filter`, `application-group`,
  `application-status`, `application-tag`, `authentication-object`,
  `device-object`, `display-name`, `dynamic-user-group`, `external-list`,
  `group-mapping`, `import`, `log-settings`, `profile-group`, `profiles`,
  `redistribution-agent`, `region`, `rulebase`, `schedule`, `service`,
  `service-group`, `setting`, `tag`, `threats`, `zone`; plus
  `deviceconfig`, `network`, `mgt-config`, `shared`.
- `<show><config><merged/></config></show>`: **220 KB**; vsys entries carry
  only `display-name`, `group-mapping`, `import`, `log-settings`,
  `redistribution-agent`, `setting`, `zone` — the device-local part.
- Consequences: the backup copy (C7) is `effective-running`, stored
  compressed and encrypted with its hash; the sanitized view is a
  **category index** (per vsys: element category and entry count, and the
  `deviceconfig` / `network` subsections), never the rendered 11.8 MB; the
  `merged` read is the device-local overlay and is kept as a second,
  small view. Responses of this size must be streamed to the store, not
  held as a string.

## 2. Panorama

- Own `xpath=/config`: **84.8 MB**, `status=success`. Counts:
  `template` entries 40, `template-stack` entries 130, `device-group`
  entries 363; 1196 `devices/entry/vsys` occurrences; `panorama`,
  `readonly`, `shared`, `mgt-config` at top level.
- Consequences: the provenance read is streamed and reduced on the fly to
  the assignment index (device serial → template stack, templates in the
  stack, device groups with parents); the full document is not kept unless
  a later record asks for a Panorama backup.
- `show interface all` on Panorama has a different shape (`entry0..entryN`
  with `mgmt_active` / `mgmt_primary` fields) and `show routing route`
  returns `status=error code=17`: Panorama is not a firewall; the inventory
  capability is never pointed at it. `show high-availability state` on
  Panorama has `local-info` / `peer-info` directly under `result` (no
  `group` layer) — a separate parser if Panorama HA is ever shown.

## 3. Nothing further owed for Palo Alto configuration measurement.
