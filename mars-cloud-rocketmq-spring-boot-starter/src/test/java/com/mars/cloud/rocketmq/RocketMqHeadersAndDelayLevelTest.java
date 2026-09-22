package com.mars.cloud.rocketmq;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqHeadersAndDelayLevelTest {

    @Test
    void producerHeadersMatchTheRocketMqPropertyNamesTheBinderConverterReads() {
        assertThat(RocketMqHeaders.TAGS).isEqualTo(org.apache.rocketmq.common.message.MessageConst.PROPERTY_TAGS);
        assertThat(RocketMqHeaders.KEYS).isEqualTo(org.apache.rocketmq.common.message.MessageConst.PROPERTY_KEYS);
        assertThat(RocketMqHeaders.DELAY).isEqualTo(org.apache.rocketmq.common.message.MessageConst.PROPERTY_DELAY_TIME_LEVEL);
        assertThat(RocketMqHeaders.TRANSACTION_CHECK_TIMES).isEqualTo(org.apache.rocketmq.common.message.MessageConst.PROPERTY_TRANSACTION_CHECK_TIMES);
    }

    @Test
    void consumerHeadersMatchTheNamesTheBinderConverterProduces() {
        assertThat(RocketMqHeaders.RECEIVED_TAGS).isEqualTo(toRocketHeaderKey("TAGS"));
        assertThat(RocketMqHeaders.RECEIVED_KEYS).isEqualTo(toRocketHeaderKey("KEYS"));
        assertThat(RocketMqHeaders.RECEIVED_MESSAGE_ID).isEqualTo(toRocketHeaderKey(com.alibaba.cloud.stream.binder.rocketmq.constant.RocketMQConst.Headers.MESSAGE_ID));
        assertThat(RocketMqHeaders.RECEIVED_TOPIC).isEqualTo(toRocketHeaderKey(com.alibaba.cloud.stream.binder.rocketmq.constant.RocketMQConst.Headers.TOPIC));
    }

    private static String toRocketHeaderKey(String key) {
        return com.alibaba.cloud.stream.binder.rocketmq.support.RocketMQMessageConverterSupport.toRocketHeaderKey(key);
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
