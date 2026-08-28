# PHASE 0.6.1C — Discovery Lifecycle + Capability Profile Foundation

**Status:** AUTOMATED_VALIDATED / REAL_ENV PREFLIGHT_BLOCKED
**Date started:** 2026-08-25  
**Product baseline entering this build:** 0.6.1B.1.6  
**Engineering baseline:** DEV.1 — Corporate Git + Copilot (ACTIVE)

---

## Objective

Deliver three explicitly separated but interdependent layers that make
collection safe enough for recurring scheduling:

1. **Discovery lifecycle** — management-led varlık güven/yaşam döngüsü
   kaydı.
2. **Capability profile + planner** — platform kimliği belirsiz kalsa
   bile kanıtlanmış read-only yeteneklere dayalı güvenli collection
   planı.
3. **Execution coordinator + limited scheduler** — aynı fiziksel cihaza
   eşzamanlı erişimi önleyen ve varsayılan olarak devre dışı olan
   sınırlı scheduler ile birlikte P0 güvenlik kapısı.

Bu build, yukarıdaki katmanları teslim etmeden önce zorunlu build
agreement ve durable plan kaydı aşamasını tamamlar. Metadata güncellemesi
zorunlu acceptance criterion olarak bu build içinde üç aşamada yapılır:
build start, kabul edilen alt teslimatlar ve build close.

---

## Frozen Architecture Decisions

| Karar | Sonuç |
|---|---|
| Scheduler kapsam | Sınırlı ve default-disabled; yalnızca onaylı mevcut read-only workflow'lar |
| Lifecycle/capability persistence | Mevcut `unified.json` sözleşmesini bozmayan ayrı, sürümlü, türetilmiş projection |
| Lock granülaritesi | Fiziksel endpoint münhasır; VSX VSID ve PAN VSYS yalnızca planlama context'i |
| Concurrency budget | Sabit, konservatif, vendor/context bazlı; artış gerçek ortam kanıtı gerektirir |
| Scheduler yapılandırması | Yalnızca RuntimeRoot policy; repo'da estate kimliği, credential veya gerçek zamanlama yok |
| Lock çakışması | Eşdeğer aktif job'a coalesce; ikinci cihaz bağlantısı açılmaz |
| Job provenance | `manual` ve `scheduled` aktif; `event` yalnızca şema rezervi, tetikleyici değil |
| Coordinator persistence | Tek-süreç, manifest/state bazlı; distributed lease/queue DEPLOY.1'e devredilir |
| Yeni device command | Kapsam dışı |
| Yeni vendor | Kapsam dışı |
| Webhook / event intake | Kapsam dışı |
| Write automation | Kapsam dışı |

---

## Definition of Done

Tüm aşamalar tamamlandığında build AUTOMATED_VALIDATED statüsüne geçer.
DONE yalnızca aşağıdaki human real-environment gate sonrası kabul edilir:

- Düşük kapsamlı, opt-in scheduled read-only job çalışır.
- Aynı endpoint için eşzamanlı manual/scheduled istek coalesce olur.
- Tek physical-session admission, doğru provenance, collector sonucu
  korunumu ve privacy sınırı doğrulanır.
- Concurrency ya da polling sıklığı artışı yoktur.

---

## Phase 0 — Build Agreement and Durable Plan State

Bu belge Phase 0 çıktısıdır.

Zorunlu başlangıç metadata güncellemeleri (2026-08-25):

- [x] `PHASE0_6_1C_...md` oluşturuldu (bu belge)
- [x] `CURRENT_STATE.md` güncellendi: 0.6.1C aktif build
- [x] `project/roadmap.json` güncellendi: 0.6.1C → now
- [x] `project/backlog.json` güncellendi: coordinator + lifecycle → in_progress
- [x] `project/feature_registry.json` güncellendi: coordinator + discovery → in_progress
- [x] `project/build_history.json` güncellendi: 0.6.1C in_progress girişi eklendi

---

## Phase 1 — Discovery Lifecycle and Capability Profile

**Teslim edilecek:**

