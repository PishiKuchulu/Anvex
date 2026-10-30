# گزارش مشکلات Anvex — نسخه 4.10.5 (versionCode 83)

تاریخ بررسی: ۶ آبان ۱۴۰۵ (۲۸ اکتبر ۲۰۲۶)
منبع: `Anvex-main.zip` (مخزن `PishiKuchulu/Anvex`)

---

## ۱. دامنه و روش بررسی

**چیزی که انجام شد:** مرور ایستا (static review) سورس. حدود ۴۴ هزار خط Kotlin، فایل‌های Gradle، Manifest، XMLهای منابع، ورک‌فلوهای CI، داکیومنت‌ها و assetهای harness.

**چیزی که انجام نشد:** پروژه build/اجرا نشد و روی دستگاه تست نشد. هر مورد «اطمینان» دارد:
- **بالا:** مستقیم در کد دیدم و منطقش روشنه.
- **متوسط:** بر اساس خوندن کد و رفتار شناخته‌شده‌ی API استنتاج کردم، قبل از اصلاح روی دستگاه تأیید کنید.

**عمق بررسی:**
- **خط‌به‌خط خوانده شد:** مسیرهای امنیتی-حساس. `KeystoreCrypto`، `ProviderRepository`، سرورهای لوکال، `HarnessBridge`، `ActionSafetyPolicy`/`SensitivePaths`/`ActionDispatcher`، ترمینال/شل، `AgentToolExecutor`، `ZipCodec`، `RealProjectFileSystem`، `PortableBackup`، `GitHubRepository`، `LanAccessManager`، `AppUpdateChecker`، `McpClient`، نصب Skill/Plugin، `PiEngine`/`NodeMobileRuntime`، `MainActivity`، ورک‌فلوها. در دور دوم: `SigningKeyManager`، `CloudBuildRepository`، `ToolchainManager`، `AndroidBuildEngine`، `Sse`/`OpenAiCompatibleClient`/`FallbackProviderClient`، `ContextBuilder`، `BoltStreamParser.flush` و حلقه‌ی استریم در `ChatSession`، `ZaiAccountCoordinator`، کد native.
- **فقط با الگوجویی (grep) یا نمونه‌برداری:** UI (ChatScreen، Theme، Markdown)، کلاینت‌های Anthropic/Gemini/OpenCode، بقیه‌ی `ChatSession`، `VideoRenderer`، تست‌ها.
- **بررسی نشد:** باینری‌های داخل assets (`pi-runtime.zip`، `npm.zip`، `babel.min.js`، `mermaid.min.js`، libnode).

### مسیرهای کوتاه‌شده در گزارش

| نماد | مسیر کامل |
|---|---|
| `[A]` | `core/android/src/main/kotlin/com/anvex/core/android` |
| `[E]` | `core/engine/src/main/kotlin/com/anvex/core/engine` |
| `[AI]` | `core/ai/src/main/kotlin/com/anvex/core/ai` |
| `[APP]` | `app/src/main/kotlin/com/anvex/app` |

---

## ۲. خلاصه

| شدت | تعداد | معنی |
|---|---|---|
| 🔴 بالا | 7 | نشت کلید/راز، دور زدن مرز امنیتی، یا باگ عملکردی جدی |
| 🟠 متوسط | 17 | ریسک واقعی ولی با شرط، یا باگ مؤثر روی کاربر |
| 🟡 پایین | 16 | کیفیت، پایداری، سازگاری، هزینه‌ی نگهداری |

### مهم‌ترین‌ها

1. **H-01** سوکت کنترل Pi بدون احراز هویت است و API key را روی آن می‌فرستد.
2. **H-02 / H-03** محافظت از فایل‌های حساس (`.env` و …) با ترفندهای ساده دور زده می‌شود و ممکنه راز به ارائه‌دهنده‌ی ابری برسه.
3. **H-04** بعد از تغییر Provider، رویدادهای چت در Harness دیگه به UI نمی‌رسن.
4. **H-05** کلید MCP می‌تونه بی‌صدا حذف بشه و فایل پیکربندی atomic نیست.
5. **M-05** گیت داخلی `.gitignore` و `.github/` رو هرگز track نمی‌کنه و همه‌چیز (حتی `node_modules` و `.env`) رو stage می‌کنه.
6. **H-06** اگر استریم مدل وسط یک فایل قطع بشه (یا به سقف توکن برسه)، فایل ناقص مثل فایل کامل روی دیسک نوشته می‌شه و `finish_reason` هیچ‌جا بررسی نمی‌شه.
7. **H-07** AI و همگام‌سازی می‌تونن فایل workflow رو عوض کنن که با Secretهای امضا (keystore و رمزها) اجرا می‌شه.

### چیزهایی که خوب انجام شده

این‌ها رو برای اینکه حفظ بشن می‌نویسم:
- `allowBackup=false` همراه با `data_extraction_rules` و network security config فقط-HTTPS (cleartext فقط loopback).
- سرورهای لوکال فقط روی `127.0.0.1` bind می‌شن، توکن per-session دارن، Host رو چک می‌کنن و مقایسه‌ی توکن constant-time است.
- ابزار `fetch` ایجنت در برابر SSRF مقاوم است (`PublicOnlyDns`، فقط https، redirect دستی و چک هر hop).
- محدودیت اندازه/تعداد در ZIP، tar و نصب پلاگین (ضد zip-bomb و zip-slip).
- PBKDF2-SHA256 با ۶۰۰ هزار تکرار + AES-GCM برای بکاپ، و رمزنگاری کلیدها با AndroidKeyStore.
- Room: `exportSchema=true`، بدون `fallbackToDestructiveMigration`.
- `onTimeout` سرویس foreground برای Android 15 مدیریت شده.
- پلاگین‌ها پیش‌فرض غیرفعال‌اند و entrypoint اجرایی ندارن.
- README در مورد کلید debug عمومی و مسیرهای شبکه صادقانه است.

---

## ۳. 🔴 شدت بالا

### H-01 — سوکت کنترل Pi بدون احراز هویت؛ API key روی آن ارسال می‌شود
- **اطمینان:** بالا
- **محل:** `[A]/runtime/pi/PiEngine.kt:95` (ساخت `ServerSocket`) و `:163` (`configJson.put("apiKey", …)`)
- **مشکل:** `ServerSocket(0, 1, 127.0.0.1)` اولین اتصال ورودی رو می‌پذیره و بدون هیچ handshake/توکنی، JSON پیکربندی شامل `apiKey` متن‌ساده رو روی همون سوکت می‌نویسه. هر برنامه‌ی دیگه‌ی روی گوشی می‌تونه پورت‌های loopback رو اسکن کنه و قبل از درایور Node وصل بشه.
- **اثر:** مهاجم محلی کلید API کاربر رو می‌گیره و می‌تونه فرمان‌های Pi (ابزارهای فایل/شل) رو هم بفرسته.
- **مقایسه:** `NodeMobileRuntime` همین کار رو با توکن انجام می‌ده (خط 100، 176–181)، یعنی الگوی درست در همین کدبیس هست.
- **راه‌حل:**
  - یک توکن تصادفی ۲۵۶ بیتی تولید کنید و از طریق env/argv به درایور بدید.
  - پیام اول سوکت باید توکن باشه و با `MessageDigest.isEqual` چک بشه.
  - بهتره `apiKey` اصلاً روی سوکت نره (از env یا فایل با مجوز ۶۰۰ در دایرکتوری private بدید) یا از Unix domain socket در مسیر private استفاده کنید.
  - فقط یک اتصال بپذیرید و بعد از آن `ServerSocket` رو ببندید.

