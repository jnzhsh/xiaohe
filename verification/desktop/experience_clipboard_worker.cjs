'use strict';

// Clipboard payloads stay in this process and the local WebView debugger only.
const {spawn}=require('node:child_process');
const {randomBytes}=require('node:crypto');
const readline=require('node:readline');
const path=require('node:path');

const DEVICE='emulator-5558';
const PACKAGE='app.xiaohe.assistant';
const APP_URL='https://xiaohe.local/index.html';
const PORT=8767;
const ADB=path.join(__dirname,'android-tools','platform-tools','platform-tools','adb.exe');
const MAX_TEXT=4*1024*1024;
const HISTORY_MS=10000;
const REQUEST_AGE_MS=3000;
const ADB_CLIENTS=new Set();
const REASONS=new Set([
  'invalid_request','empty_text','text_too_large','queue_full','stale_request','request_expired',
  'device_unavailable','app_not_running','app_not_foreground','forward_in_use',
  'connection_failed','target_unavailable','wrong_page','no_focus_history',
  'focus_changed','selection_changed','target_hidden','unsupported_target',
  'insertion_unverified','paste_cancelled','connection_recovering','worker_stopping','internal_error'
]);

class WorkerError extends Error {
  constructor(reason){super(REASONS.has(reason)?reason:'internal_error');this.reason=this.message;}
}

function validId(id){return (typeof id==='string'&&/^[A-Za-z0-9_-]{1,128}$/.test(id))||(Number.isSafeInteger(id)&&id>=0);}
function validateRequest(value,now=Date.now()){
  const id=validId(value?.id)?value.id:null;
  if(!value||typeof value!=='object'||id===null||!Number.isSafeInteger(value.time)||value.time<=0||typeof value.text!=='string')return {id,reason:'invalid_request'};
  if(value.time>now)return {id,reason:'stale_request'};
  if(now-value.time>REQUEST_AGE_MS)return {id,reason:'request_expired'};
  if(!value.text.length)return {id,reason:'empty_text'};
  if(value.text.length>MAX_TEXT)return {id,reason:'text_too_large'};
  if(value.requestId!==undefined&&!validId(value.requestId))return {id,reason:'invalid_request'};
  return {request:{id,time:value.time,text:value.text,requestId:value.requestId}};
}

function foregroundIsApp(output){
  const lines=String(output).split(/\r?\n/);
  const top=lines.filter(line=>/\btopResumedActivity\s*[:=]/.test(line));
  const candidates=top.length?top:lines.filter(line=>/\b(?:mResumedActivity|ResumedActivity)\s*[:=]/.test(line));
  const components=new Set(candidates.map(line=>line.match(/\bu\d+\s+([A-Za-z0-9_.$]+\/[A-Za-z0-9_.$]+)(?=\s|})/)?.[1]).filter(Boolean));
  return components.size===1&&[...components][0].startsWith(PACKAGE+'/');
}

function runAdb(args,timeout=3500){
  return new Promise((resolve,reject)=>{
    let child,out='',size=0,settled=false;
    const finish=(error,value)=>{if(settled)return;settled=true;clearTimeout(timer);error?reject(error):resolve(value);};
    const timer=setTimeout(()=>{child?.kill();finish(new WorkerError('device_unavailable'));},timeout);
    try{child=spawn(ADB,['-s',DEVICE,...args],{stdio:['ignore','pipe','pipe'],windowsHide:true});}
    catch(_){finish(new WorkerError('device_unavailable'));return;}
    ADB_CLIENTS.add(child);
    child.on('error',()=>{ADB_CLIENTS.delete(child);finish(new WorkerError('device_unavailable'));});
    child.stdout.on('data',chunk=>{size+=chunk.length;if(size>8*1024*1024){child.kill();finish(new WorkerError('device_unavailable'));return;}out+=chunk.toString('utf8');});
    // ADB errors may include unrelated system data. Never forward them to a log.
    child.stderr.on('data',()=>{});
    child.on('close',code=>{ADB_CLIENTS.delete(child);finish(null,{code,output:out});});
  });
}
function cancelOwnAdb(){for(const child of ADB_CLIENTS){try{child.kill();}catch(_){} }}

