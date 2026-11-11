package com.example.personality.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.personality.experiment.OfflineRanking.Strategy.*;
import static com.example.personality.experiment.OfflineFairnessExperimentTest.*;

class OfflineRobustnessExperimentTest {
    final OfflineRobustnessExperiment runner = new OfflineRobustnessExperiment();

    static OfflineRobustnessExperiment.Plan plan(OfflineExperimentInput input) {
        return new OfflineRobustnessExperiment.Plan("合成敏感性验证", input.datasetId(), List.of(9L, 2L), List.of(.6, 0.0));
    }

    @Test void executesEveryDeclaredCombinationAndZeroWeightKeepsRelevance() {
        var input = sample(888, .9);
        var report = runner.run(input, plan(input));
        assertEquals(4, report.runs().size());
        assertEquals(List.of(2L, 2L, 9L, 9L), report.runs().stream().map(OfflineRobustnessExperiment.Run::seed).toList());
        assertEquals(List.of(0.0, .6, 0.0, .6), report.runs().stream().map(OfflineRobustnessExperiment.Run::fairnessWeight).toList());
        for (var run : report.runs()) {
            assertEquals(run.seed(), run.report().seed());
            assertEquals(run.fairnessWeight(), run.report().fairnessWeight());
            if (run.fairnessWeight() == 0) {
                assertEquals(variant(run.report(), RELEVANCE).decisions(), variant(run.report(), FAIR_EXPOSURE).decisions());
            }
        }
        assertNotEquals(report.runs().get(0).report().queryOrder(), report.runs().get(2).report().queryOrder());
    }

    @Test void independentRunsMatchSingleRunnerAndDoNotChangeInputOrPlan() {
        var input = sample(888, .9);
        var plan = plan(input);
        var report = runner.run(input, plan);
        assertEquals(report, runner.run(input, plan));
        for (var run : report.runs()) {
            assertEquals(new OfflineFairnessExperiment().run(sample(run.seed(), run.fairnessWeight())), run.report());
        }
        assertEquals(888L, input.seed());
        assertEquals(.9, input.fairnessWeight());
        assertEquals(List.of(9L, 2L), plan.seeds());
        assertEquals(List.of(.6, 0.0), plan.fairnessWeights());
    }

    @Test void stratifiedSummariesAndPairedDeltasKeepTheirOwnScope() {
        var base = sample(1, .5);
        var input = input(1, .5, base.nodes(), List.of(query("a", "A"), query("b", "B")));
        var report = runner.run(input, plan(input));
        assertEquals(18, report.summaries().size()); // 2 权重 × 3 策略 ×（总体 + A + B）
        assertEquals(6, report.pairedFairMinusRelevance().size());
        assertEquals(Set.of("A", "B"), new HashSet<>(report.summaries().stream()
                .filter(s -> s.scope().equals("STRATUM")).map(OfflineRobustnessExperiment.Summary::stratum).toList()));
        for (var s : report.summaries()) {
            assertEquals(2, s.metrics().get("gini").validCount());
            assertEquals(.5, s.metrics().get("gini").mean(), 1e-12);
        }
        for (var delta : report.pairedFairMinusRelevance()) {
            assertEquals(0.0, delta.metrics().get("gini").mean()); // 每场景仅一次请求，公平计数均从零开始
        }
    }

    @Test void meansAndRangesDoNotInventZerosForMissingMetrics() {
        assertEquals(new OfflineRobustnessExperiment.Stats(2, 1, .75, .5, 1.0),
                OfflineRobustnessExperiment.summarize(Arrays.asList(.5, null, 1.0)));
        assertEquals(new OfflineRobustnessExperiment.Stats(0, 2, null, null, null),
                OfflineRobustnessExperiment.summarize(Arrays.asList(null, null)));
        assertThrows(IllegalArgumentException.class, () -> OfflineRobustnessExperiment.summarize(List.of(Double.NaN)));
    }

