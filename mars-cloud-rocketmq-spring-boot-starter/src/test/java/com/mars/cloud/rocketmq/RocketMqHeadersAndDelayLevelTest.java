package com.mars.cloud.rocketmq;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqHeadersAndDelayLevelTest {

    @Test
    void producerHeadersMatchWhatTheBinderConverterReads() {
        assertThat(RocketMqHeaders.TAGS).isEqualTo("TAGS");
        assertThat(RocketMqHeaders.KEYS).isEqualTo("KEYS");
        assertThat(RocketMqHeaders.DELAY).isEqualTo("DELAY");
    }

    @Test
    void consumerHeadersMatchWhatTheBinderFills() {
        assertThat(RocketMqHeaders.RECEIVED_TAGS).isEqualTo("ROCKET_TAGS");
        assertThat(RocketMqHeaders.RECEIVED_KEYS).isEqualTo("ROCKET_KEYS");
        assertThat(RocketMqHeaders.RECEIVED_MESSAGE_ID).isEqualTo("ROCKET_MQ_MESSAGE_ID");
        assertThat(RocketMqHeaders.RECEIVED_TOPIC).isEqualTo("ROCKET_MQ_TOPIC");
        assertThat(RocketMqHeaders.TRANSACTION_CHECK_TIMES).isEqualTo("TRANSACTION_CHECK_TIMES");
    }

    @Test
    void delayLevelsAreTheEighteenFixedLevelsInAscendingOrder() {
        DelayLevel[] levels = DelayLevel.values();
        assertThat(levels).hasSize(18);
        for (int i = 0; i < levels.length; i++) {
            assertThat(levels[i].level()).isEqualTo(i + 1);
        }
        assertThat(Arrays.stream(levels).map(DelayLevel::duration)).isSorted();
        assertThat(DelayLevel.LEVEL_2.duration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(DelayLevel.LEVEL_16.duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(DelayLevel.LEVEL_18.duration()).isEqualTo(Duration.ofHours(2));
    }
}
