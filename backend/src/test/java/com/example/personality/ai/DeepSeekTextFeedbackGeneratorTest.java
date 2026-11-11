package com.example.personality.ai;

import com.example.personality.domain.TravelState;
import com.example.personality.entity.RecommendationFeedback;
import com.example.personality.exception.AiServiceException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型反馈 JSON 的严格边界测试。
 *
 * <p>每个测试都使用真实解析函数，不联网、不需要 key。它们保护的不是模型输出是否优雅，
 * 而是模型无法越过服务端已经算好的数值和操作协议边界。</p>
 */
class DeepSeekTextFeedbackGeneratorTest {

    private static final FeedbackPromptContext CONTEXT = new FeedbackPromptContext(
            "这家太远了，想安静一点", "灵隐寺", 4200, 3000,
            45, 40, "当前想吃饭，距离上限 10 公里");

    @Test
    void parsesMixedFeedbackAndRecomputesRefreshFlags() {
        TextFeedbackResult result = DeepSeekTextFeedbackGenerator.parse("""
                {"kind":"MIXED","reaction":"LIKE","signals":["TOO_FAR"],
                 "operations":[
                   {"op":"SET_CONSTRAINT","key":"maxDistanceMeters","value":3000},
                   {"op":"ADD_PREFERENCE","value":"QUIET"}
                 ],"unrecognized":[],"summary":"喜欢这里但想近一点",
                 "shouldRecommend":false,"excludeSeen":true}
                """, CONTEXT);

        assertThat(result.kind()).isEqualTo(TravelFeedbackKind.MIXED);
        assertThat(result.reaction()).isEqualTo(RecommendationFeedback.Reaction.LIKE);
        assertThat(result.signals()).containsExactly(TravelFeedbackSignal.TOO_FAR);
        assertThat(result.operations()).containsExactly(
                TravelIntentOperation.setConstraint(
                        TravelIntentOperation.ConstraintKey.MAX_DISTANCE_METERS, 3000),
                TravelIntentOperation.addPreference(TravelState.QUIET));
        assertThat(result.shouldRecommend()).isTrue();
        assertThat(result.excludeSeen()).isFalse();
    }

    @Test
    void ignoresUnknownReactionAndDerivesConservativeKindFromOperations() {
        TextFeedbackResult result = DeepSeekTextFeedbackGenerator.parse("""
                {"kind":"NOT_A_KIND","reaction":"MAYBE","signals":[],
                 "operations":[{"op":"ADD_PREFERENCE","value":"QUIET"}],
                 "unrecognized":[],"summary":"安静一点","shouldRecommend":false}
                """, CONTEXT);

        assertThat(result.reaction()).isNull();
        assertThat(result.kind()).isEqualTo(TravelFeedbackKind.SESSION_ADJUSTMENT);
        assertThat(result.operations()).containsExactly(TravelIntentOperation.addPreference(TravelState.QUIET));
        assertThat(result.shouldRecommend()).isTrue();
    }

    @Test
    void dropsIllegalOperationButKeepsOtherValidOperations() {
        TextFeedbackResult result = DeepSeekTextFeedbackGenerator.parse("""
                {"kind":"SESSION_ADJUSTMENT","reaction":null,"signals":[],
                 "operations":[
                   {"op":"TELEPORT","value":"QUIET"},
                   {"op":"ADD_PREFERENCE","value":"QUIET"}
                 ],"unrecognized":[],"summary":"安静一点"}
                """, CONTEXT);

        assertThat(result.operations()).containsExactly(TravelIntentOperation.addPreference(TravelState.QUIET));
    }

    @Test
    void dropsInventedDistanceButKeepsValidPreference() {
        TextFeedbackResult result = DeepSeekTextFeedbackGenerator.parse("""
                {"kind":"SESSION_ADJUSTMENT","reaction":null,
                 "signals":["TOO_FAR"],"operations":[
                   {"op":"SET_CONSTRAINT","key":"maxDistanceMeters","value":9999},
                   {"op":"ADD_PREFERENCE","value":"QUIET"}
                 ],"unrecognized":[],"summary":"收近一点并找安静的","shouldRecommend":true}
                """, CONTEXT);

        assertThat(result.operations()).containsExactly(
                TravelIntentOperation.addPreference(TravelState.QUIET));
    }

