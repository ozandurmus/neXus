# Management server (MDS) system-log collection — design

**Status: DRAFT (2026-11-11). Not implementation authority.** Docs only: no code, no migration, no gate row, and no
device command is added or sent by this document. Every command in §8, §9 and §11 is NEW and needs the Product Owner's
approval of its exact literal before any run (`AGENTS.md`, "Network action taxonomy", PO amendment 2026-09-26).
Vendor paths and semantics that official Check Point documentation could not establish are marked `UNKNOWN` (vendor
semantics law) and are settled by the one-MDS measurement of §11 before anything freezes.

Inputs read:

- [DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) (APPROVED): the gateway contract this
  extends. Its §2.3 names the three conditions for enabling management servers: MDS measurement, MDS-scoped gate rows,
  and the PO's switch. This document designs the first two and asks for the third.
- `SystemLogCollectionService.java`: today `targets()` lists only `role = gateway`, and `start()` refuses a
  `management_server` with `SYSTEM_LOG_DISABLED_ON_MANAGEMENT`. The setting `system_log.include_management_servers`
  already exists (default `false`).
- `CpSystemLogCollect` / `CpSystemLogCollectJobExecutor`: scope `cp_gaia_gateway`, fixed `SourceFamily` enum, gates
  C1–C8 of the Phase B contract §10.1.
- `gate_registry_fixture.yaml`: the approved `cp_multi_domain_server` rows (backup, `mdsstat` into a work directory,
  `cpinfo -y all`, `show-ha-status`, the policy `mgmt_cli -d '<DOMAIN>'` reads, `cplic print`, `cplp list`). None reads a
  log file.
- [MDS_LOG_EXPORTER_HEALTH_PROPOSAL_2026_10_22.md](MDS_LOG_EXPORTER_HEALTH_PROPOSAL_2026_10_22.md) (DRAFT): the earlier
  MDS read design (Log Exporter health). This document reuses its session rule and its `mdsenv <DS>&&` form and does not
  duplicate its exporter detections.
- [CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md](CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md): the measured rule
  that `mdsenv` changes the calling shell and must run on the same line as the command it scopes.
- [LOG_MASKING_RISK_MEASUREMENT_2026_11_10.md](LOG_MASKING_RISK_MEASUREMENT_2026_11_10.md) §10 (`logmask-v5`).
- The MDS crash history (October 2026, §3) and the watched backup test of 2026-11-11 02:00.
- Official Check Point R82 documentation, cited per row in §5 (Appendix A).

---

## Özet (PO için)

**Ne istendi.** MDS'in kendi sistem loglarını da toplamak. Bugün neXus MDS'i bilerek dışarıda tutuyor
(`SYSTEM_LOG_DISABLED_ON_MANAGEMENT`).

**Neyi toplamayı öneriyoruz.**

