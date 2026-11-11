package com.example.personality.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 针对推荐结果的一次自然语言反馈请求。 */
public record FeedbackEventRequest(
        @NotBlank @Size(max = 200) String text,
        Long recommendationId,
        @NotNull @Valid TravelQuerySnapshot query,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @jakarta.validation.constraints.Min(0) Long expectedFeedbackVersion) {
    public FeedbackEventRequest(String text,Long recommendationId,TravelQuerySnapshot query,Double latitude,Double longitude) {
        this(text,recommendationId,query,latitude,longitude,null);
    }
}
