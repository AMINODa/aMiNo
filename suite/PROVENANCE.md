# Suite Provenance — المصادر المستوردة داخل aMiNo

aMiNo v2 يدمج جناحاً من ثلاثة تطبيقات بنيات بناء مستقلة. هذه الوثيقة توثّق مصدر كل شجرة والتعديلات المحلية عليها — لا شيء آخر تغيّر.

| الشجرة | المصدر الأعلى | المرجع المثبت | التعديلات المحلية |
|---|---|---|---|
| `suite/rinigan/` | github.com/AMINODa/Rinigan-App | tag `v6.3` (versionCode 35) | إظلال الثيم بألوان aMiNo + تسمية العرض — انظر القسم 2 |
| `suite/lspatch/` | github.com/JingMatrix/LSPatch | tag `v1.2` | ترقيع fallback للإصدار + إظلال seed — انظر القسم 3 |
| `suite/lspatch/core/` | github.com/JingMatrix/LSPosed | `e00c5c5038bbcc14e0b382ab893301d6993e6989` (submodule core في v1.2) | بلا تعديل |

تم حذف مجلدات `.git` من الأشجار المستوردة (لأنها تُصبح جزءاً من مستودع aMiNo). لذلك:

- `suite/lspatch/VENDORED_VERSION` = `487` — verCode الإصدار الرسمي المطابق (أصول الإصدار: `manager-v1.2-487-release.apk`)، يُقرأ من `build.gradle.kts` عند غياب `.git`.
- fallback إصدار النواة في `build.gradle.kts` يعرض `1.0-e00c5c5` + هاش الكومت الكامل (للعرض في About فقط — لا استخدام وقت التشغيل).

## قواعد التحديث لاحقاً
1. أي تحديث للأشجار يجب أن يحافظ على applicationIds كما هي (`com.rinigan.app`, `org.lsposed.lspatch`) — الخطافات وسلطة Provider والـsocket وارتباط الوحدات محفورة فيها.
2. Rinigan تُبنى بشهادتها الأصلية `rinigan.keystore` (ترقية موضعية لنسخ المستخدم).
3. لا يجوز تحديث الثيم بأي لون خارج هوية aMiNo (`#0B0B0E` أسطح `#131318/#16161B` حدود `#2B2B33` أحمر `#D32F2F/#E53935/#FF867C`).

## طريقة البناء (من جذر amino)
```bash
# Rinigan (AGP 8.2.2 / Gradle 8.7 / JDK 21)
cd suite/rinigan && JAVA_HOME=/home/z/jdk-21 ./gradlew :app:assembleRelease

# LSPatch manager + jar (AGP 9.3.1 / Gradle 9.6.1 / NDK 29.0.13113456 / JDK 21)
cd suite/lspatch && JAVA_HOME=/home/z/jdk-21 ./gradlew buildRelease -Dorg.gradle.jvmargs="-Xmx1792m"

# aMiNo (الجذر)
JAVA_HOME=/home/z/jdk-21 ./gradlew :manager:assembleRelease --no-daemon ...
```
