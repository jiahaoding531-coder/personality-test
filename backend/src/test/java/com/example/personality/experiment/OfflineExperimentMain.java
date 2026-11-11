package com.example.personality.experiment;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import com.example.personality.service.RecommendationEngine;

public final class OfflineExperimentMain {
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("用法：OfflineExperimentMain 输入.json 新输出目录 项目根目录");
            System.exit(2);
        }
        try {
            execute(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
            System.out.println("离线实验完成；报告统计模拟入选，不代表真实用户曝光或满意度。");
            System.out.println(Path.of(args[1]).toAbsolutePath().normalize());
        } catch (Exception e) {
            // 不输出原始 JSON、个人坐标或完整堆栈。
            System.err.println("实验未完成（" + e.getClass().getSimpleName() + "）。请检查输入契约、源码目录和输出目录是否已存在。");
            System.exit(1);
        }
    }

    static void execute(Path inputFile, Path outputDirectory, Path repositoryRoot) throws IOException {
        if (Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(outputDirectory.toString());
        if (Files.size(inputFile) > 5_000_000) throw new IOException("输入文件超过 5 MB");
        byte[] bytes = Files.readAllBytes(inputFile);
        var input = JSON.readValue(bytes, OfflineExperimentInput.class);
        var report = new OfflineFairnessExperiment().run(input);
        var manifest = new TreeMap<String, Object>();
        manifest.put("toolVersion", OfflineRanking.VERSION);
        manifest.put("datasetKind", input.datasetKind());
        manifest.put("provenanceDeclaration", input.provenance());
        manifest.put("dataVerifiedByTool", false);
        manifest.put("measurement", report.measurement());
        manifest.put("warning", "合成样例只验证程序；用户提供的数据也需独立核验来源和许可。相关性分不是满意度真值。");
        manifest.put("inputSha256", sha256(bytes));
        manifest.put("sourceSha256", sourceHashes(repositoryRoot));
        manifest.put("runtimeClassSha256", runtimeHashes());
        manifest.put("javaVersion", System.getProperty("java.version"));
        String reportJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        manifest.put("reportSha256", sha256(reportJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // 所有输入、计算、追溯信息先验证成功，再创建新目录。CREATE_NEW 保证不覆盖旧文件。
        Files.createDirectory(outputDirectory);
        Files.write(outputDirectory.resolve("input.json"), bytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(outputDirectory.resolve("report.json"), reportJson, StandardOpenOption.CREATE_NEW);
        Files.writeString(outputDirectory.resolve("manifest.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n", StandardOpenOption.CREATE_NEW);
    }

    static Map<String, String> sourceHashes(Path root) throws IOException {
        var hashes = new TreeMap<String, String>();
        String main = "backend/src/main/java/com/example/personality/";
        addSource(hashes, root, "backend/pom.xml");
        addSource(hashes, root, main + "service/RecommendationEngine.java");
        for (String directory : List.of(main + "domain", "backend/src/test/java/com/example/personality/experiment")) {
            try (var files = Files.list(root.resolve(directory))) {
                for (var file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    addSource(hashes, root, root.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return hashes;
    }

    private static void addSource(Map<String, String> hashes, Path root, String relative) throws IOException {
        hashes.put(relative, sha256(Files.readAllBytes(root.resolve(relative))));
    }

    static Map<String, String> runtimeHashes() throws IOException {
        var hashes = new TreeMap<String, String>();
        try {
            Path main = Path.of(RecommendationEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path tests = Path.of(OfflineExperimentMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            hashClasses(hashes, main, "com/example/personality/domain", "main/", false);
            hashClasses(hashes, main, "com/example/personality/service", "main/", true);
            hashClasses(hashes, tests, "com/example/personality/experiment", "test/", false);
        } catch (URISyntaxException e) { throw new IOException("不能定位运行字节码", e); }
        return hashes;
    }

    private static void hashClasses(Map<String, String> hashes, Path root, String directory, String prefix, boolean engineOnly) throws IOException {
        try (var files = Files.walk(root.resolve(directory))) {
            for (var file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !engineOnly || p.getFileName().toString().startsWith("RecommendationEngine")).sorted().toList()) {
                hashes.put(prefix + root.relativize(file).toString().replace('\\', '/'), sha256(Files.readAllBytes(file)));
            }
        }
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JDK 缺少 SHA-256", e); }
    }
}
