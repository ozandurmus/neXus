# Script-run launcher: stdout capture race investigation (2026-11-22)

Status: INVESTIGATION — NOT CONFIRMED. No launcher change is proposed as a verified fix.
Scope: `ScriptRunBrokerTest.launcherCapturesStdoutAndStderrClosesFd3InChildAndPreservesExitCode`
(`ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/scriptrunner/ScriptRunBrokerTest.java`),
`ScriptRunPodSpec.LAUNCHER`, `ScriptRunLog.split`.

## 1. Observed failure

- Auto ship pretest, 2026-11-22 20:50 (log `ship-auto-1122-2040.log`, line 21):
  `AssertionFailedError: expected: <stdout-line-1\nstdout-line-2\n> but was: ...`
- The log line is cut at 239 bytes, so the actual stdout value is **not recorded** anywhere readable.
  The actual value is therefore unknown. The rc check (`0`) passed before the stdout assertion, so the
  child exited 0 and the fd-3 forgery check had not yet run.

## 2. Mechanics of the launcher (read from source)

```
exec 3>&1
"$@" 2> >(sed -u -e 's/^/E|/' -e '$a\' >&3) 3>&- < /dev/null | sed -u -e 's/^/O|/' -e '$a\'
rc=${PIPESTATUS[0]}
exec 3>&-
wait
```

- Both streams end up in the one pod log. Stdout lines are tagged `O|` by the second sed, stderr lines
  `E|` by the process-substitution sed, which writes to fd 3 (a dup of the pod stdout).
- `ScriptRunLog.split` routes each newline-terminated line by its tag. An untagged line counts as stderr.
- Two writers share the pod log (the O-sed and the E-sed). Each writes whole lines, so the split is
  correct only if no single line is written to the pod log in pieces that another writer can interleave.

## 3. Candidate mechanisms (unproven)

A. **Line interleaving across the two writers.** If GNU sed (Linux, the ship runner) flushes the tag
   and the body of one line as separate `write(2)` calls, the other tagged line can land in between.
   The split then yields one stdout line containing stderr text, or a stderr line that is misfiled.
   This would produce exactly a stdout value that differs from `stdout-line-1\nstdout-line-2\n`.
   Not verified: this depends on GNU sed's stdio buffering under `-u`, which we could not inspect here.

B. **Stdout pipe held open by the stderr process substitution.** The `>(...)` process substitution is
   created while the child's redirections are evaluated, so it inherits the child's fd 1, which is the
   pipe to the O-sed. The E-sed therefore holds the stdout pipe write end until the E-sed exits. This
   delays EOF on the O side but does not drop data, because the launcher `wait`s and the pipeline only
   finishes after both seds exit. It can cause a delay, not a loss. Recorded as a structural hazard.

C. **Test expectation.** Ruled out as the primary cause. Stdout is single-writer (only the O-sed writes
   the O lines) and the child writes stdout in program order, so exact equality of the stdout value is
   a promise the product does make. A different stdout value is therefore a product defect (mechanism A
   or a similar misfiling), not a test that asserts too much. The test must not be relaxed.

## 4. Reproduction attempts

| Setup | Runs | Result |
|---|---|---|
| Launcher extracted from `ScriptRunPodSpec.java`, child script from the test, macOS bash 3.2 and BSD sed, no load | 200 | 0 mismatches |
| Same, with 4 `yes` CPU hogs | 400 | 0 mismatches |
| `ScriptRunBrokerTest` launcher test via Gradle (`--rerun-tasks`), 4 CPU hogs | 30 | 30 pass, 0 fail |

Not reproduced. The failing environment (Linux, GNU sed, GNU bash 5) was not available locally, and no
Docker is installed, so it could not be matched. The result is consistent with a Linux-only sed or
bash behavior (mechanism A) but does not establish it.

## 5. Decision

- Launcher: **unchanged**. No verified defect.
- Test: **unchanged** (no weakening, no `@Disabled`).
- Next evidence needed to settle it:
  1. The full pod log or the full assertion message from the failing ship run (the ship log truncated it).
  2. Reproduction on the ship runner image (GNU sed, bash 5) with the same child script, looped under load.
  3. `strace -f -e trace=write` (Linux) on the launcher to see whether the O-sed and E-sed write each
     line in one `write(2)` call.

## 6. Proposed fix if mechanism A is confirmed (not implemented)

- Make each tagged line a single write: have each sed emit whole lines (`sed -u` alone is not enough; use
  a single writer for the tagged pod log, e.g. both streams merged through one tagging stage that reads
  line by line and writes each tagged line in one call), or write the O and E streams to two separate
  pod-log files and merge them in `ScriptRunLog` by a sequence tag.
- Close the stdout inheritance in B by redirecting the process substitution's stdout away from the pipe,
  e.g. `2> >(... >&3 ) 1>/dev/null` inside the process substitution, or by giving the E-sed `>&3` only.
  This is a launcher change and needs the network-device gate only if command behavior changes; it is a
  pod-script change, so it needs the normal review and the SCRIPT_EXECUTION_CONTRACT amendment process.

## 7. Open issues

- The failing actual value is unknown (log truncated). The assertion message should carry the actual value
  in a future change; that is a test change and was not made in this investigation.
- No code change was made. This document is the only artifact.
