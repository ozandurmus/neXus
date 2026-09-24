# Check Point Provider-1 (MDS): mds_backup over SSH

status: planned · target: 

Backbox reference (trail 34410129, 2026-09-22): 'Check Point -> Provider-1 -> R81.1 and above -> MDS Backup - No Logs (SSH)' -- Expert shell, TMOUT raised, blades_summary / fwm mds ver / mdsstat / cplic print -x / netstat -rn as evidence reads, clish 'lock database override' + 'save configuration gaia_config.txt', then '$CPMDIR/scripts/mds_backup -b -l -d /var/log/BackBox' (no logs) and the archive fetched from the device. Backbox also tars /etc/shadow into its bundle -- the product must NOT: secret material is withheld (Raw-evidence law). The management server role is currently refused at admission (MANAGEMENT_SERVER_UNGATED); this item is what gates it. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.

2026-09-22: measurement record written from the Backbox trail -- docs/design/VENDOR_BACKUP_MEASUREMENTS_2026_09_22.md (transport, sequence, artefact, secret risk, neXus fit). Prerequisites before this vendor's contract: Add-device vendor value + endpoint kind, a confirm (identity) capability, the vendor CHECK constraints (V16/V17 allow only check_point/palo_alto), and for HTTPS vendors the generic HTTPS client. Proposed order in the record.

2026-09-22 PO tried to add an MDS through Add device: it enrolls (read collection only) and reports 'Peer not confirmed: ADDRESS_MISSING' -- the first-contact peer follow found no peer address, which for a single MDS is the expected result, but the wording reads as an error. Provider-1/MDS needs its own first-contact and inventory contract (domains, CMAs) before backup; measure first.

NXS-LOCAL-0369: mds_backup export as a second backup type (V61/V62); one live export completed 2026-09-24. Second MDS of an HA pair not read (mds_ha_second_mds_read).
