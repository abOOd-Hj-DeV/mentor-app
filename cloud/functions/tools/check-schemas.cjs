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
const validators = Object.fromEntries(files.filter(p => p.endsWith('.schema.json')).map(p => [p, ajv.compile(JSON.parse(readFileSync(join(dir, p), 'utf8')))]));
const fixtures = JSON.parse(readFileSync(join(dir, 'fixtures/contracts.json'), 'utf8'));
for (const vector of fixtures) {
  if (!validators[vector.schema]) throw Error('unknown fixture schema');
  const valid = validators[vector.schema](vector.value);
  if (valid !== vector.valid) throw Error(`schema vector mismatch: ${vector.id}: ${JSON.stringify(validators[vector.schema].errors)}`);
}
console.log(`${Object.keys(validators).length} strict draft2020 schemas, ${fixtures.length} fixtures and ${files.length} asset hashes verified`);