function parseForwards(output,port=PORT){return String(output).split(/\r?\n/).map(line=>line.trim().split(/\s+/)).filter(row=>row.length===3&&row[1]==='tcp:'+port);}

class CdpClient {
  constructor(socket,eventHandler){
    this.socket=socket;this.pending=new Map();this.serial=0;this.open=true;this.eventHandler=eventHandler;
    socket.addEventListener('message',event=>{
      let message;try{if(typeof event.data!=='string')return;message=JSON.parse(event.data);}catch(_){return;}
      if(message.id){const call=this.pending.get(message.id);if(!call)return;this.pending.delete(message.id);clearTimeout(call.timer);message.error?call.reject(new WorkerError('connection_failed')):call.resolve(message.result||{});}
      else if(typeof message.method==='string'){try{this.eventHandler(message.method,message.params||{});}catch(_){}}
    });
    socket.addEventListener('close',()=>this.invalidate());
    socket.addEventListener('error',()=>this.invalidate());
  }
  invalidate(){if(!this.open)return;this.open=false;for(const call of this.pending.values()){clearTimeout(call.timer);call.reject(new WorkerError('connection_failed'));}this.pending.clear();try{this.eventHandler('worker.disconnected',{});}catch(_){}}
  command(method,params={},timeout=2500){
    return new Promise((resolve,reject)=>{
      if(!this.open||this.socket.readyState!==1){reject(new WorkerError('connection_failed'));return;}
      const id=++this.serial,timer=setTimeout(()=>{this.pending.delete(id);reject(new WorkerError('connection_failed'));},timeout);
      this.pending.set(id,{resolve,reject,timer});
      try{this.socket.send(JSON.stringify({id,method,params}));}catch(_){this.pending.delete(id);clearTimeout(timer);reject(new WorkerError('connection_failed'));}
    });
  }
  close(){this.invalidate();try{this.socket.close();}catch(_){}}
}

