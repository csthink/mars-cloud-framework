package com.mars.cloud.rocketmq;

import java.time.Duration;

/**
 * RocketMQ 的 18 档固定延迟。
 *
 * <p>binder 只暴露固定档延迟，任意时刻定时消息拿不到；事务消息忽略延迟档，延迟消息只能由普通生产者发送。
 *
 * @since 2026-09-22
 */
public enum DelayLevel {

    LEVEL_1(1, Duration.ofSeconds(1)),
    LEVEL_2(2, Duration.ofSeconds(5)),
    LEVEL_3(3, Duration.ofSeconds(10)),
    LEVEL_4(4, Duration.ofSeconds(30)),
    LEVEL_5(5, Duration.ofMinutes(1)),
    LEVEL_6(6, Duration.ofMinutes(2)),
    LEVEL_7(7, Duration.ofMinutes(3)),
    LEVEL_8(8, Duration.ofMinutes(4)),
    LEVEL_9(9, Duration.ofMinutes(5)),
    LEVEL_10(10, Duration.ofMinutes(6)),
    LEVEL_11(11, Duration.ofMinutes(7)),
    LEVEL_12(12, Duration.ofMinutes(8)),
    LEVEL_13(13, Duration.ofMinutes(9)),
    LEVEL_14(14, Duration.ofMinutes(10)),
    LEVEL_15(15, Duration.ofMinutes(20)),
    LEVEL_16(16, Duration.ofMinutes(30)),
    LEVEL_17(17, Duration.ofHours(1)),
    LEVEL_18(18, Duration.ofHours(2));

    private final int level;
    private final Duration duration;

    DelayLevel(int level, Duration duration) {
        this.level = level;
        this.duration = duration;
    }

    /** 写入 {@link RocketMqHeaders#DELAY} 头的档位。 */
    public int level() {
        return level;
    }

    /** 该档位对应的延迟时长。 */
    public Duration duration() {
        return duration;
    }
}
