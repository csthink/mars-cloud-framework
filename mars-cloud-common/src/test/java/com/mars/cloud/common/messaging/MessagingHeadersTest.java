package com.mars.cloud.common.messaging;

import com.mars.cloud.common.context.InternalCallHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingHeadersTest {

    @Test
    void traceHeadersFollowW3cTraceContextNames() {
        assertThat(MessagingHeaders.TRACEPARENT).isEqualTo("traceparent");
        assertThat(MessagingHeaders.TRACESTATE).isEqualTo("tracestate");
    }

    @Test
    void eventIdHeaderUsesTheInternalHeaderPrefixWithoutCollidingWithIdentityHeaders() {
        assertThat(MessagingHeaders.EVENT_ID).isEqualTo("X-Mars-Event-Id");
        assertThat(MessagingHeaders.EVENT_ID)
                .isNotIn(InternalCallHeaders.SUBJECT, InternalCallHeaders.CLIENT_ID, InternalCallHeaders.TENANT_ID);
    }
}
