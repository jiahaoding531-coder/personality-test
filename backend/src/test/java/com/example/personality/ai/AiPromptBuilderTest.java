package com.example.personality.ai;

import com.example.personality.dto.DimensionResult;
import com.example.personality.dto.SessionResultResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词构建的单元测试。
 *
 * <p><b>这个测试不需要任何 API Key，也不会产生任何费用</b>——
 * 因为 {@link AiPromptBuilder} 是纯逻辑类，不发网络请求。
 * 这正是把提示词从调用代码里拆出来的第一个好处。
 *
 * <p>它验证的不是"提示词写得好不好"（那要靠人看输出），而是
 * <b>"那些必须存在的约束有没有被误删"</b>。提示词是一大段文本，
 * 改动时很容易顺手删掉某一行而毫无察觉——这类回归只有测试能拦住。
 */
class AiPromptBuilderTest {

    private final AiPromptBuilder builder = new AiPromptBuilder();

    // ==========================================================
    // 系统提示词：必须包含的安全边界
    // ==========================================================

    @Test
    @DisplayName("系统提示词必须包含四条安全边界（不做诊断 / 不声称临床意义 / 不写成定论）")
    void systemPrompt_containsSafetyBoundaries() {
        String prompt = builder.systemPrompt();

        // 这四条来自计划书第九节的明确要求，任何一条被删掉都算回归
        assertTrue(prompt.contains("心理疾病诊断"),
                "必须明确禁止做心理疾病诊断");
        assertTrue(prompt.contains("临床意义"),
                "必须明确否认临床意义");
        assertTrue(prompt.contains("固定不变的人生结论"),
                "必须禁止把人格描述成固定不变的结论");
        assertTrue(prompt.contains("不提及具体的分数数字"),
                "必须要求不要念叨具体分数");
    }

    @Test
    @DisplayName("系统提示词必须包含写作风格要求（第二人称 / 篇幅 / 不要空话）")
    void systemPrompt_containsStyleRequirements() {
        String prompt = builder.systemPrompt();

        assertTrue(prompt.contains("第二人称"), "必须要求用第二人称");
        assertTrue(prompt.contains("400 到 600 字"), "必须限制篇幅");
        assertTrue(prompt.contains("空话"), "必须给出反例，否则模型会输出放之四海皆准的废话");
        assertTrue(prompt.contains("优势"), "必须要求既讲优势也讲代价");
    }

    // ==========================================================
    // 用户提示词：数据必须完整
    // ==========================================================

    @Test
    @DisplayName("用户提示词包含全部 5 个维度的名称、分数和档位")
    void userPrompt_containsAllDimensions() {
        String prompt = builder.userPrompt(sampleResult(4));

        assertTrue(prompt.contains("开放性"));
        assertTrue(prompt.contains("外向性"));
        assertTrue(prompt.contains("责任心"));
        assertTrue(prompt.contains("宜人性"));
        assertTrue(prompt.contains("情绪稳定性"));

        // 分数和档位都要在。用 toPlainString 是为了避免科学计数法
        assertTrue(prompt.contains("100.00"), "分数应完整保留两位小数");
        assertTrue(prompt.contains("50.00"), "50 分不应被写成 5E+1");
        assertTrue(prompt.contains("偏高"));
        assertTrue(prompt.contains("中等"));
    }

    @Test
    @DisplayName("itemCount 有效时，应把「由 N 道题计算得出」写进提示词")
    void userPrompt_includesItemCountWhenValid() {
        String prompt = builder.userPrompt(sampleResult(4));
        assertTrue(prompt.contains("由 4 道题计算得出"),
                "告诉模型题数可以防止它编出「你答了 40 道题」这类与事实不符的话");
    }

    @Test
    @DisplayName("itemCount 为 -1（当前未实现）时，应整段省略而不是写成「由 -1 道题」")
    void userPrompt_omitsItemCountWhenInvalid() {
        String prompt = builder.userPrompt(sampleResult(-1));

        assertFalse(prompt.contains("-1"),
                "无效的题数必须被省略，绝不能原样塞进提示词——模型会把它当成真实信息");
        assertFalse(prompt.contains("由 -1 道题"),
                "更不该出现这种明显荒谬的表述");
        // 但维度本身仍要完整
        assertTrue(prompt.contains("开放性"));
        assertTrue(prompt.contains("100.00"));
    }

    @Test
    @DisplayName("提示词里每道维度各占一行，且以「请据此写一段反馈」收尾")
    void userPrompt_structureIsStable() {
        String prompt = builder.userPrompt(sampleResult(4));

        long bulletCount = prompt.lines().filter(line -> line.startsWith("- ")).count();
        assertTrue(bulletCount == 5, "应有 5 行维度，实际 " + bulletCount);

        assertTrue(prompt.trim().endsWith("请据此写一段反馈。"),
                "结尾应给出明确指令，而不是让提示词戛然而止");
    }

    // ==========================================================
    // 测试夹具
    // ==========================================================

    /** 造一份 5 个维度的结果，分数固定，便于断言。 */
    private static SessionResultResponse sampleResult(int itemCount) {
        List<DimensionResult> dims = List.of(
                dim("OPENNESS", "开放性", "100.00", "HIGH", "偏高", itemCount),
                dim("EXTRAVERSION", "外向性", "81.25", "HIGH", "偏高", itemCount),
                dim("CONSCIENTIOUSNESS", "责任心", "50.00", "MEDIUM", "中等", itemCount),
                dim("AGREEABLENESS", "宜人性", "75.00", "HIGH", "偏高", itemCount),
                dim("EMOTIONAL_STABILITY", "情绪稳定性", "93.75", "HIGH", "偏高", itemCount)
        );
        return new SessionResultResponse(
                1L, "SUBMITTED", Instant.parse("2026-09-14T12:00:00Z"),
                Instant.parse("2026-09-14T12:05:00Z"), dims, "免责声明");
    }

    private static DimensionResult dim(String key, String name, String score,
                                       String level, String levelLabel, int itemCount) {
        return new DimensionResult(key, name, new BigDecimal(score),
                itemCount > 0 ? itemCount * 3 : -1, itemCount, level, levelLabel, "解读文案");
    }
}
