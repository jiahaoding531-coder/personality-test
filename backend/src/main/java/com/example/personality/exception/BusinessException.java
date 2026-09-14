package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/**
 * 所有业务异常的基类。
 *
 * <p><b>⚠️ 这里有一个从 JDBC 转过来的人必踩的坑，务必记住：</b>
 *
 * <p>你写 {@code TransferDemo.java} 的时候是这么干的：
 * <pre>
 *   conn.setAutoCommit(false);
 *   try {
 *       // ... 两条 UPDATE ...
 *       conn.commit();
 *   } catch (SQLException e) {   // SQLException 是"受检异常"
 *       conn.rollback();          // 所以你能 catch 到并回滚
 *   }
 * </pre>
 *
 * <p>但 Spring 的 {@code @Transactional} <b>默认只在遇到 RuntimeException（非受检异常）
 * 时才回滚</b>。如果抛出的是受检异常（比如 {@code IOException}），事务会照常提交——
 * 于是你会看到"明明抛异常了，数据却存进去了"这种极难排查的现象。
 *
 * <p>所以本项目的所有自定义异常<b>一律继承 RuntimeException</b>。
 * 这样只要方法抛出它们，事务就一定会回滚。</p>
 *
 * <p>另外：为什么把 HttpStatus 放在异常里？因为这样 GlobalExceptionHandler
 * 就不用写一堆 if-else 去判断"这个异常该返回 404 还是 409"——
 * 异常自己知道答案。这叫"把信息附着在异常上"。</p>
 */
public abstract class BusinessException extends RuntimeException {

    private final HttpStatus status;

    protected BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
