package com.example.personality.experiment;

import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;

/** 离线检查材料是否自洽；不验证现实事实，不授予实验资格。 */
public final class OfflineEvidenceReadiness {
    public record Issue(String severity, String code, String path, String message) { }
    public record NodeCheck(String nodeId, String name, boolean declaredEligible, List<Issue> issues) { }
    public record Report(String version, String registerId, String status, boolean structurallyValid,
                         boolean dataVerifiedByTool, boolean experimentAuthorized,
                         List<Issue> issues, List<NodeCheck> nodes) { }

    public Report check(JsonNode input) {
        List<Issue> issues = new ArrayList<>();
        List<NodeCheck> nodes = new ArrayList<>();
        if (input == null || !input.isObject()) {
            add(issues, "ERROR", "ROOT_TYPE", "$", "登记必须是 JSON 对象");
            return report(null, issues, nodes);
        }
        if (!bounded(input, 0, new int[]{0})) {
            add(issues, "ERROR", "STRUCTURE_LIMIT", "$", "结构过深、字段过长或值数量超限");
            return report(null, issues, nodes);
        }
        if (!input.path("schemaVersion").isIntegralNumber() || !"1".equals(input.path("schemaVersion").asText())
                || !"PILOT_EVIDENCE_REGISTER".equals(input.path("artifactType").asText())) {
            add(issues, "ERROR", "UNSUPPORTED_SCHEMA", "$", "仅支持 PILOT_EVIDENCE_REGISTER v1");
            return report(null, issues, nodes);
        }
        String registerId = text(input, "registerId", "$", 128, issues);
        text(input, "reviewedOn", "$", 32, issues);
        Map<String, JsonNode> sources = new LinkedHashMap<>();
        JsonNode sourceArray = array(input, "sources", "$", 200, issues);
        int index = 0;
        for (JsonNode source : sourceArray) {
            String path = "$.sources[" + index++ + "]";
            String id = text(source, "id", path, 128, issues);
            if (sources.putIfAbsent(id, source) != null) add(issues, "ERROR", "DUPLICATE_SOURCE_ID", path + ".id", "来源编号重复");
            text(source, "title", path, 256, issues);
            text(source, "type", path, 128, issues);
            text(source, "checkedOn", path, 32, issues);
            String url = text(source, "url", path, 2048, issues);
            try {
                URI uri = URI.create(url);
                if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            } catch (RuntimeException e) { add(issues, "ERROR", "SOURCE_URL_INVALID", path + ".url", "来源需为不含凭据的 HTTP(S) 地址；工具不会访问该地址"); }
        }
        // 遍历所有证据块，不能只检查节点的身份引用而漏掉价格、照片和实体关系。
        referencesAndDates(input, "$", sources.keySet(), issues);
        JsonNode nodeArray = array(input, "nodes", "$", 100, issues);
        Set<String> nodeIds = new HashSet<>();
        index = 0;
        for (JsonNode node : nodeArray) {
            String path = "$.nodes[" + index++ + "]";
            List<Issue> problems = new ArrayList<>();
            String id = text(node, "id", path, 128, problems);
            String name = text(node, "name", path, 256, problems);
            if (!nodeIds.add(id)) add(problems, "ERROR", "DUPLICATE_NODE_ID", path + ".id", "节点编号重复");
            JsonNode identity = node.path("identitySourceIds");
            if (!identity.isArray() || identity.isEmpty()) add(problems, "ERROR", "IDENTITY_SOURCE_REQUIRED", path + ".identitySourceIds", "需提供身份来源引用");
            requireBoolean(node, "experimentEligible", path, problems);
            checkCoordinate(node.path("coordinate"), path + ".coordinate", sources, problems);
            checkLeads(node.path("coordinateLeads"), path + ".coordinateLeads", problems);
            checkEvidenceBlocks(node, path, problems);
            checkOperations(node.path("currentOperationalValues"), path + ".currentOperationalValues", problems);
            material(node, "annotation", path, "ANNOTATION", "人工属性及独立复核", problems);
            material(node, "popularityEvidence", path, "POPULARITY", "独立人气资料或预声明分组定义", problems);
            if (node.path("experimentEligible").isBoolean() && node.path("experimentEligible").booleanValue()
                    && problems.stream().anyMatch(p -> !p.severity().equals("REVIEW"))) {
                add(problems, "ERROR", "ELIGIBILITY_CONTRADICTION", path + ".experimentEligible", "资格声明不能覆盖缺项或矛盾；不得进入实验");
            }
            nodes.add(new NodeCheck(id, name, node.path("experimentEligible").isBoolean() && node.path("experimentEligible").booleanValue(), List.copyOf(problems)));
        }
        if (nodes.size() <= 3) add(issues, "REVIEW", "TOP3_COMPETITION_INSUFFICIENT", "$.nodes", "总候选不超过 Top3，难以检验入选竞争；更多节点也仍须核对逐轮可行集合");
        checkRelations(input.path("entityRelations"), issues);
        add(issues, "REVIEW", "MANUAL_REVIEW_REQUIRED", "$", "还需人工确认真实性、时效性、入口、许可和实验冻结条件；检查通过不授予资格");
        return report(registerId, issues, nodes);
    }

