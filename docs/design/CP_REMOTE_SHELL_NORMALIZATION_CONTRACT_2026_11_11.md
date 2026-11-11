# Check Point command normalization — no login shell, environment loaded explicitly (contract)

## Status

DRAFT — DO NOT IMPLEMENT. No literal in this document is approved. Only the **phase 1** literals (the three MDS export
rows, section 6.1) and the lab read M2 are put to the Product Owner (PO) now, through the PO session, per literal.
The phase 2 and 3 lists are **withdrawn from approval** until the lab check (section 2) shows where `vsenv` and
`mdsenv` are defined and each call site's transport is settled (section 3); Appendix 1 is a mechanical candidate kept
for review, not an approval list. Second review: `docs/design/ARGON_CP_SHELL_NORMALIZATION_REVIEW_2026_11_11.md`
(verdict REWORK on the full list; its findings are applied below). Trigger:
`docs/design/INCIDENT_2026_11_11_MDS_FORK_STORM.md`. MDS-specific fixes: `docs/design/MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md`.

PO direction (2026-11-11, verbatim): "bash lc kullanımı bizi çok zora soktu, sokabilme ihtimali de var önümüzdeki
günlerde. Sonuçta firewalları R82 taşıdğımızda neler olacağını bilmiyoruz bunları normalize etsek ya ? Backbox bash lc
kullanmıyor backup almak için". On the method (verbatim): "Seçenek 2'ye daha yakınım. Kabuk kullanmamam gerekiyor,
altı üstü ssh atıyorum. Ama buna arka planda çalışalım. Şimdilik R82 upgrade bir süre ertelenecek ama 1-2 gün üzerine
çalışırsak çözeriz bence." Declined: keeping `bash -lc` with `unset BASH_ENV` inside; any start literal that keeps a
shell around `mds_backup` in its old form (L1, L1b); the reads L5 and M1. Target release: R82 (upgrade postponed).

## 1. The rule

A Check Point command that needs the Check Point environment is sent **as it is**, preceded by one fixed prefix:

```
unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; <command>
```

- **No login shell and no extra shell.** The prefix and the command run in the one shell that SSH always starts for
  the account (section 2). neXus starts no `bash -l`, `bash -lc` or `bash -c` of its own — with one exception, the MDS
  start (below).
- **The Check Point environment is loaded explicitly**, from `/etc/profile.d/CP.sh`, the same entry point the login
  chain uses (`/etc/bashrc:52` → `/etc/profile.d/CP.sh:2` → `$CPDIR/tmp/.CPprofile.sh`; on the MDS
  `.CPprofile.sh:50` → `MDSprofile.sh`, measured on R82 on 2026-11-11). Its output is discarded so parsers see only the
  command's own output. `CP.sh` rather than `/opt/CPshrd-R82/tmp/.CPprofile.sh` keeps the text independent of the
  release directory.
- **`BASH_ENV` and `ENV` are unset before and after the profile**, so neither the profile nor any bash script the
  command starts can re-read start-up files — the mechanism of the 2026-11-11 storm — whichever file closes that
  loop. (A non-interactive bash reads the file named by `BASH_ENV` whatever options it was started with; only the
  unset protects it.)
- **The command is unchanged**, including `vsenv <VSID> && ...` and `mdsenv <DOMAIN> ...` exactly where today's
  contracts use them, and no new use of either. **Open:** on 2026-09-14 neXus measured that sourcing the Check Point
  profile (`$CPDIR/tmp/.CPprofile.sh`) inside `bash -c` does **not** define `vsenv` (exit 127), while `bash -lc` does
  (`docs/design/CP_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md` §8). So `vsenv` comes from somewhere else in the login
  chain; the prefix as written cannot serve the `vsenv` rows until the lab check finds that file. Because nothing is nested in quotes any more, commands that contain
  single quotes (the `mgmt_cli` rows) keep their text exactly.

