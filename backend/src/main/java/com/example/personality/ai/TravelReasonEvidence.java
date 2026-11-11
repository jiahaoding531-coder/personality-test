package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 解释的受控依据目录：模型只能选择 ID，所有面向用户的句子都由本类产生。
 * 这是纯 Java 值对象（类似 Python dataclass + 纯函数），不访问网络、数据库或画像。
 * 缓存只保存版本、批次内容指纹与依据 ID，不保存可信度未知的模型原文。
 */
public final class TravelReasonEvidence {
    public static final int VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public enum Kind { STATIC_LABEL, USER_PREFERENCE, BATCH_CONDITION, SAVED_SCORE }
    public record Evidence(String id, String code, Kind kind, String text) { }
    public record CacheSelection(List<String> evidenceIds, boolean modelSelected) { }
    private final Map<Integer,List<Evidence>> byRank;
    private final Map<Integer,String> names;
    private final String fingerprint;
    private final Map<Integer,WalkingNotice> walkingNotices;

    /** 类似只读的Python dataclass：只保留本批次来源和该地点的有效静态数值。 */
    private record WalkingNotice(boolean inferred,Integer walking) {
        String text() {
            String source=inferred?"本批次系统推测“有点累了”":"本批次你选择了“有点累了”";
            if(walking==null) {
                return "体力提示："+source+"；当前地点资料缺少有效的步行强度标注，无法确认是否省力。";
            }
            return "体力提示："+source+"；当前地点资料的步行强度静态标注为"+walking+
                    "/100。请结合体力权衡，入选不代表省力路线。";
        }
    }

