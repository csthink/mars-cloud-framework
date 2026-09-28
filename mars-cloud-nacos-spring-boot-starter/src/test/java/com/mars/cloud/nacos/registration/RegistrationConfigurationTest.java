package com.mars.cloud.nacos.registration;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class RegistrationConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"true","yes","on","y","t"})
    void rejectsRelaxedEmptyProtectionProperty(String value) {
        try(var context=new GenericApplicationContext()) {
            var env=environment().withProperty("spring.cloud.nacos.discovery.naming-push-empty-protection",value);context.setEnvironment(env);
            context.registerBean(org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties.class);context.refresh();
            var configuration=new RegistrationConfiguration(context,List.of(),"127.0.0.1");
            assertThatThrownBy(()->configuration.read(8080)).hasMessageContaining("empty instance protection");
        }
    }
    @Test void preservesSecureMetadataAndImmutableIdentity() {
        try(var context=new GenericApplicationContext()) {
            var env=environment().withProperty("spring.cloud.nacos.discovery.secure","true");context.setEnvironment(env);
            context.registerBean(org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties.class);context.refresh();
            var configuration=new RegistrationConfiguration(context,List.of(),"127.0.0.1");
            var previous=configuration.read(8080);
            assertThat(previous.snapshot.metadata()).containsEntry("secure","true");
            env.setProperty("spring.cloud.nacos.discovery.service","changed");
            assertThat(configuration.read(8080).snapshot.service()).isEqualTo("changed");
            assertThat(previous.snapshot.service()).isEqualTo("example");
        }
    }
    @Test void bareHostUsesTheSdkDefaultPortAndExplicitUrisKeepTheirPortSemantics() {
        assertThat(RegistrationSettings.addresses("nacos.example,other.example:8849")).extracting(java.net.URI::toString)
                .containsExactly("http://nacos.example:8848/nacos/v3/","http://other.example:8849/nacos/v3/");
        assertThat(RegistrationSettings.addresses("https://nacos.example/custom")).extracting(java.net.URI::toString)
                .containsExactly("https://nacos.example/custom/v3/");
    }
    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("spring.application.name","example")
                .withProperty("spring.cloud.nacos.discovery.namespace","isolated")
                .withProperty("spring.cloud.nacos.discovery.server-addr","localhost:8848")
                .withProperty("spring.cloud.nacos.discovery.username","test-user")
                .withProperty("spring.cloud.nacos.discovery.password","test-password");
    }
}
