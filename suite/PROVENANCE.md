# Suite Provenance — المصادر المستوردة داخل aMiNo

aMiNo v2 يدمج جناحاً من ثلاثة تطبيقات بنيات بناء مستقلة. هذه الوثيقة توثّق مصدر كل شجرة والتعديلات المحلية عليها — لا شيء آخر تغيّر.

| الشجرة | المصدر الأعلى | المرجع المثبت | التعديلات المحلية |
|---|---|---|---|
| `suite/rinigan/` | github.com/AMINODa/Rinigan-App | tag `v6.3` (versionCode 35) | إظلال الثيم بألوان aMiNo + تسمية العرض — انظر القسم 2 |
| `suite/lspatch/` | github.com/JingMatrix/LSPatch | canary `492` (كومت `4d57363`، 2026-10-23) | ترقيع fallback للإصدار + إظلال seed + shims AGP 8.11 — انظر القسم 3 |
| `suite/lspatch/core/` | github.com/JingMatrix/LSPosed | `e00c5c5038bbcc14e0b382ab893301d6993e6989` (submodule core في v1.2) | بلا تعديل |

تم حذف مجلدات `.git` من الأشجار المستوردة (لأنها تُصبح جزءاً من مستودع aMiNo). لذلك:

- `suite/lspatch/VENDORED_VERSION` = `492` — verCode الإصدار الرسمي المطابق (canary `492`، كومت `4d57363`؛ أصول الـloader المستخرجة حرفياً من `manager-v1.2-492-release.apk`، ومصادر المدير/المحرك متزامنة مع نفس الكومت)، يُقرأ من `build.gradle.kts` عند غياب `.git`.
- fallback إصدار النواة في `build.gradle.kts` يعرض `1.0-e00c5c5` + هاش الكومت الكامل (للعرض في About فقط — لا استخدام وقت التشغيل).

## قواعد التحديث لاحقاً
1. أي تحديث للأشجار يجب أن يحافظ على applicationIds كما هي (`com.rinigan.app`, `org.lsposed.lspatch`) — الخطافات وسلطة Provider والـsocket وارتباط الوحدات محفورة فيها.
2. Rinigan تُبنى بشهادتها الأصلية `rinigan.keystore` (ترقية موضعية لنسخ المستخدم).
3. لا يجوز تحديث الثيم بأي لون خارج هوية aMiNo (`#0B0B0E` أسطح `#131318/#16161B` حدود `#2B2B33` أحمر `#D32F2F/#E53935/#FF867C`).

## تدقيق r1433 — المزامنة الكاملة مع canary 492 (مقارنة ملفية ضد `4d57363`)
- بعد المقارنة الشاملة (سكربت `scripts/diff_upstream.py`) أصبحت الشجرة المورّدة **مطابقة 100% للـ canary 492** باستثناء الفروق الموثقة أعلاه فقط:
  - ملفات كانت لا تزال من عصر v1.2 وقد نُسخت من 492 حرفياً في r1433: `patch-loader/…/LSPApplication.java`، `patch-loader/…/GmsRedirector.java` (كان مفقوداً كلياً)، `patch/…/{LSPatch,ApkPatcher,ManifestOverrides}.java`، `share/java/…/{Constants,PatchConfig}.java`، `meta-loader/proguard-rules.pro`.
  - `manager/src/main/assets/keystore` غير موجود في `suite/lspatch/manager` **عمداً**: الأصول مركزية في موديل التطبيق `manager/src/main/assets/` (نفس md5 الموجود في upstream).
  - قواعد `consumerProguardFiles` أُضيفت للوحدات `daemon-service` (حزم IPC `org.matrix.vector.ipc/daemonservice`) و`share/android` (`org.lsposed.lspatch.share.remote`) و`manager-ui` — عقود binder تُحلّ بالاسم.
- ملاحظة تشغيلية: الترقيع عبر المحرك يتطلب **API 26+** عملياً (`java.nio.file.Files` في apkzlib؛ مدير upstream نفسه minSdk 28). أقل من ذلك: باقي aMiNo يعمل، والترقيع لا يُدعم — نفس سقف upstream المسموح.

