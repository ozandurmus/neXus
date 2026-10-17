# Device system-log programme — phase B contract (incremental collection and storage)

**Status: DRAFT — pending PO command approval.** No command in §10 is approved by this document. It becomes
FROZEN only after (1) the M1/M5 findings record and the M3/M4 findings record are committed next to the phase A
contract, (2) the parameters marked `PENDING-M1`/`PENDING-M5`/`PENDING-M3M4` in §11 are filled from them, and
(3) the PO approves each exact command in §10 and answers §13.

Authority and inputs:

- [PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md](PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md)
  §3 (phase B approved in direction; raw-evidence law exception for the system-log programme).
- [DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md](DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md) (phase A): sources §2, read
  shapes L1–L5 §3, measurements M1–M5 §4/§4.1, storage/privacy §5, PO answers §7 (30-day raw retention, PAN config
  logs included, every enrolled firewall, 6-hour interval).
- PO decisions in chat, 2026-10-15/16 (§1 below).
- `AGENTS.md`: network-device command gate, raw-evidence law, UNKNOWN/fail-closed law, aiview masking law.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` §"Network-device command gate" (the ten gate fields).
- `UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §2.3 (`sftp_get` gate applicability).
- `DATA_SANITIZER_CONTRACT.md` §3 item 3 (log export uses the text path).
- `NOTIFICATION_ROUTING_CONTRACT.md` (FROZEN; §8 below needs an amendment to it).

---

## Özet (PO için)

**Ne yapılacak.** Her firewall'un kendi sistem loglarını neXus'ta biriktiriyoruz. Her seferinde yalnızca son
okumadan sonra eklenen satırları alıyoruz. Okuma her 6 saatte bir yapılıyor, yalnızca 00/06/12/18 (İstanbul)
pencerelerinde.

**Check Point gateway'lerde her koşuda neler oluyor.** Her cihaza tek bir SSH oturumu açılır ve komutlar sırayla
çalışır:

1. Tek bir `stat` komutu çalışır. Bu komut her log dosyasının kimliğini (inode), boyutunu ve son yazma zamanını
   verir. Kapsanan dosyalar: messages, fwd, cpd, cpwd, fwk, mq_mng, spike_detective.log/.csv ve bunların döndürülmüş
   kopyaları.
2. Büyüyen her dosyanın yalnızca yeni baytları okunur, `tail -c +<ofset> | head -c <tavan>` ile. Tek okuma en fazla
   8 MiB'tır.
3. Dosya döndürüldüyse (inode değiştiyse) önce eski dosyanın okunmamış kalanı okunur, sonra yeni dosya baştan
   okunur.
4. Bazı satırlar biz okuyamadan silindiyse, bunu sessizce geçmiyoruz. Ekranda ve kayıtta "boşluk" olarak, bayt
   sayısıyla gösteriyoruz.
5. `/var/log/crash` ve `/var/log/dump/usermode` klasörlerinin yalnızca listesi alınır (ad, boyut, zaman). İçerik
   asla okunmaz. Yeni bir dosya çıkarsa bildirim gider: süreç adı, zaman ve adet.
6. Spike Detective klasörünün listesi alınır. Yeni oluşmuş veri dosyaları indirilir.

**Günde bir kez, 06:00 penceresinde.** Dünkü `sar` metin dosyası indirilir. Cihazın gece yaklaşık 03:00'te ürettiği
HCP raporu (`.tar.gz`) da indirilir. HCP raporları 7 gün saklanır.

**Palo Alto'da.** XML API ile sistem ve config logları okunur. Yalnızca son görülen kayıt zamanından sonrakiler
alınır.

**Cihaz yükü.**

- Cihazda sıkıştırma yapılmaz.
- `find` kullanılmaz.
- Polling yapılmaz. Tek istisna, PAN'ın kendi log işinin bitişini sınırlı sayıda kontrol etmektir.
- Cihaz başına bir oturum açılır ve komutlar sırayla çalışır.
- Bir koşuda cihaz başına en fazla 64 MiB okunur.
- Sıkıştırma neXus tarafında yapılır.

**Ne kadar veri.** Gerçek sayılar bu gece/yarın tamamlanacak M1 ve M5 ölçümlerinden gelecek. Önerilen tavanlar
şunlar:

- Ham loglar 30 gün saklanır.
- Cihaz başına en fazla 2 GiB tutulur (sıkıştırılmış).
- HCP raporları 7 gün saklanır.
- Dosyalar neXus'ta şifreli ve sıkıştırılmış durur.
- aiview yalnızca maskelenmiş metni görür.
- Ürün dışına çıkış yalnızca Data Sanitizer'dan geçerek olur.

**Management server (MDS).** Şimdilik kapalı. Tasarım ileride açılmasına izin veriyor, ama varsayılan kapalı ve bu
onayın parçası değil.

**Onayınız gerekenler.**

1. §10'daki 13 yeni komut: Check Point için 8, Palo Alto için 5. Faz A'da onaylanmış 18 ölçüm komutu (V149, V151,
   V157) toplamada kullanılmıyor. Onlar yalnızca ölçüm içindi, ham veri saklamıyorlardı.
2. §13'teki sorular: okuma yöntemi, yeni bildirim tipi, ilk koşuda geriye dönük okuma miktarı, depolama tavanı
   dolunca ne olacağı, HCP raporunun dışa aktarımı ve operatörün maskesiz görüp göremeyeceği.

---

## 1. PO decisions this contract designs for (chat, 2026-10-15/16)

| # | Decision | Where designed |
|---|---|---|
| D1 | Collect every 6 hours, inside the 00/06/12/18 Europe/Istanbul windows, reading only lines new since the last run; no overwrite gaps (one MDS rotated `/var/log/messages` every 15–30 min under a fault) | §3, §4, §9 |
| D2 | Check Point gateway sources: `/var/log/messages` (+rotations), `$FWDIR/log/fwd.elg`, `$CPDIR/log/cpd.elg`, `cpwd.elg`, `fwk.elg`, `mq_mng.elg`, `/var/log/sa/sarNN`, Spike Detective data and `spike_detective.log/.csv` (stored compressed/archived), HCP report (06:00 window, keep 7 days), crash/dump directories as a listing only with a new-file alert | §2, §4–§8 |
| D3 | Management servers (MDS) are disabled now (crash investigation); allow them later, default off | §2.3 |
| D4 | Palo Alto: system and config logs over the XML API log query (as M3/M4), incremental by newest seq/time seen | §6 |
| D5 | Raw logs are an explicit exception to the raw-evidence law: stored and retained with a default retention and size cap per device, exported only through the Data Sanitizer text path, aiview sees masked | §5, §12 |
| D6 | Device load: one existing trusted SSH session per device per run, sequential reads, bounded bytes per read, no polling | §3.4, §10 |

