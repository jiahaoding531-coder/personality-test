package com.example.personality.ai;

import com.example.personality.exception.AiServiceException;
import com.example.personality.exception.AiRequestRejectedException;
import com.example.personality.exception.InvalidAiCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** 模型只选择依据；返回给用户的文本全部来自服务端目录，不做关键词“验真”。 */
@Component
public class DeepSeekTravelReasonGenerator implements TravelReasonGenerator {
    private static final Logger log=LoggerFactory.getLogger(DeepSeekTravelReasonGenerator.class);
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DeepSeekChatClient chatClient;
    private final TravelReasonPromptBuilder promptBuilder;
    public DeepSeekTravelReasonGenerator(DeepSeekChatClient chatClient,TravelReasonPromptBuilder promptBuilder) {
        this.chatClient=chatClient;this.promptBuilder=promptBuilder;
    }
    @Override public List<RankedReason> generateReasons(AiCredentials credentials,TravelReasonInput input) {
        var catalog=TravelReasonEvidence.from(input);
        try {
            String response=chatClient.complete(credentials,promptBuilder.systemPrompt(),promptBuilder.userPrompt(input),true);
            return parseSelections(response,catalog);
        } catch(AiServiceException | AiRequestRejectedException | InvalidAiCredentialsException unavailable) {
            // 不记录模型原文、凭据、异常链，也不重试付费请求。
            log.warn("推荐依据选择不可用，使用本批次确定性解释");
            return fallback(catalog);
        }
    }
    public static List<RankedReason> fallback(TravelReasonEvidence catalog) {
        var result=new ArrayList<RankedReason>();
        for(int rank:catalog.ranks()) {
            var ids=catalog.fallbackIds(rank);
            result.add(new RankedReason(rank,catalog.render(rank,ids),ids,false));
        }
        return List.copyOf(result);
    }
    /** 严格结构白名单：即使夹带的自由文本“看起来正确”也拒绝整份选择。 */
    static List<RankedReason> parseSelections(String content,TravelReasonEvidence catalog) {
        try {
            if(content==null || content.length()>32768) throw new IllegalArgumentException();
            JsonNode root=JSON.readTree(content);
            if(root==null || !root.isObject() || root.size()!=1 || !root.path("selections").isArray()) throw new IllegalArgumentException();
            JsonNode selections=root.path("selections");
            if(selections.isEmpty() || selections.size()>catalog.ranks().size()) throw new IllegalArgumentException();
            var found=new LinkedHashMap<Integer,RankedReason>();
            for(JsonNode selection:selections) {
                if(!selection.isObject() || selection.size()!=2 || !selection.path("rank").isIntegralNumber()
                        || !selection.path("rank").canConvertToInt() || !selection.path("evidenceIds").isArray()) throw new IllegalArgumentException();
                int rank=selection.path("rank").asInt();
                var ids=new ArrayList<String>();
                for(var id:selection.path("evidenceIds")) {if(!id.isString())throw new IllegalArgumentException();ids.add(id.asString());}
                String rendered=catalog.render(rank,ids);
                if(found.putIfAbsent(rank,new RankedReason(rank,rendered,List.copyOf(ids),true))!=null) throw new IllegalArgumentException();
            }
            // 漏选某个地点时只补确定性依据，不发第二次模型请求。
            for(var fallback:fallback(catalog)) found.putIfAbsent(fallback.rank(),fallback);
            return List.copyOf(found.values());
        } catch(RuntimeException invalid) {
            throw new AiServiceException("AI 返回的依据选择无效，无法核验");
        }
    }
}
