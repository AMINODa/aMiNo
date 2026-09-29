# aMiNo Autonomous Agent — Architecture Decision (r1379)

> قرار معماري معتمد: **النموذج يخطط، المحرك ينفذ، النموذج يتدخل عند الأخطاء — المعمارية C (هجين)**

## 1. لماذا C؟

| المعمارية | المشكلة |
|---|---|
| A — النموذج يقرر كل أمر | كل أمر = round-trip كامل للـ API (بطيء، مكلف)، والنموذج قد "ينسى" الهدف، وحد الأدوات الحالي 6 rounds يكفي فقط لمهام صغيرة |
| B — خطة تنفذ كاملة دون تدخل | هشّة: أول خطأ (صلاحية/إصدار Android مختلف) يُفشل الخطة كلها دون تصحيح |
| **C — هجين (المعتمدة)** | تخطيط ذكي مركزي + تنفيذ منضبط محلي + تصحيح ذاتي عند الفشل فقط. الأفضل هندسياً: النموذج يفكر، المحرك يضبط |

## 2. المودولات المستقلة

| الوحدة | الملف | مسؤوليتها | مبني على |
|---|---|---|---|
| **Brain** | `agent/auto/AutoPrompts.kt` | فهم الهدف/القيود، صياغة برومبتات التخطيط والتصحيح والتقرير | `AgentIdentity` (r1376) |
| **Planner** | `agent/auto/AutonomousEngine.kt` (phase PLAN) | تحويل الهدف إلى خطة JSON خطوات مرقمة بأوامر محددة | `LlmProvider.chat()` موجود |
| **Validator** | `agent/auto/CommandValidator.kt` | فحص كل أمر: ALLOW (قراءة فقط) / CONFIRM (يتطلب موافقتك) / BLOCK (ممنوع نهائياً) | جديد بالكامل |
| **Executor** | `agent/auto/ExecutionEngine.kt` | تنفيذ حقيقي عبر wireless ADB + timeout + التقاط المخرجات ورمز الخروج | `AgentTools.shellCommand` + `AdbClient` |
| **Observer** | `agent/auto/Observer.kt` | تحليل النتيجة: نجح/فشل/غير مؤكد + تصنيف سبب الفشل للتصحيح | جديد |
| **Memory** | `MemoryRepository` + `working memory` (موجود) | تخزين الهدف والخطة والنتائج والتقرير في SQLite | `AminoDb` (r1376) |

## 3. حلقة التنفيذ الذاتي (Autonomous Execution Loop)

```text
الهدف
  ↓
PLAN      ← LLM call #1: خطة JSON (goal, steps[{title, commands, expect}])
  ↓
لكل خطوة:
  VALIDATE  ← CommandValidator: ALLOW / CONFIRM / BLOCK
  │   BLOCK  → تسجيل + طلب أمر بديل من النموذج (بدون تنفيذ الممنوع)
  │   CONFIRM→ إيقاف الحلقة وطلب موافقة صريحة من المستخدم (AlertDialog)
  │   ALLOW  → تنفيذ مباشر
  ↓
EXECUTE   ← ADB حقيقي: stdout + exit code + timeout 20s
  ↓
OBSERVE   ← verdict: SUCCESS / FAILED / UNCERTAIN + سبب الفشل
  ↓ (فشل فقط)
SELF-CORRECT ← LLM: {action: retry | replace | skip | abort} + بديل واقعي
  ↓
NEXT STEP / إعادة التقييم
  ↓
REPORT    ← LLM call أخير: تقرير نهائي صادق مبني على النتائج الحقيقية فقط
```

حدود صارمة: `MAX_STEPS=8`، `MAX_COMMANDS_PER_STEP=4`، `MAX_CORRECTIONS_TOTAL=6`،
`COMMAND_TIMEOUT=20s`، إلغاء فوري بزر Stop.

## 4. حدود السلامة (Safety Boundaries)

1. **لا حذف أو تعديل حساس دون موافقة صريحة**: أي `pm uninstall/clear/disable`، `settings put`،
   `rm`، `mv/cp/chmod`، `kill`، `am force-stop`، `input` … → CONFIRM (الحلقة تتوقف حتى توافق).
2. **لا بيانات تخرج من الهاتف دون موافقة**: `curl/wget/nc/scp`، فتح روابط خارجية من الشل → CONFIRM
   مع تنبيه صريح بأن البيانات قد تغادر الجهاز.
3. **لا تجاوز صلاحيات**: `su/sudo/reboot/flash/dd→/dev/`، `mkfs`، remount، stop/start (الإطار) → BLOCK نهائي.
4. **مجهول = سؤال**: أي أمر غير مصنّف → CONFIRM (افتراضي آمن، لا تنفيذ تلقائي للأوامر المجهولة).
5. **النموذج لا ينفذ أبداً**: مخرجاته نصوص تخطيط فقط؛ كل أمر يمر إجبارياً عبر Validator.

## 5. ما المأخوذ من الكود الحالي (بدون إعادة اختراع)

| المكوّن الحالي | الاستخدام |
|---|---|
| `AgentOrchestrator` (r1376) | يبقى للمحادثة العادية؛ حلقة المهام الذاتية جديدة منفصلة عنه |
| `OpenAiCompatProvider.buildMessages/buildBody/execute` | إعادة استخدام مباشرة لطلبات التخطيط/التصحيح/التقرير |
| `CloudflareProvider` / `GeminiProvider` | نفس مزودات r1377-78 بدون تغيير (Cloudflare افتراضي) |
| `AgentTools.shellCommand` + `AdbClient` + `__AMINO_RC_` | نمط التنفيذ الحقيقي المعتمد في ExecutionEngine |
| `ShellSession` state | بوابة "shell_not_connected" الصادقة قبل أي خطة |
| `MemoryRepository` + `AminoDb` | تخزين الخطة/الأوامر/المخرجات/التقرير في نفس المحادثة |
| `ChatAdapter` الصفوف القابلة للتوسيع | عرض أوامر الحلقة ومخرجاتها بنفس الهوية الحمراء/السوداء |

## 6. ما لا يزال غير موجود (بصراحة)

- لا offline local model — التخطيط والتصحيح يحتاجان مزود API مضبوطاً من صفحة المفاتيح.
- لا embeddings search — الذاكرة بالكلمات المفتاحية (LIKE) كما في r1376.
- Validator قائم على أنماط (regex) — أقوى من لا شيء، لكنه ليس sandbox؛ الموافقة البشرية هي خط الدفاع الثاني، والافتراضي الآمن للأوامر المجهولة هو الثالث.
