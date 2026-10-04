# Katı Mod

Zip'i boş bir repo klasörüne aç (`.github` repo kökünde kalmalı), push et; Actions APK'yı üretir.
APK'yı kurunca: bildirim iznini ver → üstteki durum kartından 4 izni aç → uygulamaları seç → "Süre ata".
Güncelledikten sonra erişilebilirlik servisini bir kez kapatıp aç.

Davranış
- Süre dolunca "Yo big Harv Wait for me" bildirimi, 3 sn sonra tam ekran yemin ekranı.
- Yemin: günde en fazla 3 kez, her biri 10 dk. "Uygulamayı kapat" ana ekrana atar ve işlemi öldürür.
- Süreyi düşürmek hemen, artırmak/kaldırmak 24 saat sonra geçerli.
- Sınır varken Ayarlar/yükleyicide "Katı Mod" geçen ekranlar (kaldırma, servisi kapatma, izin alma) engellenir.
- Bölünmüş ekran ve yüzen pencerelerdeki uygulamalar da taranır.

Ayarlar: Prefs.kt (OATH, BYPASS_MIN, BYPASS_MAX_PER_DAY, GRACE_SEC, LOOSEN_HOURS)
