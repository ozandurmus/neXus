# Spike Detective listing: getting past the 256 KiB C4 bound — proposal

**Status: DRAFT — for Product Owner approval. Not implementation authority.** Nothing here changes a gate row,
a migration or the collector. It amends nothing in
[DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) (APPROVED, PO 2026-10-17) until the PO
picks an option in §6 and the contract is amended to match.

## Summary

The Spike Detective listing (C4) is bounded at 256 KiB. One gateway holds about 3,631 data directories, and its listing
exceeds the bound, so its spike source is `UNKNOWN` on every run and it never takes a baseline. Three options:

- **A — raise the bound to 1 MiB.** Same command; only the byte bound changes. Because a file name is at most 255 bytes,
  the measured 3,638 lines fit in 1 MiB whatever the names' length. No added device work. Growth in the directory
  count can bring the problem back.
- **B — sort newest-first (`-t`) and read a bounded prefix on the neXus side.** One flag is added to the command; there
  is no `head` and no pipe. This is the durable fix, independent of how large the listing grows. It changes the rule
  "a listing at its bound is never processed" for this row and needs one real-environment check.
- **C — SFTP directory read (`readdir`).** A new SFTP operation that gives no reliable link count and no ordering. Not
  recommended.

The requested form "newest-first, bounded count, using only already-approved command forms" cannot be met exactly. No
approved C4/C5 row sorts. Bounding the count on the device needs `head`, which D6 forbids. §3 sets this out.

