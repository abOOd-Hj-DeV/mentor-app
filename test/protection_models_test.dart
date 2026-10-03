import 'package:flutter_test/flutter_test.dart';
import 'package:mentor_app/protection/models.dart';
import 'support/fake_protection_repository.dart';

void main() {
  group('age profiles', () {
    for (final age in [10, 11, 12, 13, 14, 15]) {
      test('age $age maps explicitly to expected thresholds', () {
        final profile = AgeProfile.fromNative(stateData(age: age)['profile']);
        expect(profile.label, age <= 12 ? '10-12' : '13-15');
        expect(profile.cover, age <= 12 ? .60 : .70);
        expect(profile.shield, age <= 12 ? .80 : .85);
        expect(profile.exit, age <= 12 ? .90 : .95);
      });
    }
    for (final age in [9, 16, 13.5, 13.0, null, '13', true]) {
      test(
        'rejects non-contract age $age',
        () => expect(() => AgeProfile.validateAge(age), throwsFormatException),
      );
    }
    test('rejects profile mismatch and leading-zero revisions', () {
      final profile = object(stateData(age: 13)['profile']);
      expect(
        () => AgeProfile.fromNative({...profile, 'profile': '10-12'}),
        throwsFormatException,
      );
      expect(
        () => AgeProfile.fromNative({...profile, 'policyRevision': '01'}),
        throwsFormatException,
      );
    });
  });
  group('finite scores', () {
    test(
      'Sexy remains diagnostic and score clamps tolerated softmax drift',
      () {
        final sexyOnly = Scores.fromNative({
          'porn': 0,
          'hentai': 0,
          'sexy': 1,
          'explicit_score': 0,
        });
        expect(sexyOnly.explicitScore, 0);
        final drift = Scores.fromNative({
          'porn': .6,
          'hentai': .4005,
          'sexy': 0,
          'explicit_score': 1,
        });
        expect(drift.explicitScore, 1);
      },
    );
    for (final value in [double.nan, double.infinity, -.1, 1.1, '0.6', true]) {
      test(
        'rejects invalid probability $value',
        () => expect(
          () => Scores.fromNative({
            'porn': value,
            'hentai': 0,
            'sexy': 0,
            'explicit_score': .6,
          }),
          throwsFormatException,
        ),
      );
    }
    test('rejects wrong explicit arithmetic, excess sum and media keys', () {
      expect(
        () => Scores.fromNative({
          'porn': .6,
          'hentai': .1,
          'sexy': .2,
          'explicit_score': .9,
        }),
        throwsFormatException,
      );
      expect(
        () => Scores.fromNative({
          'porn': .6,
          'hentai': .4,
          'sexy': .1,
          'explicit_score': 1,
        }),
        throwsFormatException,
      );
      expect(
        () => Scores.fromNative({
          'porn': .6,
          'hentai': .1,
          'sexy': .2,
          'explicit_score': .7,
          'image': 'forbidden',
        }),
        throwsFormatException,
      );
    });
  });
  test('native state denies child guardian auth and unknown/extra fields', () {
    expect(
      () => ProtectionState.fromNative(
        stateData(role: 'child', authenticated: true),
      ),
      throwsFormatException,
    );
    expect(
      () => ProtectionState.fromNative({...stateData(), 'incidents': []}),
      throwsFormatException,
    );
    expect(
      () => ProtectionState.fromNative({...stateData(), 'role': 'demo'}),
      throwsFormatException,
    );
  });
  test(
    'unknown analysis/offline/partial-ready never claims local readiness',
    () {
      expect(ProtectionState.fromNative(stateData()).localReady, isFalse);
      expect(
        ProtectionState.fromNative(stateData(analysis: 'ready')).localReady,
        isTrue,
      );
      expect(
        ProtectionState.fromNative(
          stateData(role: 'unconfigured', age: null, analysis: 'ready'),
        ).localReady,
        isFalse,
      );
    },
  );
  test('metadata incident preserves truthful unknown execution', () {
    final value = Incident.fromNative(
      incidentData(status: 'unknown', executedStage: 0),
    );
    expect(value.requestedStage, 1);
    expect(value.executed.stage, 0);
    expect(value.scores.explicitScore, closeTo(.67, 1e-12));
  });
  test(
    'metadata rejects media, inconsistent evidence and impossible Hentai HOME',
    () {
      expect(
        () => Incident.fromNative({...incidentData(), 'crop': []}),
        throwsFormatException,
      );
      final data = incidentData();
      expect(
        () => Incident.fromNative({
          ...data,
          'evidence': {...object(data['evidence']), 'span_us': '399999'},
        }),
        throwsFormatException,
      );
      expect(
        () => Incident.fromNative({
          ...data,
          'category': 'hentai_dominant',
          'stage': 3,
          'requested': {'stage': 3, 'action': 'home'},
          'evidence': {...object(data['evidence']), 'route': 'hentai_dominant'},
        }),
        throwsFormatException,
      );
    },
  );
  test('unknown/released results cannot pretend actual action', () {
    expect(
      () => ExecutionResult.fromNative({
        'action': 'home',
        'stage': 3,
        'status': 'unknown',
        'error': null,
      }),
      throwsFormatException,
    );
    expect(
      () => ExecutionResult.fromNative({
        'action': 'force_stop',
        'stage': 3,
        'status': 'executed',
        'error': null,
      }),
      throwsFormatException,
    );
  });
  test(
    'bounds on QR payload are UTF-8 bytes and fingerprints are complete',
    () {
      expect(
        () => PairQr.fromNative({'qrPayload': 'ع' * 2000, 'fingerprints': {}}),
        throwsFormatException,
      );
      expect(
        () => PairQr.fromNative({
          'qrPayload': '{}',
          'fingerprints': {'guardian_hpke': 'short'},
        }),
        throwsFormatException,
      );
    },
  );
}
