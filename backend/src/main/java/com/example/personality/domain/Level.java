package com.example.personality.domain;

/**
 * 归一化分数（0~100）对应的档位。
 *
 * <p>只分 3 档而不是 5 档，是因为每个维度只有 4 道题，归一化后总共只有
 * 17 个可能的取值（0、6.25、12.5、...、100）。档位分得越细，落在同一档的
 * 人越少，"档位"这个说法就越没有意义。
 *
 * <p>V0.2 如果扩到每维度 6 题（25 个取值），可以考虑拆成 5 档。
 */
public enum Level {

    LOW("偏低"),
    MEDIUM("中等"),
    HIGH("偏高");

    private final String label;

    Level(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 把 0~100 的分数映射到档位。
     *
     * <p>注意这里用的是 {@code compareTo} 而不是 {@code <}，因为 normalized 是
     * BigDecimal。BigDecimal 不能用 == 比较，也不能直接用 &lt; &gt;，
     * 必须用 compareTo（返回 -1/0/1）。这是 BigDecimal 最容易踩的坑之一。
     */
    public static Level fromScore(java.math.BigDecimal normalized) {
        if (normalized.compareTo(java.math.BigDecimal.valueOf(66.67)) >= 0) {
            return HIGH;
        }
        if (normalized.compareTo(java.math.BigDecimal.valueOf(33.33)) >= 0) {
            return MEDIUM;
        }
        return LOW;
    }
}
