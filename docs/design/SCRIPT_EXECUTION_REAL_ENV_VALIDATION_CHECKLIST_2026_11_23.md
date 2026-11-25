# Script Execution — real-environment validation checklist, slices 1–3 (2026-11-23)

**Status: DRAFT -- checklist for the PO's real-environment run; not a contract.** It decides nothing and changes no
contract, decision record or project state. Backlog item `script_execution_module` stays `AUTOMATED_VALIDATED` until
the Product Owner has run this checklist (PO decision 2026-11-23: the PO runs the real-environment validation of
slices 1–3 personally, with a harmless non-device script, once slice 3 is live).

Controlling documents: `docs/design/SCRIPT_EXECUTION_CONTRACT.md` (FROZEN; sections 3.1–3.5 and 3.7, Amendment
2026-11-18 items 1–5, Amendment 2026-11-18 (b) items 1–12) and
`docs/design/PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md`. Every step below cites the clause it
proves. CLI shapes were copied from `ui2/cli/.../ScriptCli.java` on `origin/main` at `46050da0`; the executable is
`nexus-cli` (`overwatch-cli` is the same program under its new name).

**What this run touches.** No device, no external host, no management address. The scripts below print only names,
booleans and counts of their own; the one outbound attempt goes to the documentation address `192.0.2.1`, which no
host owns. Report the relationship, not the values (AGENTS.md, Sensitive identity reporting law): every evidence token
in this document is a boolean, a count, an enum or a duration — never a host name, address, user name, token or
output line.

**Slice 3 deployment check (verify before ticking P1).** Slice 3 (`V266__script_execution_slice3_notifications.sql` and
`ScriptRunNotifier`, landed at `8420aaf1`) is now in repository source on `origin/main` alongside slices 1, 2 and 4
(script record, versions, secret scan, routes, CLI, per-run pod, broker, scheduler, masked audited output and the
Operations › Script execution screen). Precondition P1 still checks whether the deployed build on the target cluster
includes `V266`; if the running build predates `V266`, tick P1 "no" and mark every `N` step `NOT_RUN` (the rest of the
checklist is still worth running).

---

## 1. Preconditions (tick each yes / no)

| # | Precondition | How to check (counts or booleans only) | Yes / No |
|---|---|---|---|
| P1 | Slice 3 (script-run notifications) is on the running build | Build record / release notes; otherwise the `N` steps are `NOT_RUN` | |
| P2 | Broker `runner_available` is true | `K01` below prints `1` or more | |
| P3 | The execution image is configured on the broker | `K02` below prints a number greater than `1` (a length, not the value) | |
| P4 | A syslog receiver and/or an SMTP relay is configured in Settings › Notifications and its test send works | `nexus-cli notifications` shows the settings; `nexus-cli notifications-test syslog` and `nexus-cli notifications-test mail` each exit `0`. Neither configured: mark the `N` steps `NOT_RUN` | |
| P5 | Two accounts exist: a `security_admin` (creates and edits scripts) and an operator account that is **not** `security_admin` (used for every "Run now", contract 3.6) | Account list in the user screen | |
| P6 | No other person is signed in during the run (the session count in `S`-steps is a delta) and nobody else is running scripts | Agreed with the team | |
| P7 | `kubectl -n ui2` access exists for the PO (read-only `get` is enough) | `kubectl -n ui2 get jobs` exits `0` | |

Stop at any "No" in P2, P3, P5 or P7: the run cannot start. P1 and P4 only turn the `N` steps into `NOT_RUN`.

## 2. Setup and the check helpers

Run once, in a scratch directory that is deleted at the end. Placeholders in angle brackets are yours to fill; do not
paste them into the build record.

```bash
WORK=$(mktemp -d)
adm() { NEXUS_CLI_HOME="$WORK/admin"    nexus-cli "$@"; }    # security_admin session
opr() { NEXUS_CLI_HOME="$WORK/operator" nexus-cli "$@"; }    # operator session
adm login <baseUrl> <security_admin account> local           # password: NEXUS_CLI_PASSWORD or the prompt
opr login <baseUrl> <operator account> local
# A throw-away secret for step M, built at run time (never written into a file of the repository):
probe="zzv$(date +%s)$$"
```

