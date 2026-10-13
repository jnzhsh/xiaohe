package app.xiaohe.assistant;

import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Bounded, read-only recall. Neither a prompt projection nor a context reference rewrites a record. */
public final class MemoryRecall {
  private static final int ROW_LIMIT=30,CHAR_BUDGET=60000,POOL_LIMIT=60;
  private static final Pattern POINTER=Pattern.compile("他们|她们|它们|(?<!其)他|她|对方|那位|那家|那件|那次|那条|这个|这些|这件|这条|我的|我们的|我家|我之前|我以前|我上次|我给|我收到|记忆|记过|记录里|收藏过|还记得|(?:我|我们).{0,4}(?:有哪些|有过|存了|记了|收藏|记录|保存)");
  private static final Pattern PERSONAL_ACTION=Pattern.compile("(?:我|我们).{0,3}(?:订|买|寄|给|收|花|存|借|欠)|(?:我|我们)当时");
  private static final Pattern CONTINUATION=Pattern.compile("^(?:那|那么|这样|然后|还有|再|你觉得|你认为|你建议)|上次|上一次|之前|对方|他们|她们|那件|这件|这条|那个|这个");
  private static final Pattern NEW_KNOWLEDGE=Pattern.compile("^\\s*(?:我想知道|我想了解|请解释|解释一下|什么是|介绍一下|讲讲)");
  private static final Pattern FILLER=Pattern.compile("我想知道|我想了解|告诉我|帮我|请问|你觉得|你认为|你建议|应该|应当|需要|能不能|是否|多少|怎样|怎么|如何|为什么|是什么|什么|哪里|哪天|什么时候|目前|现在|轮到|这次|上次|之前|然后|那么|比较|合适|合理|他们|她们|它们|对方|那个|这个|这些|这件|那件|这条|那条|我的|我们的|我们|你们|已经|可以|要给|该给|给我|回我|还有|我|你|他|她|它|该|要|给|回|呢|吗|么|吧|了|的|那|这");
  private static final Pattern NUMBER=Pattern.compile("[0-9]+(?:[.,][0-9]+)*");
  private final Store store;
  private final MemoryLedger ledger;
  public MemoryRecall(Store store){this.store=store;ledger=new MemoryLedger(store);}

