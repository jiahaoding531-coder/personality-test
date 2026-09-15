package com.example.personality.ai;

import com.example.personality.domain.ScoredPlace;
import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.domain.Weather;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推荐理由提示词的单元测试。
 *
 * <p><b>不联网、不需要 Key、不花一分钱</b>——{@link TravelReasonPromptBuilder}
 * 是纯逻辑类。这正是把提示词从调用代码里拆出来的第一个好处。
 *
 * <p>它验证的不是"提示词写得好不好"（那要靠人看输出），而是
 * <b>"那些必须存在的东西有没有被误删"</b>。提示词是一大段拼出来的文本，
 * 改动时很容易顺手删掉某一行而毫无察觉——而删掉的可能是
 * "不许提数字"这条约束，或者"这是系统推测的"那个标记。
 * 这类回归只有测试能拦住。
 */
class TravelReasonPromptBuilderTest {

    private final TravelReasonPromptBuilder builder = new TravelReasonPromptBuilder();

    // ==========================================================
    // 系统提示词：必须包含的约束
    // ==========================================================

    @Test
    @DisplayName("系统提示词必须包含四条安全边界（不提数字 / 不提算法词 / 不编造 / 不把猜测当事实）")
    void systemPrompt_containsBoundaries() {
        String prompt = builder.systemPrompt();

        assertTrue(prompt.contains("数字"), "必须明确禁止出现数字");
        assertTrue(prompt.contains("算法"), "必须禁止提「算法」「得分」「因子」这类词");
        assertTrue(prompt.contains("编"), "必须明确禁止编造输入里没有的信息");
        assertTrue(prompt.contains("系统推测"), "必须要求区分系统推断的状态和用户说的");
    }

    @Test
    @DisplayName("系统提示词必须要求写清「为什么是它」，而不只是夸它好")
    void systemPrompt_demandsComparison() {
        String prompt = builder.systemPrompt();

        // 这是 AI 真正能加的价值：乘法式只能回答"它自己好不好"，
        // 回答不了"它凭什么排第一"
        assertTrue(prompt.contains("为什么是它"), "必须明确要求解释为什么是它");
        assertTrue(prompt.contains("比另外两个"), "必须要求点出它比另外两个强在哪");
    }

    @Test
    @DisplayName("系统提示词必须说明 JSON 输出格式，且带上 json 字样")
    void systemPrompt_specifiesJsonFormat() {
        String prompt = builder.systemPrompt();

        // ⚠️ 开了 response_format=json_object 时，提示词里必须出现 "json"
        // 字样，否则多数厂商会直接拒绝这次请求。这条不是风格问题，是硬要求。
        assertTrue(prompt.toLowerCase().contains("json"), "必须出现 json 字样");
        assertTrue(prompt.contains("rank"), "必须说明 reasons 数组里要有 rank 字段");
    }

    // ==========================================================
    // 用户提示词：必须喂进去的事实
    // ==========================================================

    @Test
    @DisplayName("【核心】五个因子必须逐个摆出来——没有它们，AI 只能编")
    void userPrompt_includesAllFiveFactors() {
        String prompt = builder.userPrompt(input(Weather.of("中雨", 18)));

        assertTrue(prompt.contains("兴趣匹配"), "缺兴趣匹配");
        assertTrue(prompt.contains("距离"), "缺距离");
        assertTrue(prompt.contains("口碑质量"), "缺质量");
        assertTrue(prompt.contains("此刻状态"), "缺状态");
        assertTrue(prompt.contains("天气"), "缺天气");
    }

    @Test
    @DisplayName("用户提示词必须带上处境：几点、还剩多久、天气、地名")
    void userPrompt_includesSituation() {
        String prompt = builder.userPrompt(input(Weather.of("中雨", 18)));

        assertTrue(prompt.contains("杭州市西湖区北山街附近"), "缺地名");
        assertTrue(prompt.contains("约 240 分钟"), "缺剩余时间");
        assertTrue(prompt.contains("中雨"), "缺天气");
        // 几点也要给——"现在 18 点"是解释"为什么推饭馆"的前提
        assertTrue(prompt.contains("18 点"), "缺当前时间");
    }

    @Test
    @DisplayName("⚠️ 系统推断的状态必须被单独标出来")
    void userPrompt_marksInferredStates() {
        // 这一条守的是"不把猜测当事实"。AI 转述时若说成"你说了你累了"，
        // 而实际是系统按时间猜的，就是在编造用户的意图。
        String prompt = builder.userPrompt(input(null));

        assertTrue(prompt.contains("有点累了"), "状态的中文名应该在");
        assertTrue(prompt.contains("系统推测"), "系统推断的状态必须被标明");
    }

