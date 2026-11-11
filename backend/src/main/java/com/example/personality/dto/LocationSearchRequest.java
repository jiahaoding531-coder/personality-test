package com.example.personality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 关键词只传给高德固定接口，不接受客户端指定上游地址或密钥。 */
public record LocationSearchRequest(@NotBlank @Size(max=80) String keyword) { }
