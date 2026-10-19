# Device system-log programme — measurement findings (M1/M2/M3/M4/M5, 2026-10-16/17)

**Status: RECORD — measurement record, committed next to the contracts (rule: measurement record before contract). It
decides nothing and authorizes no command; the parameters it determines are applied in
[DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) §11.** Counts, sizes, shapes and
formats only. No raw log line, host name, address, serial or file name is recorded here. Device names are the
aiview pseudonyms.

## Özet (PO için)

**Ne ölçüldü.** 53 Check Point gateway (20 küme içinde 40 üye, 13 tekil cihaz) ve 39 Palo Alto firewall. Check Point
tarafında 16 Ekim 12:02 (son 20 satır okumaları) ve 17 Ekim 06:05 (M1 ilk okuma ve on M5 okuması) koşuları, Palo Alto
tarafında 17 Ekim 06:05 koşusu. Hiçbir ölçüm komutu hata vermedi; tek eksik, bir gateway'de HCP klasörünün hiç
olmaması.

**Ne öğrendik.**

1. Palo Alto tarafı net. Sistem log'u medyan günde yaklaşık 1.800 kayıt, en yoğun firewall günde yaklaşık 22.000.
   Config log'u günde yaklaşık 2 kayıt. Sayfa boyutu için 2.000, koşu başına en çok 4 sayfa öneriyoruz; ölçülen en yoğun firewall'un 6 saatlik hacminin
   yaklaşık 1,5 katını karşılıyor. Zaman biçimi sabit.
2. HCP raporu küçük: cihazdaki HCP klasörünün toplamı en çok 4,8 MiB. Taslaktaki 64 MiB sınırı gereksiz geniş; 16 MiB
   öneriyoruz.
3. `fwk.elg` ve `mq_mng.elg` her gateway'de var, yalnızca VSX'te değil. Satır zaman biçimleri (messages, elg, sar,
   Palo Alto) belirlendi.
4. Spike Detective klasöründe bazı cihazlarda binlerce alt klasör birikmiş (en çok 3.638). İlk koşuda hepsini
   çekmeyeceğiz; ilk liste "başlangıç çizgisi" olacak ve koşu başına en çok 16 klasör alınacak.
5. 25 cihaz, her komutun başında fazladan bir satır basıyor (içeriğini göremiyoruz). Toplayıcı çıktıyı konuma göre
   değil biçime göre okumalı.
6. `/var/log/crash` hepsinde boş. `/var/log/dump/usermode` içinde 39 cihazda toplam 122 dosya var; bunlar ilk
   listede "izlemeden önce vardı" diye işaretlenecek, alarm üretmeyecek.

**Bilmediğimiz.** Büyük dosyaların boyutu, günlük büyüme hızı ve döndürme sıklığı. Sebep: aiview maskesi 100.000'den
büyük her sayıyı (dosya boyutu, zaman damgası), tüm dosya adlarını ve saat değerlerini gizliyor. Bu yüzden messages,
cpd, fwk, sar gibi büyük dosyaların boyutu ve yaşı bu veriden hesaplanamadı. M1'in ikinci okuması aynı maskeden
geçerse büyümeyi yine veremez. Çözüm önerisi: Palo Alto'da yaptığımız gibi, Check Point ölçümünü sunucu tarafında
sayısal özete çevirmek (isim yok, yalnızca boyut/yaş/sayı); bu ek okuma onayı gerektirir. O zamana kadar okuma
tavanları (8/32/64 MiB) "geçici" duruyor. Yetersiz kalırsa veri sessizce kaybolmaz, ekranda `BACKLOG` olarak görünür.
Palo Alto `seqno` ardışıklığı, `dir=forward` ve `query` sözdizimi resmi doküman bekliyor.

---

## 1. What was run

