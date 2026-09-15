package com.example.personality.domain;

/**
 * 旅行偏好的 8 个维度。
 *
 * <h2>和 {@link Dimension}（人格维度）的关系</h2>
 *
 * <p>两者是**平行的、互不相干**的两套量表：
 * <ul>
 *   <li>{@link Dimension} —— 人格测试用，5 个维度，每维度 4 道题</li>
 *   <li>{@link TravelDimension} —— 旅行偏好测试用，8 个维度，每维度 1 道题</li>
 * </ul>
 *
 * <p><b>为什么不合并成一个枚举？</b>因为它们的语义层级不同：
 * 人格维度描述"你是个什么样的人"，旅行维度描述"这次旅行你想要什么"。
 * 合并之后会出现"给一个人格维度算旅行属性"这种没有意义的操作，
 * 而且 {@code PersonalityProfile} 那 5 个固定列也没法塞进 8 个维度。
 *
 * <p>注意最后三个（小众 / 人群容忍 / 步行意愿）不是"地方有什么"，
 * 而是**用户能接受什么**。它们对应的地点属性是：
 * <ul>
 *   <li>{@code HIDDEN_GEMS} ←→ 地点的冷门程度（越冷门越合适）</li>
 *   <li>{@code CROWD_TOLERANCE} ←→ 地点的热闹程度（越能忍越热闹也行）</li>
 *   <li>{@code WALKING} ←→ 地点的步行强度（越愿意走越费腿的地方也能去）</li>
 * </ul>
 */
public enum TravelDimension implements ScaleDimension {

    NATURE("自然风光"),
    CULTURE("人文历史"),
    FOOD("美食探索"),
    PHOTOGRAPHY("摄影出片"),
    HIDDEN_GEMS("小众独特"),
    CROWD_TOLERANCE("人群耐受"),
    WALKING("步行意愿"),
    PLANNING("提前规划");

    private final String label;

    TravelDimension(String label) {
        this.label = label;
    }

    /** 中文展示名。实现自 {@link ScaleDimension}。 */
    @Override
    public String label() {
        return label;
    }

    /**
     * 这个维度是否影响「推荐哪个地点」。
     *
     * <p>{@link #PLANNING} 不影响地点选择——它描述的是"你怎么安排行程"
     * （提前排好 vs 到了再说），而不是"你想要什么样的地方"。
     * 它会影响**行程生成**的粒度（一天排 5 个点 vs 2 个点），
     * 但不是候选排序的输入。
     *
     * <p>把这个判断放在枚举上而不是散落在各处 if 里，
     * 是为了让"哪些维度参与排序"这件事只有一个定义处。
     */
    public boolean affectsPlaceChoice() {
        return this != PLANNING;
    }
}
