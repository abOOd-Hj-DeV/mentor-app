import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:local_inspector/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('local_inspector/control');
  var enabled = false;
  final commands = <String>[];
  setUp(() {
    enabled = false;
    commands.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          commands.add(call.method);
          if (call.method == 'status') {
            return jsonEncode({
              'enabled': enabled,
              'active': false,
              'ready': false,
            });
          }
          return null;
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  testWidgets('Capture requires accessibility and explicit consent', (
    tester,
  ) async {
    await tester.pumpWidget(const LocalInspectorApp());
    await tester.pump();
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    enabled = true;
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    await tester.ensureVisible(find.byType(CheckboxListTile));
    await tester.tap(find.byType(CheckboxListTile));
    await tester.pump();
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNotNull,
    );
    await tester.ensureVisible(find.byType(FilledButton));
    await tester.tap(find.byType(FilledButton));
    await tester.pump();
    expect(commands, contains('start'));
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('Settings opens the native accessibility settings flow', (
    tester,
  ) async {
    await tester.pumpWidget(const LocalInspectorApp());
    await tester.pump();
    await tester.tap(find.text('فتح إعدادات إمكانية الوصول'));
    await tester.pump();
    expect(commands, contains('settings'));
    expect(commands, isNot(contains('start')));
    await tester.pumpWidget(const SizedBox());
  });
}
