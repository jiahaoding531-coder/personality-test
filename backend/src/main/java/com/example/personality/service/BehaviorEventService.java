package com.example.personality.service;
import com.example.personality.domain.*;
import com.example.personality.dto.BehaviorReceipt;
import com.example.personality.exception.BehaviorRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;

@Service
public class BehaviorEventService {
    private final BehaviorEventStore store;
    private final BehaviorTargetValidator validator;
    private final Clock clock;
    public BehaviorEventService(BehaviorEventStore store,BehaviorTargetValidator validator,Clock clock) {
        this.store=store; this.validator=validator; this.clock=clock;
    }
    /** @Transactional 相当于 setAutoCommit(false)，任何运行时异常会整体 rollback。 */
    @Transactional
    public BehaviorReceipt record(AccessScope scope,BehaviorEventCommand command) {
        command.validate(false);
        validator.validateScope(scope);
        // 先比较旧事件，再验证新来源和时间；快照过期不会破坏已有事件的幂等。
        if(store.existing(scope,command)) return new BehaviorReceipt(command.eventId(),true);
        validateNew(scope,command);
        return new BehaviorReceipt(command.eventId(),!store.insert(scope,command));
    }
    public void validateNew(AccessScope scope,BehaviorEventCommand command) {
        var now=clock.instant();
        if(command.occurredAt().isBefore(now.minus(Duration.ofDays(7))) || command.occurredAt().isAfter(now.plusSeconds(300)))
            throw BehaviorRequestException.invalid();
        validator.validate(scope,command);
    }
}