| Run | When (Europe/Istanbul) | Content | Devices |
|---|---|---|---|
| M2 (V149) | 2026-10-16 12:02 / 12:04 | `tail -n 20` of `messages`, `fwd.elg`, `cpd.elg`, `cpwd.elg` | 53 Check Point gateways |
| M1 first read (V149) | 2026-10-17 06:05 | `cp_syslog_measure_stat`: `stat -c "%i %s %Y"` of `messages`, `messages.1`, `fwd.elg`, `cpd.elg`, `cpwd.elg` | 53 |
| M5 (V157) | 2026-10-17 06:05 | all ten gates: `stat_ext`, `ls_sa`, `ls_spike`, `ls_hcp`, `ls_crash`, `tail_fwk`, `tail_spike`, `tail_spike_csv`, `head_sar`, `hcp_listing` | 53 |
| M3/M4 (V151) | 2026-10-17 06:05 | `pan_system` and `pan_config`, 100 entries each, one summary per log type | 39 Palo Alto firewalls |

Outputs: 53 + 159 + 583 Check Point output files (every gate answered on every device) and 78 PAN summaries. The 53
Check Point devices are 13 standalone and 40 cluster members (20 clusters). VSX/USFW status of a device cannot be told
from this data.

Statistics: n is the number of devices unless stated. `p90` is the value at index `floor(0.9 * (n - 1))` of the sorted
values (no interpolation).

## 2. What the projection hides (read this first)

The Check Point outputs reached this record only through the aiview diagnostic projection (`DiagnosticText.masked`),
which keeps a token only when it is in a safe vocabulary, is an integer of at most 5 digits, or is an interface name.
Everything else becomes a keyed pseudonym token (equal value, equal token; no order, no magnitude). Consequences:

| Hidden by the projection | Effect on this record |
|---|---|
| every integer of 6 or more digits: file sizes of 100,000 B and above, epoch mtimes, large inodes and pids, `total` block counts of 100,000 KiB and above | **no size above 99,999 B, no mtime and therefore no age, no rotation timing, no growth rate can be computed** for any file. Only "below 100,000 B (value visible)" versus "100,000 B or more (masked)" is known. Two masked values can be compared for equality only |
| every file name and every word outside the vocabulary (months, program names, permission strings, directory names) | file naming, rotation suffixes, `.gz`, sar names, HCP names, Spike Detective directory names and crash process classes are **not observable**; only counts and positions are |
| times written `HH:MM:SS` and decimals such as `0.00` | line times and sar values are masked; `HH:MM` (tar member times) stays visible |
| any line containing a secret word (`password`, `key`, `token`, `enc`, `credential`, and similar) | replaced by one marker line; counted here |

Everything below that is marked **inferred** is read from token positions and classes, not from vendor documentation.
The PAN summaries were computed inside the product from the raw response and are not affected: every PAN number below is
exact.

**Consequence for M1.** The second M1 read (due 24 h after the first, 2026-10-18 06:05) will give only "same token or
different token" for any size of 100,000 B or more. It gives real growth only for the files that are currently below
100,000 B (the `fwd.elg` and `cpwd.elg` files that are small, the Spike Detective `.csv`, a few `.log` files).
Proposal R1 in §9.

## 3. Check Point incremental sources

### 3.1 Presence and size class (M1 first read and M5 gate 1)

