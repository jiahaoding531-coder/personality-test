package com.example.personality.service;
import com.example.personality.domain.*;
import com.example.personality.dto.RestaurantSearchRequest;
import com.example.personality.exception.BehaviorRequestException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RestaurantSearchContextRegistryTest {
    final AccessScope scope=new AccessScope(1L,null);
    RestaurantSearchRequest request(int page) { return new RestaurantSearchRequest(33.735,113.3077,CoordinateSystem.WGS84,3000,page); }
    @Test void 三十分钟边界失效重启也不会静默重建旧来源() {
        var clock=new MutableClock(); var registry=new RestaurantSearchContextRegistry(clock); UUID id=UUID.randomUUID();
        registry.require(scope,id,request(1),false,false);
        clock.now=clock.now.plusSeconds(1799); registry.require(scope,id,request(2),true,false);
        clock.now=clock.now.plusSeconds(1);
        assertEquals(410,assertThrows(BehaviorRequestException.class,()->registry.require(scope,id,request(2),true,false)).status().value());
        assertEquals(410,assertThrows(BehaviorRequestException.class,()->new RestaurantSearchContextRegistry(clock).require(scope,id,request(1),true,false)).status().value());
    }
    @Test void 最多保留256个上下文且跨会话不可混用() {
        var registry=new RestaurantSearchContextRegistry(Clock.systemUTC()); UUID first=UUID.randomUUID();
        registry.require(scope,first,request(1),false,false);
        assertEquals(404,assertThrows(BehaviorRequestException.class,()->registry.require(new AccessScope(2L,null),first,request(2),true,false)).status().value());
        for(int i=0;i<256;i++) registry.require(scope,UUID.randomUUID(),request(1),false,false);
        assertEquals(410,assertThrows(BehaviorRequestException.class,()->registry.require(scope,first,request(2),true,false)).status().value());
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-09T00:00:00Z");
        public ZoneId getZone(){ return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone){ return this; }
        public Instant instant(){ return now; }
    }
}
