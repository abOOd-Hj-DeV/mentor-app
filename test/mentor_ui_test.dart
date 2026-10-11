import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mentor_app/main.dart';
import 'support/fake_protection_repository.dart';

void main() {
  const channel = MethodChannel('dev.k230.mentor/mvp');
  const layout = MethodChannel('dev.k230.mentor/layout');
  late FakeProtectionRepository repository;
  late Map<String, dynamic> summary;
  var settingsOpened = false;
  setUp(() {
    repository = FakeProtectionRepository(
      initial: stateData(role: 'unconfigured', age: null),
    );
    summary = {'role': null, 'linked': false, 'accessibility': false};
    settingsOpened = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'setRole') {
            summary['role'] = (call.arguments as Map)['role'];
          }
          return call.method == 'summary' ? summary : null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(layout, (call) async {
          if (call.method == 'openAccessibilitySettings') settingsOpened = true;
          return null;
        });
  });
  tearDown(() async {
    await repository.close();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(layout, null);
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

  Future<void> tap(WidgetTester tester, String text) async {
    final target = find.text(text);
    await tester.scrollUntilVisible(
      target,
      180,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(target);
    await tester.pumpAndSettle();
  }

  Future<void> unmount(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  }

  testWidgets('MVP role selection needs no age, PIN or pairing', (
    tester,
  ) async {
    await mount(tester);
    expect(find.text('هذا جهاز الطفل'), findsOneWidget);
    expect(find.text('هذا جهاز الأهل'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    await tap(tester, 'هذا جهاز الطفل');
    expect(find.text('مساحتك، بإيقاع أهدأ'), findsOneWidget);
    expect(repository.calls, isNot(contains('createPairOffer')));
    expect(repository.calls, isNot(contains('guardianAuthenticate')));
    await unmount(tester);
  });
  testWidgets('child opens normal Android accessibility settings', (
    tester,
  ) async {
    summary['role'] = 'child';
    await mount(tester);
    await tap(tester, 'فتح إمكانية الوصول');
    expect(settingsOpened, isTrue);
    await unmount(tester);
  });
  testWidgets('guardian shows unavailable data rather than fabricated zeros', (
    tester,
  ) async {
    summary['role'] = 'guardian';
    await mount(tester);
    expect(find.text('—'), findsOneWidget);
    expect(find.text('الإعدادات'), findsNothing);
    expect(find.byType(PopupMenuButton<String>), findsNothing);
    await unmount(tester);
  });
  testWidgets(
    'guardian displays imported execution count and safe usage alert',
    (tester) async {
      summary = {
        'role': 'guardian',
        'linked': true,
        'blockedThreats': 7,
        'todayUsageMs': 3600000,
        'yesterdayUsageMs': 1800000,
        'comparisonReady': true,
        'usageBreaks': 2,
      };
      await mount(tester);
      expect(find.text('7'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.textContaining('زاد الاستخدام المسجّل'),
        180,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.textContaining('30 دقيقة'), findsOneWidget);
      expect(find.textContaining('ليست تشخيصاً نفسياً'), findsOneWidget);
      await unmount(tester);
    },
  );
  testWidgets('guardian needs a completed baseline before usage comparison', (
    tester,
  ) async {
    summary = {
      'role': 'guardian',
      'linked': true,
      'blockedThreats': 0,
      'todayUsageMs': 60000,
      'yesterdayUsageMs': 0,
      'comparisonReady': false,
    };
    await mount(tester);
    await tester.scrollUntilVisible(
      find.textContaining('يوم سابق مكتمل'),
      180,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.textContaining('زاد الاستخدام المسجّل'), findsNothing);
    await unmount(tester);
  });
  for (final role in ['child', 'guardian']) {
    testWidgets('$role remains usable at narrow width and large text', (
      tester,
    ) async {
      summary['role'] = role;
      await mount(tester, size: const Size(320, 640), scale: 2);
      expect(tester.takeException(), isNull);
      await unmount(tester);
    });
  }
}
