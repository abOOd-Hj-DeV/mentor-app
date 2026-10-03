import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mentor_app/main.dart';
import 'package:mentor_app/protection/models.dart';
import 'package:mentor_app/protection/repository.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'support/fake_protection_repository.dart';

void main() {
  late FakeProtectionRepository repository;
  setUp(() {
    repository = FakeProtectionRepository();
  });
  tearDown(() async {
    await repository.close();
  });
  Future<void> mount(
    WidgetTester tester, {
    Size size = const Size(390, 844),
    double scale = 1,
  }) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = size;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.platformDispatcher.textScaleFactorTestValue = scale;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpWidget(MentorApp(repository: repository));
    await tester.pumpAndSettle();
  }

  Future<void> tapText(WidgetTester tester, String value) async {
    final finder = find.text(value);
    if (finder.evaluate().isEmpty) {
      await tester.scrollUntilVisible(
        finder,
        200,
        scrollable: find.byType(Scrollable).first,
        maxScrolls: 60,
      );
    }
    await Scrollable.ensureVisible(tester.element(finder.first), alignment: .5);
    await tester.pumpAndSettle();
    await tester.tap(finder.hitTestable().first);
    await tester.pumpAndSettle();
  }

  Future<void> unlock(WidgetTester tester) async {
    await tapText(tester, 'الإعدادات');
    await tapText(tester, 'فتح مساحة الوالد');
  }

  Future<void> unmount(WidgetTester tester) =>
      tester.pumpWidget(const SizedBox());

  testWidgets('Arabic RTL onboarding mode choice grants no native authority', (
    tester,
  ) async {
    repository.data = stateData(role: 'unconfigured', age: null);
    await mount(tester);
    expect(
      Directionality.of(tester.element(find.text('لمن هذا الجهاز؟'))),
      TextDirection.rtl,
    );
    await tapText(tester, 'جهاز الوالد');
    expect(repository.calls, isNot(contains('guardianAuthenticate')));
    expect(find.text('الإعدادات'), findsNothing);
    await tapText(tester, 'تحقق الوالد على جهازه');
    expect(repository.calls, contains('guardianAuthenticate'));
    expect(find.text('الإعدادات'), findsOneWidget);
    await unmount(tester);
  });
  testWidgets('missing native plugin visibly fails without fake protection', (
    tester,
  ) async {
    repository.failure = const ProtectionFailure('native_unavailable');
    await mount(tester);
    expect(
      find.textContaining('مكوّن الحماية الأصلي غير متاح'),
      findsOneWidget,
    );
    expect(find.textContaining('المسار المحلي جاهز'), findsNothing);
    await unmount(tester);
  });
  testWidgets('initial loading is explicit and does not show readiness', (
    tester,
  ) async {
    repository.stateCompleter = Completer<ProtectionState>();
    await tester.pumpWidget(MentorApp(repository: repository));
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.textContaining('المسار المحلي جاهز'), findsNothing);
    repository.stateCompleter!.complete(
      ProtectionState.fromNative(repository.data),
    );
    await tester.pumpAndSettle();
    await unmount(tester);
  });
  testWidgets(
    'guardian inbox remains generic and unfetched until authenticated',
    (tester) async {
      repository.items = [Incident.fromNative(incidentData())];
      await mount(tester);
      await tapText(tester, 'الصندوق');
      expect(find.text('com.example.viewer'), findsNothing);
      expect(repository.calls, isNot(contains('listIncidents')));
      await tapText(tester, 'فتح مساحة الوالد');
      await tapText(tester, 'قراءة الصندوق الموثّق');
      expect(find.text('com.example.viewer'), findsOneWidget);
      await tester.tap(find.byTooltip('قفل مساحة الوالد'));
      await tester.pumpAndSettle();
      expect(find.text('com.example.viewer'), findsNothing);
      await unmount(tester);
    },
  );
  testWidgets(
    'age thirteen shows older preview and pending receipt, keeps installed age',
    (tester) async {
      await mount(tester);
      await unlock(tester);
      await tester.tap(find.byType(DropdownButtonFormField<int>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('13 عاماً').last);
      await tester.pumpAndSettle();
      expect(find.text('معاينة الفئة 13-15'), findsOneWidget);
      await tapText(tester, 'إرسال إعداد العمر الموقّع');
      expect(repository.calls, contains('setChildAge:13'));
      expect(find.textContaining('آخر ملف مؤكّد: 12'), findsOneWidget);
      expect(find.textContaining('بانتظار إيصال الطفل'), findsWidgets);
      await unmount(tester);
    },
  );
  testWidgets(
    'direct pairing renders actual native QR and full fingerprint only',
    (tester) async {
      repository.data = {...repository.data, 'pairing': 'unpaired'};
      await mount(tester);
      await unlock(tester);
      await tapText(tester, 'الاقتران والبصمات');
      await tapText(tester, 'إنشاء عرض الاقتران');
      final qrFinder = find.byType(QrImageView);
      await tester.ensureVisible(qrFinder);
      await tester.pumpAndSettle();
      expect(qrFinder, findsOneWidget);
      expect(tester.widget<QrImageView>(qrFinder).size, greaterThan(0));
      expect(find.text('الاقتران مؤكّد من الجهاز'), findsNothing);
      await unmount(tester);
    },
  );
  testWidgets(
    'metadata detail distinguishes requested from unknown actual action',
    (tester) async {
      repository.items = [
        Incident.fromNative(incidentData(status: 'unknown', executedStage: 0)),
      ];
      await mount(tester);
      await tapText(tester, 'الصندوق');
      await tapText(tester, 'فتح مساحة الوالد');
      await tapText(tester, 'قراءة الصندوق الموثّق');
      await tapText(tester, 'عرض التفاصيل الموثّقة ←');
      expect(find.text('الإجراء المطلوب: تغطية الجزء المكتشف'), findsOneWidget);
      expect(find.text('الإجراء الفعلي: لا إجراء مؤكد'), findsOneWidget);
      await unmount(tester);
    },
  );
  testWidgets(
    'child shield has safe navigation/help but no guardian or dismiss controls',
    (tester) async {
      repository.data = stateData(role: 'child', active: true);
      await mount(tester);
      expect(find.text('لنأخذ لحظة هادئة'), findsOneWidget);
      expect(find.text('الإعدادات'), findsNothing);
      expect(find.text('الصندوق'), findsNothing);
      await tapText(tester, 'العودة لمكان آمن');
      expect(find.textContaining('لم يُؤكد الانتقال'), findsOneWidget);
      expect(find.text('حاجب الحماية قائم'), findsOneWidget);
      await unmount(tester);
    },
  );
  testWidgets(
    'app background removes authenticated incident details immediately',
    (tester) async {
      repository.items = [Incident.fromNative(incidentData())];
      await mount(tester);
      await tapText(tester, 'الصندوق');
      await tapText(tester, 'فتح مساحة الوالد');
      await tapText(tester, 'قراءة الصندوق الموثّق');
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pumpAndSettle();
      expect(find.text('com.example.viewer'), findsNothing);
      expect(find.text('فتح مساحة الوالد'), findsOneWidget);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text('com.example.viewer'), findsNothing);
      await unmount(tester);
    },
  );
  testWidgets(
    'diagnostics accessible from actual app, retaining original layout channel',
    (tester) async {
      const channel = MethodChannel('dev.k230.mentor/layout');
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            channel,
            (_) async => {'running': true, 'connected': true, 'nodes': 3},
          );
      await mount(tester);
      await tester.tap(find.byTooltip('مساعدة وتشخيص'));
      await tester.pumpAndSettle();
      await tapText(tester, 'تشخيص التخطيط المحلي المتقدم');
      expect(find.text('عدد المستطيلات: 3'), findsOneWidget);
      await unmount(tester);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    },
  );
  for (final size in [const Size(320, 900), const Size(1024, 900)]) {
    testWidgets(
      'RTL guardian screens fit $size with 2x text and accessible targets',
      (tester) async {
        final semantics = tester.ensureSemantics();
        await mount(tester, size: size, scale: 2);
        expect(tester.takeException(), isNull);
        await unlock(tester);
        expect(tester.takeException(), isNull);
        await tapText(tester, 'الصندوق');
        expect(tester.takeException(), isNull);
        expect(find.textContaining('صندوق الحماية'), findsOneWidget);
        await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
        await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
        await unmount(tester);
        semantics.dispose();
      },
    );
  }
  testWidgets('narrow child 2x text has no overflow and no sensitive imagery', (
    tester,
  ) async {
    repository.data = stateData(role: 'child', active: true);
    await mount(tester, size: const Size(320, 900), scale: 2);
    expect(tester.takeException(), isNull);
    expect(find.byType(Image), findsNothing);
    await tapText(tester, 'طلب مساعدة الوالد');
    expect(tester.takeException(), isNull);
    await unmount(tester);
  });
}
