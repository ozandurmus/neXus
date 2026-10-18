# Policy Collection Completeness and 6-Hour Incremental Revisions — Plan

**Status: APPROVED — PO 2026-10-18 for the seven new commands of §6 (P-1, P-2, P-3, P-4, C-1, C-2, C-3), exactly as
written; C-3 stays conditional (registered, no collector uses it until the Check Point lane shows the NAT timeout or
size evidence).** Every other part of this plan stays a plan: steps 1 to 4 below are not approved implementation by this
status. The approval, the MDS re-enable decision and the partial-first rule are recorded in
[PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md](PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md).
Source: the DRAFT dated 2026-10-18, the file `POLICY_COLLECTION_COMPLETENESS_PLAN_DRAFT.md` in the design
directory of branch `docs/policy-completeness`, copied here with this header and the §9 implementation record added; sections 0 to 8 are
unchanged from the draft. Every UNKNOWN in §8 stays UNKNOWN: the approval of a command is not the resolution of a
vendor semantic.
Date: 2026-10-18. Source baseline: `origin/main` at `70484a81`.
Scope of the draft: audit + plan only. No code, migration, gate row, device or API command, machine session or DB write was
made. Live numbers come from read-only `SELECT`s (inside `BEGIN; SET TRANSACTION READ ONLY; … ROLLBACK`) on
the `ui2` database, executed 2026-10-17 21:10–21:30 UTC (2026-10-18 00:10–00:30 Europe/Istanbul). Only counts,
states, timestamps and opaque UUIDs were selected; no names, addresses or rule contents.

Request (PO, 2026-10-18): collect the missing policies and hit counts on Check Point and Palo Alto, plus objects
and groups; collect NAT for every device group and every policy package; then make collection incremental every
6 hours, with revisions.

---

## 0. PO özeti (Türkçe, sade dil)

**Bugün ne var?**

- **Palo Alto (Panorama):** 54 device group'un hepsi 6 saatte bir eksiksiz toplanıyor. Son 14 günde 19 iş
  çalıştı, hepsi başarılı oldu. Bir tur 4-13 dakika sürüyor ve yaklaşık 100 MB okuyor. Toplanan 40.932 güvenlik
  kuralının 40.917'sinde hit sayısı var; bu sayılar firewall'lardan (38 cihaz, 128 VSYS) okunuyor.
  **NAT hiç toplanmıyor.** NAT kuralları Panorama'dan zaten gelen cevabın içinde var; ama kod yalnızca güvenlik
  kurallarını okuyor. Bunu eklemek için **yeni komut gerekmiyor**, kodu değiştirmek yeterli. NAT kurallarının
  **hit sayısı** için ise firewall başına bir yeni okuma komutu gerekiyor.
- **Check Point (MDS):** 5 domain ve 45 politika paketi var. Erişim kuralları (60.948), NAT kuralları (5.496)
  ve 22 nesne türü toplanıyor. Erişim kurallarının hepsinde hit sayısı var. **Ancak veriler yaklaşık 48 saattir
  güncellenmiyor.** Sebebi şu: MDS cihazı neXus'ta "devre dışı" işaretli ve otomatik tarama devre dışı
  cihazları atlıyor. Bunun dışındaki eksikler şunlar: 1 domain'in son turu eksik bitti, 2 pakette NAT okunamadı,
  22 × 5 = 110 nesne-tür kaydının 14'ü eksik. Eksik kayıtların ikisi "host" listesi; bu iki listede toplam
  yaklaşık 44.000 host nesnesi eksik. Check Point'in yönetim API'si NAT kuralları için hit sayısı **vermiyor**:
  resmi belgelerde böyle bir parametre yok.
- **"Değişenleri topla" mantığı** Check Point için kodda var, ama otomatik turlar her 6 saatte **tam okuma**
  yapıyor. Her tur yaklaşık 1 saat sürüyor ve MDS'ten 0,56-0,61 GB okuyor. Panorama tarafında ise turdan tura
  değişen bir şeyi tanıyan bir mekanizma yok.

**Ne öneriyorum (sırasıyla)?**

1. Check Point'i yeniden başlatma kararı sizde; MDS'teki çökme incelemesi bitince verilmeli. Ardından eksik
   kalan 1 domain, 2 NAT ve 14 nesne türünün sebebini, saklanan maskeli iş kayıtlarından bulup düzeltelim.
2. Nesneler ve gruplar: Check Point'te bazı nesne türleri ekranda "desteklenmiyor" görünüyor; bu tamamen bir
   kod düzeltmesi. Palo Alto'da eksik nesne türlerini (uygulama, uygulama filtresi, EDL, bölge, dinamik
   kullanıcı grubu) aynı cevaptan çıkaralım. Bu adımda yeni komut yok.
3. NAT: Palo Alto NAT kurallarını ve varsayılan kuralları aynı cevaptan çıkaralım (yeni komut yok). NAT hit
   sayısı için firewall başına bir yeni okuma komutu var (onayınıza sunuluyor).
4. Artımlı toplama:
   - (a) Check Point'te otomatik turları "yalnızca değişeni topla" moduna alalım. Hit sayılarını tazelemek için
     günde bir tur, örneğin 00:00, tam okuma olarak kalsın. Bunun için yeni komut gerekmiyor; MDS yükü büyük
     ölçüde düşer.
   - (b) Her domain ve her device group için bir "revizyon" kaydı tutalım. Bu kayıt şunları içerir: ne zaman
     değişti, kaç kural ve nesne eklendi, değişti ya da silindi.
   - (c) "Kim, ne zaman değiştirdi" bilgisi de istenirse iki yeni okuma gerekiyor: Check Point'te
     `show-changes`, Panorama'da config log.

**Onayınızı bekleyen yeni komutlar** §6'daki tabloda, tam metinleriyle duruyor: Palo Alto'da 4, Check Point'te
2 zorunlu komut; isteğe bağlı 2 komut daha var. Her biri ayrı ayrı onay gerektiriyor. Yeni komutların hiçbiri
cihaz ayarını değiştirmiyor; hepsi okuma.

---

## 1. Rules applied

- `AGENTS.md` "Network action taxonomy": every new or changed device command needs the network-device command
  gate (ten fields, `docs/AI_DEVELOPMENT_PROTOCOL.md` §"Network-device command gate"). A parse-scope extension of
  a command already issued (same command, session, timeout and frequency) is not a command addition.
- `AGENTS.md` PO Amendment 2026-09-26: the PO must approve the exact command before execution. Approving one
  command does not approve the next.