**The one exception — the MDS export start.** `mds_backup` must keep running after the SSH channel closes and must leave
its exit code in a file (`mds_backup.rc`, the job's source of truth). A program started with `setsid nohup` cannot
write another program's exit code; only a shell can (`...; echo $? > file`). The start therefore keeps one small
**non-login, no-start-up-file** bash around `mds_backup`:

```
unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cd /var/log && setsid nohup bash --noprofile --norc -c "$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo \$? > %1$s/mds_backup.rc" </dev/null >/dev/null 2>&1 &
```

The inner bash is not a login shell and reads no start-up file because `BASH_ENV` was unset just before it starts
(`--noprofile --norc` adds nothing for a non-interactive shell; they are kept as a statement of intent). `$CPMDIR` is
filled in by the outer shell (double quotes) and `\$?` is left for the inner bash, so it does not depend on which
variables `MDSprofile.sh` exports. If the profile did not load, `$CPMDIR` is empty, `/scripts/mds_backup` is not
found, and the exit code file records 127 — a clean failure.

### 1.1 Why a shell is still involved at all (plain words for the PO)

1. **SSH itself runs every command through the account's shell.** OpenSSH `sshd(8)`: commands are run "via the
   user's shell using its `-c` option", under "the user's login shell as specified in the system password database".
   There is no "no shell" path over SSH exec. What neXus controls is only what that shell reads and loads.
2. **Check Point commands need the Check Point environment** (`$CPDIR`, `$FWDIR`, `$CPMDIR`, the search path, and the
   `vsenv`/`mdsenv` shell functions). It exists only inside a shell that has loaded the Check Point profile.
3. **What this contract removes** is everything neXus added on top: the login shell (`bash -lc`), which read root's
   `.bash_profile`, which exported `BASH_ENV`, which caused the storm.

Considered and rejected:
- `bash -lc 'unset BASH_ENV; <command>'` — declined by the PO; keeps the login shell.
- `env -u BASH_ENV -u ENV bash --noprofile --norc -c '. /etc/profile.d/CP.sh ...; <command>'` (revision 1 of this
  contract) — a second shell for every command; nested quoting for 38 `mgmt_cli` rows; nothing gained over the prefix.
- Calling binaries by absolute path with variables set inline (`CPDIR=... /opt/CPshrd-R82/bin/cpinfo`) — names the
  release directory, cannot provide `vsenv`/`mdsenv`, and still runs through the account's shell.

## 2. What the SSH exec path does (grounding and UNKNOWNs)

| Point | Status | Source |
|---|---|---|
| sshd runs an exec request through the account's shell with its `-c` option | Documented for OpenSSH; Gaia's sshd assumed to be OpenSSH (**UNKNOWN** until M2 shows `$0`) | OpenSSH `sshd(8)`, AUTHENTICATION and LOGIN PROCESS sections |
| sshd sets a basic environment; `~/.ssh/environment` only if `PermitUserEnvironment` is on (off by default); runs `~/.ssh/rc` or `/etc/ssh/sshrc` if present | Documented (OpenSSH); Gaia's settings **UNKNOWN** | `sshd(8)` |
| A non-interactive bash started by sshd reads `~/.bashrc` | Documented as bash behaviour ("Invoked by remote shell daemon"); whether Gaia's bash build enables it **UNKNOWN** | GNU Bash manual, "Bash Startup Files" |
| A non-interactive bash reads the file named by `BASH_ENV`, whatever options it was started with; `--noprofile`/`--norc` matter only for login and interactive shells | Documented | GNU Bash manual, "Bash Startup Files" |
| A Gaia account's shell is either Gaia Clish (`/etc/cli.sh`, the default) or `/bin/bash` (Expert), set with `set user <name> shell ...` in Clish | Documented | Check Point R82 Gaia Administration Guide ("Configuring the Gaia OS for SCP Connection", "Managing User Accounts in Gaia Clish"); R80.30 guide for `/etc/cli.sh` as default |
| The prefix works only for accounts whose shell is bash (Expert landing); a Clish-landing account receives Clish, which cannot parse it | Follows from the two rows above | — |
| In production, the exec shell already has the Check Point environment for some reads (bare `cphaprob`, `mgmt_cli`, `$MDSVERUTIL`, the `mdsenv` function work today) but not for all (2026-09-22: bare `cpinfo -y all` "not on PATH" on every gateway, `InventoryReadPlan.java:84-85`) | Observed; the cause is **UNKNOWN** (per account shell, per landing, or per release) | Repository history |
| Sourcing the Check Point profile in `bash -c` defines `vsenv` | **Measured: no** (exit 127, 2026-09-14); `bash -lc` does. Where `vsenv` is defined is **UNKNOWN** | `CP_INVENTORY_MEASUREMENT_FINDINGS_2026_09_14.md` §7-8; lab check M2 |
| `mdsenv`, `$MDSVERUTIL`, `$CPMDIR`, `mdsstat`, `cplic` available after sourcing `CP.sh` on the MDS | **UNKNOWN** (the R82 MDS chain reaches `MDSprofile.sh`) | Lab check M2; phase 1 acceptance run |
| Supported way to load the Check Point environment in a non-interactive shell | **UNKNOWN** — no official Check Point document found; CheckMates community scripts use `source /etc/profile.d/CP.sh` (plus `$MDSDIR/scripts/MDSprofile.sh` on MDS). Asked in the TAC report. | Community, not vendor authority |
| How BackBox runs Check Point backups | **UNKNOWN**. BackBox's public Check Point solution brief and integration page give no shell or command detail. The PO's statement is recorded as the PO's. | backbox.com (searched 2026-11-11) |

**Lab check M2 (new read, needs PO approval; replaces the declined M1).** One-off, class 0, on one R81.20 gateway
(a VSX member if possible), one R82 gateway when available, and the MDS, through the normal exec path with neXus's
own account:

`unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; echo "$0"; type -t vsenv mdsenv mdsstat cplic; echo ${#CPDIR} ${#FWDIR} ${#CPMDIR} ${#MDSVERUTIL}; grep -l vsenv /etc/profile /etc/bashrc /etc/profile.d/*.sh 2>/dev/null`

It answers, per device: which shell sshd started (`bash`, or a Clish error), which of `vsenv`, `mdsenv`, `mdsstat`,
`cplic` exist after the prefix, the lengths (not values) of four Check Point variables, and which system start-up
files mention `vsenv` (file names only). Nothing else is kept. Calling `vsenv 0` itself is not part of M2; once the
defining file is known, the phase 2 design is revised and reviewed again before any literal goes to the PO.

## 3. Landing, transports and interactive shells

- **Expert landing over the exec channel (account shell `/bin/bash`)**: the prefix form applies.
- **Clish landing**: an exec request goes to Clish (`/etc/cli.sh -c ...`), which cannot parse the prefix. neXus
  detects Clish landing per run (`clishLanding`, `InventoryCapabilityExecutor.java:277-288`) and uses Clish commands
  or an interactive session; the prefix form is never sent there, and a test pins this.
- **Interactive sessions are not prefixed.** Several reads are sent inside an interactive Expert session, not over
  the exec channel: the MDS `mgmt_cli` policy and discovery reads (`CheckPointPolicyCollector.java:919`,
  `CpShowChangesReader.java:219`, `MgmtCliEnumerationAdapter.java:308`, `ManagementPlaneEnumerationAdapter.java:274`),
  the MDS `cpinfo`/`cplic`/`cplp`/HA-status reads (`InventoryCapabilityExecutor.java:464-467`), and `cphaprob -a if`
  where the interactive shell is preferred. An interactive session is a login shell on the device, so root's
  `.bash_profile` runs and exports `BASH_ENV`. There the fix is S1: `unset BASH_ENV ENV` sent once, as soon as the
  Expert prompt is reached, in **both** places that open such a session — `InteractiveShellSession` (after its
  Expert transition) and the failover `FramedShell`, which opens its own channel (`FramedShell.java`,
  `SshExecTransport.openShellChannel`) and today bypasses the session setup.
- Residual risk: when a Clish-landing session runs `expert`, the new login shell runs root's start-up files before S1
  can be sent. The 2026-11-11 storm needed a bash script started *after* `BASH_ENV` was exported; root's
  `.bash_profile` exports it as its last step, so the window is the login itself. **UNKNOWN** whether anything runs
  in that window; M2 does not cover it.

Which rows travel over which transport is settled per call site before the phase 2 list is rebuilt; the mechanical
W2 classification in Appendix 1 does not yet reflect it (41 of its rows are interactive and must not be prefixed).

## 4. Inventory — where neXus builds a Check Point remote command today

### 4.1 Gate rows (the literals the device receives)

Live `gate_registry`, vendor `check_point`, 2026-11-11: 178 SSH rows.

| Group | Rows | Today | Changes |
|---|---|---|---|
| `bash -lc '...'` (W1) | 82 (77 gateway reads, 3 MDS export, 2 gateway class-2 failover) | login shell | prefix form (MDS start: W1*) |
| Bare Check Point binaries relying on the exec shell's implicit profile (W2): `mgmt_cli` 38, `cphaprob` 9, `fw` 3, `clusterXL_admin` 2 (class 2), `cpview`, `cpstat`, `cpinfo`, `vsx`, `cplic`, `cplp` 1 each | 58 | implicit profile | prefix form |
| Clish, plain OS reads with no Check Point variable (`df`, `cat`, `ls`, `mkdir`, `tar`, `sha256sum`, `uname`, `netstat`, `ip`, `arp`, `uptime`, `rm`), Spark CLI, failover shell framing | 38 | no Check Point environment needed | none |

80 of the 82 W1 rows are in `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`; 2
(`cp_inventory_cphaprob_a_m_if_vsenv0`, `cp_inventory_vsid_cphaprob_a_m_if`) exist only in migrations. All 140 rows,
old and new, are in Appendix 1.

### 4.2 Code that builds or wraps a remote command

| # | File:line | Vendor / role | Purpose | Current literal or template |
|---|---|---|---|---|
| C1 | `ui2/worker/.../backup/cp/MdsExportPlan.java:18, 20, 26-27` | CP MDS | MDS export context reads and `mds_backup` start | `bash -lc 'mdsstat' > %s/...`, `bash -lc 'cplic print -x' > ...`, `cd /var/log && setsid nohup bash -lc '$CPMDIR/scripts/mds_backup ...'` |
| C2 | `ui2/service/.../boot/DeviceCompositionConfiguration.java:403-406` | CP MDS | service-side copy of the MDS export literal set | same as C1 |
| C3 | `ui2/worker/.../backup/BackupCapabilityExecutor.java:391-407` | CP all | login-shell retry when a Clish read answers empty (show reads, `add backup local`) | `bash -lc '<clish command>'` |
| C4 | `ui2/worker/.../diagnostic/DiagnosticJobExecutor.java:101-103` | CP gateway | wraps every gateway diagnostic read on the exec path | `bash -lc '<read>'` |
| C5 | `ui2/worker/.../inventory/InventoryCapabilityExecutor.java:1616` | CP gateway | fallback when a `cphaprob`/`vsx` read answers empty or from Clish | `bash -lc '<command>'` |
| C6 | `ui2/worker/.../inventory/InventoryCapabilityExecutor.java:1671` | CP VSX | physical-context wrapper for single reads | `bash -lc 'vsenv 0 >/dev/null 2>&1 \|\| true; <read>'` |
| C7 | `ui2/worker/.../inventory/InventoryCapabilityExecutor.java:1822-1830` | CP gateway / VSX | batched physical reads with section markers | `bash -lc 'echo "===NEXUS_SECTION:ifs==="; [vsenv 0 >/dev/null 2>&1 \|\| true; ]fw getifs; ...'` |
| C8 | `ui2/worker/.../inventory/InventoryReadPlan.java:86, 99, 100, 121, 197` | CP gateway / MDS | `cpinfo -y all`, `cplic print`, `cplp list`, `cpstat -f policy fw`, per-VSID reads | `bash -lc '...'`, `bash -lc 'vsenv <vsid> && <reads>'` |
| C9 | `ui2/worker/.../failover/CpFailoverJobExecutor.java:456-457` (gate key for reads **and** the class-2 DOWN/UP), `:578` (class-2 DOWN/UP execution), `:599` (reads) | CP gateway | failover reads, failover state change, their gate keys | `bash -lc 'vsenv 0 && <cmd>'`, `bash -lc '<cmd>'`, `bash -lc 'vsenv <VSID> && <cmd>'` |
| C10 | `ui2/worker/.../failover/CpFailoverJobExecutor.java:602-609` | CP gateway | the same reads inside the member's interactive login shell (framed) | `vsenv <VSID> && <read>` inside `( ... )` |
| C11 | `ui2/capability-registry/.../GateRow.java:67-69` | CP gateway | pins the two class-2 failover literals | `bash -lc 'vsenv <VSID> && clusterXL_admin down\|up'` |
| C12 | `ui2/job-engine/.../pathvalidation/PathValidation.java:58, 60` | CP gateway | path validation route lookup | `bash -lc 'ip -4 route get <DST>'`, `bash -lc 'vsenv <VSID> && ip -4 route get <DST>'` |
| C13 | `ui2/job-engine/.../systemlog/CpSystemLogCollect.java:68-76` | CP gateway | system-log stat and listings | `bash -lc 'date +%s; stat ...'`, `bash -lc 'ls -lgo ...'` |
| C14 | `ui2/job-engine/.../systemlog/CpSpikeLayoutReview.java:52, 54` | CP gateway | spike-detective listings | `bash -lc 'ls -lgo ... /var/log/spike_detective/...'` |
| C15 | `ui2/worker/.../discovery/cp/ManagementShellCommands.java:31-34, 48, 67-68` | CP MDS | management-plane discovery (closed prefix set, not gate rows) | `$MDSVERUTIL AllCMAs`; `mdsenv '<DOMAIN>' >/dev/null 2>&1 && cpmiquerybin object "" network_objects "type='<TYPE>'" 2>/dev/null` |
| C16 | `ui2/worker/.../transport/ssh/SshExecTransport.java:358-380, 431` (`InteractiveShellSession`) | CP, others | interactive PTY shell: Clish-landing devices, failover framed shell, configuration reads | a login shell on the device |

Earlier signal: on 2026-11-06/07, `bash -lc 'cpinfo -y all'` on the R82 MDS answered nothing within 60 s and was
moved to the interactive shell (V195, `InventoryReadPlan.java:87-90`); the reason was not established.

Added after the second review — sites that build or match Check Point command text and must change with any phase 2
list:

| # | File:line | Vendor / role | Purpose | Current literal or template |
|---|---|---|---|---|
| C17 | `ui2/service/.../boot/DeviceCompositionConfiguration.java:100-103, 303-318` | CP gateway | service-side copy of the physical inventory reads, used to admit `cp_inventory_collect` | `fw getifs`, `cphaprob stat`, `cphaprob -a if`, `vsx stat -v`, ... |
| C18 | `ui2/capability-registry/src/main/resources/capabilities/cp_inventory_collect.yaml:49-74` | CP gateway | capability steps | same bare reads |
| C19 | `ui2/worker/.../confirm/DeviceFirstContactCommandSet.java:35-36`, `ConfirmCapabilityExecutor.java:119-121` | CP | enrolment confirm, exec first, interactive fallback | `cphaprob stat` |
| C20 | `ui2/worker/.../discovery/cp/MgmtCliCommands.java`; `ui2/job-engine/.../policy/CpPolicyGates.java:11-94`, `PolicyHitGates.java:9-21`, `PolicyCompletenessGates.java:67-74` | CP MDS | `mgmt_cli` command text and the gate keys policy jobs require | `mgmt_cli -r true -d '<DOMAIN>' ...` |
| C21 | `ui2/worker/.../transcript/JobTranscript.java:78-79` | CP MDS | redacts `mgmt_cli` arguments in transcripts by `startsWith("mgmt_cli ")` — a prefix would bypass it | (matcher) |
| C22 | `ui2/worker/.../failover/FramedShell.java`, `SshExecTransport.java:455-473` | CP gateway | failover framed interactive shell, own channel | login shell |

Searched and found nothing: other vendors' remote commands (no `bash -l`, `--login`, `su -`, `sudo -i`, profile
sourcing in `ui2/*/src/main/java` outside Check Point); legacy Python (`checkpoint/`, `utils/`): no `bash -lc`.

