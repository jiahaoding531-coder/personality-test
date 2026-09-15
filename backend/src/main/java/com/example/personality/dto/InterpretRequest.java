package com.example.personality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 「说说你现在什么情况」的请求体。
 *
 * @param text 用户的原话，比如"我有点累了，想找个安静的地方坐坐，还有一个小时"
 */
public record InterpretRequest(

        @NotBlank(message = "请说点什么")
        /**
         * ⚠️ 限长 200 字，是必要的而不是洁癖。
         *
         * <p>这段文本会**原样进提示词**。不限长的话，一个人可以贴几万字进来——
         * token 账单（现在虽然是他自己的 key，但服务端要为他转发）、
         * 响应延迟、以及模型被大段无关文本带跑偏，都跟着来。
         *
         * <p>200 字够说清楚"现在什么情况"了，而且这个功能本来就只解析一句话，
         * 不是让你写游记的。
         */
        @Size(max = 200, message = "最多 200 字——把此刻的情况说清楚就够了")
        String text
) {
}
