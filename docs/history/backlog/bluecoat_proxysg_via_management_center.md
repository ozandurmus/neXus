# Blue Coat ProxySG via Symantec Management Center (PO 2026-09-25): MC REST on 8082 (basic or X-Auth-Token), GET /devices lists 4 ProxySG (SGOS 7.4.15.1), a Reporter and 2 WSS entries; the on-box API guide /help/api needs a login; backup through MC's own device backup or SSH show configuration -- decide after reading the on-box guide; first target the test proxy

status: in_progress · target: docs/design/VENDOR_BACKUP_CONTRACTS_2026_09_22.md

2026-09-25 ~10:00: validated -- MC added as a Blue Coat management server (port 8082, credential fwadm), confirm and Collect COMPLETED (246 ms); the MC is named by its TLS certificate; 7 managed devices recorded with type, model, version (with build) and status (all FULLY_MANAGED / DEPLOYED), shown in a Managed devices tab. Backup path: see bluecoat_proxysg_backup_path.
