package com.example.personality.ai;

import com.example.personality.dto.DimensionResult;
import com.example.personality.dto.SessionResultResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把人格画像组织成大模型能理解的提示词。
 *
 * <p><b>和 {@code ScoringService} 一样，这是个纯逻辑类</b>——不碰网络、不碰数据库、
 * 不碰 Spring 容器（{@code @Component} 只是个注册标签）。所以它能被单元测试直接覆盖，
 * 不需要真的调用大模型 API。
 *
 * <p>为什么要把提示词从调用代码里拆出来？两个实际理由：
 * <ol>
 *   <li><b>可测试</b>：提示词直接决定输出质量，是最该被回归测试盯住的部分。
 *       如果它埋在 {@code generate()} 方法里，测试就得连 HTTP 一起 mock，成本高到没人会写。</li>
 *   <li><b>可迭代</b>：调提示词是个反复试错的过程，你改的是这个文件里的字符串常量，
 *       而不是翻遍业务代码找那段拼字符串的地方。</li>
 * </ol>
 */
@Component
public class AiPromptBuilder {

    /**
     * 系统提示词：定义角色和写作约束。
     *
     * <p>下面这 6 条写作要求不是随便凑的，每一条都对应一种大模型最容易犯的毛病：
     * <ul>
     *   <li>不要求"串起来讲" → 它会机械地把 5 个维度逐个念一遍，像在读报告</li>
     *   <li>不要求"优势 + 代价都要说" → 它会一路夸，变成没有信息量的彩虹屁</li>
     *   <li>不给"不要写空话"的反例 → 它会输出「多与人交流」「保持开放心态」这种废话</li>
     *   <li>不限制篇幅 → 它会写 1500 字，用户根本不会看完</li>
     * </ul>
     */
    private static final String SYSTEM_PROMPT = """
            你是一位温和、务实的自我探索伙伴。用户刚完成一份人格自评，\
            你会收到 ta 在 5 个人格维度上的得分，请据此写一段反馈。

            写作要求：
            1. 用第二人称「你」，语气像朋友聊天，不要学术腔，不要职场黑话。
            2. 每个维度都要谈到，但不要机械地逐条念分数。把相关的维度串起来讲，\
            指出它们组合在一起意味着什么（例如「开放性偏高 + 责任心中等」= 什么状态）。
            3. 分数代表倾向，不是定论。避免「你就是……的人」，\
            多用「你倾向于……」「在……的场合，你可能会……」。
            4. 每个维度都要指出它在什么场景下是优势，也可能在什么场景下带来代价。\
            不要只夸，也不要只批。
            5. 结尾给 2 到 3 条具体、可执行的建议。建议必须针对这份画像本身，\
            不要写「多与人交流」这种放在谁身上都成立的空话。
            6. 篇幅控制在 400 到 600 字。用短段落，不要用 Markdown 标题，不要用列表符号。

            必须遵守的边界（违反任何一条都算输出失败）：
            - 不做任何心理疾病诊断，不暗示用户可能存在心理问题。
            - 不声称本结果具有临床意义或科学权威性。
            - 不把人格描述成固定不变的人生结论。
            - 不提及具体的分数数字、题目数量，也不要提「测试」这个行为本身，\
            直接谈倾向即可。
            """;

    /** 系统提示词是常量，直接暴露给测试和调用方。 */
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * 用户提示词：把 5 个维度的数据摆出来。
     *
     * <p><b>为什么要把 {@code itemCount} 也写进去？</b>
     * 因为不给它，模型就不知道"这个分数是几道题算出来的"，可能编出
     * 「你答了 40 道题」「在这 5 个维度共 100 分的评分里」这类与事实不符的话。
     * 虽然系统提示词已经要求它别提题目数量，但<b>把事实喂给它</b>比
     * <b>要求它别乱猜</b>更可靠——前者是信息，后者是禁令。
     *
     * <p>各维度的题数可能不同（V0.2 想扩充某个维度时就会不同），
     * 所以这里逐维度取 {@code itemCount}，而不是写死一个统一的数字。
     */
    public String userPrompt(SessionResultResponse result) {
        List<DimensionResult> dimensions = result.dimensions();

        StringBuilder sb = new StringBuilder();
        sb.append("以下是这位用户在 5 个人格维度上的自评结果");
        sb.append("（分数已归一化到 0 到 100）：\n\n");

        for (DimensionResult d : dimensions) {
            sb.append("- ").append(d.name())
              .append("：").append(d.score().toPlainString()).append(" / 100")
              .append("（").append(d.levelLabel()).append("）");
            if (d.itemCount() > 0) {
                sb.append("，由 ").append(d.itemCount()).append(" 道题计算得出");
            }
            sb.append('\n');
        }

        sb.append("\n请据此写一段反馈。");
        return sb.toString();
    }
}
