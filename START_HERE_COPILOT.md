# START_HERE_COPILOT.md

Copilot ile tamamen Türkçe devam et.

## Her yeni chat

1. `AGENTS.md` oku.
2. `CURRENT_STATE.md` oku.
3. `.github/copilot-instructions.md` kurallarını uygula.
4. Yalnız task için gerekli current `project/*` metadata'yı oku.
5. İlgili source/tests'i dar arama ile bul.
6. Historical PHASE docs ve Continuation Pack'i varsayılan olarak okuma.
7. `data/`, `output/`, `logs/`, CAS ve sensitive runtime artifact'lerini tarama.

## İlk cevap

Kod değiştirmeden önce `SESSION START` üret:

- product baseline,
- engineering baseline,
- build/task,
- movement type,
- scope/out-of-scope,
- gerekli source/tests,
- invariants/risks,
- bilerek yüklenmeyen context,
- reasoning/model önerisi,
- önerilen Git lane (feature/*, build/*, veya doğrudan main hotfix) ve gerekçesi,
- main'e merge gate (hangi kanıtlar tamamlanmadan merge yapılamaz),
- bu chat için deploy yönü (local validation only, staging-like, production-gated),
- Definition of Done.

## Build boyunca

`SCOPE → AUDIT → CONTRACT → IMPLEMENT → TARGETED_TEST → REGRESSION → HUMAN_REAL_ENV → STATE_UPDATE → HANDOVER`

Dar deterministic fixlerde contract kısa olabilir. Güvenlik/storage/vendor
semantics/deployment işlerinde architecture gate'i atlama.

## Build sonunda

Durable state'i güncelle ve `SESSION CLOSE` üret. Yeni chat'in geçmiş konuşmayı
bilmeden yalnız repository'den devam edebilmesi gerekir.

`SESSION CLOSE` içinde zorunlu olarak önerilen branch/PR hedefi, main merge
kararı (approved/blocked) ve exact non-interactive Git dispatch komutlarını
(stage/commit/push/PR base) ver.

Her build kapanışında ayrıca zorunlu olarak `main.py/UI etkisi` notu ver:

- normal çalıştırmada operatör arayüzde ne görmeli,
- değişiklik backend-only ise UI doğru render ediyorsa görünür fark
  beklenmemesi gerektiği.

## Handover standardı (zorunlu)

- Bu workspace'in VS Code/Python/PowerShell ortamı hazır ve doğrulanmıştır.
  `configure_python_environment`, interpreter seçimi, venv oluşturma veya
  environment-bootstrap ekranını hiçbir yeni chat'te çağırma. Mevcut `py`
  komutunu doğrudan kullan. Komut gerçekten başarısız olursa hatayı raporla ve
  testi pending bırak; kullanıcı aynı chat'te açıkça istemedikçe environment
  yapılandırma.
- Tekrarlı test koşusu yerine **tek atım log yöntemi** kullan:
  - `py -m pytest -q > pytest_result.log 2>&1`
  - Log'u `Get-Content pytest_result.log -Encoding Unicode -Tail 40` ile oku.
- Bir build kapanışında aynı full suite'i gereksiz tekrar etme; mevcut log kanıtı
  yeterliyse yeni koşu açma.
- Handover çıktısında test kanıtını dosya referansı ile ver: `pytest_result.log`.
- Handover çıktısında `main.py/UI etkisi` satırı ekle; arayüzü bozmama
  hedefini açıkça doğrula.
- Yeni chat'e geçerken önceki env/PATH detaylarını tekrar isteme; sadece gerçek
  çalışma hatası varsa bir kez düzelt ve state'e yaz.

## Model kullanımı

- normal audit/implementation: Sol veya eşdeğer güçlü normal model,
- küçük validation/docs: daha düşük maliyetli model/normal reasoning,
- security/storage/CAS/deployment/cross-subsystem architecture: Terra High veya
  eşdeğer yüksek reasoning,
- architecture kilitlendikten sonra mekanik implementation için tekrar normal
  modele dön.

Her önemli adım sonunda sonraki movement type + reasoning seviyesini öner.
