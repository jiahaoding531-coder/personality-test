package com.example.personality.ai;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** 只发送受控依据清单，不发送精确位置、个人身份、自由描述或最新反馈。 */
@Component
public class TravelReasonPromptBuilder {
    public String systemPrompt() {
        return """
                你是旅行推荐依据选择器。任务是选择最能解释“为什么是它”的真实依据，不创作事实。
                只能为每个给定rank选择该rank已有的1至3个evidenceIds，优先体现用户偏好、
                地点静态标签及本批次条件的差异；不得修改推荐算法的排名。
                STATIC_LABEL是地点资料静态标注，不代表实时客流；USER_PREFERENCE是本批次保存的偏好；
                BATCH_CONDITION是本批次条件（系统推测不是用户自述）；SAVED_SCORE是已保存评分依据。
                unknownInformation没有可用依据，禁止编造或补选。数字只是标识或已有资料值。
                不得返回reason、解释、额外字段或任何面向用户的句子。
                只输出JSON对象：{"selections":[{"rank":1,"evidenceIds":["给定ID"]}]}。
                """;
    }
    public String userPrompt(TravelReasonInput input) {
        var catalog=TravelReasonEvidence.from(input);
        var places=new ArrayList<Map<String,Object>>();
        for(int rank:catalog.ranks()) {
            var place=new LinkedHashMap<String,Object>();
            place.put("rank",rank);place.put("name",catalog.name(rank));place.put("evidence",catalog.forRank(rank));
            places.add(place);
        }
        var payload=new LinkedHashMap<String,Object>();
        payload.put("evidenceVersion",TravelReasonEvidence.VERSION);
        payload.put("unknownInformation",List.of("实时人流","排队情况","实时营业状态","实时价格",
                "实时天气与舒适程度","交通状况","导航路线及耗时","未保存的历史细分画像"));
        payload.put("places",places);
        return JsonMapper.builder().build().writeValueAsString(payload);
    }
}
