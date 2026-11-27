# Cisco ASA `logging permit-hostdown` & Syslog Transport Measurement (FC-6)

Backlog: `policy_change_event_trigger` (P1).
Controlling document and clauses: `docs/design/PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md`
(FROZEN — PO APPROVED 2026-11-26), section 1.1 `Q2` (hard prohibition against direct Cisco ASA TCP or TLS syslog to
Overwatch unless `logging permit-hostdown` is verified present in the unit's stored `running-config`) and section 3.2
`FC-6`.

**This lane wrote and self-tested the harness; it has not been run against any environment.** No database, pod,
HOST-A, or network device has been queried. The in-pod run belongs to the PO session, needs the PO session's go, and
runs outside ship/e2e windows because `/app/tmp` is lost on a rollout.

## What it reads

Inside `ui2-service`, with PostgreSQL opened read-only via the pod's own environment files, zero device contact, zero
new device commands, zero migrations, and zero capability-gate edits:

- Enrolled, non-disabled Cisco ASA devices (`vendor_hint = 'cisco_asa'`, `not disabled`,
  `enrollment_state in ('ENROLLED', 'DEGRADED')`).
- Each unit's newest stored, non-removed `running-config` artefact, checking `v_latest_effective_running_config`
  (`effective_running`) and falling back to the newest `show_configuration` run in `device_configuration_run` (which
  is the `read_kind` written by `asa_configuration_collect`), decrypted in memory with the pod's artefact-store key,
  bounded to at most 64 MiB of decompressed UTF-8 text, scanned line-by-line, and immediately discarded.

## Closed counters and fail-closed semantics

Every output line in `measure.out` is tab-separated (`kind<TAB>id<TAB>key<TAB>value` or `done` / `fatal<TAB><class>`),
with every field strictly matching `[a-z0-9_.]+` and verified by `show_safe.sh`. Never emits interface names, IPs,
hostnames, serials, raw config lines, or exception messages.

Per-device anonymous ordinals (`device asa_NN <key> <val>`):

| counter | meaning |
|---|---|
| `has_stored_config` | `1` if a stored running-config artefact reference exists, else `0` |
| `permit_hostdown_present` | `1` if `logging permit-hostdown` is active (last directive wins), else `0` |
| `permit_hostdown_negated` | `1` if `no logging permit-hostdown` is active (last directive wins), else `0` |
| `logging_host_total` | count of `logging host ...` lines |
| `logging_host_udp` | count of `logging host` lines with `udp/...`, `17/...`, or default (no protocol token after IP) |
| `logging_host_tcp` | count of `logging host` lines with `tcp/...` or `6/...` without `secure` |
| `logging_host_secure` | count of `logging host` lines with `tcp/...` or `6/...` and `secure` |
| `logging_host_other` | count of `logging host` lines matching none of the above |
| `read_failed` | `1` if artefact retrieval, decryption, or I/O failed, else `0` |
| `skipped_size` | `1` if decompressed config text exceeded 64 MiB, else `0` |

Fleet FC-6 summary (`summary fc6 <key> <val>`):

| key | meaning / fail-closed rule |
|---|---|
| `asa_total_count` | enrolled, non-disabled Cisco ASA units |
| `asa_with_stored_config` | units with a stored running-config artefact reference |
| `asa_without_stored_config` | units without a stored running-config artefact |
| `asa_read_failed` | stored configs that failed retrieval, decryption, or stream reading |
| `asa_skipped_size` | stored configs that exceeded the 64 MiB cap |
| `asa_permit_hostdown_present_count` | readable stored configs with `logging permit-hostdown` active |
| `asa_permit_hostdown_absent_count` | readable stored configs without active `logging permit-hostdown` (absent or negated) |
| `asa_logging_host_udp_units` | units with `>= 1` UDP `logging host` line |
| `asa_logging_host_tcp_units` | units with `>= 1` plain TCP `logging host` line |
| `asa_logging_host_secure_units` | units with `>= 1` secure TCP/TLS `logging host` line |
| `asa_direct_tcp_tls_safe` | `true` **only** when `asa_total_count > 0` AND `asa_permit_hostdown_present_count == asa_total_count` AND `asa_without_stored_config == 0` AND `asa_read_failed == 0` AND `asa_skipped_size == 0`; otherwise `false` (fail closed) |
| `asa_mtls_supported` | always `unknown` (stored `running-config` alone does not prove ASA firmware mTLS client-cert support) |

## Self-test (local, no pod, no database, no device)

```bash
cd ui2 && ./gradlew --no-daemon :worker:classes :persistence:classes :platform-core:classes :job-engine:classes
bash scripts/measurement/asa_syslog_hostdown/selftest.sh
```

`selftest.sh` verifies `show_safe.sh` drops an unsafe line (`show_safe<TAB>ok`), compiles `AsaSyslogHostdownMeasure.java`
with `javac --release 21`, and runs `--selftest` over synthetic RFC 5737 configs (`selftest<TAB>ok`).

## Run (PO session only)

```bash
MEASURE_WORK_DIR=<scratch dir> bash scripts/measurement/asa_syslog_hostdown/measure_run.sh <tag>
```

`measure_run.sh` reads the target host from `~/.config/nexus/hosta`, refuses to start while `hosta_e2e.sh` is running,
compiles `AsaSyslogHostdownMeasure.java` against the pod's own `/app/service.jar`, stages `asahostdown-<tag>` in
`ui2-service:/app/tmp`, runs `run_in_pod.sh`, waits for `done`/`fatal`, filters output through `show_safe.sh` into
`asahostdown-<tag>.tsv`, and removes the staged directory.