    private static void checkRelations(JsonNode relations, List<Issue> issues) {
        if (!relations.isArray()) { add(issues, "ERROR", "RELATIONS_TYPE", "$.entityRelations", "实体关系必须为数组，允许空数组"); return; }
        for (int i = 0; i < relations.size(); i++) {
            String path = "$.entityRelations[" + i + "]";
            JsonNode relation = relations.get(i);
            if (!relation.isObject()) { add(issues, "ERROR", "RELATION_TYPE", path, "实体关系项必须为对象"); continue; }
            JsonNode names = relation.path("names");
            Set<String> distinct = new HashSet<>();
            boolean validNames = names.isArray() && names.size() >= 2;
            if (names.isArray()) for (JsonNode name : names) {
                if (!name.isTextual() || name.asText().isBlank() || name.asText().length() > 256 || !distinct.add(name.asText().strip())) validNames = false;
            }
            if (!validNames) add(issues, "ERROR", "RELATION_NAMES", path + ".names", "关系至少需要两个不同的非空名称");
            JsonNode refs = relation.path("sourceIds");
            if (!refs.isArray() || refs.isEmpty()) add(issues, "ERROR", "RELATION_SOURCES_REQUIRED", path + ".sourceIds", "关系需保留非空来源引用");
            text(relation, "decision", path, 2000, issues);
            String status = relation.path("status").asText("");
            if (status.equals("UNRESOLVED")) add(issues, "GAP", "ENTITY_RELATION_UNRESOLVED", path, "同名/相邻/重叠关系未解决，不自动合并或拆为独立节点");
            else if (status.equals("RESOLVED")) add(issues, "REVIEW", "ENTITY_RELATION_REVIEW_REQUIRED", path, "已解决只是登记声明，仍需人工核对结论及来源，不自动合并实体");
            else add(issues, "ERROR", "RELATION_STATUS", path + ".status", "关系状态只能为 UNRESOLVED 或 RESOLVED；不能用缺项或拼写错误隐藏未解决关系");
        }
    }

