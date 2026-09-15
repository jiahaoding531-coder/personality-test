package com.example.personality.ai;

import com.example.personality.domain.ScoredPlace;
import com.example.personality.domain.TravelState;
import com.example.personality.domain.Weather;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把「这批推荐是怎么算出来的」组织成大模型能读懂的提示词。
 *
 * <p><b>和 {@code AiPromptBuilder} 一样，这是个纯逻辑类</b>——不碰网络、
 * 不碰数据库、不碰 Spring 容器（{@code @Component} 只是个注册标签）。
 * 所以它能被单元测试直接覆盖，<b>不花钱、不联网</b>。
 *
 * <h2>核心原则：给足事实，而不是下禁令</h2>
 *
 * <p>这条是从 {@code AiPromptBuilder} 那里继承来的经验：
 * <b>「把事实喂给它」比「要求它别乱猜」更可靠——前者是信息，后者是禁令。</b>
 *
 * <p>所以下面会把五个因子、每个维度的匹配、当时的处境全都摆出来，
 * 让模型没什么可编的余地；"不许编造"那条约束只是最后一道保险。
 */
@Component
public class TravelReasonPromptBuilder {

    /** 类别的中文名。给模型看，不要让它猜 "MUSEUM" 是什么意思。 */
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "NATURE", "自然风光",
            "CULTURE", "人文历史",
            "FOOD", "美食",
            "PHOTO", "摄影出片",
            "DISTRICT", "街区商圈",
            "MUSEUM", "博物馆展馆",
            "MARKET", "市集夜市"
    );

    private static final String SYSTEM_PROMPT = """
            你是一个懂杭州的本地朋友。用户刚拿到一份"此刻去哪"的推荐，
            你会收到这批推荐的完整依据，请为**每一个**推荐写一句推荐语。

            写作要求：
            1. 每个地点一到两句，整段不超过 150 字。用户是在手机上看，写长了没人读。
            2. 用第二人称「你」，像朋友顺口推荐，不要用「建议前往」「值得一游」这种公文腔。
            3. 必须说清**为什么是它**。至少有一处要点出它比另外两个强在哪——\
            比如更近、更省时间、更符合你的口味。只说"这里很好"等于没说。
            4. 不要只夸。哪一项拖了后腿可以坦诚说（「就是离你有点远」）——\
            用户要的是判断依据，不是广告。
            5. 写法上要像人在说话，不要罗列要点，不要用 Markdown 标题或列表符号。

            必须遵守的边界（违反任何一条都算输出失败）：
            - 不要出现任何数字：分数、百分比、公里、分钟、元、排名数字，都不要提。
              数字用户自己能在下面的明细里看到，你重复一遍只会显得机械。
            - 不要提「算法」「得分」「因子」「权重」「匹配度」这类词，直接说人话。
            - 只能使用给你的信息。不知道的（排队多久、周末有什么活动、有没有停车位）
              一个字都不要写。编出来的细节会让用户白跑一趟。
            - 如果某个状态标明是「系统推测」，不要写成是用户说的。

            输出格式：只输出一个 JSON 对象，不要任何解释文字，形如
            {"reasons":[{"rank":1,"reason":"..."},{"rank":2,"reason":"..."}]}
            reasons 里每个名次都要有一条，rank 用给你的名次。
            """;

    /** 系统提示词是常量，直接暴露给测试和调用方。 */
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * 用户提示词：把这批推荐的全部依据摆出来。
     *
     * <h2>为什么要把因子给得这么细</h2>
     *
     * <p>因为<b>"为什么是它"这个问题，答案就在因子里</b>：
     * 兴趣分高说明合口味，距离系数低说明有点远，状态系数低说明"你说累了、
     * 而这里费腿"，天气系数低说明"今天下雨、而这里是户外的"。
     *
     * <p>不给这些，模型就只能看着地点名和一句介绍硬编——
     * 那正是"没有拆解，AI 只能编"的意思。
     */
    public String userPrompt(TravelReasonInput input) {
        StringBuilder sb = new StringBuilder();

        appendSituation(sb, input);
        appendPlaces(sb, input);

        sb.append("\n请为上面每个名次写一句推荐语，按要求的 JSON 格式输出。");
        return sb.toString();
    }

    // ==========================================================
    // 处境
    // ==========================================================

    /**
     * 这批推荐是在什么情况下算出来的。
     *
     * <p>⚠️ 系统推断的状态要单独标出来。这是这个项目的产品原则：
     * <b>系统替你做的判断，都要能被看见</b>——而且这里更进一步，
     * 连"转述"都不许把猜的混成用户说的。
     */
    private void appendSituation(StringBuilder sb, TravelReasonInput input) {
        sb.append("【这次的情况】\n");

        if (input.locationLabel() != null && !input.locationLabel().isBlank()) {
            sb.append("- 用户在：").append(input.locationLabel()).append('\n');
        }
        if (input.now() != null) {
            sb.append("- 时间：").append(input.now().getHour()).append(" 点")
              .append(input.now().getMinute() == 0 ? "" : " " + input.now().getMinute() + " 分")
              .append('\n');
        }
        sb.append("- 还能玩：约 ").append(input.remainingMinutes()).append(" 分钟\n");
        sb.append("- 愿意走的最远距离：").append(formatNumber(input.maxDistanceKm())).append(" 公里\n");
        if (input.maxTicketPrice() != null) {
            sb.append("- 门票预算：").append(input.maxTicketPrice()).append(" 元以内\n");
        }

        if (!input.states().isEmpty()) {
            sb.append("- 用户此刻的状态：");
            for (TravelState state : input.states()) {
                sb.append(state.label());
                // ⚠️ 是系统猜的就明确标出来，别让它被当成用户说的转述出去
                if (input.inferredStates().contains(state)) {
                    sb.append("（⚠️ 这是系统推测的，不是用户说的）");
                }
                sb.append(' ');
            }
            sb.append('\n');
        }

        Weather weather = input.weather();
        if (weather != null) {
            sb.append("- 天气：").append(weather.condition())
              .append(' ').append(formatNumber(weather.temperature())).append(" 度\n");
        }
        sb.append('\n');
    }

    // ==========================================================
    // 地点
    // ==========================================================

    private void appendPlaces(StringBuilder sb, TravelReasonInput input) {
        sb.append("【推荐结果与依据】\n");

        for (TravelReasonInput.ExplainedPlace place : input.places()) {
            sb.append("\n第 ").append(place.rank()).append(" 名：").append(place.name());
            String category = CATEGORY_LABELS.get(place.category());
            if (category != null) {
                sb.append("（").append(category).append("）");
            }
            sb.append('\n');

            if (place.description() != null && !place.description().isBlank()) {
                sb.append("  介绍：").append(place.description()).append('\n');
            }

            appendFactors(sb, place);

            sb.append("  门票：").append(place.ticketPrice() == 0 ? "免费" : place.ticketPrice() + " 元")
              .append("；建议停留 ").append(place.suggestedMinutes()).append(" 分钟")
              .append("；营业时间：")
              .append(place.openFrom() == null ? "全天开放" : place.openFrom() + " - " + place.openTo())
              .append('\n');

            appendMatches(sb, place);
        }
    }

    /**
     * 五个因子，附带一句人话解释。
     *
     * <p>解释是<b>写在数据旁边的</b>，因为"距离系数 0.76"对一个模型来说
     * 不说明任何事，而"0.76，有点距离但不是特别远"就很好懂。
     * 与其在别处写一段"因子含义说明"，不如就地给。
     */
    private void appendFactors(StringBuilder sb, TravelReasonInput.ExplainedPlace place) {
        TravelReasonInput.Factors f = place.factors();

        sb.append("  综合得分：").append(place.scorePercent()).append(" 分（满分 100）\n");
        sb.append("  得分构成（每项都是乘数，越接近 1 越好）：\n");
        sb.append("    - 兴趣匹配 ").append(ratio(f.interest()))
          .append(' ').append(describeInterest(f.interest())).append('\n');
        sb.append("    - 距离 ").append(ratio(f.distance()))
          .append(' ').append(describeDistance(f.distance())).append('\n');
        sb.append("    - 口碑质量 ").append(ratio(f.quality())).append('\n');
        sb.append("    - 此刻状态 ").append(ratio(f.state()))
          .append(' ').append(describeState(f.state())).append('\n');
        sb.append("    - 天气 ").append(ratio(f.weather()))
          .append(' ').append(describeWeather(f.weather())).append('\n');
    }

    /**
     * "你在乎的维度上它拿了多少"。
     *
     * <p>这是「为什么是它」面板里显示的同一份数据，逐条摆给模型，
     * 它才能写出"你很在乎自然风光，这里正好很够"这种具体的话。
     */
    private void appendMatches(StringBuilder sb, TravelReasonInput.ExplainedPlace place) {
        if (place.topMatches().isEmpty()) {
            return;
        }
        sb.append("  用户在意的点上，这个地方的表现：\n");
        for (ScoredPlace.MatchedDimension match : place.topMatches()) {
            sb.append("    - ").append(match.dimension().label())
              .append("：用户在意程度 ").append(match.userPreference())
              .append("/100，这个地方 ").append(match.placeValue()).append("/100\n");
        }
    }

    // ==========================================================
    // 因子的人话解释
    // ==========================================================

    private static String describeInterest(double v) {
        if (v >= 0.8) return "非常合你的口味";
        if (v >= 0.6) return "比较合你的口味";
        if (v >= 0.4) return "口味上一般";
        return "和你的口味不太搭，是靠别的因素排上来的";
    }

    private static String describeDistance(double v) {
        // ⚠️ 没有定位时距离因子恒为 1.0，但那**不是**"就在你旁边"，
        // 而是"这次没算距离"。两者含义相反，不能混着说。
        if (v >= 1.0) return "（若没有定位，这一项表示未参与计算，不是\"很近\"）";
        if (v >= 0.8) return "离你很近";
        if (v >= 0.5) return "有点距离但不远";
        return "离你比较远";
    }

    private static String describeState(double v) {
        if (v >= 1.0) return "不受你此刻状态的影响";
        if (v >= 0.7) return "略微受了当前状态影响";
        return "受你当前状态影响较大（费腿／不合此刻的需要）";
    }

    private static String describeWeather(double v) {
        if (v >= 1.0) return "（若当天有天气，这一项表示天气没有影响它；若无天气，表示未参与计算）";
        if (v >= 0.7) return "天气有一点影响";
        return "天气影响较大（偏户外，而当时天气不好）";
    }

    private static String ratio(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
