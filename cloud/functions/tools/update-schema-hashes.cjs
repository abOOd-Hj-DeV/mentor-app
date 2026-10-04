const {readFileSync, writeFileSync, readdirSync, statSync} = require('node:fs');
const {join, resolve} = require('node:path');
const {createHash} = require('node:crypto');

const root = resolve(__dirname, '../../../protocol/v2');
const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
const files = readdirSync(root, {recursive: true})
  .filter(file => file !== 'manifest.json' && statSync(join(root, file)).isFile()).sort();
manifest.files = Object.fromEntries(files.map(file => [file,
  createHash('sha256').update(readFileSync(join(root, file))).digest('hex')]));
writeFileSync(join(root, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
