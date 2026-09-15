package com.example.personality.service;

import com.example.personality.domain.Dimension;
import com.example.personality.domain.DimensionScore;
import com.example.personality.domain.ScoredItem;
import com.example.personality.dto.AnswerSubmission;
import com.example.personality.entity.Answer;
import com.example.personality.entity.PersonalityProfile;
import com.example.personality.entity.Question;
import com.example.personality.entity.QuestionScale;
import com.example.personality.entity.TestSession;
import com.example.personality.exception.ConflictException;
import com.example.personality.exception.InvalidAnswersException;
import com.example.personality.exception.NotImplementedException;
import com.example.personality.exception.ResourceNotFoundException;
import com.example.personality.repository.AnswerRepository;
import com.example.personality.repository.PersonalityProfileRepository;
import com.example.personality.repository.QuestionRepository;
import com.example.personality.repository.TestSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试会话的生命周期管理：创建 → 答题 → 提交计分。
 *
 * <h2>事务边界在哪（对应你写过的 TransferDemo.java）</h2>
 *
 * <p>你手写事务是这样的：
 * <pre>
 *   Connection conn = ...;
 *   conn.setAutoCommit(false);          // ← 开启事务
 *   try {
 *       updateA(conn);                   // 第一条 UPDATE
 *       updateB(conn);                   // 第二条 UPDATE
 *       conn.commit();                   // ← 全部成功才提交
 *   } catch (SQLException e) {
 *       conn.rollback();                 // ← 任何一步失败，全部撤销
 *   }
 * </pre>
 *
 * <p>现在换成 {@code @Transactional} 写在方法上：
 * <pre>
 *   &#64;Transactional
 *   public PersonalityProfile submit(Long sessionId) {
 *       // 方法体 = setAutoCommit(false) 到 commit() 之间的所有代码
 *   }
 * </pre>
 *
 * <p>Spring 用 AOP 代理把这个方法整个包起来：进来之前开事务，正常返回就提交，
 * 抛出 RuntimeException 就回滚。你一行 try-catch 都不用写。
 *
 * <p><b>⚠️ 三个必须记住的坑：</b>
 *
 * <p><b>1. 默认只在 RuntimeException 时回滚。</b>
 * 你写 JDBC 时 {@code catch (SQLException)} 能回滚，是因为你手动调了 rollback()，
 * 什么异常都能兜住。但 Spring 的默认规则是"遇到受检异常（checked exception）不回滚"——
 * 因为它假设受检异常是"可预期的业务分支"，不是"出错了"。
 * 所以本项目的自定义异常全部继承 RuntimeException（见 BusinessException 的注释）。
 *
 * <p><b>2. 自调用会绕过事务。</b>
 * 下面 {@code submit} 是 public 的，Spring 才能给它套代理。如果你在类的<b>内部</b>
 * 写 {@code this.submit(...)}，走的是原始对象而不是代理，事务不会生效，
 * 而且不报任何错。这叫"自调用陷阱"，是 Spring 最隐蔽的坑之一。
 *
 * <p><b>3. 事务要尽量短。</b>
 * 事务没结束时，数据库连接一直被这个请求占着。所以绝对不要在事务方法里
 * 调用外部 HTTP 接口（比如大模型 API，可能要好几秒）——
 * 并发量一上来连接池立刻就满了。AI 报告那个功能就是因此被拆到事务外的。
 */
@Service
public class TestSessionService {

    private final TestSessionRepository sessionRepository;
    private final AnswerRepository answerRepository;
    private final QuestionRepository questionRepository;
    private final PersonalityProfileRepository profileRepository;
    private final ScoringService scoringService;