| File | Present | Size 0 | Size below 100,000 B (visible) | Size 100,000 B or more (masked) |
|---|---|---|---|---|
| `messages` | 53 / 53 | 0 | 6 (4,290 to 39,617 B; median 28,014) | 47 |
| `messages.1` | 53 / 53 (plain name, no `.gz`; the exact name was stat'ed) | 0 | 0 | 53 |
| `fwd.elg` | 53 / 53 | 8 | 3 more (30,448; 62,988; 76,657 B) | 42 |
| `cpd.elg` | 53 / 53 | 0 | 0 | 53 |
| `cpwd.elg` | 53 / 53 | 13 | 5 more (16,541 to 66,520 B) | 35 |
| `fwk.elg` | **53 / 53** | 0 | 0 | 53 |
| `mq_mng.elg` | **53 / 53** | 0 | 1 (55,718 B) | 52 |
| `spike_detective.log` | 53 / 53 | 0 | 7 (6,853 to 60,099 B) | 46 |
| `spike_detective.csv` | 53 / 53 | 0 | 53 | 0 |
| `hcp.elg` (out of scope; M5 gate 1 only) | 52 / 53 | 0 | 0 | 52 |

- `fwk.elg` and `mq_mng.elg` exist on every measured gateway. They are not VSX/USFW-only in this fleet, so the
  `ABSENT` case for them (Phase B §2.1) is the exception, not the rule.
- `spike_detective.csv` is small and bounded: min 1,661, median 18,496, p90 21,919, max 23,678 B (n=53). 46 of 53 lie
  between 17.6 and 23.7 kB. This looks like a size-bounded, size-rotated file (**inferred**); the threshold and the
  rotated names are not observable. The `.csv` size is visible on every device, so the second M5 read gives its growth
  and any rotation exactly.
- `messages` is below 100,000 B on 6 devices while `messages.1` is 100,000 B or more on all 53. Six live files are
  therefore recent (just rotated or recently created); the rotation period is not derivable.
- inode of `messages`: visible (below 100,000) on 42 devices, masked on 11. **23 devices show the same inode value
  (68)** and 2 more the value 100; `messages.1` inodes are spread (47 visible values; one value is shared by 2 devices). This is compatible with a live file that is
  never replaced (copytruncate), and also with an allocator that reuses the same free inode after a rename rotation.
  It does not decide the rotation mode (§8).
- The M1 stat answered with exactly 5 data lines on all 53 devices and no error line, so `messages.1` exists
  everywhere. Siblings beyond `.1` (`.2`, `.2.gz`, dated names) were not part of the command and are unknown.

### 3.2 Command banner line (all 15 gates)

25 of 53 devices print **one extra first line** on every command (M1, M2 and M5: the same 25 devices, every output; the `fwk.elg` output of 6 further devices also starts with a
redacted line, but that is a data line).
The projection replaced it with the secret-word marker, so its content is unknown; it looks like a login notice from the
`bash -lc` login shell (**inferred**). It is never a data line. Any parser of the C1 output (`date +%s` then `stat`
lines) and of the C3/C4/C6/C7 `ls` outputs must locate its lines by shape and must not assume line 1. This also explains why 21-line outputs appear for `tail -n 20`.

### 3.3 Line formats (last 20 lines; 53 devices x 20 lines = 1,060 lines per source unless stated)

All shapes below are **inferred** from token classes and positions; times are masked, so only the shape and the day
number (visible) are known.

| Source | Shape (placeholders only) | Notes |
|---|---|---|
| `messages` | `<Mon> <DD> <HH:MM:SS> <YYYY> <host> <prog>[<pid>]: <text>` | all 1,060 lines match; **0 continuation lines**; one month token on all lines; `<pid>` visible 777, masked (6 digits or more) 130, absent 153; program `clish` 212 (20.0 %), `expert` 53 (5.0 %), other 795 (75.0 %). The year is printed; no zone is printed |
| `fwd.elg` | mostly free-form; 29 of 900 lines (3.2 %, 5 devices) have the elg header `[<proc> <pid> <tid>]@<host>[<DD> <Mon> <HH:MM:SS>] <text>`, **871 lines (96.8 %) have no timestamp** | 8 devices: file empty (size 0, tail empty). 45 devices: 20 lines. No year in the header |
| `cpd.elg` | `[<proc> <pid> <tid>]@<host>[<DD> <Mon> <HH:MM:SS>] <text>` | 720 lines (67.9 %) have the proc/pid/tid header, 612 (57.7 %) a timestamp; **at least 186 lines (17.5 %) are continuation fragments** (multi-line records); 14 devices have no timestamped line among the last 20; on the 39 others all timestamps are dated the 16th. Pid or tid masked (6 digits or more) on 130 lines |
| `cpwd.elg` | same elg header, on all 800 lines (40 devices) | 13 devices: empty. 767 lines carry a timestamp. The last 20 lines span 1 day on 36 devices, 2 days on 3, 3 days on 1 (days 14, 15, 16) |
| `fwk.elg` | `[<DD> <Mon> <HH:MM:SS>][<instance>];[<cpu>];[<src>:<port> -> <dst>:<port>] [<LEVEL>]: <text>` (connection lines) and free-form lines | 159 of 1,060 lines (15.0 %) on 9 devices were removed wholesale by the secret filter, **all 20 lines on 5 devices**: fwk text often contains secret-vocabulary words. 625 of the 901 visible lines have the header, 652 a timestamp, 249 none. Lines dated the 16th appear on 34 devices (4 of them also the 15th); on three devices the timestamped lines are dated only the 12th, 13th and 22nd (stale writers) |
| `spike_detective.log` | `<time-token> info: type: <class>, ..., start time: <date time>, duration (<unit>): <N>, ..., cpu usage: <N>, average cpu usage: <N>, ...` | 20 lines on all 53 devices; every line has 8 comma-separated segments; two record shapes: lines with `id`/`name` keys 247 (23.3 %), cpu lines 813 (76.7 %). The first token is one whitespace-free masked token (its form is unknown) |
| `spike_detective.csv` | 8 comma-separated columns: `<time-token>,<type>,<id>,<name>,<duration>,<N>,<N>,<0 or 1>` | 5 lines on all 53 devices (265 lines); column 2 is `cpu` on 181 lines, another type word on 84; column 8 is `0` on 142 and `1` on 123; columns 3, 5, 6, 7 are integers of at most 5 digits |

Implications used in the contract: only `messages` lines carry a year; elg and fwk lines carry day, month and time but
no year; `fwd.elg` lines mostly carry no time at all; records can span lines (`cpd.elg`, `fwk.elg`); a "line" is not a
record; no source prints a time zone.

### 3.4 Tails: failures and emptiness

Every gate produced output on every device (no timeout, no transport error). Empty bodies are real empty files
(`fwd.elg` 8 devices, `cpwd.elg` 13 devices; both match size 0 in the stat). No `tail` error line appeared.

## 4. Whole-file, listing and archive sources (M5)

### 4.1 sar (`ls_sa`, `head_sar`)

| Measure | n | min | median | p90 | max |
|---|---|---|---|---|---|
| entries in `/var/log/sa/` | 53 | 59 | 60 | 60 | 60 |
| CPUs in the sar header | 53 | 6 | 72 | 128 | 128 |

- 47 devices list 60 files and 6 devices list 59. 60 = 30 days x 2 file kinds (`sa<DD>` binary and `sar<DD>` text;
  the kinds cannot be told apart because names are masked, **inferred** from `ls` order and the 2 x 30 count).
- Every one of the 3,174 listed files is 100,000 B or more (all masked). The `total` line is visible on 7 devices only:
  62,356 to 98,164 KiB for the whole directory (60 files). The other 46 are 100,000 KiB or more.
- Day-to-day stability: in the first 30 listed files of a device, 2 to 3 distinct sizes occur; in the second 30, 1 to 29
  (median 2, p90 8). Daily sizes of one device are nearly constant (**inferred**).
- Directory total against CPU count (7 visible devices): 6 CPUs 60.9 MiB, 8 CPUs 72.5 MiB, 16 CPUs 89.1 to 95.9 MiB
  per 60 files (KiB values in the previous bullet). Linear extrapolation with the steepest observed slope (3,581 KiB per CPU per 60 files, between 6 and
  16 CPUs) gives about 487 MiB for 60 files at 128 CPUs, so about 8.1 MiB per file on average and at most 16.3 MiB
  for a text file if the binary files were negligible. **Extrapolation from 7 devices of 16 CPUs or fewer to 15
  devices of 128 CPUs; low confidence.**
- `head -n 20` of yesterday's file: line 1 `Linux <kernel> (<host>)<TAB><date><TAB><arch> (<N> CPU)`, a blank line, then a
  header with 12 whitespace-separated columns on all 53 devices (`<time> CPU` plus 10 value columns; **no AM/PM
  token, so 24-hour time**), then one `all` row and one row per CPU. 20 lines cover only the first sample (17 data
  rows), so the sample interval is not observable.
- A usable file for "yesterday" existed on all 53 devices (the command printed a header on all).
- `sar` text values are masked (decimals), so the content class cannot be checked here.

### 4.2 Spike Detective directory (`ls_spike`)

| Measure | n | min | median | p90 | max |
|---|---|---|---|---|---|
| entries in `/var/log/spike_detective/` | 53 | 2 | 19 | 96 | 3,638 |
| of which directories (link count 2) | 53 | 0 | 14 | 93 | 3,631 |
| of which plain files (link count 1) | 53 | 2 | 4 | 7 | 7 |
| `total` (KiB, files only) | 53 | 12 | 1,480 | 4,764 | 4,988 |

- Devices with at least 100 entries: 6; at least 500: 3; at least 1,000: 1 (3,638 entries). A masked listing line is
  about 52 B; a raw `ls -lgo` line is about the same plus (name length minus 19), so the largest listing is
  **about 189 KB raw for 19-character names and 3.6 KB more per extra name character, so above the 256 KiB bound of C4
  once names exceed about 39 characters (name length is not observable)**. The
  second largest device has 707 entries (about 37 KB).
- Data directories: all link count 2 (no sub-directories), sizes 57 to 114 B (median 57; short directory entries,
  **inferred** from the filesystem behaviour). Their **names and contents are not observable**: gate 3 lists only the
  parent. The `perf_cpu_<N>.log` / `profiling_summary.elg` layout of the contract is **unverified**.
- Plain files: 2 to 7 per device (2 on 8 devices, 3 on 14, 4 on 14, 5 on 1, 6 on 8, 7 on 8); the visible sizes (96
  files below 100,000 B) are 0 to 60,099 B (median 17,770); the other 127 are 100,000 B or more. Rotated copies of
  the `.log`/`.csv` are the likely extra files; their naming is not observable.
- Cluster members differ: of 20 clusters only 1 has equal entry counts on both members.

### 4.3 HCP (`ls_hcp`, `hcp_listing`)

| Measure | n | min | median | p90 | max |
|---|---|---|---|---|---|
| files in `/var/log/hcp/last/` | 52 | 4 | 5 | 7 | 9 |
| `total` of that directory (KiB; every file together) | 52 | 3,152 | 3,310 | 4,132 | 4,840 |
| members shown by `tar -tzv` of the newest `*.tar.gz` | 52 | 2 | 3 | 3 | 5 (one device; 48 show 3, 3 show 2) |

- **Every device's whole HCP directory is at most 4,840 KiB (4.73 MiB), so every archive is at most 4.73 MiB.**
- The selection `ls -t /var/log/hcp/last/*.tar.gz | head -1` found an archive on 52 of 53 devices. One device
  (FW-KILO-06-M2) has no `/var/log/hcp/last/` directory and no `hcp.elg`: `ls` and `tar` answered with "No such file or
  directory" errors (5 error lines in `hcp_listing`, 1 in `ls_hcp`, 1 in `stat_ext`). That is `ABSENT`, not a failure.
- Archive shape: 2 to 5 members (48 devices show 3, 3 show 2, 1 shows 5); member lines are `<mode> <owner/group> <size> <date> <HH:MM>
  <name>`; 49 member sizes are visible (6,357 to 7,273 B) and 106 are 100,000 B or more, so each archive has about
  two small members and one large one (**inferred**). No listing reached the 50-line cap.
- Member time `HH:MM` (visible; date masked; device clock) of the newest archive: 36 of 52 devices between 02:55 and
  03:05; 12 others between 00:16 and 05:54; 4 between 06:08 and 06:12. The nightly run is therefore **not at 03:00 on
  every device**; four devices' newest archive carries a time inside the 06:00 window.
- The directory holds more than one file on every device (4 to 9). File names (masked) carry no observable pattern. One
  device lists a 76 B file named with a plain English word.

### 4.4 Crash and dump directories (`ls_crash`)

- `/var/log/crash/`: present and **empty (total 0, no entry) on all 53 devices**.
- `/var/log/dump/usermode/`: present on all 53; **empty on 14, at least one file on 39**. Files per device: min 0,
  median 2, p90 4, max 36 (122 files in total; 16 devices with 2, 10 with 1, 6 with 4, 5 with 3, 1 with 5, 1 with 36).
  118 of 122 file sizes are 100,000 B or more (4 visible: 152, 22,535, 55,838, 57,097 B). Directory `total`: 0 on 14
  devices, 6,152 to 34,312 KiB on 7, 100,000 KiB or more on 32.
- Names are masked, so the process-name class of the dumps is **not observable**.
- A missing directory did not occur (no error line).

## 5. Palo Alto (M3/M4)

Exact figures from the product's own summaries (39 firewalls, one `pan_system` and one `pan_config` summary each, all
status `MEASURED`, `polls` 1, 100 of 100 requested entries returned, `capped` true, receive-time format parsed on all
entries, 0 unparsed, 0 unrecognised field names).

