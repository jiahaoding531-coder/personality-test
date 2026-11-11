package com.example.personality.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.personality.experiment.OfflineExperimentMain.*;

class OfflineEvidenceReadinessMainTest {
    @TempDir Path temp;
    final Path root = Path.of("..").toAbsolutePath().normalize();
    Path input() throws Exception {
        var input = temp.resolve("evidence.json");
        Files.writeString(input, OfflineEvidenceReadinessTest.input().toString());
        return input;
    }

    @Test void createsTraceableReportWithoutChangingInputOrAuthorizingExperiment() throws Exception {
        Path input = input(), output = temp.resolve("run");
        byte[] before = Files.readAllBytes(input);
        var result = OfflineEvidenceReadinessMain.execute(input, output, root);
        assertEquals(3, result.nodes().size());
        assertTrue(result.structurallyValid());
        assertFalse(result.experimentAuthorized());
        assertArrayEquals(before, Files.readAllBytes(input));
        assertArrayEquals(before, Files.readAllBytes(output.resolve("input.json")));
        var manifest = JSON.readTree(Files.readString(output.resolve("manifest.json")));
        for (String name : List.of("input", "report", "summary")) {
            String extension = name.equals("summary") ? ".md" : ".json";
            assertEquals(sha256(Files.readAllBytes(output.resolve(name + extension))), manifest.path(name + "Sha256").asText());
        }
        assertFalse(manifest.path("dataVerifiedByTool").asBoolean());
        assertFalse(manifest.path("runtimeClassSha256").isEmpty());
        String summary = Files.readString(output.resolve("summary.md"));
        assertTrue(summary.contains("未授予实验资格"));
        assertTrue(summary.contains("叶县县衙博物馆"));
        assertTrue(summary.contains("COORDINATE_MISSING"));
        assertFalse(summary.contains("33.622136"), "人可读报告不重复输出坐标线索数值");
    }

    @Test void repeatedChecksAreReproducibleAndPreviousOutputCannotBeOverwritten() throws Exception {
        Path input = input(), a = temp.resolve("a"), b = temp.resolve("b");
        OfflineEvidenceReadinessMain.execute(input, a, root);
        OfflineEvidenceReadinessMain.execute(input, b, root);
        for (String name : List.of("input.json", "report.json", "summary.md", "manifest.json")) {
            assertArrayEquals(Files.readAllBytes(a.resolve(name)), Files.readAllBytes(b.resolve(name)));
        }
        var before = Files.readAllBytes(a.resolve("report.json"));
        assertThrows(FileAlreadyExistsException.class, () -> OfflineEvidenceReadinessMain.execute(input, a, root));
        assertArrayEquals(before, Files.readAllBytes(a.resolve("report.json")));
    }

    @Test void invalidStructureStillProducesActionableDiagnostics() throws Exception {
        Path input = input(), output = temp.resolve("bad-structure");
        var tree = OfflineEvidenceReadinessTest.input();
        tree.put("schemaVersion", 2);
        Files.writeString(input, tree.toString());
        var result = OfflineEvidenceReadinessMain.execute(input, output, root);
        assertFalse(result.structurallyValid());
        assertEquals("INVALID_REGISTER", result.status());
        assertTrue(Files.exists(output.resolve("manifest.json")));
        assertTrue(Files.readString(output.resolve("summary.md")).contains("UNSUPPORTED_SCHEMA"));
    }

    @Test void markdownEscapingPreservesReadableFieldPathsAndDoesNotCreateLinksOrHtml() throws Exception {
        Path input = input(), output = temp.resolve("escaped");
        var tree = OfflineEvidenceReadinessTest.input();
        OfflineEvidenceReadinessTest.first(tree).put("name", "地点 <b>[甲](https://example.org)|#_`*\\\n下一行");
        Files.writeString(input, tree.toString());
        OfflineEvidenceReadinessMain.execute(input, output, root);
        String summary = Files.readString(output.resolve("summary.md"));
        assertTrue(summary.contains("$.nodes&#91;0&#93;.coordinate"));
        assertTrue(summary.contains("地点 &lt;b&gt;&#91;甲&#93;(https://example.org)&#124;&#35;&#95;&#96;&#42;&#92; 下一行"));
        assertFalse(summary.contains("<b>"));
        assertFalse(summary.contains("[甲]("));
        assertFalse(summary.contains("&&#35;"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "duplicate-key", "trailing", "oversized", "missing-source-root"})
    void invalidFilesDoNotLeavePartialReport(String kind) throws Exception {
        Path input = input(), output = temp.resolve("bad-file");
        switch (kind) {
            case "malformed" -> Files.writeString(input, "{no}");
            case "duplicate-key" -> Files.writeString(input, "{\"schemaVersion\":2," + Files.readString(input).substring(1));
            case "trailing" -> Files.writeString(input, Files.readString(input) + " {}");
            case "oversized" -> Files.writeString(input, " ".repeat(1_000_001));
            case "missing-source-root" -> { }
            default -> throw new AssertionError(kind);
        }
        assertThrows(Exception.class, () -> OfflineEvidenceReadinessMain.execute(input, output, kind.equals("missing-source-root") ? temp : root));
        assertFalse(Files.exists(output));
    }
}
