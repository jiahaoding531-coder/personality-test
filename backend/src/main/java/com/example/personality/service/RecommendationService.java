package com.example.personality.service;

import com.example.personality.domain.PlaceCandidate;
import com.example.personality.domain.RecommendationContext;
import com.example.personality.domain.ScoredPlace;
import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.dto.AppliedContext;
import com.example.personality.dto.FeedbackResponse;
import com.example.personality.dto.MatchReason;
import com.example.personality.dto.RecommendationRequest;
import com.example.personality.dto.RecommendationResponse;
import com.example.personality.dto.RecommendedPlace;
import com.example.personality.entity.Place;
import com.example.personality.entity.QuestionScale;
import com.example.personality.entity.Recommendation;
import com.example.personality.entity.RecommendationFeedback;
import com.example.personality.entity.TestSession;
import com.example.personality.entity.TravelProfile;
import com.example.personality.exception.ResourceNotFoundException;
import com.example.personality.repository.PlaceRepository;
import com.example.personality.repository.RecommendationFeedbackRepository;
import com.example.personality.repository.RecommendationRepository;
import com.example.personality.repository.TestSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐编排：把「旅行画像 + 当前处境 + 候选地点」喂给算法，存下结果，返回给前端。
 *
 * <h2>这个类为什么存在</h2>
 *
 * <p>{@code RecommendationEngine} 是纯逻辑的——它只认 {@code PlaceCandidate}、
 * {@code RecommendationContext} 这些领域对象，不知道数据库和 HTTP 的存在。
 * 那样切分的好处是算法可以毫秒级单测（16 个测试），代价是**总得有人负责取数和落库**。
 * 这个类就是那个人。
 *
 * <h2>事务边界</h2>
 *
 * <p>整个方法一个事务，没有拆开——因为全程只碰本地数据库，
 * 没有大模型调用那种"几秒钟的外部等待"（那种情况必须挪到事务外，
 * 否则并发一上来连接池立刻被占满，见 {@code AiReportService} 的教训）。
 *
 * <h2>⚠️ 这里不写"重新推荐前先删掉旧的"</h2>
 *
 * <p>重新推荐走的是<b>新开一批</b>（{@code batchNo + 1}）。原因见
 * {@code RecommendationResponse} 和 V9 迁移脚本：删推荐会级联删掉用户反馈。
 */
@Service
public class RecommendationService {

    /** 计划书第十二节：「最终输出 Top 3，而不是 Top 100」。 */
    private static final int TOP_N = 3;

    /**
     * 参与修正的反馈最多取最近多少条。
     *
     * <p>为什么不全部算：修正量有 ±40 的封顶，一旦撞顶就再也动不了了——
     * 用户改了口味，系统却因为几百条旧反馈而锁死。
     *
     * <p>语义上也更对："此刻的偏好"应该由<b>最近的</b>反馈决定，
     * 而不是被历史的平均淹没。取 20 大约是六七批推荐的反馈量。
     */
    private static final int RECENT_FEEDBACK_LIMIT = 20;

