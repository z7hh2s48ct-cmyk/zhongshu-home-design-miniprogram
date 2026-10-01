const test=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');const path=require('node:path');
const tick=()=>new Promise(r=>setImmediate(r));
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};}
function catalog(region,label){return {regionCode:region,groups:[{itemId:'1',options:[{optionId:'11',label,baselinePriceCents:100,priceSource:'DEFAULT'}]}]};}
function fixture(overrides={}){
 const session={value:'first'};const calls=[];const modals=[];let definition;
 const api={getBudgetRegions:async()=>[{code:'A',name:'A'},{code:'B',name:'B'}],getMyPrices:async region=>catalog(region,region),
  setMyPrice:async(...args)=>{calls.push(['set',...args]);},resetMyPrice:async(...args)=>{calls.push(['reset',...args]);},...overrides};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/pages/budget/prices.js'),'utf8'),{
  require:name=>name.endsWith('/access')?{protectedPage:p=>{definition=p;}}:name.endsWith('/api')?api:
   {captureSession:()=>session.value,isSameSession:value=>value===session.value},
  wx:{showToast:value=>calls.push(['toast',value.title]),showModal:value=>modals.push(value)}
 });
 const page={...definition,data:structuredClone(definition.data),setData(values){Object.assign(this.data,values);}};page.onLoad({});
 return {page,session,calls,modals};
}
const event=id=>({currentTarget:{dataset:{optionId:id}}});
test('region A response cannot overwrite B and last same-region request wins',async()=>{
 const pending=[];const e=fixture({getMyPrices:region=>{const d=deferred();pending.push({region,...d});return d.promise;}});await tick();
 e.page.onRegionChange({detail:{value:1}});pending[1].resolve(catalog('B','current B'));await tick();pending[0].resolve(catalog('A','stale A'));await tick();
 assert.equal(e.page.data.regionCode,'B');assert.equal(e.page.data.groups[0].options[0].label,'current B');
 e.page.loadPrices('B');e.page.loadPrices('B');pending[3].resolve(catalog('B','latest'));await tick();pending[2].resolve(catalog('B','older'));await tick();
 assert.equal(e.page.data.groups[0].options[0].label,'latest');assert.equal(e.page.data.loading,false);
});
test('save freezes region and old success cannot clear the newer regions editor',async()=>{
 const d=deferred(),writes=[];const e=fixture({setMyPrice:(...args)=>{writes.push(args);return d.promise;}});await tick();await tick();
 e.page.startEdit(event('11'));e.page.onPriceInput({detail:{value:'2'}});const save=e.page.confirmEdit(event('11'));
 e.page.onRegionChange({detail:{value:1}});await tick();e.page.startEdit(event('11'));e.page.onPriceInput({detail:{value:'3'}});
 d.resolve({});await save;assert.equal(writes[0][1],'A');assert.equal(writes[0][2],200);
 assert.equal(e.page.data.regionCode,'B');assert.equal(e.page.data.editOptionId,'11');assert.equal(e.page.data.inputValue,'3');
 assert.equal(e.calls.filter(c=>c[0]==='toast').length,0);
});
test('reset modal cannot submit after changing region and an unloaded/account-changed read is ignored',async()=>{
 const e=fixture();await tick();await tick();e.page.reset(event('11'));e.page.onRegionChange({detail:{value:1}});
 e.modals[0].success({confirm:true});await tick();assert.equal(e.calls.filter(c=>c[0]==='reset').length,0);
 for(const change of ['session','unload']){
  const d=deferred();const x=fixture({getMyPrices:()=>d.promise});await tick();
  if(change==='session')x.session.value='second';else x.page.onUnload();
  d.resolve(catalog('A','must not render'));await tick();assert.equal(x.page.data.groups.length,0);
 }
});
test('wrong-region API response reports an error and exits loading',async()=>{
 const e=fixture({getMyPrices:async()=>catalog('B','wrong')});await tick();await tick();
 assert.equal(e.page.data.loading,false);assert.match(e.page.data.error,/地区不一致/);assert.equal(e.page.data.groups.length,0);
});
