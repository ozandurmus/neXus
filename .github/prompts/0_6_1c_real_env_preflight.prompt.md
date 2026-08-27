---
name: "0.6.1C Real-Environment Preflight"
description: "0.6.1C gerçek ortam doğrulamasından önce production coordinator/scheduler wiring kapısını fail-closed denetle"
argument-hint: "Preflight'i denetle; ağ erişimi veya implementation yapma"
agent: "agent"
---

0.6.1C gerçek ortam doğrulama kontratını devral.

Önce [AGENTS.md](../../AGENTS.md), [CURRENT_STATE.md](../../CURRENT_STATE.md),
[build contract](../../PHASE0_6_1C_REAL_ENVIRONMENT_VALIDATION.md) ve yalnız ilgili
current metadata/source/tests dosyalarını oku.

Aktif movement `ARCHITECTURE / ROOT_CAUSE` ve kapsam yalnız Section 5 production-
wiring preflight denetimidir.

Zorunlu davranış:

- P01–P10 kontrollerini gerçek production call path üzerinden kanıtla.
- Unit test veya mock-only kullanımını production wiring kanıtı sayma.
- Coordinator/scheduler sembollerinin `main.py` ve gerçek network-session
  opener'larına ulaşan çağrı zincirlerini göster.
- Runtime/sensitive dizinleri okuma.
- Network collection çalıştırma.
- Kaynak kodu değiştirme.
- Eksik wiring varsa `PREFLIGHT_BLOCKED` de ve en küçük 0.6.1C.1 implementation
  dosya/test kapsamını öner; davranış uydurma.
- Wiring tam ise 10/10 value-free preflight sonucu üret ve sonraki adımı
  `VALIDATION` olarak öner.

Çıktıda ver:

1. P01–P10 PASS/FAIL ve güvenli kısa gerekçe,
2. production call-path özeti,
3. blocker ve riskler,
4. önerilen bir sonraki movement,
5. implementation gerekiyorsa GPT-5.6 Sol / normal-strong reasoning handoff
   kapsamı,
6. `main` merge kararının blocked/eligible durumu.

Bu preflight için Terra High veya eşdeğer en güçlü onaylı modeli high reasoning
ile kullan. Kontrat deterministik hale geldikten sonra mekanik implementation'ı
GPT-5.6 Sol normal-strong reasoning'e devret.
