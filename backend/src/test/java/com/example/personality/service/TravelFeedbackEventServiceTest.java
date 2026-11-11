package com.example.personality.service;

import com.example.personality.ai.AiCredentials;
import com.example.personality.ai.AiProvider;
import com.example.personality.ai.FeedbackPromptContext;
import com.example.personality.ai.TextFeedbackGenerator;
import com.example.personality.ai.TextFeedbackResult;
import com.example.personality.ai.TravelFeedbackKind;
import com.example.personality.ai.TravelFeedbackSignal;
import com.example.personality.ai.TravelIntentOperation;
import com.example.personality.domain.TravelDimension;
import com.example.personality.dto.TravelQuerySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 4：反馈事件服务的策略与三段式事务边界。 */
@ExtendWith(MockitoExtension.class)
class TravelFeedbackEventServiceTest {

    @Mock
    private TravelFeedbackPersistence persistence;

    @Mock
    private TextFeedbackGenerator generator;

    private final TravelQuerySnapshot query = new TravelQuerySnapshot(
            List.of(), List.of(), 120, 5000, 100,
            Map.of(TravelDimension.FOOD, 0.5));

    private final AiCredentials credentials = AiCredentials.forUser(
            AiProvider.DEEPSEEK, "test-key");

    @Test
    void computesConstraintSuggestionsFromTargetFacts() {
        assertThat(FeedbackConstraintPolicy.suggestCloserLimit(4200, null))
                .contains(3100);
        assertThat(FeedbackConstraintPolicy.suggestCloserLimit(4200, 2000))
                .contains(2000);
        assertThat(FeedbackConstraintPolicy.suggestCloserLimit(600, null))
                .isEmpty();
        assertThat(FeedbackConstraintPolicy.suggestCloserLimit(100_000, null))
                .contains(50_000);
        assertThat(FeedbackConstraintPolicy.suggestBudgetLimit(100, null))
                .contains(90);
        assertThat(FeedbackConstraintPolicy.suggestBudgetLimit(0, null))
                .isEmpty();
    }

    @Test
    void networkInterpretationRunsWithoutTransaction() {
        FeedbackTargetContext target = new FeedbackTargetContext(
                91L, "灵隐寺", 4200.0, 3100, 45, 40);
        when(persistence.loadTarget(7L, 91L, 30.24, 120.14, query))
                .thenReturn(target);
        when(generator.interpret(eq(credentials), any(FeedbackPromptContext.class)))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager
                            .isActualTransactionActive()).isFalse();
                    return new TextFeedbackResult(
                            TravelFeedbackKind.SESSION_ADJUSTMENT, null,
                            Set.of(TravelFeedbackSignal.TOO_FAR),
                            List.of(TravelIntentOperation.setConstraint(
                                    TravelIntentOperation.ConstraintKey.MAX_DISTANCE_METERS,
                                    3100)),
                            List.of(), "我把距离收近一些", true, false);
                });
        when(persistence.reactionState(91L)).thenReturn(new com.example.personality.dto.FeedbackState(null,0));
        when(persistence.saveEventWithState(any(SaveFeedbackEventCommand.class)))
                .thenAnswer(call -> new SavedFeedbackEvent(55L,call.<SaveFeedbackEventCommand>getArgument(0).result()));

        TravelFeedbackEventService service =
                new TravelFeedbackEventService(persistence, generator);

        SavedFeedbackEvent saved = service.create(
                7L, 91L, "这家太远了", query, 30.24, 120.14, credentials);

        assertThat(saved.eventId()).isEqualTo(55L);
        ArgumentCaptor<SaveFeedbackEventCommand> command =
                ArgumentCaptor.forClass(SaveFeedbackEventCommand.class);
        verify(persistence).saveEventWithState(command.capture());
        assertThat(command.getValue().sessionId()).isEqualTo(7L);
        assertThat(command.getValue().recommendationId()).isEqualTo(91L);
        assertThat(command.getValue().expectedFeedbackVersion()).isEqualTo(0L);
        assertThat(command.getValue().result().operations()).hasSize(1);
    }
}
