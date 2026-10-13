(function (global) {
  'use strict';

  // Only the selected record id/revision is stored here. Native code supplies the
  // display context and revalidates the binding when the user's message is saved.
  function create({call, toast, changeView, closeDetails, cancelVoice, voiceActive, isBusy, syncDraftActions, saveTextDraft, hasPendingFiles}) {
    const $ = id => document.getElementById(id);
    const key = 'xiaohe-draft-edit-context';
    let binding = null, metadata = null, status = 'empty', selecting = false, sequence = 0;
    let fallback = $('message-input').placeholder;

    function valid(value) {
      return value && typeof value.id === 'string' && value.id.length > 0 &&
        Number.isSafeInteger(value.revision) && value.revision >= 0;
    }
    function persist() {
      try {
        if (binding) localStorage.setItem(key, JSON.stringify({id: binding.id, revision: binding.revision}));
        else localStorage.removeItem(key);
      } catch (_) { toast('这次修改的关联还没存好，草稿内容仍在。'); }
    }
    function hasContext() { return !!binding; }
    function placeholder(value) {
      if (typeof value === 'string') fallback = value;
      return binding ? '想怎么改，告诉我就好' : fallback;
    }
    function paint() {
      $('chat-edit-context').hidden = !binding;
      $('chat-edit-context').dataset.state = status;
      document.documentElement.classList.toggle('chat-editing', !!binding);
      $('chat-edit-title').textContent = metadata?.title || '这条记忆';
      $('chat-edit-summary').textContent = metadata?.summary || '';
      $('chat-edit-label').textContent = status === 'stale' ? '记录有变化' : status === 'unavailable' ? '先核对一下' : '准备调整';
      $('chat-edit-status').textContent = status === 'checking' ? '我在核对原记录…' :
        status === 'stale' ? '这条记录已有新内容，先重新选择当前版本，再继续说怎么改。' :
        status === 'unavailable' ? '这条记录暂时没读到，可以重新选择，或移除关联继续聊天。' : '';
      $('chat-edit-status').hidden = !['checking', 'stale', 'unavailable'].includes(status);
      $('chat-edit-refresh').hidden = !['stale', 'unavailable'].includes(status);
      $('chat-edit-remove').disabled = isBusy() || selecting;
      $('chat-edit-refresh').disabled = isBusy() || selecting;
      $('message-input').placeholder = placeholder();
      $('send').disabled = isBusy() || voiceActive() || selecting || (!!binding && status !== 'valid');
      syncDraftActions();
    }
    async function read(id) {
      const context = await call('memoryEditContext', {id});
      if (!valid(context) || context.id !== id) throw new Error('原记录暂时没核对好，请重新选择这条。');
      return context;
    }
    async function select(id, canApply, instruction = '') {
      if (isBusy()) throw new Error('先等这条消息收好，再选择想改的记录。');
      const ticket = ++sequence;
      selecting = true; paint();
      try {
        if (voiceActive()) await cancelVoice();
        const context = await read(id);
        if (ticket !== sequence || (canApply && !canApply())) return false;
        binding = {id: context.id, revision: context.revision};
        metadata = {title: context.title || '', summary: context.summary || ''};
        status = 'valid'; persist();
        if (instruction) {
          const input = $('message-input');
          input.value += (input.value.length ? '\n\n' : '') + instruction;
          saveTextDraft();
        }
        closeDetails(); changeView('chat'); paint();
        $('message-input').focus();
        return true;
      } finally {
        if (ticket === sequence) { selecting = false; paint(); }
      }
    }
    async function validate() {
      if (!binding) return;
      const ticket = ++sequence, chosen = {...binding};
      status = 'checking'; paint();
      try {
        const current = await read(chosen.id);
        if (ticket !== sequence || !binding || binding.id !== chosen.id || binding.revision !== chosen.revision) return;
        metadata = {title: current.title || '', summary: current.summary || ''};
        status = current.revision === chosen.revision ? 'valid' : 'stale';
      } catch (_) { if (ticket === sequence && binding) status = 'unavailable'; }
      finally { if (ticket === sequence) paint(); }
    }
    async function restore() {
      try {
        const stored = JSON.parse(localStorage.getItem(key) || 'null');
        if (!valid(stored)) { if (stored) localStorage.removeItem(key); return; }
        binding = {id: stored.id, revision: stored.revision};
      } catch (_) { return; }
      await validate();
    }
    async function prepareSend() {
      if (selecting) throw new Error('我正在核对想改的记录，稍等一下。');
      if (!binding) return null;
      const chosen = {...binding};
      await validate();
      if (!binding || binding.id !== chosen.id || binding.revision !== chosen.revision) throw new Error('修改的关联已经变了，请再确认这次想改哪条。');
      if (status !== 'valid') throw new Error(status === 'stale' ? '原记录有了新内容，先点“重新选择”，再继续修改。' : '原记录还没核对好，输入内容先留着。');
      return chosen;
    }
    function canResume(expectedSequence) {
      return !binding && !selecting && $('message-input').value === '' && !hasPendingFiles() && !voiceActive() &&
        (expectedSequence === undefined || expectedSequence === sequence);
    }
    async function resume(context, expectedSequence) {
      if (!valid(context) || !canResume(expectedSequence)) return false;
      const ticket = sequence;
      let current = null;
      try { current = await read(context.id); } catch (_) {}
      // A user may type or select another target while the read is in flight.
      if (ticket !== sequence || !canResume(expectedSequence)) return false;
      binding = {id: context.id, revision: context.revision};
      metadata = {title: current?.title || context.title || '', summary: current?.summary || context.summary || ''};
      status = !current ? 'unavailable' : current.revision === context.revision ? 'valid' : 'stale';
      persist(); paint();
      return true;
    }
    function clear() {
      ++sequence; binding = null; metadata = null; status = 'empty'; selecting = false;
      persist(); paint();
    }
    function staged(chosen) {
      if (chosen && binding && chosen.id === binding.id && chosen.revision === binding.revision) { clear(); return sequence; }
      return undefined;
    }
    function stageFailed() { if (binding && !selecting) validate().catch(() => {}); }
    $('chat-edit-remove').addEventListener('click', () => { if (!isBusy() && !selecting) clear(); });
    $('chat-edit-refresh').addEventListener('click', () => {
      if (binding && !isBusy() && !selecting) select(binding.id).catch(error => toast(error.message));
    });
    return Object.freeze({select, restore, prepareSend, resume, staged, stageFailed, clear, hasContext, paint, placeholder});
  }

  global.XiaoheChatEdit = Object.freeze({create});
})(window);