    private TravelReasonEvidence(TravelReasonInput input) {
        Map<Integer,List<Evidence>> draft=new TreeMap<>();
        Map<Integer,String> placeNames=new TreeMap<>();
        Map<Integer,WalkingNotice> notices=new TreeMap<>();
        for(var p:input.places()) {
            if(draft.containsKey(p.rank())) throw new IllegalArgumentException("推荐名次重复");
            // 不设置“高强度”阈值，不改评分；0是有效标注，缺失或越界才视为未知。
            // 这些来源已在原目录的状态/静态项中参与指纹，不新增模型ID或使v1缓存失效。
            if(input.states().contains(TravelState.TIRED)) {
                Integer walking=p.staticTraits().get(TravelDimension.WALKING);
                notices.put(p.rank(),new WalkingNotice(input.inferredStates().contains(TravelState.TIRED),
                        walking!=null && walking>=0 && walking<=100?walking:null));
            }
            var facts=new ArrayList<Evidence>();
            // 只使用批次保存的临时偏好。不推断缺失的长期画像/反馈快照。
            for(var dimension:TravelDimension.values()) {
                Double bias=input.customBiases().get(dimension);
                if(bias!=null && Double.isFinite(bias) && bias!=0) {
                    add(facts,"PREFERENCE_"+dimension,Kind.USER_PREFERENCE,
                            "本批次临时偏好希望"+(bias>0?"增加":"减少")+dimension.label()+"的比重。");
                }
            }
            for(var state:TravelState.values()) {
                if(!input.states().contains(state)) continue;
                String source=input.inferredStates().contains(state)?"系统推测条件":"用户条件";
                add(facts,"STATE_"+state,Kind.BATCH_CONDITION,"本批次"+source+"包含“"+state.label()+"”。");
            }
            // 静态标注明确标注来源；热闹/步行属性不冒充现场人流或路线。
            for(var dimension:TravelDimension.values()) {
                Integer value=p.staticTraits().get(dimension);
                if(value==null || value<0 || value>100 || !dimension.affectsPlaceChoice()) continue;
                String label=switch(dimension) {
                    case CROWD_TOLERANCE -> "热闹程度";
                    case WALKING -> "步行强度";
                    default -> dimension.label();
                };
                String qualifier=dimension==TravelDimension.CROWD_TOLERANCE?"，不代表实时人流":"";
                add(facts,"STATIC_"+dimension,Kind.STATIC_LABEL,"当前地点资料的"+label+"静态标注为"+value+"/100"+qualifier+"。");
            }
            if(p.factors()!=null && known(p.factors().interest())) {
                double interest=p.factors().interest();
                add(facts,"SCORE_INTEREST",Kind.SAVED_SCORE,"本批次保存的综合兴趣匹配依据"+
                        (interest>=.6?"较强":interest>=.4?"一般":"较弱，入选还结合了其他因素")+"；不推测未保存的细分偏好。");
            }
            if(p.factors()!=null && known(p.factors().state()) && !input.states().isEmpty()) {
                add(facts,"SCORE_STATE",Kind.SAVED_SCORE,p.factors().state()<.7?
                        "本批次保存的状态适配存在折减，请结合本次需要权衡。":"本批次保存的状态适配没有明显折减。");
            }
            if(Double.isFinite(input.maxDistanceKm()) && input.maxDistanceKm()>0) {
                add(facts,"DISTANCE_LIMIT",Kind.BATCH_CONDITION,"本批次最远距离条件为"+
                        java.math.BigDecimal.valueOf(input.maxDistanceKm()).stripTrailingZeros().toPlainString()+
                        "公里，不代表导航路线距离。");
            }
            if(input.maxTicketPrice()!=null && input.maxTicketPrice()>=0) {
                add(facts,"BUDGET_LIMIT",Kind.BATCH_CONDITION,"本批次门票预算上限为"+
                        input.maxTicketPrice()+"元，不代表实时票价或其他花费。");
            }
            if(input.remainingMinutes()>0 && p.suggestedMinutes()>0) {
                add(facts,"TIME",Kind.BATCH_CONDITION,"本批次可用时间为"+input.remainingMinutes()+
                        "分钟，当前地点资料建议停留"+p.suggestedMinutes()+"分钟；不包含已核实的路程或排队耗时。");
            }
            add(facts,"BATCH",Kind.BATCH_CONDITION,"该地点属于本批次实际推荐结果，仍以本批次已设置的条件为准。");
            draft.put(p.rank(),facts);
            placeNames.put(p.rank(),p.name());
        }
        // JSON 字段及集合顺序固定，避免 HashMap/Set 遍历顺序造成缓存误失效。
        fingerprint=sha256(JSON.writeValueAsString(List.of(VERSION,input.evidenceScope(),placeNames,draft)));
        // 每8位加分隔符，避免64位随机hex偶然被既有隐私清洗识别成手机号/身份证。
        // 不放宽全局隐私保护；缓存仍保留完整原始指纹。
        String externalFingerprint=String.join("-",fingerprint.split("(?<=\\G.{8})"));
        Map<Integer,List<Evidence>> bound=new TreeMap<>();
        for(var entry:draft.entrySet()) {
            var facts=new ArrayList<Evidence>();
            for(var fact:entry.getValue()) facts.add(new Evidence(
                    externalFingerprint+":"+entry.getKey()+":"+fact.code(),fact.code(),fact.kind(),fact.text()));
            bound.put(entry.getKey(),List.copyOf(facts));
        }
        byRank=Collections.unmodifiableMap(bound);
        names=Collections.unmodifiableMap(placeNames);
        walkingNotices=Collections.unmodifiableMap(notices);
    }
    public static TravelReasonEvidence from(TravelReasonInput input) {return new TravelReasonEvidence(input);}
    public Set<Integer> ranks(){return byRank.keySet();}
    public String fingerprint(){return fingerprint;}
    public List<Evidence> forRank(int rank){return byRank.getOrDefault(rank,List.of());}
    public String name(int rank){return names.get(rank);}

