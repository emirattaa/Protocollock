# Katı Mod

Zip'i boş bir repo klasörüne aç (`.github` repo kökünde kalmalı), push et; Actions APK'yı üretir.
Telefonda: İzinler düğmesinden 3 izni aç → uygulamaları ara/seç (çoklu) → "Süre ata".

- Tümü / Sınırlananlar sekmeleri, arama, uygulama logoları, çoklu seçim
- GuardService: süre dolunca uygulamanın üstüne yemin ekranı çizer (overlay)
- Yemin cümlesi aynen yazılırsa 10 dk izin (Prefs.kt'de değişir)
- NotifBlocker: süresi dolan uygulamaların bildirimlerini siler
