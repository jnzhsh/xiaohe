'use strict';
const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)];
const escapeHtml=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const recordTitle=r=>r.dateNormalizedTitle||r.title||'一条记忆',recordContent=r=>r.dateNormalizedContent||r.content||'原始素材已收下。';
const icons={leaf:'<path d="M20 4c-7-1-13 3-13 9a5 5 0 0 0 8 4c4-3 6-8 5-13Z"/><path d="M5 21 16 9"/>',settings:'<path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z"/><path d="m9 3-1 3-3 1-2 4 2 2 1 4 3 1 3 3 3-2 4-1 1-3 2-3-2-3-1-3-4-1-3-2Z"/>',paperclip:'<path d="m8 12 6-6a3 3 0 0 1 4 4l-8 8a5 5 0 0 1-7-7l9-9a2 2 0 0 1 3 3L6 14"/>',mic:'<rect x="9" y="2" width="6" height="13" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/>',chat:'<path d="M21 11a9 9 0 0 1-9 9 10 10 0 0 1-4-1l-5 2 1-5a9 9 0 1 1 17-5Z"/>',book:'<path d="M4 3h13a3 3 0 0 1 3 3v15H6a3 3 0 0 1-3-3V5a2 2 0 0 1 1-2ZM3 17h17M8 7h7m-7 4h5"/>',check:'<rect x="3" y="3" width="18" height="18" rx="4"/><path d="m7 12 3 3 7-7"/>',close:'<path d="m6 6 12 12M6 18 18 6"/>',download:'<path d="M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4"/>',upload:'<path d="M12 15V3m-5 5 5-5 5 5M4 17v4h16v-4"/>'};
function icon(name){return '<svg aria-hidden="true" viewBox="0 0 24 24">'+(icons[name]||icons.book)+'</svg>';}
function initIcons(root=document){root.querySelectorAll('[data-icon]').forEach(el=>{el.innerHTML=icon(el.dataset.icon);el.classList.add('icon');});}
const pendingCalls=new Map();let counter=0;
window.NativeBridge={
 resolve(id,value,error){const p=pendingCalls.get(id);if(!p)return;pendingCalls.delete(id);clearTimeout(p.timer);error?p.reject(new Error(error)):p.resolve(value);},
 event(name,value){
  if(name==='progress')progress(value.text,true);
  if(name==='error')toast(value.text);
  if(name==='shared'){if(value.text){$('#message-input').value=[$('#message-input').value,value.text].filter(Boolean).join('\n');saveDraft();}if(value.files)for(const f of value.files)state.pending.set(f.id,f);renderPending();changeView('chat');}
  if(name==='voiceState')setVoiceState(value.state||'stopped',value);
  if(name==='voicePartial'&&value.sessionId===state.voiceSessionId&&state.voiceState==='listening')$('#voice-preview').textContent=value.text||'说完点“说好了”，我会先写成文字。';
  if(name==='voiceResult')receiveVoice(value);
 }

};
function call(method,args={}){return new Promise((resolve,reject)=>{if(!window.XiaoheAndroid){reject(new Error('请在小禾安卓 App 中使用。'));return;}const id='request_'+(++counter)+'_'+Date.now();const timer=setTimeout(()=>{pendingCalls.delete(id);reject(new Error('这次操作花的时间较长，可以稍后重试。'));},15*60*1000);pendingCalls.set(id,{resolve,reject,timer});try{window.XiaoheAndroid.invoke(method,JSON.stringify(args),id);}catch(error){window.NativeBridge.resolve(id,null,error.message);}});}
const state={messages:[],sources:new Map(),sourceLoads:new Map(),memories:[],files:new Map(),pending:new Map(),draftRestored:false,settings:{},archiveReport:'',view:'chat',memoryStatus:'active',taskStatus:'active',category:'',busy:false,recording:false,voiceState:'stopped',voiceSessionId:'',cancelledVoiceSessions:new Set(),draftAnchor:null,detail:null,detailMode:'closed',detailSequence:0,detailConflict:null,entities:[],conflicts:[],tasks:[],page:{rows:[],tags:[]},pageSequence:0,pageKey:'',historyRows:[],historyMemory:null,entityPage:{rows:[]},entitySequence:0,entityQuery:'',taskPage:{rows:[]},taskSequence:0};
let toastTimer,ocrWorker,loadPromise;
function toast(text){const element=$('#toast'),dialogs=$$('dialog[open]');(dialogs[dialogs.length-1]||document.body).append(element);element.textContent=text;element.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>element.hidden=true,4200);}
function progress(text,busy=false){$('#progress').textContent=text;$('#progress').classList.toggle('busy',busy);}
function formatDate(value,options,zone){if(!value)return '';const d=new Date(value);if(Number.isNaN(d.getTime()))return '时间未定';try{return new Intl.DateTimeFormat('zh-CN',{...options,timeZone:zone||undefined}).format(d);}catch(_){return new Intl.DateTimeFormat('zh-CN',options).format(d);}}
function time(value,zone){return formatDate(value,{month:'long',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false},zone);}
function loadData(data){if(!data)return;state.messages=data.messages||[];state.memories=data.memories||[];state.files=new Map((data.files||[]).map(f=>[f.id,f]));state.settings=data.settings||{};state.archiveReport=data.archiveReport||'';state.entities=data.entities||[];state.conflicts=data.conflicts||[];state.tasks=data.tasks||state.memories;state.page=data.memoryPage||{rows:state.memories,tags:[]};state.memoryCount=data.memoryCount??state.memories.length;state.notifications=data.notifications;state.exactAlarms=data.exactAlarms;if(!state.draftRestored){state.draftRestored=true;try{for(const id of JSON.parse(localStorage.getItem('xiaohe-draft-files')||'[]'))if(state.files.has(id))state.pending.set(id,state.files.get(id));}catch(_){}if(state.draftAnchor&&!state.files.has(state.draftAnchor.anchorFileId))state.draftAnchor=null;renderPending();saveDraft();}renderAll();if(data.openMemoryId)showDetail(data.openMemoryId);}
async function refresh(){loadData(await call('bootstrap'));}
function openSheet(id){const d=$('#'+id);if(!d.open)d.showModal();}
function closeSheet(id){if(id==='detail-dialog'){state.detailMode='closed';++state.detailSequence;}$('#'+id).close();}
function changeView(view){if(view!=='chat'&&voiceActive())cancelVoice();state.view=view;$$('.view').forEach(v=>v.classList.toggle('active',v.id===view+'-view'));$$('.bottom-nav button').forEach(b=>b.classList.toggle('selected',b.dataset.view===view));$('#composer-wrap').hidden=view!=='chat';if(view==='memory')renderMemories();if(view==='tasks')renderTasks();$('#main').scrollTop=view==='chat'?$('#main').scrollHeight:0;}
function saveDraft(){
 const text=$('#message-input').value;
 if(!text.trim()&&![...state.pending.values()].some(f=>f.localVoice))state.draftAnchor=null;
 try{localStorage.setItem('xiaohe-draft',text);if(state.draftAnchor)localStorage.setItem('xiaohe-draft-anchor',JSON.stringify(state.draftAnchor));else localStorage.removeItem('xiaohe-draft-anchor');}catch(_){}
 renderDraftOrigin();
}
function renderDraftOrigin(){
 const anchor=state.draftAnchor;
 $('#draft-origin').hidden=!anchor;$('#clear-draft').hidden=!anchor;
 if(anchor)$('#draft-origin').textContent='这段里的时间，按 '+formatDate(anchor.capturedAt,{month:'long',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false},anchor.capturedZone)+' 说话时记着。';
}
function voiceActive(){return state.voiceState!=='stopped';}
function updateComposerControls(){
 const active=voiceActive();document.documentElement.classList.toggle('voice-active',active);$('#send').disabled=state.busy||active;$('#attach').disabled=state.busy||active;
 $('#voice').disabled=state.busy||['permission','loading','transcribing','cancelling'].includes(state.voiceState);
 $('#voice').classList.toggle('recording',active);$('#voice-label').textContent=state.voiceState==='listening'?'说好了':active?'稍等':'语音';
 $('#voice-cancel').disabled=state.voiceState==='cancelling';$('#clear-draft').disabled=active||state.busy;
 $('#send').textContent=state.busy?'整理中':'发送';
}
function setVoiceState(phase,value={}){
 state.voiceState=phase;state.recording=phase!=='stopped';if(value.sessionId)state.voiceSessionId=value.sessionId;
 const card=$('#voice-status');card.hidden=phase==='stopped';
 const labels={permission:'允许我听你说一句吧。',loading:'我在准备听你说…',listening:'我在听，慢慢说。',transcribing:'正在把刚才的话写下来…',cancelling:'正在停下来，草稿还在。'};
 if(labels[phase])$('#voice-status-label').textContent=labels[phase];
 if(phase==='listening')$('#voice-preview').textContent='说完点“说好了”，我会先写成文字。';
 else if(phase!=='stopped')$('#voice-preview').textContent='声音留在手机上，文字由你核对后再发送。';
 updateComposerControls();resize();
}
function receiveVoice(value){
 if(state.cancelledVoiceSessions.has(value.sessionId))return;
 const file=value.file;if(!file?.id)return;
 state.pending.set(file.id,file);state.files.set(file.id,file);
 if(!state.draftAnchor&&Number.isFinite(value.capturedAt)&&value.capturedAt>0&&value.capturedZone)state.draftAnchor={capturedAt:value.capturedAt,capturedZone:value.capturedZone,anchorFileId:file.id};
 if(value.text)$('#message-input').value=[$('#message-input').value,value.text].filter(Boolean).join('\n');
 saveDraft();renderPending();changeView('chat');
 toast(value.note||'听到了，先看看文字，想改哪里都可以。');
}
async function cancelVoice(){
 if(state.voiceSessionId){state.cancelledVoiceSessions.add(state.voiceSessionId);if(state.cancelledVoiceSessions.size>20)state.cancelledVoiceSessions.delete(state.cancelledVoiceSessions.values().next().value);}
 setVoiceState('cancelling');try{await call('voice',{action:'cancel'});}catch(e){setVoiceState('stopped');toast(e.message);}
}
function clearDraft(){state.pending.clear();state.draftAnchor=null;$('#message-input').value='';renderPending();saveDraft();toast('草稿清空了。已经记下的内容还在。');}

