package com.mars.cloud.job.internal;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorSettingsTest {

    private static ExecutorSettings settings(String bind, String registerHost) {
        return new ExecutorSettings("sample-app", bind == null ? null : InetAddress.ofLiteral(bind), 10103, registerHost,
                List.of(URI.create("http://127.0.0.1:28083")), "not-shown-in-to-string", Duration.ofSeconds(3),
                Path.of("/var/tmp/mars-job"), Duration.ofDays(7), Duration.ofSeconds(20));
    }

    @Test
    void registeredAddressUsesTheBindAddressByDefault() {
        assertThat(settings("192.0.2.8", null).registeredAddress(10103)).isEqualTo("http://192.0.2.8:10103/");
    }

    @Test
    void ipv6AddressesAreBracketed() {
        assertThat(settings("2001:db8::8", null).registeredAddress(10103)).isEqualTo("http://[2001:db8:0:0:0:0:0:8]:10103/");
        assertThat(settings("127.0.0.1", "2001:db8::8").registeredAddress(10103)).isEqualTo("http://[2001:db8::8]:10103/");
    }

    @Test
    void registerHostOverridesTheBindAddress() {
        assertThat(settings("127.0.0.1", "docker-host.example").registeredAddress(10203))
                .isEqualTo("http://docker-host.example:10203/");
        assertThat(settings(null, "192.0.2.9").registeredAddress(10203)).isEqualTo("http://192.0.2.9:10203/");
    }

    @Test
    void theActualPortIsRegisteredWhenTheConfiguredPortIsRandom() {
        assertThat(settings("127.0.0.1", null).registeredAddress(54321)).isEqualTo("http://127.0.0.1:54321/");
    }

    @Test
    void theAccessTokenNeverAppearsInTheDescription() {
        assertThat(settings("127.0.0.1", null).toString()).doesNotContain("not-shown-in-to-string");
    }
}
