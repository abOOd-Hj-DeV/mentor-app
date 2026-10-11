import 'package:flutter/material.dart';
import '../../protection/controller.dart';
import '../../protection/models.dart';
import '../guardian/guardian_page.dart';
import '../shared/widgets.dart';

class IncidentsPage extends StatelessWidget {
  const IncidentsPage({
    super.key,
    required this.controller,
    required this.onOpen,
  });
  final ProtectionController controller;
  final ValueChanged<String> onOpen;
  @override
  Widget build(BuildContext context) {
    if (!controller.guardianUnlocked) {
      return GuardianGate(controller: controller);
    }
    return PageBody(
      children: [
        const PageHeading(
          'تقارير مشفّرة للأسرة',
          'صندوق الحماية',
          'بيانات وصفية موثّقة من الطفل؛ بلا صور أو تسجيلات. التحديثات لنفس الحدث تظهر مرة واحدة، مع آخر تنفيذ مؤكد.',
        ),
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            FilledButton.icon(
              onPressed: controller.busy ? null : () => controller.loadInbox(),
              icon: const Icon(Icons.inbox_outlined),
              label: const Text('قراءة الصندوق الموثّق'),
            ),
            OutlinedButton.icon(
              onPressed: controller.busy ? null : controller.sync,
              icon: const Icon(Icons.sync),
              label: const Text('جدولة المزامنة'),
            ),
          ],
        ),
        if (controller.state?.health.cloud != 'online')
          const NoticeBox(
            'الإيصال السحابي غير متصل أو غير مهيأ. يمكنك قراءة ما تحقق منه الجهاز محلياً؛ الإشعار ليس ضماناً للوصول.',
          ),
        if (controller.incidents.isEmpty)
          SectionCard(
            child: Column(
              children: [
                const Icon(Icons.mark_email_read_outlined, size: 44),
                const SizedBox(height: 16),
                Text(
                  controller.inboxLoaded
                      ? 'لا تقارير موثّقة في هذه الصفحة'
                      : 'صندوقك الخاص، عند الحاجة',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                Text(
                  controller.inboxLoaded
                      ? 'عدم وجود تقرير لا يعني أن كل محتوى الطفل آمن.'
                      : 'اضغط قراءة الصندوق للوصول إلى البيانات التي تحقق منها Android وفك تشفيرها بعد مصادقتك.',
                ),
              ],
            ),
          ),
        for (final incident in controller.incidents)
          Card(
            child: InkWell(
              borderRadius: BorderRadius.circular(24),
              onTap: controller.busy ? null : () => onOpen(incident.eventId),
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      incident.category == 'hentai_dominant'
                          ? 'دليل رسومي · حماية هادئة'
                          : 'دليل محتوى صريح',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(incident.appPackage, textDirection: TextDirection.ltr),
                    const SizedBox(height: 8),
                    Text(
                      MaterialLocalizations.of(
                        context,
                      ).formatMediumDate(incident.occurredAt),
                    ),
                    const SizedBox(height: 12),
                    StatusPill(
                      executionName(incident.executed.status),
                      ready: incident.executed.status == 'executed',
                    ),
                    const SizedBox(height: 8),
                    Text('المطلوب: ${stageName(incident.requestedStage)}'),
                    Text('الفعلي: ${stageName(incident.executed.stage)}'),
                    const SizedBox(height: 12),
                    const Text('عرض التفاصيل الموثّقة ←'),
                  ],
                ),
              ),
            ),
          ),
        if (controller.nextCursor != null)
          OutlinedButton(
            onPressed: controller.busy
                ? null
                : () => controller.loadInbox(more: true),
            child: const Text('تحميل المزيد'),
          ),
      ],
    );
  }
}

class IncidentDetails extends StatelessWidget {
  const IncidentDetails({
    super.key,
    required this.controller,
    required this.incident,
  });
  final ProtectionController controller;
  final Incident incident;
  @override
  Widget build(BuildContext context) {
    if (!controller.guardianUnlocked) {
      return GuardianGate(controller: controller);
    }
    return PageBody(
      children: [
        const PageHeading(
          'التنفيذ، لا النية',
          'تفاصيل التقرير',
          'طلب الإجراء لا يعني تنفيذه. نعرض نتيجة التنفيذ التي تحقق منها الجهاز، دون ادعاء إيقاف تطبيق آخر بالقوة.',
        ),
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                incident.appPackage,
                textDirection: TextDirection.ltr,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              Text(
                '${MaterialLocalizations.of(context).formatMediumDate(incident.occurredAt)} · ${MaterialLocalizations.of(context).formatTimeOfDay(TimeOfDay.fromDateTime(incident.occurredAt))}',
              ),
              const Text(
                'وقت يقدمه جهاز الطفل؛ ليس توقيتاً موثّقاً من الخادم.',
              ),
              const SizedBox(height: 16),
              StatusPill(
                executionName(incident.executed.status),
                ready: incident.executed.status == 'executed',
              ),
              const SizedBox(height: 12),
              Text('الإجراء المطلوب: ${stageName(incident.requestedStage)}'),
              Text('الإجراء الفعلي: ${stageName(incident.executed.stage)}'),
              Text('مراجعة التقرير: ${incident.revision}'),
            ],
          ),
        ),
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'الدليل الوصفي',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 12),
              Text(
                'المسار: ${incident.category == 'hentai_dominant' ? 'رسومي غالب؛ لا يسمح بالمرحلة الثالثة' : 'صريح'}',
              ),
              Text(
                'إطارات مترابطة: ${incident.frameCount} · المدة: ${incident.spanUs} ميكروثانية',
              ),
              const SizedBox(height: 12),
              Text(
                'الدرجة الصريحة P + H: ${(incident.scores.explicitScore * 100).toStringAsFixed(2)}٪',
              ),
              Text(
                'P: ${(incident.scores.porn * 100).toStringAsFixed(2)}٪ · H: ${(incident.scores.hentai * 100).toStringAsFixed(2)}٪',
                textDirection: TextDirection.ltr,
              ),
              Text(
                'Sexy: ${(incident.scores.sexy * 100).toStringAsFixed(2)}٪ · تشخيص فقط، لا يدخل في التدخل',
              ),
            ],
          ),
        ),
        const NoticeBox(
          'اختبارات السياسة لا تثبت دقة المصنّف في التمييز بين روبوت وشخص بالغ. دقة التصنيف غير متحقق منها دون مجموعة بيانات موسومة حقيقية.',
        ),
      ],
    );
  }
}
