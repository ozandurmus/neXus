# Metrics (Prometheus scrape endpoint)

Enterprise phase 0.4 (see `docs/history/backlog/enterprise_phase0_logging_monitoring_scans.md`). The service, worker and module
roles export Micrometer metrics (`micrometer-registry-prometheus`, already on the classpath) in the Prometheus text format.
Spring Boot Actuator is intentionally absent from the classpath. Instana ingestion stays UNKNOWN until access exists; any
Prometheus-compatible scraper or an OpenTelemetry collector with a Prometheus receiver can read the endpoint when enabled.

## Where the endpoint is and how it is isolated

- Metrics scraping is **disabled by default** via `UI2_METRICS_ENABLED=false` (code default `false` when unset).
- When `UI2_METRICS_ENABLED=true`:
  - The stand-alone management scrape server (`MetricsServer` in `platform-core`) serves `GET /metrics` (and `HEAD /metrics`) on
    container port **9404** (`UI2_METRICS_PORT`), named `metrics`.
  - The service also serves `GET /metrics` on its internal port **8086** (`MachinePortConfiguration`), and the `configuration`
    (port **8084**) and `compliance` (port **8085**) module pods also serve `GET /metrics` on their internal health port alongside
    `GET /healthz`.
  - The public port **8080** never exposes `/metrics` or `/actuator/**`: requests there are refused with 404/403
    (`ManagementPortIsolationTest`, `MachinePortConfigurationTest`).
- The port is declared only as a named container port in `deploy/ui2/50-service-deployment.yaml`,
  `deploy/ui2/52-worker-deployment.yaml` and the configuration, compliance, policy and packet-analysis worker manifests
  (`54-`, `55-`, `57-`, `58-`), and as a commented example in `53-backup-deployment.yaml`. No Service, Ingress, Route or active
  NetworkPolicy rule lists it (checked by `test_metrics_port_is_a_named_container_port_only`), so the public ingress cannot
  route to it. Commented NetworkPolicy ingress examples are provided in `53-backup-deployment.yaml`,
  `56-service-internal.yaml`, `57-policy-deployment.yaml` and `58-packet-analysis-deployment.yaml`.

## What is exported

| Metric | Role | Labels |
| --- | --- | --- |
| `jvm_*`, `process_*`, `system_cpu_*`, `process_uptime_seconds` (memory, GC, threads, classes, file descriptors, CPU) | all | `role`, plus the standard JVM `area`, `id`, `cause`, `action`, `state` |
| `http_server_requests_seconds` (count, sum, max) | service | `method`, `uri` (the matched route pattern, such as `/api/v2/devices/{id}`, or `UNMAPPED`), `status`, `outcome` |
| `hikaricp_connections*` (active, idle, pending, max, acquire, usage, creation, timeouts) | service, worker | `pool` (`service-database`, `worker-database`) |
| `nexus_jobs` (gauge: jobs by state) | service | `state` (the closed job state enum) |
| `nexus_jobs_transitions_total` (counter: jobs by type and state) | service, worker | `type` (closed capability/job type token), `state` (closed job state enum) |
| `nexus_jobs_duration_seconds` (timer: job execution duration) | worker | `type` (closed capability/job type token), `state` (terminal job state enum) |
| `nexus_jobs_queue_depth` (gauge: jobs in `REQUESTED`, waiting to be claimed) | service | none |
| `nexus_readiness_runs_total` (counter: readiness runs by outcome) | worker | `outcome`: `READY`, `NOT_READY`, `UNKNOWN` |
| `nexus_notifications_sends_total` (counter: notification sends by type and result) | service | `type` (closed notification route or syslog channel), `result`: `success`, `failure`, `skipped` |
| `nexus_artefact_store_bytes` (gauge: encrypted artefact store bytes) | service, worker | none |
| `nexus_backup_attempts_total` (counter) | worker | `outcome`: `completed`, `failed`, `rejected`, `cancelled`, `outcome_unknown`, `stopped`, `exception` |

Every series also carries `role` (`service`, `worker`, `policy`, `backup`, `configuration`, `compliance`, `packet_analysis`).
The job state gauges come from one grouped count of the `jobs` table, cached for 30 seconds, with a 2 second query timeout; a
failed refresh keeps the last good numbers. The artefact store byte gauge reads the 30-second cached usage of `FileArtefactStore`
when mounted, or the stored ciphertext sum when not mounted.

## Label rule

No label carries a hostname, an IP address, a serial number, a user name or a device name. Labels are closed enums, route
patterns or fixed technical names. This is enforced in the registry, not by convention: `MetricLabelGuard` drops any label
key outside a fixed allow-list and replaces any value that is not a short plain token, looks like an IPv4 or IPv6 address, or
matches a device/cluster pseudonym (`FW-TANGO-04`, `CLS-ROMEO-01`) with `REDACTED`. `MetricsServerTest` and
`MetricsIsolationFenceTest` verify that every label key is in the allow-list and every value is safe.

## Not in scope here

Trace propagation, the Instana agent, alert rules and dashboards, and activating a NetworkPolicy for a specific in-cluster
scraper are separate steps of enterprise phase 0.4.
