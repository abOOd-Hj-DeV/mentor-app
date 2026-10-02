import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MentorApp());

class MentorApp extends StatelessWidget {
  const MentorApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    theme: ThemeData(colorSchemeSeed: const Color(0xff155e75)),
    home: const LayoutPage(),
  );
}

class LayoutPage extends StatefulWidget {
  const LayoutPage({super.key});

  @override
  State<LayoutPage> createState() => _LayoutPageState();
}

class _LayoutPageState extends State<LayoutPage> {
  static const _channel = MethodChannel('dev.k230.mentor/layout');
  Map<String, Object?> _status = const {};
  Timer? _timer;
  String? _error;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  Future<void> _refresh() async {
    try {
      final status = await _channel.invokeMapMethod<String, Object?>('status');
      if (mounted) {
        setState(() {
          _status = status ?? const {};
          _error = null;
        });
      }
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } on MissingPluginException {
      if (mounted) setState(() => _error = 'هذه الوظيفة متاحة على Android.');
    }
  }

  Future<void> _openSettings() async {
    try {
      await _channel.invokeMethod<void>('openAccessibilitySettings');
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final running = _status['running'] == true;
    final connected = _status['connected'] == true;
    return Directionality(
      textDirection: TextDirection.rtl,
      child: Scaffold(
        appBar: AppBar(title: const Text('Mentor — تخطيط الشاشة')),
        body: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const Text(
              'يقرأ التطبيق حدود عناصر الشاشة ويرسلها محلياً عبر USB إلى برنامج التحليل. '
              'الصورة والصوت يلتقطهما scrcpy، والتحليل يعمل على جهازك.',
            ),
            const SizedBox(height: 24),
            Text(running ? 'خدمة التخطيط تعمل' : 'خدمة التخطيط غير مفعّلة'),
            Text(connected ? 'قناة ADB متصلة' : 'قناة ADB غير متصلة'),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _openSettings,
              child: const Text('فتح إعدادات إمكانية الوصول'),
            ),
            const SizedBox(height: 24),
            Text('التطبيق الظاهر: ${_status['package'] ?? '—'}'),
            Text('عدد المستطيلات: ${_status['nodes'] ?? '—'}'),
            Text('رقم التحديث: ${_status['sequence'] ?? '—'}'),
            Text('زمن قراءة الشجرة: ${_status['captureUs'] ?? '—'} µs'),
            const SizedBox(height: 16),
            const Text(
              'قد تكون الشجرة جزئية، خصوصاً في الألعاب والفيديو وواجهات الرسم المخصص. '
              'وجود التخطيط لا يثبت أن المحتوى آمن. الإصدار الحالي مخصص للتقييم والتنبيه.',
            ),
            if (_error != null || _status['error'] != null)
              Text(_error ?? _status['error'].toString()),
          ],
        ),
      ),
    );
  }
}
