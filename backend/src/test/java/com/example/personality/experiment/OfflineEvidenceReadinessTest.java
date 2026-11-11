package com.example.personality.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OfflineEvidenceReadinessTest {
    final OfflineEvidenceReadiness checker = new OfflineEvidenceReadiness();
    static ObjectNode input() throws Exception {
        return (ObjectNode) OfflineExperimentMain.JSON.readTree(Files.readString(Path.of("../docs/experiments/data/yexian-pilot-evidence-v1.json")));
    }
    static ObjectNode first(ObjectNode root) { return (ObjectNode) root.path("nodes").get(0); }
    static Set<String> codes(OfflineEvidenceReadiness.Report report) {
        Set<String> codes = new HashSet<>();
        report.issues().forEach(i -> codes.add(i.code()));
        report.nodes().forEach(n -> n.issues().forEach(i -> codes.add(i.code())));
        return codes;
    }

    @Test void existingRegisterIsStructurallyValidButAllThreeNodesNeedEvidence() throws Exception {
        var input = input();
        String before = input.toString();
        var result = checker.check(input);
        assertTrue(result.structurallyValid());
        assertEquals("NOT_READY_FOR_EXPERIMENT", result.status());
        assertEquals(3, result.nodes().size());
        for (var node : result.nodes()) {
            assertFalse(node.declaredEligible());
            assertTrue(node.issues().stream().anyMatch(i -> i.code().equals("COORDINATE_MISSING")));
            assertTrue(node.issues().stream().anyMatch(i -> i.code().equals("ANNOTATION_MISSING")));
        }
        assertTrue(codes(result).contains("TOP3_COMPETITION_INSUFFICIENT"));
        assertTrue(codes(result).contains("ENTITY_RELATION_UNRESOLVED"));
        assertFalse(result.dataVerifiedByTool());
        assertFalse(result.experimentAuthorized());
        assertEquals(before, input.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "artifact", "duplicate-source", "duplicate-node", "date", "boolean", "missing-coordinate", "long-name", "null-nodes"})
    void invalidCoreStructureIsReported(String kind) throws Exception {
        var input = input();
        switch (kind) {
            case "version" -> input.put("schemaVersion", 2);
            case "artifact" -> input.put("artifactType", "OTHER");
            case "duplicate-source" -> ((ObjectNode) input.path("sources").get(1)).put("id", "G01");
            case "duplicate-node" -> ((ObjectNode) input.path("nodes").get(1)).put("id", "YEX-01");
            case "date" -> input.put("reviewedOn", "2026-02-30");
            case "boolean" -> first(input).put("experimentEligible", "true");
            case "missing-coordinate" -> first(input).remove("coordinate");
            case "long-name" -> first(input).put("name", "x".repeat(257));
            case "null-nodes" -> input.putNull("nodes");
            default -> throw new AssertionError(kind);
        }
        var result = checker.check(input);
        assertFalse(result.structurallyValid());
        assertEquals("INVALID_REGISTER", result.status());
        assertFalse(result.experimentAuthorized());
    }

    @Test void oversizedVersionCannotWrapAroundToSupportedVersion() throws Exception {
        var input = input();
        input.put("schemaVersion", 4_294_967_297L);
        var result = checker.check(input);
        assertFalse(result.structurallyValid());
        assertTrue(codes(result).contains("UNSUPPORTED_SCHEMA"));
    }

    @Test void eachSourceRequiresItsOwnCheckDate() throws Exception {
        var input = input();
        ((ObjectNode) input.path("sources").get(0)).remove("checkedOn");
        var result = checker.check(input);
        assertFalse(result.structurallyValid());
        assertTrue(result.issues().stream().anyMatch(i -> i.path().equals("$.sources[0].checkedOn")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "number", "empty", "missing-status", "unknown-status", "missing-sources", "one-name", "duplicate-names", "missing-decision"})
    void malformedEntityRelationCannotSilentlyLoseItsWarning(String kind) throws Exception {
        var input = input();
        var relation = (ObjectNode) input.path("entityRelations").get(0);
        switch (kind) {
            case "null" -> input.putArray("entityRelations").addNull();
            case "number" -> input.putArray("entityRelations").add(123);
            case "empty" -> input.putArray("entityRelations").addObject();
            case "missing-status" -> relation.remove("status");
            case "unknown-status" -> relation.put("status", "RESOLVD");
            case "missing-sources" -> relation.remove("sourceIds");
            case "one-name" -> relation.putArray("names").add("叶公古街");
            case "duplicate-names" -> relation.putArray("names").add("叶公古街").add("叶公古街");
            case "missing-decision" -> relation.remove("decision");
            default -> throw new AssertionError(kind);
        }
        var result = checker.check(input);
        assertFalse(result.structurallyValid());
        assertTrue(result.issues().stream().anyMatch(i -> i.severity().equals("ERROR") && i.path().startsWith("$.entityRelations[0]")));
    }

    @Test void declaredResolvedRelationshipStillRequiresHumanReview() throws Exception {
        var input = input();
        ((ObjectNode) input.path("entityRelations").get(0)).put("status", "RESOLVED");
        var result = checker.check(input);
        assertTrue(result.structurallyValid());
        assertTrue(codes(result).contains("ENTITY_RELATION_REVIEW_REQUIRED"));
        assertFalse(result.dataVerifiedByTool());
        assertFalse(result.experimentAuthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lead", "price", "operating", "entity"})
    void danglingReferencesAreFoundAtEveryDepth(String kind) throws Exception {
        var input = input();
        String expectedPath;
        switch (kind) {
            case "lead" -> { ((ObjectNode) first(input).path("coordinateLeads").get(0)).put("sourceId", "MISSING"); expectedPath = "$.nodes[0].coordinateLeads[0].sourceId"; }
            case "price" -> { ((ObjectNode) first(input).path("priceEvidence").get(0)).put("sourceId", "MISSING"); expectedPath = "$.nodes[0].priceEvidence[0].sourceId"; }
            case "operating" -> { ((ObjectNode) first(input).path("operatingEvidence")).put("sourceId", "MISSING"); expectedPath = "$.nodes[0].operatingEvidence.sourceId"; }
            case "entity" -> { ((ObjectNode) input.path("entityRelations").get(0)).putArray("sourceIds").add("MISSING"); expectedPath = "$.entityRelations[0].sourceIds[0]"; }
            default -> throw new AssertionError(kind);
        }
        var result = checker.check(input);
        assertFalse(result.structurallyValid());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("SOURCE_REFERENCE_UNKNOWN") && i.path().equals(expectedPath)));
    }

    @Test void crossRegisterNodeReferenceIsNotConfusedWithSourceId() throws Exception {
        var result = checker.check(input());
        assertTrue(result.structurallyValid());
        assertFalse(codes(result).contains("SOURCE_REFERENCE_UNKNOWN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"partial", "range", "string", "accuracy", "verified-unknown", "adopt-camera", "photo-as-entrance"})
    void invalidOrPromotedCoordinateEvidenceIsRejected(String kind) throws Exception {
        var input = input();
        var coordinate = (ObjectNode) first(input).path("coordinate");
        switch (kind) {
            case "partial" -> coordinate.put("latitude", 0);
            case "range" -> { coordinate.put("latitude", 91); coordinate.put("longitude", 0); }
            case "string" -> { coordinate.put("latitude", "0"); coordinate.put("longitude", 0); }
            case "accuracy" -> coordinate.put("accuracyMeters", -1);
            case "verified-unknown" -> coordinate.put("verified", true);
            case "adopt-camera" -> ((ObjectNode) first(input).path("coordinateLeads").get(0)).put("adopted", true);
            case "photo-as-entrance" -> {
                coordinate.put("latitude", 0).put("longitude", 0).put("coordinateSystem", "WGS84")
                        .put("pointRole", "PUBLIC_ENTRANCE").put("sourceId", "P01").put("verified", true);
            }
            default -> throw new AssertionError(kind);
        }
        assertFalse(checker.check(input).structurallyValid());
    }

    @Test void zeroCoordinatesAreValuesAndDoNotBecomeMissing() throws Exception {
        var input = input();
        ((ObjectNode) first(input).path("coordinate")).put("latitude", 0).put("longitude", 0)
                .put("coordinateSystem", "WGS84").put("pointRole", "PUBLIC_ENTRANCE").put("sourceId", "G01");
        var result = checker.check(input);
        assertTrue(result.structurallyValid());
        assertFalse(result.nodes().get(0).issues().stream().anyMatch(i -> i.code().equals("COORDINATE_MISSING")));
        assertFalse(result.experimentAuthorized());
    }

    @Test void eligibleFlagCannotEraseGapsEvenIfManualBlockerListIsEmptied() throws Exception {
        var input = input();
        first(input).put("experimentEligible", true).putArray("blockers");
        var result = checker.check(input);
        assertFalse(result.structurallyValid());
        assertTrue(codes(result).contains("ELIGIBILITY_CONTRADICTION"));
    }

    @Test void historicalPricesAndNonNullAnnotationsDoNotCertifyCurrentFacts() throws Exception {
        var input = input();
        ((ObjectNode) first(input).path("currentOperationalValues")).put("ticketPrice", 32).put("openingHours", "08:00–18:00");
        first(input).putObject("annotation").put("culture", 100);
        first(input).putObject("popularityEvidence").put("claim", "小众");
        var result = checker.check(input);
        assertTrue(codes(result).contains("CURRENT_OPERATIONS_REVIEW_REQUIRED"));
        assertTrue(codes(result).contains("ANNOTATION_REVIEW_REQUIRED"));
        assertTrue(codes(result).contains("POPULARITY_REVIEW_REQUIRED"));
        assertFalse(result.dataVerifiedByTool());
        assertFalse(result.experimentAuthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"negative", "string", "object", "boolean", "infinite", "missing"})
    void historicalPricesMustHaveExplicitValidNumericSemantics(String kind) throws Exception {
        for (String field : List.of("fullCny", "halfCny")) {
            var input = input();
            var price = (ObjectNode) first(input).path("priceEvidence").get(0);
            switch (kind) {
                case "negative" -> price.put(field, -50);
                case "string" -> price.put(field, "50");
                case "object" -> price.putObject(field);
                case "boolean" -> price.put(field, true);
                case "infinite" -> price.put(field, Double.POSITIVE_INFINITY);
                case "missing" -> price.remove(field);
                default -> throw new AssertionError(kind);
            }
            var result = checker.check(input);
            assertFalse(result.structurallyValid(), kind + ":" + field);
            assertTrue(result.nodes().get(0).issues().stream().anyMatch(i -> i.severity().equals("ERROR") && i.path().endsWith("." + field)));
        }
    }

    @Test void unknownHistoricalPriceIsAGapAndDoesNotBecomeZero() throws Exception {
        var input = input();
        ((ObjectNode) first(input).path("priceEvidence").get(0)).putNull("fullCny");
        var result = checker.check(input);
        assertTrue(result.structurallyValid());
        assertTrue(codes(result).contains("HISTORICAL_PRICE_UNKNOWN"));
        assertTrue(first(input).path("priceEvidence").get(0).path("fullCny").isNull());
    }

    @Test void historicalZeroPriceIsValidButDoesNotSetCurrentPrice() throws Exception {
        var input = input();
        ((ObjectNode) first(input).path("priceEvidence").get(0)).put("fullCny", 0).put("halfCny", 0);
        var result = checker.check(input);
        assertTrue(result.structurallyValid());
        assertTrue(first(input).path("currentOperationalValues").path("ticketPrice").isNull());
        assertFalse(result.experimentAuthorized());
    }
}