**Recommendation:** approve A now and B as the durable design (B can run with A's 1 MiB bound in place). The
decisions are the questions in §6.

## 1. Problem

- C4 `cp_syslog_collect_ls_spike` = `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'`, output bound
  256 KiB (262,144 B), timeout 30 s, once per device per job window (contract §10.1).
- Contract §8.3 and the implementation record: a C4 output that reaches its bound is `UNKNOWN` (`LISTING_AT_BOUND`),
  **never processed partially**, and a listing that is not processed does not take the baseline.
- Population (findings record §4.2): 0 to 3,631 data directories per gateway (median 14, p90 93); 6 gateways have 100 or
  more entries. The largest measured listing was 3,638 lines and about 189 KB **with masked 19-character names**.
- Reported by the PO on 2026-10-21 (chat; not yet in a findings record): on the gateway with ~3,631 data directories, every
  run ends `UNKNOWN` (`LISTING_AT_BOUND`). That gateway therefore has no baseline, and no spike directory on it is ever
  a candidate.

### 1.1 What the measurement does and does not say about the line size

From the masked measurement: 189 KB / 3,638 lines ≈ 52 B per line with a 19-character name, so the fixed part of a
`ls -lgo --time-style=+%s` line (type and mode, link count, size, epoch, separators, newline) is about **33 B**. A
real line is about `33 + L` bytes for a name of `L` bytes.

- The listing exceeds 256 KiB when `3,638 × (33 + L) > 262,144`, i.e. when the mean name is longer than about
  **39 bytes**. The PO's report implies that the real names are longer than that. The one directory reviewed on
  2026-10-20 has the class `X_spike_X_NNN_NNN-NN-NN_NN-NN-NN` (layout review §4), which is consistent with that, but
  the real length is `UNKNOWN` (names are masked and stored only as HMAC).
- **Upper bound by construction.** ext3/ext4 limit a file name to 255 bytes. At 3,638 entries, the largest possible
  listing is `3,638 × (33 + 255) = 1,047,744 B`, which is below 1 MiB (1,048,576 B). This relies only on the
  filesystem's name limit, not on the unmeasured name pattern. That the Gaia log partition is ext3/ext4 is the
  standard Gaia layout but has **not been verified on this estate**; the bound A proposes still holds for any
  filesystem whose name limit is 255 bytes.
- **Growth is `UNKNOWN`.** The second M5 read, which would give the rate at which new directories appear, has not been
  made. Whether Spike Detective prunes old directories itself is also `UNKNOWN`. So "3,631" is a snapshot from
  2026-10-17, not a ceiling.

## 2. Constraints every option keeps

- D6 / contract §3.4: one SSH session per device per run, one command at a time. No shell process for reading
  (`tail`/`head`), no `find`, no compression, no archive creation on the device.
- No command is issued in a new context: the shell is `expert`, the existing trusted session is used, and
  `bash -lc` is non-interactive.
- The entry class decides what is a data directory (type `d`, link count 2), never a name regex; names are stored only
  as HMAC (§8.3, PO amendment 2026-10-20 item 2).
- Baseline rule: a directory present before monitoring is never fetched.
- Nothing here touches C5, C8, the 16 directories per run, the 16 MiB per directory or the 64 MiB device budget.

## 3. Finding: "newest-first, bounded count, already-approved forms only" has no exact solution

- No approved row sorts the listing. The approved strings are C4 and its identical V157 measurement twin
  `cp_syslog_measure_ls_spike`, and C5 `.../spike_detective/<dir>/` (one directory's members). Both use `ls`'s default
  order (by name).
- Bounding the **count** on the device needs a second process (`| head -n <N>`). D6 forbids that, and the only approved
  `head`/`tail` strings are the V149/V157 measurement-only rows (`max_frequency` "phase A measurement only"), which
  contract §10.3 says collection must not reuse.
- Bounding by a **name glob** (for example a date component of the name) depends on the data-directory name pattern,
  which is evidence from one directory, not a rule (PO amendment 2026-10-20 item 2). The vendor semantics law forbids
  building on it.
- **Closest form.** Add `-t` (sort by modification time, newest first) to the approved C4 string, and bound the count
  on the **neXus side** by reading only a prefix of the output. This is option B. It is a change to an approved
  command string and so needs PO approval (§10 preamble: "a change to a command's shape … needs a new PO approval").

## 4. Options

### 4.1 Option A — raise the C4 output bound to 1 MiB (same command)

**Change.** On the C4 row, only `Max bytes` changes: 256 KiB → 1 MiB. Every other field and the processing rule
("at bound = `UNKNOWN`, never partial") stay as they are.

| # | gate_id | Transport | canonical_command_key | timeout_s | retry | max_frequency | session_reuse_rule | Max bytes | unsupported_behavior_ref | secret_output_risk | safe_telemetry_fields | Relation to approved |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C4 (amended) | `cp_syslog_collect_ls_spike` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'` | 30 | none | once per device per job window | same session | **1 MiB** (was 256 KiB) | missing directory = `ABSENT`; output at the bound = `UNKNOWN` (`LISTING_AT_BOUND`), never processed partially | names only | `["fileCount", "sizeBytes", "mtime", "namePattern"]` | AMENDED; string, transport, timeout, retry, frequency and session identical to the approved C4 (PO 2026-10-17); only the output bound changes |

Fields 1–10 of the network-device command gate (`docs/AI_DEVELOPMENT_PROTOCOL.md`):

1. Why: the 256 KiB bound blocks one gateway's spike source permanently (§1).
2. Class: 0, read (Java `ActionClass` read; unchanged).
3. Vendor/platform/shell/context: `check_point`, `cp_gaia_gateway`, `expert`, VS0; unchanged.
4. Timeout: 30 s, unchanged.
5. Retry: none, unchanged.
6. Frequency: once per device per job window (at most 4 per day), unchanged.
7. Session reuse: the run's one trusted SSH session, sequential; unchanged.
8. Unsupported behaviour: unchanged; the bound now sits above the largest possible listing at the measured population.
9. Secret-output risk: names only, unchanged; more names per listing, each still stored only as HMAC.
10. Safe telemetry: unchanged.

**Device load.** None added. `ls -l` already reads every entry and stats it for each run on that gateway today. The
device produces the whole listing either way; today neXus keeps at most 256 KiB of it (`CpSpikeLayoutReview.LS_SPIKE_MAX_BYTES`). The only difference is that
up to about 1 MiB of output (instead of 256 KiB) is kept from the existing session once per job window (at most 4 MiB per day for that
gateway). Gateways with small listings produce exactly the bytes they produce today.

**neXus side.** One buffer of up to 1 MiB per C4, and parsing of up to about 3,600 lines with the existing parser.

**Evidence status.** The bound is justified by the 255-byte name limit at the **measured** count (§1.1). It does
**not** cover growth. Beyond about 1,048,576 / (33 + L) entries (for example about 11,000 at L = 60) the same
`UNKNOWN` comes back.

**Baseline effect.** That gateway's first successful listing becomes its baseline. Every directory created since
rollout, while the source was `UNKNOWN`, is then recorded as `present before monitoring` and never fetched. This is
the existing baseline rule applied late, and nothing is fetched by mistake.

### 4.2 Option B — newest-first listing, prefix processed with a completeness watermark (recommended long-term)

**Change.** Add a new gate row, or replace C4, with `-t` added. neXus reads at most the bound and processes the
**prefix** under the rule below instead of discarding it.

| # | gate_id | Transport | canonical_command_key | timeout_s | retry | max_frequency | session_reuse_rule | Max bytes | unsupported_behavior_ref | secret_output_risk | safe_telemetry_fields | Relation to approved |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C4t | `cp_syslog_collect_ls_spike_newest` | `SSH_EXEC` | `bash -lc 'ls -lgo -t --time-style=+%s /var/log/spike_detective/'` | 30 | none | once per device per job window (replaces C4 for that run; never both) | same session | 256 KiB read by neXus; the rest of the output is not read (the channel is closed after the bound) | missing directory = `ABSENT`; prefix not complete per the watermark rule = `BACKLOG` (`LISTING_PREFIX_INCOMPLETE`) with the counts; output not newest-first per the order check = `UNKNOWN` (`LISTING_ORDER_UNEXPECTED`) | names only | `["fileCount", "sizeBytes", "mtime", "namePattern", "prefixLines", "prefixComplete"]` | NEW; C4 plus `-t` only (GNU `ls`: sort by modification time, newest first). No pipe, no second process, same path, timeout, frequency and session |

Gate fields 1–10: as option A, except: (1) why: a listing whose useful part (the new directories) is always at the
top, so its size stops mattering; (8) unsupported behaviour: as in the row; (10) telemetry adds `prefixLines` and
`prefixComplete`.

**Processing rule (replaces "at bound = `UNKNOWN`" for C4t only).**

1. Drop a partial last line. Parse the remaining lines. Check that the epochs do not increase down the list (newest
   first). If they increase, the result is `UNKNOWN` (`LISTING_ORDER_UNEXPECTED`) and nothing is processed.
2. **Watermark.** Per device, store `listing_complete_through`: the device epoch (from that run's C1) of the last run
   whose newest-first prefix was complete. For the first run on a device it is "none".
3. **Completeness.** A prefix that did not reach the bound is the whole listing, and therefore complete. A prefix that
   reached the bound is complete if its oldest entry's mtime is strictly less than `listing_complete_through`. A
   directory created after the last complete run has an mtime newer than that watermark, so it must be in the prefix.
   Entries that share the cut-off mtime are not trusted.
4. **Baseline from a prefix.** On a device's first run, a complete prefix stores its directories as `BASELINE`, as
   today. If the first prefix reached the bound, the device's baseline epoch is set to that run's C1 epoch. Afterwards
   a directory is **new** only if its name HMAC is unknown **and** its mtime is later than the baseline epoch. So a
   pre-existing directory outside the first prefix can never become a fetch candidate merely because it shows up in a
   later listing (for example after Spike Detective prunes others).
5. Incomplete prefix: `BACKLOG` (`LISTING_PREFIX_INCOMPLETE`), nothing new is decided, and the watermark is unchanged.
   This needs more than ~3,600 directories created or modified within one job window, far above anything measured.

**Device load.** Same as C4: `ls -l` already stats every entry, and `-t` only adds an in-memory sort of the entries
(about 3,600 here). Output volume is the same as C4 because the device writes the whole listing. neXus reads at most
256 KiB and closes the channel, and `ls` ends on the closed pipe. No second process, no file written.

**Risks and `UNKNOWN`s (need real-environment evidence before rollout).**

- `-t` behaviour on the Gaia `ls` (GNU coreutils): "sort by modification time, newest first" is the GNU coreutils
  manual's definition. The Gaia build's coreutils version and its exact behaviour with `-lgo --time-style=+%s` are not
  measured. One review run per the §8.3 method (one gateway, aiview mentality) would settle it, and the order check
  in rule 1 fails closed if it is wrong.
- Directory mtime changes when an entry inside the directory is added, removed or renamed. Whether Spike Detective
  ever writes into an old data directory is `UNKNOWN`. If it does, that directory moves to the top. It is already
  known by HMAC, so it is not fetched again. If it predates the baseline epoch, rule 4 keeps it out.
- Clock: the watermark compares device mtimes with the device epoch from C1 of the same device, never the neXus
  clock. A device clock step backwards could make a prefix look complete when it is not. Detecting that would need a
  stored previous C1 epoch, and a backwards step would be treated as `BACKLOG`.
- It changes the "never processed partially" semantics of §8.3 for this row. That is a contract amendment, not only a
  gate change.

### 4.3 Option C — SFTP directory read (`opendir`/`readdir`) on the existing session (not recommended)

| # | gate_id | Transport | canonical_command_key | timeout_s | retry | max_frequency | session_reuse_rule | Max bytes | unsupported_behavior_ref | secret_output_risk | safe_telemetry_fields | Relation to approved |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C4s | (`sftp_readdir`; gate `NOT_APPLICABLE` would need the same C4 §2.3 amendment as C2/C8) | SFTP subsystem on the same SSH session | no shell command. `opendir("/var/log/spike_detective/")`, `readdir` until EOF, `close`. Fixed path; no other path | 30 | none | once per device per job window (instead of C4) | same session (SFTP channel); sequential | 20,000 entries or 2 MiB of names, whichever comes first; at the bound = `UNKNOWN`, not partial | missing directory = `ABSENT`; SFTP unavailable = `UNKNOWN` | names only | `["fileCount", "mtime", "isDirectory"]` | NEW; a new SFTP operation (only open/read/close are approved today, C2/C8) |

**Why not recommended.**

- The SFTP v3 attributes carry type, size, permissions and mtime but **no link count**. The link count appears only in
  the server-formatted `longname`, which the SFTP draft says clients should not parse. The §8.3 entry class (type `d`
  **and** link count 2) would weaken to "type `d`".
- `readdir` order is filesystem order, so this fixes nothing that A does not fix. neXus must still read everything.
- It adds a new protocol operation and a new gate shape for no gain in device load: `sftp-server` reads and stats the
  same entries as `ls -l`.

### 4.4 Options considered and dropped

| Form | Why dropped |
|---|---|
| `ls ... \| head -n <N>` (with or without `-t`) | second process on the device (D6); the only approved `head` strings are measurement-only (§10.3) |
| Name glob by date (`.../*_<date>_*`) | depends on the unmeasured name pattern (PO amendment 2026-10-20 item 2); vendor semantics law |
| `ls -1` (names only) | loses type, link count and mtime: the entry class and the settling rule both need them |
| `find -newer` / `-maxdepth` | `find` is excluded by D6 |
| `ls -d .../*/` (directories only) | same size as C4 (directories are almost all of it); shell glob of thousands of arguments |

## 5. Recommendation

1. **Approve A now.** No new device behaviour, a bound justified by the filesystem name limit at the measured
   population, and it unblocks the one affected gateway at the next run.
2. **Approve B as the durable design.** Implement it after one review run confirms `-t` order on Gaia. Keep A's 1 MiB
   bound on C4 until C4t replaces C4 (or keep both and select by device).
3. **Do not approve C.**

After a decision, the follow-on work (not part of this DRAFT): amend the contract (§3.4, §8.3, §10.1, §13 open item 2);
add a migration with the new or changed gate row and its fixture; change the collector (`CpSystemLogCollectJobExecutor`, its `LISTING_AT_BOUND` branch), with tests for the watermark
rules if B is chosen; and add a findings-record entry for the review run (B) or for the first successful listing's
byte count (A), recorded as a count and size class only.

## 6. Questions for the PO

1. **A:** Raise the C4 output bound from 256 KiB to 1 MiB, with nothing else on the row changed? (Yes / No / a
   different bound.)
2. **B:** Approve the C4t string `bash -lc 'ls -lgo -t --time-style=+%s /var/log/spike_detective/'`, and the change of
   §8.3 from "never processed partially" to the watermark prefix rule of §4.2 for that row only?
3. **B review run:** Approve one C4t run on one gateway (aiview mentality, record only order-check pass or fail, line
   count and byte count) before C4t replaces C4?
4. **Baseline for the affected gateway:** Accept that directories created while its source was `UNKNOWN` become
   baseline and are never fetched? (The alternative, fetching them, would mean deciding "new" from rollout time, and
   that is not what §8.3 says.)
5. **C:** Confirm that the SFTP `readdir` form is not wanted?
