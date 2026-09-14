package com.example.personality.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * <p>注意这里<b>没有</b>对用户名做格式校验（不像注册时那样限制字符集和长度）。
 * 原因是：登录时的校验规则一旦和注册时不一致，就会产生
 * 「注册能过、登录被前置校验拦掉」这种诡异现象。
 * 登录只要求非空，剩下的交给认证逻辑判断——反正密码错了也是同一个错。
 */
public record LoginRequest(

        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
