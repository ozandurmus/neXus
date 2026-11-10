# PO decision record - 2026-11-10: lifecycle reads on for the whole estate; Infoblox member-level version read

**Status: RATIFIED - PO DECISION (2026-11-10).** Both questions were asked in the PO session and the answers were
relayed by it to the engineering session, which recorded them here and acted on them.

## 1. The approved lifecycle reads run for every device

Question: "Onaylı okumaları tüm cihazlarda açalım mı?" Answer (verbatim): "Evet, hepsinde aç (Önerilen)". The option
said that the next inventory fills license, contract and Live Patch data for all devices.

- **What is on:** the ten read kinds that are parsed and stored: `cp_cplic_print`, `cp_cplp_list`,
  `cp_spark_show_license`, `asa_show_license_all`, `mc_device_license`, `infoblox_license_gridwide`,
  `pan_license_info`, `pan_support_info`, `fgt_update_info_contract`, `fgt_autoupdate_versions`. Their literals and
  gate rows are those of `LIFECYCLE_READS_GROUP_A_COMMAND_GATE_ENTRIES.md` and
  `LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md`; nothing is added by this decision.
- **Where:** every device, at the end of each inventory run (`UI2_LIFECYCLE_READ_DEVICES=*` on the worker; recorded in
  `deploy/ui2/52-worker-deployment.yaml`). There is no schedule of their own.
- **Rules that stay:** the Palo Alto reads go only to a firewall whose PAN-OS major version, read in the same run, is
  known and below 12, and never to Panorama; a Quantum Spark gets `show license` only; a read that is refused, fails
  or cannot be parsed never changes the inventory result and never erases the last stored answer.
- **Not switched on:** the read kinds that are measured but not stored (`mc_show_licenses`,
  `fmg_fmupdate_dbcontract`, `mc_system_version`, `infoblox_upgradestatus_grid`). Sending them would load the devices
  for nothing.
- **How to stop:** remove the three `UI2_LIFECYCLE_*` entries from the worker and restart it.

## 2. Infoblox: the NIOS version from the member-level entries

Question: "Infoblox'ta grid düzeyindeki kayıt sürüm bilgisini vermedi. Sürümü üye düzeyindeki kayıttan (upgradestatus
type=VNODE) okumayı deneyelim mi? Bu, cihaza gidecek yeni bir okuma isteği." Answer (verbatim): "Evet, VNODE dene
(Önerilen)". The option said that it is measured on one device first.

The literal, its evidence and its gate row are in `LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md`, amendment
B-2026-11-10. It is a measure-first read on the one grid manager and is not part of the fleet-wide set until its
answer has been seen.
