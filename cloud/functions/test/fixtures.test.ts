import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {base64, canonical, context, envelope, sha, signature, signatureInput, signingKey, strictJson} from '../src/validation';
import {registration} from '../src/pairs';

test('published canonical/signature vector is independently verified, not regenerated during tests', () => {
  const f = JSON.parse(readFileSync(resolve(__dirname, '../../../../protocol/v2/fixtures/signature-canonical.json'), 'utf8'));
  const e = envelope(strictJson(canonical(f.envelope)));
  assert.equal(canonical(e).toString('hex'), f.canonical_hex); assert.equal(context(e).toString('hex'), f.context_hex);
  assert.equal(signatureInput(e).toString('hex'), f.signature_input_hex); assert.equal(sha(canonical(e)), f.envelope_sha256);
  const pair = registration(f.request, 'guardian', 1000000);
  signature(signingKey(pair.child), signatureInput(e), base64(e.signature_b64, 8, 72));
});