## طريقة البناء (من جذر amino)
```bash
# Rinigan (AGP 8.2.2 / Gradle 8.7 / JDK 21)
cd suite/rinigan && JAVA_HOME=/home/z/jdk-21 ./gradlew :app:assembleRelease

# LSPatch manager + jar (AGP 9.3.1 / Gradle 9.6.1 / NDK 29.0.13113456 / JDK 21)
cd suite/lspatch && JAVA_HOME=/home/z/jdk-21 ./gradlew buildRelease -Dorg.gradle.jvmargs="-Xmx1792m"

# aMiNo (الجذر)
JAVA_HOME=/home/z/jdk-21 ./gradlew :manager:assembleRelease --no-daemon ...
```

## تدقيق r1434 — جذر فشل التثبيت INSTALL_PARSE_FAILED_MANIFEST_MALFORMED (TikTok)
- **الدليل الميداني**: مخرجات ترقيع TikTok من المستخدم (17 ملفاً من Drive) + سجل الفشل على الجهاز: `INSTALL_PARSE_FAILED_MANIFEST_MALFORMED` بعد DELETE_SUCCEEDED.
- **الجذر (واحد وليس عدة أسباب)**: محرر الـmanifest (`com.wind.meditor`) يحتاج جدول معرّفات سمات أندرويد من `assets/public.xml` (`ResourceIdXmlReader.parseIdFromXml`) ليكتب أي سمة `android:*` مضافة (meta-data «lspatch» مثلًا). الملف كان يصل للتطبيق عند upstream عبر وحدة Android library (`core/external/axml/manifest-editor/lib/src/main/assets/public.xml`)، لكن وحدة `:lspatch-engine` عندنا `java-library` نقية — موارد الـjar لا تصل إلى APK أندرويد، و«الأصول المركزية» في `manager/src/main/assets/` لم تشمل public.xml أحداً لم يعلم بوجوده. النتيجة على الجهاز: كل سمة مضافة تُكتب بمعرّف -1 → لا تُسجَّل في خريطة الموارد (`AxmlWriter.updateWithResourceId` يُسجل فقط للمعرفات ≠ -1) → محلل أندرويد يقرأ بالمعرّف لا بالنص → «meta-data missing android:name» → رفض التثبيت. **المفكّكات النصية (pyaxml/androguard) لا ترى العيب لأنها تقرأ النصوص** — لهذا بدا «الفحص الأولي سليماً».
- **الإصلاح**: (1) `manager/src/main/assets/public.xml` — الملف الأصلي من upstream (md5 9fe353671f43173de8474d419474c05b مطابق). (2) حماية في `ResourceIdXmlReader`: غياب الجدول يفشل الترقيع فوراً برسالة واضحة بدل إخراج APK غير قابل للتثبيت بصمت (فروق aMiNo موثقة، الكود لا يزال upstream وإلا).
- **مشتتات تم استبعادها بالدليل**: provider «identifierprovider» بلا android:name موجود في TikTok الأصلي نفسه (attrCount=1 في `origin.apk` المضمّن، md5 توقيع music.ly الأصلي) — ليس عيباً وليس سبباً؛ أصل `base-492-lspatched.apk` في الحزمة = TikTok كامل وليس Lite؛ تمريرة C2DM لم تعمل (لا GMS في config.json)؛ `ProviderDeleteVisitor`/المappers لم تُستخدم (null).
- **التجربة الذهبية للمستخدم مفسَّرة**: «Shizuku المدمج يعمل 100% مع LSPatch الأصلي» — لأن مدير upstream يحمل public.xml؛ نفس Shizuku + المدمج بلا public.xml = فشل. Shizuku لم يكن المتغير أبداً؛ المحتوى كان هو المتغير.
- **بوابة جديدة دائمة**: ملف APK المُنشر يجب أن يحتوي `assets/public.xml` (تفحص مع البوابات الأربع).
