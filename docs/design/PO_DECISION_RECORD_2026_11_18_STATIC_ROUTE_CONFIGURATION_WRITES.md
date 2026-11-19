# PO decision record -- 2026-11-18: static route configuration writes (carve-out)

## Status

**RATIFIED -- PRODUCT OWNER DECISION, 2026-11-18** (chat, answering "Route yazma karar kaydı ... Onaylıyor musun?" with
"Onaylıyorum"). Sections 2.1-2.3 are applied to `docs/AI_DEVELOPMENT_PROTOCOL.md` and `AGENTS.md` in the same change (wording
condensed, same conditions). This record still approves no command literal, no code, no migration, no `gate_registry` row and no
switch: R1 write literals need the PO's literal-by-literal sign-off and the static route contract must be FROZEN first. Drafted from
`OPERATIONS_STATIC_ROUTE_CHANGE_CONTRACT_DRAFT_2026_11_17.md` section 1.4. No device or host was contacted.

## 1. What is asked

Ratify a narrow carve-out so that the product may later add or delete **one static IPv4 route per job** on:

- Check Point Gaia, non-VSX (standalone or non-Cloning-Group ClusterXL members),
- Palo Alto Networks direct firewall (not Panorama-template-sourced), legacy `virtual-router` only,
- Cisco ASA, routed mode (single context or named context),

under the contract's four-eyes approval, DB kill switch plus environment hard lock, change-control ledger, per-unit allowlist and phase gates
(`R0` reads and preview, `R1` one vendor and one pilot unit, `R2` one further vendor at a time).

## 2. Proposed amendment text (applies only on ratification)

### 2.1 `docs/AI_DEVELOPMENT_PROTOCOL.md`, line ~210

Current: "No new class 2, 3 or 4 command at the current product maturity."

Proposed replacement:

> No new class 2, 3 or 4 command at the current product maturity, with one exception: the static IPv4 route add and delete
> `CLASS_3_CONFIGURATION_WRITE` commands (and their save/commit, candidate-revert and inverse commands) for Check Point Gaia (non-VSX),
> Palo Alto direct-firewall legacy virtual-router and Cisco ASA routed mode, admitted only under
> `docs/design/PO_DECISION_RECORD_2026_11_18_STATIC_ROUTE_CONFIGURATION_WRITES.md` and the static route contract once that contract is
> FROZEN. Each such command still needs its own gate entry, and the Product Owner's literal-by-literal sign-off per phase.

### 2.2 `docs/AI_DEVELOPMENT_PROTOCOL.md`, lines ~234-235

Current: "Prohibited at current maturity (taxonomy classes 2-4): firewall configuration writes, policy install, commit, reboot/shutdown, forced
failover, interface/routing change, credential change and automatic remediation."

Proposed replacement (the list is kept; one bounded exception is added):

> Prohibited at current maturity (taxonomy classes 2-4): firewall configuration writes, policy install, commit, reboot/shutdown, forced
> failover, interface/routing change, credential change and automatic remediation -- except a single static IPv4 route add or delete
> (with its save or commit) on the three platforms and under the conditions of
> `PO_DECISION_RECORD_2026_11_18_STATIC_ROUTE_CONFIGURATION_WRITES.md`.

### 2.3 `AGENTS.md`, "Network action taxonomy" (new paragraph after the 2026-09-22 amendment)

> **PO Amendment (2026-11-18, static route configuration writes).** Per
> `docs/design/PO_DECISION_RECORD_2026_11_18_STATIC_ROUTE_CONFIGURATION_WRITES.md` (RATIFIED), a `CLASS_3_CONFIGURATION_WRITE` that adds or
> deletes exactly one static IPv4 route (never the default route) may run on a Check Point Gaia (non-VSX) unit, a Palo Alto direct firewall
> using a legacy virtual-router, or a Cisco ASA in routed mode, only when all of the following hold; a write missing any one is refused
> before the command is issued: (1) the phase gate of the static route contract is open for that vendor (`R1` one pilot unit, then one vendor
> at a time); (2) the unit is on the per-unit allowlist and both the environment hard lock and the DB kill switch are on; (3) four-eyes:
> `operation_admin` requests, a different `security_admin` approves, the executor is the requester or the approver; (4) every write, save or
> commit, rollback and outcome is written to the append-only change ledger and mailed through `operations_change`; (5) the exact command
> literal has its own signed-off gate entry; (6) no scheduled or automatic execution. Rollback follows section 3. Everything else in
> classes 2-4 stays prohibited.

## 3. Governance rules the amendment states

- Four-eyes replaces the failover single-approver rule for route changes: requester != approver, executor in {requester, approver}.
- Rollback: one `security_admin` may run the pre-signed inverse of a completed change within 60 minutes of completion (digest was part of
  the original approval), recorded as `ROLLBACK` with executor and reason and mailed; after 60 minutes it is a new four-eyes request. Never
  automatic.
- Ledger retention: append-only, no deletion path, no time limit set by neXus.

## 4. What stays prohibited

Default route (`0.0.0.0/0`); dynamic routing, PBR, IPv6, blackhole/reject/null0 and tracked routes; Check Point VSX and Cloning Group;
Panorama-template-sourced contexts; PAN-OS Advanced Routing Engine (`logical-router`); batch edits; scheduled execution; automatic rollback or
remediation; FortiGate (see 5); every other class 2-4 write (policy install, reboot, forced failover, interface, credential).

## 5. PO answers already given (chat 2026-11-18, recorded in the contract appendix)

| Question | Answer |
|---|---|
| Rollback approval (PO-1) | One `security_admin` within 60 minutes, pre-signed inverse. |
| Ledger retention (PO-3) | Unlimited, append-only, no deletion path. |
| FortiGate (PO-4) | FortiManager-managed: out of scope for local writes; any FortiGate route change is a separate design. |
| `security_admin` count (PO-2) | Not given. Assumed at least two named identities; the real count must be verified before `R1`. With only one, four-eyes blocks every change. |

## 6. New fact: PAN-OS Advanced Routing Engine (PO-5 answered)

Measured 2026-11-18 from stored inventory only (no device contacted), counts only: all 38 enrolled active Palo Alto firewalls have interfaces
bound to a legacy `virtual-router` in their latest inventory; none is bound to the Advanced Routing Engine. The contract's "34 firewalls"
figure is stale. PO-5 is therefore answered: legacy virtual-router only, ARE stays refused (`ROUTING_CONTEXT_UNSUPPORTED`). An estate change
(a unit moving to ARE) is caught by the Stage A/B pre-check and refused, not written.

## 7. What this record does not do

- It does not approve any command literal. Each read and write literal needs its own PO literal approval through the network-device
  command gate. The proposed `R0` read literals are listed in `OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md`.
- It does not freeze the contract, open `R1`, add an `ActionRegistry` entry, a migration or a gate row.
- It does not change the host action boundary or the 2026-09-22 and 2026-09-26 amendments.

## 8. Ratification

On ratification the PO session changes this status to RATIFIED with the date and the PO's words, then applies sections 2.1 to 2.3 to
`docs/AI_DEVELOPMENT_PROTOCOL.md` and `AGENTS.md` in one change and re-renders `DECISION_RECORD_SUCCESSOR_INDEX.md`.
