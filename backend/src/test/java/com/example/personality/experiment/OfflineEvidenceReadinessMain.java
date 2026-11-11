package com.example.personality.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.example.personality.experiment.OfflineExperimentMain.*;

/** 检查文件并输出独立报告，不写回证据登记。 */
public final class OfflineEvidenceReadinessMain {
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("用法：OfflineEvidenceReadinessMain 登记.json 新输出目录 项目根目录");
            System.exit(2);
        }
        try {
            var result = execute(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
            System.out.println("证据准备检查完成；未授予实验资格。状态：" + result.status());
            System.out.println(Path.of(args[1]).toAbsolutePath().normalize());
            if (!result.structurallyValid()) System.exit(3);
        } catch (Exception e) {
            // 不回显原始输入、坐标值或异常堆栈，避免无意传播私人资料。
            System.err.println("检查未完成（" + e.getClass().getSimpleName()
                    + "）。请检查输入、源码路径和输出目录；不使用不完整报告。");
            System.exit(1);
        }
    }

    static OfflineEvidenceReadiness.Report execute(Path input, Path output, Path root) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(output.toString());
        byte[] inputBytes;
        try (var stream = Files.newInputStream(input)) {
            inputBytes = stream.readNBytes(1_000_001);
            if (inputBytes.length > 1_000_000) throw new IOException("登记文件超出 1 MB 限制");
        }
        var report = new OfflineEvidenceReadiness().check(JSON.readTree(inputBytes));
        byte[] summary = markdown(report).getBytes(StandardCharsets.UTF_8);
        var manifest = new TreeMap<String, Object>();
        manifest.put("toolVersion", report.version());
        manifest.put("registerId", report.registerId());
        manifest.put("dataVerifiedByTool", false);
        manifest.put("experimentAuthorized", false);
        manifest.put("inputSha256", sha256(inputBytes));
        manifest.put("summarySha256", sha256(summary));
        var sources = new TreeMap<>(sourceHashes(root));
        String script = "scripts/check-offline-evidence.ps1";
        sources.put(script, sha256(Files.readAllBytes(root.resolve(script))));
        manifest.put("sourceSha256", sources);
        manifest.put("runtimeClassSha256", runtimeHashes());
        manifest.put("javaVersion", System.getProperty("java.version"));
        // 先完成解析和校验清单，再创建新目录。CREATE_NEW 防止覆盖，manifest 最后写入。
        Files.createDirectory(output);
        Files.write(output.resolve("input.json"), inputBytes, StandardOpenOption.CREATE_NEW);
        manifest.put("reportSha256", writeReport(output.resolve("report.json"), report));
        Files.write(output.resolve("summary.md"), summary, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n", StandardOpenOption.CREATE_NEW);
        return report;
    }

    private static String writeReport(Path file, OfflineEvidenceReadiness.Report report) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW), digest)) {
                JSON.writerWithDefaultPrettyPrinter().writeValue(stream, report);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private static String markdown(OfflineEvidenceReadiness.Report report) {
        var out = new StringBuilder("# 地点证据准备检查报告\n\n");
        out.append("登记：").append(cell(report.registerId())).append("；检查器：").append(report.version()).append("。\n\n")
                .append("状态：").append(report.status()).append("；节点数量：").append(report.nodes().size()).append("。\n\n")
                .append("**未授予实验资格。** 结构自洽不代表事实已核验；本工具不访问来源网页、不认定许可、不认证入口，也不生成实验输入。\n\n")
                .append("ERROR＝文件结构或声明矛盾；GAP＝资料待补齐；REVIEW＝需要人工复核。未知不等于零或免费。\n\n");
        List<OfflineEvidenceReadiness.Issue> all = new ArrayList<>(report.issues());
        report.nodes().forEach(n -> all.addAll(n.issues()));
        for (String severity : List.of("ERROR", "GAP", "REVIEW")) {
            out.append(severity).append("：").append(all.stream().filter(i -> severity.equals(i.severity())).count()).append(" 条；");
        }
        out.append("统计的是检查项，不能当作独立事实数量或资料完成百分比。\n\n## 全局检查\n\n");
        table(out, report.issues());
        for (var node : report.nodes()) {
            out.append("\n## ").append(cell(node.nodeId())).append(" · ").append(cell(node.name())).append("\n\n")
                    .append("原登记 experimentEligible 声明：").append(node.declaredEligible()).append("。该声明不会改变检查结论。\n\n");
            table(out, node.issues());
        }
        out.append("\n## 后续处理\n\n先修复 ERROR，再补齐可追溯材料并人工复核。保留未知项，不从历史价格、照片坐标或定性描述推断现行事实。\n\n")
                .append("当前报告只检查 v1 登记约定，不认证人工标注质量或长尾分组，也不检查在线系统。正式实验仍需另行审查并冻结输入。\n\n")
                .append("input.json 是原文件快照，可能含原始来源信息；分享前单独检查。此摘要不重复输出坐标值。manifest.json 用于核对文件、源码与运行字节码。\n");
        return out.toString();
    }

    private static void table(StringBuilder out, List<OfflineEvidenceReadiness.Issue> issues) {
        out.append("|级别|检查代码|字段位置|说明|\n|---|---|---|---|\n");
        for (var i : issues) out.append('|').append(i.severity()).append('|').append(i.code()).append('|')
                .append(cell(i.path())).append('|').append(cell(i.message())).append("|\n");
    }

    private static String cell(String value) {
        if (value == null) return "未识别";
        // 逐字符编码，避免后续替换再次改坏已经生成的 HTML 实体。
        var escaped = new StringBuilder();
        for (char c : value.toCharArray()) escaped.append(switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '\\' -> "&#92;";
            case '|' -> "&#124;";
            case '`' -> "&#96;";
            case '[' -> "&#91;";
            case ']' -> "&#93;";
            case '*' -> "&#42;";
            case '_' -> "&#95;";
            case '#' -> "&#35;";
            case '\r', '\n' -> " ";
            default -> String.valueOf(c);
        });
        return escaped.toString();
    }
}
