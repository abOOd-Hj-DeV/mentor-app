import {generateKeyPairSync, KeyObject, randomBytes, randomUUID, sign} from 'node:crypto';
import {parse} from 'protobufjs';
import {canonical, Envelope, Peer, sha, signatureInput, SUITE} from '../src/validation';

export const KEYSET_PROTO = `syntax="proto3";
message KeyData {string type_url=1; bytes value=2; uint32 key_material_type=3;}
message Key {KeyData key_data=1; uint32 status=2; uint32 key_id=3; uint32 output_prefix_type=4;}
message Keyset {uint32 primary_key_id=1; repeated Key key=2;}
message HpkeParams {uint32 kem=1; uint32 kdf=2; uint32 aead=3;}
message HpkePublicKey {uint32 version=1; HpkeParams params=2; bytes public_key=3;}`;
export function keys(uid: string): {peer: Peer; privateKey: KeyObject} {
  const ec = generateKeyPairSync('ec', {namedCurve: 'prime256v1'});
  const root = parse(KEYSET_PROTO).root;
  const publicKey = root.lookupType('HpkePublicKey').encode({version: 0, params: {kem: 1, kdf: 1, aead: 2}, publicKey: randomBytes(32)}).finish();
  const hpke = Buffer.from(root.lookupType('Keyset').encode({primaryKeyId: 42, key: [{keyId: 42, status: 1, outputPrefixType: 3,
    keyData: {typeUrl: 'type.googleapis.com/google.crypto.tink.HpkePublicKey', keyMaterialType: 3, value: publicKey}}]}).finish());
  const spki = ec.publicKey.export({type: 'spki', format: 'der'});
  return {privateKey: ec.privateKey, peer: {device_id: randomUUID(), auth_uid: uid,
    hpke_public_keyset_b64: hpke.toString('base64url'), hpke_kid: sha(hpke), signing_public_spki_b64: spki.toString('base64url'), signing_kid: sha(spki)}};
}
export function signed(record: Record<string, unknown>, key: KeyObject): Record<string, unknown> {
  return {...record, signature_b64: sign('sha256', Buffer.concat([Buffer.from('mentor.pair.v2\n'), canonical(record)]), key).toString('base64url')};
}
export function fixture(now = Date.now()) {
  const guardian = keys('guardian'); const child = keys('child'); const pairId = randomUUID();
  const offer = signed({v: 2, type: 'pair_offer', pair_id: pairId, nonce_b64: randomBytes(32).toString('base64url'),
    expires_at_ms: String(now + 300000), guardian: guardian.peer}, guardian.privateKey);
  const response = signed({v: 2, type: 'pair_response', pair_id: pairId, offer_sha256: sha(canonical(offer)), child: child.peer}, child.privateKey);
  const confirmation = signed({v: 2, type: 'pair_confirm', pair_id: pairId, offer_sha256: sha(canonical(offer)),
    response_sha256: sha(canonical(response))}, guardian.privateKey);
  const request = {v: 2, pair_id: pairId, guardian: guardian.peer, child: child.peer, offer, response, confirmation};
  function message(kind: Envelope['kind'] = 'incident'): Envelope {
    const sender = kind === 'control' ? guardian : child; const recipient = kind === 'control' ? child : guardian;
    const e: Envelope = {v: 2, kind, pair_id: pairId, sender_id: sender.peer.device_id, recipient_id: recipient.peer.device_id,
      message_id: randomUUID(), recipient_hpke_kid: recipient.peer.hpke_kid, sender_signing_kid: sender.peer.signing_kid,
      suite: SUITE, ciphertext_b64: randomBytes(64).toString('base64url'), signature_b64: ''};
    e.signature_b64 = sign('sha256', signatureInput(e), sender.privateKey).toString('base64url'); return e;
  }
  return {guardian, child, pairId, request, message};
}
