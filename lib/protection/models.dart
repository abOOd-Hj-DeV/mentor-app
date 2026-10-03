import 'dart:math' as math;
import 'dart:convert';

const policyVersion = 'age-10-15-v1';

Map<String, Object?> object(Object? value) {
  if (value is! Map || value.keys.any((key) => key is! String)) {
    throw const FormatException('invalid_native_response');
  }
  return Map<String, Object?>.from(value);
}

String textField(Map<String, Object?> data, String key, {int max = 255}) {
  final value = data[key];
  if (value is! String || value.isEmpty || value.length > max) {
    throw const FormatException('invalid_native_response');
  }
  return value;
}

String enumField(Map<String, Object?> data, String key, Set<String> values) {
  final value = textField(data, key);
  if (!values.contains(value)) {
    throw const FormatException('invalid_native_response');
  }
  return value;
}

int integerField(Map<String, Object?> data, String key, int min, int max) {
  final value = data[key];
  if (value is! int || value < min || value > max) {
    throw const FormatException('invalid_native_response');
  }
  return value;
}

bool boolField(Map<String, Object?> data, String key) {
  final value = data[key];
  if (value is! bool) throw const FormatException('invalid_native_response');
  return value;
}

BigInt decimalField(Map<String, Object?> data, String key, {int min = 0}) {
  final value = textField(data, key, max: 19);
  if (!RegExp(r'^(0|[1-9][0-9]{0,18})$').hasMatch(value)) {
    throw const FormatException('invalid_native_response');
  }
  final result = BigInt.parse(value);
  if (result < BigInt.from(min) ||
      result > BigInt.parse('9223372036854775807')) {
    throw const FormatException('invalid_native_response');
  }
  return result;
}

String uuidField(Map<String, Object?> data, String key) {
  final value = textField(data, key, max: 36);
  if (!RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
  ).hasMatch(value)) {
    throw const FormatException('invalid_native_response');
  }
  return value;
}

void exactKeys(Map<String, Object?> data, Set<String> keys) {
  if (data.length != keys.length || !keys.containsAll(data.keys)) {
    throw const FormatException('invalid_native_response');
  }
}

class AgeProfile {
  const AgeProfile(this.age, this.revision);
  final int age;
  final String revision;
  String get label => age <= 12 ? '10-12' : '13-15';
  double get cover => age <= 12 ? .60 : .70;
  double get shield => age <= 12 ? .80 : .85;
  double get exit => age <= 12 ? .90 : .95;

  static int validateAge(Object? value) {
    if (value is! int || value < 10 || value > 15) {
      throw const FormatException('invalid_age');
    }
    return value;
  }

  factory AgeProfile.fromNative(Object? value) {
    final data = object(value);
    exactKeys(data, {'age', 'profile', 'policyVersion', 'policyRevision'});
    final age = validateAge(data['age']);
    final revision = decimalField(data, 'policyRevision', min: 1).toString();
    final result = AgeProfile(age, revision);
    if (data['profile'] != result.label ||
        data['policyVersion'] != policyVersion) {
      throw const FormatException('invalid_native_response');
    }
    return result;
  }
}

class ProtectionHealth {
  const ProtectionHealth({
    required this.accessibility,
    required this.companion,
    required this.analysis,
    required this.execution,
    required this.encryption,
    required this.cloud,
    required this.outboxCount,
  });
  final bool accessibility;
  final String companion, analysis, execution, encryption, cloud;
  final int outboxCount;
  factory ProtectionHealth.fromNative(Object? value) {
    final data = object(value);
    exactKeys(data, {
      'accessibility',
      'companion',
      'analysis',
      'execution',
      'encryption',
      'cloud',
      'outboxCount',
    });
    return ProtectionHealth(
      accessibility: boolField(data, 'accessibility'),
      companion: enumField(data, 'companion', {
        'connected',
        'disconnected',
        'legacy',
      }),
      analysis: enumField(data, 'analysis', {
        'ready',
        'unknown',
        'failed',
        'unconfigured',
      }),
      execution: enumField(data, 'execution', {'ready', 'degraded', 'locked'}),
      encryption: enumField(data, 'encryption', {'ready', 'locked', 'failed'}),
      cloud: enumField(data, 'cloud', {
        'online',
        'offline',
        'unconfigured',
        'auth_error',
      }),
      outboxCount: integerField(data, 'outboxCount', 0, 10000),
    );
  }
}

