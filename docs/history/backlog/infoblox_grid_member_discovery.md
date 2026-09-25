# Infoblox: enumerate grid members (GET /wapi/v<ver>/member) so the Grid Manager's members appear under it; needs a gate entry and a discovery-tree mapping; the grid backup already covers the whole grid database

status: in_progress · target: docs/design/VENDOR_BACKUP_CONTRACTS_2026_09_22.md

2026-09-25 00:54: real-environment validated. The confirm and every completed backup read GET /wapi/v<ver>/member (gate infoblox_member_list, V71); the production grid lists 7 members, recorded as the Grid Manager's virtual systems and shown under it as GRID MEMBERS 7 (masked VS-SEC-xx to aiview). PO next: make the member data meaningful (platform/model, grid master vs candidate, HA pairing, DNS/DHCP service status) rather than names only -- needs _return_fields beyond host_name,platform and a member detail view; also the device chip reads 'Confirmed - Not collected' with a Collect now button that has no HTTPS inventory job behind it.
