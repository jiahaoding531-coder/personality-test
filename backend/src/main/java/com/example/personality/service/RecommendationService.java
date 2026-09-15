package com.example.personality.service;

import com.example.personality.domain.PlaceCandidate;
import com.example.personality.domain.RecommendationContext;
import com.example.personality.domain.ScoredPlace;
import com.example.personality.dto.MatchReason;
import com.example.personality.dto.RecommendationRequest;
import com.example.personality.dto.RecommendationResponse;
import com.example.personality.dto.RecommendedPlace;
import com.example.personality.entity.Place;
import com.example.personality.entity.Recommendation;
import com.example.personality.entity.TravelProfile;
import com.example.personality.repository.PlaceRepository;
import com.example.personality.repository.RecommendationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    /** 营业时间的展示格式。数据库里是 TIME，输出成 "HH:mm" 给前端直接用。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final TravelProfileService travelProfileService;
    private final PlaceRepository placeRepository;
    private final RecommendationRepository recommendationRepository;
    private final RecommendationEngine engine;

    public RecommendationService(TravelProfileService travelProfileService,
                                 PlaceRepository placeRepository,
                                 RecommendationRepository recommendationRepository,
                                 RecommendationEngine engine) {
        this.travelProfileService = travelProfileService;
        this.placeRepository = placeRepository;
        this.recommendationRepository = recommendationRepository;
        this.engine = engine;
    }

    /**
     * 给某次旅行会话推荐 Top 3。
     *
     * @throws com.example.personality.exception.ResourceNotFoundException 会话不存在，
     *         或还没提交（没画像就没法推荐）
     */
    @Transactional
    public RecommendationResponse recommend(Long sessionId, RecommendationRequest request) {

        // ① 用户是谁：读这次会话的旅行画像（没画像会抛 404）
        TravelProfile profile = travelProfileService.loadProfile(sessionId);

        // ② 有什么可去：取候选地点
        //
        // ⚠️ 这里是模拟数据阶段的简化——直接取全表。
        // 59 个 POI 全在杭州，量也小，取回来让引擎按半径过滤完全够用。
        // 真实产品应该按定位做「边界框查询」（WHERE latitude BETWEEN ? AND ? AND
        // longitude BETWEEN ? AND ?），让数据库先把候选缩到几十条，
        // 否则真接了高德的数据以后，全国几万个 POI 全量捞进内存是要出事的。
        List<PlaceCandidate> candidates = placeRepository.findAll().stream()
                .map(Place::toCandidate)
                .toList();

        // ③ 此刻什么情况：现在几点、还剩多久、人在哪、最多走多远
        RecommendationContext context = RecommendationContext.withLocation(
                LocalTime.now(),
                request.remainingMinutesOrDefault(),
                request.latitude(),
                request.longitude(),
                request.maxDistanceKmOrDefault());

        // ④ 交给算法。它不认识数据库，只做硬过滤 + 打分 + 排序
        List<ScoredPlace> top = engine.recommend(
                profile.toPreferenceMap(), candidates, context, TOP_N);

        // ⑤ 落库：新开一批。存下来的理由是"用户反馈要挂到某条推荐上"，
        //    以及"接受率是否随使用提升"这个核心指标需要历史数据（计划书第十九节）
        int batchNo = recommendationRepository.nextBatchNo(sessionId);
        List<Recommendation> saved = recommendationRepository.saveAll(toEntities(sessionId, batchNo, top));

        return new RecommendationResponse(
                sessionId,
                batchNo,
                generatedAt(saved),
                toPlaces(top));
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

    private static List<RecommendedPlace> toPlaces(List<ScoredPlace> top) {
        List<RecommendedPlace> places = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            places.add(toPlace(i + 1, top.get(i)));
        }
        return places;
    }

    private static RecommendedPlace toPlace(int rank, ScoredPlace scored) {
        PlaceCandidate place = scored.place();
        return new RecommendedPlace(
                rank,
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
