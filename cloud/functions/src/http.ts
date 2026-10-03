import {Request, Response} from 'express';
import {check, envelope, exact, RelayError, strictJson, text, uuid} from './validation';
import {membershipBody, registration} from './pairs';
import {CiphertextRelay} from './envelopes';
import {Notifications} from './notifications';

export interface HttpDependencies {
  relay: CiphertextRelay; notifications: Notifications;
  verifyIdToken(token: string): Promise<string>;
  verifyAppCheck?: (token: string) => Promise<void>;
  rateLimit(uid: string): Promise<void>; now?: () => number;
}
export function createHandler(deps: HttpDependencies) {
  return async (req: Request & {rawBody?: Buffer}, res: Response): Promise<void> => {
    res.set('Cache-Control', 'no-store'); res.set('X-Content-Type-Options', 'nosniff');
    try {
      const auth = req.headers.authorization; check(auth && /^Bearer [A-Za-z0-9_.-]{1,8192}$/.test(auth), 'unauthenticated');
      let uid: string;
      try { uid = await deps.verifyIdToken(auth.slice(7)); } catch { throw new RelayError('unauthenticated'); }
      check(typeof uid === 'string' && /^[A-Za-z0-9:_-]{1,128}$/.test(uid), 'unauthenticated');
      if (deps.verifyAppCheck) {
        const token = req.headers['x-firebase-appcheck']; check(typeof token === 'string' && token.length <= 8192, 'forbidden');
        try { await deps.verifyAppCheck(token); } catch { throw new RelayError('forbidden'); }
      }
      await deps.rateLimit(uid);
      check(req.originalUrl.length <= 2048, 'bounds');
      const url = new URL(req.originalUrl, 'https://local.invalid');
      const params: Record<string, string> = {};
      for (const [name, value] of url.searchParams) { check(!Object.hasOwn(params, name)); params[name] = value; }
      const path = url.pathname;
      const post = (max: number) => {
        check(req.method === 'POST' && Object.keys(params).length === 0);
        check(typeof req.headers['content-type'] === 'string' && /^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(req.headers['content-type']));
        check(req.headers['content-encoding'] === undefined && Buffer.isBuffer(req.rawBody)); return strictJson(req.rawBody, max);
      };
      if (path === '/v2/pairs') {
        const data = registration(post(16384), uid, (deps.now ?? Date.now)());
        res.status(200).json({v: 2, ...(await deps.relay.register(data))}); return;
      }
      const pairAction = /^\/v2\/pairs\/([^/]+)\/(accept|revoke)$/.exec(path);
      if (pairAction) {
        const pairId = uuid(pairAction[1]); const action = pairAction[2]; const digest = membershipBody(post(16384), pairId);
        if (action === 'accept') await deps.relay.accept(pairId, digest, uid); else await deps.relay.revoke(pairId, digest, uid);
        res.status(200).json({v: 2, pair_id: pairId, status: action === 'accept' ? 'active' : 'revoked'}); return;
      }
      if (path === '/v2/envelopes' && req.method === 'POST') {
        const data = envelope(post(24576)); const result = await deps.relay.ingest(data, uid);
        // Push is best effort. Never roll back a persisted event or log exception bodies/tokens.
        try { await deps.notifications.notify(data.pair_id, data.recipient_id); } catch { /* foreground/periodic sync recovers */ }
        res.status(result.status === 'stored' ? 201 : 200).json(result); return;
      }
      if (path === '/v2/envelopes' && req.method === 'GET') {
        check(Object.keys(params).every(k => ['pair_id', 'after', 'limit'].includes(k)) && 'pair_id' in params);
        check(!req.rawBody || req.rawBody.length === 0);
        const limit = Number(text(params.limit ?? '50', /^[1-9][0-9]?$/));
        res.status(200).json(await deps.relay.fetch(uuid(params.pair_id), uid, limit, params.after)); return;
      }
      const deletion = /^\/v2\/envelopes\/([^/]+)$/.exec(path);
      if (deletion && req.method === 'DELETE') {
        check(Object.keys(params).length === 1 && 'pair_id' in params && (!req.rawBody || req.rawBody.length === 0));
        const id = uuid(deletion[1]); await deps.relay.delete(uuid(params.pair_id), id, uid);
        res.status(200).json({v: 2, message_id: id, status: 'deleted'}); return;
      }
      if (path === '/v2/push-token') {
        const data = exact(post(8192), ['v', 'device_id', 'token']); check(data.v === 2);
        await deps.notifications.register(uid, uuid(data.device_id), text(data.token, /^[\x21-\x7e]{1,4096}$/));
        res.status(200).json({v: 2, status: 'registered'}); return;
      }
      throw new RelayError('invalid_envelope');
    } catch (e) {
      const failure = e instanceof RelayError ? e : new RelayError('unavailable');
      res.status(failure.status).json({v: 2, error: failure.code});
    }
  };
}
