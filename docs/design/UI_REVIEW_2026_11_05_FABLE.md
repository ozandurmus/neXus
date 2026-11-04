**Fable ikinci görüş: neXus UI incelemesi (Argon'un 2026-11-05 raporu üzerine)**

Argon'un raporunu (bölüm 1-5) ve listelenen 16 ekran görüntüsünü inceledim. Genel kanı: Argon'un teşhisi doğru. Ürün temiz ama her ekran kendi muhasebesini ve parser kurallarını ana tuvale basıyor. Sorun veri miktarı değil, veri önceliği. Aşağıda madde madde kararım var.

## Argon'un maddelerine karar

**R-15 + Bölüm 3, yönetici ana sayfası: Katılıyorum, değişiklikle.** Tazelik bandı (öğe 1) ve "Şimdi dikkat gerektirenler" listesi (öğe 3) doğru çekirdek. Bugünkü Overview (06) 97 kırmızı kareyi "20 dk önce okundu" alt yazısıyla sunuyor, oysa 132 cihazın 131'i 24 saatten eski. Değiştireceklerim: altı öğe hâlâ fazla. Öğe 5 (çerçeve bazlı uyum çubukları) ve öğe 6 (24 saatlik değişim nabzı) katlanmanın altına insin veya tek bir "eğilim" şeridine birleşsin. Öğe 1'in başına tek kelimelik bir hüküm gelsin: SAĞLIKLI / RİSKLİ / BİLİNMİYOR. Öğe 3 en fazla beş satır olsun, önem sırasına göre, her satırda yaş bilgisi bulunsun. Dört sütunun "+24 s delta" fikri iyi ama günlük anlık görüntü saklanıyor mu, ekrandan belli değil: UNKNOWN.

**R-03, büyük yapılandırma değişikliklerini Backups'tan taşımak: Katılıyorum, değişiklikle.** 17'de 21 satırlık liste yedek tablosunu tamamen katlanmanın altına itmiş, bu doğru tespit. Ancak PO Ekim'de "yedek sapmalarını" bu sayfaya özellikle istedi ve bu sapmalar ardışık yedekler arasındaki farklardan türetiliyor. Yani ait oldukları yer Backups. Önerim: "Aktif büyük sapmalar 36" kartı kalsın, 21 satırlık liste "Hangi cihazlar →" ile açılan çekmeceye veya tablo filtresine dönüşsün. Dikkat: 21 satırın tamamı aynı dakikada "Admin access users değişti" diyor. Bu tek bir olay. Tek satırda gruplanmalı: "21 cihaz, aynı anda, yönetici kullanıcıları". Change feed'e kopya göndermeye gerek yok. "47 s önce" eskimiş rozeti: katılıyorum.

**R-09, Delivery plan'ı Administration'dan kaldırmak: Katılmıyorum.** PO bu sayfayı kullanıyor (32). Bir Build pilinin arkasına saklamak keşfedilebilirliği öldürür. Yerinde kalsın, ancak "RECORDS" altından çıkıp kendi "PRODUCT" başlığına geçsin ve yalnızca PO/mühendis rollerine görünsün. Müşteri yöneticisi için gizlenir, PO için bir tık uzakta kalır.

**R-10, yönetici sayfalarını aiview'dan gizlemek: Kısmen katılmıyorum.** aiview PO'nun ve ajanların denetim gözüdür. Sayfaları gizlemek denetimi köreltir. Argon'un alternatifi doğru olan: sayfa iskeleti kalsın, içeriği "kısıtlı" StatePanel ile gösterilsin, böylece gözle yapı doğrulanabilir. Yazma düğmelerini görsel olarak kilitlemek (07'deki beş düğme, 17'deki "Run Fleet Backup", 21'deki "+ Add device"): katılıyorum. Üst bardaki "Compliance Admin" alt başlığı: katılıyorum, yanıltıcı.

