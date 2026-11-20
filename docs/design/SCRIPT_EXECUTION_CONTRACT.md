# Script Execution module — contract

## Status

**FROZEN — 2026-09-22, Product Owner answered §4 the same day** (answers
recorded in §4 and §6). Written on the Product Owner's decision 2 in
`PO_DECISION_RECORD_2026_09_22_AUTOMATION_EDITOR_AND_SCRIPT_EXECUTION.md`.
§6 carries one item that needs a constitutional amendment before it can be
built (device writes from a script); everything else is implementable. Backlog
`script_execution_module` (P1). Not to be confused with the Task Executor /
Automation module (device steps over gated commands): this module runs
**operator-supplied programs on the neXus side**, firewall-independent.

## 1. What it is, in one paragraph

The operator adds a script — a `.sh`, `.py` or `.jar`, uploaded or written in
the product's editor — gives it a cron schedule, and neXus runs it at that
time in an isolated runner, records the run (version, exit code, duration,
captured output) on the Jobs screen, and notifies over syslog and/or an SMTP
relay. Typical uses: a nightly report that calls `nexus-cli`, a check against
a third-party system, an export to a file share. Backbox calls this the
"script" side of Automations; here it is a separate module because its
security shape is different from a device step.

## 2. What the constitution already fixes

- **Host action boundary.** A script is code the product runs on the host's
  cluster; it is neither a device command (network action taxonomy) nor an
  agent's host action (host register). It gets its own authorization form:
  this contract, ratified by the decision record.
- **Secrets only through the credential store**; a script never contains a
  credential literal (the upload is scanned for the same secret patterns the
  repository privacy gate uses, and refused with the line number).
- **Raw-evidence law** applies to captured output: bounded, masked, discarded
  after retention.
- **No browser → device path** is untouched: a script cannot reach a device
  except through `nexus-cli`, which goes through the service's gates.

## 3. The design

### 3.1 Script record

`script` table: `script_id`, `name`, `kind` (`sh` | `py` | `jar`), `version`
(monotonic), `sha256`, `body` (text for sh/py; jar as an artefact-store
object, class `script_binary`), `schedule` (five-field cron, normalized as
the backup scheduler does), `enabled`, `timeout_s` (≤ 3600), `egress`
(list of `host:port` the runner may reach; empty = none), `notify`
(`syslog` | `smtp` | both | none), `created_by_actor_fingerprint`,
`created_at`. Every save is a new version; the run records which version ran.

### 3.2 Runner

A dedicated `ui2-script-runner` Deployment (one pod, scale 1) in the `ui2`
namespace:

- image: the product image's runtime plus `python3`, `bash`, a JRE and
  `nexus-cli`; **no** `kubectl`, no cluster ServiceAccount token
  (`automountServiceAccountToken: false`), no artefact-store, credential-
  store or database mounts, no host paths;
- runs as a non-root uid with a read-only root filesystem and an `emptyDir`
  scratch volume per run (wiped after);
- NetworkPolicy: egress only to `ui2-service` (for `nexus-cli`) and to the
  script's declared `egress` list, resolved at run time; ingress none;
- resources: `limits` 1 CPU / 1 GiB by default; a run beyond `timeout_s` is
  killed and recorded `TIMED_OUT`.

The runner claims `script_run` jobs from the same job table as the worker
(its own job type, lease with heartbeat, drain on rollout), so the Jobs
screen, filters and CSV export cover script runs without new UI.

### 3.3 Run

`script_run` job: `target_kind = script`, `target_ref = script_id@version`.
The runner writes the body to scratch, runs it with a clean environment
(`NEXUS_SCRIPT_ID`, `NEXUS_RUN_ID`, `NEXUS_CLI=/app/nexus-cli`, `HOME=scratch`,
`PATH` minimal), captures stdout and stderr up to 256 KiB each (masked through
the same masker as job output), records exit code and duration, and sets the
job outcome `SUCCESS` (exit 0) / `FAILURE` / `TIMED_OUT`. The captured output
is stored as a job artefact (class `script_output`) and downloaded through
the audited download route; the Jobs screen shows the last 4 KiB inline.

