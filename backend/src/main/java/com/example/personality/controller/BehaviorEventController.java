package com.example.personality.controller;
import com.example.personality.domain.BehaviorEventCommand;
import com.example.personality.dto.BehaviorReceipt;
import com.example.personality.exception.BehaviorRequestException;
import com.example.personality.security.AppUserPrincipal;
import com.example.personality.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/travel/sessions/{sessionId}/behavior-events")
public class BehaviorEventController {
    private final BehaviorAccessService access;
    private final BehaviorEventService events;
    private final ObjectMapper mapper;
    public BehaviorEventController(BehaviorAccessService access,BehaviorEventService events,ObjectMapper mapper) {
        this.access=access; this.events=events; this.mapper=mapper;
    }
    @PostMapping
    public ResponseEntity<BehaviorReceipt> record(@PathVariable Long sessionId,
            @RequestHeader(value="X-Session-Token",required=false) String token,
            @AuthenticationPrincipal AppUserPrincipal principal,HttpServletRequest request) {
        var scope=access.requireAccess(sessionId,token,principal);
        BehaviorEventCommand command;
        // 先授权再读体，未知字段拒绝，且限制大小；不把原文放入日志或错误消息。
        try {
            if(request.getContentType()==null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())))
                throw BehaviorRequestException.invalid();
            byte[] body=request.getInputStream().readNBytes(16385);
            if(body.length>16384) throw BehaviorRequestException.invalid();
            command=mapper.readerFor(BehaviorEventCommand.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);
            if(command==null) throw BehaviorRequestException.invalid();
        } catch(IOException | IllegalArgumentException | tools.jackson.core.JacksonException e) { throw BehaviorRequestException.invalid(); }
        var receipt=events.record(scope,command);
        return ResponseEntity.status(receipt.duplicate()?HttpStatus.OK:HttpStatus.CREATED).body(receipt);
    }
}