Fixed script ids (opaque, chosen by you through `--script-id`, so no output has to be captured):

| id | purpose |
|---|---|
| `zz-validation-ok` | success script (a), also the schedule and notification subject |
| `zz-validation-slow` | timeout script (b) |
| `zz-validation-egress` | egress-denied script (c) |
| `zz-validation-mask` | masking script (d), optional |
| `zz-validation-py` | second-language script (e), optional |
| `zz-validation-warn` | secret-scan warning upload (never run) |

Read-only checks (`K` helpers). They print one number or one word each; nothing else leaves your terminal.

```bash
# K01  runner_available is true (expect 1 or more)
adm script-list | grep -Ec '"runner_available" *: *true'
# K02  the execution image is configured on the broker (expect a number greater than 1; the value is not shown)
kubectl -n ui2 get deploy ui2-script-runner -o jsonpath='{.spec.template.spec.containers[*].env[?(@.name=="UI2_SCRIPT_EXEC_IMAGE")].value}' | wc -c
# K03  per-run objects: count of Jobs, pods, Secrets and NetworkPolicies whose name starts with script-run-
kubectl -n ui2 get jobs,pods,secrets,networkpolicies -o name | grep -c '/script-run-'
# K04  active sessions (count only)
adm api GET /sessions | grep -o '"session_id"' | wc -l
# K05  last run of a script: state, outcome, exit code, duration (keys only; the first object is the newest run)
adm script-runs <scriptId> | grep -Eo '"(state|outcome|exit_code|duration_ms|version|trigger_kind|stdout_truncated|stderr_truncated|has_output)" *: *[^,}]+' | head -9
# K06  number of runs of a script
adm script-runs <scriptId> | grep -o '"run_id"' | wc -l
```

Take the **baseline** of `K03` and `K04` now, before the first run, and write both down as `K03_base` and `K04_base`.
`K03` must come back to `K03_base` after every run (a standing object that merely starts with the prefix is excluded
by the baseline, not by guessing).

## 3. The scripts

All files are created in `$WORK`. None contacts a device or a named host; none contains a secret.

### (a) `ok.sh` — success, environment names, scratch, one CLI read, one refused write

```bash
cat > "$WORK/ok.sh" <<'EOF'
#!/bin/bash
# Harmless: prints names and booleans only, never a value.
set -u
allowed="NEXUS_SCRIPT_ID NEXUS_RUN_ID NEXUS_CLI NEXUS_CLI_HOME NEXUS_CREDENTIALS_FILE"
count=0; outside=0
for name in $(env | cut -d= -f1 | grep '^NEXUS_' | sort); do
  count=$((count + 1))
  case " $allowed " in *" $name "*) ;; *) outside=$((outside + 1));; esac
done
echo "nexus_env_name_count=$count"
echo "nexus_env_names_outside_allowlist=$outside"
case "${HOME:-}" in /scratch/*) echo "home_in_scratch=true";; *) echo "home_in_scratch=false";; esac
mkdir -p "$HOME" && printf 'probe-%s\n' "$$" > "$HOME/probe.txt"
if [ "$(cat "$HOME/probe.txt")" = "probe-$$" ]; then echo "scratch_roundtrip=true"; else echo "scratch_roundtrip=false"; fi
if (echo x > /app/probe) 2>/dev/null; then echo "root_fs_writable=true"; else echo "root_fs_writable=false"; fi
"$NEXUS_CLI" session > /dev/null 2>&1; echo "cli_read_exit=$?"
"$NEXUS_CLI" script-disable "$NEXUS_SCRIPT_ID" > /dev/null 2>&1; echo "cli_admin_write_exit=$?"
echo "validation_marker_line=present"
exit 0
EOF
```