`nexus-cli` inside a script authenticates with a **script session**: a
short-lived token minted per run for the script's `run_as` role (default
`role:operator`; never `security_admin`), revoked when the run ends. What the
script can read or trigger through neXus is exactly what that role can.

### 3.4 Schedule

`ScriptScheduler`, the backup scheduler's pattern: `@Scheduled` tick every
60 s, fenced slot claim on `(script_id, slot)`, catch-up window 1 h (a
missed nightly run is not replayed six times after a long outage), one
`script_run` job per fire, `enabled=false` fires nothing. "Run now" from the
screen or `nexus-cli script-run <id>` submits the same job.

### 3.5 Notifications

- **syslog**: RFC 5424 over UDP or TCP to the host:port in Settings ›
  Notifications; one message per run end with script name, version, outcome,
  exit code, duration, run id (never output content).
- **SMTP relay**: host, port, STARTTLS on/off, from, to-list in Settings;
  relay credential (if any) as a credential-store reference; one mail per run
  end (or "on failure only" per script) with the same fields and the first
  4 KiB of masked output.
- Both are best-effort: a notification failure is recorded on the run, never
  fails the run.

### 3.6 Screen and CLI

Operations › Script Execution: list (name, kind, version, schedule, last run
outcome, next fire), editor (CodeMirror-style text area with the kind's
syntax; or upload), schedule field with "next five fires" preview, egress
list, notify choice, "Run now", run history (the Jobs screen filtered on the
script). Every action has its CLI form: `nexus-cli script-list / script-add
<file> / script-set <id> --schedule … / script-run <id> / script-runs <id>`.

Roles: create/edit/enable `role:security_admin`; run now `role:operator`;
read everyone with `job_log_read`.

### 3.7 Slices

1. Script record + versioning + secret scan + routes + CLI (`script-add /
   script-list / script-set`).
2. Runner Deployment + NetworkPolicy + `script_run` job type + scheduler +
   `script-run` + Jobs screen visibility.
3. Notifications (syslog, SMTP) + Settings › Notifications.
4. Screen (list, editor, run history).

## 4. Decisions — ratified by the Product Owner, 2026-09-22

| # | question | decision |
|---|---|---|
| 1 | Where do scripts run? | **A new, dedicated pod.** The PO went further: backup, compliance, automation, operations, configuration and inventory each become their own pod (backlog `module_per_pod_split`, major work, PO-requested). |
| 2 | Whom does a script act as? | **A chosen user/credential.** A security admin says "with this user, go and do this". A script run names a *neXus actor* (whose roles bound what it may do through `nexus-cli`) and, when it must reach a device, a *credential-store reference* (never a literal). Every run is audited under that actor. See §6 for the device-write consequence. |
| 3 | Output retention | **One month**, on the understanding that a run's record is "which script version ran, when, exit code, captured output". Configurable later; 30 days is the default. |
| 4 | Languages | **As many industry-standard scripting languages as the runner image can carry.** See §7. `.bat` cannot run on a Linux runner; PowerShell scripts (`.ps1`) can, through PowerShell Core, and that is the offered path for Windows-style scripts. |

## 6. Scripts that touch devices — contradiction resolved by PO amendment (2026-09-22)

**Resolved:** `PO_DECISION_RECORD_2026_09_22_SCHEDULED_DEVICE_WRITES_FROM_SCRIPTS.md`
(RATIFIED) amends `AGENTS.md` with the five conditions below; the text that
follows is the report as it stood before the amendment and the conditions the
amendment adopted.

The Product Owner's examples: "take the active member's configuration every
week and push it to the DR device" (Cisco ASA, whose clustering does not
replicate policy), and "run debug scripts periodically and mail the output".

The second is a read and fits this contract as written. The first is a
**network-device write from an unattended schedule**, which
`AGENTS.md` "Network action taxonomy" prohibits at the current maturity ("no
automatic (unscheduled-trigger, non-ledgered) network-device write/change
operation is permitted"; class 1 writes only through `RB.x` contracts, never
console-submittable). Per the authority hierarchy this contract cannot
override that rule, so it is reported here as a contradiction between the
Product Owner's direction and the constitution.