## 5. Implementation shape

1. One class, `CheckPointCommand` in `ui2/capability-registry` (a module both the worker and the job engine depend
   on): `PREFIX` holds the fixed prefix, `withEnvironment(String command)` returns `PREFIX + command`,
   `INTERACTIVE_SETUP` holds S1, and `mdsBackupStart(...)` builds the one W1* literal. Every site in 4.2 calls it; no
   other code may build `bash ... -c` text for Check Point.
2. Gate rows keep the full text as `canonical_command_key` (today's model). Templates (`<VSID>`, `<DOMAIN>`, `%s`) are
   substituted inside the command part only; the prefix contains no placeholder and no `%`. A test asserts
   `withEnvironment(template)` equals the gate key for every row in Appendix 1.
3. One Flyway migration per phase **adds** the new rows next to the old ones (both valid for one release), so a pod
   still on old code during the rollout does not lose its gate lookups; a later migration removes the old rows. The
   fixture, every Java constant and capability file that carries the text (4.2, including C17-C21), and the
   literal-pinning tests change in the same commit. Migration dry run on the live database (BEGIN/ROLLBACK) before
   deploy.
4. **Fail-closed fix:** C6 and C7 use `vsenv 0 >/dev/null 2>&1 || true;`, which runs the read in the landing context
   if `vsenv` is missing. The prefix form uses `vsenv 0 >/dev/null 2>&1 && ` instead, so a missing `vsenv` fails the
   read (B6/B7).
5. **Transcript redaction** (C21) matches the command after the prefix, not the start of the string.
6. **Architecture test** (`ui2/architecture-tests`): fails if any string in `ui2/*/src/main/java`,
   `gate_registry_fixture.yaml` or `capabilities/*.yaml` contains `bash -lc`, `bash -l `, `bash --login`, `-lc '`,
   `su - ` or `sudo -i`; fails if `bash ` appears in a Check Point literal other than the W1* MDS start; and fails if a
   `check_point` SSH_EXEC literal starts with a W2 binary without the prefix. Historical migrations are excluded
   (immutable); the latest state is checked through the fixture equality test. It also asserts that every Check Point
   SSH_EXEC step in `DeviceCompositionConfiguration`, the inventory capabilities, `CpPolicyGates` and
   `cp_inventory_collect.yaml` resolves against `gate_registry_fixture.yaml`.

## 6. Approval list

### 6.1 Put to the PO now: phase 1 (MDS export) and M2

| # | gate_id | Old | New |
|---|---|---|---|
| 1.1 | `mds_backup_start` | `cd /var/log && setsid nohup bash -lc '$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo $? > %1$s/mds_backup.rc' </dev/null >/dev/null 2>&1 &` | `unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cd /var/log && setsid nohup bash --noprofile --norc -c "$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo \$? > %1$s/mds_backup.rc" </dev/null >/dev/null 2>&1 &` |
| 1.2 | `mds_mdsstat` | `bash -lc 'mdsstat' > %s/mdsstat.txt 2>&1` | `unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mdsstat > %s/mdsstat.txt 2>&1` |
| 1.3 | `mds_cplic_print` | `bash -lc 'cplic print -x' > %s/cplic.txt 2>&1` | `unset BASH_ENV ENV; . /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cplic print -x > %s/cplic.txt 2>&1` |
| M2 | lab read (section 2) | (none) | as in section 2 |

None of the three uses `vsenv` or `mdsenv`. The context reads 1.2 and 1.3 are best effort (a failure gives a smaller
bundle, never a failed export); 1.1 fails cleanly (exit code 127 in the file) if the profile does not load. The
service-side copy (`DeviceCompositionConfiguration.java:403-406`) changes with them.

### 6.2 Not put to the PO yet

Phases 2 and 3 (Appendix 1, 137 rows), the code templates below and S1 wait for M2 and the per-site transport
review (section 3). Templates as they stand, for review only (the prefix shown is the revised one):

| # | Site | Old | Candidate |
|---|---|---|---|
| B1 | C3 Clish retry | `bash -lc '<clish command>'` | prefix + `<clish command>` |
| B2 | C4 diagnostic | `bash -lc '<read>'` | prefix + `<read>` |
| B3 | C5 inventory fallback | `bash -lc '<command>'` | prefix + `<command>` |
| B4 | C8 per-VSID reads | `bash -lc 'vsenv <vsid> && <reads>'` | needs the `vsenv` source (M2) |
| B5 | C9 failover read keys | `bash -lc '<cmd>'`, `bash -lc 'vsenv ... && <cmd>'` | needs the `vsenv` source (M2) |
| B6 | C6 VSX single read | `bash -lc 'vsenv 0 >/dev/null 2>&1 \|\| true; <read>'` | needs the `vsenv` source; fail-closed `&&` proposed (the second review prefers keeping `\|\| true` for availability — open) |
| B7 | C7 batch | `bash -lc '<sections with vsenv 0 ... \|\| true>'` | as B6 |
| B8 | C15 domain list | `$MDSVERUTIL AllCMAs` | prefix + same |
| B9 | C15 domain object query | `mdsenv '<DOMAIN>' ... && cpmiquerybin ...` | needs the `mdsenv` source (M2) |
| S1 | interactive Expert sessions (`InteractiveShellSession`, `FramedShell`) | (none) | `unset BASH_ENV ENV` once after the Expert prompt |

## 7. Rollout and verification

Background work over one to two days (PO); the R82 fleet upgrade is postponed.

1. **Phase 1 (MDS export, 3 rows), after PO approval.** Ships with the MDS fix contract. The MDS export stays manual only; the first run
   is by hand under the PO's watch (that contract, section F).
2. **Lab check before phase 2.** M2 on one R81.20 gateway, one R82 gateway when available, and the MDS. Then the
   phase 2 design is revised (the `vsenv`/`mdsenv` source, per-site transports), reviewed again through
   `scripts/consult_argon.py`, and only then put to the PO. After approval, each phase-2 command family runs once with
   the old and once with the new literal; output digests compared locally and
   reported `MATCH` / `MISMATCH` per family, never the output. Any `MISMATCH` stops phase 2 for that family.
3. **Phase 2 (rebuilt list, B1-B9, S1)** fleet-wide after a clean comparison.
4. **Phase 3 (4 class-2 failover rows)** only after a failover review through the `scripts/consult_*` path and a
   failover dry run; `GateRow.java` changes in the same commit. In phase 2, `CpFailoverJobExecutor` changes only its
   read branch: `gate()` (lines 456-457) keeps building the old key for DOWN/UP, and line 578 (the DOWN/UP execution)
   is untouched until phase 3, so the class-2 gate lookup does not break in between.
5. Every phase: Java lane (`jt.sh`) and `tools/tests` before ship; ship through `shipq2.sh`.

## 8. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | The prefix loads only `CP.sh`; a W1 command that depended on something only the login files set (`/etc/profile`: PATH entries, umask, limits) behaves differently. W2 commands already ran without the login files. | Lab digest comparison per family; phase gates. |
| R2 | `vsenv` missing after the prefix makes `\|\| true` reads run in the wrong context. | B6/B7 fail-closed; M2. |
| R3 | A Clish-landing account receives the prefix form. | Never sent on Clish landing (section 3); test. |
| R4 | Older releases may lack `/etc/profile.d/CP.sh`. | M2 per release; a missing environment fails loudly (command not found, exit 127), never silently. |
| R5 | Interactive sessions keep a login shell. | S1. |
| R6 | The exec shell may also read `~/.bashrc` on its own (sshd detection), so `CP.sh` may be loaded twice. | Harmless: the login chain already loads it more than once; `BASH_ENV` is unset either way. |
| R7 | 140 changed literals in one approval. | Generated mechanically from the live gate table by one rule; phases ship separately. |

## Cross-references

- `docs/design/INCIDENT_2026_11_11_MDS_FORK_STORM.md`
- `docs/design/MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md`
- `docs/design/ARGON_MDS_FORK_STORM_REVIEW_2026_11_11.md`
- `docs/design/ARGON_CP_SHELL_NORMALIZATION_REVIEW_2026_11_11.md`
- `docs/AI_DEVELOPMENT_PROTOCOL.md`
- TAC/R&D report (PO session, shared document): https://claude.ai/artifact/AJJhAPf8QEhvmjwoPC5udR

## Appendix 1 — gate rows, mechanical candidate (NOT an approval list)

Generated from the live `gate_registry` on 2026-11-11 by the rule in section 1 (W1: `bash -lc '<command>'` →
prefix + command, any redirection after the closing quote kept; W2: prefix + whole literal; W1*: the MDS start).
Shown with the first prefix form (`. /etc/profile.d/CP.sh ...; unset BASH_ENV ENV;`); the revised prefix also unsets
before sourcing. Known defects, from the second review: the `vsenv`/`mdsenv` rows cannot work as written (section 2);
41 W2 rows are sent inside interactive sessions and must not be prefixed (section 3). Only rows 1.1-1.3 are put to the
PO, in their revised form (section 6.1).

### Phase 1 — MDS export (blocks the first watched manual export): 3 rows

| # | gate_id | role | class | type | old literal | new literal |
|---|---|---|---|---|---|---|
| 1.1 | `mds_backup_start` | cp_multi_domain_server | recovery-write | W1* | `cd /var/log && setsid nohup bash -lc '$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo $? > %1$s/mds_backup.rc' </dev/null >/dev/null 2>&1 &` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cd /var/log && setsid nohup bash --noprofile --norc -c "$CPMDIR/scripts/mds_backup -b -l -d %1$s > %1$s/mds_backup.log 2>&1; echo \$? > %1$s/mds_backup.rc" </dev/null >/dev/null 2>&1 &` |
| 1.2 | `mds_cplic_print` | cp_multi_domain_server | read | W1 | `bash -lc 'cplic print -x' > %s/cplic.txt 2>&1` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cplic print -x > %s/cplic.txt 2>&1` |
| 1.3 | `mds_mdsstat` | cp_multi_domain_server | read | W1 | `bash -lc 'mdsstat' > %s/mdsstat.txt 2>&1` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mdsstat > %s/mdsstat.txt 2>&1` |

### Phase 2 — every other Check Point read: 133 rows

| # | gate_id | role | class | type | old literal | new literal |
|---|---|---|---|---|---|---|
| 2.1 | `cp_diagnostic_cpview_measure` | cp_gaia_gateway | read | W2 | `cpview -p` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cpview -p` |
| 2.2 | `cp_failover_arp_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && arp -an'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && arp -an` |
| 2.3 | `cp_failover_arp_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && arp -an'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && arp -an` |
| 2.4 | `cp_failover_bond_members` | cp_gaia_gateway | read | W2 | `cphaprob show_bond <BOND>` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob show_bond <BOND>` |
| 2.5 | `cp_failover_bond_members_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cphaprob show_bond <BOND>'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cphaprob show_bond <BOND>` |
| 2.6 | `cp_failover_bond_members_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cphaprob show_bond <BOND>'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cphaprob show_bond <BOND>` |
| 2.7 | `cp_failover_bonds` | cp_gaia_gateway | read | W2 | `cphaprob show_bond` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob show_bond` |
| 2.8 | `cp_failover_bonds_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cphaprob show_bond'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cphaprob show_bond` |
| 2.9 | `cp_failover_bonds_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cphaprob show_bond'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cphaprob show_bond` |
| 2.10 | `cp_failover_connections` | cp_gaia_gateway | read | W2 | `fw tab -t connections -s` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; fw tab -t connections -s` |
| 2.11 | `cp_failover_connections_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && fw tab -t connections -s'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && fw tab -t connections -s` |
| 2.12 | `cp_failover_fw_stat` | cp_gaia_gateway | read | W2 | `fw stat` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; fw stat` |
| 2.13 | `cp_failover_fw_stat_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && fw stat'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && fw stat` |
| 2.14 | `cp_failover_fw_stat_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && fw stat'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && fw stat` |
| 2.15 | `cp_failover_last_event` | cp_gaia_gateway | read | W2 | `cphaprob show_failover` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob show_failover` |
| 2.16 | `cp_failover_last_event_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cphaprob show_failover'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cphaprob show_failover` |
| 2.17 | `cp_failover_last_event_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cphaprob show_failover'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cphaprob show_failover` |
| 2.18 | `cp_failover_pnotes` | cp_gaia_gateway | read | W2 | `cphaprob -ia list` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob -ia list` |
| 2.19 | `cp_failover_pnotes_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cphaprob -ia list'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cphaprob -ia list` |
| 2.20 | `cp_failover_pnotes_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cphaprob -ia list'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cphaprob -ia list` |
| 2.21 | `cp_failover_routing` | cp_gaia_gateway | read | W2 | `cpstat os -f routing` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cpstat os -f routing` |
| 2.22 | `cp_failover_routing_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cpstat os -f routing'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cpstat os -f routing` |
| 2.23 | `cp_failover_routing_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cpstat os -f routing'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cpstat os -f routing` |
| 2.24 | `cp_failover_syncstat` | cp_gaia_gateway | read | W2 | `cphaprob syncstat` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob syncstat` |
| 2.25 | `cp_failover_syncstat_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cphaprob syncstat'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cphaprob syncstat` |
| 2.26 | `cp_failover_syncstat_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cphaprob syncstat'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cphaprob syncstat` |
| 2.27 | `cp_failover_tablestat` | cp_gaia_gateway | read | W2 | `cphaprob tablestat` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob tablestat` |
| 2.28 | `cp_failover_tablestat_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cphaprob tablestat'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cphaprob tablestat` |
| 2.29 | `cp_failover_tablestat_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cphaprob tablestat'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cphaprob tablestat` |
| 2.30 | `cp_failover_traffic_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cat /proc/net/dev'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cat /proc/net/dev` |
| 2.31 | `cp_failover_traffic_vsid` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cat /proc/net/dev'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cat /proc/net/dev` |
| 2.32 | `cp_identity_cpinfo_hotfixes` | cp_gaia_gateway | read | W1 | `bash -lc 'cpinfo -y all'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cpinfo -y all` |
| 2.33 | `cp_identity_cpinfo_hotfixes_mds` | cp_multi_domain_server | read | W2 | `cpinfo -y all` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cpinfo -y all` |
| 2.34 | `cp_identity_mds_ha_status` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -f json show-ha-status` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -f json show-ha-status` |
| 2.35 | `cp_identity_mds_ha_status_accept_once` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true --unsafe-auto-accept true -f json show-ha-status` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true --unsafe-auto-accept true -f json show-ha-status` |
| 2.36 | `cp_inventory_cphaprob_a_if` | cp_gaia_gateway | read | W2 | `cphaprob -a if` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob -a if` |
| 2.37 | `cp_inventory_cphaprob_a_if_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cphaprob -a if'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cphaprob -a if` |
| 2.38 | `cp_inventory_cphaprob_a_m_if` | cp_gaia_gateway | read | W2 | `cphaprob -a -m if` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob -a -m if` |
| 2.39 | `cp_inventory_cphaprob_a_m_if_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cphaprob -a -m if'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cphaprob -a -m if` |
| 2.40 | `cp_inventory_cphaprob_stat` | cp_gaia_gateway | read | W2 | `cphaprob stat` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob stat` |
| 2.41 | `cp_inventory_cphaprob_stat_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cphaprob stat'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cphaprob stat` |
| 2.42 | `cp_inventory_fw_getifs` | cp_gaia_gateway | read | W2 | `fw getifs` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; fw getifs` |
| 2.43 | `cp_inventory_fw_getifs_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && fw getifs'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && fw getifs` |
| 2.44 | `cp_inventory_ip_addr_show_state_only_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && ip -4 addr show'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && ip -4 addr show` |
| 2.45 | `cp_inventory_ip_addr_show_v4_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && ip -details -4 addr show'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && ip -details -4 addr show` |
| 2.46 | `cp_inventory_ip_addr_show_v6_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && ip -6 addr show'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && ip -6 addr show` |
| 2.47 | `cp_inventory_ip_link_show_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && ip -o link show'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && ip -o link show` |
| 2.48 | `cp_inventory_ip_route_show_table_all_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && ip -4 route show table all'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && ip -4 route show table all` |
| 2.49 | `cp_inventory_vsid_addr_and_route` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && ip -4 addr show && ip -4 route show'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && ip -4 addr show && ip -4 route show` |
| 2.50 | `cp_inventory_vsid_cphaprob_a_if` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cphaprob -a if'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cphaprob -a if` |
| 2.51 | `cp_inventory_vsid_cphaprob_a_m_if` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cphaprob -a -m if'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cphaprob -a -m if` |
| 2.52 | `cp_inventory_vsid_cphaprob_stat` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && cphaprob stat'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && cphaprob stat` |
| 2.53 | `cp_inventory_vsid_fw_getifs` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && fw getifs'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && fw getifs` |
| 2.54 | `cp_inventory_vsid_fw_getifs_and_route` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && fw getifs && ip -4 route show'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && fw getifs && ip -4 route show` |
| 2.55 | `cp_inventory_vsid_ip_addr_show_state_only` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && ip -4 addr show'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && ip -4 addr show` |
| 2.56 | `cp_inventory_vsid_ip_route_show_table_all` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && ip -4 route show table all'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && ip -4 route show table all` |
| 2.57 | `cp_inventory_vsx_stat_v` | cp_gaia_gateway | read | W2 | `vsx stat -v` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsx stat -v` |
| 2.58 | `cp_lifecycle_cplic_print` | cp_gaia_gateway | read | W1 | `bash -lc 'cplic print'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cplic print` |
| 2.59 | `cp_lifecycle_cplic_print_mds` | cp_multi_domain_server | read | W2 | `cplic print` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cplic print` |
| 2.60 | `cp_lifecycle_cplic_print_vsx` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cplic print'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cplic print` |
| 2.61 | `cp_lifecycle_cplp_list` | cp_gaia_gateway | read | W1 | `bash -lc 'cplp list'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cplp list` |
| 2.62 | `cp_lifecycle_cplp_list_mds` | cp_multi_domain_server | read | W2 | `cplp list` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cplp list` |
| 2.63 | `cp_lifecycle_cplp_list_vsx` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cplp list'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cplp list` |
| 2.64 | `cp_localdiag_cat_fw1logs_cpbak` | cp_gaia_gateway | read | W1 | `bash -lc 'cat /var/CPbackup/schemes/fw1logs.cpbak'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cat /var/CPbackup/schemes/fw1logs.cpbak` |
| 2.65 | `cp_localdiag_cphaprob_state` | cp_gaia_gateway | read | W1 | `bash -lc 'cphaprob state'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cphaprob state` |
| 2.66 | `cp_localdiag_cpstat_fw_log_connection` | cp_gaia_gateway | read | W1 | `bash -lc 'cpstat fw -f log_connection'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cpstat fw -f log_connection` |
| 2.67 | `cp_localdiag_du_fwdir_log` | cp_gaia_gateway | read | W1 | `bash -lc 'du -sh $FWDIR/log'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; du -sh $FWDIR/log` |
| 2.68 | `cp_localdiag_du_fwdir_log_dir` | cp_gaia_gateway | read | W1 | `bash -lc 'du -sh $FWDIR/log/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; du -sh $FWDIR/log/` |
| 2.69 | `cp_localdiag_du_fwdir_log_gib` | cp_gaia_gateway | read | W1 | `bash -lc 'du -s --block-size=1073741824 $FWDIR/log/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; du -s --block-size=1073741824 $FWDIR/log/` |
| 2.70 | `cp_localdiag_ls_fwdir_log` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -la $FWDIR/log \| tail -20'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -la $FWDIR/log \| tail -20` |
| 2.71 | `cp_localdiag_ls_fwdir_log_dir` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -la $FWDIR/log/ \| tail -20'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -la $FWDIR/log/ \| tail -20` |
| 2.72 | `cp_localdiag_ls_fwdir_log_newest` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lt --time-style=+%Y-%m-%d $FWDIR/log/ \| head -20'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lt --time-style=+%Y-%m-%d $FWDIR/log/ \| head -20` |
| 2.73 | `cp_policy_access_roles` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-access-roles limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-access-roles limit 50 offset '<N>' details-level full` |
| 2.74 | `cp_policy_access_rulebase` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase name '<LAYER>' limit 100 offset '<N>' details-level full use-object-dictionary true` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase name '<LAYER>' limit 100 offset '<N>' details-level full use-object-dictionary true` |
| 2.75 | `cp_policy_access_rulebase_hits` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase name '<LAYER>' limit 100 offset '<N>' details-level full use-object-dictionary true show-hits true` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase name '<LAYER>' limit 100 offset '<N>' details-level full use-object-dictionary true show-hits true` |
| 2.76 | `cp_policy_access_rulebase_uid` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase uid '<UID>' limit 100 offset '<N>' details-level full use-object-dictionary true` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase uid '<UID>' limit 100 offset '<N>' details-level full use-object-dictionary true` |
| 2.77 | `cp_policy_access_rulebase_uid_hits` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase uid '<UID>' limit 100 offset '<N>' details-level full use-object-dictionary true show-hits true` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase uid '<UID>' limit 100 offset '<N>' details-level full use-object-dictionary true show-hits true` |
| 2.78 | `cp_policy_address_ranges` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-address-ranges limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-address-ranges limit 50 offset '<N>' details-level full` |
| 2.79 | `cp_policy_dns_domains` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-dns-domains limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-dns-domains limit 50 offset '<N>' details-level full` |
| 2.80 | `cp_policy_dynamic_objects` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-dynamic-objects limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-dynamic-objects limit 50 offset '<N>' details-level full` |
| 2.81 | `cp_policy_gateways_and_servers` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-gateways-and-servers limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-gateways-and-servers limit 50 offset '<N>' details-level full` |
| 2.82 | `cp_policy_groups` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-groups limit 50 offset '<N>' details-level full dereference-group-members false` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-groups limit 50 offset '<N>' details-level full dereference-group-members false` |
| 2.83 | `cp_policy_groups_with_exclusion` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-groups-with-exclusion limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-groups-with-exclusion limit 50 offset '<N>' details-level full` |
| 2.84 | `cp_policy_hosts` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-hosts limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-hosts limit 50 offset '<N>' details-level full` |
| 2.85 | `cp_policy_install_cpstat` | cp_gaia_gateway | read | W1 | `bash -lc 'cpstat -f policy fw'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; cpstat -f policy fw` |
| 2.86 | `cp_policy_install_cpstat_vsenv0` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv 0 && cpstat -f policy fw'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv 0 && cpstat -f policy fw` |
| 2.87 | `cp_policy_last_published_session` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-last-published-session` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-last-published-session` |
| 2.88 | `cp_policy_nat_rulebase` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 500 offset '<N>' details-level standard use-object-dictionary true` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 500 offset '<N>' details-level standard use-object-dictionary true` |
| 2.89 | `cp_policy_nat_rulebase_by_uid` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 500 offset '<N>' details-level standard use-object-dictionary true` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 500 offset '<N>' details-level standard use-object-dictionary true` |
| 2.90 | `cp_policy_nat_rulebase_page100` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 100 offset '<N>' details-level standard use-object-dictionary true` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 100 offset '<N>' details-level standard use-object-dictionary true` |
| 2.91 | `cp_policy_nat_rulebase_uid_probe` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 1 offset 0 details-level standard use-object-dictionary true` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 1 offset 0 details-level standard use-object-dictionary true` |
| 2.92 | `cp_policy_networks` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-networks limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-networks limit 50 offset '<N>' details-level full` |
| 2.93 | `cp_policy_packages` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 500 details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 500 details-level full` |
| 2.94 | `cp_policy_packages_50` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 50 offset '<N>' details-level full` |
| 2.95 | `cp_policy_packages_paged` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 20 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 20 offset '<N>' details-level full` |
| 2.96 | `cp_policy_security_zones` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-security-zones limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-security-zones limit 50 offset '<N>' details-level full` |
| 2.97 | `cp_policy_service_groups` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-service-groups limit 50 offset '<N>' details-level full dereference-group-members false` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-service-groups limit 50 offset '<N>' details-level full dereference-group-members false` |
| 2.98 | `cp_policy_services_dce_rpc` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-services-dce-rpc limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-services-dce-rpc limit 50 offset '<N>' details-level full` |
| 2.99 | `cp_policy_services_icmp` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-services-icmp limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-services-icmp limit 50 offset '<N>' details-level full` |
| 2.100 | `cp_policy_services_icmp6` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-services-icmp6 limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-services-icmp6 limit 50 offset '<N>' details-level full` |
| 2.101 | `cp_policy_services_other` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-services-other limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-services-other limit 50 offset '<N>' details-level full` |
| 2.102 | `cp_policy_services_rpc` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-services-rpc limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-services-rpc limit 50 offset '<N>' details-level full` |
| 2.103 | `cp_policy_services_sctp` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-services-sctp limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-services-sctp limit 50 offset '<N>' details-level full` |
| 2.104 | `cp_policy_services_tcp` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-services-tcp limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-services-tcp limit 50 offset '<N>' details-level full` |
| 2.105 | `cp_policy_services_udp` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-services-udp limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-services-udp limit 50 offset '<N>' details-level full` |
| 2.106 | `cp_policy_show_changes` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-changes from-date '<FROM>' to-date '<TO>' limit 10 offset '<N>'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-changes from-date '<FROM>' to-date '<TO>' limit 10 offset '<N>'` |
| 2.107 | `cp_policy_show_task` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-task task-id '<UID>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-task task-id '<UID>' details-level full` |
| 2.108 | `cp_policy_time_groups` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-time-groups limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-time-groups limit 50 offset '<N>' details-level full` |
| 2.109 | `cp_policy_times` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-times limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-times limit 50 offset '<N>' details-level full` |
| 2.110 | `cp_policy_unused_objects` | cp_multi_domain_server | read | W2 | `mgmt_cli -r true -d '<DOMAIN>' -f json show-unused-objects limit 50 offset '<N>' details-level full` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; mgmt_cli -r true -d '<DOMAIN>' -f json show-unused-objects limit 50 offset '<N>' details-level full` |
| 2.111 | `cp_syslog_collect_ls_crash` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/` |
| 2.112 | `cp_syslog_collect_ls_hcp` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lgo --time-style=+%s /var/log/hcp/last/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/hcp/last/` |
| 2.113 | `cp_syslog_collect_ls_sa` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lgo --time-style=+%s /var/log/sa/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/sa/` |
| 2.114 | `cp_syslog_collect_ls_spike` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/spike_detective/` |
| 2.115 | `cp_syslog_collect_ls_spike_dir` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/<dir>/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/spike_detective/<dir>/` |
| 2.116 | `cp_syslog_collect_stat` | cp_gaia_gateway | read | W1 | `bash -lc 'date +%s; stat -c "%n %i %s %Y" /var/log/messages* $FWDIR/log/fwd.elg* $CPDIR/log/cpd.elg* $CPDIR/log/cpwd.elg* $FWDIR/log/fwk.elg* $FWDIR/log/mq_mng.elg* /var/log/spike_detective/spike_detective.log* /var/log/spike_detective/spike_detective.csv*'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; date +%s; stat -c "%n %i %s %Y" /var/log/messages* $FWDIR/log/fwd.elg* $CPDIR/log/cpd.elg* $CPDIR/log/cpwd.elg* $FWDIR/log/fwk.elg* $FWDIR/log/mq_mng.elg* /var/log/spike_detective/spike_detective.log* /var/log/spike_detective/spike_detective.csv*` |
| 2.117 | `cp_syslog_measure_hcp_listing` | cp_gaia_gateway | read | W1 | `bash -lc 'f=$(ls -t /var/log/hcp/last/*.tar.gz \| head -1); tar -tzvf "$f" \| head -n 50'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; f=$(ls -t /var/log/hcp/last/*.tar.gz \| head -1); tar -tzvf "$f" \| head -n 50` |
| 2.118 | `cp_syslog_measure_head_sar` | cp_gaia_gateway | read | W1 | `bash -lc 'head -n 20 /var/log/sa/sar$(date -d yesterday +%d)'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; head -n 20 /var/log/sa/sar$(date -d yesterday +%d)` |
| 2.119 | `cp_syslog_measure_ls_crash` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/` |
| 2.120 | `cp_syslog_measure_ls_hcp` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lgo --time-style=+%s /var/log/hcp/last/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/hcp/last/` |
| 2.121 | `cp_syslog_measure_ls_sa` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lgo --time-style=+%s /var/log/sa/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/sa/` |
| 2.122 | `cp_syslog_measure_ls_spike` | cp_gaia_gateway | read | W1 | `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ls -lgo --time-style=+%s /var/log/spike_detective/` |
| 2.123 | `cp_syslog_measure_stat` | cp_gaia_gateway | read | W1 | `bash -lc 'stat -c "%i %s %Y" /var/log/messages /var/log/messages.1 $FWDIR/log/fwd.elg $CPDIR/log/cpd.elg $CPDIR/log/cpwd.elg'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; stat -c "%i %s %Y" /var/log/messages /var/log/messages.1 $FWDIR/log/fwd.elg $CPDIR/log/cpd.elg $CPDIR/log/cpwd.elg` |
| 2.124 | `cp_syslog_measure_stat_ext` | cp_gaia_gateway | read | W1 | `bash -lc 'stat -c "%n %s %Y" $FWDIR/log/fwk.elg $FWDIR/log/mq_mng.elg /var/log/hcp.elg /var/log/spike_detective/spike_detective.log /var/log/spike_detective/spike_detective.csv'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; stat -c "%n %s %Y" $FWDIR/log/fwk.elg $FWDIR/log/mq_mng.elg /var/log/hcp.elg /var/log/spike_detective/spike_detective.log /var/log/spike_detective/spike_detective.csv` |
| 2.125 | `cp_syslog_measure_tail_cpd` | cp_gaia_gateway | read | W1 | `bash -lc 'tail -n 20 $CPDIR/log/cpd.elg'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; tail -n 20 $CPDIR/log/cpd.elg` |
| 2.126 | `cp_syslog_measure_tail_cpwd` | cp_gaia_gateway | read | W1 | `bash -lc 'tail -n 20 $CPDIR/log/cpwd.elg'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; tail -n 20 $CPDIR/log/cpwd.elg` |
| 2.127 | `cp_syslog_measure_tail_fwd` | cp_gaia_gateway | read | W1 | `bash -lc 'tail -n 20 $FWDIR/log/fwd.elg'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; tail -n 20 $FWDIR/log/fwd.elg` |
| 2.128 | `cp_syslog_measure_tail_fwk` | cp_gaia_gateway | read | W1 | `bash -lc 'tail -n 20 $FWDIR/log/fwk.elg'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; tail -n 20 $FWDIR/log/fwk.elg` |
| 2.129 | `cp_syslog_measure_tail_messages` | cp_gaia_gateway | read | W1 | `bash -lc 'tail -n 20 /var/log/messages'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; tail -n 20 /var/log/messages` |
| 2.130 | `cp_syslog_measure_tail_spike` | cp_gaia_gateway | read | W1 | `bash -lc 'tail -n 20 /var/log/spike_detective/spike_detective.log'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; tail -n 20 /var/log/spike_detective/spike_detective.log` |
| 2.131 | `cp_syslog_measure_tail_spike_csv` | cp_gaia_gateway | read | W1 | `bash -lc 'tail -n 5 /var/log/spike_detective/spike_detective.csv'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; tail -n 5 /var/log/spike_detective/spike_detective.csv` |
| 2.132 | `path_validation_cp_route_get` | cp_gaia_gateway | read | W1 | `bash -lc 'ip -4 route get <DST>'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; ip -4 route get <DST>` |
| 2.133 | `path_validation_cp_route_get_vsx` | cp_gaia_gateway | read | W1 | `bash -lc 'vsenv <VSID> && ip -4 route get <DST>'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && ip -4 route get <DST>` |

### Phase 3 — class 2 failover commands (separate failover review): 4 rows

| # | gate_id | role | class | type | old literal | new literal |
|---|---|---|---|---|---|---|
| 3.1 | `cp_failover_down` | cp_gaia_gateway | operational-state-change | W2 | `clusterXL_admin down` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; clusterXL_admin down` |
| 3.2 | `cp_failover_down_vsid` | cp_gaia_gateway | operational-state-change | W1 | `bash -lc 'vsenv <VSID> && clusterXL_admin down'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && clusterXL_admin down` |
| 3.3 | `cp_failover_up` | cp_gaia_gateway | operational-state-change | W2 | `clusterXL_admin up` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; clusterXL_admin up` |
| 3.4 | `cp_failover_up_vsid` | cp_gaia_gateway | operational-state-change | W1 | `bash -lc 'vsenv <VSID> && clusterXL_admin up'` | `. /etc/profile.d/CP.sh >/dev/null 2>&1; unset BASH_ENV ENV; vsenv <VSID> && clusterXL_admin up` |
