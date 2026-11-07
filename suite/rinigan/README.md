# Rinigan | رينيغان

[English](#english) | [العربية](#العربية) | [Français](#français)

---

<a id="english"></a>
## English

**Rinigan** is an Android virtual camera module based on **Xposed / LSPosed / LSPatch**. It replaces the camera feed that apps receive with a local video, images or a network stream, and provides basic audio and playback controls.

### Main features

- Replace the camera feed with a local video
- **Cross-Process Link Environment (v6.3)** — your link (website OR live stream) is rendered inside the Rinigan app itself, then the frames are injected into the target app's camera. Works even when the target app has NO INTERNET permission (the classic LSPatch black-screen cause), and a built-in Diagnostics card shows the exact pipeline state at every stage
- Supports Camera1 / Camera2
- **Per-App Transformation Engine (v5.2)**: automatic per-app rotation, scaling and mirroring of the replacement stream
- Supports image replacement of photo results
- Supports microphone mute, audio replacement and original video sound
- Supports random playback, notification-bar switching and network streams
- Works with LSPosed, LSPatch, EdXposed and classic Xposed

### Per-App Transformation Engine (new in v5.2)

The universal transformation engine normalizes orientation and aspect ratio for **every** calling app automatically:

- **Universal default fallback** — unclassified apps get a portrait, full-frame FIT_CENTER stream
- **Automatic calling-app detection** — the requesting package is resolved at runtime (host process + Binder UID)
- **Per-app profiles** — rotation (0/90/180/270), scale mode (FIT_CENTER / CENTER_CROP / STRETCH), target resolution, horizontal/vertical flip, auto portrait normalization
- **OpenGL matrix relay** — frames are transformed before reaching the app's Surface; Camera1 preview buffers are transformed at byte level (NV21)

Configure per app in the new **Per-App** tab, via `app_profiles` in `cs_config.json`, or by dropping an `app_profiles.json` file in `DCIM/Camera1`:

```json
{
  "profiles": {
    "com.whatsapp": { "rotation": 0, "scaleMode": "FIT_CENTER", "normalizePortrait": true },
    "com.instagram.android": { "rotation": 90, "scaleMode": "CENTER_CROP" }
  }
}
```

### Live Link Display Environment (new in v6.2)
### Cross-Process Link Environment (new in v6.3)
v6.3 moves the whole display environment into the Rinigan app process: the link is rendered there (MediaPlayer+EGL for streams, off-screen WebView for pages) and the NV21 frames travel to the target app over an abstract unix socket that needs **zero permissions**. The target app no longer needs INTERNET, a working WebView or overlay windows — the three LSPatch failure modes that produced a black camera in v6.2. A new Diagnostics card on the Home screen shows the engine state, a live preview of what cameras receive, frame counters and the target app's hook events, so a failure is always explained in plain language instead of silence.

Rinigan v6.2 gives **every** link a real display environment — the exact fix for the v6.1 "link shows nothing when the camera opens" bug. The root cause: MediaPlayer can only stream direct media files, so websites and watch pages could never appear. Now there are two rendering paths and the link always shows:

- **PAGE links** (websites, YouTube/Facebook watch pages, any non-media link) — rendered in a true browser environment: a VirtualDisplay + Presentation hosting a WebView with JavaScript and autoplay enabled. Inline live players play inside the page, inside your camera
- **MEDIA links** (`mp4`, `m3u8`, `mpd`, `ts`…) — streamed live by MediaPlayer as before, now with a smarter watchdog (slow networks no longer cause an endless reconnect loop)
- **Peer frames in video calls** — a new frame bridge feeds the Camera2 reader and Camera1 preview-callback paths with real frames for pages AND HLS, so the other side of the call sees the link too (v6.1 was preview-only for HLS, black for pages)
- **Never black** — hardware rendering → software rendering → local video fallback, in order; toasts announce each stage, and a dead link can never freeze the host app (bounded waits replaced infinite spins)
- **http:// allowed** — cleartext policy is hooked, so non-https links work on Android 9+

### Basic usage

After installing and enabling the module, configure its scope for the target app. When you first open the target app's camera screen, prepare a video with the matching resolution and name it `virtual.mp4`.

Common path:

```
/Internal storage/DCIM/Camera1/virtual.mp4
```

If the target app cannot access public storage, use its private directory:

```
/Internal storage/Android/data/[target package]/files/Camera1/virtual.mp4
```

Reopen the target app and the camera feed will be replaced.

### Notes

Some apps or the system camera may fail to hook due to different implementations; if you see a black screen, glitched frames or wrong orientation, first check the video resolution, encoding format and file path.

This project is for learning, research and legal use only. Please comply with your local laws and regulations.

## العربية

**Rinigan** هو وحدة كاميرا افتراضية لأندرويد تعتمد على **Xposed / LSPosed / LSPatch**، تستبدل صورة الكاميرا التي تحصل عليها التطبيقات بفيديو محلي أو صور أو بث شبكي، مع أدوات أساسية للتحكم بالصوت والتشغيل.

### الميزات الرئيسية

- استبدال صورة الكاميرا بفيديو محلي
- **بيئة عرض الروابط عبر العمليات (v6.3)** — رابطك (موقع أو بث مباشر) يُعرض داخل تطبيق رينيغان نفسه ثم تُنقل إطاراته إلى كاميرا التطبيق الهدف. يعمل حتى لو لم يكن للتطبيق الهدف صلاحية إنترنت (السبب الكلاسيكي للشاشة السوداء مع LSPatch)، مع بطاقة تشخيص مدمجة تعرض حالة الأنبوب في كل مرحلة
- دعم Camera1 / Camera2
- **محرّك التحويل لكل تطبيق (v5.2)**: تدوير وقياس وانعكاس تلقائي للبث البديل حسب كل تطبيق
- دعم استبدال نتائج التصوير بالصور
- دعم كتم الميكروفون واستبدال الصوت وصوت الفيديو الأصلي
- دعم التشغيل العشوائي والتبديل من شريط الإشعارات والبث الشبكي
- يعمل مع LSPosed و LSPatch و EdXposed و Xposed التقليدي

### محرّك التحويل لكل تطبيق (جديد في v5.2)

يقوم المحرك الشامل بضبط الاتجاه ونسبة الأبعاد تلقائيًا لكل تطبيق يستدعي الكاميرا:

- **قاعدة عامة افتراضية** — أي تطبيق غير مصنّف يحصل على بث عمودي كامل الإطار بنمط FIT_CENTER
- **كشف تلقائي للتطبيق الطالب** — تُحدَّد الحزمة الطالبة وقت التشغيل (عملية التطبيق + Binder UID)
- **ملفات تعريف لكل تطبيق** — دوران (0/90/180/270)، نمط القياس (FIT_CENTER / CENTER_CROP / STRETCH)، دقة الهدف، انعكاس أفقي/رأسي، تطبيع عمودي تلقائي
- **مرحّل مصفوفة OpenGL** — تُحوَّل الإطارات قبل وصولها إلى Surface الخاص بالتطبيق؛ وتُحوَّل مخازن معاينة Camera1 على مستوى البايت (NV21)

يُضبَط كل تطبيق من تبويب **لكل تطبيق** الجديد، أو عبر `app_profiles` في `cs_config.json`، أو بوضع ملف `app_profiles.json` في `DCIM/Camera1`.

### بيئة عرض الروابط المباشرة (جديد في v6.2)
### بيئة عرض الروابط عبر العمليات (جديد في v6.3)
نقلت v6.3 بيئة العرض بالكامل إلى عملية تطبيق رينيغان: الرابط يُعرض هناك (MediaPlayer+EGL للبث، WebView خارج الشاشة للمواقع) وتنتقل إطارات NV21 إلى التطبيق الهدف عبر سوكيت يونكس مجرد لا يحتاج **أي صلاحيات**. لم يعد التطبيق الهدف يحتاج إنترنت أو WebView عاملًا أو نوافذ تراكب — وهي أوضاع فشل LSPatch الثلاثة التي كانت تُنتج شاشة سوداء في v6.2. كما أضافت بطاقة تشخيص في الشاشة الرئيسية تعرض حالة المحرك ومعاينة حية لما تصلك عليه الكاميرات وعدّادات الإطارات وأحداث الهدف، فيُشرح أي فشل بلغة واضحة بدل الصمت.

يوفر Rinigan v6.2 **بيئة عرض حقيقية لكل رابط** — وهذا هو الإصلاح الدقيق لمشكلة v6.1 "الرابط لا يظهر شيء عند فتح الكاميرا". السبب الجذري: MediaPlayer لا يستطيع تشغيل إلا ملفات الوسائط المباشرة، لذلك كانت المواقع وصفحات المشاهدة لا تظهر أبدًا. الآن هناك مسارا عرض ولن يبقى الرابط أبدًا بدون صورة:

- **روابط المواقع** (مواقع، صفحات مشاهدة YouTube/Facebook، أي رابط غير إعلامي) — تُعرض في بيئة متصفح حقيقية: شاشة افتراضية + نافذة Presentation تستضيف WebView مع تفعيل JavaScript والتشغيل التلقائي. مشغلات البث المباشر تعمل داخل الصفحة وداخل كاميرتك
- **روابط الوسائط** (`mp4`، `m3u8`، `mpd`، `ts`…) — تُبث مباشرةً بواسطة MediaPlayer كما السابق، مع مراقب أذكى (الشبكات البطيئة لم تعد تسبب حلقة إعادة اتصال لا نهائية)
- **إطارات الطرف الآخر في مكالمات الفيديو** — جسر إطارات جديد يغذي مساري قارئ Camera2 ورد أل Camera1 بإطارات حقيقية للصفحات ولـ HLS، فيرى الطرف الآخر في المكالمة الرابط أيضًا (في v6.1 كان HLS للمعاينة فقط والصفحات سوداء)
- **بدون شاشة سوداء أبدًا** — عرض عتادي ← عرض برمجي ← فيديو محلي، بهذا الترتيب؛ وتظهر تنبيهات Toast لكل مرحلة، ولا يمكن لرابط ميت تجميد التطبيق الهدف (انتظار محدود بدل الدوران اللانهائي)
- **http:// مسموح** — سياسة cleartext مخترقة، فروابط غير https تعمل على Android 9+

### الاستخدام الأساسي

بعد تثبيت الوحدة وتفعيلها، اضبط نطاقها للتطبيق الهدف. عند أول دخول لكاميرا التطبيق الهدف، جهّز فيديو بنفس الدقة وسمّه `virtual.mp4`.

المسار الشائع:

```
/التخزين الداخلي/DCIM/Camera1/virtual.mp4
```

إذا لم يتمكن التطبيق الهدف من الوصول إلى التخزين العام، استخدم مجلده الخاص:

```
/التخزين الداخلي/Android/data/[حزمة التطبيق]/files/Camera1/virtual.mp4
```

أعد فتح التطبيق الهدف وستُستبدل صورة الكاميرا.

### ملاحظات

بعض التطبيقات أو كاميرا النظام قد لا تعمل مع الاعتراض بسبب اختلاف طريقة تنفيذها؛ عند ظهور شاشة سوداء أو تشويش أو اتجاه خاطئ، تحقق أولاً من دقة الفيديو وصيغة الترميز ومسار الملف.

هذا المشروع مخصص للتعلم والبحث والاستخدام القانوني فقط. يرجى الالتزام بالقوانين المحلية.

## Français

**Rinigan** est un module Android de caméra virtuelle basé sur **Xposed / LSPosed / LSPatch**. Il remplace le flux de la caméra reçu par les applications par une vidéo locale, des images ou un flux réseau, et fournit des contrôles audio et de lecture de base.

### Fonctionnalités principales

- Remplacer le flux de la caméra par une vidéo locale
- **Environnement de lien inter-processus (v6.3)** — votre lien (site OU flux live) est rendu dans Rinigan lui-même, puis les images sont injectées dans la caméra de l\'application cible. Fonctionne même sans permission INTERNET côté cible (la cause classique de l\'écran noir avec LSPatch), avec une carte de diagnostic intégrée qui montre l\'état exact du pipeline à chaque étape
- Prise en charge de Camera1 / Camera2
- **Moteur de transformation par application (v5.2)** : rotation, mise à l'échelle et miroir automatiques du flux de remplacement pour chaque application
- Remplacement des photos par des images
- Coupure du micro, remplacement audio et son d'origine de la vidéo
- Lecture aléatoire, changement via la barre de notification et flux réseau
- Compatible avec LSPosed, LSPatch, EdXposed et Xposed classique

### Moteur de transformation par application (nouveau en v5.2)

Le moteur universel normalise l'orientation et le ratio pour **toutes** les applications appelantes :

- **Repli universel** — toute application non classifiée reçoit un flux portrait plein cadre en FIT_CENTER
- **Détection automatique de l'application appelante** — le paquet demandeur est résolu à l'exécution (processus hôte + Binder UID)
- **Profils par application** — rotation (0/90/180/270), mode d'échelle (FIT_CENTER / CENTER_CROP / STRETCH), résolution cible, miroir horizontal/vertical, normalisation portrait auto
- **Relais matriciel OpenGL** — les images sont transformées avant d'atteindre la Surface de l'application ; les tampons d'aperçu Camera1 sont transformés au niveau octet (NV21)

Configurez chaque application dans le nouvel onglet **Par app**, via `app_profiles` dans `cs_config.json`, ou en déposant un fichier `app_profiles.json` dans `DCIM/Camera1`.

### Environnement d\'affichage de liens en direct (nouveau en v6.2)
### Environnement de lien inter-processus (nouveau en v6.3)
v6.3 déplace tout l\'environnement d\'affichage dans le processus de l\'application Rinigan : le lien y est rendu (MediaPlayer+EGL pour les flux, WebView hors écran pour les pages) et les images NV21 voyagent vers l\'application cible via un socket unix abstrait qui n\'exige **aucune permission**. L\'application cible n\'a plus besoin d\'INTERNET, d\'un WebView fonctionnel ni de fenêtres overlay — les trois modes de défaillance LSPatch qui provoquaient un écran noir en v6.2. Une nouvelle carte de diagnostic sur l\'écran d\'accueil affiche l\'état du moteur, un aperçu en direct, les compteurs d\'images et les événements de l\'application cible.

Rinigan v6.2 offre à **chaque** lien un véritable environnement d\'affichage — la correction exacte du bug v6.1 « le lien n\'affiche rien à l\'ouverture de la caméra ». La cause racine : MediaPlayer ne peut diffuser que des fichiers média directs, donc les sites web et les pages de visionnage n\'apparaissaient jamais. Il y a désormais deux chemins de rendu et le lien s\'affiche toujours :

- **Liens PAGE** (sites web, pages de visionnage YouTube/Facebook, tout lien non-média) — rendus dans un vrai environnement navigateur : VirtualDisplay + Presentation hébergeant un WebView avec JavaScript et lecture automatique activés. Les lecteurs en direct s\'exécutent dans la page, dans votre caméra
- **Liens MEDIA** (`mp4`, `m3u8`, `mpd`, `ts`…) — diffusés en direct par MediaPlayer comme avant, avec un chien de garde plus intelligent (les réseaux lents ne provoquent plus une boucle de reconnexion infinie)
- **Images pour l\'autre correspondant en appel vidéo** — un nouveau pont d\'images alimente les chemins lecteur Camera2 et callback Camera1 avec de vraies images pour les pages ET le HLS : l\'autre côté de l\'appel voit aussi le lien (en v6.1 le HLS était en aperçu seul et les pages en noir)
- **Jamais d\'écran noir** — rendu matériel → rendu logiciel → vidéo locale, dans cet ordre ; des toasts annoncent chaque étape, et un lien mort ne peut jamais figer l\'application hôte (attentes bornées au lieu de boucles infinies)
- **http:// autorisé** — la politique cleartext est interceptée, les liens non-https fonctionnent sur Android 9+

### Utilisation de base

Après l'installation et l'activation du module, configurez sa portée pour l'application cible. À la première ouverture de la caméra de l'application cible, préparez une vidéo à la résolution correspondante nommée `virtual.mp4`.

Chemin courant :

```
/Stockage interne/DCIM/Camera1/virtual.mp4
```

Si l'application cible ne peut pas accéder au stockage public, utilisez son dossier privé :

```
/Stockage interne/Android/data/[paquet de l'application]/files/Camera1/virtual.mp4
```

Rouvrez l'application cible : le flux de la caméra sera remplacé.

### Remarques

Certaines applications ou la caméra système peuvent ne pas être interceptées selon leur implémentation ; en cas d'écran noir, d'image corrompue ou d'orientation incorrecte, vérifiez d'abord la résolution vidéo, le format d'encodage et le chemin du fichier.

Ce projet est destiné à l'apprentissage, à la recherche et à un usage légal uniquement. Veuillez respecter les lois locales.

## License

MIT License — see [LICENSE](LICENSE).
