package com.mars.cloud.common.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MessagingNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"order", "order-event", "mars-cloud-order-service", "a1-b2", "x"})
    void acceptsLowercaseHyphenSeparatedNames(String name) {
        assertThat(MessagingNames.isName(name)).isTrue();
        assertThat(MessagingNames.requireName(name, "name")).isEqualTo(name);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Order", "order_event", "order--event", "-order", "order-", "order event", "order.event", "%DLQ%order", "s1-"})
    void rejectsNamesOutsideTheConvention(String name) {
        assertThat(MessagingNames.isName(name)).isFalse();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessagingNames.requireName(name, "name"))
                .withMessageContaining("name");
    }

    @Test
    void topicAppendsTheEventSuffix() {
        assertThat(MessagingNames.topic("order")).isEqualTo("order-event");
        assertThat(MessagingNames.isTopic("order-event")).isTrue();
        assertThat(MessagingNames.isTopic("order")).isFalse();
        assertThat(MessagingNames.isTopic("-event")).isFalse();
        assertThat(MessagingNames.isTopic("event")).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> MessagingNames.requireTopic("order"));
        assertThatIllegalArgumentException().isThrownBy(() -> MessagingNames.topic("Order"));
    }

    @Test
    void consumerGroupJoinsApplicationAndTopic() {
        assertThat(MessagingNames.consumerGroup("mars-cloud-product-service", "order-event"))
                .isEqualTo("mars-cloud-product-service-order-event");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessagingNames.consumerGroup("mars-cloud-product-service", "order"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessagingNames.consumerGroup("Product", "order-event"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "CANCELLED", "GRANTED", "CREATED", "A", "PAID_2"})
    void acceptsUppercaseTags(String tag) {
        assertThat(MessagingNames.isTag(tag)).isTrue();
        assertThat(MessagingNames.requireTag(tag)).isEqualTo(tag);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"paid", "Paid", "1PAID", "_PAID", "PAID-2", "PAID 2", "*"})
    void rejectsTagsOutsideTheConvention(String tag) {
        assertThat(MessagingNames.isTag(tag)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> MessagingNames.requireTag(tag));
    }

    @Test
    void emptyPrefixLeavesNamesUnchanged() {
        assertThat(MessagingNames.requirePrefix("")).isEmpty();
        assertThat(MessagingNames.withPrefix("", "order-event")).isEqualTo("order-event");
        assertThat(MessagingNames.stripPrefix("", "order-event")).isEqualTo("order-event");
    }

    @Test
    void prefixIsAddedOnceAndStrippedBack() {
        assertThat(MessagingNames.withPrefix("s1-", "order-event")).isEqualTo("s1-order-event");
        assertThat(MessagingNames.withPrefix("s6-", "mars-cloud-product-service-order-event"))
                .isEqualTo("s6-mars-cloud-product-service-order-event");
        assertThat(MessagingNames.stripPrefix("s1-", "s1-order-event")).isEqualTo("order-event");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessagingNames.withPrefix("s1-", "s1-order-event"))
                .withMessageContaining("重复");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessagingNames.stripPrefix("s1-", "order-event"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessagingNames.stripPrefix("s1-", "s1-"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessagingNames.withPrefix("s1-", "s1-event"))
                .withMessageContaining("重复");
    }

    @ParameterizedTest
    @ValueSource(strings = {"s1", "S1-", "s1--", "-", "s 1-", "s1-order-"})
    void rejectsMalformedPrefixes(String prefix) {
        assertThatIllegalArgumentException().isThrownBy(() -> MessagingNames.requirePrefix(prefix));
        assertThatIllegalArgumentException().isThrownBy(() -> MessagingNames.withPrefix(prefix, "order-event"));
    }

    @Test
    void nullPrefixIsAnError() {
        assertThatNullPointerException().isThrownBy(() -> MessagingNames.requirePrefix(null));
    }
}
