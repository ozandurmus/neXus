# Failover one-shell pilot allow-list (ConfigMap `ui2-failover-pilot`)

`NEXUS_CP_FAILOVER_ONE_SHELL_CLUSTERS` is read from the ConfigMap `ui2-failover-pilot`, key `one_shell_clusters`
(comma-separated cluster refs, exact match; `*` = every cluster). The cluster refs are real names, so the ConfigMap is
never committed; it is created by hand on HOST-A. The reference is `optional: true`: a missing ConfigMap or key means
"no clusters", so every cluster stays on the exec path.

Create or update (idempotent; the value comes from your shell, never from a file in the repo):

    kubectl -n ui2 create configmap ui2-failover-pilot --from-literal=one_shell_clusters=<refs> --dry-run=client -o yaml | kubectl apply -f -

Environment variables from a ConfigMap are read at pod start, so restart the workload afterwards:

    kubectl -n ui2 rollout restart deploy/ui2-worker deploy/ui2-policy

Design and rollout: `docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md` (sections 3.7 and 6).
