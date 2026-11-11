package com.example.personality.dto;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 反馈发生时客户端正在使用的旅行查询快照。
 *
 * <p>它只用于提示词和事件留痕，不直接替换服务端状态；真正应用 operations
 * 仍由前端 reducer 完成。</p>
 */
public record TravelQuerySnapshot(
        @NotNull @Size(max = 2) List<TravelState> intents,
        @NotNull @Size(max = 7) List<TravelState> preferences,
        @Min(15) @Max(1440) Integer durationMinutes,
        @Min(500) @Max(50000) Integer maxDistanceMeters,
        @Min(0) @Max(1000) Integer budgetMax,
        @NotNull @Size(max = 8) Map<TravelDimension, Double> biases) {
}