## 2. Scope

### 2.1 In scope

**Check Point Gaia gateways** (`platform_role_scope = cp_gaia_gateway`): standalone gateways, ClusterXL members (each
member is its own device), and VSX/USFW hosts, which run in context VS0 only (§2.2).

Incremental text sources. Each is a *file family*: the live file plus its rotated siblings.

| Source key | Base path (expanded by the login shell) | Family glob | Notes |
|---|---|---|---|
| `cp_messages` | `/var/log/messages` | `/var/log/messages*` | rotation mode `PENDING-M1` (rename vs copytruncate) |
| `cp_fwd_elg` | `$FWDIR/log/fwd.elg` | `$FWDIR/log/fwd.elg*` | size-rotated; naming `PENDING-M1` |
| `cp_cpd_elg` | `$CPDIR/log/cpd.elg` | `$CPDIR/log/cpd.elg*` | |
| `cp_cpwd_elg` | `$CPDIR/log/cpwd.elg` | `$CPDIR/log/cpwd.elg*` | process restarts; high signal |
| `cp_fwk_elg` | `$FWDIR/log/fwk.elg` | `$FWDIR/log/fwk.elg*` | VSX/USFW only; absent elsewhere = `ABSENT` (§9) |
| `cp_mq_mng_elg` | `$FWDIR/log/mq_mng.elg` | `$FWDIR/log/mq_mng.elg*` | |
| `cp_spike_log` | `/var/log/spike_detective/spike_detective.log` | `…/spike_detective.log*` | |
| `cp_spike_csv` | `/var/log/spike_detective/spike_detective.csv` | `…/spike_detective.csv*` | |

Whole-file sources are fetched once, when they are complete:

| Source key | What | When | Retention |
|---|---|---|---|
| `cp_sar_daily` | yesterday's `/var/log/sa/sar<DD>` (text, written by the device at 23:53) | 06:00 window, once a day | 30 days |
| `cp_hcp_report` | newest `/var/log/hcp/last/hcp_report_<host>_<dd_mm_yy_HH_MM>.tar.gz` (device nightly run, ~03:00) | 06:00 window, once a day | 7 days (PO) |
| `cp_spike_data` | files of each new, settled Spike Detective data directory: `perf_cpu_N.log`, `profiling_summary.elg` (layout `PENDING-M5`, gate 3) | any window, once per directory | 30 days |

Listing-only sources (metadata, never content):

| Source key | What | When |
|---|---|---|
| `cp_crash_listing` | one-level listing of `/var/log/crash/` and `/var/log/dump/usermode/` | every run |

**Palo Alto PAN-OS firewalls** (`pan_firewall`, never Panorama), with two sources: `pan_system` (`log-type=system`)
and `pan_config` (`log-type=config`).

### 2.2 Out of scope (phase B)

- Traffic and threat logs (`fw.log`, PAN traffic/threat). These are not system logs.
- Per-virtual-system logs on a VSX host (`$FWDIR/CTX/CTX0000N/log/*`). VS0 only; see §13 Q8.
- Gaia Embedded (Spark, `gaia_embedded`). Its paths differ, so the sources are `UNSUPPORTED`.
- `/var/log/dmesg`, `/var/log/hcp.elg` and binary `/var/log/sa/sa<DD>`. These are not in the PO's list.
- Pattern catalog and priorities (phase C), and knowledge links and the agent (phase D). Phase B stores chunks and
  counters only. The parsed event index in phase A §5 belongs to phase C.

### 2.3 Management servers (default off)

MDS / management servers (`cp_multi_domain_server`) are excluded:

- The setting `system_log.include_management_servers` defaults to `false`. While it is false, a collection request
  for a management server is refused with `SYSTEM_LOG_DISABLED_ON_MANAGEMENT`, manual or scheduled. This matches the
  backup precedent `GAIA_BACKUP_DISABLED_ON_MANAGEMENT`.
- Enabling it later needs three things:
  1. MDS-specific measurement. The paths differ: `$MDSDIR/log/*`, and per-domain `$FWDIR` under `mdsenv`.
  2. MDS-scoped gate rows (`platform_role_scope = cp_multi_domain_server`), separately approved. None are requested
     here.
  3. The PO's explicit switch.
- The measured fast rotation (`messages` every 15–30 min under a fault) was on an MDS. §4.6 handles it the same way
  for any device.

## 3. Run shape

### 3.1 Schedule

- Capabilities `cp_system_log_collect` and `pan_system_log_collect`. Module `diagnostics` (or a new `logs` module;
  this needs the `ui2_job_module` mapping in the implementation migration).
- A scheduled trigger admits one job per enabled target in each 00/06/12/18 window, through `JobWindowPolicy` (the
  60-minute default). Manual starts obey the same windows.
- **Staggering.** Each device's start is offset inside the window by `hash(device_id) mod 40` minutes. This spreads
  the load and keeps clear of window-start readiness and policy jobs.
- **Daily items** (`cp_sar_daily`, `cp_hcp_report`) run only in the 06:00 window run. This is after the device's
  23:53 sar write and its ~03:00 HCP run, and outside the 02:00–02:59 scheduled backup window.
- **Missed run.** A device unreachable in one window is simply continued from its cursor in the next. Any loss
  meanwhile is detected and reported (§4.5). There is no catch-up run outside the windows.

### 3.2 Check Point run order (one SSH session, sequential)

1. `cp_syslog_collect_stat`, one command. It gives the device clock plus name, inode, size and mtime of every file
   in every family.
2. For each incremental source in the fixed order of §2.1, the planned reads (§4) with `cp_syslog_collect_read`. Each
   read is stored before the next is sent.
3. `cp_syslog_collect_ls_crash`, then the new-file comparison and alerts (§7).
4. `cp_syslog_collect_ls_spike`. For each new settled data directory, run `cp_syslog_collect_ls_spike_dir` and then
   one `sftp_get` per allowed file (§8.3).
5. 06:00 run only: `cp_syslog_collect_ls_sa` then one `sftp_get`, and `cp_syslog_collect_ls_hcp` then one `sftp_get`
   (§8.1, §8.2).
6. Disconnect.

### 3.3 Palo Alto run order (one API key per run)

1. Get the key through the existing authentication path (as the M3/M4 executor does).
2. `pan_system` pages (§6).
3. `pan_config` pages (§6).

Everything is serial per firewall.

### 3.4 Device-load bounds (all `PENDING-M1` unless stated)

