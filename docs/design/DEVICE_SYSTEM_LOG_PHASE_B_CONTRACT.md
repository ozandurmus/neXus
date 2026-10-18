# Device system-log programme — phase B contract (incremental collection and storage)

**Status: APPROVED — PO 2026-10-17; parameters marked PENDING-M1/M5/M3M4 are set from the measurement findings
record before implementation freezes them.** The PO approved all 13 commands in §10 exactly as written (C2 as the
SFTP ranged read) and settled every question in §13; the record is
[PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md](PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md). The
findings record
[DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md](DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md) is now
committed. §11 gives each parameter one status: **RESOLVED** (the findings determine the value, which is now in the
text), **PROVISIONAL** (a number is proposed from the findings with its headroom, and a wrong guess fails visibly as
`BACKLOG` or `TOO_LARGE`, never silently), **PENDING** (no number: it needs the second M1/M5 read, a numeric
Check Point measurement summary, or the first rollout) or **UNKNOWN** (vendor semantics: the PAN `seqno` contiguity,
`dir=forward` and `query` syntax stay **UNKNOWN** until an official documentation citation is recorded). The
Check Point sizes of 100,000 B or more, all mtimes and all file names were masked in the measurement text, so growth
rates, rotation timing and name patterns are not in the record (findings §2).

Authority and inputs:

- [PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md](PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md)
  §3 (phase B approved in direction; raw-evidence law exception for the system-log programme).
- [DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md](DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md): the
  measurement record (M1 first read, M2, M3/M4, M5) that sets the §11 parameters.
- [DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md](DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md) (phase A): sources §2, read
  shapes L1–L5 §3, measurements M1–M5 §4/§4.1, storage/privacy §5, PO answers §7 (30-day raw retention, PAN config
  logs included, every enrolled firewall, 6-hour interval).
- PO decisions in chat, 2026-10-15/16 (§1 below) and 2026-10-17 (§13; the record named in the status line).
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
2. Büyüyen her dosyanın yalnızca yeni baytları okunur. Okuma bir SFTP aralıklı okumasıdır: dosya, zaten açık olan
   SSH oturumunun SFTP alt sistemi üzerinden açılır, kayıtlı bayt ofsetinden tavana kadar okunur ve kapatılır.
   Cihazda okuma için hiçbir komut çalışmaz (`tail`/`head` yok, kabuk süreci yok). Satır sınırına kırpma, parmak izi
   kontrolü ve rotasyon mantığı neXus tarafında yapılır. Tek okuma en fazla 8 MiB'tır (geçici; bkz. §3.4).
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
- Okuma için cihazda kabuk komutu çalışmaz; `find` kullanılmaz.
- Polling yapılmaz. Tek istisna, PAN'ın kendi log işinin bitişini sınırlı sayıda kontrol etmektir.
- Cihaz başına bir oturum açılır ve komutlar sırayla çalışır.
- Bir koşuda cihaz başına en fazla 64 MiB okunur.
- Sıkıştırma neXus tarafında yapılır.

**Ne kadar veri.** Ölçüm bulgu kaydı işlendi (§11). Palo Alto ve HCP sayıları kesinleşti: HCP raporu en çok
16 MiB, Palo Alto sayfası 2.000 kayıt. Check Point'te büyük dosyaların boyutu ve günlük büyümesi aiview maskesi
yüzünden ölçülemedi; bu sayılar bekliyor ve okuma tavanları "geçici" duruyor. Tavan yetmezse veri sessizce
kaybolmaz, ekranda `BACKLOG` olarak görünür. Önerilen tavanlar şunlar:

- Ham loglar 30 gün saklanır.
- Cihaz başına en fazla 2 GiB tutulur (sıkıştırılmış).
- HCP raporları 7 gün saklanır.
- Dosyalar neXus'ta şifreli ve sıkıştırılmış durur.
- aiview yalnızca maskelenmiş metni görür.
- Ürün dışına çıkış yalnızca Data Sanitizer'dan geçerek olur.

**Management server (MDS).** Şimdilik kapalı. Tasarım ileride açılmasına izin veriyor, ama varsayılan kapalı ve bu
onayın parçası değil.

**Onay durumu (PO, 2026-10-17).**

1. §10'daki 13 yeni komutun tamamı onaylandı: Check Point için 8, Palo Alto için 5. Check Point'te C2, SFTP
   aralıklı okuma olarak onaylandı; bu, C4 sözleşmesinin SFTP kullanımına küçük bir ek gerektiriyor (§4.3). Faz A'da
   onaylanmış 18 ölçüm komutu (V149, V151, V157) toplamada kullanılmıyor. Onlar yalnızca ölçüm içindi, ham veri
   saklamıyorlardı.
2. Okuma yöntemi sizin önerinizle belirlendi: kesme işi neXus tarafında yapılır, cihazda okuma için komut
   çalışmaz. Dosyayı her seferinde baştan kopyalamak aktarımı katlardı. Bu yüzden yalnızca kayıtlı ofsetten sonrası
   okunuyor.
3. §13'teki diğer sorular belgenin kendi önerileriyle kapandı: ilk koşuda her canlı dosyanın son 8 MiB'ı (rotasyon
   geçmişi yok); tavan dolunca en eski parçalar erken silinir (kayıtlı ve görünür); `device_crash_file` bildirimi
   eklenir, rotasyon kayıpları yalnızca ekranda ve günlük özette görünür; HCP dışa aktarımı Sanitizer arşivleri
   destekleyene kadar kapalı; PAN config-log ayrıntıları gizli bilgi maskelenerek saklanır; VSX için yalnızca VS0;
   `.gz` döndürülmüş dosyanın okunmamış kalanı raporlanan boşluk olur; operatör maskesiz satırı görebilir (her
   görüntüleme denetim kaydına yazılır), aiview her zaman maskelenmiş görür.
4. Bulgu kaydı geldi (§11). Kesinleşenler: PAN sayfa boyutu ve sayfa sayısı, HCP üst sınırı, satır zaman biçimleri.
   Hâlâ sayı beklenenler: okuma tavanı ve bütçeler (geçici), 2 GiB cihaz tavanı (Check Point tarafı), rotasyon biçimi
   ve büyüme hızı. Bunlar için Check Point ölçümünün sunucu tarafında sayısal özete çevrilmesi öneriliyor
   (bulgu kaydı R1); bu ek ölçüm okuması sizin onayınızı gerektirir.

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
| `cp_messages` | `/var/log/messages` | `/var/log/messages*` | `messages.1` exists as a plain sibling on 53/53 gateways. Rotation mode (rename vs copytruncate) `PENDING` (needs the second M1 read, §11) |
| `cp_fwd_elg` | `$FWDIR/log/fwd.elg` | `$FWDIR/log/fwd.elg*` | size-rotated; sibling naming `PENDING` (first C1 run). Empty (size 0) on 8 of 53 gateways; most lines carry no timestamp (§4.7) |
| `cp_cpd_elg` | `$CPDIR/log/cpd.elg` | `$CPDIR/log/cpd.elg*` | multi-line records (at least 17 % of lines are continuations) |
| `cp_cpwd_elg` | `$CPDIR/log/cpwd.elg` | `$CPDIR/log/cpwd.elg*` | process restarts; high signal. Empty (size 0) on 13 of 53 gateways |
| `cp_fwk_elg` | `$FWDIR/log/fwk.elg` | `$FWDIR/log/fwk.elg*` | present on 53/53 measured gateways, not VSX/USFW only; a missing file = `ABSENT` (§9). 15 % of the last-20 lines contain secret-vocabulary words (§12) |
| `cp_mq_mng_elg` | `$FWDIR/log/mq_mng.elg` | `$FWDIR/log/mq_mng.elg*` | present on 53/53 measured gateways; line form not measured (no tail gate) |
| `cp_spike_log` | `/var/log/spike_detective/spike_detective.log` | `…/spike_detective.log*` | |
| `cp_spike_csv` | `/var/log/spike_detective/spike_detective.csv` | `…/spike_detective.csv*` | 8 comma-separated columns; measured size 1,661 to 23,678 B on 53/53 (46 within 17.6 to 23.7 kB: size-bounded) |