| Measure (n = 39) | min | median | p90 | max |
|---|---|---|---|---|
| `pan_system`: result bytes (100 entries) | 114,976 | 116,752 | 117,648 | 122,727 |
| `pan_system`: bytes per entry | 1,150 | 1,168 | 1,177 | 1,227 |
| `pan_system`: time span of 100 entries (s) | 393 | 4,881 | 10,230 | 11,231 |
| `pan_system`: entries per day (extrapolated from the span) | 769 | 1,770 | 4,267 | 21,985 |
| `pan_system`: entries per 6 h (extrapolated) | 192 | 443 | 1,067 | 5,496 |
| `pan_system`: stored bytes per day (extrapolated, XML bytes) | 0.89 MB | 2.07 MB | 5.01 MB | 26.25 MB |
| `pan_system`: enqueue-to-FIN (ms) | 2,126 | 2,163 | 2,200 | 2,209 |
| `pan_config`: result bytes (100 entries) | 102,798 | 103,664 | 104,148 | 106,898 |
| `pan_config`: bytes per entry | 1,028 | 1,037 | 1,041 | 1,069 |
| `pan_config`: time span of 100 entries (days) | 41.1 | 64.1 | 95.3 | 107.1 |
| `pan_config`: entries per day | 0.9 | 1.6 | 1.9 | 2.4 |
| `pan_config`: enqueue-to-FIN (ms) | 2,117 | 2,163 | 2,195 | 2,204 |

