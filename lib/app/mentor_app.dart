import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'package:flutter_localizations/flutter_localizations.dart';

import '../features/child/child_page.dart';
import '../features/diagnostics/layout_page.dart';
import '../features/guardian/guardian_page.dart';
import '../features/shared/mvp_pages.dart';
import '../features/incidents/incidents_page.dart';
import '../features/pairing/pairing_page.dart';
import '../features/shared/widgets.dart';
import '../protection/controller.dart';
import '../protection/models.dart';
import '../protection/repository.dart';
import 'router.dart';
import 'theme.dart';

class MentorApp extends StatelessWidget {
  const MentorApp({super.key, this.repository});
  final ProtectionRepository? repository;
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'مُرشد',
    theme: mentorTheme(),
    locale: const Locale('ar'),
    supportedLocales: const [Locale('ar')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: MentorShell(repository: repository),
  );
}

class MentorShell extends StatefulWidget {
  const MentorShell({super.key, this.repository});
  final ProtectionRepository? repository;
  @override
  State<MentorShell> createState() => _MentorShellState();
}

class _MentorShellState extends State<MentorShell> with WidgetsBindingObserver {
  late final ProtectionController _controller;
  MentorView _view = MentorView.home;
  final String? _choice = null;
  Incident? _incident;
  static const _mvpChannel = MethodChannel('dev.k230.mentor/mvp');
  Map<String, dynamic> _mvpSummary = {};
  String? _mvpRole;
  bool _mvpLoaded = false;
  Timer? _mvpRefresh;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _controller = ProtectionController(
      widget.repository ?? PlatformProtectionRepository(),
    );
    _controller.addListener(_changed);
    _controller.start();
    _refreshMvp();
    _mvpRefresh = Timer.periodic(
      const Duration(seconds: 10),
      (_) => _refreshMvp(),
    );
  }

  Future<void> _refreshMvp() async {
    try {
      final value = await _mvpChannel.invokeMapMethod<String, dynamic>(
        'summary',
      );
      if (mounted) {
        setState(() {
          _mvpSummary = value ?? {};
          _mvpRole = value?['role'] as String?;
          _mvpLoaded = true;
        });
      }
    } on MissingPluginException {
      if (mounted) setState(() => _mvpLoaded = true);
    } on PlatformException {
      // The last received report stays visible during a transient native error.
    }
  }

  Future<void> _selectRole(String role) async {
    try {
      await _mvpChannel.invokeMethod<void>('setRole', {'role': role});
      await _refreshMvp();
    } on PlatformException {
      if (mounted) setState(() => _mvpSummary['roleError'] = true);
    } on MissingPluginException {
      if (mounted) setState(() => _mvpSummary['roleError'] = true);
    }
  }

  void _changed() {
    if (!_controller.guardianUnlocked) _incident = null;
    if (mounted) setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) {
      _controller.suspend();
    } else {
      _controller.resume();
    }
  }

  @override
  void dispose() {
    _mvpRefresh?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _controller.removeListener(_changed);
    _controller.dispose();
    super.dispose();
  }

  void _go(MentorView view) => setState(() {
    _view = view;
    _incident = null;
  });
  void _diagnostics() => Navigator.of(
    context,
  ).push(MaterialPageRoute<void>(builder: (_) => const LayoutPage()));
  Future<void> _openIncident(String id) async {
    final value = await _controller.incident(id);
    if (mounted && value != null && _controller.guardianUnlocked) {
      setState(() {
        _incident = value;
        _view = MentorView.incident;
      });
    }
  }

  Widget _page() {
    final state = _controller.state;
    if (_mvpRole == 'child') {
      return state?.activeProtection != null
          ? ChildPage(controller: _controller)
          : MvpChildPage(
              controller: _controller,
              accessibility: _mvpSummary['accessibility'] == true,
            );
    }
    if (_mvpRole == 'guardian') {
      return MvpGuardianPage(summary: _mvpSummary, onRefresh: _refreshMvp);
    }
    if (_mvpLoaded) {
      return MvpStartPage(
        onRole: _selectRole,
        error: _mvpSummary['roleError'] == true,
      );
    }
    if (_controller.loading) {
      return const Center(
        child: CircularProgressIndicator(
          semanticsLabel: 'جارٍ قراءة الحالة الأصلية',
        ),
      );
    }
    if (_view == MentorView.pairing) {
      return PairingPage(controller: _controller, choice: _choice);
    }
    if (state?.role == 'child') {
      return state?.activeProtection != null
          ? ChildPage(controller: _controller)
          : MvpChildPage(controller: _controller);
    }
    if (state?.role == 'guardian') {
      return switch (_view) {
        MentorView.inbox => IncidentsPage(
          controller: _controller,
          onOpen: _openIncident,
        ),
        MentorView.incident =>
          _incident == null
              ? GuardianGate(controller: _controller)
              : IncidentDetails(controller: _controller, incident: _incident!),
        _ => const Center(child: CircularProgressIndicator()),
      };
    }
    return const Center(child: CircularProgressIndicator());
  }

  @override
  Widget build(BuildContext context) {
    final guardian = !_mvpLoaded && _controller.state?.role == 'guardian';
    final selected = switch (_view) {
      MentorView.inbox || MentorView.incident => 1,
      _ => 0,
    };
    final wide = MediaQuery.sizeOf(context).width >= 800;
    void select(int index) => _go([MentorView.home, MentorView.inbox][index]);
    final content = Column(
      children: [
        if (_controller.busy)
          const LinearProgressIndicator(
            semanticsLabel: 'جارٍ انتظار المكوّن الأصلي',
          ),
        if (!_mvpLoaded &&
            (_controller.errorCode != null ||
                _controller.state?.error != null ||
                _controller.notice != null))
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 180),
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 12, 24, 0),
              child: NoticeBox(
                _controller.errorCode != null ||
                        _controller.state?.error != null
                    ? errorMessage(
                        _controller.errorCode ?? _controller.state?.error,
                      )
                    : _controller.notice!,
                error:
                    _controller.errorCode != null ||
                    _controller.state?.error != null,
              ),
            ),
          ),
        Expanded(child: _page()),
      ],
    );
    return Scaffold(
      appBar: AppBar(
        title: const Text('مُرشد'),
        leading: _view == MentorView.pairing || _view == MentorView.incident
            ? IconButton(
                tooltip: 'رجوع',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => _go(
                  _view == MentorView.incident
                      ? MentorView.inbox
                      : MentorView.home,
                ),
              )
            : null,
        actions: [
          if (guardian)
            IconButton(
              tooltip: 'قفل مساحة الوالد',
              onPressed: _controller.lock,
              icon: const Icon(Icons.lock_outline),
            ),
          IconButton(
            tooltip: 'تحديث الحالة',
            onPressed: _refreshMvp,
            icon: const Icon(Icons.refresh),
          ),
          if (!_mvpLoaded)
            PopupMenuButton<String>(
              tooltip: 'مساعدة وتشخيص',
              onSelected: (_) => _diagnostics(),
              itemBuilder: (_) => const [
                PopupMenuItem(
                  value: 'diagnostics',
                  child: Text('تشخيص التخطيط المحلي المتقدم'),
                ),
              ],
            ),
        ],
      ),
      body: SafeArea(
        child: Row(
          children: [
            if (guardian && wide)
              NavigationRail(
                selectedIndex: selected,
                onDestinationSelected: select,
                labelType: NavigationRailLabelType.all,
                destinations: const [
                  NavigationRailDestination(
                    icon: Icon(Icons.shield_outlined),
                    label: Text('الحالة'),
                  ),
                  NavigationRailDestination(
                    icon: Icon(Icons.inbox_outlined),
                    label: Text('الصندوق'),
                  ),
                ],
              ),
            Expanded(child: content),
          ],
        ),
      ),
      bottomNavigationBar: guardian && !wide
          ? NavigationBar(
              selectedIndex: selected,
              onDestinationSelected: select,
              destinations: const [
                NavigationDestination(
                  icon: Icon(Icons.shield_outlined),
                  label: 'الحالة',
                ),
                NavigationDestination(
                  icon: Icon(Icons.inbox_outlined),
                  label: 'الصندوق',
                ),
              ],
            )
          : null,
    );
  }
}
