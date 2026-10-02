const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const root='cmd-proxy-app/src/main/resources/configui/';
function fixture(mobile=false){
 const c={document:{addEventListener(){}},window:{matchMedia:()=>({matches:mobile}),getComputedStyle:()=>({minHeight:'64px'})}};
 vm.createContext(c);vm.runInContext(fs.readFileSync(root+'assets/js/starweave.js','utf8'),c);
 return c;
}
test('both composers send on desktop Enter but retain Shift+Enter and IME confirmation',()=>{
 const c=fixture();let sent=0,prevented=0;c.sendStarweaveMessage=c.sendTeamSessionMessage=()=>sent++;
 for(const name of ['starMessageKeydown','teamSessionKeydown']){
  c[name]({key:'Enter',preventDefault(){prevented++}});
  for(const extra of [{shiftKey:true},{isComposing:true},{keyCode:229}])c[name]({key:'Enter',...extra,preventDefault(){throw Error('newline or composition intercepted')}});
 }
 assert.equal(sent,2);assert.equal(prevented,2);
});
test('mobile Enter keeps the native newline in both composers',()=>{
 const c=fixture(true);c.sendStarweaveMessage=c.sendTeamSessionMessage=()=>{throw Error('mobile sent')};
 for(const name of ['starMessageKeydown','teamSessionKeydown'])c[name]({key:'Enter',preventDefault(){throw Error('newline intercepted')}});
});
function resizable(c){
 const listeners={},head={getBoundingClientRect:()=>({height:60})};
 const main={querySelector:()=>head,getBoundingClientRect:()=>({height:600})};
 const compose={getBoundingClientRect:()=>({height:height+30})};let height=68,captured=false;
 const input={style:{set height(value){height=parseFloat(value)},get height(){return height+'px'}},getBoundingClientRect:()=>({height}),closest:s=>s==='.session-main'?main:compose};
 const handle={parentElement:{querySelector:()=>input},setPointerCapture(){captured=true},hasPointerCapture:()=>captured,releasePointerCapture(){captured=false},addEventListener:(name,fn)=>listeners[name]=fn,removeEventListener:name=>delete listeners[name]};
 return {input,handle,listeners};
}
test('top-right drag grows upwards, clamps height and cleans up on release or cancellation',()=>{
 const c=fixture();
 for(const finish of ['pointerup','pointercancel','lostpointercapture']){
  const {input,handle,listeners}=resizable(c);
  c.startChatInputResize({button:0,currentTarget:handle,clientY:400,pointerId:1,preventDefault(){}});
  listeners.pointermove({clientY:250,pointerId:2});assert.equal(input.style.height,'68px');
  listeners.pointermove({clientY:250,pointerId:1});assert.equal(input.style.height,'218px');
  listeners.pointermove({clientY:-500,pointerId:1});assert.equal(input.style.height,'410px');
  listeners.pointermove({clientY:900,pointerId:1});assert.equal(input.style.height,'64px');
  listeners[finish]({pointerId:1});assert.equal(Object.keys(listeners).length,0);
 }
});
test('resize handles support keyboard adjustment and are wired to both inputs',()=>{
 const c=fixture(),{input,handle}=resizable(c);
 c.chatInputResizeKeydown({key:'ArrowUp',currentTarget:handle,preventDefault(){}});assert.equal(input.style.height,'92px');
 c.chatInputResizeKeydown({key:'ArrowDown',currentTarget:handle,preventDefault(){}});assert.equal(input.style.height,'68px');
 const html=fs.readFileSync(root+'index.html','utf8');
 for(const id of ['starMessageInput','teamSessionInput'])assert.match(html,new RegExp('<div class="session-input-wrap"><textarea id="'+id+'"[^]*?</textarea><button[^>]*onpointerdown="startChatInputResize\\(event\\)"'));
 assert.match(fs.readFileSync(root+'assets/css/starweave.css','utf8'),/\.session-compose \.session-input-wrap textarea\{[^}]*max-height:none/);
});