### H-02 — دور زدن allow-list ترمینال/شل برای فرمان‌های AI (فیلتر لغوی)
- **اطمینان:** بالا (دور زدن)؛ متوسط (اینکه خروجی به مدل برگرده)
- **محل:** `[E]/SensitivePaths.kt:86` (`commandReferencesSensitive`)، `[A]/runtime/AndroidTerminalSession.kt:37`، `[A]/runtime/AndroidShellRuntime.kt:65`
- **مشکل ۱:** تشخیص فایل حساس فقط روی توکن‌های متنی خام انجام می‌شه (فقط quote از دو سر برداشته می‌شه). `cat .e''nv` یا `cat .e"n"v` برای فیلتر معادل `.env` نیست، ولی شل اون رو `.env` می‌کنه. در allow-list هم `cat`، `head`، `tail`، `grep`، `find` مجازن.
- **مشکل ۲:** `AndroidTerminalSession.send` (برای `origin=AI`) بررسی کاراکترهای glob (`* ? [ { !`) رو ندارد، ولی `AndroidShellRuntime.isAiSafeCommand` داره. پس `cat .e?v` در ترمینال رد می‌شه.
- **اثر:** خواندن `.env`، `*.pem`، `.git/config` و … توسط فرمان AI. اگر خروجی به مدل برگرده، راز به ارائه‌دهنده‌ی ابری می‌رسه.
- **راه‌حل:**
  - فرمان‌های «فقط-خواندنی» رو به‌جای شل، **نیتیو در Kotlin** پیاده کنید (`ls/cat/head/tail/grep/find` روی `ProjectFileSystem`) و همون‌جا `SensitivePaths` رو روی مسیر **نرمال‌شده/canonical** اعمال کنید.
  - اگر شل لازمه، قبل از اجرا فرمان رو با یک parser واقعی توکن‌بندی کنید (نه split روی فاصله) و هر quote/escape/glob/expansion رو رد کنید.
  - منطق رو بین ترمینال و شل یکی کنید (الان دو کپی ناهمسان وجود داره).

### H-03 — دور زدن محافظت فایل‌های حساس با `copy`/`rename`
- **اطمینان:** بالا
- **محل:** `[E]/ActionDispatcher.kt:158` (`isProtectedPath` فقط `.git` و `.anvex`)، `:207` (`rename`)، `:232` (`copyFile`)، و `[A]/tools/AgentToolExecutor.kt` (`read`)
- **مشکل:** ابزار `read` ایجنت `.env` رو رد می‌کنه، ولی AI می‌تونه با اکشن شل مجازِ مترجم (`CommandTranslator`: `mv`/`cp`) `.env` رو به `config.txt` تغییر نام بده/کپی کنه و بعد با `read config.txt` محتوا رو بخونه (نتیجه‌ی ابزار به مدل برمی‌گرده، `ChatSession.kt:462`). همچنین نوشتن روی `.env` و پوشه‌ی `.anvex-pi` (کد افزونه‌ی Pi) مسدود نیست.
- **اثر:** نشت رازها به ارائه‌دهنده‌ی ابری از طریق prompt injection (مثلاً از یک صفحه‌ی وب که `fetch` می‌کنه).
- **راه‌حل:**
  - `SensitivePaths.isSecretFile` رو روی **مبدأ** `rename/copy` و روی **مقصد** هم اعمال کنید.
  - `.anvex-pi` رو به `isProtectedPath` اضافه کنید.
  - برای نوشتن روی فایل‌های حساس تأیید صریح کاربر بخواید.

### H-04 — پل Harness بعد از تغییر Provider رویدادها را دیگه به UI نمی‌رسونه
- **اطمینان:** متوسط-بالا (استنتاج از کد؛ تست دستگاه لازم)
- **محل:** `[A]/harness/HarnessBridge.kt:181` (`startSessionWatcher`)، `:195`، و `start()`
- **مشکل ۱:** `startSessionWatcher` مقدار `session` رو یک‌بار می‌خونه و `s.state.collect { … }` می‌زنه. `StateFlow` هرگز تموم نمی‌شه، پس حلقه‌ی `while(true)` هیچ‌وقت `session` جدید رو برنمی‌داره. بعد از اینکه `collectLatest` سشن جدید ساخت، رویدادهای `delta/turn_end/artifact` از سشن قدیمی (بسته‌شده) می‌آد.
- **مشکل ۲:** `collectLatest` روی `observeAll()` با **هر تغییری در جدول Providerها** (حتی ویرایش اسم یک Provider غیرفعال) سشن رو می‌بنده و دوباره می‌سازه. اگه وسط استریم باشه کنسل می‌شه.
- **راه‌حل:** سشن رو در یک `StateFlow<ChatSession?>` نگه دارید و با `flatMapLatest { it?.state ?: emptyFlow() }` جمع کنید. سشن رو فقط وقتی دوباره بسازید که Provider پیش‌فرض، کلید، URL یا مدل واقعاً عوض شده.

### H-05 — پیکربندی MCP: از دست رفتن بی‌صدای کلید، نوشتن غیر-atomic، race
- **اطمینان:** بالا
- **محل:** `[A]/harness/HarnessBridge.kt:592` (`mcpAdd`)، `:871` (`saveMcpConfigs`)، تابع `mcpConfigs()` (حدود خط 858)
- **مشکل:**
  - `runCatching { KeystoreCrypto.encrypt(...) }.getOrNull()` در `mcpAdd` و `saveMcpConfigs`: اگه رمزنگاری fail کنه، کلید `null` ذخیره می‌شه ولی پاسخ `ok` برمی‌گرده.
  - `mcpConfigs()` وقتی رمزگشایی fail کنه کلید رو `null` می‌ذاره. با اولین `mcpAdd/mcpRemove` بعدی، کلیدِ همه‌ی سرورهایی که رمزگشاییشون fail شده **برای همیشه حذف می‌شه**.
  - `file.writeText(...)` غیر-atomic است و read-modify-write قفل نداره. کرش وسط نوشتن ⇒ فایل خراب ⇒ `getOrDefault(emptyList())` یعنی «هیچ سروری نیست» و ذخیره‌ی بعدی روی اون می‌نویسه.
- **راه‌حل:** از همان الگوی `writeAtomically` فایل‌سیستم استفاده کنید، یک `Mutex` بذارید، خطای رمزنگاری رو به UI برگردونید، و کلید ناخوانا رو به‌صورت «نیازمند ورود مجدد» نگه دارید (حذفش نکنید). بهتره MCP رو در Room ذخیره کنید.

