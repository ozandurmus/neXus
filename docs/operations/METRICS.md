# Metrics (Prometheus scrape endpoint)

Enterprise phase 0.4 (see `docs/history/backlog/enterprise_phase0_logging_monitoring_scans.md`). The service and the worker
roles export Micrometer metrics in the Prometheus text format. Instana ingestion stays UNKNOWN until access exists; any
Prometheus-compatible scraper or an OpenTelemetry collector with a Prometheus receiver can read it today.

## Where the endpoint is and how it is isolated

- The scrape endpoint is `GET /metrics` on the management port, container port **9404**, named `metrics`.
- It is served by a small stand-alone JDK HTTP server (`MetricsServer` in `platform-core`), not by Spring MVC and not by
  Spring Boot Actuator. Actuator is not on the classpath. The main API on port 8080, its session cookie, CSRF and RBAC gate
  chain are unchanged, and the main port serves nothing under `/actuator/**` or `/metrics`: an anonymous request there is
  refused (`ManagementPortIsolationTest`).
- The port is declared only as a named container port in `deploy/ui2/50-service-deployment.yaml`,
  `deploy/ui2/52-worker-deployment.yaml` and the configuration, compliance, policy and packet-analysis worker manifests
  (`54-`, `55-`, `57-`, `58-`). No Service, Ingress, Route or NetworkPolicy lists it (checked by
  `test_metrics_port_is_a_named_container_port_only`), so the ingress cannot route to it. The endpoint has no
  authentication of its own; it is reachable only by an in-cluster client that knows the pod address. Opening it to a
  specific scraper is a later NetworkPolicy decision and is not made here.
- The server starts only when `UI2_METRICS_PORT` is set (the manifests set `9404`). Unset, `0` or invalid leaves it off. Only
  `GET` and `HEAD` on `/metrics` answer; every other path is 404 and every other method 405.

## What is exported

| Metric | Role | Labels |
| --- | --- | --- |
| `jvm_*`, `process_*`, `system_cpu_*`, `process_uptime_seconds` (memory, GC, threads, classes, file descriptors, CPU) | all | `role`, plus the standard JVM `area`, `id`, `cause`, `action`, `state` |
| `http_server_requests_seconds` (count, sum, max) | service | `method`, `uri` (the matched route pattern, such as `/api/v2/devices/{id}`, or `UNMAPPED`), `status`, `outcome` |
| `hikaricp_connections*` (active, idle, pending, max, acquire, usage, creation, timeouts) | service, worker | `pool` (`service-database`, `worker-database`) |
| `nexus_jobs` (gauge: jobs by state) | service | `state` (the closed job state enum) |
| `nexus_jobs_queue_depth` (gauge: jobs in `REQUESTED`, waiting to be claimed) | service | none |
| `nexus_backup_attempts_total` (counter) | worker | `outcome`: `completed`, `failed`, `rejected`, `cancelled`, `outcome_unknown`, `stopped`, `exception` |

Every series also carries `role` (`service`, `worker`, `policy`, ...). The job gauges come from one grouped count of the
`jobs` table, cached for 30 seconds, with a 2 second query timeout; a failed refresh keeps the last good numbers.

## Label rule

No label carries a hostname, an IP address, a serial number, a user name or a device name. Labels are enums, route
patterns or fixed technical names. This is enforced in the registry, not by convention: `MetricLabelGuard` drops any label
key outside a fixed allow-list and replaces any value that is not a short plain token, or that looks like an IPv4 or IPv6
address, with `REDACTED`. `MetricsServerTest` scrapes a full registry and fails when any label key is outside the list or
any value is address-like. Add a new label key to `MetricLabelGuard.ALLOWED_KEYS` only with a reason in the PR.

## Not in scope here

Trace propagation, the Instana agent, alert rules and dashboards, and a NetworkPolicy for the scraper are separate steps of
enterprise phase 0.4. The configuration, compliance, policy and packet-analysis workloads reuse the worker entry point; their
manifests set `UI2_METRICS_PORT` and the named container port the same way as the service and worker.
