package com.example.personality.service;

import com.example.personality.ai.TravelReasonInput;
import com.example.personality.domain.AmbientContext;
import com.example.personality.domain.PlaceCandidate;
import com.example.personality.domain.RecommendationContext;
import com.example.personality.domain.ScoredPlace;
import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.domain.Weather;
import com.example.personality.dto.AppliedContext;
import com.example.personality.dto.FeedbackResponse;
import com.example.personality.dto.MatchReason;
import com.example.personality.dto.RecommendationRequest;
import com.example.personality.dto.RecommendationResponse;
import com.example.personality.dto.RecommendedPlace;
import com.example.personality.dto.ScoreBreakdown;
import com.example.personality.entity.Place;
import com.example.personality.entity.QuestionScale;
import com.example.personality.entity.Recommendation;
import com.example.personality.entity.RecommendationBatch;
import com.example.personality.entity.RecommendationFeedback;
import com.example.personality.entity.TestSession;
import com.example.personality.entity.TravelProfile;
import com.example.personality.exception.ResourceNotFoundException;
import com.example.personality.repository.PlaceRepository;
import com.example.personality.repository.RecommendationBatchRepository;
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
 * <p>整个方法一个事务，没有拆开——因为它<b>全程只碰本地数据库</b>。
 *
 * <p>⚠️ 这条性质是<b>要主动维护</b>的，不是天然成立的。接入高德之后，
 * 地名、天气这类需要网络等待的数据如果在这里现查，事务就会横跨一次
 * 几百毫秒到几秒的网络往返，把数据库连接白白占住（池子只有 10 个），
 * 并发一上来所有接口一起排队超时——{@code AiReportService} 已经吃过一次这个亏。
 *
 * <p>所以规矩是：<b>要外部数据，调用方先在事务外取好再传进来</b>（见 {@link AmbientService}）。
 * 往这个方法里加任何 {@code RestClient} / HTTP 调用之前，先想清楚这一条。
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
    private final RecommendationBatchRepository batchRepository;
    private final RecommendationFeedbackRepository feedbackRepository;
    private final TestSessionRepository sessionRepository;
    private final Clock clock;
    private final ContextInferrer inferrer;
    private final RecommendationEngine engine;
    private final TravelPreferenceAdjuster adjuster;

    public RecommendationService(TravelProfileService travelProfileService,
                                 PlaceRepository placeRepository,
                                 RecommendationRepository recommendationRepository,
                                 RecommendationBatchRepository batchRepository,
                                 RecommendationFeedbackRepository feedbackRepository,
                                 TestSessionRepository sessionRepository,
                                 Clock clock,
                                 ContextInferrer inferrer,
                                 RecommendationEngine engine,
                                 TravelPreferenceAdjuster adjuster) {
        this.travelProfileService = travelProfileService;
        this.placeRepository = placeRepository;
        this.recommendationRepository = recommendationRepository;
        this.batchRepository = batchRepository;
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
     * <h2>⚠️ 这个方法里不许出现网络调用</h2>
     *
     * <p>整个方法是一个事务，全程占着数据库连接；而外部调用要等几百毫秒到几秒。
     * 连接池只有 10 个连接，把网络等待放进事务，十来个并发就能把池子占满，
     * 让所有接口一起排队超时。
     *
     * <p>所以需要外部数据（地名、天气）时，调用方要先在<b>事务外</b>
     * 通过 {@link AmbientService} 取好，再当参数传进来。保持这个方法
     * "只碰本地数据库"——这样它的耗时是确定的、可预测的。
     *
     * @param ambient 地名 + 天气，由调用方在事务外用 {@link AmbientService} 查好。
     *                <b>两个字段都可以为 null</b>（没定位、没配高德、或上游失败），
     *                这时不给地名、天气因子恒为 1.0，其余一切照常
     * @throws com.example.personality.exception.ResourceNotFoundException 会话不存在，
     *         或还没提交（没画像就没法推荐）
     */
    @Transactional
    public RecommendationResponse recommend(Long sessionId, RecommendationRequest request,
                                            AmbientContext ambient) {

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

        // 天气挂在处境上（而不是单独传给引擎）：它和"还剩多少时间""我累了"
        // 是同一层的东西——"此刻的处境"，每次请求都可能不同，不持久化。
        // 拿不到天气时这里是 null，引擎会把天气系数当作 1.0（等于这一项不存在）。
        RecommendationContext context = RecommendationContext.withLocation(
                now,
                request.remainingMinutesOrDefault(),
                request.latitude(),
                request.longitude(),
                request.maxDistanceKmOrDefault(),
                request.maxTicketPrice(),
                allStates)
                .withWeather(ambient.weather());

        // ⑦ 交给算法。它不认识数据库，也不认识反馈——只做硬过滤 + 打分 + 排序
        List<ScoredPlace> top = engine.recommend(effective, candidates, context, TOP_N);

        // ⑧ 落库：新开一批。存下来的理由是"用户反馈要挂到某条推荐上"，
        //    以及"接受率是否随使用提升"这个核心指标需要历史数据（计划书第十九节）
        int batchNo = recommendationRepository.nextBatchNo(sessionId);
        List<Recommendation> saved = recommendationRepository.saveAll(toEntities(sessionId, batchNo, top));

        // ⑨ 记下这批推荐当时的处境（V11）。
        //
        // 生成 AI 理由时是**另一次请求**——推荐要秒回，AI 要几秒，不能绑在一起。
        // 到那时内存里早就什么都没有了，只能从数据库重建"当初是怎么算的"。
        // 不存的话，AI 拿到的就只有一个孤零零的百分数，那它只能编。
        //
        // ⚠️ 这里存的是**最终生效的**状态（用户说的 + 系统推断的），
        // 并把"哪些是推断的"单独记一份——AI 转述时不能把猜的说成用户说的。
        batchRepository.save(RecommendationBatch.of(
                sessionId, batchNo,
                ambient.location() == null ? null : blankToNull(ambient.location().label()),
                context.now(),
                context.remainingMinutes(),
                BigDecimal.valueOf(context.maxDistanceKm()),
                context.maxTicketPrice(),
                context.states(),
                inferred,
                context.weather() == null ? null : context.weather().condition(),
                context.weather() == null ? null : context.weather().temperature()));

        return new RecommendationResponse(
                sessionId,
                batchNo,
                generatedAt(saved),
                toPlaces(top, saved),
                buildAppliedContext(context, inferred),
                // 地名是调用方在事务外查好传进来的。取不到就是 null，
                // 不在这里补救——补救意味着一次网络调用，而这里在事务里。
                ambient.location() == null ? null : blankToNull(ambient.location().label()));
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
                inferredApplied,
                toWeatherLabel(context.weather()));
    }

    /**
     * 天气也摊开给用户看。
     *
     * <p>和 {@code inferredStates} 是同一条原则：<b>系统替用户做的判断，
     * 都要能被看见。</b>"下雨天把户外景点往后排"是一个相当强的判断，
     * 不说的话，用户只会觉得"这几个地方怎么跟我口味不搭"。
     *
     * <p>{@code affectsRecommendation} 单独标出来，是为了让前端能区分
     * 「今天晴天，天气对结果没影响」和「下雨了，户外的地方都被压了」——
     * 都显示成"已考虑天气"的话，这个提示很快会变成噪音。
     */
    private static AppliedContext.WeatherLabel toWeatherLabel(Weather weather) {
        if (weather == null) {
            return null;   // 没拿到天气，前端就不显示这一项
        }
        return new AppliedContext.WeatherLabel(
                weather.condition(),
                weather.temperature(),
                weather.label(),
                weather.affectsRecommendation());
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
    // 推荐理由：重建"这批是怎么算的" / 回填生成结果
    // ==========================================================
    //
    // 这两个方法放在这里的理由：生成 AI 理由时需要的"依据"，绝大部分
    // 是**这个类自己在算推荐时用过的东西**——有效偏好（叠加了反馈修正）、
    // 地点属性、打分因子。放到别的类里去重建，就得把 loadHistory /
    // resolveScopeSessionIds 那几十行抄一遍，而抄出来的那份迟早会和
    // 原版不一致，于是"AI 说的"和"面板上显示的"就开始打架。
    //
    // ⚠️ 注意它们都是**独立的小事务**，不是被 recommend() 调用的。
    // 生成理由走的是和 AiReportService 一样的三段式：
    //     ① 这里读（短事务） → ② 调大模型（事务外） → ③ 这里写（短事务）
    // 大模型调用绝不能进事务，理由见本类上方「事务边界」那一节。

    /**
     * 重建一批推荐的全部依据，供生成 AI 理由使用。
     *
     * <p>作用于该会话<b>最新的一批</b>——前端展示的就是那一批。
     *
     * @throws ResourceNotFoundException 这个会话还没推荐过
     */
    @Transactional(readOnly = true)
    public ReasonContext buildReasonContext(Long sessionId) {
        RecommendationBatch batch = batchRepository
                .findFirstBySessionIdOrderByBatchNoDesc(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "会话 " + sessionId + " 还没有推荐记录，无法生成推荐理由"));

        List<Recommendation> rows = recommendationRepository
                .findBySessionIdAndBatchNoOrderByRankNoAsc(sessionId, batch.getBatchNo());

        Map<Long, Place> placesById = new LinkedHashMap<>();
        for (Place place : placeRepository.findAll()) {
            placesById.put(place.getId(), place);
        }

        // "为什么合你的口味"要用**有效偏好**来算——也就是叠加过反馈修正的那份。
        // 用问卷原始画像的话，用户点过 👎 之后 AI 的解释就会和面板上显示的
        // 对不上（面板用的也是有效偏好）。保持一致比"用哪一份"更重要。
        TravelProfile profile = travelProfileService.loadProfile(sessionId);
        Map<TravelDimension, Integer> questionnaire = profile.toPreferenceMap();
        SessionHistory history = loadHistory(sessionId,
                resolveScopeSessionIds(sessionId, userIdOf(sessionId)), placesById, questionnaire);
        Map<TravelDimension, Integer> effective =
                adjuster.effectivePreference(questionnaire, history.signals());

        List<TravelReasonInput.ExplainedPlace> explained = new ArrayList<>(rows.size());
        Map<Integer, String> existingReasons = new LinkedHashMap<>();
        for (Recommendation row : rows) {
            if (row.getReason() != null && !row.getReason().isBlank()) {
                existingReasons.put(row.getRankNo(), row.getReason());
            }
            Place place = placesById.get(row.getPlaceId());
            if (place == null) {
                continue;   // 地点被删了之类的极端情况，跳过这一条
            }
            explained.add(toExplained(row, place, effective));
        }

        return new ReasonContext(batch.getBatchNo(), buildReasonInput(batch, explained), existingReasons);
    }

    private TravelReasonInput.ExplainedPlace toExplained(Recommendation row, Place place,
                                                         Map<TravelDimension, Integer> effective) {
        Recommendation.Factors f = row.getFactors();
        return new TravelReasonInput.ExplainedPlace(
                row.getRankNo(),
                place.getName(),
                place.getCategory(),
                place.getDescription(),
                (int) Math.round(row.getScore().doubleValue() * 100),
                new TravelReasonInput.Factors(
                        ratio(f.interest()), ratio(f.distance()), ratio(f.quality()),
                        ratio(f.state()), ratio(f.weather())),
                place.getTicketPrice(),
                place.getSuggestedMinutes(),
                formatTime(place.getOpenFrom()),
                formatTime(place.getOpenTo()),
                // 和前端「为什么是它」面板走的是同一个算法，所以两处必然一致
                engine.matchDimensions(effective, place.toCandidate().traits()));
    }

    /**
     * 从批次处境重建 {@link TravelReasonInput} 的处境部分。
     *
     * <p>⚠️ 天气要<b>成对</b>判断：condition 和 temperature 有一个是 null，
     * 就当作没有天气。只判其中一个的话，另一个 null 会在
     * {@code doubleValue()} 上抛空指针——而这是个"外部数据源没配好"
     * 就会走到的正常分支，不该炸。
     */
    private static TravelReasonInput buildReasonInput(RecommendationBatch batch,
                                                      List<TravelReasonInput.ExplainedPlace> places) {
        Weather weather = null;
        if (batch.getWeatherCondition() != null && batch.getWeatherTemperature() != null) {
            weather = Weather.of(batch.getWeatherCondition(),
                    batch.getWeatherTemperature().doubleValue());
        }

        return new TravelReasonInput(
                batch.getLocationLabel(),
                batch.getContextTime(),
                batch.getRemainingMinutes(),
                batch.getMaxDistanceKm().doubleValue(),
                batch.getMaxTicketPrice(),
                batch.stateSet(),
                batch.inferredStateSet(),
                weather,
                places);
    }

    /** {@code BigDecimal} 转回比例，调用方保证非 null（都是刚存下去的）。 */
    private static double ratio(BigDecimal value) {
        return value == null ? 1.0 : value.doubleValue();
    }

    /**
     * 把生成好的理由写回推荐记录。
     *
     * <p>只改 {@code reason} 列，<b>不新开批次</b>——理由是对<b>已有这一批</b>的注解，
     * 不是一次新的推荐。新开批次会让前端展示的那批和刚生成理由的那批对不上。
     *
     * @param reasonByRank 名次 → 理由。只写这里有的名次，
     *                     模型少给了几条也不影响其余几条落库
     */
    @Transactional
    public void attachReasons(Long sessionId, int batchNo, Map<Integer, String> reasonByRank) {
        List<Recommendation> rows = recommendationRepository
                .findBySessionIdAndBatchNoOrderByRankNoAsc(sessionId, batchNo);

        List<Recommendation> changed = new ArrayList<>(rows.size());
        for (Recommendation row : rows) {
            String reason = reasonByRank.get(row.getRankNo());
            if (reason != null && !reason.isBlank()) {
                row.attachReason(reason);
                changed.add(row);
            }
        }
        recommendationRepository.saveAll(changed);
    }

    /**
     * 生成理由所需的一切：批次号、AI 的输入、以及这批里<b>已经有理由</b>的那些。
     *
     * @param batchNo          最新一批的批次号，回填和响应都要用
     * @param input            交给大模型的事实
     * @param existingReasons  名次 → 已有理由。用来判断能不能直接吃缓存
     */
    public record ReasonContext(int batchNo, TravelReasonInput input,
                                Map<Integer, String> existingReasons) {
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
                    toColumnRatio(scored.score()),
                    new Recommendation.Factors(
                            toColumnRatio(scored.interestScore()),
                            toColumnRatio(scored.distanceFactor()),
                            toColumnRatio(scored.qualityFactor()),
                            toColumnRatio(scored.stateFactor()),
                            toColumnRatio(scored.weatherFactor()))));
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
     * <p>⚠️ 引擎保证了 score ≤ 1（五个因子相乘，每个都 ≤ 1），
     * 而数据库上有 {@code CHECK (score BETWEEN 0 AND 1)} 卡着。
     * 改打分公式时务必保持这个不变量。
     *
     * <p>五个因子用的是同一个方法——它们的列定义和 score 一模一样
     * （都是 {@code NUMERIC(4,3)}），而且都表示"0~1 的比例"。
     * 起名 {@code Ratio} 而不是 {@code Score}，是因为它两个都转。
     */
    private static BigDecimal toColumnRatio(double ratio) {
        return BigDecimal.valueOf(ratio).setScale(3, RoundingMode.HALF_UP);
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
                place.latitude(),
                place.longitude(),
                place.description(),
                scored.scorePercent(),
                // 分数是怎么来的——只给一个百分数回答不了"为什么是它"
                new ScoreBreakdown(
                        scored.interestScore(),
                        scored.distanceFactor(),
                        scored.qualityFactor(),
                        scored.stateFactor(),
                        scored.weatherFactor(),
                        scored.score()),
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

    /**
     * 空串统一成 null。
     *
     * <p>接口契约里"没有地名"只有<b>一种</b>表示法。留两种（null 和 ""）的话，
     * 前端就得写 {@code {label && <p>{label}</p>}} 之外再加一层判空，
     * 而漏写的那次会渲染出一个空白的"你在 附近"。
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
