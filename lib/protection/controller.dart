import 'dart:async';

import 'package:flutter/foundation.dart';

import 'models.dart';
import 'repository.dart';

class ProtectionController extends ChangeNotifier {
  ProtectionController(this.repository, {DateTime Function()? now})
    : _now = now ?? DateTime.now;
  final ProtectionRepository repository;
  final DateTime Function() _now;
  ProtectionState? state;
  String? errorCode, notice;
  bool loading = true, busy = false, inboxLoaded = false;
  PairQr? qr;
  ProfileUpdate? pendingProfile;
  final Map<String, Incident> _incidents = {};
  String? nextCursor;
  DateTime? _authExpiry;
  StreamSubscription<ProtectionEvent>? _subscription;
  Timer? _authTimer;
  int _generation = 0;
  int _authenticationAttempt = 0;
  bool _disposed = false;
  bool _foreground = true;
  bool get guardianUnlocked =>
      _foreground &&
      state?.role == 'guardian' &&
      state?.guardianAuthenticated == true &&
      _authExpiry != null &&
      _now().isBefore(_authExpiry!);
  List<Incident> get incidents => guardianUnlocked
      ? (List<Incident>.of(_incidents.values)
          ..sort((a, b) => b.occurredAt.compareTo(a.occurredAt)))
      : const [];

  Future<void> start() async {
    _subscription = repository.events.listen(
      (event) {
        if (_disposed) return;
        if (event.state != null) {
          _accept(event.state!);
        } else if (event.inboxChanged) {
          notice = 'يوجد تحديث للحماية. افتح الصندوق بعد تحقق الوالد.';
        }
        notifyListeners();
      },
      onError: (_) {
        if (_disposed) return;
        errorCode = 'native_unavailable';
        state = null;
        lock();
      },
    );
    await refresh();
  }

  void _accept(ProtectionState value) {
    final previousRole = state?.role;
    final revoked =
        (state?.guardianAuthenticated == true &&
            !value.guardianAuthenticated) ||
        (previousRole == 'guardian' && value.role != 'guardian') ||
        (previousRole != 'child' && value.role == 'child');
    state = value;
    if (revoked) {
      _generation++;
      _authenticationAttempt++;
      _authExpiry = null;
      _authTimer?.cancel();
    }
    if (!guardianUnlocked) {
      _clearPrivate(
        clearQr: value.role != 'child' || previousRole != value.role,
      );
    }
  }

  void _clearPrivate({bool clearQr = true}) {
    _incidents.clear();
    inboxLoaded = false;
    nextCursor = null;
    pendingProfile = null;
    if (clearQr) qr = null;
  }

  void suspend() {
    _foreground = false;
    lock(preserveAuthenticationAttempt: true);
  }

  Future<void> resume() {
    _foreground = true;
    return refresh();
  }

  void lock({bool preserveAuthenticationAttempt = false}) {
    if (!preserveAuthenticationAttempt) _authenticationAttempt++;
    _generation++;
    _authExpiry = null;
    _authTimer?.cancel();
    _clearPrivate();
    notice = null;
    if (!_disposed) notifyListeners();
  }

  Future<T?> _run<T>(
    Future<T> Function() action, {
    int? authenticationAttempt,
  }) async {
    if (busy || _disposed) return null;
    final generation = _generation;
    busy = true;
    errorCode = null;
    notice = null;
    notifyListeners();
    try {
      final result = await action();
      if (!_disposed &&
          (generation == _generation ||
              authenticationAttempt != null &&
                  authenticationAttempt == _authenticationAttempt)) {
        return result;
      }
    } on ProtectionFailure catch (failure) {
      if (!_disposed && generation == _generation) errorCode = failure.code;
    } on FormatException catch (error) {
      if (!_disposed && generation == _generation) {
        errorCode = error.message == 'invalid_age'
            ? 'invalid_age'
            : 'invalid_native_response';
      }
    } catch (_) {
      if (!_disposed && generation == _generation) errorCode = 'unavailable';
    } finally {
      if (!_disposed) {
        busy = false;
        notifyListeners();
      }
    }
    return null;
  }

  Future<void> refresh() async {
    final result = await _run(repository.getState);
    if (_disposed) return;
    loading = false;
    if (result != null) {
      _accept(result);
    } else if (errorCode != null) {
      state = null;
      _clearPrivate();
    }
    notifyListeners();
  }

  void _requireGuardian() {
    if (!guardianUnlocked) throw const ProtectionFailure('guardian_required');
  }

