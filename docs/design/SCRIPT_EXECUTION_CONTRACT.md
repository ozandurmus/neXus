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
