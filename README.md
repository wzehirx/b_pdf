# b PDF

[![CI](https://github.com/wzehirx/b_pdf/actions/workflows/ci.yml/badge.svg)](https://github.com/wzehirx/b_pdf/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

English | [Türkçe](#türkçe)

**b PDF** is a free, open-source, offline PDF toolkit for Android, built accessibility-first. Every tool works with TalkBack, with no gesture-only interaction, and nothing ever leaves your device: the app has no network permission.

## Features

Tools are delivered milestone by milestone. Status: v0.1.0 provides the foundation; the tools below are planned.

| Milestone | Tools | Status |
|---|---|---|
| v0.1 | Foundation: engine, file handling, accessibility core, home screen, CI | Released |
| v0.2 | Merge, Split, Remove pages, Extract pages, Rotate | Planned |
| v0.3 | Organize pages, Images to PDF, PDF to images | Planned |
| v0.4 | Protect, Unlock, Metadata, Repair | Planned |
| v0.5 | Compress, Watermark, Page numbers | Planned |
| v0.6 | PDF to text, Accessible viewer, open from other apps | Planned |
| v0.7 | Sign, OCR (Turkish and English) | Planned |
| v1.0 | Polish, full accessibility audit | Planned |

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
- Noto Sans font (SIL OFL 1.1), planned for text drawn into PDFs
- Tesseract / Tesseract4Android (Apache-2.0), planned for OCR

---

## Türkçe

**b PDF**, Android için ücretsiz, açık kaynaklı ve çevrimdışı çalışan, erişilebilirliği önceleyen bir PDF araç takımıdır. Her araç TalkBack ile kullanılabilir, hiçbir işlem yalnızca harekete bağlı değildir ve hiçbir veri cihazınızdan çıkmaz: uygulamanın ağ izni yoktur.

### Özellikler

Araçlar aşama aşama eklenir. Durum: v0.1.0 temeli sağlar; aşağıdaki araçlar planlanmıştır.

| Aşama | Araçlar | Durum |
|---|---|---|
| v0.1 | Temel: motor, dosya işleme, erişilebilirlik çekirdeği, ana ekran, CI | Yayınlandı |
| v0.2 | Birleştir, Böl, Sayfa sil, Sayfa ayıkla, Döndür | Planlandı |
| v0.3 | Sayfaları düzenle, Görüntülerden PDF, PDF'den görüntü | Planlandı |
| v0.4 | Şifrele, Şifreyi kaldır, Üst veri, Onar | Planlandı |
| v0.5 | Sıkıştır, Filigran, Sayfa numarası | Planlandı |
| v0.6 | PDF'den metin, Erişilebilir görüntüleyici, diğer uygulamalardan açma | Planlandı |
| v0.7 | İmzala, OCR (Türkçe ve İngilizce) | Planlandı |
| v1.0 | Son rötuşlar, tam erişilebilirlik denetimi | Planlandı |

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
- Noto Sans yazı tipi (SIL OFL 1.1), PDF'e yazılan metinler için planlandı
- Tesseract / Tesseract4Android (Apache-2.0), OCR için planlandı
