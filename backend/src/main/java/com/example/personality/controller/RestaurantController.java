package com.example.personality.controller;
import com.example.personality.dto.*;
import com.example.personality.exception.AmapRequestException;
import com.example.personality.security.*;
import com.example.personality.service.BehaviorAccessService;
import com.example.personality.service.RestaurantSearchOrchestrator;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 餐饮接口独立于景点控制器，先校验会话归属，再调用高德。 */
@RestController
@RequestMapping("/api/travel/sessions/{sessionId}/restaurants")
public class RestaurantController {
    private final BehaviorAccessService access;
    private final RestaurantSearchOrchestrator searches;
    public RestaurantController(BehaviorAccessService access,RestaurantSearchOrchestrator searches){
        this.access=access; this.searches=searches;
    }
    @PostMapping("/search")
    public RestaurantSearchResponse search(@PathVariable Long sessionId,
            @RequestHeader(value="X-Session-Token",required=false) String token,
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody RestaurantSearchRequest request){
        var scope=access.requireAccess(sessionId,token,principal);
        return searches.search(scope,request);
    }
}
