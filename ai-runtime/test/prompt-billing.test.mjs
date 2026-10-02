import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Provider, Journal } from '../src/runtime.mjs';
const job = { jobId: '301', phase: 'FLAT', payload: { requestedCount: 1 } };
const input = { phase: 'FLAT', requirements: {} };
function setup(limit=3, fetcher) {
  const dir=mkdtempSync(join(tmpdir(),'prompt-billing-'));
  const journal=new Journal(dir,limit),events=[];
  let supplier=0;
  const provider=new Provider({base:'https://fixture.invalid',model:'fixture',temperature:0,key:'fake-key'},journal,async()=>{
    supplier++;return fetcher ? fetcher() : new Response(JSON.stringify({choices:[{message:{content:'plan'}}]}));
  });
  const hooks={reserve:async()=>events.push('reserve'),dispatch:async()=>events.push('dispatch'),confirm:async()=>events.push('confirm'),unknown:async()=>events.push('unknown'),notSent:async()=>events.push('not-sent')};
  return {journal,provider,events,hooks,supplier:()=>supplier,clean:()=>rmSync(dir,{recursive:true,force:true})};
}
test('exhausted daily quota produces zero supplier requests and zero money events',async()=>{
  const s=setup(1);try{s.journal.reserve();await assert.rejects(s.provider.plan(job,input,[],new AbortController().signal,s.hooks),/DAILY_CALL_LIMIT/);assert.equal(s.supplier(),0);assert.deepEqual(s.events,[]);}finally{s.clean();}
});
test('existing uncertain journal never reserves or repeats a supplier call',async()=>{
  const s=setup();try{s.journal.write('plan-301',{state:'started'});await assert.rejects(s.provider.plan(job,input,[],new AbortController().signal,s.hooks),/OUTCOME_UNCERTAIN/);assert.equal(s.supplier(),0);assert.deepEqual(s.events,[]);}finally{s.clean();}
});
test('cancel after reservation compensates without dispatching',async()=>{
  const s=setup(),controller=new AbortController();try{s.hooks.reserve=async()=>{s.events.push('reserve');controller.abort();};await assert.rejects(s.provider.plan(job,input,[],controller.signal,s.hooks));assert.equal(s.supplier(),0);assert.deepEqual(s.events,['reserve','not-sent']);}finally{s.clean();}
});
test('lost dispatch acknowledgement compensates because supplier was never invoked',async()=>{
  const s=setup();try{s.hooks.dispatch=async()=>{s.events.push('dispatch');throw Error('core acknowledgement lost');};await assert.rejects(s.provider.plan(job,input,[],new AbortController().signal,s.hooks));assert.equal(s.supplier(),0);assert.deepEqual(s.events,['reserve','dispatch','not-sent']);}finally{s.clean();}
});
test('supplier response uncertainty retains reconciliation state and cannot repeat',async()=>{
  const s=setup(3,()=>{throw Error('supplier response lost');});try{await assert.rejects(s.provider.plan(job,input,[],new AbortController().signal,s.hooks));assert.equal(s.supplier(),1);assert.deepEqual(s.events,['reserve','dispatch','unknown']);await assert.rejects(s.provider.plan(job,input,[],new AbortController().signal,s.hooks),/OUTCOME_UNCERTAIN/);assert.equal(s.supplier(),1);}finally{s.clean();}
});
test('received result survives confirmation loss: replay confirms only and calls supplier once',async()=>{
  const s=setup();let fail=true;try{s.hooks.confirm=async()=>{s.events.push('confirm');if(fail)throw Error('acknowledgement lost');};await assert.rejects(s.provider.plan(job,input,[],new AbortController().signal,s.hooks));assert.equal(s.journal.read('plan-301').state,'received');fail=false;assert.equal(await s.provider.plan(job,input,[],new AbortController().signal,s.hooks),'plan');assert.equal(s.supplier(),1);assert.equal(s.journal.read('plan-301').state,'completed');assert.equal(await s.provider.plan(job,input,[],new AbortController().signal,s.hooks),'plan');assert.equal(s.supplier(),1);}finally{s.clean();}
});