| Bound | Proposed default | Why |
|---|---|---|
| Sessions per device per run | 1 (SSH; SFTP fetches use a subsystem channel on the same session) | D6 |
| Concurrency on one device | 1 command at a time | D6 |
| Bytes per read (`<cap>`) | 8 MiB | bounded single transfer |
| Bytes per source per run | 32 MiB | one noisy file cannot starve the others |
| Bytes per device per run (all sources, excluding HCP) | 64 MiB | device and link load |
| HCP archive max | 64 MiB (`PENDING-M5`, gate 4) | refuse larger, report `TOO_LARGE` |
| Spike data per directory | 16 MiB (`PENDING-M5`, gate 3) | |
| Wall-clock budget per device job | 15 min | inside the 60-minute window |
| Device-side work | `stat`, `ls`, `tail`/`head` with a seek, SFTP read; no compression, no `find`, no archive creation on the device | D6 |

When a budget is exhausted the run stops reading that source and records `BACKLOG` with exact byte counts (§9). It
does not skip ahead. The next run continues from the cursor.

## 4. Incremental-read mechanism (Check Point)

### 4.1 Cursor

There is one cursor per (device, source key):

| Field | Meaning |
|---|---|
| `inode` | inode of the file the cursor points into |
| `offset_bytes` | first unread byte in that file (always just after a `\n`, §4.3) |
| `anchor_hmac` | HMAC-SHA256, under the product privacy key, of the `K = min(64, offset)` bytes before `offset`. It verifies that the file at `offset` is the same file and the same content. No raw bytes are stored in the database |
| `family_snapshot` | the previous run's stat of the family: list of {name, inode, size, mtime} |
| `device_epoch_at_read` | device clock at the last stat |
| `updated_job_id` | the job that advanced it |

The cursor advances only in the same transaction that records the chunk row (§5.2), after the artefact is fully
written. A crash between read and record therefore re-reads the same bytes next time (at-least-once). Duplicate
chunks are prevented by a unique key on (device, source, inode, start_offset).

### 4.2 The stat read

```
bash -lc 'date +%s; stat -c "%n %i %s %Y" /var/log/messages* $FWDIR/log/fwd.elg* $CPDIR/log/cpd.elg* $CPDIR/log/cpwd.elg* $FWDIR/log/fwk.elg* $FWDIR/log/mq_mng.elg* /var/log/spike_detective/spike_detective.log* /var/log/spike_detective/spike_detective.csv*'
```

- `%n` prints the **expanded absolute path**, so every later read names an absolute path taken from this run's own
  output. No `$FWDIR` is substituted from neXus.
- A family glob that matches nothing stays literal, and `stat` prints an error line for it. That is the result
  `ABSENT` for the source, not a failure.
- `date +%s` gives the device clock. It is used for the settledness checks (§8) and for skew telemetry. neXus never
  compares device mtimes with its own clock.
- Rotated siblings are ordered by name suffix and cross-checked by mtime. A `.gz` sibling is listed but never read
  (§4.6).

### 4.3 The read

```
bash -lc 'tail -c +<start> -- <path> | head -c <cap>'
```

- `<path>` must be one of the absolute paths that this run's stat output printed. Its basename must match the
  source's family pattern (base name plus optional `.<1–2 digits>` suffix, exact form `PENDING-M1`) and its
  directory must equal the directory of the family's base file.
- `<start> = offset − K + 1` (1-based), so the first `K` bytes received are the anchor region. `<cap> = K + budget`.
  Both are digits only.
- GNU `tail -c +N` on a regular file seeks; it does not read the skipped bytes. `head -c` stops after `<cap>` and ends
  the pipe.
- **Anchor check.** The HMAC of the first `K` received bytes must equal `anchor_hmac`. On a mismatch the content at
  `offset` is not what was read before (inode reuse, file replaced, truncate-and-regrow). The source is handled as
  `ANCHOR_MISMATCH` (§4.5).
- **Line trim.** The stored chunk ends at the last `\n` received, and the cursor moves there. A partial final line is
  read again next run. If a full-cap read contains no `\n`, the cap is stored as is and the chunk is flagged
  `LINE_SPLIT`.
- **Byte exactness (implementation requirement).** Offsets count bytes. The current `ExecResult.Completed(String
  output, …)` combines stdout and stderr as text. That is unusable here: invalid UTF-8 or interleaved stderr would
  corrupt offsets. The read needs an exec mode that streams **stdout only, as bytes**, into the artefact sink, counts
  the bytes received, and captures stderr separately for the error classification. This is a `ExecSpec`/transport
  extension. It is not a new C4 step kind.

Alternative (§13 Q1): an SFTP ranged read (JSch `ChannelSftp.get(path, out, monitor, RESUME, skip)`) moves the bytes
without a shell process on the device. It still needs the stat for inodes, because SFTP v3 attributes carry no inode.
It requires a C4 amendment, since today's `sftp_get` kind fetches a whole file. The recommendation is the
`tail | head` read above: it is gate-registered, it is phase A's L2/L3 shape, and it is what the PO named.

### 4.4 Planning per source (decision table)

Let `C` be the stored cursor and `F` this run's family stat (name → inode, size).

| Case | Detection | Reads, in order | Record |
|---|---|---|---|
| First run (no cursor) | no row | current file from `max(0, size − backfill)` (backfill: §13 Q3, default 8 MiB) | `INITIAL_BASELINE` gap with the exact skipped byte count (`size − backfill`, when positive) |
| No change | `C.inode` is the current file, `size == offset` | none | `NO_NEW_DATA` |
| Growth | `C.inode` is the current file, `size > offset` | current `[offset, size)`, capped | `COLLECTED` or `BACKLOG` |
| Rename rotation | `C.inode` found as sibling `S` | `S` from `offset` to `S.size`, then every sibling newer than `S` whole (oldest first), then the current file from 0 | `COLLECTED`, `ROTATION_FOLLOWED` |
| Copytruncate | `C.inode` is the current file, `size < offset` | the sibling whose size ≥ `offset` and whose anchor matches, from `offset` to its end; then the current file from 0 | `ROTATION_FOLLOWED`; if no sibling matches: `TRUNCATED` gap |
| Rotated away | `C.inode` not in `F` | the oldest surviving sibling whole, then newer ones, then the current file | `ROTATED_AWAY` gap (§4.5) |
| Anchor mismatch | §4.3 | current file from 0 | `ANCHOR_MISMATCH` gap, extent `UNKNOWN` |
| Absent | the base file is missing | none | `ABSENT` (not a failure; e.g. `fwk.elg` on a non-VSX gateway) |
| Budget exhausted mid-chain | per-run budget | stop; the cursor stays at the last stored byte, possibly in a rotated sibling | `BACKLOG` with exact unread bytes still visible in `F` |

The rotated sibling `S` no longer grows, so its end is its stat size. If it grew after the stat (a race), the next
run reads the remainder.

