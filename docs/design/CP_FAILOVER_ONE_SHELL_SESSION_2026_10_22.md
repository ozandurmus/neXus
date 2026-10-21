# Check Point failover: one shell per member per pass (2026-10-22)

Status: **DRAFT (design), literals APPROVED** -- the Product Owner approved L1-L4 (section 5) on 2026-10-22 with "one
cluster first" ([PO_DECISION_RECORD_2026_10_22_CP_ONE_SHELL_SESSION.md](PO_DECISION_RECORD_2026_10_22_CP_ONE_SHELL_SESSION.md),
RATIFIED). The framing gate row `cp_failover_shell_framing` is `SIGNED_OFF` (migration `V174`). The path is OFF by default
and, when switched on, runs only for allow-listed clusters (section 3.7). Not real-environment validated.

## 1. Problem

The agreed design (`FAILOVER_READINESS_CHECKS_CP_PAN_DRAFT.md`, Product Owner decisions 2026-09-27, item 2):
"One SSH session per member per pass; the checks run one at a time, in order, inside that session, with an optional
configurable pause between checks."

The Product Owner reported on 2026-10-22, from the Check Point audit log, that one Check Point readiness run produces
a "Log In" entry (console connection) every 2-3 seconds -- about 30 to 40 entries per run.

## 2. Findings (code as of `origin/main` bd588cfa)

| Item | Where | What it does today |
| --- | --- | --- |
| Session | `CpFailoverJobExecutor.connect` | One `DeviceTransport.connect` per member per run (one TCP + SSH authentication per member). This part of the design is met. |
| Command | `CpFailoverJobExecutor.command` | Every read runs as `ssh.exec(member.session(), new ExecSpec("bash -lc '<cmd>'", pty), gateTimeout)`; VS runs use `bash -lc 'vsenv <VSID> && <cmd>'`. `pty` is true only for `cphaprob -a if`. |
| Exec | `SshExecTransport.execRaw` | Opens a new JSch `exec` channel per call (`openChannel("exec")`, `setCommand`, connect, read to channel close, exit status from the channel), then disconnects that channel. |
| Writes | `CpFailoverJobExecutor.command` (DOWN/UP branch) | `clusterXL_admin down/up` also run as one exec channel each, inside `store.dispatch`/`dispatchResume` (intent record before, reply evaluated by exit status 0, bounded output, no "command not found"). |
| Pause | `Ui2WorkerMain` | `UI2_FAILOVER_COMMAND_PAUSE_SECONDS` (default 2 s) between consecutive commands of a run (CP and PAN). |
| Shell support | `DeviceTransport.execInteractive`, `InteractiveShellSession` | A persistent PTY shell exists, but it ends a command by recognising the shell prompt and reports no exit status; it was built for Gaia Embedded / Quantum Spark and other vendors. Its own javadoc: "no marker/exit-status framing mode". It is not used by failover. |
| Banner | `CpFailoverJobExecutor.normalizeRead`, `ReadinessShapeLog.logBannerStripped` | Removes a leading `Warning! ...` preamble (and the VS context line) that the per-command login shell prints in front of the output; logs `bannerStripped=true` once per run. |
| Clish vs Expert | `AGENTS.md` (Check Point) | The validated administrator login shell is Expert (bash). `bash -lc` sent on an exec channel only works when the account's login shell is bash: the exec request is run by the login shell with `-c`. An account whose login shell is clish cannot run today's literals at all. Today's path never enters expert mode; it relies on bash being the login shell. |

Callers of `command(...)`: the readiness battery (`checks("pre", ...)`), the execution run (pre, the DOWN/UP writes,
`waitFor` polls, post, post_return) and the resume step (`readState`, UP, `waitFor`, post_return). All of them go
through the same private `command(member, command, ...)` method, so one change there covers all three.

Count check (synthetic, no device output used): a readiness pass reads, per member, `cphaprob stat`,
`cphaprob tablestat`, `cphaprob -a if`, `cphaprob show_bond` (+ one `cphaprob show_bond "<bond>"` per selected bond),
`cat /proc/net/dev` twice, `arp -an`, `cphaprob syncstat`, `fw stat`, `cphaprob -ia list`, `cphaprob show_failover`,
`cpstat os -f routing` -- 12 reads plus the bond reads, so about 24 to 40 exec channels per run, each 2 s pause + command
time apart. That is the count and the cadence of the audit entries the Product Owner reported.

### 2.1 What Gaia counts as a login

Evidence: the audit entries match the exec channels one to one in count (about 30-40 per run) and in cadence (the 2 s
pause plus command time). The SSH connection itself is opened only twice per run (once per member), so the entries are
not counted per connection.

What each exec channel starts on the gateway: sshd runs a new process for the channel -- the account's login shell
with `-c "bash -lc '<cmd>'"` -- and that runs a second, login bash (`-l`) that reads the system login profile (which is
also where the `Warning!` preamble and the Check Point environment come from). So each command is a separate
session-channel start and a separate login-shell start on the gateway.