function tagsHtml(tags=[]){return '<div class="tag-row">'+tags.map(t=>'<span class="tag">'+escapeHtml(t)+'</span>').join('')+'</div>';}
function attachmentsHtml(ids=[]){return '<div class="message-files">'+ids.map(id=>{const f=state.files.get(id);if(!f)return '';const img=f.mime?.startsWith('image/')?'<img src="/media/'+encodeURIComponent(id)+'" alt="附件预览">':icon('paperclip');return '<button class="attachment" data-file="'+escapeHtml(id)+'">'+img+'<span>'+escapeHtml(f.name)+'</span></button>';}).join('')+'</div>';}
function messageText(m){const content=String(m.content||''),long=content.length>1200;return '<div>'+escapeHtml(long?content.slice(0,1200)+'…':content)+'</div>'+(long?'<p class="processing-note">正文较长，先显示前 1200 字。</p><div class="reply-tools"><button class="secondary-button" data-full-message="'+escapeHtml(m.id)+'">看看全文</button></div>':'');}
function actionableCare(r){return r.status==='active'&&r.care?.level==='actionable'&&!r.care.deferred&&r.care.kind?.startsWith('reminder_')&&!!r.care.question;}
function reminderScheduled(r){return r.reminderSchedule?.state==='scheduled'&&r.reminderSchedule.notificationsAllowed===true;}
function currentCare(){const source=[...state.messages].reverse().find(m=>m.role==='user');if(!source)return null;const rows=state.memories.filter(r=>actionableCare(r)&&(r.care.sourceId||r.sourceId)===source.id),reply=[...state.messages].reverse().find(m=>m.role==='assistant'&&m.sourceId===source.id);return rows.find(r=>String(reply?.content||'').includes(r.care.question))||rows[0]||null;}
function careCard(r,alreadySaid=false){return '<div class="conversation-care">'+(!alreadySaid?'<p>'+escapeHtml(r.care.question)+'</p>':'')+'<div class="reply-tools"><button class="secondary-button" data-care-reply="'+escapeHtml(r.id)+'">补充一句</button><button class="soft-button" data-care-defer="'+escapeHtml(r.id)+'">以后再说</button></div></div>';}
function renderChat(){const log=$('#chat-log');if(!state.messages.length){log.innerHTML='<div class="welcome"><h1>我在，慢慢说。</h1><p>想记的事、看到的资料、突然冒出的念头，都可以交给我。<br>等你需要时，我帮你找回来。</p></div>'+(!state.settings.connected?'<div class="setup-note"><p>先把你的 AI 接口交给我，<br>我们就可以开始整理了。</p><button class="secondary-button" data-setup>连接 AI</button></div>':'');return;}
 const care=currentCare(),sourceIndex=state.messages.map(m=>m.role).lastIndexOf('user'),replies=care?state.messages.slice(sourceIndex+1).filter(m=>m.role==='assistant'&&(!m.sourceId||m.sourceId===(care.care.sourceId||care.sourceId))):[],reply=replies.length?replies[replies.length-1]:null;
 $('#message-input').placeholder=care?'想到具体安排时告诉我，也可以继续聊别的…':'想到什么，就告诉我…';
 log.innerHTML=state.messages.map(m=>{const own=m.role==='user';const related=state.memories.filter(r=>m.evidenceIds?.length?m.evidenceIds.includes(r.id):r.sourceId===(m.sourceId||m.id));return '<article class="message '+(own?'user':'assistant')+'" data-message-id="'+escapeHtml(m.id)+'">'+(!own?'<span class="avatar">'+icon('leaf')+'</span>':'')+'<div class="message-content">'+messageText(m)+attachmentsHtml(m.files||[])+(!own&&(related.length||m.evidenceIds?.length)?'<div class="reply-tools">'+(m.evidenceIds?.length?m.evidenceIds.slice(0,3).map(id=>state.memories.find(r=>r.id===id)||{id,title:'看看回答的依据'}):related.slice(0,3)).map(r=>'<button class="secondary-button" data-memory="'+escapeHtml(r.id)+'">'+escapeHtml(recordTitle(r))+'</button>').join('')+'</div>':'')+(reply?.id===m.id?careCard(care,String(m.content||'').includes(care.care.question)):'')+(m.error&&!(state.messages.find(source=>source.id===(m.sourceId||m.id))?.processed)?'<div class="error-note">'+escapeHtml(m.error)+'</div><div class="reply-tools"><button class="secondary-button" data-retry="'+escapeHtml(m.sourceId||m.id)+'">再整理一次</button></div>':own&&!m.processed&&!state.busy?'<div class="reply-tools"><button class="secondary-button" data-retry="'+escapeHtml(m.id)+'">继续整理</button></div>':'')+'</div></article>';}).join('');
 requestAnimationFrame(()=>{if(state.view==='chat')$('#main').scrollTop=$('#main').scrollHeight;});}
