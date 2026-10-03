import {FieldValue, Firestore, Timestamp} from 'firebase-admin/firestore';
import {randomBytes} from 'node:crypto';
import {PairRecord, authorizeEnvelope, recipient} from './authorization';
import {base64, canonical, check, Envelope, Peer, RelayError, sha, signature, signingKey, text, uuid} from './validation';
import {signatureInput} from './validation';
import {PairRegistration} from './pairs';

const DAY = 86400000;
export interface StoredEnvelope {envelope: Envelope; hash: string; receivedAtMs: number; size: number; deleted: boolean; expiresAt: Timestamp}

export class CiphertextRelay {
  constructor(private readonly db: Firestore, private readonly now: () => number = Date.now, private readonly retentionDays = 30) {
    check(Number.isInteger(retentionDays) && retentionDays >= 1 && retentionDays <= 30);
  }
  private pairRef(id: string) { return this.db.collection('mentor_pairs').doc(uuid(id)); }
  private messageRef(pairId: string, id: string) { return this.db.collection('mentor_envelopes').doc(`${uuid(pairId)}_${uuid(id)}`); }

  async register(registration: PairRegistration): Promise<{status: 'pending' | 'active'; transcript_sha256: string}> {
    const ref = this.pairRef(registration.pairId);
    return this.db.runTransaction(async tx => {
      const existing = await tx.get(ref);
      if (existing.exists) {
        const old = existing.data() as PairRecord;
        check(old.status !== 'revoked' && old.transcriptSha256 === registration.transcriptSha256, 'message_conflict');
        return {status: old.status, transcript_sha256: old.transcriptSha256};
      }
      tx.create(ref, {...registration, status: 'pending', pendingCount: 0, pendingBytes: 0});
      return {status: 'pending' as const, transcript_sha256: registration.transcriptSha256};
    });
  }
  async accept(pairId: string, digest: string, uid: string): Promise<void> {
    const ref = this.pairRef(pairId);
    await this.db.runTransaction(async tx => {
      const record = await tx.get(ref); check(record.exists, 'forbidden'); const p = record.data() as PairRecord;
      check(p.status !== 'revoked' && p.transcriptSha256 === digest && p.child.auth_uid === uid, 'forbidden');
      tx.update(ref, {status: 'active'});
    });
  }
  async revoke(pairId: string, digest: string, uid: string): Promise<void> {
    const ref = this.pairRef(pairId);
    await this.db.runTransaction(async tx => {
      const record = await tx.get(ref); check(record.exists, 'forbidden'); const p = record.data() as PairRecord;
      check(p.transcriptSha256 === digest && p.guardian.auth_uid === uid, 'forbidden'); tx.update(ref, {status: 'revoked'});
    });
  }
  async ingest(envelope: Envelope, uid: string): Promise<{v: 2; message_id: string; status: 'stored' | 'duplicate'; envelope_sha256: string; received_at_ms: string}> {
    const pairRef = this.pairRef(envelope.pair_id); const ref = this.messageRef(envelope.pair_id, envelope.message_id);
    const quotaRef = this.db.collection('mentor_quotas').doc(envelope.pair_id); const now = this.now();
    const bytes = canonical(envelope); const hash = sha(bytes);
    return this.db.runTransaction(async tx => {
      const [pairSnapshot, existing, quotaSnapshot] = await Promise.all([tx.get(pairRef), tx.get(ref), tx.get(quotaRef)]);
      check(pairSnapshot.exists, 'forbidden'); const pair = pairSnapshot.data() as PairRecord;
      const sender = authorizeEnvelope(pair, envelope, uid);
      signature(signingKey(sender), signatureInput(envelope), base64(envelope.signature_b64, 8, 72));
      if (existing.exists) {
        const old = existing.data() as StoredEnvelope; check(old.hash === hash, 'message_conflict');
        return {v: 2 as const, message_id: envelope.message_id, status: 'duplicate' as const, envelope_sha256: hash, received_at_ms: String(old.receivedAtMs)};
      }
      const times = (quotaSnapshot.data()?.times ?? []) as number[];
      const recent = times.filter(t => t > now - 60000 && t <= now); check(recent.length < 60, 'rate_limited');
      check(pair.pendingCount < 10000 && pair.pendingBytes + bytes.length <= 32 * 1024 * 1024, 'rate_limited');
      tx.create(ref, {pairId: pair.pairId, recipientId: envelope.recipient_id, envelope, hash, size: bytes.length, receivedAtMs: now,
        deleted: false, expiresAt: Timestamp.fromMillis(now + this.retentionDays * DAY)});
      tx.update(pairRef, {pendingCount: pair.pendingCount + 1, pendingBytes: pair.pendingBytes + bytes.length});
      tx.set(quotaRef, {times: [...recent, now]});
      return {v: 2 as const, message_id: envelope.message_id, status: 'stored' as const, envelope_sha256: hash, received_at_ms: String(now)};
    });
  }
  async fetch(pairId: string, uid: string, limit: number, cursor?: string): Promise<{v: 2; envelopes: Envelope[]; next_cursor: string | null}> {
    check(Number.isInteger(limit) && limit >= 1 && limit <= 50);
    const snapshot = await this.pairRef(pairId).get(); check(snapshot.exists, 'forbidden'); const p = snapshot.data() as PairRecord;
    const target = recipient(p, uid);
    let query = this.db.collection('mentor_envelopes').where('pairId', '==', pairId).where('recipientId', '==', target.device_id)
      .where('deleted', '==', false).orderBy('receivedAtMs').orderBy('__name__');
    if (cursor !== undefined) {
      text(cursor, /^[A-Za-z0-9_-]{1,512}$/);
      const record = await this.db.collection('mentor_cursors').doc(sha(Buffer.from(cursor))).get();
      const data = record.data(); check(data && data.uid === uid && data.pairId === pairId && data.expiresAt.toMillis() >= this.now(), 'forbidden');
      query = query.startAfter(data.receivedAtMs, data.documentId);
    }
    const messages = await query.limit(limit + 1).get();
    const entries = messages.docs.slice(0, limit);
    let next: string | null = null;
    const last = entries.at(-1);
    if (messages.size > limit && last) {
      next = randomBytes(32).toString('base64url');
      await this.db.collection('mentor_cursors').doc(sha(Buffer.from(next))).create({uid, pairId, receivedAtMs: last.data().receivedAtMs,
        documentId: last.id, expiresAt: Timestamp.fromMillis(this.now() + 600000)});
    }
    return {v: 2, envelopes: entries.filter(e => e.data().expiresAt.toMillis() > this.now())
      .map(e => (e.data() as StoredEnvelope).envelope), next_cursor: next};
  }
  async delete(pairId: string, messageId: string, uid: string): Promise<void> {
    const pairRef = this.pairRef(pairId); const ref = this.messageRef(pairId, messageId);
    await this.db.runTransaction(async tx => {
      const [pairSnapshot, message] = await Promise.all([tx.get(pairRef), tx.get(ref)]);
      check(pairSnapshot.exists, 'forbidden'); const pair = pairSnapshot.data() as PairRecord; const target = recipient(pair, uid);
      if (!message.exists) return;
      const stored = message.data() as StoredEnvelope & {recipientId: string}; check(stored.recipientId === target.device_id, 'forbidden');
      if (stored.deleted) return;
      tx.set(ref, {pairId, recipientId: stored.recipientId, hash: stored.hash, receivedAtMs: stored.receivedAtMs, deleted: true,
        size: 0});
      tx.update(pairRef, {pendingCount: Math.max(0, pair.pendingCount - 1), pendingBytes: Math.max(0, pair.pendingBytes - stored.size)});
    });
  }

