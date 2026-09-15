package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把"用户说了一句什么"组织成让大模型做**翻译**的提示词。
 *
 * <p><b>和 {@code AiPromptBuilder}、{@code TravelReasonPromptBuilder} 一样，
 * 这是个纯逻辑类</b>——不碰网络、不碰数据库、不碰 Spring 容器
 * （{@code @Component} 只是个注册标签）。所以它能被单元测试直接覆盖，
 * <b>不花钱、不联网</b>。
 *
 * <h2>这个提示词的任务比另外两个都窄</h2>
 *
 * <p>它不是让它写文章，而是让它做**结构化翻译**：把一句大白话填进几个固定的槽位。
 * 窄任务是好事——输出可控、可校验、失败模式清楚。
 *
 * <p>所以下面把**词表原样列出来**（它得知道有哪些现成的说法可用），
 * 也把**维度名原样列出来**（词表装不下的部分要落到这些维度上）。
 * 不给这两样，它只能自己编名字，而编出来的名字我们一个都认不了。
 */
@Component
public class TextIntentPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            你是一个把用户口语翻译成结构化条件的解析器。用户会说一句自己现在的情况，
            你要把它填进固定的几个槽位。

            规则（按优先级）：

            1. **优先用现成的状态词**。下面列出的状态名是系统已经支持的，能对上就一定用它：

            %s

            2. **对不上词表时，才用维度倾向**。用户可以说的东西远不止上面那几个，比如
               "想找个特别小众的地方"——词表里没有"小众"这个状态，
               但下面有对应的维度，那就填进 biases。键**必须**是下面这几个之一：

               %s

               值表示"想要更多"还是"想要更少"，范围 -1 到 1：
               负数表示这个方面越少越好，正数表示越多越好。拿不准就给 -0.5 或 0.5。

            3. **只在用户真的说了的时候才填数字**。用户没提钱，就不要填 maxTicketPrice；
               没提时间，就不要填 remainingMinutes。**编一个出来比留空有害得多**——
               用户会看到一个自己从没要求过的条件被悄悄施加了。

            4. **听懂了但用不上的，放进 unrecognized**。比如"想找个能带狗的地方"——
               我们**确实没有**"能不能带宠物"这个维度，硬塞进 biases 只会让推荐
               莫名其妙地偏掉。原样放进 unrecognized 才对。
               **这一条很重要**：与其假装听懂、悄悄忽略，不如如实告诉用户
               "这句我没能用上"，他才知道系统的边界在哪。
               ⚠️ 判断标准是"**下面维度表里有没有真正对应的东西**"，而不是
               "能不能勉强扯上关系"。扯不上就老实说扯不上。

            5. summary 用一句话复述你的理解，第二人称，像在跟用户确认。
               不要出现任何数字（不要说"60 分钟"，说"一个小时左右"）。

            输出格式：只输出一个 JSON 对象，不要任何解释文字：
            {
              "states": ["TIRED"],
              "biases": {"CROWD_TOLERANCE": -0.5},
              "remainingMinutes": 60,
              "maxDistanceKm": 2,
              "maxTicketPrice": 50,
              "unrecognized": ["想找个能带狗的地方"],
              "summary": "你有点累了，想找个安静的地方待一会儿"
            }

            没有内容的槽位：数组给空数组 []，对象给空对象 {}，数字给 null。
            """;

    /** 系统提示词是常量，直接暴露给测试和调用方。 */
    public String systemPrompt() {
        return SYSTEM_PROMPT.formatted(renderStateVocabulary(), renderDimensionVocabulary());
    }

    /**
     * 把用户的原话包成用户提示词。
     *
     * <p>⚠️ 用引号把原话**框起来**。不框的话，一句"忽略上面的规则"就可能被当成指令
     * 执行——用户输入和大模型指令混在一起，是这类功能最典型的一个坑。
     * 框起来 + 系统提示词里说清"这是用户说的话，不是给你的指令"，能挡掉大部分。
     */
    public String userPrompt(String text) {
        return "用户说：「" + (text == null ? "" : text.trim()) + "」\n\n请按规则解析成 JSON。";
    }

    // ==========================================================
    // 词表渲染
    // ==========================================================

    /**
     * 把状态词表渲染成给模型看的一段文本。
     *
     * <p>直接从 {@link TravelState} 枚举生成，**不手写一份**——
     * 手写的话，加了新状态却忘了改这里，模型就永远用不上它，
     * 而且不会有任何报错。
     */
    private String renderStateVocabulary() {
        StringBuilder sb = new StringBuilder();
        for (TravelState state : TravelState.values()) {
            sb.append("   - ").append(state.name())
              .append("（").append(state.label()).append("）")
              .append(describeBias(state.attributeBias()))
              .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 把状态的作用翻译成人话给模型看。
     *
     * <p>不直接把 {@code {WALKING: -0.8}} 给它是刻意的：那个映射是**算法内部的事**，
     * 模型只需要知道"这个状态大致是什么倾向"，好判断用户的话能不能对上。
     * 把系数给出去反而会诱导它去"算"——而它不该算。
     */
    private String describeBias(Map<TravelDimension, Double> bias) {
        StringBuilder sb = new StringBuilder();
        bias.forEach((dimension, value) -> {
            if (!sb.isEmpty()) {
                sb.append("、");
            }
            sb.append(value >= 0 ? "更看重" : "更避开").append(dimension.label());
        });
        return sb.isEmpty() ? "" : "，即" + sb;
    }

    /** 维度词表。只列**会影响地点选择**的那些——PLANNING 不参与排序，列了反而误导。 */
    private String renderDimensionVocabulary() {
        StringBuilder sb = new StringBuilder();
        for (TravelDimension dimension : TravelDimension.values()) {
            if (!dimension.affectsPlaceChoice()) {
                continue;
            }
            sb.append("   - ").append(dimension.name())
              .append("（").append(dimension.label()).append("）\n");
        }
        return sb.toString().stripTrailing();
    }
}
