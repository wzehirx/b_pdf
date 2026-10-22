# b PDF

[![CI](https://github.com/wzehirx/b_pdf/actions/workflows/ci.yml/badge.svg)](https://github.com/wzehirx/b_pdf/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

English | [Türkçe](#türkçe)

## İndir / Download

**[b PDF 1.0.0 APK'yı indir / Download the APK](https://github.com/wzehirx/b_pdf/raw/ccr-b3c31da5-4het63/apk/b-pdf-v1.0.0.apk)** (44 MB, Android 8.0+)

- TR: Bu APK, projenin kalıcı release anahtarıyla imzalıdır; sonraki sürümler bunun üstüne kurulur. Releases sayfasındaki APK'lar ise debug anahtarıyla imzalıdır; ikisi birbirinin üstüne kurulmaz, birinden diğerine geçerken önce uygulamayı kaldırın.
- EN: This APK is signed with the project's permanent release key, so later versions install over it. The APKs on the Releases page are debug-signed; the two cannot be installed over each other, so uninstall first when switching.
- SHA-256: `b0c05835e97c066cfead1952bb41f0148a57cda745fd7f664f0bc8a175d83b74`

**b PDF** is a free, open-source, offline PDF toolkit for Android, built accessibility-first. Every tool works with TalkBack, with no gesture-only interaction, and nothing ever leaves your device: the app has no network permission. Version 1.0.0 is the first stable release: all 21 tools are in, and every screen has been audited against the accessibility checklist.

## Features

**Organize**
- **Merge PDFs**: combine several PDFs into one, in the order you choose.
- **Split PDF**: break a PDF into parts by page ranges or every N pages; save to a folder, as one ZIP, or share.
- **Remove pages** and **Extract pages**: delete the pages you do not need, or save selected pages as a new PDF.
- **Organize pages**: reorder, rotate, duplicate and delete pages in one go, with undo.
- **Rotate pages**: turn all or selected pages by 90, 180 or 270 degrees.

**Convert**
- **Images to PDF**: turn photos and pictures (from the gallery or the camera) into a PDF with page size, orientation and margin options.
- **PDF to images**: save pages as JPEG or PNG pictures at a resolution you choose.
- **PDF to text**: read or save the text of all or selected pages, one "Page N" heading per page.
- **PDF to Word, Excel or HTML**: offline; "Text and pictures" keeps text editable with one heading per page and pictures with alt text, "Exact look" also keeps drawings and shapes by inserting each page as a picture. Excel output is basic (columns are guessed from spacing).
- **Word, Excel or text to PDF**: offline; headings, bold, lists, tables (as text) and pictures are kept; fonts, colours and non-picture shapes are not.
- **Text recognition (OCR)**: read scanned pages in Turkish, English or both, on the device, and save the text or a searchable PDF.

**Security**
- **Protect with password** and **Remove password**: add a password and permissions, or remove the password from a PDF you can open.
- **Sign**: stamp a typed or drawn signature on a page.

**Edit**
- **Compress**: make a PDF smaller with light, balanced or strong picture compression.
- **Watermark** and **Page numbers**: stamp text or numbers on all or selected pages (Turkish letters are supported).
- **Document info**: read and edit title, author, subject, keywords and creator.
- **Repair**: rebuild a damaged PDF so it opens again.

**View**
- **Accessible reader**: read a PDF one page per screen or as text with one heading per page; opens PDFs from other apps and lists your recent files.

**Settings**: theme (follow system, light, dark), high contrast, app language (Android 13 and newer; older versions follow the system language), default save behavior (ask each time or share first), clear cache. **About** shows the version, license and open-source licenses.

**Signing is visual, not cryptographic.** The Sign tool stamps your typed name or a drawn signature onto a page, like a rubber stamp. It is not a cryptographic (digital) signature: it does not prove who signed and does not detect later changes to the file. A typed signature is the default and works fully with a screen reader; drawing is optional.

**Text recognition (OCR) runs on your device.** The language data is bundled, so no download and no network access is needed. Recognition takes a few seconds per page, can be cancelled, and can contain mistakes, so check important details.

## TalkBack guide

b PDF is made to be used with TalkBack. Every screen starts with a heading (the title), and sections have headings too, so you can use TalkBack heading navigation to move between them.

**Open the actions menu.** Anything you could drag has TalkBack custom actions, and also visible buttons. To open the actions of the focused item, do a three-finger tap, or swipe up and then right (the default TalkBack "Actions" menu gesture), or use the TalkBack menu. Then double-tap the action you want. After an action, b PDF announces the new position or state.

**Reorder in Merge and Images to PDF.** Focus a file or picture. Its actions are "Move up", "Move down", "Move to top", "Move to bottom" and "Remove" (the first item has no "Move up" and the last has no "Move down"). The row also reads its position, for example "2 of 5", and the same actions exist as buttons next to the name. After a move you hear, for example, "report.pdf moved to position 1 of 3".

**Organize pages.** Every page says "Page 2 of 7", plus "rotated 90°" when it is turned. Its actions are "Move up", "Move down", "Move to position…" (type the number), "Rotate left", "Rotate right", "Duplicate" and "Delete"; there is also an "Actions for page N" button on each page. "Undo" reverses the last change. Then choose "Create organized PDF".

**Viewer.** Choose a PDF (or open one from another app, or from the recent files on the home screen). In page mode, each page is "Page N of M" and has the actions "Next page", "Previous page", "Go to page…" and "Switch to text mode"; the same are buttons, with "Zoom in" and "Zoom out" (pinching also works). "Read page text" reads the text of the shown page. Text mode lists the text of every page under its own "Page N" heading, so heading navigation (swipe up or down with the headings setting) jumps from page to page. Page changes are announced once.

**OCR flow.** Open "Text recognition (OCR)" (or "Try text recognition (OCR)" in PDF to text when a PDF has no text). Choose a PDF, the language of the text (Turkish and English by default) and which pages, then "Recognize text". You hear "Recognizing page 3 of 12" as it goes and can cancel. When it finishes, focus moves to the result; the text is under one "Page N" heading per page. Then choose "Save as text file", "Share text", "Copy all text" or "Save searchable PDF".

**Results and errors.** Progress is announced. When an operation finishes, focus moves to the result, where you find "Save" and "Share". Errors say what to fix (for example an invalid page range) and take focus. Password-protected PDFs ask for the password in a dialog.

See [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md) for the checklist, the audit results and the manual test procedure. Found a problem? Please open an [accessibility issue](https://github.com/wzehirx/b_pdf/issues/new/choose).

## Install

1. Go to [Releases](https://github.com/wzehirx/b_pdf/releases) and download the signed APK, `b-pdf-<version>.apk`.
2. Open the file. If Android asks, allow installing apps from this source ("Install unknown apps") for your browser or file manager.
3. Install and open b PDF.

Requires Android 8.0 (API 26) or newer. The signed APKs of all releases use the same signing key, so you can update by installing the new APK over the old one.

## Build from source

Requirements: JDK 21 and Android SDK platform 36 with build-tools 36.0.0.

```bash
scripts/setup-android-sdk.sh        # provisions the Android SDK (optional if you already have it)
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug             # APK in app/build/outputs/apk/debug/
```

Release builds are signed in CI from four repository secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Until they are set, releases are signed with a debug key kept in the Actions cache, so releases still install over each other; switching to the real key later needs one uninstall. Optionally set the repository variable `BPDF_CERT_SHA256` to the SHA-256 of your release certificate and the workflow checks it. A local `assembleRelease` without them uses the debug key.

Release workflow notes: a push to `main` or any `ccr-*` branch that raises `bpdf.versionName` publishes that version, so keep release branches to branches you trust; the actions are pinned by major version, not by commit.

## Architecture

Single Gradle module `app`, package `io.github.wzehirx.bpdf` (Kotlin, Jetpack Compose with Material 3, PdfBox-Android, Tesseract4Android):

- `core/pdf`: the PDF engine, one class per operation, pure file in, file out, no UI.
- `core/work`: `OperationRunner`, a coroutine job with progress, cancel and error state.
- `core/files`: Storage Access Framework helpers, file naming, sharing, recent files.
- `core/settings`: preferences (DataStore) and the per-app language.
- `core/ocr`: text recognition engine and the searchable PDF builder.
- `core/a11y`: announcements, semantics helpers, reorder custom actions.
- `feature/*`: one package per tool, plus `settings` and `about` (screen and ViewModel).
- `ui`: theme (including high contrast), navigation, home screen, shared accessible components.

Adding a tool follows [docs/FEATURE_PATTERN.md](docs/FEATURE_PATTERN.md).

## Testing

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Unit and Compose UI tests run on Robolectric; each screen has semantics tests for labels, headings and custom actions, plus audit tests for touch targets and 200% font scale. CI runs the same gate on every push.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Every change must keep the accessibility checklist in [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md) green and all strings available in English and Turkish. Changes are recorded in [CHANGELOG.md](CHANGELOG.md).

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

**b PDF**, Android için ücretsiz, açık kaynaklı ve çevrimdışı çalışan, erişilebilirliği önceleyen bir PDF araç takımıdır. Her araç TalkBack ile kullanılabilir, hiçbir işlem yalnızca harekete bağlı değildir ve hiçbir veri cihazınızdan çıkmaz: uygulamanın ağ izni yoktur. 1.0.0 ilk kararlı sürümdür: 21 aracın hepsi hazırdır ve her ekran erişilebilirlik denetim listesine göre denetlenmiştir.

### Özellikler

**Düzenle**
- **PDF birleştir**: birkaç PDF'yi seçtiğiniz sırayla tek dosyada birleştirir.
- **PDF böl**: PDF'yi sayfa aralıklarına ya da her N sayfada bir parçalara ayırır; klasöre, tek ZIP olarak kaydedebilir ya da paylaşabilirsiniz.
- **Sayfa sil** ve **Sayfa çıkar**: gerekmeyen sayfaları siler ya da seçtiğiniz sayfaları yeni bir PDF olarak kaydeder.
- **Sayfaları düzenle**: sayfaları tek seferde sıralar, döndürür, çoğaltır ve siler; geri alma vardır.
- **Döndür**: tüm sayfaları ya da seçtiklerinizi 90, 180 veya 270 derece çevirir.

**Dönüştür**
- **Görselden PDF'e**: galeriden ya da kameradan fotoğrafları sayfa boyutu, yön ve kenar boşluğu seçenekleriyle PDF yapar.
- **PDF'den görsele**: sayfaları seçtiğiniz çözünürlükte JPEG ya da PNG olarak kaydeder.
- **PDF'den metne**: tüm sayfaların ya da seçtiklerinizin metnini, her sayfa kendi "Sayfa N" başlığı altında, okur veya kaydeder.
- **PDF'den Word, Excel veya HTML'ye**: çevrimdışı; "Metin ve resimler" metni düzenlenebilir bırakır, her sayfa için bir başlık ve açıklamalı resimler ekler; "Birebir görünüm" her sayfayı resim olarak da ekleyerek çizim ve şekilleri korur. Excel çıktısı basittir (sütunlar boşluklardan tahmin edilir).
- **Word, Excel veya metinden PDF'ye**: çevrimdışı; başlıklar, kalın yazı, listeler, tablolar (metin olarak) ve resimler korunur; yazı tipleri, renkler ve resim olmayan şekiller korunmaz.
- **Metin tanıma (OCR)**: taranmış sayfaları cihazda Türkçe, İngilizce veya ikisi birden okur; metni ya da aranabilir PDF'yi kaydeder.

**Güvenlik**
- **Şifrele** ve **Şifre kaldır**: parola ve izinler ekler ya da açabildiğiniz bir PDF'nin parolasını kaldırır.
- **İmzala**: sayfaya yazılı ya da çizilmiş bir imza basar.

**Değiştir**
- **Sıkıştır**: hafif, dengeli ya da güçlü resim sıkıştırmasıyla PDF'yi küçültür.
- **Filigran** ve **Sayfa numarası**: tüm sayfalara ya da seçtiklerinize metin veya numara basar (Türkçe harfler desteklenir).
- **Belge bilgileri**: başlık, yazar, konu, anahtar sözcükler ve oluşturan uygulamayı okur ve düzenler.
- **Onar**: bozuk bir PDF'yi yeniden oluşturarak tekrar açılır hale getirir.

**Görüntüle**
- **Erişilebilir okuyucu**: PDF'yi her ekranda bir sayfa olarak ya da her sayfası bir başlık altında metin olarak okur; diğer uygulamalardan PDF açar ve son dosyalarınızı listeler.

**Ayarlar**: tema (sistemi izle, açık, koyu), yüksek karşıtlık, uygulama dili (Android 13 ve üstü; eski sürümler sistem dilini izler), varsayılan kaydetme davranışı (her seferinde sor ya da önce paylaş), önbelleği temizle. **Hakkında** ekranı sürümü, lisansı ve açık kaynak lisanslarını gösterir.

**İmza görseldir, kriptografik değildir.** İmzala aracı yazdığınız adı ya da çizdiğiniz imzayı bir kaşe gibi sayfaya basar. Kriptografik (dijital) imza değildir: kimin imzaladığını kanıtlamaz ve dosyada sonradan yapılan değişiklikleri algılamaz. Yazılı imza varsayılandır ve ekran okuyucuyla tam çalışır; çizim isteğe bağlıdır.

**Metin tanıma (OCR) cihazınızda çalışır.** Dil verisi uygulamayla birlikte gelir; indirme ve ağ erişimi gerekmez. Tanıma sayfa başına birkaç saniye sürer, iptal edilebilir ve hata içerebilir; önemli ayrıntıları kontrol edin.

### TalkBack rehberi

b PDF, TalkBack ile kullanılmak üzere yapılmıştır. Her ekran bir başlıkla (başlık satırı) başlar, bölümlerin de başlığı vardır; böylece TalkBack başlık gezinmesiyle bölümler arasında geçebilirsiniz.

**Eylemler menüsünü açma.** Sürükleyebileceğiniz her şeyin TalkBack özel eylemleri ve görünür düğmeleri vardır. Odaktaki öğenin eylemlerini açmak için üç parmakla dokunun, ya da yukarı sonra sağa kaydırın (TalkBack "Eylemler" menüsü hareketi), ya da TalkBack menüsünü kullanın. Sonra istediğiniz eyleme çift dokunun. Bir eylemden sonra b PDF yeni konumu ya da durumu duyurur.

**Birleştir ve Görselden PDF'e ekranlarında sıralama.** Bir dosyaya ya da resme odaklanın. Eylemleri: "Yukarı taşı", "Aşağı taşı", "En üste taşı", "En alta taşı" ve "Kaldır" (ilk öğede "Yukarı taşı", son öğede "Aşağı taşı" yoktur). Satır konumunu da okur, örneğin "5 dosyadan 2."; aynı eylemler addan sonraki düğmelerdir. Taşıdıktan sonra, örneğin "rapor.pdf 3 dosyadan 1. sıraya taşındı" duyarsınız.

**Sayfaları düzenle.** Her sayfa "2. sayfa, toplam 7" der; döndürülmüşse buna döndürme derecesi eklenir. Eylemleri: "Yukarı taşı", "Aşağı taşı", "Konuma taşı…" (numarayı yazarsınız), "Sola döndür", "Sağa döndür", "Çoğalt" ve "Sil"; her sayfada ayrıca bir "N. sayfa işlemleri" düğmesi vardır. "Geri al" son değişikliği geri alır. Sonra "Düzenlenmiş PDF oluştur" düğmesini seçin.

**Okuyucu.** Bir PDF seçin (ya da başka bir uygulamadan ya da ana ekrandaki son dosyalardan açın). Sayfa kipinde her sayfa "N. sayfa, toplam M" der ve "Sonraki sayfa", "Önceki sayfa", "Sayfaya git…" ve "Metin kipine geç" eylemlerini taşır; aynıları düğme olarak da vardır, ayrıca "Yakınlaştır" ve "Uzaklaştır" bulunur (çimdikleme de çalışır). "Sayfa metnini oku" görünen sayfanın metnini okur. Metin kipi her sayfanın metnini kendi "Sayfa N" başlığı altında listeler; böylece başlık gezinmesi (başlıklar ayarıyla yukarı ya da aşağı kaydırma) sayfadan sayfaya atlar. Sayfa değişimleri bir kez duyurulur.

**OCR akışı.** "Metin tanıma (OCR)" aracını açın (ya da metni olmayan bir PDF için PDF'den metne ekranındaki OCR düğmesini kullanın). PDF'yi, metnin dilini (varsayılan Türkçe ve İngilizce) ve hangi sayfaların okunacağını seçin, sonra "Metni tanı" düğmesine basın. İlerledikçe "3. sayfa okunuyor, toplam 12 sayfa" gibi duyurular alırsınız ve iptal edebilirsiniz. Bitince odak sonuca geçer; metin her sayfa için bir "Sayfa N" başlığı altındadır. Sonra "Metin dosyası olarak kaydet", "Metni paylaş", "Tüm metni kopyala" ya da "Aranabilir PDF kaydet" seçeneklerinden birini seçin.

**Sonuçlar ve hatalar.** İlerleme duyurulur. İşlem bitince odak sonuca geçer; orada "Kaydet" ve "Paylaş" düğmeleri bulunur. Hatalar neyi düzeltmeniz gerektiğini söyler (örneğin geçersiz sayfa aralığı) ve odağı alır. Parolalı PDF'ler için bir iletişim kutusunda parola istenir.

Denetim listesi, denetim sonuçları ve elle test yöntemi için bkz. [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md). Bir sorun mu buldunuz? Lütfen bir [erişilebilirlik sorunu](https://github.com/wzehirx/b_pdf/issues/new/choose) açın.

### Kurulum

1. [Releases](https://github.com/wzehirx/b_pdf/releases) sayfasına gidip imzalı APK'yı, `b-pdf-<sürüm>.apk` dosyasını indirin.
2. Dosyayı açın. Android sorarsa, tarayıcınız veya dosya yöneticiniz için bu kaynaktan uygulama yüklemeye ("Bilinmeyen uygulamaları yükle") izin verin.
3. Yükleyin ve b PDF'yi açın.

Android 8.0 (API 26) veya üstü gerekir. Tüm sürümlerin imzalı APK'ları aynı imza anahtarını kullanır; yeni APK'yı eskisinin üzerine yükleyerek güncelleyebilirsiniz.

### Kaynaktan derleme

Gereksinimler: JDK 21 ve Android SDK platform 36 ile build-tools 36.0.0.

```bash
scripts/setup-android-sdk.sh        # Android SDK'yı kurar (zaten varsa gerekmez)
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug             # APK: app/build/outputs/apk/debug/
```

Yayın sürümleri CI'da dört depo gizli anahtarıyla imzalanır: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` ve `SIGNING_KEY_PASSWORD`. Bunlar eklenene kadar sürümler Actions önbelleğinde saklanan bir hata ayıklama (debug) anahtarıyla imzalanır; böylece sürümler birbirinin üstüne kurulur. Sonradan gerçek anahtara geçmek bir kez kaldırıp yeniden kurmayı gerektirir. İsterseniz `BPDF_CERT_SHA256` depo değişkenine sertifikanızın SHA-256 değerini yazın, iş akışı bunu da denetler. Yerelde gizli anahtarsız `assembleRelease` hata ayıklama anahtarını kullanır.

### Mimari

Tek Gradle modülü `app`, paket `io.github.wzehirx.bpdf` (Kotlin, Material 3 ile Jetpack Compose, PdfBox-Android, Tesseract4Android):

- `core/pdf`: PDF motoru; her işlem için bir sınıf, dosya girer dosya çıkar, arayüz yok.
- `core/work`: `OperationRunner`; ilerleme, iptal ve hata durumu olan bir coroutine işi.
- `core/files`: Storage Access Framework yardımcıları, dosya adlandırma, paylaşım, son dosyalar.
- `core/settings`: tercihler (DataStore) ve uygulama başına dil.
- `core/ocr`: metin tanıma motoru ve aranabilir PDF oluşturucu.
- `core/a11y`: duyurular, semantik yardımcıları, sıralama özel eylemleri.
- `feature/*`: her araç için bir paket, ayrıca `settings` ve `about` (ekran ve ViewModel).
- `ui`: tema (yüksek karşıtlık dahil), gezinme, ana ekran, ortak erişilebilir bileşenler.

Yeni araç eklemek için [docs/FEATURE_PATTERN.md](docs/FEATURE_PATTERN.md) izlenir.

### Test

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Birim ve Compose arayüz testleri Robolectric üzerinde çalışır; her ekranın etiket, başlık ve özel eylemleri doğrulayan semantik testleri, ayrıca dokunma hedefleri ve %200 yazı boyutu için denetim testleri vardır. CI aynı kontrolleri her push'ta çalıştırır.

### Katkı

Bkz. [CONTRIBUTING.md](CONTRIBUTING.md). Her değişiklik [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md) içindeki erişilebilirlik denetim listesini karşılamalı ve tüm metinler İngilizce ile Türkçe olarak bulunmalıdır. Değişiklikler [CHANGELOG.md](CHANGELOG.md) dosyasında tutulur.

### Lisans

Apache License 2.0, bkz. [LICENSE](LICENSE).

Üçüncü taraf bileşenler:
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache-2.0)
- [Noto Sans](https://github.com/notofonts/latin-greek-cyrillic) Regular yazı tipi (SIL OFL 1.1), filigran ve sayfa numarası gibi PDF'e yazılan metinler için uygulamaya dahildir; lisans metni `app/src/main/assets/fonts/NotoSans-OFL.txt` dosyasındadır
- [Tesseract OCR](https://github.com/tesseract-ocr/tesseract) ve [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) sarmalayıcısı 4.9.0 (ikisi de Apache-2.0), OCR için kullanılır. Sarmalayıcı JitPack'ten alınan tek bağımlılıktır (`cz.adaptech.tesseract4android`).
- [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) dil verisi (Apache-2.0), `app/src/main/assets/tessdata/` içinde uygulamaya dahildir (lisans ve ayrıntılar oradaki `LICENSE.txt` dosyasındadır), commit `87416418657359cb625c412a48b6e1d6d41c29bd`:
  - `eng.traineddata`, 4113088 bayt, SHA-256 `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
  - `tur.traineddata`, 4550554 bayt, SHA-256 `7393381111e1152420fc4092cb44eef4237580d21b92bf30d7d221aad192c6b7`