function recordCard(r){const dayKnown=r.time?.dateStart&&['task','event'].includes(r.type),due=r.structuredReview?null:r.remindAt||r.dueAt||(['task','event'].includes(r.type)?r.time?.state==='resolved'?r.time.start:dayKnown:null),flexible=!due&&!r.structuredReview&&r.status==='active'&&['task','event'].includes(r.type);return '<button class="memory-card" data-memory="'+escapeHtml(r.id)+'"><h3>'+escapeHtml(recordTitle(r))+'</h3><p>'+escapeHtml(recordContent(r).slice(0,180))+'</p>'+tagsHtml(r.tags||[])+memoryBadges(r)+(due?'<div class="time">'+escapeHtml(!r.remindAt&&(r.time?.state!=='resolved'||r.time?.precision!=='minute')?dateOnly(due,r.time?.anchor?.zone):time(due,r.reminderTime?.anchor?.zone||r.time?.anchor?.zone))+(r.remindAt?(reminderScheduled(r)?' · 有提醒':' · 提醒时间已记下'):dayKnown&&r.time?.state!=='resolved'?' · 时刻未定':'')+'</div>':flexible?'<div class="processing-note">'+(r.care?.kind?.startsWith('reminder_')?'先记着，还没设提醒':'时间先不定')+'</div>':'')+(r.processing==='pending'?'<div class="processing-note">原件已收下，等待 AI 整理。</div>':'')+'</button>';}
function paintTasks(){const page=state.taskPage;$('#task-list').innerHTML=(page.rows||[]).map(recordCard).join('')+(!page.rows?.length?'<div class="empty-state">'+icon('check')+'<p>'+(state.taskStatus==='active'?'手边暂时没有待安排的事。':'完成的事，会留在这里。')+'</p></div>':'')+(page.hasMore?'<button class="secondary-button load-more" data-more-tasks>再看看一些 · 共 '+page.total+' 件</button>':'');}
async function renderTasks(more=false){if(state.view!=='tasks')return;const token=++state.taskSequence,previous=more?state.taskPage.rows:[];try{const page=await call('memoryPage',{status:state.taskStatus,tasksOnly:true,limit:40,offset:previous.length});if(token!==state.taskSequence)return;if(more)page.rows=[...previous,...page.rows];state.taskPage=page;rememberRows(page.rows);paintTasks();}catch(e){if(token===state.taskSequence)toast(e.message);}}
function renderAll(){renderChat();paintMemories();if(state.view==='memory')renderMemories();renderTasks();$('#archive-report').textContent=state.archiveReport;}
function renderPending(){$('#pending-files').hidden=!state.pending.size;$('#pending-files').innerHTML=[...state.pending.values()].map(f=>'<div class="pending-file"><span>'+escapeHtml(f.name)+'</span><button aria-label="移除附件" data-remove-file="'+escapeHtml(f.id)+'">×</button></div>').join('');try{localStorage.setItem('xiaohe-draft-files',JSON.stringify([...state.pending.keys()]));}catch(_){} }
function setBusy(b){state.busy=b;if(b){clearTimeout(toastTimer);$('#toast').hidden=true;}updateComposerControls();}
async function recognizeFrames(frames){if(!frames?.length||!state.settings.localOcr)return;const groups=new Map();try{progress('我在手机上读图片里的文字…',true);ocrWorker=await Tesseract.createWorker(['chi_sim','eng'],1,{workerPath:location.origin+'/vendor/worker.min.js',corePath:location.origin+'/vendor/core',langPath:location.origin+'/vendor/lang',workerBlobURL:true,cacheMethod:'none',logger:m=>{if(m.status==='recognizing text')progress('我在读图片里的文字… '+Math.round(m.progress*100)+'%',true);}});
 for(const frame of frames){const result=await ocrWorker.recognize(frame.dataUrl);const current=groups.get(frame.sourceId)||[];current.push('['+frame.label+']\n'+result.data.text);groups.set(frame.sourceId,current);}for(const [id,parts]of groups)await call('saveOcr',{id,text:parts.join('\n\n')});
 }catch(error){toast('图片原件已经留下，文字识别这次没有完成。');}finally{if(ocrWorker){await ocrWorker.terminate();ocrWorker=null;}}}
async function processMessage(id,fileIds,retry=false){let media={frames:[]};if(fileIds.length){progress('我在看看你交来的素材…',true);media=await call('prepareMedia',{files:fileIds});await recognizeFrames(media.frames);}loadData(await call('processMessage',{id,frames:state.settings.vision?media.frames:[],retry}));progress('这条我替你记着了。');}
async function send(){if(state.busy||voiceActive())return;const input=$('#message-input');const text=input.value.trim(),files=[...state.pending.keys()];if(!text&&!files.length)return;setBusy(true);changeView('chat');let saved;
 try{const id=crypto.randomUUID?crypto.randomUUID():'message_'+Date.now()+'_'+Math.random().toString(36).slice(2);progress('先替你收下…',true);saved=await call('stageMessage',{id,text,files,...(state.draftAnchor||{})});state.messages.push(saved);for(const f of state.pending.values())state.files.set(f.id,f);state.pending.clear();renderPending();input.value='';state.draftAnchor=null;saveDraft();renderChat();await processMessage(saved.id,files);}
 catch(error){toast(error.message);progress(saved?'原始内容已在本机，稍后可以继续整理。':'这次还没存好，输入内容仍在。');if(saved)await refresh().catch(()=>{});}finally{setBusy(false);renderChat();}}
