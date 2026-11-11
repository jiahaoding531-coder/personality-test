package com.example.personality.exception;
import org.springframework.http.HttpStatus;

/** 只公开确定的错误分类，不附带可能包含 Key 的上游异常或 URL。 */
public class AmapRequestException extends BusinessException {
    public AmapRequestException(String code) {
        super(code.equals("AMAP_QUOTA_EXCEEDED") || code.equals("AMAP_NOT_CONFIGURED")
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY, code, switch(code) {
            case "AMAP_PERMISSION_DENIED" -> "高德服务权限或密钥配置异常，请联系维护者。";
            case "AMAP_QUOTA_EXCEEDED" -> "高德服务调用额度或频率已达限制，请稍后重试。";
            case "AMAP_NETWORK_ERROR" -> "暂时无法连接高德服务，请稍后重试。";
            case "AMAP_NOT_CONFIGURED" -> "高德地点服务尚未启用。";
            default -> "高德地点查询未成功，请稍后重试。";
        });
    }
}
