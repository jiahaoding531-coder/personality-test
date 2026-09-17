package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本理解提示词的单元测试。
 *
 * <p><b>不联网、不需要 Key、不花一分钱。</b>
 *
 * <p>它验证的是<b>"那些必须存在的东西有没有被误删"</b>——
 * 尤其是**词表**。词表是从枚举生成的，这一条会跟着枚举自动更新；
 * 但如果哪天有人把生成改成手写一份，加了新状态就会悄悄失效，
 * 表现是"这个词表里明明有，AI 却永远用不上"。
 */
class TextIntentPromptBuilderTest {

    private final TextIntentPromptBuilder builder = new TextIntentPromptBuilder();

    @Test
    @DisplayName("【核心】系统提示词必须列全状态词表——漏一个，AI 就永远用不上它")
    void systemPromptListsEveryState() {
        String prompt = builder.systemPrompt();

        for (TravelState state : TravelState.values()) {
            assertTrue(prompt.contains(state.name()),
                    "词表缺了 " + state.name() + "——AI 不可能用上它不知道的词");
            assertTrue(prompt.contains(state.label()),
                    "缺了 " + state.name() + " 的中文名，AI 没法判断用户的话能不能对上");
        }
    }

    @Test
    @DisplayName("系统提示词必须列全会影响地点选择的维度")
    void systemPromptListsEveryRelevantDimension() {
        String prompt = builder.systemPrompt();

        for (TravelDimension dimension : TravelDimension.values()) {
            if (!dimension.affectsPlaceChoice()) {
                continue;
            }
            assertTrue(prompt.contains(dimension.name()),
                    "维度表缺了 " + dimension.name());
        }

        // PLANNING 不参与地点排序，列进去反而会诱导模型往那儿放东西
        assertFalse(prompt.contains(TravelDimension.PLANNING.name()),
                "PLANNING 不该出现在给模型的维度表里");
    }

    @Test
    @DisplayName("必须把状态的'倾向'讲给模型听，而不是给它打分系数")
    void systemPromptExplainsStatesInPlainWords() {
        String prompt = builder.systemPrompt();

        // 说清楚每个状态"更看重什么 / 更避开什么"，模型才判断得了用户的话对不对得上。
        // ⚠️ 但**不能把 -0.8 这种系数给它**——那是算法内部的事，
        //    给出去会诱导它去"算"，而它不该算。
        assertTrue(prompt.contains("更看重") || prompt.contains("更避开"),
                "要说清状态的方向");
        assertFalse(prompt.contains("-0.8"),
                "不该把打分系数暴露给模型");
    }

    @Test
    @DisplayName("必须禁止编造用户没说的条件")
    void systemPromptForbidsInventing() {
        String prompt = builder.systemPrompt();

        // 编一个出来比留空有害得多：用户会看到一个自己从没要求过的条件被悄悄施加
        assertTrue(prompt.contains("只在用户真的说了"), "必须要求只在用户说了的时候才填数字");
        assertTrue(prompt.contains("编"), "必须明确禁止编造");
    }

    @Test
    @DisplayName("必须要求把'用不上的'如实交出来")
    void systemPromptRequiresHonestUnrecognized() {
        String prompt = builder.systemPrompt();

        // 这条是这个功能的诚实所在：说不出来就说"这句我没用上"，
        // 比假装听懂强——用户据此才知道系统的边界在哪
        assertTrue(prompt.contains("unrecognized"), "必须要求输出 unrecognized");
        assertTrue(prompt.contains("用不上") || prompt.contains("没能用上"),
                "要说清 unrecognized 装的是什么");
    }

    @Test
    @DisplayName("必须说明输出是 JSON，且带上 json 字样")
    void systemPromptSpecifiesJsonFormat() {
        String prompt = builder.systemPrompt();

        // 开了 response_format=json_object 时，提示词里必须出现 "json" 字样，
        // 否则多数厂商会直接拒绝这次请求
        assertTrue(prompt.toLowerCase().contains("json"), "必须出现 json 字样");
        for (String field : new String[]{"operations", "op", "value", "unrecognized", "summary"}) {
            assertTrue(prompt.contains(field), "输出格式缺了字段 " + field);
        }
    }

    @Test
    @DisplayName("自然语言必须输出操作，而不是一份新 Chip 列表")
    void systemPromptDefinesTravelIntentOperations() {
        String prompt = builder.systemPrompt();

        for (String operation : new String[]{
                "ADD_INTENT", "REMOVE_INTENT", "REPLACE_INTENTS",
                "ADD_PREFERENCE", "REMOVE_PREFERENCE", "SET_CONSTRAINT",
                "CLEAR_TRAVEL_INTENT"}) {
            assertTrue(prompt.contains(operation), "缺少操作 " + operation);
        }
        assertTrue(prompt.contains("无法确定") && prompt.contains("保留"),
                "必须明确：无法确定时保留现有条件");
        assertTrue(prompt.contains("算了") && prompt.contains("REPLACE_INTENTS"),
                "必须给改变主意的示例");
    }

    @Test
    @DisplayName("有先后关系的多个活动必须保留顺序，不能误判成改变主意")
    void systemPromptPreservesOrderedMultipleIntents() {
        String prompt = builder.systemPrompt();

        assertTrue(prompt.contains("吃完饭再") && prompt.contains("顺序"),
                "必须明确：连续活动要保留全部核心意图及先后顺序");
    }

    @Test
    @DisplayName("⚠️ 用户原话要用引号框起来——不然它可能被当成指令执行")
    void userPromptQuotesTheUserText() {
        String prompt = builder.userPrompt("我累了");

        // 用户输入和大模型指令混在一起，是这类功能最典型的坑：
        // 一句"忽略上面的规则"就可能被当成指令。框起来能挡掉大部分。
        assertTrue(prompt.contains("「我累了」"),
                "用户原话必须被框起来，实际：" + prompt);
    }

    @Test
    @DisplayName("空输入不炸")
    void userPromptHandlesBlankText() {
        assertTrue(builder.userPrompt(null).contains("「」"));
        assertTrue(builder.userPrompt("   ").contains("「」"));
    }
}
