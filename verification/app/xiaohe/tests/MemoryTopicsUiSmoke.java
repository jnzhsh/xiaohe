package app.xiaohe.tests;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.webkit.WebView;
import app.xiaohe.assistant.MainActivity;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Real WebView and native pointer checks, using synthetic data in a separate store. */
public final class MemoryTopicsUiSmoke {
  private static String eval(Instrumentation test,WebView web,String script)throws Exception{return ClipboardSmoke.evaluate(test,web,script);}
  private static void waitFor(Instrumentation test,WebView web,String condition,String message)throws Exception{
    for(int i=0;i<80;i++){if("true".equals(eval(test,web,condition))){Thread.sleep(120);return;}Thread.sleep(100);}
    String flags=eval(test,web,"(()=>{return {records:state.page.total,rows:state.page.rows.length,hasTheme:!!state.theme,studyTheme:state.theme==='tag:学习',hasTag:!!state.category,hasQuery:!!document.getElementById('memory-search').value,secondaryIncluded:state.page.rows.some(r=>r.id==='topics-cross-label'),tap:window.__topicsTapTrace||null,detailOpen:document.getElementById('detail-dialog').open};})()");
    throw new AssertionError(message+"; synthetic state="+flags);
  }
  private static void prepareTap(Instrumentation test,WebView web,String selector,String id)throws Exception{
    Object target=new JSONTokener(eval(test,web,"(()=>{const e=document.querySelector("+JSONObject.quote(selector)+");if(!e)return false;if(!e.id)e.id="+JSONObject.quote(id)+";e.scrollIntoView({block:'center'});return e.id;})()")).nextValue();
    MemoryTopicsSmoke.check(target instanceof String,"Synthetic target exists");
    double x=Double.NaN,y=Double.NaN;int stable=0;boolean ready=false;
    // The page scrolls smoothly. Wait for the intended target, rather than tapping
    // coordinates captured while search results and scroll position are still moving.
    for(int attempt=0;attempt<60;attempt++){
      JSONObject point=new JSONObject(eval(test,web,"(()=>{const e=document.querySelector("+JSONObject.quote(selector)+");if(!e)return {ready:false};if(!e.id)e.id="+JSONObject.quote(id)+";const r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2,hit=document.elementFromPoint(x,y);return {x,y,id:e.id,ready:r.top>=0&&r.bottom<=innerHeight&&!!hit&&(hit===e||e.contains(hit))};})()"));
      if(point.optBoolean("ready")){double nextX=point.getDouble("x"),nextY=point.getDouble("y");stable=Math.abs(nextX-x)<.5&&Math.abs(nextY-y)<.5?stable+1:0;x=nextX;y=nextY;target=point.getString("id");if(stable>=3){ready=true;break;}}else stable=0;
      Thread.sleep(80);
    }
    MemoryTopicsSmoke.check(ready,"Native target geometry must settle and remain visible");
    eval(test,web,"(()=>{window.__topicsExpectedTarget="+JSONObject.quote((String)target)+";window.__topicsTapTrace={matched:false,trusted:false};if(!window.__topicsTraceInstalled){document.addEventListener('click',e=>{const t=e.target.closest('button,summary');window.__topicsTapTrace={matched:!!t&&t.id===window.__topicsExpectedTarget,trusted:e.isTrusted};},true);window.__topicsTraceInstalled=true;}return true;})()");
    ClipboardSmoke.tap(test,web,(String)target);
    waitFor(test,web,"window.__topicsTapTrace.matched&&window.__topicsTapTrace.trusted","Native click reached expected target");
  }
  private static void search(Instrumentation test,WebView web,String id,String value)throws Exception{
    eval(test,web,"(()=>{const e=document.getElementById("+JSONObject.quote(id)+");e.value="+JSONObject.quote(value)+";e.dispatchEvent(new Event('input',{bubbles:true}));return true;})()");
  }
  private static String screenshot(Instrumentation test,String phase)throws Exception{
    File directory=new File(test.getTargetContext().getFilesDir(),"topics-evidence");directory.mkdirs();File file=new File(directory,phase+"-memory.png");Bitmap image=test.getUiAutomation().takeScreenshot();MemoryTopicsSmoke.check(image!=null,"Synthetic screenshot available");try(OutputStream out=new FileOutputStream(file)){MemoryTopicsSmoke.check(image.compress(Bitmap.CompressFormat.PNG,100,out),"Synthetic screenshot saved");}finally{image.recycle();}return file.getAbsolutePath();
  }
  public static JSONObject run(Instrumentation test,String phase)throws Exception{
    MemoryTopicsSmoke.check(phase.equals("before")||phase.equals("after"),"Known UI verification phase");
    try(MemoryTopicsSmoke.Fixture fixture=new MemoryTopicsSmoke.Fixture(test.getTargetContext())){
      MemoryTopicsSmoke.seed(fixture.store,false);fixture.activate();
      Activity activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Field member=activity.getClass().getDeclaredField("web");member.setAccessible(true);WebView web=(WebView)member.get(activity);int orientation=activity.getRequestedOrientation();
      try{
        test.runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        waitFor(test,web,"!!window.App&&state.memoryCount===18&&innerHeight>400","Synthetic page and portrait viewport loaded");
        prepareTap(test,web,".bottom-nav button[data-view=memory]","topics-memory-nav");
        waitFor(test,web,"state.view==='memory'&&state.page.rows.length===18","Memory page uses real native synthetic store");
        eval(test,web,"(()=>{document.getElementById('main').scrollTop=0;return true;})()");Thread.sleep(250);
        JSONObject measurements=new JSONObject(eval(test,web,"(()=>{const b=document.getElementById('category-filters'),list=document.getElementById('memory-list');return {filterButtons:b.querySelectorAll('button').length,themes:(state.page.themes||[]).length,tags:(state.page.tags||[]).length,listTop:list.getBoundingClientRect().top,viewportHeight:innerHeight,records:state.page.total};})()"));
        measurements.put("screenshot",screenshot(test,phase));
        if(phase.equals("before")){
          MemoryTopicsSmoke.check(measurements.getInt("filterButtons")>6&&measurements.getInt("tags")>80,"Baseline exposes detailed tags as many categories");
          MemoryTopicsSmoke.check(measurements.getDouble("listTop")>measurements.getDouble("viewportHeight"),"Baseline categories push records below first viewport");
          return measurements.put("baselineConfirmed",true);
        }
        byte[] records=MemoryTopicsSmoke.digest(fixture.store,"memories"),history=MemoryTopicsSmoke.digest(fixture.store,"versions");
        MemoryTopicsSmoke.check(measurements.getInt("filterButtons")<=6&&measurements.getInt("themes")==8,"Primary browse controls are bounded, counts cover all themes");
        MemoryTopicsSmoke.check(measurements.getDouble("listTop")<measurements.getDouble("viewportHeight")-40,"Records remain visible in initial viewport");
        MemoryTopicsSmoke.check("true".equals(eval(test,web,"!document.getElementById('tag-browser').open&&document.querySelectorAll('#tag-filters button').length===0&&Array.from(document.querySelectorAll('#memory-list .memory-card')).every(c=>c.querySelectorAll('.tag').length<=4)&&!!document.querySelector('[data-memory=topics-card-many] .tag-more')")),"Detailed tags are folded and cards show three with a count");
        prepareTap(test,web,"#memory-list [data-memory=topics-card-many]","topics-card-open");
        waitFor(test,web,"document.getElementById('detail-dialog').open&&document.querySelectorAll('#detail-body>.tag-row .tag').length===7","Full original tags remain in detail");
        prepareTap(test,web,"#detail-dialog button[data-close]","topics-close-detail");
        prepareTap(test,web,"#more-themes-summary","topics-more-summary");
        waitFor(test,web,"document.getElementById('more-themes').open","More themes opens");search(test,web,"theme-search","随手");
        waitFor(test,web,"document.querySelectorAll('#extra-themes button[data-theme]').length===1","All themes can be searched");
        prepareTap(test,web,"#extra-themes button[data-theme]","topics-extra-select");
        waitFor(test,web,"state.theme==='tag:随手灵感'&&state.page.total===2&&state.page.themes.length===8","Selecting a hidden theme preserves other facets");
        prepareTap(test,web,"#category-filters button[data-theme='']","topics-clear-theme");waitFor(test,web,"!state.theme&&state.page.total===18","All themes restores collection");
        prepareTap(test,web,"#more-themes-summary","topics-more-summary");
        prepareTap(test,web,"#tag-browser-summary","topics-tags-summary");waitFor(test,web,"document.getElementById('tag-browser').open","Detailed-tag browser opens");
        search(test,web,"tag-search","细线索79");waitFor(test,web,"document.querySelectorAll('#tag-filters button[data-tag-filter]').length===1","Detailed tag search reaches every original label");
        prepareTap(test,web,"#tag-filters button[data-tag-filter]","topics-fine-select");
        waitFor(test,web,"state.category==='细线索79'&&state.page.total===1&&state.page.rows[0].id==='topics-volume-15'","Any-tag filter uses the actual native query");
        prepareTap(test,web,"#memory-current-filter button[data-clear-tag]","topics-clear-tag");waitFor(test,web,"!state.category&&state.page.total===18","Detailed filter can be cleared outside folded panel");
        search(test,web,"memory-search","不存在qzqx");waitFor(test,web,"state.page.total===0&&state.page.themes.length===0&&state.page.tagCounts.length===0&&!!document.querySelector('#memory-list .empty-state')","Search with no results clears old facets");
        search(test,web,"memory-search","");waitFor(test,web,"state.page.total===18&&state.page.themes.length===8","Clearing search restores full facets");
        prepareTap(test,web,"#category-filters button[data-theme='tag:学习']","topics-primary-study");
        waitFor(test,web,"state.page.total===3&&!state.page.rows.some(r=>r.id==='topics-cross-label')","Primary-theme selection excludes same secondary label");
        MemoryTopicsSmoke.check(Arrays.equals(records,MemoryTopicsSmoke.digest(fixture.store,"memories"))&&Arrays.equals(history,MemoryTopicsSmoke.digest(fixture.store,"versions")),"UI browsing preserves original JSON, tags and history");
        return measurements.put("interactionChecks",true).put("stable",true);
      }finally{test.runOnMainSync(()->{activity.setRequestedOrientation(orientation);activity.finish();});}
    }
  }
}
