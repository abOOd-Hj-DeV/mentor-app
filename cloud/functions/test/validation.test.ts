import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {authorizeEnvelope, PairRecord, recipient} from '../src/authorization';
import {registration} from '../src/pairs';
import {base64, canonical, decimal, envelope, peer, strictJson} from '../src/validation';
import {genericMessage} from '../src/notifications';
import {fixture} from './helpers';

test('strict raw JSON rejects duplicate/escaped duplicate, malformed UTF8, comments, trailing roots, nesting, number lexemes and size', () => {
  for (const value of ['{"v":2,"v":2}', '{"v":2,"\\u0076":2}', '{"v":2}{}', '{"v":2,}', '{/*x*/"v":2}',
    '{"v":2.0}', '{"v":2e0}', '{"v":1e999}', '[]', '{"a":' + '['.repeat(12) + '0' + ']'.repeat(12) + '}'])
    assert.throws(() => strictJson(Buffer.from(value)));
  assert.throws(() => strictJson(Buffer.from([123, 34, 0xc0, 34, 58, 48, 125])));
  assert.throws(() => strictJson(Buffer.alloc(24577, 32)));
  assert.throws(() => decimal('9223372036854775808'));
  assert.throws(() => base64('Zh', 1, 32)); assert.throws(() => base64('Zg==', 1, 32));
});
test('server only accepts exact bounded envelopes, never plaintext or media fields', () => {
  const f = fixture(); const e = f.message(); assert.deepEqual(envelope(strictJson(canonical(e))), e);
  for (const key of ['payload', 'app_package', 'scores', 'frame_b64', 'audio', 'chat_text', 'photo']) assert.throws(() => envelope({...e, [key]: 'private'}));
  for (const delta of [{v: true}, {kind: 'photo'}, {sender_id: '../path'}, {suite: 'RSA'}, {ciphertext_b64: 'AA'},
    {ciphertext_b64: Buffer.alloc(16433).toString('base64url')}, {signature_b64: 'AA'}]) assert.throws(() => envelope({...e, ...delta}));
});
test('pair transcript pins keys and authenticated accounts, rejects substitution/replay expiry/null UID', () => {
  const now = 1000000; const f = fixture(now); const p = registration(f.request, 'guardian', now);
  assert.equal(p.pairId, f.pairId); peer(p.guardian); peer(p.child);
  assert.throws(() => registration(f.request, 'child', now));
  assert.throws(() => registration(f.request, 'guardian', now + 300001));
  assert.throws(() => registration({...f.request, child: fixture(now).child.peer}, 'guardian', now));
  assert.throws(() => peer({...p.guardian, auth_uid: null}));
  assert.throws(() => peer({...p.child, hpke_public_keyset_b64: p.guardian.hpke_public_keyset_b64}));
  assert.throws(() => registration({...f.request, confirmation: {...f.request.confirmation, response_sha256: 'a'.repeat(64)}}, 'guardian', now));
});
test('UID/pair/direction/key pins are all necessary, device_id alone never authenticates', () => {
  const f = fixture(); const p: PairRecord = {...registration(f.request, 'guardian', Date.now()), status: 'active', pendingCount: 0, pendingBytes: 0};
  for (const kind of ['incident', 'control_receipt', 'control'] as const) {
    const e = f.message(kind); const uid = kind === 'control' ? 'guardian' : 'child'; assert.ok(authorizeEnvelope(p, e, uid));
    assert.throws(() => authorizeEnvelope(p, e, 'stranger'));
    assert.throws(() => authorizeEnvelope(p, {...e, pair_id: randomUUID()}, uid));
    assert.throws(() => authorizeEnvelope(p, {...e, recipient_id: e.sender_id}, uid));
    assert.throws(() => authorizeEnvelope(p, {...e, sender_signing_kid: 'a'.repeat(64)}, uid));
    assert.throws(() => authorizeEnvelope({...p, status: 'revoked'}, e, uid));
  }
  assert.equal(recipient(p, 'guardian').device_id, p.guardian.device_id); assert.throws(() => recipient(p, 'stranger'));
});
test('FCM is data only and generic, with no incident identifiers or notification object', () => {
  const message = genericMessage('test-token'); assert.deepEqual(message.data, {v: '2', type: 'inbox_changed'});
  assert.equal('notification' in message, false); assert.equal(JSON.stringify(message).includes('event_id'), false);
});
