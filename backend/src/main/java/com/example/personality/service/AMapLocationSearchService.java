package com.example.personality.service;

import com.example.personality.amap.AmapClient;
import com.example.personality.dto.*;
import com.example.personality.exception.AmapRequestException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** 关键词查真实 POI，不使用逆地理编码，不接触景点引擎或 places 表。 */
@Service
@ConditionalOnProperty(name="app.amap.enabled", havingValue="true")
public class AMapLocationSearchService {
    private final AmapClient client;
    public AMapLocationSearchService(AmapClient client) { this.client=client; }

    // 没有 @Transactional：外部 HTTP 不占用数据库事务连接。
    public LocationSearchResponse search(LocationSearchRequest request) {
        String keyword=request.keyword();
        if(keyword==null || keyword.isBlank() || keyword.length()>80)
            throw new IllegalArgumentException("请输入 1–80 字的地点名称");
        JsonNode body=client.getRequired("/v5/place/text",Map.of(
                "keywords",keyword.trim(),"page_size","10","page_num","1"));
        if(!body.path("pois").isArray()) throw new AmapRequestException("AMAP_SEARCH_FAILED");
        List<LocationCandidate> candidates=new ArrayList<>();
        Set<String> known=new HashSet<>();
        for(JsonNode poi:body.path("pois")) {
            String name=text(poi,"name"), coordinate=text(poi,"location");
            if(name==null || coordinate==null) continue;
            String[] pair=coordinate.split(",",-1);
            if(pair.length!=2) continue;
            double longitude,latitude;
            try { longitude=Double.parseDouble(pair[0]); latitude=Double.parseDouble(pair[1]); }
            catch(NumberFormatException ignored) { continue; }
            if(!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude)>90 || Math.abs(longitude)>180) continue;
            String id=text(poi,"id");
            String identity=id!=null ? id : name+":"+coordinate;
            if(!known.add(identity)) continue;
            candidates.add(new LocationCandidate(id,name,text(poi,"address"),text(poi,"pname"),
                    text(poi,"cityname"),text(poi,"adname"),latitude,longitude,"GCJ02","AMAP_POI"));
            if(candidates.size()==10) break;
        }
        return new LocationSearchResponse(List.copyOf(candidates));
    }

    private static String text(JsonNode node,String field) {
        JsonNode value=node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString().trim() : null;
    }
}
