# حماية محلية — Flutter + Kotlin

تطبيق مستقل عن Mentor، معرّفه `dev.k230.local_inspector`. يعمل على Android 11
أو أحدث، دون سيرفر أو ADB أو MediaProjection أو Firebase.

## التشغيل على الهاتف

1. ثبّت APK وافتح «حماية محلية».
2. اضغط «فتح إعدادات إمكانية الوصول» وفعّل خدمة «حماية محلية» يدوياً.
   قد يطلب Android السماح بالإعدادات المقيدة للتطبيق المثبت خارج المتجر.
3. عد إلى التطبيق وحدد العمر (10–15)، واقرأ الموافقة ثم اضغط «بدء الحماية المحلية».
4. افتح التطبيق المراد مراقبته. شاشة الحماية نفسها والمشغل لا يدخلان في التحليل.
5. يمكنك العودة إلى لوحة الحالة أو إيقاف الحماية من التطبيق أو إعدادات Android.

## مسار العمل

`AccessibilityService.takeScreenshot → RGB → YOLO → مناطق الصور → NSFWJS → LocalPolicy.evaluateImmediate → HOME`

- لقطة كل 1000 ms. إذا رفض Android الفاصل (`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`)
  يزيد التطبيق الفاصل 250 ms حتى 3000 ms ويستمر، دون إيقاف الحماية.
  الفاصل الحالي والمعدل المقاس يظهران في الواجهة؛ ليسا ضماناً لأداء كل هاتف.
- فشل الالتقاط أو التحليل يُسجل ويُعرض مع نوع الاستثناء، وتستمر الحماية
  وتُعاد المحاولة في الدورة التالية؛ لا يُوقف التطبيق نفسه.
- التحليل على خيط منفصل باستخدام ONNX Runtime Android **1.22.0** CPU بخيطين.
  إصدار Android 1.22.1 غير متاح في Maven المستخدم، بخلاف إصدار C++ السابق.
- إطار واحد قيد التحليل وآخر منتظر؛ الأحدث يستبدل المنتظر.
  لا توجد مهلة صلاحية أو تأكيدات متتالية لقرار HOME؛ يُنفذ حتى عند تأخر الإطار،
  أو تغير التطبيق أو التمرير أو أبعاد الشاشة. يتوقف التنفيذ عند إيقاف الحماية يدوياً.
  قد يغادر HOME التطبيق المفتوح حالياً بسبب تصنيف لقطة من تطبيق سابق.
- النموذجان داخل APK، مع تحقق SHA-256 والأشكال والبيانات الوصفية.
- YOLO: RGB NCHW 640×640، letterbox 114، bilinear half-pixel، ثقة 0.25،
  NMS 0.70، Image/BackgroundImage فقط، حد أدنى 64 px و1% من الشاشة،
  وحتى 8 مناطق. NSFWJS: RGB NHWC 224×224، align-corners، قيم [0,1].
- ترتيب المخرجات: Drawing, Hentai, Neutral, Porn, Sexy.
  `explicit = clamp(Porn + Hentai, 0, 1)`؛ Sexy منفصلة.

## الإجراءات

| العمر | طلب HOME فوراً عند Porn + Hentai |
|---|---:|
| 10–12 | 0.60 |
| 13–15 | 0.70 |

العتبات تجريبية، وليست دقة مثبتة للمصنف.

- استجابة واحدة: `performGlobalAction(GLOBAL_ACTION_HOME)` بعد أول منطقة تتجاوز العتبة.
- Porn وHentai يعاملان بالمجموع نفسه؛ Hentai وحده يستطيع طلب HOME عند تجاوز العتبة.
- لا تُعرض تغطية أو حاجب، ولا يُنتظر عدد إضافي من الملاحظات أو مدة أو تطابق مناطق.
- إذا رفض Android تنفيذ HOME يظهر الخطأ وتستمر الحماية حتى التصنيف التالي.
- HOME يرجع للرئيسية ولا ينفذ force-stop.
- مسار التأكيد القديم موجود في `LocalPolicy.evaluate` لكنه غير مستخدم في الخدمة.
  اختبارات السياسة والإجراءات القديمة تخص ذلك السلوك السابق؛ لم تُشغّل بعد هذا التعديل بطلب المستخدم.

## الخصوصية والحدود

Release لا يطلب INTERNET. لا صور أو صوت أو نصوص محادثات في التخزين
أو سجل الأحداث؛ اللقطات مؤقتة في الذاكرة وتُحرر. السجل الوصفي مؤقت وحتى
50 إجراء، ويُمسح عند انتهاء العملية. العمر فقط يُحفظ في الإعدادات.

هذه نسخة محلية مستقلة للتجربة؛ لا تضم ربط أجهزة الأهل أو Firebase أو مقاومة
تعطيل الخدمة. يمكن إيقافها يدوياً. الشاشات المحمية بـFLAG_SECURE لا يمكن
تحليلها؛ فشل الالتقاط/التحليل يُعرض كخطأ ولا يثبت سلامة الشاشة.
تشغيل الصوت غير مطلوب هنا ولم يُضف.

مصادر وتراخيص النموذجين في `android/app/src/main/assets/models`.
مرجع العينة السوداء في الاختبار من تحويل NSFWJS الأصلي؛ ليس اختباراً لدقة
كشف محتوى مخالف أو حل الالتباس مع الروبوتات.

## البناء والفحوص

Flutter 3.44.2 / Dart 3.12.2، JDK 17، Android SDK، Gradle wrapper.

### التشغيل بوضع Debug

فعّل USB debugging على الهاتف، وصله بالحاسوب، ووافق على بصمة اتصال USB:

```sh
cd local_inspector
flutter pub get
flutter devices
flutter run --debug -d <DEVICE_ID>
```

استبدل `<DEVICE_ID>` بمعرّف الهاتف الظاهر في `flutter devices`.
بعد التشغيل فعّل خدمة «حماية محلية» يدوياً واختر العمر ووافق على الالتقاط.
إذا رفض التثبيت بسبب اختلاف التوقيع، احذف نسخة الاختبار القديمة ثم أعد الأمر؛
ذلك يمسح العمر المحفوظ ويحتاج تفعيل خدمة إمكانية الوصول مجدداً.

لفحص خطأ بدء الحماية احتفظ بالطرفية مفتوحة، أو شغّل:

```sh
adb -s <DEVICE_ID> logcat -v time LocalInspector:E AndroidRuntime:E '*:S'
```

Release يحتفظ بأسماء أصناف ONNX Runtime وأعضائها اللازمة لنداءات JNI؛
إزالة هذه الأصناف أو إعادة تسميتها بواسطة R8 تمنع تحميل النماذج.
مرجع ONNX Runtime: https://onnxruntime.ai/docs/get-started/with-mobile.html

### الفحوص

```sh
flutter pub get
flutter analyze
flutter test
flutter build apk --release
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

APK موقع بمفتاح تطوير أدوات Android للتثبيت التجريبي. لا توجد مفاتيح توقيع
في المصدر. النشر في متجر يحتاج إعداد توقيع مستقل.

مصادر API:
https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)
https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/accessibilityservice/AccessibilityService.java