    @Test
    void requestAlternativeIsSignalNotOperationAndForcesExcludeSeen() {
        TextFeedbackResult result = DeepSeekTextFeedbackGenerator.parse("""
                {"kind":"SESSION_ADJUSTMENT","reaction":null,
                 "signals":["REQUEST_ALTERNATIVE","UNKNOWN"],"operations":[],
                 "unrecognized":[],"summary":"换一个吧","shouldRecommend":false,"excludeSeen":false}
                """, CONTEXT);

        assertThat(result.signals()).containsExactly(TravelFeedbackSignal.REQUEST_ALTERNATIVE);
        assertThat(result.operations()).isEmpty();
        assertThat(result.shouldRecommend()).isTrue();
        assertThat(result.excludeSeen()).isTrue();
    }

    @Test
    void pureAlternativeRequestDropsModelInjectedSessionOperation() {
        TextFeedbackResult result = DeepSeekTextFeedbackGenerator.parse("""
                {"kind":"SESSION_ADJUSTMENT","reaction":null,
                 "signals":["REQUEST_ALTERNATIVE"],
                 "operations":[{"op":"CLEAR_TRAVEL_INTENT"}],
                 "unrecognized":[],"summary":"换一个吧"}
                """, new FeedbackPromptContext(
                "换一个吧", "灵隐寺", 4200, 3000,
                45, 40, "当前想吃饭，距离上限 10 公里"));

        assertThat(result.signals()).containsExactly(TravelFeedbackSignal.REQUEST_ALTERNATIVE);
        assertThat(result.operations()).isEmpty();
        assertThat(result.shouldRecommend()).isTrue();
        assertThat(result.excludeSeen()).isTrue();
    }

    @Test
    void pureAlternativeRequestRepairsMissingSignalAndDropsInjectedOperation() {
        TextFeedbackResult result = DeepSeekTextFeedbackGenerator.parse("""
                {"kind":"SESSION_ADJUSTMENT","reaction":null,"signals":[],
                 "operations":[{"op":"CLEAR_TRAVEL_INTENT"}],
                 "unrecognized":[],"summary":"换一家吧"}
                """, new FeedbackPromptContext(
                "换一家吧", "灵隐寺", 4200, 3000,
                45, 40, "当前想吃饭，距离上限 10 公里"));

        assertThat(result.signals()).containsExactly(TravelFeedbackSignal.REQUEST_ALTERNATIVE);
        assertThat(result.operations()).isEmpty();
        assertThat(result.shouldRecommend()).isTrue();
        assertThat(result.excludeSeen()).isTrue();
    }

    @Test
    void emptyOperationsDoNotTrustModelRefreshFlags() {
        TextFeedbackResult result = DeepSeekTextFeedbackGenerator.parse("""
                {"kind":"SESSION_ADJUSTMENT","reaction":null,"signals":[],"operations":[],
                 "unrecognized":[],"summary":"暂时没有调整","shouldRecommend":true,"excludeSeen":true}
                """, CONTEXT);

        assertThat(result.shouldRecommend()).isFalse();
        assertThat(result.excludeSeen()).isFalse();
    }

    @Test
    void brokenJsonThrowsAiServiceException() {
        assertThatThrownBy(() -> DeepSeekTextFeedbackGenerator.parse("这家太远了", CONTEXT))
                .isInstanceOf(AiServiceException.class);
    }

    @Test
    void jsonObjectWithTrailingTokensThrowsAiServiceException() {
        assertThatThrownBy(() -> DeepSeekTextFeedbackGenerator.parse(
                "{\"operations\":[]} trailing", CONTEXT))
                .isInstanceOf(AiServiceException.class);
    }

    @ParameterizedTest
    @MethodSource("nonObjectJson")
    void nonObjectJsonThrowsAiServiceException(String content) {
        assertThatThrownBy(() -> DeepSeekTextFeedbackGenerator.parse(content, CONTEXT))
                .isInstanceOf(AiServiceException.class);
    }

    private static Stream<String> nonObjectJson() {
        return Stream.of(null, "```json\n\n```", "null", "[]", "\"文本\"", "123");
    }

    @Test
    void invalidJsonWarningDoesNotContainModelContent() {
        String untrustedContent = "模型原文绝不能进入日志";
        Logger logger = (Logger) LoggerFactory.getLogger(DeepSeekTextFeedbackGenerator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> DeepSeekTextFeedbackGenerator.parse(untrustedContent, CONTEXT))
                    .isInstanceOf(AiServiceException.class);
            assertThat(appender.list)
                    .allSatisfy(event -> assertThat(event.getFormattedMessage())
                            .doesNotContain(untrustedContent));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