class ActiveProtection {
  const ActiveProtection(
    this.eventId,
    this.stage,
    this.explanationKey,
    this.canNavigateHome,
    this.actionRevision,
    this.targetScreenToken,
  );
  final String eventId, explanationKey, actionRevision, targetScreenToken;
  final int stage;
  final bool canNavigateHome;
  factory ActiveProtection.fromNative(Object? value) {
    final data = object(value);
    exactKeys(data, {
      'eventId',
      'stage',
      'explanationKey',
      'canNavigateHome',
      'actionRevision',
      'targetScreenToken',
    });
    return ActiveProtection(
      uuidField(data, 'eventId'),
      integerField(data, 'stage', 1, 3),
      enumField(data, 'explanationKey', {'calm_younger', 'calm_older'}),
      boolField(data, 'canNavigateHome'),
      decimalField(data, 'actionRevision', min: 1).toString(),
      uuidField(data, 'targetScreenToken'),
    );
  }
}

class ProtectionState {
  const ProtectionState({
    required this.role,
    required this.guardianAuthenticated,
    required this.profile,
    required this.pairing,
    required this.health,
    required this.activeProtection,
    required this.error,
  });
  final String role, pairing;
  final bool guardianAuthenticated;
  final AgeProfile? profile;
  final ProtectionHealth health;
  final ActiveProtection? activeProtection;
  final String? error;
  bool get localReady =>
      profile != null &&
      pairing == 'paired' &&
      health.accessibility &&
      health.companion == 'connected' &&
      health.analysis == 'ready' &&
      health.execution == 'ready';

  factory ProtectionState.fromNative(Object? value) {
    final data = object(value);
    exactKeys(data, {
      'role',
      'guardianAuthenticated',
      'profile',
      'pairing',
      'health',
      'activeProtection',
      'error',
    });
    final role = enumField(data, 'role', {'unconfigured', 'guardian', 'child'});
    final authenticated = boolField(data, 'guardianAuthenticated');
    if (authenticated && role != 'guardian') {
      throw const FormatException('invalid_native_response');
    }
    return ProtectionState(
      role: role,
      guardianAuthenticated: authenticated,
      profile: data['profile'] == null
          ? null
          : AgeProfile.fromNative(data['profile']),
      pairing: enumField(data, 'pairing', {
        'unpaired',
        'pending',
        'paired',
        'revoked',
        'key_lost',
      }),
      health: ProtectionHealth.fromNative(data['health']),
      activeProtection: data['activeProtection'] == null
          ? null
          : ActiveProtection.fromNative(data['activeProtection']),
      error: data['error'] == null ? null : textField(data, 'error', max: 64),
    );
  }
}

class Scores {
  const Scores(this.porn, this.hentai, this.sexy);
  final double porn, hentai, sexy;
  double get explicitScore => math.min(1, math.max(0, porn + hentai));
  factory Scores.fromNative(Object? value) {
    final data = object(value);
    exactKeys(data, {'porn', 'hentai', 'sexy', 'explicit_score'});
    double probability(String key) {
      final value = data[key];
      if (value is! num || !value.isFinite || value < 0 || value > 1) {
        throw const FormatException('invalid_native_response');
      }
      return value.toDouble();
    }

    final result = Scores(
      probability('porn'),
      probability('hentai'),
      probability('sexy'),
    );
    if (result.porn + result.hentai + result.sexy > 1.001 ||
        (probability('explicit_score') - result.explicitScore).abs() > 1e-6) {
      throw const FormatException('invalid_native_response');
    }
    return result;
  }
}

class ExecutionResult {
  const ExecutionResult(this.action, this.stage, this.status, this.error);
  final String action, status;
  final int stage;
  final String? error;
  factory ExecutionResult.fromNative(Object? value) {
    final data = object(value);
    exactKeys(data, {'action', 'stage', 'status', 'error'});
    final action = enumField(data, 'action', {
      'none',
      'cover_region',
      'calm_shield',
      'home',
    });
    final stage = integerField(data, 'stage', 0, 3);
    if (['none', 'cover_region', 'calm_shield', 'home'][stage] != action) {
      throw const FormatException('invalid_native_response');
    }
    final status = enumField(data, 'status', {
      'executed',
      'failed',
      'rejected',
      'unknown',
      'released',
    });
    if ((status == 'unknown' || status == 'released') &&
        (stage != 0 || data['error'] != null)) {
      throw const FormatException('invalid_native_response');
    }
    return ExecutionResult(
      action,
      stage,
      status,
      data['error'] == null ? null : textField(data, 'error', max: 64),
    );
  }
}

class Incident {
  const Incident({
    required this.eventId,
    required this.revision,
    required this.occurredAt,
    required this.appPackage,
    required this.category,
    required this.scores,
    required this.frameCount,
    required this.spanUs,
    required this.requestedStage,
    required this.executed,
  });
  final String eventId, appPackage, category;
  final BigInt revision, spanUs;
  final DateTime occurredAt;
  final Scores scores;
  final int frameCount, requestedStage;
  final ExecutionResult executed;

