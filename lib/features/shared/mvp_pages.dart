import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../protection/controller.dart';
import 'widgets.dart';

class MvpChildPage extends StatelessWidget {
  const MvpChildPage({
    super.key,
    required this.controller,
    this.accessibility = false,
  });
  final ProtectionController controller;
  final bool accessibility;

  @override
  Widget build(BuildContext context) => PageBody(
    children: [
      const _CareHero(
        eyebrow: 'مُرشد معك',
        title: 'مساحتك، بإيقاع أهدأ',
        subtitle: 'قليل من الحماية، وقليل من الاستراحة. يومك أكبر من شاشة.',
      ),
      _InfoCard(
        icon: Icons.shield_rounded,
        title: 'حين يظهر محتوى غير مناسب',
        text: 'توقف اللوحة التطبيق، ثم يظهر لغز صغير لتغيير المشهد.',
        color: const Color(0xffe6f3ed),
      ),
      _InfoCard(
        icon: Icons.self_improvement_rounded,
        title: 'استراحة كل 20 دقيقة',
        text:
            'عند استخدام التطبيق نفسه باستمرار، نذكّرك بالحركة والماء والراحة.',
        color: const Color(0xffeee9f8),
      ),
      _InfoCard(
        icon: Icons.accessibility_new_rounded,
        title: accessibility
            ? 'تذكير الاستراحة جاهز'
            : 'لنفعّل تذكير الاستراحة',
        text: accessibility
            ? 'خدمة إمكانية الوصول مفعلة. اتصال اللوحة والتحليل يحتاجان تحققاً مستقلاً.'
            : 'فعّل خدمة مُرشد من إعدادات إمكانية الوصول. لا تحتاج إلى إدخال عمر.',
        color: const Color(0xfffff5e5),
      ),
      OutlinedButton.icon(
        onPressed: () => const MethodChannel(
          'dev.k230.mentor/layout',
        ).invokeMethod<void>('openAccessibilitySettings'),
        icon: const Icon(Icons.accessibility_new_rounded),
        label: const Text('فتح إمكانية الوصول'),
      ),
      const Center(
        child: Text(
          'لا صور محفوظة، ولا محادثات تُرفع',
          style: TextStyle(color: Color(0xff64777c)),
        ),
      ),
    ],
  );
}

class MvpGuardianPage extends StatelessWidget {
  const MvpGuardianPage({
    super.key,
    required this.summary,
    required this.onRefresh,
  });
  final Map<String, dynamic> summary;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) {
    final linked = summary['linked'] == true;
    final blocked = (summary['blockedThreats'] as num?)?.toInt() ?? 0;
    final today = (summary['todayUsageMs'] as num?)?.toInt() ?? 0;
    final yesterday = (summary['yesterdayUsageMs'] as num?)?.toInt() ?? 0;
    final comparison = summary['comparisonReady'] == true;
    final increase = (today - yesterday) ~/ 60000;
    return PageBody(
      children: [
        const _CareHero(
          eyebrow: 'مُرشد · العائلة أولاً',
          title: 'قربك منه هو الحماية الأهم',
          subtitle: 'ملخص بسيط لما حُجب وما يستحق انتباهك، بلا إعدادات مربكة.',
        ),
        SectionCard(
          child: Row(
            children: [
              const CircleAvatar(
                radius: 28,
                backgroundColor: Color(0xffe6f3ed),
                child: Icon(Icons.shield_rounded, color: Color(0xff2d7568)),
              ),
              const SizedBox(width: 18),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      linked ? '$blocked' : '—',
                      style: const TextStyle(
                        fontSize: 38,
                        fontWeight: FontWeight.w800,
                        color: Color(0xff2d7568),
                      ),
                    ),
                    const Text('تهديدات أُوقف تطبيقها فعلياً'),
                  ],
                ),
              ),
            ],
          ),
        ),
        const _InfoCard(
          icon: Icons.favorite_rounded,
          title: 'رسالة عناية',
          text:
              'اسأل طفلك عن يومه، لا عن هاتفه فقط. حوار هادئ ونشاط مشترك قد يصنعان فرقاً.',
          color: Color(0xfffff5e5),
        ),
        _InfoCard(
          icon: Icons.auto_graph_rounded,
          title: 'تنبيهات الاستخدام',
          text: !linked
              ? 'بانتظار أول تقرير من جهاز الطفل. لا توجد أرقام تقديرية.'
              : comparison && increase > 0
              ? 'زاد الاستخدام المسجّل بمقدار $increase دقيقة مقارنة بالأمس. خصّص وقتاً لنشاط مشترك؛ زيادة الاستخدام ليست تشخيصاً نفسياً.'
              : 'الاستخدام في يوم التقرير: ${today ~/ 60000} دقيقة. ${comparison ? 'لم تُسجّل زيادة عن أمس.' : 'تحتاج المقارنة إلى يوم سابق مكتمل من القياس.'}',
          color: const Color(0xffe8f1f8),
        ),
        if (linked)
          _InfoCard(
            icon: Icons.self_improvement_rounded,
            title: 'لحظات استراحة',
            text:
                '${summary['usageBreaks'] ?? 0} تذكيرات عُرضت بعد الاستخدام المتواصل. شجّعه على الحركة والماء والنوم المنتظم.',
            color: const Color(0xffeee9f8),
          ),
        FilledButton.icon(
          onPressed: onRefresh,
          icon: const Icon(Icons.sync),
          label: const Text('تحديث الملخص'),
        ),
        const Center(
          child: Text(
            'ملخص فقط: دون صور أو محتوى محادثات',
            style: TextStyle(color: Color(0xff64777c)),
          ),
        ),
      ],
    );
  }
}

