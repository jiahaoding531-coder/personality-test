package com.example.personality.experiment;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.example.personality.experiment.OfflineExperimentMain.*;

/** 仅供本地离线执行的文件入口。 */
public final class OfflineRobustnessMain {
    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("用法：OfflineRobustnessMain 输入.json 计划.json 新输出目录 项目根目录");
            System.exit(2);
        }
        try {
            execute(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            System.out.println("多种子离线实验完成；结果只描述模拟入选，不证明真实游客收益。");
            System.out.println(Path.of(args[2]).toAbsolutePath().normalize());
        } catch (Exception e) {
            System.err.println("实验未完成（" + e.getClass().getSimpleName()
                    + "）。请检查输入、计划、源码路径及输出目录；不使用不完整报告。");
            System.exit(1);
        }
    }

    static void execute(Path input, Path plan, Path output, Path root) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(output.toString());
        byte[] inputBytes = boundedRead(input, 5_000_000), planBytes = boundedRead(plan, 64_000);
        var data = JSON.readValue(inputBytes, OfflineExperimentInput.class);
        var declared = JSON.readValue(planBytes, OfflineRobustnessExperiment.Plan.class);
        var report = new OfflineRobustnessExperiment().run(data, declared);
        byte[] summaryBytes = markdown(report).getBytes(StandardCharsets.UTF_8);
        var manifest = new TreeMap<String, Object>();
        manifest.put("toolVersion", OfflineRobustnessExperiment.VERSION);
        manifest.put("rankingVersion", OfflineRanking.VERSION);
        manifest.put("datasetKind", data.datasetKind());
        manifest.put("dataVerifiedByTool", false);
        manifest.put("provenanceDeclaration", data.provenance());
        manifest.put("measurement", report.measurement());
        manifest.put("analysis", "EXPLORATORY_SEED_AND_WEIGHT_SENSITIVITY");
        manifest.put("warning", "种子只改变查询顺序，不代表独立用户样本；不得据此推断真实公平收益或统计显著性。");
        manifest.put("inputSha256", sha256(inputBytes));
        manifest.put("planSha256", sha256(planBytes));
        manifest.put("summarySha256", sha256(summaryBytes));
        var sourceHashes = new TreeMap<>(sourceHashes(root));
        String script = "scripts/run-offline-robustness.ps1";
        sourceHashes.put(script, sha256(Files.readAllBytes(root.resolve(script))));
        manifest.put("sourceSha256", sourceHashes);
        manifest.put("runtimeClassSha256", runtimeHashes());
        manifest.put("javaVersion", System.getProperty("java.version"));
        // 输入、计算及源码信息先验证，再落新目录；报告流式写入并同步摘要，不复制完整 JSON。
        Files.createDirectory(output);
        Files.write(output.resolve("input.json"), inputBytes, StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("plan.json"), planBytes, StandardOpenOption.CREATE_NEW);
        manifest.put("reportSha256", writeReport(output.resolve("report.json"), report));
        Files.write(output.resolve("summary.md"), summaryBytes, StandardOpenOption.CREATE_NEW);
        byte[] manifestBytes = (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(output.resolve("manifest.json"), manifestBytes, StandardOpenOption.CREATE_NEW);
    }

    private static String writeReport(Path file, OfflineRobustnessExperiment.Report report) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW), digest)) {
                JSON.writerWithDefaultPrettyPrinter().writeValue(stream, report);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private static byte[] boundedRead(Path file, int maximum) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("文件超出大小限制");
            return bytes;
        }
    }

    private static String markdown(OfflineRobustnessExperiment.Report report) {
        var out = new StringBuilder("# 离线公平曝光敏感性报告\n\n");
        out.append("计划：").append(cell(report.plan().planId())).append("；数据集：")
                .append(cell(report.plan().datasetId())).append("；类型：").append(report.datasetKind()).append("。\n\n")
                .append("模拟入选、前三位置等权；不是用户真实曝光、到访或满意度。种子仅改变同一批查询顺序，不能视为独立用户样本。\n\n")
                .append("种子：").append(report.plan().seeds()).append("；权重：").append(report.plan().fairnessWeights())
                .append("；组合：").append(report.runs().size()).append("。全部组合公开，不自动挑选最优参数。\n\n")
                .append("表中单元格为均值 [最小, 最大]；范围不是置信区间。缺失指标保留未知，详细有效/缺失数见 report.json。\n\n")
                .append("|范围|权重|策略|Gini|机会校正 Gini|长尾份额|覆盖率|原分保留率|平均距离 km|\n")
                .append("|---|---:|---|---|---|---|---|---|---|\n");
        for (var row : report.summaries()) {
            out.append('|').append(scope(row.scope(), row.stratum())).append('|').append(number(row.fairnessWeight()))
                    .append('|').append(row.strategy()).append('|');
            for (String metric : List.of("gini", "opportunityAdjustedGini", "longTailShare", "coverage", "relevanceRetention", "meanDistanceKm")) {
                out.append(format(row.metrics().get(metric))).append('|');
            }
            out.append('\n');
        }
        out.append("\n## 配对差值：公平组 − 相关性组\n\n")
                .append("同种子、同权重、同场景配对。Gini 负值表示更均衡；长尾份额或原分保留率负值表示下降，不能隐藏。总体和各场景分开报告。\n\n")
                .append("|范围|权重|Gini 差|长尾份额差|原分保留率差|\n|---|---:|---|---|---|\n");
        for (var row : report.pairedFairMinusRelevance()) {
            out.append('|').append(scope(row.scope(), row.stratum())).append('|').append(number(row.fairnessWeight())).append('|');
            for (String metric : List.of("gini", "longTailShare", "relevanceRetention")) out.append(format(row.metrics().get(metric))).append('|');
            out.append('\n');
        }
        out.append("\n本报告是探索性敏感性检查。正式真实节点实验前应独立核验坐标、访问条件与人工属性，并预先冻结验证/测试划分及可接受的相关性损失。\n");
        return out.toString();
    }

    private static String format(OfflineRobustnessExperiment.Stats value) {
        if (value.validCount() == 0) return "未知（有效 0，缺失 " + value.missingCount() + "）";
        return number(value.mean()) + " [" + number(value.min()) + ", " + number(value.max()) + "]"
                + (value.missingCount() == 0 ? "" : "（缺失 " + value.missingCount() + "）");
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.6f", value); }
    private static String scope(String kind, String stratum) { return kind.equals("AGGREGATE") ? "总体" : "场景：" + cell(stratum); }
    private static String cell(String value) { return value.replace("|", "\\|").replace('\r', ' ').replace('\n', ' '); }
}
