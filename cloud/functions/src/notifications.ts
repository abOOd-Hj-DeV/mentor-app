import {Firestore, Timestamp} from 'firebase-admin/firestore';
import {Message} from 'firebase-admin/messaging';
import {KeyManagementServiceClient} from '@google-cloud/kms';
import {CiphertextRelay} from './envelopes';
import {check, sha, text, uuid} from './validation';

export interface TokenProtection {encrypt(token: Buffer, aad: Buffer): Promise<Buffer>; decrypt(ciphertext: Buffer, aad: Buffer): Promise<Buffer>}
export class KmsTokenProtection implements TokenProtection {
  constructor(private readonly name: string, private readonly client = new KeyManagementServiceClient()) {}
  private requireKey(): void { check(/^projects\/[^/]+\/locations\/[^/]+\/keyRings\/[^/]+\/cryptoKeys\/[^/]+$/.test(this.name), 'unavailable'); }
  async encrypt(token: Buffer, aad: Buffer): Promise<Buffer> {
    this.requireKey(); const [result] = await this.client.encrypt({name: this.name, plaintext: token, additionalAuthenticatedData: aad});
    check(result.ciphertext, 'unavailable'); return Buffer.from(result.ciphertext as Uint8Array);
  }
  async decrypt(ciphertext: Buffer, aad: Buffer): Promise<Buffer> {
    this.requireKey(); const [result] = await this.client.decrypt({name: this.name, ciphertext, additionalAuthenticatedData: aad});
    check(result.plaintext, 'unavailable'); return Buffer.from(result.plaintext as Uint8Array);
  }
}
export function genericMessage(token: string): Message {
  return {token, data: {v: '2', type: 'inbox_changed'}, android: {priority: 'normal', ttl: 86400000, collapseKey: 'mentor_inbox'}};
}
export class Notifications {
  constructor(private readonly db: Firestore, private readonly relay: CiphertextRelay, private readonly protection: TokenProtection,
    private readonly send: (message: Message) => Promise<unknown>, private readonly now: () => number = Date.now) {}
  private aad(uid: string, deviceId: string) { return Buffer.from(`mentor.push.v2\n${uid}\n${deviceId}\n`); }
  private ref(uid: string, deviceId: string) { return this.db.collection('mentor_push').doc(sha(this.aad(uid, deviceId))); }
  async register(uid: string, deviceId: string, token: string): Promise<void> {
    uuid(deviceId); text(token, /^[\x21-\x7e]{1,4096}$/); await this.relay.ownedDevice(uid, deviceId);
    const ciphertext = await this.protection.encrypt(Buffer.from(token), this.aad(uid, deviceId));
    await this.ref(uid, deviceId).set({uid, ciphertext: ciphertext.toString('base64'),
      expiresAt: Timestamp.fromMillis(this.now() + 30 * 86400000)});
  }
  async notify(pairId: string, deviceId: string): Promise<boolean> {
    uuid(deviceId); const target = await this.relay.target(pairId, deviceId); check(target.auth_uid, 'forbidden');
    const ref = this.ref(target.auth_uid, deviceId); const snapshot = await ref.get();
    if (!snapshot.exists) return false;
    const record = snapshot.data(); check(record && record.uid === target.auth_uid && record.expiresAt.toMillis() > this.now(), 'unavailable');
    await this.relay.ownedDevice(record.uid, deviceId);
    const plaintext = await this.protection.decrypt(Buffer.from(record.ciphertext, 'base64'), this.aad(record.uid, deviceId));
    try { await this.send(genericMessage(text(plaintext.toString('ascii'), /^[\x21-\x7e]{1,4096}$/))); return true; }
    finally { plaintext.fill(0); }
  }
}
