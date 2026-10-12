'use strict';
(() => {
  const configuration=['cfg-base','cfg-model','cfg-key','cfg-asr','cfg-asr-model','cfg-asr-key'];
  const ranges=new WeakMap();let interaction=0;
  async function clipboardCall(method){let timeout;try{return await Promise.race([call(method),new Promise((_,reject)=>{timeout=setTimeout(()=>reject(new Error('这次粘贴没有响应，可以再点一下。')),2200);})]);}finally{clearTimeout(timeout);}}
  const remember=()=>{const field=document.activeElement;if(field?.matches('input,textarea')&&Number.isInteger(field.selectionStart))ranges.set(field,{start:field.selectionStart,end:field.selectionEnd});};
  document.addEventListener('selectionchange',remember,true);
  document.addEventListener('focusout',remember,true);
  document.addEventListener('pointerdown',()=>{remember();interaction++;},true);
  document.addEventListener('keydown',event=>{remember();if(!['Control','Shift','Alt','Meta'].includes(event.key))interaction++;},true);
  for(const id of configuration){
    const field=document.getElementById(id),label=field?.closest('label');if(!label)continue;
    const heading=document.createElement('span');heading.className='paste-heading';
    const caption=document.createElement('span');caption.append(label.firstChild);
    const button=document.createElement('button');button.type='button';button.className='field-paste';button.dataset.paste=id;button.textContent='粘贴';button.setAttribute('aria-label','粘贴到'+caption.textContent);
    heading.append(caption,button);label.insertBefore(heading,field);
    field.setAttribute('autocapitalize','none');field.setAttribute('autocorrect','off');field.spellcheck=false;
  }
  const tools=document.querySelector('.composer-tools>div');
  if(tools){const button=document.createElement('button');button.type='button';button.className='soft-button';button.dataset.paste='message-input';button.textContent='粘贴';tools.append(button);}
  async function paste(button){
    const field=document.getElementById(button.dataset.paste);if(!field||field.disabled||field.readOnly)return;
    const version=interaction,replaceAll=configuration.includes(field.id);let selection=ranges.get(field);
    button.disabled=true;
    try{
      await clipboardCall('preparePaste');
      if(version!==interaction||!field.isConnected)throw new Error('输入位置变了，请在需要的输入框旁再点粘贴。');
      // The composing session may have committed text and moved the caret.
      if(Number.isInteger(field.selectionStart))selection={start:field.selectionStart,end:field.selectionEnd};
      if(typeof window.__xiaohePasteFromHost==='function'){
        await window.__xiaohePasteFromHost(field.id,replaceAll,selection);
      }else{
        const clipboard=await clipboardCall('readClipboard');
        if(version!==interaction||!field.isConnected)throw new Error('输入位置变了，请在需要的输入框旁再点粘贴。');
        field.focus();if(replaceAll)field.select();else if(selection)field.setSelectionRange(selection.start,selection.end);
        if(document.activeElement!==field||!document.execCommand('insertText',false,clipboard.text))throw new Error('这次没有粘贴成功，选好输入框后再试一次。');
      }
      if(field.id==='message-input')saveDraft();
      toast('已粘贴。');
    }catch(error){toast(error.message||'这次没有粘贴成功，可以再点一下。');}
    finally{if(button.isConnected)button.disabled=false;}
  }
  document.addEventListener('click',event=>{const button=event.target.closest('button[data-paste]');if(!button||!event.isTrusted)return;event.preventDefault();if(!button.disabled)paste(button);});
})();
