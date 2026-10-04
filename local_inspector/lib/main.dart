import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const LocalInspectorApp());

class LocalInspectorApp extends StatelessWidget {
  const LocalInspectorApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'حماية محلية',
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xff176b63),
        brightness: Brightness.dark,
      ),
      scaffoldBackgroundColor: const Color(0xff101c25),
      cardTheme: const CardThemeData(
        color: Color(0xff1a2b36),
        margin: EdgeInsets.only(bottom: 14),
      ),
    ),
    home: const Directionality(
      textDirection: TextDirection.rtl,
      child: MonitorPage(),
    ),
  );
}

class MonitorPage extends StatefulWidget {
  const MonitorPage({super.key});
  @override
  State<MonitorPage> createState() => _MonitorPageState();
}

class _MonitorPageState extends State<MonitorPage> {
  static const _channel = MethodChannel('local_inspector/control');
  Timer? _timer;
  Map<String, dynamic> _status = {};
  int _age = 10;
  bool _accepted = false;
  bool _working = false;
  String _message = '';

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final raw = await _channel.invokeMethod<String>('status');
      if (!mounted || raw == null) return;
      final status = jsonDecode(raw) as Map<String, dynamic>;
      setState(() {
        _status = status;
        if (!_working && status['active'] == true) _age = status['age'] as int;
      });
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() => _message = error.message ?? 'تعذر قراءة حالة الحماية');
      }
    } on MissingPluginException {
      if (mounted) {
        setState(() => _message = 'هذه النسخة تتطلب هاتف Android 11 أو أحدث');
      }
    }
  }

  Future<void> _command(String method) async {
    setState(() {
      _working = true;
      _message = '';
    });
    try {
      await _channel.invokeMethod<void>(
        method,
        method == 'start' ? {'age': _age} : null,
      );
      await _refresh();
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() => _message = error.message ?? 'تعذر تنفيذ الطلب');
      }
    } on MissingPluginException {
      if (mounted) setState(() => _message = 'الخدمة متاحة على Android فقط');
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  Widget _section(String title, List<Widget> children) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 14),
          ...children,
        ],
      ),
    ),
  );
  String _number(String key) =>
      ((_status[key] as num?) ?? 0).toStringAsFixed(1);
  Widget _metric(String title, String value) => Expanded(
    child: Column(
      children: [
        Text(value, style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 4),
        Text(title, textAlign: TextAlign.center),
      ],
    ),
  );
  String _stage(int stage) => switch (stage) {
    1 => 'تغطية منطقة الصورة',
    2 => 'حاجب كامل',
    3 => 'طلب العودة للرئيسية',
    _ => 'مراقبة دون حجب',
  };
  @override
  Widget build(BuildContext context) {
    final active = _status['active'] == true;
    final enabled = _status['enabled'] == true;
    final ready = _status['ready'] == true;
    final error = (_status['error'] as String?) ?? '';
    final scores = _status['scores'] as Map<String, dynamic>?;
    final events = (_status['events'] as List<dynamic>?) ?? [];
    return Scaffold(
      appBar: AppBar(
        title: const Text('حماية محلية'),
        actions: [
          const Padding(
            padding: EdgeInsets.all(16),
            child: Icon(Icons.phonelink_lock),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text(
            'التحليل يبقى على هاتفك',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
          const SizedBox(height: 10),
          const Text(
            'YOLO يحدد مناطق الصور، وNSFWJS يصنفها. النموذجان مضمّنان في التطبيق، واللقطات لا تُحفظ أو تُرفع.',
          ),
          const SizedBox(height: 22),
          _section('إعداد الحماية', [
            Text(
              enabled
                  ? 'خدمة إمكانية الوصول مفعلة'
                  : 'الخطوة الأولى: تفعيل خدمة «حماية محلية»',
            ),
            const SizedBox(height: 10),
            OutlinedButton.icon(
              onPressed: _working ? null : () => _command('settings'),
              icon: const Icon(Icons.accessibility_new),
              label: const Text('فتح إعدادات إمكانية الوصول'),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<int>(
              initialValue: _age,
              decoration: const InputDecoration(labelText: 'عمر الطفل'),
              items: [
                for (int age = 10; age <= 15; age++)
                  DropdownMenuItem(value: age, child: Text('$age سنة')),
              ],
              onChanged: active || _working
                  ? null
                  : (age) => setState(() => _age = age!),
            ),
            const SizedBox(height: 8),
            Text(
              _age <= 12
                  ? 'فئة ١٠–١٢: تغطية 60%، حاجب 80%، HOME عند 90%'
                  : 'فئة ١٣–١٥: تغطية 70%، حاجب 85%، HOME عند 95%',
            ),
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              value: _accepted,
              onChanged: active
                  ? null
                  : (value) => setState(() => _accepted = value ?? false),
              title: const Text('أوافق على التقاط الشاشة محلياً وتطبيق الحجب'),
              subtitle: const Text(
                'يتطلب Android 11+. يمكنني الإيقاف في أي وقت.',
              ),
            ),
            FilledButton.icon(
              onPressed: _working || (!active && (!_accepted || !enabled))
                  ? null
                  : () => _command(active ? 'stop' : 'start'),
              icon: Icon(
                active ? Icons.stop_circle_outlined : Icons.shield_outlined,
              ),
              label: Text(active ? 'إيقاف الحماية' : 'بدء الحماية المحلية'),
            ),
            if (_message.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(_message),
              ),
          ]),
          _section('الحالة المباشرة', [
            Text(
              active
                  ? (ready
                        ? 'الحماية تعمل — افتح التطبيق المراد مراقبته'
                        : 'جاري تحميل النموذجين…')
                  : 'الحماية متوقفة',
            ),
            const SizedBox(height: 10),
            Text(
              'النموذجان: ${ready ? 'محملان محلياً' : 'لم يتم تحميلهما بعد'}',
            ),
            Text(_stage((_status['stage'] as int?) ?? 0)),
            const SizedBox(height: 18),
            Row(
              children: [
                _metric('التقاط / ثانية', _number('captureFps')),
                _metric('تحليل / ثانية', _number('analysisFps')),
                _metric('زمن التحليل ms', '${_status['analysisMs'] ?? 0}'),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              'اللقطات: ${_status['captured'] ?? 0} • المحللة: ${_status['analyzed'] ?? 0} • المتجاوزة: ${_status['dropped'] ?? 0}',
            ),
            Text('عدد تصنيفات NSFWJS: ${_status['classifications'] ?? 0}'),
            const SizedBox(height: 8),
            const Text(
              'المستهدف نحو 3 لقطات/ثانية. المعدل الفعلي يعتمد على الهاتف وعدد المناطق؛ تُتجاوز اللقطات القديمة عند البطء.',
            ),
            if (error.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  error,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
          ]),
          _section('آخر تصنيف', [
            if (scores == null)
              const Text(
                'لم تُصنّف منطقة صورة بعد؛ هذا لا يثبت أن الشاشة آمنة.',
              )
            else ...[
              for (final label in [
                'Porn',
                'Hentai',
                'Sexy',
                'Neutral',
                'Drawing',
              ])
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Row(
                    children: [
                      Expanded(child: Text(label)),
                      Text(
                        '${(((scores[label] as num?) ?? 0) * 100).toStringAsFixed(1)}%',
                      ),
                    ],
                  ),
                ),
              Text(
                'Porn + Hentai: ${(((scores['explicit'] as num?) ?? 0) * 100).toStringAsFixed(1)}%',
              ),
            ],
            const SizedBox(height: 12),
            const Text(
              'Hentai المرتفعة وحدها تحتاج أكثر من 60% وتأكيداً أطول. لا تنفّذ HOME وحدها مهما تكررت. Sexy مؤشر منفصل.',
            ),
            const SizedBox(height: 8),
            const Text(
              'التغطية تحجب مساحة الصورة المصنفة بغطاء معتم. الحاجب الكامل هادئ، وHOME يرجع للرئيسية ولا يغلق التطبيق بالقوة.',
            ),
          ]),
          _section('الأحداث على هذا الجهاز', [
            const Text(
              'سجل مؤقت دون صور أو صوت، يُمسح عند انتهاء عملية التطبيق.',
            ),
            const SizedBox(height: 10),
            if (events.isEmpty) const Text('لا توجد إجراءات مسجلة بعد.'),
            for (final value in events.take(20))
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.shield_outlined),
                title: Text(
                  (value as Map<String, dynamic>)['action'] as String,
                ),
                subtitle: Text(value['package'] as String),
              ),
            if (events.isNotEmpty)
              TextButton(
                onPressed: () => _command('clearEvents'),
                child: const Text('مسح السجل'),
              ),
          ]),
        ],
      ),
    );
  }
}
