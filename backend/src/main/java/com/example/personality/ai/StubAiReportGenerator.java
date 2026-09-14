package com.example.personality.ai;

import com.example.personality.dto.SessionResultResponse;
import com.example.personality.exception.NotImplementedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V0.1 的占位实现：调用即抛 501。
 *
 * <p>它存在的意义不是"以后可能有用"，而是让整条链路在编译期就是通的——
 * Controller、Service、接口、Bean 装配全都真实存在且能启动，
 * 唯一缺的只是一个方法体里的 HTTP 调用。
 *
 * <p>这叫"骨架先行"：把结构的坑先占住，后面的实现就只是填空，
 * 而不是"又要改架构"。V0.2 新增一个实现类即可，其余代码零改动。
 *
 * <p><b>条件装配</b>：本类和 {@link DeepSeekAiReportGenerator} 用同一个配置项
 * {@code app.ai.enabled} 的<b>正反两面</b>作为条件，保证容器里任何时候
 * 都<b>恰好有一个</b> {@code AiReportGenerator}：
 * <ul>
 *   <li>{@code enabled=true} → 装配 DeepSeek 实现</li>
 *   <li>{@code enabled=false} 或没配 → 装配本桩实现（{@code matchIfMissing = true}）</li>
 * </ul>
 *
 * <p>为什么不用 {@code @ConditionalOnMissingBean}？那个注解依赖 Bean 的
 * 注册顺序，在 {@code @Component} 上行为不稳定（在 {@code @Configuration}
 * 里才可靠）。用同一个属性的正反值来判断，结果是确定的，不依赖顺序。
 */
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "false", matchIfMissing = true)
public class StubAiReportGenerator implements AiReportGenerator {

    @Override
    public String generateReport(SessionResultResponse result) {
        throw new NotImplementedException(
                "AI 报告功能将在 V0.2 实现。当前为占位实现（stub），"
                        + "画像数据已准备就绪，共 " + result.dimensions().size() + " 个维度可供分析。");
    }

    @Override
    public String providerName() {
        return "stub";
    }
}
