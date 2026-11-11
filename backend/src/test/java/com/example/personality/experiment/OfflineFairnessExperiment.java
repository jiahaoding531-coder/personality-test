package com.example.personality.experiment;

import com.example.personality.domain.*;
import com.example.personality.service.RecommendationEngine;
import java.util.*;
import static com.example.personality.experiment.OfflineRanking.Strategy;

/** 离线实验入口，不加载 Spring、不访问数据库或外部 API。 */
public final class OfflineFairnessExperiment {
    public record Candidate(long id, double baseScore, double distanceKm, double priority,
                            double interest, double distanceFactor, double qualityFactor,
                            double stateFactor, double weatherFactor) { }
    public record Decision(String queryId, String stratum, List<Candidate> eligible, List<Long> selected) { }
    public record Summary(int queries, int emptyQueries, Map<Long, Long> selections,
                          Map<Long, Integer> opportunities, OfflineExposureMetrics.Metrics metrics,
                          Double meanBaseScore, Double relevanceRetention, Double meanDistanceKm) { }
    public record Variant(Strategy strategy, Summary aggregate, Map<String, Summary> strata, List<Decision> decisions) { }
    public record Report(String version, String measurement, String datasetId, String datasetKind,
                         long seed, double fairnessWeight, List<String> queryOrder, List<Variant> variants) { }

    public Report run(OfflineExperimentInput input) {
        return run(input, List.of(Strategy.values()));
    }

    Report run(OfflineExperimentInput input, List<Strategy> executionOrder) {
        Objects.requireNonNull(input, "必须提供离线输入");
        input.validate();
        if (executionOrder == null || executionOrder.size() != 3
                || !new HashSet<>(executionOrder).equals(Set.of(Strategy.values()))) {
            throw new IllegalArgumentException("三组策略必须各执行一次");
        }
        // 固定 ID 顺序消除数据库/文件行顺序对同分排名的影响。
        List<PlaceCandidate> places = input.nodes().stream().sorted(Comparator.comparingLong(OfflineExperimentInput.Node::id))
                .map(n -> new PlaceCandidate(n.id(), n.name(), n.category(), n.latitude(), n.longitude(), n.traits(),
                        n.indoor(), n.quality(), n.ticketPrice(), n.suggestedMinutes(),
                        n.openFrom() == null ? null : OfflineExperimentInput.time(n.openFrom()),
                        n.openTo() == null ? null : OfflineExperimentInput.time(n.openTo()), "离线输入"))
                .toList();
        var queries = new ArrayList<>(input.queries());
        Collections.shuffle(queries, new Random(input.seed()));
        var engine = new RecommendationEngine();
        List<Prepared> prepared = new ArrayList<>();
        for (var query : queries) {
            var context = RecommendationContext.withLocation(OfflineExperimentInput.time(query.localTime()),
                    query.remainingMinutes(), query.latitude(), query.longitude(), query.maxDistanceKm(),
                    query.maxTicketPrice(), query.states()).withExtraBias(query.extraBias());
            // 不调用业务 Service：不写推荐记录，不改变 seen/反馈，不访问天气或定位服务。
            var feasible = List.copyOf(engine.recommend(Map.copyOf(query.preference()), places, context, places.size()));
            double ideal = OfflineRanking.rank(feasible, Strategy.RELEVANCE, Map.of(), input.fairnessWeight())
                    .stream().mapToDouble(ScoredPlace::score).sum();
            prepared.add(new Prepared(query.id(), query.stratum(), feasible, ideal));
        }
        Set<Long> tail = Set.copyOf(input.longTailPlaceIds());
        List<Variant> variants = new ArrayList<>();
        for (var strategy : executionOrder) {
            var aggregate = new Accumulator();
            Map<String, Accumulator> strata = new TreeMap<>();
            List<Decision> decisions = new ArrayList<>();
            for (var query : prepared) {
                var stratum = strata.computeIfAbsent(query.stratum(), ignored -> new Accumulator());
                // 只读本组、本场景已经完成的选择，试排和其他策略都不能增加计数。
                var selected = OfflineRanking.rank(query.feasible(), strategy, stratum.selections, input.fairnessWeight());
                var evidence = query.feasible().stream().map(p -> new Candidate(p.place().id(), p.score(), p.distanceKm(),
                        OfflineRanking.priority(p, strategy, stratum.selections, input.fairnessWeight()),
                        p.interestScore(), p.distanceFactor(), p.qualityFactor(), p.stateFactor(), p.weatherFactor())).toList();
                decisions.add(new Decision(query.id(), query.stratum(), evidence, selected.stream().map(p -> p.place().id()).toList()));
                stratum.accept(query, selected);
                aggregate.accept(query, selected);
            }
            Map<String, Summary> summaries = new TreeMap<>();
            strata.forEach((name, accumulator) -> summaries.put(name, accumulator.summary(tail)));
            variants.add(new Variant(strategy, aggregate.summary(tail), Collections.unmodifiableMap(summaries), List.copyOf(decisions)));
        }
        variants.sort(Comparator.comparing(Variant::strategy));
        return new Report(OfflineRanking.VERSION, "SIMULATED_SELECTIONS_EQUAL_POSITION_WEIGHT", input.datasetId(), input.datasetKind(),
                input.seed(), input.fairnessWeight(), queries.stream().map(OfflineExperimentInput.Query::id).toList(), List.copyOf(variants));
    }

    private record Prepared(String id, String stratum, List<ScoredPlace> feasible, double idealScore) { }

    private static final class Accumulator {
        final Map<Long, Long> selections = new TreeMap<>();
        final Map<Long, Integer> opportunities = new TreeMap<>();
        int queries, emptyQueries;
        long slots;
        double score, ideal, distance;

        void accept(Prepared query, List<ScoredPlace> selected) {
            queries++;
            if (selected.isEmpty()) emptyQueries++;
            for (var p : query.feasible()) {
                opportunities.merge(p.place().id(), 1, Integer::sum);
                selections.putIfAbsent(p.place().id(), 0L);
            }
            ideal += query.idealScore();
            for (var p : selected) {
                selections.merge(p.place().id(), 1L, Long::sum);
                slots++;
                score += p.score();
                distance += p.distanceKm();
            }
        }

        Summary summary(Set<Long> tail) {
            return new Summary(queries, emptyQueries, Collections.unmodifiableMap(new TreeMap<>(selections)),
                    Collections.unmodifiableMap(new TreeMap<>(opportunities)),
                    OfflineExposureMetrics.calculate(selections, opportunities, tail),
                    slots == 0 ? null : score / slots, ideal == 0 ? null : score / ideal, slots == 0 ? null : distance / slots);
        }
    }
}