async function retry(id){if(state.busy)return;const message=state.messages.find(m=>m.id===id);if(!message)return;setBusy(true);try{await processMessage(id,message.files||[],true);}catch(e){toast(e.message);}finally{setBusy(false);renderChat();}}
function showSettings(){if(voiceActive())cancelVoice();const s=state.settings;$('#cfg-base').value=s.baseUrl||'';$('#cfg-model').value=s.model||'';$('#cfg-key').value='';$('#cfg-key').placeholder=s.hasKey?'已保存。留空则继续使用当前密钥':'填写密钥；无鉴权的本地接口可留空';$('#cfg-vision').checked=!!s.vision;$('#cfg-ocr').checked=s.localOcr!==false;$('#cfg-asr').value=s.asrUrl||'';$('#cfg-asr-model').value=s.asrModel||'whisper-1';$('#cfg-asr-key').value='';$('#cfg-asr-key').placeholder=s.hasAsrKey?'已保存。留空保留当前密钥':'同一服务可复用对话密钥，其他服务请独立填写';$('#connection-status').textContent=s.connected?'接口设置已记住，可以试着连接。':'填写后，我就能帮你自动整理了。';$('#notification-status').textContent=state.notifications===false?'设置第一条提醒时，系统会请求通知权限。':state.exactAlarms===false?'系统目前只允许大致时间的提醒，想准时提醒可开启下方权限。':'提醒在手机上安排，不依赖应用一直打开。';openSheet('settings-dialog');}
async function saveSettings(){const payload={baseUrl:$('#cfg-base').value.trim(),model:$('#cfg-model').value.trim(),apiKey:$('#cfg-key').value,vision:$('#cfg-vision').checked,localOcr:$('#cfg-ocr').checked,asrUrl:$('#cfg-asr').value.trim(),asrModel:$('#cfg-asr-model').value.trim(),asrKey:$('#cfg-asr-key').value};state.settings=await call('saveSettings',payload);$('#cfg-key').value='';$('#cfg-asr-key').value='';$('#connection-status').textContent='设置记住了，密钥留在本机。';renderChat();return state.settings;}
function beginDetail(mode,id=state.detail){state.detail=id;state.detailMode=mode;return ++state.detailSequence;}
function detailCurrent(token,id){return state.detailSequence===token&&state.detail===id&&$('#detail-dialog').open;}
function detailLoading(title){$('#detail-title').textContent=title;$('#detail-body').innerHTML='<p class="help" role="status">我在找这条记录…</p>';openSheet('detail-dialog');}
function sourceHtml(source){return '<details class="detail-source"><summary>看看当时怎么说的</summary><div class="detail-content">'+escapeHtml(source.content)+'</div>'+attachmentsHtml(source.files||[])+'</details>';}
function fetchSource(id){const source=state.messages.find(m=>m.id===id)||state.sources.get(id);if(source)return Promise.resolve(source);if(state.sourceLoads.has(id))return state.sourceLoads.get(id);const request=call('getMessage',{id}).then(m=>{if(m?.id)state.sources.set(id,m);return m;}).finally(()=>state.sourceLoads.delete(id));state.sourceLoads.set(id,request);return request;}
function conflictNotes(id){return state.conflicts.filter(c=>(c.memberIds||[]).includes(id)&&(!c.state||c.state==='open')).map(c=>'<div class="conflict-note"><h3>之前还记过另一种说法</h3><p>原话都留着。可能是情况变了，也可能各自有条件，想起来时再补一句就好。</p><button class="secondary-button" data-conflict="'+escapeHtml(c.id)+'">看看这两种说法</button></div>').join('');}
function paintDetail(r,token){
 const source=state.messages.find(m=>m.id===r.sourceId)||state.sources.get(r.sourceId);
 $('#detail-title').textContent=recordTitle(r);
 $('#detail-body').innerHTML=tagsHtml(r.tags||[])+'<div class="source-line">'+escapeHtml(r.status==='archived'?'已归档':r.status==='completed'?'已完成':'放在手边')+' · '+escapeHtml(time(r.created,r.sourceAnchor?.zone))+'</div><div class="detail-content">'+escapeHtml(recordContent(r))+'</div>'+(r.processingNote&&!r.structuredReview?'<p class="processing-note">'+escapeHtml(r.processingNote)+'</p>':'')+(r.remindAt&&!r.structuredReview?'<p class="warm-note">'+(r.reminderFired?'已提醒：':reminderScheduled(r)?'提醒已安排：':'提醒时间先记着：')+escapeHtml(time(r.remindAt,r.reminderTime?.anchor?.zone||r.time?.anchor?.zone))+(r.reminderSchedule?.notificationsAllowed===false?'。通知权限还没打开。':'')+'</p>':'')+(actionableCare(r)?careCard(r):'')+'<div class="button-row">'+(r.status==='active'?'<button class="secondary-button" data-change-status="completed">这件事做完了</button>':'<button class="secondary-button" data-change-status="active">放回手边</button>')+(r.status!=='archived'?'<button class="secondary-button" data-change-status="archived">收进归档</button>':'')+'<button class="secondary-button" data-reminder>设个提醒</button><button class="secondary-button" data-edit-memory>补充或更正</button></div>'+((r.files||[]).length?'<div class="detail-source"><h3>原始素材</h3>'+attachmentsHtml(r.files)+'<div>'+(r.files||[]).map(fid=>{const f=state.files.get(fid);return f?'<p class="processing-note">'+escapeHtml(f.name)+'：'+escapeHtml(f.processingNote||'原件保存在本机')+'</p>':'';}).join('')+'</div></div>':'')+'<details class="detail-source" id="memory-background"><summary>看看来由 · 补充线索</summary>'+memoryExtras(r)+'<div id="detail-original-source">'+(source?sourceHtml(source):'')+'</div><div id="detail-conflicts">'+conflictNotes(r.id)+'</div></details>';
 openSheet('detail-dialog');
 if(!source&&r.sourceId)fetchSource(r.sourceId).then(m=>{if(m&&detailCurrent(token,r.id)&&state.detailMode==='record')$('#detail-original-source').innerHTML=sourceHtml(m);}).catch(()=>{});
 if(r.truthState==='disputed')call('getConflicts',{memoryId:r.id}).then(groups=>{if(!detailCurrent(token,r.id)||state.detailMode!=='record')return;state.conflicts=state.conflicts.filter(c=>!(c.memberIds||[]).includes(r.id)).concat(groups||[]);$('#detail-conflicts').innerHTML=conflictNotes(r.id);}).catch(e=>{if(detailCurrent(token,r.id))toast(e.message);});
}
function showDetail(id){
 const token=beginDetail('record',id);state.detailConflict=null;
 const row=state.memories.find(x=>x.id===id);
 if(row){paintDetail(row,token);return;}
 detailLoading('一条记忆');
 call('getMemory',{id}).then(r=>{if(r)rememberRows([r]);if(!detailCurrent(token,id))return;if(r)paintDetail(r,token);else $('#detail-body').innerHTML='<p class="help">这条记忆暂时没有找到，可以稍后再试。</p><button class="secondary-button" data-reload-detail>再找一次</button>';}).catch(e=>{if(detailCurrent(token,id)){$('#detail-body').innerHTML='<p class="help">'+escapeHtml(e.message)+'</p><button class="secondary-button" data-reload-detail>再找一次</button>';}});
}
async function refreshDetail(id,context){await refresh();if(detailCurrent(context.token,context.id))showDetail(id);}
async function updateMemory(payload,context){await call('updateMemory',{id:context.id,...payload});await refreshDetail(context.id,context);}
async function action(button){
 if(button.disabled||button.dataset.close)return;
 const inDetail=!!button.closest('#detail-dialog'),previousMode=state.detailMode,context=inDetail?{id:state.detail,token:beginDetail('action')}:null;
 const hold=inDetail||button.hasAttribute('data-more-memories')||button.hasAttribute('data-more-tasks')||button.hasAttribute('data-care-defer');if(hold)button.disabled=true;
 try{
  if(await careAction(button,context))return;
  if(button.dataset.file)await call('openFile',{id:button.dataset.file});
  else if(button.dataset.memory)showDetail(button.dataset.memory);
  else if(button.dataset.retry)await retry(button.dataset.retry);
  else if(button.hasAttribute('data-setup'))showSettings();
  else if(button.dataset.removeFile){state.pending.delete(button.dataset.removeFile);renderPending();saveDraft();}
  else if(button.dataset.changeStatus){await updateMemory({status:button.dataset.changeStatus},context);toast(button.dataset.changeStatus==='completed'?'完成了，我记下了。':'这条记忆的位置调整好了。');}
  else if(button.hasAttribute('data-reminder')){const result=await call('reminder',{id:context.id});if(result?.id){await refreshDetail(context.id,context);const row=state.memories.find(r=>r.id===context.id)||result;toast(reminderScheduled(row)?'提醒安排好了。':'时间记下了，提醒先留着。');}else if(detailCurrent(context.token,context.id))showDetail(context.id);}
  else if(button.hasAttribute('data-edit-memory')){const r=state.memories.find(m=>m.id===context.id);$('#detail-body').innerHTML='<label>想补充或更正的内容<textarea class="editor" id="memory-editor"></textarea></label><div class="button-row"><button class="primary-button" data-save-edit>记住更正</button><button class="secondary-button" data-cancel-edit>返回</button></div>';$('#memory-editor').value=r.dateNormalizedContent||r.content||'';}
  else if(button.hasAttribute('data-save-edit')){await updateMemory({content:$('#memory-editor').value},context);toast('更正记下了，原始来源还留着。');}
  else if(button.hasAttribute('data-cancel-edit')||button.hasAttribute('data-reload-detail'))showDetail(context.id);
 }catch(error){toast(error.message);}finally{if(context&&detailCurrent(context.token,context.id)&&state.detailMode==='action')state.detailMode=previousMode;if(hold&&button.isConnected)button.disabled=false;}
}
document.addEventListener('click',e=>{const button=e.target.closest('button');if(button)action(button);});
$$('[data-close]').forEach(b=>b.addEventListener('click',()=>closeSheet(b.dataset.close)));$$('.bottom-nav button').forEach(b=>b.addEventListener('click',()=>changeView(b.dataset.view)));
$('#settings-open').addEventListener('click',showSettings);$('#send').addEventListener('click',send);$('#message-input').addEventListener('input',saveDraft);
$('#message-input').addEventListener('keydown',e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();send();}});
$('#attach').addEventListener('click',async()=>{try{const r=await call('pickFiles');for(const f of r.files||[])state.pending.set(f.id,f);renderPending();}catch(e){toast(e.message);}});
$('#voice').addEventListener('click',async()=>{
 if(state.busy||['permission','loading','transcribing','cancelling'].includes(state.voiceState))return;
 const stopping=state.voiceState==='listening';$('#message-input').blur();setVoiceState(stopping?'transcribing':'loading');
 try{await call('voice',{action:stopping?'stop':'start'});}catch(e){setVoiceState('stopped');toast(e.message);}
});
$('#voice-cancel').addEventListener('click',cancelVoice);
$('#clear-draft').addEventListener('click',clearDraft);
$('#save-settings').addEventListener('click',()=>saveSettings().catch(e=>toast(e.message)));
$('#test-connection').addEventListener('click',async()=>{const b=$('#test-connection');b.disabled=true;$('#connection-status').textContent='我在试着连接…';try{await saveSettings();const r=await call('testConnection');$('#connection-status').textContent=r.ok?'连上了。可以开始把事情交给我了。':'这次没连上。';}catch(e){$('#connection-status').textContent=e.message;}finally{b.disabled=false;}});
$('#export-backup').addEventListener('click',async()=>{try{const r=await call('export');if(r.ok)toast('完整备份已经导出，原始素材也一起收好了。');}catch(e){toast(e.message);}});
$('#import-backup').addEventListener('click',async()=>{try{const r=await call('import');if(typeof r.added==='number'){await refresh();toast('恢复了 '+r.added+' 条新记忆，相同记录没有重复。');}}catch(e){toast(e.message);}});
$('#archive-now').addEventListener('click',async()=>{const button=$('#archive-now'),report=$('#archive-report');button.disabled=true;button.textContent='正在整理…';report.textContent='我在梳理旧记录的日期，也检查可以归档的内容…';try{const result=await call('runArchive');await refresh();report.textContent=result.report||state.archiveReport;const rows=result.remainingDates||[];$('#date-clues').hidden=rows.length===0;$('#date-clues-list').innerHTML=rows.map(row=>'<button class="secondary-button" data-memory="'+escapeHtml(row.id)+'">'+escapeHtml(row.title||'一条时间线索')+'</button>').join('');toast('整理完成：固定 '+(result.datesNormalized||0)+' 条日期，归档 '+(result.archived||0)+' 条。');}catch(e){report.textContent='这次没能完成整理：'+e.message;toast(e.message);}finally{button.disabled=false;button.textContent='现在整理一下';}});
$('#exact-alarm').addEventListener('click',()=>call('exactAlarmSettings').catch(e=>toast(e.message)));
let memorySearchTimer;$('#memory-search').addEventListener('input',()=>{++state.pageSequence;clearTimeout(memorySearchTimer);memorySearchTimer=setTimeout(()=>renderMemories(),180);});
$$('#memory-status button').forEach(b=>b.addEventListener('click',()=>{state.memoryStatus=b.dataset.status;state.category='';$$('#memory-status button').forEach(x=>x.classList.toggle('selected',x===b));renderMemories();}));
$$('#task-status button').forEach(b=>b.addEventListener('click',()=>{state.taskStatus=b.dataset.status;$$('#task-status button').forEach(x=>x.classList.toggle('selected',x===b));renderTasks();}));
$('#category-filters').addEventListener('click',e=>{const b=e.target.closest('[data-category]');if(b){state.category=b.dataset.category;renderMemories();}});
let entitySearchTimer;document.addEventListener('input',e=>{if(e.target.id==='entity-search'){++state.entitySequence;clearTimeout(entitySearchTimer);entitySearchTimer=setTimeout(()=>{if(state.detailMode==='bind'&&$('#detail-dialog').open)renderEntities(state.detailSequence,state.detail);},180);}});
$('#detail-dialog').addEventListener('close',()=>{if(!$('#detail-dialog').open){state.detailMode='closed';++state.detailSequence;}});
$('#detail-dialog').addEventListener('cancel',()=>{state.detailMode='closed';++state.detailSequence;});