1. `utils/discovery_lifecycle.py` — Varlık lifecycle state machine.
   Tüketici: management/direct evidence sonuçları.
   Lifecycle durumları: `DISCOVERED`, `VALIDATED`, `STABLE`,
   `EXCLUDED`, `REMOVED`.
   Zorunlu alanlar: confidence, evidence plane, first/last observed
   timestamp, transition reason code.
   Platform kimliği belirsizse `UNKNOWN` kalır; capability gözlemi
   platform sınıflandırması yerine geçmez.

2. `utils/capability_registry.py` — Vendor + fiziksel endpoint +
   evidence context anahtarlı capability profili.
   Ayrı alanlar: platform identity, shell türü (expert / direct-clish /
   unknown), direct collection imkânı, VSX/VSYS context, confidence.
   CP: Expert + explicit `clish -c`, direct-Clish capability-only (Spark
   kimliği değil), VSX `vsenv <VSID>` context.

3. Collection planner — capability registry ve lifecycle projection
   tüketen, güvenli collection planı veya açık skip/defer reason üreten
   saf fonksiyon.
   ClusterXL standby üyesini gereksiz early-login önce suppress eder;
   identity gate ve `MEMBER_SPECIFIC` semantiği korunur.

**Privacy invariantları:**

- Hiçbir lifecycle veya capability kaydına raw configuration, credential,
  gerçek device/network kimliği yazılmaz.
- `InventoryExclusionPolicy` yaklaşımı korunur: runtime-only, vendor-
  aware, exact-match; eşleşen kimlik log'a yazılmaz.
- Browser/export payload'larına transport transcript girmez.

**Bağımlılıklar:**

- Mevcut `unified.json` sözleşmesi kırılmadan genişletilir.
- `InventoryExclusionPolicy` (utils/inventory_exclusions.py) yeniden kullanılır.
- CP kural: Expert → explicit `clish -c`; direct-Clish ≠ Spark identity.

---

## Phase 2 — Collection Execution Coordinator + Limited Scheduler

**Ön koşul:** Phase 1 lifecycle/capability projection şeması dondurulmuş olmalı.

**Teslim edilecek:**

1. `utils/collection_executor.py` — Tüm collection erişimlerinin merkezi
   admission boundary'si.
   - Fiziksel endpoint mutex lock.
   - Vendor/context concurrency token bucket (CP / PAN / VSX ayrı).
   - Mevcut `SECURITYEXPERT_CP_STAGE_COOLDOWN_SECONDS` ile uyumlu.
   - Lock çakışması → coalesce; ikinci bağlantı açılmaz.
   - Bounded cancellation + timeout-only retry.
   - Privacy: job metadata'ya secret, raw target veya transport
     transcript yazılmaz.

2. `utils/run_context.py` genişletilir:
   - `job_id`, `provenance` (`manual` | `scheduled`), `effective_scope`,
     `coordinator_decision`, `coalesced_to` alanları manifest'e eklenir.
   - Hiçbiri gizli değer içermez.

3. `main.py` entegrasyonu:
   - Coordinator admission, tüm collector çağrılarından önce gelir.
   - Tüm mevcut partial mode davranışları (`--only`, `--render-only`,
     `--cp-config-collect`, vb.) korunur.
   - Varsayılan polling hızı veya concurrency değişmez.

4. CP ve PAN runner entegrasyonu:
   - `checkpoint/cp_runner.py`, `checkpoint/direct_ssh_probe.py`,
     `checkpoint/vsx_runner.py`,
     `configuration/checkpoint_config_collector.py`,
     `panorama/panorama_runtime_runner.py`,
     `configuration/panorama_config_collector.py` — fiziksel device
     admission için ortak coordinator API'sini kullanır.
   - Vendor-native komut semantiği değiştirilmez.

5. Limited scheduler:
   - RuntimeRoot policy yoksa başlamaz (default disabled).
   - Yalnızca allowlist'teki mevcut read-only workflow'ları tetikler.
   - Hatalı policy → fail before network access.
   - Eşdeğer aktif job varsa coalesce → skip-with-reason.
   - Provenance: `scheduled`.
   - Event/webhook intake eklenmez.

---

## Phase 3 — UI and Safe Observability

**Ön koşul:** Phase 1 ve Phase 2 şemaları dondurulmuş.

**Teslim edilecek:**

- Lifecycle, capability confidence, planlanan collection mode ve
  coordinator/coalescing sonucunu gösteren additive payload alanları.
- Mevcut Inventory, Configuration ve Compliance master-detail
  sözleşmeleri bozulmaz.