function createPageMonitorSource(bindingName,helperName){
  // This function is serialized into the one approved top-level document.
  // It never reads input.value, textContent, innerText, or selection text.
  const install=function(bindingName,helperName,approvedUrl){
    if(window.top!==window||location.href!==approvedUrl)return false;
    if(window[helperName]){window[helperName].emit();return true;}
    const inputTypes=new Set(['text','search','tel','url','password']);
    let current=null,focusGeneration=0,selectionGeneration=0,lastSelection=null,gesture=null,requestSequence=0,interactionGeneration=0;
    const pending=new Map();
    function editable(element){
      if(!element||element.nodeType!==1||element.disabled||element.readOnly||element.closest('[inert]'))return null;
      const tag=element.tagName.toLowerCase();
      if(tag==='textarea'||(tag==='input'&&inputTypes.has((element.type||'text').toLowerCase())))return element;
      return null;
    }
    function selection(element){
      if(!element)return {start:null,end:null,anchor:null,focus:null};
      if(element.tagName.toLowerCase()==='input'||element.tagName.toLowerCase()==='textarea'){
        let start=null,end=null;try{start=element.selectionStart;end=element.selectionEnd;}catch(_){}
        return {start:Number.isInteger(start)?start:null,end:Number.isInteger(end)?end:null,anchor:null,focus:null};
      }
      const s=window.getSelection();
      return s&&element.contains(s.anchorNode)&&element.contains(s.focusNode)?{start:s.anchorOffset,end:s.focusOffset,anchor:s.anchorNode,focus:s.focusNode}:{start:null,end:null,anchor:null,focus:null};
    }
    function meta(){
      const element=editable(document.activeElement);
      if(element!==current){current=element;focusGeneration++;lastSelection=null;}
      const s=selection(element);
      if(!lastSelection||lastSelection.start!==s.start||lastSelection.end!==s.end||lastSelection.anchor!==s.anchor||lastSelection.focus!==s.focus){selectionGeneration++;lastSelection=s;}
      return {id:element?.id||'',tag:element?.tagName.toLowerCase()||'',type:element?.tagName.toLowerCase()==='input'?(element.type||'text').toLowerCase():'',focusGeneration,selectionGeneration,start:s.start,end:s.end};
    }
    function emit(){try{window[bindingName](JSON.stringify(meta()));}catch(_){} }
    function visible(element){
      if(!element?.isConnected||document.visibilityState==='hidden'||!document.hasFocus()||!element.getClientRects().length)return false;
      const r=element.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;
      if(r.bottom<=0||r.right<=0||r.top>=innerHeight||r.left>=innerWidth)return false;
      for(let node=element;node&&node.nodeType===1;node=node.parentElement){const style=getComputedStyle(node);if(style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||Number(style.opacity)===0)return false;}
      return true;
    }
    function matches(expected){
      if(location.href!==approvedUrl)return 'wrong_page';
      const actual=meta();
      if(!current)return 'unsupported_target';
      if(actual.focusGeneration!==expected.focusGeneration||actual.id!==expected.id||actual.tag!==expected.tag||actual.type!==expected.type)return 'focus_changed';
      if(actual.selectionGeneration!==expected.selectionGeneration||actual.start!==expected.start||actual.end!==expected.end)return 'selection_changed';
      if(!visible(current))return 'target_hidden';
      return '';
    }
    async function pasteOn(element,expected,text,prepared){
      let reason=matches(expected);
      if(reason||element!==current)return {ok:false,reason:reason||'focus_changed'};
      if(typeof text!=='string'||!Number.isInteger(expected.start)||!Number.isInteger(expected.end))return {ok:false,reason:'unsupported_target'};
      // Flush the input method before editing. Changing .value/setRangeText while
      // it still composes leaves a stale native buffer that can overwrite paste.
      const until=performance.now()+1400;
      const interaction=interactionGeneration;
      if(!prepared&&window.XiaoheAndroid&&typeof call==='function'){
        let timeout;
        try{await Promise.race([call('preparePaste'),new Promise((_,reject)=>{timeout=setTimeout(()=>reject(new Error()),1200);})]);}
        catch(_){return {ok:false,reason:'paste_cancelled'};}
        finally{clearTimeout(timeout);}
      }
      if(performance.now()>until)return {ok:false,reason:'request_expired'};
      const actual=meta();
      if(element!==current||actual.focusGeneration!==expected.focusGeneration||actual.id!==expected.id||actual.type!==expected.type||interaction!==interactionGeneration)return {ok:false,reason:'focus_changed'};
      if(!visible(element))return {ok:false,reason:'target_hidden'};
      // The browser editor performs this native edit in one synchronous turn,
      // after validating the original object and selection. It maintains IME
      // and undo state; clipboard text stays a protocol argument, never code.
      if(!document.execCommand('insertText',false,text))return {ok:false,reason:'paste_cancelled'};
      emit();
      return {ok:true};
    }
    function captureGesture(event){const button=event.target.closest?.('button[data-paste]');if(event.isTrusted&&button)gesture={id:button.dataset.paste,at:performance.now()};else if(event.isTrusted)gesture=null;}
    function interacting(event){if(event.isTrusted&&!(event.type==='keydown'&&['Control','Shift','Alt','Meta'].includes(event.key)))interactionGeneration++;}
    const interactions=['pointerdown','keydown','compositionstart'];for(const event of interactions)document.addEventListener(event,interacting,true);
    document.addEventListener('click',captureGesture,true);
    const requestFromHost=(id,replaceAll,range)=>{
      if(!gesture||gesture.id!==id||performance.now()-gesture.at>3000)return Promise.reject(new Error('请在输入框旁点一下粘贴。'));
      gesture=null;const element=editable(document.getElementById(id));if(!element)return Promise.reject(new Error('这个位置暂时不能粘贴。'));
      element.scrollIntoView({block:'nearest'});element.focus();if(replaceAll)element.select();else if(Number.isInteger(range?.start)&&Number.isInteger(range?.end))element.setSelectionRange(range.start,range.end);
      if(!visible(element))return Promise.reject(new Error('先回到需要粘贴的输入框，再试一次。'));
      emit();const requestId='paste_'+(++requestSequence)+'_'+Date.now();
      return new Promise((resolve,reject)=>{
        const timeout=setTimeout(()=>{pending.delete(requestId);reject(new Error('这次没有拿到剪贴板内容，复制后再点粘贴。'));},5000);
        pending.set(requestId,{resolve,reject,timeout});
        window[bindingName](JSON.stringify({kind:'request_paste',requestId,meta:meta()}));
      });
    };
    Object.defineProperty(window,'__xiaohePasteFromHost',{value:requestFromHost,configurable:true});
    const events=['focusin','focusout','selectionchange'];
    for(const event of events)document.addEventListener(event,emit,true);
    function finishRequest(id,ok){const request=pending.get(id);if(!request)return false;pending.delete(id);clearTimeout(request.timeout);ok?request.resolve(true):request.reject(new Error('这次没有粘贴成功，请在输入框旁再点一次粘贴。'));return true;}
    const helper={emit,check(expected){const reason=matches(expected);return reason?{ok:false,reason}:{ok:true};},resolve(expected){return matches(expected)?null:current;},pasteOn,finishRequest(args){return finishRequest(args.id,args.ok);},cleanup(){for(const event of events)document.removeEventListener(event,emit,true);for(const event of interactions)document.removeEventListener(event,interacting,true);document.removeEventListener('click',captureGesture,true);for(const id of [...pending.keys()])finishRequest(id,false);if(window.__xiaohePasteFromHost===requestFromHost)delete window.__xiaohePasteFromHost;delete window[helperName];delete window[bindingName];}};
    Object.defineProperty(window,helperName,{value:helper,configurable:true});emit();return true;
  };
  return '('+install.toString()+')('+JSON.stringify(bindingName)+','+JSON.stringify(helperName)+','+JSON.stringify(APP_URL)+')';
}

