package com.example.personality.dto;

/**
 * 李克特量表的一个选项。
 *
 * <p>把选项文案放在后端返回，而不是让前端硬编码中文。
 * 好处是将来要改文案（比如把"说不好"改成"中性"），只改后端一处，
 * 已发布的前端不用重新打包。
 */
public record ScaleOption(int value, String label) {
}
