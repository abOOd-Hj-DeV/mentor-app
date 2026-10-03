import 'dart:async';
import 'package:mentor_app/protection/models.dart';
import 'package:mentor_app/protection/repository.dart';

const eventId = '10000000-0000-4000-8000-000000000005';
const commandId = '20000000-0000-4000-8000-000000000005';

Map<String, Object?> stateData({
  String role = 'guardian',
  bool authenticated = false,
  int? age = 12,
  String cloud = 'offline',
  String analysis = 'unknown',
  bool active = false,
}) => {
  'role': role,
  'guardianAuthenticated': authenticated,
  'profile': age == null
      ? null
      : {
          'age': age,
          'profile': age <= 12 ? '10-12' : '13-15',
          'policyVersion': policyVersion,
          'policyRevision': '1',
        },
  'pairing': role == 'unconfigured' ? 'unpaired' : 'paired',
  'health': {
    'accessibility': true,
    'companion': 'connected',
    'analysis': analysis,
    'execution': 'ready',
    'encryption': 'ready',
    'cloud': cloud,
    'outboxCount': 2,
  },
  'activeProtection': active
      ? {
          'eventId': eventId,
          'stage': 2,
          'explanationKey': 'calm_younger',
          'canNavigateHome': true,
        }
      : null,
  'error': null,
};

Map<String, Object?> incidentData({
  String revision = '1',
  String status = 'executed',
  int executedStage = 1,
}) => {
  'v': 2,
  'type': 'incident',
  'event_id': eventId,
  'incident_revision': revision,
  'child_device_id': '20000000-0000-4000-8000-000000000002',
  'occurred_at_ms': '1791043200000',
  'time_source': 'device_wall_clock',
  'app_package': 'com.example.viewer',
  'category': 'explicit',
  'scores': {'porn': .65, 'hentai': .02, 'sexy': .10, 'explicit_score': .67},
  'evidence': {
    'route': 'explicit',
    'frame_count': 3,
    'span_us': '400000',
    'first_pts_us': '10000000',
    'last_pts_us': '10400000',
    'complete': true,
  },
  'stage': 1,
  'requested': {'action': 'cover_region', 'stage': 1},
  'executed': {
    'action': ['none', 'cover_region', 'calm_shield', 'home'][executedStage],
    'stage': executedStage,
    'status': status,
    'error': null,
  },
  'versions': {
    'contract': 'mentor-parental-v2.0',
    'policy': policyVersion,
    'policy_revision': '1',
    'app': '1.0.0+1',
    'model': 'nsfwjs-mobilenet-v2-onnx',
  },
};

class FakeProtectionRepository implements ProtectionRepository {
  FakeProtectionRepository({Map<String, Object?>? initial})
    : data = initial ?? stateData();
  Map<String, Object?> data;
  final stream = StreamController<ProtectionEvent>.broadcast(sync: true);
  final calls = <String>[];
  List<Incident> items = [];
  String? cursor;
  Completer<IncidentPage>? inboxCompleter;
  Completer<ProtectionState>? stateCompleter;
  Completer<DateTime?>? authenticationCompleter;
  Completer<PairQr>? qrCompleter;
  ProtectionFailure? failure;
  DateTime? authExpiry;
  bool authenticated = true;
  ProfileUpdate? update;
  ExecutionResult homeResult = const ExecutionResult(
    'calm_shield',
    2,
    'failed',
    'home_unverified',
  );
  static const qr = PairQr('{"test_only":"public pairing fixture"}', {
    'guardian_hpke':
        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  });
  void check(String name) {
    calls.add(name);
    if (failure != null) throw failure!;
  }

  void emit(Map<String, Object?> value) {
    data = value;
    stream.add(ProtectionEvent.state(ProtectionState.fromNative(value)));
  }

  Future<void> close() => stream.close();
  @override
  Stream<ProtectionEvent> get events => stream.stream;
  @override
  Future<ProtectionState> getState() async {
    check('getState');
    return stateCompleter?.future ?? ProtectionState.fromNative(data);
  }

  @override
  Future<void> openAccessibilitySettings() async {
    check('openAccessibilitySettings');
  }

  @override
  Future<DateTime?> guardianAuthenticate() async {
    check('guardianAuthenticate');
    if (!authenticated) return null;
    data = {...data, 'role': 'guardian', 'guardianAuthenticated': true};
    if (authenticationCompleter != null) return authenticationCompleter!.future;
    return authExpiry ?? DateTime.now().add(const Duration(seconds: 90));
  }

  @override
  Future<PairQr> createPairOffer() async {
    check('createPairOffer');
    return qr;
  }

  @override
  Future<void> scanPairQr(String step) async {
    check('scanPairQr:$step');
  }

  @override
  Future<PairQr> getPairQr(String step) async {
    check('getPairQr:$step');
    return qrCompleter?.future ?? qr;
  }

  @override
  Future<PairQr> createControlChallenge(
    String operation,
    String? eventId,
  ) async {
    check('createControlChallenge:$operation');
    return qr;
  }

  @override
  Future<ProfileUpdate> setChildAge(int age) async {
    check('setChildAge:$age');
    return update ??
        ProfileUpdate('pending', commandId, AgeProfile(age, '2'), null);
  }

  @override
  Future<IncidentPage> listIncidents(String? cursor, int limit) async {
    check('listIncidents');
    return inboxCompleter?.future ?? IncidentPage(items, this.cursor);
  }

  @override
  Future<Incident> getIncident(String id) async {
    check('getIncident');
    return items.first;
  }

  @override
  Future<bool> syncInbox() async {
    check('syncInbox');
    return true;
  }

  @override
  Future<PairQr> requestGuardianUnlock(String id) async {
    check('requestGuardianUnlock');
    return qr;
  }

  @override
  Future<ExecutionResult> navigateHome() async {
    check('navigateHome');
    return homeResult;
  }

  @override
  Future<void> revokePair() async {
    check('revokePair');
  }
}
