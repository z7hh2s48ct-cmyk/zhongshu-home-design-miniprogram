import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const rootArg=process.argv.indexOf('--root');
if(rootArg>=0&&!process.argv[rootArg+1])throw Error('--root requires a repository directory');
const root=rootArg>=0?path.resolve(process.argv[rootArg+1]):path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const lock=JSON.parse(fs.readFileSync(path.join(root,'前端程序/vendor/tdesign-lock.json'),'utf8'));
const base=path.join(root,'前端程序/miniprogram/components/tdesign-miniprogram');
const walk=dir=>fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.relative(base,path.join(dir,e.name)).replaceAll('\\','/')]);
const actual=walk(base).sort(),expected=lock.files.map(f=>f.path).sort();
if(JSON.stringify(actual)!==JSON.stringify(expected))throw Error('Vendored file set drift: review against official distribution before updating the lock.');
// Git normalizes these text files to LF; an existing Windows worktree may still contain CRLF.
// Normalize only CRLF, without trimming whitespace or ignoring any content edits.
for(const entry of lock.files)if(createHash('sha256').update(fs.readFileSync(path.join(base,entry.path),'utf8').replaceAll('\r\n','\n')).digest('hex')!==entry.sha256)throw Error(`Vendored content drift: ${entry.path}`);
console.log(`Official ${lock.name}@${lock.version} subset: ${expected.length} LF-normalized hashes verified (reviewed local patches recorded in lock).`);
