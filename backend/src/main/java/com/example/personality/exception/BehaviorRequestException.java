package com.example.personality.exception;
import org.springframework.http.HttpStatus;
/** 错误信息固定，不回显请求原文、坐标、令牌或密钥。 */
public class BehaviorRequestException extends BusinessException {
    public BehaviorRequestException(HttpStatus status, String code, String message) { super(status, code, message); }
    public static BehaviorRequestException invalid() { return new BehaviorRequestException(HttpStatus.BAD_REQUEST,"INVALID_BEHAVIOR_EVENT","行为事件参数不合法"); }
    public static BehaviorRequestException missing() { return new BehaviorRequestException(HttpStatus.NOT_FOUND,"BEHAVIOR_SOURCE_NOT_FOUND","行为来源不存在或已失效，请重新搜索"); }
    public static BehaviorRequestException conflict() { return new BehaviorRequestException(HttpStatus.CONFLICT,"BEHAVIOR_EVENT_CONFLICT","同一事件标识不能用于不同操作"); }
    public static BehaviorRequestException staleFeedback() { return new BehaviorRequestException(HttpStatus.CONFLICT,"STALE_FEEDBACK_VERSION","地点反馈已更新，请重新获取当前状态后再操作"); }
}