- İşletim sistemi logları: `/var/log/messages`, `/var/log/secure` (Ekim'deki "SSH girişi reddedildi" burada yazılır) ve
  `/var/log/cron` (02:00'de hangi zamanlanmış işin başladığı).
- Süreç bekçisi (`cpwd.elg`).
- Yönetim süreçlerinin logları: CPM, Solr ve RFL (Ekim'de çöken Java süreçleri) ile MDS düzeyindeki FWM.
- Her domain sunucusu için o sunucunun FWM logu.
- API logunun yalnızca boyutu. İçeriği sır riski yüzünden şimdilik kapalı.

Denetim (audit) loglarını şimdilik almıyoruz. Bunlar düz metin değil; okumak için cihazda ağır bir araç çalıştırmak
gerekiyor. Politika değişiklikleri zaten `show-changes` ile geliyor.

**Domainler.** Kayıtlarda 5 domain ve 2 MDS var. Her domainin her iki MDS'te birer sunucusu var, yalnızca biri aktif.
Toplama sırasında domainlere `mdsenv` ile girmiyoruz. Tek bir `stat` komutu tüm domain sunucusu klasörlerini listeliyor;
okumayı SFTP ile yapıyoruz. Böylece domain başına ek kabuk açılmıyor. Klasör yolunun doğruluğunu ölçümde bir kez
`mdsenv` ile kanıtlıyoruz.

**Resmi belge durumu.** Yolların çoğu Check Point R82 belgelerinde var. Belgede olmayanlar UNKNOWN olarak işaretli ve
ölçümde netleşecek: değişkenlerin (`$MDS_FWDIR`, `$RTDIR`, `$CPDIR`) gerçek değeri, `.elg` rotasyon kuralı, `sar`
klasörü, `/var/log/crash`.

**Yük güvenliği (çöken kutu bu).**

- Her koşuda tek SSH oturumu açılır, komutlar sırayla çalışır.
- İlk komut her zaman cihazın görev sayısıdır (`cat /proc/loadavg`). Sayı o MDS'in kendi taban değerinin iki katını
  (en fazla 8.000) aşıyorsa koşu başka hiçbir komut göndermeden çıkar. Değer okunamıyorsa da çıkar.
- Bir koşuda en fazla 4 komut çalışır; Check Point ortamını yükleyen giriş kabuğu bunlardan yalnızca biridir. Geri
  kalan her şey SFTP okumasıdır.
- Java süreçlerine (CPM, Solr) dokunan hiçbir araç çalışmaz: `mgmt_cli`, `fw log`, `fwm logexport` yok.
- Saatler: yalnızca 06, 12 ve 18 pencereleri. 00 penceresi ve 01:30–03:30 arası MDS için kapalı. Bu kontrol hem iş
  kabulünde hem de cihaza bağlanmadan hemen önce yapılır.
- İki MDS asla aynı anda okunmaz.

**Çökme sonrası paket.** 7 küçük okuma komutu: açılış zamanı ve cihaz saati, `cpwd_admin list`, gece aralığı için `sar`
(görev sayısı, fork hızı), messages içinde OOM satırları, CPM/Solr/RFL loglarında thread hataları, en çok thread açan
süreçler, kernel ve WatchDog süreç sınırları. Bu gece için öneri: MDS ayakta kalırsa 03:30'dan sonra T-1, T-2, T-3 ve
T-6; yeniden başlarsa yedisi de. Hepsi sizin onayınıza bağlı.

**Yayılım.** Önce tek MDS'te yalnızca ölçüm (MX-1..3). Sonuca göre toplama komutları düzeltilip size tekrar gelir. Sonra
o MDS'te tek elle koşu, bir hafta zamanlanmış toplama, ikinci MDS, en son kalıcı anahtar.

**İkinci görüş.** Argon "değişikliklerle onay" verdi. Bir engelleyici (grep sınırı dosya başına işliyordu) ve
çoğu büyük bulgu bu sürüme işlendi (§14).

**Sizden istenen.** §8, §9 ve §11'deki komutlar için tek tek onay ve §13'teki sorular. Hiçbir komut gönderilmedi.

---

## 1. Decision this designs for

PO 2026-11-11: "MDS tarafının tasarımına da başla". The Phase B contract left management servers default-off (D3, §2.3)
because the MDS was under a crash investigation. This document is the design that §2.3 asks for. Turning collection on
remains the PO's switch.

## 2. Estate facts (counts only; live DB, 2026-11-11 01:07)

| Fact | Count |
|---|---|
| Check Point devices with role `management_server` | 2 (both enabled) |
| Distinct Domains in `cp_domain_server_placement` | 5 |
| Domain server placement rows (Domain x server) | 10, of which 5 active |
| Multi-Domain Servers named in the placement | 2 |
| Domains with a policy run in the last 30 days | 5 |

Reading: the two MDS form one Multi-Domain HA pair; each of the 5 Domains has one Domain Management Server on each MDS,
one active and one standby. A standby Domain Management Server still writes its own process logs, so each MDS is
expected to hold 5 Domain server directories. The number of those directories on each device is `UNKNOWN` until M-MDS-1
(§11) counts them; the placement table is presentation context, never a join key (identity law).

## 3. The crash history and what it requires

Two nights in October 2026 (10-15 and 10-16) one MDS stopped answering at about 02:25–02:30. The sanitized CPSDC
export showed:

- the `sar` task count went 3.78k (02:00) → 10.2k (02:10) → 38.4k (02:20), with about 2.8k forks per second;
- every Java management process (CPM and others) logged `OutOfMemoryError: Failed to create a thread, errno 11`;
- `cpwd` reported "failed on start" for CPM, FWM and SOLR;
- SSH logins were refused.

The only neXus command present on both crash nights was the scheduled Gaia `clish -c "add backup local"` (02:00–02:04).
The cause is not established. Tonight's (2026-11-11 02:00) watched backup test is the PO's experiment for that question.

What this design takes from it:

1. **The failure mode is process and thread exhaustion, not memory or disk.** So the bound that matters most is the
   number of processes and threads neXus creates on the MDS, and whether it touches the Java processes at all. Bytes
   matter less.
2. **The collector must never run near 01:30–03:30**: the backup (02:00), the daily MDS export (02:00), and the
   observed crash time (02:25).
3. **The collector must stop on its own when the box is already in trouble** (§6.4 stop gate).
4. **A bounded triage bundle** for the morning after a restart (§9).

## 4. Scope

### 4.1 In scope

`platform_role_scope = cp_multi_domain_server`, Gaia R82, Expert login shell (the validated administrator shell),
transport `SSH_EXEC` plus the SFTP subsystem on the same session (as Phase B C2/C8).

### 4.2 Out of scope (now)

- **Audit logs** (`$FWDIR/log/fw.adtlog` and the dated `*.adtlog` files, A.5). Official documentation reads them only
  through `fwm logexport`, which exports them "to an ASCII file" in a Domain context (or `fw log`), so they are not
  plain text (their exact format is `UNKNOWN`). Reading them means a Check Point log-parsing process per Domain on the
  device, or the Management API log query through the Java/Solr stack. Both are the cost §3 forbids. Policy-change audit is already
  collected by the approved `cp_policy_show_changes` read. Q2 asks the PO to confirm.
- **Traffic logs** (`fw.log` received from gateways). Not system logs.
- **Solr and indexer data files**, Java heap dumps and core files: listing only (§9 T-7), never content.
- **Log Exporter health**: designed separately in the DRAFT exporter proposal; not duplicated here.
- **HCP and Spike Detective on the MDS**: not proposed now. The MDS sources of §5 come first.
- **Any write**: no `cpwd_admin` start or stop, no `mdsenv` state change beyond the one shell line, no file removal,
  no log switch.

## 5. Which logs matter, and where they are

Status words: **VERIFIED** = stated in official Check Point documentation (Appendix A); **path form VERIFIED** = the
documentation names the file relative to an environment variable whose value on this MDS is not yet established;
**UNKNOWN** = not established by official documentation, settled by the measurement of §11 or left out.

`$MDSDIR`, `$MDS_FWDIR`, `$CPDIR`, `$MDS_TEMPLATE` and `$RTDIR` are expanded by the device's own login shell
(`bash -lc`). neXus never substitutes them. Every later read uses an absolute path printed by the stat of the same run
(Phase B §4.2 rule). No collection literal is sent before MX-1 has shown every one of these variables non-empty and
pointing at the expected tree (§11): an empty `$RTDIR` would turn `$RTDIR/log/solr.log*` into `/log/solr.log*`, and
`/log` is a real Gaia partition.

### 5.1 Device-level sources (one set per MDS)

| Source key | Family glob (as the stat lists it) | Why it matters | Status |
|---|---|---|---|
| `mds_messages` | `/var/log/messages*` | Gaia OS log: kernel OOM, `fork` failures, clish audit lines, and on R81.20 the `JVMDUMP039I ... OutOfMemoryError` line of a Java management process (A.8). On this MDS it rotated every 15–30 min under a fault (Phase B D1) | path VERIFIED (A.6); the `JVMDUMP039I` line on R82 `UNKNOWN`; rotation `UNKNOWN` (MX-3, §5.4) |
| `mds_secure` | `/var/log/secure*` | sshd refusals, PAM limit (`nproc`) failures, `su`/`sudo`: the "SSH logins refused" symptom of October is written here, not in `messages` | path VERIFIED (A.6, sk97638) |
| `mds_cron` | `/var/log/cron*` | what scheduled task started at 02:00 | **UNKNOWN** (no official page; a standard Linux file; `ABSENT` if not there) |
| `mds_cpwd_elg` | `$CPDIR/log/cpwd.elg*` | WatchDog: every start, stop and "failed on start" of CPM/FWM/Solr; the highest-signal source for the crash | path form VERIFIED (A.7); `$CPDIR` value and whether one WatchDog log covers every Domain on an MDS `UNKNOWN` (MX-1) |
| `mds_cpm_elg` | `$MDS_FWDIR/log/cpm.elg*` | CPM, the Java management server: thread and OOM errors, session lines | path form VERIFIED (A.3, sk168175); `$MDS_FWDIR` value `UNKNOWN` (A.1, MX-1) |
| `mds_mds_elg` | `$MDS_TEMPLATE/log/mds.elg*` | the MDS-level `fwm mds` process | path form VERIFIED (A.3); `$MDS_TEMPLATE` value `UNKNOWN` (MX-1) |
| `mds_solr_log` | `$RTDIR/log/solr.log*` | Solr (Java), the search index whose heap exhaustion on an R82 MDS is a documented fault (A.8, sk183783) | path form VERIFIED (A.3); `$RTDIR` value `UNKNOWN` (MX-1) |
| `mds_solr_run_log` | `$RTDIR/log/solrRun.log*` | Solr's run wrapper log: a JVM that cannot create threads may still get its native error out here when its own logging can no longer write | as `mds_solr_log` |
| `mds_rfl_log` | `$RTDIR/log/RFL.log*` | RFL (Java) query service | as `mds_solr_log` |
| `mds_rfl_run_log` | `$RTDIR/log/rflRun.log*` | RFL's run wrapper log | as `mds_solr_log` |
| `mds_cpd_elg` | `$CPDIR/log/cpd.elg*` | CPD at MDS level (SIC, status) | path form VERIFIED (A.3); `$CPDIR` value `UNKNOWN` (MX-1) |
| `mds_api_elg` | `$MDS_FWDIR/log/api.elg*` | Management API server log | path form VERIFIED (A.4, sk181906); **content off by default** (secret risk, §10.2, Q4); listed by the stat for its size only |

Not chosen: `$MDS_FWDIR/log/fwm.elg` as a separate MDS-level FWM log (the official debug page names `mds.elg` for the
MDS-level `fwm mds`; an MDS-level `fwm.elg` is `UNKNOWN`), `cloud_proxy.elg`, `cpca.elg`, the start/stop script logs,
and `log_indexer.elg` (the Log Exporter proposal owns it).

### 5.2 Per-Domain sources (one per Domain Management Server directory on each MDS)

| Source key | Family glob | Why it matters | Status |
|---|---|---|---|
| `mds_dom_fwm_elg` | `$MDSDIR/customers/*/CPsuite-R82/fw1/log/fwm.elg*` | the Domain Management Server's FWM: policy install, database, per-Domain errors | file VERIFIED as `$FWDIR/log/fwm.elg*` after `mdsenv <Domain>` (A.2, sk97638); the literal directory is **derived** from the official `mdsenv` example (`$FWDIR` = `/opt/CPmds-R82/customers/<Domain Server>/CPsuite-R82/fw1`), not stated in one document: `UNKNOWN` until MX-2 proves it |
| `mds_dom_cpd_elg` | decided after MX-2 | the Domain's CPD (SIC, HA sync, policy install) | **conditional**: MX-2 prints `$CPDIR` inside one Domain context; if that is a per-Domain directory holding `log/cpd.elg`, the glob is added and comes back to the PO as a new literal; otherwise it is dropped |

The directory under `customers/` is named after the **Domain Management Server** (the official example is
`MyDomain_Server`), not the Domain. So the glob is expected to find one directory per Domain server hosted on this MDS,
active or standby. Whether every directory there is a current Domain server is `UNKNOWN`: a decommissioned or restored
directory would also match. MX-1 counts the directories; a count different from the placement table (5 per MDS
expected) is reported to the PO before collection, and later every directory that matches no known Domain server name
is shown as "not in placement" (§7).

Not chosen: a per-Domain `cpm.elg` or `api.elg` (none documented; CPM and the API server are MDS-level processes in the
official pages, A.3 and A.4).

### 5.3 Whole-file and listing sources

| Source key | What | When | Status |
|---|---|---|---|
| `mds_sar_daily` | yesterday's text `/var/log/sa/sar<DD>`, same as Phase B §8.2 | 06:00 window only | **no official Check Point page** (community only, A.6); real-environment evidence on 53/53 gateways (Phase B M5); MDS presence `UNKNOWN` (MX-1) |
| `mds_crash_listing` | one-level listing of `/var/log/dump/usermode/` (VERIFIED as the user-mode core location, A.6) and `/var/log/crash/` (`UNKNOWN` officially; listed on gateways by Phase B C3), names and sizes only | every run | as stated |

### 5.4 Rotation

No official page states the size, count or naming of rotated `.elg` files; the debug pages only use `fwm.elg*`, which
implies several files (A.9). Gaia's own rotation table is `/etc/cpshell/log_rotation.conf`, one line per file with
`<path> <bytes> <count>` (A.10, sk113241). MX-3 reads that table once. It is documented for Gaia's `cp_log_switch`
rotation; whether it also governs the Check Point process logs (`cpm.elg`, the Solr/RFL logs, the per-Domain `.elg`) is
`UNKNOWN`. So MX-3 gives the rotation of the files it lists, and every other source keeps the Phase B method (rotation
seen across two stats). Until then the read-eligible sibling pattern stays the Phase B one (`^<base>(\.[0-9]{1,2})?$`),
and every other sibling is listed, never read.

## 6. Load safety

### 6.1 Process and thread budget per run

Every SSH exec channel costs the MDS one `sshd` child plus a shell. A `bash -lc` login shell on an MDS also sources the
Check Point environment scripts, which fork several helper processes (the count is `UNKNOWN`; MX-1 to MX-3 measure it
from the kernel's cumulative fork counter, §11). A plain exec runs a non-login shell and sources none of them. The SFTP
subsystem is one `sftp-server` process for the whole session.

| Bound | Value | Basis |
|---|---|---|
| SSH sessions per MDS per run | 1 | Phase B D6 |
| Exec channels per run | at most 4: MC-0 stop gate, MC-1 stat, MC-3 crash listing, and in the 06:00 window MC-6 sar listing | design; everything else is an SFTP read |
| Login shells (`bash -lc`) per run | **1** (MC-1 only, because it needs the Check Point variables); MC-0, MC-3 and MC-6 are plain commands with fixed paths | Argon review; the approved MDS rows `df -P /var/log` and `uname -a` already run as plain commands on this MDS |
| `mdsenv` calls per run | 0 | the per-Domain paths are reached by one glob in MC-1 (§7) |
| Java processes touched | 0: no `mgmt_cli`, `fw log`, `fwm logexport`, `cpstat`, CPM Doctor, `api status`, Solr query | §3 |
| Concurrent channels | 1 (strictly sequential, as Phase B) | D6 |
| Wall-clock budget per job | 10 min, then the session is closed and remaining sources are `BACKLOG` | PROVISIONAL |

### 6.2 Size and rate

| Bound | Value | Status |
|---|---|---|
| Bytes per SFTP read | 4 MiB (+64 B anchor) | PROVISIONAL; half the gateway cap because the MDS has more sources (11 device-level content sources plus one per Domain server: 16 on each MDS if it hosts 5 Domain servers) |
| Bytes per source per run | 16 MiB | PROVISIONAL |
| Bytes per MDS per run (all sources) | 64 MiB | PROVISIONAL (same device budget as gateways) |
| First-run backfill per source | 1 MiB (the gateways take 8 MiB) | PROVISIONAL; 16 sources x 1 MiB fit the device budget with room |
| Rate | no throttle is proposed; reads are sequential, one at a time. M-MDS-2 measures the run's duration and its fork count | PENDING |

A budget reached is `BACKLOG` with exact pending bytes, never a silent skip (Phase B §4.5).

### 6.3 Time window and pair exclusion

- MDS jobs run only in the **06:00, 12:00 and 18:00** windows. The 00:00 window is skipped for MDS: its 60 minutes end
  one hour before the backup and export at 02:00, and a slow run should never drift towards them (Q1).
- A hard exclusion **01:30–03:30 Europe/Istanbul** applies to every MDS system-log job, manual included. It is checked
  twice: at admission (`SystemLogCollectionService`) and again by the **worker before it opens the SSH session**, so a
  job admitted late or delayed in the queue cannot reach the MDS inside the exclusion. A job found inside it ends
  `DEFERRED_EXCLUSION_WINDOW` without contacting the device.
- **Pair exclusion.** The two MDS of one HA pair are never read at the same time. Each MDS keeps its own idempotency key
  (`syslog-collect:<device>:<slot>`, as today). The second member is admitted only when the peer has no non-terminal
  job (`SUBMITTED`, `CLAIMED`, `EXECUTING`); when both are due in the same scheduler tick, the order is fixed (lower
  `device_id` first). A shared key per pair is not used: it would deduplicate the second member for the whole window
  (Argon review).
- **Stagger.** The Phase B offset is salted with the capability: `hash(capability + ":" + device_id) mod 40` minutes, so
  this job and any other MDS job hashing the same device (the Log Exporter proposal) do not start in the same minute.

### 6.4 Stop gate (device health before anything else)

MC-0 is the first command of every run: a plain `cat /proc/loadavg`, which costs one `sshd` child and one short
non-login shell, no Check Point environment. The fourth field of `/proc/loadavg` is `running/total`, where `total` is the
kernel's count of scheduling entities (processes and threads); it is the same quantity `sar -q` prints as `plist-sz`. This
is a Linux interface (`proc(5)`), not a Check Point one.

- If `total` is above the device's stop threshold, the run sends no further command, closes the session, and records
  `DEFERRED_DEVICE_BUSY`.
- If the line is missing or does not parse, the run is `UNKNOWN` and stops the same way (fail closed).
- **Threshold per MDS**: 2 x that MDS's own baseline (its median `total` over its last 12 runs, or the MX value for the
  first runs), with a hard ceiling of 8,000. The two MDS differ (the one with more active Domain servers has more
  threads), so neither borrows the other's baseline. The crash nights went from 3.78k to 38.4k.
- `DEFERRED_DEVICE_BUSY` and `DEFERRED_EXCLUSION_WINDOW` are clean outcomes (the job is `COMPLETED` with the reason; no
  cursor moves, nothing is lost). One `system_log_run` row per device-level source records the deferral; per-Domain
  sources are not planned in a deferred run. **Two consecutive deferrals show as ATTENTION** on the System health screen,
  so a box that stays busy never looks silently green.
- The `total` of every run is kept as telemetry: an MDS task-count trend, three points a day, at no extra device cost.

### 6.5 No long-running sessions

Each run opens, works and closes one session. There is no persistent shell, no `tail -f`, no `watch`, no background
process, no `nohup`, and nothing is left running on the device. A dropped session is `UNKNOWN` for the unread sources;
the next window continues from the cursor.

## 7. Per-Domain versus device-level context

**How Domains are reached.** Two ways exist:

1. `mdsenv <Domain>&& <command>` on one shell line, which switches the shell into the Domain Management Server's
   context (official, A.1; the measured same-line precedent in `CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md`). It
   costs one login shell plus the environment scripts **per Domain server**.
2. One glob over the Domain server directories in the MDS-level stat (`$MDSDIR/customers/*/...`), then SFTP reads of
   the printed absolute paths. It costs one shell for all Domains.

This design uses **(2)**: no `mdsenv` during collection, at most one login shell per run whatever the number of Domains,
and no Domain identifier from neXus in any command (no quoting surface). `mdsenv` is used once, in MX-2, to prove that the
directory a Domain server's own environment points at is the one the glob finds.

**Partitioning.** The Phase B executor takes one base directory per source family, and its ranged read requires every
sibling to sit in that one directory. For `mds_dom_fwm_elg` the stat lines are first partitioned by the directory
component after `customers/`; each partition is its own source instance `(mds_dom_fwm_elg, context_ref)` with its own base
file, cursor, anchor and budget, and only then planned and read. A sibling in another Domain server's directory is never
read for this instance.

**Identity and labels.** `context_ref` is the HMAC (privacy key) of the directory name printed by MC-1. The clear name is
never stored and never sent back to the device except inside an absolute path MC-1 printed in the same run. For display
only, neXus computes the same HMAC over the Domain server names it already knows (`cp_domain_server_placement`, the
`logmask-v5` catalog) and labels a match with that server's masked `DMN-` form and its placement state (active or standby,
labelled "from placement"). A directory with no match is shown as `DMN-UNLISTED-<first 6 hex of context_ref>` and "not in
placement". This match is presentation only: it is never a join key, never proof that a Domain is active, and never
changes what is collected (identity law).

**Storage consequence.** `system_log_cursor`, `system_log_chunk`, `system_log_gap` and `system_log_run` restrict
`source_key` to the Phase B list by a `CHECK`, and their keys are `(device_id, source_key)`. The implementation adds a
`context_ref TEXT NOT NULL DEFAULT ''` column to:

- the primary key of `system_log_cursor`;
- the unique key of `system_log_chunk` (`device_id, source_key, context_ref, inode, start_offset`, plus whatever the
  current unique index carries) and its day index;
- the key of `system_log_run` per job and the gap index;
- a `CHECK` that `context_ref <> ''` exactly for the per-Domain source keys.

The retention pruner (`RETENTION_SHORTENED_BY_CAP` per source), the read API and the CLI (`syslog-sources`,
`syslog-chunks`, `syslog-gaps`, `syslog-export`) carry `context_ref` as an optional selector; every gateway call stays
unchanged. One migration with a BEGIN/ROLLBACK dry run on the live database first, as every migration.

## 8. Collection commands for PO approval

All rows: vendor `check_point`, `platform_role_scope = cp_multi_domain_server`, action class `read` (class 0), retry
`none` (the next window is the retry), session reuse = the run's one trusted SSH session, strictly sequential, no
concurrent channel. Field names follow the protocol's ten gate fields. The `stat` and `ls` shapes are the Phase B ones,
so the existing parsers apply; they are still new rows, because scope, purpose and frequency are new (Phase B §10.3).
Shell context: `expert` for MC-1 (login shell); `expert` plain exec for MC-0, MC-3, MC-6; `not_applicable` for the SFTP
rows (as the Phase B `cp_syslog_collect_read` and `cp_syslog_collect_fetch` rows).

| # | gate_id | Transport | Exact command | timeout_s | max_frequency | Max output | unsupported_behavior | secret_output_risk | safe_telemetry_fields |
|---|---|---|---|---|---|---|---|---|---|
| MC-0 | `mds_syslog_collect_gate` | `SSH_EXEC` | `cat /proc/loadavg` | 15 | once per MDS per job window (at most 3 a day) | 1 KiB | missing or unparsable line = `UNKNOWN`, run ends; `total` over the threshold = `DEFERRED_DEVICE_BUSY`, run ends | none (five numbers) | `["tasksRunning", "tasksTotal", "load1"]` |
| MC-1 | `mds_syslog_collect_stat` | `SSH_EXEC` | `bash -lc 'date +%s; stat -c "%n %i %s %Y" /var/log/messages* /var/log/secure* /var/log/cron* $CPDIR/log/cpwd.elg* $CPDIR/log/cpd.elg* $MDS_FWDIR/log/cpm.elg* $MDS_FWDIR/log/api.elg* $MDS_TEMPLATE/log/mds.elg* $RTDIR/log/solr.log* $RTDIR/log/solrRun.log* $RTDIR/log/RFL.log* $RTDIR/log/rflRun.log* $MDSDIR/customers/*/CPsuite-R82/fw1/log/fwm.elg*'` | 30 | once per MDS per job window | 256 KiB (`cpm.elg` alone may keep about 60 rotated files on R82, community report, A.9) | an unmatched glob is a `stat` error line = `ABSENT` for that source; no clock line = `UNKNOWN`; a path outside the tree MX-1 established = `UNKNOWN` for that source, not read | paths carry Domain server directory names: stored as HMAC, masked as `DMN-` | `["deviceEpoch", "name", "inode", "sizeBytes", "mtime", "domainDirs"]` |
| MC-2 | `mds_syslog_collect_read` | `SFTP_GET` (byte range; subsystem on the same session) | no shell command. Read-only SFTP open of `<path>`, read `[<pos>, <pos> + <cap>)`, close. `<path>` = an absolute path printed by MC-1 in the same run, basename in the source's family pattern `^<base>(\.[0-9]{1,2})?$`, directory equal to the directory of that source instance's base file (§7); `<pos>`, `<cap>` computed by neXus, `<cap> ≤ 4194368` | 60 | per source instance per run, until 16 MiB per source or 64 MiB per MDS | `<cap>` | missing file at read time or a short anchor read = `UNKNOWN` for that source, cursor unchanged | raw log lines: administrator names, Domain, policy and gateway names, addresses; `cpm.elg` may carry session ids or request fragments (§10.2). Redacted on write, stored encrypted, masked for aiview | `["startOffset", "bytesRead", "lines", "anchorMatch", "lineSplit"]` |
| MC-3 | `mds_syslog_collect_ls_crash` | `SSH_EXEC` | `ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/` | 30 | once per MDS per job window | 256 KiB | a missing directory = `ABSENT` | names may carry process names; stored as HMAC plus parsed process; masked | `["fileCount", "sizeBytes", "mtime", "process"]` |
| MC-6 | `mds_syslog_collect_ls_sa` | `SSH_EXEC` | `ls -lgo --time-style=+%s /var/log/sa/` | 30 | once per MDS per day (06:00 window) | 64 KiB | missing = `ABSENT` | names only | `["fileCount", "sizeBytes", "mtime", "namePattern"]` |
| MC-8 | `mds_syslog_collect_fetch` | `SFTP_GET` (whole file) | fetch the one `sar<DD>` path printed by MC-6 in the same run (Phase B §8.2 selection rule); no other path | 120 | once per MDS per day | 32 MiB | over the bound = `TOO_LARGE`, not fetched; gone = `ABSENT` | host name in the header line; counters and process names | `["sizeBytes", "sha256", "kind"]` |

A typical MDS run therefore issues MC-0, MC-1, about 8–16 MC-2 reads (one per growing source instance) and MC-3: three
exec channels (one login shell) and one SFTP subsystem. The 06:00 run adds MC-6 and one MC-8.

Command shape notes:

- `$MDS_FWDIR/log/api.elg*` is in the stat even while `mds_api_elg` is off: listing its size costs nothing and shows
  growth; its content is read only if Q4 is answered yes.
- `ls` with a `--time-style` option is GNU `ls`; Phase B ran the same shape on 53 gateways. On the MDS the plain exec of
  MC-3/MC-6 (no `bash -lc`) is new and is proven by M-MDS-2; if the MDS shell rejects it, the literal comes back with
  `bash -lc` and the shell count rises by one.

## 9. Crash triage bundle (after an MDS restart, or after tonight's test)

Purpose: collect a small, fixed set of facts once, to tell a process/thread exhaustion (the October pattern) from other
causes. One manual diagnostic job, started by an administrator, at least 10 minutes after a restart and after SSH
logins work again. It is not part of the scheduled collection. It obeys the 01:30–03:30 exclusion like everything else;
the morning after a 02:25 crash is after 03:30 anyway.

All rows: vendor `check_point`, scope `cp_multi_domain_server`, class `read`, shell `expert` (login shell where a Check
Point variable is used, plain otherwise), `SSH_EXEC`, retry `none`, timeout as listed, the job's one session,
sequential, frequency **once per MDS per triage job, at most 2 jobs per MDS per day**. Output is parsed in memory into the
numeric `safe_telemetry_fields`, plus the masked text projection in the job result (no raw retention; this is a
diagnostic, not the system-log exception). `<DD>` is the two-digit day of the night to inspect in the device's local
date (from T-1's `date +%d`), safe form `^(0[1-9]|[12][0-9]|3[01])$`.

**Numbers are parsed, not masked away.** The aiview text projection hides every `HH:MM:SS` time, every decimal and every
integer of six or more digits (measurement findings of 2026-10-17, §2). That made the Phase B Check Point sizes
unmeasurable. The triage values (task counts, fork counters, thread counts, times) are not identities, so neXus parses
them in memory into the telemetry fields and shows those numbers; the masked text is supporting context only.

| # | gate_id | Exact command | timeout_s | Max output | What it answers | unsupported_behavior | secret_output_risk | safe_telemetry_fields |
|---|---|---|---|---|---|---|---|---|
| T-1 | `mds_triage_uptime` | `date +%s; date +%d; date +%z; cat /proc/uptime; cat /proc/loadavg; grep ^processes /proc/stat; last -x -n 20 reboot shutdown` | 30 | 16 KiB | when it restarted, clean or not (a `reboot` with no `shutdown` before it), the device's date and UTC offset (so `<DD>` and the `sar` times are read in the device's own zone), current task count and cumulative forks | a missing part = that field `UNKNOWN`; `last` with no `wtmp` = reboots `UNKNOWN` | none (kernel version strings, times) | `["deviceEpoch", "deviceDay", "utcOffset", "uptimeS", "tasksTotal", "forksSinceBoot", "reboots", "shutdowns"]` |
| T-2 | `mds_triage_cpwd_list` | `bash -lc 'cpwd_admin list'` | 30 | 64 KiB | which watched processes run, their PIDs, start counts and last start times | command not found or no header = `UNKNOWN` | `COMMAND` column can carry paths and Domain server names: discarded in memory; `APP` names can embed Domain server names (`_`-split masking) | `["apps", "running", "terminated", "startCountMax", "appPid"]` |
| T-3 | `mds_triage_sar_night` | `sar -q -w -r -s 01:30:00 -e 03:30:00 -f /var/log/sa/sa<DD>` | 60 | 64 KiB | task count (`plist-sz`), run queue, fork rate (`proc/s`), memory, 01:30–03:30 device-local time, at the device's sar interval | missing file = `ABSENT` (that night not kept); a `LINUX RESTART` line splits the series and is reported as a restart time | none (counters) | `["plistSzMax", "plistSzAt0200", "procPerSMax", "memUsedPctMax", "restartsInRange"]` |
| T-4 | `mds_triage_messages_oom` | `grep -h -s -I -E "oom-killer\|Out of memory\|Killed process\|Resource temporarily unavailable\|fork: \|JVMDUMP039I\|OutOfMemoryError" /var/log/messages* \| tail -n 200` | 60 | 128 KiB | kernel OOM kills, fork failures, and Java OOM lines, across every plain rotated `messages` file (fast rotation under a fault puts 02:25 in an older sibling) | no match = zero counts (a result); no file = `UNKNOWN` | host names, process names, user names (masked) | `["oomKills", "forkFailures", "javaOomLines", "firstTime", "lastTime"]` |
| T-5 | `mds_triage_java_threads` | `bash -lc 'grep -H -s -I -E "OutOfMemoryError\|unable to create native thread\|Failed to create a thread\|JVMDUMP" $MDS_FWDIR/log/cpm.elg $MDS_FWDIR/log/cpm.elg.0 $MDS_FWDIR/log/cpm.elg.1 $RTDIR/log/solr.log $RTDIR/log/solrRun.log $RTDIR/log/RFL.log $RTDIR/log/rflRun.log \| tail -n 100'` | 60 | 128 KiB | whether the Java thread exhaustion of October repeated in CPM, Solr or RFL, which one, and when | a missing file is skipped silently (`-s`) and reported as not present; no match = zero counts | admin and Domain names can appear in the same lines (masked); the file path names the process | `["oomLinesByProcess", "firstTime", "lastTime"]` |
| T-6 | `mds_triage_top_threads` | `ps -eo pid=,uid=,nlwp=,rss=,etimes=,comm= --sort=-nlwp \| head -n 25` | 30 | 16 KiB | which processes hold the most threads now; PIDs are joined in memory with T-2's PIDs so a `java` line is reported as CPM, Solr or RFL; `uid` shows whether one user owns the bulk (a per-user process limit binds per user) | unparsable = `UNKNOWN` | process names only (`comm`, never `args`); numeric uid | `["topApp", "topNlwp", "javaNlwpByApp", "nlwpByUid"]` |
| T-7 | `mds_triage_limits_dumps` | `cat /proc/sys/kernel/pid_max /proc/sys/kernel/threads-max; for p in $(pidof cpwd); do grep -H "Max processes" /proc/$p/limits; done; ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/` | 30 | 64 KiB | the kernel's process-id and thread ceilings, the process limit WatchDog (and so every process it starts) runs under, and new core files | no `cpwd` process = that limit `UNKNOWN`; missing directory = `ABSENT` | names only | `["pidMax", "threadsMax", "cpwdMaxProcesses", "newDumps"]` |

The table writes `\|` only to keep the Markdown table intact: the literal sent is a plain `|`.

Notes:

- `grep -m` was removed from T-4 and T-5: GNU `grep -m N` stops after N matches **per file**, so it does not bound the
  total (Argon review). The total is bounded by `tail -n`, and T-5 reads only the live file and two siblings, never all
  rotations of `cpm.elg`. `-I` skips binary (for example compressed) siblings; `-s` keeps a missing file from failing the
  command.
- T-3 reads the **binary** `sa<DD>` through `sar` itself, because the text `sar<DD>` is written only at 23:53 (Phase B
  §8.2) and the triage is wanted the same morning. Whether the October crash nights' `sa` files still exist is
  `UNKNOWN` (about 30 days are kept; the nights were 26–27 days ago); a run for those days would be a separate approval.
- T-7 reads the WatchDog's own process limit (`/proc/<pid>/limits`), not the limit of the SSH login shell, which can
  differ. The October crash ended near 38.4k tasks with `errno 11` (`EAGAIN` when creating a thread); T-6 and T-7 show
  whether a per-user process limit or the kernel's `pid_max`/`threads-max` is the ceiling that was hit.
- Nothing here restarts, stops or signals a process. `cpwd_admin list` only reads WatchDog's table; its columns are
  official (A.7): `APP`, `PID`, `STAT` (`E` executing, `T` terminated), `#START`, `START_TIME`, `MON`, `COMMAND`.
- T-7 lists `/var/log/dump/usermode/`, the official user-mode core location (A.6). Gaia deletes cores there when `/log`
  has under 200 MB free and keeps by default 2 per process (A.6), so a missing core is not proof that no process crashed.
- The Solr heap fault of sk183783 is fixed from R82 JHF Take 39; the MDS's installed Take is already recorded by the
  approved `cpinfo -y all` read, so the triage does not ask for it again.

**Tonight (2026-11-11).** With the PO's approval of the literals:

- **If the MDS does not restart**: after 03:30, T-1, T-2, T-3 (`<DD>` = the device's day from T-1, expected 11) and T-6.
  That shows whether `plist-sz` and the fork rate rose during the 02:00 backup without a crash, which is the comparison
  the test needs.
- **If it restarts**: after logins work and at least 10 minutes after the restart (and after 03:30), all seven in the
  order T-1, T-2, T-3, T-5, T-4, T-6, T-7.

## 10. Masking and secrets

### 10.1 Fit with `logmask-v5`

`logmask-v5` already holds the identity kinds an MDS log carries (dictionary of 2026-11-10: administrators 49,
policies 397, Domains 5, MDS hosts 14, plus rules, objects, zones and virtual systems):

| What MDS logs carry | Where | `logmask-v5` |
|---|---|---|
| Domain and Domain server names | per-Domain paths (`customers/<Domain Server>/`), FWM and CPM lines, `cpwd_admin list` `APP` names | `DMN-` tokens (the catalog holds MDS Domain and server names); `/` is not a run character, so a path component is its own token, and `_` is a part delimiter, so a Domain name inside a server or `APP` name is still masked |
| administrator names | CPM and API login/session lines, FWM install lines, clish audit in `messages`, `secure` | `USR-` |
| policy package and layer names | FWM install lines | `POL-` |
| gateway and cluster names, MDS host names | everywhere | device pseudonyms (`FW-`, `CLS-`) and host names |
| addresses | everywhere, `secure` source addresses | prefix-preserving IP permutation |

Rules for this design:

1. **No aiview view before a masking check on MDS data.** After the first week on one MDS, the counts-only measurement of
   `LOG_MASKING_RISK_MEASUREMENT_2026_11_10.md` §8 is re-run over the MDS chunks. The MDS sources are shown to aiview only
   when the surviving identity occurrences are no higher than the gateway level of §10.2 there (242 lines in 2.9
   million). Until then they are visible to `operation_admin` and `security_admin` only (audited per view, Phase B
   Q10).
2. **Known open gap.** `logmask-v5` still leaves the word right after `[` in `cpwd.elg` lines (gap 8 of that record, 221
   occurrences on gateways). `mds_cpwd_elg` carries the same line form, so rule 1 cannot pass for it until gap 8 is
   closed on a sanitized copy; until then `mds_cpwd_elg` stays admin-only even if the other sources pass.
3. **Domain and Domain server names are never dropped as generic.** The catalog drops names made only of generic words;
   for the MDS kinds (Domain, Domain server, MDS host) that filter is not applied, because there are only a handful and a
   generic-looking Domain name is still an identity.
4. **Domain server directory names** in file names and paths are stored as HMAC only, as Phase B file names.
5. A synthetic test pins that the masked view splits on `/` and `_` for MDS paths and `APP` names.
6. The catalog refresh stays at 15 minutes and fails closed (no dictionary, no masked view).

### 10.2 Secrets (redacted on write, before encryption)

New rules in addition to Phase B §12, tested on synthetic lines only:

- Management API and web session material: `X-chkp-sid`, `"sid"`, `session-id`, `api-key`, `Authorization: Bearer|Basic
  <value>`, `Cookie:` values, `JSESSIONID=`.
- Password keys in request fragments (API, CPM): `password`, `new-password`, `old-password`, `expert-password`,
  `grub-password`, `private-key-password`, `passphrase`, `passcode`, `base64-password`, `ftw-sic-key`.
- Directory, identity-provider and connector credentials that CPM and FWM manage: `bind-password`, `ldap-password`,
  `client-secret`, `client_secret`, `secret-key`, `access-key`, `shared-secret`, `pre-shared-secret`, `psk`.
- SIC one-time passwords and activation keys, including command-line forms in `fwm.elg`/`cpd.elg` (`activation key`,
  `one-time password`, a `-p <value>` after a SIC command word).
- Certificate and private-key blocks (`-----BEGIN ... -----` to `-----END ... -----`), replaced as a block.

Whether `api.elg` writes request bodies at all, and with which masking, is `UNKNOWN` (Appendix A.4). So `mds_api_elg`
stays off until a redaction-count check on one week of MDS data (counts only) shows the rules hold. Q4.

## 11. Rollout: one MDS, measure first, then schedule

| Step | What | Commands | Gate to the next step |
|---|---|---|---|
| 0 | Wait for the backup test result of 2026-11-11 02:00 | none (T-1..T-7 only if the PO asks, §9) | the PO's decision on the backup question; no MDS log read while that is open (Q6) |
| M-MDS-0 | One measurement run on **one** MDS (the PO picks; proposal: the member with fewer active Domain servers), 12:00 window, PO informed | MX-1, MX-2, MX-3 only | outputs parsed into numbers and shape classes; masked text only |
| M-MDS-1 | From M-MDS-0: the variable values, Domain server directory count against the placement, the per-Domain `$CPDIR`, the rotation table, the fork cost of one login shell and of one `mdsenv`; the MC literals corrected if any path differs and re-approved; the stop threshold set | none | engineering report to the PO |
| M-MDS-2 | One manual collection run on the same MDS, 12:00 window, first-run backfill 1 MiB per source | MC-0, MC-1, MC-2, MC-3 | fork count and task count before and after, duration, bytes; no incident, no `DEFERRED_DEVICE_BUSY` |
| R-1 | Scheduled on the same MDS, 06:00 and 18:00 only, for 7 days | MC-0/1/2/3, MC-6/8 | no MDS incident; masking check of §10.1 on the week's data; redaction counts for Q4 |
| R-2 | Second MDS added (its own baseline), pair exclusion active, 06/12/18 | same | 7 days clean |
| R-3 | The PO's switch `system_log.include_management_servers = true` becomes the standing state | — | — |

Rollback at any step: the switch off (no device command); data already stored stays under the normal retention.

Measurement commands (once each, M-MDS-0 only; output parsed in memory, kept only as numbers and shape classes, so the
masked text projection cannot hide them again, the Phase B findings R1 lesson). All: vendor `check_point`, scope
`cp_multi_domain_server`, class `read`, shell `expert` login shell, `SSH_EXEC`, retry `none`, the run's one session,
sequential, frequency once in M-MDS-0 (a repeat is a new approval).

Fork cost is measured with the kernel's cumulative fork counter (`processes` in `/proc/stat`), read before and after the
step, because a short helper process has already exited when `/proc/loadavg` is read again (Argon review). The counter
counts every fork on the box, so the figure is an upper bound for neXus's own cost.

| # | gate_id | Exact command | timeout_s | Max output | What it proves | unsupported_behavior | secret_output_risk | safe_telemetry_fields |
|---|---|---|---|---|---|---|---|---|
| MX-1 | `mds_syslog_measure_env` | `bash -lc 'grep ^processes /proc/stat; echo "MDSDIR=$MDSDIR"; echo "MDS_FWDIR=$MDS_FWDIR"; echo "FWDIR=$FWDIR"; echo "CPDIR=$CPDIR"; echo "MDS_TEMPLATE=$MDS_TEMPLATE"; echo "RTDIR=$RTDIR"; cat /proc/loadavg; ls -d $MDSDIR/customers/*/; ls -d /var/log/sa/; grep ^processes /proc/stat'` | 30 | 16 KiB | the variable values at MDS level (settles the official conflict of A.1: MDS-level `$FWDIR` "equal to `$MDSDIR`" against `MDSVERUTIL MDSFwDir`), the Domain server directory count, whether `/var/log/sa/` exists, the baseline task count, and (with the plain exec of MX-3 just before) the forks of one login shell | an empty variable or a missing directory is a result recorded as such; no output = `UNKNOWN` | install paths with version strings; Domain server names in the directory list (counted, masked) | `["varSet", "varPathClass", "domainServerDirs", "saPresent", "tasksTotal", "forksDuring"]` |
| MX-2 | `mds_syslog_measure_domain_env` | `bash -lc 'grep ^processes /proc/stat; mdsenv <DS> && echo "FWDIR=$FWDIR" && echo "CPDIR=$CPDIR" && ls -d $CPDIR/log/cpd.elg; grep ^processes /proc/stat'` with `<DS>` = one Domain server identifier already stored by discovery, safe form `^[A-Za-z0-9._-]{1,128}$`, quoted by the existing helper; run for **one** Domain server only | 30 | 4 KiB | that a Domain server's own `$FWDIR` is the `customers/<Domain Server>/CPsuite-R82/fw1` directory the glob finds (A.2 is derived, not stated); the Domain's `$CPDIR` and whether it holds `log/cpd.elg`; the forks of one `mdsenv`. The `&&` chain prints nothing Domain-specific if `mdsenv` fails, so an MDS-level value is never mistaken for a Domain one | `mdsenv` failure (no `FWDIR=` line) = `UNKNOWN`, recorded | install paths, the Domain server name (masked) | `["fwdirMatchesGlob", "cpdirClass", "cpdElgPresent", "forksDuring"]` |
| MX-3 | `mds_syslog_measure_rotation_conf` | `grep ^processes /proc/stat; cat /etc/cpshell/log_rotation.conf` | 30 | 64 KiB | per-file rotation size and count for the files Gaia's table lists (A.10); whether it lists the Check Point process logs at all (`UNKNOWN`, §5.4); the fork counter before MX-1 | missing file = `ABSENT` (then the Phase B two-stat method applies to every source) | paths and numbers only; masked anyway | `["files", "rotateBytesBySource", "rotateCountBySource", "forksBefore"]` |

MX-3 runs first (plain exec, no login shell), then MX-1, then MX-2: the counter differences then give one plain exec, one
login shell, and one `mdsenv`.

## 12. Implementation outline (after approval; not started)

1. Migration: the gate rows of §8 (and §9, §11 if approved), capability `cp_mds_system_log_collect`, the `context_ref`
   column and keys and the new source keys (§7), the clean outcomes `DEFERRED_DEVICE_BUSY` and
   `DEFERRED_EXCLUSION_WINDOW`; BEGIN/ROLLBACK dry run on the live DB first.
2. `SystemLogCollectionService`: a management server is admitted only when the switch is on, the vendor is
   `check_point`, the clock is inside an MDS window and outside 01:30–03:30, and the HA peer has no non-terminal job
   (§6.3); per-device idempotency keys stay; the stagger is salted with the capability.
   `SYSTEM_LOG_DISABLED_ON_MANAGEMENT` stays the refusal while the switch is off.
3. Executor: the exclusion check again before `connect`; MC-0 stop gate first; the Phase B planner, cursor, anchor and
   gap logic reused; a per-role source catalog instead of the fixed `SourceFamily` enum; per-Domain partitioning by
   directory before planning (§7); the two deferral outcomes added to the clean set; a System health ATTENTION after two
   consecutive deferrals.
4. Parsers: the `/proc/loadavg` and `/proc/stat` lines and the per-Domain path classifier, as pure functions with
   synthetic tests.
5. Retention pruner, read API and CLI carry `context_ref` (§7).
6. Redaction rules of §10.2 and the masking tests of §10.1 (synthetic lines only).
7. The triage job (§9) as a separate diagnostic capability, manual only, CLI parity (`mds-triage <deviceId>`).
8. Architecture tests and the Java lane tests before ship; first run per §11.

## 13. Questions for the PO

1. **Windows.** MDS reads only in the 06/12/18 windows (00 skipped) and never 01:30–03:30: agreed?
2. **Audit logs.** Leave the binary audit logs out (they need a heavy tool on the device) and rely on the policy
   `show-changes` read for change audit: agreed?
3. **Measurement.** Approve MX-1, MX-2 and MX-3 on one MDS in a 12:00 window, and name the MDS (proposal: the member
   with fewer active Domain servers)? The collection literals MC-0 to MC-8 come back for approval after that, corrected
   if the measurement shows a different path.
4. **API log.** Keep `api.elg` content off until a week of redaction counts shows no secret survives, and collect only its
   size until then?
5. **Triage bundle.** Approve T-1 to T-7 as written, manual start only? For tonight: after 03:30, T-1, T-2, T-3 and T-6
   if the MDS stays up; all seven if it restarts.
6. **Order.** No MDS log read until you have decided the backup question from tonight's test: agreed?

## 14. Second opinion

Argon reviewed the first draft of this document on 2026-11-11 (unaltered output:
[ARGON_MDS_SYSTEM_LOG_COLLECTION_REVIEW_2026_11_11.md](ARGON_MDS_SYSTEM_LOG_COLLECTION_REVIEW_2026_11_11.md); verdict
APPROVE WITH CHANGES). Taken into this version:

| Argon finding | Severity | Change |
|---|---|---|
| `grep -m` is per file, so T-4/T-5 were unbounded across rotations | BLOCKER | `-m` removed; total bounded by `tail -n`; T-5 limited to the live file and two siblings (§9) |
| MX-2 `&&`/`;` precedence; `loadavg` cannot measure fork cost; MC-1 ran before the variables were checked | MAJOR | `&&` chain; `/proc/stat` fork counter; M-MDS-0 sends MX-1..3 only (§11) |
| Shared idempotency key would starve the second MDS; exclusion only at admission | MAJOR | per-device keys plus peer non-terminal check; worker re-checks before connect (§6.3) |
| Stop gate ran after the login shell; MC-3/MC-6 used `bash -lc` needlessly | MAJOR | MC-0 plain `cat /proc/loadavg` first; MC-3/MC-6 plain; one login shell per run (§6.1, §6.4) |
| Unparsable `loadavg`; one baseline for both MDS | MINOR | fail closed; per-MDS baseline (§6.4) |
| Stagger collides with other MDS jobs | MINOR | stagger salted with the capability (§6.3) |
| Single base directory per family breaks the per-Domain glob | MAJOR | partition by directory before planning (§7) |
| `context_ref` HMAC cannot be labelled | MAJOR | presentation-only HMAC match against known Domain server names, opaque fallback (§7) |
| Orphan directories under `customers/` | MINOR | MX-1 count against the placement; "not in placement" label (§5.2, §7) |
| Missing `secure`, `cron`, `solrRun.log`, `rflRun.log`; per-Domain `cpd.elg` | MAJOR | added; per-Domain `cpd.elg` conditional on MX-2 (§5) |
| Missing gate fields in §9/§11; SFTP rows need gate ids | MAJOR | columns added; `mds_syslog_collect_read`/`_fetch` named as the Phase B rows are (§8, §9, §11) |
| `sar` time zone; `LINUX RESTART`; `ps` `comm` is `java`; `ulimit -u` is the shell's | MINOR | T-1 prints day and UTC offset; restart lines parsed; T-6 adds `uid` and joins T-2 PIDs; T-7 reads WatchDog's own limit (§9) |
| Over-claimed VERIFIED (rotation table scope, `$CPDIR`/`$MDS_TEMPLATE` values, `JVMDUMP039I` on R82, directory 1:1) | MAJOR | restated as path form VERIFIED or `UNKNOWN` (§5, Appendix A) |
| cpwd gap 8; generic-word filter could drop Domain names | MAJOR | `mds_cpwd_elg` admin-only until gap 8 closes; no generic filter for MDS kinds (§10.1) |
| Missing secret classes | MAJOR | added (§10.2) |
| `context_ref` in unique keys, pruner, API/CLI; deferral accounting and visibility | MAJOR | §7 storage list; clean outcomes and ATTENTION after two deferrals (§6.4, §12) |

Not taken: none. Argon's suggestion to pipe through `tail` adds one short process per triage command; that is accepted
as the cost of a bounded output.

---

## Appendix A. Official sources

Read on 2026-11-11 from public Check Point pages (no device was contacted). "Derived" means two official statements
combined by this document, not stated in one place. Community (CheckMates) reports are hints only, never authority.

| # | Claim | Official source | Version | Status |
|---|---|---|---|---|
| A.1 | `mdsenv [<DMS name or IPv4>]` sets the shell variables for that Domain Management Server; with no argument, for the MDS level. Example: `$FWDIR` becomes `/opt/CPmds-R82/customers/MyDomain_Server/CPsuite-R82/fw1` | R82 CLI Reference Guide, `MDSG/mdsenv.htm` | R82 | VERIFIED |
| A.1 | `$MDSDIR` = `/opt/CPmds-R82`; MDS-level `$FWDIR` "equal to `$MDSDIR`"; in a Domain server `$FWDIR` = `.../customers/<DMS>/CPsuite-R82/fw1`; `$MDS_TEMPLATE` = location of log files and Java archives; `$CPDIR` described without a path | R82 Multi-Domain Security Management Admin Guide, `Environment-Variables.htm` | R82 | VERIFIED |
| A.1 | `$MDS_FWDIR`: used by the sk articles, never defined. `$MDSVERUTIL MDSFwDir` returns `/opt/CPsuite-R82/fw1` (and `MDSVarFwDir` `/var/opt/CPsuite-R82/fw1`), which conflicts with "MDS-level `$FWDIR` = `$MDSDIR`" | R82 CLI Reference Guide, `MDSVERUTIL-MDSFwDir.htm`, `MDSVERUTIL-MDSVarFwDir.htm` | R82 | value **UNKNOWN** (MX-1) |
| A.2 | Per-Domain FWM log: `$FWDIR/log/fwm.elg*` after `mdsenv <Domain_Name>` | sk97638 | R82.10, R82, R81.20 | VERIFIED |
| A.2 | Literal `/opt/CPmds-R82/customers/<DMS>/CPsuite-R82/fw1/log/` | A.1 + A.2 | — | Derived; **UNKNOWN** until MX-2 |
| A.2 | Per-Domain `cpm.elg`, `api.elg`, `cpd.elg` | none found | — | **UNKNOWN** (not collected) |
| A.3 | `$MDS_FWDIR/log/cpm.elg` (CPM server log on an MDS) | sk168175 | R82.10, R82, R81.20 | VERIFIED |
| A.3 | `$MDS_TEMPLATE/log/mds.elg*` (`fwm mds`); `$CPDIR/log/cpd.elg`; `$FWDIR/log/fwd.elg`; `$FWDIR/log/cpca.elg`; `$RTDIR/log/solr.log`, `solrRun.log`, `RFL.log`, `rflRun.log` | sk97638 | R82 | VERIFIED (path forms; `$RTDIR` value **UNKNOWN**, MX-1) |
| A.4 | `$MDS_FWDIR/log/api.elg*` is the Management API log (the official parser reads it after `mdsenv <DMS>`); `$FWDIR/log/api.elg` in symptom text | sk181906; sk119553 | R82, R81.20 | VERIFIED |
| A.5 | Audit logs are typically saved on the MDS and synced to the other MDS in HA | R82 MDSM Admin Guide, `Log-Server-Deployment-Scenarios.htm` | R82 | VERIFIED |
| A.5 | Audit files `$FWDIR/log/fw.adtlog` and dated `*.adtlog`; `fwm logexport` exports `*.log`/`*.adtlog` to ASCII, in a Domain context on an MDS; `fw log -a` means account entries, not audit | R82 CLI Reference Guide, `SECMG/fw-log.htm`, `fw-lslogs.htm`, `fwm-logexport.htm` | R82 | VERIFIED; file format **UNKNOWN**; MDS-level `.adtlog` path **UNKNOWN** |
| A.6 | `/var/log/messages`, `/var/log/dmesg`, `/var/log/secure`, `/var/log/auth/` | sk97638 | R82 | VERIFIED |
| A.6 | `/var/log/dump/usermode/<proc>.<pid>.core`; deleted when `/log` has under 200 MB free; default 2 per process; default total 5000 MB on a Management Server | R82 Gaia Admin Guide, `Crash-Data.htm`; sk92764 | R82 | VERIFIED |
| A.6 | `/var/log/crash` (kernel dumps) | none found | — | **UNKNOWN** (community only; listed on gateways by Phase B) |
| A.6 | `/var/log/sa/` sar files | none found | — | **UNKNOWN** (community only; real-environment evidence on 53 gateways, Phase B M5) |
| A.7 | `$CPDIR/log/cpwd.elg` (WatchDog log) | sk97638 | R82 | VERIFIED |
| A.7 | `cpwd_admin list` columns `APP`, `PID`, `STAT` (`E`/`T`), `#START`, `START_TIME`, `MON`, `COMMAND` | R82 CLI Reference Guide, `FWG/cpwd_admin-list.htm` | R82 | VERIFIED |
| A.8 | Java OOM evidence: `/var/log/messages` shows `JVMDUMP039I ... OutOfMemoryError` and a core appears in `/var/log/dump/usermode` (CPM, Solr, RFL) | sk171173 (points to sk123417) | R81.20 | VERIFIED (R81.20 page) |
| A.8 | Solr out of Java heap on an R82 MDS; fixed in R82 JHF Take 39 | sk183783 | R82 | VERIFIED |
| A.8 | `$MDS_FWDIR/scripts/cpm_dump.sh` writes CPM cores to `/var/log/dump/usermode/` (not used here) | sk97638 | R82 | VERIFIED |
| A.8 | `mds_backup`: no SmartConsole changes while it runs; `-s` stops MDS processes; a log switch first is best practice; active `*.log`/`*.adtlog` are not collected | R82 CLI Reference Guide, `MDSG/mds_backup.htm` | R82 | VERIFIED |
| A.8 | "unable to create new native thread", `hs_err` file location, nproc/ulimit guidance for an MDS; any effect of Gaia `add backup local` on an MDS | none found | — | **UNKNOWN** |
| A.9 | `.elg` rotation size, count, naming, compression | only implied by `fwm.elg*` in sk97638 | R82 | **UNKNOWN** |
| A.10 | `/etc/cpshell/log_rotation.conf`, one line per file `<path> <bytes> <count>`; `log_start list` shows it; errors appear as `cp_log_switch` lines in `/var/log/messages`; an upgrade replaces the file | sk113241 | R82.10, R81.20 | VERIFIED |
| A.10 | default size and count for `/var/log/messages` | none found | — | **UNKNOWN** (MX-3) |

Community hints (not authority): `api.elg` under `/var/log/opt/CPsuite-R82/fw1/log/` on an MDS; `cpm.elg` keeping about
60 rotated files of about 21 MB on one R82 server; CPM Doctor is CPU-heavy (one more reason it is excluded); MDS
control logs under `$MDSDIR/log/`.

Links: guides at `https://sc1.checkpoint.com/documents/R82/WebAdminGuides/EN/<guide>/Content/<topic>`, sk articles at
`https://support.checkpoint.com/results/sk/<sk number>`. The full text of sk183144 (CPM debug) and sk33208 (MDS fwm
debug) needs a login and was not read.
