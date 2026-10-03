import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'models.dart';

class ProtectionFailure implements Exception {
  const ProtectionFailure(this.code);
  final String code;
}

class ProtectionEvent {
  const ProtectionEvent.state(this.state) : inboxChanged = false;
  const ProtectionEvent.inbox() : state = null, inboxChanged = true;
  final ProtectionState? state;
  final bool inboxChanged;
}

abstract class ProtectionRepository {
  Stream<ProtectionEvent> get events;
  Future<ProtectionState> getState();
  Future<void> openAccessibilitySettings();
  Future<DateTime?> guardianAuthenticate();
  Future<PairQr> createPairOffer();
  Future<void> scanPairQr(String step);
  Future<PairQr> getPairQr(String step);
  Future<PairQr> createControlChallenge(String operation, String? eventId);
  Future<ProfileUpdate> setChildAge(int age);
  Future<IncidentPage> listIncidents(String? cursor, int limit);
  Future<Incident> getIncident(String eventId);
  Future<bool> syncInbox();
  Future<PairQr> requestGuardianUnlock(String eventId);
  Future<ExecutionResult> navigateHome();
  Future<void> revokePair();
}

class PlatformProtectionRepository implements ProtectionRepository {
  PlatformProtectionRepository({
    MethodChannel? channel,
    EventChannel? eventChannel,
    bool? supported,
  }) : _channel = channel ?? const MethodChannel('dev.k230.mentor/protection'),
       _eventChannel =
           eventChannel ??
           const EventChannel('dev.k230.mentor/protection/events'),
       _supported =
           supported ??
           (!kIsWeb && defaultTargetPlatform == TargetPlatform.android);
  final MethodChannel _channel;
  final EventChannel _eventChannel;
  final bool _supported;

  Future<T> _call<T>(
    String method,
    Object? arguments,
    T Function(Object?) parse,
  ) async {
    if (!_supported) throw const ProtectionFailure('unsupported_device');
    try {
      final value = await _channel
          .invokeMethod<Object?>(method, arguments)
          .timeout(const Duration(seconds: 15));
      return parse(value);
    } on MissingPluginException {
      throw const ProtectionFailure('native_unavailable');
    } on PlatformException catch (error) {
      throw ProtectionFailure(error.code);
    } on TimeoutException {
      throw const ProtectionFailure('timeout');
    } on FormatException {
      throw const ProtectionFailure('invalid_native_response');
    }
  }

  @override
  Stream<ProtectionEvent> get events {
    if (!_supported) return const Stream.empty();
    return _eventChannel.receiveBroadcastStream().map((value) {
      final data = object(value);
      if (data['v'] != 2) {
        throw const ProtectionFailure('invalid_native_response');
      }
      if (data['type'] == 'state_changed') {
        exactKeys(data, {'v', 'type', 'state'});
        return ProtectionEvent.state(ProtectionState.fromNative(data['state']));
      }
      if (data['type'] == 'inbox_changed') {
        exactKeys(data, {'v', 'type'});
        return const ProtectionEvent.inbox();
      }
      throw const ProtectionFailure('invalid_native_response');
    });
  }

  @override
  Future<ProtectionState> getState() =>
      _call('getState', null, ProtectionState.fromNative);
  @override
  Future<void> openAccessibilitySettings() =>
      _call('openAccessibilitySettings', null, (_) {});
  @override
  Future<DateTime?> guardianAuthenticate() =>
      _call('guardianAuthenticate', null, (value) {
        final data = object(value);
        exactKeys(data, {'authenticated', 'expiresAtMs'});
        if (!boolField(data, 'authenticated')) return null;
        final expiry = decimalField(data, 'expiresAtMs');
        if (expiry > BigInt.from(8640000000000000)) {
          throw const FormatException('invalid_native_response');
        }
        return DateTime.fromMillisecondsSinceEpoch(expiry.toInt());
      });
  @override
  Future<PairQr> createPairOffer() =>
      _call('createPairOffer', null, PairQr.fromNative);
  @override
  Future<void> scanPairQr(String step) {
    if (!{
      'offer',
      'response',
      'confirmation',
      'challenge',
      'control',
      'receipt',
    }.contains(step)) {
      throw const ProtectionFailure('bounds');
    }
    return _call('scanPairQr', {'step': step}, (_) {});
  }

