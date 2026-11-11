package com.example.personality.amap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class RestaurantParserTest {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void 完整真实字段解析() {
        var r=RestaurantParser.parse(json.readTree("""
            {"id":"B123","name":"测试餐饮","type":"餐饮服务;中餐厅","typecode":"050100",
             "address":"测试路","location":"113.307408,33.734722","distance":"41",
             "business":{"cost":"18.00","opentime_today":"10:00-20:00","tel":"123",
                         "rating":"4.1","tag":"中餐","keytag":"小吃"},
             "photos":[{"url":"https://example.com/photo.jpg"}]}
            """));
        assertEquals("B123",r.poiId());
        assertEquals("050100",r.typeCode());
        assertEquals(33.734722,r.latitude());
        assertEquals(113.307408,r.longitude());
        assertEquals(41,r.distanceMeters());
        assertEquals(18.0,r.averageCost());
        assertEquals("10:00-20:00",r.openingHoursText());
        assertEquals("123",r.telephone());
        assertEquals(4.1,r.rating());
        assertEquals(2,r.tags().size());
        assertEquals("https://example.com/photo.jpg",r.imageUrl());
    }
    @Test void 缺失字段返回空而不是虚构默认值() {
        var r=RestaurantParser.parse(json.readTree("{\"id\":\"B123\",\"name\":\"餐饮\"}"));
        assertNull(r.averageCost()); assertNull(r.openingHoursText()); assertNull(r.imageUrl());
        assertNull(r.telephone()); assertNull(r.rating()); assertNull(r.tags());
        assertNull(r.latitude()); assertNull(r.longitude()); assertNull(r.distanceMeters());
    }
    @Test void 空数组空串无效数值和不安全图片均为空() {
        var r=RestaurantParser.parse(json.readTree("""
            {"location":"NaN,91","distance":"-1","business":{"cost":[],"rating":"NaN","tel":""},
             "photos":[{"url":"http://example.com/a.jpg"}]}
            """));
        assertNull(r.latitude()); assertNull(r.averageCost()); assertNull(r.rating());
        assertNull(r.telephone()); assertNull(r.distanceMeters()); assertNull(r.imageUrl());
    }
    @Test void 周营业文本兜底且零元与缺失区分() {
        var r=RestaurantParser.parse(json.readTree("""
            {"business":{"cost":"0","opentime_week":"周一至周日 09:00-21:00"}}
            """));
        assertEquals(0.0,r.averageCost());
        assertEquals("周一至周日 09:00-21:00",r.openingHoursText());
    }
}