What resolves it: a Product Owner amendment to `AGENTS.md` (the same way the
2026-09-19 host-action amendment was made) that permits **scheduled,
ledgered, per-target-authorized** device writes from a script run, with (a)
the target device named in the script's record, not discovered at run time,
(b) the credential a credential-store reference with write rights the PO
granted for that target, (c) every write recorded in the job ledger with the
script version, and (d) a per-vendor `RB.x`-style contract for the write
command (for Cisco ASA: the configuration-replace mechanism, measured on a
real device first). Until that amendment is recorded, a script run's device
access through `nexus-cli` is read-only, and a script that reaches a device
directly (its own SSH) has no gate at all -- which is why the declared egress
list in §3.1 is the control, and why a device address in a script's egress
list is refused until the amendment exists.

## 7. Runner image — languages

Base: the product image's Ubuntu runtime. Interpreters installed:

| kind | runs with | note |
|---|---|---|
| `sh` / `bash` | bash 5 | |
| `py` | python3 (3.12) + pip-installed `requests`, `paramiko`, `pyyaml` | no network install at run time |
| `jar` | OpenJDK 21 JRE | `java -jar` |
| `js` / `mjs` | Node.js 22 LTS | |
| `pl` | perl 5 | |
| `rb` | ruby 3 | |
| `ps1` | PowerShell Core 7 (`pwsh`) | Windows-style scripts; `.bat`/`.cmd` do not run on Linux and are refused at upload with that reason |
| `go` binary | none needed | a static binary uploaded as `bin`, executed directly |

Image size is the cost; the runner is one pod, so it is paid once.

## 5. Tests that must exist before slice 2 ships

- A script containing a credential literal is refused at upload with the line number, never stored.
- The runner pod has no ServiceAccount token, no writable root, no volumes beyond scratch (manifest test).
- A script that tries an undeclared egress fails to connect (NetworkPolicy fixture test).
- A run beyond `timeout_s` is killed and recorded `TIMED_OUT`; its scratch is wiped.
- The per-run `nexus-cli` token is revoked at run end; a later use is 401.
- A `script_run` job heartbeats its lease and survives a rollout (drain).

## Amendment 2026-11-18

Ratified in `docs/design/PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md` (PO decision 2026-11-18), which overrides §2, §3.1, §3.3, §3.6, §5 and §6 where they differ:

1. **Secrets in a script are a warning, never a refusal; bodies are encrypted at rest and write-only.** Uploads are scanned with the repository privacy-gate patterns (`PRIVATE_KEY_MATERIAL`, `CREDENTIAL_LITERAL`), returning a warning (line numbers and pattern class, never the matched value, recommending credential-store references) and storing `secret_findings` on `script_version`. Every script body (text or binary) is stored encrypted at rest via the artefact store's envelope encryption (class `script_body`); the database stores only `sha256`, `size_bytes`, `artefact_ref`, `key_id`, `wrapped_data_key`, and `secret_findings`. No API, CLI or UI route ever returns a stored script body, not even to `role:security_admin`; only the slice 2 runner decrypts a version to execute it. Editing a script means uploading a new version.
2. **Egress to an enrolled device address is a warning, never a refusal.** Any `host:port` may be declared. When a declared egress host matches an enrolled device's management address (stored data only), the version records `egress_device_targets` (opaque device IDs) and the API/UI/CLI returns a warning that this is a direct path outside neXus command gates.
3. **`.bat`/`.cmd` remain refused** with `"cannot run on the Linux runner; use PowerShell (.ps1)"` (technical impossibility on the Linux runner).
4. **The runner stays a dedicated pod** (§3.2, slice 2).
5. **Declared credential-store references (`credential_refs`).** A script version declares `credential_refs` (credential-store reference IDs validated to exist, set by `role:security_admin` via API/CLI). Slice 2's runner resolves only those declared refs at run start (`NEXUS_CRED_<ALIAS>_USERNAME` / `_SECRET` or a `0600` scratch file), masks their values from captured output, and records the refs used on the run.

