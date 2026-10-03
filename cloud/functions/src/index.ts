import {initializeApp} from 'firebase-admin/app';
import {getAuth} from 'firebase-admin/auth';
import {getAppCheck} from 'firebase-admin/app-check';
import {getFirestore, Timestamp} from 'firebase-admin/firestore';
import {getMessaging} from 'firebase-admin/messaging';
import {onRequest} from 'firebase-functions/v2/https';
import {onSchedule} from 'firebase-functions/v2/scheduler';
import {defineBoolean, defineInt, defineString} from 'firebase-functions/params';
import {CiphertextRelay} from './envelopes';
import {createHandler} from './http';
import {KmsTokenProtection, Notifications} from './notifications';
import {check, sha} from './validation';

initializeApp();
const kmsKey = defineString('PUSH_KMS_KEY', {default: ''});
const requireAppCheck = defineBoolean('REQUIRE_APP_CHECK', {default: false});
const retentionDays = defineInt('ENVELOPE_RETENTION_DAYS', {default: 30});
function services() {
  const db = getFirestore(); const relay = new CiphertextRelay(db, Date.now, retentionDays.value());
  const notifications = new Notifications(db, relay, new KmsTokenProtection(kmsKey.value()), m => getMessaging().send(m));
  return {db, relay, notifications};
}
export const mentorRelay = onRequest({region: 'us-central1', cors: false, maxInstances: 20, concurrency: 40, timeoutSeconds: 60}, async (req, res) => {
  const {db, relay, notifications} = services();
  const handler = createHandler({relay, notifications,
    verifyIdToken: async token => (await getAuth().verifyIdToken(token, true)).uid,
    verifyAppCheck: requireAppCheck.value() ? async token => { await getAppCheck().verifyToken(token); } : undefined,
    rateLimit: async uid => {
      const ref = db.collection('mentor_http_quotas').doc(sha(Buffer.from(uid))); const now = Date.now();
      await db.runTransaction(async tx => {
        const record = await tx.get(ref); const minute = Math.floor(now / 60000);
        const count = record.data()?.minute === minute ? Number(record.data()?.count) : 0;
        check(count < 120, 'rate_limited'); tx.set(ref, {minute, count: count + 1, expiresAt: Timestamp.fromMillis(now + 3600000)});
      });
    }});
  await handler(req, res);
});
export const mentorRetention = onSchedule({schedule: 'every 30 minutes', region: 'us-central1', timeoutSeconds: 540}, async () => {
  const {db, relay} = services();
  for (let batch = 0; batch < 20; batch++) if (await relay.sweep() < 500) break;
  for (const name of ['mentor_cursors', 'mentor_http_quotas', 'mentor_push']) {
    const expired = await db.collection(name).where('expiresAt', '<=', Timestamp.now()).limit(500).get();
    const batch = db.batch(); expired.docs.forEach(doc => batch.delete(doc.ref)); await batch.commit();
  }
});