### H-06 — خروجی ناقص مدل به‌عنوان فایل کامل نوشته می‌شود
- **اطمینان:** بالا
- **محل:** `[E]/BoltStreamParser.kt:204` (`flush`)، `[A]/session/ChatSession.kt:391` (`Completed`)، `:401`، `:404`، `:415`
- **مشکل:** وقتی استریم قطع بشه (خطای شبکه، `finish_reason=length`، `stop_reason=max_tokens`)، `flush(roundId)` اکشن فایلِ نیمه‌باز رو با محتوای ناقص «می‌بنده» و `onActionClose` رو صدا می‌زنه، یعنی نیمه‌ی فایل روی دیسک نوشته می‌شه. `StreamEvent.Completed.finishReason` هیچ‌جا مصرف نمی‌شه (فقط `usage`)، پس برای حالت `length` حتی پیامی به کاربر نمی‌رسه. در مسیر خطا هم `flush` قبل از بررسی `failure` اجرا می‌شه. در لغو دستی هم بین `flush` و `consumer.cancel()` یک race کوچک وجود داره.
- **اثر:** `package.json`، HTML یا Kotlin نیمه‌کاره بی‌صدا در پروژه می‌نشینه، بیلد/پیش‌نمایش می‌شکنه و کاربر دلیلش رو نمی‌فهمه. چون فایل «اعمال‌شده» ثبت می‌شه، MutationLog هم نسخه‌ی ناقص رو ثبت می‌کنه.
- **راه‌حل:**
  - `flush` فقط اکشن‌های بسته‌شده رو اعمال کنه و اکشن باز رو به‌صورت «ناقص» گزارش/دور بریزه (نه اعمال).
  - `finishReason` در `{length, max_tokens, MAX_TOKENS}` یا قطع بدون بسته شدن تگ ⇒ پیام «خروجی کامل نشد» + دکمه‌ی «ادامه بده» که فایل ناقص رو ادامه می‌ده.
  - تست رگرسیون: استریم مصنوعی که وسط `<boltAction type="file">` قطع می‌شه.

### H-07 — AI و همگام‌سازی می‌تونن workflowی را عوض کنن که با Secretهای امضا اجرا می‌شه
- **اطمینان:** متوسط-بالا (زنجیره‌ای است و به فعال بودن cloud signing وابسته است)
- **محل:** `[E]/ActionDispatcher.kt:158` (`isProtectedPath` فقط `.git`/`.anvex`)، `[A]/signing/SigningKeyManager.kt` (`pushSigningSecrets`)، `[A]/repo/CloudBuildRepository.kt:537` (قالب workflow)، `[A]/repo/GitHubRepository.kt` (`push` شامل `.github/`)
- **مشکل:** `pushSigningSecrets` فایل keystore و رمزها (`KEYSTORE_BASE64`، `KEYSTORE_PASSWORD`، `KEY_PASSWORD`) رو به Actions Secrets مخزن می‌فرسته. اما مسیر `.github/workflows/**` برای AI محافظت‌شده نیست و `push` هم همه‌ی `.github/` رو همگام می‌کنه. یک مدل دچار prompt injection (مثلاً از صفحه‌ای که `fetch` کرده) می‌تونه workflow رو طوری بنویسه که `${{ secrets.KEYSTORE_BASE64 }}` رو به بیرون بفرسته؛ با push بعدی (یا `workflow_dispatch`) اجرا می‌شه.
- **اثر:** سرقت کلید امضا = از دست رفتن هویت برنامه (کسی می‌تونه نسخه‌ی جعلی با همون امضا منتشر کنه).
- **راه‌حل:**
  - `.github/workflows/**` رو به `isProtectedPath` اضافه کنید (نوشتن فقط با تأیید صریح و نمایش diff).
  - قبل از `push` اگر workflow تغییر کرده، هشدار جداگانه بدید.
  - Secretهای امضا رو در یک GitHub **Environment** با required reviewers نگه دارید و فقط برای تگ/شاخه‌ی محافظت‌شده در دسترس باشه.
  - ترجیحاً امضا روی دستگاه (کلید هرگز خارج نشه) و در ابر فقط بیلد unsigned.

---

## ۴. 🟠 شدت متوسط

### M-01 — `ActionSafetyPolicy` یک deny-list است و UI «Safe» نشان می‌دهد
- **اطمینان:** بالا
- **محل:** `[E]/ActionSafetyPolicy.kt` (`assess`، `segmentRisk`)، `[APP]/ui/screens/developer/DeveloperCenterScreen.kt:152`
- **مشکل:** هر چیزی که در لیست الگوهای خطرناک نباشه «SAFE» حساب می‌شه. نمونه‌ها: `./x.sh` (نام فرمان بعد از `substringAfterLast('/')` می‌شه `x.sh` و هیچ قاعده‌ای نداره)، `npm install` (اجرای lifecycle scripts)، `npx pkg`، `make`، `python3 script.py`، `curl -o`.
- **اثر:** در مسیر AI، allow-list شل جلوی اجرا رو می‌گیره (پس اثر مستقیم محدود است)، ولی خود پیام «Safe classification» در Developer Center و برچسب‌های `Risk.SAFE` در مسیرهای دیگه (مثلاً `AgentTaskBridge`) اطمینان کاذب می‌ده.
- **راه‌حل:** مدل رو معکوس کنید: allow-list صریح برای اتومات، و هر فرمان قادر به اجرای کد (interpreter، package manager، اسکریپت) = نیازمند تأیید. برچسب «Safe» رو به «بدون الگوی خطر شناخته‌شده» تغییر بدید.

### M-02 — LAN server: HTTP ساده، حافظه‌ی نامحدود، DoS بدون احراز هویت
- **اطمینان:** بالا
- **محل:** `[A]/reliability/LanAccessManager.kt:42` (bind `0.0.0.0`)، `:96` (`reader.readLine()`)
- **مشکل:**
  - توکن Bearer روی HTTP ساده در LAN رد و بدل می‌شه و قابل شنود است (README/KNOWN_LIMITATIONS هم می‌گه).
  - `BufferedReader.readLine()` طول خط رو محدود نمی‌کنه. `take(2000)` بعد از خوندن کل خط اعمال می‌شه. یک کلاینت بدون احراز هویت می‌تونه بدون ارسال `\n` حافظه رو پر کنه.
  - `soTimeout=3s` با هر بایت دریافتی ریست می‌شه. ۸ کلاینت کند همه‌ی slotها رو می‌گیرن.
  - `jsonEscape` فقط `\`، `"` و `\n` رو escape می‌کنه (کاراکترهای کنترلی دیگه JSON رو خراب می‌کنن) و `take(500)` بعد از escape ممکنه وسط یک دنباله‌ی escape ببره.
  - query string جدا نمی‌شه (`/api/status?x=1` → 404).
- **راه‌حل:** خواندن خط با سقف (مثلاً ۸ KB)، deadline کلی برای هر اتصال، TLS (گواهی self-signed با pinning یا QR-pairing)، `kotlinx.serialization` برای JSON. اگر ارزش محصولی کمی داره، حذفش رو هم در نظر بگیرید.