function validMetadata(value){
  return value&&typeof value.id==='string'&&value.id.length<=256&&typeof value.tag==='string'&&value.tag.length<=32&&typeof value.type==='string'&&value.type.length<=32&&Number.isSafeInteger(value.focusGeneration)&&Number.isSafeInteger(value.selectionGeneration)&&(value.start===null||Number.isSafeInteger(value.start))&&(value.end===null||Number.isSafeInteger(value.end));
}

class ClipboardWorker {
  constructor(options={}){
    this.adb=options.adb||runAdb;this.fetch=options.fetch||fetch;this.WebSocket=options.WebSocket||WebSocket;this.now=options.now||Date.now;this.emit=options.emit||(()=>{});
    this.binding='__xiaohePasteMetadata_'+randomBytes(12).toString('hex');this.helper='__xiaohePasteHelper_'+randomBytes(12).toString('hex');
    this.session=null;this.connecting=null;this.history=[];this.browserRequests=new Map();this.port=null;this.forwardRemote=null;this.forwardCandidates=new Set();this.stopping=false;this.activePaste=null;this.uncertainUntil=0;
  }
  status(event,id,reason){const message={event};if(event!=='ready')message.id=validId(id)?id:null;if(reason)message.reason=REASONS.has(reason)?reason:'internal_error';this.emit(message);}
  async adbOk(args,timeout){const result=await this.adb(args,timeout);if(result.code!==0)throw new WorkerError('device_unavailable');return result.output;}
  async foreground(){return foregroundIsApp(await this.adbOk(['shell','dumpsys','activity','activities']));}
  async ensureForward(pid){
    if(this.stopping)throw new WorkerError('worker_stopping');
    const remote='localabstract:webview_devtools_remote_'+pid;
    if(this.port===null){
      // A fresh OS-selected port belongs only to this worker. Old forwards from
      // a previous VM/app session cannot prevent the clipboard from reconnecting.
      const result=await this.adb(['forward','tcp:0',remote]);
      const port=Number(result.output.trim());
      if(result.code!==0||!Number.isInteger(port)||port<1024||port>65535)throw new WorkerError('forward_in_use');
      this.port=port;this.forwardCandidates.add(remote);this.forwardRemote=remote;return;
    }
    const rows=parseForwards(await this.adbOk(['forward','--list']),this.port);
    if(rows.length&&!(rows.length===1&&rows[0][0]===DEVICE&&this.forwardCandidates.has(rows[0][2])))throw new WorkerError('forward_in_use');
    if(rows.length&&this.forwardRemote===remote)return;
    if(this.stopping)throw new WorkerError('worker_stopping');
    // The server may commit a forward before a cancelled ADB client reports success.
    this.forwardCandidates.add(remote);
    const result=await this.adb(['forward',...(rows.length?[]:['--no-rebind']),'tcp:'+this.port,remote]);
    if(result.code!==0)throw new WorkerError('forward_in_use');this.forwardRemote=remote;
  }
  protocolEvent(method,params){
    if(method==='worker.disconnected'||method==='Runtime.executionContextsCleared'){this.history=[];this.browserRequests.clear();if(!this.stopping)this.emit({event:'disconnected'});return;}
    if(method==='Runtime.executionContextDestroyed'){this.history=this.history.filter(row=>row.contextId!==params.executionContextId);return;}
    if(method==='Page.frameNavigated'&&!params.frame?.parentId&&params.frame?.url!==APP_URL){this.history=[];return;}
    if(method!=='Runtime.bindingCalled'||params.name!==this.binding||!Number.isSafeInteger(params.executionContextId))return;
    let payload;try{payload=JSON.parse(params.payload);}catch(_){return;}
    const buttonRequest=payload.kind==='request_paste',meta=buttonRequest?payload.meta:payload;if(!validMetadata(meta))return;
    const now=this.now(),row={receivedAt:now,contextId:params.executionContextId,meta:{id:meta.id,tag:meta.tag,type:meta.type,focusGeneration:meta.focusGeneration,selectionGeneration:meta.selectionGeneration,start:meta.start,end:meta.end}};this.history.push(row);
    const older=this.history.filter(row=>now-row.receivedAt>HISTORY_MS).at(-1),recent=this.history.filter(row=>now-row.receivedAt<=HISTORY_MS).slice(-160);
    this.history=[...(older?[older]:[]),...recent];
    if(buttonRequest&&validId(payload.requestId)){
      for(const [id,request] of this.browserRequests)if(now-request.receivedAt>6000)this.browserRequests.delete(id);
      if(this.browserRequests.size<8){this.browserRequests.set(payload.requestId,row);this.emit({event:'request_paste',requestId:payload.requestId});}
    }
  }
  async socket(url){
    const parsed=new URL(url);if(parsed.protocol!=='ws:'||!['127.0.0.1','localhost','[::1]'].includes(parsed.hostname)||Number(parsed.port)!==this.port)throw new WorkerError('connection_failed');
    // Always use the approved loopback endpoint, including debugger-advertised URLs.
    parsed.hostname='127.0.0.1';
    return new Promise((resolve,reject)=>{
      let socket,timer,settled=false;
      const failure=()=>{if(settled)return;settled=true;clearTimeout(timer);try{socket?.close();}catch(_){}reject(new WorkerError('connection_failed'));};
      try{socket=new this.WebSocket(parsed.href);}catch(_){failure();return;}
      timer=setTimeout(failure,2500);socket.addEventListener('error',failure,{once:true});socket.addEventListener('close',failure,{once:true});
      socket.addEventListener('open',()=>{if(settled)return;settled=true;clearTimeout(timer);resolve(socket);},{once:true});
    });
  }
  async ensureConnection(){
    if(this.stopping)throw new WorkerError('worker_stopping');
    if(this.now()<this.uncertainUntil)throw new WorkerError('connection_recovering');
    if(this.session?.client.open&&this.history.length)return this.session;
    if(this.connecting)return this.connecting;
    this.connecting=this.connect();try{return await this.connecting;}finally{this.connecting=null;}
  }
  async connect(){
    if(this.session)await this.cleanupSession(this.session);this.session=null;this.history=[];
    const pidResult=await this.adb(['shell','pidof',PACKAGE]);
    const pid=pidResult.output.trim();if(pidResult.code!==0||!/^\d+$/.test(pid))throw new WorkerError('app_not_running');
    if(this.stopping)throw new WorkerError('worker_stopping');
    await this.ensureForward(pid);if(this.stopping)throw new WorkerError('worker_stopping');
    let targets;try{const response=await this.fetch('http://127.0.0.1:'+this.port+'/json/list',{signal:AbortSignal.timeout(2000),redirect:'error'});if(!response.ok)throw new Error();targets=await response.json();}catch(_){throw new WorkerError('connection_failed');}
    const matching=Array.isArray(targets)?targets.filter(target=>target.type==='page'&&target.url===APP_URL&&typeof target.webSocketDebuggerUrl==='string'):[];
    if(matching.length!==1)throw new WorkerError('target_unavailable');
    const ws=await this.socket(matching[0].webSocketDebuggerUrl),client=new CdpClient(ws,(method,params)=>this.protocolEvent(method,params));
    const session={client,scriptId:null};this.session=session;
    try{
      for(const [method,params] of [['Runtime.enable',{}],['Page.enable',{}],['Runtime.addBinding',{name:this.binding}]]){if(this.stopping)throw new WorkerError('worker_stopping');await client.command(method,params);}
      if(this.stopping)throw new WorkerError('worker_stopping');
      const source=createPageMonitorSource(this.binding,this.helper),script=await client.command('Page.addScriptToEvaluateOnNewDocument',{source});session.scriptId=script.identifier;
      if(this.stopping)throw new WorkerError('worker_stopping');
      const installed=await client.command('Runtime.evaluate',{expression:source,returnByValue:true});
      if(installed.exceptionDetails||installed.result?.value!==true||!this.history.length)throw new WorkerError('target_unavailable');
      if(this.stopping)throw new WorkerError('worker_stopping');this.status('ready');return session;
    }catch(error){await this.cleanupSession(session);if(this.session===session)this.session=null;throw error instanceof WorkerError?error:new WorkerError('connection_failed');}
  }
  async evaluate(session,contextId,expression){
    const result=await session.client.command('Runtime.evaluate',{contextId,expression,returnByValue:true});
    if(result.exceptionDetails)throw new WorkerError('target_unavailable');return result.result?.value;
  }
  // A focus change and key press in the same millisecond have no safe ordering.
  targetAt(time){return [...this.history].reverse().find(row=>row.receivedAt<time);}
  helperExpression(method,args){return '(()=>{if(location.href!=='+JSON.stringify(APP_URL)+')return {ok:false,reason:"wrong_page"};const h=window['+JSON.stringify(this.helper)+'];return h?h['+JSON.stringify(method)+']('+((args===undefined)?'':JSON.stringify(args))+'):{ok:false,reason:"target_unavailable"};})()';}
  async paste(request){
    if(this.stopping||this.activePaste){this.status('skipped',request.id,this.stopping?'worker_stopping':'queue_full');request.text='';return;}
    const task=this.performPaste(request);this.activePaste=task;
    try{await task;}finally{if(this.activePaste===task)this.activePaste=null;}
  }
  async performPaste(request){
    let session,insertDispatched=false,target,objectId,pasted=false;
    try{
      if(this.stopping)throw new WorkerError('worker_stopping');
      if(this.now()-request.time>REQUEST_AGE_MS){this.status('skipped',request.id,'request_expired');return;}
      if(!await this.foreground()){this.status('skipped',request.id,'app_not_foreground');return;}
      session=await this.ensureConnection();target=request.requestId?this.browserRequests.get(request.requestId):this.targetAt(request.time);
      if(this.now()-request.time>REQUEST_AGE_MS){this.status('skipped',request.id,'request_expired');return;}
      if(!target||!target.meta.tag){this.status('skipped',request.id,'no_focus_history');return;}
      const check=await this.evaluate(session,target.contextId,this.helperExpression('check',target.meta));
      if(check?.ok!==true){this.status('skipped',request.id,check?.reason||'target_unavailable');return;}
      // Resolve an opaque original-element reference, without reading its value.
      if(!await this.foreground()){this.status('skipped',request.id,'app_not_foreground');return;}
      if(this.stopping)throw new WorkerError('worker_stopping');
      if(this.now()-request.time>REQUEST_AGE_MS){this.status('skipped',request.id,'request_expired');return;}
      const resolved=await session.client.command('Runtime.evaluate',{contextId:target.contextId,expression:this.helperExpression('resolve',target.meta),returnByValue:false});
      objectId=resolved.result?.objectId;
      if(resolved.exceptionDetails||!objectId){this.status('skipped',request.id,'focus_changed');return;}
      if(this.stopping)throw new WorkerError('worker_stopping');
      if(this.now()-request.time>REQUEST_AGE_MS){this.status('skipped',request.id,'request_expired');return;}
      insertDispatched=true;
      const inserted=await session.client.command('Runtime.callFunctionOn',{
        objectId,
        functionDeclaration:'function(expected,text,helperName,prepared){const h=window[helperName];return h?h.pasteOn(this,expected,text,prepared):{ok:false,reason:"target_unavailable"};}',
        arguments:[{value:target.meta},{value:request.text},{value:this.helper},{value:!!request.requestId}],returnByValue:true,awaitPromise:true
      },2500);
      if(inserted.exceptionDetails){this.status('error',request.id,'insertion_unverified');return;}
      const finish=inserted.result?.value;
      if(finish?.ok!==true){this.status('skipped',request.id,finish?.reason||'target_unavailable');return;}
      pasted=true;this.status('pasted',request.id);
    }catch(error){
      // An interrupted insert may already have happened. Never retry this request.
      const reason=insertDispatched?'insertion_unverified':error instanceof WorkerError?error.reason:'internal_error';this.status(!insertDispatched&&(this.stopping||reason==='connection_recovering')?'skipped':'error',request.id,reason);
      if(insertDispatched){
        this.uncertainUntil=this.now()+2000;
        if(session)await this.cleanupSession(session);if(this.session===session)this.session=null;
      }else if(session){await this.cleanupSession(session);if(this.session===session)this.session=null;}
    }finally{
      if(request.requestId){
        const original=this.browserRequests.get(request.requestId);this.browserRequests.delete(request.requestId);
        const active=session?.client.open?session:this.session;
        if(original&&active?.client.open)try{await this.evaluate(active,original.contextId,this.helperExpression('finishRequest',{id:request.requestId,ok:pasted}));}catch(_){}
      }
      if(objectId&&session?.client.open){try{await session.client.command('Runtime.releaseObject',{objectId},500);}catch(_){} }
      request.text='';
    }
  }
  async cleanupSession(session){
    const client=session?.client;if(!client)return;
    if(client.open){
      try{await client.command('Runtime.evaluate',{expression:'(()=>{if(location.href!=='+JSON.stringify(APP_URL)+')return;const h=window['+JSON.stringify(this.helper)+'];if(h)h.cleanup();})()'},800);}catch(_){}
      if(session.scriptId)try{await client.command('Page.removeScriptToEvaluateOnNewDocument',{identifier:session.scriptId},800);}catch(_){}
      try{await client.command('Runtime.removeBinding',{name:this.binding},800);}catch(_){}
    }
    client.close();this.history=[];
  }
  async shutdown(){
    this.stopping=true;cancelOwnAdb();
    if(this.activePaste)try{await this.activePaste;}catch(_){}
    if(this.connecting)try{await this.connecting;}catch(_){}
    if(this.session)await this.cleanupSession(this.session);this.session=null;
    if(this.port!==null&&this.forwardCandidates.size){try{const rows=parseForwards(await this.adbOk(['forward','--list'],500),this.port);if(rows.length===1&&rows[0][0]===DEVICE&&this.forwardCandidates.has(rows[0][2]))await this.adb(['forward','--remove','tcp:'+this.port],500);}catch(_){}this.forwardRemote=null;this.forwardCandidates.clear();}
  }
}