- `UNKNOWN`, excluded reason, deferred collection ve safety outcome
  açıkça gösterilir.
- Browser/export payload'larına raw configuration, credential, gerçek
  network identity veya transport transcript girmez.

---

## Phase 3 — UI and Safe Observability

**Status:** DONE (2026-08-26)

Teslim edilenler:

- `utils/discovery_capability_ui.py` — Lifecycle/capability/coordinator/
  scheduler durumunu sanitized bir payload'a projekte eden saf fonksiyon.
  Store'lar boşsa açık empty-state döner (Phase 4 entegrasyonu bekleniyor).
- `utils/collection_executor.py`: `DEFAULT_CONCURRENCY_BUDGETS` public alias
  ve `CollectionCoordinator.budget_snapshot()` eklendi (secrets-free).
- `utils/html_export.py`: `lifecycle_store`, `capability_store`,
  `coordinator`, `scheduler_policy` opsiyonel keyword-args olarak eklendi;
  mevcut çağrılar etkilenmedi.
- `templates/index.html`: yeni "Discovery" nav modülü + `__DISCOVERY_JSON_PLACEHOLDER__`.
- `static/app.js`: `renderDiscoveryModule()`, `lifecycleStateTone()`,
  `jobStatusTone()`; mevcut `switchModule`/`savedModule` listelerine additive.
- `static/style.css`: generic `.table-wrap` / `.data-table` sınıfları.
- `tests/test_phase0_6_1c_discovery_capability_ui.py`: 14 test (payload,
  privacy/no-leak, UI marker contract, end-to-end render smoke test).

Doğrulama: `104 passed, 1 skipped` (Phase 1+2+3 birleşik targeted koşum).

---

## Phase 4 — Validation and Acceptance

**Test matrisi:**

### Lifecycle testleri
- Lifecycle state transition (tüm geçerli geçişler)
- Geçersiz transition reddi
- Evidence/confidence merge
- Exclusion (reason code korunumu)
- Pre-0.6.1C snapshot backward uyumluluğu

### Capability / planner testleri
- Expert + explicit Clish planı
- Direct-Clish + bilinmeyen platform (Spark identity değil)
- VSX physical endpoint + VSID
- ClusterXL standby suppress
- PAN VSYS context
- Identity failure → planner defers
- Unknown capability → UNKNOWN, not guessed

### Coordinator testleri
- Atomic physical lock (eşzamanlı invocation altında)
- CP / PAN / VSX budget izolasyonu
- Lock çakışması → coalesce, kayıt, ikinci bağlantı yok
- Cancellation edge case
- Timeout-only retry (auth/identity hataları retry edilmez)
- Cooldown uyumluluğu (SECURITYEXPERT_CP_STAGE_COOLDOWN_SECONDS)
- Malformed RuntimeRoot policy → fail before network access
- Default-disabled scheduler (policy olmadan iş üretmez)
- Manifest provenance/redaction (gizli değer yok)

### Referans altyapı
- `tests/test_phase0_3_run_context.py`
- `tests/test_phase0_4_2_parallel_cp.py`
- `tests/test_dev0_4_1_inventory_exclusions.py`
- `tests/test_known_safety_gaps.py`

**Automated acceptance commands:**

```powershell
py -m pytest -q tests/test_phase0_6_1c_discovery_lifecycle.py tests/test_phase0_6_1c_collection_executor.py
py -m pytest -q  # full regression
python -B main.py --render-only
```

**Human real-environment gate (DONE için zorunlu):**

```powershell
python -B main.py --runtime-root <path>
```

Doğrulama gözlemleri:
1. Scheduler RuntimeRoot policy olmadan iş üretmez.
2. Opt-in scheduled job çalışır ve provenance `scheduled` olarak kaydedilir.
3. Aynı endpoint'e eşzamanlı istek coalesce olur; ikinci bağlantı açılmaz.
4. Collector sonuçları korunur.
5. Privacy boundary: manifest, payload ve log'da raw config/secret/kimlik yok.
6. Toplam collection frekansı veya concurrency artmamış.

---

## Phase 4 — Validation and Acceptance

**Status:** DONE (2026-08-26)

Automated validation (scoped):

