# Katı Mod

Zip'i boş bir repo klasörüne aç (`.github` repo kökünde kalmalı), push et; Actions APK'yı üretir.
Telefonda: Bildirim iznini ver → "İzinler" düğmesinden 4 adımı aç → uygulamaları ara/seç (çoklu) → "Süre ata".

- Süre dolunca: "Yo big Harv Wait for me" bildirimi gelir, 5 sn sonra yemin ekranı açılır (Prefs.GRACE_SEC)
- Yemin cümlesi aynen yazılırsa 10 dk izin (Prefs.BYPASS_MIN)
- Kullanım süresi olay kayıtlarından hesaplanır, 2 sn'de bir kontrol edilir
- NotifBlocker: süresi dolan uygulamaların bildirimlerini siler
