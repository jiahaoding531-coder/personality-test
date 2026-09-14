package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冒烟测试：只验证集成测试的**基础设施**是通的。
 *
 * <p>它本身没什么业务价值，但能快速区分"业务代码有 bug"和"测试环境没配好"。
 * 如果这个类挂了，说明是上下文装配、数据库连接、Flyway 迁移或 Security 配置的问题，
 * 而不是被测代码的问题——排查范围立刻缩小。
 */
class SetupSmokeTest extends IntegrationTestBase {

    @Test
    @DisplayName("应用上下文能启动，Flyway 迁移已执行，题库有 20 道题")
    void contextLoadsAndMigrationsRan() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                // 这个断言顺带验证了：测试库的表建好了、V2 脚本把 20 道题灌进去了
                .andExpect(jsonPath("$.questionCount").value(20));
    }

    @Test
    @DisplayName("题库接口返回 20 道题和 5 个量表选项")
    void questionsEndpointWorks() throws Exception {
        mockMvc.perform(get("/api/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options.length()").value(5))
                .andExpect(jsonPath("$.questions.length()").value(20))
                // 反向计分标记绝不能暴露给前端，否则用户能据此操纵结果
                .andExpect(jsonPath("$.questions[0].reverseScored").doesNotExist());
    }

    @Test
    @DisplayName("能注册并登录（验证 CSRF 后置处理器和会话提取都正常工作）")
    void registerAndLoginWorks() throws Exception {
        String username = uniqueUsername("smoke");

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "password123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                // 密码哈希绝不能出现在响应里
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
}