    @Test void emptyQueriesRemainExplicitInEveryCombination() {
        var input = input(1, .5, List.of(), List.of(query("empty", "空场景")));
        var report = runner.run(input, plan(input));
        for (var s : report.summaries()) {
            assertEquals(new OfflineRobustnessExperiment.Stats(0, 2, null, null, null), s.metrics().get("gini"));
            assertEquals(1.0, s.metrics().get("emptyQueries").mean());
        }
        for (var d : report.pairedFairMinusRelevance()) {
            assertNull(d.metrics().get("gini").mean());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate-seed", "one-seed", "null-seed", "duplicate-weight", "negative-zero", "nan", "negative", "over-one", "no-zero", "dataset", "too-many-runs"})
    void rejectsAmbiguousOrOversizedPlans(String kind) {
        var input = sample(1, .5);
        List<Long> seeds = List.of(1L, 2L);
        List<Double> weights = List.of(0.0, .5);
        String dataset = input.datasetId();
        switch (kind) {
            case "duplicate-seed" -> seeds = List.of(1L, 1L);
            case "one-seed" -> seeds = List.of(1L);
            case "null-seed" -> seeds = Arrays.asList(1L, null);
            case "duplicate-weight" -> weights = List.of(0.0, .5, .5);
            case "negative-zero" -> weights = List.of(0.0, -0.0);
            case "nan" -> weights = List.of(0.0, Double.NaN);
            case "negative" -> weights = List.of(0.0, -.1);
            case "over-one" -> weights = List.of(0.0, 1.1);
            case "no-zero" -> weights = List.of(.5);
            case "dataset" -> dataset = "另一个数据集";
            case "too-many-runs" -> { seeds = java.util.stream.LongStream.range(0, 20).boxed().toList(); weights = List.of(0.0, .1, .2, .3); }
            default -> throw new AssertionError(kind);
        }
        var plan = new OfflineRobustnessExperiment.Plan("非法计划", dataset, seeds, weights);
        assertThrows(IllegalArgumentException.class, () -> runner.run(input, plan));
    }

    @Test void totalWorkIsBoundedEvenWhenEachSingleRunIsValid() {
        var nodes = new ArrayList<OfflineExperimentInput.Node>();
        var queries = new ArrayList<OfflineExperimentInput.Query>();
        for (int i = 1; i <= 100; i++) nodes.add(node(i, 50, 0, 0, 30, null, null));
        for (int i = 1; i <= 500; i++) queries.add(query("q" + i, "s"));
        var input = input(1, .5, nodes, queries);
        input.validate();
        var plan = new OfflineRobustnessExperiment.Plan("过量", input.datasetId(), List.of(1L, 2L, 3L), List.of(0.0, .5));
        assertThrows(IllegalArgumentException.class, () -> runner.run(input, plan));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plan-id", "dataset-id", "query-id", "stratum"})
    void rejectsOversizedRepeatedLabelsBeforeBuildingReports(String field) {
        var base = sample(1, .5);
        String longText = "x".repeat(129);
        var query = query("q", "s");
        if (field.equals("query-id") || field.equals("stratum")) {
            query = new OfflineExperimentInput.Query(field.equals("query-id") ? longText : "q",
                    field.equals("stratum") ? longText : "s", query.coordinateSystem(), query.localTime(),
                    query.latitude(), query.longitude(), query.maxDistanceKm(), query.remainingMinutes(), query.maxTicketPrice(),
                    query.preference(), query.states(), query.extraBias());
        }
        var input = new OfflineExperimentInput(field.equals("dataset-id") ? longText : base.datasetId(),
                base.datasetKind(), base.provenance(), base.coordinateSystem(), base.longTailDefinition(),
                base.seed(), base.fairnessWeight(), base.longTailPlaceIds(), base.nodes(), List.of(query));
        var plan = new OfflineRobustnessExperiment.Plan(field.equals("plan-id") ? longText : "文本边界验证",
                input.datasetId(), List.of(1L, 2L), List.of(0.0));
        assertThrows(IllegalArgumentException.class, () -> runner.run(input, plan));
    }

    @Test void emptyCandidatePoolsStillCountTowardReportSizeLimit() {
        var queries = new ArrayList<OfflineExperimentInput.Query>();
        for (int i = 1; i <= 501; i++) queries.add(query("q" + i, "s" + i));
        var input = input(1, 0, List.of(), queries);
        var plan = new OfflineRobustnessExperiment.Plan("空候选报告边界", input.datasetId(),
                java.util.stream.LongStream.range(0, 20).boxed().toList(), List.of(0.0));
        assertThrows(IllegalArgumentException.class, () -> runner.run(input, plan));
    }
}
