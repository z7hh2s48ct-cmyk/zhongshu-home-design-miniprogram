const test=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');const vm=require('node:vm');
test('logical page guard survives three real refreshSession rotations and rejects a new login',async()=>{
  const storage=new Map();let round=0;let expire=false;const module={exports:{}};
  const wx={getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,value),removeStorageSync:key=>storage.delete(key),
    request(options){if(options.url.endsWith('/token-refresh')){round++;options.success({statusCode:200,data:{code:0,data:{accessToken:'fixture-'+round,refreshToken:'refresh-'+round,restricted:false}}});}
      else if(expire){expire=false;options.success({statusCode:401,data:{code:401}});}else options.success({statusCode:200,data:{code:0,data:{ok:true}}});},
    redirectTo(){},navigateTo(){},reLaunch(){},showToast(){}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/utils/request.js'),'utf8'),{module,require:name=>{assert.equal(name,'./config');return{apiBase:'https://fixture.invalid',tenantId:1};},wx,getApp:()=>({globalData:{}})});
  const http=module.exports;http.setTokens('fixture-0','refresh-0');const page=http.captureSession();
  for(let i=0;i<3;i++){expire=true;await http.get('/app-api/design/v1/fixture');assert.equal(http.isSameSession(page),true);}
  assert.equal(round,3);http.setTokens('different-login','different-refresh');assert.equal(http.isSameSession(page),false);
});