    /** 同一选取必须完全属于同一推荐行；不允许空、重复或超量依据。 */
    public String render(int rank,List<String> ids) {
        if(!byRank.containsKey(rank) || ids==null || ids.isEmpty() || ids.size()>3
                || new HashSet<>(ids).size()!=ids.size()) throw new IllegalArgumentException("依据选择非法");
        StringBuilder text=new StringBuilder(names.get(rank)).append("：");
        WalkingNotice notice=walkingNotices.get(rank);
        for(String id:ids) {
            var fact=forRank(rank).stream().filter(e->e.id().equals(id)).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("依据不属于本批次推荐"));
            // 仍逐个验证ID；合并到下方必显提示，避免状态或88/100等事实重复出现。
            if(notice!=null && (fact.code().equals("STATE_TIRED") || fact.code().equals("STATIC_WALKING"))) continue;
            text.append(fact.text());
        }
        // 模型选择、降级与可信缓存都走同一渲染入口，不能由模型决定省略体力提示。
        if(notice!=null) text.append(notice.text());
        return text.toString();
    }
    public List<String> fallbackIds(int rank) {
        var facts=forRank(rank);
        if(facts.isEmpty()) throw new IllegalArgumentException("推荐名次不存在");
        var chosen=new ArrayList<String>();
        for(var f:facts) if((f.kind()==Kind.USER_PREFERENCE || f.code().startsWith("STATE_")) && chosen.size()<1) chosen.add(f.id());
        // 根据本次偏好优先选对应静态属性，没偏好时选该地点数值最高的静态标注。
        Evidence staticFact=null;
        for(var f:facts) if(f.code().startsWith("PREFERENCE_")) {
            String code="STATIC_"+f.code().substring("PREFERENCE_".length());
            staticFact=facts.stream().filter(e->e.code().equals(code)).findFirst().orElse(null);
            if(staticFact!=null) break;
        }
        if(staticFact==null) staticFact=facts.stream().filter(f->f.kind()==Kind.STATIC_LABEL
                && !f.code().equals("STATIC_CROWD_TOLERANCE") && !f.code().equals("STATIC_WALKING"))
                .max(Comparator.comparingInt(TravelReasonEvidence::staticValue)).orElse(null);
        if(staticFact!=null) chosen.add(staticFact.id());
        Evidence score=facts.stream().filter(f->f.code().equals("SCORE_INTEREST")).findFirst().orElse(null);
        if(chosen.size()<2 && score!=null) chosen.add(score.id());
        Evidence time=facts.stream().filter(f->f.code().equals("TIME")).findFirst().orElse(facts.get(facts.size()-1));
        if(chosen.size()<3) chosen.add(time.id());
        return List.copyOf(chosen);
    }
    public String encodeCache(int rank,List<String> ids,boolean modelSelected) {
        render(rank,ids);
        var envelope=new LinkedHashMap<String,Object>();
        envelope.put("version",VERSION);envelope.put("fingerprint",fingerprint);envelope.put("rank",rank);
        envelope.put("evidenceIds",ids);envelope.put("modelSelected",modelSelected);
        return JSON.writeValueAsString(envelope);
    }
    public Optional<CacheSelection> readCache(int rank,String raw) {
        if(raw==null || raw.length()>4096) return Optional.empty();
        try {
            JsonNode root=JSON.readTree(raw);
            if(root==null || !root.isObject() || root.size()!=5 || !root.path("version").isIntegralNumber()
                    || root.path("version").asInt()!=VERSION || !root.path("fingerprint").isString()
                    || !fingerprint.equals(root.path("fingerprint").asString())
                    || !root.path("rank").isIntegralNumber() || root.path("rank").asInt()!=rank
                    || !root.path("modelSelected").isBoolean() || !root.path("evidenceIds").isArray()) return Optional.empty();
            var ids=new ArrayList<String>();
            for(var id:root.path("evidenceIds")) {if(!id.isString())return Optional.empty();ids.add(id.asString());}
            render(rank,ids);
            return Optional.of(new CacheSelection(List.copyOf(ids),root.path("modelSelected").asBoolean()));
        } catch(RuntimeException invalid) {return Optional.empty();}
    }
    private static boolean known(double v){return Double.isFinite(v) && v>=0 && v<=1;}
    private static void add(List<Evidence> facts,String code,Kind kind,String text){facts.add(new Evidence("",code,kind,text));}
    private static int staticValue(Evidence e) {
        int end=e.text().indexOf("/100");int start=e.text().lastIndexOf('为',end);
        return Integer.parseInt(e.text().substring(start+1,end));
    }
    private static String sha256(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("缺少标准SHA-256算法",impossible);}
    }
}
