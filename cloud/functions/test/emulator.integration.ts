import {test, after} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID, sign, createCipheriv, createDecipheriv, randomBytes} from 'node:crypto';
import {Firestore} from 'firebase-admin/firestore';
import {CiphertextRelay} from '../src/envelopes';
import {Notifications, TokenProtection} from '../src/notifications';
import {registration} from '../src/pairs';
import {canonical, envelope, sha, signatureInput, strictJson} from '../src/validation';
import {fixture} from './helpers';

// Tests explicitly require the emulator; production imports never select test services.
assert.ok(process.env.FIRESTORE_EMULATOR_HOST && /^(localhost|127\.0\.0\.1):[0-9]+$/.test(process.env.FIRESTORE_EMULATOR_HOST));
const projectId = 'demo-mentor-relay'; const db = new Firestore({projectId});
after(async () => {await db.terminate();});
async function setup() {
  let now = 1000000; const relay = new CiphertextRelay(db, () => now); const f = fixture(now);
  const p = registration(f.request, 'guardian', now); await relay.register(p);
  await relay.accept(f.pairId, p.transcriptSha256, 'child');
  return {f, p, relay, advance: (n: number) => {now += n;}};
}
test('real transactions provide concurrent idempotency, semantic duplicates, conflicts and signature rejection', async () => {
  const {f, relay} = await setup(); const e = f.message();
  const results = await Promise.all(Array.from({length: 5}, () => relay.ingest(e, 'child')));
  assert.equal(results.filter(r => r.status === 'stored').length, 1);
  assert.equal(results.filter(r => r.status === 'duplicate').length, 4);
  assert.equal((await relay.ingest(envelope(strictJson(Buffer.from(JSON.stringify(e, null, 2)))), 'child')).status, 'duplicate');
  const changed = {...e, ciphertext_b64: randomBytes(64).toString('base64url')};
  await assert.rejects(relay.ingest(changed, 'child'), {code: 'invalid_envelope'});
  changed.signature_b64 = sign('sha256', signatureInput(changed), f.child.privateKey).toString('base64url');
  await assert.rejects(relay.ingest(changed, 'child'), {code: 'message_conflict'});
  const record = (await db.collection('mentor_envelopes').doc(`${f.pairId}_${e.message_id}`).get()).data();
  assert.equal(record?.hash, sha(canonical(e))); assert.equal(Object.hasOwn(record ?? {}, 'plaintext'), false);
  const pair = (await db.collection('mentor_pairs').doc(f.pairId).get()).data();
  assert.equal(pair?.pendingCount, 1);
});
test('wrong UID/pair/revocation/direction reject; server can never patch transcript pins', async () => {
  const {f, relay, p} = await setup(); const e = f.message();
  await assert.rejects(relay.ingest(e, 'guardian'), {code: 'forbidden'});
  await assert.rejects(relay.ingest({...e, pair_id: randomUUID()}, 'child'), {code: 'forbidden'});
  await assert.rejects(relay.ingest({...e, recipient_hpke_kid: 'a'.repeat(64)}, 'child'), {code: 'forbidden'});
  await assert.rejects(relay.accept(f.pairId, p.transcriptSha256, 'guardian'), {code: 'forbidden'});
  await assert.rejects(relay.register({...p, transcriptSha256: 'a'.repeat(64)}), {code: 'message_conflict'});
  await relay.ingest(f.message('control'), 'guardian'); await relay.ingest(f.message('control_receipt'), 'child');
  await relay.revoke(f.pairId, p.transcriptSha256, 'guardian');
  await assert.rejects(relay.ingest(e, 'child'), {code: 'forbidden'});
  await assert.rejects(relay.accept(f.pairId, p.transcriptSha256, 'child'), {code: 'forbidden'});
  await assert.rejects(relay.fetch(f.pairId, 'guardian', 50), {code: 'forbidden'});
});
test('recipient-bound pagination, delete tombstones and upload-after-delete never resurrect ciphertext', async () => {
  const {f, relay} = await setup(); const items = [f.message(), f.message(), f.message()];
  for (const e of items) await relay.ingest(e, 'child');
  const first = await relay.fetch(f.pairId, 'guardian', 1); assert.equal(first.envelopes.length, 1); assert.ok(first.next_cursor);
  await assert.rejects(relay.fetch(f.pairId, 'child', 1, first.next_cursor), {code: 'forbidden'});
  const second = await relay.fetch(f.pairId, 'guardian', 2, first.next_cursor);
  assert.equal(second.envelopes.length, 2); assert.equal(new Set([...first.envelopes, ...second.envelopes].map(e => e.message_id)).size, 3);
  assert.equal(second.next_cursor, null);
  await assert.rejects(relay.fetch(f.pairId, 'guardian', 51), {code: 'invalid_envelope'});
  await assert.rejects(relay.fetch(f.pairId, 'guardian', 1, 'a'.repeat(513)), {code: 'invalid_envelope'});
  await assert.rejects(relay.delete(f.pairId, items[0]!.message_id, 'child'), {code: 'forbidden'});
  await relay.delete(f.pairId, items[0]!.message_id, 'guardian'); await relay.delete(f.pairId, items[0]!.message_id, 'guardian');
  assert.equal((await relay.ingest(items[0]!, 'child')).status, 'duplicate');
  const record = (await db.collection('mentor_envelopes').doc(`${f.pairId}_${items[0]!.message_id}`).get()).data();
  assert.equal(Object.hasOwn(record ?? {}, 'envelope'), false); assert.equal(record?.deleted, true);
});
test('write quotas and pending storage limits are atomic; retention removes ciphertext and reconciles capacity', async () => {
  const {f, relay, advance} = await setup();
  for (let i = 0; i < 60; i++) await relay.ingest(f.message(), 'child');
  await assert.rejects(relay.ingest(f.message(), 'child'), {code: 'rate_limited'});
  advance(60001); const e = f.message(); await relay.ingest(e, 'child');
  await db.collection('mentor_pairs').doc(f.pairId).update({pendingCount: 10000});
  await assert.rejects(relay.ingest(f.message(), 'child'), {code: 'rate_limited'});
  await db.collection('mentor_pairs').doc(f.pairId).update({pendingCount: 61, pendingBytes: 32 * 1024 * 1024});
  await assert.rejects(relay.ingest(f.message(), 'child'), {code: 'rate_limited'});
  await db.collection('mentor_pairs').doc(f.pairId).update({pendingBytes: canonical(e).length * 61});
  advance(31 * 86400000); await relay.sweep();
  assert.equal((await relay.fetch(f.pairId, 'guardian', 50)).envelopes.length, 0);
  const pair = (await db.collection('mentor_pairs').doc(f.pairId).get()).data(); assert.equal(pair?.pendingCount, 0);
  assert.equal((await relay.ingest(e, 'child')).status, 'duplicate');
  advance(90 * 86400000); await relay.sweep();
  assert.equal((await relay.ingest(e, 'child')).status, 'duplicate');
});
test('push ownership, encrypted token storage, AAD binding and generic delivery only', async () => {
  const {f, relay} = await setup(); const key = randomBytes(32); const sent: unknown[] = [];
  const protection: TokenProtection = {
    async encrypt(token, aad) {const iv = randomBytes(12); const c = createCipheriv('aes-256-gcm', key, iv); c.setAAD(aad);
      return Buffer.concat([iv, c.update(token), c.final(), c.getAuthTag()]);},
    async decrypt(token, aad) {const c = createDecipheriv('aes-256-gcm', key, token.subarray(0, 12)); c.setAAD(aad); c.setAuthTag(token.subarray(-16));
      return Buffer.concat([c.update(token.subarray(12, -16)), c.final()]);},
  };
  const notifications = new Notifications(db, relay, protection, async m => {sent.push(m);});
  await assert.rejects(notifications.register('child', f.guardian.peer.device_id, 'sensitive-test-token'), {code: 'forbidden'});
  await notifications.register('guardian', f.guardian.peer.device_id, 'sensitive-test-token');
  const records = await db.collection('mentor_push').get(); assert.equal(JSON.stringify(records.docs.map(r => r.data())).includes('sensitive-test-token'), false);
  assert.equal(await notifications.notify(f.pairId, f.guardian.peer.device_id), true);
  assert.deepEqual((sent[0] as {data: unknown}).data, {v: '2', type: 'inbox_changed'});
  assert.equal('notification' in (sent[0] as object), false);
  await assert.rejects(protection.decrypt(await protection.encrypt(Buffer.from('x'), Buffer.from('uid1')), Buffer.from('uid2')));
});
test('deployed deny rules reject direct REST reads/writes even with a simulated authenticated user', async () => {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  const path = `http://${host}/v1/projects/${projectId}/databases/(default)/documents/mentor_pairs/forbidden`;
  const jwt = `${Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url')}.${Buffer.from(JSON.stringify({sub: 'guardian', user_id: 'guardian', aud: projectId,
    iss: `https://securetoken.google.com/${projectId}`, iat: 1, exp: 9999999999})).toString('base64url')}.`;
  for (const auth of [undefined, `Bearer ${jwt}`]) {
    const headers: Record<string, string> = {'Content-Type': 'application/json'}; if (auth) headers.Authorization = auth;
    assert.equal((await fetch(path, {headers})).status, 403);
    assert.equal((await fetch(path, {method: 'PATCH', headers, body: JSON.stringify({fields: {status: {stringValue: 'active'}}})})).status, 403);
  }
});