- `AGENTS.md` evidence law: "Command presence in source != command approval" when output is used for a new
  purpose. §5.1 therefore asks for an explicit PO acknowledgement of the Panorama NAT parse-scope extension, even
  though it adds no gate row.
- `UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` §3.3 step 2 (FROZEN): the canonical command key is
  `(vendor, platform_role_scope, shell_context, transport_kind, exact literal)`. A different literal, or the same
  literal under a different `platform_role_scope`, is a different key and needs its own `gate_registry` row.
- AIView mentality: counts and opaque references only.

## 2. Current coverage in code (evidence)

### 2.1 Admission and cadence (both vendors)

| Fact | Evidence |
|---|---|
| Discovery refresh runs at 00/06/12/18 Europe/Istanbul for every enrolled, **not disabled** management server | `ui2/service/.../management/DiscoveryRefreshScheduler.java:42` (cron), `:65` (`not d.disabled`); `ui2/platform-core/.../platform/JobWindowPolicy.java:11-12` |
| Each finished discovery queues one policy job. The refresh actor queues **FULL**; any other discovery queues CHANGED_ONLY | `ui2/worker/.../policy/PolicyCollectionJobExecutor.java:42-50` |
| The 6-hour automatic throttle applies only to `automatic && CHANGED_ONLY`, so FULL refresh runs bypass it | `ui2/persistence/.../policy/PolicyCollectionRepository.java:18`, `:84-87`, `:94` |
| Manual requests default to CHANGED_ONLY | `ui2/service/.../policy/PolicyCollectionService.java:75-77` |
| Run deadline 7200 s (`UI2_POLICY_RUN_DEADLINE_SECONDS`) | `ui2/worker/.../Ui2WorkerMain.java:324` |

### 2.2 Palo Alto (Panorama 12.1; firewalls 33 × 11.1, 6 × 12.1 by `devices.observed_software_version`)

| Item | Collected? | Evidence |
|---|---|---|
| Approved reads | `devicegroups`, `dg-hierarchy`, `/config/shared`, per-DG `/config/devices/entry[@name='localhost.localdomain']/device-group/entry[@name='<DG>']` | `V117__pan_policy_reads.sql:4-11`; `jobs/policy/PanPolicyGates.java:11-15`; fixture `gate_registry_fixture.yaml:3073-3140`; `PO_DECISION_RECORD_2026_10_01_PAN_POLICY_READS.md:10-17` |
| Security pre/post (shared → ancestors → DG; post reversed) | yes | `platform-core/.../policy/PanoramaPolicyMapper.java:58-62`, `:121` (`<phase>/security/rules/entry` only) |
| Default security rules (`default-security-rules`) | **no** | `PanoramaPolicyMapper.java:121`: only `security/rules` is read |
| NAT pre/post | **no**. The data is already inside the approved shared and DG responses but is not parsed | `PanoramaPolicyMapper.java:121`. The PAN XML API `action=show` with an xpath returns that node's subtree (vendor example returns `rulebase/security` with its `rules`: [Use XPath to Get Active Configuration](https://docs.paloaltonetworks.com/pan-os/10-0/pan-os-panorama-api/pan-os-xml-api-request-types/configuration-api/get-active-configuration/use-xpath-to-get-active-configuration)). The live collector already parses DG-resident object entries from the same response, which corroborates that descendants are returned |
| Objects | address, address-group (static and dynamic filter), service, service-group, application-group, tag, schedule. Stored **inline in each DG snapshot**, with shared objects copied into every DG | `PanoramaPolicyMapper.java:44`, `:53-57`, `:154-183` |
| Objects not collected | custom `application`, `application-filter`, `external-list` (EDL), `region`, `dynamic-user-group`, profile groups; zones (template scope, not DG) | `PanoramaPolicyMapper.java:44` (closed list) |
| Object precedence | Ancestor/descendant collisions become UNKNOWN, because the approved reads do not expose the precedence setting | `PanoramaPolicyMapper.java:64-72`; PO record `:47-52` |
| Hit counts | security rulebase only, per firewall per VSYS, direct firewall read, joined to rules by **name**. Ambiguous names are left unjoined | `V121__policy_hit_counts.sql:7`, signed off by `V155__pan_rule_hit_count_signoff.sql:7-10`; `jobs/policy/PolicyHitGates.java:10`; `worker/.../policy/PanoramaPolicyCollector.java:220-266`; `PolicyHitCounts.java:37-77` |
| Firewall-local (non-Panorama) rules | **not shown while a Panorama exists**. The local projection is an offline fallback used only when no Panorama node exists | `service/.../policy/LocalFirewallPolicyService.java:10`, `:24` |
| Change/revision marker | none across jobs. `contentVersion` (SHA-256 of lineage XML) is used only to resume units inside one job | `PanoramaPolicyCollector.java:152-153`, `:275-288` |

### 2.3 Check Point (MDS; R81.10 / R81.20 per PO; DB holds no MDS version value)

| Item | Collected? | Evidence |
|---|---|---|
| Approved reads | domains, packages (paged), access rulebase by name/UID (+`show-hits true`), NAT rulebase, 22 object types, `show-last-published-session` | `V116`, `V118`, `V123`, `V124`, `V125`, `V130`; `jobs/policy/CpPolicyGates.java:11-39`; fixture `:2992-3600` |
| Access layers incl. inline | yes | `worker/.../policy/CheckPointPolicyCollector.java:265-273`; `CheckPointPolicyMapper.java:41-44` |
| NAT per package | **yes** (`show-nat-rulebase package '<PKG>' limit 500 … details-level standard use-object-dictionary true`, 60 s, no retry) | `V116__cp_policy_reads.sql:9`; `CheckPointPolicyCollector.java:261-290`; `CheckPointPolicyMapper.java:45-77` |
| Hit counts | access rules via `show-hits true` (MDS-aggregated). NAT: none, and the documented API has no hit parameter for NAT (§5.3) | `V124:4-6`, `V130:7`; `PolicyHitCounts.java:29-36` |
| Objects/groups (domain inventory) | 22 types into `cp_policy_object_inventory` (+ `cp_policy_json_chunk`), refreshed at most every 24 h (`UI2_POLICY_CP_OBJECTS_EVERY=86400`) | `V124:56-64`, `V126`; `CheckPointPolicyCollector.java:348-411`, `:355-356` |
| Objects inside package snapshots | rulebase `objects-dictionary` only. Native types outside a closed list map to `unresolved`/UNSUPPORTED; group members absent from the dictionary are UNRESOLVED | `CheckPointPolicyMapper.java:173-202`, `:183-191` |
| Change marker | per-domain `show-last-published-session` (uid + publish-time). An equal signal reuses the previous complete domain run, **in CHANGED_ONLY mode only** | `CpDomainReuse.java:38-86`; `CheckPointPolicyCollector.java:218-233` |
| Concurrency | up to 4 MDS sessions (`ui2.policy.cp.max-sessions`) | `PO_DECISION_RECORD_2026_10_02_CP_POLICY_PARALLEL_SESSIONS.md:44-48`; `CheckPointPolicyCollector.java:156` |

