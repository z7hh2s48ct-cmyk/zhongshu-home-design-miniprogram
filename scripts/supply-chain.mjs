#!/usr/bin/env node
// Local scans only. No source, dependency graph or binary is uploaded to a scanning service.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync,execFileSync } from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const out=path.join(root,'artifacts','sbom');fs.mkdirSync(out,{recursive:true});
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
const scanner='aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969';
const run=(args)=>{const result=spawnSync('docker',args,{cwd:root,stdio:'inherit',shell:false});if(result.error)throw result.error;if(result.status!==0)throw Error(`DOCKER_EXIT_${result.status}`);};
function files(dir) {return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(dir,e.name)):[path.join(dir,e.name)]).sort();}
const vendored=path.join(root,'前端程序','miniprogram','components','tdesign-miniprogram');
execFileSync(process.execPath,[path.join(root,'scripts/check-vendored.mjs')],{stdio:'inherit'});
const vendorLock=JSON.parse(fs.readFileSync(path.join(root,'前端程序/vendor/tdesign-lock.json'),'utf8'));
const components=files(vendored).map(file=>({type:'file',name:path.relative(root,file).replaceAll('\\','/'),
  'bom-ref':path.relative(root,file).replaceAll('\\','/'),hashes:[{alg:'SHA-256',content:digest(fs.readFileSync(file))}]}));
components.unshift({type:'library',name:vendorLock.name,version:vendorLock.version,'bom-ref':`pkg:npm/${vendorLock.name}@${vendorLock.version}`,
  purl:`pkg:npm/${vendorLock.name}@${vendorLock.version}`,licenses:[{license:{id:'MIT'}}],externalReferences:[{type:'distribution',url:vendorLock.source}]});
fs.writeFileSync(path.join(out,'mini-vendored-sbom.json'),JSON.stringify({bomFormat:'CycloneDX',specVersion:'1.6',version:1,
  metadata:{timestamp:new Date().toISOString(),component:{type:'application',name:'zhongshu-mini-vendored'},
    properties:[{name:'zhongshu:provenance',value:`Official npm tarball integrity and subset hashes verified; ${vendorLock.scope}`}]},components},null,2)+'\n');
if(process.argv.includes('--mini-only')) {console.log(`Vendored inventory: ${components.length-1} files; TDesign ${vendorLock.version}.`);process.exit(0);}

const jar=path.join(root,'后端程序','yudao-server','target','yudao-server.jar');
if(!fs.existsSync(jar))throw Error('Build the current backend JAR before scanning.');
run(['build','--tag','zhongshu-ai-runtime:sbom','ai-runtime']);
run(['image','save','--output',path.join(out,'runtime-image.tar'),'zhongshu-ai-runtime:sbom']);
const common=['run','--rm','-e','TRIVY_SKIP_VERSION_CHECK=true','--mount',`type=bind,source=${out},target=/out`,'--mount','type=volume,source=zs-trivy-cache,target=/root/.cache/trivy'];
run([...common,'--mount',`type=bind,source=${path.join(root,'管理后台')},target=/scan,readonly`,scanner,'fs','--scanners','vuln',
  '--skip-dirs','/scan/node_modules','--skip-dirs','/scan/dist','--format','cyclonedx','--output','/out/admin-sbom.json','--no-progress','/scan']);
// Trivy 0.74 fs disables individual-package analyzers. rootfs is required for nested JARs.
run([...common,'--mount',`type=bind,source=${jar},target=/scan/backend.jar,readonly`,scanner,'rootfs','--scanners','vuln',
  '--format','cyclonedx','--output','/out/backend-sbom.json','--no-progress','/scan']);
run([...common,scanner,'image','--input','/out/runtime-image.tar','--scanners','vuln','--format','cyclonedx','--output','/out/runtime-sbom.json','--no-progress']);
run([...common,scanner,'sbom','--scanners','vuln','--format','cyclonedx','--output','/out/mini-sbom.json','--no-progress','/out/mini-vendored-sbom.json']);
const summary={createdAt:new Date().toISOString(),scanner,backendSha256:digest(fs.readFileSync(jar)),miniProvenance:`tdesign-miniprogram@${vendorLock.version}; bundled tslib separate version not inferred`,reports:[]};
for(const name of ['mini','admin','backend','runtime']) {
  const filename=`${name}-sbom.json`,bytes=fs.readFileSync(path.join(out,filename)),bom=JSON.parse(bytes);
  if(!bom.components?.length)throw Error(`EMPTY_COMPONENT_INVENTORY_${name}: scanner coverage failed, not a clean scan`);
  const vulnerabilities=bom.vulnerabilities||[];
  summary.reports.push({name,file:filename,sha256:digest(bytes),components:bom.components?.length||0,
    vulnerabilities:vulnerabilities.length,highOrCritical:vulnerabilities.filter(v=>v.ratings?.some(r=>['high','critical'].includes(r.severity))).map(v=>v.id)});
}
fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify(summary,null,2)+'\n');console.log(JSON.stringify(summary,null,2));
if(summary.reports.some(r=>r.highOrCritical.length))process.exitCode=1;