    private static void checkCoordinate(JsonNode coordinate, String path, Map<String, JsonNode> sources, List<Issue> issues) {
        if (!coordinate.isObject()) { add(issues, "ERROR", "COORDINATE_TYPE", path, "正式坐标对象不可缺少，未知字段应显式为 null"); return; }
        for (String key : List.of("latitude", "longitude", "coordinateSystem", "pointRole", "sourceId", "accuracyMeters", "verified")) {
            if (!coordinate.has(key)) add(issues, "ERROR", "COORDINATE_FIELD_MISSING", path + "." + key, "正式坐标字段缺失，不能默认为零");
        }
        requireBoolean(coordinate, "verified", path, issues);
        boolean hasLat = !coordinate.path("latitude").isNull() && !coordinate.path("latitude").isMissingNode();
        boolean hasLon = !coordinate.path("longitude").isNull() && !coordinate.path("longitude").isMissingNode();
        boolean valuesValid = number(coordinate.path("latitude"), -90, 90) && number(coordinate.path("longitude"), -180, 180);
        if (!hasLat && !hasLon) add(issues, "GAP", "COORDINATE_MISSING", path, "正式经纬度尚未取得，不采用地址推算或相机线索");
        else if (hasLat != hasLon) add(issues, "ERROR", "COORDINATE_PARTIAL", path, "经纬度必须一起填写或一起保持未知");
        else if (!valuesValid) add(issues, "ERROR", "COORDINATE_VALUE_INVALID", path, "经纬度必须是有限数值且在合法范围内，不接受数字字符串");
        String system = coordinate.path("coordinateSystem").asText("");
        boolean knownSystem = Set.of("WGS84", "GCJ02").contains(system);
        if (!knownSystem) {
            add(issues, system.equals("UNKNOWN") ? "GAP" : "ERROR", "COORDINATE_SYSTEM_UNKNOWN", path + ".coordinateSystem", "坐标基准未明确，不自动转换或贴标签");
        }
        String role = coordinate.path("pointRole").asText("");
        boolean entrance = Set.of("PUBLIC_ENTRANCE", "STREET_ENDPOINT", "STREET_REPRESENTATIVE_POINT").contains(role);
        if (!entrance) add(issues, "GAP", "COORDINATE_ROLE_UNCONFIRMED", path + ".pointRole", "需确认公共入口或街区点位规则，相机/建筑中心不等于入口");
        JsonNode source = sources.get(coordinate.path("sourceId").asText(""));
        boolean sourcePresent = source != null;
        if (!sourcePresent) add(issues, "GAP", "COORDINATE_SOURCE_MISSING", path + ".sourceId", "正式坐标缺少有效来源引用");
        boolean photoOnly = sourcePresent && "PUBLIC_PHOTO_METADATA".equals(source.path("type").asText());
        boolean verified = coordinate.path("verified").isBoolean() && coordinate.path("verified").booleanValue();
        if (verified && (!valuesValid || !knownSystem || !entrance || !sourcePresent || photoOnly)) {
            add(issues, "ERROR", "COORDINATE_VERIFICATION_CONTRADICTION", path + ".verified", "核验声明与数值、坐标系、点位或来源不一致；照片元数据不足以证明公共入口");
        } else if (!verified) add(issues, "GAP", "COORDINATE_NOT_VERIFIED", path + ".verified", "正式坐标仍声明未核验");
        else add(issues, "REVIEW", "COORDINATE_MANUAL_REVIEW_REQUIRED", path, "坐标字段自洽不代表原始来源和入口已经被程序验证");
        JsonNode accuracy = coordinate.path("accuracyMeters");
        if (accuracy.isNull()) add(issues, "REVIEW", "ACCURACY_UNKNOWN", path + ".accuracyMeters", "精度未记录，小数位数不能代表定位精度");
        else if (!number(accuracy, 0, Double.MAX_VALUE)) add(issues, "ERROR", "ACCURACY_INVALID", path + ".accuracyMeters", "精度必须是非负有限数值或明确未知");
    }

    private static void checkLeads(JsonNode leads, String path, List<Issue> issues) {
        if (!leads.isArray()) { add(issues, "ERROR", "LEADS_TYPE", path, "坐标线索必须是数组"); return; }
        for (int i = 0; i < leads.size(); i++) {
            JsonNode lead = leads.get(i);
            String p = path + "[" + i + "]";
            requireBoolean(lead, "adopted", p, issues);
            if (lead.path("adopted").isBoolean() && lead.path("adopted").booleanValue()) {
                add(issues, "ERROR", "LEAD_AUTO_ADOPTION", p + ".adopted", "线索不能只改布尔值自动成为正式坐标；须另存独立入口核验证据");
            }
            requiredSource(lead, p, issues);
            if (!number(lead.path("latitude"), -90, 90) || !number(lead.path("longitude"), -180, 180)) {
                add(issues, "ERROR", "LEAD_COORDINATE_INVALID", p, "线索经纬度格式或范围错误");
            }
        }
    }