  Future<void> authenticate() async {
    if (busy || _disposed) return;
    if (state?.role == 'child') {
      errorCode = 'guardian_required';
      notifyListeners();
      return;
    }
    final expiry = await _run(
      repository.guardianAuthenticate,
      authenticationAttempt: ++_authenticationAttempt,
    );
    if (expiry == null || _disposed) return;
    final duration = expiry.difference(_now());
    if (duration <= Duration.zero || duration > const Duration(seconds: 120)) {
      errorCode = 'invalid_native_response';
      notifyListeners();
      return;
    }
    _authExpiry = expiry;
    _authTimer?.cancel();
    _authTimer = Timer(duration, lock);
    await refresh();
  }

  Future<void> openSettings() async =>
      _run(repository.openAccessibilitySettings);
  Future<void> createOffer() async {
    final value = await _run(() {
      _requireGuardian();
      return repository.createPairOffer();
    });
    if (value != null && guardianUnlocked) {
      qr = value;
      notifyListeners();
    }
  }

  Future<void> scan(String step) async {
    final result = await _run(() async {
      if ({'response', 'challenge', 'receipt'}.contains(step)) {
        _requireGuardian();
      }
      await repository.scanPairQr(step);
      return true;
    });
    if (result == true) await refresh();
  }

  Future<void> showQr(String step) async {
    final value = await _run(() {
      if ({'confirmation', 'control'}.contains(step)) _requireGuardian();
      return repository.getPairQr(step);
    });
    if (value != null) {
      qr = value;
      notifyListeners();
    }
  }

  Future<void> updateAge(Object? age) async {
    final value = await _run(() {
      _requireGuardian();
      return repository.setChildAge(AgeProfile.validateAge(age));
    });
    if (value == null || !guardianUnlocked) return;
    pendingProfile = value.status == 'pending' ? value : null;
    if (value.qrPayload != null) qr = PairQr(value.qrPayload!, const {});
    final current = await _run(repository.getState);
    if (current != null) _accept(current);
    if (guardianUnlocked) {
      if (value.status == 'pending') {
        notice =
            'بانتظار إيصال الطفل الموقّع. لم نغيّر الملف المحلي أو ندّعِ تطبيقه.';
      } else if (current?.profile?.age == value.profile.age &&
          BigInt.parse(current!.profile!.revision) >=
              BigInt.parse(value.profile.revision)) {
        notice = 'وصل تأكيد تطبيق الملف من المكوّن الأصلي.';
      } else {
        errorCode = 'invalid_native_response';
      }
    }
    notifyListeners();
  }

  Future<void> loadInbox({bool more = false}) async {
    final value = await _run(() {
      _requireGuardian();
      return repository.listIncidents(more ? nextCursor : null, 50);
    });
    if (value == null || !guardianUnlocked) return;
    for (final incident in value.items) {
      final old = _incidents[incident.eventId];
      if (old == null || incident.revision > old.revision) {
        _incidents[incident.eventId] = incident;
      }
    }
    nextCursor = value.nextCursor;
    inboxLoaded = true;
    notifyListeners();
  }

  Future<Incident?> incident(String eventId) async {
    final value = await _run(() {
      _requireGuardian();
      return repository.getIncident(eventId);
    });
    if (!guardianUnlocked) return null;
    if (value != null && value.eventId != eventId) {
      errorCode = 'invalid_native_response';
      notifyListeners();
      return null;
    }
    if (value != null) {
      final old = _incidents[eventId];
      if (old == null || value.revision > old.revision) {
        _incidents[eventId] = value;
      }
    }
    return _incidents[eventId];
  }

  Future<void> sync() async {
    final scheduled = await _run(repository.syncInbox);
    if (scheduled != null) {
      notice = scheduled
          ? 'جُدولت المزامنة. هذا لا يؤكد وصول الإشعار أو التقارير.'
          : 'لم تُجدول المزامنة. راجع إعداد الخدمة.';
      notifyListeners();
    }
  }

  Future<void> requestHelp() async {
    final active = state?.activeProtection;
    if (active == null) return;
    final value = await _run(
      () => repository.requestGuardianUnlock(active.eventId),
    );
    if (value != null) {
      qr = value;
      notifyListeners();
    }
  }

  Future<void> challenge(String operation) async {
    final value = await _run(
      () => repository.createControlChallenge(operation, null),
    );
    if (value != null) {
      qr = value;
      notifyListeners();
    }
  }

  Future<void> home() async {
    final result = await _run(repository.navigateHome);
    if (result == null) return;
    final message = result.status == 'executed' && result.action == 'home'
        ? 'تم التحقق من الانتقال للشاشة الرئيسية.'
        : 'لم يُؤكد الانتقال للشاشة الرئيسية. لا يعني الطلب إزالة الحاجب.';
    await refresh();
    if (!_disposed) {
      notice = message;
      notifyListeners();
    }
  }

  Future<void> revoke() async {
    final result = await _run(() async {
      _requireGuardian();
      await repository.revokePair();
      return true;
    });
    if (result == true) await refresh();
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    _subscription?.cancel();
    _authTimer?.cancel();
    super.dispose();
  }
}
