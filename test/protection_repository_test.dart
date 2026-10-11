import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mentor_app/protection/repository.dart';
import 'support/fake_protection_repository.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.k230.mentor/protection');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late PlatformProtectionRepository repository;
  late List<MethodCall> calls;
  setUp(() {
    repository = PlatformProtectionRepository(supported: true);
    calls = [];
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  void respond(Object? Function(MethodCall call) handler) {
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return handler(call);
    });
  }

  test(
    'fixed channel reads strict native state, never supplies defaults',
    () async {
      respond((_) => stateData(age: 13));
      expect((await repository.getState()).profile?.label, '13-15');
      expect(calls.single.method, 'getState');
      expect(calls.single.arguments, isNull);
      respond((_) => null);
      await expectLater(
        repository.getState(),
        throwsA(isA<ProtectionFailure>()),
      );
    },
  );
  test('missing plugin and unsupported web stay unavailable', () async {
    await expectLater(
      repository.getState(),
      throwsA(
        isA<ProtectionFailure>().having(
          (e) => e.code,
          'code',
          'native_unavailable',
        ),
      ),
    );
    final web = PlatformProtectionRepository(supported: false);
    await expectLater(
      web.getState(),
      throwsA(
        isA<ProtectionFailure>().having(
          (e) => e.code,
          'code',
          'unsupported_device',
        ),
      ),
    );
    expect(await web.events.isEmpty, isTrue);
  });
  test('native exception text never reaches Arabic error UI', () async {
    respond(
      (_) => throw PlatformException(
        code: 'private-key-data',
        message: 'secret incident text',
      ),
    );
    try {
      await repository.getState();
      fail('expected failure');
    } on ProtectionFailure catch (error) {
      expect(errorMessage(error.code), isNot(contains('secret')));
      expect(errorMessage(error.code), isNot(contains('private-key')));
    }
  });
  test(
    'auth parses expiry but cannot turn false response into authority',
    () async {
      respond((_) => {'authenticated': false, 'expiresAtMs': null});
      expect(await repository.guardianAuthenticate(), isNull);
      respond((_) => {'authenticated': true, 'expiresAtMs': '1791043200000'});
      expect(
        (await repository.guardianAuthenticate())?.millisecondsSinceEpoch,
        1791043200000,
      );
      respond((_) => {'authenticated': true, 'expiresAtMs': true});
      await expectLater(
        repository.guardianAuthenticate(),
        throwsA(isA<ProtectionFailure>()),
      );
    },
  );
  test(
    'pairing and direct control method/argument names exactly match contract',
    () async {
      respond((_) => {'qrPayload': '{}', 'fingerprints': {}});
      await repository.createPairOffer();
      await repository.scanPairQr('offer');
      await repository.getPairQr('response');
      await repository.createControlChallenge('set_profile', null);
      await repository.requestGuardianUnlock(eventId);
      expect(calls.map((call) => call.method), [
        'createPairOffer',
        'scanPairQr',
        'getPairQr',
        'createControlChallenge',
        'requestGuardianUnlock',
      ]);
      expect(calls[1].arguments, {'step': 'offer'});
      expect(calls[2].arguments, {'step': 'response'});
      expect(calls[3].arguments, {'operation': 'set_profile', 'eventId': null});
      expect(calls[4].arguments, {'eventId': eventId});
    },
  );
  test(
    'profile change uses int age and validates typed pending result',
    () async {
      respond(
        (_) => {
          'status': 'pending',
          'commandId': commandId,
          'profile': stateData(age: 13)['profile'],
          'qrPayload': null,
        },
      );
      final result = await repository.setChildAge(13);
      expect(calls.single.arguments, {'age': 13});
      expect(result.status, 'pending');
      expect(result.profile.label, '13-15');
      expect(() => repository.setChildAge(16), throwsFormatException);
    },
  );
  test('inbox uses only verified metadata wrappers and pagination', () async {
    respond(
      (call) => call.method == 'getIncident'
          ? incidentData()
          : {
              'items': [incidentData()],
              'nextCursor': 'opaque-cursor',
            },
    );
    final result = await repository.listIncidents(null, 50);
    expect(result.items.single.eventId, eventId);
    expect(result.nextCursor, 'opaque-cursor');
    await repository.getIncident(eventId);
    expect(calls.first.arguments, {'cursor': null, 'limit': 50});
    expect(calls.last.arguments, {'eventId': eventId});
    respond(
      (_) => {
        'items': [incidentData()],
        'nextCursor': null,
        'plaintext': 'forbidden',
      },
    );
    await expectLater(
      repository.listIncidents(null, 50),
      throwsA(isA<ProtectionFailure>()),
    );
  });
  test(
    'navigation reports actual shield on HOME failure; no force-stop API',
    () async {
      respond(
        (_) => {
          'action': 'calm_shield',
          'stage': 2,
          'status': 'failed',
          'error': 'home_unverified',
        },
      );
      final result = await repository.navigateHome();
      expect(result.stage, 2);
      expect(result.error, 'home_unverified');
      expect(calls.single.method, 'navigateHome');
      respond(
        (_) => {
          'action': 'force_stop',
          'stage': 3,
          'status': 'executed',
          'error': null,
        },
      );
      await expectLater(
        repository.navigateHome(),
        throwsA(isA<ProtectionFailure>()),
      );
    },
  );
  test(
    'remaining controls keep fixed contract names and null arguments',
    () async {
      respond(
        (call) => call.method == 'syncInbox' ? {'scheduled': true} : null,
      );
      await repository.openAccessibilitySettings();
      expect(await repository.syncInbox(), isTrue);
      await repository.revokePair();
      expect(calls.map((call) => call.method), [
        'openAccessibilitySettings',
        'syncInbox',
        'revokePair',
      ]);
      expect(calls.every((call) => call.arguments == null), isTrue);
    },
  );
  test('event channel emits only sanitized state/inbox updates', () async {
    const events = MethodChannel('dev.k230.mentor/protection/events');
    messenger.setMockMethodCallHandler(events, (_) async => null);
    final emitted = <ProtectionEvent>[];
    final errors = <Object>[];
    final subscription = repository.events.listen(
      emitted.add,
      onError: errors.add,
    );
    await Future<void>.delayed(Duration.zero);
    Future<void> send(Object data) async {
      await messenger.handlePlatformMessage(
        events.name,
        const StandardMethodCodec().encodeSuccessEnvelope(data),
        (_) {},
      );
    }

    await send({
      'v': 2,
      'type': 'state_changed',
      'state': stateData(role: 'child'),
    });
    await send({'v': 2, 'type': 'inbox_changed'});
    await send({'v': 2, 'type': 'inbox_changed', 'app_package': 'forbidden'});
    await Future<void>.delayed(Duration.zero);
    expect(emitted, hasLength(2));
    expect(emitted.first.state?.role, 'child');
    expect(emitted.last.inboxChanged, isTrue);
    expect(errors, hasLength(1));
    await subscription.cancel();
    messenger.setMockMethodCallHandler(events, null);
  });
}
