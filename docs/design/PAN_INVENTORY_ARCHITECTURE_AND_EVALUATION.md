# Palo Alto Inventory Architecture and Evaluation Specification

## Status

**DRAFT — FOR ARCHITECTURAL EVALUATION (CODEX ASTRA & CLAUDE FABLE).**
Target implementation authority: `PO_DECISION_RECORD_2026_09_14E_PAN_INVENTORY_MEASURED_FORMS.md` (FROZEN) and `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md` (APPROVED).

---

## 1. Context and Objective

39 physical Palo Alto Networks firewalls (along with 140 associated Virtual Systems) have been discovered and registered from the live Panorama management server (`198.51.100.10`) into the UI2 Device Registry.

The current objective is to advance from **Registration** (`Registered, not confirmed` / Draft) to **Enrolled** status, and execute **Inventory Collection** (`inventory_collect`) across the Palo Alto fleet, producing full interfaces, routing tables, and HA cluster state projections in the UI.

This document synthesizes:
1. The frozen Product Owner contracts (`14E`, `14C`, `14D`).
2. Battle-tested Python collectors and parsers (`panorama/preflight_collector.py`, `panorama/panorama_runtime_runner.py`, `panorama/pan_preflight_battery.py`).
3. Java worker implementations (`InventoryCapabilityExecutor`, `ConfirmCapabilityExecutor`, `PanXmlApiTransport`).
4. Enterprise network constraints, direct vs proxy paths, and privacy invariants.

---

## 2. Request Forms & Network Action Gate

Per `PO_DECISION_RECORD_2026_09_14E` §1 (PF-1..PF-3) and `PAN_INVENTORY_API_ROUTE_GATE_ENTRIES.md`:

1. **Authentication & Session Establishment (Keygen):**
   - Method: `POST /api/`
   - Parameters: `type=keygen`, `user=<username>`, `password=<secret>` (strictly in the request body, **never** in URL query parameters).
   - Header Token: The returned `<key>...</key>` is placed in the `X-PAN-KEY` HTTP header for all subsequent operational reads in the session.

2. **The 4 Closed Operational Reads (Read Plan):**
   - **Identity Read:** `type=op&cmd=<show><system><info></info></system></show>`
     - Yields: serial, hostname, model, sw-version, mac-address, ip-address, uptime, advanced-routing flag.
   - **HA State Read:** `type=op&cmd=<show><high-availability><state></state></high-availability></show>`
     - Yields: local-info (state, mode, priority, preemptive, state-sync, mgmt-ip), peer-info (state, mgmt-ip, conn-status, conn-ha1/conn-ha1-backup/conn-ha2 status), running-sync.
   - **Interface Read:** `type=op&cmd=<show><interface>all</interface></show>`
     - Yields: `hw/entry` physical ports (name, state, speed, duplex, mac, type, mode) + `ifnet/entry` logical interfaces (name, id, ip, addr, addr6, tag, vsys, zone, fwd/virtual-router).
     - **Invariant (PF-2):** No per-vsys narrowing (`&vsys=`). Unscoped read carries the `vsys` leaf directly.
   - **Route Table Read:** `type=op&cmd=<show><routing><route></route></routing></show>`
     - Yields: `result/entry` routes (destination, nexthop, interface, flags, metric, virtual-router).
     - **Invariant (PF-2):** Unscoped read across all virtual routers.

---

## 3. The One-Box Model (Product Owner 2026-09-14)

Unlike Check Point VSX (where each VS is treated as an independent network entity with its own interactive `vsenv` shell context), Palo Alto Networks implements a unified one-box model:

