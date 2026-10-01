const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'); const path = require('node:path'); const vm = require('node:vm');
const tick = () => new Promise(resolve => setImmediate(resolve));
function page() {
  const requests=[];const images=[];const session={value:'fixture'};let definition;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/pages/library/index.js'),'utf8'),{
    require:name=>({'../../utils/access':{protectedPage:value=>{definition=value;}},
      '../../utils/api':{listCases:params=>new Promise(resolve=>requests.push({params,resolve}))},
      '../../utils/assets':{fetchAssetDataUrl:id=>new Promise(resolve=>images.push({id,resolve}))},
      '../../utils/format':{styleLabel:value=>value},'../../utils/request':{getToken:()=>session.value,isSameSession:value=>value===session.value}})[name],wx:{showToast() {}}
  });
  const instance={...definition,data:structuredClone(definition.data),setData(patch){for(const[key,value]of Object.entries(patch)){
    if(key.includes('[')){const match=key.match(/^cases\[(\d+)\]\.(.*)$/);this.data.cases[Number(match[1])][match[2]]=value;}else this.data[key]=value;
  }}};
  return {page:instance,requests,images,session};
}
const row=(id,style='MODERN')=>({caseId:id,title:id,coverAssetId:'asset-'+id,styleCode:style});
test('last filter reset wins even while old source/page is loading',async()=>{
  const e=page();e.page.loadPage(true);e.page.selectSource({currentTarget:{dataset:{index:1}}});
  assert.equal(e.requests.length,2);assert.equal(e.requests[1].params.sourceType,'AI');
  e.requests[1].resolve({list:[row('ai')],nextCursor:null});await tick();e.requests[0].resolve({list:[row('company')],nextCursor:'old'});await tick();
  assert.equal(e.page.data.cases[0].id,'ai');assert.equal(e.page.data.hasMore,false);assert.equal(e.page.data.loading,false);
});
test('normal append keeps earlier-page in-flight covers; reset invalidates their generation',async()=>{
  const e=page();e.page.loadPage(true);e.requests[0].resolve({list:[row('first')],nextCursor:'page2'});await tick();
  e.page.loadPage(false);e.requests[1].resolve({list:[row('second')],nextCursor:null});await tick();
  e.images[0].resolve('first-cover');e.images[1].resolve('second-cover');await tick();
  assert.equal(e.page.data.cases[0].image,'first-cover');assert.equal(e.page.data.cases[1].image,'second-cover');
  e.page.loadPage(true);e.requests[2].resolve({list:[row('third')],nextCursor:null});await tick();
  e.page.onFilterChange({currentTarget:{dataset:{key:'styleCode',value:'CHINESE'}}});
  e.images[2].resolve('stale-third-cover');e.requests[3].resolve({list:[row('last','CHINESE')],nextCursor:null});await tick();
  assert.equal(e.page.data.cases[0].id,'last');assert.equal(e.page.data.cases[0].image,'');
});
test('another account or unloaded page cannot receive a pending list',async()=>{
  for(const unloaded of [false,true]){const e=page();e.page.loadPage(true);if(unloaded)e.page.onUnload();else e.session.value='different';
    e.requests[0].resolve({list:[row('stale')],nextCursor:null});await tick();assert.equal(e.page.data.cases.length,0);}
});
