SecurityExpert'i corporate GitHub repository'ye taşımaya hazırlanıyoruz.

Henüz hiçbir dosyayı değiştirme.

AGENTS.md, CURRENT_STATE.md ve PRIVACY_AND_DATA_HANDLING.md kurallarını oku.

Amaç:
source repository'deki gerçek environment bağımlılıklarını ve
repository'ye taşınmaması gereken bilgileri tespit etmek.

data/, output/, logs/, CAS runtime artifacts içeriğini OKUMA.
Yalnız bunların repository boundary dışında tutulduğunu doğrula.

Source, tests, configuration templates ve documentation içinde ara:

- hard-coded Check Point Management endpoint
- hard-coded Panorama endpoint
- management/firewall IP adresleri
- gerçek hostname/device object names
- username/email
- internal domain/FQDN
- serial
- password/API key/private key/PSK/community/auth secret
- SSH fingerprints
- TLS certificate/private material
- gerçek environment'a ait test fixtures
- gerçek local filesystem/user paths
- environment-specific constants
- generated/runtime artifact references

Secret değerleri cevabında ASLA tekrar yazma.
Gerçek IP/hostname bulursan değeri gösterme; yalnız dosya:satır ve
classification bildir.

Sonra bunları sınıflandır:

A. source'tan runtime configuration'a taşınmalı
B. synthetic test fixture'a çevrilmeli
C. Git tarafından ignore edilmeli
D. documentation'da sanitize edilmeli
E. acceptable repository-safe

Sonuçta:
1. repository privacy findings
2. affected files
3. proposed runtime configuration architecture
4. credential-provider architecture
5. fixture sanitization plan
6. recommended .gitignore boundary
7. automated repository privacy gate önerisi
8. DEV.0 için implementation contract

üret.

Kod değiştirme.
Network collection çalıştırma.
Historical PHASE docs veya Continuation Pack'i gerekmedikçe okuma.