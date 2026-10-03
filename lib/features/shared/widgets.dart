import 'package:flutter/material.dart';
import '../../app/theme.dart';

class PageBody extends StatelessWidget {
  const PageBody({super.key, required this.children});
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.topCenter,
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 920),
      child: ListView.separated(
        padding: const EdgeInsets.all(24),
        itemCount: children.length,
        separatorBuilder: (_, _) => const SizedBox(height: 20),
        itemBuilder: (_, index) => children[index],
      ),
    ),
  );
}

class SectionCard extends StatelessWidget {
  const SectionCard({super.key, required this.child, this.color});
  final Widget child;
  final Color? color;
  @override
  Widget build(BuildContext context) => Card(
    color: color,
    child: Padding(padding: const EdgeInsets.all(24), child: child),
  );
}

class PageHeading extends StatelessWidget {
  const PageHeading(this.eyebrow, this.title, this.description, {super.key});
  final String eyebrow, title, description;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        eyebrow,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(color: teal),
      ),
      const SizedBox(height: 8),
      Text(title, style: Theme.of(context).textTheme.headlineMedium),
      const SizedBox(height: 8),
      Text(description, style: Theme.of(context).textTheme.bodyLarge),
    ],
  );
}

class StatusPill extends StatelessWidget {
  const StatusPill(
    this.text, {
    super.key,
    this.ready = false,
    this.textDirection,
  });
  final String text;
  final bool ready;
  final TextDirection? textDirection;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
    decoration: BoxDecoration(
      color: ready ? const Color(0xffe4f1eb) : const Color(0xffffefda),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      text,
      textDirection: textDirection,
      style: TextStyle(
        color: ready ? teal : caution,
        fontWeight: FontWeight.w700,
      ),
    ),
  );
}

class NoticeBox extends StatelessWidget {
  const NoticeBox(this.message, {super.key, this.error = false});
  final String message;
  final bool error;
  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: SectionCard(
      color: error ? const Color(0xffffefda) : const Color(0xffe9f0ee),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            error ? Icons.info_outline_rounded : Icons.lock_outline_rounded,
            color: error ? caution : teal,
          ),
          const SizedBox(width: 12),
          Expanded(child: Text(message)),
        ],
      ),
    ),
  );
}

class SafetyMark extends StatelessWidget {
  const SafetyMark({super.key, this.size = 88});
  final double size;
  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: const Color(0xffe0eee7),
        borderRadius: BorderRadius.circular(size * .32),
      ),
      child: Icon(Icons.spa_outlined, size: size * .55, color: teal),
    ),
  );
}

String stageName(int stage) => switch (stage) {
  1 => 'تغطية الجزء المكتشف',
  2 => 'حاجب هادئ',
  3 => 'انتقال للشاشة الرئيسية',
  _ => 'لا إجراء مؤكد',
};
String executionName(String status) => switch (status) {
  'executed' => 'نُفّذ وتأكد',
  'failed' => 'لم يكتمل التنفيذ',
  'rejected' => 'رُفض الطلب',
  'unknown' => 'التنفيذ غير مؤكد',
  'released' => 'رُفع الحاجب بتحقق',
  _ => 'غير معروف',
};
