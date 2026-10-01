const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/assets/js/registry.js'), 'utf8');
function harness(api) {
    const fields = new Map();
    const get = id => { if (!fields.has(id)) fields.set(id, {value:'', textContent:'', checked:false}); return fields.get(id); };
    const messages=[];
    const context = vm.createContext({document:{getElementById:get}, curInstance:{instanceId:'local'}, api, showSnackbar:m=>messages.push(m), writeClipboard:async()=>{}});
    vm.runInContext(source, context);
    return {context, get, messages};
}
test('registers entered values immediately without applying unrelated business config', async () => {
    const calls=[];
    const h=harness(async (url,options)=>{calls.push({url,body:options&&JSON.parse(options.body)});return {ok:true,json:async()=>({clientEnabled:true,centerUrl:'10.0.0.1:10528',clientCredential:'********',clientStatus:'CONNECTING'})};});
    h.get('registryCenterUrl').value='10.0.0.1:10528';h.get('registryClientCredential').value='private';h.get('registryDisplayName').value='我的电脑';
    await h.context.registerEnvironment();
    assert.equal(calls[0].url,'/api/registry/settings');
    assert.deepEqual(calls[0].body,{clientEnabled:true,centerUrl:'10.0.0.1:10528',clientCredential:'private',displayName:'我的电脑'});
    assert.equal(h.get('registryClientStatus').textContent,'连接中');
    assert.equal(h.get('registryClientCredential').value,'********');
    assert.equal(h.context.registryBusy,false);
    assert.ok(calls.every(c=>!c.url.includes('/api/refresh')));
});
test('background status refresh preserves unsaved text and displays verified registration', async () => {
    const h=harness(async()=>({ok:true,json:async()=>({clientEnabled:true,centerUrl:'saved',clientStatus:'REGISTERED'})}));
    h.get('registryCenterUrl').value='正在输入';await h.context.loadRegistrySettings(false);
    assert.equal(h.get('registryCenterUrl').value,'正在输入');assert.equal(h.get('registryClientStatus').textContent,'已注册');
});
test('a late response from another environment cannot overwrite selected settings', async () => {
    let finish;
    const h=harness(()=>new Promise(resolve=>{finish=resolve}));
    const loading=h.context.loadRegistrySettings(true);h.context.curInstance={instanceId:'remote'};
    h.get('registryCenterUrl').value='新环境';finish({ok:true,json:async()=>({centerUrl:'旧环境'})});await loading;
    assert.equal(h.get('registryCenterUrl').value,'新环境');
});
test('failed registration releases controls and reports the server error', async () => {
    const h=harness(async()=>({ok:false,json:async()=>({error:'注册凭证无效'})}));await h.context.registerEnvironment();
    assert.equal(h.context.registryBusy,false);assert.equal(h.get('registryRegister').disabled,false);assert.equal(h.messages[0],'注册凭证无效');
});
test('cancellation only changes registration state', async () => {
    const calls=[];const h=harness(async(url,options)=>{calls.push(options&&JSON.parse(options.body));return{ok:true,json:async()=>({clientEnabled:false,clientStatus:'UNREGISTERED'})}});
    await h.context.unregisterEnvironment();assert.deepEqual(calls[0],{clientEnabled:false});assert.equal(h.get('registryCancel').disabled,true);
});
const core = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/assets/js/core.js'), 'utf8');
function coreSection(start,end){const a=core.indexOf(start),b=core.indexOf(end,a);assert.ok(a>=0&&b>a);return core.slice(a,b)}
test('offline environment clicks retain the current environment and its business state', async () => {
    const notices=[];
    const context=vm.createContext({curInstance:{instanceId:'local'},instances:[{instanceId:'remote',remote:true,online:false}],dirty:false,showSnackbar:m=>notices.push(m)});
    vm.runInContext(coreSection('async function switchInstance(', 'async function loadConfig('),context);
    await context.switchInstance('remote');assert.equal(context.curInstance.instanceId,'local');assert.deepEqual(notices,['目标环境离线']);
});
test('refresh retains an offline selection instead of redirecting operations to the center', async () => {
    const context=vm.createContext({curInstance:{instanceId:'remote'},instances:[],location:{hash:'instance=remote'},renderEnvTabs(){},showSnackbar(){},fetch:async()=>({json:async()=>[{instanceId:'local',self:true},{instanceId:'remote',remote:true,online:false}]})});
    vm.runInContext(coreSection('async function loadInstances(', 'function setEnvMenuOpen('),context);
    await context.loadInstances(false);assert.equal(context.curInstance.instanceId,'remote');assert.equal(context.curInstance.online,false);
});
