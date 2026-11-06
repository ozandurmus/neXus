# CP measurements - 2026-11-06 afternoon (counts only)

Status: EVIDENCE RECORD (non-authoritative). Follows `PO_SESSION_HANDOVER_2026_11_06.md` §2.

## 1. Identity panel (PR 765)
Live as aiview: the served bundle carries the collapsible Platform identity panel; the device tab list no longer
has an Identity tab. Bundle markers checked by `~/Codo/ops-scripts/probes/uibundle.mjs`.

## 2. Hotfix level: R82 management server and Quantum Spark (PR 767)
- **Management server (R82).** The PR 764 shape log gave `len=0`. The shell's masked tail shape showed only the
  echo of `bash -lc 'cpinfo -y all'`: no byte of answer at all, not a slow stream.
  - The read had used the generic 30 s timeout. PR 767 gives it the gate's 60 s (V47).
  - Re-measured after PR 767: still no byte within 60 s (`result=TimedOut`). On R81.20 (2026-09-25) the same read
    answered in 4 s.
  - The hotfix level stays UNKNOWN. A longer timeout would be a guess. Next step: a PO decision (see §4).
- **Quantum Spark.** The official R81.10.X Spark CLI Reference Guide has no hotfix command. `show software-version`
  (p. 1930) "shows the version and the build of the current software".
  - PR 767 stores that version and build as the patch level. This is a parse-scope extension of an already gated
    read, so no new command was needed.
  - Live: 3 of 4 Sparks now show `R81.10.x Build N`. The fourth (R81.10.07) failed to connect: TCP 22 timed out
    (reachability, not neXus).

## 3. VSX VIP prefixes, stage 1 (`PO_DECISION_RECORD_2026_11_06_VSX_VIP_PREFIX_FROM_MANAGEMENT.md`)
One FULL CP collection with `UI2_POLICY_CP_OBJECTS_EVERY=60`, reverted afterwards (default 86400).
`[CP_GATEWAY_FIELDS]` gave 30 lines across 10 object types.
- `CpmiVsClusterNetobj` (19 objects) and `CpmiVsNetobj` (7) carry `interfaces[].ipv4-address` and
  `interfaces[].ipv4-mask-length`. `CpmiVsxClusterNetobj` and `CpmiVsxNetobj` carry them on some interfaces.
- **No VS identifier.** No VS object carries a VSID or a reference to its VSX cluster or host. The official API
  reply objects (`show-vsx-cluster`, `show-legacy-virtual-system`, API v2) carry none either.
- Discovery has the same 19 + 7 VS UIDs (`VIRTUAL_SYSTEM_CLUSTER`, `STANDALONE_VIRTUAL_SYSTEM`). Their
  `cluster_reference` and `parent_candidate_id` are empty, so no recorded chain links a VS object to a member's VSID.
- **Result:** condition 2 of the decision cannot be met. Stage 2 is not built, the VIPs stay
  `ADDRESS_PREFIX_UNKNOWN`, and a new PO decision is needed (the record's own staging rule).

## 4. Pending PO decisions
1. **MDS hotfix level.** Choose one:
   - the PO runs `time cpinfo -y all` by hand once on the management server (Expert) to learn its real duration;
   - or approve a longer gated timeout;
   - or approve a different read.
2. **MDS HA role.** Approve the exact literal `mgmt_cli -r true -f json show-ha-status`.
   - Official evidence: Management API `show-ha-status`, "Retrieve domain high availability status", in API
     v1.9.1, v2 and v2.1. It takes no request fields.
   - The reply carries `servers[].ha-state` (active or standby) and `sync-state`.
3. **VSX stage 2.** Choose one:
   - keep the VIPs UNKNOWN;
   - or amend condition 2 to allow a stricter value binding: every recorded VIP of a VS context must equal the
     interface addresses of exactly one UID-bound VS object.
