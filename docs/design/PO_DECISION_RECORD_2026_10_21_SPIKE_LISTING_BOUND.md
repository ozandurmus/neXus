# PO decision record — 2026-10-21: Spike Detective listing bound raised to 1 MiB (option A), newest-first listing later (option B)

**Status: RATIFIED — PO DECISION (chat, 2026-10-21: "Şimdi A, sonra B").** Recorded by the engineering session on
`lane/spike-bound-1mib`. It answers question 1 of [SPIKE_LISTING_CHUNKING_PROPOSAL.md](SPIKE_LISTING_CHUNKING_PROPOSAL.md) §6 and
amends [DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) §8.3 and §10.1 (dated amendment
2026-10-21). Automated validation only; nothing here is real-environment validated.

## 1. Context

One gateway holds about 3,631 Spike Detective data directories. Its C4 listing (`cp_syslog_collect_ls_spike`) exceeds the
256 KiB output bound, so its spike source is `UNKNOWN` (`LISTING_AT_BOUND`) on every run, never takes a baseline, and no spike
directory on it is ever a candidate. The proposal set out three options; the PO chose "A now, B later".

## 2. Decisions

| # | Decision | Status |
| --- | --- | --- |
| 1 | **Option A.** The C4 output bound is raised from 256 KiB to 1 MiB (1,048,576 B). Same command, nothing else on the row changed. | RATIFIED, implemented (V173, code constant, contract amendment, tests) |
| 2 | **Option B** (newest-first `-t`, prefix read with a completeness watermark) is the later, durable design. | Approved as direction only. Stays DRAFT: it needs one real-environment check of `-t` on Gaia, a PO approval of the C4t string and of the change to the "never processed partially" rule for its own row, and a contract amendment, before any implementation. Not in this change. |
| 3 | **Option C** (SFTP `readdir`). | Not wanted (not recommended in the proposal; not approved). |

## 3. What changed

- **Gate row.** Migration `V173__spike_listing_bound_one_mebibyte.sql` updates `cp_syslog_collect_ls_spike` only: its
  `max_frequency` text becomes `once per device per job window; max bytes: 1 MiB` (the bound lives in that text, as in V160) and its
  source pointer cites this record. Actor fingerprint `migration:V173_spike_listing_bound_one_mebibyte`, action id
  `gate_registry_update_by_migration`. Re-running is a no-op. The fixture `gate_registry_fixture.yaml` is in step.
- **Code.** `CpSpikeLayoutReview.LS_SPIKE_MAX_BYTES` is `1024 * 1024`. It is the single constant the collector
  (`CpSystemLogCollectJobExecutor`) and the layout-review job (`CpSpikeLayoutReviewJobExecutor`) pass to the SSH exec as the
  output limit; the transport (`SshExecTransport`) bounds by that value and keeps no other buffer cap for this read. The exec
  buffer grows to at most 1 MiB plus one read chunk of 4 KiB, once per device per job window.
- **Unchanged.** Command string, shell context, transport, timeout (30 s: a 1 MiB read on an established session is a small
  fraction of it), retry (none), frequency, session rule, the 64 KiB C5 bound, the 256 KiB C3 bound. A listing that reaches the
  bound is still `UNKNOWN` (`LISTING_AT_BOUND`) and never processed partially; one byte below the bound is processed in full.

## 4. Consequences to accept

- The affected gateway's first successful listing is its baseline. Directories created while its source was `UNKNOWN` are
  recorded as `present before monitoring` and never fetched (the existing baseline rule applied late; proposal §6 question 4).
- The bound covers the measured population whatever the names' length (3,638 entries x at most 288 B = 1,047,744 B). It does not
  cover growth: past about 1,048,576 / (33 + name length) entries the same `UNKNOWN` returns until B is built. The proposal asks
  for a count and size class of the first successful listing to be recorded in the findings record.
- Device load is unchanged: `ls -l` already stats every entry; neXus keeps up to 1 MiB (instead of 256 KiB) of its output once
  per job window, at most 4 MiB per day for that gateway.

## 5. Not decided here

Proposal §6 questions 2 (C4t string and rule change) and 3 (the real-environment `-t` review run) for B are not answered by this
record; they are the next decision, taken when B is scheduled.