    private static void checkEvidenceBlocks(JsonNode node, String path, List<Issue> issues) {
        for (String key : List.of("priceEvidence", "datedObservations")) {
            if (!node.has(key)) continue;
            JsonNode evidence = node.path(key);
            if (!evidence.isArray()) { add(issues, "ERROR", "EVIDENCE_ARRAY_TYPE", path + "." + key, "证据列表类型错误"); continue; }
            for (int i = 0; i < evidence.size(); i++) {
                String itemPath = path + "." + key + "[" + i + "]";
                requiredSource(evidence.get(i), itemPath, issues);
                if (key.equals("priceEvidence")) for (String field : List.of("fullCny", "halfCny")) {
                    JsonNode amount = evidence.get(i).path(field);
                    if (amount.isNull()) add(issues, "GAP", "HISTORICAL_PRICE_UNKNOWN", itemPath + "." + field, "历史材料中的金额未知，不按零元处理");
                    else if (!number(amount, 0, Double.MAX_VALUE)) add(issues, "ERROR", "HISTORICAL_PRICE_INVALID", itemPath + "." + field, "历史金额须为非负有限数值；未知应显式为 null，不接受数字字符串");
                }
            }
        }
        for (String key : List.of("operatingEvidence", "showEvidence", "proposalEvidence")) {
            if (node.has(key)) requiredSource(node.path(key), path + "." + key, issues);
        }
        if (node.has("proposalEvidence")) add(issues, "REVIEW", "PROPOSAL_NOT_CURRENT_APPROVAL", path + ".proposalEvidence", "听证拟案不能自动作为现行票价或联票范围的正式批准");
    }

    private static void checkOperations(JsonNode values, String path, List<Issue> issues) {
        if (!values.isObject()) { add(issues, "ERROR", "CURRENT_OPERATIONS_TYPE", path, "现行条件必须是对象，未知值显式填写 null"); return; }
        for (String key : List.of("ticketPrice", "openingHours", "reservationRequired", "freeParking")) {
            JsonNode value = values.path(key);
            if (value.isMissingNode()) { add(issues, "ERROR", "CURRENT_FIELD_MISSING", path + "." + key, "缺少现行条件字段"); continue; }
            if (value.isNull()) { add(issues, "GAP", "CURRENT_VALUE_UNKNOWN", path + "." + key, "现行条件尚未确认；不使用历史材料自动补值"); continue; }
            boolean valid = switch (key) {
                case "ticketPrice" -> number(value, 0, Double.MAX_VALUE);
                case "openingHours" -> value.isTextual() && !value.asText().isBlank();
                default -> value.isBoolean();
            };
            if (!valid) add(issues, "ERROR", "CURRENT_VALUE_TYPE", path + "." + key, "现行条件类型或数值不合法");
            // v1 没有逐现行值的审批记录契约，因此不能只看非空就认证为最新事实。
            add(issues, "GAP", "CURRENT_OPERATIONS_REVIEW_REQUIRED", path + "." + key, "已填写值仍缺逐字段现行依据审查，请人工核对适用日期、人群及来源");
        }
    }

    private static void material(JsonNode node, String key, String path, String code, String label, List<Issue> issues) {
        JsonNode value = node.path(key);
        if (value.isMissingNode()) add(issues, "ERROR", code + "_FIELD_MISSING", path + "." + key, "必需的材料字段缺失，未知应填写 null");
        else if (value.isNull()) add(issues, "GAP", code + "_MISSING", path + "." + key, "待补齐" + label);
        else {
            if (!value.isObject()) add(issues, "ERROR", code + "_TYPE", path + "." + key, "材料必须是对象或 null");
            add(issues, "GAP", code + "_REVIEW_REQUIRED", path + "." + key, "已有材料仍需人工复核" + label + "，不从非空字段推断完成");
        }
    }

