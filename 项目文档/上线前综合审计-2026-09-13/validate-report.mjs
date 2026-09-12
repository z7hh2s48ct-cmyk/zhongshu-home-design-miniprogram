import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
const out=path.dirname(fileURLToPath(import.meta.url)),root=path.resolve(out,'../..');
const read=n=>JSON.parse(fs.readFileSync(path.join(out,n),'utf8'));
const scope=read('scope-snapshot.json');
const changed=scope.snapshots.filter(s=>!fs.existsSync(path.join(root,s.path))||createHash('sha256').update(fs.readFileSync(path.join(root,s.path))).digest('hex')!==s.sha256).map(s=>s.path);
const currentHead=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();
const currentStatus=execFileSync('git',['-c','core.quotepath=false','status','--short'],{cwd:root,encoding:'utf8'}).split('\n').filter(x=>x&&!x.includes('上线前综合审计-2026-09-13'));
const addedStatus=currentStatus.filter(x=>!scope.status.includes(x));
const removedStatus=scope.status.filter(x=>!currentStatus.includes(x));
const reviewFile=path.join(out,'concurrent-config-review.json');
const review=fs.existsSync(reviewFile)?read('concurrent-config-review.json'):null;
const reviewCurrent=review&&review.files.every(s=>fs.existsSync(path.join(root,s.path))&&createHash('sha256').update(fs.readFileSync(path.join(root,s.path))).digest('hex')===s.sha256);
const documentedChangesOnly=!!reviewCurrent&&changed.every(p=>review.files.some(f=>f.path===p))&&addedStatus.every(s=>review.expectedAddedStatus.includes(s))&&removedStatus.length===0;
const docs=fs.readdirSync(out).filter(n=>n.endsWith('.md'));
let links=0;const badLinks=[];const tableErrors=[];
for(const name of docs){
  const txt=fs.readFileSync(path.join(out,name),'utf8');
  for(const m of txt.matchAll(/\]\(<([^>]+)>\)/g)){links++;const target=m[1].replace(/:\d+$/,'');if(!fs.existsSync(target))badLinks.push({name,target});}
  let columns=null;
  for(const [i,line] of txt.split(/\r?\n/).entries()){
    if(!line.startsWith('|')){columns=null;continue;}
    const n=line.split('|').length;
    if(columns===null)columns=n;else if(n!==columns)tableErrors.push({name,line:i+1,expected:columns,actual:n});
  }
}
const evidence=read('source-evidence.json');
const badLines=evidence.filter(e=>e.line<1||e.line>fs.readFileSync(path.join(root,e.path),'utf8').split(/\r?\n/).length);
const result={validatedAt:new Date().toISOString(),headUnchanged:scope.head===currentHead,sourceFilesChecked:scope.snapshots.length,changedSourceFiles:changed,preexistingStatusUnchanged:addedStatus.length===0&&removedStatus.length===0,documentedConcurrentChangesOnly:documentedChangesOnly,addedStatus,removedStatus,markdownFiles:docs.length,localLinksChecked:links,badLinks,tableErrors,badEvidenceLines:badLines,flowRows:read('业务流程矩阵.json').length,riskRows:read('风险清单.json').length};
fs.writeFileSync(path.join(out,'report-validation.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result,null,2));
if(!result.headUnchanged||((changed.length||!result.preexistingStatusUnchanged)&&!documentedChangesOnly)||badLinks.length||tableErrors.length||badLines.length)process.exitCode=1;