**R-16, Policy 7 sekmeden 4'e: Katılıyorum.** 09 ve 12 aynı 59 satırlık konteyner tablosunu iki kez gösteriyor; Argon 10 ve 11 için de aynısını raporluyor. Hygiene'in seçimsiz hâlinin tahmin bazlı bulgu özeti olması en değerli parça: uzman 59 konteyneri tek tek açmak zorunda kalmamalı. Installation, Rules içinde bir sütun ve durum pili olmalı. Tek şart: Lookup taşınırken Policy içinde "bu politikada ara" derin bağlantısı kalsın. 09'daki her satırdaki yeşil nokta da kaldırılmalı, saklanmış anlık görüntü nötr bir olgudur.

**R-20, Path ve Lookup'ı TRACE altında birleştirmek: Katılıyorum, değişiklikle.** 14 ve 16 aynı dörtlüyü (kaynak, hedef, servis, port) iki ayrı yerde soruyor. Birleşsin ama iki mod ayrı hüküm anlamı taşımalı: Path topoloji üzerinde atlama atlama, Lookup tek bir anlık görüntüde kural eşleşmesi. Menü etiketi "Path & Lookup" uzun, "Trace" yeterli. 16'daki iki satırlık mavi maskeleme kutusu tek satır pile insin, hız sınırı notu tooltip'e. Gelişmiş alanlar (ingress birimi, arayüz, bölgeler) açılır bölümün arkasına: katılıyorum.

**R-21 ve R-22, Diagnostics/Offline analysis Tools'a, Administration 14'ten 4'e: Katılıyorum, değişiklikle.** 02 ve 34 birebir aynı tablo, Job Logs yönlendirme olmalı. Tek cihazlık araçların Tools altında toplanması mantıklı. Dört grup doğru, ama Delivery plan için yer ayrılmalı (bkz. R-09) ve kısıtlı sayfalar gizlenmek yerine kilitli gösterilmeli (bkz. R-10). Efor tahmini M yerine L: yönlendirme, e2e seçicileri ve rol matrisi değişiyor.

**R-18, halka grafikler yerine sıralı çubuklar: Katılıyorum, değişiklikle.** 07'deki altı halkanın ortasında altı kez "60" ve "39" yazıyor; grafik hiçbir şey söylemiyor ve etiketler kesiliyor. Sıralı yatay çubuklar doğru. Eklenecek şey: yöneticinin sorusu "kaç cihaz eski veya desteklenmeyen sürümde?" Bu nedenle her satıcı kartının üstüne tek bir hüküm satırı gelsin: "N cihaz birden fazla ana sürümde, en eski Take X". Yedi satıcının tamamı kapsanmalı.

**Bölüm 2.2 hedef mimari: Katılıyorum, değişiklikle.** Omurga doğru. İki düzeltme: (a) Değişiklikler hâlâ iki yerde kalıyor: Change feed Operations altında, Policy › Changes Policy altında. TRACE başlığı Path & Lookup'ın yanına birleşik bir "Changes" girdisi de almalı; "son 24 saatte ne değişti" tek yerden yanıtlanmalı. (b) Delivery plan ve kilitli sayfalar yukarıdaki gibi.

## Argon'un gözden kaçırdıkları

