package com.example.personality.dto;
import com.example.personality.domain.CoordinateSystem;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record RestaurantSearchRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull CoordinateSystem coordinateSystem,
        @Min(1) @Max(50000) Integer radius,
        @Min(value=1,message="页码不能小于 1") @Max(value=25,message="页码不能超过 25") Integer page,
        UUID searchSessionId) {
    public RestaurantSearchRequest(Double latitude, Double longitude, CoordinateSystem coordinateSystem, Integer radius, Integer page) {
        this(latitude,longitude,coordinateSystem,radius,page,null);
    }
    /** 保持已有 Java 调用兼容；HTTP 未传 page 时同样使用第一页。 */
    public RestaurantSearchRequest(Double latitude, Double longitude, CoordinateSystem coordinateSystem, Integer radius) {
        this(latitude,longitude,coordinateSystem,radius,null,null);
    }
    @Override public String toString() {
        return "RestaurantSearchRequest[coordinateSystem="+coordinateSystem+", position=已隐藏]";
    }
}
