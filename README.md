# Mentor

واجهة Flutter عربية، مع خدمة Android أصلية مكتوبة بكوتلن لقراءة تخطيط الشاشة.
الخدمة ترسل حدود العناصر فقط عبر ADB، دون نصوصها أو صور الشاشة.
التقاط الصورة والصوت يتم في برنامج C++ بواسطة scrcpy.

## البناء

استخدم Flutter 3.44.2 وJava 17 وAndroid SDK 36:

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
(cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug)
```

تُولّد Flutter ملفات Gradle wrapper المحلية عند أول بناء.
إذا رد Maven Central بالخطأ HTTP 429، استخدم الإعداد الاختياري:

```bash
mkdir -p "$HOME/.gradle/init.d"
cp tools/central-mirror.gradle "$HOME/.gradle/init.d/mentor-central-mirror.gradle"
```

## الاستخدام مع C++

ثبّت APK ثم افتح Mentor واضغط زر إعدادات إمكانية الوصول وفعّل خدمة Mentor بنفسك.
الخدمة لا تُفعّل تلقائياً. يلزم تفعيل USB debugging والموافقة على اتصال الكمبيوتر.

```bash
./build/apps/k230-monitor --max-fps 10 \
  --nsfwjs-model models/nsfwjs-mobilenet-v2.onnx --onnx-threads 1 \
  --layout --layout-record output/phone-layout.bin \
  --record output/phone-av.k230rec --verbose
```

الحالة «قناة ADB متصلة» تعني وجود مستقبِل فعلي عبر ADB، وليس مجرد توصيل USB.
التطبيق يوفّر خريطة العناصر؛ إصدار التحذيرات الحالي مسجّل في C++.
قناة أوامر `k230_companion` لعرض التحذير على الهاتف لم تُنفّذ هنا.

## البيانات المرسلة

قناة `localabstract:k230_layout` مستقلة عن scrcpy.
كل رسالة تبدأ بطول payload من 4 بايت little-endian:

```text
u32 magic=0x59414c4b, u16 version=1, u16 flags
u64 session, u64 sequence
i64 sampled_at_us, i64 completed_at_us, i64 valid_from_us
u32 width, height, rotation, display_id, window_id, node_count, package_length
ASCII package_name
nodes: i32 left, top, right, bottom; u32 kind, id (24 bytes/node)
```

الـheader حجمه 76 بايت، والحد الأقصى 256 عقدة و256 بايت لاسم الحزمة.
`PARTIAL=1` و`INVALIDATE=2`؛ رسالة الإبطال لا تحتوي عقداً.
أنواع العناصر: عادي `0`، صورة `1`، سطح فيديو `2`، WebView `3`.
هذه الأنواع مستنتجة من اسم فئة Android وليست ضماناً لمعنى المحتوى.
الحدود في إحداثيات الشاشة الحالية، بما فيها اتجاه الدوران الحالي.
التوقيت `System.nanoTime()/1000`؛ يلزم التحقق من توافقه مع PTS على كل جهاز.
الاتصال محلي ومسموح لعميل ADB فقط (UID shell/root)، مع إعادة الاتصال وتحديث الخريطة.

## تحقق المحاكي

يوجد نشاط debug فقط بثلاث صور لاختبار الخدمة والقناة:

```bash
adb shell am start -n dev.k230.mentor_app/.LayoutFixtureActivity
./build/tools/k230-layout-dump --duration 3 --record output/layout.bin
./build/tools/k230-layout-dump --replay output/layout.bin
```

التقطنا رسائل الخدمة فعلياً على محاكي Android API 35 وفكّها C++:
شاشة `1080×2400` وثلاث عقد `ImageView` بحدود
`[0,51,1080,451]`، `[0,451,1080,851]`، `[0,851,1080,1251]`.
هذا يثبت شكل الرسالة على المحاكي، ولا يثبت تغطية جميع التطبيقات أو أداء الهاتف/K230.

إمكانية الوصول قد لا تكشف الرسومات المخصصة والألعاب والفيديو كاملاً.
تغيّر الشاشة يبطل الإحداثيات القديمة، ويستخدم C++ الفحص البصري البديل عند فقد الخريطة.
لا تعتبر نتيجة تحليل خريطة جزئية إعلاناً بأن الشاشة آمنة.

توثيق Android:
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