Whole-file sources are fetched once, when they are complete:

| Source key | What | When | Retention |
|---|---|---|---|
| `cp_sar_daily` | yesterday's `/var/log/sa/sar<DD>` (text, written by the device at 23:53). `/var/log/sa/` holds 59 to 60 files (30 days x binary and text); every file is 100,000 B or more | 06:00 window, once a day | 30 days |
| `cp_hcp_report` | newest `*.tar.gz` in `/var/log/hcp/last/` (found on 52/53 gateways; usual form `hcp_report_<host>_<dd_mm_yy_HH_MM>.tar.gz` is not verified, §8.1). The device's run time is about 03:00 on 36/52 gateways and elsewhere on the others | 06:00 window, once a day | 7 days (PO) |
| `cp_spike_data` | files of each new, settled Spike Detective data directory: `perf_cpu_N.log`, `profiling_summary.elg` (the directory contents are **not measured**: gate 3 lists the parent only; off by default until one directory listing has been reviewed, §8.3) | any window, once per directory | 30 days |

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

### 3.4 Device-load bounds (status per row; basis in the findings record)

| Bound | Default | Status and basis |
|---|---|---|
| Sessions per device per run | 1 (SSH; every SFTP read and fetch uses a subsystem channel on the same session) | design (D6) |
| Concurrency on one device | 1 command at a time | design (D6) |
| Bytes per read (`<cap>`; the SFTP read stops here) | 8 MiB | **PROVISIONAL.** No live file size of 100,000 B or more was measurable (findings §2), so the cap can be neither confirmed nor refuted. Safe because an undersized cap only produces `BACKLOG`. Fits the first-run baseline: 8 live incremental sources x 8 MiB = 64 MiB |
| Bytes per source per run | 32 MiB | **PROVISIONAL**, same basis (4 reads of the cap) |
| Bytes per device per run (all sources, excluding HCP) | 64 MiB | **PROVISIONAL**, same basis. The first-run baseline of 8 live files can reach this on its own, so a first run in the 06:00 window may leave `cp_sar_daily` as `BACKLOG` (reported; the next window continues) |
| HCP archive max | **16 MiB** | **RESOLVED.** Every device's whole `/var/log/hcp/last/` directory is at most 4,840 KiB (4.73 MiB; n=52, median 3.23 MiB), so any archive is at most 4.73 MiB. 16 MiB is 3.4 times that bound. Larger = `TOO_LARGE` |
| sar file max (`sar<DD>` text) | **32 MiB** | **PROVISIONAL.** Every sar file is 100,000 B or more and the 6 to 16 CPU devices show 61 to 96 MiB per 60 files; linear extrapolation to the 15 devices with 128 CPUs gives about 8 MiB per file on average and at most about 16.3 MiB for a text file. 32 MiB is 2 times that worst case (low confidence, 7 devices). Larger = `TOO_LARGE`, reported |
| Spike data per directory | 16 MiB | **PENDING.** The data directories were not listed (findings §4.2); no size is known |
| Spike data directories per run | 16 | **PROVISIONAL.** Devices hold 0 to 3,631 data directories (median 14, p90 93). The first listing is a baseline, never fetched (§8.3). New directories beyond 16 per run are `BACKLOG`. The rate of new directories is unknown (the second M5 read gives it) |
| Wall-clock budget per device job | 15 min | not measured; PAN needs about 2.2 s per request (findings §5) |
| Device-side work | `stat` and `ls` over exec; SFTP ranged reads and whole-file SFTP fetches. No shell process for reading (no `tail`/`head`), no compression, no `find`, no archive creation on the device | D6, PO 2026-10-17 |

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
- **Leading lines.** 25 of 53 measured gateways print one extra line before the output of every command (the
  `bash -lc` login shell; content not observed). The parser locates the device-clock line as the first line that
  matches `^[0-9]{9,11}$` and each `stat` line by the shape `^/\S+ [0-9]+ [0-9]+ [0-9]+$`, and ignores every other
  line. It never assumes line 1. The same rule applies to the `ls` outputs of C3, C4, C5, C6 and C7 (findings §3.2).
  A C1 output with no device-clock line is `UNKNOWN`.

### 4.3 The read (SFTP ranged read; PO 2026-10-17)

The PO chose to do the cutting on the neXus side and to run nothing for reading on the device. The incremental read
is therefore an **SFTP ranged read**, not a shell command:

1. Open `<path>` read-only through the SFTP subsystem on the run's one SSH session (no second session).
2. Read from the 0-based byte position `<pos> = offset − K` for `<cap> = K + budget` bytes at most, or until end of
   file. `K = min(64, offset)`, so the first `K` bytes received are the anchor region.
3. Close the file.

- **Same path rule as C1.** `<path>` must be one of the absolute paths that this run's stat output (C1) printed. Its
  basename must match the source's read-eligible family pattern `^<base>(\.[0-9]{1,2})?$` (RESOLVED as a
  proposal from the findings: `messages.1` exists as a plain name on 53/53 gateways; the sibling names of the `.elg`
  families are not yet observed, so every other suffix, including `.gz`, `.old` and dated names, is listed in
  `family_snapshot` and never read) and its directory must equal the directory of the family's base file. `<pos>` and `<cap>` are computed
  by neXus from the cursor and the budget. They are never operator input and never come from a device response.
- **What the device does.** The SFTP server answers read requests for the requested region of one file. It starts
  no shell, runs no `tail`, `head` or pipe, and compresses nothing. neXus sends no write, no `setstat`, no rename and
  no remove. A request-ahead inside the SFTP window can make the device serve a little past `<cap>`. Those bytes are
  discarded: never stored, never counted against the cursor or the budgets. The read is bounded, not open-ended.
- **Why the transport issue goes away.** Earlier drafts piped `tail | head` through the exec channel, whose result
  combines stdout and stderr as text, so offsets could be corrupted by invalid UTF-8 or interleaved stderr. An SFTP
  data stream carries file bytes only. Errors arrive as SFTP status codes and are classified separately, and nothing
  passes through the text `ExecResult`. The implementation streams the bytes into the artefact sink and counts
  exactly what it received. No new exec mode is needed.
- **Identity.** SFTP v3 attributes carry no inode, so inodes come only from C1. A rename between C1 and the open can
  put a different file at `<path>`. The anchor check below catches it. A path that no longer exists, or a read that
  returns fewer than `K` bytes when `K > 0`, is `UNKNOWN` for this source in this run. The cursor does not move, so
  nothing is lost.
- **Anchor check (fingerprint).** The HMAC of the first `K` received bytes must equal `anchor_hmac`. On a mismatch the
  content at `offset` is not what was read before (inode reuse, file replaced, truncate-and-regrow). The source is
  handled as `ANCHOR_MISMATCH` (§4.5). This runs in neXus.
- **Line trim.** Done in neXus. The stored chunk ends at the last `\n` received, and the cursor moves there. A partial
  final line is read again next run. If a full-cap read contains no `\n`, the cap is stored as is and the chunk is
  flagged `LINE_SPLIT`.
- **Rotation logic** (§4.4) runs in neXus over the C1 stat. Nothing about it needs the device.

