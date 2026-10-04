import 'package:flutter/material.dart';
import '../../protection/controller.dart';
import '../pairing/pairing_page.dart';
import '../shared/widgets.dart';
import 'remote_server_card.dart';

class ChildPage extends StatelessWidget {
  const ChildPage({super.key, required this.controller});
  final ProtectionController controller;
  @override
  Widget build(BuildContext context) {
    final active = controller.state?.activeProtection;
    final older = active?.explanationKey == 'calm_older';
    return PageBody(
      children: [
        const Align(
          alignment: AlignmentDirectional.centerStart,
          child: SafetyMark(size: 104),
        ),
        PageHeading(
          'مُرشد معك',
          active == null ? 'مساحتك، بهدوء' : 'لنأخذ لحظة هادئة',
          active == null
              ? 'يمكنك متابعة حالة الاتصال وطلب المساعدة. لا توجد هنا تقاريرك الخاصة أو إعدادات الوالد.'
              : older
              ? 'ظهر محتوى يحتاج وقفة. لا حكم عليك؛ اختر الرجوع لمكان آمن أو تحدث مع الوالد.'
              : 'أحياناً يظهر شيء غير مناسب لنا. لننتقل إلى مكان أهدأ، ويمكنك طلب مساعدة الوالد.',
        ),
        if (active != null)
          SectionCard(
            color: const Color(0xffe9f0ee),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'حاجب الحماية قائم',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 12),
                const Text(
                  'لا نزيل الحاجب لمجرد تغير نتيجة التحليل. نحتاج انتقالاً مؤكداً أو إذناً موقّعاً من الوالد.',
                ),
                const SizedBox(height: 20),
                Wrap(
                  spacing: 12,
                  runSpacing: 12,
                  children: [
                    FilledButton.icon(
                      onPressed: controller.busy || !active.canNavigateHome
                          ? null
                          : controller.home,
                      icon: const Icon(Icons.home_outlined),
                      label: const Text('العودة لمكان آمن'),
                    ),
                    OutlinedButton.icon(
                      onPressed: controller.busy
                          ? null
                          : controller.requestHelp,
                      icon: const Icon(Icons.favorite_border),
                      label: const Text('طلب مساعدة الوالد'),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                OutlinedButton(
                  onPressed: controller.busy
                      ? null
                      : () => controller.scan('control'),
                  child: const Text('مسح إذن الوالد المشفّر'),
                ),
              ],
            ),
          )
        else
          NoticeBox(
            controller.state?.localReady == true
                ? 'المسار المحلي جاهز حسب الجهاز. هذا ليس إعلاناً بأن كل محتوى الشاشة آمن.'
                : 'الحماية غير مؤكدة الآن. اطلب مساعدة الوالد للتحقق من USB والأذونات وإعداد الملف.',
          ),
        if (controller.qr != null) PairQrCard(controller.qr!),
        OutlinedButton.icon(
          onPressed: controller.busy ? null : controller.refresh,
          icon: const Icon(Icons.refresh),
          label: const Text('تحديث الحالة'),
        ),
        OutlinedButton.icon(
          onPressed: controller.busy ? null : controller.openSettings,
          icon: const Icon(Icons.accessibility_new),
          label: const Text('أذونات الحماية المحلية'),
        ),
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'إعداد مع الوالد',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 8),
              const Text(
                'لضبط العمر دون إنترنت، اعرض تحدياً للوالد ثم امسح إجابته الموقّعة. هذه الخطوة لا تغير الملف وحدها.',
              ),
              const SizedBox(height: 16),
              OutlinedButton(
                onPressed: controller.busy
                    ? null
                    : () => controller.challenge('set_profile'),
                child: const Text('عرض تحدي إعداد العمر'),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: controller.busy
                    ? null
                    : () => controller.scan('control'),
                child: const Text('مسح إجابة الوالد'),
              ),
            ],
          ),
        ),
        const RemoteServerCard(),
        const NoticeBox(
          'المسار المحلي لا يرفع صوراً أو صوتاً. مشاركة الشاشة مع سيرفر التحليل اختيارية وتحتاج موافقة مستقلة. تبقى تنبيهات الوالد مشفّرة من الطرف إلى الطرف.',
        ),
      ],
    );
  }
}
