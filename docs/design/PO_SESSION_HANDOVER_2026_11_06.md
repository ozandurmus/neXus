# PO session handover — 2026-11-06 afternoon

Status: HANDOVER (non-authoritative; `CURRENT_STATE.md` and `project/QUEUE.md` win). Supersedes
`PO_SESSION_HANDOVER_2026_11_05.md` for the open work below.

## 1. Live state at handover (about 16:00 Istanbul)
- PRs 745-764 are on main and live. The last ship in flight at handover was PR "Device header: Platform identity as
  a collapsible panel; Identity tab removed" (branch `ui/device-header-identity-panel-1106`). Its chain output is
  `~/Codo/ops-scripts/logs/chain-idpanel-1106.out`, and it prints `SHIPPED`/`FAILED` plus `DONE`.
- Schema V193. Development mode: schedules off except the system-log collection (00/06/12/18).
- aiview may start policy and inventory collection (one device or all); see AGENTS.md.

## 2. Open work, in order
1. **Check the identity panel ship.**
   - If it failed, read `~/Codo/ops-scripts/logs/idpanel.log` and fix; the branch has vitest 1505 green.
   - If it shipped, verify live as aiview: the device header shows the collapsible Platform identity panel and the
     Identity tab is gone.
2. **R82 management server shows no hotfix/jumbo take** (PO 2026-11-06).
   - The parser (`CheckPointPlatformFactsParser.JUMBO_TAKE`, measured on R81.20) parses all 55 R81.20 gateways but
     not the only R82 device, the MDS.
   - PR 764 logs `[CP_CPINFO_NO_JUMBO] ... len= lines= hotfixLines= shapes=` (digits masked, vendor package tokens
     only) when no jumbo line is found.
   - Run `bash ~/Codo/ops-scripts/runprobe.sh ~/Codo/ops-scripts/probes/invmds.mjs` (aiview, one MDS inventory
     job). When it completes, read `kubectl -n ui2 logs deploy/ui2-worker --since=30m | grep CP_CPINFO_NO_JUMBO`.
   - Adapt the parser to the measured R82 shape, with a test from a synthetic fixture of that shape, ship, re-collect
     and check the header shows the take.
   - If the shape shows a truncated or empty interactive answer instead, fix the read, not the regex.
3. **VSX VIP prefixes from management data**, stage 1
   (`PO_DECISION_RECORD_2026_11_06_VSX_VIP_PREFIX_FROM_MANAGEMENT.md`).
   - PR 763 logs `[CP_GATEWAY_FIELDS] type= objects= keys=` for the `gateways-and-servers` listing.
   - It has not run yet: the object inventory is re-read at most every 24 h (`UI2_POLICY_CP_OBJECTS_EVERY=86400`),
     and the last read was 2026-11-06 01:01.
   - Either start a FULL CP policy collection after 01:01 on 2026-11-07, or temporarily set
     `kubectl -n ui2 set env deploy/ui2-policy UI2_POLICY_CP_OBJECTS_EVERY=60` for one FULL collection and revert by
     re-applying `deploy/ui2/57-policy-deployment.yaml` with the live image digest.
   - Start the collection with `bash ~/Codo/ops-scripts/runprobe.sh ~/Codo/ops-scripts/probes/natcollect.mjs`. Wait
     with `probes/wait-cpcollect.sh`, editing the job id first.
   - Then read `kubectl -n ui2 logs deploy/ui2-policy --since=2h | grep CP_GATEWAY_FIELDS`. Do this before any ship,
     because a ship restarts the pod and loses the log.
4. **VSX stage 2** (only if VS objects carry interfaces, mask lengths and a VS identifier).
   - Retain those interface fields in the object inventory.
   - In `TopologyBuilder`, take a VIP prefix from the bound management object under the record's conditions 1-4:
     management-derived evidence grade, UID binding, exact address match, fail closed.
   - Then rebuild the topology (restart ui2-service) and re-measure ADDRESS_PREFIX_UNKNOWN (54 today;
     `TOPOLOGY_CONFLICTS_RCA_2026_11_06.md`).
5. **New device reads, approved by the PO in principle** (2026-11-06: "onaylar ok"):
   - the MDS HA role;
   - the hotfix level on Spark gateways (R81.10.x, 4 devices).

   AGENTS.md (2026-09-26 amendment) still needs the PO to see each exact command literal. Propose each literal with
   official vendor documentation, get the exact-command approval, then add the gate migration and parser.
6. **Later:**
   - Policy UNKNOWN levers (`POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md` §0.1: CP access roles, PAN source-user,
     CP gateway objects, which depend on the same interface retention as item 4).
   - Analysis-container P0 masking work and the Argon design review.

## 3. Tooling notes
- Probes (aiview, counts only) live in `~/Codo/ops-scripts/probes/`:
  - collections: `natcollect`, `invall`, `invmds`, `natsrc`;
  - policy hygiene split: `x0`;
  - path queries: `pathm`;
  - topology roles: `roles`;
  - A6 sample and validation: `a6pick`, `a6cp`;
  - order and stability digests: `m9o`, `m9f`.
- Ships: `~/Codo/ops-scripts/shipq2.sh`, background only. Detach the branch first.
  - Run `python3 -m pytest -q tools/tests -n 8` before shipping docs, AGENTS or state edits: the shipq2 pretest
    skips the tools tests, and the cold-start word ceilings bite.
  - A deploy drains ui2-policy, so never ship while a policy collection runs.
- No aiview probe during a ship's e2e. Read worker and policy logs before the next ship restarts the pods.
