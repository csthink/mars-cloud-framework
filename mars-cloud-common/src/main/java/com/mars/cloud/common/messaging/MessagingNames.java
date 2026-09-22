package com.mars.cloud.common.messaging;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 主题、消费组、tag 与运行环境前缀的命名规则。
 *
 * <p>主题名形如 {@code <domain>-event}，消费组名形如 {@code <应用名>-<主题>}，两者都只允许小写字母、数字与
 * 单个连字符分隔的片段；tag 是大写的事件名。运行环境前缀形如 {@code s1-}，由运行环境给出，
 * 主题名与消费组名必须一起加前缀：RocketMQ 要求同一消费组的订阅完全一致，两个运行环境用同名消费组订阅
 * 不同前缀的主题会互相打坏订阅关系。
 *
 * @since 2026-09-21
 */
public final class MessagingNames {

    /** 主题名与消费组名的形态。 */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** tag（事件名）的形态。 */
    public static final Pattern TAG = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** 运行环境前缀的形态：小写片段加一个结尾连字符。 */
    public static final Pattern PREFIX = Pattern.compile("^[a-z0-9]+-$");

    /** 主题名的固定后缀。 */
    public static final String TOPIC_SUFFIX = "-event";

    private MessagingNames() {
    }

    /**
     * 返回领域对应的主题名。
     *
     * @param domain 领域名，如 {@code order}
     * @return {@code <domain>-event}
     */
    public static String topic(String domain) {
        return requireName(domain, "domain") + TOPIC_SUFFIX;
    }

    /**
     * 返回应用消费某主题时使用的消费组名。
     *
     * @param application 应用名，如 {@code mars-cloud-order-service}
     * @param topic 主题名，必须已经是 {@link #requireTopic(String)} 认可的形态
     * @return {@code <应用名>-<主题>}
     */
    public static String consumerGroup(String application, String topic) {
        return requireName(application, "application") + "-" + requireTopic(topic);
    }

    /** 名字是否符合主题名与消费组名的形态。 */
    public static boolean isName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** 名字是否是主题名：符合形态并以 {@value #TOPIC_SUFFIX} 结尾。 */
    public static boolean isTopic(String name) {
        return isName(name) && name.endsWith(TOPIC_SUFFIX) && name.length() > TOPIC_SUFFIX.length();
    }

    /** 名字是否符合 tag 的形态。 */
    public static boolean isTag(String tag) {
        return tag != null && TAG.matcher(tag).matches();
    }

    /** 校验并返回一个符合形态的名字。 */
    public static String requireName(String name, String field) {
        Objects.requireNonNull(field, "field 不能为空");
        if (!isName(name)) {
            throw new IllegalArgumentException(field + " 必须是小写字母、数字与单个连字符分隔的片段，收到: " + name);
        }
        return name;
    }

    /** 校验并返回一个主题名。 */
    public static String requireTopic(String topic) {
        if (!isTopic(topic)) {
            throw new IllegalArgumentException("topic 必须形如 <domain>" + TOPIC_SUFFIX + "，收到: " + topic);
        }
        return topic;
    }

    /** 校验并返回一个 tag。 */
    public static String requireTag(String tag) {
        if (!isTag(tag)) {
            throw new IllegalArgumentException("tag 必须是大写字母开头的大写事件名，收到: " + tag);
        }
        return tag;
    }

    /** 校验并返回一个运行环境前缀；空字符串表示没有前缀。 */
    public static String requirePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix 不能为 null，没有前缀时传空字符串");
        if (!prefix.isEmpty() && !PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("prefix 必须形如 s1-（小写片段加结尾连字符）或为空，收到: " + prefix);
        }
        return prefix;
    }

    /**
     * 给主题名或消费组名加运行环境前缀。
     *
     * <p>双重前缀的判断按字面：不带前缀的名字若以同一片段开头（前缀 {@code s1-} 配主题 {@code s1-event}）
     * 也会被拒绝，所以不带前缀的名字首段不得与前缀片段相同。
     *
     * @param prefix 运行环境前缀，空字符串表示不加
     * @param name 不带前缀的名字
     * @return 带前缀的名字；已经带该前缀的名字会被拒绝，避免双重前缀
     */
    public static String withPrefix(String prefix, String name) {
        requirePrefix(prefix);
        requireName(name, "name");
        if (prefix.isEmpty()) {
            return name;
        }
        if (name.startsWith(prefix)) {
            throw new IllegalArgumentException("name 已经带有前缀 " + prefix + "，不能重复加: " + name);
        }
        return prefix + name;
    }

    /**
     * 去掉运行环境前缀。
     *
     * @param prefix 运行环境前缀，空字符串表示没有前缀
     * @param name 带前缀的名字
     * @return 去掉前缀后的名字；不带该前缀的名字会被拒绝
     */
    public static String stripPrefix(String prefix, String name) {
        requirePrefix(prefix);
        requireName(name, "name");
        if (prefix.isEmpty()) {
            return name;
        }
        if (!name.startsWith(prefix)) {
            throw new IllegalArgumentException("name 不带前缀 " + prefix + ": " + name);
        }
        return name.substring(prefix.length());
    }
}