**C4 amendment this decision requires (named part of the PO decision of 2026-10-17).**
`UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §2.3 describes `sftp_get` as fetching one remote path into
the staging area. The ranged read uses the SFTP subsystem for more than that whole-file fetch. The amendment, as the
PO approved it:

- The existing `sftp_get` step kind gains an optional read-only byte range `{start_offset, max_bytes}`. Both values
  are non-negative integers computed by neXus. `max_bytes` is bounded by the step's size bound.
- The step-kind set stays closed. No new kind is added, and `sftp_put` stays refused.
- The gate-reference rule is unchanged: the path was produced by a gated prior step (C1), so the fetch is
  `NOT_APPLICABLE` under the same sentence of §2.3. The range itself sends no device command.
- The PO approval of C2 in §10.1 is the approval of this use. It covers this one purpose (system-log incremental
  reads) and extends to no other source or capability.

This document records the amendment. The text of the FROZEN C4 contract is edited by the implementation PR, under C4's
own amendment procedure, and that PR cites the PO record named in the status line.

### 4.4 Planning per source (decision table)

Let `C` be the stored cursor and `F` this run's family stat (name → inode, size).

| Case | Detection | Reads, in order | Record |
|---|---|---|---|
| First run (no cursor) | no row | current file from `max(0, size − backfill)` (backfill: §13 Q3, 8 MiB) | `INITIAL_BASELINE` gap with the exact skipped byte count (`size − backfill`, when positive) |
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

**Truncation detected at read time.** If a file is found smaller than the start of a C2 read between the stat and the read
(`RangedFetchStatus.SIZE_BELOW_OFFSET`), the file changed under the plan. SFTP v3 carries no inode (§4.3 Identity), so the
run cannot tell an in-place truncation from a rename followed by a new file, and it does not guess. With a cursor the
source is **deferred for this run**: the cursor, the anchor and the family snapshot stay as they were, no gap is recorded,
the bytes of the failed read are discarded, and the source ends `BACKLOG` with the reason `FILE_CHANGED_DURING_READ` and
`pending_bytes_unknown = true` in the run detail (the unread amount is not known; it is not 0). `BACKLOG` is not a
failure (§9), so this alone leaves the job `COMPLETED`. The next run reads a fresh C1 stat and its planner decides, by the
table above: `TRUNCATED` (Copytruncate row) or a followed rotation (Rename rotation row), reading from byte 0 where the
table says so. Chunks stored earlier in the same run stay stored and their cursor advance stands. On a **first run** (no
cursor) nothing was read before, so the whole smaller file is read in the same run inside the budgets and no gap is
recorded. The same C2 read is used and no command, timeout, retry or frequency changes.

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
`syslog-gaps`, and in the daily summary. Rotation-loss gaps do not send a notification (§13 Q5); they show on screen and in the daily summary.

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
as `ROTATED_AWAY` with its observed compressed size and `extent_unknown = true`, and §13 Q9 records the PO's choice (accept the reported gap).

### 4.7 Line timestamp forms (measured; findings §3.3 and §4.1)

`first_line_time` and `last_line_time` of a chunk (§5.2) are the time of the first and the last *timestamped* line of
the chunk, never of a continuation line. No source prints a time zone, so every parsed time is stored as the device's
local wall-clock text with the basis `DEVICE_LOCAL` and is never converted (the same rule as the PAN cursor).

| Source | Form (shape; token classes inferred from the masked output) | Year | Rule |
|---|---|---|---|
| `cp_messages` | leading `<Mon> <DD> <HH:MM:SS> <YYYY> <host> <prog>[<pid>]:`; all 1,060 sampled lines match; 0 continuation lines | printed | parse; an unrecognised month token (spelling not observable) = time unknown |
| `cp_fwd_elg` | elg header `[<proc> <pid> <tid>]@<host>[<DD> <Mon> <HH:MM:SS>] <text>` on only 3.2 % of sampled lines; 96.8 % carry no timestamp | not printed | a chunk with no timestamped line has both times null |
| `cp_cpd_elg`, `cp_cpwd_elg` | same elg header; 57.7 % (cpd) and 95.9 % (cpwd) of sampled lines carry a timestamp; cpd has multi-line records | not printed | year taken from `device_epoch_at_read`: the latest date not after the device clock with that day and month |
| `cp_fwk_elg` | leading `[<DD> <Mon> <HH:MM:SS>][<instance>];[<cpu>];` on 625 of 901 visible sampled lines; 249 have no timestamp | not printed | as the elg rule |
| `cp_mq_mng_elg` | not measured (no tail gate) | unknown | both times null until measured |
| `cp_spike_log`, `cp_spike_csv` | first field is one whitespace-free token whose form is not observable; `.log` has 8 comma-separated segments after it, `.csv` 8 columns | unknown | both times null until measured (`PENDING`) |
| `cp_sar_daily` | `HH:MM:SS` in 24-hour form at the start of each row (no AM/PM token); the date is in the header line | in the header | the file covers one day; times are not parsed per row in phase B |
| `pan_system`, `pan_config` | `receive_time` `yyyy/MM/dd HH:mm:ss` (all 78 summaries, 0 unparsed) | printed | kept verbatim as the cursor |

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

### 5.3 Retention and caps (defaults; PO-settable in Settings; `PENDING` numbers need the data named in §11)

| Class | Retention | Per-device cap |
|---|---|---|
| Incremental raw chunks (CP and PAN) | 30 days (PO, phase A §7.1) | 2 GiB stored, compressed. **PAN: not binding** (largest firewall about 0.79 GB raw per 30 days, before compression). **CP: `PENDING`** (growth rates not measurable, findings §2) |
| `cp_sar_daily` | 30 days | within the 2 GiB |
| `cp_spike_data` | 30 days | 512 MiB (`PENDING`: data-directory layout and sizes not measured) |
| `cp_hcp_report` | 7 days (PO) | 7 archives, at most 7 x 4.73 MiB = 33 MiB per device (**RESOLVED** bound from the findings, §3.4) |
| Gap, run, crash entry and crash alert rows | 180 days (same as the parsed index in phase A §7.1) | — |

When a device reaches its cap before the retention ends, its oldest chunks are pruned early. The pruning is
ledgered and shown as `RETENTION_SHORTENED_BY_CAP`; new data is never refused for the cap. The alternative is
§13 Q4 (not chosen).

**Fleet estimate (formula; PAN and HCP terms measured, Check Point terms `PENDING`).**

`stored ≈ Σ_devices Σ_sources growth_per_day × 30 / r + Σ HCP sizes × 7`, where `r` is the gzip ratio. Plain-text
syslog typically compresses about 8–10×; this is to be confirmed on the first week of chunks.

Measured terms (findings §4.3 and §5):

- **PAN.** About 106,600 `pan_system` entries and 125 MB per day over the 39 firewalls, plus about 60 KB per day of
  `pan_config`: about 3.76 GB raw per 30 days for the fleet before compression (extrapolated from the newest 100
  entries per firewall, XML result bytes as the proxy for stored bytes; the estimate is burst-biased).
- **HCP.** At most 52 x 7 x 4.73 MiB = 1.68 GiB for the fleet (upper bound, from the directory totals; the archives
  are already compressed).
- **Check Point incremental sources and sar.** `PENDING`: the sizes are masked in the measurement (findings §2).

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
- **Bounds (RESOLVED from the M3/M4 summaries; findings §5).** At most 4 pages per log type per run, page size
  `<n> = 2000`. Basis: the extrapolated 6-hour volume of `pan_system` is 192 / 443 / 1,067 / 5,496 entries
  (min / median / p90 / max over 39 firewalls); 4 x 2000 = 8,000 entries is 1.46 times the maximum and 7.5 times the p90,
  and only 2 firewalls exceed one page. A 2000-entry page is at most 2000 x 1,227 B = 2.5 MB (the measured maximum
  per entry), 3.4 times under the 8 MiB result cap, which is kept. The PAN maximum of 5000 (not verified against
  vendor documentation) would be 6.1 MB, only 1.3 times under the cap. If the bound is reached, the run records
  `BACKLOG`. Under the default settings that never means skipping.
- **Seed sizes (RESOLVED).** `pan_system`: `<n> = 2000` newest entries (covers 2.2 h at the busiest firewall, 27 h at
  the median, 62 h at the quietest). `pan_config`: `<n> = 100` (100 entries cover 41 to 107 days on every firewall,
  more than the 30-day retention).
- **Page time (measured, pilot 2026-10-18).** Every request of the M3/M4 measurement reached `FIN` at the first check
  after 2.1 to 2.2 s with 100 entries. The first live run measured a 2000-entry `pan_system` seed page at
  `enqueue_to_fin_ms` 2334 (result 2.33 MB) and a 100-entry `pan_config` seed page at 2175 ms (§11).
- **Stall.** If a page holds more than `<n>` entries in the same second, paging cannot advance. The run records a
  `PAGE_STALL` gap with `extent_unknown = true` and moves the cursor one second forward.
- **Loss detection.** Whether `seqno` is contiguous per log type is **UNKNOWN**. It needs vendor documentation or M3
  evidence before freeze. Until it is confirmed, a seqno jump claims nothing: no gap row and no outcome change. The
  seqno is probably shared across log types, so jumps are normal (the first live run, §11, showed jumps on the seed of
  both sources). The executor keeps only a neutral `seqno_jumps` count in the run detail. The PAN log quota overwrite
  therefore cannot be proven from the seqno; the `INITIAL_BASELINE` gap of a full seed, `PAGE_STALL` and `BACKLOG`
  remain the only PAN gap kinds.
- **Storage.** One chunk per log type per run. The entries are kept as JSON Lines, one object per `<entry>`, with
  every child element name → text as received. This is the raw entry; the transformation is lossless for fields.
- **Config-log detail.** The config-log result fields that can carry configured values were observed as
  `before-change-preview`, `after-change-preview`, `path` and `full-path` (on 5 to 6 of 39 firewalls within the newest
  100 entries); the names `before-change-detail`/`after-change-detail` were **not observed**. All six names are
  redacted on write by the secret rules (§12). The PO kept them with redaction (§13 Q7), not dropped.
- **Polling.** The `action=get` checks are the PAN API's own asynchronous job pattern. They are bounded: 2 s
  interval, at most 30 checks per page, as in V151 (kept: 60 s against 2.2 s observed at 100 entries). This is the
  only repeated request in the design.
- `dir=forward` and the `query` filter syntax need an official PAN-OS XML API documentation citation recorded before
  freeze (vendor semantics law).

## 7. New crash/dump files: listing and alert

- The listing is `ls -lgo --time-style=+%s` of the two directories, one level, every run. The product never reads
  content, never descends into a crash directory, and never runs `file`, `tar` or `strings` on a dump.
- **New file.** A name that was not in the previous listing, or the same name with a changed size or mtime. It is
  matched by `name_hmac` and mtime.
- **First listing.** The first listing for a device is a baseline. Pre-existing files are recorded with
  `first_seen_job` set and raise no alert. They are shown as "present before monitoring".
- **Process name.** It is parsed from the file name. The name pattern is **`PENDING`**: the M5 gate 5 names were
  masked, so the pattern is not in the record (findings §4.4). Until it is measured the parse has no pattern and
  every entry's process is `UNKNOWN`, which is the specified fallback and is still alerted. Entries under
  `/var/log/crash/` are `kernel`.
- **Measured baseline (findings §4.4).** `/var/log/crash/` is empty on all 53 gateways. `/var/log/dump/usermode/`
  holds 122 files on 39 gateways (14 empty; per device median 2, p90 4, max 36); 118 of them are 100,000 B or more. All
  of these are `present before monitoring` on the first listing and raise no alert.
- **Alert.** One alert per (device, run, process): process, count of new files, newest mtime (device time, shown in
  Europe/Istanbul). It goes through a new notification type `device_crash_file` ("Device crash and dump files").
  This is an amendment to the FROZEN `NOTIFICATION_ROUTING_CONTRACT.md` §2 and to its V100 type check. The PO
  approved it (§13 Q5). It follows that contract's digest, watermark and leader rules. Device names in mail follow
  the routing contract; aiview sees pseudonyms.
- **Removed file.** A file that disappears gets `removed_at` set. That is informational and raises no alert.

## 8. Whole-file fetches

All three use the existing `sftp_get` streaming fetch on the run's SSH session. The remote path is always a name
printed by a gated listing in the same run, so C4 §2.3 gives the fetch `NOT_APPLICABLE` gate resolution. This
contract still lists it in §10 so the PO approves it explicitly. The size bound is `maxBytes` from §3.4; a larger
file is `TOO_LARGE` and is not fetched.

### 8.1 HCP report (06:00 window)

- The listing is `cp_syslog_collect_ls_hcp`. The candidate is the newest file (by the device's mtime from the
  listing) whose name matches the safe form `^[A-Za-z0-9._-]+\.tar\.gz$`. The measurement used exactly this
  selection (`*.tar.gz`, newest first) and it found an archive on 52 of 53 gateways; the file names were masked,
  so the stricter form `^hcp_report_[A-Za-z0-9._-]+_[0-9]{2}_[0-9]{2}_[0-9]{2}_[0-9]{2}_[0-9]{2}\.tar\.gz$` is **not
  verified**. A candidate that matches the safe form but not the strict form is fetched and flagged
  `NAME_SHAPE_UNEXPECTED` (a naming mismatch must never turn into a silent `ABSENT`). The name is stored as an HMAC
  plus the parsed time when the strict form matches (§5.2).
- Shape of the archive (findings §4.3): 2 to 5 members (median 3), at most 4.73 MiB, an integrity check on neXus (below)
  needs no size beyond the 16 MiB bound of §3.4. The device's own run time is about 03:00 on 36 of 52 gateways, but
  16 gateways show other times (00:16 to 05:54, and 06:08 to 06:12 on 4): a 06:00-window fetch can meet an archive
  that is not yet 10 minutes old, which the settling rule below defers to the next day.
- It is fetched only when the file is not already stored and has settled: mtime at least 10 min older than the
  device clock from the stat.
- Integrity is checked on neXus: the gzip and tar structure must be readable. The device does no `tar -t`. A broken
  archive is stored as `CORRUPT` and not retried.
- It is kept 7 days. An HCP missing on a given day is `ABSENT` for that day. It is not a failure, since the device
  may not run HCP.

### 8.2 sar (06:00 window)

- The listing is `cp_syslog_collect_ls_sa`. The candidate is the text file `sar<DD>` (proposed pattern
  `^sar(0[1-9]|[12][0-9]|3[01])$`; the binary `^sa(0[1-9]|[12][0-9]|3[01])$` is never fetched) whose mtime falls in
  the 30 h before the device clock and whose `DD` is the day before the device's date. The directory holds 59 or 60
  files on every gateway (30 days of both kinds), so the listing is about 61 lines and needs no more than the 64 KiB
  bound. The file names were masked in the measurement, so the two patterns are taken from the phase A contract and
  are consistent with the 2 x 30 count, not verified by name.
- Size: every file is 100,000 B or more; the bound is 32 MiB (`PROVISIONAL`, §3.4); a larger file is `TOO_LARGE` and
  reported. The first row of a sar file has the shape `Linux <kernel> (<host>)<TAB><date><TAB><arch> (<N> CPU)` and the
  header has 12 columns in 24-hour form (findings §4.1).
- It is fetched once; the `(name_hmac, mtime)` uniqueness prevents a second fetch.
- The binary `sa<DD>` is not fetched.

### 8.3 Spike Detective data

- The listing is `cp_syslog_collect_ls_spike`. A data directory is an entry with link count 2 (all 6,334
  measured data directories have link count 2; the plain files `spike_detective.log`/`.csv` and their rotated copies have
  link count 1). The directory **name pattern is `PENDING`** (masked in the measurement), so the collector takes the
  entry class (directory) and not a name regex, and stores the name only as an HMAC.
- **Population (findings §4.2).** 0 to 3,631 data directories per gateway (median 14, p90 93); 6 gateways have 100 or
  more entries, one has 3,631 directories.
- **First listing is a baseline.** All directories present in a device's first listing are recorded as
  `present before monitoring` and are **never fetched**. Only directories that appear later are candidates.
- **Per run.** At most 16 new directories per run (§3.4); the rest is `BACKLOG`.
- **Listing bound.** The C4 output bound is 256 KiB. The largest measured listing (3,638 lines) is about 189 KB with
  19-character names and exceeds 256 KiB once names exceed about 39 characters, which cannot be checked (names masked).
  A listing that reaches the bound is `UNKNOWN` for the source in that run (never processed partially). Raising the
  bound is a change to an approved row and needs the PO (§13, open items).
- **Layout is not measured.** Gate 3 lists only the parent directory. The contents of a data directory
  (`perf_cpu_<N>.log`, `profiling_summary.elg`) are unverified, so fetching spike data is **off by default**
  (`system_log.spike_data_enabled`, default `false`, security_admin). It is switched on after one `cp_syslog_collect_ls_spike_dir`
  listing of one gateway's directory has been reviewed in the findings record. Until then the source still runs the
  baseline listing.
- A data directory is new when its name is not yet stored, and settled when its mtime is at least 10 min older than
  the device clock.
- For each new, settled directory the run does one `cp_syslog_collect_ls_spike_dir` and then one `sftp_get` per file
  named `perf_cpu_<N>.log` or `profiling_summary.elg`, up to 16 MiB per directory. Other members are listed in the
  manifest but not fetched.
- M5 gate 3 shows the data entries are subdirectories (6,334 across 53 gateways), so the `_dir` gate stays.
  `<dir>` must also match the safe form `^[A-Za-z0-9._-]{1,128}$` because the real name pattern is not measured; a
  name outside it is skipped and counted as `NAME_REJECTED`. This tightens the C5 path rule from "the measured
  pattern" to a safe character form and is listed under the open items of §13 for the PO's acknowledgement. The one
  review listing (first rollout, one gateway, the newest settled directory) is run under the aiview mentality and its
  member-name classes and sizes are recorded in the findings record before `spike_data_enabled` is turned on.
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

All rows: action class `read`. **All 13 are APPROVED (PO 2026-10-17)** exactly as written, with C2 as the SFTP ranged
read (§4.3). They are inserted with `sign_off_state = SIGNED_OFF` by the implementation migration together with the
fixture rows. The approval covers the command shapes and terms below. The bounds that were marked `PENDING-M1`/`PENDING-M5` (byte caps and
similar) are set from the findings record, with the status shown in §3.4 and §11. A change to a command's shape, path rule, frequency or transport needs a
new PO approval. Field names follow `gate_registry` and the
protocol's ten gate fields.

### 10.1 New — Check Point Gaia gateway (`check_point`, `cp_gaia_gateway`, shell `expert`) — APPROVED (PO 2026-10-17)

| # | gate_id | Transport | canonical_command_key | timeout_s | retry | max_frequency | session_reuse_rule | Max bytes | unsupported_behavior_ref | secret_output_risk | safe_telemetry_fields | Relation to approved |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1 | `cp_syslog_collect_stat` | `SSH_EXEC` | `bash -lc 'date +%s; stat -c "%n %i %s %Y" /var/log/messages* $FWDIR/log/fwd.elg* $CPDIR/log/cpd.elg* $CPDIR/log/cpwd.elg* $FWDIR/log/fwk.elg* $FWDIR/log/mq_mng.elg* /var/log/spike_detective/spike_detective.log* /var/log/spike_detective/spike_detective.csv*'` | 30 | none | once per device per job window (≤ 4/day) | the run's one existing trusted SSH session; non-interactive bash login shell; sequential | 64 KiB output | an unmatched glob or missing file is a `stat` error line = `ABSENT`, not a failure | file paths may contain host-specific install paths; no content; paths masked in aiview projections | `["deviceEpoch", "name", "inode", "sizeBytes", "mtime"]` | NEW; combines V149 `cp_syslog_measure_stat` (`%i %s %Y`) and V157 `cp_syslog_measure_stat_ext` (`%n`) over the PO's source list, with family globs |
| C2 | `cp_syslog_collect_read` | SFTP ranged read (C4 `sftp_get` step with a byte range; subsystem on the same SSH session; gate `NOT_APPLICABLE` per C4 §2.3 as amended in §4.3) | no shell command. Read-only SFTP open of `<path>`, read `[<pos>, <pos> + <cap>)`, close. `<pos>`, `<cap>` non-negative integers computed by neXus, `<cap> ≤ 8388672`; `<path>` = an absolute path printed by C1 in the same run, basename in the source's family pattern, directory equal to the family base directory | 60 | none | per source per run, until the per-source (32 MiB) or per-device (64 MiB) budget; only inside the job window | same session (SFTP channel); sequential; never concurrent with another command or transfer | `<cap>` (8 MiB + 64 B anchor) | missing file at read time (rotation race) or a short anchor read = `UNKNOWN` for this source in this run, cursor unchanged, re-planned next run; SFTP unavailable = `UNKNOWN` | raw log lines: user names, addresses, host names, clish command audit lines that can carry `password-hash`/secret values; redacted on write (§12), stored encrypted, masked for aiview | `["startOffset", "bytesRead", "lines", "anchorMatch", "lineSplit"]` | NEW; replaces the phase A L2/L3 `tail`/`head` shape by PO decision of 2026-10-17: the cutting runs in neXus, nothing runs on the device for reading. Needs the C4 amendment in §4.3 |
| C3 | `cp_syslog_collect_ls_crash` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/'` | 30 | none | once per device per job window | same session | 256 KiB | a missing directory is a result (`ABSENT`) | file names may carry process names, addresses (kernel crash directory names) or host names; names stored as HMAC plus the parsed process; masked in aiview | `["fileCount", "sizeBytes", "mtime", "process"]` | NEW; string identical to the approved V157 `cp_syslog_measure_ls_crash` (measurement-only frequency) |
| C4 | `cp_syslog_collect_ls_spike` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'` | 30 | none | once per device per job window | same session | 256 KiB | missing directory = `ABSENT` | names only | `["fileCount", "sizeBytes", "mtime", "namePattern"]` | NEW; string identical to V157 `cp_syslog_measure_ls_spike` |
| C5 | `cp_syslog_collect_ls_spike_dir` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/<dir>/'`; `<dir>` = a name printed by C4 in the same run, matching the safe name form of §8.3 (the data-directory name pattern is not measured) | 30 | none | once per new, settled data directory | same session | 64 KiB | missing = `ABSENT` | names only | `["fileCount", "sizeBytes", "mtime"]` | NEW; **conditional**: dropped if M5 gate 3 shows no subdirectories. Gate 3 shows 6,334 subdirectories on 53 gateways, so the condition is not met and C5 stays. Used once on one device in the first rollout to review the layout (§8.3) |
| C6 | `cp_syslog_collect_ls_sa` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/sa/'` | 30 | none | once per device per day (06:00 window) | same session | 64 KiB | missing = `ABSENT` | names only | `["fileCount", "sizeBytes", "mtime", "namePattern"]` | NEW; string identical to V157 `cp_syslog_measure_ls_sa` |
| C7 | `cp_syslog_collect_ls_hcp` | `SSH_EXEC` | `bash -lc 'ls -lgo --time-style=+%s /var/log/hcp/last/'` | 30 | none | once per device per day (06:00 window) | same session | 64 KiB | missing = `ABSENT` | archive names carry the host name; stored as HMAC plus the parsed time; masked | `["fileCount", "sizeBytes", "mtime", "namePattern"]` | NEW; string identical to V157 `cp_syslog_measure_ls_hcp` |
| C8 | (`sftp_get`; gate `NOT_APPLICABLE` per C4 §2.3) | `sftp_get` subsystem on the same SSH session | fetch one absolute path printed by C4/C5 (spike data), C6 (`sar<DD>`) or C7 (HCP archive) in the same run; no other path | 120 (sar, spike), 300 (HCP) | none | HCP and sar: once per device per day; spike: once per new directory | same session (SFTP channel) | HCP 16 MiB (RESOLVED, §3.4), sar 32 MiB (PROVISIONAL, §3.4), spike 16 MiB per directory (`PENDING`, layout not measured) | SFTP unavailable or path gone = `UNKNOWN`/`ABSENT`; over bound = `TOO_LARGE`, not fetched | HCP archives contain device diagnostics that may include configuration fragments and names: stored encrypted as received, never shown raw to aiview (§12); sar and spike data are counters and process names | `["sizeBytes", "sha256", "kind"]` | NEW; the existing whole-file streaming fetch used by `cp_gateway_backup` |

