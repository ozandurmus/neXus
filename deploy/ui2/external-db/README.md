# External database: network layer

Kubernetes NetworkPolicy has no deny rule, so "everything may reach everything except the database" cannot be written
without selecting the other pods for Egress and then allowlisting all their other traffic (pod IPs, service IPs, DNS,
devices, proxy, registry). Whether `ipBlock 0.0.0.0/0` covers pod and service addresses is left to the network plugin,
so that approach could cut working connections. It is deliberately not shipped.

What protects an external database instead (design doc section 7):

1. The DBA allowlist in `pg_hba.conf`: only the cluster's node addresses (pod traffic leaves through the node), per
   database and per role, `hostssl` and `scram-sha-256`, final reject for everything else.
2. Per-module database users (migration `V242`): a pod that reaches the port still holds only its own module's role.
3. Optional additive allow `46-database-egress-allow.yaml.tmpl`: needed once, because backup, policy and
   packet-analysis already have egress policies that name only the in-cluster database pod.

Switch order: DBA allowlist first, then apply the template, then change `db_host` in the ConfigMap and roll out.
Rollback: change `db_host` back, delete `ui2-db-external-egress`.