class MvpStartPage extends StatelessWidget {
  const MvpStartPage({super.key, required this.onRole, this.error = false});
  final ValueChanged<String> onRole;
  final bool error;
  @override
  Widget build(BuildContext context) => PageBody(
    children: [
      const _CareHero(
        eyebrow: 'أهلاً بك في مُرشد',
        title: 'حماية بسيطة، ومساحة للحياة',
        subtitle:
            'اختر وظيفة هذا الجهاز مرة واحدة. لا عمر، ولا رمز اقتران، ولا إعدادات معقّدة في النسخة التجريبية.',
      ),
      FilledButton.icon(
        onPressed: () => onRole('child'),
        icon: const Icon(Icons.child_care),
        label: const Text('هذا جهاز الطفل'),
      ),
      OutlinedButton.icon(
        onPressed: () => onRole('guardian'),
        icon: const Icon(Icons.favorite_outline),
        label: const Text('هذا جهاز الأهل'),
      ),
      if (error)
        const NoticeBox('تعذّر حفظ وظيفة الجهاز. أعد المحاولة.', error: true),
    ],
  );
}

class _InfoCard extends StatelessWidget {
  const _InfoCard({
    required this.icon,
    required this.title,
    required this.text,
    required this.color,
  });
  final IconData icon;
  final String title, text;
  final Color color;

  @override
  Widget build(BuildContext context) => SectionCard(
    color: color,
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: const Color(0xff416a65), size: 30),
        const SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 7),
              Text(text, style: const TextStyle(height: 1.6)),
            ],
          ),
        ),
      ],
    ),
  );
}

class _CareHero extends StatelessWidget {
  const _CareHero({
    required this.eyebrow,
    required this.title,
    required this.subtitle,
  });
  final String eyebrow, title, subtitle;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(24),
    decoration: BoxDecoration(
      borderRadius: BorderRadius.circular(30),
      gradient: const LinearGradient(
        colors: [Color(0xffdef2e9), Color(0xffede7f7)],
      ),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          eyebrow,
          style: const TextStyle(
            color: Color(0xff2d7568),
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 12),
        const Center(
          child: SizedBox(
            width: 180,
            height: 112,
            child: CustomPaint(painter: _FamilyPainter()),
          ),
        ),
        const SizedBox(height: 14),
        Text(
          title,
          style: Theme.of(context).textTheme.headlineSmall?.copyWith(
            color: const Color(0xff18353f),
            fontWeight: FontWeight.w800,
            height: 1.3,
          ),
        ),
        const SizedBox(height: 10),
        Text(subtitle, style: const TextStyle(height: 1.6)),
      ],
    ),
  );
}

class _FamilyPainter extends CustomPainter {
  const _FamilyPainter();
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = const Color(0xfff7cba0);
    canvas.drawCircle(const Offset(112, 28), 21, paint);
    canvas.drawCircle(const Offset(67, 44), 16, paint);
    paint.color = const Color(0xff4e8979);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        const Rect.fromLTWH(87, 50, 54, 61),
        const Radius.circular(23),
      ),
      paint,
    );
    paint.color = const Color(0xff8270b1);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        const Rect.fromLTWH(47, 62, 42, 49),
        const Radius.circular(18),
      ),
      paint,
    );
    paint
      ..color = const Color(0xffd4b364)
      ..strokeWidth = 4
      ..strokeCap = StrokeCap.round;
    for (final center in [const Offset(24, 26), const Offset(159, 55)]) {
      canvas.drawLine(
        center - const Offset(5, 0),
        center + const Offset(5, 0),
        paint,
      );
      canvas.drawLine(
        center - const Offset(0, 5),
        center + const Offset(0, 5),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
