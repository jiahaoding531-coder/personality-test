package com.example.personality.service;

import com.example.personality.domain.AccessScope;
import com.example.personality.dto.RestaurantSearchRequest;
import com.example.personality.exception.BehaviorRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** 仅在内存保存带随机密钥的摘要；不保留原始搜索中心，重启后显式失效。 */
@Component
public class RestaurantSearchContextRegistry {
    private final Clock clock;
    private final byte[] secret=new byte[32];
    private final LinkedHashMap<UUID,Context> contexts=new LinkedHashMap<>();
    public RestaurantSearchContextRegistry(Clock clock) { this.clock=clock; new SecureRandom().nextBytes(secret); }
    public synchronized void require(AccessScope scope,UUID id,RestaurantSearchRequest request,boolean previouslyPersisted,boolean legacy) {
        String digest=digest(request);
        Context context=contexts.get(id);
        if(context!=null) {
            if(!context.scope().equals(scope)) throw BehaviorRequestException.missing();
            if(!clock.instant().isBefore(context.expiresAt())) { contexts.remove(id); throw expired(); }
            if(!context.digest().equals(digest)) throw new BehaviorRequestException(HttpStatus.CONFLICT,"RESTAURANT_CONTEXT_CHANGED","搜索条件已改变，请重新搜索");
            return;
        }
        if(previouslyPersisted || (!legacy && request.page()!=null && request.page()!=1)) throw expired();
        contexts.entrySet().removeIf(entry->!clock.instant().isBefore(entry.getValue().expiresAt()));
        if(contexts.size()>=256) contexts.remove(contexts.keySet().iterator().next());
        contexts.put(id,new Context(scope,digest,clock.instant().plus(Duration.ofMinutes(30))));
    }
    private String digest(RestaurantSearchRequest request) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            String value=Double.toString(request.latitude())+"|"+Double.toString(request.longitude())+"|"
                    +request.coordinateSystem().name()+"|"+(request.radius()==null?3000:request.radius());
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException impossible) { throw new IllegalStateException("无法创建安全上下文摘要",impossible); }
    }
    private static BehaviorRequestException expired() { return new BehaviorRequestException(HttpStatus.GONE,"RESTAURANT_CONTEXT_EXPIRED","搜索上下文已失效，请重新搜索"); }
    private record Context(AccessScope scope,String digest,Instant expiresAt) {}
}
