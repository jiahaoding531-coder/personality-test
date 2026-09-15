package com.example.personality.controller;

import com.example.personality.dto.QuestionsResponse;
import com.example.personality.entity.QuestionScale;
import com.example.personality.service.QuestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题库接口。
 *
 * <p>{@code @RestController} = {@code @Controller} + {@code @ResponseBody}。
 * 它的意思是：这个类里所有方法的返回值都不当作"视图名"去找模板文件，
 * 而是直接用 Jackson 序列化成 JSON 写进响应体。
 *
 * <p>在传统的 Spring MVC 里，{@code @Controller} 返回 "hello" 会被解释成
 * "去找 hello.html 模板"。{@code @RestController} 则是"返回字符串 hello 本身"。
 * 前后端分离的项目一律用后者。
 */
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    /**
     * {@code GET /api/questions} —— 取题目 + 量表选项。
     *
     * <p>注意这里<b>没有手写任何 JSON 组装代码</b>。返回一个 Java 对象，
     * Spring 自动用 Jackson 把它转成 JSON：
     * <pre>
     *   record 的字段名  →  JSON 的 key
     *   字段的值        →  JSON 的 value
     *   Instant         →  ISO-8601 字符串（如 2026-09-14T12:34:56Z）
     * </pre>
     *
     * <p>record 之所以特别适合做响应对象，就是因为它没有 setter、
     * 字段全是 final，Jackson 能确定地把它序列化出来，不会出现
     * "某个 getter 忘了写导致字段丢失"的情况。
     */
    @GetMapping
    public QuestionsResponse getQuestions() {
        // 这个端点目前固定返回人格量表。等旅行测试的前端就绪，
        // 再加一个 ?scale=TRAVEL 参数或 /api/questions/travel 端点——
        // 现在开出来只会让人拿到 8 道题却无处提交。
        return questionService.getQuestions(QuestionScale.PERSONALITY);
    }
}