    /** 营业时间的展示格式。数据库里是 TIME，输出成 "HH:mm" 给前端直接用。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final TravelProfileService travelProfileService;
    private final PlaceRepository placeRepository;
    private final RecommendationRepository recommendationRepository;
    private final RecommendationFeedbackRepository feedbackRepository;
    private final TestSessionRepository sessionRepository;
    private final Clock clock;
    private final ContextInferrer inferrer;
    private final RecommendationEngine engine;
    private final TravelPreferenceAdjuster adjuster;

    public RecommendationService(TravelProfileService travelProfileService,
                                 PlaceRepository placeRepository,
                                 RecommendationRepository recommendationRepository,
                                 RecommendationFeedbackRepository feedbackRepository,
                                 TestSessionRepository sessionRepository,
                                 Clock clock,
                                 ContextInferrer inferrer,
                                 RecommendationEngine engine,
                                 TravelPreferenceAdjuster adjuster) {
        this.travelProfileService = travelProfileService;
        this.placeRepository = placeRepository;
        this.recommendationRepository = recommendationRepository;
        this.feedbackRepository = feedbackRepository;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
        this.inferrer = inferrer;
        this.engine = engine;
        this.adjuster = adjuster;
    }

    /**
     * 给某次旅行会话推荐 Top 3。
     *
     * <p>用的不是问卷画像本身，而是<b>叠加过反馈修正的有效画像</b>——
     * 这就是"👎 之后下次推荐会变"的实现方式。修正每次实时算，
     * 问卷画像本身不动（理由见 {@link TravelPreferenceAdjuster}）。
     *
     * @throws com.example.personality.exception.ResourceNotFoundException 会话不存在，
     *         或还没提交（没画像就没法推荐）
     */
    @Transactional
    public RecommendationResponse recommend(Long sessionId, RecommendationRequest request) {

        // ① 用户是谁：读这次会话的旅行画像（没画像会抛 404）
        TravelProfile profile = travelProfileService.loadProfile(sessionId);
        Map<TravelDimension, Integer> questionnaire = profile.toPreferenceMap();

        // ② 有什么可去：取候选地点
        //
        // ⚠️ 这里是模拟数据阶段的简化——直接取全表。
        // 59 个 POI 全在杭州，量也小，取回来让引擎按半径过滤完全够用。
        // 真实产品应该按定位做「边界框查询」（WHERE latitude BETWEEN ? AND ? AND
        // longitude BETWEEN ? AND ?），让数据库先把候选缩到几十条，
        // 否则真接了高德的数据以后，全国几万个 POI 全量捞进内存是要出事的。
        Map<Long, Place> placesById = new LinkedHashMap<>();
        for (Place place : placeRepository.findAll()) {
            placesById.put(place.getId(), place);
        }

        // ③ 这个会话的历史：收到过哪些反馈、看过哪些地点
        SessionHistory history = loadHistory(sessionId,
                resolveScopeSessionIds(sessionId, userIdOf(sessionId)), placesById, questionnaire);

        // ④ 用反馈修正过的偏好。没有反馈时它和问卷画像完全一样
        Map<TravelDimension, Integer> effective = adjuster.effectivePreference(
                questionnaire, history.signals());

        // ⑤ 候选过滤。"换一批"要排除已经看过、且没被 👍 的地点——
        //    不然引擎没有记忆，同样的输入必然算出同样的输出，按钮就成了摆设
        List<PlaceCandidate> candidates = placesById.values().stream()
                .filter(place -> !request.excludeSeenOrDefault()
                        || !history.seenButNotLiked().contains(place.getId()))
                .map(Place::toCandidate)
                .toList();

        // ⑥ 此刻什么情况：现在几点、还剩多久、人在哪、最多走多远、预算上限
        // 走注入的 Clock 而不是 LocalTime.now()：见 ClockConfig 的说明——
        // 直接取系统时间会让自动推断没法测、也没法演示
        LocalTime now = LocalTime.now(clock);

        // 用户说过的状态 + 系统自己推断的状态。
        //
        // ⚠️ 拼在一起是有意的：**用户说的优先，系统只在他没说的时候补**。
        // 饭点推出来"想吃饭"、而用户又明确点了"我有点累了"时，
        // 两个状态会同时生效（一个是猜的、一个是他说的），
        // 而不是让系统用猜的把用户的话盖掉。
        Set<TravelState> inferred = inferrer.inferStates(now);
        Set<TravelState> allStates = new LinkedHashSet<>(request.statesOrEmpty());
        if (request.autoInferOrDefault()) {
            allStates.addAll(inferred);
        }

        RecommendationContext context = RecommendationContext.withLocation(
                now,
                request.remainingMinutesOrDefault(),
                request.latitude(),
                request.longitude(),
                request.maxDistanceKmOrDefault(),
                request.maxTicketPrice(),
                allStates);

        // ⑦ 交给算法。它不认识数据库，也不认识反馈——只做硬过滤 + 打分 + 排序
        List<ScoredPlace> top = engine.recommend(effective, candidates, context, TOP_N);

        // ⑧ 落库：新开一批。存下来的理由是"用户反馈要挂到某条推荐上"，
        //    以及"接受率是否随使用提升"这个核心指标需要历史数据（计划书第十九节）
        int batchNo = recommendationRepository.nextBatchNo(sessionId);
        List<Recommendation> saved = recommendationRepository.saveAll(toEntities(sessionId, batchNo, top));

        return new RecommendationResponse(
                sessionId,
                batchNo,
                generatedAt(saved),
                toPlaces(top, saved),
                buildAppliedContext(context, inferred));
    }

    /**
     * 把"这次实际用了什么处境"整理出来给前端。
     *
     * <p>{@code inferredStates} 只包含<b>真的生效了的</b>推断结果——
     * 用户没开 {@code autoInfer} 时这里是空列表。不能把"推断过但没用上"的也算进去，
     * 那会让前端的提示与事实不符，用户照着改反而改错。
     *
     * <p>这是"系统替用户猜"能够成立的前提：猜了什么必须摊开给人看。
     */
    private static AppliedContext buildAppliedContext(RecommendationContext context,
                                                      Set<TravelState> inferred) {
        List<AppliedContext.StateLabel> states = context.states().stream()
                .map(state -> new AppliedContext.StateLabel(state.name(), state.label()))
                .toList();
        List<AppliedContext.StateLabel> inferredApplied = inferred.stream()
                .filter(context.states()::contains)
                .map(state -> new AppliedContext.StateLabel(state.name(), state.label()))
                .toList();

        return new AppliedContext(
                context.now().toString(),
                context.remainingMinutes(),
                context.maxDistanceKm(),
                context.maxTicketPrice(),
                states,
                inferredApplied);
    }