async function main(){
  const output=value=>{try{process.stdout.write(JSON.stringify(value)+'\n');}catch(_){} };
  const worker=new ClipboardWorker({emit:output}),queue=[];
  let running=false,ended=false,shutdownPromise=null,shutdownTimer=null,recoveryTimer=null;
  const shutdown=()=>shutdownPromise||(shutdownPromise=(async()=>{clearInterval(recoveryTimer);shutdownTimer=setTimeout(()=>process.exit(process.exitCode||0),8000);for(const request of queue.splice(0)){worker.status('skipped',request.id,'worker_stopping');request.text='';}await worker.shutdown();try{process.stdout.write('',()=>{clearTimeout(shutdownTimer);process.exit(process.exitCode||0);});}catch(_){clearTimeout(shutdownTimer);process.exit(process.exitCode||0);}})());
  const startup=worker.ensureConnection().catch(error=>worker.status('error',null,error instanceof WorkerError?error.reason:'connection_failed'));
  // The WebView can appear after the helper, or restart during APK upgrades.
  // Recovery never reads the clipboard; only a user's paste action can do that.
  recoveryTimer=setInterval(()=>{if(!ended&&!worker.stopping&&!worker.activePaste&&(!worker.session?.client.open||!worker.history.length))void worker.ensureConnection().catch(()=>{});},1500);
  async function drain(){
    if(running)return;running=true;
    try{await startup;while(queue.length&&!worker.stopping)await worker.paste(queue.shift());}
    finally{running=false;if(ended)await shutdown();}
  }
  const input=readline.createInterface({input:process.stdin,crlfDelay:Infinity,terminal:false});
  input.on('line',line=>{
    let value;try{if(line.length>MAX_TEXT*6+1024)throw new Error();value=JSON.parse(line.replace(/^\uFEFF/,''));}catch(_){worker.status('error',null,'invalid_request');return;}
    const validated=validateRequest(value,worker.now());
    if(validated.reason){worker.status(validated.reason==='invalid_request'?'error':'skipped',validated.id,validated.reason);return;}
    if(ended||worker.stopping){worker.status('skipped',validated.request.id,'worker_stopping');return;}
    if(queue.length>=8){worker.status('skipped',validated.request.id,'queue_full');validated.request.text='';return;}
    queue.push(validated.request);void drain().catch(()=>worker.status('error',null,'internal_error'));
  });
  input.on('close',()=>{ended=true;worker.stopping=true;void shutdown();});
  input.on('error',()=>{ended=true;worker.stopping=true;input.close();void shutdown();});
  process.stdout.on('error',()=>{ended=true;worker.stopping=true;input.close();void shutdown();});
  for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{ended=true;worker.stopping=true;input.close();void shutdown();});
  const fatal=()=>{worker.status('error',null,'internal_error');ended=true;worker.stopping=true;input.close();void shutdown();process.exitCode=1;};
  process.on('uncaughtException',fatal);process.on('unhandledRejection',fatal);
  process.on('exit',cancelOwnAdb);
  await startup;if(ended&&!running)await shutdown();
}

module.exports={ClipboardWorker,CdpClient,WorkerError,createPageMonitorSource,validateRequest,foregroundIsApp};
if(require.main===module)main().catch(()=>{try{process.stdout.write('{"event":"error","id":null,"reason":"internal_error"}\n');}catch(_){}process.exitCode=1;});