1. **Uyum manşeti puanlama artefaktı (08, yönetici gözü).** "%30,1 geçiyor" rakamı kanıtı olmayan kontrolleri başarısız sayıyor; kanıtlı kontrollerde oran %37,5 ve %20 bilinmiyor. Yönetici %30'u bankanın duruşu olarak okur. Manşet "kanıtlı kontrollerin %37,5'i geçiyor, %20 kanıt yok" olmalı. Ayrıca banka için birincil çerçeve CIS değil Financial Baseline olmalı, kart sırası buna göre değişmeli.
2. **Yönetici teslimatı yok (06, 08).** Tek dışa aktarım "Export Audit Report" (08). Overview'dan tek tıkla haftalık yönetici özeti (PDF/e-posta) yok. Audit raporunun içeriği UNKNOWN.
3. **Tek tazelik ölçeği yok (01, 06, 07, 40).** 07 "24 s altı / 24-72 / 72 üstü" dilimleri kullanıyor, 01 "30 saat" bandı, 40'ın başlığı "Readiness Ready · 31 s önce" yeşil pil. Aynı yaş üç ekranda üç farklı renk. Ürün genelinde tek eşik seti tanımlanmalı ve her pil ona uymalı.
4. **ObjectHeader pil satırı ürünün en iyi özeti ama saklanıyor (40, 42, 43).** "Compliance 2 critical failing · Config unchanged · Members compared · differ · Backup M1 47 s · Readiness Ready" satırı tek bakışta beş düzlemi veriyor. Bu desen sol cihaz ağacına ve 01'deki HA tablosuna taşınmalı. Ayrıca "Members compared · differ" bir bulgudur, gri değil amber olmalı.
5. **Sol cihaz ağacı şişkin (07, 40).** VS/VSYS çocukları varsayılan açık; 130 cihaz ve 45 kümede ağaç yüzlerce satıra uzuyor. Çocuklar kapalı başlasın, sayı rozetle gösterilsin ve ağaca "yalnızca sorunlular" hızlı filtresi eklensin.
6. **Geliştirici telemetrisi üst barda (tüm ekranlar).** "Build … · deployed 2h ago" ve "Times in GMT+3" yöneticinin gözünün önünde. Kullanıcı menüsüne veya alt bilgiye insin.
7. **İş başarısızlıkları sebebe göre gruplanmıyor (02, 34).** 939 ömür boyu başarısız iş var, ama en sık sebep nedir görünmüyor. "Failed" çipi varsayılan 7 güne insin ve altında "en sık 5 sebep" listesi olsun. "Live polling" anahtarı zamanlama kapalıyken yanıltıcı.
8. **Sözlük mühendis dili (06, 09, 17).** "Evidence", "readiness records", "containers", "artefact store". Yönetici katmanı için düz kelimeler gerek: "veri tazeliği", "yedek kapsamı", "politikalar". Uzman ekranlarında teknik terim kalabilir.

## Benim ilk 10'um (öncelik sırasıyla)

| # | Madde | Etki | Efor |
|---|---|---|---|
| 1 | R-01 + benim 3: eskimiş kanıt bandı ve tek tazelik ölçeği | H | S |
| 2 | R-02: takılı yükleyiciler (statik görüntüden doğrulanamaz, UNKNOWN; Argon canlı gördü) | H | S |
| 3 | R-15 değişiklikli: yönetici ana sayfası, hüküm kelimesi + dikkat listesi | H | M |
| 4 | R-03 değişiklikli: yedek tablosu üstte, sapmalar çekmecede, 47 s rozeti | H | S |
| 5 | R-16: Policy 4 sekme, tahmin bazlı hijyen özeti | H | M |
| 6 | R-11: KPI kartları ve başlık düğmeleri yalnızca ilgili sekmede | H | S |
| 7 | Benim 1: uyum manşeti dürüstlüğü, Financial Baseline birincil | H | S |
| 8 | R-06 + R-04: Members/Identity birleşmesi, ölü tıklar, yanlış yeşil | M | S |
| 9 | R-20 değişiklikli: Trace altında iki modlu Path/Lookup | M | M |
| 10 | R-22 + R-21 + R-08: Administration 4 grup, Tools birleşmesi, Job Logs yönlendirme; Delivery plan korunur, kısıtlı sayfalar kilitli gösterilir | M | L |

Özet: Argon'un 26 maddesinin büyük kısmı doğru ve çoğu küçük efor. Üç yerde ayrışıyorum: Delivery plan kalmalı, aiview'dan sayfa gizlenmemeli, yedek sapmaları Backups'ta kalmalı. Argon'un görmediği en büyük boşluk, ürün genelinde tek bir tazelik ölçeğinin ve yöneticiye dürüst bir uyum manşetinin olmaması.
