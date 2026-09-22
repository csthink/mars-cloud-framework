package com.mars.cloud.rocketmq;

import com.mars.cloud.rocketmq.autoconfigure.MarsRocketMqDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class MarsRocketMqDefaultsEnvironmentPostProcessorTest {

    private final MarsRocketMqDefaultsEnvironmentPostProcessor processor = new MarsRocketMqDefaultsEnvironmentPostProcessor();

    @Test
    void defaultsSwitchOffInProcessRetryAndMessageTrace() {
        MockEnvironment environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("spring.cloud.stream.default.consumer.max-attempts")).isEqualTo("1");
        assertThat(environment.getProperty("spring.cloud.stream.rocketmq.default.consumer.push.max-reconsume-times")).isEqualTo("16");
        assertThat(environment.getProperty("spring.cloud.stream.rocketmq.binder.enable-msg-trace")).isEqualTo("false");
        assertThat(environment.getProperty("spring.cloud.stream.rocketmq.default.producer.enable-msg-trace")).isEqualTo("false");
        assertThat(environment.getProperty("spring.cloud.stream.rocketmq.default.consumer.enable-msg-trace")).isEqualTo("false");
        assertThat(environment.getProperty("spring.cloud.stream.rocketmq.binder.name-server")).isNull();
        assertThat(environment.getProperty("mars.rocketmq.prefix")).isNull();
    }

    @Test
    void explicitValuesBeatTheDefaults() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.cloud.stream.default.consumer.max-attempts", "3");
        processor.postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("spring.cloud.stream.default.consumer.max-attempts")).isEqualTo("3");
    }

    @Test
    void environmentVariablesMapToPropertiesWithTopPrecedence() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ROCKETMQ_NAME_SERVER", " 127.0.0.1:29876 ")
                .withProperty("MARS_MQ_PREFIX", "s1-")
                .withProperty("MARS_ROCKETMQ_TOPOLOGY", "provision")
                .withProperty("spring.cloud.stream.rocketmq.binder.name-server", "10.0.0.1:9876");
        processor.postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("spring.cloud.stream.rocketmq.binder.name-server")).isEqualTo("127.0.0.1:29876");
        assertThat(environment.getProperty("mars.rocketmq.prefix")).isEqualTo("s1-");
        assertThat(environment.getProperty("mars.rocketmq.topology")).isEqualTo("provision");
    }

    @Test
    void runningTwiceDoesNotDuplicateSources() {
        MockEnvironment environment = new MockEnvironment().withProperty("MARS_MQ_PREFIX", "s1-");
        processor.postProcessEnvironment(environment, null);
        processor.postProcessEnvironment(environment, null);
        assertThat(environment.getPropertySources().stream().map(org.springframework.core.env.PropertySource::getName))
                .containsOnlyOnce("marsRocketMqDefaults", "marsRocketMqEnvironment");
    }
}
