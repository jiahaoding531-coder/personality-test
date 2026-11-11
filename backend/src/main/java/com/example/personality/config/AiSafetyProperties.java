package com.example.personality.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** record 类似 Python dataclass；所有保护值由部署者配置，不接受前端传入。 */
@Validated
@ConfigurationProperties("app.ai.safety")
public record AiSafetyProperties(
        @DefaultValue("6") @Min(1) int perUserPerMinute,
        @DefaultValue("2") @Min(1) int maxConcurrent,
        @DefaultValue("100") @Min(0) long maxDailyRequests,
        @DefaultValue("300") @Min(0) long maxTotalRequests,
        @DefaultValue("200000") @Min(0) long maxDailyReservedTokens,
        @DefaultValue("1000000") @Min(0) long maxTotalReservedTokens,
        @DefaultValue("20000") @Min(1) int maxPromptCharacters,
        @DefaultValue("1024") @Min(1) int maxTrackedUsers) { }
