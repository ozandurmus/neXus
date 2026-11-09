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

## 5. Follow-up 2026-11-07/09 (PRs 769-773)
- **MDS hotfix level: done.** PO 2026-11-07: "the command works on this version". The raw-shape diagnostics (PR 770)
  showed that the login-shell form answered only CR LF after the echo and then nothing for 60 s. With the PO's approval,
  the direct literal `cpinfo -y all` (V195, management servers only) answered in 5.3 s. Live: `R82 Jumbo Take 127`.
- **MDS HA role (V194, PR 769): live but blocked.** `mgmt_cli -r true -f json show-ha-status` stops at a
  certificate-fingerprint prompt (`(y/n) [n] ?`) and times out.
  - On 2026-11-09 the FULL CP policy collection failed at its first MDS-level read, `show-domains`, on the same prompt.
    The fingerprint changed after the last COMPLETED collection (2026-11-06 16:45).
  - neXus never answers the prompt. The PO verifies and accepts the fingerprint on the MDS by hand (PO choice
    2026-11-07). Until then policy collection and the HA role stay blocked.
- **VSX stage 2 (PR 770): live, not yet measured.** The condition 2 amendment (strict address-set binding) is in the
  decision record. The measurement needs a COMPLETED policy collection that re-reads the object inventory, followed by
  a topology rebuild. Baseline: 54 `ADDRESS_PREFIX_UNKNOWN` conflicts on the 2026-11-09 snapshot.
- **Security gate (PR 773).** The UBI9 base reported four new no-fix HIGH CVEs: expat, gnupg2 and two sqlite. The PO
  accepted them until 2026-11-14.
  - On 2026-11-14 the base image must be refreshed.
  - CVE-2026-91415 (java-21) now has a fixed package (RHSA-2026:88349) and must not be extended.
- **CI.** The validate check had waited 8-11 min on apt downloads of Chromium dependencies. The bounded install (PR 771)
  brought it to 96-111 s.

## 6. Result 2026-11-09 afternoon (PRs 776-777)
- **Fingerprint accepted once through neXus.** PO 2026-11-09: "fingerprint ok kabul edebilirsin", then "Yes, send it
  once".
  - The one-off literal `mgmt_cli -r true --unsafe-auto-accept true -f json show-ha-status` (V196) was sent in one
    inventory run. The worker switch `UI2_CP_MDS_FINGERPRINT_ACCEPT_ONCE=1` was set for that run and removed right
    after.
  - The acceptance persisted: the plain read now answers in 0.5 s, and the FULL CP policy collection COMPLETED again.
    A later certificate change will prompt again.
- **MDS HA role: still UNKNOWN.** The reply keys are `uid, name, domain-type, successfully-synced`, with no `servers`
  array (PR 777 key-shape log, no values).
  - The likely meaning is that no HA peer is listed for this MDS.
  - The absence of a field is not proof, so the role stays UNKNOWN until the PO confirms whether a secondary MDS
    exists.
- **VSX stage 2: measured.** After the COMPLETED collection re-read the object inventory and the service restart
  rebuilt the topology:
  - `ADDRESS_PREFIX_UNKNOWN` conflicts went from 54 to 30;
  - all conflicts went from 300 to 274.
  - The bound prefixes carry the `MANAGEMENT_DERIVED` grade and are never corroborated.
- **Delivery note.** Twice, the re-ship of PR 775/776 produced no `pull_request` check run. Closing and reopening the
  PR triggered the run, and the ship then reused the open PR.
