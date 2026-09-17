# Check Point Failover Command Gate Batch
**Status:** DRAFT

## 1. Overview
This document serves as the formal Command Gate (Security Approval) for executing failover and state evaluation commands on production Check Point clusters. It outlines the specific commands allowed, their syntax, privilege requirements, payload structure, and security considerations.

## 2. Command Evaluation

### 2.1 `cphaprob stat`
*   **Command Syntax:** `cphaprob stat`
*   **Purpose:** Displays the status of ClusterXL members, including their state (Active/Standby/Down) and priority.
*   **Required Privileges:** Gaia Clish or Expert mode.
*   **Payload Shape:** Plain text output detailing cluster member IDs, states, and priorities.
*   **Privacy/DLP Risks:** Low risk. It does not leak passwords, IPSEC keys, or sensitive configuration data. It reveals the internal cluster structure and hostname/IP addresses of the cluster members.
*   **Idempotency/Safety:** Strictly read-only. Safe to execute repeatedly without altering system state.

### 2.2 `cphaprob -a if`
*   **Command Syntax:** `cphaprob -a if`
*   **Purpose:** Displays the status of all cluster interfaces, showing which interfaces are UP, DOWN, or in a problematic state across the cluster.
*   **Required Privileges:** Gaia Clish or Expert mode.
*   **Payload Shape:** Plain text output listing interfaces, their IP addresses, subnet masks, and status (e.g., UP, DOWN, non-monitored).
*   **Privacy/DLP Risks:** Low to Medium risk. Reveals internal network topology, interface names, and IP addressing schemes. Does not expose secrets.
*   **Idempotency/Safety:** Strictly read-only. Safe to execute repeatedly.

### 2.3 `cphaprob tablestat`
*   **Command Syntax:** `cphaprob tablestat`
*   **Purpose:** Displays statistics for the synchronization tables used by ClusterXL. Helps identify if sync is working correctly or if tables are full/dropping packets.
*   **Required Privileges:** Expert mode.
*   **Payload Shape:** Plain text output with table names, IDs, record counts, and sync status metrics.
*   **Privacy/DLP Risks:** Low risk. Exposes internal kernel table names and connection counts, but no payload data or PII.
*   **Idempotency/Safety:** Strictly read-only. Safe to execute.

## 3. Security Summary
All commands evaluated in this batch are diagnostic and **strictly read-only**. They do not modify the state of the firewall or the cluster. The privacy risk is limited to exposure of internal infrastructure details (IPs, hostnames, interface names), which should be treated as sensitive operational data but do not constitute secrets or PII.

Approval is recommended for automated failover validation routines.