function rememberRows(rows){for(const r of rows){const i=state.memories.findIndex(x=>x.id===r.id);if(i<0)state.memories.push(r);else state.memories[i]=r;}}
function paintMemories(){const page=state.page;$('#memory-overview').textContent='原话和后来补充的内容，都留在这里。';const tags=page.tags||[];$('#category-filters').innerHTML=tags.length?'<button data-category="" class="'+(!state.category?'selected':'')+'">全部分类</button>'+tags.map(t=>'<button data-category="'+escapeHtml(t)+'" class="'+(state.category===t?'selected':'')+'">'+escapeHtml(t)+'</button>').join(''):'';$('#memory-list').innerHTML=(page.rows||[]).map(recordCard).join('')+(!page.rows?.length?'<div class="empty-state">'+icon('book')+'<p>'+($('#memory-search').value?'暂时没找到，换个词试试。':'这里的内容，我会慢慢替你收好。')+'</p></div>':'')+(page.hasMore?'<button class="secondary-button load-more" data-more-memories>再看看一些 · 共 '+page.total+' 条</button>':'');}
async function renderMemories(more=false){const token=++state.pageSequence,args={status:state.memoryStatus,tag:state.category,query:$('#memory-search').value},key=JSON.stringify(args);more=more&&key===state.pageKey;const previous=more?(state.page.rows||[]):[];try{const p=await call('memoryPage',{...args,offset:previous.length,limit:40});if(token!==state.pageSequence)return;if(more)p.rows=[...previous,...p.rows];state.page=p;state.pageKey=key;rememberRows(p.rows);paintMemories();}catch(e){if(token===state.pageSequence)toast(e.message);}}
function memoryBadges(r){const text=r.truthState==='historical'?'过去的记录':r.truthState==='retracted'?'旧说法已更正':'';return text?'<div class="memory-badges"><span>'+text+'</span></div>':'';}
function dateOnly(n,zone){return formatDate(n,{year:'numeric',month:'long',day:'numeric'},zone);}
function entityLabel(e){return e.name+(e.qualifier?' · '+e.qualifier:'');}
function memoryExtras(r){
 const t=r.time,a=r.sourceAnchor||t?.originalAnchor||t?.anchor;
 const clue=r.structuredReview?'更正的内容已经记住了，先前的安排先放下。':r.truthState==='disputed'?'两种说法先一起留着，聊到时再看。':r.referenceState==='unresolved'?'这句话先照原样留下，聊到更多线索时再接起来。':r.truthState==='historical'?'这是之前的情况，后来发生的变化也留着。':r.truthState==='retracted'?'这是先前的说法，后来已经补充更正。':'';
 let html='<div class="detail-context">'+(r.subjectLabel?'<p>有关：'+escapeHtml(r.subjectLabel)+'</p>':'')+(clue?'<p class="help">'+clue+'</p>':'')+(t?.raw?'<p>当时说的是“'+escapeHtml(t.raw)+'”'+(r.structuredReview?' · 之前的时间先放下':t.state==='resolved'?' · '+escapeHtml(t.precision==='minute'?time(t.start,t.anchor?.zone):dateOnly(t.start,t.anchor?.zone)):' · 时间先不定')+'</p>':'')+(a?'<p class="help">原消息：'+escapeHtml(dateOnly(a.capturedAt,a.zone))+' · '+escapeHtml(a.zone)+(a.zoneEstimated?'（旧记录时区为估计）':'')+'</p>':'')+'<div class="button-row">'+(r.referenceState==='unresolved'?'<button class="secondary-button" data-bind-open>补充这是谁</button>':'')+(t?.raw&&t.state!=='resolved'&&!r.structuredReview?'<button class="secondary-button" data-time-open>补个日期</button>':'')+(r.structuredReview?'<button class="secondary-button" data-review-structure>在聊天里补充</button>':'')+'<button class="secondary-button" data-history-open>看看更正记录</button></div></div>';
 if((r.sourceIds||[]).length>1)html+='<details class="detail-source"><summary>全部来源 · '+r.sourceIds.length+' 次提到</summary>'+r.sourceIds.map(id=>'<button class="secondary-button" data-source="'+escapeHtml(id)+'">看看这次的原话</button>').join('')+'</details>';
 return html;
}
async function showConflict(id){
 const c=state.conflicts.find(x=>x.id===id);if(!c){toast('这组说法暂时没有找到，请重新打开记忆。');return;}
 const memoryId=state.detail,token=beginDetail('conflict');state.detailConflict=id;detailLoading('看看这两种说法');
 const rows=(await Promise.all(c.memberIds.map(id=>call('getMemory',{id})))).filter(Boolean);rememberRows(rows);
 if(!detailCurrent(token,memoryId))return;
 $('#detail-body').innerHTML='<p class="help">原话都留着。想起是什么时候变了、或者有什么不同条件，随时补一句就好。</p>'+rows.map(r=>'<article class="conflict-choice"><h3>'+escapeHtml(r.title)+'</h3><p>'+escapeHtml(r.content)+'</p><p class="help">'+escapeHtml(dateOnly(r.created,r.sourceAnchor?.zone))+' 收到 · '+escapeHtml(r.subjectLabel||'')+'</p>'+(r.sourceId?'<button class="secondary-button" data-source="'+escapeHtml(r.sourceId)+'" data-origin-conflict="'+escapeHtml(id)+'">看看原话</button>':'')+'<button class="secondary-button" data-choose-fact="'+escapeHtml(r.id)+'" data-conflict-id="'+escapeHtml(id)+'">之前记错了，以这条为准</button>'+(rows.length===2&&c.memberIds.length===2?'<button class="secondary-button" data-change-fact="'+escapeHtml(r.id)+'" data-conflict-id="'+escapeHtml(id)+'">后来变成这样</button>':'')+'</article>').join('')+'<div class="button-row"><button class="secondary-button" data-explain-conflict>在聊天里补充</button><button class="soft-button" data-cancel-edit>先放着</button></div>';
}
function startOfDay(iso,zone){
 if(!zone)return new Date(iso+'T00:00:00').getTime();
 const [y,m,d]=iso.split('-').map(Number),target=Date.UTC(y,m-1,d);let at=target;
 const formatter=new Intl.DateTimeFormat('en-US',{timeZone:zone,year:'numeric',month:'numeric',day:'numeric',hour:'numeric',minute:'numeric',second:'numeric',hourCycle:'h23'});
 for(let i=0;i<4;i++){const p={};for(const part of formatter.formatToParts(new Date(at)))if(part.type!=='literal')p[part.type]=Number(part.value);const observed=Date.UTC(p.year,p.month-1,p.day,p.hour,p.minute,p.second),next=at+target-observed;if(next===at)return at;at=next;}
 return at;
}
async function renderEntities(token,memoryId,more=false){
 const sequence=++state.entitySequence,query=$('#entity-search').value;more=more&&query===state.entityQuery;const previous=more?state.entityPage.rows:[];
 try{const page=await call('entityPage',{query,offset:previous.length,limit:40});if(sequence!==state.entitySequence||!detailCurrent(token,memoryId)||state.detailMode!=='bind')return;if(more)page.rows=[...previous,...page.rows];state.entityPage=page;state.entityQuery=query;$('#entity-options').innerHTML=page.rows.map(e=>'<button class="secondary-button entity-choice" data-bind-entity="'+escapeHtml(e.id)+'">'+escapeHtml(entityLabel(e))+'</button>').join('')+(!page.rows.length?'<p class="help">'+(query?'暂时没找到，可以换个名字，或在下面记一个新对象。':'还没有记住对象，可以在下面告诉我。')+'</p>':'')+(page.hasMore?'<button class="secondary-button load-more" data-more-entities>再看看一些 · 共 '+page.total+' 个</button>':'');}
 catch(e){if(detailCurrent(token,memoryId)&&sequence===state.entitySequence){$('#entity-options').innerHTML='<p class="help">'+escapeHtml(e.message)+'</p><button class="secondary-button" data-entity-retry>再找一次</button>';}}
}
async function showHistory(context,more=false){
 const token=beginDetail('history',context.id),previous=more&&state.historyMemory===context.id?state.historyRows:[];
 if(!more)detailLoading('更正记录');
 const rows=await call('getHistory',{id:context.id,offset:previous.length});if(!detailCurrent(token,context.id))return;
 state.historyMemory=context.id;state.historyRows=[...previous,...rows];
 $('#detail-body').innerHTML='<p class="help">每次更正都留下来。恢复内容会再添一版。</p>'+state.historyRows.map(v=>'<article class="history-row"><p class="help">'+escapeHtml(time(v.created))+' · 第 '+escapeHtml(v.snapshot?.revision||1)+' 版</p><strong>'+escapeHtml(v.reason)+'</strong><p>'+escapeHtml(v.snapshot?.content||'')+'</p><button class="secondary-button" data-restore-version="'+escapeHtml(v.id)+'">恢复这一版的内容</button></article>').join('')+(!state.historyRows.length?'<p class="help">这条记忆还没有更正记录。</p>':'')+(rows.length===100?'<button class="secondary-button" data-more-history>看看更早的版本</button>':'')+'<div class="button-row"><button class="secondary-button" data-cancel-edit>返回这条记忆</button></div>';
}
async function careAction(b,context){
 if(b.dataset.careReply){if(context)closeSheet('detail-dialog');changeView('chat');$('#message-input').focus();return true;}
 if(b.dataset.careDefer){await call('deferCare',{id:b.dataset.careDefer});await refresh();if(context&&detailCurrent(context.token,context.id))showDetail(context.id);toast('好，先记着，等你想起再说。');return true;}
 if(b.dataset.fullMessage){const token=beginDetail('message',null);detailLoading('当时的完整内容');const m=await call('getMessage',{id:b.dataset.fullMessage});if(!detailCurrent(token,null))return true;$('#detail-body').innerHTML=m?'<p class="help">'+escapeHtml(time(m.capturedAt||m.created,m.capturedZone))+'</p><div class="detail-content">'+escapeHtml(m.content)+'</div>'+attachmentsHtml(m.files||[]):'<p class="help">这条原消息暂时没有找到。</p>';return true;}
 if(b.hasAttribute('data-more-tasks')){await renderTasks(true);return true;}
 if(b.hasAttribute('data-review-structure')){const r=state.memories.find(x=>x.id===context.id),review='更正之前关于'+(r.subjectLabel||r.title)+'的记录：'+r.content;closeSheet('detail-dialog');changeView('chat');$('#message-input').value=[$('#message-input').value,review].filter(Boolean).join('\n\n');saveDraft();$('#message-input').focus();return true;}
 if(b.hasAttribute('data-more-memories')){await renderMemories(true);return true;}
 if(b.dataset.conflict||b.dataset.returnConflict){await showConflict(b.dataset.conflict||b.dataset.returnConflict);return true;}
 if(b.dataset.chooseFact){await call('resolveConflict',{id:b.dataset.conflictId,chosenId:b.dataset.chooseFact,mode:'correction'});await refreshDetail(b.dataset.chooseFact,context);toast('记下了，这条是你确认过的说法。');return true;}
 if(b.dataset.changeFact){const r=state.memories.find(x=>x.id===b.dataset.changeFact),zone=r?.sourceAnchor?.zone||r?.time?.anchor?.zone;$('#detail-body').innerHTML='<p>从哪一天开始变成这样？之前的说法会留在过去。</p><label>生效日期<input id="change-date" type="date"></label>'+(zone?'<p class="help">日期按原消息的时区 '+escapeHtml(zone)+' 记住。</p>':'')+'<div class="button-row"><button class="primary-button" data-save-change="'+escapeHtml(b.dataset.changeFact)+'" data-conflict-id="'+escapeHtml(b.dataset.conflictId)+'">记住这个变化</button><button class="secondary-button" data-return-conflict="'+escapeHtml(b.dataset.conflictId)+'">返回两种说法</button></div>';return true;}
 if(b.dataset.saveChange){const d=$('#change-date').value;if(!d){toast('先选一下生效日期。');return true;}const r=state.memories.find(x=>x.id===b.dataset.saveChange),at=startOfDay(d,r?.sourceAnchor?.zone||r?.time?.anchor?.zone);await call('resolveConflict',{id:b.dataset.conflictId,chosenId:b.dataset.saveChange,mode:'change',transitionAt:at});await refreshDetail(b.dataset.saveChange,context);toast('现在和过去的说法都留好了。');return true;}
 if(b.hasAttribute('data-explain-conflict')){closeSheet('detail-dialog');changeView('chat');$('#message-input').focus();toast('直接说就好，可以补充时间或不同的条件。');return true;}
 if(b.hasAttribute('data-history-open')||b.hasAttribute('data-more-history')){await showHistory(context,b.hasAttribute('data-more-history'));return true;}
 if(b.dataset.restoreVersion){await call('restoreVersion',{id:b.dataset.restoreVersion});await refreshDetail(context.id,context);toast('这版内容恢复了，其他版本也留着。');return true;}
 if(b.hasAttribute('data-bind-open')){const r=state.memories.find(x=>x.id===context.id),token=beginDetail('bind');state.entityPage={rows:[]};state.entityQuery='';$('#detail-body').innerHTML='<p>你说的“'+escapeHtml((r.subjectId&&r.unresolvedRelated?.length?r.unresolvedRelated[0].name:r.subjectProposal?.name)||r.subjectLabel||'这个对象')+'”指的是谁？</p><label class="visually-hidden" for="entity-search">找一个对象</label><input id="entity-search" type="search" placeholder="找名字、关系或别名"><div id="entity-options"><p class="help" role="status">我在找记住的对象…</p></div><details class="detail-source"><summary>是一个新的对象</summary><label>名字<input id="entity-name" placeholder="如：小林"></label><label>用什么区分<input id="entity-qualifier" placeholder="如：大学同学；也可以留空"></label><button class="primary-button" data-bind-new>记住这个对象</button></details><div class="button-row"><button class="secondary-button" data-cancel-edit>先放着</button></div>';await renderEntities(token,context.id);return true;}
 if(b.hasAttribute('data-more-entities')||b.hasAttribute('data-entity-retry')){await renderEntities(beginDetail('bind'),context.id,b.hasAttribute('data-more-entities'));return true;}
 if(b.dataset.bindEntity||b.hasAttribute('data-bind-new')){const name=$('#entity-name')?.value.trim()||'';if(!b.dataset.bindEntity&&!name){toast('先告诉我这个对象的名字。');return true;}await call('bindEntity',{id:context.id,entityId:b.dataset.bindEntity||'',name,qualifier:$('#entity-qualifier')?.value.trim()||''});await refreshDetail(context.id,context);toast('这个对象确认好了。');return true;}
 if(b.hasAttribute('data-time-open')){const r=state.memories.find(x=>x.id===context.id);$('#detail-body').innerHTML='<p>这件事发生或开始生效在哪一天？</p>'+(r.time?.raw?'<p class="help">当时说的是“'+escapeHtml(r.time.raw)+'”，这句话会一起留着。</p>':'')+'<label>日期<input id="event-date" type="date"></label><div class="button-row"><button class="primary-button" data-save-time>记住日期</button><button class="secondary-button" data-cancel-edit>先放着</button></div>';return true;}
 if(b.hasAttribute('data-save-time')){const d=$('#event-date').value;if(!d){toast('先选一下日期。');return true;}await call('confirmTime',{id:context.id,iso:d});await refreshDetail(context.id,context);toast('日期记下了，当时的原话还留着。');return true;}
 if(b.dataset.source){const token=beginDetail('source',context.id);detailLoading('当时的原话');const m=await fetchSource(b.dataset.source);if(!detailCurrent(token,context.id))return true;$('#detail-body').innerHTML=(m?'<p class="help">'+escapeHtml(time(m.capturedAt||m.created,m.capturedZone))+' · '+escapeHtml(m.capturedZone||'时区未知')+'</p><div class="detail-content">'+escapeHtml(m.content)+'</div>'+attachmentsHtml(m.files||[]):'<p class="help">这次的原话暂时没有找到。</p>')+'<div class="button-row">'+(b.dataset.originConflict?'<button class="secondary-button" data-return-conflict="'+escapeHtml(b.dataset.originConflict)+'">返回两种说法</button>':'<button class="secondary-button" data-cancel-edit>返回这条记忆</button>')+'</div>';return true;}
 return false;
}