### M-03 — به‌روزرسانی داخل برنامه: checksum اختیاری و هم‌منبع
- **اطمینان:** بالا
- **محل:** `[A]/update/AppUpdateChecker.kt:150` و `download`، `[APP]/ui/components/AppUpdate.kt:114`
- **مشکل:**
  - اگر asset `.sha256` در Release نباشه، دانلود بدون راستی‌آزمایی پذیرفته می‌شه.
  - checksum از همون Release (همون ریشه‌ی اعتماد) خونده می‌شه، پس فقط ضد خرابی است، نه ضد دستکاری.
  - قبل از نصب، گواهی امضای APK با گواهی برنامه‌ی نصب‌شده مقایسه نمی‌شه. (Android خودش در نصب بررسی می‌کنه، ولی برای debug با کلید عمومی این محافظت بی‌اثر است.)
  - `downloadUrl` و `sha256Url` از JSON خوانده می‌شن و host اون‌ها اعتبارسنجی نمی‌شه.
  - فایل سه بار خونده می‌شه (دانلود، شمارش طول، هش).
  - APK بزرگ (۶۰–۱۶۰ MB) در `cache/shared` بعد از نصب پاک نمی‌شه.
- **راه‌حل:** checksum اجباری، اعتبارسنجی host (`github.com/PishiKuchulu/Anvex/releases/download/…`)، مقایسه‌ی `PackageManager.getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)` با امضای فعلی، هش هم‌زمان با دانلود، و پاک‌سازی بعد از نصب.

### M-04 — همگام‌سازی GitHub
- **اطمینان:** بالا برای ۱ و ۴؛ متوسط برای ۲ (بر اساس رفتار `git/trees` با `base_tree`)
- **محل:** `[A]/repo/GitHubRepository.kt:62` (`scope=repo`)، `:205` (`base_tree`)، `push/pull`، و `[E]/ArtifactPackaging.kt:28`
- **مشکل‌ها:**
  1. OAuth با `scope=repo` دسترسی کامل به همه‌ی مخازن خصوصی می‌ده. برای sync یک مخزن، بیش از حد است.
  2. **حذف/تغییر نام محلی روی ریموت اعمال نمی‌شه:** درخت با `base_tree` ساخته می‌شه و مسیرهای حذف‌شده با `sha: null` علامت نمی‌خورن، پس فایل‌های حذف‌شده در GitHub می‌مونن (تغییر نام = دو نسخه).
  3. `pull` فایل‌های محلی رو بدون تشخیص تعارض overwrite می‌کنه.
  4. تشخیص راز ضعیف است. `placeholderMarks` شامل `"token"`، `"password"`، `"secret"`، `"example"`، `"$"` است، پس مقدار واقعیِ حاوی این کلمات «placeholder» حساب می‌شه. الگوی توکن‌های شناخته‌شده (`ghp_`، `sk-`، `AKIA`، `AIza`، `-----BEGIN … PRIVATE KEY`، JWT) وجود نداره و فقط بر اساس **نام کلید** تشخیص می‌ده. `.env.development`، `.npmrc`، `service-account.json`، `.dev.vars` (Cloudflare) در `excludedNames` نیستن.
  5. دو سیاست موازی و ناهمسان: `SensitivePaths` در مقابل `ArtifactHygienePolicy`.
  6. خطای HTTP فقط کد رو نشون می‌ده (بدون پیام GitHub، rate-limit، SSO).
  7. یک درخواست HTTP به‌ازای هر blob (کند).
- **راه‌حل:** fine-grained PAT یا GitHub App با `contents:write` فقط روی یک مخزن؛ ثبت `sha:null` برای حذف‌ها؛ پیش‌نمایش diff قبل از pull؛ یک `SecretPolicy` مشترک (قانون‌های prefix توکن + آنتروپی + فهرست نام/پسوند)؛ نمایش `message` و هدرهای rate-limit.

### M-05 — GitLite: `.gitignore`/`.github` هرگز track نمی‌شن، `addAll` همه‌چیز را stage می‌کند
- **اطمینان:** بالا
- **محل:** `[E]/vcs/GitLite.kt:319` (`rel.startsWith(".git")`)، `workingTreeFiles()`، و `[A]/harness/HarnessBridge.kt:410`
- **مشکل:**
  - شرط `startsWith(".git")` علاوه بر `.git/`، فایل‌های `.gitignore`، `.gitattributes`، `.github/` (workflows) رو هم نادیده می‌گیره.
  - هیچ پشتیبانی از `.gitignore` نیست. `addAll` همه رو stage می‌کنه: `node_modules/`، `build/`، `.anvex/`، `.env`، keystoreها.
  - `git(op, …)` در bridge برای هر عملی (حتی `status`) `GitLite.init(root)` می‌زنه و ناخواسته `.git` در پوشه‌ی کاربر می‌سازه.
- **راه‌حل:** `startsWith(".git")` رو به `== ".git"` یا segment اول == `.git` تغییر بدید؛ `.gitignore` + `.anvexignore` + `SecretPolicy` اعمال کنید؛ `init` فقط با درخواست صریح.

### M-06 — صادرات ZIP بدون فیلتر راز
- **اطمینان:** بالا برای `ZipCodec`؛ مسیر JSZip/`ArtifactManager` رو بررسی نکردم
- **محل:** `[E]/ZipCodec.kt:31` (`isExportable`)
- **مشکل:** `.env`، `*.keystore`، `*.pem`، `.npmrc`، و `.anvex/` (به‌جز `logs/`) صادر می‌شن. `readFileBytes(path) ?: continue` فایل ناخوانا رو بی‌صدا حذف می‌کنه. فایل‌های موقت `.<name>.tmp-<uuid>` (باقی‌مانده از کرش) هم صادر می‌شن.
- **راه‌حل:** سیاست راز مشترک روی همه‌ی مسیرهای export/sync، هشدار قبل از export اگر راز پیدا شد، و گزارش فایل‌های skip‌شده.

### M-07 — `RealProjectFileSystem`: symlink، حافظه، کارایی
- **اطمینان:** متوسط (symlink)، بالا (بقیه)
- **محل:** `[A]/fs/RealProjectFileSystem.kt:111` (`delete`)، `:151` (`walk`)، `readFile`
- **مشکل:**
  - `File.deleteRecursively()` و `walkTopDown()` در Kotlin به symlink به پوشه‌ها دنبال می‌کنن. ترمینال (`ln -s`) یا `npm` می‌تونن symlink بسازن؛ حذف پروژه/`delete(recursive)` می‌تونه به بیرون پروژه برسه.
  - `walk()` مسیرهایی برمی‌گردونه که `resolve()` برای اون‌ها `SecurityException` می‌ده، پس export/جستجو با یک symlink کلاً fail می‌شه.
  - `readFile` کل فایل رو در حافظه می‌خونه (`HarnessBridge.readFile` اول می‌خونه، بعد اندازه رو چک می‌کنه، پس OOM قبل از چک).
  - `fd.sync()` به‌ازای هر نوشتن (چندین دهها ms روی فلش) وقتی ایجنت ده‌ها فایل می‌نویسه کند است.
- **راه‌حل:** `Files.walkFileTree` با `NOFOLLOW_LINKS`، رد symlink در `walk()`، چک سایز قبل از خواندن، و fsync فقط برای ویرایش‌های کاربر.

