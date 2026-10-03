import {createHash, createPublicKey, verify, KeyObject} from 'node:crypto';
import {visit} from 'jsonc-parser';
import {parse as parseProto} from 'protobufjs';

export type ErrorCode = 'unauthenticated' | 'forbidden' | 'invalid_envelope' | 'bounds' | 'message_conflict' | 'rate_limited' | 'unavailable';
export class RelayError extends Error {
  constructor(readonly code: ErrorCode) { super(code); }
  get status(): number { return {unauthenticated: 401, forbidden: 403, invalid_envelope: 400, bounds: 413,
    message_conflict: 409, rate_limited: 429, unavailable: 503}[this.code]; }
}
export function check(condition: unknown, code: ErrorCode = 'invalid_envelope'): asserts condition {
  if (!condition) throw new RelayError(code);
}
export type Obj = Record<string, unknown>;
export function object(value: unknown): Obj {
  check(value !== null && typeof value === 'object' && !Array.isArray(value));
  return value as Obj;
}
export function exact(value: unknown, keys: string[]): Obj {
  const o = object(value); check(Object.keys(o).sort().join('\n') === [...keys].sort().join('\n')); return o;
}
export function text(value: unknown, pattern: RegExp): string { check(typeof value === 'string' && pattern.test(value)); return value; }
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const HEX = /^[0-9a-f]{64}$/;
export const uuid = (value: unknown): string => text(value, UUID);
export const sha = (value: Uint8Array): string => createHash('sha256').update(value).digest('hex');
export function decimal(value: unknown): bigint {
  const s = text(value, /^(0|[1-9][0-9]{0,18})$/); const n = BigInt(s); check(n <= 9223372036854775807n, 'bounds'); return n;
}
export function base64(value: unknown, min: number, max: number): Buffer {
  const s = text(value, /^[A-Za-z0-9_-]+$/); check(s.length <= Math.ceil(max * 4 / 3), 'bounds');
  const bytes = Buffer.from(s, 'base64url');
  check(bytes.length >= min && bytes.length <= max && bytes.toString('base64url') === s, 'bounds'); return bytes;
}

export function strictJson(raw: Buffer, maxBytes = 24576): Obj {
  check(raw.length > 0 && raw.length <= maxBytes, 'bounds');
  let source: string;
  try { source = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(raw); }
  catch { throw new RelayError('invalid_envelope'); }
  check(!source.includes('\u0000') && !source.includes('\ufeff'));
  const stack: ({kind: 'object'; names: Set<string>} | {kind: 'array'; count: number})[] = [];
  let tokens = 0;
  function value(): void {
    check(++tokens <= 4096, 'bounds');
    const parent = stack.at(-1);
    if (parent?.kind === 'array') check(++parent.count <= 32, 'bounds');
  }
  function push(node: typeof stack[number]): void { value(); stack.push(node); check(stack.length <= 10, 'bounds'); }
  visit(source, {
    onObjectBegin: () => push({kind: 'object', names: new Set()}),
    onObjectProperty: name => {
      check(++tokens <= 4096, 'bounds'); const parent = stack.at(-1); check(parent?.kind === 'object');
      check(!parent.names.has(name)); parent.names.add(name); check(parent.names.size <= 32, 'bounds');
    },
    onObjectEnd: () => { stack.pop(); },
    onArrayBegin: () => push({kind: 'array', count: 0}),
    onArrayEnd: () => { stack.pop(); },
    onLiteralValue: (v, offset, length) => { value(); if (typeof v === 'number') {
      check(Number.isSafeInteger(v) && /^(0|[1-9][0-9]*)$/.test(source.slice(offset, offset + length)), 'bounds');
    } },
    onError: () => { throw new RelayError('invalid_envelope'); },
  }, {disallowComments: true, allowTrailingComma: false, allowEmptyContent: false});
  let parsed: unknown;
  try { parsed = JSON.parse(source) as unknown; } catch { throw new RelayError('invalid_envelope'); }
  return object(parsed);
}

/** RFC8785 subset: all signed public contracts have ASCII strings and integer v only. */
export function canonical(value: unknown): Buffer {
  function emit(v: unknown): string {
    if (v === null) return 'null';
    if (typeof v === 'string') { check(/^[\x20-\x7e]*$/.test(v)); return JSON.stringify(v); }
    if (typeof v === 'number') { check(Number.isSafeInteger(v) && v >= 0); return JSON.stringify(v); }
    if (typeof v === 'boolean') return JSON.stringify(v);
    if (Array.isArray(v)) return `[${v.map(emit).join(',')}]`;
    const o = object(v);
    return `{${Object.keys(o).sort().map(k => `${emit(k)}:${emit(o[k])}`).join(',')}}`;
  }
  return Buffer.from(emit(value));
}