### 10.2 New — Palo Alto (`palo_alto`, `pan_firewall`, shell `not_applicable`, transport `PAN_XML_API`) — APPROVED (PO 2026-10-17)

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
| New, **APPROVED (PO 2026-10-17)** | 8 (C1–C8: 6 `SSH_EXEC` gate rows, plus the two `sftp_get` steps C2 (ranged read) and C8 (whole file); C5 conditional) | 5 (P1–P5) | **13** |
| Already approved, measurement-only, not reused | 15 | 3 | 18 |

Per Check Point device, a typical run issues:

- C1;
- about 6–8 C2 SFTP ranged reads (one per growing source, more after a rotation);
- C3;
- C4.

The 06:00 run adds C6, C7 and two C8 fetches.

Per Palo Alto firewall, a typical run issues 2 enqueues (P3, P4) and their P5 checks.

Not requested: MDS-scoped rows (§2.3). The `tail | head` form of C2 from the earlier draft is withdrawn; no `tail` or `head` command is requested.

## 11. How the measurements feed the parameters

The findings record
[DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md](DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md) holds M1's
first read, M2, M3/M4 and M5. The Check Point outputs reached it only through the aiview projection, which hides every
integer of 6 or more digits (all sizes of 100,000 B or more, all mtimes), every file name and every `HH:MM:SS` time
(findings §2). The PAN figures are exact (computed in the product). The PO's approval of 2026-10-17 does not set
these numbers; each status below is the engineering result of the findings.

