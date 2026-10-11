import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mentor_app/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.k230.mentor/layout');
  testWidgets('Shows native service state and opens Android settings', (
    tester,
  ) async {
    var settingsOpened = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'openAccessibilitySettings') {
            settingsOpened = true;
            return null;
          }
          return {
            'running': true,
            'connected': true,
            'nodes': 3,
            'package': 'dev.example',
          };
        });
    await tester.pumpWidget(const MaterialApp(home: LayoutPage()));
    await tester.pump();
    expect(find.text('خدمة التخطيط تعمل'), findsOneWidget);
    expect(find.text('قناة ADB متصلة'), findsOneWidget);
    expect(find.text('عدد المستطيلات: 3'), findsOneWidget);
    await tester.tap(find.text('فتح إعدادات إمكانية الوصول'));
    await tester.pump();
    expect(settingsOpened, isTrue);
    await tester.pumpWidget(const SizedBox());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
}
