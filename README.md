<div align="center">

# 👁️ aMiNo

تطبيق أندرويد يسمح للتطبيقات الأخرى باستخدام صلاحيات النظام (ADB/Root) مباشرة — بهوية **aMiNo** الثابتة: لوجو شارينغان حادّ وعالي الدقة، وثيم أسود × أحمر، وميزة **Shell** الحصرية.

An Android app that lets other apps use system-level APIs directly with ADB/root privileges — fully rebranded as **aMiNo** with a razor-sharp Sharingan logo, a black × red theme, and the exclusive **Shell** feature.

</div>

---

## ✨ الميزات | Features

- 🔴 **لوجو شارينغان** مرسوم برمجياً بدقة 4K — حواف حادة 100% بدون أي ضباب، والعين **تدور** داخل التطبيق (شاشة Shell وشاشة التشغيل)
- 🖤 **ثيم أسود × أحمر** إجباري في كل الشاشات (قوائم، حوارات، إعدادات)
- 🖥️ **Shell — طرفية مباشرة داخل التطبيق**: بعد الاقتران، اتصال ADB تفاعلي **يبقى مفتوحاً** ولا تُغلق النافذة تلقائياً بعد الاتصال
- 🧪 **خيارات تجريبية**: أزرار فحص سريعة للتأكد من نجاح الاقتران والاتصال (فحص الاقتران، من أنا، معلومات الجهاز، فحص خدمة aMiNo، حالة التصحيح اللاسلكي) + أمر `help` داخل الطرفية
- 🔗 **تدفق بعد الاقتران**: عند نجاح الاقتران تبقى النافذة مفتوحة وتعرض زر **«متابعة: تشغيل الخدمة»** ثم **«فتح Shell»** مباشرة

## 📥 التحميل والتثبيت المباشر | Download & Install

اذهب إلى صفحة **[Releases](../../releases)** ونزّل أحدث APK:
- فعّل خيار **"التثبيت من مصادر غير معروفة"** في إعدادات هاتفك
- ثبّت ملف الـ APK مباشرة — جاهز للاستخدام فوراً (Android 7.0+)

Go to the **[Releases](../../releases)** page and download the latest APK — signed (CN=aMiNo) and ready for direct installation.

## 🚀 طريقة الاستخدام | Quick Start

1. ابدأ الخدمة عبر **Wireless debugging** (بدون PC في Android 11+) أو **Root**
2. بعد نجاح الاقتران اضغط **«متابعة: تشغيل الخدمة»** — النافذة لا تُغلق
3. افتح بطاقة **Shell** من الشاشة الرئيسية أو زر **فتح Shell** — طرفية حية متصلة بجهازك
4. جرّب الخيارات التجريبية للتأكد من نجاح الاقتران والاتصال

Start via **Wireless debugging** or **Root**, keep the window open, then open the **Shell** card for a live terminal that stays connected.

## 🖥️ أوامر Shell التجريبية | Experimental Shell Commands

| الأمر | الغرض |
|---|---|
| `echo AMINO_PING` | يؤكد نجاح الاقتران والاتصال (يظهر ✓ تلقائياً) |
| `whoami` / `id` | هوية مستخدم ADB على الجهاز |
| `getprop ro.product.model` | معلومات الجهاز |
| `ps -A \| grep -i libamino` | التأكد من أن خدمة aMiNo تعمل |
| `settings get global adb_wifi_enabled` | حالة التصحيح اللاسلكي |
| `help` | قائمة الأوامر التجريبية داخل الطرفية |

## ℹ️ ملاحظات | Notes

- يدعم جميع المعالجات: `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`
- الواجهة كاملة بالعربية والإنجليزية وباقي اللغات (37 لغة)
- جلسة Shell تبقى مفتوحة حتى بعد مغادرة شاشة الطرفية وتعيد الاتصال تلقائياً بآخر منفذ ناجح

## 🙏 الاعتماد والرخصة | Credits & License

هذا التطبيق مبني على المشروع المفتوح المصدر [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) (وفورك [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku)) المرخص تحت **[Apache License 2.0](LICENSE)** — كل الفضل لمؤلفي المشروع الأصلي.

This project is based on the open-source [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) project, licensed under the **Apache License 2.0**.
