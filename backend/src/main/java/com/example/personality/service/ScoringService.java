package com.example.personality.service;

import com.example.personality.domain.Dimension;
import com.example.personality.domain.DimensionScore;
import com.example.personality.domain.ScaleDimension;
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
 * 量表计分：把原始答案换算成各维度的 0~100 分。<b>人格量表（5 维）和旅行量表（8 维）共用这一个类。</b>
 *
 * <p><b>这个类是故意写成"纯 Java"的</b>——它没有任何 {@code @Autowired} 依赖，
 * 不碰数据库、不碰 HTTP、不碰 Spring 容器。唯一的注解 {@code @Service} 只是
 * 给容器看的标签，测试时完全可以 {@code new ScoringService()} 直接构造。
 *
 * <p>这样切分的好处：如果你发现"分数算出来不对"，问题一定在这个文件里，
 * 不会和 Controller 的参数绑定、Repository 的查询、事务的边界混在一起。
 *
 * <h2>为什么是泛型的</h2>
 *
 * <p>人格量表和旅行量表的维度是两套平行的枚举（{@link Dimension} / {@link TravelDimension}），
 * 但"反向翻转 → 按维度求和 → 归一化到 0~100 → 每个维度都必须有作答"这四步
 * <b>完全一样</b>，差异只有"维度叫什么名字"。
 *
 * <p>所以算法只写一份，用类型参数 {@code D} 把"这次是哪套量表"交给调用方。
 * 泛型没有引入任何新依赖——{@code new ScoringService()} 依然可以直接构造。
 *
 * <p><b>⚠️ 泛型加在方法上，不是加在类上。</b>写成
 * {@code class ScoringService<D extends ...>} 会立刻出问题：Spring 的组件扫描看到的
 * 是裸类型，{@code ScoringService<Dimension>} 和 {@code ScoringService<TravelDimension>}
 * 无法作为两个 bean 共存。而且"这次算哪套量表"是<b>一次调用</b>的信息，
 * 不是<b>一个 bean</b> 的信息，绑到类上属于层次错位。
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
     * 对一组<b>人格量表</b>的作答计分。
     *
     * <p>这只是个便捷重载，真正的算法在下面那个泛型版本里。留着它是为了
     * 让既有调用点（{@code TestSessionService}、13 个计分测试）一行都不用改——
     * 重构的原则是"新能力加上去，旧用法不受影响"。
     *
     * @param items 每道题的"维度 + 是否反向 + 原始分"
     * @return 5 个维度各自的计分结果，key 是维度枚举
     * @throws InvalidAnswersException 分值为空/越界，或某个维度一道题都没有
     */
    public Map<Dimension, DimensionScore<Dimension>> score(List<ScoredItem<Dimension>> items) {
        return score(items, Dimension.class);
    }

    /**
     * 对一组作答计分——<b>两套量表共用这一个方法</b>。
     *
     * <p>算法本身和"维度是哪个枚举"毫无关系：按维度分组求和 → 用 {@link #normalize}
     * 归一化 → 检查每个维度都有作答。所以它能同时服务人格量表
     * （5 维、每维 4 题、有反向题）和旅行量表（8 维、每维 1 题、无反向题），
     * 而且将来加第三套量表也不用再动这里。
     *
     * <p><b>⚠️ 这就是 V6 迁移注释里说的"计分引擎一行都不用改"。</b>
     * 在那之前那句话其实是假的——{@code score()} 的签名写死了 {@code Dimension}，
     * 旅行量表根本传不进来。
     *
     * @param items    每道题的"维度 + 是否反向 + 原始分"
     * @param enumType 这次用的是哪套维度枚举，传 {@code Dimension.class} 或
     *                 {@code TravelDimension.class}。<b>它有两个用途</b>：
     *                 构造 {@code EnumMap}、以及拿到"这套量表一共有哪些维度"来做完整性校验
     * @return 每个维度各自的计分结果，key 是维度枚举
     * @throws InvalidAnswersException 分值为空/越界，或某个维度一道题都没有
     */
    public <D extends Enum<D> & ScaleDimension> Map<D, DimensionScore<D>> score(
            List<ScoredItem<D>> items, Class<D> enumType) {

        if (items == null || items.isEmpty()) {
            throw new InvalidAnswersException("答案列表为空");
        }

        // 用 EnumMap 而不是 HashMap：key 是枚举时 EnumMap 更快也更省内存，
        // 而且遍历顺序固定为枚举的声明顺序（OPENNESS → ... → EMOTIONAL_STABILITY），
        // 输出因此是稳定的，便于测试和对比。
        //
        // ⚠️ 泛型下必须由调用方把 Class 传进来：EnumMap 的构造器要 Class<K>，
        // 而类型参数 D 在运行期是被擦除的，代码自己拿不到它。
        Map<D, Integer> sums = new EnumMap<>(enumType);
        Map<D, Integer> counts = new EnumMap<>(enumType);

        for (ScoredItem<D> item : items) {
            int effective = effectiveScore(item);

            Integer currentSum = sums.get(item.dimension());
            sums.put(item.dimension(), currentSum == null ? effective : currentSum + effective);

            Integer currentCount = counts.get(item.dimension());
            counts.put(item.dimension(), currentCount == null ? 1 : currentCount + 1);
        }

        Map<D, DimensionScore<D>> result = new EnumMap<>(enumType);
        // getEnumConstants() 是泛型版的 values()：拿到这套枚举的全部常量。
        // 遍历"全集"而不是"用户答过的维度"，才能发现漏答的情况。
        //
        // 它的声明是「非枚举类型返回 null」。类型上界已经保证了 D 是枚举，
        // 所以这里不可能为 null——但显式挡一下，比留一个方向不明的 NPE 强。
        D[] allDimensions = enumType.getEnumConstants();
        if (allDimensions == null) {
            throw new IllegalArgumentException("不是枚举类型，无法计分：" + enumType.getName());
        }
        for (D dimension : allDimensions) {
            Integer sum = sums.get(dimension);
            if (sum == null) {
                throw new InvalidAnswersException("维度「" + dimension.label() + "」没有任何作答，无法计分");
            }
            int count = counts.get(dimension);
            result.put(dimension, new DimensionScore<>(dimension, sum, count, normalize(sum, count)));
        }

        // 返回不可变视图：调用方拿到之后无法偷偷改这个 Map，
        // 避免了"我明明只读了一下，结果数据被改了"这类难查的 bug。
        return Collections.unmodifiableMap(result);
    }

    /**
     * 算出单题的<b>有效分</b>：正向题原样返回，反向题做 {@code 6 - score} 的镜像翻转。
     *
     * <p>包级私有（不加 private），是为了让同包的测试类能直接调用它做细粒度验证。
     *
     * <p>参数写成 {@code ScoredItem<?>} 而不是 {@code ScoredItem<D>}：这个方法体
     * 只读 {@code rawScore()} 和 {@code reverseScored()}，跟"是哪套量表"无关。
     * 用通配符把它标出来，两个好处——不用给这个方法也加类型参数（加了纯属噪音），
     * 而且现有测试里的 {@code service.effectiveScore(item(...))} 一行都不用改。
     */
    int effectiveScore(ScoredItem<?> item) {
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
