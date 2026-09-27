# PO decision record 2026-09-27 -- Check Point failover execution (VSX per Virtual System, return to standby)

**Status:** RATIFIED -- Product Owner, 2026-09-27 (chat decisions quoted in
`FAILOVER_EXECUTION_CP_CONTRACT.md` §8, §10). Recorded by the engineering session because the frozen
`FAILOVER_EXECUTION_CP_CONTRACT.md` differs from earlier ratified directions in `PRODUCT_DIRECTION_RECORD.md`; the
Product Owner's newer decisions resolve them as follows.

1. **Supersedes DR item 39 (first mutation target classic ClusterXL only).** The first release covers classic
   ClusterXL **and VSX**, where each Virtual System is its own failover unit and every step runs inside
   `vsenv <VSID>` (PO: "Biz VSLS kullanmıyoruz. VSX'ler kendileri failover oluyor, şasi bağımsız ... önce vsenv ile
   içine giriyorum ... clusterXL_admin down ile failover yapıyorum"). VSLS remains out of scope.
2. **Consistent with DR item 37 (no automatic rollback).** On any problem after the failover the run stops and warns;
   nothing is reversed automatically. The final `clusterXL_admin up` on the former active is **not** a failback: it
   re-admits that member as Standby while traffic stays on the new active; it runs only after every post-check passed.
3. **DR item 368 stands.** Before the first failover on a production cluster, the network-security / change-management
   review is signed by the stakeholders; the Product Owner obtains it. Building and testing the feature is not blocked
   by it.
4. **Checks are exactly the PO's list** (`cphaprob stat`, `cphaprob tablestat` -- the cluster IP table, compared
   between members, not a sync check -- `cphaprob -a if`, ARP, connections, traffic rate; CPS when the PO names its
   command). No other pre-check is added without a new PO approval of its exact command.