Which of these two writes the Gaia audit "Log In" record is **UNKNOWN**: no vendor documentation in the repository says
so, and no device measurement has been done. The design below removes both: one shell channel per member per pass, and
inside it no nested login shell per command. Rollout step 3 (section 6) measures the result.

## 3. Design

### 3.1 Transport

* New port method `DeviceTransport.openShell(session, timeout)` returning a raw `ShellChannel` (send one line, read
  bytes with a bounded wait, close). Default: not implemented. `SshExecTransport` opens one JSch `shell` channel on the
  existing trusted session, with a PTY (`vt100`, 4096 columns x 10000 rows, as `InteractiveShellSession` does).
  `CompositeDeviceTransport` delegates it.
* **PTY: required.** `cphaprob -a if` produces no output without a terminal (measured, see `ExecSpec` javadoc), and all
  reads of a pass share the one channel. A PTY means the device echoes the typed line, ends lines with CR LF and may
  send terminal escape sequences; the framing below removes all three.
* The shell channel's shell is the account's login shell started by sshd as a login shell, so it reads the same system
  login profile that `bash -lc` read per command today: the Check Point environment is present once, for the whole
  pass. Admission (`EndpointRuntime.checkCommand`, the endpoint lease) and job cancellation are checked before every
  line sent, as `exec` checks them before every command.

### 3.2 Framing (`FramedShell`)

For each read the worker draws a fresh 128-bit nonce from `SecureRandom` (never derived from device data) and sends one
line:

```
echo __NX_<nonce>_BEGIN__; <read>; echo __NX_<nonce>_END_$?__
```

* Output = the lines strictly between a line that is exactly `__NX_<nonce>_BEGIN__` and the line that ends with
  `__NX_<nonce>_END_<digits>__`; exit status = those digits (0-255). Text in front of the END marker on its line is the
  read's last line without a newline.
* Echo stripping: the echoed input line never equals the BEGIN line (it starts with `echo`, may carry the prompt) and
  never matches END (it carries `$?`, not digits), so everything before BEGIN -- banner, profile messages, prompt, the
  echo -- is discarded.
* Every line is cleaned of terminal escape sequences (CSI, OSC, two-byte ESC sequences) and of every CR before it is
  compared or kept.
* Bounds: the read's own gate timeout is the deadline for the whole command; output after BEGIN is bounded by the
  executor's existing bound (262,144 bytes); text before BEGIN is bounded by 64 KiB.
* Any framing failure -- deadline, closed channel, output over the bound, preamble over its bound, an END before
  BEGIN, a second BEGIN, an END with an invalid status -- returns a failed result, **closes the shell** and marks it
  broken. The executor turns it into its existing `COMMAND_UNAVAILABLE` path: that check is `UNKNOWN`, never a guessed
  value. A broken shell is never reused and no new shell is opened for that member in that pass; every later read on
  that member fails the same way (`UNKNOWN`).
* Exit status semantics are unchanged in the executor: a read is accepted only with status 0, bounded output and none
  of "command not found / no such file or directory / permission denied".

### 3.3 Inner command form

Inside the shell the read is sent **without** the `bash -lc '...'` wrapper: the bare gated command
(`cphaprob stat`, ...), or `vsenv <VSID> && <cmd>` in a VS run. Reason: the shell is already a login shell with the
Check Point environment, and a nested `bash -lc` per command would start a login shell per command again -- one of the
two candidate triggers of the audit record (section 2.1). In a VS run `vsenv` leaves the shell in that VS context; every
read of the run re-enters the same VSID, and the shell is never shared with another run, so no read depends on a context
it did not set itself.

### 3.4 Clish accounts

If an account's login shell is clish, `echo` is not a clish command, no BEGIN line arrives, the first read times out
and the shell is closed: every check of that member is `UNKNOWN`. The worker does **not** enter expert mode (that would
need a new command and the expert password, and today's exec path never does it either -- it depends on bash being the
login shell). Command success is the only evidence of the shell type; the prompt is never used to decide it.

### 3.5 Writes stay on exec

`clusterXL_admin down` and `clusterXL_admin up` stay on their current exec path, unchanged. Reasons:
* the exec path's reply evidence is the SSH channel's own exit status; on the shell it would be text parsed from the
  output stream -- a different evidence source for a CLASS 2 write;
* a shell broken by an earlier read would make the write "not sent" yet reported as no reply (`OUTCOME_UNCERTAIN` with
  an incident) -- a worse outcome than today.
So a failover run shows one shell login per member per pass plus one exec login for each write sent (DOWN and UP on the
former active member; UP alone in a resume).

### 3.6 Pause

`NEXUS_CP_FAILOVER_COMMAND_PAUSE_MS` (0-60000) sets the pause between consecutive Check Point failover commands. When
unset: 1000 ms for a run on the shell path (design default); a run on the exec path (switch off, or its cluster not
allow-listed) keeps the existing `UI2_FAILOVER_COMMAND_PAUSE_SECONDS` (default 2 s), so its behaviour is unchanged. An
invalid value falls back to that default. The pause is chosen per run and taken before every command after the first.