Status words: **RESOLVED** (the findings determine it; the value is in the text), **PROVISIONAL** (proposed number with
stated headroom; a wrong guess fails visibly), **PENDING** (no number; the data named is missing), **UNKNOWN**
(vendor semantics).

| Parameter (this document) | Source measurement | Status | Value or reason |
|---|---|---|---|
| Per-read cap 8 MiB, per-source 32 MiB, per-device 64 MiB (§3.4) | M1 growth per day; M5 gate 1 | **PROVISIONAL** | no size of 100,000 B or more is measurable; kept as safe bounds (only `BACKLOG` if too small); fits the first-run baseline (8 x 8 MiB). Closing needs numeric sizes (findings R1) |
| 2 GiB per-device cap (§5.3) | M1 and M5 growth; M3/M4 volume | PAN **RESOLVED** (not binding, 0.79 GB raw per 30 days at the largest firewall); CP **PENDING** | CP growth not measurable |
| Rotation mode per family (rename vs copytruncate) (§4.4) | M1 inode and size across two reads | **PENDING** | one read only. The second M1 read gives inode equality (rename if the inode changed; same inode with a changed size suggests copytruncate). 23 of 53 gateways show the same `messages` inode value, which does not decide it |
| Sibling naming, rotate count, read-eligible regex (§4.3, §2.1, C2) | M1 stat; C1's first run | `messages.1` plain on 53/53 **RESOLVED**; regex `^<base>(\.[0-9]{1,2})?$` **PROVISIONAL**; siblings beyond `.1` and the `.elg` siblings **PENDING** | the first stat covered `messages.1` only; names masked |
| `.elg` size-rotation threshold, retention horizon, `FAST_ROTATING` (§4.6) | M1 + M5 gate 1 (size across a rotation) | **PENDING** | needs sizes and growth. The Spike Detective `.csv` (size visible, 17.6 to 23.7 kB on 46 of 53) is the only source whose rotation the second M5 read will show |
| Whether rotated siblings are `.gz` (§4.6, Q9) | M1 family listing | **PENDING** | `.1` is plain; `.2` and later not observed. The design handles `.gz` as a reported gap either way |
| `fwk.elg` / `mq_mng.elg` presence (§2.1) | M5 gate 1 | **RESOLVED** | present on 53/53; `ABSENT` is the exception |
| sar retention depth, naming, size, header (§8.2, C8) | M5 gates 2 and 9 | depth **RESOLVED** (59 to 60 files = 30 days x 2); header shape **RESOLVED**; names **PROVISIONAL** (masked); size bound **PROVISIONAL** 32 MiB | see §3.4 and §8.2 |
| Spike directory layout and sizes (§8.3, C5) | M5 gates 3 and 7–8 | C5 kept **RESOLVED**; population **RESOLVED** (0 to 3,631, median 14, p90 93); data-directory name pattern, layout and sizes **PENDING**; per-run count 16 **PROVISIONAL**; fetch off by default | gate 3 lists the parent only |
| HCP name pattern, count, size, creation time (§8.1, C8) | M5 gates 4 and 10 | size bound 16 MiB **RESOLVED**; selection rule (`*.tar.gz`, newest) **RESOLVED**; strict `hcp_report_...` name **PENDING** (masked); creation time about 03:00 on 36/52, otherwise 00:16 to 06:12 **RESOLVED** | one gateway has no HCP directory (`ABSENT`) |
| Crash/dump name pattern, hence the process-name parse (§7) | M5 gate 5 | **PENDING** | names masked; fallback `UNKNOWN` applies. Baselines **RESOLVED**: `/var/log/crash/` empty on 53/53; `/var/log/dump/usermode/` 122 files on 39 gateways |
| Line timestamp forms for `first/last_line_time` (§4.7, §5.2) | M2; M5 gates 6–8 | `messages`, elg, fwk, sar, PAN **RESOLVED**; `mq_mng` not measured and spike `.log`/`.csv` first-field form **PENDING** | shapes inferred from token classes |
| Leading non-data line on 25 of 53 gateways (§4.2) | all gates | **RESOLVED** | parse by shape |
| PAN `<n>`, pages per run, seed sizes, poll bound, 8 MiB result cap (§6, P1–P5) | M3/M4 summaries | **RESOLVED**: `<n>` 2000, 4 pages, seeds 2000 (system) and 100 (config), 2 s x 30 polls, 8 MiB cap kept | page time at n=2000 **measured** 2334 ms (pilot 2026-10-18, below) |
| PAN config-log redaction fields (§6, §12) | M4 field names | **RESOLVED** | `before-/after-change-preview`, `path`, `full-path`; `-detail` not observed |
| PAN `seqno` contiguity, `dir=forward`, `query` syntax | official PAN-OS XML API documentation, plus M3 field names | **UNKNOWN** | `seqno` is present on every entry; contiguity is not in the summary (findings R4); needs a documentation citation before freeze |