export const SUITE = 'HPKE_X25519_HKDF_SHA256_AES256GCM_RAW+ECDSA_P256_SHA256_DER';
export interface Envelope {
  v: 2; kind: 'incident' | 'control' | 'control_receipt'; pair_id: string; sender_id: string; recipient_id: string;
  message_id: string; recipient_hpke_kid: string; sender_signing_kid: string; suite: typeof SUITE;
  ciphertext_b64: string; signature_b64: string;
}
export function envelope(value: unknown): Envelope {
  const o = exact(value, ['v', 'kind', 'pair_id', 'sender_id', 'recipient_id', 'message_id', 'recipient_hpke_kid',
    'sender_signing_kid', 'suite', 'ciphertext_b64', 'signature_b64']);
  check(o.v === 2 && ['incident', 'control', 'control_receipt'].includes(String(o.kind)) && o.suite === SUITE);
  for (const key of ['pair_id', 'sender_id', 'recipient_id', 'message_id']) uuid(o[key]);
  text(o.recipient_hpke_kid, HEX); text(o.sender_signing_kid, HEX);
  base64(o.ciphertext_b64, 49, 16432); der(base64(o.signature_b64, 8, 72));
  check(canonical(o).length <= 24576, 'bounds'); return o as unknown as Envelope;
}
export function context(o: Envelope): Buffer {
  return Buffer.from(['mentor.envelope.v2', o.kind, o.pair_id, o.sender_id, o.recipient_id, o.message_id,
    o.recipient_hpke_kid, o.sender_signing_kid, o.suite, ''].join('\n'), 'ascii');
}
export function signatureInput(o: Envelope): Buffer {
  const c = context(o); const ciphertext = base64(o.ciphertext_b64, 49, 16432);
  const a = Buffer.alloc(4); a.writeUInt32BE(c.length); const b = Buffer.alloc(4); b.writeUInt32BE(ciphertext.length);
  return Buffer.concat([Buffer.from('mentor.signature.v2\n'), a, c, b, ciphertext]);
}
export function der(bytes: Buffer): void {
  check(bytes.length >= 8 && bytes.length <= 72 && bytes[0] === 0x30 && bytes[1] === bytes.length - 2);
  let offset = 2;
  const order = BigInt('0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551');
  for (let i = 0; i < 2; i++) {
    check(bytes[offset++] === 2); const length = bytes[offset++];
    check(length !== undefined && length >= 1 && length <= 33 && offset + length <= bytes.length);
    const integer = bytes.subarray(offset, offset + length); const first = integer[0]; check(first !== undefined && first < 128);
    check(length === 1 || first !== 0 || (integer[1] ?? 0) >= 128);
    const n = BigInt(`0x${integer.toString('hex')}`); check(n > 0 && n < order); offset += length;
  }
  check(offset === bytes.length);
}
export function signature(publicKey: KeyObject, input: Buffer, bytes: Buffer): void {
  der(bytes); check(verify('sha256', input, publicKey, bytes));
}

export interface Peer {
  device_id: string; auth_uid: string | null; hpke_public_keyset_b64: string; hpke_kid: string;
  signing_public_spki_b64: string; signing_kid: string;
}
const keysetType = parseProto(`syntax="proto3";
  message KeyData {string type_url=1; bytes value=2; uint32 key_material_type=3;}
  message Key {KeyData key_data=1; uint32 status=2; uint32 key_id=3; uint32 output_prefix_type=4;}
  message Keyset {uint32 primary_key_id=1; repeated Key key=2;}
  message HpkeParams {uint32 kem=1; uint32 kdf=2; uint32 aead=3;}
  message HpkePublicKey {uint32 version=1; HpkeParams params=2; bytes public_key=3;}`).root;
function publicHpke(bytes: Buffer): void {
  const ks = keysetType.lookupType('Keyset').decode(bytes) as unknown as {primaryKeyId: number; key: {
    keyId: number; status: number; outputPrefixType: number; keyData: {typeUrl: string; value: Buffer; keyMaterialType: number}}[]};
  check(ks.key.length === 1); const k = ks.key[0]; check(k);
  check(ks.primaryKeyId === k.keyId && k.status === 1 && k.outputPrefixType === 3 && k.keyData.keyMaterialType === 3 &&
    k.keyData.typeUrl === 'type.googleapis.com/google.crypto.tink.HpkePublicKey');
  const p = keysetType.lookupType('HpkePublicKey').decode(k.keyData.value) as unknown as {
    version: number; params: {kem: number; kdf: number; aead: number}; publicKey: Buffer};
  check(p.version === 0 && p.params.kem === 1 && p.params.kdf === 1 && p.params.aead === 2 && p.publicKey.length === 32);
}
export function signingKey(p: Peer): KeyObject {
  const spki = base64(p.signing_public_spki_b64, 1, 128);
  const key = createPublicKey({key: spki, type: 'spki', format: 'der'});
  check(key.asymmetricKeyType === 'ec' && key.asymmetricKeyDetails?.namedCurve === 'prime256v1' &&
    (key.export({type: 'spki', format: 'der'}) as Buffer).equals(spki)); return key;
}
export function peer(value: unknown, cloud = true): Peer {
  const o = exact(value, ['device_id', 'auth_uid', 'hpke_public_keyset_b64', 'hpke_kid', 'signing_public_spki_b64', 'signing_kid']);
  uuid(o.device_id); check(!cloud || o.auth_uid !== null);
  if (o.auth_uid !== null) text(o.auth_uid, /^[A-Za-z0-9:_-]{1,128}$/);
  text(o.hpke_kid, HEX); text(o.signing_kid, HEX);
  const hpke = base64(o.hpke_public_keyset_b64, 1, 1024); const spki = base64(o.signing_public_spki_b64, 1, 128);
  check(sha(hpke) === o.hpke_kid && sha(spki) === o.signing_kid);
  publicHpke(hpke); signingKey(o as unknown as Peer); return o as unknown as Peer;
}