  @override
  Future<PairQr> getPairQr(String step) {
    if (!{
      'response',
      'confirmation',
      'challenge',
      'control',
      'receipt',
    }.contains(step)) {
      throw const ProtectionFailure('bounds');
    }
    return _call('getPairQr', {'step': step}, PairQr.fromNative);
  }

  @override
  Future<PairQr> createControlChallenge(String operation, String? eventId) =>
      _call('createControlChallenge', {
        'operation': operation,
        'eventId': eventId,
      }, PairQr.fromNative);
  @override
  Future<ProfileUpdate> setChildAge(int age) {
    AgeProfile.validateAge(age);
    return _call('setChildAge', {'age': age}, ProfileUpdate.fromNative);
  }

  @override
  Future<IncidentPage> listIncidents(String? cursor, int limit) =>
      _call('listIncidents', {'cursor': cursor, 'limit': limit}, (value) {
        final data = object(value);
        exactKeys(data, {'items', 'nextCursor'});
        final items = data['items'];
        if (items is! List || items.length > limit || limit < 1 || limit > 50) {
          throw const FormatException('invalid_native_response');
        }
        return IncidentPage(
          List.unmodifiable(items.map(Incident.fromNative)),
          data['nextCursor'] == null
              ? null
              : textField(data, 'nextCursor', max: 512),
        );
      });
  @override
  Future<Incident> getIncident(String eventId) =>
      _call('getIncident', {'eventId': eventId}, Incident.fromNative);
  @override
  Future<bool> syncInbox() => _call('syncInbox', null, (value) {
    final data = object(value);
    exactKeys(data, {'scheduled'});
    return boolField(data, 'scheduled');
  });
  @override
  Future<PairQr> requestGuardianUnlock(String eventId) =>
      _call('requestGuardianUnlock', {'eventId': eventId}, PairQr.fromNative);
  @override
  Future<ExecutionResult> navigateHome() =>
      _call('navigateHome', null, ExecutionResult.fromNative);
  @override
  Future<void> revokePair() => _call('revokePair', null, (_) {});
}

String errorMessage(String? code) => switch (code) {
  'unsupported_device' =>
    'الحماية الأصلية متاحة على Android فقط. هذه الواجهة للقراءة ولا توفر حماية على الويب.',
  'native_unavailable' =>
    'مكوّن الحماية الأصلي غير متاح في هذا الإصدار. لا يمكن تأكيد الحماية أو تنفيذ إعدادات الوالد.',
  'invalid_age' =>
    'أدخل عمراً صحيحاً من 10 إلى 15 عاماً. لن يتغير آخر ملف صالح.',
  'guardian_required' || 'locked' || 'authentication_required' =>
    'يلزم تحقق الوالد على جهازه للوصول إلى هذه المعلومات والإعدادات.',
  'key_lost' =>
    'فُقد مفتاح الجهاز. يلزم اقتران مباشر جديد؛ تسجيل الدخول لا يستعيد التقارير القديمة.',
  'timeout' => 'لم يصل تأكيد التنفيذ بعد. تحقق من الحالة قبل إعادة المحاولة.',
  'invalid_native_response' =>
    'تعذر التحقق من حالة المكوّن الأصلي. لا نفترض نجاح العملية.',
  'cloud_unconfigured' || 'unconfigured' =>
    'الخدمة السحابية غير مهيأة. يلزم إعداد Firebase حقيقي؛ لا يوجد وضع تجريبي غير آمن.',
  'offline' || 'unavailable' =>
    'الاتصال غير متاح حالياً. الحماية المحلية المؤكدة مستقلة عن الإنترنت.',
  'home_unverified' =>
    'لم نتمكن من تأكيد الوصول للشاشة الرئيسية. يبقى الحاجب عند الحاجة.',
  _ =>
    'تعذر إتمام العملية بأمان. راجع الأذونات والاتصال أو اطلب مساعدة الوالد.',
};
