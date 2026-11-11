package com.example.personality.experiment;

import com.example.personality.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.personality.experiment.OfflineRanking.Strategy.*;

class OfflineRankingTest {
    static ScoredPlace scored(long id, double score, double distance) {
        var place = new PlaceCandidate(id, "合成测试节点" + id, "CULTURE", 0, 0,
                new PlaceTraits(50, 50, 50, 50, 50, 50, 50), 50, 100, 0, 30,
                null, null, "仅测试，不是真实地点");
        return new ScoredPlace(place, score, score, distance, 1, 1, 1, 1, List.of());
    }

    static List<Long> ids(List<ScoredPlace> places) {
        return places.stream().map(p -> p.place().id()).toList();
    }

    @Test void distanceUsesDistanceAndStableIdTieBreak() {
        var input = List.of(scored(4, .9, 4), scored(3, .8, 1), scored(1, .1, 1), scored(2, .7, 2));
        assertEquals(List.of(1L, 3L, 2L), ids(OfflineRanking.rank(input, DISTANCE, Map.of(), .3)));
    }

    @Test void relevanceKeepsThreeBestOriginalScores() {
        var input = List.of(scored(4, .7, 1), scored(3, .8, 1), scored(2, .9, 1), scored(1, .9, 1));
        assertEquals(List.of(1L, 2L, 3L), ids(OfflineRanking.rank(input, RELEVANCE, Map.of(), .3)));
    }

    @Test void underexposedFourthCandidateCanEnterWithoutChangingItsScore() {
        var fourth = scored(4, .7, 1);
        var input = List.of(scored(1, .9, 1), scored(2, .85, 1), scored(3, .8, 1), fourth);
        var actual = OfflineRanking.rank(input, FAIR_EXPOSURE, Map.of(1L, 10L, 2L, 10L, 3L, 10L), .5);
        assertEquals(List.of(4L, 1L, 2L), ids(actual));
        assertSame(fourth, actual.get(0), "保留原始评分和五因子对象");
        assertEquals(.7, actual.get(0).score());
    }

    @Test void zeroFairnessWeightRestoresRelevanceOrder() {
        var input = List.of(scored(4, .7, 1), scored(2, .9, 2), scored(1, .9, 3), scored(3, .8, 1));
        assertEquals(List.of(1L, 2L, 3L), ids(OfflineRanking.rank(input, FAIR_EXPOSURE, Map.of(1L, 999L), 0)));
    }

    @Test void fewerThanThreeCandidatesAreNotInventedOrDuplicated() {
        assertEquals(List.of(1L), ids(OfflineRanking.rank(List.of(scored(1, .5, 1)), RELEVANCE, Map.of(), .2)));
        assertEquals(List.of(), OfflineRanking.rank(List.of(), FAIR_EXPOSURE, Map.of(), .2));
    }

    @Test void rankingDoesNotMutateCallerListsOrCounts() {
        var input = new ArrayList<>(List.of(scored(2, .6, 1), scored(1, .8, 2)));
        var before = List.copyOf(input);
        var counts = new HashMap<>(Map.of(1L, 2L));
        OfflineRanking.rank(input, FAIR_EXPOSURE, counts, .5);
        assertEquals(before, input);
        assertEquals(Map.of(1L, 2L), counts);
    }

    @Test void duplicateCandidateIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> OfflineRanking.rank(
                List.of(scored(1, .5, 1), scored(1, .6, 2)), RELEVANCE, Map.of(), .5));
    }

    @Test void invalidWeightsScoresAndCountsAreRejected() {
        for (double value : new double[]{-.1, 1.1, Double.NaN}) {
            assertThrows(IllegalArgumentException.class, () -> OfflineRanking.rank(List.of(), FAIR_EXPOSURE, Map.of(), value));
        }
        assertThrows(IllegalArgumentException.class, () -> OfflineRanking.rank(List.of(scored(1, Double.NaN, 1)), RELEVANCE, Map.of(), .5));
        assertThrows(IllegalArgumentException.class, () -> OfflineRanking.rank(List.of(scored(1, .5, 1)), FAIR_EXPOSURE, Map.of(1L, -1L), .5));
    }
}
