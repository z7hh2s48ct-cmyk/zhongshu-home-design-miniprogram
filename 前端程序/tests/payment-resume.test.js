const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
function fixture() {
  let definition; let token='owner-A'; const calls=[]; let finish;
  const api={getPayParams: id=>{calls.push(['params',id]);return new Promise(r=>{finish=r;});}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/pages/payment/success.js'),'utf8'),{
    require: name=>name.endsWith('/access')?{protectedPage:p=>{definition=p;}}:name.endsWith('/api')?api:name.endsWith('/request')?{getToken:()=>token}:{errorText:e=>e.message},
    wx:{requestPayment:p=>calls.push(['pay',p])},clearTimeout,setTimeout
  });
  const page={...definition,data:{...definition.data,canPay:true,orderId:'123'},setData:p=>Object.assign(page.data,p),_seq:0,_token:token};
  return {page,calls,resolve:p=>finish(p),changeOwner:()=>{token='owner-B';}};
}
const params={timeStamp:'1',nonceStr:'nonce',package:'prepay_id=original',signType:'RSA',paySign:'test-signature'};
test('resume uses original order params and blocks duplicate taps',async()=>{
  const f=fixture();const work=f.page.resumePayment();f.page.resumePayment();assert.equal(f.calls.length,1);
  f.resolve(params);await work;assert.deepEqual(f.calls[0],['params','123']);assert.equal(f.calls[1][0],'pay');
});
test('switching account while loading params cannot launch payment',async()=>{
  const f=fixture();const work=f.page.resumePayment();f.changeOwner();f.resolve(params);await work;assert.equal(f.calls.length,1);
});
test('incomplete params remain a retryable error rather than a new charge',async()=>{
  const f=fixture();const work=f.page.resumePayment();f.resolve({state:'PENDING'});await work;
  assert.equal(f.calls.length,1);assert.equal(f.page.data.paying,false);assert.match(f.page.data.error,/支付参数/);
});
