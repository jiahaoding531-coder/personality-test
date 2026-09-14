package com.example.personality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>校验规则写在 DTO 上而不是 Service 里，好处是 Controller 参数加个
 * {@code @Valid} 就自动生效，且错误信息能通过 {@code fieldErrors}
 * 精确地回到前端对应输入框旁边。
 */
public record RegisterRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度需在 3 到 50 之间")
        // 限定字符集不只是为了好看：用户名可能出现在 URL、日志、未来可能的
        // 用户名@域名 形式的登录里。放开任意字符会引入各种转义问题。
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
                message = "用户名只能包含字母、数字、下划线和短横线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, message = "密码至少 8 位")
        // 上限 72 不是随便定的：**BCrypt 只取前 72 个字节**，
        // 超出部分会被静默忽略。如果不设这个上限，用户设了一个 100 位的密码，
        // 实际只有前 72 位生效——他不会知道，而系统也不会告诉他。
        // 与其留一个隐式行为，不如明确拒绝。
        @Size(max = 72, message = "密码不能超过 72 位")
        String password
) {
}
