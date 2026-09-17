# PO Decision Record — 2026-09-17 — Check Point Inventory & Topology Hybrid Collection

## Status
**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-17.**

## 1. Objective and Architecture
To achieve the "Unified View" of Discovery -> Inventory -> Configuration for Check Point and VSX devices, collection will follow a **Hybrid Architecture**:
1.  **Topology & Interfaces (Management Plane):** Collection via Check Point Management API (`mgmt_cli`) from the Multi-Domain Server (MDS).
2.  **Routing & Live State (Data/Control Plane):** Collection via Direct SSH (`ssh_exec`) to the Gateway / VSX Chassis using OS-level commands.

## 2. The Hybrid Justification (Why not 100% mgmt_cli?)
-   `mgmt_cli` provides a structured, hierarchical JSON representation of `CpmiVsxClusterNetobj`, `CpmiVsNetobj`, and their interfaces. This eliminates complex text-parsing of `vsx stat -v` for topology building.
-   However, **Dynamic and Live Routing Tables (OSPF, BGP, kernel state)** do not exist in the MDS Management API. They reside strictly in the Gaia OS kernel of the gateway.
-   Therefore, to provide full Inventory, the system MUST fetch routes directly from the device's kernel via SSH.

## 3. Data Source Truth and Alignment (The Audit Feature)
-   **Configured State:** The IPs and Interfaces returned by MDS (`mgmt_cli`) represent the *Intent* (what is configured in SmartConsole).
-   **Live State:** The IPs and Routes returned by Gateway SSH (`ip addr`, `ip route`) represent the *Truth* (what the kernel is actively running).
-   If these two diverge (e.g., a policy was not installed, or a manual OS change occurred), the system will not blindly overwrite one with the other. It will flag an `ALIGNMENT_MISMATCH` to the user.

## 4. Execution Commands (The Closed Gate)
### A. Management API (MDS) Collection
Issued over SSH to MDS using the `bash -lc` wrapper to ensure `.CPprofile.sh` is loaded:
-   `mgmt_cli -r true show domains limit 500 details-level full -f json`
-   For each domain: `mgmt_cli -r true -d "<Domain_Name>" show gateways-and-servers limit 500 details-level full -f json`

### B. Gateway (VSX) Live Routing Collection
Issued over Direct SSH to the target Gateway/Chassis:
-   Physical Chassis: `bash -lc 'ip -4 route show table all'`
-   Per Virtual System: `bash -lc 'vsenv <VSID> && ip -4 route show'`

## 5. Next Steps for Implementation
1.  Extend the `MgmtCliCommands` Java adapter to fetch and parse VSX Topology objects.
2.  Build a Gateway Routing adapter that consumes the CLI route outputs.
3.  Store them in a unified UI2 database schema.
