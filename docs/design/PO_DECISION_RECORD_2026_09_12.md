# PO Decision Record — 2026-09-12

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-12.** This document records
Product Owner decisions given during the 2026-09-11/12 UI 2.0 sessions that
existed only in session chat. `AGENTS.md` "Authority hierarchy" item 7 makes
chat non-authoritative, so an unrecorded verbal decision is lost at the
session boundary regardless of how clearly it was stated. This file is the
durable record; it does not create new authority, it preserves authority the
Product Owner already exercised.

Section 6 is different in kind: it is an **audit list of actions the agent
took under its own hand**, published so a later session audits them rather
than inheriting them as settled.

## 1. Collection gate — vendor data collection is stopped

**PO directive.** No vendor data-collection or extraction work proceeds until
the Product Owner specifies, **per vendor**, the collection type and the
collection methods. The stated reason: each product's collection type and the
methods used to collect its data may need to be discussed separately.

**Scope.** Applies to every collection/extraction path — Check Point and Palo
Alto alike, discovery-driven or manual, SSH/API/SNMP. UI 2.0 shell work
(navigation, screens, device model, authentication, persistence) is **exempt**
and continues.

**Consequence for contracts.** `UI2_0_B1_05_CP_INVENTORY_EXTRACTION_CONTRACT.md`
and any successor extraction contract must not advance to implementation while
this gate holds. A contract may be written; it may not be implemented.

**Lifts when:** the Product Owner states, per vendor, collection type and
methods. No other event lifts it.

## 2. Implementation language — Java, written from scratch

**PO directive.** New feature implementation is Java, written from scratch.
The existing Python scripts in this repository are **know-how only**: they may
be read to understand semantics, evidence handling and vendor behaviour, and
they must not be ported, wrapped, transliterated, or invoked from the new
implementation.

**Scope.** All UI 2.0 feature work. This does not retire the existing Python
tooling, its tests, or the repository governance scripts
(`scripts/project_queue.py`, `scripts/repository_privacy_check.py`,
`scripts/gov_session_transfer.py`), which remain the repository's own
machinery and are unaffected.

**Consequence.** A Java implementation that reproduces a Python script's
behaviour must derive that behaviour from the vendor contract and the
evidence laws, not from the script's code path. Where the Python script
encodes an unproven assumption, the Java implementation inherits the
`UNKNOWN`, not the assumption.

## 3. Failover check semantics

Product Owner input for the failover domain. These are requirements on any
future failover-readiness contract; no such contract currently carries them.

### 3.1 Reciprocal peer agreement is a required check

After per-vendor checks are applied to discovered devices (PAN checks to PAN,
CP checks to CP), the readiness assessment may only report that failover is
possible when **all members are UP** and the cluster configuration and
visibility **agree reciprocally across both peers**.

Worked form the Product Owner gave, for a PAN Active/Standby pair:

- `PAN-01` reports `local = active`, `peer = standby`, and
- `PAN-02` reports `local = standby`, `peer = active`, observed in the same
  collection pass.

Both halves are required. This is one check among several, not the whole
assessment. It is a direct application of `AGENTS.md` "Evidence laws" — a
member's report about its peer is not independent peer observation — and a
one-sided claim yields `UNKNOWN` / `RELATIONSHIP_INCONSISTENT`, never a
green readiness verdict.

### 3.2 VSX / VSLS mentality — the two vendors are not symmetric

**Palo Alto.** `vsys` follows the chassis. Failover is chassis-scoped: if
device 1 is active, its `vsys` instances are active with it. There is no
independent per-`vsys` active/standby distribution.

**Check Point.** Both the chassis **and** each VSX carry their own cluster
state. This estate runs **VSLS disabled** with individual member state
preserved, so active/standby distribution is **not** load-derived — it is
**static and operator-chosen**. Example the Product Owner gave: with three
VSX instances on a chassis, one may be active on member 1 and two on member 2,
or all three on member 2, while the chassis itself is active on member 1.

**Consequence.** A model that infers per-VS placement from chassis state, or
that treats an operator-chosen distribution as drift, is wrong for Check
Point. Expected-state evidence is required before calling any member
difference drift (`AGENTS.md` "Check Point": ClusterXL member differences are
`MEMBER_SPECIFIC` unless expected-state evidence proves otherwise).

**Estate fact.** Palo Alto here runs **Active/Standby**. There is no PAN
Active/Active in this estate; the A/A question recorded in
`PAN_ACTIVE_ACTIVE_FAILOVER_UNIT_GAP_2026_09_12.md` is **latent**, not live.

### 3.3 Re-run the checks on an operator failover request

The readiness checks run on a periodic schedule. That does not satisfy the
request path: when the operator requests a failover, the product **re-runs
the checks once more first**, against live evidence, before the request is
allowed to proceed. A previously-green periodic result is never sufficient
authorization for a request made later. This is `AGENTS.md`'s
"Readiness != authorization" invariant made operational.

### 3.4 One SSH session per device, reused across commands

Required interaction shape, for **both** Check Point and Palo Alto:

```
open one session to the device
  run command 1, take the output, confirm the shell returned
  run command 2, take the output, confirm the shell returned
  ... until the command set is finished
exit
```

A separate session per command is explicitly **not wanted**. The
shell-returned confirmation between commands is part of the requirement, not
an implementation detail: it is what makes the next command's output
attributable to that command.

### 3.5 Check Point commands still needing the command gate

`cphaprob stat`, `cphaprob -a if`, `cphaprob tablestat` are required for the
Check Point side of the failover checks and have **not** been through the
network-device command gate (`docs/AI_DEVELOPMENT_PROTOCOL.md`). They are not
implementable until they have.

