# Script-run launcher: stdout/stderr capture race investigation (2026-11-22, root cause 2026-11-26)

Status: CONFIRMED on macOS (build Mac test host) — platform artifact of BSD `sed -u`, measured. Linux (pod image,
GNU sed 4.8, bash 5.1): **UNKNOWN by measurement** (no Linux runtime available locally); by GNU sed source the failing
interleave cannot occur for newline-terminated lines. Launcher unchanged; test made platform-honest.
Scope: `ScriptRunBrokerTest.launcherCapturesStdoutAndStderrClosesFd3InChildAndPreservesExitCode`
(`ui2/worker/src/test/java/com/securityexpert/nexus/ui2/worker/scriptrunner/ScriptRunBrokerTest.java`),
`ScriptRunPodSpec.LAUNCHER`, `ScriptRunLog.split`.

## 1. Observed failures

- Auto ship pretest, 2026-11-22 20:50: `AssertionFailedError: expected: <stdout-line-1\nstdout-line-2\n> but was: ...`
  (log line cut at 239 bytes; actual value not recorded).
- At least 7 ship pretests since 2026-11-22, and ship batches D20261126-05 and D20261126-10.
- 2026-11-26 04:55: `expected: <true> but was: <false>` with no message — the only message-less assertion was the last
  one, `split7.stderr().contains("stderr-line-1\n")` (exit-code-7 run).
- The ship pretest runs on the build Mac: `/bin/bash` 3.2.57, BSD `/usr/bin/sed`. Production runs the launcher in the
  script-exec image (`ui2/Containerfile.script-exec`, FROM the UBI9 main image): GNU bash 5.1, GNU sed 4.8.

## 2. Mechanics of the launcher (read from source)

```
exec 3>&1
"$@" 2> >(sed -u -e 's/^/E|/' -e '$a\' >&3) 3>&- < /dev/null | sed -u -e 's/^/O|/' -e '$a\'
rc=${PIPESTATUS[0]}
exec 3>&-
wait
```

Two writers share the pod log (the O-sed and the E-sed). `ScriptRunLog.split` routes each newline-terminated line by
its leading tag, so the split is correct only if each tagged line reaches the pod log in one `write(2)`.

## 3. Root cause (measured on macOS)

**BSD `sed -u` writes a line's text and its newline in two separate `write(2)` calls.**

- Source (FreeBSD `usr.bin/sed`, from which macOS sed derives; macOS `man sed`: "-u Make output unbuffered"):
  `main.c` — `case 'u': setvbuf(stdout, NULL, _IONBF, 0)`; `process.c` `OUT()` —
  `fwrite(ps, 1, psl, outfile); if (psanl) fputc('\n', outfile);`. Unbuffered stdio → two writes per line.
- Measured: `sed -u -e 's/^/E|/' -e '$a\'` fed two lines through a pipe, reader blocked in `read(2)`: in 3/300 runs a
  read chunk ended mid-line (`...E|stderr-line-2` then a separate `\n`). A single pipe write under PIPE_BUF is
  delivered atomically, so a mid-line chunk proves the line left sed in more than one write.
- Consequence in the launcher: the O-sed writes `O|stdout-line-1`, the E-sed writes `E|stderr-line-1\n`, then the O-sed
  writes its `\n`. The pod log holds `O|stdout-line-1E|stderr-line-1\n\n`; `split` files the whole line as stdout.
  Stdout then differs from `stdout-line-1\nstdout-line-2\n` (the 11-22 failure) and stderr lacks `stderr-line-1\n` (the
  11-26 failure). The mirror order (`E|stderr-line-1O|stdout-line-1\n\n`) was also observed.
- Not a cause: bash 3.2 `wait` and process-substitution lifetime. The test reads the pod-log pipe to EOF, and the E-sed
  holds that pipe, so a late `E|` line is delayed, never lost. No failure in the loop showed a missing tail; every
  failure carried the mid-line tag shape above.

### Measured failure rates (macOS arm64, `/bin/bash` 3.2.57, BSD sed; launcher extracted from `ScriptRunPodSpec.java`, child script identical to the test; 2 loops in parallel + 4 `yes` CPU hogs)