  private static final class Stats {int missing,filtered,currentFiltered;boolean referenceCapped,searchTruncated,searchCapped;}
  private static final class TextView {final String text;final JSONArray ranges;final boolean truncated;TextView(String t,JSONArray r,boolean x){text=t;ranges=r;truncated=x;}}
  private static String meaningful(String text){return FILLER.matcher(text).replaceAll(" ").replaceAll("[\\s，。？?！!：:、；;]","");}
  private static List<String> keywords(String text){
    String clean=MemorySchema.normal(meaningful(text));LinkedHashSet<String> words=new LinkedHashSet<>();Matcher latin=Pattern.compile("[a-z0-9]{2,48}").matcher(clean);while(latin.find()&&words.size()<32)words.add(latin.group());
    for(int i=0;i+1<clean.length()&&words.size()<32;i++){char a=clean.charAt(i),b=clean.charAt(i+1);if(Character.isLetterOrDigit(a)&&Character.isLetterOrDigit(b)&&!Character.isSurrogate(a)&&!Character.isSurrogate(b))words.add(clean.substring(i,i+2));}return new ArrayList<>(words);
  }
  private static int overlap(List<String> words,String text){String clean=MemorySchema.normal(text);int score=0;for(String word:words)if(clean.contains(word))score++;return score;}
  private static String slice(String text,int at,int size){int start=Math.max(0,Math.min(at,Math.max(0,text.length()-size)));if(start>0&&Character.isLowSurrogate(text.charAt(start))&&Character.isHighSurrogate(text.charAt(start-1)))start--;int end=Math.min(text.length(),start+size);if(end<text.length()&&end>start&&Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(start,end);}
  private static List<String> windows(String text,int max){LinkedHashSet<String> out=new LinkedHashSet<>();if(text!=null&&!text.trim().isEmpty()){out.add(slice(text,0,160));if(text.length()>160){out.add(slice(text,text.length()/2,160));out.add(slice(text,text.length()-160,160));}}return new ArrayList<>(out).subList(0,Math.min(max,out.size()));}
  private static void ids(LinkedHashSet<String> out,JSONObject m,String key){JSONArray a=m.optJSONArray(key);if(a!=null)for(int i=0;i<a.length()&&out.size()<64;i++){String id=a.optString(i);if(!id.isEmpty())out.add(id);}}
  private boolean allowed(JSONObject r,String question,JSONObject source,Set<String> names)throws Exception {return r!=null&&MemoryLedger.recallEligible(r,question,source)&&ledger.recallMatchesNamed(r,names);}
  private static boolean fromSource(JSONObject r,String id){if(r.optString("sourceId").equals(id))return true;JSONArray sources=r.optJSONArray("sourceIds");if(sources!=null)for(int i=0;i<sources.length();i++)if(sources.optString(i).equals(id))return true;return false;}
  private void sourceFinals(LinkedHashMap<String,JSONObject> group,JSONObject user,String question,JSONObject source,Set<String> names,Stats stats)throws Exception {
    if(user==null||!user.optString("role").equals("user")||user.optString("id").equals(source.optString("id")))return;
    // Pending/removed references can predate a finalized legacy row. Search the known source's text,
    // then verify provenance; a creation-time neighbour is never evidence of shared source.
    for(String text:windows(user.optString("content"),3)){JSONArray matches=ledger.recallSearch(text,60,source,question,false);stats.searchCapped|=matches.length()>=60;
      for(int j=0;j<matches.length();j++){JSONObject r=matches.getJSONObject(j);if(!fromSource(r,user.optString("id"))||!allowed(r,question,source,names))continue;if(group.size()<12)group.putIfAbsent(r.getString("id"),r);else stats.referenceCapped=true;}}
  }
  private LinkedHashMap<String,JSONObject> references(JSONArray recent,String question,JSONObject source,Set<String> names,Stats stats)throws Exception {
    // Only the nearest evidenced turn is the anchor. An older, unrelated conversation cannot become a new topic.
    for(int i=recent.length()-1;i>=0;i--){JSONObject message=recent.getJSONObject(i);if(message.optString("id").equals(source.optString("id")))continue;LinkedHashSet<String> keys=new LinkedHashSet<>();JSONObject originalUser=null;
      if(message.optString("role").equals("assistant")){
        if(!message.optString("error").isEmpty())continue;ids(keys,message,"evidenceIds");JSONObject user=store.one("messages",message.optString("sourceId"));
        if(user!=null&&user.optString("role").equals("user")){originalUser=user;ids(keys,user,"recalledIds");ids(keys,user,"memoryIds");}
      }else if(message.optString("role").equals("user")){originalUser=store.one("messages",message.optString("id"));if(originalUser==null)originalUser=message;ids(keys,originalUser,"recalledIds");ids(keys,originalUser,"memoryIds");}
      if(keys.isEmpty())continue;LinkedHashMap<String,JSONObject> group=new LinkedHashMap<>(),finalSources=new LinkedHashMap<>();boolean needsFinal=false;
      for(String id:keys){JSONObject r=store.one("memories",id);if(r==null){stats.missing++;needsFinal=true;continue;}if(r.optString("processing").equals("pending")){stats.filtered++;needsFinal=true;JSONObject owner=store.one("messages",r.optString("sourceId"));if(owner!=null&&finalSources.size()<4)finalSources.put(owner.getString("id"),owner);continue;}if(!allowed(r,question,source,names)){stats.filtered++;continue;}if(group.size()<12)group.put(id,r);else stats.referenceCapped=true;}
      if(needsFinal){sourceFinals(group,originalUser,question,source,names,stats);for(JSONObject owner:finalSources.values())if(originalUser==null||!owner.optString("id").equals(originalUser.optString("id")))sourceFinals(group,owner,question,source,names,stats);}
      if(!group.isEmpty())return group;
      // An explicit switch of person must not search backwards for some other old referenced topic.
      if(!names.isEmpty())return group;
    }return new LinkedHashMap<>();
  }
  private void add(LinkedHashMap<String,JSONObject> out,Map<String,LinkedHashSet<String>> origins,JSONArray rows,String origin,String question,JSONObject source,Set<String> names,int max)throws Exception {
    for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(!allowed(r,question,source,names))continue;String id=r.getString("id");if(!out.containsKey(id)&&out.size()>=max)continue;out.putIfAbsent(id,r);origins.computeIfAbsent(id,k->new LinkedHashSet<>()).add(origin);}
  }
  private static void take(LinkedHashMap<String,JSONObject> chosen,Collection<JSONObject> rows,int count)throws Exception {int added=0;for(JSONObject r:rows){if(chosen.size()>=ROW_LIMIT||added>=count)break;if(!chosen.containsKey(r.getString("id"))){chosen.put(r.getString("id"),r);added++;}}}
  private static void focusCurrent(LinkedHashMap<String,JSONObject> rows,List<String> words,Set<String> names,Stats stats)throws Exception {
    if(rows.size()<2||words.isEmpty())return;Map<String,Integer> frequency=new HashMap<>(),covered=new HashMap<>();Map<String,String> texts=new HashMap<>();
    for(JSONObject r:rows.values()){String text=MemorySchema.normal(MemorySchema.searchable(r));texts.put(r.getString("id"),text);for(String word:words)if(text.contains(word))frequency.put(word,frequency.getOrDefault(word,0)+1);}
    Map<String,Double> scores=new HashMap<>();int bestCovered=0;double best=0;Set<String> protect=new HashSet<>();Map<String,String> namedBest=new HashMap<>();
    for(JSONObject r:rows.values()){String id=r.getString("id"),text=texts.get(id);int count=0;double score=0;for(String word:words)if(text.contains(word)){count++;score+=1+Math.log((rows.size()+1.0)/(frequency.get(word)+1.0));}covered.put(id,count);scores.put(id,score);bestCovered=Math.max(bestCovered,count);best=Math.max(best,score);
      for(String name:names){boolean linked=r.optString("subjectId").equals(name);JSONArray entities=r.optJSONArray("entityIds");if(entities!=null)for(int i=0;i<entities.length();i++)linked|=entities.optString(i).equals(name);if(linked&&(!namedBest.containsKey(name)||score>scores.get(namedBest.get(name))))namedBest.put(name,id);}}
    // Weak/short questions still retain their available subject evidence. A distinctive current
    // topic with several matching terms can suppress rows matching only a corpus-wide filler word.
    if(bestCovered<3)return;protect.addAll(namedBest.values());int minimum=Math.max(2,bestCovered/3);Iterator<Map.Entry<String,JSONObject>> entries=rows.entrySet().iterator();
    while(entries.hasNext()){String id=entries.next().getKey();if(!protect.contains(id)&&(covered.get(id)<minimum||scores.get(id)<best*.30)){entries.remove();stats.currentFiltered++;}}
  }
  public JSONObject collect(JSONObject source,String original,List<String> materials)throws Exception {synchronized(store){
    if(original==null)original="";if(materials==null)materials=Collections.emptyList();Stats stats=new Stats();Set<String> names=ledger.recallNamedEntities(original);
    LinkedHashMap<String,JSONObject> current=new LinkedHashMap<>(),topic=new LinkedHashMap<>(),material=new LinkedHashMap<>();Map<String,LinkedHashSet<String>> origins=new HashMap<>();
    List<String> queryWindows=windows(original,3);stats.searchTruncated=original.length()>160;
    for(String text:queryWindows){JSONArray found=ledger.recallSearch(text,30,source,original,true);stats.searchCapped|=found.length()>=30;add(current,origins,found,"currentQuery",original,source,names,POOL_LIMIT);}List<String> questionWords=keywords(original);focusCurrent(current,questionWords,names,stats);
    String meaningful=meaningful(original),knowledgeBody=NEW_KNOWLEDGE.matcher(original).replaceFirst("");boolean newKnowledge=NEW_KNOWLEDGE.matcher(original).find()&&!POINTER.matcher(knowledgeBody).find()&&!PERSONAL_ACTION.matcher(knowledgeBody).find();
    boolean continuation=!newKnowledge&&(CONTINUATION.matcher(original).find()||POINTER.matcher(original).find()||meaningful.length()<4||!names.isEmpty());
    JSONArray recent=ledger.recent(source.optString("id"));LinkedHashMap<String,JSONObject> refs=continuation?references(recent,original,source,names,stats):new LinkedHashMap<>();
    StringBuilder seed=new StringBuilder();int anchors=0;for(JSONObject r:refs.values()){if(anchors++==2)break;seed.append(ledger.recallTopic(r)).append(' ');}
    int refScore=overlap(questionWords,seed.toString()),directScore=0;for(JSONObject r:refs.values())refScore=Math.max(refScore,overlap(questionWords,r.optString("content")+" "+r.optString("value")));for(JSONObject r:current.values())directScore=Math.max(directScore,overlap(questionWords,r.optString("title")+" "+r.optString("content")+" "+r.optJSONArray("tags")));
    if(refScore==0&&(directScore>=2||!POINTER.matcher(original).find()&&meaningful.length()>=4)){refs.clear();seed.setLength(0);}
    for(String id:refs.keySet())origins.computeIfAbsent(id,k->new LinkedHashSet<>()).add("recentReference");
    if(!refs.isEmpty()&&!seed.toString().trim().isEmpty())for(String text:windows(seed.toString(),3)){JSONArray found=ledger.recallSearch(text,20,source,original,false);stats.searchCapped|=found.length()>=20;add(topic,origins,found,"topicExpansion",original,source,names,POOL_LIMIT);}
    LinkedHashSet<String> materialWindows=new LinkedHashSet<>();for(int pass=0;pass<3&&materialWindows.size()<12;pass++)for(String text:materials){if(text==null||text.trim().isEmpty())continue;stats.searchTruncated|=text.length()>160;materialWindows.add(slice(text,pass==0?0:pass==1?text.length()/2:Math.max(0,text.length()-160),160));if(materialWindows.size()==12)break;}
    for(String text:materialWindows){JSONArray found=ledger.recallSearch(text,8,source,original,true);stats.searchCapped|=found.length()>=8;add(material,origins,found,"material",original,source,names,POOL_LIMIT);}
    LinkedHashMap<String,JSONObject> chosen=new LinkedHashMap<>();take(chosen,current.values(),14);take(chosen,refs.values(),8);take(chosen,topic.values(),8);take(chosen,material.values(),8);take(chosen,current.values(),ROW_LIMIT);take(chosen,refs.values(),ROW_LIMIT);take(chosen,topic.values(),ROW_LIMIT);take(chosen,material.values(),ROW_LIMIT);
    JSONArray full=new JSONArray(),visible=new JSONArray(),referenceIds=new JSONArray(),sentIds=new JSONArray();JSONObject sourceCounts=new JSONObject();boolean truncated=false;int remaining=chosen.size();
    for(JSONObject record:chosen.values()){
      String id=record.getString("id");JSONObject snapshot=new JSONObject(record.toString());LinkedHashSet<String> via=origins.getOrDefault(id,new LinkedHashSet<>());snapshot.put("recallSources",new JSONArray(via));
      if(MemoryLedger.recallLast(original)&&(!snapshot.optString("kind").equals("event")||!TimeAnchor.resolved(snapshot.optJSONObject("time"))))snapshot.put("recallLastTimeUncertain",true).put("recallCaution","原话可作线索，但事件时间未经核对，不能认定是最近一次或某一天发生。");
      if(snapshot.optString("truthState").equals("historical"))snapshot.put("recallCaution","这是过去有效的说法，不能当作当前事实。");
      int available=CHAR_BUDGET-visible.toString().length()-2,budget=Math.min(8000,available/Math.max(1,remaining--));JSONObject projection=project(snapshot,questionWords,budget);
      if(projection==null){truncated=true;continue;}snapshot.put("recallVisible",projection);full.put(snapshot);visible.put(projection);sentIds.put(id);if(refs.containsKey(id))referenceIds.put(id);
      truncated|=projection.getJSONObject("visibleCoverage").getJSONArray("truncatedFields").length()>0;for(String origin:via)sourceCounts.put(origin,sourceCounts.optInt(origin)+1);
    }
    LinkedHashSet<String> poolIds=new LinkedHashSet<>(current.keySet());poolIds.addAll(refs.keySet());poolIds.addAll(topic.keySet());poolIds.addAll(material.keySet());
    boolean personal=!newKnowledge&&(!names.isEmpty()||POINTER.matcher(original).find()||PERSONAL_ACTION.matcher(original).find()||referenceIds.length()>0);
    JSONObject coverage=new JSONObject().put("complete",false).put("personalQuery",personal).put("rowLimit",ROW_LIMIT).put("charBudget",CHAR_BUDGET).put("sentIds",sentIds).put("sentCount",sentIds.length()).put("promptChars",visible.toString().length()).put("truncated",truncated||stats.searchTruncated||stats.referenceCapped||stats.searchCapped).put("hasMore",poolIds.size()>full.length()||stats.searchCapped).put("searchLimitReached",stats.searchCapped).put("rankedReadLimit",150).put("namedEntityCount",names.size()).put("sourceCounts",sourceCounts).put("missingReferenceCount",stats.missing).put("filteredReferenceCount",stats.filtered).put("filteredCurrentCount",stats.currentFiltered).put("contextFrozen",source.optJSONArray("contextMessageIds")!=null).put("queryWindows",queryWindows.size()).put("materialWindows",materialWindows.size()).put("referenceTopicUsed",referenceIds.length()>0);
    return new JSONObject().put("rows",full).put("promptRows",visible).put("referenceIds",referenceIds).put("coverage",coverage);
  }}

