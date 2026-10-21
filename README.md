# b PDF

[![CI](https://github.com/wzehirx/b_pdf/actions/workflows/ci.yml/badge.svg)](https://github.com/wzehirx/b_pdf/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

English | [Türkçe](#türkçe)

**b PDF** is a free, open-source, offline PDF toolkit for Android, built accessibility-first. Every tool works with TalkBack, with no gesture-only interaction, and nothing ever leaves your device: the app has no network permission.

## Features

Tools are delivered milestone by milestone. Status: v0.7.0 adds Sign and OCR; Polish and the full accessibility audit are planned for v1.0.

| Milestone | Tools | Status |
|---|---|---|
| v0.1 | Foundation: engine, file handling, accessibility core, home screen, CI | Released |
| v0.2 | Merge, Split, Remove pages, Extract pages, Rotate | Released |
| v0.3 | Organize pages, Images to PDF, PDF to images | Released |
| v0.4 | Protect, Unlock, Metadata, Repair | Released |
| v0.5 | Compress, Watermark, Page numbers | Released |
| v0.6 | PDF to text, Accessible viewer, open from other apps | Released |
| v0.7 | Sign, OCR (Turkish and English) | Released |
| v1.0 | Polish, full accessibility audit | Planned |

**Signing is visual, not cryptographic.** The Sign tool stamps your typed name or a drawn signature onto a page, like a rubber stamp. It is not a cryptographic (digital) signature: it does not prove who signed and does not detect later changes to the file. A typed signature is the default and works fully with a screen reader; drawing is optional.

**Text recognition (OCR) runs on your device.** It reads scanned pages in Turkish, English or both, shows the text page by page, and can save it as a .txt file or as a searchable PDF (your original pages plus an invisible text layer that search and screen readers can use). The language data is bundled, so no download and no network access is needed; the first run copies it to the app's private storage. Recognition takes a few seconds per page, can be cancelled, and can contain mistakes.

## Accessibility

Accessibility is the definition of done for every screen; see [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md).

- Every control has a clear label, and titles are headings.
- Anything you could drag (for example reordering files or pages) also offers TalkBack custom actions and visible buttons. Open the actions with the TalkBack "Actions" menu: three-finger tap, or swipe up then right. Typical actions: move up, move down, move to top, move to bottom, remove.
- Progress and results are announced; errors are announced assertively and take focus.
- Touch targets are at least 48dp, text scales to 200%, and colours meet WCAG AA in light and dark themes.

Found a problem? Please open an [accessibility issue](https://github.com/wzehirx/b_pdf/issues/new/choose).

## Install

1. Go to [Releases](https://github.com/wzehirx/b_pdf/releases) and download `b-pdf-<version>.apk`.
2. Open the file. If Android asks, allow installing apps from this source ("Install unknown apps") for your browser or file manager.
3. Install and open b PDF.

Requires Android 8.0 (API 26) or newer.

## Build from source

Requirements: JDK 21 and Android SDK platform 36 with build-tools 36.0.0.

```bash
scripts/setup-android-sdk.sh        # provisions the Android SDK (optional if you already have it)
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug             # APK in app/build/outputs/apk/debug/
```

## Architecture

Single Gradle module `app`, package `io.github.wzehirx.bpdf`:

- `core/pdf`: the PDF engine, one class per operation, pure file in, file out, no UI. Built on PdfBox-Android.
- `core/work`: `OperationRunner`, a coroutine job with progress, cancel and error state.
- `core/files`: Storage Access Framework helpers, file naming, sharing.
- `core/a11y`: announcements, semantics helpers, reorder custom actions.
- `feature/*`: one package per tool (screen and ViewModel).
- `ui`: theme, navigation, home screen, shared accessible components.

## Testing

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Unit and Compose UI tests run on Robolectric; each screen has semantics tests for labels, headings and custom actions. CI runs the same gate on every push.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Changes are recorded in [CHANGELOG.md](CHANGELOG.md).

## License

Apache License 2.0, see [LICENSE](LICENSE).

Third-party components:
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache-2.0)
- [Noto Sans](https://github.com/notofonts/latin-greek-cyrillic) Regular font (SIL OFL 1.1), bundled for text drawn into PDFs such as watermarks and page numbers; license text in `app/src/main/assets/fonts/NotoSans-OFL.txt`
- [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) and its [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) wrapper 4.9.0 (both Apache-2.0), used for OCR. The wrapper is the only dependency fetched from JitPack (`cz.adaptech.tesseract4android`).
- [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) language data (Apache-2.0), bundled in `app/src/main/assets/tessdata/` (license and details in `LICENSE.txt` there), commit `87416418657359cb625c412a48b6e1d6d41c29bd`:
  - `eng.traineddata`, 4113088 bytes, SHA-256 `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
  - `tur.traineddata`, 4550554 bytes, SHA-256 `7393381111e1152420fc4092cb44eef4237580d21b92bf30d7d221aad192c6b7`

---

## Türkçe

**b PDF**, Android için ücretsiz, açık kaynaklı ve çevrimdışı çalışan, erişilebilirliği önceleyen bir PDF araç takımıdır. Her araç TalkBack ile kullanılabilir, hiçbir işlem yalnızca harekete bağlı değildir ve hiçbir veri cihazınızdan çıkmaz: uygulamanın ağ izni yoktur.

### Özellikler

Araçlar aşama aşama eklenir. Durum: v0.7.0 İmzala ve OCR araçlarını ekler; son rötuşlar ve tam erişilebilirlik denetimi v1.0 için planlanmıştır.

| Aşama | Araçlar | Durum |
|---|---|---|
| v0.1 | Temel: motor, dosya işleme, erişilebilirlik çekirdeği, ana ekran, CI | Yayınlandı |
| v0.2 | Birleştir, Böl, Sayfa sil, Sayfa ayıkla, Döndür | Yayınlandı |
| v0.3 | Sayfaları düzenle, Görüntülerden PDF, PDF'den görüntü | Yayınlandı |
| v0.4 | Şifrele, Şifreyi kaldır, Üst veri, Onar | Yayınlandı |
| v0.5 | Sıkıştır, Filigran, Sayfa numarası | Yayınlandı |
| v0.6 | PDF'den metin, Erişilebilir görüntüleyici, diğer uygulamalardan açma | Yayınlandı |
| v0.7 | İmzala, OCR (Türkçe ve İngilizce) | Yayınlandı |
| v1.0 | Son rötuşlar, tam erişilebilirlik denetimi | Planlandı |

**İmza görseldir, kriptografik değildir.** İmzala aracı yazdığınız adı ya da çizdiğiniz imzayı bir kaşe gibi sayfaya basar. Kriptografik (dijital) imza değildir: kimin imzaladığını kanıtlamaz ve dosyada sonradan yapılan değişiklikleri algılamaz. Yazılı imza varsayılandır ve ekran okuyucuyla tam çalışır; çizim isteğe bağlıdır.

**Metin tanıma (OCR) cihazınızda çalışır.** Taranmış sayfaları Türkçe, İngilizce veya ikisi birden okur, metni sayfa sayfa gösterir ve .txt dosyası ya da aranabilir PDF olarak (özgün sayfalarınız artı arama ve ekran okuyucuların kullanabildiği görünmez bir metin katmanı) kaydedebilir. Dil verisi uygulamayla birlikte gelir; indirme ve ağ erişimi gerekmez, ilk çalıştırmada uygulamanın özel alanına kopyalanır. Tanıma sayfa başına birkaç saniye sürer, iptal edilebilir ve hata içerebilir.

### Erişilebilirlik

Erişilebilirlik her ekran için "bitti" tanımının parçasıdır; bkz. [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md).

- Her denetimin açık bir etiketi vardır, başlıklar başlık olarak işaretlenir.
- Sürükleyebileceğiniz her şey (örneğin dosya ya da sayfa sıralama) TalkBack özel eylemleri ve görünür düğmeler de sunar. Eylemleri TalkBack "Eylemler" menüsünden açın: üç parmakla dokunun ya da yukarı sonra sağa kaydırın. Tipik eylemler: yukarı taşı, aşağı taşı, en üste taşı, en alta taşı, kaldır.
- İlerleme ve sonuçlar duyurulur; hatalar acil olarak duyurulur ve odağı alır.
- Dokunma hedefleri en az 48dp'dir, yazı %200'e kadar büyür, renkler açık ve koyu temada WCAG AA düzeyindedir.

Bir sorun mu buldunuz? Lütfen bir [erişilebilirlik sorunu](https://github.com/wzehirx/b_pdf/issues/new/choose) açın.

### Kurulum

1. [Releases](https://github.com/wzehirx/b_pdf/releases) sayfasına gidip `b-pdf-<sürüm>.apk` dosyasını indirin.
2. Dosyayı açın. Android sorarsa, tarayıcınız veya dosya yöneticiniz için bu kaynaktan uygulama yüklemeye ("Bilinmeyen uygulamaları yükle") izin verin.
3. Yükleyin ve b PDF'yi açın.

Android 8.0 (API 26) veya üstü gerekir.

### Kaynaktan derleme

Gereksinimler: JDK 21 ve Android SDK platform 36 ile build-tools 36.0.0.

```bash
scripts/setup-android-sdk.sh        # Android SDK'yı kurar (zaten varsa gerekmez)
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug             # APK: app/build/outputs/apk/debug/
```

### Mimari

Tek Gradle modülü `app`, paket `io.github.wzehirx.bpdf`:

- `core/pdf`: PDF motoru; her işlem için bir sınıf, dosya girer dosya çıkar, arayüz yok. PdfBox-Android üzerine kuruludur.
- `core/work`: `OperationRunner`; ilerleme, iptal ve hata durumu olan bir coroutine işi.
- `core/files`: Storage Access Framework yardımcıları, dosya adlandırma, paylaşım.
- `core/a11y`: duyurular, semantik yardımcıları, sıralama özel eylemleri.
- `feature/*`: her araç için bir paket (ekran ve ViewModel).
- `ui`: tema, gezinme, ana ekran, ortak erişilebilir bileşenler.

### Test

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Birim ve Compose arayüz testleri Robolectric üzerinde çalışır; her ekranın etiket, başlık ve özel eylemleri doğrulayan semantik testleri vardır. CI aynı kontrolleri her push'ta çalıştırır.

### Katkı

Bkz. [CONTRIBUTING.md](CONTRIBUTING.md). Değişiklikler [CHANGELOG.md](CHANGELOG.md) dosyasında tutulur.

### Lisans

Apache License 2.0, bkz. [LICENSE](LICENSE).

Üçüncü taraf bileşenler:
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache-2.0)
- [Noto Sans](https://github.com/notofonts/latin-greek-cyrillic) Regular yazı tipi (SIL OFL 1.1), filigran ve sayfa numarası gibi PDF'e yazılan metinler için uygulamaya dahildir; lisans metni `app/src/main/assets/fonts/NotoSans-OFL.txt` dosyasındadır
- [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) ve [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) sarmalayıcısı 4.9.0 (ikisi de Apache-2.0), OCR için kullanılır. Sarmalayıcı JitPack'ten alınan tek bağımlılıktır (`cz.adaptech.tesseract4android`).
- [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) dil verisi (Apache-2.0), `app/src/main/assets/tessdata/` içinde uygulamaya dahildir (lisans ve ayrıntılar oradaki `LICENSE.txt` dosyasındadır), commit `87416418657359cb625c412a48b6e1d6d41c29bd`:
  - `eng.traineddata`, 4113088 bayt, SHA-256 `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
  - `tur.traineddata`, 4550554 bayt, SHA-256 `7393381111e1152420fc4092cb44eef4237580d21b92bf30d7d221aad192c6b7`
