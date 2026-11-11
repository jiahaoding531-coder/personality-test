package com.example.personality.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OfflineRobustnessMainTest {
    @TempDir Path temp;
    final JsonMapper json = JsonMapper.builder().build();
    final Path root = Path.of("..").toAbsolutePath().normalize();

    Path input() throws Exception {
        var path = temp.resolve("input.json");
        Files.writeString(path, json.writeValueAsString(OfflineFairnessExperimentTest.sample(99, .9)));
        return path;
    }
    Path plan() throws Exception {
        var path = temp.resolve("plan.json");
        var sample = OfflineFairnessExperimentTest.sample(99, .9);
        Files.writeString(path, json.writeValueAsString(OfflineRobustnessExperimentTest.plan(sample)));
        return path;
    }

    @Test void preservesInputsAndWritesCompleteTraceableArtifacts() throws Exception {
        Path input = input(), plan = plan(), output = temp.resolve("run");
        byte[] inputBefore = Files.readAllBytes(input), planBefore = Files.readAllBytes(plan);
        OfflineRobustnessMain.execute(input, plan, output, root);
        assertArrayEquals(inputBefore, Files.readAllBytes(input));
        assertArrayEquals(planBefore, Files.readAllBytes(plan));
        assertArrayEquals(inputBefore, Files.readAllBytes(output.resolve("input.json")));
        assertArrayEquals(planBefore, Files.readAllBytes(output.resolve("plan.json")));
        var report = json.readTree(Files.readString(output.resolve("report.json")));
        assertEquals(4, report.path("runs").size());
        assertEquals("SYNTHETIC_TEST_ONLY", report.path("datasetKind").asText());
        var manifest = json.readTree(Files.readString(output.resolve("manifest.json")));
        for (String name : List.of("input", "plan", "report", "summary")) {
            String suffix = name.equals("summary") ? ".md" : ".json";
            assertEquals(hash(Files.readAllBytes(output.resolve(name + suffix))), manifest.path(name + "Sha256").asText());
        }
        assertFalse(manifest.path("dataVerifiedByTool").asBoolean());
        assertTrue(manifest.path("sourceSha256").has("backend/src/main/java/com/example/personality/service/RecommendationEngine.java"));
        assertFalse(manifest.path("runtimeClassSha256").isEmpty());
        String summary = Files.readString(output.resolve("summary.md"));
        assertTrue(summary.contains("模拟入选"));
        assertTrue(summary.contains("不是置信区间"));
        assertTrue(summary.contains("公平组 − 相关性组"));
        assertTrue(summary.contains("0.600000"));
    }

    @Test void repeatedRunsAreByteIdentical() throws Exception {
        Path input = input(), plan = plan(), a = temp.resolve("a"), b = temp.resolve("b");
        OfflineRobustnessMain.execute(input, plan, a, root);
        OfflineRobustnessMain.execute(input, plan, b, root);
        for (String name : List.of("input.json", "plan.json", "report.json", "summary.md", "manifest.json")) {
            assertArrayEquals(Files.readAllBytes(a.resolve(name)), Files.readAllBytes(b.resolve(name)), name);
        }
    }

    @Test void existingDirectoryIsNeverOverwritten() throws Exception {
        Path input = input(), plan = plan(), output = Files.createDirectory(temp.resolve("old"));
        Files.writeString(output.resolve("keep.txt"), "已有结果");
        assertThrows(FileAlreadyExistsException.class, () -> OfflineRobustnessMain.execute(input, plan, output, root));
        assertEquals("已有结果", Files.readString(output.resolve("keep.txt")));
        try (var files = Files.list(output)) { assertEquals(1, files.count()); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unknown", "duplicate-key", "fractional-seed", "numeric-string", "null-seed", "trailing", "oversized-plan", "bad-input", "bad-root"})
    void invalidFilesDoNotCreateAnyReport(String kind) throws Exception {
        Path input = input(), plan = plan(), output = temp.resolve("bad");
        var tree = (ObjectNode) json.readTree(Files.readString(plan));
        switch (kind) {
            case "missing" -> { tree.remove("seeds"); Files.writeString(plan, json.writeValueAsString(tree)); }
            case "unknown" -> { tree.put("unknown", 1); Files.writeString(plan, json.writeValueAsString(tree)); }
            case "duplicate-key" -> Files.writeString(plan, "{\"planId\":\"other\"," + Files.readString(plan).substring(1));
            case "fractional-seed" -> { tree.putArray("seeds").add(1.5).add(2); Files.writeString(plan, json.writeValueAsString(tree)); }
            case "numeric-string" -> { tree.putArray("seeds").add("1").add("2"); Files.writeString(plan, json.writeValueAsString(tree)); }
            case "null-seed" -> { tree.putArray("seeds").addNull().add(2); Files.writeString(plan, json.writeValueAsString(tree)); }
            case "trailing" -> Files.writeString(plan, Files.readString(plan) + " {} ");
            case "oversized-plan" -> Files.writeString(plan, " ".repeat(64_001));
            case "bad-input" -> Files.writeString(input, "{}");
            case "bad-root" -> { }
            default -> throw new AssertionError(kind);
        }
        assertThrows(Exception.class, () -> OfflineRobustnessMain.execute(input, plan, output, kind.equals("bad-root") ? temp : root));
        assertFalse(Files.exists(output));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