Expected output lines: `nexus_env_name_count=5`, `nexus_env_names_outside_allowlist=0`, `home_in_scratch=true`,
`scratch_roundtrip=true`, `root_fs_writable=false`, `cli_read_exit=0`, `cli_admin_write_exit=3` (HTTP 403 prints exit
`3`), `validation_marker_line=present`. The refused write is the proof that the script session is exactly the `run_as`
role (3.3): afterwards the script must still be enabled.

### (b) `slow.sh` — timeout

```bash
printf '#!/bin/bash\necho "timeout_probe=started"\nsleep 300\necho "timeout_probe=finished_unexpectedly"\n' > "$WORK/slow.sh"
```

Registered with `--timeout 30`; the `sleep` is ten times longer.

### (c) `egress.sh` — one TCP attempt to a documentation address that is not declared

```bash
cat > "$WORK/egress.sh" <<'EOF'
#!/bin/bash
# 192.0.2.1 is a documentation address (RFC 5737); it is not in this script's egress list.
if timeout 5 bash -c 'exec 3<>/dev/tcp/192.0.2.1/443' 2>/dev/null; then
  echo "egress_probe=CONNECTED"
else
  echo "egress_probe=NOT_CONNECTED"
fi
exit 0
EOF
```

Limit, stated plainly: a documentation address is unroutable anyway, so `NOT_CONNECTED` alone does not prove the
NetworkPolicy. The supporting evidence is step R03 (the per-run NetworkPolicy exists while the run is alive and is gone
afterwards) and the contract test of the policy; this step proves that an undeclared connection attempt neither
connects nor breaks the run.

### (d) optional `mask.sh` — masking of a credential-store value in output

```bash
cat > "$WORK/mask.sh" <<'EOF'
#!/bin/bash
# The alias comes from the credential's display name zz-validation-probe (upper-cased, other characters become _).
s="${NEXUS_CRED_ZZ_VALIDATION_PROBE_SECRET:-}"
if [ -z "$s" ]; then echo "cred_present=false"; exit 0; fi
echo "cred_present=true"
printf 'plain: %s\n' "$s"
printf 'base64: %s\n' "$(printf '%s' "$s" | base64)"
exit 0
EOF
```

