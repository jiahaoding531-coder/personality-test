package com.example.personality.experiment;

import java.util.*;

/** 多种子实验的离线契约；不参与线上推荐。 */
public final class OfflineRobustnessExperiment {
    public static final String VERSION = "offline-robustness-v1";
    public record Plan(String planId, String datasetId, List<Long> seeds, List<Double> fairnessWeights) { }
    public record Stats(int validCount, int missingCount, Double mean, Double min, Double max) { }
    public record Run(long seed, double fairnessWeight, OfflineFairnessExperiment.Report report) { }
    public record Summary(double fairnessWeight, String scope, String stratum, OfflineRanking.Strategy strategy,
                          Map<String, Stats> metrics) { }
    public record PairedDelta(double fairnessWeight, String scope, String stratum, Map<String, Stats> metrics) { }
    public record Report(String version, String measurement, String datasetKind, Plan plan, List<Run> runs,
                         List<Summary> summaries, List<PairedDelta> pairedFairMinusRelevance) { }

    public Report run(OfflineExperimentInput input, Plan plan) {
        Objects.requireNonNull(input, "必须提供数据");
        input.validate();
        Plan frozen = validateAndFreeze(input, plan);
        List<Run> runs = new ArrayList<>();
        // 每轮 new 执行器并由其重置各组计数。顺序执行，避免占满本机内存。
        for (long seed : frozen.seeds()) {
            for (double weight : frozen.fairnessWeights()) {
                var current = new OfflineExperimentInput(input.datasetId(), input.datasetKind(), input.provenance(),
                        input.coordinateSystem(), input.longTailDefinition(), seed, weight,
                        input.longTailPlaceIds(), input.nodes(), input.queries());
                runs.add(new Run(seed, weight, new OfflineFairnessExperiment().run(current)));
            }
        }
        List<Scope> scopes = new ArrayList<>();
        scopes.add(new Scope("AGGREGATE", null));
        input.queries().stream().map(OfflineExperimentInput.Query::stratum).distinct().sorted()
                .forEach(stratum -> scopes.add(new Scope("STRATUM", stratum)));
        List<Summary> summaries = new ArrayList<>();
        List<PairedDelta> differences = new ArrayList<>();
        for (double weight : frozen.fairnessWeights()) {
            List<Run> matching = runs.stream().filter(run -> run.fairnessWeight() == weight).toList();
            for (Scope scope : scopes) {
                for (var strategy : OfflineRanking.Strategy.values()) {
                    var metrics = matching.stream().map(run -> values(summary(run, strategy, scope))).toList();
                    summaries.add(new Summary(weight, scope.kind(), scope.stratum(), strategy, summarizeMetrics(metrics)));
                }
                List<Map<String, Double>> deltas = new ArrayList<>();
                for (Run run : matching) {
                    var fair = values(summary(run, OfflineRanking.Strategy.FAIR_EXPOSURE, scope));
                    var relevant = values(summary(run, OfflineRanking.Strategy.RELEVANCE, scope));
                    Map<String, Double> delta = new TreeMap<>();
                    fair.forEach((key, value) -> delta.put(key,
                            value == null || relevant.get(key) == null ? null : value - relevant.get(key)));
                    deltas.add(delta);
                }
                differences.add(new PairedDelta(weight, scope.kind(), scope.stratum(), summarizeMetrics(deltas)));
            }
        }
        return new Report(VERSION, "SIMULATED_SELECTIONS_EQUAL_POSITION_WEIGHT", input.datasetKind(), frozen,
                List.copyOf(runs), List.copyOf(summaries), List.copyOf(differences));
    }

    private static Plan validateAndFreeze(OfflineExperimentInput input, Plan plan) {
        require(plan != null && plan.planId() != null && !plan.planId().isBlank(), "必须声明实验计划编号");
        require(input.datasetId().equals(plan.datasetId()), "实验计划与数据集不匹配");
        require(plan.planId().length() <= 128 && input.datasetId().length() <= 128, "计划及数据集编号最多 128 字符");
        for (var query : input.queries()) {
            require(query.id().length() <= 128 && query.stratum().length() <= 128, "反复写入报告的查询和场景名最多 128 字符");
        }
        require(plan.seeds() != null && plan.seeds().size() >= 2 && plan.seeds().size() <= 20,
                "必须预先声明 2–20 个种子");
        TreeSet<Long> seeds = new TreeSet<>();
        for (Long seed : plan.seeds()) require(seed != null && seeds.add(seed), "种子不得为空或重复");
        require(plan.fairnessWeights() != null && !plan.fairnessWeights().isEmpty()
                && plan.fairnessWeights().size() <= 6, "必须声明 1–6 个权重");
        TreeSet<Double> weights = new TreeSet<>();
        for (Double weight : plan.fairnessWeights()) {
            require(weight != null && Double.isFinite(weight) && weight >= 0 && weight <= 1, "权重必须在 [0,1]");
            require(weights.add(weight == 0 ? 0.0 : weight), "权重不得重复（含正负零）");
        }
        require(weights.contains(0.0), "必须包含零权重作为原相关性对照");
        long combinations = (long) seeds.size() * weights.size();
        require(combinations <= 60, "一次最多 60 个组合");
        // 空候选也会产生决策和场景摘要，不能让节点数为零绕过报告内存保护。
        require(combinations * Math.max(1, input.queries().size()) <= 10_000, "总查询轮次最多 10000");
        require(combinations * Math.max(1, input.nodes().size()) * Math.max(1, input.queries().size()) <= 250_000,
                "总节点乘查询工作量最多 250000");
        return new Plan(plan.planId(), plan.datasetId(), List.copyOf(seeds), List.copyOf(weights));
    }

    private record Scope(String kind, String stratum) { }

    private static OfflineFairnessExperiment.Summary summary(Run run, OfflineRanking.Strategy strategy, Scope scope) {
        var variant = run.report().variants().stream().filter(v -> v.strategy() == strategy).findFirst().orElseThrow();
        return scope.kind().equals("AGGREGATE") ? variant.aggregate() : variant.strata().get(scope.stratum());
    }

    private static Map<String, Double> values(OfflineFairnessExperiment.Summary summary) {
        var result = new TreeMap<String, Double>();
        result.put("gini", summary.metrics().gini());
        result.put("opportunityAdjustedGini", summary.metrics().opportunityAdjustedGini());
        result.put("longTailShare", summary.metrics().longTailShare());
        result.put("coverage", summary.metrics().coverage());
        result.put("zeroExposureShare", summary.metrics().zeroExposureShare());
        result.put("meanBaseScore", summary.meanBaseScore());
        result.put("relevanceRetention", summary.relevanceRetention());
        result.put("meanDistanceKm", summary.meanDistanceKm());
        result.put("emptyQueries", (double) summary.emptyQueries());
        return result;
    }

    private static Map<String, Stats> summarizeMetrics(List<Map<String, Double>> rows) {
        var result = new TreeMap<String, Stats>();
        for (String key : rows.get(0).keySet()) {
            result.put(key, summarize(rows.stream().map(row -> row.get(key)).toList()));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 缺失统计不是零；这里的范围只是种子敏感性，不是置信区间。 */
    static Stats summarize(List<Double> values) {
        int valid = 0;
        double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Double value : values) {
            if (value == null) continue;
            require(Double.isFinite(value), "指标不能是非有限值");
            valid++;
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new Stats(valid, values.size() - valid, valid == 0 ? null : sum / valid,
                valid == 0 ? null : min, valid == 0 ? null : max);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
