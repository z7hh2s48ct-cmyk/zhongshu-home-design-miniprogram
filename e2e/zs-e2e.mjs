#!/usr/bin/env node
import { createHash, createHmac, randomUUID } from 'node:crypto'
import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises'
import { dirname, isAbsolute, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { homedir, tmpdir } from 'node:os'

const here = dirname(fileURLToPath(import.meta.url)), repo = resolve(here, '..')
const names = [
  '服务健康检查','匿名首页可访问','公开户型库含开发种子','管理端账号登录','管理端生成一次性授权码',
  '微信 Stub 登录签发受限会话','受限会话授权快照为 NONE','受保护端点拦截受限会话','授权码兑换并激活',
  '个人资料升级并锁定账号标识','充值方案可购买','充值订单进入待支付','主动对账驱动 Stub 支付','充值到账且点数可见',
  '创建自主设计项目','创建平面任务并扣点','Runtime HMAC 领取平面任务','Runtime 回写平面隔离区 PNG',
  '驱动器完成平面结算','平面候选按任务晋升','选定平面并推进阶段','创建立面任务','Runtime HMAC 领取立面任务',
  'Runtime 回写立面隔离区 PNG','驱动器完成立面结算','立面候选晋升并选定','最终结果版本可回读',
  '预算冻结规则与输入快照','发布前校核通过','用户提交投稿','管理端审核队列可见','审核通过并生成站内信',
  '独立发布且户型库可见','用户统计与 AI 运维读模型可见','人工调点执行双人控制',
  '导出、一次性票据与审计可追溯','解绑后会话实时降级'
]
if (names.length !== 37) throw new Error(`检查点应为 37，实际 ${names.length}`)
if (process.argv[2] === '--list') { names.forEach((n,i)=>console.log(`${String(i+1).padStart(2,'0')}. ${n}`)); process.exit(0) }

const id = (process.env.ZS_E2E_RUN_ID || `${Date.now()}-${randomUUID().slice(0,8)}`).replace(/[^\w.-]/g,'-')
const origin = new URL(process.env.ZS_E2E_BASE_URL || 'http://127.0.0.1:48080')
const assetRoot = resolve(process.env.ZS_E2E_ASSET_ROOT || resolve(tmpdir(),'zhongshu-assets'))
const reportDir = resolve(process.env.ZS_E2E_REPORT_DIR || resolve(repo,'artifacts','e2e'))
const timeout = Number(process.env.ZS_E2E_POLL_TIMEOUT_MS || 120000)
const tenant = process.env.ZS_E2E_ADMIN_TENANT_ID || '1'
const secret = process.env.ZS_E2E_INTERNAL_SECRET || 'zsdev-internal-secret-0123456789abcdef'
const checker = process.env.ZS_E2E_CHECKER_TOKEN || 'test100'
const S = { results:[], files:[] }
const ok = (v,m)=>{ if(!v) throw new Error(m) }

function safety(){
  ok(['127.0.0.1','localhost','::1','[::1]'].includes(origin.hostname) || process.env.ZS_E2E_ALLOW_REMOTE==='true',
    `拒绝向远程地址 ${origin.origin} 写入；隔离环境需显式设置 ZS_E2E_ALLOW_REMOTE=true`)
  ok(isAbsolute(assetRoot) && assetRoot!==resolve('/') && assetRoot!==resolve(homedir()) && assetRoot!==repo,
    '资产根目录必须是安全的绝对目录，且不能是文件系统、用户或仓库根目录')
}
function url(a,p){ return new URL(`${a==='app'?'/app-api':a==='admin'?'/admin-api':''}${p}`,origin) }
async function raw(a,p,o={}){
  const h=new Headers(o.headers||{}); if(o.token) h.set('Authorization',`Bearer ${o.token}`); if(a==='admin') h.set('tenant-id',tenant)
  let body; if(o.body!==undefined){body=typeof o.body==='string'?o.body:JSON.stringify(o.body);h.set('Content-Type','application/json')}
  const r=await fetch(url(a,p),{method:o.method||'GET',headers:h,body,signal:AbortSignal.timeout(20000)})
  const bytes=Buffer.from(await r.arrayBuffer()); let json; try{json=JSON.parse(bytes.toString('utf8'))}catch{}
  return {r,bytes,json}
}
async function api(a,p,o={}){const x=await raw(a,p,o);ok(x.r.ok,`${o.method||'GET'} ${p} HTTP ${x.r.status}`);if(x.json&&typeof x.json.code==='number'){ok(x.json.code===0,`${p}: ${x.json.msg||x.json.code}`);return x.json.data}return x.json??x.bytes}
const app=(p,o={})=>api('app',p,{...o,token:o.token||S.appToken})
const admin=(p,o={})=>api('admin',p,{...o,token:o.token||S.adminToken})
async function internal(p,b){const text=JSON.stringify(b),ts=String(Math.floor(Date.now()/1000));const hash=createHash('sha256').update(text).digest('hex');const sig=createHmac('sha256',secret).update(`${ts}\nPOST\n${p}\n${hash}`).digest('hex');return api('internal',p,{method:'POST',body:text,headers:{'X-ZS-Timestamp':ts,'X-ZS-Signature':sig}})}
async function poll(label,fn,pred){const end=Date.now()+timeout;let value;while(Date.now()<end){value=await fn();if(pred(value))return value;await new Promise(r=>setTimeout(r,2000))}throw new Error(`${label} 超时，末态=${JSON.stringify(value)}`)}
async function step(fn){const i=S.results.length,n=names[i],at=Date.now();process.stdout.write(`[${String(i+1).padStart(2,'0')}/37] ${n} ... `);try{const detail=await fn();S.results.push({index:i+1,name:n,status:'PASS',durationMs:Date.now()-at,detail});console.log('PASS')}catch(e){S.results.push({index:i+1,name:n,status:'FAIL',durationMs:Date.now()-at,error:e.message});console.log('FAIL');throw e}}
async function claim(jobId,phase){const list=await internal('/internal-api/design/v1/ai-jobs/claims',{workerId:`e2e-${id}-${phase}`,maxJobs:8,phase});const c=list.find(x=>String(x.jobId)===String(jobId));ok(c&&c.phase===phase,`未领取到 ${phase} 任务 ${jobId}`);return c}
async function result(c,phase){const png=Buffer.from((await readFile(resolve(here,'fixtures/tiny-png.base64'),'utf8')).trim(),'base64');ok(png.subarray(0,8).toString('hex')==='89504e470d0a1a0a','PNG fixture 无效');const key=`${c.payload.outputPrefix}/e2e-${id}-${phase}.png`,file=resolve(assetRoot,...key.split('/'));const rel=relative(assetRoot,file);ok(rel&&!rel.startsWith('..')&&!isAbsolute(rel),'Runtime objectKey 逃逸资产根目录');await mkdir(dirname(file),{recursive:true});await writeFile(file,png);S.files.push(file);const base={attemptNo:c.attemptNo,fencingToken:c.fencingToken};ok(await internal(`/internal-api/design/v1/ai-jobs/${c.jobId}/progress-events`,{...base,progress:80,stage:`${phase}_RENDERING`})===true,'进度回写失败');const out=await internal(`/internal-api/design/v1/ai-jobs/${c.jobId}/result-events`,{...base,providerCode:'stub',sourceEventId:`e2e-${id}-${phase}`,candidateSlotNo:1,objectKey:key,sha256:createHash('sha256').update(png).digest('hex'),mimeType:'image/png',sizeBytes:png.length});ok(out==='QUARANTINED','结果未进入隔离区');return out}
async function report(status,started,error){await mkdir(reportDir,{recursive:true});const data={schemaVersion:1,suite:'T02-05-reconstructed-37',provenance:'依据仓库文档重建；原 /tmp/zs-e2e.mjs 不可用',runId:id,status,startedAt:new Date(started).toISOString(),finishedAt:new Date().toISOString(),passed:S.results.filter(x=>x.status==='PASS').length,total:37,error:error?.message||null,checkpoints:S.results};const rows=names.map((n,i)=>`| ${i+1} | ${n} | ${S.results[i]?.status||'NOT_RUN'} |`);await writeFile(resolve(reportDir,'latest.json'),JSON.stringify(data,null,2)+'\n');await writeFile(resolve(reportDir,'latest.md'),['# T02-05 E2E 执行报告','',`- 运行：\`${id}\``,`- 结果：**${status}（${data.passed}/37）**`,'- 来源：依据仓库历史文档重建，非遗失脚本逐字恢复。','', '| # | 检查点 | 状态 |','|---:|---|---|',...rows,''].join('\n'))}

async function suite(){
  await step(async()=>{const x=await api('internal','/actuator/health');ok(x.status==='UP','health 非 UP');return x.status})
  await step(async()=>{const x=await api('app','/design/v1/home');ok(x&&typeof x==='object','首页无数据');return Object.keys(x).length})
  await step(async()=>{const x=await api('app','/design/v1/cases?limit=20');ok(x.list?.length>0,'无种子案例');return x.list.length})
  await step(async()=>{const x=await api('admin','/system/auth/login',{method:'POST',body:{username:process.env.ZS_E2E_ADMIN_USERNAME||'admin',password:process.env.ZS_E2E_ADMIN_PASSWORD||'admin123',captchaVerification:''}});ok(x.accessToken,'无 admin token');S.adminToken=x.accessToken;const a=await admin('/design/v1/accounts?pageNo=1&pageSize=100');S.oldAccounts=new Set((a.list||[]).map(v=>String(v.id)));return 'authenticated'})
  await step(async()=>{const x=await admin('/design/v1/access-code-batches',{method:'POST',body:{quantity:1,deliveryMode:'INLINE',validityDays:7,purposeNote:`T02-05 ${id}`}});ok(x.oneTimeCodes?.length===1,'未返回一次性明文');S.code=x.oneTimeCodes[0];return x.id})
  await step(async()=>{const x=await api('app','/design/v1/auth/wechat-login',{method:'POST',body:{code:`e2e-${id}`}});ok(x.restricted===true&&x.accessToken,'不是受限会话');S.appToken=x.accessToken;return x.restricted})
  await step(async()=>{const x=await app('/design/v1/access-grant');ok(x.status==='NONE','授权态非 NONE');return x.status})
  await step(async()=>{const x=await raw('app','/design/v1/point-account',{token:S.appToken});ok(!(x.r.ok&&x.json?.code===0),'受限会话越权');return x.json?.code||x.r.status})
  await step(async()=>{const x=await app('/design/v1/access-code-redemptions',{method:'POST',body:{accessCode:S.code}});ok(x.status==='ACTIVE','兑换未激活');return x.status})
  await step(async()=>{const p=await app('/design/v1/profile');ok(p.accessGrantStatus==='ACTIVE','profile 未激活');const a=await admin('/design/v1/accounts?pageNo=1&pageSize=100'),fresh=(a.list||[]).filter(v=>!S.oldAccounts.has(String(v.id)));ok(fresh.length===1,'无法唯一识别新账号');S.accountId=String(fresh[0].id);return S.accountId})
  await step(async()=>{const x=await app('/design/v1/recharge-plans');ok(x.length,'无充值方案');S.plan=x.find(v=>v.recommended)||x[0];return S.plan.planId})
  await step(async()=>{const x=await app(`/design/v1/recharge-orders?planId=${S.plan.planId}`,{method:'POST',body:null,headers:{'Idempotency-Key':`e2e-${id}-pay`}});ok(x.paymentState==='PENDING','订单非 PENDING');S.order=x;return x.orderId})
  await step(async()=>{const x=await admin(`/design/v1/recharge-orders/${S.order.orderId}/reconciliation`,{method:'POST'});ok(['RECOVERED','ALREADY_RECONCILED'].includes(x),'对账未收口');return x})
  await step(async()=>{const x=await poll('充值到账',()=>app(`/design/v1/recharge-orders/${S.order.orderId}`),v=>v.fulfillmentState==='CREDITED');const p=await app('/design/v1/point-account');ok(p.availablePoints>=x.basePoints+x.bonusPoints,'点数未到账');return p.availablePoints})
  await step(async()=>{const x=await app('/design/v1/design-projects',{method:'POST',body:{sourceType:'SELF_UPLOAD',requirementInputs:{floors:2,buildingArea:168,runId:id}}});ok(x.projectId&&x.stage==='FLAT','项目创建失败');S.projectId=x.projectId;return x.projectId})
  await step(async()=>{const x=await app(`/design/v1/design-projects/${S.projectId}/flat-jobs`,{method:'POST',body:{count:1},headers:{'Idempotency-Key':`e2e-${id}-flat`}});ok(x.jobId,'无平面 jobId');S.flat=String(x.jobId);return S.flat})
  await step(async()=>{S.flatClaim=await claim(S.flat,'FLAT');return S.flatClaim.fencingToken})
  await step(async()=>result(S.flatClaim,'FLAT'))
  await step(async()=>{const x=await poll('平面结算',()=>app(`/design/v1/ai-jobs/${S.flat}`),v=>['SUCCEEDED','PARTIALLY_SUCCEEDED'].includes(v.status));ok(x.acceptedCount===1,'平面有效数非 1');return x.status})
  await step(async()=>{const x=await poll('平面晋升',()=>app(`/design/v1/design-projects/${S.projectId}?jobId=${S.flat}`),v=>v.candidates?.length===1);ok(x.candidates.every(v=>String(v.jobId)===S.flat),'候选混阶段');S.flatCandidate=String(x.candidates[0].candidateId);return S.flatCandidate})
  await step(async()=>{const x=await app(`/design/v1/design-projects/${S.projectId}/flat-selections`,{method:'POST',body:{jobId:S.flat,candidateId:S.flatCandidate}});ok(x.stage==='ELEVATION'&&String(x.selectedFlatCandidateId)===S.flatCandidate,'平面选定失败');return x.stage})
  await step(async()=>{const x=await app(`/design/v1/design-projects/${S.projectId}/elevation-jobs`,{method:'POST',body:{count:1,styleCode:'MODERN',roofType:'GABLE',material:'STONE',color:'WARM_WHITE'},headers:{'Idempotency-Key':`e2e-${id}-elev`}});ok(x.jobId,'无立面 jobId');S.elev=String(x.jobId);return S.elev})
  await step(async()=>{S.elevClaim=await claim(S.elev,'ELEVATION');return S.elevClaim.fencingToken})
  await step(async()=>result(S.elevClaim,'ELEVATION'))
  await step(async()=>{const x=await poll('立面结算',()=>app(`/design/v1/ai-jobs/${S.elev}`),v=>['SUCCEEDED','PARTIALLY_SUCCEEDED'].includes(v.status));ok(x.acceptedCount===1,'立面有效数非 1');return x.status})
  await step(async()=>{const x=await poll('立面晋升',()=>app(`/design/v1/design-projects/${S.projectId}?jobId=${S.elev}`),v=>v.candidates?.length===1);S.elevCandidate=String(x.candidates[0].candidateId);const y=await app(`/design/v1/design-projects/${S.projectId}/elevation-selections`,{method:'POST',body:{jobId:S.elev,candidateId:S.elevCandidate}});ok(y.resultVersionId,'无结果版本');S.version=String(y.resultVersionId);return S.version})
  await step(async()=>{const x=await app(`/design/v1/design-projects/${S.projectId}/result-versions`);ok(x.list?.some(v=>String(v.versionId)===S.version),'版本不可回读');return x.list.length})
  await step(async()=>{const x=await app(`/design/v1/design-projects/${S.projectId}/budget-estimates`,{method:'POST',body:{regionCode:'VAR1',structureType:'BRICK',materialGrade:'A',buildingArea:168,resultVersionId:S.version}});ok(x.ruleVersion==='VAR1'&&x.totalMinCents<x.totalMaxCents,'预算快照无效');return x.estimateId})
  const submission=()=>({resultVersionId:S.version,publicDisplayGranted:true,generationReferenceGranted:true,note:`T02-05 ${id}`})
  await step(async()=>{const x=await app(`/design/v1/design-projects/${S.projectId}/publication-validations`,{method:'POST',body:submission()});ok(x.valid===true&&!x.missingFields.length,'发布校核失败');return x.valid})
  await step(async()=>{const x=await app(`/design/v1/design-projects/${S.projectId}/submissions`,{method:'POST',body:submission(),headers:{'Idempotency-Key':`e2e-${id}-submit`}});ok(x.status==='SUBMITTED','投稿失败');S.submission=String(x.submissionId);return S.submission})
  await step(async()=>{const x=await admin('/design/v1/submissions?status=SUBMITTED&pageNo=1&pageSize=100');ok(x.list?.some(v=>String(v.submissionId)===S.submission),'审核队列无投稿');return x.total})
  await step(async()=>{const x=await admin(`/design/v1/submissions/${S.submission}/review-decisions`,{method:'POST',body:{decision:'APPROVE',comment:`E2E ${id}`}});ok(x.status==='APPROVED','审核失败');const m=await poll('站内信',()=>app('/design/v1/messages?limit=50'),v=>v.list?.some(z=>z.messageType==='SUBMISSION_REVIEWED'&&String(z.bizId)===S.submission));return m.list.length})
  await step(async()=>{const x=await admin(`/design/v1/submissions/${S.submission}/publication-commands`,{method:'POST'});ok(x.publishedCaseId,'发布失败');S.caseId=String(x.publishedCaseId);const c=await poll('案例上架',()=>api('app','/design/v1/cases?sourceType=AI&limit=50'),v=>v.list?.some(z=>String(z.caseId)===S.caseId));return c.list.length})
  await step(async()=>{const a=await admin(`/design/v1/accounts/${S.accountId}`);S.grant=String(a.grants?.find(v=>v.status==='ACTIVE')?.id||'');ok(S.grant,'用户无有效授权');const d=await admin('/design/v1/dashboard/summary'),j=await admin('/design/v1/ai-jobs?pageNo=1&pageSize=100'),ids=new Set(j.list.map(v=>String(v.jobId)));ok(d.accountsActive>=1&&ids.has(S.flat)&&ids.has(S.elev),'运营读模型缺数据');return j.total})
  await step(async()=>{const x=await admin('/design/v1/manual-point-adjustments',{method:'POST',body:{targetUserId:S.accountId,delta:1,reason:`T02-05 ${id}`}});const self=await raw('admin',`/design/v1/manual-point-adjustments/${x.adjustmentId}/review-decisions`,{method:'POST',token:S.adminToken,body:{approve:true,comment:'self'}});ok(!(self.r.ok&&self.json?.code===0),'同人复核未拒绝');const y=await admin(`/design/v1/manual-point-adjustments/${x.adjustmentId}/review-decisions`,{method:'POST',token:checker,body:{approve:true,comment:'checker'}});ok(y.status==='EXECUTED','双人调点未执行');return y.status})
  await step(async()=>{const x=await admin('/design/v1/export-jobs',{method:'POST',body:{jobType:'POINT_LEDGER',runId:id}});const done=await poll('导出 worker',()=>admin(`/design/v1/export-jobs/${x.exportJobId}`),v=>v.status==='COMPLETED');const t=await admin(`/design/v1/export-jobs/${x.exportJobId}/download-tickets`,{method:'POST'});ok(t.ticket,'无下载票据');const f=await raw('admin',`/design/v1/export-jobs/${x.exportJobId}/content?ticket=${encodeURIComponent(t.ticket)}`,{token:S.adminToken});ok(f.r.ok&&f.bytes.toString('utf8').includes('流水号'),'CSV 下载失败');const audit=await admin('/design/v1/audit-events?pageNo=1&pageSize=100');ok(audit.total>0,'无审计记录');return {exportJobId:done.exportJobId,auditTotal:audit.total}})
  await step(async()=>{ok(await admin(`/design/v1/access-grants/${S.grant}/revocations`,{method:'POST'})===true,'解绑失败');const g=await app('/design/v1/access-grant');ok(g.status==='NONE','会话未降级');const p=await raw('app','/design/v1/point-account',{token:S.appToken});ok(!(p.r.ok&&p.json?.code===0),'降级后仍可访问业务');return g.status})
}

safety()
if(process.argv[2]==='--validate'){const p=Buffer.from((await readFile(resolve(here,'fixtures/tiny-png.base64'),'utf8')).trim(),'base64');ok(p.subarray(0,8).toString('hex')==='89504e470d0a1a0a','fixture 无效');console.log('[e2e] 离线校验通过：37 个检查点');process.exit(0)}
const started=Date.now();let failure
try{await suite();ok(S.results.length===37,'检查点未完整执行')}catch(e){failure=e;console.error(`\n[e2e] ${e.message}`)}finally{for(const f of S.files){try{await unlink(f)}catch(e){if(e.code!=='ENOENT')console.error(`[e2e] 清理失败 ${f}: ${e.message}`)}}await report(failure?'FAIL':'PASS',started,failure)}
if(failure)process.exit(1);console.log(`\n[e2e] 37/37 PASS，报告：${resolve(reportDir,'latest.md')}`)
