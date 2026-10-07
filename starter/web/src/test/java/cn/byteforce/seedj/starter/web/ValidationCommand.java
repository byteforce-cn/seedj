package cn.byteforce.seedj.starter.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 测试用校验命令。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class ValidationCommand {

    /**
     * 名称（必填）。
     */
    @NotBlank(message = "名称不能为空")
    public String name;
}