### M-08 — Providerها بی‌صدا ناپدید می‌شن؛ بازیابی همه‌چیز-یا-هیچ
- **اطمینان:** بالا
- **محل:** `[A]/repo/ProviderRepository.kt:198` (`toDomain`)، `replaceAllForRestore`، `validateBaseUrl`
- **مشکل:**
  - `toDomain()` در هر خطا (حتی رمزگشایی هدرها) `null` می‌ده و `mapNotNull` اون Provider رو از لیست حذف می‌کنه. کاربر فقط می‌بینه Provider «گم شده».
  - `replaceAllForRestore` از `save()` استفاده می‌کنه که `validateBaseUrl` رو اجرا می‌کنه. یک Provider با URL مثل `http://192.168.x.x` (که در نسخه‌های قدیمی مجاز بود) کل restore رو fail می‌کنه.
  - رمزنگاری بدون AAD است (هیچ اتصالی بین ciphertext و ردیف/URL نیست) و `KeyStore.getInstance().load()` در هر encrypt/decrypt تکرار می‌شه (`KeystoreCrypto.kt:87`).
- **راه‌حل:** بازگرداندن `ProviderConfig` با فلگ `credentialsUnavailable` به‌جای `null`، نمایش وضعیت در UI، و restore با «رد کردن موردهای نامعتبر + گزارش».

### M-09 — بازیابی بکاپ تنظیمات امنیتی را بدون تأیید برمی‌گرداند
- **اطمینان:** بالا
- **محل:** `[A]/PortableBackup.kt:60`، `[A]/repo/SettingsRepository.kt:156`
- **مشکل:** `restoreFromBackup` مقدارهای `lanEnabled`، `termuxBridgeEnabled`، `webToolsEnabled`، `webPort`، `agentEngine` رو مستقیم می‌نویسه. بازیابی روی گوشی جدید ممکنه بی‌صدا LAN server رو فعال کنه. `editorFontSize`/`chatFontSize` هم بدون `coerceIn` نوشته می‌شن (setterها clamp می‌کنن)، پس بکاپ دستکاری‌شده/قدیمی می‌تونه UI رو خراب کنه.
- **راه‌حل:** گزینه‌های حساس رو بازیابی نکنید یا با دیالوگ تأیید؛ clamp در restore.

### M-10 — `NodeCompatRuntime.FsBridge`: استثنا از مرز JS Bridge
- **اطمینان:** متوسط-بالا
- **محل:** `[A]/runtime/NodeCompatRuntime.kt` (متد `invoke`)
- **مشکل:** `PathSanitizer.sanitize(path) ?: return err(...)`. `sanitize` استثنا می‌ندازه، نه null، پس شاخه‌ی `?:` مرده است و برای مسیر مثل `../x` یک `IllegalArgumentException` از متد `@JavascriptInterface` عبور می‌کنه، که معمولاً باعث کرش اپ می‌شه. همچنین فقط از `File(projectRoot, safe)` استفاده می‌شه (بدون canonical/symlink check و نوشتن غیر-atomic).
- **راه‌حل:** `sanitizeOrNull`، try/catch کل متد، و استفاده از `RealProjectFileSystem`.

### M-11 — `MainActivity`: اشتراک‌گذاری، پرش تم/زبان در شروع
- **اطمینان:** بالا
- **محل:** `[APP]/MainActivity.kt:70` (`copyToCache`)، `:128`
- **مشکل:**
  - `copyToCache` کل محتوا رو کپی می‌کنه و **بعد** سقف ۲۰ MB رو چک می‌کنه. یک فایل چند GB قبل از حذف فضای حافظه رو پر می‌کنه. از `Thread {}` خام هم استفاده می‌شه.
  - `settings` با `initialValue = null` شروع می‌شه، `themeMode ?: SYSTEM` و `language ?: SYSTEM`، در حالی که پیش‌فرض ذخیره‌شده `DARK` است. پس فریم اول با تم سیستم رسم می‌شه و بعد عوض می‌شه، و جهت RTL/LTR هم ممکنه چشمک بزنه.
  - `requestPermissions` منسوخ است و بدون توضیح (rationale) درخواست می‌شه.
- **راه‌حل:** شمارش بایت حین کپی و قطع در سقف؛ تا لود شدن DataStore یک splash نگه دارید (یا `runBlocking` کوتاه روی `first()` در `Application`)؛ `registerForActivityResult`.

### M-12 — کلاینت MCP: ناسازگاری با Streamable HTTP
- **اطمینان:** بالا برای ۱–۳؛ متوسط برای تأثیر
- **محل:** `[AI]/mcp/McpClient.kt:131` و `[A]/harness/HarnessBridge.kt:637` (`mcpCall`)
- **مشکل:**
  1. هدر `Mcp-Session-Id` (برگشتی از `initialize`) ذخیره/ارسال نمی‌شه. `MCP-Protocol-Version` هم ارسال نمی‌شه.
  2. `mcpCall` یک کلاینت تازه می‌سازه و **بدون `initialize()`** مستقیم `tools/call` می‌زنه. سرورهای stateful رد می‌کنن.
  3. `nextId++` thread-safe نیست؛ `parseFrame` اگر id مطابق نباشه پاسخ یک id دیگه رو برمی‌گردونه.
  4. پاسخ‌های `text/event-stream` با `string()` کامل بافر می‌شن.
  5. ~~URL سرور اعتبارسنجی نمی‌شه~~ — **اصلاح دور سوم:** این ادعا نادرست بود؛ `McpServerConfig.init` آدرس رو هنگام decode اعتبارسنجی می‌کنه (http فقط برای loopback).
- **راه‌حل:** نگهداری session per-server، ارسال هدرها، `initialize` تنبل با cache، شمارنده‌ی `AtomicLong`، تطبیق دقیق id، پشتیبانی از DELETE session.

### M-13 — مدیریت Insets: چند لایه `imePadding` + `adjustResize` + edge-to-edge
- **اطمینان:** متوسط (رفتار واقعی روی دستگاه‌ها متفاوته)
- **محل:** `app/src/main/AndroidManifest.xml:59` (`adjustResize`)، `[APP]/MainActivity.kt` (`enableEdgeToEdge`)، `ui/components/ProjectScaffold.kt:133`، `ui/AnvexApp.kt:166`، `ChatScreen.kt:360`، `DirectChatScreen.kt:333`، `TerminalScreen.kt:219`
- **مشکل:** هر لایه `imePadding()` یا `consumeWindowInsets` جداگانه داره و کامنت‌ها نشون می‌دن چند بار «fix» شده. با `adjustResize` روی edge-to-edge حساب دوباره‌ی inset ممکنه به فاصله‌ی خالی زیر نوار یا حرکت‌نکردن کیبورد روی بعضی دستگاه‌ها منجر بشه.
- **راه‌حل:** یک استراتژی واحد: `adjustNothing` + `WindowInsets.ime` فقط در یک نقطه (بالاترین Scaffold)، بقیه فقط `consumeWindowInsets`. تست روی Android 11/13/15 و کیبوردهای Gboard/سامسونگ/MIUI.