Before step M01, in the credential-store screen (the one used for device credentials) create one throw-away entry with
display name `zz-validation-probe`, any user name, and as secret the value of `$probe` (at least 12 characters, so the
masker's minimum length of 3 is far exceeded). Note its reference id as `<CRED_REF_ID>`. Delete the entry at the end
(step X03).

### (e) optional `second.py` — the second language in the image

`ui2/Containerfile.script-exec` on `origin/main` installs Python 3.12 with `requests`, `paramiko` and `pyyaml`, Node.js
22, perl 5 and ruby 3 (PowerShell is **not** installed: a `.ps1` upload is refused with that technical reason, Amendment
(b) item 8). One interpreter is enough for this run:

```bash
cat > "$WORK/second.py" <<'EOF'
import os, sys
names = sorted(k for k in os.environ if k.startswith("NEXUS_"))
print("nexus_env_name_count=%d" % len(names))
import requests, yaml  # installed in the image; no network is used
print("imports_ok=true")
home = os.environ["HOME"]
os.makedirs(home, exist_ok=True)
path = os.path.join(home, "probe.txt")
with open(path, "w") as handle:
    handle.write("x")
with open(path) as handle:
    print("scratch_roundtrip=%s" % (handle.read() == "x"))
sys.exit(0)
EOF
```

### The warning-upload file `warn.sh` (never run)

Built at run time so that no secret-shaped literal exists in any file of the repository:

```bash
printf '#!/bin/bash\n%s="%s"\necho ok\n' "api""_key" "probe-$(date +%s)" > "$WORK/warn.sh"
```

## 4. Step table

Evidence is recorded as `name=value` with the value a boolean, count, enum or duration. `R` = run lifecycle, `V` =
versions and record, `T` = timeout, `E` = egress, `M` = masking, `S` = schedule, `N` = notifications, `X` = cleanup.
"UI" is Operations › Script execution (`?screen=operations&tab=script-execution`); each CLI form has that screen as its
equivalent, and the first run of a script should be done once in the UI as the operator to prove the screen as well.

### V — script record, versions, scan, write-only body (slice 1)

| Step | Action (CLI, as security_admin unless said) | Expected observation | Clause | Evidence to record |
|---|---|---|---|---|
| V01 | `adm script-add "$WORK/ok.sh" --script-id zz-validation-ok --name zz-validation-ok --kind sh --timeout 60 --egress none --notify none --enabled false` | exit `0`; the response has `"current_version": 1`, `"enabled": false` | 3.1, Amendment item 1 | `add_exit=0`, `current_version=1` |
| V02 | `adm script-version-add zz-validation-ok "$WORK/ok.sh"` twice (append a comment line to the file before the second call so the hash changes) | each call exit `0`; `adm script-list zz-validation-ok` shows `current_version` `3` and three entries in `versions`, each with its own `sha256` | 3.1 (every save is a new version, monotonic) | `current_version=3`, `versions_count=3`, `versions_monotonic=true` |
| V03 | `adm script-add "$WORK/warn.sh" --script-id zz-validation-warn --name zz-validation-warn --kind sh --enabled false` | exit `0` (**not** a refusal); the response carries `secret_findings` with one finding of pattern class `CREDENTIAL_LITERAL` and a line number, plus a non-empty `warnings`; the response and `script-list zz-validation-warn` do not contain the probe value. Check `K07` | Amendment 2026-11-18 item 1 (warning, never refusal; line numbers and class, never the value) | `warn_add_exit=0`, `secret_findings_count=1`, `finding_class=CREDENTIAL_LITERAL`, `value_echoed=false` |
| V04 | `adm script-get zz-validation-ok` and `adm script-list zz-validation-ok`, then `K08` | no key named `body`, `content` or `content_base64`; the marker line of script (a) is not in either response | Amendment item 1 (no route returns a stored body, not even to `security_admin`) | `body_key_count=0`, `marker_in_response=false` |
| V05 | `adm script-set zz-validation-ok --enabled true` then `adm script-set zz-validation-ok --notify both` (or `syslog` / `smtp` if only one receiver exists; leave `none` if `N` is `NOT_RUN`) | exit `0` each; `script-list` shows `enabled: true` and the chosen `notify` | 3.1, 3.6 | `enabled=true`, `notify=<enum>` |

```bash
# K07  the probe literal is not echoed back (expect 0): the literal is the second field of the file's assignment line
lit=$(sed -n '2p' "$WORK/warn.sh" | cut -d'"' -f2); adm script-list zz-validation-warn | grep -cF -- "$lit"
# K08  body-like keys in the script responses (expect 0) and the marker line of script (a) (expect 0)
adm script-get zz-validation-ok | grep -Eic '"(body|content|content_base64)"'
adm script-get zz-validation-ok | grep -c validation_marker_line
```

### R — a successful run and its per-run objects (slice 2)

| Step | Action | Expected observation | Clause | Evidence to record |
|---|---|---|---|---|
| R01 | As the operator: `opr script-run zz-validation-ok` (or UI › "Run now"), then poll `K05` for `zz-validation-ok` until `state` is terminal | exit `0` (HTTP 202 body); run reaches `outcome` `SUCCESS` | 3.3, 3.4 ("Run now" submits the same job), 3.6 (run now = operator) | `outcome=SUCCESS`, `exit_code=0`, `duration_ms=<n>` |
| R02 | `K05`; compare `version` with the `current_version` from V02 | the run records version `3` | 3.1 (the run records which version ran) | `run_version=3`, `run_version_matches_current=true` |
| R03 | Positive control, taken in step T01 (the run of script (b) lasts 30 s; script (a) is over in seconds): `K03` during that run | count is greater than `K03_base` (Job, pod, Secrets and the per-run NetworkPolicy exist) | Amendment (b) items 1–3 | `objects_during_run_gt_base=true` |
| R04 | After the run is terminal (wait 30 s): `K03` | equals `K03_base`: Job, pod, `script-run-<id>-s<n>` Secrets and `script-run-<id>-e<epoch>` NetworkPolicy are all gone | Amendment (b) items 1–3 | `objects_after_run_delta=0` |
| R05 | `K04` after the run | equals `K04_base`: the per-run script session is revoked | 3.3 (revoked when the run ends); contract section 5 | `active_sessions_delta=0` |
| R06 | `adm script-list zz-validation-ok` | still `enabled: true`: the refused write in script (a) did not change the script | 3.3 (the session can do exactly what `run_as` can) | `script_still_enabled=true` |
| R07 | Download: `opr script-output <runId> > "$WORK/ok.out"` (or UI › "Download run output"), then `K09` | the verdict lines in `K09` all hold | 3.3, Amendment (b) item 6 | see the `K09` tokens below |
| R08 | `K05`: `stdout_truncated`, `stderr_truncated` | both `false`; `has_output` `true` | 3.3 (256 KiB cap, not hit) | `stdout_truncated=false`, `stderr_truncated=false`, `has_output=true` |
| R09 | The audit row for the download (see "Audit row" below) | exactly one new row for this run | Amendment (b) item 11 | `output_download_audit_rows=1` |

`<runId>` is the `run_id` of the newest run in `adm script-runs zz-validation-ok`; paste it only into your own shell.

```bash
# K09  verdict lines of script (a): count of exact line matches in the downloaded output (each expected 1)
for l in 'nexus_env_name_count=5' 'nexus_env_names_outside_allowlist=0' 'home_in_scratch=true' 'scratch_roundtrip=true' \
         'root_fs_writable=false' 'cli_read_exit=0' 'cli_admin_write_exit=3' 'validation_marker_line=present'; do
  printf '%s -> %s\n' "${l%%=*}" "$(grep -c -x -- "$l" "$WORK/ok.out")"; done
```

Record each as `<name>_ok=true` when its count is `1`, e.g. `nexus_env_names_ok=true`, `scratch_roundtrip_ok=true`,
`root_fs_read_only_ok=true`, `cli_read_ok=true`, `cli_write_refused_ok=true`. (Output lines are prefixed by the launcher
with `O|` or `E|` only inside the pod log; the stored, masked output you download is the script's own text. If the
download shows the prefix, match on the suffix and note `output_prefix_visible=true`.)

**Audit row.** Take the count of audit rows for action `script_output_download` on table `script_run` **immediately
before** the download and immediately after, using whichever audit view the PO already uses. Do not open the run detail
with its output tail between the two counts: that read writes the same audit row (`ScriptExecutionService.auditOutputRead`).
If no screen or route can show that count, record `R09 | NOT_RUN | audit_read_surface=none` and open a backlog item for
"no read surface for the script output-download audit row"; do not query the database by hand into a chat or record.

### T — timeout (slice 2)

| Step | Action | Expected observation | Clause | Evidence to record |
|---|---|---|---|---|
| T01 | `adm script-add "$WORK/slow.sh" --script-id zz-validation-slow --name zz-validation-slow --kind sh --timeout 30 --egress none --notify none --enabled true`, then as the operator `opr script-run zz-validation-slow`; during the first 20 s take `K03` and `K04` | `K03` greater than `K03_base`; `K04` equals `K04_base` plus 1 (positive controls for R03 and R05 on a run that lasts) | Amendment (b) items 1–3 | `objects_during_run_gt_base=true`, `active_sessions_during_run_delta=1` |
| T02 | `K05` after the run is terminal | `outcome` `TIMED_OUT`; `duration_ms` at least `30000` and well under the 300 s the script sleeps (pod deadline enforced) | 3.2 (`TIMED_OUT`), Amendment (b) item 2 (`activeDeadlineSeconds = timeout_s`) | `outcome=TIMED_OUT`, `duration_ms=<n>`, `ended_before_script_finished=true` |
| T03 | Wait 30 s, then `K03` and `K04` | `K03` equals `K03_base`; `K04` equals `K04_base`: the killed run's objects and session are gone | 3.2, 3.3, Amendment (b) items 2 and 7 | `objects_after_run_delta=0`, `active_sessions_delta=0` |

### E — egress (slice 2)

| Step | Action | Expected observation | Clause | Evidence to record |
|---|---|---|---|---|
| E01 | `adm script-add "$WORK/egress.sh" --script-id zz-validation-egress --name zz-validation-egress --kind sh --timeout 60 --egress none --notify none --enabled true`, then `opr script-run zz-validation-egress`; during the run `kubectl -n ui2 get networkpolicies -o name` filtered on `script-run-` (count) | count `1` while the run is alive | Amendment (b) item 3, 3.2 (NetworkPolicy, egress only to the declared list) | `per_run_networkpolicy_during_run=1` |
| E02 | `K05`, then download the output as in R07 and count the exact line `egress_probe=NOT_CONNECTED` | `outcome` `SUCCESS`, `exit_code` `0`; the line count is `1`; the line `egress_probe=CONNECTED` count is `0` | 3.2, contract section 5 (undeclared egress fails to connect); Amendment (b) item 3 | `outcome=SUCCESS`, `egress_probe_connected=false` |
| E03 | `K03` 30 s after the run | equals `K03_base` | Amendment (b) item 3 | `objects_after_run_delta=0` |

### M — masking (optional; slice 2 and Amendment items 5, 6, 10)

| Step | Action | Expected observation | Clause | Evidence to record |
|---|---|---|---|---|
| M01 | `adm script-add "$WORK/mask.sh" --script-id zz-validation-mask --name zz-validation-mask --kind sh --timeout 60 --egress none --notify none --enabled true`; then `adm script-set zz-validation-mask --credential-refs <CRED_REF_ID>` | both exit `0`; after the second call `current_version` is `2` and `credential_refs` lists exactly one reference | Amendment item 5, Amendment (b) item 10 (changing refs makes a new version with the same body) | `credential_refs_count=1`, `refs_change_new_version=true` |
| M02 | `opr script-run zz-validation-mask`, wait for a terminal state, then `opr script-output <runId> > "$WORK/mask.out"` and `K10` | `outcome` `SUCCESS`; the plain value and its Base64 form do not appear; `[REDACTED]` appears for both lines | Amendment (b) item 6 (plain and Base64 forms masked before the 256 KiB truncation) | `cred_present=true`, `plain_value_in_output=false`, `base64_value_in_output=false`, `output_masked_form_seen=true` |
| M03 | `K05`-style read of the run: `credential_refs_used` | one reference recorded on the run | Amendment item 5 (records the refs used) | `credential_refs_used_count=1` |

```bash
# K10  expect: 0, 0, then 2 or more
grep -cF -- "$probe" "$WORK/mask.out"
grep -cF -- "$(printf '%s' "$probe" | base64)" "$WORK/mask.out"
grep -c '\[REDACTED\]' "$WORK/mask.out"
```

If `cred_present=false` is printed, the alias differs from `ZZ_VALIDATION_PROBE`; the display name was not exactly
`zz-validation-probe`. Fix the name and repeat; record `alias_matched=false` for the first attempt.

### L — second language (optional; slice 2)

| Step | Action | Expected observation | Clause | Evidence to record |
|---|---|---|---|---|
| L01 | `adm script-add "$WORK/second.py" --script-id zz-validation-py --name zz-validation-py --kind py --timeout 60 --egress none --notify none --enabled true`, `opr script-run zz-validation-py`, then download and count the exact lines `imports_ok=true` and `scratch_roundtrip=True`, `nexus_env_name_count=5` | `outcome` `SUCCESS`, all three counts `1` | Section 7 (`py`: 3.12 with `requests`, `paramiko`, `pyyaml`) | `kind=py`, `outcome=SUCCESS`, `imports_ok=true` |

### S — schedule (slice 2, scheduler)

Cron is read in `Europe/Istanbul` (Amendment (b) item 12). Pick a time **five minutes from now** by the clock of
`Europe/Istanbul` and write it as `<MIN> <HOUR> * * *`.

| Step | Action | Expected observation | Clause | Evidence to record |
|---|---|---|---|---|
| S01 | `adm script-set zz-validation-ok --schedule "<MIN> <HOUR> * * *"`, then `adm script-list zz-validation-ok` | the response says `schedule_zone` `Europe/Istanbul`; the first entry of `next_five_fires` is that minute **minus three hours** in UTC (`Z` suffix) | Amendment (b) item 12, 3.4 | `schedule_zone=Europe/Istanbul`, `next_fire_zone_offset_ok=true` |
| S02 | Take `K06` for `zz-validation-ok` (`runs_before`), wait until two minutes after the minute, take `K06` again and `K05` | exactly one more run; its `trigger_kind` is not the manual one and `scheduled_slot` is set; `outcome` `SUCCESS` | 3.4 (one `script_run` job per fire, fenced slot) | `scheduled_runs_delta=1`, `trigger_kind=<enum, not manual>`, `outcome=SUCCESS` |
| S03 | Wait two more minutes, `K06` again | unchanged: the same slot did not fire twice | 3.4 (fenced slot claim) | `duplicate_fire=false` |
| S04 | `adm script-set zz-validation-ok --schedule "* * * * *" --enabled false`, take `K06`, wait three minutes, take `K06` | unchanged: an every-minute schedule on a disabled script fires nothing | 3.4 (`enabled=false` fires nothing) | `disabled_fires_delta=0` |
| S05 | `adm script-set zz-validation-ok --schedule clear` | exit `0`; `schedule` is `null` | 3.4 | `schedule_cleared=true` |

### N — notifications (slice 3; `NOT_RUN` when P1 or P4 is "No")

Re-enable the script first: `adm script-set zz-validation-ok --enabled true`. `notify` is `both` (or the one receiver you
have). Then run it once: `opr script-run zz-validation-ok`.

| Step | Action | Expected observation | Clause | Evidence to record |
|---|---|---|---|---|
| N01 | At the syslog receiver, count messages carrying the run id of this run (look for the id in your own receiver, do not copy it anywhere) | exactly one message, RFC 5424 shaped, with script name, version, outcome, exit code, duration and run id | 3.5 (syslog, one message per run end) | `syslog_messages_for_run=1`, `syslog_fields_present=true` |
| N02 | In the same message, count occurrences of the marker line of script (a) (`validation_marker_line`) and of the word `nexus_env_name_count` | `0` and `0`: never output content in syslog | 3.5 ("never output content") | `syslog_has_output_content=false` |
| N03 | In the mailbox, count mails for this run | exactly one, with the same fields and the first 4 KiB of masked output (the marker line is inside it) | 3.5 (SMTP, one mail per run end, first 4 KiB masked output) | `mails_for_run=1`, `mail_has_output_tail=true`, `mail_fields_present=true` |
| N04 | Run `zz-validation-slow` (T01 again) once with `notify` `syslog` or `smtp` (`adm script-set zz-validation-slow --notify <mode>`) | one message or mail for the `TIMED_OUT` run, whose outcome field says `TIMED_OUT` | 3.5 (one per run end, whatever the outcome) | `notification_for_timed_out_run=1`, `notification_outcome=TIMED_OUT` |
| N05 | `adm script-runs zz-validation-ok` for a recorded notification failure field, only if you saw no message in N01/N03 | a failure is recorded on the run and the run outcome is unchanged | 3.5 (best-effort; a notification failure never fails the run) | `notification_failure_recorded=<true|false>`, `run_outcome_unchanged=true` |

Set `notification_received=true` only when N01 or N03 passed.

### X — cleanup (always, even after a FAIL)

| Step | Action | Expected | Evidence |
|---|---|---|---|
| X01 | `adm script-set <id> --schedule clear` and `adm script-disable <id>` for each `zz-validation-*` id | exit `0`; no schedule remains | `scripts_enabled_after=0`, `schedules_remaining=0` |
| X02 | `K03` and `K04` | equal `K03_base` and `K04_base` | `objects_final_delta=0`, `active_sessions_final_delta=0` |
| X03 | Delete the throw-away credential-store entry `zz-validation-probe`; `rm -rf "$WORK"`; `unset probe`; `adm logout` and `opr logout` | done | `throwaway_credential_removed=true` |

Scripts cannot be deleted through any route (versions are a history); they stay as disabled records named
`zz-validation-*` — say so in the build record rather than hiding it.

## 5. Result block for the build record

Paste one line per step, in this exact shape; delete nothing, mark what was not done `NOT_RUN`. No host name, address,
user name, token, run id or output line belongs in it.

```
step id | PASS / FAIL / NOT_RUN | evidence tokens
P1-P7   | <PASS|FAIL>            | p1_slice3_live=<bool>, p2_runner_available=<bool>, p3_exec_image=<bool>, p4_receiver_configured=<bool>, p5_accounts=<bool>
V01     | <...>                  | add_exit=0, current_version=1
V02     | <...>                  | current_version=3, versions_count=3, versions_monotonic=<bool>
V03     | <...>                  | warn_add_exit=0, secret_findings_count=1, finding_class=CREDENTIAL_LITERAL, value_echoed=<bool>
V04     | <...>                  | body_key_count=0, marker_in_response=<bool>
V05     | <...>                  | enabled=<bool>, notify=<enum>
R01     | <...>                  | outcome=<enum>, exit_code=<n>, duration_ms=<n>
R02     | <...>                  | run_version=3, run_version_matches_current=<bool>
R03     | <...>                  | objects_during_run_gt_base=<bool>
R04     | <...>                  | objects_after_run_delta=<n>
R05     | <...>                  | active_sessions_delta=<n>
R06     | <...>                  | script_still_enabled=<bool>
R07     | <...>                  | nexus_env_names_ok=<bool>, scratch_roundtrip_ok=<bool>, root_fs_read_only_ok=<bool>, cli_read_ok=<bool>, cli_write_refused_ok=<bool>
R08     | <...>                  | stdout_truncated=<bool>, stderr_truncated=<bool>, has_output=<bool>
R09     | <...>                  | output_download_audit_rows=<n>
T01-T03 | <...>                  | outcome=<enum>, duration_ms=<n>, objects_after_run_delta=<n>, active_sessions_delta=<n>
E01-E03 | <...>                  | per_run_networkpolicy_during_run=<n>, egress_probe_connected=<bool>, outcome=<enum>, objects_after_run_delta=<n>
M01-M03 | <...>                  | refs_change_new_version=<bool>, plain_value_in_output=<bool>, base64_value_in_output=<bool>, output_masked_form_seen=<bool>, credential_refs_used_count=<n>
L01     | <...>                  | kind=py, outcome=<enum>, imports_ok=<bool>
S01-S05 | <...>                  | schedule_zone=<enum>, next_fire_zone_offset_ok=<bool>, scheduled_runs_delta=<n>, duplicate_fire=<bool>, disabled_fires_delta=<n>
N01-N05 | <...>                  | syslog_messages_for_run=<n>, syslog_has_output_content=<bool>, mails_for_run=<n>, mail_has_output_tail=<bool>, notification_received=<bool>
X01-X03 | <...>                  | scripts_enabled_after=<n>, objects_final_delta=<n>, active_sessions_final_delta=<n>
```

Also record, once, as plain facts: the build/image identifier the run was made against (the digest or release tag the
build record already names), the date, and `executor=PO`.

**Rule.** Any `FAIL` keeps `script_execution_module` at `AUTOMATED_VALIDATED` and opens a backlog item for that step
(the engineering session adds it through `tools/delivery/project_queue.py`). `NOT_RUN` is not a pass: a required step
left `NOT_RUN` keeps the item at `AUTOMATED_VALIDATED` as well, and the reason is written next to it (for the `N` steps:
`slice3_not_live` or `no_receiver`; for `R09`: `audit_read_surface=none`). Optional steps (M, L) may be `NOT_RUN`
without blocking. Only an all-`PASS` result block lets the Product Owner decide on `REAL_ENV_VALIDATED`; recording the
block is not that decision.
