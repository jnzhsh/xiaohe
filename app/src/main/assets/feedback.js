(function (global) {
  'use strict';

  // Feedback has its own local draft and GitHub connection. It never uses AI or
  // browser storage; the native bridge sanitizes and persists every saved draft.
  function create({call, toast, openSheet, closeSheet, openSettings, beforeOpen}) {
    const $ = id => document.getElementById(id);
    const dialog = $('feedback-dialog');
    let draft = null, preview = null, receipt = null;
    let config = {hasToken: false, repo: 'jnzhsh/xiaohe'};
    let nativeState = 'editing', mode = 'loading', busy = false;
    let revision = 0, epoch = 0, requestedMessageId = '', saveTimer, replacementRequested = false;
    let saveChain = Promise.resolve(), settingsEpoch = 0, settingsBusy = false;

    function note(text) { $('feedback-status').textContent = text || ''; }
    function readDraft() {
      return {
        id: draft.id, title: $('feedback-title').value,
        description: $('feedback-description').value, messageId: draft.messageId || '',
        includeConversation: !!draft.messageId && $('feedback-context').checked,
        includeDiagnostics: $('feedback-diagnostics').checked
      };
    }
    function updateConfig(value) {
      if (value) config = {hasToken: !!value.hasToken, repo: String(value.repo || 'jnzhsh/xiaohe')};
      $('feedback-repo').textContent = config.repo;
      $('feedback-settings-repo').textContent = config.repo;
      $('feedback-token').placeholder = config.hasToken ? '已保存；留空继续使用' : '在这里粘贴 GitHub token';
      $('feedback-clear-token').disabled = !config.hasToken;
      $('feedback-token-state').textContent = config.hasToken ? 'GitHub 凭据已保存在手机。' : '填写后，才能在应用里创建反馈。';
    }
    function paint() {
      $('feedback-editor').hidden = mode !== 'editing';
      $('feedback-preview-section').hidden = mode !== 'preview';
      $('feedback-result').hidden = mode !== 'submitted';
      $('feedback-uncertain').hidden = mode !== 'uncertain';
      $('feedback-new-draft').hidden = mode === 'loading';
      $('feedback-reload').hidden = mode !== 'loading' || busy;
      $('feedback-new-draft').disabled = busy || mode === 'uncertain';
      $('feedback-replace-confirm').hidden = !replacementRequested || busy || !['editing', 'preview'].includes(mode);
      $('feedback-draft-help').hidden = !['editing', 'preview'].includes(mode);
      for (const id of ['feedback-title', 'feedback-description', 'feedback-context', 'feedback-diagnostics', 'feedback-prepare', 'feedback-edit', 'feedback-check']) $(id).disabled = busy;
      $('feedback-context').disabled = busy || !draft?.messageId;
      $('feedback-conversation-option').hidden = !draft?.messageId;
      $('feedback-submit').disabled = busy || !config.hasToken || !preview || nativeState !== 'ready';
      $('feedback-submit').textContent = busy && mode === 'preview' ? '正在创建…' : '确认创建公开 Issue';
      $('feedback-token-needed').hidden = config.hasToken || mode !== 'preview';
      $('feedback-draft-context-note').hidden = !draft?.messageId || (!!requestedMessageId && draft.messageId === requestedMessageId);
      $('feedback-preview-title').textContent = preview?.title || '';
      $('feedback-preview-body').textContent = preview?.body || '';
      $('feedback-redaction-note').hidden = !preview?.redacted;
      $('feedback-uncertain-body').textContent = preview?.body || '';
      $('feedback-receipt-number').textContent = receipt?.number ? 'Issue #' + receipt.number : '';
      $('feedback-open-issue').disabled = !receipt?.url;
    }
    function adopt(packet, showPreview = false) {
      if (!packet || !packet.draft) throw new Error('反馈草稿暂时没有打开，可以再试一次。');
      draft = packet.draft;
      preview = packet.preview && typeof packet.preview.hash === 'string' ? packet.preview : null;
      receipt = packet.receipt || null;
      nativeState = packet.state || 'editing';
      updateConfig(packet.config);
      $('feedback-title').value = draft.title || '';
      $('feedback-description').value = draft.description || '';
      $('feedback-context').checked = !!draft.messageId && draft.includeConversation === true;
      $('feedback-diagnostics').checked = draft.includeDiagnostics !== false;
      mode = nativeState === 'submitted' ? 'submitted' : nativeState === 'uncertain' ? 'uncertain' : showPreview && preview ? 'preview' : 'editing';
      paint();
      return packet;
    }
    function queueSave(snapshot, version) {
      const request = saveChain.catch(() => {}).then(() => call('feedbackSave', {draft: snapshot}));
      saveChain = request;
      request.then(packet => {
        if (dialog.open && version === revision && !busy && mode === 'editing' && draft?.id === snapshot.id) {
          adopt(packet);
          note('草稿已留在手机，还没有发出去。');
        }
      }, () => {});
      return request;
    }
    function flushSave() {
      clearTimeout(saveTimer);
      if (!draft || mode !== 'editing') return saveChain.catch(() => {});
      return queueSave(readDraft(), revision);
    }
    function changed() {
      if (!draft || busy || mode !== 'editing') return;
      revision++;
      preview = null;
      nativeState = 'editing';
      note('先替你留成草稿…');
      clearTimeout(saveTimer);
      saveTimer = setTimeout(() => flushSave().catch(() => {
        if (dialog.open) note('这次草稿没能保存，输入的内容还在，可以再试。');
      }), 300);
    }
    async function open(messageId = '', newDraft = false) {
      const ticket = ++epoch;
      replacementRequested = false;
      requestedMessageId = String(messageId || '');
      if (beforeOpen) await beforeOpen();
      if (ticket !== epoch) return;
      closeSheet('settings-dialog');
      mode = 'loading'; busy = true; paint();
      note('我在取出手机上的草稿…'); openSheet('feedback-dialog');
      try {
        await saveChain.catch(() => {});
        const packet = await call('feedbackOpen', {messageId: requestedMessageId, newDraft: !!newDraft});
        if (ticket !== epoch) return;
        revision++; adopt(packet, packet.state === 'ready');
        note(packet.message || (mode === 'uncertain' ? '上次的提交结果还没确认，我不会自动再发一遍。' : mode === 'submitted' ? '反馈已经创建，可以打开看看。' : '还没发出的内容，先替你留着。'));
      } catch (error) {
        if (ticket === epoch) note(error.message || '这次没能打开反馈草稿，请稍后再试。');
      } finally {
        if (ticket === epoch) { busy = false; paint(); }
      }
    }
    async function recover(error) {
      const message = error.message || '这次操作没能完成，草稿还留在手机。';
      try {
        const packet = await call('feedbackOpen', {messageId: requestedMessageId});
        adopt(packet, packet.state === 'ready');
      } catch (_) {}
      note(message);
    }
    async function prepare() {
      if (busy || !draft || mode !== 'editing') return;
      clearTimeout(saveTimer); busy = true; paint(); note('我在整理将要公开的内容…');
      const ticket = epoch;
      try {
        await queueSave(readDraft(), revision);
        const packet = await call('feedbackPrepare', {draft: readDraft()});
        if (ticket !== epoch) return;
        adopt(packet, true);
        note('下面是最终要公开的标题和正文，确认后才会创建。');
      } catch (error) {
        if (ticket === epoch) await recover(error);
      } finally { if (ticket === epoch) { busy = false; paint(); } }
    }
    async function submit() {
      if (busy || mode !== 'preview' || !preview || nativeState !== 'ready' || !config.hasToken) return;
      busy = true; paint(); note('正在把你确认的内容交给 GitHub…');
      const ticket = epoch, payload = {draftId: preview.draftId, previewHash: preview.hash};
      try {
        const packet = await call('feedbackSubmit', payload);
        if (ticket !== epoch) return;
        adopt(packet, packet.state === 'ready');
        note(packet.message || (mode === 'submitted' ? '创建好了，谢谢你告诉我哪里需要改进。' : '提交结果还没确认，请先检查状态。'));
      } catch (error) { if (ticket === epoch) await recover(error); }
      finally { if (ticket === epoch) { busy = false; paint(); } }
    }
    async function check() {
      if (busy || !draft || mode !== 'uncertain') return;
      busy = true; paint(); note('我在检查这份反馈是否已经创建…'); const ticket = epoch;
      try {
        const packet = await call('feedbackCheck', {draftId: draft.id});
        if (ticket !== epoch) return;
        adopt(packet, false); note(packet.message || (mode === 'submitted' ? '找到了这份反馈，可以打开看看。' : '还没确认结果，草稿和这次提交内容都留着。'));
      } catch (error) { if (ticket === epoch) await recover(error); }
      finally { if (ticket === epoch) { busy = false; paint(); } }
    }
    function attachReplyButtons() {
      $('chat-log').querySelectorAll('.message.assistant[data-message-id]').forEach(message => {
        const content = message.querySelector('.message-content');
        if (!content || content.querySelector('[data-feedback-message]')) return;
        const tools = document.createElement('div'), button = document.createElement('button');
        tools.className = 'feedback-reply-tools'; button.type = 'button'; button.className = 'soft-button';
        button.dataset.feedbackMessage = message.dataset.messageId;
        button.textContent = '反馈这条回应'; tools.append(button); content.append(tools);
      });
    }
    async function loadConfig() {
      const ticket = ++settingsEpoch; $('feedback-token').value = '';
      try {
        const packet = await call('feedbackOpen', {});
        if (ticket === settingsEpoch) updateConfig(packet.config);
      } catch (_) { if (ticket === settingsEpoch) $('feedback-token-state').textContent = 'GitHub 设置暂时没有读到，可以再打开设置试试。'; }
    }
    async function configure(clear = false) {
      if (settingsBusy) return;
      const token = $('feedback-token').value.trim();
      if (!clear && !token) { $('feedback-token-state').textContent = config.hasToken ? '当前凭据还留着；更换时再填写新的 token。' : '先在密码框里填写 GitHub token。'; return; }
      settingsBusy = true; ++settingsEpoch;
      for (const id of ['feedback-save-token', 'feedback-clear-token', 'feedback-test-connection']) $(id).disabled = true;
      try {
        const packet = await call('feedbackConfigure', {token: clear ? '' : token, clearToken: clear});
        updateConfig(packet.config);
        if (packet.draft) adopt(packet, mode === 'preview');
        $('feedback-token-state').textContent = clear ? 'GitHub 凭据已清除，反馈草稿仍保留。' : 'GitHub 凭据保存好了，可以检查连接。';
      } catch (error) { $('feedback-token-state').textContent = error.message || '这次凭据没能保存，可以再试。'; }
      finally {
        settingsBusy = false;
        $('feedback-token').value = '';
        $('feedback-save-token').disabled = false; $('feedback-test-connection').disabled = false;
        $('feedback-clear-token').disabled = !config.hasToken;
      }
    }
    async function connection() {
      const button = $('feedback-test-connection'); if (button.disabled || settingsBusy) return;
      settingsBusy = true; ++settingsEpoch;
      for (const id of ['feedback-save-token', 'feedback-clear-token', 'feedback-test-connection']) $(id).disabled = true;
      $('feedback-token-state').textContent = '我在检查 GitHub 连接…';
      try {
        const packet = await call('feedbackConnection', {});
        $('feedback-token-state').textContent = packet.message || (packet.ok ? '连上了，实际创建结果以提交后收到的 Issue 编号为准。' : '这次没连上，请检查凭据。');
      } catch (error) { $('feedback-token-state').textContent = error.message || '这次没能检查连接。'; }
      finally {
        settingsBusy = false; button.disabled = false;
        $('feedback-save-token').disabled = false; $('feedback-clear-token').disabled = !config.hasToken;
      }
    }
    $('chat-log').addEventListener('click', event => {
      const button = event.target.closest('[data-feedback-message]');
      if (button) open(button.dataset.feedbackMessage).catch(error => toast(error.message));
    });
    new MutationObserver(attachReplyButtons).observe($('chat-log'), {childList: true, subtree: true});
    attachReplyButtons();
    $('feedback-open').addEventListener('click', () => open().catch(error => toast(error.message)));
    $('feedback-reload').addEventListener('click', () => open(requestedMessageId).catch(error => toast(error.message)));
    $('feedback-prepare').addEventListener('click', prepare);
    $('feedback-submit').addEventListener('click', submit);
    $('feedback-check').addEventListener('click', check);
    $('feedback-edit').addEventListener('click', () => { if (!busy) { mode = 'editing'; preview = null; paint(); note('改好后，再看一次最终内容。'); } });
    $('feedback-new-draft').addEventListener('click', () => {
      if (busy || mode === 'uncertain') return;
      const unsent = ['editing', 'preview'].includes(mode) && draft && ($('feedback-title').value.trim() || $('feedback-description').value.trim());
      if (unsent) { replacementRequested = true; paint(); $('feedback-replace-confirm').scrollIntoView({block: 'center'}); }
      else open(requestedMessageId, true).catch(error => toast(error.message));
    });
    $('feedback-keep-draft').addEventListener('click', () => { replacementRequested = false; paint(); });
    $('feedback-confirm-new').addEventListener('click', () => {
      if (replacementRequested && !busy && mode !== 'uncertain') open(requestedMessageId, true).catch(error => toast(error.message));
    });
    $('feedback-connect').addEventListener('click', () => { closeSheet('feedback-dialog'); openSettings(); $('feedback-settings').scrollIntoView({block: 'start'}); });
    $('feedback-open-issue').addEventListener('click', () => { if (receipt?.url) call('openUrl', {url: receipt.url}).catch(error => toast(error.message)); });
    $('feedback-save-token').addEventListener('click', () => configure(false));
    $('feedback-clear-token').addEventListener('click', () => configure(true));
    $('feedback-test-connection').addEventListener('click', connection);
    $('feedback-token-guide').addEventListener('click', () => call('openUrl', {url: 'https://github.com/settings/personal-access-tokens/new'}).catch(error => toast(error.message)));
    for (const id of ['feedback-title', 'feedback-description']) $(id).addEventListener('input', changed);
    for (const id of ['feedback-context', 'feedback-diagnostics']) $(id).addEventListener('change', changed);
    dialog.addEventListener('close', () => { ++epoch; flushSave().catch(() => toast('这次反馈草稿没能保存，重新打开后请检查内容。')); });
    const settings = $('settings-dialog');
    new MutationObserver(() => { if (settings.open) loadConfig(); else { ++settingsEpoch; $('feedback-token').value = ''; } }).observe(settings, {attributes: true, attributeFilter: ['open']});
    updateConfig();
    return Object.freeze({open});
  }

  global.XiaoheFeedback = Object.freeze({create});
})(window);