  /** Do not enable Firestore TTL for active envelopes: this transaction also maintains quotas. */
  async sweep(): Promise<number> {
    const expired = await this.db.collection('mentor_envelopes').where('expiresAt', '<=', Timestamp.fromMillis(this.now())).limit(500).get();
    for (const candidate of expired.docs) await this.db.runTransaction(async tx => {
      const record = await tx.get(candidate.ref); if (!record.exists) return;
      const data = record.data() as StoredEnvelope & {pairId: string}; if (data.expiresAt.toMillis() > this.now()) return;
      if (data.deleted) { tx.update(candidate.ref, {expiresAt: FieldValue.delete()}); return; }
      const pairRef = this.pairRef(data.pairId); const pairSnapshot = await tx.get(pairRef);
      if (!data.deleted && pairSnapshot.exists) {
        const pair = pairSnapshot.data() as PairRecord;
        tx.update(pairRef, {pendingCount: Math.max(0, pair.pendingCount - 1), pendingBytes: Math.max(0, pair.pendingBytes - data.size)});
      }
      tx.set(candidate.ref, {pairId: data.pairId, recipientId: data.envelope.recipient_id, hash: data.hash, receivedAtMs: data.receivedAtMs,
        deleted: true, size: 0});
    });
    return expired.size;
  }
  async ownedDevice(uid: string, deviceId: string): Promise<Peer> {
    uuid(deviceId);
    // Indexed UID fields, never trust the request's device_id as authentication.
    for (const role of ['guardian', 'child']) {
      const result = await this.db.collection('mentor_pairs').where(`${role}.auth_uid`, '==', uid).where('status', '==', 'active').limit(100).get();
      for (const doc of result.docs) {
        const p = doc.data() as PairRecord; const member = role === 'guardian' ? p.guardian : p.child;
        if (member.device_id === deviceId) return member;
      }
    }
    throw new RelayError('forbidden');
  }
  async target(pairId: string, deviceId: string): Promise<Peer> {
    const record = await this.pairRef(pairId).get(); check(record.exists, 'forbidden'); const pair = record.data() as PairRecord;
    check(pair.status === 'active', 'forbidden');
    if (pair.guardian.device_id === deviceId) return pair.guardian;
    check(pair.child.device_id === deviceId, 'forbidden'); return pair.child;
  }
}
