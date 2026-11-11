package com.example.personality.amap;

import com.example.personality.domain.CoordinateSystem;
import com.example.personality.domain.GeoCoordinate;
import com.example.personality.exception.CoordinateConversionException;
import com.example.personality.exception.AmapRequestException;
import tools.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/** 只负责转换。调用方必须等转换成功后再查询高德，禁止失败时使用原坐标。 */
@Component
@ConditionalOnProperty(name = "app.amap.enabled", havingValue = "true")
public class AmapCoordinateConverter {
    private final AmapClient client;

    public AmapCoordinateConverter(AmapClient client) {
        this.client = client;
    }

    public GeoCoordinate toGcj02(GeoCoordinate coordinate) {
        if (coordinate == null || coordinate.coordinateSystem() == CoordinateSystem.UNKNOWN) {
            throw new CoordinateConversionException(true);
        }
        if (coordinate.coordinateSystem() == CoordinateSystem.GCJ02) {
            return coordinate;
        }
        // 仅在接口边界按高德限制格式化至六位；不改变原始定位精度。
        String location = String.format(Locale.ROOT, "%.6f,%.6f",
                coordinate.longitude(), coordinate.latitude());
        JsonNode body;
        try {
            body = client.getRequired("/v3/assistant/coordinate/convert",
                    Map.of("locations", location, "coordsys", "gps"));
        } catch (AmapRequestException e) {
            if ("AMAP_SEARCH_FAILED".equals(e.code())) throw new CoordinateConversionException(false);
            throw e;
        }
        try {
            String[] parts = AmapClient.text(body, "locations").split(",", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new GeoCoordinate(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]),
                    CoordinateSystem.GCJ02);
        } catch (IllegalArgumentException e) {
            throw new CoordinateConversionException(false);
        }
    }
}
