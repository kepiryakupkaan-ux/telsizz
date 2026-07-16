# Telsiz (Walkie-Talkie) Uygulaması

Aynı WiFi ağındaki iki Android telefonun birbirini otomatik bulup, bas-konuş (PTT)
usulüyle konuşmasını sağlayan basit bir uygulama. Arka planda çalışır; uygulamayı
kapatsan bile dinleme aktif kalır ve ekranda kayan bir "KONUŞ" butonuyla konuşabilirsin.

## Nasıl çalışır?

1. **Keşif**: Her telefon 2 saniyede bir yerel ağa (UDP broadcast, port 8888) "buradayım"
   mesajı yollar. Diğer telefon bunu duyunca IP adresini öğrenir. Manuel IP girmene gerek yok.
2. **Ses**: Konuş butonuna bastığın sürece mikrofon sesi ham PCM formatında UDP paketleri
   halinde (port 8889) karşı cihaza gönderilir ve orada anında çalınır.
3. **Arka plan**: Uygulama bir Foreground Service (bildirimli arka plan servisi) çalıştırır,
   bu yüzden uygulamayı kapatsan/ekranı kilitlesen bile servis çalışmaya devam eder.
4. **Kayan buton**: "Diğer uygulamaların üzerine çizme" izni verirsen, ekranın üstünde
   her yerde sürükleyebileceğin yeşil bir KONUŞ butonu belirir — uygulamayı açmadan da kullanılır.

## Kurulum (Android Studio ile)

Bu klasör tam bir Android Studio projesidir.

1. Android Studio'yu aç → **Open** → bu `WalkieTalkie` klasörünü seç.
2. Gradle senkronizasyonunun bitmesini bekle (ilk açılışta sürüm güncelleme önerebilir,
   kabul edebilirsin).
3. Telefonunu USB ile bağla (Geliştirici Seçenekleri + USB Hata Ayıklama açık olmalı)
   veya bir emülatör kullan.
4. **Run ▶** butonuna bas, iki telefona da kur.
5. Uygulama ilk açıldığında mikrofon, bildirim ve "üzerine çizme" izinlerini onayla.
6. İki telefon da aynı WiFi ağına bağlıyken birkaç saniye içinde "Bağlandı: ..." yazısını göreceksin.
7. KONUŞ butonuna basılı tut, konuş, bırak.

## Önemli notlar / sınırlamalar

- **Aynı ağda olmaları şart** ve router'ın cihazlar arası iletişime (AP/client isolation
  kapalı olmalı) izin vermesi gerekir. Bazı misafir WiFi ağları veya halka açık ağlar
  cihazlar arası trafiği bilerek engeller — bu durumda keşif çalışmaz.
- Şu an sadece **2 cihaz** için tasarlandı (ilk bulunan cihazla eşleşir). Daha fazla
  cihaz eklemek istersen peer listesini genişletmek gerekir, isteyip söylemen yeterli.
- Ses şifrelenmeden yerel ağda gönderiliyor (aynı ev/ofis ağı için yeterli, ama güvenlik
  kritikse şifreleme eklenmeli).
- Android 12+ cihazlarda ilk çalıştırmada "diğer uygulamaların üzerine çizme" izni
  ayarlar ekranından manuel açılmayı isteyebilir, uygulama seni otomatik oraya yönlendirir.
- minSdk 24 (Android 7.0) ve üzeri telefonlarda çalışır.

## Dosya yapısı

```
WalkieTalkie/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/walkietalkie/
│       │   ├── MainActivity.kt          (ekran, izinler, PTT butonu)
│       │   └── WalkieTalkieService.kt   (keşif, ses gönder/al, arka plan, kayan buton)
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/strings.xml
│           └── drawable/ic_launcher.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```