### 4.5 Gaps are always reported, with byte counts

A gap is a byte range the pool will never hold. Each gap row carries:

- `kind`;
- `known_lost_bytes`: exact, computed only from sizes the product itself observed;
- `extent_unknown`: true when files could have existed and vanished between two stats;
- the time window `[last cursor read, this read]`, in device epoch;
- the affected file names. These are masked in every aiview projection.

| Kind | `known_lost_bytes` | `extent_unknown` |
|---|---|---|
| `ROTATED_AWAY` | `(last seen size of C.inode − offset)` + sizes of siblings in `C.family_snapshot` that are gone and were never read | true if the oldest surviving sibling's inode was not in `C.family_snapshot` (whole files rotated in and out unseen) |
| `TRUNCATED` | `offset − 0`, upper bound only if no sibling continued | true |
| `ANCHOR_MISMATCH` | unknown | true |
| `INITIAL_BASELINE` | exact skipped prefix | false |
| `BACKLOG` (not yet a loss) | 0; `pending_bytes` = exact unread bytes | false. It becomes `ROTATED_AWAY` if the backlog later rotates away |
| `COLLECTION_FAILED` | 0 | the window is reported; any loss shows up in the next run |

A gap is never silent. It appears in the run result, on the Logs screen for that device and day, through CLI
`syslog-gaps`, and in the daily summary. Whether a gap also sends a notification is §13 Q5.

### 4.6 Fast rotation and compressed rotations

A source's *retention horizon* is `rotate_count × rotation_size / growth_rate`, derived from M1 and M5 (§11). If
the horizon is below 9 h (the 6 h interval plus 50 % margin), the source is flagged `FAST_ROTATING` for that device.

The job windows forbid a shorter interval, so the run does not read more often. Instead:

1. The gap is measured and reported exactly as in §4.5.
2. The Logs screen shows the device's horizon next to the source.
3. Raising the device's rotation count is a device configuration change and is outside neXus. It is reported as a
   recommendation only.

The PO decides per source from the measurement (phase A §7.4).

If M1 or M5 shows rotated siblings stored compressed (`*.gz`), the remainder of the rotated file is not readable
without decompressing on the device. Decompression on the device is not proposed (D6). The remainder is recorded
as `ROTATED_AWAY` with its observed compressed size and `extent_unknown = true`, and §13 Q9 asks the PO.

## 5. Storage model

### 5.1 Artefacts

- Bytes go to the existing artefact store, using its existing envelope encryption with a per-artefact data key
  wrapped under the master key, and gzip on the neXus side (`ArtefactStore.open(…, gzip = true)`).
- There is one artefact per (device, source, run): a *chunk*. A day view concatenates that day's chunks in offset
  order. Envelope-encrypted objects are not appended to, so "one object per device per day" (phase A §5) becomes
  one object per run (four a day). A later daily compaction is allowed but not required.
- `ArtefactClass` gains `system_log`. Retention pruning reuses the backup pruning mechanism and is ledgered.
- HCP archives and spike data are stored as received in their own artefacts. Spike files of one data directory go
  into one neXus-side tar.gz.
- **Free-space guard.** A chunk is not written when the artefact volume has less than 15 % free space. The source
  records `STORAGE_REFUSED` (an `UNKNOWN` outcome; the cursor does not move, so nothing is lost while the device
  still holds the bytes). Reason: the 2026-10-10 disk incident.

### 5.2 Tables

These are created by one implementation migration, numbered at implementation time. It gets the BEGIN/ROLLBACK dry
run before deploy and audit triggers like V151. None is created by this document.

| Table | Key fields (no raw log text in any table) |
|---|---|
| `system_log_cursor` | `(device_id, source_key)` PK; `inode`, `offset_bytes`, `anchor_hmac`, `family_snapshot jsonb` (names, inodes, sizes, mtimes), `pan_last_receive_time` (device-local text exactly as received), `pan_boundary_seqnos jsonb`, `device_epoch_at_read`, `updated_job_id`, `updated_at` |
| `system_log_chunk` | `chunk_id`; `device_id`, `source_key`, `job_id`, `collected_at`, `log_day` (Europe/Istanbul date of `collected_at`), `file_name_hmac`, `inode`, `start_offset`, `end_offset`, `bytes`, `lines`, `first_line_time`/`last_line_time` (parsed when the line format is known, else null), `redaction_count`, `flags` (`LINE_SPLIT`, `ROTATION_FOLLOWED`), `artefact_ref`, `wrapped_data_key`, `plaintext_sha256`, `ciphertext_bytes`; unique `(device_id, source_key, inode, start_offset)` |
| `system_log_gap` | `gap_id`; `device_id`, `source_key`, `job_id`, `kind`, `known_lost_bytes`, `pending_bytes`, `extent_unknown`, `window_start_epoch`, `window_end_epoch`, `detail jsonb` (counts, suffixes, inode numbers; no names in clear) |
| `system_log_file` | whole-file fetches (`cp_sar_daily`, `cp_hcp_report`, `cp_spike_data`): `device_id`, `kind`, `remote_name_hmac`, `name_time` (parsed from the name), `size`, `mtime`, `artefact_ref`, `wrapped_data_key`, `sha256`, `expires_at`; unique `(device_id, kind, remote_name_hmac, mtime)` |
| `device_crash_entry` | `device_id`, `directory` (`crash`/`dump_usermode`), `name_hmac`, `process` (parsed; `kernel` for `/var/log/crash`; `UNKNOWN` if the name does not match the measured pattern), `size`, `mtime`, `first_seen_job`, `last_seen_job`, `removed_at` |
| `device_crash_alert` | `alert_id`; `device_id`, `process`, `count`, `newest_mtime`, `job_id`, `notified_at` |
| `system_log_run` | per job and source: outcome enum (§9), bytes read, bytes stored, reads issued, duration |

File names can carry host names (HCP names do). They are stored only as an HMAC for identity, plus the parsed safe
fields. The clear name exists only inside the encrypted artefact, in its manifest header.

### 5.3 Retention and caps (proposed defaults; PO-settable in Settings)

| Class | Retention | Per-device cap |
|---|---|---|
| Incremental raw chunks (CP and PAN) | 30 days (PO, phase A §7.1) | 2 GiB stored, compressed (`PENDING-M1`) |
| `cp_sar_daily` | 30 days | within the 2 GiB |
| `cp_spike_data` | 30 days | 512 MiB |
| `cp_hcp_report` | 7 days (PO) | 7 archives |
| Gap, run, crash entry and crash alert rows | 180 days (same as the parsed index in phase A §7.1) | — |

