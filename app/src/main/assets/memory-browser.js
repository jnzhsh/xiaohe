(function (global) {
  'use strict';

  // Internal browsing UI. Classification is a read-only projection supplied by memoryPage;
  // this module does not rewrite raw tags, merge synonyms, or migrate stored records.
  function create(deps) {
    const {state, $, $$, escapeHtml, icon, recordCard, rememberRows, call, toast} = deps;
    if (typeof state.theme !== 'string') state.theme = '';
    if (!Number.isInteger(state.themeLimit) || state.themeLimit < 1) state.themeLimit = 60;
    if (!Number.isInteger(state.tagLimit) || state.tagLimit < 1) state.tagLimit = 60;

    function cardTags(tags = []) {
      const labels = (Array.isArray(tags) ? tags : []).filter(t => typeof t === 'string' && t.trim());
      const extra = Math.max(0, labels.length - 3);
      return '<div class="tag-row">' + labels.slice(0, 3).map(t => '<span class="tag">' + escapeHtml(t) + '</span>').join('') +
        (extra ? '<span class="tag tag-more">+' + extra + ' 个标签</span>' : '') + '</div>';
    }

    let memorySearchTimer;$('#memory-search').addEventListener('input',()=>{++state.pageSequence;clearTimeout(memorySearchTimer);memorySearchTimer=setTimeout(()=>renderMemories(),180);});
    $$('#memory-status button').forEach(b=>b.addEventListener('click',()=>{state.memoryStatus=b.dataset.status;state.category='';state.theme='';$('#theme-search').value='';$('#tag-search').value='';state.themeLimit=state.tagLimit=60;$$('#memory-status button').forEach(x=>x.classList.toggle('selected',x===b));renderMemories();}));
    $('#memory-view').addEventListener('click',e=>{const b=e.target.closest('button');if(!b)return;if(b.hasAttribute('data-theme')){state.theme=b.dataset.theme;renderMemories();}else if(b.hasAttribute('data-tag-filter')){state.category=b.dataset.tagFilter;renderMemories();}else if(b.hasAttribute('data-clear-tag')){state.category='';renderMemories();}else if(b.hasAttribute('data-more-themes')){state.themeLimit+=60;paintExtraThemes();}else if(b.hasAttribute('data-more-tags')){state.tagLimit+=60;paintTagFilters();}});
    $('#more-themes').addEventListener('toggle',paintExtraThemes);$('#tag-browser').addEventListener('toggle',paintTagFilters);
    $('#theme-search').addEventListener('input',()=>{state.themeLimit=60;paintExtraThemes();});$('#tag-search').addEventListener('input',()=>{state.tagLimit=60;paintTagFilters();});
    function themeButton(theme){return '<button data-theme="'+escapeHtml(theme.key)+'" class="'+(state.theme===theme.key?'selected':'')+'">'+escapeHtml(theme.label)+' <small>'+theme.count+'</small></button>';}
    function visibleThemes(){const themes=state.page.themes||[],visible=themes.slice(0,5);if(state.theme&&!visible.some(t=>t.key===state.theme)){const selected=themes.find(t=>t.key===state.theme)||{key:state.theme,label:state.theme==='untagged'?'未分主题':state.theme.slice(4),count:0};if(visible.length===5)visible.pop();visible.push(selected);}return visible;}
    function labelMatches(label,query){return String(label).normalize('NFC').toLocaleLowerCase().includes(query.trim().normalize('NFC').toLocaleLowerCase());}
    function paintExtraThemes(){const box=$('#extra-themes');if(!$('#more-themes').open){box.innerHTML='';return;}const visible=new Set(visibleThemes().map(t=>t.key)),q=$('#theme-search').value,rows=(state.page.themes||[]).filter(t=>(q||!visible.has(t.key))&&labelMatches(t.label,q));box.innerHTML=rows.slice(0,state.themeLimit).map(themeButton).join('')+(!rows.length?'<p class="help">暂时没有匹配的主题。</p>':'')+(rows.length>state.themeLimit?'<button data-more-themes>再看看一些 · 共 '+rows.length+' 个</button>':'');}
    function paintTagFilters(){const box=$('#tag-filters'),all=state.page.tagCounts||[];$('#tag-browser-summary').textContent='按标签找一找'+(all.length?' · '+all.length+' 个':'');if(!$('#tag-browser').open){box.innerHTML='';return;}const rows=all.filter(t=>labelMatches(t.tag,$('#tag-search').value));box.innerHTML=rows.slice(0,state.tagLimit).map(t=>'<button data-tag-filter="'+escapeHtml(t.tag)+'" class="'+(state.category===t.tag?'selected':'')+'">'+escapeHtml(t.tag)+' <small>'+t.count+'</small></button>').join('')+(!rows.length?'<p class="help">暂时没有匹配的标签。也可以直接在上面搜索原话。</p>':'')+(rows.length>state.tagLimit?'<button data-more-tags>再看看一些 · 共 '+rows.length+' 个</button>':'');}
    function paintMemories(){
     const page=state.page,themes=page.themes||[],visible=visibleThemes();$('#memory-overview').textContent='原话和后来补充的内容，都留在这里。';
     $('#category-filters').innerHTML='<button data-theme="" class="'+(!state.theme?'selected':'')+'">全部主题 <small>'+(page.scopeTotal??page.total??0)+'</small></button>'+visible.map(themeButton).join('');
     const extra=themes.filter(t=>!visible.some(v=>v.key===t.key)).length;$('#more-themes').hidden=!extra;$('#more-themes-summary').textContent='更多主题 · '+extra+' 个';paintExtraThemes();paintTagFilters();
     $('#memory-current-filter').hidden=!state.category;$('#memory-current-filter').innerHTML=state.category?'<span>标签：'+escapeHtml(state.category)+'</span><button class="soft-button" data-clear-tag>取消标签筛选</button>':'';
     $('#memory-list').innerHTML=(page.rows||[]).map(recordCard).join('')+(!page.rows?.length?'<div class="empty-state">'+icon('book')+'<p>'+($('#memory-search').value||state.theme||state.category?'暂时没找到，换个词或主题试试。':'这里的内容，我会慢慢替你收好。')+'</p></div>':'')+(page.hasMore?'<button class="secondary-button load-more" data-more-memories>再看看一些 · 共 '+page.total+' 条</button>':'');
    }
    async function renderMemories(more=false){const token=++state.pageSequence,args={status:state.memoryStatus,tag:state.category,theme:state.theme.startsWith('tag:')?state.theme.slice(4):'',themeMissing:state.theme==='untagged',query:$('#memory-search').value},key=JSON.stringify(args);more=more&&key===state.pageKey;const previous=more?(state.page.rows||[]):[];try{const p=await call('memoryPage',{...args,offset:previous.length,limit:40});if(token!==state.pageSequence)return;if(more)p.rows=[...previous,...p.rows];state.page=p;state.pageKey=key;rememberRows(p.rows);paintMemories();}catch(e){if(token===state.pageSequence)toast(e.message);}}

    // Registration is synchronous, before app.js begins its first bootstrap request.
    // No rendering or bridge calls run during factory initialization.
    return Object.freeze({paint: paintMemories, render: renderMemories, cardTags});
  }

  global.XiaoheMemoryBrowser = Object.freeze({create});
})(window);