## 4. Build sequencing — the roof before the rooms

**PO directive.** Contract-first work had outrun the product: two days of
contracts and sessions with no standing product. The correction: **stand the
roof up first, then build underneath it piece by piece.** The Product Owner's
stated model is how a comparable product behaves — an empty shell and menus
after login, then add a device, then discover it, then attach feature checks
to that device.

**Required first state.** A clean, empty database; the UI and its menus
visible; nothing pre-populated.

**Ordered sequence the Product Owner gave:**

1. Device add. Discovery (choose IP and vendor, and for discovery state
   whether the target is Panorama or MDS) or manual (IP and vendor, then
   SSH/API/SNMP as appropriate for that device).
2. Discovery returns candidates; the **operator multi-selects** which to
   import. The Product Owner's worked case: 50 devices found, the operator
   picks, and may deliberately exclude one or two. Import is never all-or-
   nothing.
3. On import the device row is created and scheduled retention begins.
4. Configuration and IP/route evidence follow, subject to §1's collection gate.

**Consequence.** A new contract is not opened ahead of the product step it
governs. `AGENTS.md`'s "Mandatory build lifecycle" requirement for a frozen
contract before implementation is unchanged for the cases it names (new
vendor semantics, new device commands, CLASS 2+ behaviour, schema migration,
a major security boundary); this directive constrains speculative contract
work that governs nothing yet built.

## 5. Authentication phasing

**PO directive.** Phase 1 is **LDAP + local**. RADIUS and TACACS are added
later and are not considered difficult. **Local is always the fallback**, and
every product surface offers both a local account path and a directory path.

This decision is already carried in
`UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` Correction C-2. It is restated
here only so the phasing has a single dated source; C-3's text remains the
contract.

**Open, blocking a local login path:** a local-authentication successor
contract does not yet exist and is required before any local login path is
implemented.

## 6. Agent-authority audit list — verify, do not inherit

The Product Owner has stated that a FROZEN status applied by an agent is not
evidence of Product Owner review: *"do not trust it just because you froze
it."* The following were created, frozen, amended or executed by the agent on
its own hand during these sessions. Each is listed so a later session audits
it rather than treating it as settled law.

### 6.1 Contracts the agent froze

| Document | What the agent did |
| --- | --- |
| `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` | Written and frozen by the agent. Supersedes the predecessor draft and records four of that draft's claims as disproved. §3's directive evidence is mixed: DIR-1–7 and DIR-9 are bytecode-proved; DIR-8 and DIR-10 are file-tree inspection covering only the repository/manifest half. |
| `UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` | Written and frozen by the agent, then amended twice by the agent (A-1: `row_pk` is outside the guarantee; A-2: §5.4's stated check was wrong). |

### 6.2 Contracts the agent amended or corrected

| Document | What the agent did |
| --- | --- |
| `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` | Correction C-1 (session takeover writes two correlated audit rows) and Correction C-2 (§5's auth phasing). |
| `UI2_0_D1_*` | Pending amendment applied at Product Owner instruction. |
| `UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` | Marked SUPERSEDED by the agent; body and amendments retained as history. |
| B1-2/3/4/4b/7 | Sixteen citations repointed clause-by-clause to B1-1a. One citation (B1-2 §7 item 4, `AuditContextIntegrationTest`) was **flagged, not repointed** — B1-1a §5/§10 withdrew it and no successor clause exists. |

### 6.3 Git actions the agent executed

PR #183 was opened and **merged to `main`** by the agent (merge commit
`82c4b70`), under explicit Product Owner authorization given in session. Per
`AGENTS.md` "Git authority and execution law" that authorization was
sufficient and is not re-litigated here; it is recorded because the
authorization itself was verbal and would otherwise be unevidenced.

### 6.4 Agent claims withdrawn during the sessions

Recorded so they are not re-derived from commit history as if they still
stood:

- A claim that GOV.ORCH.1/2 were unimplemented — **false**, caused by a grep
  that missed a multi-line `add_parser(` call. Withdrawn.
- A claim that `ui2/worker` declared `capability-registry` twice — **false**,
  the second occurrence was a comment. Withdrawn.
- A fail-closed NULL check in migration `V5` that blocked legitimate writes —
  an agent-introduced defect, fixed in `V6`.
- Address-shaped literals in the design preview data set, which reached three
  built bundles and took the repository privacy gate from 3 findings to 6 —
  an agent-introduced regression, corrected before the merge.

## 7. Decisions that remain open

These are **not** decided by this document and block the work they name:

| Id | Question | Blocks |
| --- | --- | --- |
| CP backup semantics | `CP_BACKUP_VENDOR_CONTRADICTION_2026_09_12.md` — the contradiction between the assumed blocking-backup behaviour and R81.20's documented asynchronous semantics. | Any implementation against the blocking-backup assumption. |
| LDAP TLS trust | `LDAP_TLS_TRUST_STORE_PIN_GAP_2026_09_12.md` — CA bundle format and pinning policy. | LDAP authentication transport. |
| PAN A/A | `PAN_ACTIVE_ACTIVE_FAILOVER_UNIT_GAP_2026_09_12.md` — failover unit under Active/Active. | Latent only; this estate is Active/Standby (§3.2). |

## 8. Cross-references

- `AGENTS.md` — authority hierarchy, evidence laws, Git authority law.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — network-device command gate (§3.5).
- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` — authentication phasing (§5).
- `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` — platform skeleton (§6.1).
