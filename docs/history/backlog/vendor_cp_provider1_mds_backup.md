# Check Point Provider-1 (MDS): mds_backup over SSH

status: planned · target: 

Backbox reference (trail 34410129, 2026-09-22): 'Check Point -> Provider-1 -> R81.1 and above -> MDS Backup - No Logs (SSH)' -- Expert shell, TMOUT raised, blades_summary / fwm mds ver / mdsstat / cplic print -x / netstat -rn as evidence reads, clish 'lock database override' + 'save configuration gaia_config.txt', then '$CPMDIR/scripts/mds_backup -b -l -d /var/log/BackBox' (no logs) and the archive fetched from the device. Backbox also tars /etc/shadow into its bundle -- the product must NOT: secret material is withheld (Raw-evidence law). The management server role is currently refused at admission (MANAGEMENT_SERVER_UNGATED); this item is what gates it. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.
