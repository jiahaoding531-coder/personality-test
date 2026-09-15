package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/**
 * 调用方提供的 AI 凭据有问题，映射为 HTTP 400。
 *
 * <h2>为什么是 400 不是 502</h2>
 *
 * <p>这两种失败长得很像，但对用户的意义完全相反：
 *
 * <ul>
 *   <li><b>502</b>（{@link AiServiceException}）——上游挂了、超时了、限流了。
 *       用户该做的是<b>等一等再试</b>，重填什么都没用</li>
 *   <li><b>400</b>（本类）——<b>调用方给的东西有问题</b>：厂商名写错了，
 *       或者填的 key 被上游拒绝。用户该做的是<b>改输入</b>，等多久都没用</li>
 * </ul>
 *
 * <p>前端靠状态码就能决定弹"重试"还是弹"重新填写 key"。
 * 如果两种情况都返回 502，用户会一直点重试，而问题永远不会自己好。
 */
public class InvalidAiCredentialsException extends BusinessException {

    public InvalidAiCredentialsException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
