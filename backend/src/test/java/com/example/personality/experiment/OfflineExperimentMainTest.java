package com.example.personality.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.HexFormat;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

class OfflineExperimentMainTest {
    @TempDir Path temp;
    final JsonMapper json = JsonMapper.builder().build();
    final Path root = Path.of("..").toAbsolutePath().normalize();

    Path input() throws Exception {
        Path path = temp.resolve("input.json");
        Files.writeString(path, json.writeValueAsString(OfflineFairnessExperimentTest.sample(20261026, .5)));
        return path;
    }

    @Test void writesTraceableReportsAndAnExactInputCopy() throws Exception {
        Path input = input(), output = temp.resolve("run");
        OfflineExperimentMain.execute(input, output, root);
        assertTrue(Files.exists(output.resolve("report.json")), "必须实际产出报告");
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output.resolve("input.json")));
        var report = json.readTree(Files.readString(output.resolve("report.json")));
        assertEquals("SIMULATED_SELECTIONS_EQUAL_POSITION_WEIGHT", report.path("measurement").asText());
        assertEquals(3, report.path("variants").size());
        var manifest = json.readTree(Files.readString(output.resolve("manifest.json")));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
        assertEquals(hash, manifest.path("inputSha256").asText());
        assertTrue(manifest.path("sourceSha256").size() >= 10);
        assertFalse(manifest.path("runtimeClassSha256").isEmpty(), "必须追溯实际执行的字节码，不只记录磁盘源码");
        assertEquals("SYNTHETIC_TEST_ONLY", manifest.path("datasetKind").asText());
    }

    @Test void repeatedExecutionProducesIdenticalReportBytes() throws Exception {
        Path input = input(), a = temp.resolve("a"), b = temp.resolve("b");
        OfflineExperimentMain.execute(input, a, root);
        OfflineExperimentMain.execute(input, b, root);
        assertTrue(Files.exists(a.resolve("report.json")));
        assertArrayEquals(Files.readAllBytes(a.resolve("report.json")), Files.readAllBytes(b.resolve("report.json")));
        assertArrayEquals(Files.readAllBytes(a.resolve("manifest.json")), Files.readAllBytes(b.resolve("manifest.json")));
    }

    @Test void existingOutputIsNeverOverwritten() throws Exception {
        Path input = input(), output = Files.createDirectory(temp.resolve("existing"));
        Files.writeString(output.resolve("keep.txt"), "保留已有结果");
        assertThrows(FileAlreadyExistsException.class, () -> OfflineExperimentMain.execute(input, output, root));
        assertEquals("保留已有结果", Files.readString(output.resolve("keep.txt")));
        try (var files = Files.list(output)) { assertEquals(1, files.count()); }
    }

    @Test void malformedJsonDoesNotCreateAnOutputDirectory() throws Exception {
        Path input = temp.resolve("bad.json"), output = temp.resolve("bad-output");
        Files.writeString(input, "{not-json}");
        assertThrows(Exception.class, () -> OfflineExperimentMain.execute(input, output, root));
        assertFalse(Files.exists(output));
    }

    @Test void missingNumericFieldIsNotSilentlyInventedAsZero() throws Exception {
        Path input = input(), output = temp.resolve("missing");
        var tree = (ObjectNode) json.readTree(Files.readString(input));
        ((ObjectNode) tree.path("nodes").get(0)).remove("indoor");
        Files.writeString(input, json.writeValueAsString(tree));
        assertThrows(Exception.class, () -> OfflineExperimentMain.execute(input, output, root));
        assertFalse(Files.exists(output));
    }

    @Test void unknownFieldsAndNumericStringCoercionAreRejected() throws Exception {
        Path input = input();
        var tree = (ObjectNode) json.readTree(Files.readString(input));
        tree.put("unexpectedField", 1);
        Files.writeString(input, json.writeValueAsString(tree));
        assertThrows(Exception.class, () -> OfflineExperimentMain.execute(input, temp.resolve("unknown"), root));
        tree.remove("unexpectedField");
        tree.put("fairnessWeight", "0.5");
        Files.writeString(input, json.writeValueAsString(tree));
        assertThrows(Exception.class, () -> OfflineExperimentMain.execute(input, temp.resolve("coercion"), root));
    }

    @Test void duplicateJsonKeysAreRejectedInsteadOfChangingTheSeed() throws Exception {
        Path input = input();
        String content = Files.readString(input);
        Files.writeString(input, "{\"seed\":999," + content.substring(1));
        assertThrows(Exception.class, () -> OfflineExperimentMain.execute(input, temp.resolve("duplicates"), root));
    }

    @Test void missingSourceRootDoesNotProduceAnUntraceableReport() throws Exception {
        Path input = input(), output = temp.resolve("no-source");
        assertThrows(Exception.class, () -> OfflineExperimentMain.execute(input, output, temp));
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest
    @ValueSource(strings = {"seed", "id", "ticketPrice", "profile"})
    void fractionalIntegerFieldsAreRejectedWithoutTruncation(String field) throws Exception {
        Path input = input(), output = temp.resolve("fractional");
        var tree = (ObjectNode) json.readTree(Files.readString(input));
        switch (field) {
            case "seed" -> tree.put("seed", 20261026.9);
            case "id" -> ((ObjectNode) tree.path("nodes").get(0)).put("id", 1.9);
            case "ticketPrice" -> ((ObjectNode) tree.path("nodes").get(0)).put("ticketPrice", 20.9);
            case "profile" -> ((ObjectNode) tree.path("queries").get(0).path("preference")).put("CULTURE", 80.9);
            default -> throw new AssertionError(field);
        }
        Files.writeString(input, json.writeValueAsString(tree));
        assertThrows(Exception.class, () -> OfflineExperimentMain.execute(input, output, root));
        assertFalse(Files.exists(output));
    }
}
