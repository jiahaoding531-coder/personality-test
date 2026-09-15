package com.example.personality.domain;

/**
 * 一个地点在 7 个维度上的属性值（0~100）。
 *
 * <p>这是**推荐算法的输入**，刻意做成不依赖数据库和框架的纯数据。
 * 这样 {@code RecommendationEngine} 就能直接 {@code new} 出来测，
 * 不需要起 Spring 容器、也不需要连数据库。
 *
 * <p>字段顺序和 {@link TravelDimension} 的声明顺序一致，
 * 方便 {@link #valueOf(TravelDimension)} 里的 switch 一一对应。
 *
 * @param lively 热闹程度。<b>注意它的方向和 {@code CROWD_TOLERANCE} 是"同向"的</b>——
 *               地点越热闹这个值越高，用户越能忍受人群偏好值也越高，
 *               所以直接相乘就是"匹配"。不需要取反。
 */
public record PlaceTraits(
        int nature,
        int culture,
        int food,
        int photography,
        int hiddenGems,
        int lively,
        int walking
) {

    /**
     * 按维度取值。
     *
     * <p>用 switch 表达式（Java 14+）而不是 if-else 链：
     * 编译器会检查枚举是否**全部覆盖**——将来往 TravelDimension 里
     * 加一个新维度，这里立刻编译不过，不会静默返回错误的值。
     */
    public int valueOf(TravelDimension dimension) {
        return switch (dimension) {
            case NATURE -> nature;
            case CULTURE -> culture;
            case FOOD -> food;
            case PHOTOGRAPHY -> photography;
            case HIDDEN_GEMS -> hiddenGems;
            case CROWD_TOLERANCE -> lively;
            case WALKING -> walking;
            // PLANNING 不是地点属性，没有对应的值。
            // 返回 0 让它在加权求和里自然失效（乘 0），
            // 而不是抛异常——因为调用方可能无意中传进来。
            case PLANNING -> 0;
        };
    }
}