  private static Object compact(Object value,int depth)throws Exception {
    if(value instanceof String)return slice((String)value,0,240);
    if(value instanceof JSONObject){JSONObject out=new JSONObject();if(depth==0)return out;Iterator<String> keys=((JSONObject)value).keys();int count=0;while(keys.hasNext()&&count++<24){String key=keys.next();out.put(key,compact(((JSONObject)value).get(key),depth-1));}return out;}
    if(value instanceof JSONArray){JSONArray out=new JSONArray();if(depth==0)return out;JSONArray a=(JSONArray)value;for(int i=0;i<Math.min(12,a.length());i++)out.put(compact(a.get(i),depth-1));return out;}return value;
  }
  private static TextView excerpt(String text,int max,List<String> words)throws Exception {
    if(max>=text.length())return new TextView(text,new JSONArray().put(new JSONObject().put("start",0).put("end",text.length())),false);
    if(max<32)return new TextView(slice(text,0,Math.max(0,max)),new JSONArray(),true);
    LinkedHashSet<Integer> hits=new LinkedHashSet<>();String lower=text.toLowerCase(Locale.ROOT);for(String word:words){int at=lower.indexOf(word);if(at>=0)hits.add(at);if(hits.size()==8)break;}
    Matcher number=NUMBER.matcher(text);List<Integer> first=new ArrayList<>(),last=new ArrayList<>();while(number.find()){if(first.size()<2)first.add(number.start());last.add(number.start());if(last.size()>2)last.remove(0);}
    LinkedHashSet<Integer> points=new LinkedHashSet<>();int rank=0;for(int at:hits){if(rank++==2)break;points.add(at);}points.addAll(last);points.add(Math.max(0,text.length()-160));points.addAll(first);points.add(0);points.addAll(hits);
    int each=Math.max(48,Math.min(260,(max-16)/Math.min(5,Math.max(1,points.size()))));List<int[]> ranges=new ArrayList<>();int used=0;
    for(int at:points){int start=Math.max(0,at-each/3),end=Math.min(text.length(),start+each);if(start>0&&Character.isLowSurrogate(text.charAt(start)))start--;if(end<text.length()&&end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;boolean redundant=false;for(int[] r:ranges)if(start>=r[0]&&end<=r[1])redundant=true;if(redundant)continue;if(used+end-start+(ranges.isEmpty()?0:7)>max)continue;ranges.add(new int[]{start,end});used+=end-start+(ranges.size()==1?0:7);}
    ranges.sort(Comparator.comparingInt(r->r[0]));List<int[]> merged=new ArrayList<>();for(int[] r:ranges){if(!merged.isEmpty()&&r[0]<=merged.get(merged.size()-1)[1])merged.get(merged.size()-1)[1]=Math.max(merged.get(merged.size()-1)[1],r[1]);else merged.add(r);}
    StringBuilder out=new StringBuilder();JSONArray offsets=new JSONArray();for(int[] r:merged){if(out.length()>0)out.append("\n[…]\n");out.append(text,r[0],r[1]);offsets.put(new JSONObject().put("start",r[0]).put("end",r[1]));}return new TextView(out.toString(),offsets,true);
  }
  private static JSONObject project(JSONObject r,List<String> words,int budget)throws Exception {
    if(budget<350)return null;
    for(int textLimit=Math.max(128,(budget-850)/2);textLimit>=32;textLimit/=2){JSONObject out=new JSONObject(),ranges=new JSONObject();JSONArray shortened=new JSONArray();
      for(String key:new String[]{"id","revision","created","updated","title","dateNormalizedTitle","type","kind","status","truthState","subjectId","subjectLabel","referenceState","structuredReview","predicate","scope","time","sourceAnchor","validFrom","validTo","eventState","dueAt","remindAt","reminderTime","queryCaution","recallCaution","recallLastTimeUncertain","recallSources","sourceId","sourceIds","entityIds","tags"})if(r.has(key)){Object value=compact(r.get(key),4);out.put(key,value);if(!String.valueOf(value).equals(String.valueOf(r.get(key))))shortened.put(key);}
      for(String key:new String[]{"content","dateNormalizedContent","value"})if(r.has(key)){String text=r.optString(key);TextView view=excerpt(text,textLimit,words);out.put(key,view.text);if(view.truncated){shortened.put(key);ranges.put(key,view.ranges);}}
      out.put("id",r.getString("id"));out.put("visibleCoverage",new JSONObject().put("complete",false).put("truncatedFields",shortened).put("originalContentChars",r.optString("content").length()).put("excerptRanges",ranges));if(out.toString().length()<=budget)return out;
    }return null;
  }
}
