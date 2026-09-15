package com.example.personality.service;

import com.example.personality.domain.TravelState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextInferrer} 的单元测试。
 *
 * <p><b>这个类的测试必须能喂固定时间。</b>
 * 如果直接调 {@code LocalTime.now()}，测试就会依赖"跑测试的时候是不是饭点"——
 * 那种测试早上跑绿、中午跑红，是最难查的一类问题。
 * 这正是把推断做成纯逻辑类、时间从参数传进来的原因。
 */
class ContextInferrerTest {

    private final ContextInferrer inferrer = new ContextInferrer();

    // ==========================================================
    // 饭点边界——最容易写错的地方
    // ==========================================================

    @Test
    @DisplayName("午饭时段推断「想吃饭」，午饭前后不推断")
    void lunchWindow() {
        assertTrue(inferrer.inferStates(LocalTime.of(12, 0)).contains(TravelState.HUNGRY));
        assertTrue(inferrer.inferStates(LocalTime.of(11, 30)).contains(TravelState.HUNGRY),
                "11:30 是饭点起点，应该算");
        assertFalse(inferrer.inferStates(LocalTime.of(11, 29)).contains(TravelState.HUNGRY),
                "11:29 还不算");
        assertFalse(inferrer.inferStates(LocalTime.of(13, 0)).contains(TravelState.HUNGRY),
                "13:00 是终点，用左闭右开——归给不算的那一边，边界才不会有歧义");
        assertFalse(inferrer.inferStates(LocalTime.of(14, 0)).contains(TravelState.HUNGRY));
    }

    @Test
    @DisplayName("晚饭时段推断「想吃饭」")
    void dinnerWindow() {
        assertTrue(inferrer.inferStates(LocalTime.of(18, 0)).contains(TravelState.HUNGRY));
        assertTrue(inferrer.inferStates(LocalTime.of(17, 30)).contains(TravelState.HUNGRY));
        assertFalse(inferrer.inferStates(LocalTime.of(19, 30)).contains(TravelState.HUNGRY));
    }

    @Test
    @DisplayName("非饭点什么都不推断——宁可少推，也不装作知道")
    void outsideMealWindowsInferNothing() {
        assertEquals(Set.of(), inferrer.inferStates(LocalTime.of(10, 0)));
        assertEquals(Set.of(), inferrer.inferStates(LocalTime.of(15, 0)));
        assertEquals(Set.of(), inferrer.inferStates(LocalTime.of(23, 0)));
        assertEquals(Set.of(), inferrer.inferStates(LocalTime.of(4, 0)));
    }

    // ==========================================================
    // 边界：这些是"看起来很对、其实错了"的地方
    // ==========================================================

    @Test
    @DisplayName("推断只产出 HUNGRY，不会凭空造出别的状态")
    void onlyInfersHungry() {
        // 遍历一整天，每个整点都检查一遍。
        // ⚠️ 这条是在守一个承诺：**没有数据支撑的状态就不该被推断出来**。
        // 比如"天黑了推室内"——系统根本没有室内/户外的字段，推了就是瞎猜。
        for (int hour = 0; hour < 24; hour++) {
            Set<TravelState> states = inferrer.inferStates(LocalTime.of(hour, 0));
            for (TravelState state : states) {
                assertEquals(TravelState.HUNGRY, state,
                        hour + " 点推出来了不该推的状态：" + state.label());
            }
        }
    }

    @Test
    @DisplayName("返回的集合不可变、不会在多次调用间串味")
    void resultIsIndependentPerCall() {
        Set<TravelState> lunch = inferrer.inferStates(LocalTime.of(12, 0));
        Set<TravelState> afternoon = inferrer.inferStates(LocalTime.of(15, 0));

        assertEquals(Set.of(TravelState.HUNGRY), lunch);
        assertEquals(Set.of(), afternoon);
        // 两次调用互不影响——如果内部复用了同一个可变集合，这里就会露馅
        assertTrue(lunch.contains(TravelState.HUNGRY));
    }
}