```
+-----------------------------------------------------------------------------------+
|                           Palo Alto Physical Firewall                             |
|                                                                                   |
|  [ Hardware Ports (hw/entry) ] -> Context: "physical"                             |
|                                                                                   |
|  [ Logical Interfaces (ifnet/entry) ]                                             |
|        ├── vsys1: ethernet1/1.100 (IP: 192.0.2.1/24, fwd: vr:default)            |
|        └── vsys2: ethernet1/2.200 (IP: 198.51.100.1/24, fwd: vr:dmz)            |
|                                                                                   |
|  [ Virtual Routers & Routes ]                                                     |
|        ├── vr:default (Routes: 192.0.2.0/24 via 192.0.2.254) -> Context: "vsys1" |
|        └── vr:dmz     (Routes: 0.0.0.0/0  via 198.51.100.254) -> Context: "vsys2" |
|                                                                                   |
|  [ HA Runtime State ] -> Context: "physical" (Cluster Member Fact)                |
+-----------------------------------------------------------------------------------+
```

- **PM-1:** One single contact pass collects the entire device estate. Virtual systems are logical contexts derived from that data, never separate reads.
- **PM-2:** Physical ports (`hw/entry`) belong to the `physical` context. Interfaces (`ifnet/entry`) belong to a vsys via the `vsys` leaf.
- **PM-3:** Routes belong to a virtual router. A virtual router maps to a vsys through the interfaces that reference it (`ifnet/entry/fwd` matching `vr:<name>`). A virtual router spanning multiple vsys is attributed to each; an unreferenced virtual router falls into `physical`.
- **PM-4:** Stored contexts are `physical` (ports, unattributed routes, HA facts) plus one context per distinct vsys id observed (`vsys1`, `vsys2`, ...).

---

## 4. Transport & Connectivity: Direct vs Panorama-Proxy

An essential architectural question arises regarding device access:

1. **Direct Device Access (`pan_xml_api`):**
   - Target: `https://<device_ip>:443/api/`
   - Requirements: Network route and firewall rule from worker host to device management IP on port 443. Device TLS certificate verification or fingerprint matching.
   - Status in Estate: Probed from `HOST-A`; port 443 TCP is open.

2. **Panorama-Proxied Access (`pan_proxy_api`):**
   - Target: `https://<panorama_ip>:443/api/?type=op&cmd=...&target=<device_serial>`
   - Requirements: Panorama credentials (already stored and verified in Discovery: `fwadm`).
   - Advantages: Single egress point, zero direct worker-to-perimeter routing requirements, uses existing established Panorama trust channel.
   - Evidence in Python codebase: `panorama/panorama_runtime_runner.py:op_cmd(..., target=serial)` and `panorama/preflight_collector.py:285`.

---

## 5. Transport Implementation Fixes & Hardening

1. **URI Scheme Invariant (`java.net.http`):**
   - In `WorkerClaimLoop.java`, `addressRef` is often a bare IP address (e.g. `"192.0.2.10"`).
   - In `PanXmlApiTransport.java`, `URI.create(target.baseUrl() + API_PATH)` throws `IllegalArgumentException: URI with undefined scheme` if `baseUrl` lacks `https://`.
   - **Rule:** `PanXmlApiTransport` and `WorkerClaimLoop` must defensively ensure normalized `https://` URIs.

2. **Error Visibility & Fail-Closed State Transitions:**
   - In `ConfirmJobExecutor` and `InventoryJobExecutor`, failed state transitions must retain descriptive terminal reasons (e.g., `connect_failed: timeout`, `identity_mismatch_refused`), preventing silent `FAILED` states.

---

## 6. Questions for Architectural Review

1. **Direct vs Panorama Proxying:**
   Should the system support a dual-mode transport (`pan_direct` and `pan_panorama_proxy`), or should devices discovered via Panorama default to Panorama proxying when direct firewall management access is restricted or non-routable?
2. **Context Persistence & Alignment:**
   Does mapping Virtual Routers to VSYS through interface forwarding (`ifnet/entry/fwd`) handle edge cases like shared virtual routers or interfaces in multiple security zones cleanly?
3. **Identity Verification & HA Corroboration:**
   Does the port of Python `OP.0b` HA normalization logic in Java provide adequate protection against representation drift (e.g., zero-padded serials, hyphenated formats)?
4. **Readiness vs Enrollment Gate:**
   How should `device_confirm_palo_alto` behave when a device is temporarily unreachable during bulk onboarding? Should it remain in retryable `CLAIMED`/`REQUESTED` or transition to `FAILED` with clear retry semantics?
