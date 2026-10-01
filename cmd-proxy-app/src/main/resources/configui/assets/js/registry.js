/* 注册配置独立保存，业务请求仍通过 api() 跟随当前环境。 */
var registryState={serverEnabled:false,clientEnabled:false,tunnelPort:10530},registryBusy=false,registryLoadToken=0;
function registryStatusLabel(status){return {STOPPED:'未启用',STARTING:'启动中',RUNNING:'运行中',UNREGISTERED:'未注册',CONNECTING:'连接中',REGISTERED:'已注册',ERROR:'连接异常'}[status]||status||''}
function renderRegistryStatus(){
var client=document.getElementById('registryClientStatus'),server=document.getElementById('registryServerStatus');
if(client){client.textContent=registryState.clientError||registryStatusLabel(registryState.clientStatus);client.className='toggle-desc'+(registryState.clientError?' channel-error':'')}
if(server){server.textContent=registryState.serverError||registryStatusLabel(registryState.serverStatus);server.className='toggle-desc'+(registryState.serverError?' channel-error':'')}
var register=document.getElementById('registryRegister'),cancel=document.getElementById('registryCancel');
if(register)register.disabled=registryBusy;
if(cancel)cancel.disabled=registryBusy||!registryState.clientEnabled;
var enabled=document.getElementById('registryServerEnabled');if(enabled)enabled.disabled=registryBusy;
}
async function loadRegistrySettings(fill){
var token=++registryLoadToken,environment=(curInstance&&curInstance.instanceId)||'';
try{var response=await api('/api/registry/settings');if(!response.ok)throw new Error('HTTP '+response.status);var next=await response.json();
if(token!==registryLoadToken||environment!==((curInstance&&curInstance.instanceId)||''))return;
registryState=next;
if(fill){document.getElementById('registryCenterUrl').value=next.centerUrl||'';document.getElementById('registryDisplayName').value=next.displayName||'';document.getElementById('registryTunnelPort').value=next.tunnelPort||10530;document.getElementById('registryServerEnabled').checked=!!next.serverEnabled}
renderRegistryStatus();
}catch(e){if(fill&&token===registryLoadToken){registryState={clientEnabled:false,serverEnabled:false,clientError:'注册设置加载失败',serverError:''};renderRegistryStatus()}}
}
async function configureRegistry(values){
if(registryBusy)return;
registryBusy=true;renderRegistryStatus();var environment=(curInstance&&curInstance.instanceId)||'';
try{var response=await api('/api/registry/settings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(values)}),result=await response.json();
if(!response.ok)throw new Error(result.error||'HTTP '+response.status);
if(environment!==((curInstance&&curInstance.instanceId)||''))return;
registryState=result;showSnackbar(values.clientEnabled===true?'正在注册，连接可用后会显示“已注册”':values.clientEnabled===false?'正在取消注册':'中心设置已保存');
await loadRegistrySettings(true);
}catch(e){showSnackbar(e.message);if(environment===((curInstance&&curInstance.instanceId)||''))document.getElementById('registryServerEnabled').checked=!!registryState.serverEnabled}
finally{registryBusy=false;renderRegistryStatus()}
}
function registerEnvironment(){return configureRegistry({clientEnabled:true,centerUrl:document.getElementById('registryCenterUrl').value,displayName:document.getElementById('registryDisplayName').value})}
function unregisterEnvironment(){return configureRegistry({clientEnabled:false})}
function configureRegistryServer(){return configureRegistry({serverEnabled:document.getElementById('registryServerEnabled').checked,tunnelPort:parseInt(document.getElementById('registryTunnelPort').value)||10530})}
