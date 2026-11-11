package com.example.personality.amap;

import com.example.personality.domain.CoordinateSystem;
import com.example.personality.domain.GeoCoordinate;
import com.example.personality.exception.CoordinateConversionException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import com.example.personality.exception.AmapRequestException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 坐标边界测试使用公开测试点，不调用真实高德或记录用户位置。 */
class AmapCoordinateConverterTest {
    private final AmapClient client = mock(AmapClient.class);
    private final AmapCoordinateConverter converter = new AmapCoordinateConverter(client);

    @Test
    void wgs84成功转换并标记高德坐标() {
        when(client.getRequired(anyString(), anyMap())).thenReturn(JsonMapper.builder().build()
                .readTree("{\"locations\":\"113.313,33.733\"}"));
        GeoCoordinate input = new GeoCoordinate(33.735012345, 113.307712345, CoordinateSystem.WGS84);
        GeoCoordinate converted = converter.toGcj02(input);
        assertEquals(new GeoCoordinate(33.733, 113.313, CoordinateSystem.GCJ02), converted);
        assertEquals(33.735012345, input.latitude());
        verify(client).getRequired("/v3/assistant/coordinate/convert",
                Map.of("locations", "113.307712,33.735012", "coordsys", "gps"));
        assertSame(converted, converter.toGcj02(converted));
        verifyNoMoreInteractions(client);
    }

    @Test
    void gcj02直接返回且不调用转换接口() {
        GeoCoordinate input = new GeoCoordinate(33.735, 113.3077, CoordinateSystem.GCJ02);
        assertSame(input, converter.toGcj02(input));
        verifyNoInteractions(client);
    }

    @Test
    void 未确认坐标系不能默认为高德坐标() {
        var error = assertThrows(CoordinateConversionException.class,
                () -> converter.toGcj02(new GeoCoordinate(33.735, 113.3077, CoordinateSystem.UNKNOWN)));
        assertEquals("COORDINATE_SYSTEM_UNKNOWN", error.code());
        verifyNoInteractions(client);
    }

    @Test
    void 上游转换失败不能返回原坐标() {
        when(client.getRequired(anyString(), anyMap())).thenThrow(new AmapRequestException("AMAP_SEARCH_FAILED"));
        var error = assertThrows(CoordinateConversionException.class,
                () -> converter.toGcj02(new GeoCoordinate(33.735, 113.3077, CoordinateSystem.WGS84)));
        assertEquals("COORDINATE_CONVERSION_FAILED", error.code());
    }

    @Test
    void 错误响应坐标必须拒绝() {
        for (String value : new String[]{"", "NaN,33", "113,91", "113,33;114,34", "wrong"}) {
            when(client.getRequired(anyString(), anyMap())).thenReturn(JsonMapper.builder().build()
                    .readTree("{\"locations\":\"" + value + "\"}"));
            assertThrows(CoordinateConversionException.class,
                    () -> converter.toGcj02(new GeoCoordinate(33, 113, CoordinateSystem.WGS84)));
        }
    }

    @Test
    void 坐标契约拒绝越界非有限值和缺失坐标系() {
        assertThrows(IllegalArgumentException.class, () -> new GeoCoordinate(91, 113, CoordinateSystem.WGS84));
        assertThrows(IllegalArgumentException.class, () -> new GeoCoordinate(33, 181, CoordinateSystem.WGS84));
        assertThrows(IllegalArgumentException.class, () -> new GeoCoordinate(Double.NaN, 113, CoordinateSystem.WGS84));
        assertThrows(IllegalArgumentException.class, () -> new GeoCoordinate(33, Double.POSITIVE_INFINITY, CoordinateSystem.WGS84));
        assertThrows(IllegalArgumentException.class, () -> new GeoCoordinate(33, 113, null));
    }

    @Test
    void 坐标对象字符串不泄露精确位置() {
        String text = new GeoCoordinate(33.735012345, 113.307712345, CoordinateSystem.WGS84).toString();
        assertFalse(text.contains("33.735"));
        assertFalse(text.contains("113.307"));
    }
}
