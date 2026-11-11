package com.example.personality.controller;

import com.example.personality.service.*;
import com.example.personality.dto.*;
import com.example.personality.exception.AmapRequestException;
import com.example.personality.security.AppUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel/sessions/{sessionId}/locations")
public class LocationSearchController {
    private final BehaviorAccessService access;
    private final ObjectProvider<AMapLocationSearchService> search;
    public LocationSearchController(BehaviorAccessService access,ObjectProvider<AMapLocationSearchService> search) {
        this.access=access; this.search=search;
    }
    @PostMapping("/search")
    public LocationSearchResponse search(@PathVariable Long sessionId,
            @RequestHeader(value="X-Session-Token",required=false) String token,
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody LocationSearchRequest request) {
        // 复用现有更严格的会话归属规则；AI 关闭与否不影响此接口。
        access.requireAccess(sessionId,token,principal);
        AMapLocationSearchService service=search.getIfAvailable();
        if(service==null) throw new AmapRequestException("AMAP_NOT_CONFIGURED");
        return service.search(request);
    }
}
