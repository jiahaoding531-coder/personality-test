package com.example.personality.ai;

/**
 * 反馈里的非操作性信号。
 *
 * <p>特别是 {@link #REQUEST_ALTERNATIVE}：它只表示用户要下一批推荐，
 * 绝不能伪造成任意一条状态操作。</p>
 */
public enum TravelFeedbackSignal {
    TOO_FAR,
    TOO_EXPENSIVE,
    REQUEST_ALTERNATIVE
}
