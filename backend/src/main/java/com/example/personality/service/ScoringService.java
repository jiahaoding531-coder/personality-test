package com.example.personality.service;

import com.example.personality.domain.Dimension;
import com.example.personality.domain.DimensionScore;
import com.example.personality.domain.ScoredItem;
import com.example.personality.exception.InvalidAnswersException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 人格计分：把原始答案换算成 5 个维度的 0~100 分。
 *
 * <p><b>这个类是故意写成"纯 Java"的</b>——它没有任何 {@code @Autowired} 依赖，
 * 不碰数据库、不碰 HTTP、不碰 Spring 容器。唯一的注解 {@code @Service} 只是
 * 给容器看的标签，测试时完全可以 {@code new ScoringService()} 直接构造。
 *
 * <p>这样切分的好处：如果你发现"分数算出来不对"，问题一定在这个文件里，
 * 不会和 Controller 的参数绑定、Repository 的查询、事务的边界混在一起。
 *
 * <h2>计分规则</h2>
 * <ol>
 *   <li><b>反向计分</b>：{@code reverseScored = true} 的题，有效分 = {@code 6 - 原始分}。
 *       1↔5、2↔4 互为镜像，中点 3 保持不动。</li>
 *   <li><b>原始分求和</b>：同一维度的有效分相加，范围 {@code itemCount} ~ {@code itemCount * 5}。</li>
 *   <li><b>归一化到 0~100</b>：{@code (rawSum - minSum) / (maxSum - minSum) * 100}。
 *       4 道题时即 {@code (rawSum - 4) / 16 * 100}，最低 4 分 → 0.00，最高 20 分 → 100.00。</li>
 * </ol>
 *
 * <p>注意 {@code itemCount} 是<b>算出来的</b>而不是硬编码 4。这样 V0.2 想把某个维度
 * 加到 6 道题，只需要往 questions 表插数据，这个文件一行都不用改。
 */
@Service
public class ScoringService {

    /** 单题最低分（李克特量表的"非常不同意"）。 */
    static final int MIN_ITEM_SCORE = 1;

    /** 单题最高分（李克特量表的"非常同意"）。 */
    static final int MAX_ITEM_SCORE = 5;

    /**
     * 反向计分的翻转基数：{@code MIN + MAX = 6}。
     * 公式是 {@code 6 - score}，于是 1→5、2→4、3→3、4→2、5→1。
     */
    static final int REVERSE_BASE = MIN_ITEM_SCORE + MAX_ITEM_SCORE;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 对一组作答计分。
     *
     * @param items 每道题的"维度 + 是否反向 + 原始分"
     * @return 5 个维度各自的计分结果，key 是维度枚举
     * @throws InvalidAnswersException 分值为空/越界，或某个维度一道题都没有
     */
    public Map<Dimension, DimensionScore> score(List<ScoredItem> items) {
        if (items == null || items.isEmpty()) {
            throw new InvalidAnswersException("答案列表为空");
        }

        // 用 EnumMap 而不是 HashMap：key 是枚举时 EnumMap 更快也更省内存，
        // 而且遍历顺序固定为枚举的声明顺序（OPENNESS → ... → EMOTIONAL_STABILITY），
        // 输出因此是稳定的，便于测试和对比。
        Map<Dimension, Integer> sums = new EnumMap<>(Dimension.class);
        Map<Dimension, Integer> counts = new EnumMap<>(Dimension.class);

        for (ScoredItem item : items) {
            int effective = effectiveScore(item);

            Integer currentSum = sums.get(item.dimension());
            sums.put(item.dimension(), currentSum == null ? effective : currentSum + effective);

            Integer currentCount = counts.get(item.dimension());
            counts.put(item.dimension(), currentCount == null ? 1 : currentCount + 1);
        }

        Map<Dimension, DimensionScore> result = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            Integer sum = sums.get(dimension);
            if (sum == null) {
                throw new InvalidAnswersException("维度「" + dimension.label() + "」没有任何作答，无法计分");
            }
            int count = counts.get(dimension);
            result.put(dimension, new DimensionScore(dimension, sum, count, normalize(sum, count)));
        }

        // 返回不可变视图：调用方拿到之后无法偷偷改这个 Map，
        // 避免了"我明明只读了一下，结果数据被改了"这类难查的 bug。
        return Collections.unmodifiableMap(result);
    }

    /**
     * 算出单题的<b>有效分</b>：正向题原样返回，反向题做 {@code 6 - score} 的镜像翻转。
     *
     * <p>包级私有（不加 private），是为了让同包的测试类能直接调用它做细粒度验证。
     */
    int effectiveScore(ScoredItem item) {
        int raw = item.rawScore();
        if (raw < MIN_ITEM_SCORE || raw > MAX_ITEM_SCORE) {
            throw new InvalidAnswersException(
                    "分值必须在 " + MIN_ITEM_SCORE + " ~ " + MAX_ITEM_SCORE + " 之间，实际收到：" + raw);
        }
        return item.reverseScored() ? REVERSE_BASE - raw : raw;
    }

    /**
     * 把某维度的原始总分归一化到 0.00 ~ 100.00。
     *
     * <p>公式：{@code (rawSum - itemCount * 1) / (itemCount * 5 - itemCount * 1) * 100}
     * <br>化简后每道题的权重正好是 {@code 100 / (itemCount * 4)}：
     * 4 道题时每题 6.25 分。
     *
     * <p>用 BigDecimal 而不是 double，是因为像 6.25 这种数用 double 会得到
     * 6.249999999999999 之类的值，存进数据库再读出来对不上，测试会莫名其妙地红。
     * 除法必须显式指定小数位和舍入方式（这里的 2 和 HALF_UP），
     * 否则 100/3 这种除不尽的运算会直接抛 ArithmeticException。
     */
    BigDecimal normalize(int rawSum, int itemCount) {
        if (itemCount <= 0) {
            throw new InvalidAnswersException("题目数量必须大于 0，实际收到：" + itemCount);
        }

        int minSum = itemCount * MIN_ITEM_SCORE;
        int maxSum = itemCount * MAX_ITEM_SCORE;
        int span = maxSum - minSum;

        return BigDecimal.valueOf(rawSum - minSum)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(span), 2, RoundingMode.HALF_UP);
    }
}
