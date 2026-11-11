package com.example.personality.ai;

import com.example.personality.exception.AiServiceException;
import com.example.personality.domain.TravelState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 受控选择协议测试，模型不再拥有自由文本输出权限。 */
class DeepSeekTravelReasonGeneratorTest {
    @Test void 模型选满三项优势仍显示必显体力提示而不增加选择ID() {
        var catalog=TravelReasonEvidence.from(TravelReasonEvidenceTest.walkingInput(88,Set.of(TravelState.TIRED),Set.of(),.4));
        var ids=List.of(TravelReasonEvidenceTest.id(catalog,1,"PREFERENCE_NATURE"),
                TravelReasonEvidenceTest.id(catalog,1,"STATIC_NATURE"),TravelReasonEvidenceTest.id(catalog,1,"SCORE_INTEREST"));
        String body="{\"selections\":[{\"rank\":1,\"evidenceIds\":[\""+String.join("\",\"",ids)+"\"]}]}";
        var selected=DeepSeekTravelReasonGenerator.parseSelections(body,catalog).get(0);
        assertTrue(selected.modelSelected());
        assertEquals(ids,selected.evidenceIds());
        assertTrue(selected.reason().contains("88/100"));
        assertTrue(selected.reason().contains("入选不代表省力路线"));
    }

    @Test void 确定性降级保留自然偏好并补充体力取舍() {
        var catalog=TravelReasonEvidence.from(TravelReasonEvidenceTest.walkingInput(88,Set.of(TravelState.TIRED),Set.of(),.4));
        var fallback=DeepSeekTravelReasonGenerator.fallback(catalog).get(0);
        assertFalse(fallback.modelSelected());
        assertTrue(fallback.evidenceIds().size()<=3);
        assertTrue(fallback.reason().contains("增加自然风光"));
        assertTrue(fallback.reason().contains("步行强度静态标注为88/100"));
        assertTrue(fallback.reason().contains("入选不代表省力路线"));
    }

    private final TravelReasonEvidence catalog=TravelReasonEvidence.from(
            TravelReasonEvidenceTest.input("owner1:batch1",Map.of(),Set.of()));
    private String selection(String fields) {return "{\"selections\":[{"+fields+"}]}";}
    private String valid() {return selection("\"rank\":1,\"evidenceIds\":[\""+catalog.forRank(1).get(0).id()+"\"]");}

    @Test void 正常选择只渲染已有依据不采纳模型句子() {
        var result=DeepSeekTravelReasonGenerator.parseSelections(valid(),catalog);
        assertEquals(1,result.size());
        assertTrue(result.get(0).modelSelected());
        assertEquals(catalog.render(1,result.get(0).evidenceIds()),result.get(0).reason());
    }
    @ParameterizedTest
    @ValueSource(strings={"null","[]","{} {}","{broken","{\"selections\":[]}","{\"data\":[]}",
            "{\"reasons\":[{\"rank\":1,\"reason\":\"现场没人\"}]}",
            "{\"selections\":[{\"rank\":\"1\",\"evidenceIds\":[]}]}",
            "{\"selections\":[{\"rank\":1.5,\"evidenceIds\":[]}]}",
            "{\"selections\":[{\"rank\":4,\"evidenceIds\":[\"forged\"]}]}",
            "{\"selections\":[{\"rank\":1,\"evidenceIds\":null}]}",
            "{\"selections\":[{\"rank\":1,\"evidenceIds\":[1]}]}"})
    void 非法结构空选择越界及自由文本均拒绝(String raw) {
        assertThrows(AiServiceException.class,()->DeepSeekTravelReasonGenerator.parseSelections(raw,catalog));
    }
    @Test void 重复名次和重复依据不静默接受() {
        var one=valid().substring("{\"selections\":[".length(),valid().length()-2);
        assertThrows(AiServiceException.class,()->DeepSeekTravelReasonGenerator.parseSelections(
                "{\"selections\":["+one+","+one+"]}",catalog));
        String id=catalog.forRank(1).get(0).id();
        assertThrows(AiServiceException.class,()->DeepSeekTravelReasonGenerator.parseSelections(
                selection("\"rank\":1,\"evidenceIds\":[\""+id+"\",\""+id+"\"]"),catalog));
    }
    @Test void 超过三项依据拒绝() {
        var ids=catalog.forRank(1).stream().limit(4).map(e->"\""+e.id()+"\"").toList();
        assertThrows(AiServiceException.class,()->DeepSeekTravelReasonGenerator.parseSelections(
                selection("\"rank\":1,\"evidenceIds\":["+String.join(",",ids)+"]"),catalog));
    }
    @Test void 缺少地点的选择用本地依据补齐不再请求模型() {
        var base=TravelReasonTrustRegressionTest.input();var p=base.places().get(0);
        var other=new TravelReasonInput.ExplainedPlace(2,"另一个公开公园","CULTURE","资料",50,
                p.factors(),0,30,null,null,List.of());
        var input=new TravelReasonInput(null,null,120,3,null,Set.of(),Set.of(),Map.of(),null,List.of(p,other));
        var multi=TravelReasonEvidence.from(input);
        var id=multi.forRank(1).get(0).id();
        var result=DeepSeekTravelReasonGenerator.parseSelections(
                selection("\"rank\":1,\"evidenceIds\":[\""+id+"\"]"),multi);
        assertEquals(2,result.size());
        assertTrue(result.stream().anyMatch(r->r.rank()==2 && !r.modelSelected()));
    }
}
