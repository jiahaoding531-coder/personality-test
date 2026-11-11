package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/** 当前用户尚未建立可复用的旅行画像，前端可据此进入旅行偏好测试。 */
public class TravelProfileRequiredException extends BusinessException {

    public static final String CODE = "TRAVEL_PROFILE_REQUIRED";

    public TravelProfileRequiredException(String message) {
        super(HttpStatus.NOT_FOUND, CODE, message);
    }
}