### M-14 — زنجیره‌ی تأمین و انتشار (CI)
- **اطمینان:** بالا
- **محل:** `.github/workflows/release-signed.yml:71,109`، `build-apk.yml`، `app/build.gradle.kts:35`
- **مشکل:**
  - اکشن‌ها با تگ (`@v4`، `@v2`) pin شدن، نه commit SHA. `softprops/action-gh-release@v2` با `contents: write` در job امضا اجرا می‌شه.
  - **اصلاح دور سوم:** `release-signed.yml` در واقع job `verify` (تست + lint) دارد و `signed-release` با `needs: verify` به آن وابسته است، پس ادعای «بدون gate تست» نادرست بود و job منتشرکننده `signed-release` است نه `verify`. چیزی که درست مانده: نبود Environment محافظت‌شده (reviewer) برای Secretهای امضا.
  - `echo "${{ secrets.KEYSTORE_BASE64 }}"` رو مستقیم در اسکریپت shell interpolate می‌کنه (بهتره از env استفاده بشه).
  - validation برای `gradle-wrapper.jar` وجود نداره، `verification-metadata.xml` هم نیست، و Dependabot/CodeQL/secret-scanning پیکربندی نشده.
  - **کلید debug عمومی:** `keystore/debug.keystore` با رمز `android` (در `app/build.gradle.kts:35`) برای Release غلتان (تگ `latest`) استفاده می‌شه. README صادقانه می‌گه، ولی هر کسی می‌تونه APKی بسازه که روی نصب debug شما «به‌روزرسانی» حساب بشه.
- **راه‌حل:** pin با SHA، `needs:` تست‌ها در job انتشار، GitHub Environment با required reviewers، `gradle/actions/wrapper-validation`، `dependency-verification`، و برای nightly از یک keystore **جداگانه در secrets** استفاده کنید. همون اثر «نصب روی نسخه‌ی قبلی» رو بدون کلید عمومی می‌ده.

### M-15 — کد اجرایی هم‌UID می‌تونه حالت برنامه را دستکاری کند (ریسک طراحی)
- **اطمینان:** متوسط (به تأیید کاربر برای اجرا وابسته است)
- **محل:** `[A]/runtime/*` (ترمینال/Node/Pi)، `ProviderRepository`
- **مشکل:** پردازش‌های Node/shell با همان UID برنامه اجرا می‌شن و دیتابیس Room و `filesDir` رو می‌تونن بخونن/بنویسن. کلید API رمزگشایی نمی‌شه (Keystore)، ولی `baseUrl` یک Provider در DB قابل تغییره، پس بعد از آن برنامه خودش کلید رو رمزگشایی و به URL مهاجم می‌فرسته. فایل `mcp-servers.json` و دایرکتوری پلاگین‌ها هم همین‌طور.
- **راه‌حل ارزان:** `baseUrl` و `model` رو به‌عنوان **AAD** در AES-GCM کلید API وارد کنید تا هر تغییر در DB رمزگشایی رو بشکنه. برای MCP هم همین الگو. (جزئیات در فایل ایده‌ها.)

### M-16 — کلاینت OpenAI-compatible و Fallback
- **اطمینان:** بالا
- **محل:** `[AI]/OpenAiCompatibleClient.kt` (`runAttempt`)، `[AI]/Sse.kt` (`stream`، `mapHttpError`)، `[AI]/FallbackProviderClient.kt`
- **مشکل‌ها:**
  1. `stream_options: {include_usage: true}` ارسال نمی‌شه، پس در OpenAI/OpenRouter و مشابه‌ها `usage` هیچ‌وقت نمی‌رسه (شمارش توکن/هزینه خالیه).
  2. پاسخ HTTP 200 با بدنه‌ی JSON غیر-SSE (خطای gateway مثل LiteLLM/پروکسی) فقط «Stream ended without content» می‌ده و پیام اصلی گم می‌شه.
  3. بازتلاش بدون `reasoning_effort` روی **هر** 400 (حتی «طول زمینه زیاده») انجام می‌شه و درخواست دوبل می‌شه.
  4. کدهای 402 (اعتبار تموم)، 408، 409، 413، 422 همگی `PROTOCOL` می‌شن و `Retry-After` خونده نمی‌شه.
  5. `readUtf8Line()` بدون سقف طول خط است.
  6. در `FallbackProviderClient` رویدادهای `Reasoning` تلاش ناموفقِ اول در UI می‌مونن و Fallback بدون backoff انجام می‌شه.
- **راه‌حل:** `stream_options` (با فلگ per-provider برای سرورهایی که 400 می‌دن)، بررسی `Content-Type` و خواندن بدنه‌ی غیر-SSE، بازتلاش فقط وقتی پیام خطا به `reasoning_effort` اشاره می‌کنه، نگاشت 402/413 به نوع‌های مشخص، و خواندن `Retry-After`.

### M-17 — `ContextBuilder`: ارسال فایل حساس و انتخاب ایستای زمینه
- **اطمینان:** بالا
- **محل:** `[E]/ContextBuilder.kt` (`excluded`، `build`، `PRIORITY_FILES`)
- **مشکل‌ها:**
  1. `excluded()` فایل‌های راز رو حذف نمی‌کنه. اگر کاربر `.env` (یا هر فایل حساس) رو در ویرایشگر باز کرده باشه، `openFilePath` باعث می‌شه **کل محتوا در prompt** به ارائه‌دهنده‌ی ابری بره. `SensitivePaths` اعمال نمی‌شه.
  2. انتخاب محتوا ثابت و وب‌محوره: فقط `package.json`، `index.html`، `src/main.jsx` و … . در پروژه‌ی Kotlin/Python/Worker جز فهرست فایل‌ها محتوایی به مدل نمی‌رسه و مدل باید با ۴ دور `read` حدس بزنه.
  3. بودجه‌ی ثابت ۴۸٬۰۰۰ نویسه (مستقل از پنجره‌ی مدل)، فهرست فایل‌ها الفبایی بریده می‌شه (فایل‌های آخر الفبا نامرئی)، و `.take(MAX_TOTAL_CHARS)` ممکنه وسط یک فایل ببره.
  4. `fs.walk()` کل درخت رو در هر نوبت پیمایش می‌کنه.
- **راه‌حل:** `SecretPolicy` روی همه‌ی فایل‌های واردشده به prompt (با redact)، انتخاب زمینه بر اساس زبان/نوع پروژه + فایل‌های ذکرشده در پیام + اخیراً ویرایش‌شده‌ها + نتیجه‌ی جستجو، و بودجه‌ی وابسته به `contextWindow` مدل.

---

## ۵. 🟡 شدت پایین

### L-01 — سرورهای لوکال (Preview/Harness)
**محل:** `[A]/server/StaticPreviewServer.kt:60`، `HarnessWebServer.kt`
- استخر ۲ thread با صف نامحدود: دو اتصال کند (slowloris) بقیه رو بلاک می‌کنه.
- حلقه‌ی `accept` در هر خطا لاگ + ادامه می‌ده (احتمال حلقه‌ی داغ).
- کل فایل در حافظه خونده می‌شه و `Range` پشتیبانی نمی‌شه (seek ویدیو/صدا در Preview).
- `HarnessWebServer` برای دانلود از `isSecretFile` استفاده می‌کنه، نه `isSensitive`، پس `.anvex/` دانلودشدنیه (ناهمسان با Preview).
- کد تکراری (`readLine`، `respond`، `decodePath`، `mimeFor`)؛ اسم thread‌ها هنوز `bolt-*` است.
- `<domain>::1</domain>` در NSC احتمالاً برای IPv6 literal کار نمی‌کنه.

