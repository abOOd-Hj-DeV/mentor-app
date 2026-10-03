import 'package:flutter/material.dart';
import '../../protection/controller.dart';
import '../../protection/models.dart';
import '../pairing/pairing_page.dart';
import '../shared/widgets.dart';

class GuardianGate extends StatelessWidget {
  const GuardianGate({super.key, required this.controller});
  final ProtectionController controller;
  @override
  Widget build(BuildContext context) => PageBody(
    children: [
      const Align(
        alignment: AlignmentDirectional.centerStart,
        child: SafetyMark(),
      ),
      const PageHeading(
        'مساحة الوالد الخاصة',
        'التفاصيل تبقى لك',
        'تحقق على جهاز الوالد للوصول للإعدادات والتقارير. تُقفل الجلسة عند مغادرة التطبيق أو بعد دقيقتين كحد أقصى.',
      ),
      const NoticeBox(
        'الصندوق مشفّر. لا نعرض تطبيقاً أو درجات أو تفاصيل حادثة قبل تحقق الوالد.',
      ),
      FilledButton.icon(
        onPressed: controller.busy ? null : controller.authenticate,
        icon: const Icon(Icons.fingerprint),
        label: const Text('فتح مساحة الوالد'),
      ),
    ],
  );
}

class GuardianPage extends StatefulWidget {
  const GuardianPage({
    super.key,
    required this.controller,
    required this.onPairing,
    required this.onDiagnostics,
  });
  final ProtectionController controller;
  final VoidCallback onPairing, onDiagnostics;
  @override
  State<GuardianPage> createState() => _GuardianPageState();
}

class _GuardianPageState extends State<GuardianPage> {
  int? _age;
  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    if (!controller.guardianUnlocked) {
      return GuardianGate(controller: controller);
    }
    final previewAge = _age ?? controller.state?.profile?.age;
    final preview = previewAge == null ? null : AgeProfile(previewAge, '1');
    return PageBody(
      children: [
        const PageHeading(
          'إعدادات الأسرة',
          'رعاية تناسب العمر',
          'العمر يحدده الوالد صراحةً. لا توجد قيمة افتراضية، ولا يؤدي تغيير الملف إلى إزالة حاجب قائم.',
        ),
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('ملف العمر', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              Text(
                controller.state?.profile == null
                    ? 'لا يوجد ملف مؤكّد بعد'
                    : 'آخر ملف مؤكّد: ${controller.state!.profile!.age} عاماً · ${controller.state!.profile!.label}',
              ),
              const SizedBox(height: 20),
              DropdownButtonFormField<int>(
                initialValue: previewAge,
                decoration: const InputDecoration(
                  labelText: 'عمر الطفل · 10 إلى 15 عاماً',
                ),
                items: [
                  for (var age = 10; age <= 15; age++)
                    DropdownMenuItem(value: age, child: Text('$age عاماً')),
                ],
                onChanged: controller.busy
                    ? null
                    : (value) => setState(() => _age = value),
              ),
              if (preview != null) ...[
                const SizedBox(height: 16),
                StatusPill('معاينة الفئة ${preview.label}'),
                const SizedBox(height: 8),
                Text(
                  'تغطية ${(preview.cover * 100).round()}٪ · حاجب ${(preview.shield * 100).round()}٪ · انتقال ${(preview.exit * 100).round()}٪',
                ),
                const Text(
                  'المعاينة ليست ملفاً مطبقاً. تكرار الأحداث لا يطلب إلا الحاجب؛ المسار الرسومي لا يطلب الانتقال للشاشة الرئيسية.',
                ),
              ],
              const SizedBox(height: 20),
              FilledButton.icon(
                onPressed: controller.busy || previewAge == null
                    ? null
                    : () => controller.updateAge(previewAge),
                icon: const Icon(Icons.verified_user_outlined),
                label: const Text('إرسال إعداد العمر الموقّع'),
              ),
              if (controller.pendingProfile != null) ...[
                const SizedBox(height: 16),
                const NoticeBox(
                  'الإعداد بانتظار إيصال الطفل. ما زال آخر ملف صالح محفوظاً؛ لا نعرض تطبيقاً وهمياً.',
                ),
              ],
            ],
          ),
        ),
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'تحكم مباشر دون إنترنت',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              const Text(
                'اطلب تحدياً من جهاز الطفل، ثم امسحه هنا قبل إصدار إعداد أو منح رفع الحاجب. كل تحدٍ أحادي الاستخدام ومحدود الصلاحية.',
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  OutlinedButton(
                    onPressed: controller.busy
                        ? null
                        : () => controller.scan('challenge'),
                    child: const Text('مسح تحدي الطفل'),
                  ),
                  OutlinedButton(
                    onPressed: controller.busy
                        ? null
                        : () => controller.showQr('control'),
                    child: const Text('عرض التحكم الموقّع'),
                  ),
                  OutlinedButton(
                    onPressed: controller.busy
                        ? null
                        : () => controller.scan('receipt'),
                    child: const Text('مسح إيصال الطفل'),
                  ),
                ],
              ),
            ],
          ),
        ),
        if (controller.qr != null) PairQrCard(controller.qr!),
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'المفاتيح والخصوصية',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              const Text(
                'التقارير بيانات وصفية فقط. لا تُرسل صور أو صوت أو نصوص محادثات. يرى الخادم معرّفات التوجيه وحجم الرسالة المشفّرة ووقت الاستلام ومعلومات الشبكة، وليس محتواها.',
              ),
              const SizedBox(height: 8),
              const Text(
                'فقدان مفاتيح الوالد يمنع قراءة الرسائل القديمة؛ الحساب السحابي لا يستعيدها. يلزم إعداد Firebase حقيقي، وقد تتأخر FCM أو لا تصل.',
              ),
              const SizedBox(height: 16),
              OutlinedButton.icon(
                onPressed: widget.onPairing,
                icon: const Icon(Icons.link),
                label: const Text('الاقتران والبصمات'),
              ),
            ],
          ),
        ),
        OutlinedButton.icon(
          onPressed: () => _confirmRevoke(context),
          icon: const Icon(Icons.link_off),
          label: const Text('طلب إلغاء الاقتران'),
        ),
        OutlinedButton.icon(
          onPressed: controller.lock,
          icon: const Icon(Icons.lock_outline),
          label: const Text('قفل مساحة الوالد الآن'),
        ),
        TextButton.icon(
          onPressed: widget.onDiagnostics,
          icon: const Icon(Icons.developer_mode),
          label: const Text('تشخيص التخطيط المحلي المتقدم'),
        ),
      ],
    );
  }

  Future<void> _confirmRevoke(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('إلغاء الاقتران؟'),
        content: const Text(
          'يطلب هذا تحكماً موقّعاً. لا يزيل حاجب الطفل أو ملفه تلقائياً، ولا يعني أن الطفل استلم الإلغاء.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('تراجع'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('تأكيد الطلب'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) await widget.controller.revoke();
  }
}
