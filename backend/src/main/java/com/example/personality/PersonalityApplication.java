package com.example.personality;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 *
 * <p>{@code @SpringBootApplication} 是三个注解的合体，展开来是：
 * <ul>
 *   <li>{@code @Configuration} —— 这个类可以定义 Bean</li>
 *   <li>{@code @EnableAutoConfiguration} —— 让 Spring Boot 根据 classpath 上的
 *       依赖自动配置（看到 postgresql 驱动就配数据源，看到 spring-webmvc 就配 Web 容器）</li>
 *   <li>{@code @ComponentScan} —— 扫描<b>本包及子包</b>下所有带 {@code @Component}
 *       /{@code @Service}/{@code @Repository}/{@code @RestController} 的类，注册成 Bean</li>
 * </ul>
 *
 * <p>⚠️ 最后一条是个隐藏约束：<b>所有业务类都必须放在 com.example.personality
 * 这个包或它的子包下面</b>，否则扫描不到，启动时会报 "No qualifying bean of type ..."。
 * 这是新手最常遇到的启动失败原因之一。
 */
@SpringBootApplication
public class PersonalityApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonalityApplication.class, args);
    }
}
