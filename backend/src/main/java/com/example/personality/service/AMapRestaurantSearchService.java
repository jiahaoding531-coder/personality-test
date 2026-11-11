package com.example.personality.service;
import com.example.personality.amap.*;
import com.example.personality.domain.GeoCoordinate;
import com.example.personality.dto.*;
import com.example.personality.exception.AmapRequestException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** 独立餐饮搜索，无本地点仓库依赖，也不在数据库事务内调用外部服务。 */
@Service
@ConditionalOnProperty(name="app.amap.enabled",havingValue="true")
public class AMapRestaurantSearchService {
    private final AmapClient client;
    private final AmapCoordinateConverter converter;
    public AMapRestaurantSearchService(AmapClient client,AmapCoordinateConverter converter){
        this.client=client; this.converter=converter;
    }
    public RestaurantSearchResponse search(RestaurantSearchRequest request){
        int radius=request.radius()==null?3000:request.radius();
        int page=request.page()==null?1:request.page();
        if(page<1 || page>25) throw new IllegalArgumentException("页码应为 1 至 25");
        if(radius<1 || radius>50000) throw new IllegalArgumentException("搜索半径应为 1 至 50000 米");
        var position=converter.toGcj02(new GeoCoordinate(request.latitude(),request.longitude(),request.coordinateSystem()));
        var body=client.getRequired("/v5/place/around",Map.of(
            "location",String.format(Locale.ROOT,"%.6f,%.6f",position.longitude(),position.latitude()),
            "types","050000","sortrule","distance","radius",Integer.toString(radius),
            "page_size","10","page_num",Integer.toString(page),"show_fields","business,photos"));
        if(!body.path("pois").isArray()) throw new AmapRequestException("AMAP_SEARCH_FAILED");
        List<Restaurant> restaurants=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(var node:body.path("pois")){
            Restaurant restaurant=RestaurantParser.parse(node);
            // 缺少核心标识、坐标或距离的记录无法安全导航或遵守距离限制，予以排除。
            if(restaurant.poiId()==null || restaurant.name()==null || restaurant.latitude()==null
                || restaurant.longitude()==null || restaurant.distanceMeters()==null
                || restaurant.distanceMeters()>radius || !seen.add(restaurant.poiId())) continue;
            restaurants.add(restaurant);
        }
        restaurants.sort(Comparator.comparingInt(Restaurant::distanceMeters));
        return new RestaurantSearchResponse(UUID.randomUUID(),Instant.now(),
            restaurants.isEmpty()?"NO_RESULTS":"SUCCESS",radius,"GCJ02","STRAIGHT_LINE",List.copyOf(restaurants),page);
    }
}