    public TestSessionService(TestSessionRepository sessionRepository,
                              AnswerRepository answerRepository,
                              QuestionRepository questionRepository,
                              PersonalityProfileRepository profileRepository,
                              ScoringService scoringService) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.questionRepository = questionRepository;
        this.profileRepository = profileRepository;
        this.scoringService = scoringService;
    }

    /**
     * 开启一次新的测试会话。
     *
     * <p>只有一条 INSERT，本来不开事务也行，但统一加上更省心：
     * 将来这个方法要扩展成"建会话 + 预生成答题卡"时，事务边界已经在那儿了。
     */
    @Transactional
    public TestSession createSession(Long userId) {
        return sessionRepository.save(TestSession.start(userId));
    }

    /**
     * 批量保存作答（支持分多次提交，实现"中断后继续答题"）。
     *
     * <p>已答过的题会<b>更新</b>而不是重复插入——靠的是先按 sessionId 把已有答案
     * 捞出来做成 Map，再用 {@code questionId} 去查有没有答过。
     * 数据库上 {@code (session_id, question_id)} 的唯一约束是最后一道保险。
     *
     * @return 本次写入（含更新）的作答条数
     */
    @Transactional
    public int saveAnswers(Long sessionId, List<AnswerSubmission> submissions) {
        TestSession session = requireSession(sessionId);
        if (session.isSubmitted()) {
            throw new ConflictException("会话 " + sessionId + " 已提交，不能再修改答案");
        }

        Map<Long, Question> questionsById = loadQuestionsById(session.getScale());

        // 先整体校验一遍，再统一写库。避免"写了一半才发现第 8 题不存在"，
        // 留下一个答了一半的会话。
        for (AnswerSubmission submission : submissions) {
            if (!questionsById.containsKey(submission.questionId())) {
                throw new InvalidAnswersException("题目不存在：id=" + submission.questionId());
            }
        }

        // 把已经答过的题做成 Map<questionId, Answer>，便于 O(1) 查重。
        // 如果用 List.contains() 逐个比对，20 道题就是 400 次比较，虽然这里量小
        // 无所谓，但 Map 查找是更该养成的习惯。
        Map<Long, Answer> existingByQuestionId = new HashMap<>();
        for (Answer answer : answerRepository.findBySessionId(sessionId)) {
            existingByQuestionId.put(answer.getQuestionId(), answer);
        }

        List<Answer> toSave = new ArrayList<>(submissions.size());
        for (AnswerSubmission submission : submissions) {
            Answer existing = existingByQuestionId.get(submission.questionId());
            if (existing == null) {
                toSave.add(Answer.of(sessionId, submission.questionId(), submission.score()));
            } else {
                existing.changeScore(submission.score());   // JPA 会把它标记为"脏"，事务提交时自动 UPDATE
                toSave.add(existing);
            }
        }

        answerRepository.saveAll(toSave);
        return toSave.size();
    }

    /**
     * 提交测试并计分。<b>这是整个 MVP 的闭环收口点。</b>
     *
     * <p>方法体是一个完整事务：校验状态 → 校验答完 → 计分 → 存画像 → 标记会话已提交。
     * 这五步要么全成，要么全败。
     *
     * <p>为什么必须是一个事务？如果"存画像成功但标记状态失败"，用户会看到
     * 会话还是 IN_PROGRESS、画像却已经存在；他再点一次提交，就会算出第二份画像
     * 覆盖第一份（或者撞上唯一约束报 500）。这类半完成状态是最难排查的线上问题。
     *
     * @return 计算出的画像
     * @throws ResourceNotFoundException 会话不存在
     * @throws ConflictException         会话已提交过
     * @throws InvalidAnswersException   题目没答完
     */
    @Transactional
    public PersonalityProfile submit(Long sessionId) {
        TestSession session = requireSession(sessionId);

        // ---- 量表检查 ----
        // 这个方法的返回类型是 PersonalityProfile，整套逻辑（ScoredItem 用
        // Dimension、结果存 personality_profiles）都只适用于人格量表。
        // 旅行量表的计分还没接入（TravelProfile / RecommendationEngine 已就位，
        // 缺的是计分与推荐的服务层），这里明确挡住而不是让它算出错误的东西。
        //
        // 抛 501 而不是 500：这是"功能没做"，不是"服务器坏了"。
        if (session.getScale() != QuestionScale.PERSONALITY) {
            throw new NotImplementedException(
                    "量表「" + session.getScale().label() + "」的计分尚未接入，暂时无法提交");
        }

        // ---- 幂等性第一道防线：应用层检查会话状态 ----
        if (session.isSubmitted()) {
            throw new ConflictException("会话 " + sessionId + " 已经提交过了，不能重复提交");
        }
        // ---- 幂等性第二道防线：检查画像是否已存在 ----
        // 第一道防线在并发下可能失效（两个请求同时读到 IN_PROGRESS），
        // 但 personality_profiles.session_id 上的唯一约束会让其中一个事务失败，
        // 无论如何都不会产生两份画像。
        if (profileRepository.existsBySessionId(sessionId)) {
            throw new ConflictException("会话 " + sessionId + " 已经生成过画像了");
        }

        Map<Long, Question> questionsById = loadQuestionsById(session.getScale());

        List<Answer> answers = answerRepository.findBySessionId(sessionId);
        if (answers.isEmpty()) {
            throw new InvalidAnswersException("还没有作答任何题目，无法提交");
        }
        if (answers.size() < questionsById.size()) {
            int missing = questionsById.size() - answers.size();
            throw new InvalidAnswersException("还有 " + missing + " 道题没有作答，无法提交");
        }

        // 把"数据库里的作答记录"翻译成"计分器认识的输入"。
        // 这一步是实体层和领域层之间的适配——ScoringService 完全不知道
        // Answer / Question 这两个 JPA 实体的存在。
        List<ScoredItem> items = new ArrayList<>(answers.size());
        for (Answer answer : answers) {
            Question question = questionsById.get(answer.getQuestionId());
            if (question == null) {
                // 理论上不会发生（saveAnswers 已经校验过），但外键只保证引用的题目存在，
                // 不保证它现在还在。留一道防御，出问题时能立刻定位。
                throw new IllegalStateException("作答引用了不存在的题目：id=" + answer.getQuestionId());
            }
            // getDimension() 现在返回的是字符串（为了同时装下两套量表的维度名），
            // 这里必须用它解析成人格枚举。走到这一步的会话已经确保是 PERSONALITY，
            // 所以解析不会失败。
            items.add(new ScoredItem(question.getPersonalityDimension(), question.isReverseScored(), answer.getScore()));
        }

        Map<Dimension, DimensionScore> scores = scoringService.score(items);

        PersonalityProfile profile = profileRepository.save(PersonalityProfile.from(sessionId, scores));

        session.markSubmitted();
        // 这一行 save 其实是多余的——JPA 的"脏检查"（dirty checking）会发现
        // 从数据库查出来的 session 对象被改动了，事务提交时自动生成 UPDATE。
        // 这里显式写出来是为了让意图更清楚，对新手更友好。
        sessionRepository.save(session);

        return profile;
    }

    // ==========================================================
    // 私有辅助方法
    // ==========================================================

    /**
     * 按 ID 取会话，取不到就抛 404。
     *
     * <p>这里演示 {@code Optional} 的惯用法。{@code findById} 返回
     * {@code Optional<TestSession>}，{@code orElseThrow} 接收一个
     * <b>Supplier</b>——注意参数 {@code () -> new ResourceNotFoundException(...)}
     * 是一个 lambda，它<b>只在真的为空时才会执行</b>。
     *
     * <p>对比"先赋值再判空"的写法：
     * <pre>
     *   TestSession s = repo.findById(id).orElse(null);
     *   if (s == null) { throw ... }        // 忘了写这行就是 NPE
     * </pre>
     * {@code orElseThrow} 把"必须处理不存在的情况"变成了<b>类型系统强制的约束</b>——
     * 你不写它，就拿不到 TestSession 对象，编译器直接不让你过。
     */
    private TestSession requireSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("测试会话不存在：id=" + sessionId));
    }

    /**
     * 把<b>指定量表</b>的题库读进内存做成 Map，方便按 ID 查题干信息。
     * 一套题最多 20 道的量级，完全没必要优化。
     *
     * <p><b>⚠️ 这里的 scale 过滤是必须的，别改回 findAll()。</b>
     *
     * <p>questions 表从 V6 起同时装着两套题（20 道人格 + 8 道旅行）。不过滤的话：
     * <ul>
     *   <li>{@code submit} 里那句 {@code answers.size() < questionsById.size()}
     *       会永远成立——人格会话只答 20 道，却要跟 28 道比，用户看到的是
     *       「还有 8 道题没有作答」，而他明明每道都答了</li>
     *   <li>{@code saveAnswers} 会放行另一套量表的题号，写进一堆和本次会话
     *       无关的作答</li>
     * </ul>
     * 顺带还白拿一个效果：拿旅行题号往人格会话里提交，会被当成"题目不存在"挡掉。
     */
    private Map<Long, Question> loadQuestionsById(QuestionScale scale) {
        Map<Long, Question> byId = new HashMap<>();
        for (Question question : questionRepository.findByScaleOrderBySortOrderAsc(scale)) {
            byId.put(question.getId(), question);
        }
        return byId;
    }
}
