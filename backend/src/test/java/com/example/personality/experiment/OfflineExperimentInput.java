package com.example.personality.experiment;

import com.example.personality.domain.*;
import java.time.LocalTime;
import java.util.*;

/** 离线数据契约。record 类似 Python dataclass；这里不关联数据库实体。 */
public record OfflineExperimentInput(
        String datasetId, String datasetKind, String provenance, String coordinateSystem,
        String longTailDefinition, Long seed, Double fairnessWeight,
        List<Long> longTailPlaceIds, List<Node> nodes, List<Query> queries) {
    public record Node(long id, String name, String category, String coordinateSystem,
                       double latitude, double longitude, PlaceTraits traits, int indoor, int quality,
                       int ticketPrice, int suggestedMinutes, String openFrom, String openTo) { }
    public record Query(String id, String stratum, String coordinateSystem, String localTime,
                        double latitude, double longitude, double maxDistanceKm, int remainingMinutes,
                        Integer maxTicketPrice, Map<TravelDimension, Integer> preference,
                        Set<TravelState> states, Map<TravelDimension, Double> extraBias) { }

    public void validate() {
        require(text(datasetId) && text(provenance) && text(longTailDefinition), "必须声明数据集、来源和长尾定义");
        require(Set.of("SYNTHETIC_TEST_ONLY", "USER_SUPPLIED").contains(datasetKind == null ? "" : datasetKind), "未知数据集类型");
        require(Set.of("SYNTHETIC", "WGS84", "GCJ02").contains(coordinateSystem == null ? "" : coordinateSystem), "坐标系必须明确");
        require(!"SYNTHETIC".equals(coordinateSystem) || "SYNTHETIC_TEST_ONLY".equals(datasetKind), "合成坐标不能冒充实地数据");
        require(seed != null && fairnessWeight != null && Double.isFinite(fairnessWeight)
                && fairnessWeight >= 0 && fairnessWeight <= 1, "必须明确种子和 [0,1] 公平权重");
        require(nodes != null && queries != null && longTailPlaceIds != null, "节点、查询及长尾列表不可缺少");
        require(nodes.size() <= 1000 && queries.size() <= 1000, "第一版限 1000 节点和 1000 次离线查询");
        require((long) nodes.size() * queries.size() <= 50_000, "节点数乘查询数最多 50000，避免逐轮报告占用过多内存");
        Set<Long> nodeIds = new HashSet<>();
        for (var n : nodes) {
            require(n != null && n.id() > 0 && nodeIds.add(n.id()), "节点 ID 必须为不重复的正数");
            require(text(n.name()) && text(n.category()), "节点名称和类别不可为空");
            require(coordinateSystem.equals(n.coordinateSystem()), "节点坐标系与数据集不一致");
            coordinates(n.latitude(), n.longitude());
            require(n.traits() != null, "节点必须有明确人工属性");
            for (var dimension : TravelDimension.values()) {
                require(inRange(n.traits().valueOf(dimension), 0, 100), "节点属性必须在 0–100");
            }
            require(inRange(n.indoor(), 0, 100) && inRange(n.quality(), 0, 100)
                    && inRange(n.ticketPrice(), 0, 1000) && inRange(n.suggestedMinutes(), 1, 1440), "节点条件数值非法");
            require((n.openFrom() == null) == (n.openTo() == null), "营业时间必须同时给出或同时为空");
            if (n.openFrom() != null) {
                require(!time(n.openFrom()).isAfter(time(n.openTo())), "现有引擎不支持跨午夜营业，本实验拒绝此输入");
            }
        }
        require(new HashSet<>(longTailPlaceIds).size() == longTailPlaceIds.size()
                && nodeIds.containsAll(longTailPlaceIds), "长尾列表必须是不重复的已有节点 ID");
        Set<String> queryIds = new HashSet<>();
        for (var q : queries) {
            require(q != null && text(q.id()) && queryIds.add(q.id()) && text(q.stratum()), "查询 ID 必须唯一且场景不可为空");
            require(coordinateSystem.equals(q.coordinateSystem()), "查询坐标系与数据集不一致");
            coordinates(q.latitude(), q.longitude());
            time(q.localTime());
            require(Double.isFinite(q.maxDistanceKm()) && q.maxDistanceKm() >= .5 && q.maxDistanceKm() <= 50
                    && inRange(q.remainingMinutes(), 1, 1440)
                    && (q.maxTicketPrice() == null || inRange(q.maxTicketPrice(), 0, 1000)), "查询条件数值非法");
            require(q.preference() != null && q.preference().size() == TravelDimension.values().length, "必须冻结完整的八维有效画像");
            for (var d : TravelDimension.values()) {
                require(q.preference().get(d) != null && inRange(q.preference().get(d), 0, 100), "画像维度必须在 0–100");
            }
            require(q.states() != null && q.states().stream().noneMatch(Objects::isNull) && q.extraBias() != null, "状态和偏向必须明确");
            for (var entry : q.extraBias().entrySet()) {
                require(entry.getKey() != null && entry.getValue() != null && Double.isFinite(entry.getValue())
                        && entry.getValue() >= -1 && entry.getValue() <= 1, "偏向必须在 [-1,1]");
            }
        }
    }

    static LocalTime time(String text) {
        try { return LocalTime.parse(text); }
        catch (RuntimeException e) { throw new IllegalArgumentException("请输入明确的本地时刻，如 10:00"); }
    }
    private static void coordinates(double latitude, double longitude) {
        require(Double.isFinite(latitude) && Double.isFinite(longitude) && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180, "经纬度非法");
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static boolean inRange(int value, int min, int max) { return value >= min && value <= max; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
