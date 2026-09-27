package com.mars.cloud.job.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class MarsJobDefaultsEnvironmentPostProcessorTest {

    private static MockEnvironment process(MockEnvironment environment) {
        new MarsJobDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void environmentVariablesAreMappedOnlyWhenPresent() {
        MockEnvironment environment = process(new MockEnvironment()
                .withProperty("MARS_MQ_PREFIX", " s2- ")
                .withProperty("MARS_JOB_ADMIN_ADDRESSES", "http://127.0.0.1:28083")
                .withProperty("MARS_JOB_ACCESS_TOKEN", "test-access-token-0123456789")
                .withProperty("MARS_JOB_REGISTER_HOST", "docker-host.example"));
        assertThat(environment.getProperty("mars.job.prefix")).isEqualTo("s2-");
        assertThat(environment.getProperty("mars.job.admin.addresses")).isEqualTo("http://127.0.0.1:28083");
        assertThat(environment.getProperty("mars.job.access-token")).isEqualTo("test-access-token-0123456789");
        assertThat(environment.getProperty("mars.job.executor.register-host")).isEqualTo("docker-host.example");

        MockEnvironment none = process(new MockEnvironment());
        assertThat(none.getPropertySources().contains(MarsJobDefaultsEnvironmentPostProcessor.ENVIRONMENT_SOURCE)).isFalse();
    }

    @Test
    void mappedVariablesOverrideConfigurationFiles() {
        MockEnvironment environment = process(new MockEnvironment()
                .withProperty("mars.job.prefix", "s9-")
                .withProperty("MARS_MQ_PREFIX", "s1-"));
        assertThat(environment.getProperty("mars.job.prefix")).isEqualTo("s1-");
    }

    @Test
    void theExecutorPortIsDerivedFromTheBusinessPort() {
        assertThat(process(new MockEnvironment().withProperty("server.port", "8203"))
                .getProperty("mars.job.executor.port")).isEqualTo("10203");
        assertThat(process(new MockEnvironment().withProperty("server.port", "8203")
                .withProperty("mars.job.executor.port-offset", "3000")).getProperty("mars.job.executor.port")).isEqualTo("11203");
    }

    @Test
    void anExplicitPortIsKeptForTheVerifierToJudge() {
        MockEnvironment environment = process(new MockEnvironment()
                .withProperty("server.port", "8203")
                .withProperty("mars.job.executor.port", "12345"));
        assertThat(environment.getProperty("mars.job.executor.port")).isEqualTo("12345");
        assertThat(environment.getPropertySources().contains(MarsJobDefaultsEnvironmentPostProcessor.DEFAULTS_SOURCE)).isFalse();
    }

    @Test
    void noPortIsDerivedFromARandomOrMissingBusinessPort() {
        assertThat(process(new MockEnvironment().withProperty("server.port", "0")).getProperty("mars.job.executor.port")).isNull();
        assertThat(process(new MockEnvironment()).getProperty("mars.job.executor.port")).isNull();
        assertThat(process(new MockEnvironment().withProperty("server.port", "${PORT}")).getProperty("mars.job.executor.port")).isNull();
    }

    @Test
    void theDerivedPortHasTheLowestPrecedence() {
        MockEnvironment environment = process(new MockEnvironment().withProperty("server.port", "8203"));
        assertThat(environment.getPropertySources().stream().reduce((first, second) -> second).orElseThrow().getName())
                .isEqualTo(MarsJobDefaultsEnvironmentPostProcessor.DEFAULTS_SOURCE);
    }
}
