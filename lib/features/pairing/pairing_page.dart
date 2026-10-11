import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../../protection/controller.dart';
import '../../protection/models.dart';
import '../shared/widgets.dart';

class PairingPage extends StatelessWidget {
  const PairingPage({super.key, required this.controller, this.choice});
  final ProtectionController controller;
  final String? choice;
  @override
  Widget build(BuildContext context) {
    final guardian =
        controller.state?.role == 'guardian' ||
        (controller.state?.role != 'child' && choice == 'guardian');
    final busy = controller.busy;
    return PageBody(
      children: [
        const PageHeading(
          'ثقة من جهاز إلى جهاز',
          'اقتران مباشر، بلا وسيط للمفاتيح',
          'امسح الرموز أمام الوالد، وقارن البصمات كاملة على الجهازين. لا يمكن للخادم استبدال المفتاح الذي وثقت به.',
        ),
        StatusPill(switch (controller.state?.pairing) {
          'paired' => 'الاقتران مؤكّد من الجهاز',
          'pending' => 'بانتظار التأكيد النهائي',
          'key_lost' => 'مفتاح مفقود · يلزم اقتران جديد',
          'revoked' => 'الاقتران ملغى',
          _ => 'غير مقترن',
        }, ready: controller.state?.pairing == 'paired'),
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                guardian ? 'خطوات جهاز الوالد' : 'خطوات جهاز الطفل',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 12),
              Text(
                guardian
                    ? '١. تحقق محلياً واعرض عرض الاقتران.\n٢. امسح استجابة الطفل وقارن البصمات.\n٣. اعرض التأكيد ليمسحه الطفل.'
                    : '١. امسح عرض الوالد.\n٢. اعرض استجابتك ليمسحها الوالد.\n٣. امسح تأكيد الوالد. لا يصبح الجهاز مقترناً قبل التحقق.',
              ),
              const SizedBox(height: 20),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: guardian
                    ? [
                        FilledButton.icon(
                          onPressed: busy ? null : controller.createOffer,
                          icon: const Icon(Icons.qr_code),
                          label: const Text('إنشاء عرض الاقتران'),
                        ),
                        OutlinedButton(
                          onPressed: busy
                              ? null
                              : () => controller.scan('response'),
                          child: const Text('مسح استجابة الطفل'),
                        ),
                        OutlinedButton(
                          onPressed: busy
                              ? null
                              : () => controller.showQr('confirmation'),
                          child: const Text('عرض التأكيد'),
                        ),
                      ]
                    : [
                        FilledButton.icon(
                          onPressed: busy
                              ? null
                              : () => controller.scan('offer'),
                          icon: const Icon(Icons.qr_code_scanner),
                          label: const Text('مسح عرض الوالد'),
                        ),
                        OutlinedButton(
                          onPressed: busy
                              ? null
                              : () => controller.showQr('response'),
                          child: const Text('عرض الاستجابة'),
                        ),
                        OutlinedButton(
                          onPressed: busy
                              ? null
                              : () => controller.scan('confirmation'),
                          child: const Text('مسح التأكيد'),
                        ),
                      ],
              ),
            ],
          ),
        ),
        if (controller.qr != null) PairQrCard(controller.qr!),
        const NoticeBox(
          'الرمز يحمل مفاتيح عامة أو رسالة مشفّرة فقط. لا تُشارك رموز التحكم خارج الأسرة. فقدان مفاتيح فك التشفير يجعل التقارير القديمة غير قابلة للاستعادة.',
        ),
        OutlinedButton.icon(
          onPressed: busy ? null : controller.refresh,
          icon: const Icon(Icons.refresh),
          label: const Text('تحقق من حالة الاقتران'),
        ),
      ],
    );
  }
}

class PairQrCard extends StatelessWidget {
  const PairQrCard(this.qr, {super.key});
  final PairQr qr;
  @override
  Widget build(BuildContext context) => SectionCard(
    child: Column(
      children: [
        const Text(
          'للمسح المباشر على الجهاز الآخر',
          style: TextStyle(fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 16),
        LayoutBuilder(
          builder: (context, constraints) => Semantics(
            label:
                'رمز موثّق صادر من Android. استخدم كاميرا الجهاز الآخر للمسح.',
            child: QrImageView(
              data: qr.payload,
              size: constraints.maxWidth.clamp(120, 320).toDouble(),
              backgroundColor: Colors.white,
              errorCorrectionLevel: QrErrorCorrectLevel.L,
              errorStateBuilder: (_, _) => const NoticeBox(
                'تعذر عرض الرمز دون اقتطاع. أعد إنشاؤه من الجهاز الأصلي.',
                error: true,
              ),
            ),
          ),
        ),
        for (final entry in qr.fingerprints.entries) ...[
          const SizedBox(height: 12),
          Text(switch (entry.key) {
            'guardian_hpke' => 'بصمة تشفير الوالد',
            'child_hpke' => 'بصمة تشفير الطفل',
            'guardian_signing' => 'بصمة توقيع الوالد',
            'child_signing' => 'بصمة توقيع الطفل',
            _ => 'بصمة المفتاح الموثّق',
          }),
          SelectableText(
            RegExp(
              '.{1,4}',
            ).allMatches(entry.value).map((m) => m.group(0)).join(' '),
            textDirection: TextDirection.ltr,
            textAlign: TextAlign.center,
          ),
        ],
      ],
    ),
  );
}
