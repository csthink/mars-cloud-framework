package com.mars.cloud.mvc.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 框架级错误码区间的**唯一权威分配表**。
 *
 * <p>归属由错误码的**前缀**唯一决定，不靠各模块在配置里重复声明——一处定义、一处校验，
 * 结构上排除「同一段被两个模块claim」的可能。
 *
 * <p>分配定稿见设计文档 5.2。新增框架模块时在这里加一行，并同步
 * {@code mars.error-code.framework-layers} 的默认值。
 *
 * @since 2026-09-19
 */
public final class FrameworkErrorCodeRange {

    /** 区段前缀位数：60000 → "60"。 */
    private static final int PREFIX_LENGTH = 2;

    /**
     * 前缀 → 区间。归属由前缀唯一决定，所以查询是「按前缀命中」，不需要额外索引。
     * 用 {@link LinkedHashMap} 保持声明顺序，报错信息更可读。
     */
    private static final Map<String, Range> BY_PREFIX = new LinkedHashMap<>();

    /**
     * 归属名 → 区间，供配置按名字声明时查权威区段。
     */
    private static final Map<String, Range> BY_OWNER = new LinkedHashMap<>();

    static {
        // 归属名            区段起点   区段终点
        allocate("common", 60000, 60999);
        allocate("mvc", 61000, 61999);
        allocate("security", 62000, 62999);
        allocate("gateway", 63000, 63999);
        allocate("auth-service", 64000, 64999);
        allocate("upms-service", 65000, 65999);
        allocate("business", 66000, 99999);
    }

    private FrameworkErrorCodeRange() {
    }

    private static void allocate(String owner, int start, int end) {
        Range range = new Range(owner, start, end);
        BY_PREFIX.put(prefixOf(start), range);
        BY_OWNER.put(owner, range);
    }

    /**
     * 按错误码返回其归属的层或服务名；无法识别（如非法码值）时返回 {@code null}。
     */
    public static String ownerOf(int code) {
        Range range = rangeOfCode(code);
        return range == null ? null : range.owner();
    }

    /**
     * 按错误码返回其归属区间；无法识别时返回 {@code null}。
     */
    public static Range rangeOfCode(int code) {
        if (code < 0) {
            return null;
        }
        return BY_PREFIX.get(prefixOf(code));
    }

    /**
     * 按归属名返回区间；未知归属名返回 {@code null}。
     */
    public static Range rangeOf(String owner) {
        return owner == null ? null : BY_OWNER.get(owner);
    }

    /**
     * 全部归属名，按区段升序。
     */
    public static java.util.Set<String> owners() {
        return BY_OWNER.keySet();
    }

    static String prefixOf(int code) {
        String s = Integer.toString(code);
        return s.length() <= PREFIX_LENGTH ? s : s.substring(0, PREFIX_LENGTH);
    }

    /**
     * 一个错误码区段。
     *
     * @param owner 归属名
     * @param start 起点（含）
     * @param end   终点（含）
     */
    public record Range(String owner, int start, int end) {

        public boolean contains(int code) {
            return code >= start && code <= end;
        }

        /**
         * 与另一区段是否重叠（闭区间）。
         *
         * <p>注意不能写成调用 {@code contains}：包含关系下两个方向都为真，会无限递归。
         */
        public boolean overlaps(Range other) {
            return this.start <= other.end && other.start <= this.end;
        }

        @Override
        public String toString() {
            return owner + "[" + start + ", " + end + "]";
        }
    }
}
