package com.example.personality.service;
import com.example.personality.domain.AccessScope;
import com.example.personality.dto.*;
import com.example.personality.exception.AmapRequestException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** 三段式：短事务查归属 → 事务外调用高德 → 短事务保存来源，不进入景点引擎。 */
@Service
public class RestaurantSearchOrchestrator {
    private final ObjectProvider<AMapRestaurantSearchService> services;
    private final RestaurantSearchContextRegistry registry;
    private final RestaurantSearchPersistence persistence;
    public RestaurantSearchOrchestrator(ObjectProvider<AMapRestaurantSearchService> services,RestaurantSearchContextRegistry registry,RestaurantSearchPersistence persistence) {
        this.services=services; this.registry=registry; this.persistence=persistence;
    }
    public RestaurantSearchResponse search(AccessScope scope,RestaurantSearchRequest request) {
        var service=services.getIfAvailable();
        if(service==null) throw new AmapRequestException("AMAP_NOT_CONFIGURED");
        boolean legacy=request.searchSessionId()==null;
        UUID search=legacy?UUID.randomUUID():request.searchSessionId();
        registry.require(scope,search,request,persistence.known(scope,search),legacy);
        return persistence.save(scope,search,service.search(request));
    }
}