  // Native returns verified metadata only; this parser never decrypts envelopes.
  factory Incident.fromNative(Object? value) {
    final data = object(value);
    exactKeys(data, {
      'v',
      'type',
      'event_id',
      'incident_revision',
      'child_device_id',
      'occurred_at_ms',
      'time_source',
      'app_package',
      'category',
      'scores',
      'evidence',
      'stage',
      'requested',
      'executed',
      'versions',
    });
    if (data['v'] != 2 ||
        data['type'] != 'incident' ||
        data['time_source'] != 'device_wall_clock') {
      throw const FormatException('invalid_native_response');
    }
    uuidField(data, 'child_device_id');
    final package = textField(data, 'app_package');
    if (!RegExp(r'^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$').hasMatch(package)) {
      throw const FormatException('invalid_native_response');
    }
    final time = decimalField(data, 'occurred_at_ms');
    if (time > BigInt.from(8640000000000000)) {
      throw const FormatException('invalid_native_response');
    }
    final category = enumField(data, 'category', {
      'explicit',
      'hentai_dominant',
    });
    final evidence = object(data['evidence']);
    exactKeys(evidence, {
      'route',
      'frame_count',
      'span_us',
      'first_pts_us',
      'last_pts_us',
      'complete',
    });
    if (evidence['route'] != category || evidence['complete'] != true) {
      throw const FormatException('invalid_native_response');
    }
    final first = decimalField(evidence, 'first_pts_us');
    final last = decimalField(evidence, 'last_pts_us');
    final span = decimalField(evidence, 'span_us');
    if (last < first || last - first != span) {
      throw const FormatException('invalid_native_response');
    }
    final requested = object(data['requested']);
    exactKeys(requested, {'action', 'stage'});
    final stage = integerField(data, 'stage', 1, 3);
    final count = integerField(evidence, 'frame_count', 3, 32);
    final longChain = category == 'hentai_dominant' || stage == 3;
    if (count < (longChain ? 5 : 3) ||
        span < BigInt.from(longChain ? 1000000 : 400000)) {
      throw const FormatException('invalid_native_response');
    }
    if (requested['stage'] != stage ||
        requested['action'] !=
            ['none', 'cover_region', 'calm_shield', 'home'][stage] ||
        (category == 'hentai_dominant' && stage == 3)) {
      throw const FormatException('invalid_native_response');
    }
    final versions = object(data['versions']);
    exactKeys(versions, {
      'contract',
      'policy',
      'policy_revision',
      'app',
      'model',
    });
    if (versions['contract'] != 'mentor-parental-v2.0' ||
        versions['policy'] != policyVersion) {
      throw const FormatException('invalid_native_response');
    }
    decimalField(versions, 'policy_revision', min: 1);
    textField(versions, 'app', max: 32);
    textField(versions, 'model', max: 64);
    return Incident(
      eventId: uuidField(data, 'event_id'),
      revision: decimalField(data, 'incident_revision', min: 1),
      occurredAt: DateTime.fromMillisecondsSinceEpoch(time.toInt()),
      appPackage: package,
      category: category,
      scores: Scores.fromNative(data['scores']),
      frameCount: count,
      spanUs: span,
      requestedStage: stage,
      executed: ExecutionResult.fromNative(data['executed']),
    );
  }
}

class IncidentPage {
  const IncidentPage(this.items, this.nextCursor);
  final List<Incident> items;
  final String? nextCursor;
}

class PairQr {
  const PairQr(this.payload, this.fingerprints);
  final String payload;
  final Map<String, String> fingerprints;
  factory PairQr.fromNative(Object? value) {
    final data = object(value);
    exactKeys(data, {'qrPayload', 'fingerprints'});
    final fingerprints = object(data['fingerprints']);
    if (fingerprints.length > 8 ||
        fingerprints.values.any(
          (v) => v is! String || !RegExp(r'^[0-9a-f]{64}$').hasMatch(v),
        )) {
      throw const FormatException('invalid_native_response');
    }
    final payload = textField(data, 'qrPayload', max: 2953);
    if (utf8.encode(payload).length > 2953) {
      throw const FormatException('invalid_native_response');
    }
    return PairQr(
      payload,
      Map<String, String>.unmodifiable(fingerprints.cast<String, String>()),
    );
  }
}

class ProfileUpdate {
  const ProfileUpdate(
    this.status,
    this.commandId,
    this.profile,
    this.qrPayload,
  );
  final String status, commandId;
  final AgeProfile profile;
  final String? qrPayload;
  factory ProfileUpdate.fromNative(Object? value) {
    final data = object(value);
    exactKeys(data, {'status', 'commandId', 'profile', 'qrPayload'});
    return ProfileUpdate(
      enumField(data, 'status', {'pending', 'applied'}),
      uuidField(data, 'commandId'),
      AgeProfile.fromNative(data['profile']),
      data['qrPayload'] == null
          ? null
          : textField(data, 'qrPayload', max: 2953),
    );
  }
}