### 2.4 History today

`policy_rule_history` (`V120`, `V122`, `V132`, `V150`) records added/removed/modified rules on every snapshot
publication, through a trigger on `policy_snapshot`. CP NAT rules are included because they are snapshot
sections. Hit counts are excluded from the diff (`V121:10-12`). Initial captures and renumbering noise are
suppressed (`V150`). Nothing records **object** history or a per-container **revision** record.

## 3. Current coverage in live data (read-only counts, 2026-10-17 21:10–21:30 UTC)

| Measure | Palo Alto (Panorama) | Check Point (MDS) |
|---|---|---|
| Management sources | 1 Panorama, enrolled, enabled | 1 MDS, enrolled, **disabled** (`devices.disabled = true`) |
| Containers known / with snapshot | 54 DGs / 54 (every recent job checkpointed 54/54 units) | 5 domains, 45 packages / 5 domains, 45 packages |
| Newest / oldest snapshot | 2026-10-17 21:02 / 15:01 UTC (current run) | 2026-10-15 21:40 / 21:08 UTC (**~47.5 h old**) |
| Latest container run complete | 54/54 | 4/5 domains complete; 1 domain `COLLECTED` but `complete=false` (3,114 rules) |
| Snapshots with a failure entry | 0 | 2 of 45, both NAT `INVALID_OR_INCOMPLETE_RESPONSE` |
| Security/access rules | 40,932 (all "Post rules"; 0 pre rules in this estate) | 60,948 in 804 access sections |
| NAT rules | **0** (not parsed) | 5,496 in 329 NAT sections (43 of 45 packages) |
| Rules with hit counts | 40,917 / 40,932 (15 without: ambiguous name or no member breakdown) | 60,948 / 60,948 access; 0 / 5,496 NAT |
| Snapshots: all rules with hits / partial / empty DG | 43 / 7 / 4 | 2 / 43 (partial = NAT sections) / 0 |
| Hit targets | 128 firewall-VSYS (38 firewalls), all enrolled | MDS-aggregated (`source=mds`) |
| Objects stored | inline per DG: 1,615,122 address, 175,744 service, 25,395 address-group, 12,929 schedule, 3,579 service-group, 1,192 tag, 4 application-group (shared copied per DG). 435 UNKNOWN (precedence), 517 unresolved | Inventory: 110 rows (5 domains × 22 types). 96 RESOLVED, **14 TYPE_INCOMPLETE**. Package dictionaries: 32,339 UNRESOLVED references and 7,482 UNSUPPORTED types |
| Inventory gaps | n/a (no PAN inventory table) | 12 zero-fetch failures: services-sctp ×5, time-groups ×4, groups-with-exclusion ×2, dns-domains ×1. 2 truncated `hosts`: 1,650 / 28,413 and 2,500 / 20,073, so **44,336 host objects missing** |
| Jobs (14 days) | 19 COMPLETED, 0 failed | 32 total: 2 clean, 29 completed **PARTIAL**, 1 FAILED (`SESSION_CLEANUP_FAILED`) |
| Run size/time | ~101 MB per run (849 steps, largest response 8.2 MB); 4–13 min | 0.56–0.61 GB per run (2,321–5,575 steps); 37–71 min |
| Mode of automatic runs | 17 FULL, 1 CHANGED_ONLY | 10 FULL, 2 CHANGED_ONLY. Reuse ever happened only on manual CHANGED_ONLY runs (3 domain-runs `REUSED`) |
| History | 245,447 rows, 63,243 initial captures | — |
| Storage | `policy_snapshot` 325 MB, `policy_rule_history` 364 MB, baseline 326 MB, `cp_policy_json_chunk` **2,859 MB** | |

CP partial-job reason classes (14 days, counts per job terminal reason):

| Reason class | Jobs |
|---|---|
| `show-nat-rulebase` page `INVALID_OR_INCOMPLETE_RESPONSE` | 10 |
| access-rulebase page `INVALID_OR_INCOMPLETE_RESPONSE` | 8 |
| `show-packages` invalid/truncated (incl. legacy `PACKAGE_LIST_TRUNCATED_BY_SERVER total=21 returned=20` before V123) | 6 |
| preflight `FAILED_DataAccessException` | 2 |
| `POLICY_DB_DOMAIN_WRITE_FAILED` / `POLICY_DB_SNAPSHOT_WRITE_FAILED` | 2 |
| preflight `INVALID_OR_INCOMPLETE_RESPONSE` | 1 |

Other verified observations:

- **No discovery or policy runs for either vendor from 2026-10-10 06:00 to 2026-10-15 18:00 (Istanbul).** The
  cause was not investigated in this audit.
- **The 2026-10-15 PAN history spike** (40,733 `modified` rows) is 40,673 `enabled` field changes. It coincides
  with commit `17d59220` ("a Palo Alto rule without a `<disabled>` leaf is enabled", 2026-10-15 13:20 +03:00).
  It is a parser correction, not operator changes.
- **CP reuse never refreshes hit counts.** A reused domain republishes the previous snapshots, including their
  old hit values (`CpDomainReuse.java:61-82`). This matters for §5.4.

## 4. Gap list (quantified)

