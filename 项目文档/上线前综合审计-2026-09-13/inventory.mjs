import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
const out = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(out, '../..');
const files = execFileSync('git', ['-c', 'core.quotepath=false', 'ls-files', '-co', '--exclude-standard', '-z'], {cwd:root, encoding:'utf8'}).split('\0').filter(Boolean).filter(p=> !p.startsWith('.claude/') && !p.startsWith('项目文档/上线前综合审计-2026-09-13/'));
const source = [...new Set(files)].filter(p=> /\.(java|js|ts|vue|wxml|scss|wxss|sql)$/.test(p) && fs.existsSync(path.join(root,p)));
const records = source.map(p=> ({path:p, lines:fs.readFileSync(path.join(root,p),'utf8').split(/\r?\n/).length, bytes:fs.statSync(path.join(root,p)).size}));
const groups = {
  miniOwn: r=>r.path.startsWith('前端程序/miniprogram/')&&!r.path.includes('/components/tdesign-miniprogram/'),
  adminBusiness:r=>/^管理后台\/src\/(views|api)\/zs\//.test(r.path),
  backendBusiness:r=>/^后端程序\/yudao-module-(identity|commerce|design|ai-orchestration)\/src\/main\//.test(r.path),
  backendInfra:r=>r.path.startsWith('后端程序/yudao-module-infra/src/main/')&&r.path.includes('/zhongshu/'),
  backendServer:r=>r.path.startsWith('后端程序/yudao-server/src/main/')
};
const metrics=Object.fromEntries(Object.entries(groups).map(([k,fn])=>{const a=records.filter(fn);return [k,{files:a.length,lines:a.reduce((s,r)=>s+r.lines,0),largest:[...a].sort((a,b)=>b.lines-a.lines).slice(0,12)}]}));
const controllers=records.filter(r=>r.path.endsWith('Controller.java')&&(groups.backendBusiness(r)||groups.backendInfra(r)));
const endpoints=[];
for(const r of controllers){
 const txt=fs.readFileSync(path.join(root,r.path),'utf8'), ls=txt.split(/\r?\n/);
 const classIndex=ls.findIndex(l=>/public class /.test(l));
 const base=ls.slice(0,classIndex).join('\n').match(/@RequestMapping\("([^"]+)"\)/)?.[1]??'';
 for(let i=classIndex+1;i<ls.length;i++){
  const m=ls[i].match(/@(Get|Post|Put|Patch|Delete|Request)Mapping(?:\((.*)\))?/);
  if(!m)continue;
  const route=(m[2]??'').match(/"([^"]*)"/)?.[1]??'';
  const context=ls.slice(Math.max(classIndex+1,i-3),Math.min(ls.length,i+12)).join('\n');
  endpoints.push({method:m[1].toUpperCase(),route:base+route,path:r.path,line:i+1,security:context.match(/@PreAuthorize\([^\n]+/)?.[0]??(context.includes('@PermitAll')?'@PermitAll':'see class/service security')});
 }
}
const data={generatedAt:new Date().toISOString(),head:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),metrics,controllerFiles:controllers.length,endpoints,scope:'Current working tree; excludes .claude worktrees, dependencies, generated files and vendored Mini Program components; source physical lines, not coverage.'};
fs.writeFileSync(path.join(out,'inventory.json'),JSON.stringify(data,null,2)+'\n');
fs.writeFileSync(path.join(out,'接口清单.md'),'# 当前工作树业务接口清单\n\n自动提取 Spring Controller 映射，不等同于已通过 HTTP 验收；权限列只作定位。类级安全、服务内鉴权须结合源码。\n\n|方法|路径|代码位置|\n|---|---|---|\n'+endpoints.map(e=>`|${e.method}|\`${e.route}\`|${e.path}:${e.line}|`).join('\n')+'\n');
console.log(JSON.stringify({metrics,controllerFiles:controllers.length,endpoints:endpoints.length},null,2));