    /**
     * 记下用户对某条推荐的反馈。
     *
     * <p>只写 {@code recommendation_feedback}，<b>不碰画像表</b>——
     * 修正量是推荐时实时算的（见 {@link TravelPreferenceAdjuster}）。
     * 所以这个方法很轻，只是把"用户表过态"这个事实存下来。
     *
     * <p>重复提交同一条推荐是合法的：用户改主意（👎 → 👍）走的是 UPDATE
     * 而不是插入第二条，否则"接受率"这类统计会被重复数据污染
     * （{@code recommendation_feedback} 上有 {@code UNIQUE(recommendation_id)} 兜着）。
     *
     * @throws com.example.personality.exception.ResourceNotFoundException 这条推荐不存在，
     *         或者不属于这个会话
     */
    @Transactional
    public FeedbackResponse submitFeedback(Long sessionId, Long recommendationId,
                                           RecommendationFeedback.Reaction reaction) {

        Recommendation recommendation = recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> notFoundRecommendation(recommendationId));

        // ⚠️ 必须校验这条推荐属于当前会话。推荐 id 是自增的连续整数，
        // 不校验的话，拿自己的 sessionId 配上别人的 recommendationId
        // 就能往别人的推荐上写反馈——和 V0.6.1 修过的 IDOR 是同一类洞。
        // 返回 404 而不是 403：403 会泄露"这条推荐存在"。
        if (!sessionId.equals(recommendation.getSessionId())) {
            throw notFoundRecommendation(recommendationId);
        }

        RecommendationFeedback feedback = feedbackRepository.findByRecommendationId(recommendationId)
                .orElseGet(() -> RecommendationFeedback.of(recommendationId, reaction));
        feedback.changeReaction(reaction);
        feedbackRepository.save(feedback);