**Pilot 2026-10-18** (the 00:15 Europe/Istanbul window; numbers only).

- Check Point, one gateway, first run: 8 incremental sources in one session, about 18.6 MB read in total (largest
  source 8 MiB, the capped first-run baseline); crash listing baseline 2 entries; no alert. Redactions: `cpd.elg` 133,
  every other source 0.
- Palo Alto, one PA-5200 class firewall: `pan_system` seed 2000 entries, `enqueue_to_fin_ms` 2334, result 2.33 MB;
  `pan_config` seed 100 entries, 2175 ms. This sets the n=2000 page time (one firewall; the bounds of §6 stand).
- Two defects found and fixed after the pilot: the PAN executor recorded `SEQ_GAP_SUSPECTED` and the `GAP` outcome on
  seqno jumps (now a neutral `seqno_jumps` detail count, §6); the Check Point source runs recorded `duration_ms` 0
  (now the elapsed time from the first read of the source to its last answered read or stored chunk, the stat share
  excluded; whole-file sources record their fetch time).

## 12. Privacy, masking, secrets (the raw-evidence exception)

- **Exception boundary.** Raw retention is authorised for the system-log programme only (PO record 2026-10-15 §3,
  D5). It covers the §2.1 sources and nothing else collected in the same session.
- **Secrets are redacted on write, before encryption.** Lines are redacted with tested rules covering:
  - Gaia clish audit lines carrying `password-hash`, `expert-password-hash`, `set user … password`;
  - SNMP community and `usm` auth/priv strings;
  - RADIUS/TACACS `secret`/`key`;
  - `password=`, `passwd`, `key=`;
  - base64 blobs after auth keywords;
  - PAN config-log values in `before-change-preview`, `after-change-preview`, `path` and `full-path` (the observed
    names; any `before-/after-change-detail` field if one appears) for secret-bearing paths;
  - Check Point `fwk.elg` text: 15 % of the sampled lines (9 of 53 gateways, all 20 lines on 5) contain
    secret-vocabulary words, so the rules must be tuned to redact the value and not the whole line, or the pool would
    lose most of that source.

  Each redaction replaces the value with `<redacted>` and counts it (`redaction_count`). The rules are tested with
  synthetic lines only.
  The Palo Alto rule set is versioned (`pan-config-v2`): the rule-set id and the per-rule counts are stored in the
  `detail` of the source's `system_log_run` row beside the total (`system_log_chunk` has no column for them; V160 is
  unchanged). Redacting twice equals redacting once, counts included.
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