When a device reaches its cap before the retention ends, its oldest chunks are pruned early. The pruning is
ledgered and shown as `RETENTION_SHORTENED_BY_CAP`; new data is never refused for the cap. The alternative is
§13 Q4.

**Fleet estimate (formula; numbers `PENDING-M1`/`PENDING-M3M4`).**

`stored ≈ Σ_devices Σ_sources growth_per_day × 30 / r + Σ HCP sizes × 7`, where `r` is the gzip ratio. Plain-text
syslog typically compresses about 8–10×; this is to be confirmed on the first week of chunks.

The engineering report on the M1 findings gives this number before rollout, along with the artefact volume's free
space.

## 6. Palo Alto incremental query

The shape follows the M3/M4 executor (`PanSyslogMeasureJobExecutor`): enqueue, poll `action=get` until `FIN` within
a bound, then parse.

- **Cursor.** `pan_last_receive_time` is the newest `receive_time` seen, kept verbatim as the device-local string, so
  there is no time-zone conversion. `pan_boundary_seqnos` holds the `seqno`s already stored at exactly that second.
- **First run.** The seed query (newest `<n>` entries, newest first) is stored, and the cursor is set to its newest
  entry.
- **Each later page.** The forward query uses `receive_time geq '<cursor>'` and `dir=forward` (oldest first). Entries
  whose `seqno` is in `pan_boundary_seqnos` at the boundary second are dropped. If a page returns exactly `<n>`
  entries, the next page starts at that page's last `receive_time`.
- **Bounds.** At most 4 pages per log type per run, `<n> = 5000` (the PAN maximum; `PENDING-M3M4` for the real
  volume). If the bound is reached, the run records `BACKLOG`. Under the default settings that never means skipping.
- **Stall.** If a page holds more than `<n>` entries in the same second, paging cannot advance. The run records a
  `PAGE_STALL` gap with `extent_unknown = true` and moves the cursor one second forward.
- **Loss detection.** Whether `seqno` is contiguous per log type is **UNKNOWN**. It needs vendor documentation or M3
  evidence before freeze. Until it is confirmed, a seqno jump is recorded as `SEQ_GAP_SUSPECTED` with the jump size,
  never as a confirmed loss. The PAN log quota overwrite cannot otherwise be proven.
- **Storage.** One chunk per log type per run. The entries are kept as JSON Lines, one object per `<entry>`, with
  every child element name → text as received. This is the raw entry; the transformation is lossless for fields.
- **Config-log detail.** `before-change-detail` and `after-change-detail` can carry configured values. They are
  redacted on write by the secret rules (§12), and §13 Q7 asks whether to drop them entirely.
- **Polling.** The `action=get` checks are the PAN API's own asynchronous job pattern. They are bounded: 2 s
  interval, at most 30 checks per page, as in V151. This is the only repeated request in the design.
- `dir=forward` and the `query` filter syntax need an official PAN-OS XML API documentation citation recorded before
  freeze (vendor semantics law).

## 7. New crash/dump files: listing and alert

- The listing is `ls -lgo --time-style=+%s` of the two directories, one level, every run. The product never reads
  content, never descends into a crash directory, and never runs `file`, `tar` or `strings` on a dump.
- **New file.** A name that was not in the previous listing, or the same name with a changed size or mtime. It is
  matched by `name_hmac` and mtime.
- **First listing.** The first listing for a device is a baseline. Pre-existing files are recorded with
  `first_seen_job` set and raise no alert. They are shown as "present before monitoring".
- **Process name.** It is parsed from the file name with the pattern measured by M5 gate 5 (`PENDING-M5`). Entries
  under `/var/log/crash/` are `kernel`. A name that does not match is `UNKNOWN`, which is still alerted, with the
  process `UNKNOWN`.
- **Alert.** One alert per (device, run, process): process, count of new files, newest mtime (device time, shown in
  Europe/Istanbul). It goes through a new notification type `device_crash_file` ("Device crash and dump files").
  This is an amendment to the FROZEN `NOTIFICATION_ROUTING_CONTRACT.md` §2 and to its V100 type check, needs the PO's
  OK (§13 Q5), and follows that contract's digest, watermark and leader rules. Device names in mail follow the
  routing contract; aiview sees pseudonyms.
- **Removed file.** A file that disappears gets `removed_at` set. That is informational and raises no alert.

## 8. Whole-file fetches

All three use the existing `sftp_get` streaming fetch on the run's SSH session. The remote path is always a name
printed by a gated listing in the same run, so C4 §2.3 gives the fetch `NOT_APPLICABLE` gate resolution. This
contract still lists it in §10 so the PO approves it explicitly. The size bound is `maxBytes` from §3.4; a larger
file is `TOO_LARGE` and is not fetched.

### 8.1 HCP report (06:00 window)

- The listing is `cp_syslog_collect_ls_hcp`. The candidate is the newest name matching
  `^hcp_report_[A-Za-z0-9._-]+_[0-9]{2}_[0-9]{2}_[0-9]{2}_[0-9]{2}_[0-9]{2}\.tar\.gz$` (form `PENDING-M5`, gate 4).
- It is fetched only when the file is not already stored and has settled: mtime at least 10 min older than the
  device clock from the stat.
- Integrity is checked on neXus: the gzip and tar structure must be readable. The device does no `tar -t`. A broken
  archive is stored as `CORRUPT` and not retried.
- It is kept 7 days. An HCP missing on a given day is `ABSENT` for that day. It is not a failure, since the device
  may not run HCP.

### 8.2 sar (06:00 window)

- The listing is `cp_syslog_collect_ls_sa`. The candidate is `sar<DD>` whose mtime falls in the 30 h before the
  device clock and whose `DD` is the day before the device's date.
- It is fetched once; the `(name_hmac, mtime)` uniqueness prevents a second fetch.
- The binary `sa<DD>` is not fetched.

### 8.3 Spike Detective data

- The listing is `cp_syslog_collect_ls_spike`. A data directory is a subdirectory whose name matches the measured
  pattern (`PENDING-M5`, gate 3).
- A data directory is new when its name is not yet stored, and settled when its mtime is at least 10 min older than
  the device clock.
- For each new, settled directory the run does one `cp_syslog_collect_ls_spike_dir` and then one `sftp_get` per file
  named `perf_cpu_<N>.log` or `profiling_summary.elg`, up to 16 MiB per directory. Other members are listed in the
  manifest but not fetched.
- If M5 shows the data files are not in subdirectories, the `_dir` gate is dropped and the files are taken from the
  top-level listing.
- `spike_detective.log` and `.csv` are incremental sources (§4).

## 9. Outcomes and failure semantics

Per source, per run, exactly one of the following:

