import 'package:flutter/material.dart';
import '../../protection/controller.dart';
import '../shared/widgets.dart';

class SetupPage extends StatelessWidget {
  const SetupPage({
    super.key,
    required this.controller,
    required this.choice,
    required this.onChoice,
    required this.onPairing,
  });
  final ProtectionController controller;
  final String? choice;
  final ValueChanged<String> onChoice;
  final VoidCallback onPairing;
  @override
  Widget build(BuildContext context) => PageBody(
    children: [
      const Align(
        alignment: AlignmentDirectional.centerStart,
        child: SafetyMark(),
      ),
      const PageHeading(
        'مُرشد · مساحة أكثر طمأنينة',
        'خطوة صغيرة، رعاية أكبر',
        'إعداد واضح للأسرة، وحماية محلية تحترم الخصوصية. لا صور محفوظة، ولا تفاصيل حساسة في الإشعارات.',
      ),
      const NoticeBox(
        'لم تُهيأ الحماية بعد. اختيار نوع الجهاز يشرح خطوات الإعداد فقط، ولا يمنح صلاحيات الوالد.',
      ),
      SectionCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'لمن هذا الجهاز؟',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 16),
            _choice(
              Icons.supervisor_account_outlined,
              'جهاز الوالد',
              'إعداد الملف، الاقتران المباشر ومراجعة التقارير المشفّرة.',
              'guardian',
            ),
            const SizedBox(height: 12),
            _choice(
              Icons.favorite_border_rounded,
              'جهاز الطفل',
              'حالة هادئة ومساعدة آمنة، دون وصول لإعدادات الوالد.',
              'child',
            ),
          ],
        ),
      ),
      if (choice != null)
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                choice == 'guardian'
                    ? 'ابدأ بتحقق الوالد'
                    : 'مع الوالد، خطوة بخطوة',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 12),
              Text(
                choice == 'guardian'
                    ? 'يُطلب رمز الجهاز أو البصمة على جهاز الوالد. لا تكفي بصمة الطفل لمنح سلطة الوالد.'
                    : 'امسح عرض الاقتران من جهاز الوالد ثم أكمل التأكيد على الجهازين. تفعيل إمكانية الوصول وUSB يحتاج موافقتك الواضحة.',
              ),
              const SizedBox(height: 16),
              if (choice == 'guardian')
                FilledButton.icon(
                  onPressed: controller.busy ? null : controller.authenticate,
                  icon: const Icon(Icons.lock_outline),
                  label: const Text('تحقق الوالد على جهازه'),
                ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: onPairing,
                icon: const Icon(Icons.qr_code_scanner),
                label: const Text('خطوات الاقتران المباشر'),
              ),
            ],
          ),
        ),
      const NoticeBox(
        'تحتاج الحماية المحلية إلى K230 وUSB وخدمة إمكانية الوصول. الاتصال بالإنترنت لإيصال التقارير فقط، وليس دليلاً على تنفيذ الحماية.',
      ),
    ],
  );
  Widget _choice(
    IconData icon,
    String title,
    String description,
    String value,
  ) => Semantics(
    selected: choice == value,
    button: true,
    child: Card(
      color: choice == value ? const Color(0xffe9f0ee) : null,
      child: InkWell(
        borderRadius: BorderRadius.circular(24),
        onTap: () => onChoice(value),
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Row(
            children: [
              Icon(icon, size: 30),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 18,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(description),
                  ],
                ),
              ),
              if (choice == value) const Icon(Icons.check_circle_outline),
            ],
          ),
        ),
      ),
    ),
  );
}