- Receive-time format on all 78 summaries: `yyyy/MM/dd HH:mm:ss` (device-local, no zone), parsed with 0 failures. This
  matches the cursor shape of Phase B §6 and P3/P4.
- Every request was `FIN` at the first check, after 2.1 to 2.2 s: the time is flat, so it is dominated by a fixed
  latency, not by the entry count at n=100. **The time for a page of 2,000 or 5,000 entries is not measured.**
- 100 newest entries only: the per-day estimates come from the span of the newest 100 entries, which is biased toward
  the activity at the measurement time (bursty); the true daily and 6-hour volume can be higher or lower.
- Fleet totals (extrapolated, XML bytes as a proxy for stored bytes): about 106,600 system entries per day and about
  125 MB per day for `pan_system`, about 60 KB per day for `pan_config`; about 3.76 GB raw per 30 days for the
  fleet; the largest firewall about 0.79 GB raw per 30 days.
- Field names (`pan_system`, 28, all present on all 39): `actionflags`, `config_ver`, `device_name`,
  `dg_hier_level_1` to `dg_hier_level_4`, `dg_id`, `domain`, `eventid`, `fmt`, `high_res_timestamp`, `id`,
  `is-logging-service`, `module`, `object`, `opaque`, `receive_time`, `seqno`, `serial`, `severity`, `subtype`,
  `time_generated`, `tpl_id`, `type`, `vsys`, `vsys_id`, `vsys_name`.
