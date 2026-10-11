import {canonical, check, decimal, exact, peer, Peer, sha, signature, signingKey, base64, uuid, text, HEX} from './validation';

export interface PairRegistration {pairId: string; guardian: Peer; child: Peer; transcriptSha256: string}
export function registration(value: unknown, uid: string, nowMs: number): PairRegistration {
  const o = exact(value, ['v', 'pair_id', 'guardian', 'child', 'offer', 'response', 'confirmation']); check(o.v === 2);
  const pairId = uuid(o.pair_id); const guardian = peer(o.guardian); const child = peer(o.child);
  check(guardian.auth_uid === uid && guardian.auth_uid !== child.auth_uid && guardian.device_id !== child.device_id &&
    guardian.hpke_kid !== child.hpke_kid && guardian.signing_kid !== child.signing_kid, 'forbidden');
  const offer = exact(o.offer, ['v', 'type', 'pair_id', 'nonce_b64', 'expires_at_ms', 'guardian', 'signature_b64']);
  const response = exact(o.response, ['v', 'type', 'pair_id', 'offer_sha256', 'child', 'signature_b64']);
  const confirm = exact(o.confirmation, ['v', 'type', 'pair_id', 'offer_sha256', 'response_sha256', 'signature_b64']);
  for (const [record, type, sender] of [[offer, 'pair_offer', guardian], [response, 'pair_response', child],
    [confirm, 'pair_confirm', guardian]] as const) {
    check(record.v === 2 && record.type === type && record.pair_id === pairId && canonical(record).length <= 2953);
    const unsigned = {...record}; delete unsigned.signature_b64;
    signature(signingKey(sender), Buffer.concat([Buffer.from('mentor.pair.v2\n'), canonical(unsigned)]), base64(record.signature_b64, 8, 72));
  }
  base64(offer.nonce_b64, 32, 32); const expires = decimal(offer.expires_at_ms);
  check(expires >= BigInt(nowMs) && expires <= BigInt(nowMs + 300000));
  check(canonical(peer(offer.guardian)).equals(canonical(guardian)) && canonical(peer(response.child)).equals(canonical(child)));
  const offerHash = sha(canonical(offer)); const responseHash = sha(canonical(response));
  check(text(response.offer_sha256, HEX) === offerHash && text(confirm.offer_sha256, HEX) === offerHash &&
    text(confirm.response_sha256, HEX) === responseHash);
  return {pairId, guardian, child, transcriptSha256: sha(Buffer.concat([canonical(offer), canonical(response), canonical(confirm)]))};
}
export function membershipBody(value: unknown, pairId: string): string {
  const o = exact(value, ['v', 'pair_id', 'transcript_sha256']);
  check(o.v === 2 && uuid(o.pair_id) === pairId); return text(o.transcript_sha256, HEX);
}