## Amendment 2026-11-18 (b) -- broker and execution split

Decided by the orchestrator session on 2026-11-18 after the independent review
`ops-scripts/reviews/script-s2-review-1118.md` (verdict DO NOT SHIP, findings 1-13) of slice 2 (`88d25352`). It
replaces the single-pod runner of §3.2 and Amendment 2026-11-18 item 4 ("the runner stays a dedicated pod") where they
differ; recorded as a follow-up in `PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md` §6.

1. **Two workloads.** `ui2-script-runner` is a long-lived **broker** (Java, the service image, one replica). It holds
   the `script_run` job lease, and for each run it creates one short-lived Kubernetes Job `script-run-<job id>` in the
   `ui2` namespace that executes the script, then collects, masks and reports the result. No script ever runs inside
   the broker (review findings 1 and 5).
2. **Execution pod.** Distinct non-root uid/gid (20185), `automountServiceAccountToken: false`, no `env` from any
   Secret, no `envFrom`, no database, artefact-store or credential-store mount, read-only root filesystem, all
   capabilities dropped, `RuntimeDefault` seccomp, one `emptyDir` scratch, `activeDeadlineSeconds = timeout_s`,
   `backoffLimit: 0`, `restartPolicy: Never`. An init container copies the run's seed (script body, the declared
   credentials file and the per-run `nexus-cli` session file) from one-time Secrets `script-run-<id>-s<n>` (the body is
   split over several Secrets when it is larger than one Secret can hold) into the scratch volume with mode `0400`
   (`0500` for a `bin` body); only the init container mounts the Secrets. The broker deletes the Job, its pod, the
   Secrets and the run's NetworkPolicy at the end of every run, whatever the outcome. A process a script leaves behind
   dies with its pod; the next run gets a new pod, a new scratch volume and new Secrets (finding 5).
3. **Per-run egress (finding 4).** A default-deny NetworkPolicy covers every execution pod; for each run the broker
   creates `script-run-<id>-e<epoch>` that allows `ui2-service:8080` (for `nexus-cli`, with `ui2-service` injected into
   `podSpec.hostAliases`) and, when declared `egress` resolves to at least one allowed external target, DNS to
   `kube-dns` (UDP/TCP 53) and each declared `host:port` of the version as `ipBlock /32` (or `/128`) of the addresses
   the host resolves to **at run start** (hostnames are re-resolved for every run; cluster pod/service CIDRs, node IPs
   from `UI2_NODE_IPS` / `status.hostIP`, and resolved `UI2_DB_HOST` addresses are blocked; when `egress = []` or no
   declared entry resolves to an allowed target, `kube-dns:53` is omitted so the pod has zero DNS egress). This replaces
   the userspace proxy and interpreter preloads, which are removed.
