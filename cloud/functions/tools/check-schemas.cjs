const {readFileSync, readdirSync} = require('node:fs');
const {join, resolve} = require('node:path');
const {createHash} = require('node:crypto');
const Ajv2020 = require('ajv/dist/2020').default;
const dir = resolve(__dirname, '../../../protocol/v2');
const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'));
const files = readdirSync(dir, {recursive: true}).filter(p => p !== 'manifest.json' && !require('node:fs').statSync(join(dir, p)).isDirectory()).sort();
if (JSON.stringify(files) !== JSON.stringify(Object.keys(manifest.files).sort())) throw Error('manifest file set differs');
for (const file of files) {
  const bytes = readFileSync(join(dir, file));
  if (createHash('sha256').update(bytes).digest('hex') !== manifest.files[file]) throw Error(`hash mismatch: ${file}`);
}
const ajv = new Ajv2020({strict: true, allErrors: true});
// Canonical companion conditionals inherit numeric constraints from their enclosing policy.
const companionAjv = new Ajv2020({strict: true, strictTypes: false, allErrors: true});
const validators = Object.fromEntries(files.filter(p => p.endsWith('.schema.json')).map(p => [p,
  (p === 'companion.schema.json' ? companionAjv : ajv).compile(JSON.parse(readFileSync(join(dir, p), 'utf8')))]));
const fixtures = JSON.parse(readFileSync(join(dir, 'fixtures/contracts.json'), 'utf8'));
for (const vector of fixtures) {
  if (!validators[vector.schema]) throw Error('unknown fixture schema');
  const valid = validators[vector.schema](vector.value);
  if (valid !== vector.valid) throw Error(`schema vector mismatch: ${vector.id}: ${JSON.stringify(validators[vector.schema].errors)}`);
}
console.log(`${Object.keys(validators).length} strict draft2020 schemas, ${fixtures.length} fixtures and ${files.length} asset hashes verified`);
const companion = JSON.parse(readFileSync(join(dir, 'companion.manifest.json'), 'utf8'));
for (const [file, hash] of Object.entries(companion.sha256)) {
  if (createHash('sha256').update(readFileSync(join(dir, file))).digest('hex') !== hash) throw Error(`companion hash mismatch: ${file}`);
}
for (const name of ['hello', 'bind', 'bound', 'state', 'decision', 'ack', 'released']) {
  const value = JSON.parse(readFileSync(join(dir, `fixtures/${name}.json`), 'utf8'));
  if (!validators['companion.schema.json'](value)) throw Error(`companion fixture mismatch: ${name}: ${JSON.stringify(validators['companion.schema.json'].errors)}`);
}
const traces = JSON.parse(readFileSync(join(dir, 'fixtures/policy-traces.json'), 'utf8'));
if (traces.contract !== companion.contract || traces.cases.length !== 40) throw Error('canonical policy trace set differs');
console.log('Canonical companion SHA256 manifest, seven wire fixtures and 40 policy traces verified');