## 13. PO questions and answers (decided 2026-10-17)

The PO's chat answer was: "Okuma kısmı, sunucuya çekip tail head orda yapsak nolur daha verimli olur bence. Komutlar
ok. Diğer konularda önerilerin ok". Q1 and Q2 follow from the first two sentences, and Q3–Q10 from the third (each
takes this document's own recommendation). The full interpretation is in the PO record named in the status line.

1. **Read method — DECIDED: SFTP ranged read.** The PO wanted the cutting done on the neXus side with nothing run on
   the device for reading. The first reading of that idea, copying whole files each run, would have multiplied the
   transfer, so it was refined to a ranged read from the stored byte offset. C2 is the SFTP ranged read (§4.3,
   §10.1). Line trimming, fingerprint checks and rotation logic run in neXus. This needs the C4 §2.3 `sftp_get`
   amendment of §4.3, which is named as part of this decision.
2. **Command approval — DECIDED: all 13 approved** as written in §10.1/§10.2, with C2 as the SFTP ranged read. C5 stays
   conditional on M5 gate 3.
3. **First run backfill — DECIDED (recommendation).** The last 8 MiB of each live file only, no rotated history,
   recorded as `INITIAL_BASELINE`.
4. **Cap reached — DECIDED (recommendation).** The oldest chunks are pruned early, ledgered and shown as
   `RETENTION_SHORTENED_BY_CAP`. New collection does not stop.
5. **Notifications — DECIDED (recommendation).** The new type `device_crash_file` is added (amends the FROZEN
   notification routing contract and its V100 check, §7). Rotation-loss gaps (`ROTATED_AWAY`/`TRUNCATED`) do not
   notify. They show on screen and in the daily summary.
6. **HCP export — DECIDED (recommendation).** Export stays blocked until the Data Sanitizer handles tar.gz.
7. **PAN config-log details — DECIDED (recommendation).** `before-/after-change-detail` is stored with secret
   redaction. It is not dropped.
8. **VSX — DECIDED (recommendation).** VS0 only. Per-virtual-system `fwd`/`fwk` logs are measured and added later,
   not now.
9. **Compressed rotations — DECIDED (recommendation).** If M1 shows `.gz` rotated files, the unread remainder is
   accepted as a reported gap (§4.6). Nothing is decompressed on the device.
10. **Operator view — DECIDED (recommendation).** `operation_admin`/`security_admin` may view unmasked lines inside the
    product, audited per view. aiview is always masked.

Still open, and not PO questions: the `PENDING` values of §11, and the PAN vendor-documentation citation for `seqno`
contiguity, `dir=forward` and the `query` syntax.

For the PO's acknowledgement (not decided here; each follows from the findings record):

1. The C5 `<dir>` rule moves from "the measured pattern" to a safe name form (§8.3), because the name pattern could
   not be measured.
2. The C4 output bound of 256 KiB can be reached by the largest Spike Detective listing (3,638 entries). Reaching it is
   `UNKNOWN`. Raising the bound would change an approved row (§8.3).
3. Extra measurement reads: a numeric Check Point measurement summary (findings R1) needs more than the "at most twice
   per device" reads of the M5 rule if it is to give growth rates.

## 14. CLI parity (every UI action has a CLI command)

| CLI command | API | Action id (new) | Roles |
|---|---|---|---|
| `syslog-collect-start [deviceId]` | `POST /api/v2/system-logs/collect` | `system_log_collect_start` | operation_admin, security_admin (window-gated) |
| `syslog-sources <deviceId>` | `GET /api/v2/system-logs/{deviceId}/sources` (cursor state, horizon, `FAST_ROTATING`, last outcome) | `system_log_read` | operator and above; aiview masked |
| `syslog-chunks <deviceId> [source] [day]` | `GET …/{deviceId}/chunks` | `system_log_read` | same |
| `syslog-view <chunkId>` | `GET …/chunks/{chunkId}/text` (masked for aiview; unmasked per Q10, audited) | `system_log_read` / `system_log_read_unmasked` | operation_admin, security_admin (unmasked, audited); others and aiview masked (Q10) |
| `syslog-gaps [deviceId]` | `GET /api/v2/system-logs/gaps` | `system_log_read` | operator and above |
| `syslog-export <deviceId> <source> <day> <reason> <destPath>` | `POST …/export` (Data Sanitizer text path only) | `system_log_export` | operation_admin, security_admin |
| `syslog-crash-files [deviceId]` | `GET /api/v2/system-logs/crash-files` | `system_log_read` | operator and above |
| `syslog-hcp <deviceId>` | `GET …/{deviceId}/hcp` (masked member listing) | `system_log_read` | operator and above |
| `syslog-settings` / `syslog-settings-set <key> <value>` | `GET/PUT /api/v2/config/system-logs` (retention, caps, `include_management_servers`) | `system_log_settings_write` | security_admin |

The existing `syslog-measure-start` and `syslog-measurements` stay for phase A.

## 15. Implementation outline (after the PENDING parameters are set from the findings record)

