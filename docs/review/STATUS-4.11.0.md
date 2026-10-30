# وضعیت رفع مشکلات در نسخه 4.11.0

راهنما: ✅ رفع شد · 🟡 بخشی رفع شد · ⚪ تغییر نکرد (دلیل ذکر شده)

> **مهم:** هیچ‌کدام از تغییرات در این محیط کامپایل یا اجرا نشدن (Gradle/Android SDK/شبکه در دسترس نبود).
> فقط بررسی ایستا انجام شد: تعادل پرانتز/آکولاد همه‌ی فایل‌های Kotlin تغییرکرده، `node --check` برای
> درایور Pi، و خواندن دوباره‌ی کد. قبل از انتشار حتماً `./gradlew test assembleDebug` اجرا و روی دستگاه تست کنید.

| شناسه | وضعیت | یادداشت |
|---|---|---|
| H-01 | ✅ | handshake با توکن در `PiEngine` و `driver.mjs` (داخل `pi-runtime.zip` بازبسته شد)؛ نسخه‌ی runtime → `pi-1.4.1-auth` |
| H-02 | ✅ | نرمال‌سازی quote/escape + تشخیص glob در `SensitivePaths`؛ gate ترمینال هم‌سان شد. ابزارهای نیتیو read-only (ایده 2.1) پیاده نشد |
| H-03 | ✅ | `cp`/`mv` فایل راز و `cat` مجازی توسط AI رد می‌شود؛ `.anvex-pi` محافظت شد |
| H-04 | ✅ | watcher با `flatMapLatest`؛ rebuild فقط با تغییر واقعی Provider پیش‌فرض |
| H-05 | ✅ | نوشتن atomic + قفل + بدون حذف بی‌صدای کلید + فایل خراب به `.corrupt` منتقل می‌شود. (ذخیره در Room انجام نشد) |
| H-06 | ✅ | `flush(applyIncomplete=false)` + پیام کاربر + تست. دکمه‌ی «ادامه بده» خودکار پیاده نشد |
| H-07 | 🟡 | نوشتن AI در `.github/workflows/**` مسدود؛ قالب و release workflow سخت‌تر شد. هشدار هنگام push، امضای روی دستگاه و Environment reviewer باید توسط شما تنظیم شود |
| M-01 | 🟡 | فقط برچسب UI صادقانه شد؛ مدل deny-list تغییر نکرد (مسیر AI با allow-list محافظت می‌شود) |
| M-02 | 🟡 | سقف طول خط، deadline، JSON درست. TLS اضافه نشد |
| M-03 | 🟡 | checksum اجباری، اعتبارسنجی URL، دانلود تک‌پاس. مقایسه‌ی گواهی امضا و پاک‌سازی cache انجام نشد |
| M-04 | 🟡 | الگوی توکن‌ها + قاعده‌ی مشترک فایل راز. `scope=repo`، پیش‌نمایش diff در pull، انتشار حذف‌ها (عمداً: خطر حذف فایل‌های مخزن) تغییر نکرد |
| M-05 | ✅ | GitLite: `.gitignore`/`.github` track می‌شوند، ignore rules، `status` بدون ساخت `.git` |
| M-06 | ✅ | export اشتراکی بدون راز؛ بکاپ داخلی عمداً کامل می‌ماند |
| M-07 | 🟡 | symlink-safe delete/walk + چک اندازه قبل از خواندن. fsync هر نوشتن تغییر نکرد |
| M-08 | 🟡 | Provider با خطای Keystore دیگر ناپدید نمی‌شود. restore همه‌یا‌هیچ و AAD تغییر نکرد |
| M-09 | ✅ | LAN/Termux هنگام restore فعال نمی‌شوند؛ clamp فونت |
| M-10 | ✅ | |
| M-11 | ✅ | کپی محدود + خواندن synchronous تنظیمات قبل از فریم اول |
| M-12 | 🟡 | session id، protocol header، شمارنده‌ی atomic، initialize تنبل. بافر SSE و DELETE session نه |
| M-13 | ⚪ | Insets بدون تست روی دستگاه‌های واقعی ریسک رگرسیون دارد |
| M-14 | 🟡 | permissions، environment، wrapper validation، Dependabot، secret از env. pin با SHA نشد (بدون شبکه SHA معتبر قابل‌دریافت نبود) |
| M-15 | ⚪ | AAD روی رمزنگاری کلید نیاز به مهاجرت داده دارد؛ پیاده نشد |
| M-16 | 🟡 | `stream_options`، JSON 200، fallback `message`. نگاشت 402/413 و `Retry-After` نه |
| M-17 | 🟡 | فایل راز وارد prompt نمی‌شود، فایل‌های اولویت غیر وب، مرتب‌سازی عمق‌محور. امتیازدهی ارتباط/بودجه بر اساس پنجره‌ی مدل نه |
| L-01 | 🟡 | pool/accept loop. Range و حذف کد تکراری نه |
| L-02 | 🟡 | delta تفاضلی. `runBlocking` در bridge و تقسیم god-class نه |
| L-03 | ⚪ | |
| L-04 | ✅ | UTF-8 چندبایتی، reap فرزندان، جلوگیری از `cat`/`tail -f` بی‌پایان |
| L-05 | ⚪ | |
| L-06 | ⚪ | |
| L-07 | ⚪ | `addWebMessageListener` نیاز به تست روی WebView دارد |
| L-08 | 🟡 | آیکن‌های Send آینه‌ای + توضیحات ترجمه‌شده. مهاجرت `tr()` → `strings.xml` نه |
| L-09 | 🟡 | عنوان KNOWN_LIMITATIONS و CHANGELOG. تجمیع اسناد قدیمی نه |
| L-10 | ⚪ | ارتقای وابستگی بدون build ممکن نیست؛ Dependabot اضافه شد |
| L-11 | 🟡 | تست‌های جدید برای H-02/H-03/H-06/M-04/M-05/M-06/M-12/M-17/L-14 |
| L-12 | ⚪ | |
| L-13 | 🟡 | permissions/timeout/cache/choice در قالب |
| L-14 | ✅ | |
| L-15 | ✅ | |
| L-16 | ⚪ | |

## ایده‌ها (فایل IDEAS)
پیاده‌شده: handshake توکن (1.2)، بخشی از SecretPolicy (1.3)، ادامه/دور ریختن خروجی ناقص (15.1 بخشی)، سخت‌سازی Cloud Build (15.3 بخشی)،
delta تفاضلی (10)، انیمیشن لیست‌ها و انتقال صفحه‌ها (9)، Dependabot (12).
پیاده‌نشده (نیاز به طراحی/تست دستگاه): checkpoint، plan-approve، MCP stdio، compaction/caching، tool-calling بومی، مدل محلی،
بکاپ کامل، preview آفلاین، مهاجرت i18n، و بقیه‌ی فهرست.

---

## Merge note (maintainer, 2026-10-30)

This tree was merged with the maintainer's parallel remediation of the same two reports
(base: the external 4.11.0 pass; the maintainer's stricter variants kept where they
overlapped — AAD credential binding, SHA-pinned actions + CodeQL, signer comparison in
the updater, push-deletion sync, auto-continue for truncated prose, per-provider cost
tracking, Z.ai save-time host guard, and the intent-without-action continuation).
The "never compiled" status above no longer applies to the merged tree: it is verified
by the project's CI (unit tests + audits) on the merged commit.