- Field names (`pan_config`): 24 always present (`actionflags`, `admin`, `client`, `cmd`, `config_ver`, `device_name`,
  `dg_hier_level_1` to `dg_hier_level_4`, `dg_id`, `domain`, `high_res_timestamp`, `host`, `is-logging-service`,
  `receive_time`, `result`, `seqno`, `serial`, `subtype`, `time_generated`, `tpl_id`, `type`, `vsys_id`) and 4 on some
  firewalls only: `path` and `full-path` (6 firewalls), `after-change-preview` (6), `before-change-preview` (5).
  **The field names `before-change-detail` and `after-change-detail` used in Phase B §6/§12 were not observed; the
  observed names end in `-preview`.** Whether `-detail` fields exist in other entries is unknown.
- `seqno` is present on every entry of both log types. Whether it is contiguous is **not measured** (the summary holds
  no sequence statistics).
- Enum classes, `pan_system` (39 firewalls, 100 entries each):
  - `severity`: `informational` 3,750, `medium` 116, `critical` 30, `high` 4 (critical on 6 firewalls).
  - `subtype`: `general` 2,550, `device-telemetry` 515, `auth` 459, `userid` 184, `vpn` 110, `lacp` 18, `url-filtering`
    14, `ntpd` 10, `iot` 8, `ha` 6, `routing` 6, `ras` 6, `satd` 6, `panorama-check` 4, `port` 2, `sslmgr` 2.
  - `eventid`: 2 to 15 distinct values per firewall (median 4, p90 11). Most frequent classes: `general`,
    `send-success`, `auth-success`, the LDAP connect events (success and failure), `link-down`, the IPsec and IKE
    negotiation events, `ha`-related sync events.
