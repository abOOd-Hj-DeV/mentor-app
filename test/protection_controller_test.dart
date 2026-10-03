import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:mentor_app/protection/controller.dart';
import 'package:mentor_app/protection/models.dart';
import 'package:mentor_app/protection/repository.dart';
import 'support/fake_protection_repository.dart';

void main() {
  late FakeProtectionRepository repository;
  late ProtectionController controller;
  late DateTime now;
  setUp(() {
    now = DateTime(2026, 10, 3);
    repository = FakeProtectionRepository()
      ..authExpiry = now.add(const Duration(seconds: 90));
    controller = ProtectionController(repository, now: () => now);
  });
  tearDown(() async {
    controller.dispose();
    await repository.close();
  });

  test(
    'guardian needs actual native auth and an authoritative guardian state',
    () async {
      await controller.start();
      await controller.loadInbox();
      await controller.createOffer();
      await controller.updateAge(13);
      expect(repository.calls, isNot(contains('listIncidents')));
      expect(repository.calls, isNot(contains('createPairOffer')));
      expect(controller.errorCode, 'guardian_required');
      await controller.authenticate();
      expect(controller.guardianUnlocked, isTrue);
      await controller.createOffer();
      expect(controller.qr, isNotNull);
    },
  );
  test(
    'approved native bootstrap can establish guardian from unconfigured',
    () async {
      repository.data = stateData(role: 'unconfigured', age: null);
      await controller.start();
      expect(controller.state?.role, 'unconfigured');
      await controller.authenticate();
      expect(controller.state?.role, 'guardian');
      expect(controller.guardianUnlocked, isTrue);
    },
  );
  test('child cannot switch role by authenticating or edit age', () async {
    repository.data = stateData(role: 'child');
    await controller.start();
    await controller.authenticate();
    await controller.updateAge(13);
    expect(repository.calls, isNot(contains('guardianAuthenticate')));
    expect(repository.calls, isNot(contains('setChildAge:13')));
    expect(controller.state?.role, 'child');
  });
  test(
    'invalid ages do not reset the last valid profile or call native setter',
    () async {
      await controller.start();
      await controller.authenticate();
      for (final age in [9, 16, 13.1, null, '13']) {
        await controller.updateAge(age);
        expect(controller.errorCode, 'invalid_age');
        expect(controller.state?.profile?.age, 12);
      }
      expect(
        repository.calls.where((call) => call.startsWith('setChildAge')),
        isEmpty,
      );
    },
  );
  test(
    'pending age thirteen previews older policy without claiming installed',
    () async {
      await controller.start();
      await controller.authenticate();
      await controller.updateAge(13);
      expect(controller.pendingProfile?.profile.label, '13-15');
      expect(controller.state?.profile?.age, 12);
      expect(controller.notice, contains('بانتظار'));
    },
  );
  test('expired or oversized auth session cannot unlock', () async {
    await controller.start();
    repository.authExpiry = now.subtract(const Duration(seconds: 1));
    await controller.authenticate();
    expect(controller.guardianUnlocked, isFalse);
    repository.authExpiry = now.add(const Duration(seconds: 121));
    await controller.authenticate();
    expect(controller.guardianUnlocked, isFalse);
    expect(controller.errorCode, 'invalid_native_response');
  });
  test('cancelled native authentication grants nothing', () async {
    repository.authenticated = false;
    await controller.start();
    await controller.authenticate();
    expect(controller.guardianUnlocked, isFalse);
  });
  test(
    'device credential activity may background; actual auth unlocks only on return',
    () async {
      repository.data = stateData(role: 'unconfigured', age: null);
      repository.authenticationCompleter = Completer<DateTime?>();
      await controller.start();
      final pending = controller.authenticate();
      controller.suspend();
      repository.authenticationCompleter!.complete(repository.authExpiry);
      await pending;
      expect(controller.guardianUnlocked, isFalse);
      expect(controller.incidents, isEmpty);
      await controller.resume();
      expect(controller.guardianUnlocked, isTrue);
      expect(controller.state?.role, 'guardian');
    },
  );
  test(
    'explicit lock cancels pending native authentication even after resume',
    () async {
      repository.authenticationCompleter = Completer<DateTime?>();
      await controller.start();
      final pending = controller.authenticate();
      controller.suspend();
      controller.lock();
      await controller.resume();
      repository.authenticationCompleter!.complete(repository.authExpiry);
      await pending;
      expect(controller.guardianUnlocked, isFalse);
    },
  );
  test('native auth revocation discards late signed control QR', () async {
    await controller.start();
    await controller.authenticate();
    repository.qrCompleter = Completer<PairQr>();
    final pending = controller.showQr('control');
    repository.emit(stateData(authenticated: false));
    repository.qrCompleter!.complete(FakeProtectionRepository.qr);
    await pending;
    expect(controller.guardianUnlocked, isFalse);
    expect(controller.qr, isNull);
  });
  test(
    'monotonic revision merge survives refresh, duplicates and out-of-order pages',
    () async {
      await controller.start();
      await controller.authenticate();
      repository.items = [Incident.fromNative(incidentData(revision: '3'))];
      await controller.loadInbox();
      repository.items = [Incident.fromNative(incidentData(revision: '1'))];
      await controller.loadInbox();
      await controller.loadInbox(more: true);
      expect(controller.incidents, hasLength(1));
      expect(controller.incidents.single.revision, BigInt.from(3));
      await controller.incident(eventId);
      expect(controller.incidents.single.revision, BigInt.from(3));
    },
  );
  test(
    'background lock purges data and discards late sensitive responses',
    () async {
      await controller.start();
      await controller.authenticate();
      repository.inboxCompleter = Completer<IncidentPage>();
      final pending = controller.loadInbox();
      controller.lock();
      repository.inboxCompleter!.complete(
        IncidentPage([Incident.fromNative(incidentData())], null),
      );
      await pending;
      expect(controller.incidents, isEmpty);
      expect(controller.inboxLoaded, isFalse);
      expect(controller.guardianUnlocked, isFalse);
      expect(controller.qr, isNull);
    },
  );
  test(
    'auth deadline hides incidents and blocks new private requests',
    () async {
      await controller.start();
      await controller.authenticate();
      repository.items = [Incident.fromNative(incidentData())];
      await controller.loadInbox();
      now = now.add(const Duration(seconds: 90));
      expect(controller.guardianUnlocked, isFalse);
      expect(controller.incidents, isEmpty);
      await controller.loadInbox();
      expect(controller.errorCode, 'guardian_required');
    },
  );
  test('native auth revocation clears private state immediately', () async {
    await controller.start();
    await controller.authenticate();
    repository.items = [Incident.fromNative(incidentData())];
    await controller.loadInbox();
    repository.emit(stateData(authenticated: false));
    expect(controller.incidents, isEmpty);
    expect(controller.inboxLoaded, isFalse);
  });
  test(
    'child QR survives native heartbeat, failed HOME never releases shield',
    () async {
      repository.data = stateData(role: 'child', active: true);
      await controller.start();
      await controller.requestHelp();
      repository.emit(stateData(role: 'child', active: true));
      expect(controller.qr, isNotNull);
      await controller.home();
      expect(controller.state?.activeProtection?.stage, 2);
      expect(controller.notice, contains('لم يُؤكد'));
    },
  );
  test('platform/storage failures never become success', () async {
    await controller.start();
    await controller.authenticate();
    repository.failure = const ProtectionFailure('storage_failed');
    await controller.updateAge(13);
    expect(controller.errorCode, 'storage_failed');
    expect(controller.notice, isNull);
    expect(controller.state?.profile?.age, 12);
  });
  test(
    'applied receipt must agree with authoritative profile, never rewrites it locally',
    () async {
      await controller.start();
      await controller.authenticate();
      repository.update = const ProfileUpdate(
        'applied',
        commandId,
        AgeProfile(13, '2'),
        null,
      );
      await controller.updateAge(13);
      expect(controller.state?.profile?.age, 12);
      expect(controller.notice, isNull);
      expect(controller.errorCode, 'invalid_native_response');
      repository.data = {
        ...repository.data,
        'profile': {
          ...stateData(age: 13)['profile'] as Map<String, Object?>,
          'policyRevision': '2',
        },
      };
      await controller.updateAge(13);
      expect(controller.state?.profile?.age, 13);
      expect(controller.notice, contains('تأكيد تطبيق'));
    },
  );
}