| Variant | Iterations (each = exit-0 run + exit-7 run) | Exit-0 run failing | Exit-7 run failing (`stderr-line-1` missing) |
|---|---|---|---|
| Launcher as shipped (`sed -u`), batch 1 | 600 | 13 (2.2 %): stdout mismatch + `stderr-line-1` missing, same line | 6 (1.0 %) |
| Launcher as shipped (`sed -u`), batch 2 (repeat, same conditions, after the control) | 600 | 5 (0.8 %) | 3 (0.5 %) |
| Launcher as shipped, total | 1200 | 18 (1.5 %) | 9 (0.75 %) — per test iteration ≤ 27/1200 (≤ 2.3 %) |
| Control: same launcher, `sed -u` → `sed -l` (BSD line-buffered: one write per line, like GNU `-u`), run between batch 1 and 2 | 600 | 0 | 0 |

No other check (exit code, fd-3 forgery, `stderr-line-2`, `Bad file descriptor`) failed in any run. The earlier
investigation (section 6 below) saw 0 failures with a single loop; the interleave needs both seds to flush at the same
instant, which two parallel loops under load make frequent enough to measure.

## 4. Linux verdict (UNKNOWN by measurement, clean by source)

No Docker, Podman, Lima or GNU sed is installed on the investigating Mac, so the loop could not run with the pod's
bash/sed. By GNU sed 4.8 source (`sed/sed.c`: `case 'u': unbuffered = true` — "fflush(stdout) on every line output";
no `setvbuf` on stdout anywhere; `sed/execute.c` `output_line`: `ck_fwrite(text)`, `ck_fwrite(&buffer_delimiter)`,
then `flush_output` → `fflush`), stdout stays fully buffered and each newline-terminated line leaves in one `fflush`,
i.e. one `write(2)` while the line fits the stdio buffer (and is atomic in the pipe below PIPE_BUF, 4096 on Linux).
The interleave above therefore cannot occur for this test's lines on Linux. This is source reasoning, not a
measurement: the Linux verdict for the test stays UNKNOWN until the loop runs on the pod image.

Residual Linux hazards, by the same source reading, **not measured and not this flake** (no launcher change made):

- A final line with no trailing newline: GNU sed flushes the text, then `$a\` emits the missing newline in a later
  flush (`dump_append`), so another stream's line can land between them.
- A line longer than the stdio buffer or PIPE_BUF is written in pieces and can interleave with the other stream.

Either would misfile part of a line between stdout and stderr; neither drops bytes or affects exit code or fd 3.
Closing them needs one writer for the pod log (e.g. tag both streams through a single merging stage), which is a
launcher change under SCRIPT_EXECUTION_CONTRACT review, not taken here.

## 5. Change (2026-11-26)

- `ScriptRunPodSpec.LAUNCHER`: **unchanged** (hypothesis 2 not proven on Linux).
- `ScriptRunBrokerTest.launcherCapturesStdoutAndStderrClosesFd3InChildAndPreservesExitCode`:
  - static ordering asserts (`2> >(` before `3>&-`, `exec 3>&-\nwait`) always run;
  - the behavioural part runs only when `sed` on PATH is GNU sed (`sed --version` reports `GNU sed`), as in the pod
    image, and is skipped with `Assumptions.assumeTrue` on a BSD-sed host (the build Mac). Linux assertions are not
    weakened;
  - every assertion carries a shape-only message: exit code, byte count, `O|`/`E|`/untagged/empty line counts, lines
    with a mid-line tag, and which synthetic expected line is missing — never raw log bytes.
- New `launcherRunShapeNamesTheInterleaveMeasuredWithBsdSedWithoutEchoingTheLog`: pins the measured interleave shape
  through `ScriptRunLog.split` and the shape message.

## 6. Earlier reproduction attempts (2026-11-22, kept)

| Setup | Runs | Result |
|---|---|---|
| Launcher + child script, macOS bash 3.2 / BSD sed, no load | 200 | 0 mismatches |
| Same, with 4 `yes` CPU hogs | 400 | 0 mismatches |
| `ScriptRunBrokerTest` launcher test via Gradle (`--rerun-tasks`), 4 CPU hogs | 30 | 30 pass |

## 7. Open issues

- Linux measurement: run the same loop (launcher + child, exit 0 and 7, under load) in the script-exec image or the
  test-runner image (UBI9, GNU sed) to turn section 4 from source reasoning into evidence.
- On the build Mac the behavioural launcher check is now skipped; it runs only where GNU sed is on PATH (Linux
  test-runner image, or a Mac with GNU sed first on PATH).
- Residual Linux hazards in section 4 (unterminated last line, very long lines) remain unmeasured.
