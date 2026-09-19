package com.mars.cloud.common.error;

/**
 * 错误码契约：全框架唯一的最小抽象。
 *
 * <p>本接口刻意不含任何 i18n / Spring 依赖，只表达「一个纯数字错误码」。
 * 文案解析由各栈自己的 advice 层负责，解析结果再交给统一响应信封。
 *
 * <p>约定：实现类必须落在所属模块声明的错误码区间内，由
 * {@code mars-cloud-mvc-spring-boot-starter} 的区间校验器在启动时校验。
 */
public interface ErrorCode {

    /**
     * 纯数字码，全局唯一
     */
    int getCode();

    /**
     * 统一的 i18n key 规范：error.code.&lt;数字&gt;
     *
     * <p>与 i18n 资源对齐：i18n/error-code_*.properties 中的 key
     */
    default String getMsgKey() {
        return "error.code." + getCode();
    }
}
