import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'cloud_device_client.dart';

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

class _LayoutPageState extends State<LayoutPage> with WidgetsBindingObserver {
  static const _layoutChannel = MethodChannel('dev.k230.mentor/layout');
  final _serverUrlController = TextEditingController();
  final _pairingCodeController = TextEditingController();
  final _deviceNameController = TextEditingController(text: 'هاتف Android');
  Map<String, Object?> _status = const {};
  CloudDeviceCredentials? _credentials;
  Timer? _statusTimer;
  Timer? _heartbeatTimer;
  String? _error;
  String? _cloudError;
  DateTime? _lastHeartbeatAt;
  bool _pairing = false;
  bool _sendingEvent = false;
  bool _unlinking = false;
  bool _heartbeatInFlight = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadCredentials();
    _refresh();
    _statusTimer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => _refresh(),
    );
  }

  Future<void> _loadCredentials() async {
    try {
      final credentials = await CloudDeviceClient.loadCredentials();
      if (!mounted) return;
      if (credentials != null) {
        setState(() {
          _credentials = credentials;
          _serverUrlController.text = credentials.serverUrl;
          _deviceNameController.text = credentials.name;
        });
        _startHeartbeat();
      }
    } on PlatformException catch (e) {
      if (mounted)
        setState(
          () => _cloudError = 'تعذر قراءة بيانات الربط الآمنة: ${e.message}',
        );
    } on FormatException catch (e) {
      if (mounted) setState(() => _cloudError = e.message);
    }
  }

  Future<void> _refresh() async {
    try {
      final status = await _layoutChannel.invokeMapMethod<String, Object?>(
        'status',
      );
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
      await _layoutChannel.invokeMethod<void>('openAccessibilitySettings');
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _pairDevice() async {
    setState(() {
      _pairing = true;
      _cloudError = null;
    });
    CloudDeviceCredentials? credentials;
    try {
      credentials = await CloudDeviceClient.claim(
        serverUrl: _serverUrlController.text,
        code: _pairingCodeController.text,
        deviceName: _deviceNameController.text,
      );
      try {
        await CloudDeviceClient.saveCredentials(credentials);
      } catch (_) {
        // If secure storage fails, revoke the newly issued token rather than
        // leaving an inaccessible device record on the server.
        try {
          await CloudDeviceClient.unlink(credentials);
        } catch (_) {
          // The pairing result remains unpersisted; the web owner can revoke it.
        }
        rethrow;
      }
      if (!mounted) return;
      setState(() {
        _credentials = credentials;
        _pairingCodeController.clear();
        _cloudError = null;
      });
      _startHeartbeat();
    } on CloudApiException catch (e) {
      if (mounted) {
        setState(
          () => _cloudError = 'فشل الربط (HTTP ${e.statusCode}): ${e.message}',
        );
      }
    } on FormatException catch (e) {
      if (mounted) setState(() => _cloudError = e.message);
    } on PlatformException catch (e) {
      if (mounted)
        setState(
          () => _cloudError = 'تعذر حفظ بيانات الربط بأمان: ${e.message}',
        );
    } catch (e) {
      if (mounted) setState(() => _cloudError = 'تعذر الاتصال بالخادم: $e');
    } finally {
      if (mounted) setState(() => _pairing = false);
    }
  }

  void _startHeartbeat() {
    _heartbeatTimer?.cancel();
    final credentials = _credentials;
    if (credentials == null) return;
    final interval = credentials.heartbeatIntervalSeconds.clamp(15, 300);
    _heartbeatTimer = Timer.periodic(
      Duration(seconds: interval),
      (_) => _sendHeartbeat(),
    );
    if (WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed) {
      unawaited(_sendHeartbeat());
    }
  }

  Future<void> _sendHeartbeat() async {
    final credentials = _credentials;
    if (credentials == null || _heartbeatInFlight) return;
    _heartbeatInFlight = true;
    try {
      await CloudDeviceClient.sendHeartbeat(
        credentials,
        // The app currently reports accessibility layout only, not screen
        // capture. Never advertise capture unless a user-approved capture
        // feature is implemented and actively running.
        screenCaptureActive: false,
      );
      if (mounted) {
        setState(() {
          _lastHeartbeatAt = DateTime.now();
          _cloudError = null;
        });
      }
    } on CloudApiException catch (e) {
      if (e.statusCode == 401 || e.statusCode == 404) {
        await _forgetCredentials(
          'أُلغي ربط هذا الجهاز من الخادم. أدخل رمز ربط جديداً.',
        );
      } else if (mounted) {
        setState(() => _cloudError = 'تعذر إرسال نبضة الحالة: ${e.message}');
      }
    } on PlatformException catch (e) {
      if (mounted) setState(() => _cloudError = e.message);
    } catch (e) {
      if (mounted) setState(() => _cloudError = 'تعذر إرسال نبضة الحالة: $e');
    } finally {
      _heartbeatInFlight = false;
    }
  }

  Future<void> _sendTestEvent() async {
    final credentials = _credentials;
    if (credentials == null) return;
    setState(() {
      _sendingEvent = true;
      _cloudError = null;
    });
    try {
      await CloudDeviceClient.sendTestEvent(credentials);
      if (mounted) {
        setState(() => _cloudError = 'تم إرسال حدث اختبار إلى الخادم.');
      }
    } on CloudApiException catch (e) {
      if (e.statusCode == 401 || e.statusCode == 404) {
        await _forgetCredentials(
          'أُلغي ربط هذا الجهاز من الخادم. أدخل رمز ربط جديداً.',
        );
      } else if (mounted) {
        setState(() => _cloudError = 'تعذر إرسال الحدث: ${e.message}');
      }
    } catch (e) {
      if (mounted) setState(() => _cloudError = 'تعذر إرسال الحدث: $e');
    } finally {
      if (mounted) setState(() => _sendingEvent = false);
    }
  }

  Future<void> _unlinkDevice() async {
    final credentials = _credentials;
    if (credentials == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('إلغاء ربط الهاتف؟'),
        content: const Text(
          'سيُلغى رمز هذا الجهاز على الخادم وتُحذف بيانات الربط المحفوظة من الهاتف.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('رجوع'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('إلغاء الربط'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _unlinking = true;
      _cloudError = null;
    });
    try {
      await CloudDeviceClient.unlink(credentials);
      await _forgetCredentials('تم إلغاء الربط.');
    } on CloudApiException catch (e) {
      if (e.statusCode == 401 || e.statusCode == 404) {
        await _forgetCredentials('تمت إزالة هذا الجهاز من الخادم.');
      } else if (mounted) {
        setState(() => _cloudError = 'تعذر إلغاء الربط: ${e.message}');
      }
    } catch (e) {
      if (mounted) {
        setState(
          () => _cloudError = 'تعذر الاتصال بالخادم؛ بقي الربط محفوظاً: $e',
        );
      }
    } finally {
      if (mounted) setState(() => _unlinking = false);
    }
  }

  Future<void> _forgetCredentials(String message) async {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = null;
    try {
      await CloudDeviceClient.clearCredentials();
      if (mounted) {
        setState(() {
          _credentials = null;
          _lastHeartbeatAt = null;
          _cloudError = message;
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(
          () => _cloudError = 'تعذر حذف بيانات الربط الآمنة: ${e.message}',
        );
      }
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _startHeartbeat();
    } else {
      _heartbeatTimer?.cancel();
      _heartbeatTimer = null;
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _statusTimer?.cancel();
    _heartbeatTimer?.cancel();
    _serverUrlController.dispose();
    _pairingCodeController.dispose();
    _deviceNameController.dispose();
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
            _cloudLinkCard(),
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
            const SizedBox(height: 12),
            const Text(
              'إمكانية الوصول لا تُفعّل إلا بموافقتك من إعدادات Android. '
              'هذا التطبيق لا يلتقط صورة الشاشة ولا يرفع محتواها إلى الخادم. '
              'نبضات الحالة تعمل أثناء فتح التطبيق؛ ولا تُرسل أحداث استخدام حقيقية تلقائياً.',
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _cloudLinkCard() {
    final credentials = _credentials;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'الربط بخادم Mentor',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            if (credentials == null) ...[
              const Text(
                'أنشئ رمزاً مؤقتاً من واجهة Mentor على الويب، ثم أدخله هنا. '
                'يُحفظ رمز الجهاز مشفراً باستخدام Android Keystore.',
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _serverUrlController,
                keyboardType: TextInputType.url,
                decoration: const InputDecoration(
                  labelText: 'عنوان الخادم',
                  hintText: 'https://mentor.example',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _deviceNameController,
                textInputAction: TextInputAction.next,
                decoration: const InputDecoration(
                  labelText: 'اسم هذا الهاتف',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _pairingCodeController,
                keyboardType: TextInputType.number,
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => _pairDevice(),
                decoration: const InputDecoration(
                  labelText: 'رمز الربط',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: _pairing ? null : _pairDevice,
                child: Text(_pairing ? 'جارٍ الربط…' : 'ربط هذا الهاتف'),
              ),
            ] else ...[
              Text(
                'الحالة: ${_lastHeartbeatAt == null ? 'جارٍ الاتصال' : 'متصل بالخادم'}',
              ),
              Text('الجهاز: ${credentials.name}'),
              Text('آخر نبضة: ${_formatTime(_lastHeartbeatAt)}'),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  OutlinedButton(
                    onPressed: _sendingEvent ? null : _sendTestEvent,
                    child: Text(
                      _sendingEvent ? 'جارٍ الإرسال…' : 'إرسال حدث اختبار',
                    ),
                  ),
                  TextButton(
                    onPressed: _unlinking ? null : _unlinkDevice,
                    child: Text(
                      _unlinking ? 'جارٍ إلغاء الربط…' : 'إلغاء الربط',
                    ),
                  ),
                ],
              ),
            ],
            if (_cloudError != null) ...[
              const SizedBox(height: 8),
              Text(
                _cloudError!,
                style: TextStyle(
                  color:
                      _cloudError == 'تم إرسال حدث اختبار إلى الخادم.' ||
                          _cloudError == 'تم إلغاء الربط.' ||
                          _cloudError == 'تمت إزالة هذا الجهاز من الخادم.'
                      ? Theme.of(context).colorScheme.primary
                      : Theme.of(context).colorScheme.error,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _formatTime(DateTime? value) {
    if (value == null) return '—';
    final date = value.toLocal();
    return '${date.year}/${date.month.toString().padLeft(2, '0')}/${date.day.toString().padLeft(2, '0')} '
        '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }
}