- Enum classes, `pan_config` `cmd` (3,900 entries): `commit-all` 3,590, `validate-all` 278, `edit` 13, `commit` 11,
  `override` 4, `set` 2, `delete` 2. 92 percent of the config log is `commit-all`; the entries that carry a
  changed path (`edit`, `set`, `override`) are 19 of 3,900.

## 6. Failures per gate

| Gate | Failures | Shape |
|---|---|---|
| all 15 Check Point gates, 53 devices each | 0 gates failed to answer | n/a |
| `stat_ext` | 1 device | one `stat` error line, "cannot stat" + masked path + "No such file or directory" (`hcp.elg`) |
| `ls_hcp` | 1 device (same device) | one `ls` error line, "cannot access" + directory + "No such file or directory" |
| `hcp_listing` | 1 device (same device) | 5 lines: `ls` "cannot access ... *.tar.gz", `tar` "Cannot open: No such file or directory", "Error is not recoverable", "returned status 2" |
| `stat` (M1), `tail_*`, `ls_sa`, `ls_spike`, `ls_crash`, `head_sar` | 0 | none |
| `pan_system`, `pan_config` | 0 of 78 | all `MEASURED` |

Not failures but not data: 25 devices print one leading line on every command (§3.2). The secret filter removed 159
`fwk.elg` lines (§3.3).

## 7. What this record determines

See [DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) §11 for each parameter, its status
and the number now in the contract. In short:

- Determined (exact or bounded): PAN page size, pages per run, seed sizes, result cap, poll bound, receive-time form
  and the PAN field names; the HCP archive bound (at most 4.73 MiB observed); line-time forms of `messages`, elg,
  fwk and sar; presence of `fwk.elg` and `mq_mng.elg`; `.1` exists as a plain `messages` sibling; the Spike
  Detective directory population; the crash and dump baselines; the leading-line behaviour.
