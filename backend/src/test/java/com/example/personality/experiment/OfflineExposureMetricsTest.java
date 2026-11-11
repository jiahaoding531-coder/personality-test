package com.example.personality.experiment;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OfflineExposureMetricsTest {
    @Test void equalCountsHaveZeroGini() {
        var actual = OfflineExposureMetrics.calculate(Map.of(1L, 1L, 2L, 1L), Map.of(1L, 2, 2L, 2), Set.of(2L));
        assertEquals(0, actual.gini());
        assertEquals(.5, actual.longTailShare());
        assertEquals(1, actual.coverage());
        assertEquals(2, actual.selections());
    }

    @Test void eligibleZeroExposureNodeIsIncludedInGini() {
        var actual = OfflineExposureMetrics.calculate(Map.of(2L, 2L), Map.of(1L, 2, 2L, 2, 3L, 0), Set.of(1L));
        assertEquals(2, actual.eligibleNodes(), "从未可行的节点3不进入分母");
        assertEquals(.5, actual.gini());
        assertEquals(.5, actual.zeroExposureShare());
        assertEquals(0, actual.longTailShare());
    }

    @Test void opportunityAdjustedGiniSeparatesUnequalEligibility() {
        var actual = OfflineExposureMetrics.calculate(Map.of(1L, 1L, 2L, 2L), Map.of(1L, 1, 2L, 2), Set.of());
        assertNotNull(actual.gini());
        assertEquals(1.0 / 6, actual.gini(), 1e-12);
        assertEquals(0, actual.opportunityAdjustedGini());
    }

    @Test void zeroSelectionsAreUndefinedRatherThanPerfectFairness() {
        var actual = OfflineExposureMetrics.calculate(Map.of(), Map.of(1L, 2), Set.of());
        assertNull(actual.gini());
        assertNull(actual.longTailShare());
        assertEquals(0, actual.coverage());
        assertEquals(1, actual.zeroExposureShare());
    }

    @Test void noEligibleNodesHaveNoCoverageDenominator() {
        var actual = OfflineExposureMetrics.calculate(Map.of(), Map.of(), Set.of());
        assertNull(actual.gini());
        assertNull(actual.coverage());
        assertNull(actual.zeroExposureShare());
    }

    @Test void impossibleCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> OfflineExposureMetrics.calculate(Map.of(1L, 2L), Map.of(1L, 1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> OfflineExposureMetrics.calculate(Map.of(1L, 1L), Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> OfflineExposureMetrics.calculate(Map.of(1L, -1L), Map.of(1L, 2), Set.of()));
    }
}