### L-02 — `HarnessBridge`: کارایی و هم‌زمانی
**محل:** `[A]/harness/HarnessBridge.kt` (سراسری، `:195` برای `delta`)
- `emitEvent("delta") { put("text", state.streamText) }` در هر تغییر **کل متن** رو دوباره می‌فرسته: مصرف O(n²) برای پاسخ‌های بلند.
- `runBlocking` در متدهای `@JavascriptInterface` روی thread باندر (کارهای سنگین مثل `walk()` باعث تأخیر WebView می‌شن).
- TOCTOU در `sendPrompt` بین چک `streaming` و `send`.
- `saveZip/shareZip` کل ZIP رو base64 از پل می‌گذرونه (حافظه ×۲–۳).
- ۱۰۱۳ خط در یک کلاس (god class)، همراه با `ChatScreen` (۱۳۲۳) و `ChatViewModel` (۱۱۴۶).

### L-03 — `AgentToolExecutor`
**محل:** `[A]/tools/AgentToolExecutor.kt:82` (`list`)
- ابزار `list` نام فایل‌های حساس (`.env`، `.git/…`) رو هم برمی‌گردونه (فقط نام).
- regex `(?is)<(script|style|…)\b.*?</\1>` روی ۱٫۵ MB ورودی ممکنه برای صفحه‌های مخرب کند بشه (backtracking درجه‌ی دوم).
- جستجو با scrape کردن HTML اندپوینت DuckDuckGo (شکننده).
- `webToolsEnabled` پیش‌فرض **روشنه**: سطح حمله‌ی prompt injection غیرمستقیم از صفحات وب بدون هیچ برچسب «محتوای غیرقابل‌اعتماد» یا محدودیت اقدام بعد از `fetch`.

### L-04 — ترمینال
**محل:** `[A]/runtime/AndroidTerminalSession.kt:42,58`
- `readAvailable` بایت‌ها رو chunk‌به‌chunk به UTF-8 تبدیل می‌کنه. کاراکتر چندبایتی (فارسی ۲ بایتی) در مرز chunk خراب می‌شه.
- `process.destroy()` فرآیندهای فرزند (مثلاً `node server.js`) رو نمی‌کشه.
- `cat` بدون آرگومان و `tail -f` (در allow-list AI) تا timeout/برای همیشه معلق می‌مونن.
- محیط پردازش scrub نمی‌شه (`LD_PRELOAD` فقط در `AndroidShellRuntime` حذف می‌شه) و `HOME` روی ریشه‌ی پروژه است.

### L-05 — نصب Skill از GitHub
**محل:** `[A]/harness/SkillGitInstaller.kt:91`، `[A]/reliability/PluginPackageInstaller.kt:42`
- همیشه `HEAD` شاخه‌ی پیش‌فرض دانلود می‌شه؛ بدون pin کردن commit/tag. SHA-256 بعد از دانلود فقط نمایش داده می‌شه، با چیزی مقایسه نمی‌شه.
- `parseOwnerRepo` هر URL شامل `github.com/` رو می‌پذیره (محدودیت فقط در bridge است).
- tar: هدر PAX/GNU long-name پشتیبانی نمی‌شه (مسیرهای >۱۰۰ نویسه خراب می‌شن).
- تطبیق دقیق `manifest.gitUrl == expectedGitUrl` شکننده است (`/` انتهایی، `.git`).
- `Json { ignoreUnknownKeys = false }` برای manifest: هر فیلد جدید، نصب رو می‌شکنه.

### L-06 — Python sandbox
**محل:** `app/src/main/assets/python/index.html:11,16`
- فقط `pyodide.js` دارای SRI است؛ فایل‌های wasm و stdlib که اون اسکریپت از CDN می‌گیره بدون hash pin می‌شن. آفلاین کار نمی‌کنه.

### L-07 — WebView Preview
**محل:** `[APP]/ui/screens/preview/PreviewScreen.kt:256`
- پل `__anvex` به همه‌ی فریم‌ها و کتابخانه‌های شخص ثالث (esm.sh) در صفحه وصله. فقط `post(json)` رو در بر داره، ولی پیام‌های کنسول به `vm.onBridgeMessage` می‌ره و اگه به مدل (برای auto-fix) برگرده، کانال prompt injection است.
- import map: نسخه‌ها `latest`/range غیر-pin → پیش‌نمایش غیرقابل بازتولید و سطح زنجیره‌ی تأمین.
- `shouldOverrideUrlLoading` برای iframe رفتار قابل‌اتکایی نداره؛ برای محدود کردن origin بهتره از `WebViewCompat.addWebMessageListener(..., allowedOriginRules)` استفاده بشه.

### L-08 — i18n، RTL، دسترسی‌پذیری
**محل:** `[APP]/i18n/Strings.kt`، `DirectChatScreen.kt:554`، `TerminalScreen.kt:259`
- `tr(english)` یک Map با کلید «متن انگلیسی» است (۴۸۵ جفت در کد): غلط املایی بی‌صدا به انگلیسی برمی‌گرده، متن‌های دارای متغیر/جمع قابل ترجمه نیستن، و مترجم نمی‌تونه از ابزار استاندارد (`strings.xml`) استفاده کنه.
- `Icons.Filled.Send` (منسوخ و **غیرآینه‌ای**) در `DirectChatScreen` و `TerminalScreen`: در RTL فارسی فلش ارسال جهت اشتباه رو نشون می‌ده. `ChatScreen` درست از `AutoMirrored` استفاده می‌کنه.
- `contentDescription` در جاهایی ثابت انگلیسیه (مثلاً `"Send"`، `"Run command"`) و ۲۰ از ۱۳۵ `Icon` مقدار `null` دارن (بعضی تزئینی‌اند، بقیه باید بررسی بشن).

### L-09 — مستندات و نام‌گذاری
- `docs/KNOWN_LIMITATIONS.md` عنوانش «Anvex 4.3.0» است در حالی که برنامه 4.10.5 است.
- دو `CHANGELOG.md` (ریشه ۸۱ KB، `docs/` ۶ KB)، بیش از ۶۰ فایل ممیزی/گزارش در `docs/` و `docs/audit/`.
- ابهام README: «any OpenAI-compatible endpoint (cloud or a local server)» ولی NSC و `validateBaseUrl` فقط loopback رو برای http مجاز می‌کنن؛ سرور LAN محلی (Ollama روی PC) عملاً نمی‌شه.
- بقایای نام `Bolt` در ۲۵ فایل (`BoltAction`، `BoltStreamParser`، نام thread‌ها)، در حالی که برنامه از bolt.diy جدا شده.

