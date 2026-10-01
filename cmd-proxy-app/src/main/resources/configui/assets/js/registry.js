/* 注册配置独立保存，业务请求仍通过 api() 跟随当前环境。 */
var registryState={serverEnabled:false,clientEnabled:false,tunnelPort:10530},registryBusy=false,registryLoadToken=0;
function registryStatusLabel(status){return {STOPPED:'未启用',STARTING:'启动中',RUNNING:'运行中',UNREGISTERED:'未注册',CONNECTING:'连接中',REGISTERED:'已注册',ERROR:'连接异常'}[status]||'状态更新中'}
function registryFriendlyError(error,status){
var message=typeof error==='string'?error:error&&error.message;
if(error&&error.name==='AbortError')return '操作等待超时，请检查网络后重试';
if(status===401||status===403)return '中心暂时不允许接入，请确认中心已启用并检查访问设置';
if(status===404)return '未找到注册服务，请确认填写的是中心管理页面的地址和端口';
if(status===502||status===504)return '暂时无法连接目标环境，请检查网络并确认环境在线';
if(message&&message.length<=160&&!/[a-zA-Z]/.test(message))return message;
return '操作未完成，请检查网络和中心地址后重试';
}
async function registryRequest(options){
var controller=new AbortController(),timer=setTimeout(function(){controller.abort()},15000);
try{
var response=await api('/api/registry/settings',Object.assign({},options,{signal:controller.signal})),result;
try{result=await response.json()}catch(e){throw new Error(registryFriendlyError(null,response.status))}
if(!response.ok)throw new Error(registryFriendlyError(result.error,response.status));
return result;
}finally{clearTimeout(timer)}
}
function renderRegistryStatus(){
var client=document.getElementById('registryClientStatus'),server=document.getElementById('registryServerStatus');
if(client){client.textContent=registryState.clientError?registryFriendlyError(registryState.clientError):registryStatusLabel(registryState.clientStatus);client.className='toggle-desc'+(registryState.clientError?' channel-error':'')}
if(server){server.textContent=registryState.serverError?registryFriendlyError(registryState.serverError):registryStatusLabel(registryState.serverStatus);server.className='toggle-desc'+(registryState.serverError?' channel-error':'')}
[['registryRegister','注册当前环境'],['registryCancel',registryState.clientEnabled?'取消注册':'当前环境尚未注册，无需取消'],['registryServerApply','应用中心设置'],['registryServerEnabled','启用或关闭注册中心']].forEach(function(action){
var control=document.getElementById(action[0]);if(!control)return;
// 保留点击入口，用明确反馈解释当前为什么不能执行；处理中保持原色并使用等待光标。
control.disabled=false;control.style.cursor=registryBusy?'wait':'';
control.title=registryBusy?'正在处理，请稍候':action[1];
control.setAttribute('aria-disabled',String(registryBusy||(action[0]==='registryCancel'&&!registryState.clientEnabled)));
});
}
function registryActionAvailable(){if(registryBusy){showSnackbar('正在处理上一次操作，请稍候');return false}return true}
async function loadRegistrySettings(fill){
var token=++registryLoadToken,environment=(curInstance&&curInstance.instanceId)||'';
try{var next=await registryRequest();
if(token!==registryLoadToken||environment!==((curInstance&&curInstance.instanceId)||''))return;
registryState=next;
if(fill){document.getElementById('registryCenterUrl').value=next.centerUrl||'';document.getElementById('registryDisplayName').value=next.displayName||'';document.getElementById('registryTunnelPort').value=next.tunnelPort||10530;document.getElementById('registryServerEnabled').checked=!!next.serverEnabled}
renderRegistryStatus();
}catch(e){if(fill&&token===registryLoadToken&&environment===((curInstance&&curInstance.instanceId)||'')){registryState.clientError='设置加载失败，请检查网络后重新打开此页面';renderRegistryStatus();showSnackbar(registryState.clientError)}}
}
async function configureRegistry(values){
if(!registryActionAvailable())return;
registryBusy=true;renderRegistryStatus();var environment=(curInstance&&curInstance.instanceId)||'';
try{var result=await registryRequest({method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(values)});
if(environment!==((curInstance&&curInstance.instanceId)||''))return;
registryState=result;showSnackbar(values.clientEnabled===true?'注册设置已保存，正在连接中心':values.clientEnabled===false?'已取消注册':values.serverEnabled?'中心设置已保存，正在准备接入服务':'已关闭注册中心');
await loadRegistrySettings(true);
}catch(e){if(environment===((curInstance&&curInstance.instanceId)||'')){showSnackbar(registryFriendlyError(e));document.getElementById('registryServerEnabled').checked=!!registryState.serverEnabled}}
finally{registryBusy=false;renderRegistryStatus()}
}
function registerEnvironment(){
if(!registryActionAvailable())return;
var center=document.getElementById('registryCenterUrl').value.trim(),name=document.getElementById('registryDisplayName').value.trim();
if(!center){showSnackbar('请先填写中心管理页面的地址，例如 192.168.1.10:10528');return}
try{var address=new URL(center.indexOf('://')>=0?center:'http://'+center);if(!/^https?:$/.test(address.protocol)||!address.hostname||address.username||address.password||address.search||address.hash||(address.pathname&&address.pathname!=='/')||address.port==='0')throw new Error()}catch(e){showSnackbar('中心地址格式不正确，请填写管理页面的地址和端口');return}
if(name.length>120){showSnackbar('环境名称过长，请控制在 120 个字符以内');return}
return configureRegistry({clientEnabled:true,centerUrl:center,displayName:name});
}
function unregisterEnvironment(){
if(!registryActionAvailable())return;
if(!registryState.clientEnabled){showSnackbar('当前环境尚未注册，无需取消');return}
return configureRegistry({clientEnabled:false});
}
function configureRegistryServer(){
if(!registryActionAvailable()){document.getElementById('registryServerEnabled').checked=!!registryState.serverEnabled;return}
var port=Number(document.getElementById('registryTunnelPort').value);
if(!Number.isInteger(port)||port<1||port>65535){showSnackbar('请填写 1～65535 之间的隧道端口');document.getElementById('registryServerEnabled').checked=!!registryState.serverEnabled;return}
return configureRegistry({serverEnabled:document.getElementById('registryServerEnabled').checked,tunnelPort:port});
}
