package com.example.personality.service;
import com.example.personality.domain.AccessScope;
import com.example.personality.config.BehaviorRetentionPolicy;
import com.example.personality.dto.RestaurantSearchResponse;
import com.example.personality.exception.BehaviorRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class RestaurantSearchPersistence {
    private final JdbcTemplate jdbc;
    private final BehaviorTargetValidator validator;
    private final Clock clock;
    private final BehaviorRetentionPolicy policy;
    public RestaurantSearchPersistence(JdbcTemplate jdbc,BehaviorTargetValidator validator,Clock clock,BehaviorRetentionPolicy policy) { this.jdbc=jdbc; this.validator=validator; this.clock=clock; this.policy=policy; }
    @Transactional(readOnly=true)
    public boolean known(AccessScope scope,UUID search) {
        var rows=jdbc.query("SELECT DISTINCT session_id,user_id FROM restaurant_search_batches WHERE search_session_id=?",
                (rs,n)->new AccessScope(rs.getLong(1),(Long)rs.getObject(2)),search);
        if(rows.stream().anyMatch(owner->!owner.equals(scope))) throw BehaviorRequestException.missing();
        return !rows.isEmpty();
    }
    @Transactional
    public RestaurantSearchResponse save(AccessScope scope,UUID search,RestaurantSearchResponse response) {
        validator.validateScope(scope);
        UUID result=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO restaurant_search_batches(result_set_id,search_session_id,session_id,user_id,page,radius,status,created_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,result,search,scope.sessionId(),scope.userId(),response.currentPage(),response.radius(),response.status(),
                Timestamp.from(clock.instant()),Timestamp.from(clock.instant().plus(Duration.ofDays(policy.snapshotDays()))));
        int rank=0;
        for(var item:response.restaurants()) {
            jdbc.update("""
                    INSERT INTO restaurant_search_items(result_set_id,poi_id,name,type,type_code,address,latitude,longitude,distance_meters,rank_no)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,result,item.poiId(),item.name(),item.type(),item.typeCode(),item.address(),item.latitude(),item.longitude(),item.distanceMeters(),++rank);
        }
        return new RestaurantSearchResponse(search,response.timestamp(),response.status(),response.radius(),response.coordinateSystem(),
                response.distanceType(),response.restaurants(),response.currentPage(),result);
    }
}