| Outcome | Meaning | Cursor |
|---|---|---|
| `COLLECTED` | new bytes stored | advanced |
| `NO_NEW_DATA` | nothing new | unchanged |
| `BACKLOG` | stored up to the budget; exact `pending_bytes` remain on the device | advanced partially |
| `GAP` (+ collected) | a §4.5 gap was recorded, and whatever remained was collected | advanced |
| `ABSENT` | source not present on this device (missing file or directory) | unchanged |
| `UNSUPPORTED` | platform not covered (Gaia Embedded, management server while disabled) | none |
| `TOO_LARGE` | whole-file fetch over its bound | unchanged |
| `STORAGE_REFUSED` | free-space guard | unchanged |
| `UNKNOWN` | transport failure, timeout, unreadable output, stat parse failure, SFTP unavailable, or anchor region not received | unchanged |

The job state is `COMPLETED` when every source ended in one of the first six outcomes. It is `FAILED` with
`partial:` when any source is `UNKNOWN` or `STORAGE_REFUSED`, and `OUTCOME_UNKNOWN` per the existing executor rules
when a step could not be recorded.

There are no retries inside a run (`retry_rule: none`). The next window is the retry, and the cursor guarantees
continuity. A source's failure is never reported as "no new data", and a missing file is never reported as a
failure.

## 10. Command table for PO approval

All rows: action class `read`. They are proposed with `sign_off_state = SIGNED_OFF` after the PO's OK, and they are
inserted by the implementation migration together with the fixture rows. Field names follow `gate_registry` and the
protocol's ten gate fields.

### 10.1 New — Check Point Gaia gateway (`check_point`, `cp_gaia_gateway`, shell `expert`)

| # | gate_id | Transport | canonical_command_key | timeout_s | retry | max_frequency | session_reuse_rule | Max bytes | unsupported_behavior_ref | secret_output_risk | safe_telemetry_fields | Relation to approved |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1 | `cp_syslog_collect_stat` | `SSH_EXEC` | `bash -lc 'date +%s; stat -c "%n %i %s %Y" /var/log/messages* $FWDIR/log/fwd.elg* $CPDIR/log/cpd.elg* $CPDIR/log/cpwd.elg* $FWDIR/log/fwk.elg* $FWDIR/log/mq_mng.elg* /var/log/spike_detective/spike_detective.log* /var/log/spike_detective/spike_detective.csv*'` | 30 | none | once per device per job window (≤ 4/day) | the run's one existing trusted SSH session; non-interactive bash login shell; sequential | 64 KiB output | an unmatched glob or missing file is a `stat` error line = `ABSENT`, not a failure | file paths may contain host-specific install paths; no content; paths masked in aiview projections | `["deviceEpoch", "name", "inode", "sizeBytes", "mtime"]` | NEW; combines V149 `cp_syslog_measure_stat` (`%i %s %Y`) and V157 `cp_syslog_measure_stat_ext` (`%n`) over the PO's source list, with family globs |
| C2 | `cp_syslog_collect_read` | `SSH_EXEC` (stdout-only, byte-exact, §4.3) | `bash -lc 'tail -c +<start> -- <path> \| head -c <cap>'`; `<start>`, `<cap>` digits only, `<cap> ≤ 8388672`; `<path>` = an absolute path printed by C1 in the same run, basename in the source's family pattern | 60 | none | per source per run, until the per-source (32 MiB) or per-device (64 MiB) budget; only inside the job window | same session; sequential; never concurrent with another command | `<cap>` (8 MiB + 64 B anchor) | missing file at read time (rotation race) = re-plan next run; `UNKNOWN` for this run | raw log lines: user names, addresses, host names, clish command audit lines that can carry `password-hash`/secret values; redacted on write (§12), stored encrypted, masked for aiview | `["startOffset", "bytesRead", "lines", "anchorMatch", "lineSplit"]` | NEW; phase A L2/L3 shape. Alternative to this row: §13 Q1 (SFTP ranged read) |
| C3 | `cp_syslog_collect_ls_crash` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/'` | 30 | none | once per device per job window | same session | 256 KiB | a missing directory is a result (`ABSENT`) | file names may carry process names, addresses (kernel crash directory names) or host names; names stored as HMAC plus the parsed process; masked in aiview | `["fileCount", "sizeBytes", "mtime", "process"]` | NEW; string identical to the approved V157 `cp_syslog_measure_ls_crash` (measurement-only frequency) |
| C4 | `cp_syslog_collect_ls_spike` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'` | 30 | none | once per device per job window | same session | 256 KiB | missing directory = `ABSENT` | names only | `["fileCount", "sizeBytes", "mtime", "namePattern"]` | NEW; string identical to V157 `cp_syslog_measure_ls_spike` |
| C5 | `cp_syslog_collect_ls_spike_dir` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/<dir>/'`; `<dir>` = a name printed by C4 in the same run, matching the measured data-directory pattern | 30 | none | once per new, settled data directory | same session | 64 KiB | missing = `ABSENT` | names only | `["fileCount", "sizeBytes", "mtime"]` | NEW; **conditional**: dropped if M5 gate 3 shows no subdirectories |
| C6 | `cp_syslog_collect_ls_sa` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/sa/'` | 30 | none | once per device per day (06:00 window) | same session | 64 KiB | missing = `ABSENT` | names only | `["fileCount", "sizeBytes", "mtime", "namePattern"]` | NEW; string identical to V157 `cp_syslog_measure_ls_sa` |
| C7 | `cp_syslog_collect_ls_hcp` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/hcp/last/'` | 30 | none | once per device per day (06:00 window) | same session | 64 KiB | missing = `ABSENT` | archive names carry the host name; stored as HMAC plus the parsed time; masked | `["fileCount", "sizeBytes", "mtime", "namePattern"]` | NEW; string identical to V157 `cp_syslog_measure_ls_hcp` |
| C8 | (`sftp_get`; gate `NOT_APPLICABLE` per C4 §2.3) | `sftp_get` subsystem on the same SSH session | fetch one absolute path printed by C4/C5 (spike data), C6 (`sar<DD>`) or C7 (HCP archive) in the same run; no other path | 120 (sar, spike), 300 (HCP) | none | HCP and sar: once per device per day; spike: once per new directory | same session (SFTP channel) | HCP 64 MiB, sar 16 MiB, spike 16 MiB per directory (`PENDING-M5`) | SFTP unavailable or path gone = `UNKNOWN`/`ABSENT`; over bound = `TOO_LARGE`, not fetched | HCP archives contain device diagnostics that may include configuration fragments and names: stored encrypted as received, never shown raw to aiview (§12); sar and spike data are counters and process names | `["sizeBytes", "sha256", "kind"]` | NEW; the existing streaming fetch used by `cp_gateway_backup` |

