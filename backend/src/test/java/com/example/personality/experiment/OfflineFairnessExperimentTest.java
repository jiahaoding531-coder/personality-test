package com.example.personality.experiment;

import com.example.personality.domain.*;
import com.example.personality.service.RecommendationEngine;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.personality.experiment.OfflineRanking.Strategy.*;

class OfflineFairnessExperimentTest {
    final OfflineFairnessExperiment runner = new OfflineFairnessExperiment();

    static OfflineExperimentInput.Node node(long id, int value, double latitude, int price, int minutes, String from, String to) {
        return new OfflineExperimentInput.Node(id, "合成节点" + id, "CULTURE", "SYNTHETIC", latitude, 0,
                new PlaceTraits(value, value, value, value, value, value, value), 50, 100, price, minutes, from, to);
    }
    static Map<TravelDimension, Integer> preference() {
        var result = new EnumMap<TravelDimension, Integer>(TravelDimension.class);
        for (var dimension : TravelDimension.values()) result.put(dimension, 50);
        return result;
    }
    static OfflineExperimentInput.Query query(String id, String stratum) {
        return new OfflineExperimentInput.Query(id, stratum, "SYNTHETIC", "10:00", 0, 0, 10, 120,
                20, preference(), Set.of(), Map.of());
    }
    static OfflineExperimentInput sample(long seed, double weight) {
        var nodes = List.of(node(1, 90, 0, 0, 30, null, null), node(2, 85, 0, 0, 30, null, null),
                node(3, 80, 0, 0, 30, null, null), node(4, 70, 0, 0, 30, null, null),
                node(5, 60, 0, 0, 30, null, null), node(6, 50, 0, 0, 30, null, null));
        var queries = new ArrayList<OfflineExperimentInput.Query>();
        for (int i = 1; i <= 12; i++) queries.add(query("q" + i, "合成场景"));
        return input(seed, weight, nodes, queries);
    }
    static OfflineExperimentInput input(long seed, double weight, List<OfflineExperimentInput.Node> nodes, List<OfflineExperimentInput.Query> queries) {
        return new OfflineExperimentInput("合成验证集", "SYNTHETIC_TEST_ONLY", "自行构造，仅验证程序，不是实测数据",
                "SYNTHETIC", "测试前指定的节点，不代表真实热门或冷门", seed, weight,
                nodes.stream().filter(n -> n.id() >= 4).map(OfflineExperimentInput.Node::id).toList(), nodes, queries);
    }
    static OfflineFairnessExperiment.Variant variant(OfflineFairnessExperiment.Report report, OfflineRanking.Strategy strategy) {
        assertEquals(3, report.variants().size(), "必须实际执行三组");
        return report.variants().stream().filter(v -> v.strategy() == strategy).findFirst().orElseThrow();
    }

    @Test void fullCandidatePoolAllowsFourthNodeToEnterLaterFairBatches() {
        var report = runner.run(sample(20261026, .6));
        var fair = variant(report, FAIR_EXPOSURE);
        assertTrue(fair.decisions().stream().allMatch(d -> d.eligible().size() == 6));
        assertTrue(fair.aggregate().selections().getOrDefault(4L, 0L) > 0);
        assertEquals(0L, variant(report, RELEVANCE).aggregate().selections().getOrDefault(4L, 0L));
        assertEquals(36, fair.aggregate().metrics().selections());
    }

    @Test void allGroupsShareHardFiltersAndDoNotFillMissingSlots() {
        var nodes = List.of(node(1, 50, 0, 0, 30, null, null), node(2, 100, 1, 0, 30, null, null),
                node(3, 100, 0, 21, 30, null, null), node(4, 100, 0, 0, 121, null, null),
                node(5, 100, 0, 0, 30, "11:00", "18:00"));
        var report = runner.run(input(1, .5, nodes, List.of(query("q", "one"))));
        assertEquals(3, report.variants().size());
        for (var variant : report.variants()) {
            assertEquals(List.of(1L), variant.decisions().get(0).selected());
            assertEquals(List.of(1L), variant.decisions().get(0).eligible().stream().map(OfflineFairnessExperiment.Candidate::id).toList());
            assertEquals(Map.of(1L, 1), variant.aggregate().opportunities());
        }
    }

    @Test void relevanceBaselineMatchesActualEngineTopThreeAndRetainsFactors() {
        var input = sample(1, .5);
        var places = input.nodes().stream().map(n -> new PlaceCandidate(n.id(), n.name(), n.category(), n.latitude(), n.longitude(),
                n.traits(), n.indoor(), n.quality(), n.ticketPrice(), n.suggestedMinutes(), null, null, "")).toList();
        var context = RecommendationContext.withLocation(LocalTime.of(10, 0), 120, 0.0, 0.0, 10, 20, Set.of());
        var expected = new RecommendationEngine().recommend(preference(), places, context, 3);
        var actual = variant(runner.run(input), RELEVANCE);
        assertEquals(expected.stream().map(p -> p.place().id()).toList(), actual.decisions().get(0).selected());
        assertEquals(expected.get(0).score(), actual.decisions().get(0).eligible().get(0).baseScore());
        assertEquals(1.0, actual.aggregate().relevanceRetention());
    }