window.App={back(){if(voiceActive()){cancelVoice();return true;}const open=$$('dialog[open]');if(open.length){closeSheet(open[open.length-1].id);return true;}if(state.view!=='chat'){changeView('chat');return true;}return false;}};
function resize(){
 const viewport=window.visualViewport;
 const height=Math.max(1,Math.floor(Math.min(innerHeight,viewport&&Math.abs(viewport.scale-1)<.02?viewport.height:innerHeight)));
 document.documentElement.style.setProperty('--vh',height+'px');
 document.documentElement.classList.toggle('viewport-compact',height<480);
 document.documentElement.classList.toggle('viewport-short',height<300);
 requestAnimationFrame(()=>{
  const field=document.activeElement,body=field?.closest('.sheet-body');
  if(!body||!field.matches('input:not([type=checkbox]),textarea'))return;
  const area=body.getBoundingClientRect(),rect=field.getBoundingClientRect(),top=Math.max(0,area.top)+10,bottom=Math.min(height,area.bottom)-10;
  if(rect.bottom>bottom)body.scrollTop+=rect.bottom-bottom;
  else if(rect.top<top)body.scrollTop-=top-rect.top;
 });
}
window.addEventListener('resize',resize);
window.visualViewport?.addEventListener('resize',resize);
window.visualViewport?.addEventListener('scroll',resize);
document.addEventListener('focusin',()=>requestAnimationFrame(resize));
window.XiaoheFeedback.create({call,toast,openSheet,closeSheet,openSettings:()=>showSettings(),beforeOpen:async()=>{if(voiceActive())await cancelVoice();}});
resize();initIcons();
try{
 $('#message-input').value=localStorage.getItem('xiaohe-draft')||'';
 const anchor=JSON.parse(localStorage.getItem('xiaohe-draft-anchor')||'null');
 if(anchor&&Number.isFinite(anchor.capturedAt)&&anchor.capturedAt>0&&typeof anchor.capturedZone==='string'&&typeof anchor.anchorFileId==='string')state.draftAnchor=anchor;
}catch(_){}
renderDraftOrigin();
refresh().catch(error=>{progress(error.message);$('#chat-log').innerHTML='<div class="welcome"><h1>我在，慢慢说。</h1><p>正在打开本机记忆，请稍后再试。</p></div>';});