| # | Gap | Vendor | Quantity | New command? |
|---|---|---|---|---|
| G1 | All CP policy data stale; automatic collection stopped | CP | 5 domains / 45 packages, ~47.5 h | No. Operational: MDS device disabled (PO pause; see §7 Q1) |
| G2 | CP domain run incomplete | CP | 1 of 5 domains (3,114 rules) | No (diagnose first) |
| G3 | CP NAT read failed | CP | 2 of 45 packages; NAT failures in 10 of 32 jobs | Maybe (C-3, conditional) |
| G4 | CP object inventory incomplete | CP | 14 of 110 type rows; 44,336 hosts missing | No (diagnose first) |
| G5 | CP package objects shown UNSUPPORTED/UNRESOLVED | CP | 7,482 UNSUPPORTED types; 32,339 unresolved member refs (resolvable from inventory; join coverage not measured) | No (parse/join) |
| G6 | CP NAT hit counts | CP | 5,496 NAT rules | Not available in the documented Management API v1.8/v1.9 (§5.3) |
| G7 | PAN NAT rules | PAN | all DGs and shared; count UNKNOWN until parsed | No (parse scope of approved reads; PO acknowledgement §5.1) |
| G8 | PAN default security rules | PAN | per DG/shared; count UNKNOWN | No (parse scope) |
| G9 | PAN object types missing | PAN | application, application-filter, EDL, region, dynamic-user-group, profile-group | No (parse scope). Zones need template reads (out of scope) |
| G10 | PAN objects duplicated per DG, no inventory view | PAN | 1.83 M inline object rows for 54 DGs | No (storage design) |
| G11 | PAN NAT hit counts | PAN | 128 firewall-VSYS | **Yes** (P-1) |
| G12 | PAN security rules without hits | PAN | 15 / 40,932 | No (name join limit; accept or use UUID if the vendor exposes it, UNKNOWN) |
| G13 | Firewall-local PAN rules hidden while Panorama exists | PAN | 38 firewalls / 128 VSYS; count UNKNOWN | No (stored effective-running artefacts) |
| G14 | No incremental automatic CP runs; every 6 h is a full re-read | CP | ~0.6 GB and ~1 h per run, 4 runs/day | No (switch to CHANGED_ONLY) |
| G15 | No per-container revision record; no object history | both | — | No (migration) |
| G16 | No "who/when" change evidence | both | — | **Yes** (C-1/C-2, P-2/P-3/P-4) |

## 5. Proposed steps (in order), sizes and risks

Sizes: S ≤ 1 lane-day, M 2–4, L > 4. Every step ships as its own PR. Each step runs the Java lane tests and
`:architecture-tests:test`, and each migration is dry-run in `BEGIN/ROLLBACK` on the live DB before rollout.

### Step 1 — Fix missing collections first (G1–G4)

1a. **Resume CP (PO decision, no code).** The MDS row is disabled, so discovery refresh skips it
`DiscoveryRefreshScheduler.java:65` and no policy job is queued. Re-enable only after the PO's MDS crash
investigation clears it.
- Devil's advocate: the current automatic CP run is the heaviest MDS read neXus makes (~0.6 GB over 37–71 min
  with up to 4 sessions, every 6 h). Step 4a cuts that load and should land **before or together with**
  re-enabling.
- Whether policy collection relates to the crashes is UNKNOWN; do not assume either way.

1b. **Diagnose G2/G3/G4 from stored sanitized transcripts** (encrypted job transcripts already hold request
template, opaque target, byte count, elapsed time and failure step; `PolicyCollectionTrace`). Size S.
- No device contact.
- Output: per failure, the step, byte count and elapsed time against the 60 s (NAT) and 300 s (objects)
  budgets.

1c. **Fix by evidence (size S–M).** Likely candidates, each to be confirmed in 1b:
- NAT `limit 500` against a 60 s timeout with no retry. If the transcripts show timeouts or truncation,
  propose C-3 (a smaller NAT page with one retry), which is a new canonical key.
- Inventory zero-fetch failures on small types (services-sctp, time-groups and others, all at 15:52:0x–15:52:2x
  in the same run) suggest a session-level failure rather than type-specific data. UNVERIFIED.
- The two truncated `hosts` reads need the same diagnosis.

Risk: low (code only). An unfixed G3 keeps NAT UNKNOWN for those packages; existing snapshots survive failures.

### Step 2 — Objects and groups (G5, G9, G10)

2a. **CP mapper type coverage (parse scope, S).** Map the native types the 22 approved inventory reads already
deliver: access-role, dynamic-object, dns-domain, security-zone, services-other/sctp/dce-rpc/rpc/icmp6,
gateways/clusters.
- These no longer show as UNSUPPORTED in package snapshots (`CheckPointPolicyMapper.java:183-191`).
- Resolve UNRESOLVED group members against the domain inventory in the viewer (`PolicyQueryService.java:121`
  already builds a domain index); measure the remaining unresolved count after the change.
- Application sites/groups need C-4 (optional) only if the dictionary does not carry them; measure first.

2b. **PAN object types (parse scope, S).** Add `application`, `application-filter`, `external-list`, `region`,
`dynamic-user-group` and `profile-group` entries from the same shared/DG responses
(`PanoramaPolicyMapper.java:44`). Element names follow the vendor SDK (`panos/objects.py`; secondary source).
Real response shapes are UNVERIFIED until parsed, so a missing element stays UNKNOWN.

2c. **PAN object inventory (M, migration).** Store shared and DG-scope objects once per scope, in a
`pan_policy_object_inventory` table analogous to `cp_policy_object_inventory`. DG snapshots then reference
objects instead of copying them, which removes most of the 1.83 M duplicated rows. Risk: medium (storage
migration + viewer change); needs the migration dry-run.

### Step 3 — NAT (G7, G8, G11; G6 documented as unsupported)

3a. **PAN NAT and default rules from the approved reads (parse scope, M).**
- Parse `pre-rulebase/nat/rules/entry` and `post-rulebase/nat/rules/entry` per scope in shared → ancestor → DG
  order (post reversed), the same ordering rule as security.
