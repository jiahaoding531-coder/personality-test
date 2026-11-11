package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/** 通过现有统一错误响应返回，不携带上游请求地址或坐标。 */
public class CoordinateConversionException extends BusinessException {
    public CoordinateConversionException(boolean unknownSystem) {
        super(unknownSystem ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
                unknownSystem ? "COORDINATE_SYSTEM_UNKNOWN" : "COORDINATE_CONVERSION_FAILED",
                unknownSystem ? "此位置的坐标系尚未确认，请使用手机定位或确认手动坐标来源。"
                        : "位置转换失败，请稍后重试；本次未继续搜索餐饮。");
    }
}