4. **Service owns the keys.** The service is the only workload holding the artefact-store and credential-store keys.
   Over the internal port 8086 (the `MachinePortConfiguration` pattern, authenticated by the broker's own token
   compared by SHA-256 in constant time, `UI2_SCRIPT_RUNNER_TOKEN_SHA256`), `POST /internal/script-runs/start`
   releases, once per lease epoch and only for a job that is `EXECUTING` under a `script_runner` lease of that epoch,
   the decrypted body of the run's version, the version's declared credentials and a freshly minted per-run session;
   `POST /internal/script-runs/finish` stores the masked output (artefact class `script_output`, encrypted, on the
   service's writable script store) and closes the run; `POST /internal/script-runs/abort` revokes the session after a
   lost lease. The broker therefore needs no shared volume (the recovery volume is `ReadWriteOnce` and read-only in the
   service; script bodies and outputs live under `UI2_SCRIPT_STORE_ROOT` on the report-store claim) and holds no
   artefact-store or credential-store key. No execution pod ever holds either key.
5. **Broker privileges.** Its own database login role `ui2_script_runner` (granted by
   `ui2_apply_script_runner_role_grants()`, V258: job lease, step attempts, its module row, what the shared module
   heartbeat touches, and the job-trigger side tables listed there until the per-module database roles work makes the
   `jobs.state` triggers `SECURITY DEFINER` (its planned step 0; V258's comment names them); no script
   table, credential, session or audit row), never the shared `ui2-db` login. A namespaced Role: create/get/list/watch/
   delete on `batch/jobs`; get/list/watch on `pods`; get on `pods/log`; create/delete on `secrets` (no get, list or
   watch: those would expose every Secret of the namespace); create/get/delete on `networkpolicies`. Kubernetes RBAC
   cannot restrict `create` by object name, so the `script-run-` name prefix is enforced in the broker's code
   (`ScriptRunKubernetes`), refused for any other name, and covered by a test.
   **First-ship bootstrap order:** V258 creates `ui2_script_runner` `NOLOGIN` (when the migration role holds
   `CREATEROLE`, or `10-configmap.yaml` created it on database start) and applies `ui2_apply_script_runner_role_grants()`.
   `ui2-service` starts without `Secret/ui2-script-runner-token` (`optional: true`) and reports `runner_available: false`
   until the token digest is configured and the broker heartbeats; `module_deploy.py` applies the broker's
   `ServiceAccount`s, `Role`, `RoleBinding` and `NetworkPolicy` objects and defers `Deployment/ui2-script-runner` when
   `Secret/ui2-db-script-runner` or `Secret/ui2-script-runner-token` is absent. Afterwards (or before first ship, since
   `script_runner_db_login.py` guards `ui2_apply_script_runner_role_grants()` with `to_regprocedure`), the operator runs
   `python3 tools/delivery/script_runner_db_login.py db-login` and `python3 tools/delivery/script_runner_db_login.py token`,
   restarts `ui2-service`, and rolls out `script-runner`.
6. **Output.** The broker reads the pod log (stdout and stderr lines are tagged by the launcher), masks the whole
   capture **before** truncating each stream to 256 KiB, and drops a partial last line when the log read hit its
   byte limit. Masked forms of every injected secret value and username: exact, Base64 (with and without a trailing
   newline), URL-encoded, lower- and upper-case hex, and JSON-escaped; plus the per-run session cookie and CSRF value.
   Limit stated plainly: a script that transforms a secret any other way (reversal, splitting, encryption) can still
   print it; masking is a safety net, not a boundary.
7. **Lease loss kills the run** (finding 9): the broker deletes the Job at once and asks the service to revoke the
   session; the run is not reported as complete under a lease it no longer holds.
8. **Images** (finding 3). The execution image `ui2/Containerfile.script-exec` (the service image by digest plus the
   §7 interpreters) is built in every ship by `tools/delivery/hosta_script_exec_image.py` after the service image,
   scanned by the same security gate, and handed to the broker as `UI2_SCRIPT_EXEC_IMAGE` by
   `module_deploy.py --script-exec-image`; a ship that builds no execution image keeps the broker's current value, so
   the service image never replaces it. The broker runs the service image unchanged (main's `ui2/Containerfile`
   entrypoint, including `-XX:-UseBASE64Intrinsics`, finding 7). **PowerShell 7 is not in the execution image**: it
   is not in the UBI/RHEL 9 repositories the build can reach, and no digest-pinned offline package exists in this
   repository, so `.ps1` is refused at upload with that technical reason until it is added (same footing as
   `.bat`/`.cmd`).
9. **run_as** (finding 8) is a role from an allowlist (`role:operator` default, `role:viewer`, `role:replay_viewer`),
   enforced by a database CHECK and offered as a select in the UI; a user name is no longer accepted and
   `role:security_admin` never is.
10. **Credential refs** (finding 10): a run injects exactly the refs of the version it runs. Changing `credential_refs`
    in settings creates a new version with the same encrypted body and the new refs. Two refs whose display names map
    to the same `NEXUS_CRED_<ALIAS>` get a numeric suffix (`_2`, `_3`...) and a warning, never a refusal.
11. **Output download** (finding 11) needs `role:operator` or above and writes one audit row per download
    (`audit_script_output_download`) before the first byte.
12. **Schedule zone**: five-field cron is read in `Europe/Istanbul` by default (`UI2_SCRIPT_SCHEDULE_ZONE` overrides).
