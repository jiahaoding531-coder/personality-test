package com.example.personality.dto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** searchSessionId 与 timestamp 为未来行为关联预留，本阶段不保存学习数据。 */
public record RestaurantSearchResponse(UUID searchSessionId, Instant timestamp, String status,
        int radius, String coordinateSystem, String distanceType, List<Restaurant> restaurants, int currentPage, UUID resultSetId) {
    public RestaurantSearchResponse(UUID searchSessionId, Instant timestamp, String status, int radius,
            String coordinateSystem, String distanceType, List<Restaurant> restaurants, int currentPage) {
        this(searchSessionId,timestamp,status,radius,coordinateSystem,distanceType,restaurants,currentPage,null);
    }
    public RestaurantSearchResponse(UUID searchSessionId, Instant timestamp, String status, int radius,
            String coordinateSystem, String distanceType, List<Restaurant> restaurants) {
        this(searchSessionId,timestamp,status,radius,coordinateSystem,distanceType,restaurants,1);
    }
}
