import 'package:flutter/material.dart';
import '../../protection/controller.dart';
import '../shared/widgets.dart';

class HealthPage extends StatelessWidget {
  const HealthPage({
    super.key,
    required this.controller,
    required this.onPairing,
  });
  final ProtectionController controller;
  final VoidCallback onPairing;
  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    final health = state?.health;
    return PageBody(
      children: [
        const PageHeading(
          'نظرة واضحة، دون مبالغة',
          'حالة الحماية',
          'نفصل الاتصال والتحليل والتنفيذ والتشفير، حتى تعرف ما يعمل فعلاً وما يحتاج اهتماماً.',
        ),
        SectionCard(
          color: const Color(0xffe9f0ee),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SafetyMark(size: 64),
              const SizedBox(height: 16),
              Text(
                state?.localReady == true
                    ? 'المسار المحلي جاهز حسب الجهاز'
                    : 'الحماية تحتاج إلى تحقق',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              Text(
                state?.localReady == true
                    ? 'جاهزية المسار لا تعني أن كل محتوى الشاشة آمن، ولا تؤكد وصول التقارير للوالد.'
                    : 'غير المهيأ والمجهول والمنقطع ليست حالات آمنة. راجع بطاقات الحالة أدناه.',
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  FilledButton.icon(
                    onPressed: controller.busy ? null : controller.refresh,
                    icon: const Icon(Icons.refresh),
                    label: const Text('تحديث الحالة'),
                  ),
                  OutlinedButton.icon(
                    onPressed: onPairing,
                    icon: const Icon(Icons.link),
                    label: const Text('إدارة الاقتران'),
                  ),
                ],
              ),
            ],
          ),
        ),
        LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth >= 600
                ? (constraints.maxWidth - 16) / 2
                : constraints.maxWidth;
            return Wrap(
              spacing: 16,
              runSpacing: 16,
              children: [
                _healthCard(
                  width,
                  Icons.usb,
                  'الاتصال المحلي',
                  switch (health?.companion) {
                    'connected' => 'متصل بقناة v2',
                    'legacy' => 'قناة قديمة · تشخيص فقط',
                    _ => 'غير متصل',
                  },
                  'يتطلب K230 وUSB؛ وجود الكابل وحده لا يثبت اتصال القناة.',
                  health?.companion == 'connected',
                ),
                _healthCard(
                  width,
                  Icons.visibility_outlined,
                  'تحليل المحتوى',
                  switch (health?.analysis) {
                    'ready' => 'المحلل جاهز',
                    'failed' => 'فشل التحليل',
                    'unknown' => 'نتيجة مجهولة',
                    _ => 'غير مهيأ',
                  },
                  'التحليل الجزئي أو عدم وجود مناطق مؤهلة لا يُعد نتيجة آمنة.',
                  health?.analysis == 'ready',
                ),
                _healthCard(
                  width,
                  Icons.shield_outlined,
                  'التنفيذ على الهاتف',
                  switch (health?.execution) {
                    'ready' => 'المنفّذ جاهز',
                    'locked' => 'الجهاز مقفل',
                    _ => 'تنفيذ غير مؤكد',
                  },
                  health?.accessibility == true
                      ? 'خدمة إمكانية الوصول مفعلة. نجاح التنفيذ يحتاج إقراراً فعلياً.'
                      : 'خدمة إمكانية الوصول غير مفعلة؛ لا نفترض وجود حاجب.',
                  health?.execution == 'ready' && health?.accessibility == true,
                ),
                _healthCard(
                  width,
                  Icons.enhanced_encryption_outlined,
                  'المفاتيح والتشفير',
                  switch (health?.encryption) {
                    'ready' => 'مفاتيح الجهاز جاهزة',
                    'locked' => 'المفاتيح مقفلة',
                    _ => 'التشفير غير جاهز',
                  },
                  'لا يوجد حفظ أو إرسال بديل بنص واضح عند فشل التشفير.',
                  health?.encryption == 'ready',
                ),
                _healthCard(
                  width,
                  Icons.cloud_outlined,
                  'إيصال التقارير',
                  switch (health?.cloud) {
                    'online' => 'الخدمة متصلة',
                    'offline' => 'دون اتصال',
                    'auth_error' => 'يلزم تحقق الحساب',
                    _ => 'Firebase غير مهيأ',
                  },
                  'الإشعارات قد تتأخر أو لا تصل. افتح الصندوق للمزامنة؛ الحماية المحلية مستقلة عن الإنترنت.',
                  health?.cloud == 'online',
                ),
                _healthCard(
                  width,
                  Icons.outbox_outlined,
                  'الصندوق الصادر المشفّر',
                  '${health?.outboxCount ?? '—'} بانتظار الإيصال',
                  'لا نعرض حالة «وصل» قبل إقرار التخزين الموثّق. عدم وجود تقارير لا يثبت أمان الشاشة.',
                  false,
                ),
              ],
            );
          },
        ),
        if (state?.profile != null)
          SectionCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'الملف المؤكد: ${state!.profile!.age} عاماً',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                Text(
                  'الفئة ${state.profile!.displayLabel} · مراجعة ${state.profile!.revision}',
                ),
              ],
            ),
          ),
        OutlinedButton.icon(
          onPressed: controller.busy ? null : controller.openSettings,
          icon: const Icon(Icons.accessibility_new),
          label: const Text('إعدادات إمكانية الوصول'),
        ),
      ],
    );
  }

  Widget _healthCard(
    double width,
    IconData icon,
    String title,
    String status,
    String description,
    bool ready,
  ) => SizedBox(
    width: width,
    child: SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 28),
          const SizedBox(height: 16),
          Text(
            title,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 12),
          StatusPill(status, ready: ready),
          const SizedBox(height: 12),
          Text(description),
        ],
      ),
    ),
  );
}
