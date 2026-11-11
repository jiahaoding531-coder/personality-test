package com.example.personality.dto;

/** 推荐行锁保护的服务端版本；null reaction 代表已撤销，不代表请求失败。 */
public record FeedbackState(String reaction, long version) {}