    @Test void repeatedRunsAndVariantExecutionOrderAreIsolated() {
        var input = sample(9, .6);
        var first = runner.run(input);
        assertEquals(3, first.variants().size());
        assertEquals(first, runner.run(input));
        assertEquals(first, runner.run(input, List.of(FAIR_EXPOSURE, DISTANCE, RELEVANCE)));
        assertEquals(0L, variant(first, RELEVANCE).aggregate().selections().getOrDefault(6L, 0L));
    }

    @Test void queryOrderUsesSeedAndCandidateInputOrderDoesNotChangeTies() {
        var input = sample(123, .5);
        var first = runner.run(input);
        assertEquals(12, first.queryOrder().size());
        assertNotEquals(first.queryOrder(), runner.run(sample(456, .5)).queryOrder());
        var reversed = new ArrayList<>(input.nodes());
        Collections.reverse(reversed);
        assertEquals(first, runner.run(input(123, .5, reversed, input.queries())));
    }

    @Test void differentStrataDoNotBorrowEachOthersExposureCounts() {
        var sample = sample(9, .6);
        var report = runner.run(input(9, .6, sample.nodes(), List.of(query("a", "A"), query("b", "B"))));
        var fair = variant(report, FAIR_EXPOSURE);
        assertEquals(List.of(1L, 2L, 3L), fair.decisions().get(0).selected());
        assertEquals(List.of(1L, 2L, 3L), fair.decisions().get(1).selected());
        assertEquals(3, fair.strata().get("A").metrics().selections());
        assertEquals(3, fair.strata().get("B").metrics().selections());
    }

    @Test void zeroFairnessWeightProducesSameChoicesAsRelevance() {
        var report = runner.run(sample(3, 0));
        assertEquals(variant(report, RELEVANCE).decisions(), variant(report, FAIR_EXPOSURE).decisions());
    }

    @Test void emptyAndZeroScoreCasesKeepUndefinedMetricsNull() {
        var empty = runner.run(input(1, .5, List.of(), List.of(query("q", "empty"))));
        assertEquals(3, empty.variants().size());
        for (var v : empty.variants()) {
            assertEquals(1, v.aggregate().emptyQueries());
            assertNull(v.aggregate().metrics().gini());
            assertNull(v.aggregate().relevanceRetention());
        }
        var zeros = runner.run(input(1, .5, List.of(node(1, 0, 0, 0, 30, null, null)), List.of(query("q", "zero"))));
        assertNull(variant(zeros, RELEVANCE).aggregate().relevanceRetention());
        assertEquals(0.0, variant(zeros, RELEVANCE).aggregate().meanBaseScore());
    }

    @Test void duplicateIdsAndMixedCoordinateSystemsAreRejected() {
        var n = node(1, 50, 0, 0, 30, null, null);
        assertThrows(IllegalArgumentException.class, () -> runner.run(input(1, .5, List.of(n, n), List.of(query("q", "s")))));
        assertThrows(IllegalArgumentException.class, () -> runner.run(input(1, .5, List.of(n), List.of(query("q", "s"), query("q", "s")))));
        var wrong = new OfflineExperimentInput.Node(2, "混合坐标", "CULTURE", "GCJ02", 0, 0, n.traits(), 50, 50, 0, 30, null, null);
        assertThrows(IllegalArgumentException.class, () -> runner.run(input(1, .5, List.of(wrong), List.of(query("q", "s")))));
    }

    @Test void invalidCoordinatesTraitsAndIncompleteProfilesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> runner.run(input(1, .5,
                List.of(node(1, 50, Double.NaN, 0, 30, null, null)), List.of(query("q", "s")))));
        assertThrows(IllegalArgumentException.class, () -> runner.run(input(1, .5,
                List.of(node(1, 101, 0, 0, 30, null, null)), List.of(query("q", "s")))));
        var bad = new OfflineExperimentInput.Query("q", "s", "SYNTHETIC", "10:00", 0, 0, 10, 120, null, Map.of(), Set.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> runner.run(input(1, .5, List.of(), List.of(bad))));
    }

    @Test void inputCollectionsRemainUnchanged() {
        var sample = sample(8, .5);
        var before = new ArrayList<>(sample.queries());
        runner.run(sample);
        assertEquals(before, sample.queries());
        assertEquals(preference(), sample.queries().get(0).preference());
    }

    @Test void oversizedCandidateQueryProductIsRejectedBeforeAllocatingTraceReports() {
        var nodes = new ArrayList<OfflineExperimentInput.Node>();
        var queries = new ArrayList<OfflineExperimentInput.Query>();
        for (int i = 1; i <= 201; i++) nodes.add(node(i, 50, 0, 0, 30, null, null));
        for (int i = 1; i <= 251; i++) queries.add(query("q" + i, "s"));
        assertThrows(IllegalArgumentException.class, () -> input(1, .5, nodes, queries).validate());
    }
}