### 10.2 New — Palo Alto (`palo_alto`, `pan_firewall`, shell `not_applicable`, transport `PAN_XML_API`)

| # | gate_id | canonical_command_key | timeout_s | retry | max_frequency | session_reuse_rule | Max bytes | unsupported_behavior_ref | secret_output_risk | safe_telemetry_fields | Relation to approved |
|---|---|---|---|---|---|---|---|---|---|---|---|
| P1 | `pan_syslog_collect_system_seed` | `type=log&log-type=system&nlogs=<n>`; `<n>` digits, 1–5000 | 30 | none | once per firewall, the first run only (no cursor) | one existing-authentication API key per job; serial per firewall | 8 MiB per result (as V151) | API error = `UNKNOWN` | entries carry user names, addresses, host names; stored encrypted, masked for aiview | `["entries", "resultBytes", "enqueueToFinMs"]` | NEW; V151 `pan_syslog_measure_system_enqueue` with `nlogs` parameterized |
| P2 | `pan_syslog_collect_config_seed` | `type=log&log-type=config&nlogs=<n>` | 30 | none | first run only | same | 8 MiB | same | plus configured values in `before-/after-change-detail` (redacted on write, §6) | same | NEW; V151 `pan_syslog_measure_config_enqueue` parameterized |
| P3 | `pan_syslog_collect_system_enqueue` | `type=log&log-type=system&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')`; `<ts>` matches `^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$` and is the stored cursor | 30 | none | ≤ 4 pages per firewall per job window | same | 8 MiB per page | API error or unfinished job = `UNKNOWN`; the cursor is unchanged | as P1 | `["entries", "resultBytes", "enqueueToFinMs", "pages", "seqGapSuspected"]` | NEW (phase A L4) |
| P4 | `pan_syslog_collect_config_enqueue` | `type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')` | 30 | none | ≤ 4 pages per firewall per job window | same | 8 MiB | same | as P2 | same | NEW |
| P5 | `pan_syslog_collect_job_get` | `type=log&action=get&job-id=<id>`; `<id>` = the digits-only id the enqueue answered | 30 | none | every 2 s until `FIN`, at most 30 per enqueued job | same | 8 MiB | not `FIN` within the bound = `UNKNOWN` | as P1/P2 | `["jobStatus", "entries", "resultBytes"]` | NEW row; string identical to V151 `pan_syslog_measure_job_get`. A new row is needed because the V151 row's terms are measurement-only, with no retention |

### 10.3 Already approved (V149 / V151 / V157), not used by collection

There are 18 gates, all phase A measurement reads. They stay as they are and are **not** reused by the collectors.
Their `max_frequency` ("phase A measurement only…") and `secret_output_risk` ("…no raw retention") terms do not
cover a 6-hourly collection that keeps raw bytes. So even where a command string is identical, the collector gets
its own row. This is a new purpose, not a parse-scope extension.

| Migration | Gates |
|---|---|
| V149 (M1/M2, 5) | `cp_syslog_measure_stat`, `cp_syslog_measure_tail_messages`, `cp_syslog_measure_tail_fwd`, `cp_syslog_measure_tail_cpd`, `cp_syslog_measure_tail_cpwd` |
| V151 (M3/M4, 3) | `pan_syslog_measure_system_enqueue`, `pan_syslog_measure_config_enqueue`, `pan_syslog_measure_job_get` |
| V157 (M5, 10) | `cp_syslog_measure_stat_ext`, `cp_syslog_measure_ls_sa`, `cp_syslog_measure_ls_spike`, `cp_syslog_measure_ls_hcp`, `cp_syslog_measure_ls_crash`, `cp_syslog_measure_tail_fwk`, `cp_syslog_measure_tail_spike`, `cp_syslog_measure_tail_spike_csv`, `cp_syslog_measure_head_sar`, `cp_syslog_measure_hcp_listing` |

### 10.4 Count

| | Check Point | Palo Alto | Total |
|---|---|---|---|
| New, for approval | 8 (C1–C8: 7 gate rows plus the `sftp_get` fetch; C5 conditional) | 5 (P1–P5) | **13** |
| Already approved, measurement-only, not reused | 15 | 3 | 18 |

Per Check Point device, a typical run issues:

- C1;
- about 6–8 C2 reads (one per growing source, more after a rotation);
- C3;
- C4.

The 06:00 run adds C6, C7 and two C8 fetches.

Per Palo Alto firewall, a typical run issues 2 enqueues (P3, P4) and their P5 checks.

Not requested: MDS-scoped rows (§2.3), and the SFTP ranged-read alternative to C2 (§13 Q1).

## 11. How the measurements feed the parameters

As of 2026-10-17 no findings record for M1, M2, M3/M4 or M5 is committed. The M1 second read and both M5 reads run
in the coming windows (tonight's / tomorrow's). Every value below is a placeholder until the findings record lands.

| Parameter (this document) | Source measurement | Status |
|---|---|---|
| Per-read cap, per-source and per-device budgets (§3.4); 2 GiB device cap (§5.3) | M1: `messages`, `fwd`, `cpd`, `cpwd` growth per day (V149 `cp_syslog_measure_stat`, two reads 24 h apart); M5 gate 1 for `fwk`, `mq_mng`, spike log/csv | `PENDING-M1` (second read), `PENDING-M5` |
| Rotation mode per family (rename vs copytruncate), sibling naming, rotate count (§4.4, family patterns in §2.1 and C2) | M1 inode/size across the two reads (an inode change = rename; same inode with a smaller size = copytruncate); M5 gate 1 | `PENDING-M1`, `PENDING-M5` |
| `.elg` size-rotation threshold, hence the retention horizon and `FAST_ROTATING` (§4.6) | M1 + M5 gate 1 (size before and after the rotation) | `PENDING-M1`, `PENDING-M5` |
| Whether rotated siblings are `.gz` (§4.6, Q9) | M1 family listing (the V149 stat covers only `messages` and `messages.1`), so C1's first run is the first full family view | partly `PENDING`; confirmed only by C1's first run |
| sar naming, retention depth, size (§8.2, C8 bound) | M5 gates 2 and 9 | `PENDING-M5` |
| Spike directory layout and sizes (§8.3, C5 conditional) | M5 gates 3 and 7–8 | `PENDING-M5` |
| HCP name pattern, count, size, creation time (§8.1, C8 64 MiB bound) | M5 gates 4 and 10 | `PENDING-M5` |
| Crash/dump name pattern, hence the process-name parse (§7) | M5 gate 5 | `PENDING-M5` |
| Line timestamp forms for `first/last_line_time` (§5.2) | M2 (V149 tail gates); M5 gates 6–8 | `PENDING` (no M2 findings record committed) |
| PAN `<n>`, pages per run, poll bound, 8 MiB result cap (§6, P1–P5) | M3/M4 summaries in `system_log_measurement` (entries, span, resultBytes, enqueueToFinMs) | `PENDING-M3M4` (findings record not committed) |
| PAN `seqno` contiguity, `dir=forward`, `query` syntax | official PAN-OS XML API documentation, plus M3 field names | **UNKNOWN**; needs a documentation citation before freeze |

