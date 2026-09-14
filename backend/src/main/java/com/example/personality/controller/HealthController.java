package com.example.personality.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。对应计划书"阶段 3"的验收点——
 * 这个接口能返回 200，说明 Spring 容器起来了、Web 层通了。
 *
 * <p>本接口额外做了一件事：真的去数据库查一下。这样它同时验证了
 * "数据库连得上"，比只返回一个写死的 "UP" 有用得多——
 * 否则应用可能正常启动但连不上库，健康检查却一直报健康，
 * 这种"假健康"在容器编排环境里会导致流量被打到已经坏掉的实例上。
 *
 * <p>生产环境的正规做法是引入 {@code spring-boot-starter-actuator}，
 * 它提供 {@code /actuator/health} 并自动聚合数据库、磁盘、外部依赖的健康状态。
 * V0.1 不引依赖，手写一个够用。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 注入 {@link JdbcTemplate}——这是 Spring 对 JDBC 的薄封装。
     *
     * <p>它和你写 {@code JdbcCrud.java} 时的区别只有两点：
     * 连接从连接池自动取还（不用自己 {@code DriverManager.getConnection}），
     * 异常都转成了 Spring 统一的 {@code DataAccessException} 体系。
     * SQL 还是你自己写，结果集还是你自己映射。
     */
    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now());

        try {
            Integer questionCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM questions", Integer.class);
            body.put("database", "UP");
            body.put("questionCount", questionCount);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // 数据库不通时返回 503 Service Unavailable，而不是 200。
            // 监控系统靠状态码判断，不解析响应体。
            body.put("status", "DOWN");
            body.put("database", "DOWN");
            body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }
}
