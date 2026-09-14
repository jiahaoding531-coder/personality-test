package com.example.personality.service;

import com.example.personality.domain.Dimension;
import com.example.personality.domain.Level;
import com.example.personality.dto.DimensionResult;
import com.example.personality.dto.SessionResultResponse;
import com.example.personality.entity.PersonalityProfile;
import com.example.personality.entity.TestSession;
import com.example.personality.exception.ResourceNotFoundException;
import com.example.personality.repository.PersonalityProfileRepository;
import com.example.personality.repository.TestSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把数据库里的人格画像组装成给前端（以及下一轮给 AI）的结果对象。
 *
 * <p>这个类是<b>只读</b>的，所有方法都标了 {@code readOnly = true}。
 * 这不是摆设：Spring 会把底层 JDBC 连接设成只读，Hibernate 也会跳过
 * "脏检查"（不再为每个查出来的实体保存快照来对比是否被改动）。
 *
 * <p><b>顺带一个 Java 语法提醒</b>：字符串字面量用 ASCII 双引号 {@code "} 界定。
 * 如果你在中文文案里直接写 ASCII 双引号（比如想强调某个词），
 * 会提前把字符串截断，编译器报一堆莫名其妙的 "需要 ';'"。
 * 中文里要强调就用「」或『』，既好看又不会踩这个坑。
 */
@Service
public class ProfileQueryService {

    /**
     * 免责声明。
     *
     * <p>计划书第五节明确要求：本测试定位为"自我探索/娱乐性质的画像"，
     * 不能宣传为心理诊断或专业人格测评。把这段话放在响应里而不是只写在前端页面，
     * 是为了保证不管从哪个客户端（网页、API、将来的小程序）调用都带着它。
     */
    private static final String DISCLAIMER =
            "本结果是一份自我探索性质的参考，基于你本次作答计算得出，"
                    + "不是心理诊断，也不代表固定不变的人格结论。"
                    + "人的状态会随情境、时间和经历变化。";

    /**
     * 5 个维度 × 3 个档位的解读文案。
     *
     * <p>写成 {@code Map<维度, Map<档位, 文案>>} 的嵌套结构，取值时
     * {@code DESCRIPTIONS.get(dimension).get(level)} 两步到位。
     *
     * <p>文案的写法有两条约束（来自计划书第九节）：
     * <ol>
     *   <li><b>不说成缺陷</b>：低分档也要指出它在什么场景下是优势，
     *       不能写成"你比较差"</li>
     *   <li><b>不说成定论</b>：不写"你就是这样的人"，而是描述倾向</li>
     * </ol>
     */
    private static final Map<Dimension, Map<Level, String>> DESCRIPTIONS = Map.of(

            Dimension.OPENNESS, Map.of(
                    Level.LOW, "你更信任熟悉的事物和验证过的方法。这让你做事稳、不容易被花哨的新东西带偏，"
                            + "在需要确定性和可靠性的场合是明显优势。",
                    Level.MEDIUM, "你对新事物保持开放，但也需要一定的熟悉感作为锚点。"
                            + "面对新东西你通常是「先看看再说」的类型，不冒进也不保守。",
                    Level.HIGH, "你对新事物、新观念有比较强的好奇心，愿意为「探索本身」付出时间和成本。"
                            + "在需要创意和跨领域联想的场合，这是你的优势。"
            ),

            Dimension.EXTRAVERSION, Map.of(
                    Level.LOW, "你的能量来自独处和深度交流，而不是人多热闹。"
                            + "你能在安静里恢复状态，也往往更擅长观察细节和别人没说出口的情绪。",
                    Level.MEDIUM, "你既能享受热闹，也需要独处充电，具体看场合和当时的状态。"
                            + "这种弹性让你在不同社交环境里都比较自在。",
                    Level.HIGH, "你从人际互动中获得能量，人多的场合反而更活跃。"
                            + "你通常能比较快地建立起社交连接，在需要推动事情往前走时更占优势。"
            ),

            Dimension.CONSCIENTIOUSNESS, Map.of(
                    Level.LOW, "你比较随性，不喜欢被计划和清单束缚。这让你灵活、能随机应变，"
                            + "在计划突然被打乱的场合反而比谁都适应得快。",
                    Level.MEDIUM, "你会做计划，但也接受计划被改。在「有条理」和「随机应变」之间找平衡，"
                            + "大部分日常场景下这样已经够用。",
                    Level.HIGH, "你做事有条理、重承诺，倾向于提前完成而不是拖到最后。"
                            + "在需要长期投入、多步骤推进的事情上，这个特点会持续给你回报。"
            ),

            Dimension.AGREEABLENESS, Map.of(
                    Level.LOW, "你更看重事情本身的对错，愿意为观点据理力争。"
                            + "在需要坚持原则、把问题摆到台面上的场合，这是优点。",
                    Level.MEDIUM, "你会在「顾及对方感受」和「把话说清楚」之间权衡，看情况决定用哪种方式。",
                    Level.HIGH, "你倾向于先信任别人、体谅对方的处境，这让你很容易和别人合作。"
                            + "同时也值得留意：别因为太照顾别人，而把自己的需求往后放太久。"
            ),

            Dimension.EMOTIONAL_STABILITY, Map.of(
                    Level.LOW, "你的情绪感受比较敏锐，对外界变化反应强烈。"
                            + "这让你更容易捕捉到细微的氛围变化和别人的情绪，但也更容易被消耗，"
                            + "需要主动给自己留恢复的时间。",
                    Level.MEDIUM, "你的情绪起伏在常见范围内，遇到事情通常能在一段时间后调整过来。",
                    Level.HIGH, "你在压力下能保持相对平静，遇到突发状况不容易乱。"
                            + "这是应对高压场合的明显优势，也让你常常成为别人的稳定器。"
            )
    );

    private final TestSessionRepository sessionRepository;
    private final PersonalityProfileRepository profileRepository;

    public ProfileQueryService(TestSessionRepository sessionRepository,
                               PersonalityProfileRepository profileRepository) {
        this.sessionRepository = sessionRepository;
        this.profileRepository = profileRepository;
    }

    /**
     * 组装某次会话的完整结果。
     *
     * @throws ResourceNotFoundException 会话不存在，或还没提交（没有画像）
     */
    /**
     * 按会话 ID 取画像实体。
     *
     * <p>对外暴露实体（而不是 DTO）是刻意的：AI 报告模块需要画像的
     * <b>主键 id</b> 来建立 {@code ai_reports.profile_id} 的外键关联，
     * 而这个 id 对前端没有意义，所以没有放进 {@code SessionResultResponse}。
     *
     * <p>把"查不到就抛 404"的逻辑收在这一处，避免调用方各写一遍判空。
     */
    @Transactional(readOnly = true)
    public PersonalityProfile loadProfile(Long sessionId) {
        return profileRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "会话 " + sessionId + " 还没有结果，请先调用 submit 完成计分"));
    }

    @Transactional(readOnly = true)
    public SessionResultResponse buildResult(Long sessionId) {
        TestSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("测试会话不存在：id=" + sessionId));

        PersonalityProfile profile = loadProfile(sessionId);

        List<DimensionResult> dimensions = new ArrayList<>(Dimension.values().length);
        for (Dimension dimension : Dimension.values()) {
            BigDecimal score = profile.scoreOf(dimension);
            Level level = Level.fromScore(score);

            dimensions.add(new DimensionResult(
                    dimension.name(),
                    dimension.label(),
                    score,
                    // rawScore 和 itemCount 暂时用 -1 占位。
                    //
                    // 原因是 PersonalityProfile 表只存了归一化后的分数，没存原始分；
                    // 要拿原始分得重新遍历 answers 再算一遍（多一次查询 + 一次计分）。
                    // V0.2 如果结果页要展示"4 道题拿了 18 分"，再补上真实值。
                    //
                    // 但现在就把字段留着，是为了让 API 契约稳定——
                    // 前端的组件可以按最终结构写，将来后端填上真值，前端不用改。
                    -1,
                    -1,
                    level.name(),
                    level.label(),
                    DESCRIPTIONS.get(dimension).get(level)
            ));
        }

        return new SessionResultResponse(
                session.getId(),
                session.getStatus().name(),
                session.getCreatedAt(),
                session.getSubmittedAt(),
                dimensions,
                DISCLAIMER
        );
    }
}
