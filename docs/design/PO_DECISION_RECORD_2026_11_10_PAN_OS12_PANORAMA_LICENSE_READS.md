# PO decision record - 2026-11-10: Palo Alto license and support reads for PAN-OS 12 firewalls and Panorama

**Status: RATIFIED - PO DECISION (2026-11-10).** The question was asked in the PO session and the answer was relayed
by it to the engineering session, which recorded it here and acted on it.

## 1. Decision

Question: "PAN-OS 12 firewall'larda ve Panorama'da da lisans okuması açılsın mı?" Answer (verbatim): "Evet, aç".

The two approved Palo Alto lifecycle reads are no longer limited to firewalls below PAN-OS 12. They may also be sent
to a firewall on PAN-OS 12 or later, to a firewall whose version could not be read, and to a Panorama.

## 2. The exact requests

Both are PAN-OS XML API operational requests (`type=op`), sent with the key the inventory run already holds.

| Read | Request literal (`cmd`) | CLI form |
| --- | --- | --- |
| licenses and subscriptions | `<request><license><info/></license></request>` | `request license info` |
| support entitlement | `<request><support><info/></support></request>` | `request support info` |

Nothing else is approved by this decision. `request license fetch`, `request support check`,
`request batch license refresh` and every other `request license ...` form stay on the never-send list of
`LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md`.

## 3. Evidence

- **`request license info`, firewalls and Panorama-managed firewalls, current releases.** Palo Alto Networks, PAN-OS
  CLI Quick Start, "CLI Cheat Sheet: Device Management"
  (docs.paloaltonetworks.com/ngfw/pan-os-cli-quick-start/cli-cheat-sheet-device-management, read 2026-11-10): "Show
  the licenses installed on the device." with the command `request license info`. The page is the vendor's current,
  unversioned NGFW documentation ("Where Can I Use This?": NGFW, managed by PAN-OS or Panorama; no prerequisites).
- **`request support info`, firewalls and Panorama.** Palo Alto Networks knowledge base, "What are the CLI Commands
  to Verify Device and Support License?" (article kA10g000000Cm0BCAS, read 2026-11-10). Environment: "Any Palo Alto
  Firewall" and "Any Panorama". It gives `request license info` (licenses with issue and expiry dates and the expired
  flag) and `request support info` (support level and expiry date). The article names no PAN-OS release, and no page
  written for PAN-OS 12 names `request support info`; whether a PAN-OS 12 device answers it is what the first
  measurement shows. An unsupported request returns an error reply, which is recorded as UNKNOWN.
- **Panorama.** The same article (environment "Any Panorama"), and knowledge base article kA14u000000kFSwCAM
  ("Panorama license capacity changed after upgrading PAN-OS to 10.1.8", environment "Any Panorama"), which runs
  `request license info` on a Panorama to list its licenses. Panorama Administrator's Guide, "Manage Licenses and
  Updates" (docs.paloaltonetworks.com/panorama/administration/manage-licenses-and-updates) is the background.
- **XML API form.** Palo Alto Networks, PAN-OS XML API, "Run Operational Mode Commands (API)"
  (docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/run-operational-mode-commands-api):
  `type=op&cmd=<xml-body>`; download, upgrade and install requests are the asynchronous ones, and these two are
  not among them. The XML bodies are the ones measured on PAN-OS 11.1 on 2026-11-10
  (`LIFECYCLE_READS_MEASUREMENTS_2026_11_10.md`).

## 4. How it is applied

- **Gate rows.** A firewall on PAN-OS 12 uses the existing firewall rows (`pan_lifecycle_license_info`,
  `pan_lifecycle_support_info`, V199). Panorama gets two new rows with the same literals and the platform scope
  `panorama` (`pan_panorama_lifecycle_license_info`, `pan_panorama_lifecycle_support_info`, V202). Details:
  `LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md`, amendment C-2026-11-10.
- **Setting.** The worker setting `UI2_LIFECYCLE_PAN_EXTENDED_DEVICES` (device ids, or `*`; unset = none) names the
  devices outside the first scope that get the reads. It widens nothing by itself: the read kinds and the device must
  still be on in `UI2_LIFECYCLE_READS` and `UI2_LIFECYCLE_READ_DEVICES`.
- **Order.** First one PAN-OS 12 firewall and the Panorama; their answers are compared with the PAN-OS 11.1 format;
  then `*`. Before the setting is changed: no ship, end-to-end test or policy collection may be running.
- **Unchanged.** `show system info` stays retired for firewalls (PO 2026-10-21). The other switched firewall reads
  (`show interface all`, `show session info`) keep their PAN-OS 12 rules. A read that is refused, fails or cannot be
  parsed never changes the inventory result and never erases the last stored answer. Serial, authcode, account,
  contact and link fields of the answers are never stored or logged.
- **How to stop:** remove `UI2_LIFECYCLE_PAN_EXTENDED_DEVICES` from the worker and restart it.