### 3.7 Switch and gate

* A Check Point readiness (including scheduled readiness), execution or resume run uses the shell path only when
  `NEXUS_CP_FAILOVER_ONE_SHELL=enabled` **and** the run's cluster ref (`cluster_member_ref`) is listed in
  `NEXUS_CP_FAILOVER_ONE_SHELL_CLUSTERS` (comma-separated, exact opaque match; `*` = every cluster; empty or unset =
  none). Both are read at worker start (restart needed). Every other run: the exec path, byte for byte as before. The
  worker logs only counts (switch, `*` or the number of listed clusters), never the refs.
* A shell-path run first resolves the framing gate `cp_failover_shell_framing` (transport `SSH_INTERACTIVE`, CLASS 0
  read). If the row is not `SIGNED_OFF` the resolution is UNKNOWN and the run ends with `COMMAND_GATE_UNAVAILABLE`
  (readiness UNKNOWN) **before any connection**. The read gates themselves are resolved exactly as today; their command
  keys are unchanged.

## 4. Tests

* `FramedShellTest` (scripted fake PTY shell): framing and exit status, banner/echo/CR/escape stripping, a last line
  without newline, deadline, oversized output and oversized preamble, END before BEGIN, a second BEGIN, closed channel,
  no reuse after a failure, a fresh `SecureRandom` nonce per command, a multi-line literal never sent.
* `CpFailoverOneShellTest` (executor with the scripted shell transport): one shell channel per member per readiness pass
  and `exec` never called; the same check rows as the exec path; a framing failure gives UNKNOWN and no second shell;
  the configured shell pause between commands; an unsigned framing gate stops before any connection; the cluster
  allow-list (off, enabled + not listed = exec, enabled + listed = shell, `*`) for readiness and execution; switch off sends the exact
  old `bash -lc` literals over exec and never opens a shell; a failover run keeps DOWN/UP on exec; a VS run sends the
  `vsenv 12 && <cmd>` form.
* `CpFailoverResumeExecutorTest.oneShellResumeKeepsTheUpOnExecAndReadsInOneShellPerMember`: the resume step reads in
  one shell per member and sends its UP over exec only;
  enabled with the cluster not listed, it stays on exec.
* `CompositeDeviceTransportTest` (existing) proves the new default port method is routed by the composite.
* Not covered by automated tests: the JSch shell channel against a real Gaia gateway (rollout step 3).
* `CpFailoverShellModeTest`: switch, cluster list and pause parsing, defaults, invalid values, log form without refs.

## 5. Literals that need Product Owner approval

| # | Literal sent inside the shell | Replaces |
| --- | --- | --- |
| L1 | (channel) one SSH `shell` channel with PTY `vt100` 4096x10000 per member per run | one `exec` channel per command |
| L2 | `echo __NX_<nonce>_BEGIN__; <read>; echo __NX_<nonce>_END_$?__` -- `<nonce>` 32 lowercase hex characters from `SecureRandom`, new per command | nothing (new framing) |
| L3 | `<read>` = the bare gated read (e.g. `cphaprob stat`, `cpstat -f policy fw`, `cphaprob show_bond "<bond>"`) | `bash -lc '<read>'` |
| L4 | `<read>` in a VS run = `vsenv <VSID> && <cmd>` | `bash -lc 'vsenv <VSID> && <cmd>'` |

No other device command is added; `echo` is a shell built-in and the only new word. The writes are not changed (3.5).
**Approved by the Product Owner on 2026-10-22** (PO_DECISION_RECORD_2026_10_22_CP_ONE_SHELL_SESSION.md); V174 seeds the
row `SIGNED_OFF` and the fixture matches.

## 6. Rollout

1. Merge. The switch is off by default, so no run changes. The framing gate row is `SIGNED_OFF` (PO approval
   2026-10-22, L1-L4).
2. **One pilot cluster** (PO: "önce tek kümede"): on the worker set `NEXUS_CP_FAILOVER_ONE_SHELL=enabled` and
   `NEXUS_CP_FAILOVER_ONE_SHELL_CLUSTERS=<the pilot cluster's cluster_member_ref>` (optionally
   `NEXUS_CP_FAILOVER_COMMAND_PAUSE_MS`), restart the worker, and run one readiness pass on that cluster. Every other
   cluster stays on the exec path.
3. The Product Owner reads the pilot cluster's audit log for the run's time window: counts only -- expected one
   "Log In" per member per pass. Compare the run's check rows with the previous exec-path pass of the same cluster
   (statuses only).
4. If the count is not one per member, record the measured count here; the mechanism in 2.1 is then decided by that
   measurement, not assumed. Remove the cluster from the list (or switch off) to return to the exec path at once.
5. Then the failover fleet (`*` or the full list), as one configuration change.
6. Later, separately: inventory, configuration, backup and system-log collection on the same pattern, each with its own
   design and gate review.
