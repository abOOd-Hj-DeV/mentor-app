import {check, Envelope, Peer} from './validation';

export interface PairRecord {
  pairId: string; guardian: Peer; child: Peer; transcriptSha256: string;
  status: 'pending' | 'active' | 'revoked'; pendingCount: number; pendingBytes: number;
}
export function recipient(pair: PairRecord, uid: string): Peer {
  check(pair.status === 'active', 'forbidden');
  if (pair.guardian.auth_uid === uid) return pair.guardian;
  check(pair.child.auth_uid === uid, 'forbidden'); return pair.child;
}
export function authorizeEnvelope(pair: PairRecord, envelope: Envelope, uid: string): Peer {
  check(pair.status === 'active' && pair.pairId === envelope.pair_id, 'forbidden');
  const sender = envelope.kind === 'control' ? pair.guardian : pair.child;
  const target = envelope.kind === 'control' ? pair.child : pair.guardian;
  check(sender.auth_uid === uid && sender.device_id === envelope.sender_id && target.device_id === envelope.recipient_id &&
    sender.signing_kid === envelope.sender_signing_kid && target.hpke_kid === envelope.recipient_hpke_kid, 'forbidden');
  return sender;
}