    @Test
    @DisplayName("用户自己的状态不该被标成「系统推测」")
    void userPrompt_doesNotMarkUserStatedStates() {
        // 用户明确说的状态不该带这个标记。全标的话，AI 会以为
        // 用户什么都没说，写出来的话就变得含糊。
        TravelReasonInput onlyUserStated = new TravelReasonInput(
                "杭州市西湖区北山街附近", LocalTime.of(18, 0), 240, 10.0, null,
                Set.of(TravelState.TIRED),   // 生效的状态
                Set.of(),                    // 但没有一个是推断的
                Map.of(),                    // 也没有词表外的原始偏向
                null,
                List.of(place(1, "西湖·苏堤")));

        String prompt = builder.userPrompt(onlyUserStated);

        assertTrue(prompt.contains("有点累了"));
        assertFalse(prompt.contains("系统推测"), "没打开推断时不该出现这个标记");
    }

    @Test
    @DisplayName("用户在乎的维度要摆出来，AI 才能写出具体的「合你口味」")
    void userPrompt_includesDimensionMatches() {
        String prompt = builder.userPrompt(input(null));

        assertTrue(prompt.contains("自然风光"), "维度中文名应该在");
        assertTrue(prompt.contains("用户在意程度"), "要说清用户多在乎这个维度");
    }

    @Test
    @DisplayName("地点名和介绍、门票、营业时间都要给")
    void userPrompt_includesPlaceFacts() {
        String prompt = builder.userPrompt(input(null));

        assertTrue(prompt.contains("西湖·苏堤"));
        assertTrue(prompt.contains("两侧都是水面和柳树"));
        assertTrue(prompt.contains("免费"));
        assertTrue(prompt.contains("全天开放"));
    }

    @Test
    @DisplayName("词表覆盖不了的部分也要告诉模型——用户特意打了那句话")
    void userPrompt_includesCustomBiases() {
        // 用户特意打了一句话，理由里却只字不提，他会觉得"我说了它根本没听"。
        // 而那句话往往恰恰是他最在意的。
        String prompt = builder.userPrompt(input(Weather.of("阴", 24)));

        assertTrue(prompt.contains("用户还特别提到"), "缺了原始偏向这一段");
        assertTrue(prompt.contains(TravelDimension.CROWD_TOLERANCE.label()), "要说出是哪个维度");
        // ⚠️ 给的是方向（"要更少一些"）而不是 -0.50 这个数字：
        //    数字对模型没有意义，而且系统提示词本来就要求它不要提数字
        assertTrue(prompt.contains("要更少一些"), "负偏向要翻译成「更少」而不是给数字");
        assertTrue(prompt.contains("要更多一些"), "正偏向要翻译成「更多」");
    }

    @Test
    @DisplayName("没有原始偏向时不出现那一段")
    void userPrompt_omitsCustomBiasesWhenAbsent() {
        TravelReasonInput noBiases = new TravelReasonInput(
                "杭州市西湖区北山街附近", LocalTime.of(18, 0), 240, 10.0, null,
                Set.of(TravelState.TIRED), Set.of(), Map.of(), null,
                List.of(place(1, "西湖·苏堤")));

        assertFalse(builder.userPrompt(noBiases).contains("用户还特别提到"));
    }

    @Test
    @DisplayName("没有天气时不能编一个天气出来")
    void userPrompt_omitsWeatherWhenAbsent() {
        String prompt = builder.userPrompt(input(null));

        assertFalse(prompt.contains("天气："), "没有天气就不该有天气这一行");
    }

    // ==========================================================
    // 夹具
    // ==========================================================

    private static TravelReasonInput input(Weather weather) {
        return new TravelReasonInput(
                "杭州市西湖区北山街附近",
                LocalTime.of(18, 0),
                240,
                10.0,
                50,
                Set.of(TravelState.TIRED),
                // 标成"系统推断的"——用来验证提示词会不会把它标出来
                Set.of(TravelState.TIRED),
                // 词表覆盖不了的那部分（"想找个特别小众的地方"之类）
                Map.of(TravelDimension.CROWD_TOLERANCE, -0.5, TravelDimension.HIDDEN_GEMS, 0.6),
                weather,
                List.of(place(1, "西湖·苏堤"), place(2, "楼外楼（孤山店）")));
    }

    private static TravelReasonInput.ExplainedPlace place(int rank, String name) {
        return new TravelReasonInput.ExplainedPlace(
                rank,
                name,
                "NATURE",
                "两侧都是水面和柳树",
                63,
                new TravelReasonInput.Factors(0.64, 1.0, 0.99, 0.5, 0.5),
                0,
                120,
                null,
                null,
                List.of(new ScoredPlace.MatchedDimension(TravelDimension.NATURE, 100, 95, 9500.0)));
    }
}
