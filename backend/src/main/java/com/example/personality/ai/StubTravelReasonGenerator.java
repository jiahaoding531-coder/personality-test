package com.example.personality.ai;

import com.example.personality.exception.NotImplementedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 没有配置 AI 时的占位实现：调用即抛 501。
 *
 * <p>和 {@link StubAiReportGenerator} 一样，它存在的意义不是"以后可能有用"，
 * 而是让整条链路在没有外部依赖时也是通的——Controller、Service、接口、
 * Bean 装配全都真实存在且能启动。
 *
 * <p><b>前端靠这个 501 来决定把 AI 入口藏起来。</b>别人 clone 这个仓库、
 * 不配任何 key 直接跑，推荐功能完全正常，只是卡片上没有 AI 那段话——
 * 而不是看到一个坏掉的功能或者一堆报错。这就是"默认关掉"的意义。
 *
 * <p>条件装配：本类和 {@link DeepSeekTravelReasonGenerator} 用同一个配置项
 * {@code app.ai.enabled} 的<b>正反两面</b>，保证容器里任何时候都
 * <b>恰好有一个</b> {@code TravelReasonGenerator}。
 * 不用 {@code @ConditionalOnMissingBean} 的理由见 {@link StubAiReportGenerator}。
 */
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "false", matchIfMissing = true)
public class StubTravelReasonGenerator implements TravelReasonGenerator {

    @Override
    public List<RankedReason> generateReasons(TravelReasonInput input) {
        throw new NotImplementedException(
                "AI 推荐理由未启用。当前为占位实现（stub），"
                        + "打分依据已准备就绪，共 " + input.places().size() + " 个地点可供解读。"
                        + "启用方式：设置 AI_ENABLED=true 和 DEEPSEEK_API_KEY 后重启。");
    }

    @Override
    public String providerName() {
        return "stub";
    }
}
