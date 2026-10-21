# Accessibility

b PDF is designed so that a blind or low-vision user can complete every task with TalkBack, and so that every other user benefits from the same clarity. This document is the definition of done for every screen.

## Checklist (applies to every screen)

1. Every actionable element has a meaningful label (`contentDescription` or text). Icons that duplicate adjacent text use `contentDescription = null`.
2. Screen titles are marked as headings (`heading()`), as are section titles. Focus order is logical.
3. No gesture-only interaction. Anything draggable has equivalent custom actions (`CustomAccessibilityAction`) and visible buttons.
4. Touch targets are at least 48dp. Text supports 200% font scale without clipping (content scrolls).
5. Progress and results are announced through polite live regions. Errors are announced through assertive live regions and focus moves to the error or result.
6. Selected and checked state is exposed through `selected`, `toggleableState` or `stateDescription`.
7. Colour contrast meets WCAG AA in light and dark themes. No information is conveyed by colour alone.
8. Page thumbnails expose "Page N of M" plus rotation and selection state.
9. Every screen has a Compose UI test (Robolectric) asserting labels, headings and custom actions.

All strings must exist in English and Turkish.

## Manual TalkBack test procedure

Run this for every new or changed screen, in English and in Turkish.

1. Enable TalkBack (Settings > Accessibility > TalkBack). Set the system language to the one under test.
2. Open the screen and swipe right through every element. Confirm each element is announced with a clear name, role and state, and that nothing is skipped or announced twice.
3. Confirm the screen title is announced as a heading, and use heading navigation to move between sections.
4. For every list or reorderable item, open the TalkBack actions menu (three-finger tap, or swipe up then right) and confirm the custom actions (for example move up, move down, move to top, move to bottom, remove) are present and work. Confirm the item's position is announced afterwards.
5. Start an operation. Confirm progress is announced politely, and the result or error is announced and receives focus.
6. Trigger an error (wrong password, invalid page range such as `5-2`, corrupt file). Confirm the message is specific, localized and announced assertively.
7. Set font size to the largest and display size to the largest. Confirm nothing is clipped and everything is reachable by scrolling.
8. Switch to dark theme and check text and controls remain readable.
9. Rotate the device during an operation. Confirm the operation continues and the result is still announced.
10. Optionally repeat with Switch Access or an external keyboard (Tab/Enter) to confirm every control is reachable.

## Reporting an accessibility issue

Open an issue using the "Accessibility issue" template at https://github.com/wzehirx/b_pdf/issues/new/choose. Please include:

- The screen or tool, and b PDF version.
- Your assistive technology and version (TalkBack, Switch Access, font and display scale).
- What you expected to hear or do, and what happened.
- Android version, device and language.

Do not attach private documents; describe the PDF instead.

## Türkçe özet

b PDF'de her ekran şu ölçütleri karşılamalıdır: tüm etkileşimli öğelerin anlamlı etiketi vardır; başlıklar `heading` olarak işaretlenir; sürükleme gibi hareket gerektiren her işlemin eşdeğer özel eylemi (TalkBack "Eylemler" menüsü: üç parmakla dokunma ya da yukarı sonra sağa kaydırma) ve görünür bir düğmesi bulunur; dokunma hedefleri en az 48dp'dir ve %200 yazı boyutunda içerik kesilmez; ilerleme ve sonuçlar nazik (polite), hatalar ise acil (assertive) canlı bölge ile duyurulur ve odak hataya ya da sonuca taşınır; seçili/işaretli durum erişilebilirlik servislerine bildirilir; renk kontrastı açık ve koyu temada WCAG AA düzeyindedir; sayfa küçük resimleri "Sayfa N / M" bilgisini ve döndürme/seçim durumunu söyler; her ekranın etiket, başlık ve özel eylemleri doğrulayan bir Compose testi vardır. Tüm metinler Türkçe ve İngilizce olarak bulunur. Sorunları GitHub'daki "Accessibility issue" şablonuyla bildirebilirsiniz; TalkBack sürümünü, cihazı ve dili belirtin.