- Provisional (a number is proposed, a wrong guess fails visibly): per-read, per-source and per-device byte budgets,
  the sar bound, the Spike Detective per-run directory count.
- Not determined: everything that needs a size, an age or a name that the projection hides (§2).

## 8. Still unknown

| Item | Why | What closes it |
|---|---|---|
| Daily growth in bytes and lines of `messages`, `fwd.elg`, `cpd.elg`, `cpwd.elg`, `fwk.elg`, `mq_mng.elg`, `spike_detective.log` | sizes of 100,000 B or more are masked; no mtime | numeric measurement summary (R1); for the files currently below 100,000 B the second M1/M5 read already suffices |
| Rotation mode per family (rename or copytruncate), rotation period, `.elg` size threshold, retention horizon, `FAST_ROTATING` | same; one read only; inode equality needs the second read | the second read gives inode equality (rename versus same inode); period and threshold need numeric sizes (R1) |
| Siblings beyond `.1` (`messages.2`, `.gz`, dated names) and the `.elg` sibling names | the first stat covered `messages.1` only; names are masked | the first full-family C1 run, or R1 |
| sar sizes, sample interval, section content | sizes masked; 20 lines hold one sample | R1 (sizes), a `sar` header interval check |
| Spike data directory names, layout and sizes | gate 3 lists the parent only; names masked | one C5 listing on one device (R1) |
| Crash and dump process-name classes | names masked | R1 (name-class summary) |
| HCP file names and rotation (is `hcp_report_<host>_<dd_mm_yy_HH_MM>.tar.gz` the real form) | names masked | R1 |
| `spike_detective.log`/`.csv` time-token form and units | one masked token | R1 |
| PAN: time for a 2,000 or 5,000 entry page; `seqno` contiguity; `dir=forward`; `query` syntax; whether `-detail` fields exist | not measured | a larger-page rollout step; `seqno` min/max/count statistics in the PAN summary; official PAN-OS XML API citation |
| Time zone of device clocks | no zone is printed anywhere | device-clock offset in the C1 `date +%s` against the neXus clock (telemetry only) |

## 9. Recommendations

- **R1. Numeric Check Point measurement summary, before the second M1 read.** PAN has an in-product summarizer that
  turns the raw response into counts, sizes and enum classes and keeps nothing raw (`PanLogMeasurementSummary`). The
  Check Point measures have no equivalent, so every size, age and name was lost to the projection. A Check Point
  summary of the same kind would parse the stat and `ls` outputs in memory and persist only numbers (size, age
  against the device `date`, inode equality with the previous read, entry counts) and name classes (suffix form
  such as `.N`, `.gz`, dated; two-digit day names; regex class of the Spike Detective directory and crash file
  names), never a name. It extends the parse of already approved commands (same command, session, timeout and
  frequency), so it is not a command addition, but it needs code and a deploy before 2026-10-18 06:05. Note the
  consequence: the first M1 output is not recoverable as numbers, so a growth figure needs two summarized reads
  (and the M5 rule "at most twice per device" then needs the PO's approval for the extra reads).
- **R2. Output parsing by shape.** C1/C3/C4/C6/C7 parsers must skip leading non-data lines (§3.2).
- **R3. First-run baselines.** Crash/dump directories (122 files pre-existing), Spike Detective directories (up to 3,631
  pre-existing) and HCP archives must be recorded as baseline, not fetched or alerted.
- **R4. PAN summary statistics.** Add `seqno` min, max, distinct count and monotonic flag per page to the PAN
  summary, so contiguity can be judged without storing entries.

## 10. Follow-up record (appended 2026-10-19)

- The one C5 layout listing that §8 lists as "what closes it" for the Spike data directory names, layout and sizes is
  run by the `cp_spike_layout_review` job (PO approval 2026-10-19); its procedure and result are recorded in
  [SPIKE_DETECTIVE_LAYOUT_REVIEW_2026_10_19.md](SPIKE_DETECTIVE_LAYOUT_REVIEW_2026_10_19.md).