## 12. Privacy, masking, secrets (the raw-evidence exception)

- **Exception boundary.** Raw retention is authorised for the system-log programme only (PO record 2026-10-15 §3,
  D5). It covers the §2.1 sources and nothing else collected in the same session.
- **Secrets are redacted on write, before encryption.** Lines are redacted with tested rules covering:
  - Gaia clish audit lines carrying `password-hash`, `expert-password-hash`, `set user … password`;
  - SNMP community and `usm` auth/priv strings;
  - RADIUS/TACACS `secret`/`key`;
  - `password=`, `passwd`, `key=`;
  - base64 blobs after auth keywords;
  - PAN config-log `before-/after-change-detail` values for secret-bearing paths.

  Each redaction replaces the value with `<redacted>` and counts it (`redaction_count`). The rules are tested with
  synthetic lines only.
- **Archives are not redacted.** HCP archives and spike data cannot be redacted without unpacking. They are stored
  as received, encrypted, with access limited to `operation_admin` and `security_admin`, and are never served raw to
  aiview (aiview sees the masked member listing only).
- **aiview** always gets the masked projection. The Data Sanitizer text rules (prefix-preserving IP permutation,
  `TopologyNamePseudonymizer` device names, hostname/MAC/e-mail rules, the same HMAC key) are applied at read time to
  every line and every file name. Raw text is never in an aiview response.
- **Leaving the product.** Exports run only through the Data Sanitizer text path (CSV/JSON for PAN JSON Lines). The
  HCP tar.gz is not a sanitizer input today, so it cannot be exported until the sanitizer gains an archive path
  (§13 Q6).
- **Engineering sessions** follow the aiview mentality on HOST-A: counts, opaque ids and outcomes only. They never
  read raw chunks.
- **Database.** No table holds raw log text (§5.2). Anchors and file names are stored as HMACs.

## 13. Open questions for the PO

1. **Read method.** The recommendation is C2, `tail -c +<start> | head -c <cap>` over SSH. The alternative is an
   SFTP ranged read, which runs no shell process on the device but needs a C4 contract amendment. Which one?
2. **Command approval.** Do you approve the 13 commands in §10.1/§10.2 exactly as written? C5 is conditional on M5.
3. **First run backfill.** How much existing data should the first run take? The proposal is the last 8 MiB of each
   live file only, with no rotated history, recorded as `INITIAL_BASELINE`. The alternative is to also take the
   rotated siblings within the per-device budget.
4. **Cap reached.** When a device reaches its cap (2 GiB), should the oldest chunks be pruned early (the proposal,
   ledgered and shown), or should new collection stop?
5. **Notifications.** May a new notification type `device_crash_file` be added? It amends the FROZEN notification
   routing contract and its V100 check. Should rotation-loss gaps (`ROTATED_AWAY`/`TRUNCATED`) also notify, or only
   show on screen and in the daily summary (the proposal)?
6. **HCP export.** HCP archives cannot be sanitized today. Should their export stay blocked (the proposal) until
   the sanitizer handles tar.gz?
7. **PAN config-log details.** Should the `before-/after-change-detail` fields be stored with secret redaction (the
   proposal), or dropped entirely?
8. **VSX.** Is VS0 enough, or should the per-virtual-system `fwd`/`fwk` logs be measured and added later?
9. **Compressed rotations.** If M1 shows `.gz` rotated files, should their unread remainder be accepted as a
   reported gap (the proposal), or decompressed on the device (more device CPU)?
10. **Operator view.** Inside the product, may `operation_admin`/`security_admin` view unmasked lines? The proposal
    is yes, audited per view. aiview is always masked.

## 14. CLI parity (every UI action has a CLI command)

| CLI command | API | Action id (new) | Roles |
|---|---|---|---|
| `syslog-collect-start [deviceId]` | `POST /api/v2/system-logs/collect` | `system_log_collect_start` | operation_admin, security_admin (window-gated) |
| `syslog-sources <deviceId>` | `GET /api/v2/system-logs/{deviceId}/sources` (cursor state, horizon, `FAST_ROTATING`, last outcome) | `system_log_read` | operator and above; aiview masked |
| `syslog-chunks <deviceId> [source] [day]` | `GET …/{deviceId}/chunks` | `system_log_read` | same |
| `syslog-view <chunkId>` | `GET …/chunks/{chunkId}/text` (masked for aiview; unmasked per Q10, audited) | `system_log_read` / `system_log_read_unmasked` | Q10 |
| `syslog-gaps [deviceId]` | `GET /api/v2/system-logs/gaps` | `system_log_read` | operator and above |
| `syslog-export <deviceId> <source> <day> <reason> <destPath>` | `POST …/export` (Data Sanitizer text path only) | `system_log_export` | operation_admin, security_admin |
| `syslog-crash-files [deviceId]` | `GET /api/v2/system-logs/crash-files` | `system_log_read` | operator and above |
| `syslog-hcp <deviceId>` | `GET …/{deviceId}/hcp` (masked member listing) | `system_log_read` | operator and above |
| `syslog-settings` / `syslog-settings-set <key> <value>` | `GET/PUT /api/v2/config/system-logs` (retention, caps, `include_management_servers`) | `system_log_settings_write` | security_admin |

The existing `syslog-measure-start` and `syslog-measurements` stay for phase A.

## 15. Implementation outline (after freeze; not authorised by this DRAFT)

1. Migration:
   - the gate rows of §10;
   - the tables of §5.2;
   - `ArtefactClass.SYSTEM_LOG`;
   - the `ui2_job_module` mapping;
   - the notification type (if Q5 is approved).

   It gets the BEGIN/ROLLBACK dry run on the live database before deploy.
2. Transport: stdout-only, byte-exact streaming exec (§4.3).
3. Planner (§4.4) as a pure function over (cursor, stat), with exhaustive unit tests for every row of the decision
   table and every gap kind.
4. Executors, scheduler trigger and staggering, retention pruning, the free-space guard.
5. Redaction rules and the masking projection, with synthetic-line tests.
6. Logs screen and CLI (§14); effectiveness review at the end.
7. Architecture tests and Java lane tests before ship. The first rollout goes to one device per vendor in one
   window, and the run outcomes are checked before fleet enablement.
