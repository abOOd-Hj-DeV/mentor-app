import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../shared/widgets.dart';

class RemoteServerCard extends StatefulWidget {
  const RemoteServerCard({super.key});

  @override
  State<RemoteServerCard> createState() => _RemoteServerCardState();
}

class _RemoteServerCardState extends State<RemoteServerCard> {
  static const _channel = MethodChannel('dev.k230.mentor/protection');
  final _endpoint = TextEditingController();
  final _token = TextEditingController();
  final _pin = TextEditingController();
  Timer? _timer;
  String _status = 'stopped';
  String? _error;
  bool _busy = false;
  bool _consent = false;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
    _refresh();
  }

  Future<void> _refresh() async {
    try {
      final reply = await _channel.invokeMapMethod<String, Object?>(
        'getRemoteStatus',
      );
      if (mounted) {
        setState(() => _status = reply?['status'] as String? ?? 'stopped');
      }
    } on PlatformException {
      if (mounted) setState(() => _status = 'unsupported');
    } on MissingPluginException {
      if (mounted) setState(() => _status = 'unsupported');
    }
  }

  Future<void> _run(bool start) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await _channel.invokeMethod<void>(
        start ? 'startRemote' : 'stopRemote',
        start
            ? {
                'endpoint': _endpoint.text.trim(),
                'token': _token.text,
                'pin': _pin.text.trim().replaceAll(':', ''),
              }
            : null,
      );
      if (start) _token.clear();
      await _refresh();
    } on PlatformException catch (error) {
      if (mounted) {
        setState(
          () => _error = switch (error.code) {
            'unpaired' => 'أكمل الاقتران الآمن مع الوالد أولاً.',
            'permission_missing' => 'فعّل خدمة إمكانية الوصول أولاً.',
            _ => 'تعذر بدء الاتصال. تحقق من العنوان والرمز وبصمة الشهادة.',
          },
        );
      }
    } on MissingPluginException {
      if (mounted) {
        setState(() => _error = 'الاتصال بالسيرفر متاح على Android فقط.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    _endpoint.dispose();
    _token.dispose();
    _pin.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => SectionCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'تحليل الشاشة عبر السيرفر',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 12),
        Text(switch (_status) {
          'connected' => 'قناة السيرفر متصلة؛ التنفيذ يحتاج تأكيد الجهاز.',
          'analyzing' =>
            'وصل تأكيد تحليل إطار من السيرفر؛ التنفيذ يُؤكّد محلياً.',
          'partial' =>
            'وصل الإطار، لكن التحليل جزئي أو دون مناطق مؤهلة؛ الأمان غير مؤكد.',
          'connecting' => 'جارٍ الاتصال بالسيرفر…',
          'awaiting_screen' => 'المشاركة جاهزة؛ افتح تطبيقاً آخر لبدء التحليل.',
          'unsupported' => 'الاتصال بالسيرفر متاح على Android فقط.',
          'geometry_changed' => 'تغير اتجاه الشاشة؛ ابدأ مشاركة جديدة.',
          'capture_failed' => 'تعذر التقاط الشاشة أو الحصول على الإذن.',
          'disconnected' => 'انقطع الاتصال؛ لا يوجد تحليل سحابي جديد.',
          _ => 'مشاركة الشاشة متوقفة.',
        }),
        const SizedBox(height: 12),
        const Text(
          'إعداد مؤقت مع الوالد. تُرسل صور PNG إلى السيرفر مشفّرة وتُحلل في الذاكرة دون حفظها. لا يُرسل الصوت. يلزم إذن Android لكل جلسة؛ التحليل الجديد يتوقف عند انقطاع الاتصال.',
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _endpoint,
          textDirection: TextDirection.ltr,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(
            labelText: 'عنوان السيرفر',
            hintText: 'tls://server.example.com:8443',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _token,
          obscureText: true,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(labelText: 'رمز اتصال السيرفر'),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _pin,
          textDirection: TextDirection.ltr,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(
            labelText: 'بصمة SHA-256 للشهادة (للشهادة الذاتية)',
          ),
        ),
        CheckboxListTile(
          contentPadding: EdgeInsets.zero,
          value: _consent,
          title: const Text(
            'أوافق على إرسال صور الشاشة إلى السيرفر لتحليلها دون حفظها.',
          ),
          onChanged: _busy
              ? null
              : (value) => setState(() => _consent = value ?? false),
        ),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          children: [
            FilledButton(
              onPressed: _busy || !_consent ? null : () => _run(true),
              child: const Text('بدء مشاركة الشاشة'),
            ),
            OutlinedButton(
              onPressed: _busy ? null : () => _run(false),
              child: const Text('إيقاف المشاركة'),
            ),
          ],
        ),
      ],
    ),
  );
}
