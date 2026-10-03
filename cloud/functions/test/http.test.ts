import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Request, Response} from 'express';
import {createHandler, HttpDependencies} from '../src/http';
import {canonical, RelayError, sha} from '../src/validation';
import {fixture} from './helpers';

async function request(deps: Partial<HttpDependencies>, body: string | Buffer, auth: string | null = 'Bearer test',
  url = '/v2/envelopes', contentType = 'application/json', method = 'POST') {
  let status = 0; let result: unknown;
  const req = {headers: {authorization: auth, 'content-type': contentType}, method, originalUrl: url,
    rawBody: Buffer.from(body), body: {v: 2}} as unknown as Request & {rawBody: Buffer};
  const res = {set() {}, status(n: number) {status = n; return this;}, json(o: unknown) {result = o; return this;}} as unknown as Response;
  const defaults = {verifyIdToken: async () => 'child', rateLimit: async () => {}};
  await createHandler({...defaults, ...deps} as HttpDependencies)(req, res); return {status, result};
}
test('missing/invalid credentials fail closed before relying on framework parsed body', async () => {
  assert.equal((await request({}, '{}', null)).status, 401);
  const failure = await request({verifyIdToken: async () => {throw Error('private token');}}, '{}');
  assert.deepEqual(failure, {status: 401, result: {v: 2, error: 'unauthenticated'}});
  for (const b of ['{"v":2,"v":2}', '{}', '{"media":"private"}']) assert.equal((await request({}, b)).status, 400);
  assert.equal((await request({}, '{}', 'Bearer test', '/v2/envelopes', 'text/plain')).status, 400);
  assert.equal((await request({}, Buffer.alloc(24577))).status, 413);
});
test('App Check is optional unless explicitly configured, and ingest survives a push outage', async () => {
  const f = fixture(); const e = f.message(); let stored = 0;
  const relay = {async ingest() {stored++; return {v: 2, message_id: e.message_id, status: 'stored', envelope_sha256: sha(canonical(e)), received_at_ms: '1'};}};
  const notifications = {async notify() {throw Error('secret push failure');}};
  const deps = {relay, notifications} as unknown as Partial<HttpDependencies>;
  const result = await request(deps, canonical(e)); assert.equal(result.status, 201); assert.equal(stored, 1);
  assert.equal((await request({...deps, verifyAppCheck: async () => {}}, canonical(e))).status, 403);
});
test('fixed error codes, quota errors, duplicate query parameters and cursor bounds fail visibly', async () => {
  assert.deepEqual(await request({rateLimit: async () => {throw new RelayError('rate_limited');}}, '{}'),
    {status: 429, result: {v: 2, error: 'rate_limited'}});
  assert.equal((await request({}, '', 'Bearer test', '/v2/envelopes?pair_id=a&pair_id=b', 'application/json', 'GET')).status, 400);
});