1. Migration:
   - the gate rows of §10;
   - the tables of §5.2;
   - `ArtefactClass.SYSTEM_LOG`;
   - the `ui2_job_module` mapping;
   - the notification type (Q5, approved).

   It gets the BEGIN/ROLLBACK dry run on the live database before deploy.

   **Implemented as `V160__system_log_phase_b.sql` (step 1 record).** It adds these gate ids, tables and settings:

   - **Gate rows.** All 13 of §10, in the migration and in `gate_registry_fixture.yaml`, with the command text unchanged
     (checked against this section by `tools/tests/test_system_log_collect_gate_contract.py`). Three points were not
     written in §10:
     - C8 has no gate id there; it is registered as `cp_syslog_collect_fetch`.
     - C2 and C8 are SFTP steps (`gate NOT_APPLICABLE`). Their rows are for the record only: `transport_kind` `SFTP_GET`,
       `shell_context` `not_applicable`, and the key is the step description of §10.1.
     - C3, C4, C6, C7 and P5 repeat the command text of an approved measurement row (V157, V151). C4 §3.3 step 6 makes two
       rows with one key a hard error. The key is therefore narrowed with `shell_context` (`expert_collect` for the four
       Check Point rows, `not_applicable_collect` for P5); the command text is byte-identical. A capability spec step
       for these five commands declares that `shell_context`. This is the only way found to keep the §10.3 rule ("the
       collector gets its own row") without breaking the measurement reads.
   - **Tables.** The seven of §5.2 plus `system_log_settings` (§5.3, §2.3, §8.3: retention days, device caps,
     `include_management_servers`, `spike_data_enabled`). Inodes are stored as digits-only text (identity law). Gap and run
     `detail` refuses name and path keys, so §4.5's "affected file names" are carried as digests and suffix classes
     (§5.2 says no names in clear).
   - **Wiring.** `ArtefactClass.SYSTEM_LOG` and the `backup_artefact` class check; `ui2_job_module` maps both collection
     capabilities to `diagnostics`; the `device_crash_file` notification type (service type list, router query, route
     seeded disabled) with the amendment in `NOTIFICATION_ROUTING_CONTRACT.md` §7.
2. Transport: the ranged streaming SFTP read (§4.3): offset and length on the existing `sftp_get` fetch, bytes into
   the artefact sink, exact byte counting. Plus the C4 §2.3 amendment text (§4.3), edited in the same PR.
3. Planner (§4.4) as a pure function over (cursor, stat), with exhaustive unit tests for every row of the decision
   table and every gap kind.
4. Executors, scheduler trigger and staggering, retention pruning, the free-space guard.

   **Check Point half implemented (`CpSystemLogCollectJobExecutor`, step 4 record).** One trusted SSH session per
   device, strictly sequential: C1, the planner, C2 reads only through `CpSyslogRangedRead`, C3, then C6/C7 + C8 in the
   06:00 window. It shares the PAN storage path (artefact store, free-space guard, fenced audited transactions,
   `SystemLogCollectionService`, the disabled scheduler, `syslog-collect-start`). Points the text above does not
   settle, and how they were resolved:

   - **One chunk per read**, not per (device, source, run) (§5.1): §3.2 stores each read before the next is sent and
     §4.1 advances the cursor in the chunk's transaction, and a chunk row names one inode and one offset range.
   - **Chunk identity (`V161__system_log_chunk_identity.sql`; approved in principle 2026-10-17).** The §4.1 key
     (device, source, inode, start_offset) cannot hold two honest chunks that share it: a file system reuses inode
     numbers after a rotated file is deleted, and an `ANCHOR_MISMATCH` restart re-reads the same inode from 0. V161 adds
     the chunk's content identity to the key, so a true duplicate (an at-least-once re-read) is still refused and a
     reused inode is not. The identity is keyed, never a bare hash (§12): `system_log_chunk.plaintext_sha256` is
     replaced by `plaintext_hmac`, HMAC-SHA256 of the stored (redacted) plaintext under the keyed-fingerprint secret
     (`UI2_HOSTNAME_FINGERPRINT_KEY_FILE`), the same key as the anchors and the file-name HMACs, separated by domain
     (`system-log-chunk-content`; anchors `cp-syslog-anchor`), as 64 lowercase hex characters. The same rule replaces
     `system_log_file.sha256` with `content_hmac` (domain `system-log-file-content`, over the file as received). Both
     columns are audit-redacted like the anchors. A Check Point chunk must carry its identity; rows from before V161
     (PAN only) keep NULL, since a keyed digest cannot be derived from a bare one. The artefact manifest
     (`backup_artefact.plaintext_sha256`) keeps the store's own integrity digest, as for every artefact class. V161 also
     adds `system_log_chunk.redaction_rule_set` (§12: the rule-set id, `cp-text-v1`, stored with each chunk). V161 runs
     twice inside one BEGIN/ROLLBACK and needs that dry run on the live database before deploy.
   - **Redaction is fail closed.** Without a wired redactor no C2 read is sent and the source is `UNKNOWN`
     (`REDACTOR_MISSING`); the worker wires `LogRedactor`. sar text and HCP archives are stored as received (§12 lists
     line rules for the incremental sources; sar holds counters).
   - **Process names (§7, pattern `PENDING`).** Only a `<daemon>.<pid>.core[.gz]` name whose daemon is on a fixed list
     of Check Point and system daemons is named; every other usermode entry is `UNKNOWN` (still alerted).
   - **sar day.** The device time zone is not printed: the candidate's `DD` must be yesterday in UTC or in
     Europe/Istanbul, with the 30 h mtime rule. A sar left `BACKLOG` by the device budget is retried in the next window
     of the same day (§3.4 "the next window continues"); HCP stays a 06:00-only item.
   - **`TOO_LARGE`** keeps the job `COMPLETED` (it is reported, §9 does not list it as a failure).
   - **Spike Detective (§3.2 step 4, §8.3) is not built:** no C4/C5 is sent, so the baseline listing does not run yet.
     With `spike_data_enabled` on, `cp_spike_data` is recorded `UNSUPPORTED` (`SPIKE_DATA_NOT_IMPLEMENTED`).
   - **Run duration.** `system_log_run.duration_ms` of an incremental source is the time from its first read sent to its
     last read answered or chunk stored (the stat share excluded); a whole-file source records its fetch time; the crash
     listing records its own duration. A source with no read records 0.
   - **PAN seqno.** A seqno jump is a neutral `seqno_jumps` count in the run detail, never a gap row or an outcome (§6).
   - **Retention (§5.3)** runs in the worker, which owns the artefact store (`SystemLogRetentionPruner`, checked hourly,
     once a day, whether or not collection is enabled): raw chunks and sar 30 days, HCP 7 days, the per-device cap
     oldest-first with a `RETENTION_SHORTENED_BY_CAP` gap row per source, ledger rows 180 days. The backup vault total
     (`JooqRetentionQueries.liveCiphertextBytes`) and the overview's backup freshness no longer count `system_log`.
   - **C8 transport.** The whole-file fetch uses `DeviceTransport.fetchBounded`: the read-only SFTP open/read/close of
     the ranged read from byte 0, the shorter of the gate row's and the kind's timeout (120 s sar, 300 s HCP), one byte
     past the bound so growth is detected, and a transcript that names only the source key. `fetchStreaming` and its
     backup callers are unchanged.
5. Redaction rules and the masking projection, with synthetic-line tests.
6. Logs screen and CLI (§14); effectiveness review at the end.
7. Architecture tests and Java lane tests before ship. The first rollout goes to one device per vendor in one
   window, and the run outcomes are checked before fleet enablement. The rollout also closes three measured gaps:
   the PAN page time at n=2000 (`enqueueToFinMs` per page), the first full Check Point family listing from C1
   (sibling names, `.gz`), and the one Spike Detective data-directory listing (§8.3).

   **Rollout record (step 7).**

   - **Pilot (2026-10-18, 00:15 window):** recorded in §11 (one Check Point gateway, one Palo Alto firewall; two defects
     found and fixed afterwards).
   - **Small test (2026-10-18, the 06:05 window), result reported to the Product Owner in chat:** three Check Point and
     two Palo Alto devices ran; each second run was incremental (cursors resumed, no re-read from the start); sar and HCP
     were fetched on the Check Point devices; no false gap was recorded. Counts and sizes of this run are not repeated
     here, and no device identity is.
   - **Fleet enablement (PO decision 2026-10-18).** `UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_ENABLED=true` is set in
     `deploy/ui2/50-service-deployment.yaml`. `SystemLogCollectionScheduler` is a Spring bean of the service process
     (`@ConditionalOnProperty`, once a minute through `SystemLogCollectionService.admitDue`), so the service deployment
     is the only one that carries the switch; the worker, configuration, compliance, policy and packet-analysis pods run
     no scheduler of this kind. The default in `application.yml` stays `false`, so a process started without the
     manifest (a developer run, a test, a preview candidate with `UI2_SCHEDULING_ENABLED=false`) schedules nothing.
     Collection stays inside the 00/06/12/18 Europe/Istanbul windows (§3.1), and the staggering, free-space guard and
     retention above apply unchanged. To stop it, set the variable to `"false"` and restart the service.
     `tools/tests/test_ui2_deployment_manifests.py` pins both facts.
