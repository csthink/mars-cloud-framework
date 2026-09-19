package com.mars.cloud.mvc.env;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 错误码区间配置。
 *
 * <p>两种声明方式，合并后一起参与校验：
 *
 * <ol>
 *   <li><b>框架层</b>：{@code mars.error-code.framework-layers} 列出框架各层的归属名，
 *       区间取自权威分配表（{@code FrameworkErrorCodeRange}），应用侧不需要也不应该写死这些数字</li>
 *   <li><b>业务服务</b>：{@code mars.error-code.ranges} 声明自己的区间，
 *       用 {@code owner} 指定归属名（业务服务通常用 {@code business}）</li>
 * </ol>
 *
 * <pre>
 * mars:
 *   error-code:
 *     validate: true
 *     framework-layers: [ common, mvc ]
 *     ranges:
 *       - owner: business
 *         start: 66000
 *         end: 66999
 * </pre>
 *
 * @since 2025-10-29 14:49
 */
@ConfigurationProperties(prefix = "mars.error-code")
@Getter
@Setter
public class ErrorCodeProperties {

    /**
     * 是否启用启动期校验。默认开启——关掉它等于放弃错误码区间的结构约束。
     */
    private boolean validate = true;

    /**
     * 启用的框架层归属名（见框架分配表）。默认只需 {@code common} 与 {@code mvc}，
     * 因为它们是引入本 starter 时必然存在的两层；后续引入 security / gateway 模块时再加。
     */
    private List<String> frameworkLayers = new ArrayList<>(List.of("common", "mvc"));

    /**
     * 业务服务自己声明的区间。
     *
     * <p>{@code owner} 必填且必须是框架分配表里的归属名。**同一个 owner 可以声明多段**
     * （例如若干个业务服务各自占 {@code business} 区段里的一千个码），
     * 但各段之间、以及与其它归属之间都不得重叠。
     */
    private List<RangeDeclaration> ranges = new ArrayList<>();

    /**
     * 一条区间声明。
     */
    @Getter
    @Setter
    public static class RangeDeclaration {

        /**
         * 归属名，例如 {@code business}。
         */
        private String owner;

        /**
         * 起点（含）。
         */
        private int start;

        /**
         * 终点（含）。
         */
        private int end;
    }
}