        // 把"这次反馈让画像变了多少"算出来返回，让用户当场看得见影响
        return buildFeedbackResponse(sessionId, recommendationId, reaction);
    }

    private static ResourceNotFoundException notFoundRecommendation(Long recommendationId) {
        return new ResourceNotFoundException("推荐记录不存在：id=" + recommendationId);
    }

    private FeedbackResponse buildFeedbackResponse(Long sessionId, Long recommendationId,
                                                   RecommendationFeedback.Reaction reaction) {
        TravelProfile profile = travelProfileService.loadProfile(sessionId);
        Map<TravelDimension, Integer> questionnaire = profile.toPreferenceMap();

        Map<Long, Place> placesById = new LinkedHashMap<>();
        for (Place place : placeRepository.findAll()) {
            placesById.put(place.getId(), place);
        }

        SessionHistory history = loadHistory(sessionId,
                resolveScopeSessionIds(sessionId, userIdOf(sessionId)), placesById, questionnaire);
        Map<TravelDimension, Integer> effective = adjuster.effectivePreference(
                questionnaire, history.signals());

        List<FeedbackResponse.Adjustment> adjustments = new ArrayList<>();
        for (TravelDimension dimension : adjuster.adjustedDimensions(history.signals())) {
            int before = questionnaire.getOrDefault(dimension, 0);
            int after = effective.getOrDefault(dimension, before);
            adjustments.add(new FeedbackResponse.Adjustment(
                    dimension.name(), dimension.label(), before, after));
        }

        return new FeedbackResponse(recommendationId, reaction.name(), adjustments);
    }

    // ==========================================================
    // 会话历史：反馈信号 + 看过哪些地点
    // ==========================================================

    /**
     * 把这个用户的推荐记录和反馈读出来，折算成两样东西：
     * <b>修正信号</b>（喂给 {@link TravelPreferenceAdjuster}）和
     * <b>本会话已经看过且没被点赞的地点</b>（喂给"换一批"的排除逻辑）。
     *
     * <h2>⚠️ 修正和排除，作用范围刻意不同</h2>
     *
     * <ul>
     *   <li><b>修正（👍/👎 的影响）跨会话累积</b>——用户在这一次测试里点过的反馈，
     *       下次做新测试时依然算数。这正是"用得越多越准"的实现方式；
     *       只算当前会话的话，用户点一次"重新测一次"就把积累全清零了。</li>
     *   <li><b>排除（"换一批"）只看当前会话</b>——它的语义是"这批我已经看过了，
     *       给我新的"。如果跨会话排除，用户隔天再来点"换一批"，
     *       会把历史上所有看过的地方全部排掉，很快就没得推了。</li>
     * </ul>
     *
     * <p>归因用的是<b>问卷画像</b>而不是有效画像——这样同一条反馈
     * 在任何时刻都会归到同一个维度上，历史才是可解释的。
     *
     * @param scopeSessionIds 修正的作用范围：登录用户是"他的全部旅行会话"，
     *                        匿名用户只有当前这一个会话
     */
    private SessionHistory loadHistory(Long sessionId, List<Long> scopeSessionIds,
                                       Map<Long, Place> placesById,
                                       Map<TravelDimension, Integer> questionnaire) {

        List<Recommendation> seenInScope = recommendationRepository
                .findBySessionIdInOrderByBatchNoAscRankNoAsc(scopeSessionIds);
        if (seenInScope.isEmpty()) {
            return new SessionHistory(List.of(), Set.of());
        }

        Map<Long, Long> placeIdByRecommendationId = new HashMap<>();
        Set<Long> seenPlaceIdsHere = new LinkedHashSet<>();
        for (Recommendation recommendation : seenInScope) {
            placeIdByRecommendationId.put(recommendation.getId(), recommendation.getPlaceId());
            // 只有当前会话的才算"看过"，跨会话的历史不参与"换一批"的排除
            if (sessionId.equals(recommendation.getSessionId())) {
                seenPlaceIdsHere.add(recommendation.getPlaceId());
            }
        }

        List<RecommendationFeedback> feedbacks = feedbackRepository
                .findByRecommendationIdIn(placeIdByRecommendationId.keySet());

        // 只取最近的若干条。⚠️ 不加这个窗口的话会出问题：
        // 修正量有 ±40 的封顶，一旦撞顶就再也动不了了——
        // 用户改了口味，系统却因为半年前的反馈而锁死。
        // 取最近的在语义上也更对："此刻的偏好"应该由最近的反馈决定。
        List<RecommendationFeedback> recent = feedbacks.stream()
                .sorted(Comparator.comparing(RecommendationFeedback::getCreatedAt).reversed())
                .limit(RECENT_FEEDBACK_LIMIT)
                .toList();

        List<TravelPreferenceAdjuster.FeedbackSignal> signals = new ArrayList<>();
        Set<Long> likedPlaceIdsHere = new LinkedHashSet<>();
        for (RecommendationFeedback feedback : recent) {
            Long placeId = placeIdByRecommendationId.get(feedback.getRecommendationId());
            Place place = placeId == null ? null : placesById.get(placeId);
            if (place == null) {
                continue;   // 地点被删了之类的极端情况，跳过而不是崩掉
            }
            boolean liked = feedback.getReaction() == RecommendationFeedback.Reaction.LIKE;
            if (liked && seenPlaceIdsHere.contains(placeId)) {
                likedPlaceIdsHere.add(placeId);
            }
            signals.add(new TravelPreferenceAdjuster.FeedbackSignal(
                    adjuster.dominantDimension(questionnaire, place.toCandidate().traits()),
                    liked));
        }

        // 本会话看过、但没被点赞的地点 → "换一批"时排除。
        // 点过 👍 的不排除：用户喜欢它，应该还能再被推荐到。
        Set<Long> seenButNotLiked = new LinkedHashSet<>(seenPlaceIdsHere);
        seenButNotLiked.removeAll(likedPlaceIdsHere);

        return new SessionHistory(signals, seenButNotLiked);
    }

    /**
     * 会话属于哪个用户。匿名会话返回 null。
     *
     * <p>只查这一次，拿到的 userId 决定反馈修正的作用范围（见
     * {@link #resolveScopeSessionIds}）。会话不存在时返回 null 而不是抛异常——
     * 调用方（{@code recommend}）在此之前已经通过 loadProfile 校验过会话了，
     * 这里再抛一次只会让错误信息变模糊。
     */
    private Long userIdOf(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .map(TestSession::getUserId)
                .orElse(null);
    }

    /**
     * 修正的作用范围：登录用户是他<b>全部</b>的旅行会话，匿名用户只有当前这个。
     *
     * <p>匿名用户没有稳定的身份，跨会话记忆无从谈起——这是能力的边界，
     * 不是遗漏。和"历史记录只有登录用户才有"是同一个道理。
     */
    private List<Long> resolveScopeSessionIds(Long sessionId, Long userId) {
        if (userId == null) {
            return List.of(sessionId);
        }
        List<TestSession> travelSessions = sessionRepository
                .findByUserIdAndScaleOrderByCreatedAtDesc(userId, QuestionScale.TRAVEL);

        List<Long> ids = new ArrayList<>(travelSessions.size() + 1);
        ids.add(sessionId);   // 兜底：当前会话一定在范围内
        for (TestSession session : travelSessions) {
            if (!session.getId().equals(sessionId)) {
                ids.add(session.getId());
            }
        }
        return ids;
    }

    /** 一次推荐折算出的两样东西。 */
    private record SessionHistory(
            List<TravelPreferenceAdjuster.FeedbackSignal> signals,
            Set<Long> seenButNotLiked) {
    }

    // ==========================================================
    // 落库映射
    // ==========================================================

    private List<Recommendation> toEntities(Long sessionId, int batchNo, List<ScoredPlace> top) {
        List<Recommendation> rows = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            ScoredPlace scored = top.get(i);
            rows.add(Recommendation.of(
                    sessionId,
                    batchNo,
                    i + 1,                       // rank 从 1 开始
                    scored.place().id(),
                    toColumnScore(scored.score())));
        }
        return rows;
    }

    /**
     * 把引擎的 double 分数转成数据库列的类型。
     *
     * <p>{@code recommendations.score} 是 {@code NUMERIC(4,3)}，
     * 所以要显式指定小数位数——不指定的话 {@code BigDecimal.valueOf(0.9125...)}
     * 会带一长串小数位，存进去被截断，读出来和当初算的对不上。
     *
     * <p>用 {@code valueOf(double)} 而不是 {@code new BigDecimal(double)}：
     * 后者会把浮点误差原样展开（0.1 变成 0.1000000000000000055511151231257827），
     * 前者走的是 {@code Double.toString}，得到的是人能预期的那串数字。
     *
     * <p>⚠️ 引擎保证了 score ≤ 1（三个因子相乘，每个都 ≤ 1），
     * 而数据库上有 {@code CHECK (score BETWEEN 0 AND 1)} 卡着。
     * 改打分公式时务必保持这个不变量。
     */
    private static BigDecimal toColumnScore(double score) {
        return BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 这批推荐的生成时间。
     *
     * <p>取的是入库时 {@code @PrePersist} 填的那个值，而不是再 {@code Instant.now()} 一次——
     * 否则响应里的时间和库里的对不上，排查问题时容易怀疑人生。
     * 没有推荐结果（空列表）时退回当前时间。
     */
    private static Instant generatedAt(List<Recommendation> saved) {
        return saved.isEmpty() ? Instant.now() : saved.get(0).getCreatedAt();
    }

    // ==========================================================
    // 响应映射
    // ==========================================================

    /**
     * 把打分结果转成响应。
     *
     * <p>{@code saved} 必须和 {@code top} 一一对应（同一个下标是同一条）——
     * {@link #toEntities} 就是按顺序造的，{@code saveAll} 也保持顺序。
     * 需要它是因为<b>响应里要带 recommendationId</b>：前端点 👍/👎 时
     * 得说清"评的是哪一条推荐"，而 id 是入库之后才有的。
     */
    private static List<RecommendedPlace> toPlaces(List<ScoredPlace> top, List<Recommendation> saved) {
        List<RecommendedPlace> places = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            places.add(toPlace(i + 1, saved.get(i).getId(), top.get(i)));
        }
        return places;
    }

    private static RecommendedPlace toPlace(int rank, Long recommendationId, ScoredPlace scored) {
        PlaceCandidate place = scored.place();
        return new RecommendedPlace(
                rank,
                recommendationId,
                place.id(),
                place.name(),
                place.category(),
                place.description(),
                scored.scorePercent(),
                scored.distanceKm(),
                place.ticketPrice(),
                place.suggestedMinutes(),
                formatTime(place.openFrom()),
                formatTime(place.openTo()),
                toReasons(scored.topMatches()));
    }

    /** 全天开放的地点（公园、街区）营业时间是 null，原样输出 null，前端显示成"全天"。 */
    private static String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    private static List<MatchReason> toReasons(List<ScoredPlace.MatchedDimension> matches) {
        List<MatchReason> reasons = new ArrayList<>(matches.size());
        for (ScoredPlace.MatchedDimension match : matches) {
            reasons.add(new MatchReason(
                    match.dimension().name(),
                    match.dimension().label(),
                    match.userPreference(),
                    match.placeValue()));
        }
        return reasons;
    }
}