    private static void referencesAndDates(JsonNode value, String path, Set<String> sources, List<Issue> issues) {
        if (value.isObject()) for (var entry : value.properties()) {
            String key = entry.getKey(), p = path + "." + key;
            JsonNode child = entry.getValue();
            if (key.equals("sourceId") && !child.isNull()) reference(child, p, sources, issues);
            if (key.equals("sourceIds") || key.equals("identitySourceIds")) {
                if (!child.isArray() || child.isEmpty()) add(issues, "ERROR", "SOURCE_REFERENCES_TYPE", p, "来源引用必须是非空数组");
                else for (int i = 0; i < child.size(); i++) reference(child.get(i), p + "[" + i + "]", sources, issues);
            }
            if (Set.of("publishedOn", "checkedOn", "observedOn", "reviewedOn", "attemptedOn", "asOf").contains(key)) {
                try {
                    if (!child.isTextual()) throw new IllegalArgumentException();
                    LocalDate.parse(child.asText());
                } catch (RuntimeException e) { add(issues, "ERROR", "DATE_INVALID", p, "日期需为有效的 YYYY-MM-DD"); }
            }
            if (Set.of("currentVerified", "currentOnSiteVerified", "mayDefinePopularity", "approvedPackageScopeConfirmed").contains(key) && !child.isBoolean()) {
                add(issues, "ERROR", "BOOLEAN_REQUIRED", p, "标志必须是布尔值，不能用字符串代替");
            }
            referencesAndDates(child, p, sources, issues);
        }
        else if (value.isArray()) for (int i = 0; i < value.size(); i++) referencesAndDates(value.get(i), path + "[" + i + "]", sources, issues);
    }

    private static void reference(JsonNode value, String path, Set<String> sources, List<Issue> issues) {
        if (!value.isTextual() || value.asText().isBlank() || !sources.contains(value.asText())) {
            add(issues, "ERROR", "SOURCE_REFERENCE_UNKNOWN", path, "来源引用不存在或不是有效编号");
        }
    }
    private static void requiredSource(JsonNode block, String path, List<Issue> issues) { text(block, "sourceId", path, 128, issues); }
    private static boolean number(JsonNode value, double min, double max) {
        return value.isNumber() && Double.isFinite(value.doubleValue()) && value.doubleValue() >= min && value.doubleValue() <= max;
    }
    private static String text(JsonNode value, String key, String path, int max, List<Issue> issues) {
        JsonNode field = value.path(key);
        if (!field.isTextual() || field.asText().isBlank() || field.asText().length() > max) {
            add(issues, "ERROR", "TEXT_REQUIRED", path + "." + key, "文本缺失、类型错误或超出长度限制");
            return "";
        }
        return field.asText();
    }
    private static JsonNode array(JsonNode value, String key, String path, int max, List<Issue> issues) {
        JsonNode field = value.path(key);
        if (!field.isArray() || field.isEmpty() || field.size() > max) {
            add(issues, "ERROR", "ARRAY_REQUIRED", path + "." + key, "需提供非空且规模受限的数组");
            return OfflineExperimentMain.JSON.createArrayNode();
        }
        return field;
    }
    private static void requireBoolean(JsonNode value, String key, String path, List<Issue> issues) {
        if (!value.path(key).isBoolean()) add(issues, "ERROR", "BOOLEAN_REQUIRED", path + "." + key, "标志必须显式为布尔值");
    }
    private static boolean bounded(JsonNode value, int depth, int[] count) {
        if (depth > 20 || ++count[0] > 20000 || (value.isTextual() && value.asText().length() > 10000)) return false;
        if (value.isObject()) {
            for (var e : value.properties()) if (e.getKey().length() > 128 || !bounded(e.getValue(), depth + 1, count)) return false;
        } else if (value.isArray()) for (JsonNode child : value) if (!bounded(child, depth + 1, count)) return false;
        return true;
    }
    private static Report report(String id, List<Issue> issues, List<NodeCheck> nodes) {
        List<Issue> all = new ArrayList<>(issues);
        nodes.forEach(n -> all.addAll(n.issues()));
        boolean valid = all.stream().noneMatch(i -> i.severity().equals("ERROR"));
        String status = !valid ? "INVALID_REGISTER" : all.stream().anyMatch(i -> i.severity().equals("GAP"))
                ? "NOT_READY_FOR_EXPERIMENT" : "MANUAL_REVIEW_REQUIRED";
        return new Report("evidence-readiness-v1", id, status, valid, false, false, List.copyOf(issues), List.copyOf(nodes));
    }
    private static void add(List<Issue> issues, String severity, String code, String path, String message) {
        issues.add(new Issue(severity, code, path, message));
    }
}
