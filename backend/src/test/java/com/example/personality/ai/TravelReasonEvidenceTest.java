package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TravelReasonEvidenceTest {
    @Test void 模型只选择自然优势仍必须显示本批次体力取舍() {
        var catalog=TravelReasonEvidence.from(walkingInput(88,Set.of(TravelState.TIRED),Set.of(),.4));
        String text=catalog.render(1,List.of(id(catalog,1,"STATIC_NATURE")));
        assertTrue(text.contains("自然风光静态标注为80/100"));
        assertTrue(text.contains("有点累了"));
        assertTrue(text.contains("步行强度静态标注为88/100"));
        assertTrue(text.contains("入选不代表省力路线"));
    }

    @ParameterizedTest
    @ValueSource(doubles={.699,.7,1,Double.NaN})
    void 体力提示不受保存状态因子阈值或缺失影响(double stateFactor) {
        var catalog=TravelReasonEvidence.from(walkingInput(88,Set.of(TravelState.TIRED),Set.of(),stateFactor));
        String text=catalog.render(1,List.of(id(catalog,1,"STATIC_NATURE")));
        assertTrue(text.contains("体力提示"));
        assertTrue(text.contains("88/100"));
    }

    @Test void 推测疲劳必须明确来源而不是用户自述() {
        var catalog=TravelReasonEvidence.from(walkingInput(88,Set.of(TravelState.TIRED),Set.of(TravelState.TIRED),.4));
        String text=catalog.render(1,List.of(id(catalog,1,"STATIC_NATURE")));
        assertTrue(text.contains("系统推测"));
        assertTrue(text.contains("有点累了"));
        assertFalse(text.contains("你选择"));
    }

    @Test void 未实际生效的推测疲劳不能产生体力提示() {
        var catalog=TravelReasonEvidence.from(walkingInput(88,Set.of(),Set.of(TravelState.TIRED),.4));
        String text=catalog.render(1,List.of(id(catalog,1,"STATIC_NATURE")));
        assertFalse(text.contains("体力提示"));
        assertFalse(text.contains("有点累了"));
    }

    @ParameterizedTest
    @ValueSource(ints={0,10,88,100})
    void 合法步行标注原样说明而不擅自划分高强度或冲突(int walking) {
        var catalog=TravelReasonEvidence.from(walkingInput(walking,Set.of(TravelState.TIRED),Set.of(),.4));
        String text=catalog.render(1,List.of(id(catalog,1,"STATIC_NATURE")));
        assertTrue(text.contains("步行强度静态标注为"+walking+"/100"));
        assertTrue(text.contains("体力提示"));
        assertFalse(text.contains("高强度"));
        assertFalse(text.contains("存在冲突"));
        assertFalse(text.contains("无法确认"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints={-1,101})
    void 空或越界步行标注不能补成零值或断言省力(Integer walking) {
        var catalog=TravelReasonEvidence.from(walkingInput(walking,Set.of(TravelState.TIRED),Set.of(),.4));
        String text=catalog.render(1,List.of(id(catalog,1,"STATIC_NATURE")));
        assertTrue(text.contains("无法确认是否省力"));
        assertFalse(text.contains("步行强度静态标注为"));
        assertFalse(text.contains("高强度"));
    }

    @Test void 缺少步行维度仍明确无法确认而不是静默省略() {
        var catalog=TravelReasonEvidence.from(input("missing:walking",Map.of(),Set.of(TravelState.TIRED)));
        String text=catalog.render(1,List.of(id(catalog,1,"STATIC_NATURE")));
        assertTrue(text.contains("无法确认是否省力"));
        assertFalse(text.contains("步行强度静态标注为0"));
    }

    @Test void 已选疲劳和步行事实只出现一次且渲染不改变依据() {
        var catalog=TravelReasonEvidence.from(walkingInput(88,Set.of(TravelState.TIRED),Set.of(),.4));
        var before=List.copyOf(catalog.forRank(1));
        var ids=List.of(id(catalog,1,"STATE_TIRED"),id(catalog,1,"STATIC_WALKING"),id(catalog,1,"STATIC_NATURE"));
        String text=catalog.render(1,ids);
        assertEquals(1,text.split("有点累了",-1).length-1);
        assertEquals(1,text.split("88/100",-1).length-1);
        assertEquals(1,text.split("体力提示",-1).length-1);
        assertEquals(text,catalog.render(1,ids));
        assertEquals(before,catalog.forRank(1));
    }

    @Test void 无疲劳条件时不虚构体力提醒() {
        var catalog=TravelReasonEvidence.from(walkingInput(88,Set.of(TravelState.NATURE),Set.of(),.4));
        String text=catalog.render(1,List.of(id(catalog,1,"STATIC_WALKING")));
        assertFalse(text.contains("体力提示"));
        assertFalse(text.contains("有点累了"));
    }

    @Test void 同批次不同地点使用各自步行标注() {
        var base=walkingInput(88,Set.of(TravelState.TIRED),Set.of(),.4);
        var first=base.places().get(0);
        var other=new TravelReasonInput.ExplainedPlace(2,"第二个公开公园","NATURE","静态资料",60,
                first.factors(),0,60,null,null,List.of(),Map.of(TravelDimension.NATURE,60,TravelDimension.WALKING,10));
        var catalog=TravelReasonEvidence.from(new TravelReasonInput(null,null,240,3,null,base.states(),Set.of(),Map.of(),null,
                List.of(first,other),"two:places"));
        String one=catalog.render(1,List.of(id(catalog,1,"STATIC_NATURE")));
        String two=catalog.render(2,List.of(id(catalog,2,"STATIC_NATURE")));
        assertTrue(one.contains("步行强度静态标注为88/100"));
        assertTrue(two.contains("步行强度静态标注为10/100"));
        assertFalse(two.contains("88/100"));
    }

    static TravelReasonInput walkingInput(Integer walking,Set<TravelState> states,Set<TravelState> inferred,double stateFactor) {
        var traits=new EnumMap<TravelDimension,Integer>(TravelDimension.class);
        traits.put(TravelDimension.NATURE,80);traits.put(TravelDimension.WALKING,walking);
        return new TravelReasonInput(null,null,240,3,null,states,inferred,Map.of(TravelDimension.NATURE,.6),null,
                List.of(new TravelReasonInput.ExplainedPlace(1,"公开测试公园","NATURE","静态资料",70,
                        new TravelReasonInput.Factors(.8,.7,.9,stateFactor,1),0,60,null,null,List.of(),traits)),"owner:walking");
    }

    static String id(TravelReasonEvidence catalog,int rank,String code) {
        return catalog.forRank(rank).stream().filter(e->e.code().equals(code)).findFirst().orElseThrow().id();
    }

    @Test void 距离与预算只表述为批次条件不是地点路线或实时票价() {
        var base=input("u1:b1",Map.of(),Set.of());
        var conditions=new TravelReasonInput(null,null,120,3,100,Set.of(),Set.of(),Map.of(),null,base.places(),"u1:b1");
        var catalog=TravelReasonEvidence.from(conditions);
        assertTrue(catalog.forRank(1).stream().anyMatch(e->e.code().equals("DISTANCE_LIMIT")));
        assertTrue(catalog.forRank(1).stream().anyMatch(e->e.code().equals("BUDGET_LIMIT")));
        var ids=catalog.forRank(1).stream().filter(e->e.code().endsWith("_LIMIT")).map(TravelReasonEvidence.Evidence::id).toList();
        String text=catalog.render(1,ids);
        assertTrue(text.contains("3公里"));assertTrue(text.contains("100元"));
        assertTrue(text.contains("不代表导航路线"));assertTrue(text.contains("不代表实时票价"));
    }
    @Test void 静态热闹标注始终明确来源而不是实时事实() {
        var catalog=TravelReasonEvidence.from(input("user1:batch1",Map.of(),Set.of()));
        var evidence=catalog.forRank(1).stream().filter(e->e.code().equals("STATIC_CROWD_TOLERANCE")).findFirst().orElseThrow();
        String text=catalog.render(1,List.of(evidence.id()));
        assertTrue(text.contains("静态标注"));
        assertTrue(text.contains("不代表实时人流"));
        assertFalse(text.contains("人群没那么挤"));
    }
    @Test void 同地点不同批次条件产生不同解释而非统一套话() {
        var a=TravelReasonEvidence.from(input("u1:b1",Map.of(TravelDimension.NATURE,.7),Set.of(TravelState.TIRED)));
        var b=TravelReasonEvidence.from(input("u1:b2",Map.of(TravelDimension.CULTURE,.6),Set.of()));
        var first=a.render(1,a.fallbackIds(1));
        var second=b.render(1,b.fallbackIds(1));
        assertNotEquals(first,second);
        assertTrue(first.contains("自然风光"));
        assertTrue(second.contains("人文历史"));
    }
    @Test void 跨批次跨用户或跨地点的ID均不能使用() {
        var a=TravelReasonEvidence.from(input("u1:b1",Map.of(),Set.of()));
        var b=TravelReasonEvidence.from(input("u2:b1",Map.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,()->b.render(1,List.of(a.forRank(1).get(0).id())));
        assertThrows(IllegalArgumentException.class,()->a.render(2,List.of(a.forRank(1).get(0).id())));
    }
    @Test void 新缓存验证版本内容批次且从依据重新渲染而非信任文本() {
        var a=TravelReasonEvidence.from(input("u1:b1",Map.of(),Set.of()));
        var b=TravelReasonEvidence.from(input("u1:b2",Map.of(),Set.of()));
        var ids=a.fallbackIds(1);
        var cache=a.encodeCache(1,ids,false);
        assertEquals(ids,a.readCache(1,cache).orElseThrow().evidenceIds());
        assertTrue(b.readCache(1,cache).isEmpty());
        assertTrue(a.readCache(1,"人群没那么挤").isEmpty());
        assertTrue(a.readCache(1,cache.replace("\"version\":1","\"version\":999")).isEmpty());
        assertTrue(a.readCache(1,cache.substring(0,cache.length()-1)+",\"reason\":\"现场无人\"}").isEmpty());
    }
    @Test void 模型只能选择本批次提供的受控ID不能夹带任何句子() {
        var input=input("u1:b1",Map.of(),Set.of());
        var catalog=TravelReasonEvidence.from(input);
        var id=catalog.forRank(1).get(0).id();
        var json="{\"selections\":[{\"rank\":1,\"evidenceIds\":[\""+id+"\"]}]}";
        var result=DeepSeekTravelReasonGenerator.parseSelections(json,catalog);
        assertEquals(catalog.render(1,List.of(id)),result.get(0).reason());
        assertThrows(RuntimeException.class,()->DeepSeekTravelReasonGenerator.parseSelections(
                json.replace("\"evidenceIds\"","\"reason\":\"今天没什么人\",\"evidenceIds\""),catalog));
        assertThrows(RuntimeException.class,()->DeepSeekTravelReasonGenerator.parseSelections(
                json.replace(id,"FORGED"),catalog));
    }
    @Test void 未记录的评分与缺失天气不能被当成满分或天气好() {
        var base=input("u1:b1",Map.of(),Set.of());
        var p=base.places().get(0);
        var unknown=new TravelReasonInput.ExplainedPlace(1,p.name(),p.category(),p.description(),70,
                new TravelReasonInput.Factors(Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN),
                0,60,null,null,List.of(),p.staticTraits());
        var input=new TravelReasonInput(null,null,240,3,null,Set.of(),Set.of(),Map.of(),null,List.of(unknown),"u1:b1");
        var catalog=TravelReasonEvidence.from(input);
        assertTrue(catalog.forRank(1).stream().noneMatch(e->e.kind()==TravelReasonEvidence.Kind.SAVED_SCORE));
        var prompt=new TravelReasonPromptBuilder().userPrompt(input);
        var root=JsonMapper.builder().build().readTree(prompt);
        assertTrue(root.path("unknownInformation").isArray());
        assertFalse(prompt.contains("天气舒适"));
    }
    static TravelReasonInput input(String scope,Map<TravelDimension,Double> biases,Set<TravelState> states) {
        return new TravelReasonInput(null,null,240,3,null,states,Set.of(),biases,null,
                List.of(new TravelReasonInput.ExplainedPlace(1,"公开测试公园","NATURE","不用排队的宣传语不得作为事实",
                        70,new TravelReasonInput.Factors(.8,.7,.9,.8,1),0,60,null,null,List.of(),
                        Map.of(TravelDimension.NATURE,80,TravelDimension.CULTURE,30,TravelDimension.CROWD_TOLERANCE,20))),scope);
    }
}
