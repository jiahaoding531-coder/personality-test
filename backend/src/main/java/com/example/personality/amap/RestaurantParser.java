package com.example.personality.amap;
import com.example.personality.dto.Restaurant;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;

/** 只映射上游事实，允许字段缺失、空数组及异常数字。 */
public final class RestaurantParser {
    private RestaurantParser() {}
    public static Restaurant parse(JsonNode node) {
        Double latitude=null, longitude=null;
        String location=text(node,"location");
        if(location!=null) {
            String[] parts=location.split(",");
            if(parts.length==2) {
                longitude=number(parts[0]); latitude=number(parts[1]);
                if(latitude==null || longitude==null || Math.abs(latitude)>90 || Math.abs(longitude)>180){
                    latitude=null; longitude=null;
                }
            }
        }
        Double distance=nonnegative(text(node,"distance"));
        // 向上取整，避免 1000.9 米被截断后误通过 1000 米的硬限制。
        Integer meters=distance!=null && distance<=Integer.MAX_VALUE ? (int)Math.ceil(distance):null;
        var business=node.path("business");
        String hours=text(business,"opentime_today");
        if(hours==null) hours=text(business,"opentime_week");
        var tags=new LinkedHashSet<String>();
        for(String field:List.of("tag","keytag","rectag")) {
            String value=text(business,field); if(value!=null) tags.add(value);
        }
        String image=null;
        if(node.path("photos").isArray()) for(var photo:node.path("photos")) {
            String url=text(photo,"url");
            if(url==null) continue;
            try {
                URI uri=URI.create(url);
                if("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null){
                    image=url; break;
                }
            } catch(IllegalArgumentException ignored) { /* 无效图片用占位状态 */ }
        }
        return new Restaurant(text(node,"id"),text(node,"name"),text(node,"type"),text(node,"typecode"),
            text(node,"address"),latitude,longitude,meters,nonnegative(text(business,"cost")),
            hours,text(business,"tel"),image,nonnegative(text(business,"rating")),
            tags.isEmpty()?null:List.copyOf(tags));
    }
    private static String text(JsonNode node,String field) {
        var value=node.path(field);
        if(!value.isString()) return null;
        String text=value.asString().trim();
        return text.isEmpty()?null:text;
    }
    private static Double nonnegative(String value) {
        Double number=number(value); return number!=null && number>=0?number:null;
    }
    private static Double number(String value) {
        if(value==null) return null;
        try { double number=Double.parseDouble(value); return Double.isFinite(number)?number:null; }
        catch(NumberFormatException e){return null;}
    }
}