```text
tests/test_phase0_6_1c_discovery_lifecycle.py
tests/test_phase0_6_1c_collection_executor.py
tests/test_phase0_6_1c_discovery_capability_ui.py

Result: 91 passed, 1 skipped, 1 warning in 0.75s
```

Tüm test matrisi kapsanmıştır:
- Lifecycle: state transitions, geçersiz geçiş reddi, evidence/confidence merge, exclusion reason korunumu, backward compat.
- Capability/planner: Expert + explicit Clish, direct-Clish/unknown platform, VSX physical+VSID, ClusterXL standby suppress, PAN VSYS, identity-failure defers, unknown capability → UNKNOWN not guessed.
- Coordinator: atomic physical lock, CP/PAN/VSX budget izolasyonu, coalesce/kayıt/ikinci-bağlantı-yok, cancellation, timeout-only retry, cooldown uyumu, malformed policy → fail before network, default-disabled scheduler, manifest provenance/redaction.
- UI: payload, privacy/no-leak, UI marker contract, end-to-end render smoke.

Real-environment gate: **PREFLIGHT_BLOCKED** (build DONE için zorunlu).

2026-08-26 contract-preparation audit correction:

- Coordinator/scheduler APIs and their tests are present, but repository-wide
  usage does not demonstrate production runtime admission or scheduler
  dispatch from `main.py`/collector paths.
- `utils/discovery_capability_ui.py` still explicitly describes empty-state
  operation until real collectors are wired.
- The previously documented normal `main.py` command cannot prove scheduled
  provenance or same-process coalescing and must not be accepted as 0.6.1C
  real-environment evidence.
- Authoritative validation/preflight agreement:
  `PHASE0_6_1C_REAL_ENVIRONMENT_VALIDATION.md`.
- If the 10/10 preflight confirms missing wiring, bounded `0.6.1C.1 — Runtime
  Admission + Scheduler-One-Shot Wiring Closure` is required before network
  validation.

---

## Phase 5 — Mandatory State Closure

**Status:** DONE (2026-08-26)

Tamamlanan güncellemeler:

1. Bu belge: Faz 4 ve 5 sonuçları yazıldı.
2. `CURRENT_STATE.md`: product baseline → 0.6.1C (AUTOMATED_VALIDATED), next build → 0.6.1D.
3. `project/roadmap.json`: now_next.now.status → automated_validated.
4. `project/backlog.json`: collection_execution_coordinator, discovery_lifecycle → automated_validated.
5. `project/feature_registry.json`: discovery_capability_foundation, collection_execution_coordinator → automated_validated.
6. `project/build_history.json`: 0.6.1C → automated_validated, summary tamamlandı.

---

## Kapsam Dışı (Explicit Exclusions)

- Device write/change otomasyonu
- Yeni vendor veya yeni device command
- Webhook / event intake
- Dağıtık scheduler, queue, lease veya HA lock
- Credential-vault değişikliği
- Concurrency ya da polling sıklığı artışı
- Raw configuration ve gerçek kimliğin normal payload/export'a taşınması
- PAN TLS CA veya CP SSH host-key trust üretim provisioningi

---

## Delivery Dependency Graph

```
Phase 0 (build agreement + metadata)
    └── Phase 1 (lifecycle + capability + planner)
            ├── Phase 2 (coordinator + scheduler)  ← schema freeze gerekli
            └── Phase 3 (UI)  ← schema freeze gerekli
                    └── Phase 4 (validation)
                            └── Phase 5 (state closure)
```

---

## References

- `SESSION_HANDOVER_0_6_1B_1_3_SAFETY_AUDIT_AND_DEPLOY_1_ARCHITECTURE.md`
  — CP device interaction safety findings + DEPLOY.1 architecture
- `utils/run_context.py` — manifest/stage tracking contract (extend here)
- `utils/inventory_exclusions.py` — exclusion policy pattern (reuse here)
- `checkpoint/cp_runner.py`, `direct_ssh_probe.py`, `vsx_runner.py`,
  `configuration/checkpoint_config_collector.py` — CP admission points
- `panorama/panorama_runtime_runner.py`,
  `configuration/panorama_config_collector.py` — PAN admission points
- `AGENTS.md` §"Engineering laws" — safety and privacy invariants
- `.github/instructions/checkpoint.instructions.md` — CP command gate