### L-10 — وابستگی‌ها و Target SDK
**محل:** `gradle/libs.versions.toml`، `app/build.gradle.kts`
- AGP 8.7.3، Kotlin 2.0.21، Compose BOM 2024.12.01، OkHttp 4.12.0، BouncyCastle 1.78.1، Room 2.6.1، Navigation 2.8.5. قدیمی‌اند؛ برای هر کدوم CVE/changelog رو بررسی و ارتقا بدید (من بدون شبکه نتونستم وضعیت CVE رو چک کنم).
- `compileSdk/targetSdk = 35`. برای رفتارهای Android 16 تست و ارتقا لازمه.
- snapshot‌های Room فقط برای نسخه‌های 2، 12، 13 وجود دارن؛ مایگریشن‌های 3–11 تست نشدن.

### L-11 — پوشش تست
- ۵۲ فایل تست unit و ۶ androidTest. بدون تست برای ViewModelها، سرورهای لوکال، `HarnessBridge`، `ProviderRepository` (رمزگشایی ناموفق)، `GitHubRepository` (push/pull)، `LanAccessManager` (KNOWN_LIMITATIONS هم به کمبود اشاره می‌کنه).
- تست‌های امنیتی «رگرسیونی» وجود دارن (`ExternalReviewRegressionTest`، `SecondReviewRegressionTest`) ولی bypassهای بالا (quote-splitting، copy/rename) پوشش داده نشدن.

### L-12 — موتور بیلد روی دستگاه در Android 10+ غیرفعال است
**محل:** `[A]/build/AndroidBuildEngine.kt:41`، `[A]/build/ToolchainManager.kt:80`
- `build()` برای `SDK_INT >= 29` بلافاصله خطای «W^X» برمی‌گردونه؛ یعنی تقریباً روی همه‌ی دستگاه‌های امروزی کل مسیر Toolchain (import، `SafeDirUnzip`، `BuildScriptRiskScanner`، UI مربوط) بی‌اثره و کاربر فقط پیام خطا می‌بینه. نگهداری این کد و UI بی‌ارزشه مگر اینکه برای ≤ 28 نگه داشته بشه.
- `ToolchainManager.importBundle` اول `root.deleteRecursively()` می‌زنه و بعد کپی می‌کنه (جایگزینی غیر-atomic: اگر کپی وسط کار fail کنه، toolchain قبلی هم رفته). `copyRecursively` بیت اجرایی (exec) فایل‌ها رو حفظ نمی‌کنه، پس بعد از import احتمالاً `canExecute()` خطای «not executable» می‌ده (اطمینان متوسط).
- `requestCancellation` فقط پردازش gradle رو می‌کشه، نه JVMهای فرزند. `environment().putAll(System.getenv())` کل env رو به build می‌ده. `newestApk` ممکنه APK قدیمی رو برگردونه چون خروجی‌ها قبل از build پاک نمی‌شن.

### L-13 — قالب workflow ابری و commit روی شاخه‌ی پیش‌فرض
**محل:** `[A]/repo/CloudBuildRepository.kt:131` (`ensureWorkflowOnBranch`)، `:537` (`WORKFLOW_TEMPLATE`)
- `ensureWorkflowFile` علاوه بر شاخه‌ی بیلد، یک commit («Add Anvex cloud build workflow») روی **شاخه‌ی پیش‌فرض** مخزن کاربر می‌زنه، بدون تأیید جداگانه.
- قالب `permissions:` نداره (توکن با دسترسی پیش‌فرض می‌دوئه)، `timeout-minutes`، cache گرِیدل و `concurrency` هم نداره و اکشن‌ها با تگ (`@v4`) pin شدن.
- انتخاب artifact بر اساس «نامش شامل apk باشه و بزرگ‌ترین id» است؛ در مخزنی با چند artifact مشابه ممکنه فایل اشتباه برداشته بشه.
- نکته‌ی خوب: `VARIANT` از طریق env می‌ره (ضد injection) و هدر Authorization در redirect به هاست‌های غیر API حذف می‌شه.

### L-14 — ورود Z.ai Account بر پایه‌ی پروتکل مهندسی‌معکوس‌شده
**محل:** `[AI]/zai/ZaiProtocol.kt:14`، `ZaiAccountCoordinator.kt`
- پروتکل از یک پروژه‌ی شخص ثالث (9Router) گرفته شده و مستند رسمی نداره؛ هر تغییر سرور اون رو می‌شکنه و ممکنه با شرایط استفاده‌ی سرویس هم سازگار نباشه (بررسی کنید).
- `authorizeUrl` فقط با `startsWith("https://")` چک می‌شه. اگر پاسخ `init` دستکاری بشه، کاربر به هر سایت https هدایت می‌شه. بهتره allow-list دامنه (`*.z.ai` و دامنه‌ی ZCode) اعمال بشه.

### L-15 — پل JNI Node
**محل:** `core/android/src/main/cpp/node_bridge.cpp`
- `ReleaseStringUTFChars(jstr, utf)` حتی وقتی `utf == nullptr` است صدا زده می‌شه و `jstr == null` بررسی نمی‌شه (رفتار تعریف‌نشده).
- `GetStringUTFChars` رشته‌ی «modified UTF-8» برمی‌گردونه؛ مسیرهای حاوی ایموجی/نویسه‌های غیر-BMP ممکنه خراب بشن (پروژه با نام فارسی/ایموجی). از `GetStringChars` + تبدیل UTF-16→UTF-8 استفاده کنید.
- حجم کد کم و ساختار درسته (`node::Start` فقط در پردازش قربانی).

### L-16 — کلید امضا و کریپتوی دست‌ساز
**محل:** `[A]/signing/SigningKeyManager.kt`
- RSA-2048 با اعتبار ۳۰ ساله و حداقل طول رمز ۶ نویسه؛ برای کلید بلندمدت RSA-3072/4096 و رمز حداقل ۱۲ نویسه بهتره.
- `pushSecret(owner, repo, …)` مقدار `owner/repo` رو بدون اعتبارسنجی در URL می‌ذاره (در `CloudBuildRepository` `validateTarget` وجود داره).
- `crypto_box_seal` با HSalsa20 دست‌نویس پیاده شده. تست‌های known-answer برای nonce و HSalsa20 هست (خوبه)، ولی تست سرتاسری «رمزنگاری با کد شما ← رمزگشایی با libsodium/BouncyCastle» یا بردار کامل libsodium وجود نداره. اگر ممکنه از کتابخانه‌ی آزموده‌شده (مثلاً lazysodium) استفاده کنید.

---

## ۶. پیشنهاد اولویت‌بندی رفع

| مرحله | موارد | چرا اول |
|---|---|---|
| **هفته‌ی ۱** | H-01، H-03، H-05، H-06، H-07، M-05 | نشت کلید/راز و از دست رفتن داده؛ رفع هر کدوم کوچکه |
| **هفته‌ی ۲** | H-02، H-04، M-02، M-08، M-16، M-17 | مرز امنیتی ایجنت و پایداری Provider/Harness |
| **هفته‌ی ۳** | M-03، M-04، M-06، M-09، M-14 | زنجیره‌ی انتشار، sync، export |
| **بعد** | بقیه‌ی M و L | کیفیت، سازگاری، مستندات |

> پیشنهاد: برای هر مورد H یک تست رگرسیونی بنویسید (بخصوص H-02/H-03 با ورودی‌های مخرب مثل `cat .e''nv` و `mv .env x.txt`).