- Parse `default-security-rules`.
- Fields per the vendor SDK `NatRule` (`from`, `to`, `to-interface`, `service`, `source`, `destination`,
  `nat-type`, `source-translation/*`, `destination-translation/*`, `dynamic-destination-translation/*`,
  `disabled`, `target`, `uuid`) — [pan-os-python policies.py](https://github.com/PaloAltoNetworks/pan-os-python/blob/develop/panos/policies.py)
  (secondary, vendor SDK).
- No new command: same two reads, session, timeout and frequency (`AGENTS.md`: parse-scope extension).
- **PO acknowledgement requested (§7 Q2)**, because NAT is a new use of the approved output.
- Risk: response size limits are already enforced (16/128 MiB per response, `PanoramaPolicyCollector.java:26`).
  Snapshot size grows by the NAT rule count (UNKNOWN).

3b. **PAN NAT hit counts (P-1, new command, S).**
- One op read per firewall-VSYS, serial, reusing the per-member key of the existing security hit read.
- The vendor states hit counts exist for NAT rules ([View Policy Rule Usage, PAN-OS 11.1](https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/monitoring/view-policy-rule-usage.html)).
- The exact XML element `rule-base/entry[@name='nat']` is UNKNOWN: it is derived from the documented security
  form ([pan.dev rule hit counts](https://pan.dev/panos/docs/tutorials/rule-hit-counts/)) by substituting the
  rule-base, and must be confirmed by one PO-approved measurement call (§6).
- HA: counts are not synchronized between peers (same 11.1 page), which the per-member read already handles.

3c. **CP NAT hit counts: UNSUPPORTED via the Management API.** The documented `show-nat-rulebase` request has
only `package` (required), `filter`, `filter-settings`, `limit`, `offset`, `order`, `use-object-dictionary`,
`dereference-group-members`, `show-membership` and `details-level`. It has no `show-hits` and no
`hits-settings`, in both API v1.8 (R81.10) and v1.9 (R81.20). By contrast, `show-access-rulebase` documents
`show-hits` and `hits-settings{from-date,to-date,target}`.
- Sources: [Management API reference](https://sc1.checkpoint.com/documents/latest/APIs/index.html#cli/show-nat-rulebase~v1.9);
  machine-readable source of the same reference: `https://sc1.checkpoint.com/documents/latest/APIs/data/v1.9/dynamic/apis.json`.
- Gateway-side NAT counters would be a different product plane; this plan does not propose them.

3d. **CP NAT package name uniqueness.** `show-nat-rulebase` takes the package **name** only ("Name of the
package", same reference). The existing `VENDOR_NAME_NOT_UNIQUE` UNKNOWN therefore stays; there is no
documented UID form.

### Step 4 — Incremental every 6 hours, with revisions (G14–G16)

4a. **CP: automatic runs CHANGED_ONLY, one daily FULL (no new command, S).**
- In `PolicyCollectionJobExecutor.java:49-50`, queue CHANGED_ONLY at 06/12/18 and FULL at the 00:00 slot.
- An unchanged domain then costs one `show-last-published-session` read, already approved (`V125:5`), instead of
  hundreds of pages.
- Hit counts on reused domains stay at the age of the last FULL run, at most 24 h; the UI shows the age through
  the existing `hits_collected_at` (`V125:20`).
- If the PO wants 6-hourly CP hits, every slot must re-read the access rulebases, as today; that is a load
  decision (§7 Q4).
- Risk: low; the reuse logic is already in production for manual runs (3 REUSED domain-runs observed).

4b. **Revision record (M, migration).** Add a table `policy_revision`:
- Columns: `(source_id, container_ref, revision_no, observed_at, marker JSONB, rules_added, rules_modified,
  rules_removed, nat_rules, objects_added, objects_modified, objects_removed, complete)`.
- A new revision is written only when the marker changes:
  - CP: the published-session uid/publish-time already stored in `cp_policy_domain_run.signal`.
  - PAN: a per-DG content hash persisted across jobs. Today `contentVersion` covers the lineage XML
    (`PanoramaPolicyCollector.java:275`); the hit-count projection must stay outside the hash.
- Rule counts come from the existing `policy_rule_history` trigger.
- Object diffs are new: compare the inventory between generations for CP and between 2c inventories for PAN.
- Unchanged PAN DGs are not re-published (hits are attached separately), which also stops needless history
  trigger work on unchanged rules.

4c. **Who/when change evidence (new commands; M–L).**
- **CP (C-1, C-2).** `show-changes from-date <last publish> to-date <now>` returns a task id; `show-task
  details-level full` then returns, per published session, the `added-objects`, `modified-objects`
  (old/new) and `deleted-objects`, each with uid, type and domain (vendor example in the same reference,
  "show-changes between the dates").
  - Use the result to (i) fill the revision's object/rule change list by opaque uid and type and (ii) narrow
    re-reads to the affected layers and object types.
  - Whether a modified rule names its layer is UNKNOWN; without it, re-read the whole changed domain, as now.
  - The response carries `user-name`: drop it, or keep it masked per §6 field 9.
  - `show-changes` analyzes up to `limit` sessions (default 10) per call and pages with `offset`.
- **PAN (P-2/P-3/P-4).** Panorama config log, incremental by `receive_time`, with the same literals PO-approved
  2026-10-17 for firewalls (`gate_registry_fixture.yaml:4083-4161`). On `platform_role_scope: panorama` these
  are new canonical keys (C4 §3.3).
  - It gives the time, admin and xpath of each Panorama change, linking a revision to its change entries.
  - It ties into the system-log programme (`DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md`).
  - `log-type=config`, `nlogs` maximum 5000, `dir=forward` and `query` are documented ([API Log Retrieval
    Parameters](https://docs.paloaltonetworks.com/pan-os/10-0/pan-os-panorama-api/pan-os-xml-api-request-types/retrieve-logs-api/api-log-retrieval-parameters));
    the asynchronous job-id flow is documented in [Retrieve Logs](https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/retrieve-logs).
  - Panorama-specific config-log field content (for example, whether `path` names the DG) is UNKNOWN until
    measured.

### Load bounds (per 6-hour slot)

| Path | Today | After plan |
|---|---|---|
| CP, no domain changed | ~2,300–5,500 reads, ~0.6 GB, ~1 h | 1 domain list + 5 × `show-last-published-session` (+ C-1/C-2 only for changed domains); FULL once a day |
| CP, one domain changed | same as above | that domain re-read fully (as today); others reused |
| PAN | 4 Panorama reads + 54 DG reads + 128 firewall hit reads; ~101 MB; 4–13 min | + 128 NAT hit reads (P-1, response size UNKNOWN) + ≤ 4 Panorama config-log pages (P-2, ≤ 8 MiB each) + polls (P-4) |

The vendor gives no per-API rate limit in the cited pages: UNKNOWN. Concurrency stays as approved: PAN serial;
CP ≤ 4 sessions.

### Step summary

| Step | Size | Risk | New command |
|---|---|---|---|
| 1a resume CP | PO decision | MDS load, crash cause UNKNOWN | no |
| 1b–1c diagnose/fix CP gaps | S–M | low | C-3 only if evidence demands |
| 2a CP object types/join | S | low | no (C-4 optional) |
| 2b PAN object types | S | low | no |
| 2c PAN object inventory | M | medium (migration) | no |
| 3a PAN NAT + default rules | M | low–medium (snapshot size) | no (PO acknowledgement) |
| 3b PAN NAT hits | S | low | P-1 |
| 4a CP CHANGED_ONLY + daily FULL | S | low | no |
| 4b revision table + object diff | M | medium (migration, trigger load) | no |
| 4c who/when evidence | M–L | medium (async tasks on MDS; log volume) | C-1, C-2, P-2, P-3, P-4 |

## 6. Command table for PO approval (new commands only)

The ten gate fields follow `docs/AI_DEVELOPMENT_PROTOCOL.md` §"Network-device command gate". Class 0 means
read (`ActionClass.CLASS_0_READ`). Placeholders are server-side opaque substitutions; nothing comes from the
browser. Approving one row does not approve another.

### P-1 PAN NAT rule hit counts (firewall)

Exact text: `type=op&cmd=<show><rule-hit-count><vsys><vsys-name><entry name='<VSYS>'><rule-base><entry name='nat'><rules><all/></rules></entry></rule-base></entry></vsys-name></vsys></rule-hit-count></show>`

| # | Field | Value |
|---|---|---|
| 1 | Why | G11: NAT rule usage per firewall-VSYS. Hit counts exist for NAT rules ([View Policy Rule Usage 11.1](https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/monitoring/view-policy-rule-usage.html)). The XML element is UNKNOWN: it is derived from the documented security form ([pan.dev](https://pan.dev/panos/docs/tutorials/rule-hit-counts/)). The first execution is one PO-approved measurement on one firewall |
| 2 | Class | 0 read |
| 3 | Vendor/platform/shell/context | palo_alto / `pan_firewall` / not_applicable / `PAN_XML_API`; `<VSYS>` is the stored VSYS context of a DG target |
| 4 | Timeout | 60 s |
| 5 | Retry | none |
| 6 | Max frequency | automatic at most once per firewall and VSYS per 6 hours (shares the existing `policy_collection_domain` throttle key family); manual admin request |
| 7 | Session reuse | the per-member API key already generated for the security hit read in the same job; serial |
| 8 | Unsupported behavior | API error, missing `rule-base/entry[@name='nat']` or context mismatch → NAT hits UNKNOWN; policy retained |
| 9 | Secret-output risk | rule names (sensitive identities); parse in memory; counters only persisted |
| 10 | Safe telemetry | `outcome`, `ruleCount` |

### P-2 Panorama config log, incremental

Exact text: `type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')`

| # | Field | Value |
|---|---|---|
| 1 | Why | G16: who/when evidence and change marker for Panorama DG revisions; documented parameters ([API Log Retrieval Parameters](https://docs.paloaltonetworks.com/pan-os/10-0/pan-os-panorama-api/pan-os-xml-api-request-types/retrieve-logs-api/api-log-retrieval-parameters)) |
| 2 | Class | 0 read |
| 3 | Vendor/platform/shell/context | palo_alto / **`panorama`** / not_applicable / `PAN_XML_API` (same literal as the approved firewall row `pan_syslog_collect_config_enqueue`, but a new canonical key by scope) |
| 4 | Timeout | 30 s |
| 5 | Retry | none |
| 6 | Max frequency | ≤ 4 pages per Panorama per job window; `<n>` digits 1–5000; `<ts>` matches `^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$` (stored cursor); max 8 MiB per page |
| 7 | Session reuse | the policy job's existing Panorama API key; serial |
| 8 | Unsupported behavior | API error or unfinished job → UNKNOWN; cursor unchanged; collection proceeds without the marker |
| 9 | Secret-output risk | admin user names, xpaths with DG/object names, before/after values; keep only time, seqno, opaque DG ref and command class; redact on write; masked for aiview |
| 10 | Safe telemetry | `entries`, `resultBytes`, `enqueueToFinMs`, `pages` |

### P-3 Panorama config log, seed (first run, no cursor)

Exact text: `type=log&log-type=config&nlogs=<n>`

| # | Field | Value |
|---|---|---|
| 1 | Why | establish the first cursor for P-2 |
| 2 | Class | 0 read |
| 3 | Vendor/platform/shell/context | palo_alto / `panorama` / not_applicable / `PAN_XML_API` |
| 4 | Timeout | 30 s |
| 5 | Retry | none |
| 6 | Max frequency | once per Panorama (first run only); `<n>` 1–5000; max 8 MiB |
| 7 | Session reuse | existing policy-job API key |
| 8 | Unsupported behavior | API error → UNKNOWN; no cursor stored |
| 9 | Secret-output risk | as P-2 |
| 10 | Safe telemetry | `entries`, `resultBytes`, `enqueueToFinMs` |

### P-4 Panorama log job result

Exact text: `type=log&action=get&job-id=<id>`

| # | Field | Value |
|---|---|---|
| 1 | Why | fetch the asynchronous result of P-2/P-3 ([Retrieve Logs](https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/retrieve-logs)) |
| 2 | Class | 0 read |
| 3 | Vendor/platform/shell/context | palo_alto / `panorama` / not_applicable / `PAN_XML_API` |
| 4 | Timeout | 30 s |
| 5 | Retry | none |
| 6 | Max frequency | every 2 s until FIN, at most 30 polls per enqueued job; `<id>` digits-only from the enqueue answer; max 8 MiB |
| 7 | Session reuse | existing policy-job API key; serial |
| 8 | Unsupported behavior | not FIN within the bound → UNKNOWN |
| 9 | Secret-output risk | as P-2 |
| 10 | Safe telemetry | `jobStatus`, `entries`, `resultBytes` |

### C-1 CP show-changes (per domain, changed domains only)

Exact text: `mgmt_cli -r true -d '<DOMAIN>' -f json show-changes from-date '<FROM>' to-date '<TO>' limit 10 offset '<N>'`

| # | Field | Value |
|---|---|---|
| 1 | Why | G16: which objects/rules were added, modified or deleted per published session, by uid and type; narrows re-reads. Parameters `from-date`/`to-date` (ISO 8601; server timezone if none), `limit` (sessions to analyze, default 10), `offset`; the reply is a task id ([Management API reference, show-changes](https://sc1.checkpoint.com/documents/latest/APIs/index.html#cli/show-changes~v1.9); present in v1.8 and v1.9) |
| 2 | Class | 0 read (creates a server-side diff task; no publish, no lock is documented. Whether it opens a write session or lock is UNKNOWN; measure once) |
| 3 | Vendor/platform/shell/context | check_point / `cp_multi_domain_server` / expert / `SSH_EXEC`; `<FROM>` = stored previous `publish-time` ISO; `<TO>` = current `publish-time` ISO; `MgmtCliCommands.quote` |
| 4 | Timeout | 60 s |
| 5 | Retry | none |
| 6 | Max frequency | only when `show-last-published-session` changed; once per domain per 6 hours; ≤ 20 offsets per run |
| 7 | Session reuse | existing policy SSH sessions (≤ 4, adaptive) |
| 8 | Unsupported behavior | error or no task id → domain collected fully (as today) |
| 9 | Secret-output risk | none in the immediate reply (task id only) |
| 10 | Safe telemetry | `outcome`, `seconds` |

### C-2 CP show-task (diff result)

Exact text: `mgmt_cli -r true -d '<DOMAIN>' -f json show-task task-id '<UID>' details-level full`

| # | Field | Value |
|---|---|---|
| 1 | Why | read the C-1 task: `tasks[].task-details[].changes[].operations.{added-objects, modified-objects{old-object,new-object}, deleted-objects}` with `uid`, `type`, `domain`, plus `session{session-uid, user-name, publish-time}` (same reference, `show-task` and the "show-changes between the dates" example) |
| 2 | Class | 0 read |
| 3 | Vendor/platform/shell/context | check_point / `cp_multi_domain_server` / expert / `SSH_EXEC`; `<UID>` = task id returned by C-1 |
| 4 | Timeout | 60 s |
| 5 | Retry | none |
| 6 | Max frequency | poll every 5 s until `status` is succeeded/failed, ≤ 24 polls per task |
| 7 | Session reuse | same session as C-1 |
| 8 | Unsupported behavior | failed/unfinished task or unknown shape → domain collected fully; revision recorded without change list |
| 9 | Secret-output risk | object names and admin `user-name`; keep only uid, type, operation and publish-time; drop names and user names in memory |
| 10 | Safe telemetry | `outcome`, `added`, `modified`, `deleted`, `polls` |

### C-3 CP NAT page amendment (conditional on Step 1b evidence)

Exact text: `mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 100 offset '<N>' details-level standard use-object-dictionary true`

| # | Field | Value |
|---|---|---|
| 1 | Why | G3: 2 packages / 10 jobs with NAT page failures, only if transcripts show a timeout or size cause; mirrors the V118 access-page budget |
| 2 | Class | 0 read |
| 3 | Vendor/platform/shell/context | check_point / `cp_multi_domain_server` / expert / `SSH_EXEC` |
| 4 | Timeout | 300 s |
| 5 | Retry | once on timeout with limit 50 (that literal is a further key; it is approved together with this row) |
| 6 | Max frequency | as `cp_policy_nat_rulebase`; ≤ 200 pages per package |
| 7 | Session reuse | existing policy SSH sessions |
| 8 | Unsupported behavior | failed page → NAT gap for that package; access rules retained |
| 9 | Secret-output risk | sensitive policy data; parse in memory |
| 10 | Safe telemetry | `outcome`, `ruleCount`, `pages` |

### Optional (measure before proposing for production)

- **C-4** `mgmt_cli -r true -d '<DOMAIN>' -f json show-application-site-groups limit 50 offset '<N>' details-level full dereference-group-members false`.
  Purpose: custom application-site group members. Parameters are documented (same reference). Size is UNKNOWN.
  Same ten-field profile as the V124 object reads (300 s, no retry, once per domain per 24 h).
- **P-5** Panorama-side hit counts, documented CLI `show rule-hit-count device-group <DG> pre-rulebase security
  rules all` ([Panorama: Monitor Policy Rule Usage](https://docs.paloaltonetworks.com/panorama/administration/manage-firewalls/device-monitoring-on-panorama/device-monitoring-on-panorama-monitor-policy-rule-usage)).
  Not recommended now: the firewall path already covers 40,917/40,932 rules, and the XML form and NAT coverage
  are UNKNOWN.

### Not new commands (no gate row)

- PAN NAT, default rules and extra object types: parse scope of `pan_policy_shared` / `pan_policy_device_group`.
- CP CHANGED_ONLY scheduling and the revision table: existing approved reads.
- Firewall-local PAN rules: stored effective-running artefacts, no device read.

## 7. Open questions for the PO

1. **CP resume.** The MDS is disabled in neXus, so CP policy has been stale for ~48 h. Re-enable after the crash
   investigation, and only together with Step 4a?
2. **PAN NAT parse scope.** Acknowledge that NAT, default rules and extra object types are parsed from the
   already-approved shared/DG reads, with no new command?
3. **Hit-count freshness.** For CP, is a daily hit refresh enough (4a), or must every 6-hour slot re-read the
   access rulebases (today's load)?
4. **Who/when evidence.** Approve C-1/C-2 and P-2/P-3/P-4 now, or start with count-only revisions (4b) and add
   change evidence later?
5. **Firewall-local PAN rules.** Show them alongside Panorama intent from stored effective-running (G13)?
6. **The 2026-10-10 06:00 → 2026-10-15 18:00 gap** with no discovery or policy runs: investigate as a separate
   item?
7. **CP NAT hits.** Accept as UNSUPPORTED (management API), or open a separate gateway-plane investigation?

## 8. UNKNOWN register (vendor semantics not settled by the cited documentation)

- PAN: the exact XML for NAT hit counts (`rule-base/entry[@name='nat']`). Panorama config-log field content
  for DG changes. Any API rate limit. The NAT response size per VSYS.
- CP: the maximum `limit` for `show-nat-rulebase`/`show-access-rulebase` (the spec gives defaults only). The
  default time window of `show-hits` without `hits-settings`. Whether `show-changes` opens a session or lock on
  the MDS. Whether a modified rule's diff names its layer. Exact R81.10/R81.20 API version on the MDS (v1.8/v1.9
  assumed per PO; not in the DB).
- Both: whether policy collection load is related to the MDS crashes.

---

## 9. Implementation record (lane `lane/pol-gates`, 2026-10-18)

Added with the approval. It records what exists in code after the PO's approval of §6 and keeps every §8 UNKNOWN as
UNKNOWN. Status of what is built: `IMPLEMENTED` and `AUTOMATED_VALIDATED` (fixtures and scripted transports only). Nothing
here is `REAL_ENV_VALIDATED`: no command has been sent to a device, and each reader's first run is the partial trial of the
decision record (§4 there), on one target.

### 9.1 What is built, and what is not

Built: the seven gate rows (migration V163; V162 belongs to another lane tonight), the revision and change-evidence tables, the
six readers' code paths (C-3 has none), the per-collection revision row, and their tests. Not built: plan steps 1 (diagnose
and fix), 2 (object types and inventory), 3a (PAN NAT and default-rule parse scope), 4a (changed-only scheduling for Check
Point). The plan's §7 questions are not answered by the approval. NAT hit counts are measured, not collected: nothing is
joined to a rule.

### 9.2 Commands, rows, code and trial settings

Every reader is **off unless its setting names its one trial target**; an unset, blank or malformed value is off. Each is
admitted through the policy job (claimed only inside the 00/06/12/18 window), refuses to start if the job started outside
the window (`JobWindowPolicy`), and shares the six-hour `policy_collection_domain` throttle for automatic jobs. None can fail
a policy collection: an error becomes a fixed-code note in the job transcript.

| Command | Gate id (V163) | Code | Setting (environment variable) | Trial target | Calls |
|---|---|---|---|---|---|
| P-1 | `pan_policy_nat_rule_hit_count` | `PanoramaPolicyCollector.measureNatHits`, `PanNatHitMeasure` | `UI2_POLICY_PAN_NAT_HITS_TRIAL_FIREWALL` | one firewall (device id), its first VSYS context | one call, plus the key generation the security hit read already uses |
| P-3, P-2, P-4 | `pan_policy_config_log_seed`, `pan_policy_config_log_enqueue`, `pan_policy_log_job_get` | `PanoramaConfigLogReader` (reuses `PanLogPage` and `PanLogRedactor`) | `UI2_POLICY_PAN_CONFIG_LOG_TRIAL_SOURCE` | one Panorama (device id) | seed once, then at most 4 pages per run, polls every 2 s, at most 30 per page |
| C-1, C-2 | `cp_policy_show_changes`, `cp_policy_show_task` | `CpShowChangesReader` | `UI2_POLICY_CP_SHOW_CHANGES_TRIAL_DOMAIN` | one domain (opaque container ref), only when its published-session marker changed | at most 20 offsets of `limit 10`; polls every 5 s, at most 24 per task |
| C-3 | `cp_policy_nat_rulebase_page100` | none | none | none | registered and signed off, conditional: no collector uses it until the Check Point lane shows the NAT timeout or size evidence. Its retry literal (`limit 50`) gets its own row when C-3 is first used |

The firewall P-1 call measures only: the answer is reduced to counts, presence booleans and a capped list of element names
(`PAN_NAT_HIT_MEASURE` in the job transcript); rule names and hit values are discarded in memory. The Panorama reader stores
evidence rows only (below). Both transport the policy job's own Panorama API key. `UI2_HOSTNAME_FINGERPRINT_KEY_FILE` is not
mounted in the policy pod at the time of the plan: without it the admin identity of the evidence is stored masked
(`masked:<length>`). **Update, PO decision 2026-10-18:** `deploy/ui2/57-policy-deployment.yaml` now mounts the existing
`ui2-hostname-fingerprint-key` Secret exactly as the worker deployment does, the policy role requires the key at start (a
missing variable, file or empty value stops the pod; no silent fallback to the masked form), and the evidence stores the
admin identity as an HMAC.

Relation to the Check Point changed-only trial (`UI2_POLICY_CP_CHANGED_ONLY_DOMAINS`, 24 h age limit): the two settings are
independent. `CpDomainReuse` is the single source of truth for domain reuse: the changed-only decision, the `POLICY_RUN_` and
`POLICY_DOMAIN_` step rows and the revision observation (`CpDomainReuse.record` and `CpDomainReuse.observe`) all read the same
per-domain state, so a domain the changed-only setting reads in full is observed as not reused, and one it reuses is observed
as reused with an equal marker.

### 9.3 Tables (V163)

- `policy_revision`: one row per (job, container), `change_state` CHANGED, UNCHANGED or UNKNOWN (with a fixed
  `unknown_reason`). The first observation of a container is UNKNOWN (`NO_PREVIOUS_MARKER`). A missing marker is UNKNOWN,
  never UNCHANGED. `revision_no` rises only on CHANGED. Counts that were not measured are NULL (UNKNOWN), never zero. The
  marker is a Check Point `{uid, publish_time, posix}` of `show-last-published-session`, or a Palo Alto `content_version`.
- `policy_change_evidence` and `policy_change_cursor`: redacted Panorama config-log entries and Check Point show-changes
  summaries; the cursor of the config-log reader. Only seqno or session uid, a time, an admin HMAC or masked form, shape-checked
  command and result classes, a path HMAC, an opaque device-group ref, counts and a capped list of `{uid, type, op}`. Never
  `before-/after-change-detail`, names or raw text. Retention of the three tables is UNKNOWN (not set by any decision). Every CHECK, PRIMARY KEY and UNIQUE constraint of
  V163 has an explicit, unique name (an inline column check is auto-named `<table>_<column>_check` by PostgreSQL, which the first live
  dry run collided with); the tests assert the names.

### 9.4 A finding that corrects §2.2 and §4b

§2.2 and §4b say `contentVersion` is the SHA-256 of the lineage XML. In the code it hashes the XML document that carries
`/config/shared` plus the device-group names and parents; the device-group entries are appended to a detached element and are
not part of the hashed document. It is therefore a within-job resume token for the shared part, not a per-group content hash.
The revision marker for a device group is a new token, `PanoramaPolicyCollector.lineageVersion`: the SHA-256 of the `shared`
XML and of each device group from the root down to the group, each with its name. Whether the Panorama answer is byte-stable
for an unchanged configuration is UNKNOWN until two consecutive runs are compared; if it is not stable, every group reads
CHANGED and the rule counts from `policy_rule_history` are the authority on whether a rule changed.

### 9.5 The §8 UNKNOWN register, as it stands in code

All still UNKNOWN. They appear as comments in `PolicyCompletenessGates`, `PanoramaConfigLogReader`, `CpShowChangesReader`,
`PanNatHitMeasure` and `PanoramaPolicyCollector.lineageVersion`, and in the `source_document_pointer` of the gate rows.

| UNKNOWN (§8) | How the code treats it |
|---|---|
| PAN NAT hit XML (`rule-base/entry[@name='nat']`) | one measurement call; the summary says whether a `nat` rule base was present (`natRuleBasePresent`); nothing is stored on a snapshot |
| Panorama config-log field content for DG changes | field names `admin`, `cmd`, `result`, `path` are the firewall measurement's; an absent or oddly shaped field becomes NULL, a path that names no device group gives no container ref |
| Any API rate limit | not assumed; the plan's bounds are the only limits |
| NAT response size per VSYS | not stored; the 8 MiB and 128 MiB response caps apply |
| CP maximum `limit` for NAT and access | not touched (C-3 unused) |
| Default window of `show-hits` | not touched |
| Whether `show-changes` opens a session or lock | the reader records its outcome only; the first run is the measurement |
| Whether a modified rule's diff names its layer | not used; a changed domain is still re-read in full |
| Exact R81.10/R81.20 API version | not assumed; the response shape of `show-task` is searched tolerantly and an unattributable change is counted, not stored |
| Whether policy collection load relates to the MDS crashes | not decided; the readers are off by default and bounded |
