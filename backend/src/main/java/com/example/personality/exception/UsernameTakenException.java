package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/**
 * 注册时用户名已被占用，映射为 HTTP 409。
 *
 * <p>用 409 Conflict 而不是 400：请求本身是合法的（用户名格式没问题），
 * 只是和服务器当前状态冲突了。语义更准确，前端也能据此把焦点
 * 直接定位到用户名输入框。
 */
public class UsernameTakenException extends BusinessException {

    public UsernameTakenException(String username) {
        super(HttpStatus.CONFLICT, "用户名「" + username + "」已被占用，换一个试试");
    }
}
